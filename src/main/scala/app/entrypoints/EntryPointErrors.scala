package app.entrypoints

import cats.effect.kernel.Async

import app.entrypoints.smithy.{Conflict, Forbidden, NotFound, Unauthorized}

final class EntryPointErrors[F[_]: Async as async] private ():
  private val authenticationErrorSmithy: Unauthorized =
    Unauthorized("The current user does not have an valid token and cannot be authenticated.")

  private val authorizationErrorSmithy: Forbidden =
    Forbidden("The current user does not have the authorization to perform this action.")

  private val roleNotFoundSmithy: NotFound =
    NotFound("No role with given roleId was found in the system.")

  private val roleHasUsersSmithy: Conflict =
    Conflict("The role cannot be deleted as it is associated with existing users.")

  def authenticationError[T]: F[T] = async.raiseError(authenticationErrorSmithy)

  def authorizationError[T]: F[T] = async.raiseError(authorizationErrorSmithy)

  def roleNotFoundF[T]: F[T] = async.raiseError(roleNotFoundSmithy)

  def roleHasUsersF[T]: F[T] = async.raiseError(roleHasUsersSmithy)
end EntryPointErrors

object EntryPointErrors:
  def create[F[_]: Async as async]: EntryPointErrors[F] =
    EntryPointErrors[F]()
  end create
end EntryPointErrors
