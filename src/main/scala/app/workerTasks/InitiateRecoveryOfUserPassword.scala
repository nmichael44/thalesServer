package app.workerTasks

import cats.data.{EitherT, NonEmptyVector}
import cats.effect.MonadCancelThrow
import cats.syntax.all.*

import java.time.Instant

import app.JobSpecs.{InitiateRecoveryOfUserPasswordError, JobKind, JobResult}
import app.ThalesUtils.GenUtils as U
import app.entrypoints.smithy.{HashedResetPasswordToken, LoginName, UserInDb}
import app.model.AppModel.EmailMessage
import app.services.{EmailService, RepositoryService}
import app.uuid.UUIDGenerator
import doobie.{ConnectionIO, Transactor}
import doobie.implicits.*

private final class InitiateRecoveryOfUserPassword[F[_]: MonadCancelThrow] private (
    repoService: RepositoryService,
    xa: Transactor[F],
    uuidGen: UUIDGenerator[F],
    emailService: EmailService[F],
    wu: WorkerTaskUtils[F],
) extends WorkerTask[F, JobKind.InitiateRecoveryOfUserPasswordRequest]:
  private def initiateRecoveryOfUserPasswordDbProgram(
      loginName: LoginName,
      hashedToken: HashedResetPasswordToken,
      now: Instant,
  ): EitherT[ConnectionIO, InitiateRecoveryOfUserPasswordError, UserInDb] =
    for
      user <-
        EitherT(
          repoService
            .fetchUsersByLoginNames(NonEmptyVector.one(loginName))
            .map(
              _.get(loginName)
                .toRight(InitiateRecoveryOfUserPasswordError.NoSuchUser),
            ),
        )
      _ <- EitherT.liftF(repoService.insertResetUserPasswordToken(hashedToken, user.userId, now.plusSeconds(900)))
    yield user
  end initiateRecoveryOfUserPasswordDbProgram

  private val genTokens: EitherT[F, Nothing, (String, HashedResetPasswordToken)] =
    EitherT.liftF(
      uuidGen.generateUUIDAsString
        .map(rawToken => (rawToken, HashedResetPasswordToken(U.hashStringUrlEncoded(rawToken)))),
    )
  end genTokens

  private def initiateRecoveryOfUserPassword(j: JobKind.InitiateRecoveryOfUserPasswordRequest): F[JobResult] =
    val loginName = j.loginName

    val program: EitherT[F, InitiateRecoveryOfUserPasswordError, HashedResetPasswordToken] =
      for
        now <- wu.getNow
        (rawToken, hashedToken) <- genTokens
        user <- initiateRecoveryOfUserPasswordDbProgram(loginName, hashedToken, now).transact(xa)
        _ <- EitherT.liftF(
          emailService.sendEmail(
            EmailMessage(
              from = "noreply@thales.com",
              tos = Seq(user.email),
              ccs = Seq.empty,
              bccs = Seq.empty,
              subject = "Reset Your Password",
              body = s"Hello ${user.firstName},\n\nClick the link below to reset your password:\n\nhttps://thales.com/reset-password?token=$rawToken\n\nIf you did not request this, please ignore this email.\n\nThanks!",
            )
          )
        )
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
      emailService: EmailService[F],
      wu: WorkerTaskUtils[F],
  ): WorkerTask[F, JobKind.InitiateRecoveryOfUserPasswordRequest] =
    InitiateRecoveryOfUserPassword[F](repoService, xa, uuidGen, emailService, wu)
  end create
end InitiateRecoveryOfUserPassword
