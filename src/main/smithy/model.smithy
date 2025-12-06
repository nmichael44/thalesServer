$version: "2.0"

namespace app.entrypoints.smithy

use app.model#javaInstant

structure BoRoleInDb {
    @required
    roleId: Long,

    @required
    roleName: String,

    @required
    createdBy: Long,

    @required
    creationTime: javaInstant,
}
