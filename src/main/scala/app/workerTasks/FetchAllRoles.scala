package app.workerTasks

import cats.effect.Async
import cats.syntax.all.*

import app.JobSpecs.{JobKind, JobResult}
import app.services.RepositoryService
import doobie.Transactor
import doobie.implicits.*

private final class FetchAllRoles[F[_]: Async] private (
    repoService: RepositoryService,
    xa: Transactor[F],
    wu: WorkerTaskUtils[F],
) extends WorkerTask[F]:
  private val logFetchingAllRoles: F[Unit] = wu.logi("Fetching all roles.")

  private val fetchAllRoles: F[JobResult] =
    for
      _ <- logFetchingAllRoles
      res <- repoService.fetchAllRoles.transact(xa)
    yield JobResult.FetchAllRolesResult(res)
  end fetchAllRoles

  override def work(job: JobKind): F[JobResult] =
    fetchAllRoles
  end work
end FetchAllRoles

object FetchAllRoles:
  def create[F[_]: Async](
      repoService: RepositoryService,
      xa: Transactor[F],
      wu: WorkerTaskUtils[F],
  ): WorkerTask[F] =
    FetchAllRoles[F](repoService, xa, wu)
  end create
end FetchAllRoles
