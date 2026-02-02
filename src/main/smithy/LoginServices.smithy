$version: "2.0"

namespace app.entrypoints.smithy

use alloy#simpleRestJson
use smithy.api#http

@simpleRestJson
service LoginServices {
    version: "1.0.0"
    operations: [Login
                 ResetUserPassword]
}

@http(method: "POST", uri: "/login", code: 200)
operation Login {
    input: LoginInput
    output: LoginOutput
    errors: [
        Unauthenticated
        UserNotEnabled
        PasswordResetRequired
        TooManyLoginAttempts
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

structure TooManyLoginAttempts with [TooManyRequestsCode] {
    @required
    message: String
}

@http(method: "POST", uri: "/resetUserPassword", code: 200)
operation ResetUserPassword {
    input: ResetUserPasswordInput
    errors: [Conflict, Forbidden, Gone]
}

structure ResetUserPasswordInput {
    @required
    resetPasswordToken: ResetPasswordToken

    @required
    newPassword: UserPassword
}
