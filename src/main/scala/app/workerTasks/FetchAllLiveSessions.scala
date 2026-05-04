package app.workerTasks

import cats.data.NonEmptyVector
import cats.effect.MonadCancelThrow
import cats.syntax.all.*

import app.JobSpecs.{JobKind, JobResult}
import app.JobSpecs.JobResult.FetchAllLiveSessionsResult
import app.ThalesUtils.GenUtils as U
import app.services.{RepositoryService, ServerState}
import doobie.Transactor
import doobie.implicits.*

private final class FetchAllLiveSessions[F[_]: MonadCancelThrow as mct] private (
    repoService: RepositoryService,
    xa: Transactor[F],
    serverState: ServerState[F],
    wu: WorkerTaskUtils[F],
) extends WorkerTask[F]:
  private val fetchAllLiveSessions: F[JobResult] =
    serverState.lastAccess.get.flatMap: lastAccess =>
      NonEmptyVector
        .fromVector(lastAccess.keySet.toVector)
        .fold(mct.pure(FetchAllLiveSessionsResult(Vector.empty))): userIds =>
          repoService
            .fetchUsersByUserIds(userIds)
            .transact(xa)
            .map: users =>
              FetchAllLiveSessionsResult(
                users.keySet.view
                  .map(U.mapToSecond(lastAccess.apply))
                  .toVector,
              )
  end fetchAllLiveSessions

  override def work(job: JobKind): F[JobResult] =
    fetchAllLiveSessions
  end work
end FetchAllLiveSessions

object FetchAllLiveSessions:
  def create[F[_]: MonadCancelThrow](
      repoService: RepositoryService,
      xa: Transactor[F],
      serverState: ServerState[F],
      wu: WorkerTaskUtils[F],
  ): WorkerTask[F] =
    FetchAllLiveSessions[F](repoService, xa, serverState, wu)
  end create
end FetchAllLiveSessions
