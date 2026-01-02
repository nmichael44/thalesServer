package app

import cats.data.NonEmptyVector

import java.time.Instant

import app.entrypoints.smithy.{LoginName, PermissionId, PermissionInDb, ResetPasswordToken, Role, RoleId, RoleInDb, RoleName, User, UserId, UserInDb, UserPassword}
import app.model.AppModel.AuthenticatedUser

object JobSpecs:
  enum JobKind(val shortName: String):
    // Users, roles, and permissions
    case CreateUserRequest(user: User, creatingUserId: UserId) extends JobKind("createUserRequest")
    case FetchUsersByLoginNamesRequest(loginNames: NonEmptyVector[LoginName]) extends JobKind("FetchUsersByLoginNamesRequest")
    case FetchUsersByUserIdsRequest(userIds: NonEmptyVector[UserId]) extends JobKind("FetchUsersByIdsRequest")
    case FetchUserPermissionsRequest(userId: UserId) extends JobKind("FetchUserPermissionsRequest")
    case CreateRoleRequest(role: Role, userId: UserId) extends JobKind("CreateRoleRequest")
    case FetchAllRolesRequest extends JobKind("FetchAllRolesRequest")
    case FetchRolesByIdsRequest(roleIds: NonEmptyVector[RoleId]) extends JobKind("FetchRoleByIdRequest")
    case DeleteRoleByIdRequest(roleId: RoleId) extends JobKind("DeleteRoleByIdRequest")
    case FetchRolePermissionsByNameRequest(roleName: RoleName) extends JobKind("FetchRolePermissionsByNameRequest")
    case FetchRolePermissionsByIdRequest(roleId: RoleId) extends JobKind("FetchRolePermissionsByIdRequest")
    case FetchAllPermissionsRequest extends JobKind("FetchAllPermissionsRequest")
    case UpdateUserRolesByIdRequest(userId: UserId, roleIds: NonEmptyVector[RoleId])
        extends JobKind("UpdateUserRolesByIdRequest")

    // Login and JWT management
    case LoginRequest(loginName: LoginName, password: UserPassword) extends JobKind("LoginRequest")
    case RenewJwtTokenRequest(authenticatedUser: AuthenticatedUser) extends JobKind("RenewJwtRequest")

    // Password management.
    // The user initiates a password change.
    case ResetMyPasswordRequest(authUser: AuthenticatedUser, newPassword: UserPassword) extends JobKind("ResetMyPassword")
    // The user initiates the I-forgot-my-password process
    case InitiateRecoveryOfUserPasswordRequest(loginName: LoginName) extends JobKind("InitiateRecoveryOfUserPasswordRequest")
    // Check if the given token (given to the user in the call above) is still valid. This is to help
    // the gui give a more friendly error message.
    case CheckResetUserPasswordTokenRequest(resetPasswordToken: ResetPasswordToken)
        extends JobKind("CheckResetUserPasswordTokenRequest")
    // Finally, reset the user's password to the newPassword, if the given token is valid.
    case ResetUserPasswordRequest(token: ResetPasswordToken, newPassword: UserPassword)
        extends JobKind("ResetUserPasswordRequest")

    // Apps
    case GetAppsForUser(permissions: Set[PermissionId]) extends JobKind("GetAppsForUser")

    // Admin
    case FetchAllLiveSessionsRequest extends JobKind("FetchAllLiveSessionsRequest")
    case FetchAllUsersAssociatedWithRolesRequest(roleIds: NonEmptyVector[RoleId])
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

  enum DeleteRoleByIdError:
    case NoSuchRoleId
    case RoleHasAssociatedUsers
  end DeleteRoleByIdError

  given CanEqual[DeleteRoleByIdError, DeleteRoleByIdError] = CanEqual.derived

  enum FetchRolePermissionsByError:
    case RoleNotFound
  end FetchRolePermissionsByError

  enum UpdateUserRolesByIdError:
    case NoSuchUser
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
    case CreateUserResult(res: Either[CreateUserError, UserId])
    case FetchUsersByLoginNamesResult(res: Map[String, UserInDb])
    case FetchUsersByUserIdsResult(res: Map[UserId, UserInDb])
    case FetchUserPermissionsResult(res: Either[FetchUserPermissionsError, Vector[PermissionId]])
    case CreateRoleResult(res: Either[CreateRoleError, RoleId])
    case FetchAllRolesResult(res: Vector[RoleInDb])

    case FetchRolesByIdsResult(roleIdToRole: Map[RoleId, RoleInDb])
    case DeleteRoleByIdResult(res: Either[DeleteRoleByIdError, Unit])
    case FetchRolePermissionsByNameResult(res: Either[FetchRolePermissionsByError, Vector[PermissionId]])
    case FetchRolePermissionsByIdResult(res: Either[FetchRolePermissionsByError, Vector[PermissionId]])
    case FetchAllPermissionsResult(res: Map[PermissionId, PermissionInDb])
    case UpdateUserRolesByIdResult(res: Either[UpdateUserRolesByIdError, Unit])

    // JWT management
    case LoginResult(res: Either[LoginError, (UserId, String)])

    case ResetMyPasswordResult(res: Either[ResetMyPasswordError, Unit])
    case InitiateRecoveryOfUserPasswordResult(res: Either[InitiateRecoveryOfUserPasswordError, Unit])
    case CheckResetUserPasswordTokenResult(res: Either[CheckResetUserPasswordTokenError, Unit])
    case ResetUserPasswordResult(res: Either[ResetUserPasswordError, Unit])

    case RenewJwtTokenResult(res: Either[RenewJwtTokenError, String])

    // Apps
    case GetAppsForUserResult(permissions: Set[PermissionId])

    // Admin
    case FetchAllLiveSessionsResult(sessionsVec: Vector[(UserId, Instant)])
    case FetchAllUsersAssociatedWithRolesResult(roleIdToUsers: Map[RoleId, Vector[UserInDb]])
  end JobResult
end JobSpecs
