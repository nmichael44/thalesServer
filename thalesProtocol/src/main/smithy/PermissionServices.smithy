$version: "2.0"

namespace app.entrypoints.smithy

use alloy#simpleRestJson

@httpBearerAuth
@auth([httpBearerAuth])
@simpleRestJson
service PermissionServices {
    version: "1.0.0"
    operations: [FetchAllPermissions]
}

map PermissionsMapById {
    key: String
    value: PermissionInDb
}

@output
structure FetchAllPermissionsOutput {
    @required
    permissions: PermissionsMapById
}

@readonly
@http(method: "GET", uri: "/api/fetchAllPermissions", code: 200)
operation FetchAllPermissions {
    output: FetchAllPermissionsOutput
    errors: [UserIsUnAuthenticated, UserForbiddenFromCallingEntryPoint]
}
