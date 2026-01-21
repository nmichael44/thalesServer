package app.workerTasks

import cats.data.EitherT
import cats.effect.Async

import app.JobSpecs.{CheckResetUserPasswordTokenError, JobKind, JobResult}
import app.ThalesUtils.GenUtils as U
import app.entrypoints.smithy.HashedResetPasswordToken
import app.services.RepositoryService
import doobie.Transactor
import doobie.implicits.*

private final class CheckResetUserPasswordToken[F[_]: Async] private (
    repoService: RepositoryService,
    xa: Transactor[F],
    wu: WorkerTaskUtils[F],
) extends WorkerTask[F]:
  private def checkResetUserPasswordToken(j: JobKind.CheckResetUserPasswordTokenRequest): F[JobResult] =
    val resetPasswordToken = j.resetPasswordToken
    val hashedToken = HashedResetPasswordToken(U.hashStringUrlEncoded(resetPasswordToken.value))

    val program: EitherT[F, CheckResetUserPasswordTokenError, Unit] = for {
      (_, expiry) <- EitherT.fromOptionF(
        repoService.getResetUserPasswordTokenExpiry(hashedToken).transact(xa),
        CheckResetUserPasswordTokenError.ExpiredToken,
      )
      now <- wu.getNow
      _ <- wu.failIfF(expiry.isBefore(now), CheckResetUserPasswordTokenError.ExpiredToken)
    } yield ()

    wu.toResult(program, JobResult.CheckResetUserPasswordTokenResult.apply)
  end checkResetUserPasswordToken

  override def work(job: JobKind): F[JobResult] =
    checkResetUserPasswordToken(job.asInstanceOf[JobKind.CheckResetUserPasswordTokenRequest])
  end work
end CheckResetUserPasswordToken

object CheckResetUserPasswordToken:
  def create[F[_]: Async](
      repoService: RepositoryService,
      xa: Transactor[F],
      wu: WorkerTaskUtils[F],
  ): WorkerTask[F] =
    CheckResetUserPasswordToken[F](repoService, xa, wu)
  end create
end CheckResetUserPasswordToken
