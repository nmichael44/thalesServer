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
                 FetchAllUsersAssociatedWithRole]
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

@http(method: "POST", uri: "/api/fetchAllUsersAssociatedWithRole", code: 200)
operation FetchAllUsersAssociatedWithRole {
    input: FetchAllUsersAssociatedWithRoleInput
    output: FetchAllUsersAssociatedWithRoleOutput
    errors: [Unauthorized, NotFound]
}

structure FetchAllUsersAssociatedWithRoleInput {
    @required
    roleId: Long
}

structure FetchAllUsersAssociatedWithRoleOutput {
    @required
    users: UserList
}

@vector
list UserList {
    member: UserInDb
}
