package app.workerTasks

import cats.effect.Async
import cats.syntax.all.*

import app.JobSpecs.{JobKind, JobResult}
import app.services.RepositoryService
import doobie.Transactor
import doobie.implicits.*

private final class FetchAllPermissions[F[_]: Async] private (
    repoService: RepositoryService,
    xa: Transactor[F],
    wu: WorkerUtils[F],
) extends WorkerTask[F]:
  private val logFetchingAllPermissions: F[Unit] = wu.logi("Fetching all permissions.")

  private val fetchAllPermissions: F[JobResult] =
    for {
      _ <- logFetchingAllPermissions
      res <- repoService.fetchAllPermissions.transact(xa)
    } yield JobResult.FetchAllPermissionsResult(res)
  end fetchAllPermissions

  override def work(job: JobKind): F[JobResult] =
    fetchAllPermissions
  end work
end FetchAllPermissions

object FetchAllPermissions:
  def create[F[_]: Async](
      repoService: RepositoryService,
      xa: Transactor[F],
      wu: WorkerUtils[F],
  ): WorkerTask[F] =
    FetchAllPermissions[F](repoService, xa, wu)
  end create
end FetchAllPermissions
