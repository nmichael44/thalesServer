package app.workerTasks

import cats.data.{EitherT, NonEmptyVector}
import cats.effect.{Async, Resource}
import cats.effect.implicits.*
import cats.effect.std.Supervisor
import cats.syntax.all.*

import java.time.Instant
import scala.concurrent.duration.{Duration, DurationInt}

import app.JobSpecs.{JobKind, JobResult, LoginError}
import app.ThalesUtils.ExtensionMethodUtils.liftE
import app.ThalesUtils.GenUtils as U
import app.entrypoints.smithy.{HashedUserPassword, LoginName, PermissionInDb, UserId, UserInDb}
import app.services.{AuthService, ClockService, PasswordHasherService, RepositoryService}
import doobie.{ConnectionIO, Transactor}
import doobie.implicits.*
import org.typelevel.log4cats.Logger

private final class Login[F[_]: Async as async] private (
    repoService: RepositoryService,
    xa: Transactor[F],
    passwordHasherService: PasswordHasherService[F],
    authService: AuthService[F],
    wu: WorkerTaskUtils[F],
) extends WorkerTask[F]:
  private val logLoginFailed: EitherT[F, LoginError, Unit] = wu.logi("Login failed. Invalid password!").liftE
  private val logLoginSuccessful: EitherT[F, LoginError, Unit] = wu.logi("Login was successful!").liftE

  private def pureF[A](a: A): F[A] =
    async.pure(a)
  end pureF

  private def pureC[A](a: A): ConnectionIO[A] =
    doobie.free.connection.pure(a)
  end pureC

  private def checkRateLimit(loginName: LoginName, now: Instant): EitherT[ConnectionIO, LoginError, Unit] =
    for
      cnt <- repoService.fetchCountOfFailedAttempts(loginName, now, Login.LoginAttemptsIntervalInMinutes).liftE
      _ <- wu.failIfC[LoginError](cnt >= Login.NumberOfLoginAttemptsInInterval, LoginError.TooManyLoginAttempts)
    yield ()
  end checkRateLimit

  private def recordFailure(loginName: LoginName, now: Instant): ConnectionIO[Unit] =
    repoService.insertFailedAttempt(loginName, now)
  end recordFailure

  private def deleteFailedAttemptsForLoginName(loginName: LoginName): EitherT[ConnectionIO, LoginError, Unit] =
    repoService.deleteFailedAttemptsForLoginName(loginName).liftE
  end deleteFailedAttemptsForLoginName

  private val invalidLoginPasswordError: EitherT[F, LoginError, (UserId, String)] =
    EitherT(pureF(Left(LoginError.InvalidLoginPassword)))
  end invalidLoginPasswordError

  private type ConIOPasswordUser =
    ConnectionIO[(HashedUserPassword, Option[(UserInDb, Vector[PermissionInDb])])]

  private val userNotFound: ConIOPasswordUser =
    pureC((passwordHasherService.dummyHash, None))
  end userNotFound

  private def userFound(u: UserInDb): ConIOPasswordUser =
    repoService.fetchUserPermissions(u.userId).map(perms => (u.hashedPassword, Some((u, perms))))
  end userFound

  private def login(j: JobKind.LoginRequest): F[JobResult] =
    val (loginName, password) = (j.loginName, j.password)

    val res: EitherT[F, LoginError, (UserId, String)] =
      for
        now <- wu.getNow

        (hashToCheck, userWithPermsOpt) <- (
          for
            _ <- checkRateLimit(loginName, now)
            usersMap <- repoService.fetchUsersByLoginNames(NonEmptyVector.one(loginName)).liftE
            res <- usersMap
              .get(loginName)
              .fold[ConIOPasswordUser](userNotFound)(userFound)
              .liftE
          yield res
        ).transact(xa)

        isPasswordValid <- passwordHasherService.checkPassword(password, hashToCheck).liftE

        r <- (userWithPermsOpt, isPasswordValid) match
          case (Some((user, perms)), true) =>
            for
              _ <- wu.failIfF(!user.enabled, LoginError.UserNotEnabled)
              _ <- wu.failIfF(user.mustResetPassword, LoginError.UserMustResetPassword)
              _ <- deleteFailedAttemptsForLoginName(loginName).transact(xa)
              _ <- logLoginSuccessful
              token <- authService.createToken(user, perms, None).map(t => (user.userId, t)).liftE[LoginError]
            yield token

          case _ => // User not found OR Password invalid. We record failure in both cases to mask user existence.
            recordFailure(loginName, now).transact(xa).liftE[LoginError] *>
              logLoginFailed *>
              invalidLoginPasswordError
      yield r

    wu.toResult(res, JobResult.LoginResult.apply)
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
      wu: WorkerTaskUtils[F],
  ): Login[F] =
    Login(repoService, xa, passwordHasherService, authService, wu)
  end create

  private val LoginAttemptsIntervalInMinutes = 1
  private val NumberOfLoginAttemptsInInterval = 3

  private def deleteOldFailedLoginAttempts[F[_]: Async](
      repoService: RepositoryService,
      xa: Transactor[F],
      now: Instant,
  ): F[Int] =
    repoService
      .deleteOldLoginFailedAttempts(now, LoginAttemptsIntervalInMinutes)
      .transact(xa)
  end deleteOldFailedLoginAttempts

  private val delayBetweenCleanups: Duration = 10.minutes
  private val delayWhenErrorIsEncountered: Duration = 30.seconds
  private val failedAttemptsCleanupWorkerFiberName: String = "FailedAttemptsCleanupWorker"

  def createFailedAttemptsCleanupWorker[F[_]: { Async as async, Logger as logger }](
      repoService: RepositoryService,
      xa: Transactor[F],
      clockService: ClockService[F],
      supervisor: Supervisor[F],
  ): Resource[F, Unit] =
    def logi(s: String): F[Unit] = U.logi(failedAttemptsCleanupWorkerFiberName, s)
    def loge(e: Throwable, s: String): F[Unit] = U.loge(e, failedAttemptsCleanupWorkerFiberName, s)

    val getNow = clockService.nowInstant
    val takeALongBreak = async.sleep(delayBetweenCleanups)
    val takeAShortBreak = async.sleep(delayWhenErrorIsEncountered)

    val logTakeAShortBreak = logi("Taking a short break and trying again...")
    val logStartingAFailedAttemptsCleanupRun = logi("Starting a Failed Attempts Cleanup Run...")
    val logFailedAttemptsCleanupRunEndedGoingToSleep = logi("Failed Attempts Cleanup Run ended. Going to sleep...")
    def logNumberOfDeletedRows(rowsDeleted: Int) = logi(s"Deleted $rowsDeleted rows (old failed login attempts).")

    val oneRun =
      for
        _ <- logStartingAFailedAttemptsCleanupRun
        now <- getNow
        cnt <- deleteOldFailedLoginAttempts(repoService, xa, now)
        _ <- logNumberOfDeletedRows(cnt)
        _ <- logFailedAttemptsCleanupRunEndedGoingToSleep
        _ <- takeALongBreak
      yield ()

    val fullJob: F[Unit] = oneRun.handleErrorWith { e =>
      loge(e, "Exception in Failed Attempts Cleanup Worker") *>
        logTakeAShortBreak *>
        takeAShortBreak
    }.foreverM

    Resource.eval(supervisor.supervise(fullJob).void)
  end createFailedAttemptsCleanupWorker
end Login
