package app.entrypoints

import cats.effect.kernel.Async

import app.entrypoints.smithy.{BadRequest, Conflict, Forbidden, NotFound, Unauthorized}
import org.http4s.dsl.impl.Responses.BadRequestOps

final class EntryPointErrors[F[_]: Async as async] private ():
  private val authenticationErrorSmithy: Unauthorized =
    Unauthorized("The current user does not have an valid token and cannot be authenticated.")

  private val authorizationErrorSmithy: Forbidden =
    Forbidden("The current user does not have the authorization to perform this action.")

  private val roleNotFoundSmithy: NotFound =
    NotFound("No role with given roleId was found in the system.")

  private val roleHasUsersSmithy: Conflict =
    new Conflict("The role cannot be deleted as it is associated with existing users.")

  private val duplicateRoleSmithy: Conflict =
    new Conflict("The role was already present in the database.")

  private def badRequestSmithy(errMsg: String): BadRequest = BadRequest(errMsg)

  def authenticationError[T]: F[T] = async.raiseError(authenticationErrorSmithy)

  def authorizationError[T]: F[T] = async.raiseError(authorizationErrorSmithy)

  def roleNotFoundF[T]: F[T] = async.raiseError(roleNotFoundSmithy)

  def roleHasUsersF[T]: F[T] = async.raiseError(roleHasUsersSmithy)

  def duplicateRoleNameF[T]: F[T] = async.raiseError(duplicateRoleSmithy)

  def badRequestF[T](errMsg: String): F[T] = async.raiseError(badRequestSmithy(errMsg))
end EntryPointErrors

object EntryPointErrors:
  def create[F[_]: Async as async]: EntryPointErrors[F] =
    EntryPointErrors[F]()
  end create
end EntryPointErrors
