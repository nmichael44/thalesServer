package app.entrypoints

import app.JobSpecs.DeleteRoleByIdError.*
import cats.effect.Async
import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra}
import app.entrypoints.smithy.{BoRoleInDb, FetchBoRolesService, NotFound}
import app.model.AppModel.AuthenticatedBoUser
import app.JobSpecs.{FetchBoRoleByError, JobResult}
import app.JobSpecs.JobKind.{FetchAllBoRolesRequest, FetchBoRoleByIdRequest, DeleteRoleByIdRequest}
import app.JobSpecs.JobResult.{DeleteRoleByIdResult, FetchAllBoRolesResult, FetchBoRoleByIdResult}

private final class FetchBoRolesServiceSmithyEp[F[_]: Async as async] private (
    jobHandler: JobHandler[F],
    epErrors: EntryPointErrors[F],
) extends FetchBoRolesService[F]:
  override def fetchBoRoleById(authBoUser: AuthenticatedBoUser, roleId: Long): F[BoRoleInDb] =
    def resultToResponse(jobResult: JobResult): F[BoRoleInDb] =
      jobResult match {
        case FetchBoRoleByIdResult(res) =>
          res match {
            case Right(boRoleInDb) => async.pure(boRoleInDb)
            case Left(FetchBoRoleByError.RoleNotFound) => epErrors.roleNotFoundF
          }
      }
    end resultToResponse

    jobHandler.jobHandlerWithAuth2(
      authBoUser,
      FetchBoRolePermissionsAlg,
      FetchBoRoleByIdRequest(roleId),
      resultToResponse,
    )
  end fetchBoRoleById

  private val FetchBoRolePermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanSeeAllBoRoles).compile
  end FetchBoRolePermissionsAlg

  override def fetchAllBoRoles(authBoUser: AuthenticatedBoUser): F[Vector[BoRoleInDb]] =
    def resultToResponse(jobResult: JobResult): F[Vector[BoRoleInDb]] =
      jobResult match {
        case FetchAllBoRolesResult(res) => async.pure(res)
      }
    end resultToResponse

    jobHandler.jobHandlerWithAuth2(
      authBoUser,
      FetchAllBoRolesPermissionsAlg,
      FetchAllBoRolesRequest(),
      resultToResponse,
    )
  end fetchAllBoRoles

  private val FetchAllBoRolesPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanSeeAllBoRoles).compile
  end FetchAllBoRolesPermissionsAlg

  private val successResult: F[Unit] = async.pure(())

  private def deleteRoleById(authBoUser: AuthenticatedBoUser, roleId: Long): F[Unit] =
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
end FetchBoRolesServiceSmithyEp

object FetchBoRolesServiceSmithyEp:
  def create[F[_]: Async](jobHandler: JobHandler[F], epErrors: EntryPointErrors[F]): FetchBoRolesService[F] =
    FetchBoRolesServiceSmithyEp[F](jobHandler, epErrors)
  end create
end FetchBoRolesServiceSmithyEp
