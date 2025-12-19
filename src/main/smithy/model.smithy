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
