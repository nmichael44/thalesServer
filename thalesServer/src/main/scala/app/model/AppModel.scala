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
end AppModel
