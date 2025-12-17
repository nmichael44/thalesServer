package app.entrypoints

import cats.effect.Async
import cats.implicits.*

import app.entrypoints.smithy.InvalidLoginPassword
import app.entrypoints.smithy.LoginResponse
import app.entrypoints.smithy.LoginService
import app.entrypoints.smithy.PasswordResetRequired
import app.entrypoints.smithy.UserNotEnabled
import app.model.AppModel.LoginUserDetails
import app.services.ServerState
import app.JobSpecs.{JobKind, JobResult, LoginError}
import app.JobSpecs.JobResult.LoginResult
import app.ThalesUtils.TimeUtils

private final class LoginRequestSmithyEp[F[_]: Async as async] private (jobHandler: JobHandler[F], serverState: ServerState[F])
    extends LoginService[F]:
  private def updateLastAccess(userId: Long): F[Unit] =
    TimeUtils.nowInstant >>= { now => serverState.lastAccess.update(_ + (userId -> now)) }
  end updateLastAccess

  private val LoginErrorToResponse: Map[LoginError, F[LoginResponse]] =
    def raise[A](e: Throwable): F[A] = async.raiseError(e)

    val invalidLoginPasswordF: F[LoginResponse] =
      raise(InvalidLoginPassword("Invalid loginName/password specified."))
    val userNotEnabledF: F[LoginResponse] =
      raise(UserNotEnabled("The user cannot login because she is not enabled."))
    val userMustResetPasswordF: F[LoginResponse] =
      raise(PasswordResetRequired("The user must reset her password before logging in."))

    Map(
      LoginError.InvalidLoginPassword  -> invalidLoginPasswordF,
      LoginError.UserNotEnabled        -> userNotEnabledF,
      LoginError.UserMustResetPassword -> userMustResetPasswordF,
    )
  end LoginErrorToResponse

  private def resultToResponse(jr: JobResult): F[LoginResponse] =
    jr match {
      case LoginResult(res) =>
        res.fold(LoginErrorToResponse.apply, (userId, token) => updateLastAccess(userId) *> async.pure(LoginResponse(token)))
      case _ => async.raiseError(IllegalArgumentException(s"Unexpected JobResult: $jr"))
    }
  end resultToResponse

  override def login(loginName: String, password: String): F[LoginResponse] =
    val loginUserDetails = LoginUserDetails(loginName, password)
    val req = JobKind.LoginRequest(loginUserDetails)

    jobHandler.jobHandlerNoAuthF2(req, resultToResponse)
  end login
end LoginRequestSmithyEp

object LoginRequestSmithyEp:
  def create[F[_]: Async](jobHandler: JobHandler[F], serverState: ServerState[F]): LoginService[F] =
    LoginRequestSmithyEp[F](jobHandler, serverState)
  end create
end LoginRequestSmithyEp
