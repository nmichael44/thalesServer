$version: "2.0"

namespace app.entrypoints.smithy

use smithy4s.meta#vector
use alloy#simpleRestJson

@httpBearerAuth
@auth([httpBearerAuth])
@simpleRestJson
service RoleServices {
    version: "1.0.0",
    operations: [CreateRole
                 DeleteRoleById
                 FetchRoleById
                 FetchAllRoles]
}

@input
structure CreateRoleInput {
    @required
    role: Role
}

@output
structure CreateRoleOutput {
    @required
    roleId: Long
}

@http(method: "POST", uri: "/api/createRole", code: 200)
operation CreateRole {
    input: CreateRoleInput
    output: CreateRoleOutput
    errors: [Unauthorized, BadRequest, Conflict]
}

@input
structure DeleteRoleByIdInput {
    @httpLabel
    @required
    roleId: Long
}

@http(method: "POST", uri: "/api/deleteRoleId/{roleId}", code: 200)
operation DeleteRoleById {
    input: DeleteRoleByIdInput
    errors: [NotFound, Unauthorized, Forbidden, Conflict]
}

@input
structure FetchRoleByIdInput {
    @httpLabel
    @required
    roleId: Long
}

@readonly
@http(method: "GET", uri: "/api/fetchRole/{roleId}", code: 200)
operation FetchRoleById {
    input: FetchRoleByIdInput
    output: RoleInDb
    errors: [NotFound, Unauthorized, Forbidden]
}

@vector
list RoleVector {
    member: RoleInDb
}

@output
structure FetchAllRolesOutput {
    @required
    roles: RoleVector
}

@readonly
@http(method: "GET", uri: "/api/fetchAllRoles", code: 200)
operation FetchAllRoles {
    output: FetchAllRolesOutput
    errors: [Unauthorized, Forbidden]
}
