package app.entrypoints

import cats.effect.Async

import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra}
import app.entrypoints.smithy.{FetchRolesService, NotFound, RoleInDb}
import app.model.AppModel.AuthenticatedUser
import app.JobSpecs.{FetchRoleByError, JobResult}
import app.JobSpecs.DeleteRoleByIdError.*
import app.JobSpecs.JobKind.{DeleteRoleByIdRequest, FetchAllRolesRequest, FetchRoleByIdRequest}
import app.JobSpecs.JobResult.{DeleteRoleByIdResult, FetchAllRolesResult, FetchRoleByIdResult}

private final class FetchRolesServiceSmithyEp[F[_]: Async as async] private (
    jobHandler: JobHandler[F],
    epErrors: EntryPointErrors[F],
) extends FetchRolesService[F]:
  override def fetchRoleById(authUser: AuthenticatedUser, roleId: Long): F[RoleInDb] =
    def resultToResponse(jobResult: JobResult): F[RoleInDb] =
      jobResult match {
        case FetchRoleByIdResult(res) =>
          res match {
            case Right(boRoleInDb) => async.pure(boRoleInDb)
            case Left(FetchRoleByError.RoleNotFound) => epErrors.roleNotFoundF
          }
      }
    end resultToResponse

    jobHandler.jobHandlerWithAuth2(
      authUser,
      FetchRolePermissionsAlg,
      FetchRoleByIdRequest(roleId),
      resultToResponse,
    )
  end fetchRoleById

  private val FetchRolePermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanSeeAllBoRoles).compile
  end FetchRolePermissionsAlg

  override def fetchAllBoRoles(authUser: AuthenticatedUser): F[Vector[RoleInDb]] =
    def resultToResponse(jobResult: JobResult): F[Vector[RoleInDb]] =
      jobResult match {
        case FetchAllRolesResult(res) => async.pure(res)
      }
    end resultToResponse

    jobHandler.jobHandlerWithAuth2(
      authUser,
      FetchAllRolesPermissionsAlg,
      FetchAllRolesRequest(),
      resultToResponse,
    )
  end fetchAllBoRoles

  private val FetchAllRolesPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanSeeAllBoRoles).compile
  end FetchAllRolesPermissionsAlg

  private val successResult: F[Unit] = async.pure(())

  override def deleteRoleById(authBoUser: AuthenticatedUser, roleId: Long): F[Unit] =
    def resultToResponse(jobResult: JobResult): F[Unit] =
      jobResult match {
        case DeleteRoleByIdResult(res) =>
          res match {
            case Right(_) => successResult
            case Left(NoSuchRoleId) => epErrors.roleNotFoundF
            case Left(RoleHasAssociatedUsers) => epErrors.roleHasUsersF
          }
      }
    end resultToResponse

    jobHandler.jobHandlerWithAuth2(
      authBoUser,
      DeleteRoleByIdPermissionsAlg,
      DeleteRoleByIdRequest(roleId),
      resultToResponse,
    )
  end deleteRoleById

  private val DeleteRoleByIdPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanDeleteBoRoles).compile
  end DeleteRoleByIdPermissionsAlg
end FetchRolesServiceSmithyEp

object FetchRolesServiceSmithyEp:
  def create[F[_]: Async](jobHandler: JobHandler[F], epErrors: EntryPointErrors[F]): FetchRolesService[F] =
    FetchRolesServiceSmithyEp[F](jobHandler, epErrors)
  end create
end FetchRolesServiceSmithyEp
