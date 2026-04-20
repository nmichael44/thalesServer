package app.workerTasks

import cats.data.NonEmptyVector
import cats.effect.Async
import cats.syntax.all.*

import app.JobSpecs.{FetchRolesPermissionsByIdError, JobKind, JobResult}
import app.entrypoints.smithy.{PermissionInDb, RoleId, RoleInDb}
import app.services.RepositoryService
import doobie.Transactor
import doobie.implicits.*

private final class FetchRolesPermissionsById[F[_]: Async as async] private (
    repoService: RepositoryService,
    xa: Transactor[F],
    wu: WorkerTaskUtils[F],
) extends WorkerTask[F]:
  private def fetchRolesPermissionsById(j: JobKind.FetchRolesPermissionsByIdRequest): F[JobResult] =
    val roleIds = j.roleIds

    val dbProgram = (repoService.fetchRolesByIds(roleIds), repoService.fetchRolesPermissionsById(roleIds)).tupled

    for
      _ <- wu.logi(s"Fetching role permissions for the given roleIds: $roleIds")
      (roleIdToRole, roleIdToPermissions) <- dbProgram.transact(xa)
    yield JobResult.FetchRolesPermissionsByIdResult(generateResult(roleIdToRole, roleIdToPermissions))
  end fetchRolesPermissionsById

  override def work(job: JobKind): F[JobResult] =
    job match
      case j: JobKind.FetchRolesPermissionsByIdRequest => fetchRolesPermissionsById(j)
      case _ => async.raiseError(new IllegalArgumentException(s"Unexpected job type: $job"))
  end work

  private def generateResult(
      roleIdToRole: Map[RoleId, RoleInDb],
      roleIdToPermissions: Map[RoleId, Vector[PermissionInDb]],
  ): Either[FetchRolesPermissionsByIdError, Map[RoleId, Vector[PermissionInDb]]] =
    val missingIds = (roleIdToPermissions.keySet -- roleIdToRole.keySet).view.map(_.value).toVector

    NonEmptyVector
      .fromVector(missingIds)
      .map(FetchRolesPermissionsByIdError.NoSuchRoleIds.apply)
      .toLeft(roleIdToPermissions)
  end generateResult
end FetchRolesPermissionsById

object FetchRolesPermissionsById:
  def create[F[_]: Async](repoService: RepositoryService, xa: Transactor[F], wu: WorkerTaskUtils[F]): WorkerTask[F] =
    FetchRolesPermissionsById[F](repoService, xa, wu)
  end create
end FetchRolesPermissionsById
