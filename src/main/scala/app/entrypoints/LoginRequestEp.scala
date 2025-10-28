package app.entrypoints

import cats.effect.Async
import cats.implicits.*

import app.entrypoints.EndPointUtils.ApiError
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

private final class LoginRequestEp[F[_]: Async as async] private (jobHandler: JobHandler[F], serverState: ServerState[F])
    extends ThalesEntryPoint[F]:
  private val InvalidLoginApiError: ApiError =
    ApiError("INVALID_LOGINNAME_PASSWORD", "Invalid loginName/password specified.")
  end InvalidLoginApiError

  private val UserNotEnabledApiError: ApiError =
    ApiError("USER_IS_NOT_ENABLED", "The user cannot login because she is not enabled.")
  end UserNotEnabledApiError

  private val MustResetPasswordApiError: ApiError =
    ApiError("PASSWORD_RESET_REQUIRED", "The user must reset her password before logging in.")
  end MustResetPasswordApiError

  private val loginErrorOut: EndpointOutput[ApiError] =
    oneOf(
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.Unauthorized)
          .and(jsonBody[ApiError].example(InvalidLoginApiError)),
      ),
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.Locked)
          .and(jsonBody[ApiError].example(UserNotEnabledApiError)),
      ),
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.Forbidden)
          .and(jsonBody[ApiError].example(MustResetPasswordApiError)),
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
  end getEntryPoint

  private def updateLastAccess(userId: Long) = TimeUtils.nowInstant >>= { now =>
    serverState.lastAccess.update(_ + (userId -> now))
  }
  end updateLastAccess

  private val invalidLoginPasswordF: F[Either[ApiError, LoginRequestEpResponse]] = async.pure(Left(InvalidLoginApiError))

  private val userNotEnabledF: F[Either[ApiError, LoginRequestEpResponse]] = async.pure(Left(UserNotEnabledApiError))

  private val userMustResetPasswordF: F[Either[ApiError, LoginRequestEpResponse]] = async.pure(Left(MustResetPasswordApiError))

  private def login(loginUserDetails: LoginUserDetails): F[Either[ApiError, LoginRequestEpResponse]] =
    jobHandler.jobHandlerNoAuthF[LoginResult, ApiError, LoginRequestEpResponse](
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

object LoginRequestEp:
  def create[F[_]: Async](jobHandler: JobHandler[F], serverState: ServerState[F]): ThalesEntryPoint[F] =
    LoginRequestEp[F](jobHandler, serverState)
  end create
end LoginRequestEp
