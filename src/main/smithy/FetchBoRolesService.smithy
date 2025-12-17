$version: "2.0"

namespace app.entrypoints.smithy

@httpBearerAuth
@auth([httpBearerAuth])
service FetchBoRolesService {
    version: "1.0.0",
    operations: [FetchBoRoleById,
                 FetchAllBoRoles]
}

@input
structure FetchBoRoleByIdInput {
    @httpLabel
    @required
    roleId: Long
}

@readonly
@http(method: "GET", uri: "/bo-role/{roleId}", code: 200)
operation FetchBoRoleById {
    input: FetchBoRoleByIdInput,
    output: BoRoleInDb,
    errors: [NotFound, Unauthorized, Forbidden]
}

list RoleVector {
    member: BoRoleInDb
}

@output
structure FetchAllBoRolesOutput {
    @required
    roles: RoleVector
}

@readonly
@http(method: "GET", uri: "/fetchAllRoles", code: 200)
operation FetchAllBoRoles {
    output: FetchAllBoRolesOutput,
    errors: [Unauthorized, Forbidden]
}
