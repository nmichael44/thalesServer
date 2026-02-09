package app.workerTasks

import cats.effect.Async
import cats.syntax.all.*

import app.JobSpecs.{JobKind, JobResult}
import app.services.RepositoryService
import doobie.Transactor
import doobie.implicits.*

private final class FetchUsersByUserIds[F[_]: Async] private (
    repoService: RepositoryService,
    xa: Transactor[F],
    wu: WorkerTaskUtils[F],
) extends WorkerTask[F]:
  private val logFetchUsersByUserIds: F[Unit] = wu.logi("Fetching user by userId.")

  private def fetchUsersByUserIds(j: JobKind.FetchUsersByUserIdsRequest): F[JobResult] =
    val userIds = j.userIds

    for
      _ <- logFetchUsersByUserIds
      res <- repoService.fetchUsersByUserIds(userIds).transact(xa)
    yield JobResult.FetchUsersByUserIdsResult(res)
  end fetchUsersByUserIds

  override def work(job: JobKind): F[JobResult] =
    fetchUsersByUserIds(job.asInstanceOf[JobKind.FetchUsersByUserIdsRequest])
  end work
end FetchUsersByUserIds

object FetchUsersByUserIds:
  def create[F[_]: Async](repoService: RepositoryService, xa: Transactor[F], wu: WorkerTaskUtils[F]): WorkerTask[F] =
    FetchUsersByUserIds[F](repoService, xa, wu)
  end create
end FetchUsersByUserIds
