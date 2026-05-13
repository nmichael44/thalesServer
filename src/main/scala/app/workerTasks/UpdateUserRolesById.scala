package app.workerTasks

import cats.data.EitherT
import cats.effect.MonadCancelThrow

import app.JobSpecs.{JobKind, JobResult, UpdateUserRolesByIdError}
import app.JobSpecs.JobKind.UpdateUserRolesByIdRequest
import app.ThalesUtils.GenUtils as U
import app.services.RepositoryService
import app.services.UpdateUserRolesByIdDbError
import app.services.given
import doobie.Transactor
import doobie.implicits.*

private final class UpdateUserRolesById[F[_]: MonadCancelThrow] private (
    repoService: RepositoryService,
    xa: Transactor[F],
    wu: WorkerTaskUtils[F],
) extends WorkerTask[F, UpdateUserRolesByIdRequest]:
  private val logUpdateUserRolesById: EitherT[F, UpdateUserRolesByIdError, Unit] =
    EitherT.liftF(wu.logi("Update user roles by Id."))
  end logUpdateUserRolesById

  private def updateUserRolesById(j: UpdateUserRolesByIdRequest): F[JobResult] =
    val (userId, roleIds) = (j.userId, j.roleIds)

    val program: EitherT[F, UpdateUserRolesByIdError, Unit] =
      for
        _ <- logUpdateUserRolesById
        _ <- EitherT.fromEither(U.findDuplicates(roleIds).map(UpdateUserRolesByIdError.DuplicateRoleIds.apply).toLeft(()))
        _ <- EitherT(repoService.updateUserRolesById(userId, roleIds).transact(xa))
          .leftMap {
            case UpdateUserRolesByIdDbError.NoSuchUserId => UpdateUserRolesByIdError.NoSuchUserId
            case UpdateUserRolesByIdDbError.NoSuchRoleIds(nevRoleIds) => UpdateUserRolesByIdError.NoSuchRoleIds(nevRoleIds)
          }
      yield ()

    wu.toResult(program, JobResult.UpdateUserRolesByIdResult.apply)
  end updateUserRolesById

  override def work(job: UpdateUserRolesByIdRequest): F[JobResult] =
    updateUserRolesById(job)
  end work
end UpdateUserRolesById

object UpdateUserRolesById:
  def create[F[_]: MonadCancelThrow](
      repoService: RepositoryService,
      xa: Transactor[F],
      wu: WorkerTaskUtils[F],
  ): WorkerTask[F, UpdateUserRolesByIdRequest] =
    UpdateUserRolesById[F](repoService, xa, wu)
  end create
end UpdateUserRolesById
