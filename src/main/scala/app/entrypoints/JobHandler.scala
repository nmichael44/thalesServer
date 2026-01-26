package app.entrypoints

import cats.effect.*
import cats.effect.std.Queue
import cats.syntax.all.*

import app.JobSpecs.{JobKind, JobResult}
import app.ThalesUtils.{GenUtils as U, RequestHeaderUtils}
import app.WorkerJob
import app.auth.Permissions.CompiledPermissionAlgebra
import app.model.AppModel
import app.model.AppModel.AuthenticatedUser
import app.uuid.UUIDGenerator
import org.http4s.Request
import org.typelevel.log4cats.Logger

final class JobHandler[F[_]: { Async as async, Logger }] private (
    jobQueue: Queue[F, WorkerJob[F]],
    uuidGen: UUIDGenerator[F],
    epErrors: EntryPointErrors[F],
):
  private val FiberName = "http4sFiber"

  private def logi(s: String): F[Unit] =
    U.logi(FiberName, s)
  end logi

  private def loge(e: Throwable, uuid: String, s: String): F[Unit] =
    U.loge(e, FiberName, uuid, s)
  end loge

  private def logi(uuid: String, s: String): F[Unit] =
    U.logi(FiberName, uuid, s)
  end logi

  private val logFindingXRequestIdHeader: F[Unit] = logi("Finding XRequestId header.")
  private val logNotFound: F[Unit] = logi("... not found -- generating.")
  private val logFound: F[Unit] = logi("... found!")

  private val GetDeferredF: F[Deferred[F, Either[Throwable, JobResult]]] =
    Deferred[F, Either[Throwable, JobResult]]
  end GetDeferredF

  private def getUUIDForRequest(req: Request[F], uuidGen: UUIDGenerator[F]): F[String] =
    RequestHeaderUtils
      .getXRequestId(req)
      .fold(logNotFound *> uuidGen.generateUUIDAsString)(logFound.as)
  end getUUIDForRequest

  private def reportUnauthorizedUser[R](authUser: AuthenticatedUser, uuid: String, jobName: String): F[R] =
    logi(uuid, s"Authorization failure for user with id: '${authUser.userId}' for job '$jobName'.") *>
      epErrors.authorizationError
  end reportUnauthorizedUser

  private def logSuccessOrFailure(outcome: Either[Throwable, JobResult], uuid: String): F[Unit] =
    outcome match
      case Right(_) => logi(uuid, "Successful response.")
      case Left(e) => loge(e, uuid, "Failed with exception.")
  end logSuccessOrFailure

  private def addJobToQueue(job: WorkerJob[F]): F[Unit] =
    jobQueue.offer(job)
  end addJobToQueue

  extension (user: AuthenticatedUser)
    private def hasPermissions(jobPermissionAlgebra: CompiledPermissionAlgebra): Boolean =
      jobPermissionAlgebra.isSatisfiedBy(user.permissions)
    end hasPermissions

  def jobHandlerWithAuth[R](
      authUser: AuthenticatedUser,
      jobPermissionAlgebra: CompiledPermissionAlgebra,
      job: JobKind,
      f: JobResult => F[R],
  ): F[R] = for {
    _ <- logGeneratingXRequestIdHeader
    uuid <- uuidGen.generateUUIDAsString
    _ <- logi(uuid, "Processing request.")
    res <-
      if authUser.hasPermissions(jobPermissionAlgebra) then
        for {
          deferred <- GetDeferredF
          _ <- logi(uuid, "Permission validated. Request being queued.")
          _ <- addJobToQueue(WorkerJob(job, deferred, uuid))
          _ <- logi(uuid, "Waiting for response.")
          outcome <- deferred.get
          _ <- logi(uuid, "Response received.")
          _ <- logSuccessOrFailure(outcome, uuid)
          r <- mkResponseF(outcome, f)
        } yield r
      else reportUnauthorizedUser(authUser, uuid, job.shortName)
  } yield res
  end jobHandlerWithAuth

  private val logGeneratingXRequestIdHeader: F[Unit] = logi("Generating XRequestId UUID header.")

  def jobHandlerNoAuthF[R](job: JobKind, f: JobResult => F[R]): F[R] = for {
    _ <- logGeneratingXRequestIdHeader
    uuid <- uuidGen.generateUUIDAsString
    _ <- logi(uuid, "Processing request.")
    deferred <- GetDeferredF
    _ <- logi(uuid, "Request being queued.")
    _ <- addJobToQueue(WorkerJob(job, deferred, uuid))
    _ <- logi(uuid, "Waiting for response.")
    outcome <- deferred.get // Wait for the answer
    _ <- logi(uuid, "Response received.")
    _ <- logSuccessOrFailure(outcome, uuid)
    res <- mkResponseF(outcome, f)
  } yield res
  end jobHandlerNoAuthF

  private def mkResponseF[R](resEither: Either[Throwable, JobResult], f: JobResult => F[R]): F[R] =
    resEither.fold(async.raiseError, f)
  end mkResponseF
end JobHandler

object JobHandler:
  def create[F[_]: { Async as async, Logger }](
      jobQueue: Queue[F, WorkerJob[F]],
      uuidGen: UUIDGenerator[F],
      epErrors: EntryPointErrors[F],
  ): JobHandler[F] =
    JobHandler(jobQueue, uuidGen, epErrors)
  end create
end JobHandler
