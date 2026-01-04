package app.entrypoints

import cats.effect.Async
import cats.implicits.*

import app.JobSpecs.{JobKind, JobResult, LoginError}
import app.JobSpecs.JobResult.LoginResult
import app.ThalesUtils.TimeUtils
import app.entrypoints.smithy.{LoginName, LoginOutput, LoginServices, PasswordResetRequired, Unauthenticated, UserId, UserNotEnabled, UserPassword}
import app.services.{ClockService, ServerState}

private final class LoginServicesSmithyEp[F[_]: Async as async] private (
    jobHandler: JobHandler[F],
    serverState: ServerState[F],
    clockService: ClockService[F],
) extends LoginServices[F]:
  private def updateLastAccess(userId: UserId): F[Unit] =
    clockService.nowInstant >>= { now => serverState.lastAccess.update(m => m.updated(userId, now)) }
  end updateLastAccess

  private val loginErrorToResponse: Map[LoginError, F[LoginOutput]] =
    def raise[A](e: Throwable): F[A] = async.raiseError(e)

    val invalidLoginPasswordF: F[LoginOutput] =
      raise(Unauthenticated("Invalid loginName/password specified."))
    val userNotEnabledF: F[LoginOutput] =
      raise(UserNotEnabled("The user cannot login because she is not enabled."))
    val userMustResetPasswordF: F[LoginOutput] =
      raise(PasswordResetRequired("The user must reset her password before logging in."))

    Map(
      LoginError.InvalidLoginPassword  -> invalidLoginPasswordF,
      LoginError.UserNotEnabled        -> userNotEnabledF,
      LoginError.UserMustResetPassword -> userMustResetPasswordF,
    )
  end loginErrorToResponse

  private def resultToResponse(jr: JobResult): F[LoginOutput] =
    jr match {
      case LoginResult(res) =>
        res.fold(loginErrorToResponse.apply, (userId, token) => updateLastAccess(userId) *> async.pure(LoginOutput(token)))
      case _ => async.raiseError(IllegalArgumentException(s"Unexpected JobResult: $jr"))
    }
  end resultToResponse

  override def login(loginName: LoginName, password: UserPassword): F[LoginOutput] =
    val req = JobKind.LoginRequest(loginName, password)

    jobHandler.jobHandlerNoAuthF2(req, resultToResponse)
  end login
end LoginServicesSmithyEp

object LoginServicesSmithyEp:
  def create[F[_]: Async](
      jobHandler: JobHandler[F],
      serverState: ServerState[F],
      clockService: ClockService[F],
  ): LoginServices[F] =
    LoginServicesSmithyEp[F](jobHandler, serverState, clockService)
  end create
end LoginServicesSmithyEp
