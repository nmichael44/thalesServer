package app.audit_log

import cats.effect.{Async, Resource}
import cats.effect.implicits.*
import cats.syntax.all.*

import app.ThalesUtils.GenUtils as U
import app.entrypoints.smithy.{RoleId, UserId}
import fs2.concurrent.Topic
import org.typelevel.log4cats.Logger

object AuditLogUtils:
  enum DomainEvent:
    case UserCreated(userId: UserId)
    case UserLoggedIn(userId: UserId)
    case PasswordReset(userId: UserId)
    case RoleCreated(roleId: RoleId)
  end DomainEvent

  private val auditLogFiberName: String = "AuditLogWorker"

  def createWorker[F[_]: { Async, Logger }](topic: Topic[F, DomainEvent]): Resource[F, Unit] =
    val constUnit: Throwable => Unit = U.const1(())

    topic
      .subscribe(maxQueued = 100)
      .evalMap: e =>
        U.logi(auditLogFiberName, s"AUDIT EVENT: $e")
          .handleErrorWith: loggingErr =>
            U.loge(loggingErr, auditLogFiberName, "Failed to log audit event").handleError(constUnit)
      .compile
      .drain
      .background
      .void
  end createWorker
end AuditLogUtils
