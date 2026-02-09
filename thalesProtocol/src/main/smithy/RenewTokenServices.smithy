$version: "2.0"

namespace app.entrypoints.smithy

use alloy#simpleRestJson

@httpBearerAuth
@auth([httpBearerAuth])
@simpleRestJson
service RenewTokenServices {
    version: "1.0.0"
    operations: [RenewJwtToken]
}

@output
structure RenewJwtTokenOutput {
    @required
    newToken: String
}

@http(method: "POST", uri: "/api/renewJwtToken", code: 200)
operation RenewJwtToken {
    output: RenewJwtTokenOutput
    errors: [UserIsUnAuthenticated
             UserForbiddenFromCallingEntryPoint
             UserNotFound
             UserIsDisabled
             UserMustResetPassword
             RenewalTimeHasExpired]
}
