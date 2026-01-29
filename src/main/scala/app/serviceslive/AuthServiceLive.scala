package app.serviceslive

import cats.data.{EitherT, NonEmptyVector, OptionT}
import cats.effect.Async
import cats.syntax.all.*

import java.util.Base64

import AuthServiceLive.given
import app.Config.AppConfig.AuthConfig
import app.ThalesUtils.ExtensionMethodUtils.*
import app.ThalesUtils.GenUtils as U
import app.entrypoints.smithy.{PermissionInDb, UserId, UserInDb}
import app.mem_caches.MemCache
import app.model.AppModel.AuthenticatedUser
import app.services.{AuthService, ClockService, RenewalError, RepositoryService}
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import doobie.ConnectionIO
import doobie.Transactor
import doobie.implicits.*
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim, JwtOptions}
import pdi.jwt.algorithms.JwtHmacAlgorithm

private final class AuthServiceLive[F[_]: Async as async] private (
    appName: String,
    authConfig: AuthConfig,
    clockService: ClockService[F],
    repoService: RepositoryService,
    xa: Transactor[F],
    authUserMemCache: MemCache[F, String, AuthenticatedUser],
) extends AuthService[F]:
  private val tokenExpirationPeriodInSeconds: Long = authConfig.getExpirationPeriodInSeconds
  private val tokenExpirationPeriod: java.time.Duration = java.time.Duration.ofSeconds(tokenExpirationPeriodInSeconds)

  private val JwtEncodingAlgorithm: JwtHmacAlgorithm =
    JwtAlgorithm
      .fromString(authConfig.getJwtEncodingAlgorithm)
      .safeAs[JwtHmacAlgorithm]
      .getOrElse(throw AssertionError("We only support Hmac algorithms for token encryption."))

  private val JwtDecodingAlgorithmList: Seq[JwtHmacAlgorithm] = Seq(JwtEncodingAlgorithm)

  private def generateTokenAndUser(
      userId: UserId,
      permissions: Vector[PermissionInDb],
      origIatOpt: Option[Long],
      nowEpochSec: Long,
  ): F[(String, AuthenticatedUser)] =
    async.delay:
      val expiryEpochSec = nowEpochSec + tokenExpirationPeriodInSeconds

      val permBitSet = AuthServiceLive.permissionsToBitSet(permissions)
      val permsString = AuthServiceLive.bitSetToString(permBitSet)
      val origIat = origIatOpt.getOrElse(nowEpochSec)

      val payload = AuthServiceLive.TokenPayload(
        iss = appName,
        sub = userId.toString,
        iat = nowEpochSec,
        exp = expiryEpochSec,
        userId = userId.value,
        issuedAt = nowEpochSec,
        expiresAt = expiryEpochSec,
        permissions = permsString,
        origIat = origIat,
      )
      val token = Jwt.encode(writeToString(payload), authConfig.getSecretKey, JwtEncodingAlgorithm)
      val authUser = AuthenticatedUser(userId, permBitSet, nowEpochSec, origIat, expiryEpochSec)
      (token, authUser)
  end generateTokenAndUser

  override def createToken(user: UserInDb, permissions: Vector[PermissionInDb], origIatOpt: Option[Long]): F[String] =
    val userId = user.userId

    for
      nowEpochSec <- clockService.nowEpochSeconds
      (token, authUser) <- generateTokenAndUser(userId, permissions, origIatOpt, nowEpochSec)
      _ <- authUserMemCache.put(token, authUser, tokenExpirationPeriod)
    yield token
  end createToken

  private def decodeWithOpts(token: String, options: JwtOptions): Either[Throwable, JwtClaim] =
    Jwt.decode(token, authConfig.getSecretKey, JwtDecodingAlgorithmList, options).toEither
  end decodeWithOpts

  private def decodeJwtToken(token: String): EitherT[F, Throwable, JwtClaim] =
    EitherT(async.delay(decodeWithOpts(token, JwtOptions.DEFAULT)))
  end decodeJwtToken

  private def jwtClaimToAuthenticatedUser(jwtClaim: JwtClaim): EitherT[F, Throwable, AuthenticatedUser] =
    EitherT(async.delay(Either.catchNonFatal(readFromString[AuthenticatedUser](jwtClaim.content))))
  end jwtClaimToAuthenticatedUser

  override def validateToken(token: String): F[Either[Throwable, AuthenticatedUser]] =
    authUserMemCache.get(token) >>= { _.fold(authenticateAndCacheUser(token))(acceptCachedUser) }
  end validateToken

  private def acceptCachedUser(authUser: AuthenticatedUser): F[Either[Throwable, AuthenticatedUser]] =
    async.pure(Right(authUser))
  end acceptCachedUser

  private def authenticateAndCacheUser(token: String): F[Either[Throwable, AuthenticatedUser]] =
    (for
      jwtClaim <- decodeJwtToken(token)
      authUser <- jwtClaimToAuthenticatedUser(jwtClaim)
      _ <- EitherT.liftF(
        clockService.nowEpochSeconds >>= { now =>
          val ttlDuration = java.time.Duration.ofSeconds(authUser.expiresAt - now)
          authUserMemCache.put(token, authUser, ttlDuration)
        },
      )
    yield authUser).value
  end authenticateAndCacheUser

  private def getUserWithPermissions(userId: UserId): F[Option[(UserInDb, Vector[PermissionInDb])]] =
    val userIdVec = NonEmptyVector.one(userId)
    val dbProgram: OptionT[ConnectionIO, (UserInDb, Vector[PermissionInDb])] =
      for
        user <- OptionT(repoService.fetchUsersByUserIds(userIdVec).map(_.get(userId)))
        permissions <- repoService.fetchUserPermissions(userId).liftO
      yield (user, permissions)

    // Note here that an expression like:
    //    dbProgram.value.transact(xa)
    // also works and produces the same result. BUT, this is only
    // because this is a readonly operation.  If it wasn't, then
    // the expression above commits the transaction,
    // but the one below *rolls-back* the transaction.
    // We keep the semantically correct one here.
    dbProgram.transact(xa).value
  end getUserWithPermissions

  private val noSuchUserError: F[Either[RenewalError, String]] = U.leftF(RenewalError.NoSuchUser)
  private val userIsDisabledError: F[Either[RenewalError, String]] = U.leftF(RenewalError.UserIsDisabled)
  private val userMustResetPasswordError: F[Either[RenewalError, String]] = U.leftF(RenewalError.UserMustResetPassword)
  private val renewalTimeHasExpiredError: F[Either[RenewalError, String]] = U.leftF(RenewalError.RenewalTimeHasExpired)

  override def renewToken(authenticatedUser: AuthenticatedUser): F[Either[RenewalError, String]] =
    for
      nowEpochSeconds <- clockService.nowEpochSeconds
      response <-
        val origIat = authenticatedUser.origIat
        val sessionLifetimeInSeconds = nowEpochSeconds - origIat
        if sessionLifetimeInSeconds <= authConfig.getAllowedRenewalPeriodInSeconds
        then
          getUserWithPermissions(authenticatedUser.userId) >>= {
            _.fold(noSuchUserError) { (userInDb, permissions) =>
              if !userInDb.enabled then userIsDisabledError
              else if userInDb.mustResetPassword then userMustResetPasswordError
              else createToken(userInDb, permissions, Some(origIat)).map(Right.apply)
            }
          }
        else renewalTimeHasExpiredError
    yield response
  end renewToken
end AuthServiceLive

object AuthServiceLive:
  def create[F[_]: Async](
      appName: String,
      authConfig: AuthConfig,
      clockService: ClockService[F],
      boRepoService: RepositoryService,
      xa: Transactor[F],
      authUserMemCache: MemCache[F, String, AuthenticatedUser],
  ): AuthService[F] =
    AuthServiceLive[F](appName, authConfig, clockService, boRepoService, xa, authUserMemCache)
  end create

  private def permissionsToBitSet(perms: Seq[PermissionInDb]): java.util.BitSet =
    val bs = new java.util.BitSet()
    perms.foreach(p => bs.set(p.permissionId.value.toInt))
    bs
  end permissionsToBitSet

  private def bitSetToString(bs: java.util.BitSet): String =
    Base64.getUrlEncoder.withoutPadding.encodeToString(bs.toByteArray)
  end bitSetToString

  private def stringToBitSet(s: String): java.util.BitSet =
    java.util.BitSet.valueOf(Base64.getUrlDecoder.decode(s))
  end stringToBitSet

  private case class TokenPayload(
      iss: String,
      sub: String,
      iat: Long,
      exp: Long,
      userId: Long,
      issuedAt: Long,
      expiresAt: Long,
      permissions: String,
      origIat: Long,
  )

  private given tokenPayloadCodec: JsonValueCodec[TokenPayload] = JsonCodecMaker.make

  private given bitSetCodec: JsonValueCodec[java.util.BitSet] = new JsonValueCodec[java.util.BitSet]:
    override def decodeValue(in: JsonReader, default: java.util.BitSet): java.util.BitSet =
      import scala.language.unsafeNulls
      stringToBitSet(in.readString(null))
    end decodeValue

    override def encodeValue(x: java.util.BitSet, out: JsonWriter): Unit =
      out.writeVal(bitSetToString(x))
    end encodeValue

    override def nullValue: java.util.BitSet =
      java.util.BitSet()
    end nullValue
  end bitSetCodec

  given authUserCodec: JsonValueCodec[AuthenticatedUser] =
    JsonCodecMaker.make[AuthenticatedUser](CodecMakerConfig.withSkipUnexpectedFields(true))
  end authUserCodec
end AuthServiceLive
