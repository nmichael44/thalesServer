package app.model

import java.time.Instant

import app.auth.Permissions.Permission

object AppModel:
  final case class BoUser(
      loginName: String,
      firstName: String,
      lastName: String,
      email: String,
      phone: String,
      password: String,
  )

  final case class BoUserInDb(
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

  final case class AuthenticatedBoUser(
      userId: Long,
      permissions: Set[Permission],
      issuedAt: Long,
      origIat: Long,
      expiresAt: Long,
  )

  final case class RoleInDb(
      roleId: Long,
      roleName: String,
  )

  final case class EmailMessage(
      from: String,
      tos: Seq[String],
      ccs: Seq[String],
      bccs: Seq[String],
      subject: String,
      body: String,
  )

  final case class BoRole(roleId: Long, roleName: String)

  final case class LoginUserDetails(loginName: String, password: String)
