package app.entrypoints

import cats.data.NonEmptyVector
import cats.effect.kernel.Async

import scala.util.control.NoStackTrace

import app.ThalesUtils.ExtensionMethodUtils.*
import app.entrypoints.smithy.{BadRequest, Conflict, Forbidden, Gone, NotFound, Unauthorized}

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

  private val CheckResetUserPasswordTokenNotFoundOrInvalid: NotFound =
    NotFound("Token not found or invalid.")

  private val CheckResetUserPasswordTokenGone: Gone =
    Gone("Token has expired or was never there.")

  private def badRequestSmithy(errMsg: String): BadRequest = BadRequest(errMsg)

  private def uniquenessConstraintViolatedSmithy(errMsg: String): Conflict = Conflict(errMsg)

  def authenticationError[T]: F[T] = async.raiseError(AuthenticationErrorSmithy)

  def userNotFound[T]: F[T] = async.raiseError(UserNotFoundSmithy)

  def userIsDisabled[T]: F[T] = async.raiseError(UserIsDisabledSmithy)

  def userMustResetPassword[T]: F[T] = async.raiseError(userMustResetPasswordSmithy)

  def userMustLoginAgainTokenExpired[T]: F[T] = async.raiseError(userMustLoginAgainTokenExpiredSmithy)

  def usersPasswordIsInvalid[T](errMsgs: NonEmptyVector[String]): F[T] =
    val errors = errMsgs.view.mkString("[\"", "\", \"", "\"]")
    async.raiseError(Conflict(s"The password supplied does not satisfy the password criteria. Errors: $errors."))

  def authorizationError[T]: F[T] = async.raiseError(AuthorizationErrorSmithy)

  def roleNotFound[T]: F[T] = async.raiseError(RoleNotFoundSmithy)

  def roleHasUsers[T]: F[T] = async.raiseError(RoleHasUsersSmithy)

  def duplicateRoleName[T]: F[T] = async.raiseError(DuplicateRoleSmithy)

  def badRequest[T](errMsg: String): F[T] = async.raiseError(badRequestSmithy(errMsg))

  def internalServerError[T](errMsg: String): F[T] = async.raiseError(new RuntimeException(errMsg) with NoStackTrace)

  def uniquenessConstraintViolated[T](errMsg: String): F[T] = async.raiseError(uniquenessConstraintViolatedSmithy(errMsg))

  def checkResetUserPasswordTokenNotFoundOrInvalid[T]: F[T] = async.raiseError(CheckResetUserPasswordTokenNotFoundOrInvalid)

  def checkResetUserPasswordTokenGone[T]: F[T] = async.raiseError(CheckResetUserPasswordTokenGone)
end EntryPointErrors

object EntryPointErrors:
  def create[F[_]: Async as async]: EntryPointErrors[F] =
    EntryPointErrors[F]()
  end create
end EntryPointErrors
