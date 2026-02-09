package app.entrypoints

import cats.effect.Async
import cats.implicits.*

import app.JobSpecs.{JobKind, JobResult, LoginError, ResetUserPasswordError}
import app.JobSpecs.JobKind.CheckResetUserPasswordTokenRequest
import app.JobSpecs.JobResult.{LoginResult, ResetUserPasswordResult}
import app.ThalesUtils.GenUtils as U
import app.entrypoints.smithy.{LoginName, LoginOutput, LoginServices, ResetPasswordToken, TooManyLoginAttempts, UserId, UserPassword}
import app.services.{ClockService, ServerState}

private final class LoginServicesSmithyEp[F[_]: Async as async] private (
    jobHandler: JobHandler[F],
    serverState: ServerState[F],
    clockService: ClockService[F],
    epErrors: EntryPointErrors[F],
) extends LoginServices[F]:
  private def updateLastAccess(userId: UserId): F[Unit] =
    clockService.nowInstant >>= { now => serverState.lastAccess.update(m => m.updated(userId, now)) }
  end updateLastAccess

  private val loginErrorToResponse: Map[LoginError, F[LoginOutput]] =
    import U.->

    Map(
      LoginError.InvalidLoginPassword  -> epErrors.invalidUserNameOrPassword,
      LoginError.UserNotEnabled        -> epErrors.userIsDisabled,
      LoginError.UserMustResetPassword -> epErrors.userMustResetPassword,
      LoginError.TooManyLoginAttempts  -> epErrors.tooManyLoginAttempts,
    )
  end loginErrorToResponse

  private def resultToResponse(jr: JobResult): F[LoginOutput] =
    jr match
      case LoginResult(res) =>
        res.fold(loginErrorToResponse.apply, (userId, token) => updateLastAccess(userId) *> async.pure(LoginOutput(token)))
      case _ => async.raiseError(IllegalArgumentException(s"Unexpected JobResult: $jr"))
  end resultToResponse

  override def login(loginName: LoginName, password: UserPassword): F[LoginOutput] =
    val req = JobKind.LoginRequest(loginName, password)

    jobHandler.jobHandlerNoAuthF(req, resultToResponse)
  end login

  override def resetUserPassword(token: ResetPasswordToken, newPassword: UserPassword): F[Unit] =
    def resultToResponse(jobResult: JobResult): F[Unit] =
      jobResult match
        case ResetUserPasswordResult(res) =>
          res.fold(
            {
              case ResetUserPasswordError.InvalidToken => epErrors.invalidOrMissingResetPasswordToken
              case ResetUserPasswordError.NewPasswordIsInvalid(reasons) => epErrors.usersPasswordIsInvalid(reasons)
              case ResetUserPasswordError.UserNotEnabled => epErrors.userIsDisabled
              case ResetUserPasswordError.FailedToUpdateUserRow(errStr) => epErrors.internalServerError(errStr)
            },
            async.pure,
          )
        case _ =>
          epErrors.internalServerError("ResetUserPassword: Bad pattern match for result.")
    end resultToResponse

    jobHandler.jobHandlerNoAuthF(CheckResetUserPasswordTokenRequest(token), resultToResponse)
  end resetUserPassword
end LoginServicesSmithyEp

object LoginServicesSmithyEp:
  def create[F[_]: Async](
      jobHandler: JobHandler[F],
      serverState: ServerState[F],
      clockService: ClockService[F],
      epErrors: EntryPointErrors[F],
  ): LoginServices[F] =
    LoginServicesSmithyEp[F](jobHandler, serverState, clockService, epErrors)
  end create
end LoginServicesSmithyEp
