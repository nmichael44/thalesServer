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
structure UnauthenticatedCode {}

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
@httpError(429)
structure TooManyRequestsCode {}

@mixin
@error("client")
@httpError(404)
structure NotFoundCode {}

@mixin
@error("client")
@httpError(409)
structure ConflictCode {}

@mixin
@error("client")
@httpError(410)
structure GoneCode {}

@documentation("The user is not authorized in the system.")
@error("client")
structure InvalidUserNameOrPassword with [UnauthenticatedCode] {
    @required
    message: String
}

@documentation("The user is not authorized in the system.")
@error("client")
structure UserIsUnAuthenticated with [UnauthenticatedCode] {
    @required
    message: String
}

@documentation("The user is forbidden from performing this action.")
@error("client")
structure UserForbiddenFromCallingEntryPoint with [ForbiddenCode] {
    @required
    message: String
}

@documentation("The parameters passed to entry point are invalid.")
@error("client")
structure InvalidInputParameters with [BadRequestCode] {
    @required
    message: String
}

@documentation("The loginName or Email passed to entry point are was already in the database.")
@error("client")
structure DuplicateParamEncountered with [ConflictCode] {
    @required
    message: String
}

@documentation("The requested user was not found.")
@error("client")
structure UserNotFound with [NotFoundCode] {
    @required
    message: String
}

@documentation("The requested user has been disabled.")
@error("client")
structure UserIsDisabled with [LockedCode] {
    @required
    message: String
}

@documentation("The user must reset their password before proceeding.")
@error("client")
structure UserMustResetPassword with [LockedCode] {
    @required
    message: String
}

@documentation("The given password was invalid.")
@error("client")
structure PasswordIsInvalid with [BadRequestCode] {
    @required
    message: String
}

@documentation("The given reset-password-token was expired or missing from our database.")
@error("client")
structure ResetPasswordTokenMissing with [NotFoundCode] {
    @required
    message: String
}

@documentation("The given role name was already present in the database.")
@error("client")
structure DuplicateRoleName with [ConflictCode] {
    @required
    message: String
}

@documentation("The given roleId was not found in the database.")
@error("client")
structure RoleNotFound with [NotFoundCode] {
    @required
    message: String
}

@documentation("The given role has been given to users and thus cannot be deleted.")
@error("client")
structure RoleHasUsers with [ConflictCode] {
    @required
    message: String
}

@documentation("The jwt token renewal time has expired.")
@error("client")
structure RenewalTimeHasExpired with [ForbiddenCode] {
    @required
    message: String
}

@documentation("Too many login attempts within a small time interval.")
@error("client")
structure TooManyLoginAttempts with [TooManyRequestsCode] {
    @required
    message: String
}

@documentation("The the reset password token was invalid.")
@error("client")
structure InvalidOrMissingResetPasswordToken with [GoneCode] {
    @required
    message: String
}
