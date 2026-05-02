package app.workerTasks

import cats.data.{EitherT, NonEmptyVector}
import cats.effect.Async
import cats.syntax.all.*

import java.time.Instant

import app.JobSpecs.JobResult
import app.ThalesUtils.{GenUtils as U, PasswordValidationUtils}
import app.ThalesUtils.ExtensionMethodUtils.*
import app.TraceIdScope
import app.entrypoints.smithy.UserPassword
import app.services.ClockService
import doobie.ConnectionIO
import org.typelevel.log4cats.Logger

final class WorkerTaskUtils[F[_]: { Async, Logger }] private (
    uuidScope: TraceIdScope[F, Option[String]],
    clockService: ClockService[F],
    workerFiberName: String,
):
  val failIfC: U.EitherTFailIf[ConnectionIO] = U.EitherTFailIf[ConnectionIO]
  val failIfF: U.EitherTFailIf[F] = U.EitherTFailIf[F]

  def logT(s: String): EitherT[F, Nothing, Unit] = EitherT.liftF(logi(s))

  def logi(s: String): F[Unit] =
    uuidScope.get.flatMap: uuidOpt =>
      uuidOpt.fold(U.logi(workerFiberName, s))(U.logi(workerFiberName, _, s))
  end logi

  def loge(e: Throwable, s: String): F[Unit] =
    uuidScope.get.flatMap: uuidOpt =>
      uuidOpt.fold(U.loge(e, workerFiberName, s))(U.loge(e, workerFiberName, _, s))
  end loge

  def toResult[L, R](e: EitherT[F, L, R], f: Either[L, R] => JobResult): F[JobResult] =
    e.value.map(f)
  end toResult

  def validatePassword[E](password: UserPassword, e: NonEmptyVector[String] => E): EitherT[F, E, Unit] =
    EitherT.fromEither(
      PasswordValidationUtils
        .isPasswordGoodEnough(password)
        .toEither
        .leftMap(e),
    )
  end validatePassword

  val getNow: EitherT[F, Nothing, Instant] =
    clockService.nowInstant.liftE
  end getNow

  val logCheckingValidityOfNewPassword: EitherT[F, Nothing, Unit] =
    logT("Checking validity of new password...")
  end logCheckingValidityOfNewPassword

  val logComputingHashAndUpdatingDb: EitherT[F, Nothing, Unit] =
    logT("Password is valid. Computing hash and updating db.")
  end logComputingHashAndUpdatingDb

  val logFetchingUserFromDb: EitherT[F, Nothing, Unit] =
    logT("Fetching user and checking enable status. Writing new password.")
  end logFetchingUserFromDb
end WorkerTaskUtils

object WorkerTaskUtils:
  def create[F[_]: { Async, Logger }](
      uuidScope: TraceIdScope[F, Option[String]],
      clockService: ClockService[F],
      workerFiberName: String,
  ) =
    WorkerTaskUtils(uuidScope, clockService, workerFiberName)
  end create
end WorkerTaskUtils
