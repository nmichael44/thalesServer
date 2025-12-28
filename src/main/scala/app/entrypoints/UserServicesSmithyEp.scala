package app.entrypoints

import cats.data.{Kleisli, NonEmptyVector}
import cats.effect.Async

import app.JobSpecs.CreateUserError.{BadPassword, InvalidParameters, UniquenessConstraintViolated}
import app.JobSpecs.FetchAllUsersAssociatedWithRoleError.NoSuchRole
import app.JobSpecs.JobKind.{CreateUserRequest, FetchAllUsersAssociatedWithRoleRequest, FetchUsersByLoginNamesRequest, FetchUsersByUserIdsRequest}
import app.JobSpecs.JobResult
import app.JobSpecs.JobResult.{CreateUserResult, FetchAllUsersAssociatedWithRoleResult, FetchUsersByLoginNamesResult, FetchUsersByUserIdsResult}
import app.ThalesUtils.GenUtils as U
import app.auth.Permissions
import app.auth.Permissions.{CompiledPermissionAlgebra, PermissionAlgebra}
import app.entrypoints.FetchUserByPermissionsUtils.FetchUserPermissionsAlg
import app.entrypoints.smithy.{CreateUserOutput, FetchAllUsersAssociatedWithRoleOutput, FetchUsersByLoginNamesOutput, FetchUsersByUserIdsOutput, LoginNameList, User, UserIdList, UserInDb, UserServices}
import app.model.AppModel.AuthenticatedUser
import app.services.UpdateUserRolesDbError.NoSuchRoleIds

private final class UserServicesSmithyEp[F[_]: Async as async] private (
    jobHandler: JobHandler[F],
    epErrors: EntryPointErrors[F],
) extends UserServices[[A] =>> Kleisli[F, AuthenticatedUser, A]]:
  override def createUser(user: User): Kleisli[F, AuthenticatedUser, CreateUserOutput] =
    def successResult(userId: Long): F[CreateUserOutput] =
      async.pure(CreateUserOutput(userId))
    end successResult

    def resultToResponse(jobResult: JobResult): F[CreateUserOutput] =
      jobResult match {
        case CreateUserResult(res) =>
          res.fold(
            {
              case UniquenessConstraintViolated(errMsg: String) => epErrors.uniquenessConstraintViolated(errMsg)
              case BadPassword(errorList: NonEmptyVector[String]) => epErrors.usersPasswordIsInvalid
              case InvalidParameters(invalidParams: NonEmptyVector[(String, String)]) =>
                epErrors.badRequestF(U.paramsToStr(invalidParams))
            },
            successResult,
          )
        case _ => epErrors.internalServerErrorF("CreateUser: Bad pattern match for result.")
      }
    end resultToResponse

    Kleisli { authUser =>
      jobHandler.jobHandlerWithAuth2(
        authUser,
        CreateUserPermissionsAlg,
        CreateUserRequest(user, authUser.userId),
        resultToResponse,
      )
    }
  end createUser

  private val CreateUserPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permissions.CanCreateUsers).compile
  end CreateUserPermissionsAlg

  override def fetchUsersByLoginNames(
      loginNames: LoginNameList,
  ): Kleisli[F, AuthenticatedUser, FetchUsersByLoginNamesOutput] =
    def successResult(users: Map[String, UserInDb]): F[FetchUsersByLoginNamesOutput] =
      async.pure(FetchUsersByLoginNamesOutput(users))
    end successResult

    def resultToResponse(jobResult: JobResult): F[FetchUsersByLoginNamesOutput] =
      jobResult match {
        case FetchUsersByLoginNamesResult(res) => successResult(res)
        case _ => epErrors.internalServerErrorF("FetchUsersByLoginNames: Bad pattern match for result.")
      }
    end resultToResponse

    Kleisli { authUser =>
      jobHandler.jobHandlerWithAuth2(
        authUser,
        FetchUserPermissionsAlg,
        FetchUsersByLoginNamesRequest(loginNames.value),
        resultToResponse,
      )
    }
  end fetchUsersByLoginNames

  override def fetchUsersByUserIds(
      userIds: UserIdList,
  ): Kleisli[F, AuthenticatedUser, FetchUsersByUserIdsOutput] =
    def successResult(users: Map[Long, UserInDb]): F[FetchUsersByUserIdsOutput] =
      async.pure(FetchUsersByUserIdsOutput(users.map((userId, user) => (userId.toString, user))))
    end successResult

    def resultToResponse(jobResult: JobResult): F[FetchUsersByUserIdsOutput] =
      jobResult match {
        case FetchUsersByUserIdsResult(res) => successResult(res)
        case _ => epErrors.internalServerErrorF("FetchUsersByUserIds: Bad pattern match for result.")
      }
    end resultToResponse

    Kleisli { authUser =>
      jobHandler.jobHandlerWithAuth2(
        authUser,
        FetchUserPermissionsAlg,
        FetchUsersByUserIdsRequest(userIds.value),
        resultToResponse,
      )
    }
  end fetchUsersByUserIds

  def fetchAllUsersAssociatedWithRole(roleId: Long): Kleisli[F, AuthenticatedUser, FetchAllUsersAssociatedWithRoleOutput] =
    def successResult(users: Vector[UserInDb]): F[FetchAllUsersAssociatedWithRoleOutput] =
      async.pure(FetchAllUsersAssociatedWithRoleOutput(users))
    end successResult

    def resultToResponse(jobResult: JobResult): F[FetchAllUsersAssociatedWithRoleOutput] =
      jobResult match {
        case FetchAllUsersAssociatedWithRoleResult(res) =>
          res.fold({ case NoSuchRole => epErrors.roleNotFoundF }, successResult)
        case _ => epErrors.internalServerErrorF("FetchAllUsersAssociatedWithRole: Bad pattern match for result.")
      }
    end resultToResponse

    Kleisli { authUser =>
      jobHandler.jobHandlerWithAuth2(
        authUser,
        FetchUserPermissionsAlg,
        FetchAllUsersAssociatedWithRoleRequest(roleId),
        resultToResponse,
      )
    }
  end fetchAllUsersAssociatedWithRole
end UserServicesSmithyEp

object UserServicesSmithyEp:
  def create[F[_]: Async](
      jobHandler: JobHandler[F],
      epErrors: EntryPointErrors[F],
  ): UserServices[[A] =>> Kleisli[F, AuthenticatedUser, A]] =
    UserServicesSmithyEp[F](jobHandler, epErrors)
  end create
end UserServicesSmithyEp
