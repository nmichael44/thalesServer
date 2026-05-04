package app.workerTasks

import cats.effect.MonadCancelThrow
import cats.implicits.*

import app.JobSpecs.{JobKind, JobResult}
import app.JobSpecs.JobKind.FetchUserRoleIdsRequest
import app.JobSpecs.JobResult.FetchUserRoleIdsResult
import app.services.RepositoryService
import doobie.Transactor
import doobie.implicits.*

private final class FetchUserRoleIds[F[_]: MonadCancelThrow](
    repoService: RepositoryService,
    xa: Transactor[F],
    wu: WorkerTaskUtils[F],
) extends WorkerTask[F]:
  private val logFetchingUserRoleIds: F[Unit] = wu.logi("Fetching user role IDs.")

  private def fetchUserRoleIds(j: FetchUserRoleIdsRequest): F[JobResult] =
    val userIds = j.userIds

    for
      _ <- logFetchingUserRoleIds
      res <- repoService.fetchUserRoleIds(userIds).transact(xa)
    yield FetchUserRoleIdsResult(res)
  end fetchUserRoleIds

  override def work(job: JobKind): F[JobResult] =
    fetchUserRoleIds(job.asInstanceOf[FetchUserRoleIdsRequest])
  end work
end FetchUserRoleIds

object FetchUserRoleIds:
  def create[F[_]: MonadCancelThrow](
      repoService: RepositoryService,
      xa: Transactor[F],
      wu: WorkerTaskUtils[F],
  ): FetchUserRoleIds[F] =
    FetchUserRoleIds[F](repoService, xa, wu)
  end create
end FetchUserRoleIds
