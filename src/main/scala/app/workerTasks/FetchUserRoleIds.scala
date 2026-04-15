package app.workerTasks

import cats.effect.Async
import cats.implicits.*

import app.JobSpecs.{JobKind, JobResult}
import app.JobSpecs.JobKind.FetchUserRoleIdsRequest
import app.JobSpecs.JobResult.FetchUserRoleIdsResult
import app.services.RepositoryService
import doobie.Transactor
import doobie.implicits.*

private final class FetchUserRoleIds[F[_]: Async](
    repoService: RepositoryService,
    xa: Transactor[F],
    wu: WorkerTaskUtils[F],
) extends WorkerTask[F]:
  private def fetchUserRoleIds(j: FetchUserRoleIdsRequest): F[JobResult] =
    val userIds = j.userIds

    for
      _ <- wu.logi(s"Fetching role IDs for ${userIds.length} users.")
      res <- repoService.fetchUserRoleIds(userIds).transact(xa)
    yield FetchUserRoleIdsResult(res)
  end fetchUserRoleIds

  override def work(job: JobKind): F[JobResult] =
    fetchUserRoleIds(job.asInstanceOf[FetchUserRoleIdsRequest])
  end work
end FetchUserRoleIds

object FetchUserRoleIds:
  def create[F[_]: Async](
      repoService: RepositoryService,
      xa: Transactor[F],
      wu: WorkerTaskUtils[F],
  ): FetchUserRoleIds[F] =
    FetchUserRoleIds[F](repoService, xa, wu)
  end create
end FetchUserRoleIds
