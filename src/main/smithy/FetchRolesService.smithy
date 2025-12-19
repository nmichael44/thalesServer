$version: "2.0"

namespace app.entrypoints.smithy

use smithy4s.meta#vector

@httpBearerAuth
@auth([httpBearerAuth])
service FetchRolesService {
    version: "1.0.0",
    operations: [CreateRole,
                 DeleteRoleById,
                 FetchRoleById,
                 FetchAllRoles]
}

@input
structure CreateRoleInput {
    @required
    role: Role
}

@http(method: "POST", uri: "/createRole", code: 200)
operation CreateRole {
    input: CreateRoleInput,
    errors: [Unauthorized, BadRequest, Conflict]
}

@input
structure DeleteRoleByIdInput {
    @httpLabel
    @required
    roleId: Long
}

@http(method: "POST", uri: "/deleteRoleId/{roleId}", code: 200)
operation DeleteRoleById {
    input: DeleteRoleByIdInput,
    errors: [NotFound, Unauthorized, Forbidden, Conflict]
}

@input
structure FetchRoleByIdInput {
    @httpLabel
    @required
    roleId: Long
}

@readonly
@http(method: "GET", uri: "/role/{roleId}", code: 200)
operation FetchRoleById {
    input: FetchRoleByIdInput,
    output: RoleInDb,
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
@http(method: "GET", uri: "/fetchAllRoles", code: 200)
operation FetchAllRoles {
    output: FetchAllRolesOutput,
    errors: [Unauthorized, Forbidden]
}
