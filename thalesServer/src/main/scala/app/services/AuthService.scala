package app.services

import app.entrypoints.smithy.{PermissionInDb, UserInDb}
import app.model.AppModel.AuthenticatedUser

enum RenewalError:
  case NoSuchUser
  case UserIsDisabled
  case UserMustResetPassword
  case RenewalTimeHasExpired
end RenewalError

given CanEqual[RenewalError, RenewalError] = CanEqual.derived

trait AuthService[F[_]]:
  def createToken(user: UserInDb, permissions: Vector[PermissionInDb], origIatOpt: Option[Long]): F[String]
  def validateToken(token: String): F[Either[Throwable, AuthenticatedUser]]
  def renewToken(authenticatedBoUser: AuthenticatedUser): F[Either[RenewalError, String]]
end AuthService
