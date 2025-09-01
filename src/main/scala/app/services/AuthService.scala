package app.services

import app.auth.Permissions.Permission
import app.model.AppModel.{AuthenticatedBoUser, BoUserInDb}

enum RenewalError:
  case NoSuchUser
  case UserIsDisabled
  case UserMustResetPassword
  case RenewalTimeHasExpired
end RenewalError

given CanEqual[RenewalError, RenewalError] = CanEqual.derived

trait AuthService[F[_]]:
  def createToken(user: BoUserInDb, permissions: Seq[Permission], origIatOpt: Option[Long]): F[String]
  def validateToken(token: String): F[Either[Throwable, AuthenticatedBoUser]]
  def renewToken(authenticatedBoUser: AuthenticatedBoUser): F[Either[RenewalError, String]]
end AuthService
