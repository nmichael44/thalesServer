package app

import cats.data.NonEmptyVector

import java.time.Instant

import app.auth.Permissions.{Permission, PermissionInDb}
import app.entrypoints.smithy.RoleInDb
import app.model.AppModel.{AuthenticatedUser, LoginUserDetails, Role, User, UserInDb}

object JobSpecs:
  enum JobKind(val shortName: String):
    // Users, roles, and permissions
    case CreateUserRequest(user: User) extends JobKind("createUserRequest")
    case FetchUserByLoginNameRequest(loginName: String) extends JobKind("FetchUserByLoginNameRequest")
    case FetchUserByIdRequest(userId: Long) extends JobKind("FetchUserByIdRequest")
    case FetchMultipleUsersByIdRequest(userIds: NonEmptyVector[Long]) extends JobKind("FetchMultipleUsersByIdRequest")
    case FetchUserPermissionsRequest(userId: Long) extends JobKind("FetchUserPermissionsRequest")
    case CreateRoleRequest(role: Role, userId: Long) extends JobKind("CreateRoleRequest")
    case FetchAllRolesRequest() extends JobKind("FetchAllRolesRequest")
    case FetchRoleByNameRequest(roleName: String) extends JobKind("FetchRoleByNameRequest")
    case FetchRoleByIdRequest(roleId: Long) extends JobKind("FetchRoleByIdRequest")
    case DeleteRoleByIdRequest(roleId: Long) extends JobKind("DeleteRoleByIdRequest")
    case FetchRolePermissionsByNameRequest(roleName: String) extends JobKind("FetchRolePermissionsByNameRequest")
    case FetchRolePermissionsByIdRequest(roleId: Long) extends JobKind("FetchRolePermissionsByIdRequest")
    case FetchAllPermissionsRequest() extends JobKind("FetchAllPermissionsRequest")
    case UpdateUserRolesByIdRequest(userId: Long, roleIds: NonEmptyVector[Long]) extends JobKind("UpdateUserRolesByIdRequest")

    // Login and JWT management
    case LoginRequest(loginUserDetails: LoginUserDetails) extends JobKind("LoginRequest")
    case ResetUserPasswordRequest(loginName: String, oldPassword: String, newPassword: String) extends JobKind("ResetPassword")
    case RenewJwtTokenRequest(authenticatedUser: AuthenticatedUser) extends JobKind("RenewJwtRequest")

    // Apps
    case GetAppsForUser(permissions: Set[Permission]) extends JobKind("GetAppsForUser")

    // Admin
    case FetchAllLiveSessionsRequest() extends JobKind("FetchAllLiveSessionsRequest")
    case FetchAllUsersAssociatedWithRoleRequest(roleId: Long) extends JobKind("FetchAllUsersAssociatedWithRoleRequest")
  end JobKind

  enum CreateUserError:
    case InvalidParameters(invalidParams: NonEmptyVector[(String, String)])
    case UniquenessConstraintViolated(errMsg: String)
    case BadPassword(errorList: NonEmptyVector[String])
  end CreateUserError

  enum FetchUserByError:
    case UserNotFound
  end FetchUserByError

  given CanEqual[FetchUserByError, FetchUserByError] = CanEqual.derived

  enum FetchUserPermissionsError:
    case UserNotFound
  end FetchUserPermissionsError

  enum CreateRoleError:
    case DuplicateRoleName(roleName: String)
    case InvalidParameters(invalidParams: NonEmptyVector[(String, String)])
  end CreateRoleError

  enum FetchRoleByError:
    case RoleNotFound
  end FetchRoleByError

  given CanEqual[FetchRoleByError, FetchRoleByError] = CanEqual.derived

  enum DeleteRoleByIdError:
    case NoSuchRoleId
    case RoleHasAssociatedUsers
  end DeleteRoleByIdError

  given CanEqual[DeleteRoleByIdError, DeleteRoleByIdError] = CanEqual.derived

  enum FetchRolePermissionsByError:
    case RoleNotFound
  end FetchRolePermissionsByError

  enum UpdateUserRolesByIdError:
    case NoSuchUser(userId: Long)
  end UpdateUserRolesByIdError

  enum LoginError:
    case InvalidLoginPassword
    case UserNotEnabled
    case UserMustResetPassword
  end LoginError

  given CanEqual[LoginError, LoginError] = CanEqual.derived

  enum RenewJwtTokenError:
    case NoSuchUser(userId: Long)
    case UserIsDisabled(userId: Long)
    case UserMustResetPassword(userId: Long)
    case RenewalTimeHasExpired
  end RenewJwtTokenError

  given CanEqual[RenewJwtTokenError, RenewJwtTokenError] = CanEqual.derived

  enum ResetUserPasswordError:
    case LoginNameNotFound
    case UserNotEnabled
    case InvalidLoginPassword
    case NewPasswordInsufficient(reasons: NonEmptyVector[String])
    case FailedToUpdateUserRow(errStr: String)
  end ResetUserPasswordError

  given CanEqual[ResetUserPasswordError, ResetUserPasswordError] = CanEqual.derived

  enum FetchAllUsersAssociatedWithRoleError:
    case NoSuchRole
  end FetchAllUsersAssociatedWithRoleError

  given CanEqual[FetchAllUsersAssociatedWithRoleError, FetchAllUsersAssociatedWithRoleError] = CanEqual.derived

  enum JobResult:
    // Users, roles, and permissions
    case CreateUserResult(res: Either[CreateUserError, Long])
    case FetchUserByLoginNameResult(res: Either[FetchUserByError, UserInDb])
    case FetchUserByIdResult(res: Either[FetchUserByError, UserInDb])
    case FetchMultipleUsersByIdResult(res: Map[Long, UserInDb])
    case FetchUserPermissionsResult(res: Either[FetchUserPermissionsError, Vector[Permission]])
    case CreateRoleResult(res: Either[CreateRoleError, Long])
    case FetchAllRolesResult(res: Vector[RoleInDb])

    case FetchRoleByNameResult(res: Either[FetchRoleByError, RoleInDb])
    case FetchRoleByIdResult(res: Either[FetchRoleByError, RoleInDb])
    case DeleteRoleByIdResult(res: Either[DeleteRoleByIdError, Unit])
    case FetchRolePermissionsByNameResult(res: Either[FetchRolePermissionsByError, Vector[Permission]])
    case FetchRolePermissionsByIdResult(res: Either[FetchRolePermissionsByError, Vector[Permission]])
    case FetchAllPermissionsResult(res: Vector[PermissionInDb])
    case UpdateUserRolesByIdResult(res: Either[UpdateUserRolesByIdError, Unit])

    // JWT management
    case LoginResult(res: Either[LoginError, (Long, String)])
    case ResetUserPasswordResult(res: Either[ResetUserPasswordError, Unit])
    case RenewJwtTokenResult(res: Either[RenewJwtTokenError, String])

    // Apps
    case GetAppsForUserResult(permissions: Set[Permission])

    // Admin
    case FetchAllLiveSessionsResult(res: Vector[(UserInDb, Instant)])
    case FetchAllUsersAssociatedWithRoleResult(res: Either[FetchAllUsersAssociatedWithRoleError, Vector[UserInDb]])
  end JobResult
end JobSpecs
