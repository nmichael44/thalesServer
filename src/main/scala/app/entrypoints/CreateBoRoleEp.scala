package app.entrypoints

import cats.data.NonEmptyVector
import cats.effect.Async

import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra}
import app.entrypoints.EndPointUtils.ApiError
import app.model.AppModel
import app.services.AuthService
import app.JobSpecs.CreateBoRoleError
import app.JobSpecs.JobKind.CreateBoRoleRequest
import app.JobSpecs.JobResult.CreateBoRoleResult
import app.ThalesUtils.ImplicitConversionUtils.*
import io.circe.*
import io.circe.{Decoder, Encoder}
import io.circe.generic.auto.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint

private final class CreateBoRoleEp[F[_]: Async] private (jobHandler: JobHandler[F], authService: AuthService[F])
    extends ThalesEntryPoint[F]:
  private val InvalidParametersApiError: ApiError =
    ApiError("INVALID_PARAMETERS", "[(param1, error1), ..., (paramN, errorN)]")

  private val DuplicateBoRoleNameApiError: ApiError =
    ApiError("ROLE_ALREADY_EXISTS", "The given roleName is already present in the database.")

  private val createBoRoleEpError: EndpointOutput[ApiError] =
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
          .and(jsonBody[ApiError].example(DuplicateBoRoleNameApiError)),
      ),
    )
  end createBoRoleEpError

  private val strToAuthenticationError: String => ApiError = ApiError(EndPointUtils.UnauthenticatedApiError.errorCode, _)

  override val getEntryPoint: ServerEndpoint[Any, F] =
    endpoint
      .errorOut(createBoRoleEpError)
      .in("api")
      .securityIn(auth.bearer[String]())
      .serverSecurityLogic(EndPointUtils.authenticate(authService, strToAuthenticationError, _))
      .post
      .in("createBoRole")
      .in(jsonBody[AppModel.BoRole])
      .out(jsonBody[CreateBoRoleEpResponse])
      .serverLogic(createBoRole)

  private final case class CreateBoRoleEpResponse(roleId: Long)

  private type ReturnTypeForLogicFunction = Either[ApiError, CreateBoRoleEpResponse]

  private def doInvalidParams(invalidParams: NonEmptyVector[(String, String)]): ReturnTypeForLogicFunction =
    Left(ApiError(InvalidParametersApiError.errorCode, invalidParams.view.mkString("[\"", "\", \"", "\"]")))
  end doInvalidParams

  private val doDuplicateBoRoleName: ReturnTypeForLogicFunction = Left(DuplicateBoRoleNameApiError)

  private val unauthorizedError: Either[ApiError, CreateBoRoleEpResponse] = Left(EndPointUtils.UnauthorizedApiError)

  private def createBoRole(authenticatedBoUser: AppModel.AuthenticatedBoUser)(
      boRole: AppModel.BoRole,
  ): F[Either[ApiError, CreateBoRoleEpResponse]] =
    jobHandler.jobHandlerWithAuth[CreateBoRoleResult, ApiError, CreateBoRoleEpResponse](
      authenticatedBoUser,
      CreateBoRolePermissionsAlg,
      CreateBoRoleRequest(boRole, authenticatedBoUser.userId),
      { case CreateBoRoleResult(res) =>
        res match {
          case Left(CreateBoRoleError.InvalidParameters(invalidParams)) => doInvalidParams(invalidParams)
          case Left(CreateBoRoleError.DuplicateRoleName(roleName)) => doDuplicateBoRoleName
          case Right(roleId) => Right(CreateBoRoleEpResponse(roleId))
        }
      },
      unauthorizedError,
    )
  end createBoRole

  private val CreateBoRolePermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanCreateBoRoles).compile
  end CreateBoRolePermissionsAlg
end CreateBoRoleEp

object CreateBoRoleEp:
  def create[F[_]: Async](jobHandler: JobHandler[F], authService: AuthService[F]): ThalesEntryPoint[F] =
    CreateBoRoleEp[F](jobHandler, authService)
  end create
end CreateBoRoleEp
