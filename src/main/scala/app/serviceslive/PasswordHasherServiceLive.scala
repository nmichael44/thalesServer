package app.serviceslive

import cats.effect.Sync

import app.entrypoints.smithy.{HashedUserPassword, UserPassword}
import app.services.PasswordHasherService
import com.password4j.{Argon2Function, Password}
import com.password4j.types.Argon2

private final class PasswordHasherServiceLive[F[_]: Sync as sync] private extends PasswordHasherService[F]:
  private val argon2Function: Argon2Function =
    PasswordHasherServiceLive.createArgonFunction(
      memory = 65536, // In KB (64MB)
      iterations = 3,
      parallelism = 1,
      outputLength = 32,
      argon2Type = Argon2.ID,
      version = 19,
    )
  end argon2Function

  inline private final val LengthOfSaltValue = 16

  override def hashPassword(password: UserPassword): F[HashedUserPassword] =
    sync.blocking {
      HashedUserPassword(
        Password
          .hash(password.value)
          .addRandomSalt(LengthOfSaltValue)
          .`with`(argon2Function)
          .getResult,
      )
    }
  end hashPassword

  override def checkPassword(password: UserPassword, hashedPassword: HashedUserPassword): F[Boolean] =
    sync.blocking {
      Password.check(password.value, hashedPassword.value).`with`(argon2Function)
    }
  end checkPassword
end PasswordHasherServiceLive

object PasswordHasherServiceLive:
  def create[F[_]: Sync]: PasswordHasherService[F] =
    PasswordHasherServiceLive[F]
  end create

  private def createArgonFunction(
      memory: Int,
      iterations: Int,
      parallelism: Int,
      outputLength: Int,
      argon2Type: Argon2,
      version: Int,
  ) =
    Argon2Function.getInstance(memory, iterations, parallelism, outputLength, argon2Type, version)
  end createArgonFunction
end PasswordHasherServiceLive
