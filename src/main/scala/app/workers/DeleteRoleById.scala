package app.workers

import cats.data.EitherT
import cats.effect.Async

import app.JobSpecs.{DeleteRoleByIdError, JobKind, JobResult}
import app.ThalesUtils.ExtensionMethodUtils.*
import app.entrypoints.smithy.RoleId
import app.services.RepositoryService
import app.workers.HttpWorkerTask
import doobie.{ConnectionIO, Transactor}
import doobie.implicits.*

private final class DeleteRoleById[F[_]: Async] private (repoService: RepositoryService, xa: Transactor[F], wu: WorkerUtils[F])
    extends HttpWorkerTask[F]:
  private val logDeletingRole: EitherT[F, Nothing, Unit] = wu.logT("Deleting role.")

  private def deleteRoleDbProgram(roleId: RoleId): EitherT[ConnectionIO, DeleteRoleByIdError, Unit] =
    for {
      isRoleAssignedToUsers <- repoService.isRoleAssignedToUsers(roleId).liftE
      _ <- wu.failIfC(isRoleAssignedToUsers, DeleteRoleByIdError.RoleHasAssociatedUsers)
      cnt <- repoService.deleteRoleById(roleId).liftE
      _ <- wu.failIfC(cnt != 1, DeleteRoleByIdError.NoSuchRoleId)
    } yield ()
  end deleteRoleDbProgram

  private def deleteRole(j: JobKind.DeleteRoleByIdRequest): F[JobResult] =
    val roleId = j.roleId

    val res: EitherT[F, DeleteRoleByIdError, Unit] = for {
      _ <- logDeletingRole
      _ <- deleteRoleDbProgram(roleId).transact(xa)
    } yield ()

    wu.toResult(res, JobResult.DeleteRoleByIdResult.apply)
  end deleteRole

  override def work(job: JobKind): F[JobResult] =
    deleteRole(job.asInstanceOf[JobKind.DeleteRoleByIdRequest])
  end work
end DeleteRoleById

object DeleteRoleById:
  def create[F[_]: Async](repoService: RepositoryService, xa: Transactor[F], wu: WorkerUtils[F]): HttpWorkerTask[F] =
    DeleteRoleById[F](repoService, xa, wu)
  end create
end DeleteRoleById
