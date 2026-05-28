package app

import cats.effect.Resource
import cats.effect.implicits.*
import cats.effect.kernel.Async
import cats.implicits.*

import java.time.Instant
import scala.concurrent.duration.*

import app.ThalesUtils.GenUtils as U
import app.model.AppModel.EmailOutboxEntry
import app.services.RepositoryService
import doobie.implicits.*
import doobie.util.transactor.Transactor
import org.typelevel.log4cats.Logger

object EmailOutboxWorker:
  private inline val FiberName = "EmailOutboxWorker"
  private val PollingInterval: FiniteDuration = 10.seconds
  private inline val MaxEmailsPerSweep = 128
  private inline val BaseBackoffSeconds = 30
  private inline val MaxRetryAttempts = 5
  private val ErrorCooldown: FiniteDuration = 1.minute

  def create[F[_]: { Async as async, Logger }](
      repoService: RepositoryService,
      xa: Transactor[F],
      pollingInterval: FiniteDuration,
      failedEmailRetryDelay: FiniteDuration,
  ): Resource[F, Unit] =
    val sleepUntilNextRun = async.sleep(pollingInterval)
    val sleepAfterError = async.sleep(ErrorCooldown)
    val getNow = async.realTimeInstant

    val oneRun: F[Unit] =
      for
        now <- getNow
        entries <- repoService.fetchEligibleEmailsFromOutbox(now, MaxRetryAttempts, MaxEmailsPerSweep).transact(xa)
        _ <- entries.traverseVoid(processEntry(_, now, repoService, xa, failedEmailRetryDelay))
      yield ()

    val runStepWithErrorHandling: F[Unit] =
      (oneRun *> sleepUntilNextRun).handleErrorWith: err =>
        U.loge(err, FiberName, "Error in outbox worker loop, restarting...") *> sleepAfterError

    runStepWithErrorHandling.foreverM.background.void
  end create

  private def sendEmailToProvider[F[_]: { Async, Logger }](entry: EmailOutboxEntry): F[Unit] =
    Logger[F].info(s"[OUTBOX DISPATCH] From: ${entry.fromAddress} | To: ${entry.toAddresses.mkString(", ")} | Subject: ${entry.subject}")
  end sendEmailToProvider

  private def processEntry[F[_]: { Async as async, Logger }](
      entry: EmailOutboxEntry,
      now: Instant,
      repoService: RepositoryService,
      xa: Transactor[F],
      failedEmailRetryDelay: FiniteDuration,
  ): F[Unit] =
    val sendAttempt: F[Unit] = sendEmailToProvider(entry)

    sendAttempt.attempt.flatMap: e =>
      (e match
        case Right(_) => repoService.markEmailAsSent(entry.emailId, now)
        case Left(err) => repoService.markEmailAsFailed(entry.emailId, now, entry.attempts + 1, now.plusNanos(failedEmailRetryDelay.toNanos), err.getMessage)
      )
        .transact(xa)
  end processEntry
end EmailOutboxWorker
