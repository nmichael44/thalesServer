package app.serviceslive

import cats.data.EitherT
import cats.data.OptionT
import cats.effect.Async
import cats.syntax.all.*

import app.auth.Permissions
import app.auth.Permissions.Permission
import app.model.AppModel.{AuthenticatedBoUser, BoUserInDb}
import app.services.{AuthService, BoRepositoryService, RenewalError}
import app.Config.AppConfig.AuthConfig
import app.ThalesUtils.ExtensionMethodUtils.*
import app.ThalesUtils.GenUtils as U
import app.ThalesUtils.TimeUtils
import doobie.implicits.toConnectionIOOps
import doobie.ConnectionIO
import doobie.Transactor
import io.circe.*
import io.circe.generic.auto.*
import io.circe.syntax.*
import pdi.jwt.{JwtAlgorithm, JwtCirce, JwtClaim, JwtOptions}
import pdi.jwt.algorithms.JwtHmacAlgorithm

private final class AuthServiceLive[F[_]: Async as async] private (
    authConfig: AuthConfig,
    boRepoService: BoRepositoryService,
    xa: Transactor[F],
) extends AuthService[F]:
  private val TokenExpirationPeriodInSeconds: Long = authConfig.getExpirationPeriodInSeconds

  private def getHmacAlgorithm: JwtHmacAlgorithm =
    JwtAlgorithm
      .fromString(authConfig.getJwtEncodingAlgorithm)
      .safeAs[JwtHmacAlgorithm]
      .getOrElse(throw AssertionError("We only support Hmac algorithms for token encryption."))
  end getHmacAlgorithm

  private val JwtEncodingAlgorithm: JwtHmacAlgorithm = getHmacAlgorithm

  private val JwtDecodingAlgorithmList: Seq[JwtHmacAlgorithm] = Seq(JwtEncodingAlgorithm)

  private val ThalesAppAsJson: Json = "thales-app".asJson

  override def createToken(user: BoUserInDb, permissions: Seq[Permission], origIatOpt: Option[Long]): F[String] =
    val userId = user.userId

    TimeUtils.nowEpochSeconds >>= { nowEpochSec =>
      async.blocking {
        val expiryEpochSec = nowEpochSec + TokenExpirationPeriodInSeconds

        val issuedAsJson = nowEpochSec.asJson
        val expiresAsJson = expiryEpochSec.asJson

        val claim = Json.obj(
          "iss" -> ThalesAppAsJson,
          "sub" -> userId.toString.asJson,
          "iat" -> issuedAsJson,
          "exp" -> expiresAsJson,
          // The fields that will end up in content (see ValidateToken).
          // We replicate some of the fields above to simplify the code in validateToken().
          "userId"      -> userId.asJson,
          "issuedAt"    -> issuedAsJson,
          "expiresAt"   -> expiresAsJson,
          "permissions" -> permissions.asJson,
          "origIat"     -> origIatOpt.map(_.asJson).getOrElse(issuedAsJson),
        )

        JwtCirce.encode(claim, authConfig.getSecretKey, JwtEncodingAlgorithm)
      }
    }
  end createToken

  private def decodeWithOpts(token: String, options: JwtOptions): Either[Throwable, JwtClaim] =
    JwtCirce.decode(token, authConfig.getSecretKey, JwtDecodingAlgorithmList, options).toEither
  end decodeWithOpts

  private def decodeJwtToken(token: String): EitherT[F, Throwable, JwtClaim] =
    EitherT(async.blocking(decodeWithOpts(token, JwtOptions.DEFAULT)))
  end decodeJwtToken

  private def jwtClaimToAuthenticatedBoUser(jwtClaim: JwtClaim): EitherT[F, Throwable, AuthenticatedBoUser] =
    EitherT(async.blocking(parser.decode[AuthenticatedBoUser](jwtClaim.content)))
  end jwtClaimToAuthenticatedBoUser

  override def validateToken(token: String): F[Either[Throwable, AuthenticatedBoUser]] =
    (for {
      jwtClaim <- decodeJwtToken(token)
      authenticatedBoUser <- jwtClaimToAuthenticatedBoUser(jwtClaim)
    } yield authenticatedBoUser).value
  end validateToken

  private def getUserWithPermissions(userId: Long): F[Option[(BoUserInDb, Vector[Permission])]] =
    val dbProgram: OptionT[ConnectionIO, (BoUserInDb, Vector[Permission])] = for {
      user <- OptionT(boRepoService.fetchBoUserById(userId))
      permissions <-
        OptionT.liftF(
          boRepoService
            .fetchBoUserPermissions(userId)
            .map(_.map(p => Permissions.fromString(p.permissionName))),
        )
    } yield (user, permissions)

    dbProgram.value.transact(xa)
  end getUserWithPermissions

  private val NoSuchUserF: F[Either[RenewalError, String]] = U.leftF(RenewalError.NoSuchUser)
  private val UserIsDisabledF: F[Either[RenewalError, String]] = U.leftF(RenewalError.UserIsDisabled)
  private val UserMustResetPasswordF: F[Either[RenewalError, String]] = U.leftF(RenewalError.UserMustResetPassword)
  private val RenewalTimeHasExpiredF: F[Either[RenewalError, String]] = U.leftF(RenewalError.RenewalTimeHasExpired)

  override def renewToken(authenticatedBoUser: AuthenticatedBoUser): F[Either[RenewalError, String]] = for {
    nowEpochSeconds <- TimeUtils.nowEpochSeconds
    response <- {
      val origIat = authenticatedBoUser.origIat
      val sessionLifetimeInSeconds = nowEpochSeconds - origIat
      if sessionLifetimeInSeconds <= authConfig.getAllowedRenewalPeriodInSeconds
      then
        getUserWithPermissions(authenticatedBoUser.userId) >>= {
          _.fold(NoSuchUserF) { (boUserInDb, permissions) =>
            if !boUserInDb.enabled then UserIsDisabledF
            else if boUserInDb.mustResetPassword then UserMustResetPasswordF
            else createToken(boUserInDb, permissions, origIat.some).map(Right(_))
          }
        }
      else RenewalTimeHasExpiredF
    }
  } yield response
  end renewToken
end AuthServiceLive

object AuthServiceLive:
  def create[F[_]: Async](authConfig: AuthConfig, boRepoService: BoRepositoryService, xa: Transactor[F]): AuthService[F] =
    AuthServiceLive[F](authConfig, boRepoService, xa)
  end create
end AuthServiceLive
