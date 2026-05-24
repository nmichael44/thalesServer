package app.model

import app.entrypoints.smithy.UserId

object AppModel:
  final case class AuthenticatedUser(
      userId: UserId,
      permissions: java.util.BitSet,
      issuedAt: Long,
      origIat: Long,
      expiresAt: Long,
  )

  final case class EmailMessage(
      from: String,
      tos: Seq[String],
      ccs: Seq[String],
      bccs: Seq[String],
      subject: String,
      body: String,
  )

  enum RecipientType derives CanEqual:
    case To
    case Cc
    case Bcc
  end RecipientType

  enum OutboxStatus derives CanEqual:
    case Pending
    case Sent
    case Failed
  end OutboxStatus

  final class EmailOutboxEntry(
      val emailId: Long,
      val fromAddress: String,
      val toAddresses: Seq[String],
      val ccAddresses: Seq[String],
      val bccAddresses: Seq[String],
      val subject: String,
      val body: String,
      val status: OutboxStatus,
      val attempts: Int,
      val lastAttemptTime: Option[java.time.Instant],
      val nextAttemptTime: java.time.Instant,
      val creationTime: java.time.Instant,
      val errorMessage: Option[String],
  )
end AppModel
