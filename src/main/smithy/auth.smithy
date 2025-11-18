$version: "2.0"

namespace com.thales.auth

use alloy#simpleRestJson
use smithy.api#auth
use smithy.api#http
use smithy.api#httpError
use smithy.api#sensitive
use smithy.api#tags
use smithy.api#Unit

@simpleRestJson
service AuthService {
    version: "1.0.0",
    operations: [Login, RenewJwtToken],
    errors: [InvalidCredentials, RenewalTimeHasExpired, Forbidden]
}

// ---------------------------------
// Operations
// ---------------------------------

@http(method: "POST", uri: "/login")
operation Login {
    input: LoginRequest,
    output: LoginResponse,
    errors: [InvalidCredentials]
}

@http(method: "POST", uri: "/renewJwtToken")
operation RenewJwtToken {
    input: Unit,
    output: RenewJwtTokenResponse,
    errors: [RenewalTimeHasExpired, Forbidden]
}

structure LoginRequest {
    @required
    username: String,
    @required
    password: String,
}

structure LoginResponse {
    @required
    token: String,
}

structure RenewJwtTokenResponse {
    @required
    token: String,
}

@error("client")
@httpError(401) // Specific HTTP 401 Unauthorized
structure InvalidCredentials {
    @required
    message: String,
}

@error("client")
@httpError(403) // Forbidden
structure Forbidden {
    @required
    message: String,
}

@error("client")
@httpError(406) // Not Acceptable
structure RenewalTimeHasExpired {
    @required
    message: String,
}
