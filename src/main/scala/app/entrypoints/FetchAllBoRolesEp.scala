package app.entrypoints

import cats.effect.Async

import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra}
import app.model.AppModel.AuthenticatedBoUser
import app.model.AppModel.BoRoleInDb
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
import EndPointUtils.ApiError

private final class FetchAllBoRolesEp[F[_]: Async] private (jobHandler: JobHandler[F], authService: AuthService[F])
    extends ThalesEntryPoint[F]:
  private val fetchAllBoRolesEpErrorOut: EndpointOutput[ApiError] =
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
    )
  end fetchAllBoRolesEpErrorOut

  private val strToAuthenticationError: String => ApiError = ApiError(EndPointUtils.UnauthenticatedApiError.errorCode, _)

  override val getEntryPoint: ServerEndpoint[Any, F] =
    endpoint
      .errorOut(fetchAllBoRolesEpErrorOut)
      .in("api")
      .securityIn(auth.bearer[String]())
      .serverSecurityLogic(EndPointUtils.authenticate(authService, strToAuthenticationError, _))
      .get
      .in("fetchAllBoRoles")
      .out(jsonBody[Vector[BoRoleInDb]].description("The array of all BO Roles."))
      .serverLogic(fetchAllBoRoles)
  end getEntryPoint

  private val unauthorizedError: Either[ApiError, Vector[BoRoleInDb]] = Left(EndPointUtils.UnauthorizedApiError)

  private def fetchAllBoRoles(authenticatedBoUser: AuthenticatedBoUser)(u: Unit): F[Either[ApiError, Vector[BoRoleInDb]]] =
    jobHandler.jobHandlerWithAuth[FetchAllBoRolesResult, ApiError, Vector[BoRoleInDb]](
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
