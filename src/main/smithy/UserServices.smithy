$version: "2.0"

namespace app.entrypoints.smithy

use alloy#simpleRestJson

@httpBearerAuth
@auth([httpBearerAuth])
@simpleRestJson
service UserServices {
    version: "1.0.0",
    operations: [CreateUser]
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
