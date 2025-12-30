$version: "2.0"

namespace app.entrypoints.smithy

use alloy#simpleRestJson
use app.model#nonEmptyVecSmithy
use smithy4s.meta#vector

@httpBearerAuth
@auth([httpBearerAuth])
@simpleRestJson
service UserServices {
    version: "1.0.0",
    operations: [CreateUser
                 FetchUsersByLoginNames
                 FetchUsersByUserIds
                 FetchAllUsersAssociatedWithRoles
                 ResetMyPassword
                 CheckResetUserPasswordToken]
}

@input
structure CreateUserInput {
    @required
    user: User
}

@output
structure CreateUserOutput {
    @required
    userId: Long
}

@http(method: "POST", uri: "/api/createUser", code: 200)
operation CreateUser {
    input: CreateUserInput
    output: CreateUserOutput
    errors: [Unauthorized, BadRequest, Conflict]
}

@http(method: "POST", uri: "/api/fetchUserByLoginNames", code: 200)
operation FetchUsersByLoginNames {
    input: FetchUsersByLoginNamesInput
    output: FetchUsersByLoginNamesOutput
    errors: [Unauthorized]
}

structure FetchUsersByLoginNamesInput {
    @required
    loginNames: LoginNameList
}

structure FetchUsersByLoginNamesOutput {
    @required
    users: UserMapByLoginName
}

@nonEmptyVecSmithy
list LoginNameList {
    member: String
}

map UserMapByLoginName {
    key: String
    value: UserInDb
}

@http(method: "POST", uri: "/api/fetchUsersByUserIds", code: 200)
operation FetchUsersByUserIds {
    input: FetchUsersByUserIdsInput
    output: FetchUsersByUserIdsOutput
    errors: [Unauthorized]
}

structure FetchUsersByUserIdsInput {
    @required
    userIds: UserIdList
}

structure FetchUsersByUserIdsOutput {
    @required
    users: UserMapById
}

@nonEmptyVecSmithy
list UserIdList {
    member: Long
}

map UserMapById {
    key: String
    value: UserInDb
}

@http(method: "POST", uri: "/api/fetchAllUsersAssociatedWithRoles", code: 200)
operation FetchAllUsersAssociatedWithRoles {
    input: FetchAllUsersAssociatedWithRolesInput
    output: FetchAllUsersAssociatedWithRolesOutput
    errors: [Unauthorized]
}

structure FetchAllUsersAssociatedWithRolesInput {
    @required
    roleIds: RoleIdList
}

structure FetchAllUsersAssociatedWithRolesOutput {
    @required
    roleIdToUsers: RoleIdToUsersMap
}

map RoleIdToUsersMap {
    key: String
    value: UserList
}

@nonEmptyVecSmithy
list RoleIdList {
    member: Long
}

@vector
list UserList {
    member: UserInDb
}

@http(method: "POST", uri: "/api/resetMyPassword", code: 200)
operation ResetMyPassword {
    input: ResetMyPasswordInput
    errors: [Unauthorized, Forbidden, Conflict]
}

structure ResetMyPasswordInput {
    @required
    newPassword: String
}

@http(method: "POST", uri: "/api/checkResetUserPasswordToken", code: 200)
operation CheckResetUserPasswordToken {
    input: CheckResetUserPasswordTokenInput
    errors: [Unauthorized, NotFound, Gone]
}

structure CheckResetUserPasswordTokenInput {
    @required
    token: String
}
