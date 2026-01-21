package app.workerTasks

import cats.effect.Async
import cats.syntax.all.*

import app.JobSpecs.{JobKind, JobResult, RenewJwtTokenError}
import app.ThalesUtils.GenUtils as U
import app.services.{AuthService, RenewalError, RepositoryService}
import doobie.Transactor

private final class RenewJwtToken[F[_]: Async] private (
    repoService: RepositoryService,
    xa: Transactor[F],
    authService: AuthService[F],
    wu: WorkerUtils[F],
) extends WorkerTask[F]:
  private val renewErrorToResponse: Map[RenewalError, RenewJwtTokenError] =
    import U.->

    Map(
      RenewalError.NoSuchUser            -> RenewJwtTokenError.NoSuchUser,
      RenewalError.UserIsDisabled        -> RenewJwtTokenError.UserIsDisabled,
      RenewalError.UserMustResetPassword -> RenewJwtTokenError.UserMustResetPassword,
      RenewalError.RenewalTimeHasExpired -> RenewJwtTokenError.RenewalTimeHasExpired,
    )
  end renewErrorToResponse

  private def renewJwtToken(j: JobKind.RenewJwtTokenRequest): F[JobResult] =
    val authenticatedUser = j.authenticatedUser
    val userId = authenticatedUser.userId

    authService
      .renewToken(authenticatedUser)
      .map(_.fold(e => Left(renewErrorToResponse(e)), Right.apply))
      .map(JobResult.RenewJwtTokenResult.apply)
  end renewJwtToken

  override def work(job: JobKind): F[JobResult] =
    renewJwtToken(job.asInstanceOf[JobKind.RenewJwtTokenRequest])
  end work
end RenewJwtToken

object RenewJwtToken:
  def create[F[_]: Async](
      repoService: RepositoryService,
      xa: Transactor[F],
      authService: AuthService[F],
      wu: WorkerUtils[F],
  ) =
    RenewJwtToken(repoService, xa, authService, wu)
  end create
end RenewJwtToken
