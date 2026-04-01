package app.workerTasks

import cats.data.{EitherT, ValidatedNec}
import cats.effect.Async
import cats.syntax.all.*

import java.time.Instant

import app.JobSpecs.{CreateUserError, JobKind, JobResult}
import app.ThalesUtils.ExtensionMethodUtils.*
import app.ThalesUtils.GenUtils as U
import app.entrypoints.smithy.{HashedUserPassword, User, UserId}
import app.services.{CreateUserDbError, PasswordHasherService, RepositoryService}
import doobie.{ConnectionIO, Transactor}
import doobie.implicits.*

private final class CreateUser[F[_]: Async] private (
    repoService: RepositoryService,
    xa: Transactor[F],
    passwordHasherService: PasswordHasherService[F],
    wu: WorkerTaskUtils[F],
) extends WorkerTask[F]:
  private val logCreatingUser: EitherT[F, Nothing, Unit] = wu.logT("Creating user.")
  private val logCheckingParamsPasswordValidity: EitherT[F, Nothing, Unit] = wu.logT("Checking params/password validity.")
  private val logParamsValid: EitherT[F, Nothing, Unit] = wu.logT("Parameters look valid/non-empty.")

  private def validateUserParameters(user: User): EitherT[F, CreateUserError, Unit] =
    def verifyNonEmpty(s: String, name: String): ValidatedNec[(String, String), Unit] =
      s.nonEmpty.valid((), (name, "cannot be empty."))
    end verifyNonEmpty

    EitherT.fromEither[F](
      (
        verifyNonEmpty(user.loginName.value, "LoginName"),
        verifyNonEmpty(user.firstName, "FirstName"),
        verifyNonEmpty(user.lastName, "LastName"),
        verifyNonEmpty(user.email, "Email"),
        verifyNonEmpty(user.phone, "Phone"),
        verifyNonEmpty(user.password.value, "Password"),
      ).mapN(U.const6(()))
        .leftMap(errChain => CreateUserError.InvalidParameters(errChain.toNonEmptyVector))
        .toEither,
    )
  end validateUserParameters

  private def createUserDbProgram(
      user: User,
      creationTime: Instant,
      hashedPassword: HashedUserPassword,
      creatingUserId: UserId,
  ): EitherT[ConnectionIO, CreateUserError, UserId] =
    EitherT(
      repoService.createUser(
        user.loginName,
        user.firstName,
        user.lastName,
        user.email,
        user.phone,
        creationTime,
        hashedPassword,
        user.mustResetPassword,
        creationTime,
        user.enabled,
        creatingUserId,
      ),
    ).leftMap { case CreateUserDbError.UniquenessConstraintViolated(nm) =>
      CreateUserError.UniquenessConstraintViolated(nm)
    }
  end createUserDbProgram

  private def createUser(j: JobKind.CreateUserRequest): F[JobResult] =
    val (user, creatingUserId) = (j.user, j.creatingUserId)
    val (loginName, password) = (user.loginName, user.password)

    val res: EitherT[F, CreateUserError, UserId] =
      for
        _ <- logCreatingUser
        _ <- logCheckingParamsPasswordValidity
        _ <- validateUserParameters(user)
        _ <- logParamsValid
        _ <- wu.validatePassword(password, CreateUserError.BadPassword.apply)
        _ <- wu.logT(s"Password is valid. Creating user '${loginName.value}'.")
        hashedPassword <- EitherT.liftF(passwordHasherService.hashPassword(password))
        _ <- wu.logT(hashedPassword.value)
        creationTime <- wu.getNow
        userId <- createUserDbProgram(
          user,
          creationTime,
          hashedPassword,
          creatingUserId,
        ).transact(xa)
      yield userId

    wu.toResult(res, JobResult.CreateUserResult.apply)
  end createUser

  override def work(job: JobKind): F[JobResult] =
    createUser(job.asInstanceOf[JobKind.CreateUserRequest])
  end work
end CreateUser

object CreateUser:
  def create[F[_]: Async](
      repoService: RepositoryService,
      xa: Transactor[F],
      passwordHasherService: PasswordHasherService[F],
      wu: WorkerTaskUtils[F],
  ): WorkerTask[F] =
    CreateUser[F](repoService, xa, passwordHasherService, wu)
  end create
end CreateUser
