package app.entrypoints

import cats.data.{Kleisli, NonEmptyVector}
import cats.effect.Async

import app.JobSpecs.{FetchRoleByError, JobResult}
import app.JobSpecs.CreateUserError.*
import app.JobSpecs.JobKind.CreateUserRequest
import app.JobSpecs.JobResult.CreateUserResult
import app.ThalesUtils.ExtensionMethodUtils.*
import app.auth.Permissions
import app.auth.Permissions.{CompiledPermissionAlgebra, PermissionAlgebra}
import app.entrypoints.smithy.{User, UserServices, CreateUserOutput}
import app.model.AppModel.AuthenticatedUser

private final class UserServicesSmithyEp[F[_]: Async as async] private (
    jobHandler: JobHandler[F],
    epErrors: EntryPointErrors[F],
) extends UserServices[[A] =>> Kleisli[F, AuthenticatedUser, A]]:
  override def createUser(user: User): Kleisli[F, AuthenticatedUser, CreateUserOutput] =
    def paramsToStr(params: NonEmptyVector[(String, String)]): String =
      params.view.map((param, error) => s"($param: \"$error\")").mkString("[", ", ", "]")
    end paramsToStr

    def successResult(userId: Long): F[CreateUserOutput] =
      async.pure(CreateUserOutput(userId))
    end successResult

    def resultToResponse(jobResult: JobResult): F[CreateUserOutput] =
      jobResult match {
        case CreateUserResult(res) =>
          res.fold(
            {
              case UniquenessConstraintViolated(errMsg: String) => epErrors.uniquenessConstraintViolated(errMsg)
              case BadPassword(errorList: NonEmptyVector[String]) => epErrors.usersPasswordIsInvalid
              case InvalidParameters(invalidParams: NonEmptyVector[(String, String)]) =>
                epErrors.badRequestF(paramsToStr(invalidParams))
            },
            successResult,
          )
        case _ => epErrors.internalServerErrorF("CreateUser: Bad pattern match for result.")
      }
    end resultToResponse

    Kleisli { authUser =>
      jobHandler.jobHandlerWithAuth2(
        authUser,
        CreateUserPermissionsAlg,
        CreateUserRequest(user, authUser.userId),
        resultToResponse,
      )
    }
  end createUser

  private val CreateUserPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permissions.CanCreateUsers).compile
  end CreateUserPermissionsAlg
end UserServicesSmithyEp

object UserServicesSmithyEp:
  def create[F[_]: Async](
      jobHandler: JobHandler[F],
      epErrors: EntryPointErrors[F],
  ): UserServices[[A] =>> Kleisli[F, AuthenticatedUser, A]] =
    UserServicesSmithyEp[F](jobHandler, epErrors)
  end create
end UserServicesSmithyEp
