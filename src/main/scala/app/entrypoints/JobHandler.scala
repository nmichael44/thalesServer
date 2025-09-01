package app.entrypoints

import cats.effect.*
import cats.syntax.all.*

import app.auth.Permissions.CompiledPermissionAlgebra
import app.model.AppModel
import app.model.AppModel.AuthenticatedBoUser
import app.services.ServerState
import app.uuid.UUIDGenerator
import app.JobSpecs.{JobKind, JobResult}
import app.ThalesUtils.{GenUtils as U, RequestHeaderUtils}
import app.WorkerJob
import org.http4s.Request
import org.typelevel.log4cats.Logger

final class JobHandler[F[_]: { Async as async, Logger }](serverState: ServerState[F], uuidGen: UUIDGenerator[F]):
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

  private val DeferredF: F[Deferred[F, Either[Throwable, JobResult]]] =
    Deferred[F, Either[Throwable, JobResult]]

  private def getUUIDForRequest(req: Request[F], uuidGen: UUIDGenerator[F]): F[String] =
    RequestHeaderUtils
      .getXRequestId(req)
      .fold(logNotFound *> uuidGen.generateUUIDAsString)(logFound.as)
  end getUUIDForRequest

  private def reportUnauthorizedUser[L, R](
      user: AppModel.AuthenticatedBoUser,
      uuid: String,
      unauthorizedError: Either[L, R],
      jobName: String,
  ): F[Either[L, R]] =
    val userId = user.userId

    logi(uuid, s"Authorization failure for user with id: '$userId' for job '$jobName'.").as(unauthorizedError)
  end reportUnauthorizedUser

  private def logSuccessOrFailure(outcome: Either[Throwable, JobResult], uuid: String): F[Unit] =
    outcome match {
      case Right(_) => logi(uuid, "Successful response.")
      case Left(e) => loge(e, uuid, "Failed with exception.")
    }
  end logSuccessOrFailure

  extension (user: AppModel.AuthenticatedBoUser)
    inline private def hasPermissions(jobPermissionAlgebra: CompiledPermissionAlgebra): Boolean =
      jobPermissionAlgebra.isSatisfiedBy(user.permissions)
    end hasPermissions

  def jobHandlerWithAuth[T <: JobResult, L, R](
      authBoUser: AuthenticatedBoUser,
      jobPermissionAlgebra: CompiledPermissionAlgebra,
      job: JobKind,
      f: T => Either[L, R],
      unauthorizedError: Either[L, R],
  ): F[Either[L, R]] = for {
    _ <- logGeneratingXRequestIdHeader
    uuid <- uuidGen.generateUUIDAsString
    _ <- logi(uuid, "Processing request.")
    res <-
      if authBoUser.hasPermissions(jobPermissionAlgebra) then
        for {
          deferred <- DeferredF
          _ <- logi(uuid, "Permission validated. Request being queued.")
          _ <- serverState.jobQueue.offer(WorkerJob(job, deferred, uuid))
          _ <- logi(uuid, "Waiting for response.")
          outcome <- deferred.get // Wait for the answer
          _ <- logi(uuid, "Response received.")
          _ <- logSuccessOrFailure(outcome, uuid)
          res <- mkResponseF(outcome, f.andThen(async.pure))
        } yield res
      else reportUnauthorizedUser(authBoUser, uuid, unauthorizedError, job.shortName)
  } yield res
  end jobHandlerWithAuth

  private val logGeneratingXRequestIdHeader: F[Unit] = logi("Generating XRequestId UUID header.")

  def jobHandlerNoAuthF[T <: JobResult, L, R](job: JobKind, f: T => F[Either[L, R]]): F[Either[L, R]] = for {
    _ <- logGeneratingXRequestIdHeader
    uuid <- uuidGen.generateUUIDAsString
    _ <- logi(uuid, "Processing request.")
    deferred <- DeferredF
    _ <- logi(uuid, "Request being queued.")
    _ <- serverState.jobQueue.offer(WorkerJob(job, deferred, uuid))
    _ <- logi(uuid, "Waiting for response.")
    outcome <- deferred.get // Wait for the answer
    _ <- logi(uuid, "Response received.")
    _ <- logSuccessOrFailure(outcome, uuid)
    res <- mkResponseF(outcome, f)
  } yield res
  end jobHandlerNoAuthF

//  private def mkResponse[T, L, R](
//      resEither: Either[Throwable, JobResult],
//      f: T => Either[L, R],
//  ): F[Either[L, R]] =
//    resEither.fold(
//      e => Left((StatusCode.InternalServerError, ApiError("INTERNAL_SERVER_ERROR", e.getMessage))),
//      jr => async.pure(f(jr.asInstanceOf[T])),
//    )
//  end mkResponse

  private def mkResponseF[T, L, R](
      resEither: Either[Throwable, JobResult],
      f: T => F[Either[L, R]],
  ): F[Either[L, R]] =
    resEither.fold(
      e => async.raiseError(e),
      jr => f(jr.asInstanceOf[T]),
    )
  end mkResponseF
end JobHandler
