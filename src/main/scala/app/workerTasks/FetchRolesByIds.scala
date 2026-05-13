package app.workerTasks

import cats.effect.MonadCancelThrow
import cats.syntax.all.*

import app.JobSpecs.{JobKind, JobResult}
import app.services.RepositoryService
import doobie.Transactor
import doobie.implicits.*

private final class FetchRolesByIds[F[_]: MonadCancelThrow] private (
    repoService: RepositoryService,
    xa: Transactor[F],
    wu: WorkerTaskUtils[F],
) extends WorkerTask[F, JobKind.FetchRolesByIdsRequest]:
  private val logFetchingRoleById: F[Unit] = wu.logi("Fetching role by id.")

  private def fetchRolesByIds(j: JobKind.FetchRolesByIdsRequest): F[JobResult] =
    val roleIds = j.roleIds

    for
      _ <- logFetchingRoleById
      res <- repoService.fetchRolesByIds(roleIds).transact(xa)
    yield JobResult.FetchRolesByIdsResult(res)
  end fetchRolesByIds

  override def work(job: JobKind.FetchRolesByIdsRequest): F[JobResult] =
    fetchRolesByIds(job)
  end work
end FetchRolesByIds

object FetchRolesByIds:
  def create[F[_]: MonadCancelThrow](
      repoService: RepositoryService,
      xa: Transactor[F],
      wu: WorkerTaskUtils[F],
  ): WorkerTask[F, JobKind.FetchRolesByIdsRequest] =
    FetchRolesByIds[F](repoService, xa, wu)
  end create
end FetchRolesByIds
