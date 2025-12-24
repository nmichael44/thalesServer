package app.model

import java.time.Instant
import app.auth.Permissions.{Permission, PermissionInDb, UserPermissions}

object AppModel:
  final case class User(
      loginName: String,
      firstName: String,
      lastName: String,
      email: String,
      phone: String,
      password: String,
  )

  final case class UserInDb(
      userId: Long,
      loginName: String,
      firstName: String,
      lastName: String,
      email: String,
      phone: String,
      creationTime: Instant,
      hashedPassword: String,
      mustResetPassword: Boolean,
      userPasswordUpdateTime: Instant,
      enabled: Boolean,
  )

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

  // final case class Role(roleName: String)

  final case class LoginUserDetails(loginName: String, password: String)
end AppModel
