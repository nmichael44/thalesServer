package app.entrypoints

import cats.data.{Kleisli, NonEmptyVector}
import cats.effect.Async

import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra}
import app.entrypoints.smithy.{FetchRolesService, NotFound, Role, RoleInDb}
import app.entrypoints.smithy.FetchAllRolesOutput
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
) extends FetchRolesService[[A] =>> Kleisli[F, AuthenticatedUser, A]]:
  override def createRole(role: Role): Kleisli[F, AuthenticatedUser, Unit] =
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
        case _ => epErrors.internalServerErrorF("CreateRole: Bad pattern match for result.")
      }
    end resultToResponse

    Kleisli { authUser =>
      jobHandler.jobHandlerWithAuth2(
        authUser,
        CreateRoleIdPermissionsAlg,
        CreateRoleRequest(role, authUser.userId),
        resultToResponse,
      )
    }
  end createRole

  private val CreateRoleIdPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanCreateRoles).compile
  end CreateRoleIdPermissionsAlg

  override def deleteRoleById(roleId: Long): Kleisli[F, AuthenticatedUser, Unit] =
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
        case _ => epErrors.internalServerErrorF("DeleteRole: Bad pattern match for result.")
      }
    end resultToResponse

    Kleisli { authUser =>
      jobHandler.jobHandlerWithAuth2(
        authUser,
        DeleteRoleByIdPermissionsAlg,
        DeleteRoleByIdRequest(roleId),
        resultToResponse,
      )
    }
  end deleteRoleById

  private val DeleteRoleByIdPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanDeleteRoles).compile
  end DeleteRoleByIdPermissionsAlg

  override def fetchRoleById(roleId: Long): Kleisli[F, AuthenticatedUser, RoleInDb] =
    def resultToResponse(jobResult: JobResult): F[RoleInDb] =
      jobResult match {
        case FetchRoleByIdResult(res) =>
          res.fold(
            { case FetchRoleByError.RoleNotFound => epErrors.roleNotFoundF },
            async.pure,
          )
        case _ => epErrors.internalServerErrorF("FetchRoleById: Bad pattern match for result.")
      }
    end resultToResponse

    Kleisli { authUser =>
      jobHandler.jobHandlerWithAuth2(
        authUser,
        FetchRolePermissionsAlg,
        FetchRoleByIdRequest(roleId),
        resultToResponse,
      )
    }
  end fetchRoleById

  private val FetchRolePermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanSeeAllBoRoles).compile
  end FetchRolePermissionsAlg

  override def fetchAllRoles(): Kleisli[F, AuthenticatedUser, FetchAllRolesOutput] =
    def resultToResponse(jobResult: JobResult): F[FetchAllRolesOutput] =
      jobResult match {
        case FetchAllRolesResult(res) => async.pure(FetchAllRolesOutput(res))
        case _ => epErrors.internalServerErrorF("FetchAllRoles: Bad pattern match for result.")
      }
    end resultToResponse

    Kleisli { authUser =>
      jobHandler.jobHandlerWithAuth2(
        authUser,
        FetchAllRolesPermissionsAlg,
        FetchAllRolesRequest,
        resultToResponse,
      )
    }
  end fetchAllRoles

  private val FetchAllRolesPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanSeeAllBoRoles).compile
  end FetchAllRolesPermissionsAlg

  private val successResult: F[Unit] = async.pure(())
end FetchRolesServiceSmithyEp

object FetchRolesServiceSmithyEp:
  def create[F[_]: Async](
      jobHandler: JobHandler[F],
      epErrors: EntryPointErrors[F],
  ): FetchRolesService[[A] =>> Kleisli[F, AuthenticatedUser, A]] =
    FetchRolesServiceSmithyEp[F](jobHandler, epErrors)
  end create
end FetchRolesServiceSmithyEp
