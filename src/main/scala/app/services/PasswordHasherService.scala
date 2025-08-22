package app.services

trait PasswordHasherService[F[_]]:
  def hashPassword(password: String): F[String]
  def checkPassword(password: String, hash: String): F[Boolean]
end PasswordHasherService
