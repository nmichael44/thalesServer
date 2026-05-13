package app.workerTasks

import cats.data.EitherT
import cats.effect.MonadCancelThrow

import app.JobSpecs.{CheckResetUserPasswordTokenError, JobKind, JobResult}
import app.ThalesUtils.GenUtils as U
import app.entrypoints.smithy.HashedResetPasswordToken
import app.services.RepositoryService
import doobie.Transactor
import doobie.implicits.*

private final class CheckResetUserPasswordToken[F[_]: MonadCancelThrow] private (
    repoService: RepositoryService,
    xa: Transactor[F],
    wu: WorkerTaskUtils[F],
) extends WorkerTask[F, JobKind.CheckResetUserPasswordTokenRequest]:
  private def checkResetUserPasswordToken(j: JobKind.CheckResetUserPasswordTokenRequest): F[JobResult] =
    val resetPasswordToken = j.resetPasswordToken
    val hashedToken = HashedResetPasswordToken(U.hashStringUrlEncoded(resetPasswordToken.value))

    val program: EitherT[F, CheckResetUserPasswordTokenError, Unit] =
      for
        (_, expiry) <- EitherT.fromOptionF(
          repoService.getResetUserPasswordTokenExpiry(hashedToken).transact(xa),
          CheckResetUserPasswordTokenError.ExpiredToken,
        )
        now <- wu.getNow
        _ <- wu.failIfF[CheckResetUserPasswordTokenError](expiry.isBefore(now), CheckResetUserPasswordTokenError.ExpiredToken)
      yield ()

    wu.toResult(program, JobResult.CheckResetUserPasswordTokenResult.apply)
  end checkResetUserPasswordToken

  override def work(job: JobKind.CheckResetUserPasswordTokenRequest): F[JobResult] =
    checkResetUserPasswordToken(job)
  end work
end CheckResetUserPasswordToken

object CheckResetUserPasswordToken:
  def create[F[_]: MonadCancelThrow](
      repoService: RepositoryService,
      xa: Transactor[F],
      wu: WorkerTaskUtils[F],
  ): WorkerTask[F, JobKind.CheckResetUserPasswordTokenRequest] =
    CheckResetUserPasswordToken[F](repoService, xa, wu)
  end create
end CheckResetUserPasswordToken
