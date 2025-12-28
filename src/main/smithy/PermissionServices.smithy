$version: "2.0"

namespace app.entrypoints.smithy

use smithy4s.meta#vector
use alloy#simpleRestJson

@httpBearerAuth
@auth([httpBearerAuth])
@simpleRestJson
service PermissionServices {
    version: "1.0.0"
    operations: [FetchAllPermissions]
}

@vector
list PermissionVector {
    member: PermissionInDb
}

@output
structure FetchAllPermissionsOutput {
    @required
    permissions: PermissionVector
}

@readonly
@http(method: "GET", uri: "/api/fetchAllPermissions", code: 200)
operation FetchAllPermissions {
    output: FetchAllPermissionsOutput
    errors: [Unauthorized, Forbidden]
}
