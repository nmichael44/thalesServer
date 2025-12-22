$version: "2.0"

namespace app.entrypoints.smithy

use alloy#simpleRestJson
use smithy.api#http

@simpleRestJson
service LoginServices {
    version: "1.0.0",
    operations: [Login],
}

@http(method: "POST", uri: "/login", code: 200)
operation Login {
    input: LoginRequest,
    output: LoginResponse,
    errors: [
        InvalidLoginPassword,
        UserNotEnabled,
        PasswordResetRequired
    ]
}

structure LoginRequest {
    @required
    loginName: String,

    @required
    password: String,
}

structure LoginResponse {
    @required
    token: String,
}

structure InvalidLoginPassword with [UnauthorizedCode] {
    @required
    message: String,
}

structure UserNotEnabled with [LockedCode] {
    @required
    message: String,
}

structure PasswordResetRequired with [ForbiddenCode] {
    @required
    message: String,
}
