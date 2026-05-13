package app.workerTasks

import cats.data.{EitherT, NonEmptyVector}
import cats.effect.MonadCancelThrow

import app.JobSpecs.{JobKind, JobResult, ResetMyPasswordError}
import app.ThalesUtils.ExtensionMethodUtils.liftE
import app.entrypoints.smithy.{HashedUserPassword, UserId}
import app.services.{PasswordHasherService, RepositoryService}
import doobie.{ConnectionIO, Transactor}
import doobie.implicits.*

private final class ResetMyPassword[F[_]: MonadCancelThrow] private (
    repoService: RepositoryService,
    xa: Transactor[F],
    passwordHasherService: PasswordHasherService[F],
    wu: WorkerTaskUtils[F],
) extends WorkerTask[F, JobKind.ResetMyPasswordRequest]:
  private val logFetchingUserFromDb: EitherT[F, Nothing, Unit] =
    wu.logT("Fetching user and checking enable status. Writing new password.")
  end logFetchingUserFromDb

  private val logCheckingValidityOfNewPassword: EitherT[F, Nothing, Unit] =
    wu.logT("Checking validity of new password...")
  end logCheckingValidityOfNewPassword

  private val logComputingHashAndUpdatingDb: EitherT[F, Nothing, Unit] =
    wu.logT("Password is valid. Computing hash and updating db.")
  end logComputingHashAndUpdatingDb

  private def resetMyPasswordDbProgram(
      hashedPassword: HashedUserPassword,
      userId: UserId,
  ): EitherT[ConnectionIO, ResetMyPasswordError, Unit] =
    val userIdsVec = NonEmptyVector.one(userId)

    for
      userInDb <- EitherT.fromOptionF(
        repoService.fetchUsersByUserIds(userIdsVec).map(_.get(userId)),
        ResetMyPasswordError.FailedToUpdateUserRow(s"User (${userId.value}) not found."),
      )
      _ <- wu.failIfC(!userInDb.enabled, ResetMyPasswordError.UserNotEnabled)
      cnt <- repoService.updateUserPasswordInDb(userId, hashedPassword).liftE
      _ <- wu.failIfC(
        cnt != 1,
        ResetMyPasswordError.FailedToUpdateUserRow(s"Expected 1 row to be updated, but in fact updated $cnt."),
      )
    yield ()
  end resetMyPasswordDbProgram

  private def resetMyPassword(j: JobKind.ResetMyPasswordRequest): F[JobResult] =
    val (userId, newPassword) = (j.authUser.userId, j.newPassword)

    val program: EitherT[F, ResetMyPasswordError, Unit] =
      for
        _ <- logCheckingValidityOfNewPassword
        _ <- wu.validatePassword(newPassword, ResetMyPasswordError.NewPasswordIsInvalid.apply)
        _ <- logComputingHashAndUpdatingDb
        hashedPassword <- passwordHasherService.hashPassword(newPassword).liftE
        _ <- logFetchingUserFromDb
        _ <- resetMyPasswordDbProgram(hashedPassword, userId).transact(xa)
      yield ()

    wu.toResult(program, JobResult.ResetMyPasswordResult.apply)
  end resetMyPassword

  override def work(job: JobKind.ResetMyPasswordRequest): F[JobResult] =
    resetMyPassword(job)
  end work
end ResetMyPassword

object ResetMyPassword:
  def create[F[_]: MonadCancelThrow](
      repoService: RepositoryService,
      xa: Transactor[F],
      passwordHasherService: PasswordHasherService[F],
      wu: WorkerTaskUtils[F],
  ): WorkerTask[F, JobKind.ResetMyPasswordRequest] =
    ResetMyPassword[F](repoService, xa, passwordHasherService, wu)
  end create
end ResetMyPassword
