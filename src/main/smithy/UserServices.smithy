$version: "2.0"

namespace app.entrypoints.smithy

use smithy4s.meta#vector
use alloy#simpleRestJson

@httpBearerAuth
@auth([httpBearerAuth])
@simpleRestJson
service UserServices {
    version: "1.0.0",
    operations: [CreateUser,
                 FetchUsersByLoginNames]
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
    input: CreateUserInput,
    output: CreateUserOutput,
    errors: [Unauthorized, BadRequest, Conflict]
}

@http(method: "POST", uri: "/api/fetchUserByLoginNames", code: 200)
operation FetchUsersByLoginNames {
    input: FetchUsersByLoginNamesInput,
    output: FetchUsersByLoginNamesOutput,
    errors: [Unauthorized, BadRequest, Conflict]
}

structure FetchUsersByLoginNamesInput {
    @required
    loginNames: LoginNameList
}

structure FetchUsersByLoginNamesOutput {
    @required
    users: UserList
}

@vector
list LoginNameList {
    member: String
}

@vector
list UserList {
    member: UserInDb
}
