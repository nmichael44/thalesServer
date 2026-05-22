package app.entrypoints

import cats.data.NonEmptyVector
import cats.effect.kernel.Async

import scala.util.control.NoStackTrace

import app.ThalesUtils.ExtensionMethodUtils.*
import app.ThalesUtils.GenUtils as U
import app.entrypoints.smithy.*

final class EntryPointErrors[F[_]: Async as async] private ():
  private val invalidUserNameOrPasswordEx = InvalidUserNameOrPassword("Invalid loginName/password specified.")
  def invalidUserNameOrPassword[T]: F[T] =
    async.raiseError(invalidUserNameOrPasswordEx)
  end invalidUserNameOrPassword

  private val userIsUnAuthenticatedEx = UserIsUnAuthenticated("The current user does not have an valid token and cannot be authenticated.")
  def userIsUnAuthenticated[T]: F[T] =
    async.raiseError(userIsUnAuthenticatedEx)
  end userIsUnAuthenticated

  private val authorizationErrorEx = UserForbiddenFromCallingEntryPoint("The user is not authorized to perform this action.")
  def authorizationError[T]: F[T] =
    async.raiseError(authorizationErrorEx)
  end authorizationError

  def usersPasswordIsInvalid[T](reasons: NonEmptyVector[String]): F[T] =
    async.raiseError(PasswordIsInvalid(s"The given password was invalid: ${reasons.mkString(", ")}."))
  end usersPasswordIsInvalid

  private val invalidOrMissingResetPasswordTokenEx = InvalidOrMissingResetPasswordToken("The reset password token was invalid or has expired.")
  def invalidOrMissingResetPasswordToken[T]: F[T] =
    async.raiseError(invalidOrMissingResetPasswordTokenEx)
  end invalidOrMissingResetPasswordToken

  private val userMustLoginAgainTokenExpiredEx = RenewalTimeHasExpired("The renewal period for this token has expired. Please login again.")
  def userMustLoginAgainTokenExpired[T]: F[T] =
    async.raiseError(userMustLoginAgainTokenExpiredEx)
  end userMustLoginAgainTokenExpired

  def internalServerError[T](msg: String): F[T] =
    async.raiseError(new RuntimeException(msg) with NoStackTrace)
  end internalServerError

  private val userForbiddenFromCallingEntryPointEx = UserForbiddenFromCallingEntryPoint("The user is forbidden from performing this action.")
  def userForbiddenFromCallingEntryPoint[T]: F[T] =
    async.raiseError(userForbiddenFromCallingEntryPointEx)
  end userForbiddenFromCallingEntryPoint

  def invalidInputParameters[T](invalidParams: NonEmptyVector[(String, String)]): F[T] =
    async.raiseError(InvalidInputParameters(s"The parameters passed to entry point are invalid: ${U.paramsToStr(invalidParams)}."))
  end invalidInputParameters

  def duplicateParamEncountered[T](errMsg: String): F[T] =
    async.raiseError(DuplicateParamEncountered(s"Duplicate entry encountered. Exact error: '$errMsg'"))
  end duplicateParamEncountered

  private val userNotFoundEx = UserNotFound("The requested user was not found.")
  def userNotFound[T]: F[T] =
    async.raiseError(userNotFoundEx)
  end userNotFound

  private val userIsDisabledEx = UserIsDisabled("The requested user has been disabled.")
  def userIsDisabled[T]: F[T] =
    async.raiseError(userIsDisabledEx)
  end userIsDisabled

  private val userMustResetPasswordEx = UserMustResetPassword("The user must reset their password before proceeding.")
  def userMustResetPassword[T]: F[T] =
    async.raiseError(userMustResetPasswordEx)
  end userMustResetPassword

  def passwordIsInvalid[T](reasons: NonEmptyVector[String]): F[T] =
    async.raiseError(PasswordIsInvalid(s"The given password was invalid: ${reasons.mkString("[", ", ", "]")}."))
  end passwordIsInvalid

  private val resetPasswordTokenMissingEx = ResetPasswordTokenMissing("The given reset-password-token was expired or missing from our database.")
  def resetPasswordTokenMissing[T]: F[T] =
    async.raiseError(resetPasswordTokenMissingEx)
  end resetPasswordTokenMissing

  private val duplicateRoleNameEx = DuplicateRoleName("The given role name was already present in the database.")
  def duplicateRoleName[T]: F[T] =
    async.raiseError(duplicateRoleNameEx)
  end duplicateRoleName

  def duplicateRoleIds[T](duplicateRoleIds: NonEmptyVector[RoleId]): F[T] =
    async.raiseError(DuplicateRoleIds(RoleIdList(duplicateRoleIds)))
  end duplicateRoleIds

  def roleIdsNotFound[T](roleIds: NonEmptyVector[RoleId]): F[T] =
    async.raiseError(RoleIdsNotFound(RoleIdList(roleIds)))
  end roleIdsNotFound

  private val roleHasUsersEx = RoleHasUsers("The given role has been given to users and thus cannot be deleted.")
  def roleHasUsers[T]: F[T] =
    async.raiseError(roleHasUsersEx)
  end roleHasUsers

  private val renewalTimeHasExpiredEx = RenewalTimeHasExpired("The jwt token renewal time has expired.")
  def renewalTimeHasExpired[T]: F[T] =
    async.raiseError(renewalTimeHasExpiredEx)
  end renewalTimeHasExpired

  private val tooManyLoginAttemptsEx = TooManyLoginAttempts("Too many login attempts within a small time interval.")
  def tooManyLoginAttempts[T]: F[T] =
    async.raiseError(tooManyLoginAttemptsEx)
  end tooManyLoginAttempts
end EntryPointErrors

object EntryPointErrors:
  def create[F[_]: Async as async]: EntryPointErrors[F] =
    EntryPointErrors[F]()
  end create
end EntryPointErrors

final class QueueFullException(msg: String) extends RuntimeException(msg) with NoStackTrace
