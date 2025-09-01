package app.serviceslive

import cats.data.EitherT
import cats.effect.implicits.parallelForGenSpawn
import cats.effect.Async
import cats.syntax.all.*

import app.auth.Permissions.Permission
import app.model.AppModel.{AuthenticatedBoUser, BoUserInDb}
import app.services.{AuthService, BoRepositoryService, RenewalError}
import app.Config.AppConfig.AuthConfig
import app.ThalesUtils.ImplicitConversionUtils.*
import app.ThalesUtils.TimeUtils
import io.circe.*
import io.circe.generic.auto.*
import io.circe.parser.{decode, parse}
import io.circe.syntax.*
import pdi.jwt.{JwtAlgorithm, JwtCirce, JwtClaim, JwtOptions}
import pdi.jwt.algorithms.JwtHmacAlgorithm

private final class AuthServiceLive[F[_]: Async as async] private (
    authConfig: AuthConfig,
    boRepoService: BoRepositoryService[F],
) extends AuthService[F]:
  private def getHmacAlgorithm: JwtHmacAlgorithm =
    JwtAlgorithm
      .fromString(authConfig.getJwtEncodingAlgorithm)
      .safeAs[JwtHmacAlgorithm]
      .getOrElse(throw AssertionError("We only support Hmac algorithms for token encryption."))

  private val JwtEncodingAlgorithm: JwtHmacAlgorithm = getHmacAlgorithm

  private val JwtDecodingAlgorithmList: Seq[JwtHmacAlgorithm] = Seq(JwtEncodingAlgorithm)

  override def createToken(user: BoUserInDb, permissions: Seq[Permission], origIatOpt: Option[Long]): F[String] =
    TimeUtils.nowEpochSeconds >>= { nowEpochSec =>
      async.blocking {
        val userId = user.userId
        val expiryEpochSec = nowEpochSec + authConfig.getExpirationPeriodInSecond

        val issuedAsJson = nowEpochSec.asJson
        val expiresAsJson = expiryEpochSec.asJson

        val claim = Json.obj(
          "iss" -> "thales-app".asJson,
          "sub" -> userId.toString.asJson,
          "iat" -> issuedAsJson,
          "exp" -> expiresAsJson,
          // The fields that will end up in content (see ValidateToken).
          // We replicate some of the fields above to simply the code in validateToken().
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
    async.blocking(decodeWithOpts(token, JwtOptions.DEFAULT)).toEitherT
  end decodeJwtToken

  private def jwtClaimToAuthenticatedBoUser(jwtClaim: JwtClaim): EitherT[F, Throwable, AuthenticatedBoUser] =
    async.blocking(parse(jwtClaim.content).flatMap(_.as[AuthenticatedBoUser])).toEitherT
  end jwtClaimToAuthenticatedBoUser

  override def validateToken(token: String): F[Either[Throwable, AuthenticatedBoUser]] =
    (for {
      jwtClaim <- decodeJwtToken(token)
      authenticatedBoUser <- jwtClaimToAuthenticatedBoUser(jwtClaim)
    } yield authenticatedBoUser).value
  end validateToken

  private def getUserWithPermissions(userId: Long): F[Option[(BoUserInDb, Vector[Permission])]] =
    val userOpt: F[Option[BoUserInDb]] = boRepoService.fetchBoUserById(userId)

    userOpt.flatMap {
      case Some(boUserInDb) =>
        boRepoService.fetchBoUserPermissions(userId) >>= { permissions =>
          async.pure(Some((boUserInDb, permissions)))
        }
      case None =>
        async.pure(None)
    }
  end getUserWithPermissions

  override def renewToken(authenticatedBoUser: AuthenticatedBoUser): F[Either[RenewalError, String]] = for {
    nowEpochSeconds <- TimeUtils.nowEpochSeconds
    response <- {
      val origIat = authenticatedBoUser.origIat
      val sessionLifetimeInSeconds = nowEpochSeconds - origIat
      if sessionLifetimeInSeconds <= authConfig.getAllowedRenewalPeriodInSeconds
      then
        getUserWithPermissions(authenticatedBoUser.userId) >>= {
          case Some((boUserInDb, permissions)) =>
            if !boUserInDb.enabled then async.pure(Left(RenewalError.UserIsDisabled))
            else if boUserInDb.mustResetPassword then async.pure(Left(RenewalError.UserMustResetPassword))
            else createToken(boUserInDb, permissions, origIat.some).map(Right(_))
          case None => async.pure(Left(RenewalError.NoSuchUser))
        }
      else async.pure(Left(RenewalError.RenewalTimeHasExpired))
    }
  } yield response
  end renewToken
end AuthServiceLive

object AuthServiceLive:
  def create[F[_]: Async](authConfig: AuthConfig, boRepoService: BoRepositoryService[F]): AuthService[F] =
    AuthServiceLive[F](authConfig, boRepoService)
end AuthServiceLive
