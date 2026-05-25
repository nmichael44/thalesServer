package app.serviceslive

import cats.effect.{IO, Resource}
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*

import scala.concurrent.duration.*

import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import app.Config.AppConfigUtils.AppConfig
import app.Database.DoobieUtils
import app.entrypoints.TestUtils as TU
import app.model.AppModel.EmailMessage
import app.services.EmailService
import doobie.util.transactor.Transactor
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pureconfig.ConfigSource

final class EmailOutboxWorkerIntegrationTest extends AsyncFreeSpec with AsyncIOSpec with Matchers:
  private val transactorResource: Resource[IO, Transactor[IO]] =
    for
      appConfig <- Resource.eval(
        TU.setEnvVariables *>
          TU.resetDatabase *>
          IO.delay {
            ConfigSource.systemProperties
              .withFallback(ConfigSource.resources("application-dev.conf"))
              .withFallback(ConfigSource.resources("application.conf"))
              .at("app-config")
              .load[AppConfig]
              .left
              .map(pureconfig.error.ConfigReaderException[AppConfig])
              .toTry
              .get
          },
      )
      xa <- DoobieUtils.xaResource[IO](appConfig.getDbConnectionConfig)
    yield xa

  private val TestPollingInterval: FiniteDuration = 200.milliseconds

  private def getOutboxRow(emailId: Long): IO[Option[DbOutboxRow]] =
    TU.getDbConnection.flatMap: conn =>
      IO.blocking:
        try
          val stmt = conn.prepareStatement(
            "select emailId, fromAddress, subject, body, status, attempts, lastAttemptTime, nextAttemptTime, creationTime, errorMessage from EmailOutbox where emailId = ?",
          )
          try
            stmt.setLong(1, emailId)
            val rs = stmt.executeQuery()
            val result =
              if rs.next() then
                Some(
                  DbOutboxRow(
                    emailId = rs.getLong("emailId"),
                    fromAddress = rs.getString("fromAddress"),
                    subject = rs.getString("subject"),
                    body = rs.getString("body"),
                    status = rs.getString("status"),
                    attempts = rs.getInt("attempts"),
                    lastAttemptTime = Option(rs.getTimestamp("lastAttemptTime")).map(_.toInstant),
                    nextAttemptTime = rs.getTimestamp("nextAttemptTime").toInstant,
                    creationTime = rs.getTimestamp("creationTime").toInstant,
                    errorMessage = Option(rs.getString("errorMessage")),
                  ),
                )
              else None
            conn.commit()
            result
          finally stmt.close()
        catch
          case e: Throwable =>
            conn.rollback()
            throw e
        finally conn.close()
  end getOutboxRow

  private def clearEmailRow(emailId: Long): IO[Unit] =
    TU.getDbConnection.flatMap: conn =>
      IO.blocking:
        try
          val stmt = conn.prepareStatement("delete from EmailOutbox where emailId = ?")
          try
            stmt.setLong(1, emailId)
            stmt.executeUpdate()
            conn.commit()
          finally stmt.close()
        catch
          case e: Throwable =>
            conn.rollback()
            throw e
        finally conn.close()
  end clearEmailRow

  "EmailOutboxWorker integration flow" - {
    "should automatically pick up queued emails and mark them as SENT" in {
      val testUuid = java.util.UUID.randomUUID().toString
      val subject = s"Outbox Worker Integration $testUuid"

      val msg = EmailMessage(
        from = "worker-sender@thales.com",
        tos = Seq("worker-recipient@thales.com"),
        ccs = Seq.empty,
        bccs = Seq.empty,
        subject = subject,
        body = "Checking background outbox worker execution loop.",
      )

      val repo = RepositoryServiceLive.create
      val thalesServerLoggerName = org.typelevel.log4cats.LoggerName("EmailOutboxWorkerIntegrationTest")

      (
        Slf4jLogger.create[IO](using IO.asyncForIO, thalesServerLoggerName).widen[Logger[IO]].toResource,
        transactorResource,
      ).tupled.use: (logger, xa) =>
        implicit val implLogger: Logger[IO] = logger
        val emailService: EmailService[IO] = EmailServiceLive.create[IO](repo, xa)

        for
          // 1. Queue the test email using EmailService
          emailId <- emailService.sendEmail(msg)

          // Verify initially PENDING in the outbox
          initRowOpt <- getOutboxRow(emailId)
          _ <- IO {
            initRowOpt.isDefined shouldBe true
            initRowOpt.get.status shouldBe "PENDING"
          }

          // 2. Start the outbox worker in the background with the custom polling interval
          finalRow <- app.EmailOutboxWorker
            .create[IO](repo, xa, pollingInterval = TestPollingInterval, failedEmailRetryDelay = 30.seconds)
            .use { _ =>
              // Wait for worker to run and process the email, retrying if necessary
              def checkStatus: IO[DbOutboxRow] = getOutboxRow(emailId).flatMap {
                case Some(row) if row.status == "SENT" => IO.pure(row)
                case _ => IO.sleep(TestPollingInterval) *> checkStatus
              }
              // Timeout after 5 seconds if something goes wrong
              IO.race(IO.sleep(5.seconds), checkStatus)
                .flatMap:
                  case Right(row) => IO.pure(row)
                  case Left(_) => IO.raiseError(RuntimeException("Timed out waiting for outbox worker to process email!"))
            }
            .guarantee(clearEmailRow(emailId))
        yield
          finalRow.status shouldBe "SENT"
          finalRow.attempts shouldBe 0
          finalRow.lastAttemptTime.isDefined shouldBe true
          finalRow.errorMessage shouldBe None
    }

    "should fail sending twice, back off immediately, and succeed on the third attempt" in {
      val testUuid = java.util.UUID.randomUUID().toString
      val subject = s"Outbox Worker Failure Retry $testUuid"

      val msg = EmailMessage(
        from = "retry-sender@thales.com",
        tos = Seq("retry-recipient@thales.com"),
        ccs = Seq.empty,
        bccs = Seq.empty,
        subject = subject,
        body = "Checking worker retry and backoff loop.",
      )

      val repo = RepositoryServiceLive.create
      val thalesServerLoggerName = org.typelevel.log4cats.LoggerName("EmailOutboxWorkerRetryTest")

      (
        Slf4jLogger.create[IO](using IO.asyncForIO, thalesServerLoggerName).widen[Logger[IO]].toResource,
        transactorResource,
      ).tupled.use { case (logger, xa) =>
        val attemptCounter = new java.util.concurrent.atomic.AtomicInteger(0)
        implicit val fakeLogger: Logger[IO] = EmailOutboxWorkerIntegrationTest.FakeLogger(logger, attemptCounter)
        val emailService = EmailServiceLive.create[IO](repo, xa)

        for
          // 1. Queue the test email using EmailService
          emailId <- emailService.sendEmail(msg)

          // 2. Start the outbox worker in the background with a 100ms polling interval and 0s backoff
          finalRow <- app.EmailOutboxWorker.create[IO](repo, xa, pollingInterval = TestPollingInterval, failedEmailRetryDelay = 0.seconds).use { _ =>
            // Wait for worker to run and process the email until it succeeds (status should eventually become "SENT")
            def checkStatus: IO[DbOutboxRow] = getOutboxRow(emailId).flatMap {
              case Some(row) if row.status == "SENT" => IO.pure(row)
              case _ => IO.sleep(TestPollingInterval) *> checkStatus
            }
            // Timeout after 5 seconds if something goes wrong
            IO.race(IO.sleep(5.seconds), checkStatus).flatMap {
              case Right(row) => IO.pure(row)
              case Left(_)    => IO.raiseError(new RuntimeException("Timed out waiting for outbox worker to process email"))
            }
          }.guarantee(clearEmailRow(emailId))
        yield
          // The email should eventually succeed and be marked as "SENT"
          finalRow.status shouldBe "SENT"
          // It failed 2 times, so attempts should be 2!
          finalRow.attempts shouldBe 2
          finalRow.lastAttemptTime.isDefined shouldBe true
          // The error message from the last failure is retained in the DB
          finalRow.errorMessage shouldBe Some("SMTP Server Unavailable (Simulated)")
          // Verify that the dispatch logger was invoked exactly 3 times (justifying our AtomicInteger concurrency guarantees!)
          attemptCounter.get() shouldBe 3
      }
    }
  }
end EmailOutboxWorkerIntegrationTest

object EmailOutboxWorkerIntegrationTest:
  private final class FakeLogger(delegate: Logger[IO], attemptCounter: java.util.concurrent.atomic.AtomicInteger) extends Logger[IO]:
    override def error(message: => String): IO[Unit] = delegate.error(message)
    override def error(t: Throwable)(message: => String): IO[Unit] = delegate.error(t)(message)
    override def warn(message: => String): IO[Unit] = delegate.warn(message)
    override def warn(t: Throwable)(message: => String): IO[Unit] = delegate.warn(t)(message)
    override def info(message: => String): IO[Unit] =
      if message.startsWith("[OUTBOX DISPATCH]") then
        val count = attemptCounter.getAndIncrement()
        if count < 2 then
          IO.raiseError(RuntimeException("SMTP Server Unavailable (Simulated)"))
        else
          delegate.info(message)
      else
        delegate.info(message)
    override def info(t: Throwable)(message: => String): IO[Unit] = delegate.info(t)(message)
    override def debug(message: => String): IO[Unit] = delegate.debug(message)
    override def debug(t: Throwable)(message: => String): IO[Unit] = delegate.debug(t)(message)
    override def trace(message: => String): IO[Unit] = delegate.trace(message)
    override def trace(t: Throwable)(message: => String): IO[Unit] = delegate.trace(t)(message)
  end FakeLogger
end EmailOutboxWorkerIntegrationTest
