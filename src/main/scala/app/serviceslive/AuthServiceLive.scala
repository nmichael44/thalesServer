package app.serviceslive

import cats.data.{EitherT, NonEmptyVector, OptionT}
import cats.effect.Async
import cats.syntax.all.*

import java.util.Base64

import app.Config.AppConfig.AuthConfig
import app.ThalesUtils.ExtensionMethodUtils.*
import app.ThalesUtils.GenUtils as U
import app.ThalesUtils.TimeUtils
import app.entrypoints.SmithyCodecs.given
import app.entrypoints.smithy.{PermissionInDb, UserId, UserInDb}
import app.model.AppModel.AuthenticatedUser
import app.services.{AuthService, ClockService, RenewalError, RepositoryService}
import doobie.ConnectionIO
import doobie.Transactor
import doobie.implicits.*
import doobie.implicits.toConnectionIOOps
import io.circe.*
import io.circe.generic.auto.*
import io.circe.syntax.*
import pdi.jwt.{JwtAlgorithm, JwtCirce, JwtClaim, JwtOptions}
import pdi.jwt.algorithms.JwtHmacAlgorithm

private final class AuthServiceLive[F[_]: Async as async] private (
    authConfig: AuthConfig,
    clockService: ClockService[F],
    repoService: RepositoryService,
    xa: Transactor[F],
) extends AuthService[F]:
  private val TokenExpirationPeriodInSeconds: Long = authConfig.getExpirationPeriodInSeconds

  private val JwtEncodingAlgorithm: JwtHmacAlgorithm =
    JwtAlgorithm
      .fromString(authConfig.getJwtEncodingAlgorithm)
      .safeAs[JwtHmacAlgorithm]
      .getOrElse(throw AssertionError("We only support Hmac algorithms for token encryption."))

  private val JwtDecodingAlgorithmList: Seq[JwtHmacAlgorithm] = Seq(JwtEncodingAlgorithm)

  private val ThalesAppAsJson: Json = "thales-app".asJson

  override def createToken(user: UserInDb, permissions: Seq[PermissionInDb], origIatOpt: Option[Long]): F[String] =
    val userId = user.userId

    clockService.nowEpochSeconds >>= { nowEpochSec =>
      async.delay {
        val expiryEpochSec = nowEpochSec + TokenExpirationPeriodInSeconds

        val issuedAsJson = nowEpochSec.asJson
        val expiresAsJson = expiryEpochSec.asJson

        val permsString =
          AuthServiceLive.bitSetToString(
            AuthServiceLive.permissionsToBitSet(permissions),
          )
        val claim = Json.obj(
          "iss" -> ThalesAppAsJson,
          "sub" -> userId.toString.asJson,
          "iat" -> issuedAsJson,
          "exp" -> expiresAsJson,
          // The fields that will end up in content (see ValidateToken).
          // We replicate some of the fields above to simplify the code in validateToken().
          "userId"      -> userId.value.asJson,
          "issuedAt"    -> issuedAsJson,
          "expiresAt"   -> expiresAsJson,
          "permissions" -> permsString.asJson,
          "origIat"     -> origIatOpt.map(_.asJson).getOrElse(issuedAsJson),
        )

        JwtCirce.encode(claim, authConfig.getSecretKey, JwtEncodingAlgorithm)
      }
    }
  end createToken

  private given Decoder[java.util.BitSet] = Decoder.decodeString.emapTry { s =>
    scala.util.Try(AuthServiceLive.stringToBitSet(s))
  }
  end given

  private def decodeWithOpts(token: String, options: JwtOptions): Either[Throwable, JwtClaim] =
    JwtCirce.decode(token, authConfig.getSecretKey, JwtDecodingAlgorithmList, options).toEither
  end decodeWithOpts

  private def decodeJwtToken(token: String): EitherT[F, Throwable, JwtClaim] =
    EitherT(async.blocking(decodeWithOpts(token, JwtOptions.DEFAULT)))
  end decodeJwtToken

  private def jwtClaimToAuthenticatedUser(jwtClaim: JwtClaim): EitherT[F, Throwable, AuthenticatedUser] =
    EitherT(async.blocking(parser.decode[AuthenticatedUser](jwtClaim.content)))
  end jwtClaimToAuthenticatedUser

  override def validateToken(token: String): F[Either[Throwable, AuthenticatedUser]] =
    (for {
      jwtClaim <- decodeJwtToken(token)
      authenticatedBoUser <- jwtClaimToAuthenticatedUser(jwtClaim)
    } yield authenticatedBoUser).value
  end validateToken

  private def getUserWithPermissions(userId: UserId): F[Option[(UserInDb, Vector[PermissionInDb])]] =
    val userIdVec = NonEmptyVector.one(userId)
    val dbProgram: OptionT[ConnectionIO, (UserInDb, Vector[PermissionInDb])] = for {
      user <- OptionT(repoService.fetchUsersByUserIds(userIdVec).map(_.get(userId)))
      permissions <-
        OptionT.liftF(repoService.fetchUserPermissions(userId))
    } yield (user, permissions)

    dbProgram.value.transact(xa)
  end getUserWithPermissions

  private val NoSuchUserF: F[Either[RenewalError, String]] = U.leftF(RenewalError.NoSuchUser)
  private val UserIsDisabledF: F[Either[RenewalError, String]] = U.leftF(RenewalError.UserIsDisabled)
  private val UserMustResetPasswordF: F[Either[RenewalError, String]] = U.leftF(RenewalError.UserMustResetPassword)
  private val RenewalTimeHasExpiredF: F[Either[RenewalError, String]] = U.leftF(RenewalError.RenewalTimeHasExpired)

  override def renewToken(authenticatedUser: AuthenticatedUser): F[Either[RenewalError, String]] = for {
    nowEpochSeconds <- clockService.nowEpochSeconds
    response <- {
      val origIat = authenticatedUser.origIat
      val sessionLifetimeInSeconds = nowEpochSeconds - origIat
      if sessionLifetimeInSeconds <= authConfig.getAllowedRenewalPeriodInSeconds
      then
        getUserWithPermissions(authenticatedUser.userId) >>= {
          _.fold(NoSuchUserF) { (userInDb, permissions) =>
            if !userInDb.enabled then UserIsDisabledF
            else if userInDb.mustResetPassword then UserMustResetPasswordF
            else createToken(userInDb, permissions, origIat.some).map(Right(_))
          }
        }
      else RenewalTimeHasExpiredF
    }
  } yield response
  end renewToken
end AuthServiceLive

object AuthServiceLive:
  def create[F[_]: Async](
      authConfig: AuthConfig,
      clockService: ClockService[F],
      boRepoService: RepositoryService,
      xa: Transactor[F],
  ): AuthService[F] =
    AuthServiceLive[F](authConfig, clockService, boRepoService, xa)
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
end AuthServiceLive
