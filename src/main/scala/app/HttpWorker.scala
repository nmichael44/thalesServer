package app

import cats.effect.{Async, Resource}
import cats.effect.std.Queue
import cats.effect.syntax.all.*
import cats.implicits.*
import cats.syntax.all.*

import scala.collection.View
import scala.concurrent.duration.*
import scala.reflect.ClassTag
import scala.util.control.NoStackTrace

import app.AppDependencies
import app.Config.AppConfigUtils.AppConfig
import app.JobSpecs.{JobKind, JobResult}
import app.ThalesUtils.GenUtils as U
import app.audit_log.AuditLogUtils.DomainEvent
import app.services.{AuthService, ClockService, ExternalApiClientService, PasswordHasherService, RepositoryService, ServerState, given}
import app.uuid.UUIDGenerator
import app.workerTasks.WorkerTaskUtils
import doobie.util.transactor.Transactor
import fs2.concurrent.Topic
import org.typelevel.log4cats.Logger

object HttpWorker:
  private val workerFiberName: String = "Http Worker"

  private final class JobExecutor[F[_]: { Async as async, Logger }](deps: AppDependencies[F], auditLogTopic: Topic[F, DomainEvent]):
    private val repoService: RepositoryService = deps.repositoryService
    private val apiClient: ExternalApiClientService[F] = deps.externalApiClientService
    private val passwordHasherService: PasswordHasherService[F] = deps.passwordHasherService
    private val authService: AuthService[F] = deps.authService
    private val serverState: ServerState[F] = deps.serverState
    private val clockService: ClockService[F] = deps.clockService
    private val uuidGen: UUIDGenerator[F] = deps.uuidGen
    private val xa: Transactor[F] = deps.xa
    private val uuidScope: TraceIdScope[F, Option[String]] = deps.uuidScope
    private val wu: WorkerTaskUtils[F] = WorkerTaskUtils.create[F](uuidScope, clockService, workerFiberName)

    val logi: String => F[Unit] = wu.logi
    val loge: (Throwable, String) => F[Unit] = wu.loge

    def execWithUUID[A](uuid: String)(f: F[A]): F[A] =
      uuidScope.scope(Some(uuid)).use(_ => f)
    end execWithUUID

    def publishEvent(outcome: Either[Throwable, JobResult], job: JobKind): F[Unit] =
      outcome.toOption
        .flatMap(resultToDomainEvent(job, _))
        .traverseVoid(auditLogTopic.publish1)
    end publishEvent

    private val jobHandlersMap: Map[Class[? <: JobKind], JobKind => F[JobResult]] =
      import app.workerTasks.*
      import JobKind.*
      import U.->

      U.toMap(
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
          classOf[UpdateUserRolesByIdRequest]              -> UpdateUserRolesById.create(repoService, xa, wu),
          classOf[FetchUserRoleIdsRequest]                 -> FetchUserRoleIds.create(repoService, xa, wu),
          FetchAllLiveSessionsRequest.getClass             -> FetchAllLiveSessions.create(repoService, xa, serverState, wu),
          FetchAllPermissionsRequest.getClass              -> FetchAllPermissions.create(repoService, xa, wu),
          FetchAllRolesRequest.getClass                    -> FetchAllRoles.create(repoService, xa, wu),
        ).map(U.mapSecond(_.work)),
      )
    end jobHandlersMap

    private def missingJobError(job: JobKind): F[JobResult] =
      async.raiseError(
        new Exception(s"JobHandlersMap does not contain an implementation for class '${job.shortName}'.") with NoStackTrace,
      )
    end missingJobError

    def executeJob(job: JobKind): F[JobResult] =
      jobHandlersMap
        .get(job.getClass)
        .fold(missingJobError(job))(_(job))
    end executeJob

    private val resultToDomainEventMap: Map[(Class[? <: JobKind], Class[? <: JobResult]), JobResult => Option[DomainEvent]] =
      import JobKind.*
      import JobResult.*
      import U.->

      extension [A](obj: A) inline private def as[T]: T = obj.asInstanceOf[T]

      def mapping[Q <: JobKind, R <: JobResult](f: R => Option[DomainEvent])(using qt: ClassTag[Q], rt: ClassTag[R]) =
        (qt.runtimeClass.as[Class[Q]], rt.runtimeClass.as[Class[R]]) -> ((jr: JobResult) => f(jr.as[R]))

      def fromEither[L, R](opt: Either[L, R], toEvent: R => DomainEvent): Option[DomainEvent] =
        opt.toOption.map(toEvent)
      end fromEither

      U.toMap(
        mapping[CreateUserRequest, CreateUserResult](r => fromEither(r.res, DomainEvent.UserCreated.apply)),
        mapping[CreateRoleRequest, CreateRoleResult](r => fromEither(r.res, DomainEvent.RoleCreated.apply)),
        mapping[LoginRequest, LoginResult](r => fromEither(r.res, p => DomainEvent.UserLoggedIn.apply(p._1))),
      )
    end resultToDomainEventMap

    private def resultToDomainEvent(job: JobKind, res: JobResult): Option[DomainEvent] =
      resultToDomainEventMap.get((job.getClass, res.getClass)).flatMap(_(res))
    end resultToDomainEvent
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
        _ <- je.execWithUUID(uuid):
          val jobExecution: F[Unit] =
            for
              _ <- je.logi(s"Starting to work on ${job.shortName}...")
              outcome <- je.executeJob(job).attempt
              _ <- logSendingResultsBack
              _ <- deferred.complete(outcome)
              _ <- je.publishEvent(outcome, job)
            yield ()

          jobExecution.handleErrorWith(onErrorInner)
      yield ()

    val processSafely = processOneJob.handleErrorWith(onErrorOuter)

    processSafely.foreverM
  end createWorker

  def createWorkers[F[_]: { Async, Logger }](appConfig: AppConfig, deps: AppDependencies[F], auditLogTopic: Topic[F, DomainEvent]): Resource[F, Unit] =
    val jobExecutor: JobExecutor[F] = JobExecutor(deps, auditLogTopic)
    val serverState = deps.serverState

    val numberOfWorkers = appConfig.getBackendServerConfig.getNumberOfWorkers
    val worker = createWorker(serverState.jobQueue, jobExecutor)

    worker.background.replicateA_(numberOfWorkers)
  end createWorkers
end HttpWorker
