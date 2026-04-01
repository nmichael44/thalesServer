package app.workerTasks

import cats.data.EitherT
import cats.effect.Async

import app.JobSpecs.{DeleteRoleByIdError, JobKind, JobResult}
import app.entrypoints.smithy.RoleId
import app.services.RepositoryService
import app.workerTasks.WorkerTask
import doobie.{ConnectionIO, Transactor}
import doobie.implicits.*

private final class DeleteRoleById[F[_]: Async] private (
    repoService: RepositoryService,
    xa: Transactor[F],
    wu: WorkerTaskUtils[F],
) extends WorkerTask[F]:
  private val logDeletingRole: EitherT[F, Nothing, Unit] = wu.logT("Deleting role.")

  private def deleteRoleDbProgram(roleId: RoleId): EitherT[ConnectionIO, DeleteRoleByIdError, Unit] =
    for
      isRoleAssignedToUsers <- EitherT.liftF(repoService.isRoleAssignedToUsers(roleId))
      _ <- wu.failIfC(isRoleAssignedToUsers, DeleteRoleByIdError.RoleHasAssociatedUsers)
      cnt <- EitherT.liftF(repoService.deleteRoleById(roleId))
      _ <- wu.failIfC[DeleteRoleByIdError](cnt != 1, DeleteRoleByIdError.NoSuchRoleId)
    yield ()
  end deleteRoleDbProgram

  private def deleteRole(j: JobKind.DeleteRoleByIdRequest): F[JobResult] =
    val roleId = j.roleId

    val res: EitherT[F, DeleteRoleByIdError, Unit] =
      for
        _ <- logDeletingRole
        _ <- deleteRoleDbProgram(roleId).transact(xa)
      yield ()

    wu.toResult(res, JobResult.DeleteRoleByIdResult.apply)
  end deleteRole

  override def work(job: JobKind): F[JobResult] =
    deleteRole(job.asInstanceOf[JobKind.DeleteRoleByIdRequest])
  end work
end DeleteRoleById

object DeleteRoleById:
  def create[F[_]: Async](repoService: RepositoryService, xa: Transactor[F], wu: WorkerTaskUtils[F]): WorkerTask[F] =
    DeleteRoleById[F](repoService, xa, wu)
  end create
end DeleteRoleById
