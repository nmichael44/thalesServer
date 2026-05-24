package app.serviceslive

import cats.effect.{IO, Resource}
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*

import java.time.Instant

import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import app.Config.AppConfigUtils.AppConfig
import app.Database.DoobieUtils
import app.entrypoints.TestUtils as TU
import app.model.AppModel.{EmailMessage, OutboxStatus}
import doobie.implicits.*
import doobie.util.transactor.Transactor
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pureconfig.ConfigSource

final class EmailServiceLiveIntegrationTest extends AsyncFreeSpec with AsyncIOSpec with Matchers:
  given CanEqual[OutboxStatus, OutboxStatus] = CanEqual.derived

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

  private def countEmailsInOutbox(emailId: Long): IO[Int] =
    TU.getDbConnection.flatMap: conn =>
      IO.blocking:
        try
          val stmt = conn.prepareStatement("select count(*) from EmailOutbox where emailId = ?")
          try
            stmt.setLong(1, emailId)
            val rs = stmt.executeQuery()
            rs.next()
            val count = rs.getInt(1)
            conn.commit()
            count
          finally stmt.close()
        catch
          case e: Throwable =>
            conn.rollback()
            throw e
        finally conn.close()
  end countEmailsInOutbox

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

  private def getRecipientRows(emailId: Long): IO[Vector[DbRecipientRow]] =
    TU.getDbConnection.flatMap: conn =>
      IO.blocking:
        try
          val stmt = conn.prepareStatement(
            "select recipientId, emailId, emailAddress, recipientType from EmailRecipients where emailId = ? order by recipientId",
          )
          try
            stmt.setLong(1, emailId)
            val rs = stmt.executeQuery()
            val buffer = Vector.newBuilder[DbRecipientRow]
            while rs.next() do
              buffer += DbRecipientRow(
                recipientId = rs.getLong("recipientId"),
                emailId = rs.getLong("emailId"),
                emailAddress = rs.getString("emailAddress"),
                recipientType = rs.getString("recipientType"),
              )
            conn.commit()
            buffer.result()
          finally stmt.close()
        catch
          case e: Throwable =>
            conn.rollback()
            throw e
        finally conn.close()
  end getRecipientRows

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

  "EmailServiceLive and RepositoryService DB integration flow" - {
    "should support queueing, fetching, marking as sent, and marking as failed" in {
      val testUuid = java.util.UUID.randomUUID().toString
      val subject1 = s"Hello Integrations $testUuid"
      val subject2 = s"Hello Failure Integrations $testUuid"

      val msg = EmailMessage(
        from = "sender@thales.com",
        tos = Seq("recipient1@thales.com", "recipient2@thales.com"),
        ccs = Seq("cc@thales.com"),
        bccs = Seq("bcc@thales.com"),
        subject = subject1,
        body = "Checking database outbox system.",
      )

      val msg2 = msg.copy(subject = subject2)

      val repo = RepositoryServiceLive.create
      val thalesServerLoggerName = org.typelevel.log4cats.LoggerName("EmailServiceLiveIntegrationTest")

      (
        Slf4jLogger.create[IO](using IO.asyncForIO, thalesServerLoggerName).widen[Logger[IO]].toResource,
        transactorResource,
      ).tupled.use { case (logger, xa) =>
        implicit val implLogger: Logger[IO] = logger
        val emailService = EmailServiceLive.create[IO](repo, xa)

        for
          // 1. Send first email using the EmailService wrapper and get its generated primary key directly
          emailId1 <- emailService.sendEmail(msg)

          // Direct JDBC assertions using the unique primary key emailId1 to verify table was updated as it should be
          count <- countEmailsInOutbox(emailId1)
          outboxRowOpt <- getOutboxRow(emailId1)
          recipientRows <- getRecipientRows(emailId1)

          // 2. Fetch eligible emails from outbox
          now = Instant.now()
          eligible <- repo.fetchEligibleEmailsFromOutbox(now, maxAttempts = 5, limit = 100).transact(xa)
          entryOpt = eligible.find(_.emailId == emailId1)
          entry = entryOpt.get

          // 3. Mark as sent
          sentTime = Instant.now()
          _ <- repo.markEmailAsSent(emailId1, sentTime).transact(xa)

          // Verify status updated in DB
          outboxRowSentOpt <- getOutboxRow(emailId1)

          // Verify no longer eligible for fetching
          eligibleAfterSent <- repo.fetchEligibleEmailsFromOutbox(sentTime.plusSeconds(3600), maxAttempts = 5, limit = 100).transact(xa)

          // 4. Send second email (failure flow) using the EmailService wrapper and get its generated primary key directly
          emailId2 <- emailService.sendEmail(msg2)

          failTime = Instant.now()
          nextRetry = failTime.plusSeconds(30)
          errMsg = "SMTP Connection Timeout"
          _ <- repo.markEmailAsFailed(emailId2, failTime, attempts = 1, nextRetry, errMsg).transact(xa)

          // Verify failed properties in DB via JDBC
          outboxRowFailedOpt <- getOutboxRow(emailId2)

          // 5. Verify retry timing behavior:
          // If query time is before nextRetry, it should NOT be eligible
          eligibleBeforeRetry <- repo.fetchEligibleEmailsFromOutbox(nextRetry.minusSeconds(5), maxAttempts = 5, limit = 100).transact(xa)

          // If query time is at or after nextRetry, it SHOULD be eligible
          eligibleAtRetry <- repo.fetchEligibleEmailsFromOutbox(nextRetry.plusSeconds(5), maxAttempts = 5, limit = 100).transact(xa)

          // 6. Verify max attempt boundary:
          // Mark email as failed with maximum allowed attempts
          _ <- repo.markEmailAsFailed(emailId2, failTime, attempts = 5, nextRetry, errMsg).transact(xa)

          // Even if nextRetry time has passed, it should NOT be fetched because attempts (5) >= maxAttempts (5)
          eligibleExceededAttempts <- repo.fetchEligibleEmailsFromOutbox(nextRetry.plusSeconds(5), maxAttempts = 5, limit = 100).transact(xa)

          // Clean up specifically our two test emails by their unique generated primary keys
          _ <- clearEmailRow(emailId1)
          _ <- clearEmailRow(emailId2)
        yield
          // Verify outbox row properties
          count shouldBe 1
          outboxRowOpt.isDefined shouldBe true
          val row = outboxRowOpt.get
          row.fromAddress shouldBe msg.from
          row.subject shouldBe msg.subject
          row.body shouldBe msg.body
          row.status shouldBe "PENDING"
          row.attempts shouldBe 0
          row.lastAttemptTime shouldBe None
          row.errorMessage shouldBe None

          // Verify recipient row properties
          recipientRows.size shouldBe 4
          recipientRows.map(_.emailAddress).toSet shouldBe Set(
            "recipient1@thales.com",
            "recipient2@thales.com",
            "cc@thales.com",
            "bcc@thales.com",
          )
          recipientRows.filter(_.recipientType == "TO").map(_.emailAddress).toSet shouldBe Set(
            "recipient1@thales.com",
            "recipient2@thales.com",
          )
          recipientRows.filter(_.recipientType == "CC").map(_.emailAddress).toSet shouldBe Set("cc@thales.com")
          recipientRows.filter(_.recipientType == "BCC").map(_.emailAddress).toSet shouldBe Set("bcc@thales.com")

          // Verify eligible outbox entries properties
          eligible.size shouldBe 1
          entryOpt.isDefined shouldBe true
          entry.emailId shouldBe row.emailId
          entry.fromAddress shouldBe msg.from
          entry.toAddresses.toSet shouldBe Set("recipient1@thales.com", "recipient2@thales.com")
          entry.ccAddresses.toSet shouldBe Set("cc@thales.com")
          entry.bccAddresses.toSet shouldBe Set("bcc@thales.com")
          entry.status shouldBe OutboxStatus.Pending

          // Verify sent properties in DB
          outboxRowSentOpt.isDefined shouldBe true
          outboxRowSentOpt.get.status shouldBe "SENT"
          outboxRowSentOpt.get.lastAttemptTime.isDefined shouldBe true
          eligibleAfterSent.size shouldBe 0

          // Verify failed properties in DB
          outboxRowFailedOpt.isDefined shouldBe true
          val failRow = outboxRowFailedOpt.get
          failRow.status shouldBe "FAILED"
          failRow.attempts shouldBe 1
          failRow.errorMessage shouldBe Some(errMsg)

          // Verify retry timing eligibility
          eligibleBeforeRetry.size shouldBe 0
          eligibleAtRetry.size shouldBe 1
          eligibleAtRetry.head.emailId shouldBe emailId2

          // Verify max attempts boundary
          eligibleExceededAttempts.size shouldBe 0
      }
    }
  }
end EmailServiceLiveIntegrationTest

final case class DbOutboxRow(
    emailId: Long,
    fromAddress: String,
    subject: String,
    body: String,
    status: String,
    attempts: Int,
    lastAttemptTime: Option[Instant],
    nextAttemptTime: Instant,
    creationTime: Instant,
    errorMessage: Option[String],
)

final case class DbRecipientRow(
    recipientId: Long,
    emailId: Long,
    emailAddress: String,
    recipientType: String,
)
