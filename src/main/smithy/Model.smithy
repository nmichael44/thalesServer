$version: "2.0"

namespace app.entrypoints.smithy

use app.model#javaInstant

structure RoleInDb {
    @required
    roleId: Long,

    @required
    roleName: String,

    @required
    createdBy: Long,

    @required
    creationTime: javaInstant,
}

structure Role {
    @required
    roleName: String
}

structure PermissionInDb {
    @required
    permissionId: Long,

    @required
    permissionName: String
}

structure UserInDb {
    @required
    userId: Long,

    @required
    loginName: String,

    @required
    firstName: String,

    @required
    lastName: String,

    @required
    email: String,

    @required
    phone: String,

    @required
    creationTime: javaInstant,

    @required
    hashedPassword: String,

    @required
    mustResetPassword: Boolean,

    @required
    userPasswordUpdateTime: javaInstant,

    @required
    enabled: Boolean,
}

structure User {
    @required
    loginName: String,

    @required
    firstName: String,

    @required
    lastName: String,

    @required
    email: String,

    @required
    phone: String,

    @required
    creationTime: javaInstant,

    @required
    password: String,

    @required
    mustResetPassword: Boolean,

    @required
    userPasswordUpdateTime: javaInstant,

    @required
    enabled: Boolean,
}
