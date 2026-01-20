package app.workers

import cats.effect.Async
import cats.syntax.all.*

import app.JobSpecs.{JobKind, JobResult}
import app.entrypoints.smithy.{RoleId, UserInDb}
import app.services.RepositoryService
import doobie.{ConnectionIO, Transactor}
import doobie.implicits.*

private final class FetchAllUsersAssociatedWithRoles[F[_]: Async] private (
    repoService: RepositoryService,
    xa: Transactor[F],
    wu: WorkerUtils[F],
) extends HttpWorkerTask[F]:
  private def fetchAllUsersAssociatedWithRoles(j: JobKind.FetchAllUsersAssociatedWithRolesRequest): F[JobResult] =
    val roleIds = j.roleIds

    val dbProgram: ConnectionIO[Map[RoleId, Vector[UserInDb]]] =
      repoService.fetchAllUsersAssociatedWithRoles(roleIds)

    for {
      _ <- wu.logi(s"Fetching all users associated with roleIds: $roleIds")
      res <- dbProgram.transact(xa)
    } yield JobResult.FetchAllUsersAssociatedWithRolesResult(res)
  end fetchAllUsersAssociatedWithRoles

  override def work(job: JobKind): F[JobResult] =
    fetchAllUsersAssociatedWithRoles(job.asInstanceOf[JobKind.FetchAllUsersAssociatedWithRolesRequest])
  end work
end FetchAllUsersAssociatedWithRoles

object FetchAllUsersAssociatedWithRoles:
  def create[F[_]: Async](
      repoService: RepositoryService,
      xa: Transactor[F],
      wu: WorkerUtils[F],
  ): HttpWorkerTask[F] =
    FetchAllUsersAssociatedWithRoles[F](repoService, xa, wu)
  end create
end FetchAllUsersAssociatedWithRoles
