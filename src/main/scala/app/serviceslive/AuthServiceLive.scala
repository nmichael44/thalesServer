package app.serviceslive

import cats.data.{EitherT, NonEmptyVector, OptionT}
import cats.effect.Async
import cats.syntax.all.*

import java.util.Base64
import scala.util.control.NoStackTrace

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
import org.typelevel.log4cats.Logger
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim, JwtOptions}
import pdi.jwt.algorithms.JwtHmacAlgorithm

private final class AuthServiceLive[F[_]: { Async as async, Logger }] private (
    appName: String,
    authConfig: AuthConfig,
    clockService: ClockService[F],
    repoService: RepositoryService,
    xa: Transactor[F],
    authUserMemCache: MemCache[F, String, AuthenticatedUser],
) extends AuthService[F]:
  private val tokenExpirationPeriodInSeconds: Long = authConfig.getExpirationPeriodInSeconds
  private val tokenExpirationPeriod: java.time.Duration = java.time.Duration.ofSeconds(tokenExpirationPeriodInSeconds)

  private val tokenCacheEnabled: Boolean = false

  private val jwtEncodingAlgorithm: JwtHmacAlgorithm =
    JwtAlgorithm
      .fromString(authConfig.getJwtEncodingAlgorithm)
      .safeAs[JwtHmacAlgorithm]
      .getOrElse(throw new AssertionError("We only support Hmac algorithms for token encryption.") with NoStackTrace)

  private val jwtDecodingAlgorithmList: Seq[JwtHmacAlgorithm] = Seq(jwtEncodingAlgorithm)

  private def generateTokenAndUser(
      userId: UserId,
      permissions: Vector[PermissionInDb],
      origIatOpt: Option[Long],
      nowEpochSec: Long,
  ): F[(String, AuthenticatedUser)] =
    async.delay:
      val expiryEpochSec = nowEpochSec + tokenExpirationPeriodInSeconds

      val permBitSet = AuthServiceLive.permissionsToBitSet(permissions)
      val origIat = origIatOpt.getOrElse(nowEpochSec)

      val payload = AuthServiceLive.TokenPayload(
        permissions = permBitSet,
        origIat = origIat,
      )
      val claim = JwtClaim(
        content = writeToString(payload),
        issuer = Some(appName),
        subject = Some(userId.toString),
        issuedAt = Some(nowEpochSec),
        expiration = Some(expiryEpochSec),
      )
      val token = Jwt.encode(claim, authConfig.getSecretKey, jwtEncodingAlgorithm)
      val authUser = AuthenticatedUser(userId, permBitSet, nowEpochSec, origIat, expiryEpochSec)
      (token, authUser)
  end generateTokenAndUser

  override def createToken(user: UserInDb, permissions: Vector[PermissionInDb], origIatOpt: Option[Long]): F[String] =
    val userId = user.userId

    for
      nowEpochSec <- clockService.nowEpochSeconds
      (token, authUser) <- generateTokenAndUser(userId, permissions, origIatOpt, nowEpochSec)
      _ <- tokenCacheEnabled.whenA(
        U.logi(s"Caching user ($userId) in MemCache.  Token was: '$token'.") *>
          authUserMemCache.put(token, authUser, tokenExpirationPeriod),
      )
    yield token
  end createToken

  private def decodeWithOpts(token: String, options: JwtOptions): Either[Throwable, JwtClaim] =
    Jwt.decode(token, authConfig.getSecretKey, jwtDecodingAlgorithmList, options).toEither
  end decodeWithOpts

  private def decodeJwtToken(token: String): EitherT[F, Throwable, JwtClaim] =
    EitherT(async.delay(decodeWithOpts(token, JwtOptions.DEFAULT)))
  end decodeJwtToken

  private def jwtClaimToAuthenticatedUser(jwtClaim: JwtClaim): EitherT[F, Throwable, AuthenticatedUser] =
    EitherT(
      async.delay(
        Either.catchNonFatal {
          val p = readFromString[AuthServiceLive.TokenPayload](jwtClaim.content)
          AuthenticatedUser(
            userId = UserId(jwtClaim.subject.get.toLong),
            permissions = p.permissions,
            issuedAt = jwtClaim.issuedAt.get,
            origIat = p.origIat,
            expiresAt = jwtClaim.expiration.get,
          )
        },
      ),
    )
  end jwtClaimToAuthenticatedUser

  override def validateToken(token: String): F[Either[Throwable, AuthenticatedUser]] =
    val cachedUserOpt: F[Option[AuthenticatedUser]] =
      if tokenCacheEnabled then authUserMemCache.get(token) else async.pure(None)

    cachedUserOpt >>= { _.fold(authenticateAndCacheUser(token))(acceptCachedUser) }
  end validateToken

  private def acceptCachedUser(authUser: AuthenticatedUser): F[Either[Throwable, AuthenticatedUser]] =
    U.logi(s"User (${authUser.userId}) found in MemCache.") *> async.pure(Right(authUser))
  end acceptCachedUser

  private def logCachingUserForDuration(duration: java.time.Duration): F[Unit] =
    U.logi(s"Token is valid. Caching user in MemCache for duration ($duration).")
  end logCachingUserForDuration

  private def addUserToCache(token: String, authUser: AuthenticatedUser): F[Unit] =
    clockService.nowEpochSeconds >>= { now =>
      val ttlDuration = java.time.Duration.ofSeconds(authUser.expiresAt - now)
      logCachingUserForDuration(ttlDuration) *>
        authUserMemCache.put(token, authUser, ttlDuration)
    }
  end addUserToCache

  private def authenticateAndCacheUser(token: String): F[Either[Throwable, AuthenticatedUser]] =
    (for
      jwtClaim <- decodeJwtToken(token)
      authUser <- jwtClaimToAuthenticatedUser(jwtClaim)
      _ <- EitherT.liftF(tokenCacheEnabled.whenA(addUserToCache(token, authUser)))
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
  def create[F[_]: { Async, Logger }](
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
    val bs = java.util.BitSet()
    perms.foreach(p => bs.set(p.permissionId.value.toInt))
    bs
  end permissionsToBitSet

  private val urlEncoder: Base64.Encoder = Base64.getUrlEncoder.withoutPadding

  private def bitSetToString(bs: java.util.BitSet): String =
    urlEncoder.encodeToString(bs.toByteArray)
  end bitSetToString

  private val urlDecoder: Base64.Decoder = Base64.getUrlDecoder

  private def stringToBitSet(s: String): java.util.BitSet =
    java.util.BitSet.valueOf(urlDecoder.decode(s))
  end stringToBitSet

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

  private case class TokenPayload(
      permissions: java.util.BitSet,
      origIat: Long,
  )

  private given tokenPayloadCodec: JsonValueCodec[TokenPayload] = JsonCodecMaker.make
end AuthServiceLive
