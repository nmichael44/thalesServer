package app.workers

import cats.data.{EitherT, NonEmptyVector, OptionT}
import cats.effect.Async

import app.JobSpecs.{JobKind, JobResult, LoginError}
import app.ThalesUtils.ExtensionMethodUtils.liftE
import app.entrypoints.smithy.{PermissionInDb, UserId, UserInDb, UserPassword}
import app.services.{AuthService, PasswordHasherService, RepositoryService}
import doobie.{ConnectionIO, Transactor}
import doobie.implicits.*

private final class Login[F[_]: Async] private (
    repoService: RepositoryService,
    xa: Transactor[F],
    passwordHasherService: PasswordHasherService[F],
    authService: AuthService[F],
    wu: WorkerUtils[F],
) extends HttpWorkerTask[F]:
  private def logLoginFailed[E](e: E): F[Unit] = wu.logi("Login failed. Invalid password!")

  private def logLoginSuccessful(b: Boolean): F[Unit] = wu.logi("Login was successful!")

  private def checkPassword[E](password: UserPassword, userInDb: UserInDb, e: E): EitherT[F, E, Boolean] =
    passwordHasherService
      .checkPassword(password, userInDb.hashedPassword)
      .liftE
      .ensure(e)(identity)
      .biSemiflatTap(logLoginFailed, logLoginSuccessful)
  end checkPassword

  private def login(j: JobKind.LoginRequest): F[JobResult] =
    val (loginName, password) = (j.loginName, j.password)
    val loginNamesVec = NonEmptyVector.one(loginName)

    val fetchUserAndPermissionsDbProgram: OptionT[ConnectionIO, (UserInDb, Vector[PermissionInDb])] =
      for {
        usersMap <- OptionT.liftF(repoService.fetchUsersByLoginNames(loginNamesVec))
        user <- OptionT.fromOption[ConnectionIO](usersMap.get(loginName))
        perms <- OptionT.liftF(repoService.fetchUserPermissions(user.userId))
      } yield (user, perms)

    val program: EitherT[F, LoginError, (UserId, String)] = for {
      (userInDb, permissionsInDb) <-
        fetchUserAndPermissionsDbProgram
          .transact(xa)
          .toRight(LoginError.InvalidLoginPassword)
      _ <- wu.failIfF(!userInDb.enabled, LoginError.UserNotEnabled)
      _ <- wu.failIfF(userInDb.mustResetPassword, LoginError.UserMustResetPassword)
      _ <- checkPassword[LoginError](password, userInDb, LoginError.InvalidLoginPassword)
      token <- authService.createToken(userInDb, permissionsInDb, None).liftE[LoginError]
    } yield (userInDb.userId, token)

    wu.toResult(program, JobResult.LoginResult.apply)
  end login

  override def work(job: JobKind): F[JobResult] =
    login(job.asInstanceOf[JobKind.LoginRequest])
  end work
end Login

object Login:
  def create[F[_]: Async](
      repoService: RepositoryService,
      xa: Transactor[F],
      passwordHasherService: PasswordHasherService[F],
      authService: AuthService[F],
      wu: WorkerUtils[F],
  ) =
    Login(repoService, xa, passwordHasherService, authService, wu)
  end create
end Login
