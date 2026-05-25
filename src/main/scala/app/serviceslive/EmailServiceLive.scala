package app.serviceslive

import cats.effect.{Async, Resource}
import cats.syntax.all.*

import scala.collection.View

import app.{AppDependencies, EmailOutboxWorker}
import app.ThalesUtils.GenUtils as U
import app.model.AppModel.{EmailMessage, RecipientType}
import app.services.{EmailService, RepositoryService}
import doobie.implicits.*
import doobie.util.transactor.Transactor
import org.typelevel.log4cats.Logger

private final class EmailServiceLive[F[_]: { Async as async, Logger as logger }](
    repoService: RepositoryService,
    xa: Transactor[F],
) extends EmailService[F]:
  override def sendEmail(msg: EmailMessage): F[Long] =
    for
      now <- async.realTimeInstant
      emailId <- repoService
        .insertEmailIntoOutbox(msg.from, msg.tos, msg.ccs, msg.bccs, msg.subject, msg.body, now)
        .transact(xa)
      _ <- U.logi(s"Email to ${msg.tos.mkString(", ")} queued in outbox (ID: $emailId).")
    yield emailId
  end sendEmail
end EmailServiceLive

object EmailServiceLive:
  def create[F[_]: { Async, Logger }](repoService: RepositoryService, xa: Transactor[F]): EmailService[F] =
    EmailServiceLive[F](repoService, xa)
  end create

  def createEmailOutboxWorker[F[_]: { Async, Logger }](
      deps: AppDependencies[F],
      cfg: app.Config.AppConfigUtils.EmailOutboxWorkerConfig,
  ): Resource[F, Unit] =
    EmailOutboxWorker.create(deps.repositoryService, deps.xa, cfg.getPollingInterval, cfg.getFailedEmailRetryDelay)
  end createEmailOutboxWorker
end EmailServiceLive
