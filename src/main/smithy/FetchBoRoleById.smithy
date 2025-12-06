$version: "2.0"

namespace app.entrypoints.smithy

@httpBearerAuth
@auth([httpBearerAuth])
service FetchBoRoleByIdService {
    version: "1.0.0",
    operations: [FetchBoRoleById]
}

@readonly
@http(method: "GET", uri: "/bo-role/{roleId}", code: 200)
operation FetchBoRoleById {
    input: FetchBoRoleByIdInput,
    output: BoRoleInDb,
    errors: [NotFound, Unauthorized, Forbidden]
}

@input
structure FetchBoRoleByIdInput {
    @httpLabel
    @required
    roleId: Long
}
