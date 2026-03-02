package app.workerTasks

import cats.effect.Async
import cats.syntax.all.*

import app.JobSpecs.{JobKind, JobResult}
import app.entrypoints.smithy.{PermissionInDb, RoleId, UserInDb}
import app.services.RepositoryService
import doobie.{ConnectionIO, Transactor}
import doobie.implicits.*

private final class FetchRolesPermissionsById[F[_]: Async] private (
    repoService: RepositoryService,
    xa: Transactor[F],
    wu: WorkerTaskUtils[F],
) extends WorkerTask[F]:
  private def fetchRolesPermissionsById(j: JobKind.FetchRolesPermissionsByIdRequest): F[JobResult] =
    val roleIds = j.roleIds

    val dbProgram: ConnectionIO[Map[RoleId, Vector[PermissionInDb]]] =
      repoService.fetchRolesPermissionsById(roleIds)

    for
      _ <- wu.logi(s"Fetching role permissions for the given roleIds: $roleIds")
      res <- dbProgram.transact(xa)
    yield JobResult.FetchRolesPermissionsByIdResult(res)
  end fetchRolesPermissionsById

  override def work(job: JobKind): F[JobResult] =
    fetchRolesPermissionsById(job.asInstanceOf[JobKind.FetchRolesPermissionsByIdRequest])
  end work
end FetchRolesPermissionsById

object FetchRolesPermissionsById:
  def create[F[_]: Async](repoService: RepositoryService, xa: Transactor[F], wu: WorkerTaskUtils[F]): WorkerTask[F] =
    FetchRolesPermissionsById[F](repoService, xa, wu)
  end create
end FetchRolesPermissionsById
