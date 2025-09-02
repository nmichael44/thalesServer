package app.entrypoints

import cats.effect.Async

import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra, PermissionInDb}
import app.model.AppModel.AuthenticatedBoUser
import app.services.AuthService
import app.JobSpecs.JobKind.FetchAllBoPermissionsRequest
import app.JobSpecs.JobResult.FetchAllBoPermissionsResult
import io.circe.*
import io.circe.generic.auto.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint

private final class FetchAllBoPermissionsEp[F[_]: Async] private (jobHandler: JobHandler[F], authService: AuthService[F])
    extends ThalesEntryPoint[F]:
  private enum FetchAllBoPermissionsEpError:
    case UnauthenticatedError(error: EndPointUtils.ApiError)
    case UnauthorizedError(error: EndPointUtils.ApiError)
  end FetchAllBoPermissionsEpError

  private val fetchAllBoPermissionsEpErrorOut: EndpointOutput[FetchAllBoPermissionsEpError] =
    oneOf(
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.Unauthorized)
          .and(jsonBody[EndPointUtils.ApiError].example(EndPointUtils.UnauthenticatedApiError))
          .mapTo[FetchAllBoPermissionsEpError.UnauthenticatedError],
      ),
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.Forbidden)
          .and(jsonBody[EndPointUtils.ApiError].example(EndPointUtils.UnauthorizedApiError))
          .mapTo[FetchAllBoPermissionsEpError.UnauthorizedError],
      ),
    )
  end fetchAllBoPermissionsEpErrorOut

  private def strToAuthenticationError(str: String): FetchAllBoPermissionsEpError =
    FetchAllBoPermissionsEpError.UnauthenticatedError(
      EndPointUtils.ApiError(EndPointUtils.UnauthenticatedApiError.errorCode, str),
    )
  end strToAuthenticationError

  override val getEntryPoint: ServerEndpoint[Any, F] =
    endpoint
      .errorOut(fetchAllBoPermissionsEpErrorOut)
      .in("api")
      .securityIn(auth.bearer[String]())
      .serverSecurityLogic(EndPointUtils.authenticate(authService, strToAuthenticationError, _))
      .get
      .in("fetchAllBoPermissions")
      .out(jsonBody[Vector[PermissionInDb]].description("The array of all BO Permissions."))
      .serverLogic(fetchAllBoPermissions)
  end getEntryPoint

  private val unauthorizedError: Either[FetchAllBoPermissionsEpError, Vector[PermissionInDb]] =
    Left(FetchAllBoPermissionsEpError.UnauthorizedError(EndPointUtils.UnauthorizedApiError))
  end unauthorizedError

  private def fetchAllBoPermissions(
      authenticatedBoUser: AuthenticatedBoUser,
  )(u: Unit): F[Either[FetchAllBoPermissionsEpError, Vector[PermissionInDb]]] =
    jobHandler.jobHandlerWithAuth[FetchAllBoPermissionsResult, FetchAllBoPermissionsEpError, Vector[PermissionInDb]](
      authenticatedBoUser,
      FetchAllBoPermissionsPermissionsAlg,
      FetchAllBoPermissionsRequest(),
      { case FetchAllBoPermissionsResult(res) => Right(res) },
      unauthorizedError,
    )
  end fetchAllBoPermissions

  private val FetchAllBoPermissionsPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanSeeAllBoPermissions).compile
  end FetchAllBoPermissionsPermissionsAlg
end FetchAllBoPermissionsEp

object FetchAllBoPermissionsEp:
  def create[F[_]: Async](jobHandler: JobHandler[F], authService: AuthService[F]): ThalesEntryPoint[F] =
    FetchAllBoPermissionsEp[F](jobHandler, authService)
  end create
end FetchAllBoPermissionsEp
