package app

import cats.data.NonEmptyVector

import java.time.Instant
import app.auth.Permissions.{Permission, PermissionInDb}
import app.model.AppModel.{AuthenticatedBoUser, BoRoleInDb, BoUser, BoUserInDb, LoginUserDetails}

object JobSpecs:
  enum JobKind(val shortName: String):
    // Bo Users, roles, and permissions
    case CreateBoUserRequest(boUser: BoUser) extends JobKind("createBoUserRequest")
    case FetchBoUserByLoginNameRequest(loginName: String) extends JobKind("FetchBoUserByLoginNameRequest")
    case FetchBoUserByIdRequest(userId: Long) extends JobKind("FetchBoUserByIdRequest")
    case FetchMultipleBoUsersByIdRequest(userIds: NonEmptyVector[Long]) extends JobKind("FetchMultipleBoUsersByIdRequest")
    case FetchBoUserPermissionsRequest(userId: Long) extends JobKind("FetchBoUserPermissionsRequest")
    case CreateBoRoleRequest(roleName: String) extends JobKind("CreateBoRoleRequest")
    case FetchAllBoRolesRequest() extends JobKind("FetchAllBoRolesRequest")
    case FetchBoRoleByNameRequest(roleName: String) extends JobKind("FetchBoRoleByNameRequest")
    case FetchBoRoleByIdRequest(roleId: Long) extends JobKind("FetchBoRoleByIdRequest")
    case DeleteRoleByIdRequest(roleId: Long) extends JobKind("DeleteRoleByIdRequest")
    case FetchBoRolePermissionsByNameRequest(roleName: String) extends JobKind("FetchBoRolePermissionsByNameRequest")
    case FetchBoRolePermissionsByIdRequest(roleId: Long) extends JobKind("FetchBoRolePermissionsByIdRequest")
    case FetchAllBoPermissionsRequest() extends JobKind("FetchAllBoPermissionsRequest")
    case UpdateBoUserRolesByIdRequest(userId: Long, roleIds: NonEmptyVector[Long])
        extends JobKind("UpdateBoUserRolesByIdRequest")

    // Login and JWT management
    case LoginRequest(loginUserDetails: LoginUserDetails) extends JobKind("LoginRequest")
    case ResetBoUserPasswordRequest(loginName: String, oldPassword: String, newPassword: String)
        extends JobKind("ResetPassword")
    case RenewJwtTokenRequest(authenticatedBoUser: AuthenticatedBoUser) extends JobKind("RenewJwtRequest")

    // Apps
    case GetAppsForUser(permissions: Set[Permission]) extends JobKind("GetAppsForUser")

    // Admin
    case FetchAllLiveSessionsRequest() extends JobKind("FetchAllLiveSessionsRequest")
  end JobKind

  enum CreateBoUserError:
    case InvalidParameters(invalidParams: NonEmptyVector[(String, String)])
    case DuplicateLoginName(loginName: String)
    case BadPassword(errorList: NonEmptyVector[String])
  end CreateBoUserError

  enum FetchBoUserByError:
    case UserNotFound()
  end FetchBoUserByError

  enum FetchBoUserPermissionsError:
    case UserNotFound()
  end FetchBoUserPermissionsError

  enum CreateBoRoleError:
    case RoleAlreadyExists(roleName: String)
  end CreateBoRoleError

  enum FetchBoRoleByError:
    case NoSuchRole()
  end FetchBoRoleByError

  enum DeleteRoleByIdError:
    case NoSuchRoleId(roleId: Long)
  end DeleteRoleByIdError

  enum FetchBoRolePermissionsByError:
    case NoSuchRole()
  end FetchBoRolePermissionsByError

  enum UpdateBoUserRolesByIdError:
    case NoSuchUser(userId: Long)
  end UpdateBoUserRolesByIdError

  enum LoginError:
    case InvalidLoginPassword()
    case UserNotEnabled()
    case UserMustResetPassword()
  end LoginError

  enum RenewJwtTokenError:
    case NoSuchUser(userId: Long)
    case UserIsDisabled(userId: Long)
    case UserMustResetPassword(userId: Long)
    case RenewalTimeHasExpired()
  end RenewJwtTokenError

  enum ResetBoUserPasswordError:
    case LoginNameNotFound()
    case UserNotEnabled()
    case InvalidLoginPassword()
    case NewPasswordInsufficient(reasons: NonEmptyVector[String])
    case FailedToUpdateUserRow(errStr: String)
  end ResetBoUserPasswordError

  enum JobResult:
    // Bo Users, roles, and permissions
    case CreateBoUserResult(res: Either[CreateBoUserError, Long])
    case FetchBoUserByLoginNameResult(res: Either[FetchBoUserByError, BoUserInDb])
    case FetchBoUserByIdResult(res: Either[FetchBoUserByError, BoUserInDb])
    case FetchMultipleBoUsersByIdResult(res: Map[Long, BoUserInDb])
    case FetchBoUserPermissionsResult(res: Either[FetchBoUserPermissionsError, Vector[Permission]])
    case CreateBoRoleResult(res: Either[CreateBoRoleError, Long])
    case FetchAllBoRolesResult(res: Vector[BoRoleInDb])

    case FetchBoRoleByNameResult(res: Either[FetchBoRoleByError, BoRoleInDb])
    case FetchBoRoleByIdResult(res: Either[FetchBoRoleByError, BoRoleInDb])
    case DeleteRoleByIdResult(res: Either[DeleteRoleByIdError, Unit])
    case FetchBoRolePermissionsByNameResult(res: Either[FetchBoRolePermissionsByError, Vector[Permission]])
    case FetchBoRolePermissionsByIdResult(res: Either[FetchBoRolePermissionsByError, Vector[Permission]])
    case FetchAllBoPermissionsResult(res: Vector[PermissionInDb])
    case UpdateBoUserRolesByIdResult(res: Either[UpdateBoUserRolesByIdError, Unit])

    // JWT management
    case LoginResult(res: Either[LoginError, (Long, String)])
    case ResetBoUserPasswordResult(res: Either[ResetBoUserPasswordError, Unit])
    case RenewJwtTokenResult(res: Either[RenewJwtTokenError, String])

    // Apps
    case GetAppsForUserResult(permissions: Set[Permission])

    // Admin
    case FetchAllLiveSessionsResult(res: Vector[(BoUserInDb, Instant)])
  end JobResult
end JobSpecs
