package app.services

import app.entrypoints.smithy.{HashedUserPassword, UserPassword}

trait PasswordHasherService[F[_]]:
  def hashPassword(password: UserPassword): F[HashedUserPassword]
  def checkPassword(password: UserPassword, hashedPassword: HashedUserPassword): F[Boolean]

  val dummyHash: HashedUserPassword
end PasswordHasherService
