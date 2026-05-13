package app.workerTasks

import cats.effect.MonadCancelThrow
import cats.syntax.all.*

import app.JobSpecs.{JobKind, JobResult}
import app.services.RepositoryService
import doobie.Transactor
import doobie.implicits.*

private final class FetchAllRoles[F[_]: MonadCancelThrow] private (
    repoService: RepositoryService,
    xa: Transactor[F],
    wu: WorkerTaskUtils[F],
) extends WorkerTask[F, JobKind.FetchAllRolesRequest.type]:
  private val logFetchingAllRoles: F[Unit] = wu.logi("Fetching all roles.")

  private val fetchAllRoles: F[JobResult] =
    for
      _ <- logFetchingAllRoles
      res <- repoService.fetchAllRoles.transact(xa)
    yield JobResult.FetchAllRolesResult(res)
  end fetchAllRoles

  override def work(job: JobKind.FetchAllRolesRequest.type): F[JobResult] =
    fetchAllRoles
  end work
end FetchAllRoles

object FetchAllRoles:
  def create[F[_]: MonadCancelThrow](
      repoService: RepositoryService,
      xa: Transactor[F],
      wu: WorkerTaskUtils[F],
  ): WorkerTask[F, JobKind.FetchAllRolesRequest.type] =
    FetchAllRoles[F](repoService, xa, wu)
  end create
end FetchAllRoles
