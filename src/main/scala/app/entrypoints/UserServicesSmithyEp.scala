package app.entrypoints

import cats.data.{Kleisli, NonEmptyVector}
import cats.effect.Async

import app.JobSpecs.CreateUserError.{BadPassword, InvalidParameters, UniquenessConstraintViolated}
import app.JobSpecs.JobKind.{CreateUserRequest, FetchUsersByLoginNamesRequest}
import app.JobSpecs.JobResult
import app.JobSpecs.JobResult.{CreateUserResult, FetchUsersByLoginNamesResult}
import app.ThalesUtils.GenUtils as U
import app.auth.Permissions
import app.auth.Permissions.{CompiledPermissionAlgebra, PermissionAlgebra}
import app.entrypoints.FetchUserByPermissionsUtils.FetchUserPermissionsAlg
import app.entrypoints.smithy.{CreateUserOutput, User, UserInDb, UserServices}
import app.model.AppModel.AuthenticatedUser

private final class UserServicesSmithyEp[F[_]: Async as async] private (
    jobHandler: JobHandler[F],
    epErrors: EntryPointErrors[F],
) extends UserServices[[A] =>> Kleisli[F, AuthenticatedUser, A]]:
  override def createUser(user: User): Kleisli[F, AuthenticatedUser, CreateUserOutput] =
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
                epErrors.badRequestF(U.paramsToStr(invalidParams))
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

  override def fetchUsersByLoginNames(
      loginNames: NonEmptyVector[String],
  ): Kleisli[F, AuthenticatedUser, Map[String, UserInDb]] =
    def successResult(user: Map[String, UserInDb]): F[Map[String, UserInDb]] =
      async.pure(user)
    end successResult

    def resultToResponse(jobResult: JobResult): F[Map[String, UserInDb]] =
      jobResult match {
        case FetchUsersByLoginNamesResult(res) => successResult(res)
        case _ => epErrors.internalServerErrorF("FetchUsersByLoginNames: Bad pattern match for result.")
      }
    end resultToResponse

    Kleisli { authUser =>
      jobHandler.jobHandlerWithAuth2(
        authUser,
        FetchUserPermissionsAlg,
        FetchUsersByLoginNamesRequest(loginNames),
        resultToResponse,
      )
    }
  end fetchUsersByLoginNames
end UserServicesSmithyEp

object UserServicesSmithyEp:
  def create[F[_]: Async](
      jobHandler: JobHandler[F],
      epErrors: EntryPointErrors[F],
  ): UserServices[[A] =>> Kleisli[F, AuthenticatedUser, A]] =
    UserServicesSmithyEp[F](jobHandler, epErrors)
  end create
end UserServicesSmithyEp
