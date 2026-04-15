package app.entrypoints

import cats.data.{Kleisli, NonEmptyVector}
import cats.effect.Async

import app.JobSpecs.CreateRoleError.{DuplicateRoleName, InvalidParameters}
import app.JobSpecs.DeleteRoleByIdError.{NoSuchRoleId, RoleHasAssociatedUsers}
import app.JobSpecs.JobKind.{CreateRoleRequest, DeleteRoleByIdRequest, FetchAllRolesRequest, FetchRolesByIdsRequest, FetchRolesPermissionsByIdRequest}
import app.JobSpecs.JobResult
import app.JobSpecs.JobResult.{CreateRoleResult, DeleteRoleByIdResult, FetchAllRolesResult, FetchRolesByIdsResult, FetchRolesPermissionsByIdResult}
import app.ThalesUtils.GenUtils as U
import app.auth.Permissions
import app.auth.Permissions.{CompiledPermissionAlgebra, PermissionAlgebra}
import app.entrypoints.EntryPointUtils as EPU
import app.entrypoints.smithy.{CreateRoleOutput, FetchAllRolesOutput, FetchRolesByIdsOutput, FetchRolesPermissionsByIdOutput, PermissionsVector, Role, RoleId, RoleIdVector, RoleInDb, RoleServices}
import app.model.AppModel.AuthenticatedUser

private final class RoleServicesSmithyEp[F[_]: Async as async] private (
    jobHandler: JobHandler[F],
    epErrors: EntryPointErrors[F],
) extends RoleServices[[A] =>> Kleisli[F, AuthenticatedUser, A]]:
  override def createRole(role: Role): Kleisli[F, AuthenticatedUser, CreateRoleOutput] =
    def successResult(roleId: RoleId): F[CreateRoleOutput] =
      async.pure(CreateRoleOutput(roleId))
    end successResult

    def resultToResponse(jobResult: JobResult): F[CreateRoleOutput] =
      jobResult match
        case CreateRoleResult(res) =>
          res.fold(
            {
              case DuplicateRoleName => epErrors.duplicateRoleName
              case InvalidParameters(invalidParams) => epErrors.invalidInputParameters(invalidParams)
            },
            successResult,
          )
        case _ => EPU.internalServerError(epErrors, "CreateRole")
    end resultToResponse

    Kleisli: authUser =>
      jobHandler.jobHandlerWithAuth(
        authUser,
        CreateRoleIdPermissionsAlg,
        CreateRoleRequest(role, authUser.userId),
        resultToResponse,
      )
  end createRole

  private val CreateRoleIdPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permissions.CanCreateRoles).compile
  end CreateRoleIdPermissionsAlg

  override def deleteRoleById(roleId: RoleId): Kleisli[F, AuthenticatedUser, Unit] =
    def resultToResponse(jobResult: JobResult): F[Unit] =
      jobResult match
        case DeleteRoleByIdResult(res) =>
          res.fold(
            {
              case NoSuchRoleId => epErrors.roleNotFound
              case RoleHasAssociatedUsers => epErrors.roleHasUsers
            },
            _ => successResult,
          )
        case _ => EPU.internalServerError(epErrors, "DeleteRoleById")
    end resultToResponse

    Kleisli: authUser =>
      jobHandler.jobHandlerWithAuth(
        authUser,
        DeleteRoleByIdPermissionsAlg,
        DeleteRoleByIdRequest(roleId),
        resultToResponse,
      )
  end deleteRoleById

  private val DeleteRoleByIdPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permissions.CanDeleteRoles).compile
  end DeleteRoleByIdPermissionsAlg

  override def fetchRolesByIds(roleIds: RoleIdVector): Kleisli[F, AuthenticatedUser, FetchRolesByIdsOutput] =
    def resultToResponse(jobResult: JobResult): F[FetchRolesByIdsOutput] =
      jobResult match
        case FetchRolesByIdsResult(roleIdToRole) => async.pure(FetchRolesByIdsOutput(roleIdToRole.map(U.mapFirst(_.toString))))
        case _ => EPU.internalServerError(epErrors, "FetchRolesByIds")
    end resultToResponse

    Kleisli: authUser =>
      jobHandler.jobHandlerWithAuth(
        authUser,
        FetchRolePermissionsAlg,
        FetchRolesByIdsRequest(roleIds.value),
        resultToResponse,
      )
  end fetchRolesByIds

  private val FetchRolePermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permissions.CanSeeAllRoles).compile
  end FetchRolePermissionsAlg

  override def fetchAllRoles(): Kleisli[F, AuthenticatedUser, FetchAllRolesOutput] =
    fetchAllRolesProgram
  end fetchAllRoles

  private val fetchAllRolesProgram: Kleisli[F, AuthenticatedUser, FetchAllRolesOutput] =
    def resultToResponse(jobResult: JobResult): F[FetchAllRolesOutput] =
      jobResult match
        case FetchAllRolesResult(res) => async.pure(FetchAllRolesOutput(res))
        case _ => EPU.internalServerError(epErrors, "FetchAllRoles")
    end resultToResponse

    Kleisli: authUser =>
      jobHandler.jobHandlerWithAuth(
        authUser,
        FetchAllRolesPermissionsAlg,
        FetchAllRolesRequest,
        resultToResponse,
      )
  end fetchAllRolesProgram

  private val FetchAllRolesPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permissions.CanSeeAllRoles).compile
  end FetchAllRolesPermissionsAlg

  override def fetchRolesPermissionsById(
      roleIds: RoleIdVector,
  ): Kleisli[F, AuthenticatedUser, FetchRolesPermissionsByIdOutput] =
    def resultToResponse(jobResult: JobResult): F[FetchRolesPermissionsByIdOutput] =
      jobResult match
        case FetchRolesPermissionsByIdResult(roleIdToPermissions) =>
          async.pure(FetchRolesPermissionsByIdOutput(roleIdToPermissions.map(U.mapFirst(_.toString))))
        case _ => EPU.internalServerError(epErrors, "FetchRolesPermissionsById")
    end resultToResponse

    Kleisli: authUser =>
      jobHandler.jobHandlerWithAuth(
        authUser,
        FetchRolesPermissionsByIdPermissionsAlg,
        FetchRolesPermissionsByIdRequest(roleIds.value),
        resultToResponse,
      )
  end fetchRolesPermissionsById

  private val FetchRolesPermissionsByIdPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra
      .And(
        NonEmptyVector.of(
          PermissionAlgebra.Has(Permissions.CanSeeAllRoles),
          PermissionAlgebra.Has(Permissions.CanSeeAllPermissions),
        ),
      )
      .compile
  end FetchRolesPermissionsByIdPermissionsAlg

  private val successResult: F[Unit] = async.pure(())
end RoleServicesSmithyEp

object RoleServicesSmithyEp:
  def create[F[_]: Async](
      jobHandler: JobHandler[F],
      epErrors: EntryPointErrors[F],
  ): RoleServices[[A] =>> Kleisli[F, AuthenticatedUser, A]] =
    RoleServicesSmithyEp[F](jobHandler, epErrors)
  end create
end RoleServicesSmithyEp
