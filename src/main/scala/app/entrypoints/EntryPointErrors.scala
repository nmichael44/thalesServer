package app.entrypoints

import cats.effect.kernel.Async

import app.entrypoints.smithy.{BadRequest, Conflict, Forbidden, InternalServerError, NotFound, Unauthorized}
import org.http4s.dsl.impl.Responses.BadRequestOps

final class EntryPointErrors[F[_]: Async as async] private ():
  private val AuthenticationErrorSmithy: Unauthorized =
    Unauthorized("The current user does not have an valid token and cannot be authenticated.")

  private val AuthorizationErrorSmithy: Forbidden =
    Forbidden("The current user does not have the authorization to perform this action.")

  private val RoleNotFoundSmithy: NotFound =
    NotFound("No role with given roleId was found in the system.")

  private val RoleHasUsersSmithy: Conflict =
    new Conflict("The role cannot be deleted as it is associated with existing users.")

  private val DuplicateRoleSmithy: Conflict =
    new Conflict("The role was already present in the database.")

  private def internalServerErrorSmithy(errMsg: String): InternalServerError = InternalServerError(errMsg)

  private def badRequestSmithy(errMsg: String): BadRequest = BadRequest(errMsg)

  def authenticationError[T]: F[T] = async.raiseError(AuthenticationErrorSmithy)

  def authorizationError[T]: F[T] = async.raiseError(AuthorizationErrorSmithy)

  def roleNotFoundF[T]: F[T] = async.raiseError(RoleNotFoundSmithy)

  def roleHasUsersF[T]: F[T] = async.raiseError(RoleHasUsersSmithy)

  def duplicateRoleNameF[T]: F[T] = async.raiseError(DuplicateRoleSmithy)

  def badRequestF[T](errMsg: String): F[T] = async.raiseError(badRequestSmithy(errMsg))

  def internalServerErrorF[T](errMsg: String): F[T] = async.raiseError(internalServerErrorSmithy(errMsg))
end EntryPointErrors

object EntryPointErrors:
  def create[F[_]: Async as async]: EntryPointErrors[F] =
    EntryPointErrors[F]()
  end create
end EntryPointErrors
