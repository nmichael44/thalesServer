package app

import cats.data.NonEmptyVector

import java.time.Instant

import app.auth.Permissions.Permission
import app.entrypoints.smithy.{PermissionInDb, Role, RoleInDb, User, UserInDb}
import app.model.AppModel.{AuthenticatedUser, LoginUserDetails}

object JobSpecs:
  enum JobKind(val shortName: String):
    // Users, roles, and permissions
    case CreateUserRequest(user: User, creatingUserId: Long) extends JobKind("createUserRequest")
    case FetchUsersByLoginNamesRequest(loginNames: NonEmptyVector[String]) extends JobKind("FetchUsersByLoginNamesRequest")
    case FetchUsersByUserIdsRequest(userIds: NonEmptyVector[Long]) extends JobKind("FetchUsersByIdsRequest")
    case FetchUserPermissionsRequest(userId: Long) extends JobKind("FetchUserPermissionsRequest")
    case CreateRoleRequest(role: Role, userId: Long) extends JobKind("CreateRoleRequest")
    case FetchAllRolesRequest extends JobKind("FetchAllRolesRequest")
    case FetchRoleByNameRequest(roleName: String) extends JobKind("FetchRoleByNameRequest")
    case FetchRoleByIdRequest(roleId: Long) extends JobKind("FetchRoleByIdRequest")
    case DeleteRoleByIdRequest(roleId: Long) extends JobKind("DeleteRoleByIdRequest")
    case FetchRolePermissionsByNameRequest(roleName: String) extends JobKind("FetchRolePermissionsByNameRequest")
    case FetchRolePermissionsByIdRequest(roleId: Long) extends JobKind("FetchRolePermissionsByIdRequest")
    case FetchAllPermissionsRequest extends JobKind("FetchAllPermissionsRequest")
    case UpdateUserRolesByIdRequest(userId: Long, roleIds: NonEmptyVector[Long]) extends JobKind("UpdateUserRolesByIdRequest")

    // Login and JWT management
    case LoginRequest(loginUserDetails: LoginUserDetails) extends JobKind("LoginRequest")

    // Password management.
    case ResetMyPasswordRequest(authUser: AuthenticatedUser, newPassword: String) extends JobKind("ResetMyPassword")
    case InitiateRecoveryOfUserPasswordRequest(loginName: String) extends JobKind("InitiateRecoveryOfUserPasswordRequest")
    case CheckResetUserPasswordTokenRequest(token: String) extends JobKind("CheckResetUserPasswordTokenRequest")
    case ResetUserPasswordRequest(token: String, newPassword: String) extends JobKind("ResetUserPasswordRequest")

    case RenewJwtTokenRequest(authenticatedUser: AuthenticatedUser) extends JobKind("RenewJwtRequest")

    // Apps
    case GetAppsForUser(permissions: Set[Permission]) extends JobKind("GetAppsForUser")

    // Admin
    case FetchAllLiveSessionsRequest extends JobKind("FetchAllLiveSessionsRequest")
    case FetchAllUsersAssociatedWithRolesRequest(roleIds: NonEmptyVector[Long])
        extends JobKind("FetchAllUsersAssociatedWithRolesRequest")
  end JobKind

  enum CreateUserError:
    case InvalidParameters(invalidParams: NonEmptyVector[(String, String)])
    case UniquenessConstraintViolated(errMsg: String)
    case BadPassword(errMsgs: NonEmptyVector[String])
  end CreateUserError

  enum FetchUserPermissionsError:
    case UserNotFound
  end FetchUserPermissionsError

  enum CreateRoleError:
    case DuplicateRoleName
    case InvalidParameters(invalidParams: NonEmptyVector[(String, String)])
  end CreateRoleError

  given CanEqual[CreateRoleError, CreateRoleError] = CanEqual.derived

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
    case NoSuchUser
    case UserIsDisabled
    case UserMustResetPassword
    case RenewalTimeHasExpired
  end RenewJwtTokenError

  given CanEqual[RenewJwtTokenError, RenewJwtTokenError] = CanEqual.derived

  enum ResetMyPasswordError:
    case UserNotEnabled
    case NewPasswordIsInvalid(reasons: NonEmptyVector[String])
    case FailedToUpdateUserRow(errStr: String)
  end ResetMyPasswordError

  given CanEqual[ResetMyPasswordError, ResetMyPasswordError] = CanEqual.derived

  enum InitiateRecoveryOfUserPasswordError:
    case A
  end InitiateRecoveryOfUserPasswordError

  enum CheckResetUserPasswordTokenError:
    case InvalidToken
    case ExpiredToken
  end CheckResetUserPasswordTokenError

  given CanEqual[CheckResetUserPasswordTokenError, CheckResetUserPasswordTokenError] = CanEqual.derived

  enum ResetUserPasswordError:
    case InvalidLoginPassword
    case LoginNameNotFound
    case NewPasswordIsInvalid(reasons: NonEmptyVector[String])
    case UserNotEnabled
    case FailedToUpdateUserRow(errStr: String)
  end ResetUserPasswordError

  enum JobResult:
    // Users, roles, and permissions
    case CreateUserResult(res: Either[CreateUserError, Long])
    case FetchUsersByLoginNamesResult(res: Map[String, UserInDb])
    case FetchUsersByUserIdsResult(res: Map[Long, UserInDb])
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

    case ResetMyPasswordResult(res: Either[ResetMyPasswordError, Unit])
    case InitiateRecoveryOfUserPasswordResult(res: Either[InitiateRecoveryOfUserPasswordError, Unit])
    case CheckResetUserPasswordTokenResult(res: Either[CheckResetUserPasswordTokenError, Unit])
    case ResetUserPasswordResult(res: Either[ResetUserPasswordError, Unit])

    case RenewJwtTokenResult(res: Either[RenewJwtTokenError, String])

    // Apps
    case GetAppsForUserResult(permissions: Set[Permission])

    // Admin
    case FetchAllLiveSessionsResult(res: Vector[(UserInDb, Instant)])
    case FetchAllUsersAssociatedWithRolesResult(res: Map[Long, Vector[UserInDb]])
  end JobResult
end JobSpecs
