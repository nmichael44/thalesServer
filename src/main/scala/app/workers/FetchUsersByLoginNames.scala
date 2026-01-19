package app.workers

import cats.effect.Async
import cats.syntax.all.*

import app.JobSpecs.{JobKind, JobResult}
import app.services.RepositoryService
import doobie.Transactor
import doobie.implicits.*

private final class FetchUsersByLoginNames[F[_]: Async] private (
    repoService: RepositoryService,
    xa: Transactor[F],
    wu: WorkerUtils[F],
) extends HttpWorkerTask[F]:
  private val logFetchingUserByLoginName: F[Unit] = wu.logi("Fetching user by loginName.")

  private def fetchUsersByLoginNames(j: JobKind.FetchUsersByLoginNamesRequest): F[JobResult] =
    val loginNames = j.loginNames

    for {
      _ <- logFetchingUserByLoginName
      res <- repoService
        .fetchUsersByLoginNames(loginNames)
        .transact(xa)
    } yield JobResult.FetchUsersByLoginNamesResult(res)
  end fetchUsersByLoginNames

  override def work(job: JobKind): F[JobResult] =
    fetchUsersByLoginNames(job.asInstanceOf[JobKind.FetchUsersByLoginNamesRequest])
  end work
end FetchUsersByLoginNames

object FetchUsersByLoginNames:
  def create[F[_]: Async](
      repoService: RepositoryService,
      xa: Transactor[F],
      wu: WorkerUtils[F],
  ): HttpWorkerTask[F] =
    FetchUsersByLoginNames[F](repoService, xa, wu)
  end create
end FetchUsersByLoginNames
