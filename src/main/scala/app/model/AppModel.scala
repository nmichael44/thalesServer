package app.model

object AppModel:
  final case class AuthenticatedUser(
      userId: Long,
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

  final case class LoginUserDetails(loginName: String, password: String)
end AppModel
