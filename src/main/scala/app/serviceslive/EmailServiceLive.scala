package app.serviceslive

import cats.Applicative

import app.model.AppModel.EmailMessage
import app.services.EmailService
import org.typelevel.log4cats.Logger

private final class EmailServiceLive[F[_]: { Applicative, Logger as logger }] extends EmailService[F]:
  override def sendEmail(msg: EmailMessage): F[Unit] =
    logger.info(
      s"""Sending email:
        |  From:    ${msg.from}
        |  To:      ${msg.tos.mkString(", ")}
        |  Subject: ${msg.subject}
        |  Body:
        |${msg.body.linesIterator.map(line => s"    $line").mkString("\n")}""".stripMargin,
    )
  end sendEmail
end EmailServiceLive

object EmailServiceLive:
  def create[F[_]: { Applicative, Logger }]: EmailService[F] =
    EmailServiceLive[F]
  end create
end EmailServiceLive
