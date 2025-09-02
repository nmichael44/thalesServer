package app.entrypoints

import cats.effect.Async

import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra}
import app.model.AppModel.AuthenticatedBoUser
import app.model.AppModel.RoleInDb
import app.services.AuthService
import app.JobSpecs.JobKind.FetchAllBoRolesRequest
import app.JobSpecs.JobResult.FetchAllBoRolesResult
import io.circe.*
import io.circe.generic.auto.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint

private final class FetchAllBoRolesEp[F[_]: Async] private (jobHandler: JobHandler[F], authService: AuthService[F])
    extends ThalesEntryPoint[F]:
  private enum FetchAllBoRolesEpError:
    case UnauthenticatedError(error: EndPointUtils.ApiError)
    case UnauthorizedError(error: EndPointUtils.ApiError)
  end FetchAllBoRolesEpError

  private val fetchAllBoRolesEpErrorOut: EndpointOutput[FetchAllBoRolesEpError] =
    oneOf(
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.Unauthorized)
          .and(jsonBody[EndPointUtils.ApiError].example(EndPointUtils.UnauthenticatedApiError))
          .mapTo[FetchAllBoRolesEpError.UnauthenticatedError],
      ),
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.Forbidden)
          .and(jsonBody[EndPointUtils.ApiError].example(EndPointUtils.UnauthorizedApiError))
          .mapTo[FetchAllBoRolesEpError.UnauthorizedError],
      ),
    )
  end fetchAllBoRolesEpErrorOut

  private def strToAuthenticationError(str: String): FetchAllBoRolesEpError =
    FetchAllBoRolesEpError.UnauthenticatedError(
      EndPointUtils.ApiError(EndPointUtils.UnauthenticatedApiError.errorCode, str),
    )
  end strToAuthenticationError

  override val getEntryPoint: ServerEndpoint[Any, F] =
    endpoint
      .errorOut(fetchAllBoRolesEpErrorOut)
      .in("api")
      .securityIn(auth.bearer[String]())
      .serverSecurityLogic(EndPointUtils.authenticate(authService, strToAuthenticationError, _))
      .get
      .in("fetchAllBoRoles")
      .out(jsonBody[Vector[RoleInDb]].description("The array of all BO Roles."))
      .serverLogic(fetchAllBoRoles)
  end getEntryPoint

  private val unauthorizedError: Either[FetchAllBoRolesEpError, Vector[RoleInDb]] =
    Left(FetchAllBoRolesEpError.UnauthorizedError(EndPointUtils.UnauthorizedApiError))
  end unauthorizedError

  private def fetchAllBoRoles(
      authenticatedBoUser: AuthenticatedBoUser,
  )(u: Unit): F[Either[FetchAllBoRolesEpError, Vector[RoleInDb]]] =
    jobHandler.jobHandlerWithAuth[FetchAllBoRolesResult, FetchAllBoRolesEpError, Vector[RoleInDb]](
      authenticatedBoUser,
      FetchAllBoRolesPermissionsAlg,
      FetchAllBoRolesRequest(),
      { case FetchAllBoRolesResult(res) => Right(res) },
      unauthorizedError,
    )
  end fetchAllBoRoles

  private val FetchAllBoRolesPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanSeeAllBoRoles).compile
  end FetchAllBoRolesPermissionsAlg
end FetchAllBoRolesEp

object FetchAllBoRolesEp:
  def create[F[_]: Async](jobHandler: JobHandler[F], authService: AuthService[F]): ThalesEntryPoint[F] =
    FetchAllBoRolesEp[F](jobHandler, authService)
  end create
end FetchAllBoRolesEp
