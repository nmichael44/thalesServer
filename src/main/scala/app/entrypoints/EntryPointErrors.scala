package app.entrypoints

import cats.effect.kernel.Async

import app.entrypoints.smithy.{BadRequest, Conflict, Forbidden, InternalServerError, NotFound, Unauthorized}
import org.http4s.dsl.impl.Responses.BadRequestOps

final class EntryPointErrors[F[_]: Async as async] private ():
  private val AuthenticationErrorSmithy: Unauthorized =
    Unauthorized("The current user does not have an valid token and cannot be authenticated.")

  private val AuthorizationErrorSmithy: Forbidden =
    Forbidden("The current user does not have the authorization to perform this action.")

  private val UserNotFoundSmithy: NotFound =
    NotFound("No user with given userId was found in the system.")

  private val UserIsDisabledSmithy: Forbidden =
    Forbidden("The given user is disabled.")

  private val userMustResetPasswordSmithy: Forbidden =
    Forbidden("The given user must reset his password.")

  private val userMustLoginAgainTokenExpiredSmithy: Forbidden =
    Forbidden("The current user must login again as the renewal time for the token has expired.")

  private val usersPasswordIsInvalidSmithy: Conflict =
    Conflict("The password supplied does not satisfy the password criteria.")

  private val RoleNotFoundSmithy: NotFound =
    NotFound("No role with given roleId was found in the system.")

  private val RoleHasUsersSmithy: Conflict =
    Conflict("The role cannot be deleted as it is associated with existing users.")

  private val DuplicateRoleSmithy: Conflict =
    Conflict("The role was already present in the database.")

  private def internalServerErrorSmithy(errMsg: String): InternalServerError = InternalServerError(errMsg)

  private def badRequestSmithy(errMsg: String): BadRequest = BadRequest(errMsg)

  private def uniquenessConstraintViolatedSmithy(errMsg: String): Conflict = Conflict(errMsg)

  def authenticationError[T]: F[T] = async.raiseError(AuthenticationErrorSmithy)

  def userNotFound[T]: F[T] = async.raiseError(UserNotFoundSmithy)

  def userIsDisabled[T]: F[T] = async.raiseError(UserIsDisabledSmithy)

  def userMustResetPassword[T]: F[T] = async.raiseError(userMustResetPasswordSmithy)

  def userMustLoginAgainTokenExpired[T]: F[T] = async.raiseError(userMustLoginAgainTokenExpiredSmithy)

  def usersPasswordIsInvalid[T]: F[T] = async.raiseError(usersPasswordIsInvalidSmithy)

  def authorizationError[T]: F[T] = async.raiseError(AuthorizationErrorSmithy)

  def roleNotFoundF[T]: F[T] = async.raiseError(RoleNotFoundSmithy)

  def roleHasUsersF[T]: F[T] = async.raiseError(RoleHasUsersSmithy)

  def duplicateRoleNameF[T]: F[T] = async.raiseError(DuplicateRoleSmithy)

  def badRequestF[T](errMsg: String): F[T] = async.raiseError(badRequestSmithy(errMsg))

  def internalServerErrorF[T](errMsg: String): F[T] = async.raiseError(internalServerErrorSmithy(errMsg))

  def uniquenessConstraintViolated[T](errMsg: String): F[T] = async.raiseError(uniquenessConstraintViolatedSmithy(errMsg))
end EntryPointErrors

object EntryPointErrors:
  def create[F[_]: Async as async]: EntryPointErrors[F] =
    EntryPointErrors[F]()
  end create
end EntryPointErrors
