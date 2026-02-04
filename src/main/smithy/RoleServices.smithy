$version: "2.0"

namespace app.entrypoints.smithy

use smithy4s.meta#vector
use app.model#nonEmptyVecSmithy
use alloy#simpleRestJson

@httpBearerAuth
@auth([httpBearerAuth])
@simpleRestJson
service RoleServices {
    version: "1.0.0",
    operations: [CreateRole
                 DeleteRoleById
                 FetchRolesByIds
                 FetchAllRoles
                 FetchRolesPermissionsById]
}

@input
structure CreateRoleInput {
    @required
    role: Role
}

@output
structure CreateRoleOutput {
    @required
    roleId: RoleId
}

@http(method: "POST", uri: "/api/createRole", code: 200)
operation CreateRole {
    input: CreateRoleInput
    output: CreateRoleOutput
    errors: [UserIsUnAuthenticated, UserForbiddenFromCallingEntryPoint, InvalidInputParameters, DuplicateRoleName]
}

@input
structure DeleteRoleByIdInput {
    @httpLabel
    @required
    roleId: RoleId
}

@http(method: "POST", uri: "/api/deleteRoleId/{roleId}", code: 200)
operation DeleteRoleById {
    input: DeleteRoleByIdInput
    errors: [UserIsUnAuthenticated, UserForbiddenFromCallingEntryPoint, RoleNotFound, RoleHasUsers]
}

@http(method: "POST", uri: "/api/fetchRoles", code: 200)
operation FetchRolesByIds {
    input: FetchRolesByIdsInput
    output: FetchRolesByIdsOutput
    errors: [UserIsUnAuthenticated, UserForbiddenFromCallingEntryPoint]
}

@nonEmptyVecSmithy
list RoleIdVector {
    member: RoleId
}

@input
structure FetchRolesByIdsInput {
    @required
    roleIds: RoleIdVector
}

map RoleIdToRoleMap {
    key: String
    value: RoleInDb
}

@output
structure FetchRolesByIdsOutput {
    @required
    roleIdToRole: RoleIdToRoleMap
}

@readonly
@http(method: "GET", uri: "/api/fetchAllRoles", code: 200)
operation FetchAllRoles {
    output: FetchAllRolesOutput
    errors: [UserIsUnAuthenticated, UserForbiddenFromCallingEntryPoint]
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
@http(method: "POST", uri: "/api/fetchRolesPermissionsById", code: 200)
operation FetchRolesPermissionsById {
    input: FetchRolesPermissionsByIdInput
    output: FetchRolesPermissionsByIdOutput
    errors: [UserIsUnAuthenticated, UserForbiddenFromCallingEntryPoint]
}

@input
structure FetchRolesPermissionsByIdInput {
    @required
    roleIds: RoleIdVector
}

@output
structure FetchRolesPermissionsByIdOutput {
    @required
    roleIdToPermissions: RoleIdToPermissionsMap
}

@vector
list PermissionsVector {
    member: PermissionInDb
}

map RoleIdToPermissionsMap {
    key: String
    value: PermissionsVector
}
