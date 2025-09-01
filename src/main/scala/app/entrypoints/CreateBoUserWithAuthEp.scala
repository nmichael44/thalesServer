package app.entrypoints

import cats.data.NonEmptyVector
import cats.effect.Async

import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra}
import app.entrypoints.EndPointUtils.ApiError
import app.model.AppModel
import app.model.AppModel.AuthenticatedBoUser
import app.services.AuthService
import app.JobSpecs.CreateBoUserError
import app.JobSpecs.JobKind.CreateBoUserRequest
import app.JobSpecs.JobResult.CreateBoUserResult
import app.ThalesUtils.ImplicitConversionUtils.view
import io.circe.*
import io.circe.{Decoder, Encoder}
import io.circe.generic.auto.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint

private final class CreateBoUserWithAuthEp[F[_]: Async] private (
    jobHandler: JobHandler[F],
    authService: AuthService[F],
) extends ThalesEntryPoint[F]:
  private val InvalidParametersApiError: ApiError =
    ApiError("INVALID_PARAMETERS", "[(param1, error1), ..., (paramN, errorN)]")

  private val DuplicateLoginNameApiError: ApiError =
    ApiError("LOGIN_ALREADY_EXISTS", "The given loginName 'someLoginName' was already present in the database.")

  private val BadPasswordApiError: ApiError =
    ApiError("INVALID_PASSWORD", "[\"error1\", \"error2\"]")

  private enum CreateBoUserWithAuthEpError:
    case UnauthenticatedError(error: ApiError)
    case UnauthorizedError(error: ApiError)
    case InvalidParametersError(error: ApiError)
    case DuplicateLoginNameError(error: ApiError)
    case BadPasswordError(error: ApiError)
  end CreateBoUserWithAuthEpError

  private val createBoUserWithAuthErrorOut: EndpointOutput[CreateBoUserWithAuthEpError] =
    oneOf(
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.Unauthorized)
          .and(jsonBody[ApiError].example(EndPointUtils.UnauthenticatedApiError))
          .mapTo[CreateBoUserWithAuthEpError.UnauthenticatedError],
      ),
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.Forbidden)
          .and(jsonBody[ApiError].example(EndPointUtils.UnauthorizedApiError))
          .mapTo[CreateBoUserWithAuthEpError.UnauthorizedError],
      ),
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.BadRequest)
          .and(jsonBody[ApiError].example(InvalidParametersApiError))
          .mapTo[CreateBoUserWithAuthEpError.InvalidParametersError],
      ),
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.Conflict)
          .and(jsonBody[ApiError].example(DuplicateLoginNameApiError))
          .mapTo[CreateBoUserWithAuthEpError.DuplicateLoginNameError],
      ),
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.BadRequest)
          .and(jsonBody[ApiError].example(BadPasswordApiError))
          .mapTo[CreateBoUserWithAuthEpError.BadPasswordError],
      ),
    )
  end createBoUserWithAuthErrorOut

  private def strToAuthenticationError(str: String): CreateBoUserWithAuthEpError =
    CreateBoUserWithAuthEpError.UnauthenticatedError(ApiError(EndPointUtils.UnauthenticatedApiError.errorCode, str))
  end strToAuthenticationError

  override val getEntryPoint: ServerEndpoint[Any, F] =
    endpoint
      .errorOut(createBoUserWithAuthErrorOut)
      .in("api")
      .securityIn(auth.bearer[String]())
      .serverSecurityLogic(EndPointUtils.authenticate(authService, strToAuthenticationError, _))
      .post
      .in("createBoUser")
      .in(jsonBody[AppModel.BoUser])
      .out(jsonBody[CreateBoUserWithAuthEpResponse])
      .serverLogic(createBoUserWithAuth)

  private final case class CreateBoUserWithAuthEpResponse(userId: Long)

  private type ReturnTypeForLogicFunction = Either[CreateBoUserWithAuthEpError, CreateBoUserWithAuthEpResponse]

  private def doInvalidParams(invalidParams: NonEmptyVector[(String, String)]): ReturnTypeForLogicFunction =
    Left(
      CreateBoUserWithAuthEpError.InvalidParametersError(
        ApiError(
          InvalidParametersApiError.errorCode,
          invalidParams.view.mkString("[\"", "\", \"", "\"]"),
        ),
      ),
    )
  end doInvalidParams

  private val doDuplicateBoUserName: ReturnTypeForLogicFunction =
    Left(CreateBoUserWithAuthEpError.DuplicateLoginNameError(DuplicateLoginNameApiError))
  end doDuplicateBoUserName

  private def doBadPassword(value: NonEmptyVector[String]): ReturnTypeForLogicFunction =
    Left(
      CreateBoUserWithAuthEpError.BadPasswordError(
        ApiError(BadPasswordApiError.errorCode, value.view.mkString("[\"", "\", \"", "\"]")),
      ),
    )
  end doBadPassword

  private val unauthorizedError: Either[CreateBoUserWithAuthEpError, CreateBoUserWithAuthEpResponse] =
    Left(CreateBoUserWithAuthEpError.UnauthorizedError(EndPointUtils.UnauthorizedApiError))
  end unauthorizedError

  private def createBoUserWithAuth(authenticatedBoUser: AuthenticatedBoUser)(
      boUser: AppModel.BoUser,
  ): F[ReturnTypeForLogicFunction] =
    jobHandler.jobHandlerWithAuth[CreateBoUserResult, CreateBoUserWithAuthEpError, CreateBoUserWithAuthEpResponse](
      authenticatedBoUser,
      CreateBoUserPermissionsAlg,
      CreateBoUserRequest(boUser),
      { case CreateBoUserResult(res) =>
        res match {
          case Left(CreateBoUserError.InvalidParameters(invalidParams)) => doInvalidParams(invalidParams)
          case Left(CreateBoUserError.DuplicateLoginName(loginName)) => doDuplicateBoUserName
          case Left(CreateBoUserError.BadPassword(errorList)) => doBadPassword(errorList)
          case Right(userId) => Right(CreateBoUserWithAuthEpResponse(userId))
        }
      },
      unauthorizedError,
    )
  end createBoUserWithAuth

  private val CreateBoUserPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanCreateBoUsers).compile
  end CreateBoUserPermissionsAlg
end CreateBoUserWithAuthEp

object CreateBoUserWithAuthEp:
  def create[F[_]: Async](jobHandler: JobHandler[F], authService: AuthService[F]): ThalesEntryPoint[F] =
    CreateBoUserWithAuthEp[F](jobHandler, authService)
  end create
end CreateBoUserWithAuthEp
