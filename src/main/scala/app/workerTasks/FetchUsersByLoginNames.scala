package app.workerTasks

import cats.effect.MonadCancelThrow
import cats.syntax.all.*

import app.JobSpecs.{JobKind, JobResult}
import app.services.RepositoryService
import doobie.Transactor
import doobie.implicits.*

private final class FetchUsersByLoginNames[F[_]: MonadCancelThrow] private (
    repoService: RepositoryService,
    xa: Transactor[F],
    wu: WorkerTaskUtils[F],
) extends WorkerTask[F, JobKind.FetchUsersByLoginNamesRequest]:
  private val logFetchingUserByLoginName: F[Unit] = wu.logi("Fetching user by loginName.")

  private def fetchUsersByLoginNames(j: JobKind.FetchUsersByLoginNamesRequest): F[JobResult] =
    val loginNames = j.loginNames

    for
      _ <- logFetchingUserByLoginName
      res <- repoService
        .fetchUsersByLoginNames(loginNames)
        .transact(xa)
    yield JobResult.FetchUsersByLoginNamesResult(res)
  end fetchUsersByLoginNames

  override def work(job: JobKind.FetchUsersByLoginNamesRequest): F[JobResult] =
    fetchUsersByLoginNames(job)
  end work
end FetchUsersByLoginNames

object FetchUsersByLoginNames:
  def create[F[_]: MonadCancelThrow](
      repoService: RepositoryService,
      xa: Transactor[F],
      wu: WorkerTaskUtils[F],
  ): WorkerTask[F, JobKind.FetchUsersByLoginNamesRequest] =
    FetchUsersByLoginNames[F](repoService, xa, wu)
  end create
end FetchUsersByLoginNames
