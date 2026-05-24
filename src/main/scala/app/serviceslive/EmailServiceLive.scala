package app.serviceslive

import app.{AppDependencies, EmailOutboxWorker}
import cats.effect.{Async, Resource}
import cats.syntax.all.*
import app.model.AppModel.EmailMessage
import app.services.{EmailService, RepositoryService}
import doobie.implicits.*
import doobie.util.transactor.Transactor
import org.typelevel.log4cats.Logger

private final class EmailServiceLive[F[_]: { Async as async, Logger as logger }](
    repoService: RepositoryService,
    xa: Transactor[F],
) extends EmailService[F]:
  override def sendEmail(msg: EmailMessage): F[Unit] =
    for
      now <- async.realTimeInstant
      _ <- repoService
        .insertEmailIntoOutbox(msg.from, msg.tos, msg.ccs, msg.bccs, msg.subject, msg.body, now)
        .transact(xa)
      _ <- logger.info(s"Email to ${msg.tos.mkString(", ")} queued in outbox.")
    yield ()
  end sendEmail
end EmailServiceLive

object EmailServiceLive:
  def create[F[_]: { Async, Logger }](repoService: RepositoryService, xa: Transactor[F]): EmailService[F] =
    EmailServiceLive[F](repoService, xa)
  end create
  
  def createEmailOutboxWorker[F[_]: { Async, Logger }](deps: AppDependencies[F]): Resource[F, Unit] =
    EmailOutboxWorker.create(deps.repositoryService, deps.xa)
  end createEmailOutboxWorker
end EmailServiceLive
