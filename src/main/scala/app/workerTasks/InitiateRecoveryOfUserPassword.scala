package app.workerTasks

import cats.data.{EitherT, NonEmptyVector}
import cats.effect.Async
import cats.syntax.all.*

import java.time.Instant

import app.JobSpecs.{InitiateRecoveryOfUserPasswordError, JobKind, JobResult}
import app.ThalesUtils.ExtensionMethodUtils.liftE
import app.ThalesUtils.GenUtils as U
import app.entrypoints.smithy.{HashedResetPasswordToken, LoginName}
import app.services.RepositoryService
import app.uuid.UUIDGenerator
import doobie.{ConnectionIO, Transactor}
import doobie.implicits.*

private final class InitiateRecoveryOfUserPassword[F[_]: Async] private (
    repoService: RepositoryService,
    xa: Transactor[F],
    uuidGen: UUIDGenerator[F],
    wu: WorkerTaskUtils[F],
) extends WorkerTask[F]:
  private def initiateRecoveryOfUserPasswordDbProgram(
      loginName: LoginName,
      hashedToken: HashedResetPasswordToken,
      now: Instant,
  ): EitherT[ConnectionIO, InitiateRecoveryOfUserPasswordError, Unit] = for {
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
    _ <- repoService.insertResetUserPasswordToken(hashedToken, userId, now).liftE
  } yield ()
  end initiateRecoveryOfUserPasswordDbProgram

  private val genHashedToken: EitherT[F, Nothing, HashedResetPasswordToken] =
    uuidGen.generateUUIDAsString
      .map(token => HashedResetPasswordToken(U.hashStringUrlEncoded(token)))
      .liftE
  end genHashedToken

  private def initiateRecoveryOfUserPassword(j: JobKind.InitiateRecoveryOfUserPasswordRequest): F[JobResult] =
    val loginName = j.loginName

    val program: EitherT[F, InitiateRecoveryOfUserPasswordError, HashedResetPasswordToken] = for {
      now <- wu.getNow
      hashedToken <- genHashedToken
      _ <- initiateRecoveryOfUserPasswordDbProgram(loginName, hashedToken, now).transact(xa)
    } yield hashedToken

    wu.toResult(program, JobResult.InitiateRecoveryOfUserPasswordResult.apply)
  end initiateRecoveryOfUserPassword

  override def work(job: JobKind): F[JobResult] =
    initiateRecoveryOfUserPassword(job.asInstanceOf[JobKind.InitiateRecoveryOfUserPasswordRequest])
  end work
end InitiateRecoveryOfUserPassword

object InitiateRecoveryOfUserPassword:
  def create[F[_]: Async](
      repoService: RepositoryService,
      xa: Transactor[F],
      uuidGen: UUIDGenerator[F],
      wu: WorkerTaskUtils[F],
  ): WorkerTask[F] =
    InitiateRecoveryOfUserPassword[F](repoService, xa, uuidGen, wu)
  end create
end InitiateRecoveryOfUserPassword
