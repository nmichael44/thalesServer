package app.entrypoints

import cats.data.NonEmptyVector
import cats.effect.Async

import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra}
import app.entrypoints.EndPointUtils.ApiError
import app.model.AppModel.{AuthenticatedBoUser, BoUserInDb}
import app.services.AuthService
import app.JobSpecs.FetchAllUsersAssociatedWithRoleError
import app.JobSpecs.JobKind.FetchAllUsersAssociatedWithRoleRequest
import app.JobSpecs.JobResult.FetchAllUsersAssociatedWithRoleResult
import io.circe.*
import io.circe.generic.auto.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint

private final class FetchAllUsersAssociatedWithRoleEp[F[_]: Async] private (
    jobHandler: JobHandler[F],
    authService: AuthService[F],
) extends ThalesEntryPoint[F]:
  private val RoleNotFoundApiError: ApiError =
    ApiError("ROLE_DOES_NOT_EXIST", "No role with given roleId was found in the system.")

  private val fetchAllUsersAssociatedWithRoleEpErrorOut: EndpointOutput[ApiError] =
    oneOf(
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.Unauthorized)
          .and(jsonBody[ApiError].example(EndPointUtils.UnauthenticatedApiError)),
      ),
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.Forbidden)
          .and(jsonBody[ApiError].example(EndPointUtils.UnauthorizedApiError)),
      ),
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.NotFound)
          .and(jsonBody[ApiError].example(RoleNotFoundApiError)),
      ),
    )
  end fetchAllUsersAssociatedWithRoleEpErrorOut

  private val strToAuthenticationError: String => ApiError = ApiError(EndPointUtils.UnauthenticatedApiError.errorCode, _)

  override val getEntryPoint: ServerEndpoint[Any, F] =
    endpoint
      .errorOut(fetchAllUsersAssociatedWithRoleEpErrorOut)
      .in("api")
      .securityIn(auth.bearer[String]())
      .serverSecurityLogic(EndPointUtils.authenticate(authService, strToAuthenticationError, _))
      .get
      .in(
        "fetchAllUsersAssociatedWithRole" / path[Long]("roleId")
          .description("The roleId whose associated users we are fetching."),
      )
      .out(jsonBody[Vector[BoUserInDb]])
      .serverLogic(fetchAllUsersAssociatedWithRole)

  private val unauthorizedError: Either[ApiError, Vector[BoUserInDb]] = Left(EndPointUtils.UnauthorizedApiError)

  private val FetchAllLiveSessionsPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra
      .And(
        NonEmptyVector.of(PermissionAlgebra.Has(Permission.CanSeeAllBoRoles), PermissionAlgebra.Has(Permission.CanSeeBoUsers)),
      )
      .compile

  private val doRoleNotFound: Either[ApiError, Vector[BoUserInDb]] = Left(RoleNotFoundApiError)

  private def fetchAllUsersAssociatedWithRole(
      authenticatedBoUser: AuthenticatedBoUser,
  )(roleId: Long): F[Either[ApiError, Vector[BoUserInDb]]] =
    jobHandler.jobHandlerWithAuth[FetchAllUsersAssociatedWithRoleResult, ApiError, Vector[BoUserInDb]](
      authenticatedBoUser,
      FetchAllLiveSessionsPermissionsAlg,
      FetchAllUsersAssociatedWithRoleRequest(roleId),
      { case FetchAllUsersAssociatedWithRoleResult(res) =>
        res match {
          case Left(FetchAllUsersAssociatedWithRoleError.NoSuchRole()) => doRoleNotFound
          case Right(res) => Right(res)
        }
      },
      unauthorizedError,
    )
  end fetchAllUsersAssociatedWithRole
end FetchAllUsersAssociatedWithRoleEp

object FetchAllUsersAssociatedWithRoleEp:
  def create[F[_]: Async](jobHandler: JobHandler[F], authService: AuthService[F]): ThalesEntryPoint[F] =
    FetchAllUsersAssociatedWithRoleEp(jobHandler, authService)
  end create
end FetchAllUsersAssociatedWithRoleEp
