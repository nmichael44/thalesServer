package app

import cats.data.{EitherT, NonEmptyVector, Validated}
import cats.effect.std.Queue
import cats.effect.Async
import cats.syntax.all.*

import scala.concurrent.duration.*
import scala.util.control.NoStackTrace

import app.model.AppModel
import app.services.{AuthService, BoRepositoryService, ExternalApiClientService, PasswordHasherService, ServerState}
import app.services.CreateBoUserDbError
import app.AppDependencies
import app.Config.AppConfig.AppConfig
import app.JobSpecs.{CreateBoUserError, FetchBoUserByError, JobKind, JobResult, LoginError}
import app.JobSpecs.JobKind.FetchMultipleBoUsersByIdRequest
import app.JobSpecs.JobResult.FetchAllLiveSessionsResult
import app.JobSpecs.JobResult.FetchMultipleBoUsersByIdResult
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

    private def validatePassword(password: String): EitherT[F, CreateBoUserError, Unit] =
      PasswordValidationUtils
        .isPasswordGoodEnough(password)
        .toEither
        .leftMap(CreateBoUserError.BadPassword.apply)
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
        _ <- validatePassword(password)
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
      val j = jk.asInstanceOf[FetchMultipleBoUsersByIdRequest]
      val userIds = j.userIds

      for {
        _ <- logFetchMultipleBoUsersById
        res <- boRepoService.fetchMultipleBoUsersById(userIds)
      } yield FetchMultipleBoUsersByIdResult(res)
    end fetchMultipleBoUsersById

    private val logLoginFailed: LoginError => F[Unit] = _ => logi("Login failed. Invalid password!")

    private val logLoginSuccessful: Boolean => F[Unit] = _ => logi("Login was successful!")

    private def loginRequest(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.LoginRequest]
      val ud = j.loginUserDetails
      val (loginName, password) = (ud.loginName, ud.password)

      val res: EitherT[F, LoginError, (Long, String)] = for {
        boUserInDb <- boRepoService.fetchBoUserByLoginName(loginName).toEitherT(LoginError.InvalidLoginPassword())
        _ <- logi("Fetched user and it was: $boUserInDb").lifte
        _ <- EitherT.cond[F](boUserInDb.enabled, (), LoginError.UserNotEnabled(loginName))
        _ <- passwordHasherService
          .checkPassword(password, boUserInDb.hashedPassword)
          .lifte
          .ensure(LoginError.InvalidLoginPassword())(identity)
          .biSemiflatTap(logLoginFailed, logLoginSuccessful)

        permissions <- boRepoService.fetchBoUserPermissions(boUserInDb.userId).lifte
        token <- authService.createToken(boUserInDb, permissions, None).lifte
      } yield (boUserInDb.userId, token)

      res.value.map(JobResult.LoginResult.apply)
    end loginRequest

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
      classOf[JobKind.FetchBoUserByLoginNameRequest]   -> fetchBoUserByLoginName,
      classOf[JobKind.FetchBoUserByIdRequest]          -> fetchBoUserByUserId,
      classOf[JobKind.FetchMultipleBoUsersByIdRequest] -> fetchMultipleBoUsersById,
      classOf[JobKind.LoginRequest]                    -> loginRequest,
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
