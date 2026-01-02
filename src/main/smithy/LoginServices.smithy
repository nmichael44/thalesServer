$version: "2.0"

namespace app.entrypoints.smithy

use alloy#simpleRestJson
use smithy.api#http

@simpleRestJson
service LoginServices {
    version: "1.0.0"
    operations: [Login]
}

@http(method: "POST", uri: "/login", code: 200)
operation Login {
    input: LoginInput
    output: LoginOutput
    errors: [
        Unauthenticated
        UserNotEnabled
        PasswordResetRequired
    ]
}

structure LoginInput {
    @required
    loginName: LoginName

    @required
    password: UserPassword
}

structure LoginOutput {
    @required
    token: String
}

structure UserNotEnabled with [LockedCode] {
    @required
    message: String
}

structure PasswordResetRequired with [ForbiddenCode] {
    @required
    message: String
}
