package app.workerTasks

import cats.data.{EitherT, NonEmptyVector}
import cats.effect.Async

import java.time.Instant

import app.JobSpecs.{JobKind, JobResult, ResetUserPasswordError}
import app.ThalesUtils.ExtensionMethodUtils.liftE
import app.ThalesUtils.GenUtils as U
import app.entrypoints.smithy.{HashedResetPasswordToken, HashedUserPassword}
import app.services.{PasswordHasherService, RepositoryService}
import doobie.{ConnectionIO, Transactor}
import doobie.implicits.*

private final class ResetUserPassword[F[_]: Async] private (
    repoService: RepositoryService,
    xa: Transactor[F],
    passwordHasherService: PasswordHasherService[F],
    wu: WorkerTaskUtils[F],
) extends WorkerTask[F]:
  private def resetUserPasswordDbProgram(
      hashedToken: HashedResetPasswordToken,
      hashedPassword: HashedUserPassword,
      now: Instant,
  ): EitherT[ConnectionIO, ResetUserPasswordError, Unit] =
    for
      (userId, expiry) <- EitherT.fromOptionF(
        repoService.getResetUserPasswordTokenExpiry(hashedToken),
        ResetUserPasswordError.InvalidToken,
      )
      userInDb <- EitherT.liftF(repoService.fetchUsersByUserIds(NonEmptyVector.one(userId)).map(_(userId)))
      _ <- wu.failIfC(expiry.isBefore(now), ResetUserPasswordError.InvalidToken)
      _ <- wu.failIfC(!userInDb.enabled, ResetUserPasswordError.UserNotEnabled)
      cnt <- EitherT.liftF(repoService.updateUserPasswordInDb(userId, hashedPassword))
      _ <- wu.failIfC(
        cnt != 1,
        ResetUserPasswordError.FailedToUpdateUserRow(s"Expected 1 row to be updated, but in fact updated $cnt."),
      )
      _ <- EitherT.liftF(repoService.deleteResetUserPasswordToken(hashedToken))
    yield ()
  end resetUserPasswordDbProgram

  private def resetUserPassword(j: JobKind.ResetUserPasswordRequest): F[JobResult] =
    val (resetUserPasswordToken, newPassword) = (j.token, j.newPassword)
    val hashedToken: HashedResetPasswordToken =
      HashedResetPasswordToken(U.hashStringUrlEncoded(resetUserPasswordToken.value))

    val program: EitherT[F, ResetUserPasswordError, Unit] =
      for
        _ <- wu.logCheckingValidityOfNewPassword
        _ <- wu.validatePassword(newPassword, ResetUserPasswordError.NewPasswordIsInvalid.apply)
        _ <- wu.logComputingHashAndUpdatingDb
        hashedPassword <- EitherT.liftF(passwordHasherService.hashPassword(newPassword))
        now <- wu.getNow
        _ <- wu.logFetchingUserFromDb
        _ <- resetUserPasswordDbProgram(hashedToken, hashedPassword, now).transact(xa)
      yield ()

    wu.toResult(program, JobResult.ResetUserPasswordResult.apply)
  end resetUserPassword

  override def work(job: JobKind): F[JobResult] =
    resetUserPassword(job.asInstanceOf[JobKind.ResetUserPasswordRequest])
  end work
end ResetUserPassword

object ResetUserPassword:
  def create[F[_]: Async](
      repoService: RepositoryService,
      xa: Transactor[F],
      passwordHasherService: PasswordHasherService[F],
      wu: WorkerTaskUtils[F],
  ): WorkerTask[F] =
    ResetUserPassword(repoService, xa, passwordHasherService, wu)
  end create
end ResetUserPassword
