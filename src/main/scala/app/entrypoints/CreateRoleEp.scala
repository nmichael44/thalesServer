package app.entrypoints

import cats.data.NonEmptyVector
import cats.effect.Async

import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra}
import app.entrypoints.EndPointUtils.ApiError
import app.model.AppModel
import app.services.AuthService
import app.JobSpecs.CreateRoleError
import app.JobSpecs.JobKind.CreateRoleRequest
import app.JobSpecs.JobResult.CreateRoleResult
import app.ThalesUtils.ExtensionMethodUtils.*
import io.circe.*
import io.circe.{Decoder, Encoder}
import io.circe.generic.auto.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint

private final class CreateRoleEp[F[_]: Async] private (jobHandler: JobHandler[F], authService: AuthService[F])
    extends ThalesEntryPoint[F]:
  private val InvalidParametersApiError: ApiError =
    ApiError("INVALID_PARAMETERS", "[(param1, error1), ..., (paramN, errorN)]")
  end InvalidParametersApiError

  private val DuplicateRoleNameApiError: ApiError =
    ApiError("ROLE_ALREADY_EXISTS", "The given roleName is already present in the database.")
  end DuplicateRoleNameApiError

  private val createRoleEpError: EndpointOutput[ApiError] =
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
          .statusCodeWithDescription(StatusCode.BadRequest)
          .and(jsonBody[ApiError].example(InvalidParametersApiError)),
      ),
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.Conflict)
          .and(jsonBody[ApiError].example(DuplicateRoleNameApiError)),
      ),
    )
  end createRoleEpError

  private val strToAuthenticationError: String => ApiError = ApiError(EndPointUtils.UnauthenticatedApiError.errorCode, _)

  override val getEntryPoint: ServerEndpoint[Any, F] =
    endpoint
      .errorOut(createRoleEpError)
      .in("api")
      .securityIn(auth.bearer[String]())
      .serverSecurityLogic(EndPointUtils.authenticate(authService, strToAuthenticationError, _))
      .post
      .in("createBoRole")
      .in(jsonBody[AppModel.Role])
      .out(jsonBody[CreateRoleEpResponse])
      .serverLogic(createRole)
  end getEntryPoint

  private final case class CreateRoleEpResponse(roleId: Long)

  private type ReturnTypeForLogicFunction = Either[ApiError, CreateRoleEpResponse]

  private def doInvalidParams(invalidParams: NonEmptyVector[(String, String)]): ReturnTypeForLogicFunction =
    Left(ApiError(InvalidParametersApiError.errorCode, invalidParams.view.mkString("[\"", "\", \"", "\"]")))
  end doInvalidParams

  private val doDuplicateRoleName: ReturnTypeForLogicFunction = Left(DuplicateRoleNameApiError)

  private val unauthorizedError: Either[ApiError, CreateRoleEpResponse] = Left(EndPointUtils.UnauthorizedApiError)

  private def createRole(authenticatedUser: AppModel.AuthenticatedUser)(
      role: AppModel.Role,
  ): F[Either[ApiError, CreateRoleEpResponse]] =
    jobHandler.jobHandlerWithAuth[CreateRoleResult, ApiError, CreateRoleEpResponse](
      authenticatedUser,
      CreateRolePermissionsAlg,
      CreateRoleRequest(role, authenticatedUser.userId),
      { case CreateRoleResult(res) =>
        res match {
          case Left(CreateRoleError.InvalidParameters(invalidParams)) => doInvalidParams(invalidParams)
          case Left(CreateRoleError.DuplicateRoleName(roleName)) => doDuplicateRoleName
          case Right(roleId) => Right(CreateRoleEpResponse(roleId))
        }
      },
      unauthorizedError,
    )
  end createRole

  private val CreateRolePermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanCreateBoRoles).compile
  end CreateRolePermissionsAlg
end CreateRoleEp

object CreateRoleEp:
  def create[F[_]: Async](jobHandler: JobHandler[F], authService: AuthService[F]): ThalesEntryPoint[F] =
    CreateRoleEp[F](jobHandler, authService)
  end create
end CreateRoleEp
