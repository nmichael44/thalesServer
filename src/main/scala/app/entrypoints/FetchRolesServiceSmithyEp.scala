package app.entrypoints

import cats.data.NonEmptyVector
import cats.effect.Async

import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra}
import app.entrypoints.smithy.{FetchRolesService, NotFound, Role, RoleInDb}
import app.entrypoints.FetchRolesServiceSmithyEp.create
import app.model.AppModel.AuthenticatedUser
import app.JobSpecs.{FetchRoleByError, JobResult}
import app.JobSpecs.CreateRoleError.*
import app.JobSpecs.DeleteRoleByIdError.*
import app.JobSpecs.JobKind.{CreateRoleRequest, DeleteRoleByIdRequest, FetchAllRolesRequest, FetchRoleByIdRequest}
import app.JobSpecs.JobResult.{CreateRoleResult, DeleteRoleByIdResult, FetchAllRolesResult, FetchRoleByIdResult}
import app.ThalesUtils.ExtensionMethodUtils.*

private final class FetchRolesServiceSmithyEp[F[_]: Async as async] private (
    jobHandler: JobHandler[F],
    epErrors: EntryPointErrors[F],
) extends FetchRolesService[F]:
  override def createRole(authUser: AuthenticatedUser, role: Role): F[Unit] =
    def paramsToStr(params: NonEmptyVector[(String, String)]): String =
      params.view.map((param, error) => s"($param: \"$error\")").mkString("[", ", ", "]")
    end paramsToStr

    def resultToResponse(jobResult: JobResult): F[Unit] =
      jobResult match {
        case CreateRoleResult(res) =>
          res.fold(
            {
              case DuplicateRoleName => epErrors.duplicateRoleNameF
              case InvalidParameters(invalidParams) => epErrors.badRequestF(paramsToStr(invalidParams))
            },
            _ => successResult,
          )
      }
    end resultToResponse

    jobHandler.jobHandlerWithAuth2(
      authUser,
      CreateRoleIdPermissionsAlg,
      CreateRoleRequest(role, authUser.userId),
      resultToResponse,
    )
  end createRole

  private val CreateRoleIdPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanCreateRoles).compile
  end CreateRoleIdPermissionsAlg

  override def deleteRoleById(authUser: AuthenticatedUser, roleId: Long): F[Unit] =
    def resultToResponse(jobResult: JobResult): F[Unit] =
      jobResult match {
        case DeleteRoleByIdResult(res) =>
          res.fold(
            {
              case NoSuchRoleId => epErrors.roleNotFoundF
              case RoleHasAssociatedUsers => epErrors.roleHasUsersF
            },
            _ => successResult,
          )
      }
    end resultToResponse

    jobHandler.jobHandlerWithAuth2(
      authUser,
      DeleteRoleByIdPermissionsAlg,
      DeleteRoleByIdRequest(roleId),
      resultToResponse,
    )
  end deleteRoleById

  private val DeleteRoleByIdPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanDeleteRoles).compile
  end DeleteRoleByIdPermissionsAlg

  override def fetchRoleById(authUser: AuthenticatedUser, roleId: Long): F[RoleInDb] =
    def resultToResponse(jobResult: JobResult): F[RoleInDb] =
      jobResult match {
        case FetchRoleByIdResult(res) =>
          res.fold(
            { case FetchRoleByError.RoleNotFound => epErrors.roleNotFoundF },
            async.pure,
          )
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
      FetchAllRolesRequest,
      resultToResponse,
    )
  end fetchAllBoRoles

  private val FetchAllRolesPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanSeeAllBoRoles).compile
  end FetchAllRolesPermissionsAlg

  private val successResult: F[Unit] = async.pure(())
end FetchRolesServiceSmithyEp

object FetchRolesServiceSmithyEp:
  def create[F[_]: Async](jobHandler: JobHandler[F], epErrors: EntryPointErrors[F]): FetchRolesService[F] =
    FetchRolesServiceSmithyEp[F](jobHandler, epErrors)
  end create
end FetchRolesServiceSmithyEp
