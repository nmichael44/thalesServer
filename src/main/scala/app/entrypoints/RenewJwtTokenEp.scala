package app.entrypoints

import cats.effect.Async

import app.auth.Permissions.{CompiledPermissionAlgebra, PermissionAlgebra}
import app.auth.Permissions.Permission
import app.entrypoints.EndPointUtils.ApiError
import app.model.AppModel.AuthenticatedUser
import app.services.AuthService
import app.JobSpecs.JobKind
import app.JobSpecs.JobResult.RenewJwtTokenResult
import app.JobSpecs.RenewJwtTokenError
import io.circe.*
import io.circe.generic.auto.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint

private final class RenewJwtTokenEp[F[_]: Async] private (jobHandler: JobHandler[F], authService: AuthService[F])
    extends ThalesEntryPoint[F]:
  private val RenewalTimeHasExpiredApiError: ApiError =
    ApiError("RENEWAL_TIME_HAS_EXPIRED", "Token was renewed too many times. Please log in again.")
  end RenewalTimeHasExpiredApiError

  private val renewJwtTokenEpErrorOut: EndpointOutput[ApiError] =
    oneOf(
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.Unauthorized)
          .and(jsonBody[EndPointUtils.ApiError].example(EndPointUtils.UnauthenticatedApiError)),
      ),
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.Forbidden)
          .and(jsonBody[EndPointUtils.ApiError].example(EndPointUtils.UnauthorizedApiError)),
      ),
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.NotAcceptable)
          .and(jsonBody[EndPointUtils.ApiError].example(RenewalTimeHasExpiredApiError)),
      ),
    )
  end renewJwtTokenEpErrorOut

  private val strToAuthenticationError: String => ApiError = ApiError(EndPointUtils.UnauthenticatedApiError.errorCode, _)

  override val getEntryPoint: ServerEndpoint[Any, F] =
    endpoint
      .errorOut(renewJwtTokenEpErrorOut)
      .in("api")
      .securityIn(auth.bearer[String]())
      .serverSecurityLogic(EndPointUtils.authenticate(authService, strToAuthenticationError, _))
      .post
      .in("renewJwtToken")
      .out(jsonBody[String].description("The new JWT token."))
      .description("Renews the JWT token for an authenticated user, extending its validity.")
      .serverLogic(renewJwtToken)

  private val UnauthorizedError: Either[ApiError, String] = Left(EndPointUtils.UnauthorizedApiError)

  private def renewJwtToken(authenticatedBoUser: AuthenticatedUser)(u: Unit): F[Either[ApiError, String]] =
    jobHandler.jobHandlerWithAuth[RenewJwtTokenResult, ApiError, String](
      authenticatedBoUser,
      RenewJwtTokenPermissionsAlg,
      JobKind.RenewJwtTokenRequest(authenticatedBoUser),
      { case RenewJwtTokenResult(res) =>
        res.left.map {
          case RenewJwtTokenError.NoSuchUser(userId) => EndPointUtils.UnauthorizedApiError
          case RenewJwtTokenError.UserIsDisabled(userId) => EndPointUtils.UnauthorizedApiError
          case RenewJwtTokenError.UserMustResetPassword(userId) => EndPointUtils.UnauthorizedApiError
          case RenewJwtTokenError.RenewalTimeHasExpired => RenewalTimeHasExpiredApiError
        }
      },
      UnauthorizedError,
    )
  end renewJwtToken

  private val RenewJwtTokenPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanRenewJwtToken).compile
  end RenewJwtTokenPermissionsAlg
end RenewJwtTokenEp

object RenewJwtTokenEp:
  def create[F[_]: Async](jobHandler: JobHandler[F], authService: AuthService[F]): ThalesEntryPoint[F] =
    RenewJwtTokenEp[F](jobHandler, authService)
end RenewJwtTokenEp
