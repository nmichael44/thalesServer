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

  final case class EmailOutboxEntry(
      emailId: Long,
      fromAddress: String,
      toAddresses: Seq[String],
      ccAddresses: Seq[String],
      bccAddresses: Seq[String],
      subject: String,
      body: String,
      status: OutboxStatus,
      attempts: Int,
      lastAttemptTime: Option[java.time.Instant],
      nextAttemptTime: java.time.Instant,
      creationTime: java.time.Instant,
      errorMessage: Option[String]
  )
end AppModel
