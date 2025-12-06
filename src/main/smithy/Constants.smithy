$version: "2.0"

namespace app.entrypoints.smithy

use smithy.api#httpError

@mixin
@error("client")
@httpError(401)
structure UnauthorizedCode {}

@mixin
@error("client")
@httpError(403)
structure ForbiddenCode {}

@mixin
@error("client")
@httpError(423)
structure LockedCode {}

@mixin
@error("client")
@httpError(404)
structure NotFoundCode {}

@documentation("The user is not authorized to perform this action.")
@error("client")
structure Unauthorized with [UnauthorizedCode] {    
    @required
    message: String
}

@documentation("The user is forbidden from performing this action.")
@error("client")
structure Forbidden with [ForbiddenCode] {    
    @required
    message: String
}

@documentation("The requested resource was not found.")
structure NotFound with [NotFoundCode] {
    @required
    message: String
}
