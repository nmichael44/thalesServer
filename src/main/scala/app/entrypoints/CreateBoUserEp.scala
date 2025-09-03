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

private final class CreateBoUserEp[F[_]: Async] private (
    jobHandler: JobHandler[F],
    authService: AuthService[F],
) extends ThalesEntryPoint[F]:
  private val InvalidParametersApiError: ApiError =
    ApiError("INVALID_PARAMETERS", "[(param1, error1), ..., (paramN, errorN)]")

  private val DuplicateLoginNameApiError: ApiError =
    ApiError("LOGIN_ALREADY_EXISTS", "The given loginName 'someLoginName' was already present in the database.")

  private val BadPasswordApiError: ApiError =
    ApiError("INVALID_PASSWORD", "[\"error1\", \"error2\"]")

  private val createBoUserWithAuthErrorOut: EndpointOutput[ApiError] =
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
  end createBoUserWithAuthErrorOut

  private val strToAuthenticationError: String => ApiError = ApiError(EndPointUtils.UnauthenticatedApiError.errorCode, _)

  private final case class CreateBoUserWithAuthEpResponse(userId: Long)

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

  private type ReturnTypeForLogicFunction = Either[ApiError, CreateBoUserWithAuthEpResponse]

  private def doInvalidParams(invalidParams: NonEmptyVector[(String, String)]): ReturnTypeForLogicFunction =
    Left(ApiError(InvalidParametersApiError.errorCode, invalidParams.view.mkString("[\"", "\", \"", "\"]")))
  end doInvalidParams

  private val doDuplicateBoUserName: ReturnTypeForLogicFunction = Left(DuplicateLoginNameApiError)

  private def doBadPassword(value: NonEmptyVector[String]): ReturnTypeForLogicFunction =
    Left(ApiError(BadPasswordApiError.errorCode, value.view.mkString("[\"", "\", \"", "\"]")))
  end doBadPassword

  private val unauthorizedError: Either[ApiError, CreateBoUserWithAuthEpResponse] = Left(EndPointUtils.UnauthorizedApiError)

  private def createBoUserWithAuth(authenticatedBoUser: AuthenticatedBoUser)(
      boUser: AppModel.BoUser,
  ): F[ReturnTypeForLogicFunction] =
    jobHandler.jobHandlerWithAuth[CreateBoUserResult, ApiError, CreateBoUserWithAuthEpResponse](
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
end CreateBoUserEp

object CreateBoUserEp:
  def create[F[_]: Async](jobHandler: JobHandler[F], authService: AuthService[F]): ThalesEntryPoint[F] =
    CreateBoUserEp[F](jobHandler, authService)
  end create
end CreateBoUserEp
