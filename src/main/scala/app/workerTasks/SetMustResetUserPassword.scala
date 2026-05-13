package app.workerTasks

import cats.data.EitherT
import cats.effect.MonadCancelThrow

import app.JobSpecs.{JobKind, JobResult, SetMustResetUserPasswordError}
import app.ThalesUtils.ExtensionMethodUtils.*
import app.entrypoints.smithy.UserId
import app.services.RepositoryService
import doobie.{ConnectionIO, Transactor}
import doobie.implicits.*

private final class SetMustResetUserPassword[F[_]: MonadCancelThrow] private (
    repoService: RepositoryService,
    xa: Transactor[F],
    wu: WorkerTaskUtils[F],
) extends WorkerTask[F, JobKind.SetMustResetUserPasswordRequest]:
  private val logSettingFlag: EitherT[F, Nothing, Unit] = wu.logT("Setting MustResetPassword flag.")

  private def setMustResetUserPasswordDbProgram(
      userId: UserId,
      mustResetPassword: Boolean,
  ): EitherT[ConnectionIO, SetMustResetUserPasswordError, Unit] =
    for
      cnt <- repoService.setMustResetUserPassword(userId, mustResetPassword).liftE
      _ <- wu.failIfC[SetMustResetUserPasswordError](cnt != 1, SetMustResetUserPasswordError.UserNotFound)
    yield ()
  end setMustResetUserPasswordDbProgram

  private def setMustResetUserPassword(j: JobKind.SetMustResetUserPasswordRequest): F[JobResult] =
    val (userId, mustResetPassword) = (j.userId, j.mustResetPassword)

    val res: EitherT[F, SetMustResetUserPasswordError, Unit] =
      for
        _ <- logSettingFlag
        _ <- setMustResetUserPasswordDbProgram(userId, mustResetPassword).transact(xa)
      yield ()

    wu.toResult(res, JobResult.SetMustResetUserPasswordResult.apply)
  end setMustResetUserPassword

  override def work(job: JobKind.SetMustResetUserPasswordRequest): F[JobResult] =
    setMustResetUserPassword(job)
  end work
end SetMustResetUserPassword

object SetMustResetUserPassword:
  def create[F[_]: MonadCancelThrow](
      repoService: RepositoryService,
      xa: Transactor[F],
      wu: WorkerTaskUtils[F],
  ): WorkerTask[F, JobKind.SetMustResetUserPasswordRequest] =
    SetMustResetUserPassword[F](repoService, xa, wu)
  end create
end SetMustResetUserPassword
