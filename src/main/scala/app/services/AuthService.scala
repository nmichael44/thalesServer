package app.services

import app.entrypoints.smithy.{PermissionInDb, UserInDb}
import app.model.AppModel.{AuthenticatedUser, JwtToken}

enum RenewalError derives CanEqual:
  case NoSuchUser
  case UserIsDisabled
  case UserMustResetPassword
  case RenewalTimeHasExpired
end RenewalError

trait AuthService[F[_]]:
  def createToken(user: UserInDb, permissions: Vector[PermissionInDb], origIatOpt: Option[Long]): F[JwtToken]
  def validateToken(token: JwtToken): F[Either[Throwable, AuthenticatedUser]]
  def renewToken(authenticatedUser: AuthenticatedUser): F[Either[RenewalError, JwtToken]]
end AuthService
