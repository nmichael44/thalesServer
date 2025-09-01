package app.entrypoints

import cats.effect.Async
import cats.implicits.*

import app.entrypoints.EndPointsBases.{ApiError, EndPointErrorResult}
import app.entrypoints.ThalesEntryPoint
import app.model.AppModel
import app.model.AppModel.LoginUserDetails
import app.services.ServerState
import app.JobSpecs.JobKind.LoginRequest
import app.JobSpecs.JobResult.LoginResult
import app.JobSpecs.LoginError
import app.ThalesUtils.TimeUtils
import io.circe.*
import io.circe.{Decoder, Encoder}
import io.circe.generic.auto.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.typelevel.ErasureSameAsType

final class LoginRequestEp[F[_]: Async as async](jobHandler: JobHandler[F], serverState: ServerState[F])
    extends ThalesEntryPoint[F]:
  private val InvalidLoginApiError: ApiError = ApiError("INVALID_LOGINNAME_PASSWORD", "Invalid loginName/password specified.")

  private val UserNotEnabledApiError: ApiError =
    ApiError("USER_IS_NOT_ENABLED", "The user cannot login because she is not enabled.")

  private val MustResetPasswordApiError: ApiError =
    ApiError("PASSWORD_RESET_REQUIRED", "The user must reset her password before logging in.")

  private enum LoginRequestError:
    case InvalidLoginPasswordError(error: ApiError)
    case UserNotEnabledError(error: ApiError)
    case UserMustResetPasswordError(error: ApiError)
  end LoginRequestError

  private val loginErrorOut: EndpointOutput[LoginRequestError] =
    oneOf[LoginRequestError](
      oneOfVariant(
        StatusCodeUtils
          .statusCodeWithDescription(StatusCode.Unauthorized)
          .and(jsonBody[ApiError].example(InvalidLoginApiError))
          .mapTo[LoginRequestError.InvalidLoginPasswordError],
      ),
      oneOfVariant(
        StatusCodeUtils
          .statusCodeWithDescription(StatusCode.Locked)
          .and(jsonBody[ApiError].example(UserNotEnabledApiError))
          .mapTo[LoginRequestError.UserNotEnabledError],
      ),
      oneOfVariant(
        StatusCodeUtils
          .statusCodeWithDescription(StatusCode.Forbidden)
          .and(jsonBody[ApiError].example(MustResetPasswordApiError))
          .mapTo[LoginRequestError.UserMustResetPasswordError],
      ),
    )
  end loginErrorOut

  private final case class LoginRequestEpResponse(token: String)

  override val getEntryPoint: ServerEndpoint[Any, F] =
    endpoint.post
      .errorOut(loginErrorOut)
      .in("login")
      .in(jsonBody[LoginUserDetails])
      .out(jsonBody[LoginRequestEpResponse])
      .description("Login into the system using loginName and password and receive a jwt token on success.")
      .serverLogic(login)

  private def updateLastAccess(userId: Long) = TimeUtils.nowInstant >>= { now =>
    serverState.lastAccess.update(_ + (userId -> now))
  }

  private val invalidLoginPasswordF: F[Either[LoginRequestError, LoginRequestEpResponse]] =
    async.pure(Left(LoginRequestError.InvalidLoginPasswordError(InvalidLoginApiError)))
  end invalidLoginPasswordF

  private val userNotEnabledF: F[Either[LoginRequestError, LoginRequestEpResponse]] =
    async.pure(Left(LoginRequestError.UserNotEnabledError(UserNotEnabledApiError)))
  end userNotEnabledF

  private val userMustResetPasswordF: F[Either[LoginRequestError, LoginRequestEpResponse]] =
    async.pure(Left(LoginRequestError.UserMustResetPasswordError(MustResetPasswordApiError)))
  end userMustResetPasswordF

  private def login(loginUserDetails: LoginUserDetails): F[Either[LoginRequestError, LoginRequestEpResponse]] =
    jobHandler.jobHandlerNoAuthF[LoginResult, LoginRequestError, LoginRequestEpResponse](
      LoginRequest(loginUserDetails),
      { case LoginResult(res) =>
        res match {
          case Left(LoginError.InvalidLoginPassword()) => invalidLoginPasswordF
          case Left(LoginError.UserNotEnabled()) => userNotEnabledF
          case Left(LoginError.UserMustResetPassword()) => userMustResetPasswordF
          case Right((userId, token)) => updateLastAccess(userId).as(Right(LoginRequestEpResponse(token)))
        }
      },
    )
  end login
end LoginRequestEp
