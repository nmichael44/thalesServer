package app.entrypoints

import cats.effect.Async

import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra}
import app.entrypoints.EndPointUtils.ApiError
import app.model.AppModel.AuthenticatedBoUser
import app.services.AuthService
import app.JobSpecs.DeleteRoleByIdError
import app.JobSpecs.JobKind.DeleteRoleByIdRequest
import app.JobSpecs.JobResult.DeleteRoleByIdResult
import app.ThalesUtils.ExtensionMethodUtils.*
import io.circe.*
import io.circe.generic.auto.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint

private final class DeleteRoleByIdEp[F[_]: Async] private (jobHandler: JobHandler[F], authService: AuthService[F])
    extends ThalesEntryPoint[F]:
  private val NoSuchRoleIdApiError: ApiError =
    ApiError("NO_SUCH_ROLE_ID", "No role with the given ID was found.")
  end NoSuchRoleIdApiError

  private val RoleHasAssociatedUsersApiError: ApiError =
    ApiError("ROLE_HAS_ASSOCIATED_USERS", "The role cannot be deleted as it is associated with existing users.")
  end RoleHasAssociatedUsersApiError

  private val deleteRoleByIdEpErrorOut: EndpointOutput[ApiError] =
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
          .and(jsonBody[ApiError].example(NoSuchRoleIdApiError)),
      ),
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.Conflict)
          .and(jsonBody[ApiError].example(RoleHasAssociatedUsersApiError)),
      ),
    )
  end deleteRoleByIdEpErrorOut

  private val strToAuthenticationError: String => ApiError = ApiError(EndPointUtils.UnauthenticatedApiError.errorCode, _)

  val getEntryPoint: ServerEndpoint[Any, F] =
    endpoint
      .errorOut(deleteRoleByIdEpErrorOut)
      .in("api")
      .securityIn(auth.bearer[String]())
      .serverSecurityLogic(EndPointUtils.authenticate(authService, strToAuthenticationError, _))
      .post
      .in(
        "deleteRoleById" / path[Long]("roleId").description(
          "The roleId of the role to be deleted. When the method is called there must be no users associated with the role otherwise this call will fail.",
        ),
      )
      .out(jsonBody[Unit])
      .serverLogic(deleteRoleById)
  end getEntryPoint

  private val doNoSuchRoleId: Either[ApiError, Unit] = Left(NoSuchRoleIdApiError)

  private val doRoleHasAssociatedUsers: Either[ApiError, Unit] = Left(RoleHasAssociatedUsersApiError)

  private val DeleteRoleByIdPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanDeleteBoRoles).compile
  end DeleteRoleByIdPermissionsAlg

  private val allGood: Either[ApiError, Unit] = Right(())

  private val unauthorizedError: Either[ApiError, Unit] = Left(EndPointUtils.UnauthorizedApiError)

  private def deleteRoleById(authenticatedBoUser: AuthenticatedBoUser)(
      roleId: Long,
  ): F[Either[ApiError, Unit]] =
    jobHandler.jobHandlerWithAuth[DeleteRoleByIdResult, ApiError, Unit](
      authenticatedBoUser,
      DeleteRoleByIdPermissionsAlg,
      DeleteRoleByIdRequest(roleId),
      { case DeleteRoleByIdResult(res) =>
        res match {
          case Left(DeleteRoleByIdError.NoSuchRoleId) => doNoSuchRoleId
          case Left(DeleteRoleByIdError.RoleHasAssociatedUsers) => doRoleHasAssociatedUsers
          case Right(_) => allGood
        }
      },
      unauthorizedError,
    )
  end deleteRoleById
end DeleteRoleByIdEp

object DeleteRoleByIdEp:
  def create[F[_]: Async](jobHandler: JobHandler[F], authService: AuthService[F]): ThalesEntryPoint[F] =
    DeleteRoleByIdEp[F](jobHandler, authService)
  end create
end DeleteRoleByIdEp
