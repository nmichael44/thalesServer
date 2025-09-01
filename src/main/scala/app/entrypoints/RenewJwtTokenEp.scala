package app.entrypoints

import cats.effect.Async
import cats.implicits.*

import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra}
import app.auth.Permissions.Permission
import app.model.AppModel.AuthenticatedBoUser
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

  private enum RenewJwtTokenEpError:
    case UnauthenticatedError(error: EndPointUtils.ApiError)
    case UnauthorizedError(error: EndPointUtils.ApiError)
  end RenewJwtTokenEpError

  private val renewJwtTokenEpErrorOut: EndpointOutput[RenewJwtTokenEpError] =
    oneOf(
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.Unauthorized)
          .and(jsonBody[EndPointUtils.ApiError].example(EndPointUtils.UnauthenticatedApiError))
          .mapTo[RenewJwtTokenEpError.UnauthenticatedError],
      ),
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.Forbidden)
          .and(jsonBody[EndPointUtils.ApiError].example(EndPointUtils.UnauthorizedApiError))
          .mapTo[RenewJwtTokenEpError.UnauthorizedError],
      ),
    )
  end renewJwtTokenEpErrorOut

  private def strToAuthenticationError(str: String): RenewJwtTokenEpError =
    RenewJwtTokenEpError.UnauthenticatedError(EndPointUtils.ApiError(EndPointUtils.UnauthenticatedApiError.errorCode, str))
  end strToAuthenticationError

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

  private val unauthorizedError: Either[RenewJwtTokenEpError, String] =
    Left(RenewJwtTokenEpError.UnauthorizedError(EndPointUtils.UnauthorizedApiError))
  end unauthorizedError

  private def renewJwtToken(authenticatedBoUser: AuthenticatedBoUser)(u: Unit): F[Either[RenewJwtTokenEpError, String]] =
    jobHandler.jobHandlerWithAuth[RenewJwtTokenResult, RenewJwtTokenEpError, String](
      authenticatedBoUser,
      RenewJwtTokenPermissionsAlg,
      JobKind.RenewJwtTokenRequest(authenticatedBoUser),
      { case RenewJwtTokenResult(res) =>
        res match {
          case Left(RenewJwtTokenError.NoSuchUser(userId)) => unauthorizedError
          case Left(RenewJwtTokenError.UserIsDisabled(userId)) => unauthorizedError
          case Left(RenewJwtTokenError.UserMustResetPassword(userId)) => unauthorizedError
          case Left(RenewJwtTokenError.RenewalTimeHasExpired()) => unauthorizedError
          case Right(_) => res.asInstanceOf[Either[RenewJwtTokenEpError, String]]
        }
      },
      unauthorizedError,
    )
  end renewJwtToken

  private val RenewJwtTokenPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanRenewJwtToken).compile
  end RenewJwtTokenPermissionsAlg
end RenewJwtTokenEp

object RenewJwtTokenEp:
  def create[F[_]: Async](jobHandler: JobHandler[F], authService: AuthService[F]): ThalesEntryPoint[F] =
    new RenewJwtTokenEp[F](jobHandler, authService)
end RenewJwtTokenEp
