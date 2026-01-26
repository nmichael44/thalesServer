package app.workerTasks

import cats.data.{EitherT, NonEmptyVector}
import cats.effect.Async
import cats.syntax.all.*

import java.time.Instant

import app.JobSpecs.{CreateRoleError, JobKind, JobResult}
import app.ThalesUtils.ExtensionMethodUtils.liftE
import app.entrypoints.smithy.{Role, RoleId, RoleName}
import app.entrypoints.smithy.UserId
import app.services.{ClockService, CreateRoleDbError, RepositoryService}
import app.services.given
import doobie.{ConnectionIO, Transactor}
import doobie.implicits.*

private final class CreateRole[F[_]: Async] private (
    repoService: RepositoryService,
    xa: Transactor[F],
    clockService: ClockService[F],
    wu: WorkerTaskUtils[F],
) extends WorkerTask[F]:
  private val logCreatingRole: EitherT[F, Nothing, Unit] = wu.logT("Creating role.")
  private val logRoleParamsLookFine: EitherT[F, Nothing, Unit] = wu.logT("Parameters look valid/non-empty.")

  private val roleNameCannotBeEmptyError: CreateRoleError =
    CreateRoleError.InvalidParameters(NonEmptyVector.one(("RoleName", "cannot be empty.")))

  private def validateRoleParameters(role: Role): EitherT[F, CreateRoleError, Unit] =
    wu.failIfF(role.roleName.value.isEmpty, roleNameCannotBeEmptyError)
  end validateRoleParameters

  private def createRoleDbProgram(
      now: Instant,
      roleName: RoleName,
      userId: UserId,
  ): EitherT[ConnectionIO, CreateRoleDbError, RoleId] =
    EitherT(repoService.createRole(roleName, userId, now))
  end createRoleDbProgram

  private def mapError(e: CreateRoleDbError): CreateRoleError =
    e match
      case CreateRoleDbError.DuplicateRoleName => CreateRoleError.DuplicateRoleName
  end mapError

  private def createRole(j: JobKind.CreateRoleRequest): F[JobResult] =
    val (role, userId) = (j.role, j.userId)

    val res: EitherT[F, CreateRoleError, RoleId] = for {
      _ <- logCreatingRole
      _ <- validateRoleParameters(role)
      _ <- logRoleParamsLookFine
      now <- wu.getNow
      roleId <- createRoleDbProgram(now, role.roleName, userId)
        .transact(xa)
        .leftMap(mapError)
    } yield roleId

    wu.toResult(res, JobResult.CreateRoleResult.apply)
  end createRole

  override def work(job: JobKind): F[JobResult] =
    createRole(job.asInstanceOf[JobKind.CreateRoleRequest])
  end work
end CreateRole

object CreateRole:
  def create[F[_]: Async](
      repoService: RepositoryService,
      xa: Transactor[F],
      clockService: ClockService[F],
      wu: WorkerTaskUtils[F],
  ): WorkerTask[F] =
    CreateRole[F](repoService, xa, clockService, wu)
  end create
end CreateRole
