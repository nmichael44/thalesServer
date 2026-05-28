package app

import cats.effect.Resource
import cats.effect.implicits.*
import cats.effect.kernel.Async
import cats.implicits.*

import java.time.Instant
import scala.concurrent.duration.{DurationInt, FiniteDuration}

import app.ThalesUtils.GenUtils as U
import app.services.{ClockService, RepositoryService}
import doobie.implicits.*
import doobie.util.transactor.Transactor
import org.typelevel.log4cats.Logger

object ResetUserPasswordTokensWorker:
  private inline val ResetUserPasswordTokensWorkerFiber = "ResetUserPasswordTokensWorker"
  private val delayBetweenWorkerRuns: FiniteDuration = 15.minutes

  private def logi[F[_]: Logger](msg: String): F[Unit] =
    U.logi(ResetUserPasswordTokensWorkerFiber, msg)
  end logi

  private def loge[F[_]: Logger](e: Throwable, msg: String): F[Unit] =
    U.loge(e, ResetUserPasswordTokensWorkerFiber, msg)
  end loge

  private def createWorker[F[_]: { Async as async, Logger }](
      repoService: RepositoryService,
      clockService: ClockService[F],
      xa: Transactor[F],
  ): Resource[F, Unit] =
    val logStartingACleanup = logi("Starting a cleanup...")
    val logGoingBackToSleep = logi("Going back to sleep.")
    val getNow = clockService.nowInstant
    val deleteOldRowsFromDb = (now: Instant) => repoService.deleteExpiredResetUserPasswordTokens(now).transact(xa)
    val sleepUntilNextRun = async.sleep(delayBetweenWorkerRuns)
    val sleepAfterError = async.sleep(1.minute)

    val execOneRun: F[Unit] =
      for
        _ <- logStartingACleanup
        now <- getNow
        cnt <- deleteOldRowsFromDb(now)
        _ <- logi(s"Deleted $cnt expired reset user password tokens.")
        _ <- logGoingBackToSleep
        _ <- sleepUntilNextRun
      yield ()

    val onError: Throwable => F[Unit] = e =>
      loge(e, "A non-recoverable error occurred in the ResetUserPasswordTokens worker loop. Restarting....") *>
        sleepAfterError

    val safeRun = execOneRun.handleErrorWith(onError)

    safeRun.foreverM.background.void
  end createWorker

  def create[F[_]: { Async, Logger }](deps: AppDependencies[F]): Resource[F, Unit] =
    createWorker(deps.repositoryService, deps.clockService, deps.xa)
  end create
end ResetUserPasswordTokensWorker
