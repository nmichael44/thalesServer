$version: "2.0"

namespace app.entrypoints.smithy

use smithy.api#httpError

@mixin
@error("client")
@httpError(400)
structure BadRequestCode {}

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

@mixin
@error("client")
@httpError(409)
structure ConflictCode {}

@mixin
@error("server")
@httpError(500)
structure InternalServerErrorCode {}

@documentation("The parameters passed to entry point are invalid.")
@error("client")
structure BadRequest with [BadRequestCode] {
    @required
    message: String
}

@documentation("The user is not authorized in the system.")
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

@documentation("The request results in a conflict.")
structure Conflict with [ConflictCode] {
    @required
    message: String
}

@documentation("Internal server error.")
structure InternalServerError with [InternalServerErrorCode] {
    @required
    message: String
}
