package app

import cats.effect.kernel.Async
import cats.implicits.*

import scala.concurrent.duration.{Duration, DurationInt}

import app.ThalesUtils.{GenUtils as U, TimeUtils}
import app.services.RepositoryService
import doobie.implicits.*
import doobie.util.transactor.Transactor
import org.typelevel.log4cats.Logger

object ResetUserPasswordTokensWorker:
  private val resetUserPasswordTokensWorkerFiber = "ResetUserPasswordTokensWorker"
  private val delayBetweenWorkerRuns: Duration = 15.minutes

  private def logi[F[_]: { Async, Logger }](msg: String): F[Unit] =
    U.logi(resetUserPasswordTokensWorkerFiber, msg)
  end logi

  private def loge[F[_]: { Async, Logger }](e: Throwable, msg: String): F[Unit] =
    U.loge(e, resetUserPasswordTokensWorkerFiber, msg)
  end loge

  private def createWorker[F[_]: { Async as async, Logger }](
      repoService: RepositoryService,
      xa: Transactor[F],
  ): F[Nothing] =
    val onError: Throwable => F[Unit] = e =>
      loge(e, "A non-recoverable error occurred in the ResetUserPasswordTokens worker loop. Restarting....") *>
        async.sleep(delayBetweenWorkerRuns)

    val execOneRun = for {
      _ <- logi("Starting a cleanup...")
      cnt <- TimeUtils.nowInstant >>= { now => repoService.deleteExpiredResetUserPasswordTokens(now).transact(xa) }
      _ <- logi(s"Deleted $cnt expired reset user password tokens.")
      _ <- logi("Going back to sleep.")
      _ <- async.sleep(delayBetweenWorkerRuns)
    } yield ()

    val safeRun = execOneRun.handleErrorWith(onError)

    safeRun.foreverM
  end createWorker

  def create[F[_]: { Async, Logger }](deps: AppDependencies[F]): F[Unit] =
    val xa: Transactor[F] = deps.xa
    val supervisor = deps.supervisor
    val repoService = deps.repositoryService
    val worker = createWorker(repoService, xa)

    supervisor.supervise(worker).void
  end create
end ResetUserPasswordTokensWorker
