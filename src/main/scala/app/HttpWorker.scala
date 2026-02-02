package app

import cats.effect.Async
import cats.effect.std.Queue
import cats.implicits.*
import cats.syntax.all.*

import scala.collection.View
import scala.concurrent.duration.*
import scala.util.control.NoStackTrace

import app.AppDependencies
import app.Config.AppConfig.AppConfig
import app.JobSpecs.{JobKind, JobResult}
import app.ThalesUtils.GenUtils as U
import app.services.{AuthService, ClockService, ExternalApiClientService, PasswordHasherService, RepositoryService, ServerState, given}
import app.uuid.UUIDGenerator
import app.workerTasks.WorkerTaskUtils
import doobie.util.transactor.Transactor
import org.typelevel.log4cats.Logger

object HttpWorker:
  private val workerFiberName: String = "Http Worker"

  private final class JobExecutor[F[_]: { Async as async, Logger }](deps: AppDependencies[F]):
    private val repoService: RepositoryService = deps.repositoryService
    private val apiClient: ExternalApiClientService[F] = deps.externalApiClientService
    private val passwordHasherService: PasswordHasherService[F] = deps.passwordHasherService
    private val authService: AuthService[F] = deps.authService
    private val serverState: ServerState[F] = deps.serverState
    private val clockService: ClockService[F] = deps.clockService
    private val uuidGen: UUIDGenerator[F] = deps.uuidGen
    private val xa: Transactor[F] = deps.xa
    val uuidScope: TraceIdScope[F, Option[String]] = deps.uuidScope
    private val wu: WorkerTaskUtils[F] = WorkerTaskUtils.create[F](uuidScope, clockService, workerFiberName)

    val logi: String => F[Unit] = wu.logi
    val loge: (Throwable, String) => F[Unit] = wu.loge

    private val jobHandlersMap: Map[Class[? <: JobKind], JobKind => F[JobResult]] =
      import app.workerTasks.*
      import JobKind.*
      import U.->

      View(
        classOf[CreateUserRequest]                       -> CreateUser.create(repoService, xa, passwordHasherService, wu),
        classOf[CreateRoleRequest]                       -> CreateRole.create(repoService, xa, wu),
        classOf[ResetMyPasswordRequest]                  -> ResetMyPassword.create(repoService, xa, passwordHasherService, wu),
        classOf[FetchUsersByLoginNamesRequest]           -> FetchUsersByLoginNames.create(repoService, xa, wu),
        classOf[FetchUsersByUserIdsRequest]              -> FetchUsersByUserIds.create(repoService, xa, wu),
        classOf[LoginRequest]                            -> Login.create(repoService, xa, passwordHasherService, authService, wu),
        classOf[RenewJwtTokenRequest]                    -> RenewJwtToken.create(repoService, xa, authService, wu),
        classOf[DeleteRoleByIdRequest]                   -> DeleteRoleById.create(repoService, xa, wu),
        classOf[FetchAllUsersAssociatedWithRolesRequest] -> FetchAllUsersAssociatedWithRoles.create(repoService, xa, wu),
        classOf[FetchRolesByIdsRequest]                  -> FetchRolesByIds.create(repoService, xa, wu),
        classOf[CheckResetUserPasswordTokenRequest]      -> CheckResetUserPasswordToken.create(repoService, xa, wu),
        classOf[InitiateRecoveryOfUserPasswordRequest]   -> InitiateRecoveryOfUserPassword.create(repoService, xa, uuidGen, wu),
        classOf[ResetUserPasswordRequest]                -> ResetUserPassword.create(repoService, xa, passwordHasherService, wu),
        classOf[FetchRolesPermissionsByIdRequest]        -> FetchRolesPermissionsById.create(repoService, xa, wu),
        classOf[SetMustResetUserPasswordRequest]         -> SetMustResetUserPassword.create(repoService, xa, wu),
        FetchAllLiveSessionsRequest.getClass             -> FetchAllLiveSessions.create(repoService, xa, serverState, wu),
        FetchAllPermissionsRequest.getClass              -> FetchAllPermissions.create(repoService, xa, wu),
        FetchAllRolesRequest.getClass                    -> FetchAllRoles.create(repoService, xa, wu),
      ).map(U.mapSecond(_.work)).toMap
    end jobHandlersMap

    private def missingJobError(job: JobKind): F[JobResult] =
      async.raiseError(
        new Exception(s"JobHandlersMap does not contain an implementation for class '${job.shortName}'.") with NoStackTrace,
      )
    end missingJobError

    def executeJob(job: JobKind): F[JobResult] =
      jobHandlersMap
        .get(job.getClass)
        .map(_(job))
        .getOrElse(missingJobError(job))
    end executeJob
  end JobExecutor

  private def createWorker[F[_]: Async as async](queue: Queue[F, WorkerJob[F]], je: JobExecutor[F]): F[Nothing] =
    val logWaitingForWork = je.logi("Waiting for work.")
    val logSendingResultsBack = je.logi("Done. Sending results back...")
    val getJobFromQueue = queue.take.map(j => (j.job, j.deferred, j.uuid))
    val onErrorInner = je.loge(_, "Error while processing job. The job will be dropped.")
    val onErrorOuter: Throwable => F[Unit] = (e: Throwable) =>
      je.loge(e, "A non-recoverable error occurred in the worker loop. Restarting....") *>
        async.sleep(500.milliseconds)

    val processOneJob: F[Unit] =
      for
        _ <- logWaitingForWork
        (job, deferred, uuid) <- getJobFromQueue
        _ <- je.uuidScope.scope(Some(uuid)).use { _ =>
          val jobExecution: F[Unit] =
            for
              _ <- je.logi(s"Starting to work on ${job.shortName}...")
              outcome <- je.executeJob(job).attempt
              _ <- logSendingResultsBack
              _ <- deferred.complete(outcome)
            yield ()

          jobExecution.handleErrorWith(onErrorInner)
        }
      yield ()

    val processSafely = processOneJob.handleErrorWith(onErrorOuter)

    processSafely.foreverM
  end createWorker

  def createWorkers[F[_]: { Async, Logger }](appConfig: AppConfig, deps: AppDependencies[F]): F[Unit] =
    val jobExecutor: JobExecutor[F] = JobExecutor(deps)
    val serverState = deps.serverState

    val numberOfWorkers = appConfig.getBackendServerConfig.getNumberOfWorkers
    val worker = createWorker(serverState.jobQueue, jobExecutor)
    val supervisor = deps.supervisor

    Vector
      .from(0 until numberOfWorkers)
      .traverseVoid(_ => supervisor.supervise(worker))
  end createWorkers
end HttpWorker
