package app.workerTasks

import cats.data.{EitherT, NonEmptyVector}
import cats.effect.MonadCancelThrow
import cats.syntax.all.*

import java.time.Instant

import app.JobSpecs.{InitiateRecoveryOfUserPasswordError, JobKind, JobResult}
import app.ThalesUtils.GenUtils as U
import app.entrypoints.smithy.{HashedResetPasswordToken, LoginName}
import app.services.RepositoryService
import app.uuid.UUIDGenerator
import doobie.{ConnectionIO, Transactor}
import doobie.implicits.*

private final class InitiateRecoveryOfUserPassword[F[_]: MonadCancelThrow] private (
    repoService: RepositoryService,
    xa: Transactor[F],
    uuidGen: UUIDGenerator[F],
    wu: WorkerTaskUtils[F],
) extends WorkerTask[F, JobKind.InitiateRecoveryOfUserPasswordRequest]:
  private def initiateRecoveryOfUserPasswordDbProgram(
      loginName: LoginName,
      hashedToken: HashedResetPasswordToken,
      now: Instant,
  ): EitherT[ConnectionIO, InitiateRecoveryOfUserPasswordError, Unit] =
    for
      userId <-
        EitherT(
          repoService
            .fetchUsersByLoginNames(NonEmptyVector.one(loginName))
            .map(
              _.get(loginName)
                .map(_.userId)
                .toRight(InitiateRecoveryOfUserPasswordError.NoSuchUser),
            ),
        )
      _ <- EitherT.liftF(repoService.insertResetUserPasswordToken(hashedToken, userId, now))
    yield ()
  end initiateRecoveryOfUserPasswordDbProgram

  private val genHashedToken: EitherT[F, Nothing, HashedResetPasswordToken] =
    EitherT.liftF(
      uuidGen.generateUUIDAsString
        .map(token => HashedResetPasswordToken(U.hashStringUrlEncoded(token))),
    )
  end genHashedToken

  private def initiateRecoveryOfUserPassword(j: JobKind.InitiateRecoveryOfUserPasswordRequest): F[JobResult] =
    val loginName = j.loginName

    val program: EitherT[F, InitiateRecoveryOfUserPasswordError, HashedResetPasswordToken] =
      for
        now <- wu.getNow
        hashedToken <- genHashedToken
        _ <- initiateRecoveryOfUserPasswordDbProgram(loginName, hashedToken, now).transact(xa)
      yield hashedToken

    wu.toResult(program, JobResult.InitiateRecoveryOfUserPasswordResult.apply)
  end initiateRecoveryOfUserPassword

  override def work(job: JobKind.InitiateRecoveryOfUserPasswordRequest): F[JobResult] =
    initiateRecoveryOfUserPassword(job)
  end work
end InitiateRecoveryOfUserPassword

object InitiateRecoveryOfUserPassword:
  def create[F[_]: MonadCancelThrow](
      repoService: RepositoryService,
      xa: Transactor[F],
      uuidGen: UUIDGenerator[F],
      wu: WorkerTaskUtils[F],
  ): WorkerTask[F, JobKind.InitiateRecoveryOfUserPasswordRequest] =
    InitiateRecoveryOfUserPassword[F](repoService, xa, uuidGen, wu)
  end create
end InitiateRecoveryOfUserPassword
