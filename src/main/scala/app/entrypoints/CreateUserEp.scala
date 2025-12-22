package app.entrypoints

import cats.data.NonEmptyVector
import cats.effect.Async

import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra}
import app.entrypoints.EndPointUtils.ApiError
import app.model.AppModel
import app.model.AppModel.AuthenticatedUser
import app.services.AuthService
import app.JobSpecs.CreateUserError
import app.JobSpecs.JobKind.CreateUserRequest
import app.JobSpecs.JobResult.CreateUserResult
import app.ThalesUtils.ExtensionMethodUtils.*
import io.circe.*
import io.circe.{Decoder, Encoder}
import io.circe.generic.auto.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint

private final class CreateUserEp[F[_]: Async] private (
    jobHandler: JobHandler[F],
    authService: AuthService[F],
) extends ThalesEntryPoint[F]:
  private val InvalidParametersApiError: ApiError =
    ApiError("INVALID_PARAMETERS", "[(param1, error1), ..., (paramN, errorN)]")
  end InvalidParametersApiError

  private val DuplicateLoginNameApiError: ApiError =
    ApiError("LOGIN_ALREADY_EXISTS", "The given loginName 'someLoginName' was already present in the database.")
  end DuplicateLoginNameApiError

  private val BadPasswordApiError: ApiError =
    ApiError("INVALID_PASSWORD", "[\"error1\", \"error2\"]")
  end BadPasswordApiError

  private val createUserWithAuthErrorOut: EndpointOutput[ApiError] =
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
          .and(jsonBody[ApiError].example(DuplicateLoginNameApiError)),
      ),
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.BadRequest)
          .and(jsonBody[ApiError].example(BadPasswordApiError)),
      ),
    )
  end createUserWithAuthErrorOut

  private val strToAuthenticationError: String => ApiError = ApiError(EndPointUtils.UnauthenticatedApiError.errorCode, _)

  private final case class CreateUserWithAuthEpResponse(userId: Long)

  override val getEntryPoint: ServerEndpoint[Any, F] =
    endpoint
      .errorOut(createUserWithAuthErrorOut)
      .in("api")
      .securityIn(auth.bearer[String]())
      .serverSecurityLogic(EndPointUtils.authenticate(authService, strToAuthenticationError, _))
      .post
      .in("createUser")
      .in(jsonBody[AppModel.User])
      .out(jsonBody[CreateUserWithAuthEpResponse])
      .serverLogic(createUserWithAuth)
  end getEntryPoint

  private type ReturnTypeForLogicFunction = Either[ApiError, CreateUserWithAuthEpResponse]

  private def doInvalidParams(invalidParams: NonEmptyVector[(String, String)]): ReturnTypeForLogicFunction =
    Left(ApiError(InvalidParametersApiError.errorCode, invalidParams.view.mkString("[\"", "\", \"", "\"]")))
  end doInvalidParams

  private val doUniquenessConstraintViolated: ReturnTypeForLogicFunction = Left(DuplicateLoginNameApiError)

  private def doBadPassword(value: NonEmptyVector[String]): ReturnTypeForLogicFunction =
    Left(ApiError(BadPasswordApiError.errorCode, value.view.mkString("[\"", "\", \"", "\"]")))
  end doBadPassword

  private val unauthorizedError: Either[ApiError, CreateUserWithAuthEpResponse] = Left(EndPointUtils.UnauthorizedApiError)

  private def createUserWithAuth(authenticatedUser: AuthenticatedUser)(
      user: AppModel.User,
  ): F[ReturnTypeForLogicFunction] =
    jobHandler.jobHandlerWithAuth[CreateUserResult, ApiError, CreateUserWithAuthEpResponse](
      authenticatedUser,
      CreateUserPermissionsAlg,
      CreateUserRequest(user),
      { case CreateUserResult(res) =>
        res match {
          case Left(CreateUserError.InvalidParameters(invalidParams)) => doInvalidParams(invalidParams)
          case Left(CreateUserError.UniquenessConstraintViolated(loginName)) => doUniquenessConstraintViolated
          case Left(CreateUserError.BadPassword(errorList)) => doBadPassword(errorList)
          case Right(userId) => Right(CreateUserWithAuthEpResponse(userId))
        }
      },
      unauthorizedError,
    )
  end createUserWithAuth

  private val CreateUserPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanCreateUsers).compile
  end CreateUserPermissionsAlg
end CreateUserEp

object CreateUserEp:
  def create[F[_]: Async](jobHandler: JobHandler[F], authService: AuthService[F]): ThalesEntryPoint[F] =
    CreateUserEp[F](jobHandler, authService)
  end create
end CreateUserEp
