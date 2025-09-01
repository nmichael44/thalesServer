package app

import cats.data.{EitherT, NonEmptyVector, Validated}
import cats.effect.std.Queue
import cats.effect.Async
import cats.syntax.all.*

import scala.concurrent.duration.*
import scala.util.control.NoStackTrace

import app.model.AppModel
import app.services.{AuthService, BoRepositoryService, ExternalApiClientService, PasswordHasherService, RenewalError, ServerState}
import app.services.given
import app.services.CreateBoUserDbError
import app.AppDependencies
import app.Config.AppConfig.AppConfig
import app.JobSpecs.{CreateBoUserError, FetchBoUserByError, JobKind, JobResult, LoginError, RenewJwtTokenError}
import app.JobSpecs.JobResult.FetchAllLiveSessionsResult
import app.JobSpecs.JobResult.FetchMultipleBoUsersByIdResult
import app.JobSpecs.ResetBoUserPasswordError
import app.ThalesUtils.{GenUtils, GenUtils as U, PasswordValidationUtils, TimeUtils}
import app.ThalesUtils.ImplicitConversionUtils.*
import org.typelevel.log4cats.Logger

object HttpWorker:
  final case class UserSessionTimedOut(loginName: String) extends RuntimeException(s"User '$loginName' session timed out.")

  private final class JobExecutor[F[_]: { Async as async, Logger }](deps: AppDependencies[F]):
    private val boRepoService: BoRepositoryService[F] = deps.boRepositoryService
    private val apiClient: ExternalApiClientService[F] = deps.externalApiClientService
    private val passwordHasherService: PasswordHasherService[F] = deps.passwordHasherService
    private val authService: AuthService[F] = deps.authService
    private val serverState: ServerState[F] = deps.serverState

    val uuidScope: TraceIdScope[F, Option[String]] = deps.uuidScope

    private val WorkerFiberName = "Http Worker"

    def logi(s: String): F[Unit] =
      uuidScope.get >>= (uuidOpt => uuidOpt.fold(U.logi(WorkerFiberName, s))(U.logi(WorkerFiberName, _, s)))
    end logi

    def loge(e: Throwable, s: String): F[Unit] =
      uuidScope.get >>= (uuidOpt => uuidOpt.fold(U.loge(e, WorkerFiberName, s))(U.loge(e, WorkerFiberName, _, s)))
    end loge

    private def validateBoUserParameters(boUser: AppModel.BoUser): EitherT[F, CreateBoUserError, Unit] =
      val cannotBeEmpty = "cannot be empty."
      (
        boUser.loginName.nonEmpty.valid((), ("LoginName", cannotBeEmpty)),
        boUser.firstName.nonEmpty.valid((), ("FirstName", cannotBeEmpty)),
        boUser.lastName.nonEmpty.valid((), ("LastName", cannotBeEmpty)),
        boUser.email.nonEmpty.valid((), ("Email", cannotBeEmpty)),
        boUser.phone.nonEmpty.valid((), ("Phone", cannotBeEmpty)),
        boUser.password.nonEmpty.valid((), ("Password", cannotBeEmpty)),
      ).mapN(GenUtils.const6(()))
        .leftMap(errChain => CreateBoUserError.InvalidParameters(errChain.toNonEmptyVector))
        .toEither
        .toEitherT
    end validateBoUserParameters

    private def validatePassword[E](password: String, e: NonEmptyVector[String] => E): EitherT[F, E, Unit] =
      PasswordValidationUtils
        .isPasswordGoodEnough(password)
        .toEither
        .leftMap(e)
        .toEitherT
    end validatePassword

    private val logCreatingBoUser: EitherT[F, Nothing, Unit] = logi("Creating BO user.").lifte

    private val logCheckingParamsPasswordValidity: EitherT[F, Nothing, Unit] = logi("Checking params/password validity.").lifte

    private val logParamsValid: EitherT[F, Nothing, Unit] = logi(s"Parameters look valid/non-empty.").lifte

    private def createBoUser(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.CreateBoUserRequest]
      val boUser = j.boUser
      val (loginName, password) = (boUser.loginName, boUser.password)

      val res: EitherT[F, CreateBoUserError, Long] = for {
        _ <- logCreatingBoUser
        _ <- logCheckingParamsPasswordValidity
        _ <- validateBoUserParameters(boUser)
        _ <- logParamsValid
        _ <- validatePassword(password, CreateBoUserError.BadPassword.apply)
        _ <- logi(s"Password is valid. Creating BO user '$loginName'.").lifte
        hashedPassword <- passwordHasherService.hashPassword(password).lifte
        _ <- logi(hashedPassword).lifte
        creationTime <- TimeUtils.nowInstant.lifte
        userId <- boRepoService
          .createBoUser(
            loginName,
            boUser.firstName,
            boUser.lastName,
            boUser.email,
            boUser.phone,
            creationTime,
            hashedPassword,
            true,
            creationTime,
            true,
          )
          .toEitherT
          .leftMap { case CreateBoUserDbError.DuplicateLoginName(nm) => CreateBoUserError.DuplicateLoginName(nm) }
      } yield userId

      res.value.map(JobResult.CreateBoUserResult.apply)
    end createBoUser

    private val logFetchingBoUserByLoginName: F[Unit] = logi("Fetching BO user by loginName.")

    private def fetchBoUserByLoginName(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.FetchBoUserByLoginNameRequest]
      val loginName = j.loginName

      for {
        _ <- logFetchingBoUserByLoginName
        res <- boRepoService
          .fetchBoUserByLoginName(loginName)
          .map(_.toRight(FetchBoUserByError.UserNotFound()))
      } yield JobResult.FetchBoUserByLoginNameResult(res)
    end fetchBoUserByLoginName

    private val logFetchingBoUserByUserId: F[Unit] = logi("Fetching BO user by userId.")

    private def fetchBoUserByUserId(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.FetchBoUserByIdRequest]
      val userId = j.userId

      for {
        _ <- logFetchingBoUserByUserId
        res <- boRepoService.fetchBoUserById(userId).map(_.toRight(FetchBoUserByError.UserNotFound()))
      } yield JobResult.FetchBoUserByIdResult(res)
    end fetchBoUserByUserId

    private val logFetchMultipleBoUsersById: F[Unit] = logi("Fetching BO user by userId.")

    private def fetchMultipleBoUsersById(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.FetchMultipleBoUsersByIdRequest]
      val userIds = j.userIds

      for {
        _ <- logFetchMultipleBoUsersById
        res <- boRepoService.fetchMultipleBoUsersById(userIds)
      } yield FetchMultipleBoUsersByIdResult(res)
    end fetchMultipleBoUsersById

    private def logLoginFailed[E](e: E): F[Unit] = logi("Login failed. Invalid password!")

    private def logLoginSuccessful(b: Boolean): F[Unit] = logi("Login was successful!")

    private def checkPassword[Error](
        password: String,
        boUserInDb: AppModel.BoUserInDb,
        e: () => Error,
    ): EitherT[F, Error, Boolean] =
      passwordHasherService
        .checkPassword(password, boUserInDb.hashedPassword)
        .lifte
        .ensure(e())(identity)
        .biSemiflatTap(logLoginFailed, logLoginSuccessful)
    end checkPassword

    private def login(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.LoginRequest]
      val ud = j.loginUserDetails
      val (loginName, password) = (ud.loginName, ud.password)

      val res: EitherT[F, LoginError, (Long, String)] = for {
        boUserInDb <- boRepoService.fetchBoUserByLoginName(loginName).toEitherT(LoginError.InvalidLoginPassword())
        _ <- logi("Fetched user and it was: $boUserInDb").lifte
        _ <- EitherT.cond[F](boUserInDb.enabled, (), LoginError.UserNotEnabled())
        _ <- EitherT.cond[F](!boUserInDb.mustResetPassword, (), LoginError.UserMustResetPassword())
        _ <- checkPassword[LoginError](password, boUserInDb, LoginError.InvalidLoginPassword.apply)
        permissions <- boRepoService.fetchBoUserPermissions(boUserInDb.userId).lifte
        token <- authService.createToken(boUserInDb, permissions, None).lifte
      } yield (boUserInDb.userId, token)

      res.value.map(JobResult.LoginResult.apply)
    end login

    private def renewJwtToken(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.RenewJwtTokenRequest]
      val authenticatedBoUser = j.authenticatedBoUser
      val userId = authenticatedBoUser.userId

      val res = authService.renewToken(authenticatedBoUser).map {
        case Left(RenewalError.NoSuchUser) => JobResult.RenewJwtTokenResult(Left(RenewJwtTokenError.NoSuchUser(userId)))
        case Left(RenewalError.UserIsDisabled) => JobResult.RenewJwtTokenResult(Left(RenewJwtTokenError.UserIsDisabled(userId)))
        case Left(RenewalError.UserMustResetPassword) =>
          JobResult.RenewJwtTokenResult(Left(RenewJwtTokenError.UserMustResetPassword(userId)))
        case Left(RenewalError.RenewalTimeHasExpired) =>
          JobResult.RenewJwtTokenResult(Left(RenewJwtTokenError.RenewalTimeHasExpired()))
        case Right(token) => JobResult.RenewJwtTokenResult(Right(token))
      }
      res
    end renewJwtToken

    private val logFetchingUserFromDb = logi("Fetching user from database...").lifte
    private val logCheckingOldPassword = logi("Checking old password...").lifte
    private val logCheckingValidityOfNewPassword = logi("Checking validity of new password...").lifte
    private val logComputingHashAndUpdatingDb = logi(s"Password is valid. Computing hash and updating db.").lifte

    private def resetBoUserPassword(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.ResetBoUserPasswordRequest]
      val (loginName, oldPassword, newPassword) = (j.loginName, j.oldPassword, j.newPassword)

      val res: EitherT[F, ResetBoUserPasswordError, Unit] = for {
        boUserInDb <- boRepoService.fetchBoUserByLoginName(loginName).toEitherT(ResetBoUserPasswordError.LoginNameNotFound())
        _ <- logFetchingUserFromDb
        _ <- EitherT.cond[F](boUserInDb.enabled, (), ResetBoUserPasswordError.UserNotEnabled())
        _ <- logCheckingOldPassword
        _ <- checkPassword[ResetBoUserPasswordError](
          oldPassword,
          boUserInDb,
          ResetBoUserPasswordError.InvalidLoginPassword.apply,
        )
        _ <- logCheckingValidityOfNewPassword
        _ <- validatePassword(newPassword, ResetBoUserPasswordError.NewPasswordInsufficient.apply)
        _ <- logComputingHashAndUpdatingDb
        hashedPassword <- passwordHasherService.hashPassword(newPassword).lifte
        cnt <- boRepoService.updateBoUserPasswordInDb(boUserInDb.userId, hashedPassword).lifte
        _ <- EitherT.cond[F](
          cnt == 1,
          (),
          ResetBoUserPasswordError.FailedToUpdateUserRow(s"Expected 1 row to be updated, but in fact updated $cnt."),
        )
      } yield ()

      res.value.map(JobResult.ResetBoUserPasswordResult.apply)
    end resetBoUserPassword

    private def fetchAllLiveSessions(jk: JobKind): F[JobResult] =
      serverState.lastAccess.get >>= { lastAccess =>
        if lastAccess.isEmpty then
          // This case may appear impossible (since we are logged in at the moment),
          // but best to be safe than sorry.  We may add functionality later to remove
          // all users from the system in which case this can happen.
          FetchAllLiveSessionsResult(Vector.empty).pure
        else {
          val userIds = NonEmptyVector.fromVectorUnsafe(lastAccess.keys.toVector)

          boRepoService.fetchMultipleBoUsersById(userIds).map { boUsers =>
            val res = boUsers.view.map((userId, boUser) => (boUser, lastAccess(userId))).toVector
            FetchAllLiveSessionsResult(res)
          }
        }
      }
    end fetchAllLiveSessions

    private val JobHandlersMap: Map[Class[? <: JobKind], JobKind => F[JobResult]] = Map(
      classOf[JobKind.CreateBoUserRequest]             -> createBoUser,
      classOf[JobKind.ResetBoUserPasswordRequest]      -> resetBoUserPassword,
      classOf[JobKind.FetchBoUserByLoginNameRequest]   -> fetchBoUserByLoginName,
      classOf[JobKind.FetchBoUserByIdRequest]          -> fetchBoUserByUserId,
      classOf[JobKind.FetchMultipleBoUsersByIdRequest] -> fetchMultipleBoUsersById,
      classOf[JobKind.LoginRequest]                    -> login,
      classOf[JobKind.RenewJwtTokenRequest]            -> renewJwtToken,
      classOf[JobKind.FetchAllLiveSessionsRequest]     -> fetchAllLiveSessions,
    )

    private def misingJobImplementationException(job: JobKind): Exception =
      new Exception(s"JobHandlersMap does not contain an implementation for class '${job.shortName}'.") with NoStackTrace
    end misingJobImplementationException

    def executeJob(job: JobKind): F[JobResult] =
      JobHandlersMap
        .get(job.getClass)
        .map(_(job))
        .getOrElse(async.raiseError(misingJobImplementationException(job)))
    end executeJob
  end JobExecutor

  private val SessionTimeoutDurationInSeconds: Long = 1.hour.toSeconds

  private def createWorker[F[_]: { Async as async }](queue: Queue[F, WorkerJob[F]], je: JobExecutor[F]): F[Nothing] =
    val logWaitingForWork = je.logi("Waiting for work.")
    val logSendingResultsBack = je.logi("Done. Sending results back...")
    val getJobFromQueue = queue.take.map(j => (j.job, j.deferred, j.uuid))
    val onErrorInner = je.loge(_, "Error while processing job. The job will be dropped.")
    val onErrorOuter = (e: Throwable) =>
      je.loge(e, "A non-recoverable error occurred in the worker loop. Restarting....") *>
        async.sleep(500.milliseconds)

    val processOneJob: F[Unit] = for {
      _ <- logWaitingForWork
      (job, deferred, uuid) <- getJobFromQueue
      _ <- je.uuidScope.scope(Some(uuid)).use { _ =>
        val jobExecution: F[Unit] = for {
          _ <- je.logi(s"Starting to work on ${job.shortName}...")
          outcome <- je.executeJob(job).attempt
          _ <- logSendingResultsBack
          _ <- deferred.complete(outcome)
        } yield ()

        jobExecution.handleErrorWith(onErrorInner)
      }
    } yield ()

    val processSafely = processOneJob.handleErrorWith(onErrorOuter)

    processSafely.foreverM
  end createWorker

  def createWorkers[F[_]: { Async, Logger }](appConfig: AppConfig, deps: AppDependencies[F]): F[Unit] =
    val jobExecutor: JobExecutor[F] = JobExecutor(deps)

    val numberOfWorkers = appConfig.getBackendServerConfig.getNumberOfWorkers
    val worker = createWorker(deps.serverState.jobQueue, jobExecutor)
    val supervisor = deps.supervisor

    Vector
      .from(0 until numberOfWorkers)
      .traverseVoid(_ => supervisor.supervise(worker))
  end createWorkers
end HttpWorker
