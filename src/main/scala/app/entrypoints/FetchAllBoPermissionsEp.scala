package app.entrypoints

import cats.effect.Async

import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra, PermissionInDb}
import app.model.AppModel.AuthenticatedUser
import app.services.AuthService
import app.JobSpecs.JobKind.FetchAllPermissionsRequest
import app.JobSpecs.JobResult.FetchAllPermissionsResult
import io.circe.*
import io.circe.generic.auto.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint
import EndPointUtils.ApiError

private final class FetchAllBoPermissionsEp[F[_]: Async] private (jobHandler: JobHandler[F], authService: AuthService[F])
    extends ThalesEntryPoint[F]:
  private val fetchAllBoPermissionsEpErrorOut: EndpointOutput[ApiError] =
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
  end fetchAllBoPermissionsEpErrorOut

  private val strToAuthenticationError: String => ApiError = ApiError(EndPointUtils.UnauthenticatedApiError.errorCode, _)

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

  private val unauthorizedError: Either[ApiError, Vector[PermissionInDb]] = Left(EndPointUtils.UnauthorizedApiError)

  private def fetchAllBoPermissions(
      authenticatedBoUser: AuthenticatedUser,
  )(u: Unit): F[Either[ApiError, Vector[PermissionInDb]]] =
    jobHandler.jobHandlerWithAuth[FetchAllPermissionsResult, ApiError, Vector[PermissionInDb]](
      authenticatedBoUser,
      FetchAllBoPermissionsPermissionsAlg,
      FetchAllPermissionsRequest(),
      { case FetchAllPermissionsResult(res) => Right(res) },
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
