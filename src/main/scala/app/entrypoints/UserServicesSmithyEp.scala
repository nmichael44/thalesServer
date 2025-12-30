package app.entrypoints

import cats.data.{Kleisli, NonEmptyVector}
import cats.effect.Async

import app.JobSpecs.CheckResetUserPasswordTokenError.{ExpiredToken, InvalidToken}
import app.JobSpecs.CreateUserError.{BadPassword, InvalidParameters, UniquenessConstraintViolated}
import app.JobSpecs.JobKind.{CheckResetUserPasswordTokenRequest, CreateUserRequest, FetchAllUsersAssociatedWithRolesRequest, FetchUsersByLoginNamesRequest, FetchUsersByUserIdsRequest, ResetMyPasswordRequest}
import app.JobSpecs.JobResult
import app.JobSpecs.JobResult.{CheckResetUserPasswordTokenResult, CreateUserResult, FetchAllUsersAssociatedWithRolesResult, FetchUsersByLoginNamesResult, FetchUsersByUserIdsResult, ResetMyPasswordResult}
import app.JobSpecs.ResetMyPasswordError.{FailedToUpdateUserRow, NewPasswordIsInvalid, UserNotEnabled}
import app.ThalesUtils.GenUtils as U
import app.auth.Permissions
import app.auth.Permissions.{CompiledPermissionAlgebra, PermissionAlgebra}
import app.entrypoints.FetchUserByPermissionsUtils.FetchUserPermissionsAlg
import app.entrypoints.smithy.{CreateUserOutput, FetchAllUsersAssociatedWithRolesOutput, FetchUsersByLoginNamesOutput, FetchUsersByUserIdsOutput, LoginNameList, RoleIdList, User, UserIdList, UserInDb, UserServices}
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
              case BadPassword(errMsgs: NonEmptyVector[String]) => epErrors.usersPasswordIsInvalid(errMsgs)
              case InvalidParameters(invalidParams: NonEmptyVector[(String, String)]) =>
                epErrors.badRequest(U.paramsToStr(invalidParams))
            },
            successResult,
          )
        case _ => epErrors.internalServerError("CreateUser: Bad pattern match for result.")
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
      loginNames: LoginNameList,
  ): Kleisli[F, AuthenticatedUser, FetchUsersByLoginNamesOutput] =
    def successResult(users: Map[String, UserInDb]): F[FetchUsersByLoginNamesOutput] =
      async.pure(FetchUsersByLoginNamesOutput(users))
    end successResult

    def resultToResponse(jobResult: JobResult): F[FetchUsersByLoginNamesOutput] =
      jobResult match {
        case FetchUsersByLoginNamesResult(res) => successResult(res)
        case _ => epErrors.internalServerError("FetchUsersByLoginNames: Bad pattern match for result.")
      }
    end resultToResponse

    Kleisli { authUser =>
      jobHandler.jobHandlerWithAuth2(
        authUser,
        FetchUserPermissionsAlg,
        FetchUsersByLoginNamesRequest(loginNames.value),
        resultToResponse,
      )
    }
  end fetchUsersByLoginNames

  override def fetchUsersByUserIds(
      userIds: UserIdList,
  ): Kleisli[F, AuthenticatedUser, FetchUsersByUserIdsOutput] =
    def successResult(users: Map[Long, UserInDb]): F[FetchUsersByUserIdsOutput] =
      async.pure(FetchUsersByUserIdsOutput(users.map((userId, user) => (userId.toString, user))))
    end successResult

    def resultToResponse(jobResult: JobResult): F[FetchUsersByUserIdsOutput] =
      jobResult match {
        case FetchUsersByUserIdsResult(res) => successResult(res)
        case _ => epErrors.internalServerError("FetchUsersByUserIds: Bad pattern match for result.")
      }
    end resultToResponse

    Kleisli { authUser =>
      jobHandler.jobHandlerWithAuth2(
        authUser,
        FetchUserPermissionsAlg,
        FetchUsersByUserIdsRequest(userIds.value),
        resultToResponse,
      )
    }
  end fetchUsersByUserIds

  override def fetchAllUsersAssociatedWithRoles(
      roleIds: RoleIdList,
  ): Kleisli[F, AuthenticatedUser, FetchAllUsersAssociatedWithRolesOutput] =
    def resultToResponse(jobResult: JobResult): F[FetchAllUsersAssociatedWithRolesOutput] =
      jobResult match {
        case FetchAllUsersAssociatedWithRolesResult(res) =>
          async.pure(FetchAllUsersAssociatedWithRolesOutput(res.map((k, v) => (k.toString, v))))
        case _ =>
          epErrors.internalServerError("FetchAllUsersAssociatedWithRole: Bad pattern match for result.")
      }
    end resultToResponse

    Kleisli { authUser =>
      jobHandler.jobHandlerWithAuth2(
        authUser,
        FetchAllLiveSessionsPermissionsAlg,
        FetchAllUsersAssociatedWithRolesRequest(roleIds.value),
        resultToResponse,
      )
    }
  end fetchAllUsersAssociatedWithRoles

  private val FetchAllLiveSessionsPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra
      .And(
        NonEmptyVector.of(PermissionAlgebra.Has(Permissions.CanSeeAllRoles), PermissionAlgebra.Has(Permissions.CanSeeUsers)),
      )
      .compile
  end FetchAllLiveSessionsPermissionsAlg

  override def resetMyPassword(newPassword: String): Kleisli[F, AuthenticatedUser, Unit] =
    def resultToResponse(jobResult: JobResult): F[Unit] =
      jobResult match {
        case ResetMyPasswordResult(res) =>
          res.fold(
            {
              case UserNotEnabled => epErrors.userIsDisabled
              case NewPasswordIsInvalid(errMsgs) => epErrors.usersPasswordIsInvalid(errMsgs)
              case FailedToUpdateUserRow(errStr) => epErrors.internalServerError(errStr)
            },
            async.pure,
          )
        case _ => epErrors.internalServerError("FetchAllUsersAssociatedWithRole: Bad pattern match for result.")
      }
    end resultToResponse

    Kleisli { authUser =>
      jobHandler.jobHandlerWithAuth2(
        authUser,
        ResetMyPasswordPermissionsAlg,
        ResetMyPasswordRequest(authUser, newPassword),
        resultToResponse,
      )
    }
  end resetMyPassword

  private val ResetMyPasswordPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permissions.CanResetMyPassword).compile
  end ResetMyPasswordPermissionsAlg

  override def checkResetUserPasswordToken(token: String): Kleisli[F, AuthenticatedUser, Unit] =
    def resultToResponse(jobResult: JobResult): F[Unit] =
      jobResult match {
        case CheckResetUserPasswordTokenResult(res) =>
          res.fold(
            {
              case InvalidToken => epErrors.checkResetUserPasswordTokenNotFoundOrInvalid
              case ExpiredToken => epErrors.checkResetUserPasswordTokenGone
            },
            async.pure,
          )
        case _ => epErrors.internalServerError("CheckResetUserPasswordToken: Bad pattern match for result.")
      }
    end resultToResponse

    Kleisli { authUser =>
      jobHandler.jobHandlerWithAuth2(
        authUser,
        CheckResetUserPasswordTokenPermissionsAlg,
        CheckResetUserPasswordTokenRequest(token),
        resultToResponse,
      )
    }
  end checkResetUserPasswordToken

  private val CheckResetUserPasswordTokenPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permissions.CanCheckResetUserPasswordToken).compile
  end CheckResetUserPasswordTokenPermissionsAlg
end UserServicesSmithyEp

object UserServicesSmithyEp:
  def create[F[_]: Async](
      jobHandler: JobHandler[F],
      epErrors: EntryPointErrors[F],
  ): UserServices[[A] =>> Kleisli[F, AuthenticatedUser, A]] =
    UserServicesSmithyEp[F](jobHandler, epErrors)
  end create
end UserServicesSmithyEp
