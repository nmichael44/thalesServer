package app.services

import app.auth.Permissions.Permission
import app.model.AppModel.{AuthenticatedBoUser, BoUserInDb}

enum RenewalResponse:
  case RenewalTimeHasExpired
  case UserIsDisabled
  case RenewedToken(token: String)

trait AuthService[F[_]]:
  def createToken(user: BoUserInDb, permissions: Seq[Permission], origIatOpt: Option[Long]): F[String]
  def validateToken(token: String): F[Either[Throwable, AuthenticatedBoUser]]
  def renewToken(token: String): F[Either[Throwable, RenewalResponse]]
end AuthService
