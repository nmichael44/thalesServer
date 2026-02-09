$version: "2.0"

namespace app.entrypoints.smithy

use app.model#javaInstant
use app.model#nonEmptyVecSmithy
use smithy4s.meta#vector

long RoleId

string RoleName

long UserId

string LoginName

string UserPassword

string HashedUserPassword

long PermissionId

string PermissionName

string ResetPasswordToken

string HashedResetPasswordToken

@nonEmptyVecSmithy
list LoginNameList {
    member: LoginName
}

@nonEmptyVecSmithy
list UserIdList {
    member: UserId
}

@nonEmptyVecSmithy
list RoleIdList {
    member: RoleId
}

@vector
list UserSessionList {
    member: UserSession
}

structure RoleInDb {
    @required
    roleId: RoleId

    @required
    roleName: RoleName

    @required
    createdBy: UserId

    @required
    creationTime: javaInstant
}

structure Role {
    @required
    roleName: RoleName
}

structure PermissionInDb {
    @required
    permissionId: PermissionId

    @required
    permissionName: PermissionName
}

structure UserInDb {
    @required
    userId: UserId

    @required
    loginName: LoginName

    @required
    firstName: String

    @required
    lastName: String

    @required
    email: String

    @required
    phone: String

    @required
    creationTime: javaInstant,

    @required
    hashedPassword: HashedUserPassword

    @required
    mustResetPassword: Boolean

    @required
    userPasswordUpdateTime: javaInstant

    @required
    enabled: Boolean

    @required
    creatingUserId: UserId
}

structure User {
    @required
    loginName: LoginName

    @required
    firstName: String

    @required
    lastName: String

    @required
    email: String

    @required
    phone: String

    @required
    creationTime: javaInstant

    @required
    password: UserPassword

    @required
    mustResetPassword: Boolean

    @required
    userPasswordUpdateTime: javaInstant

    @required
    enabled: Boolean

    @required
    creatingUserId: UserId
}

structure UserSession {
    @required
    userId: UserId

    @required
    lastAccess: javaInstant
}
