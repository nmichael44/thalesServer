package app.entrypoints

import cats.data.{Kleisli, NonEmptyVector}
import cats.effect.Async

import app.JobSpecs.{JobResult, ResetUserPasswordError}
import app.JobSpecs.CheckResetUserPasswordTokenError.ExpiredToken
import app.JobSpecs.CreateUserError.{BadPassword, InvalidParameters, UniquenessConstraintViolated}
import app.JobSpecs.JobKind.{CheckResetUserPasswordTokenRequest, CreateUserRequest, FetchAllLiveSessionsRequest, FetchAllUsersAssociatedWithRolesRequest, FetchUsersByLoginNamesRequest, FetchUsersByUserIdsRequest, ResetMyPasswordRequest}
import app.JobSpecs.JobResult.{CheckResetUserPasswordTokenResult, CreateUserResult, FetchAllLiveSessionsResult, FetchAllUsersAssociatedWithRolesResult, FetchUsersByLoginNamesResult, FetchUsersByUserIdsResult, ResetMyPasswordResult, ResetUserPasswordResult}
import app.JobSpecs.ResetMyPasswordError.{FailedToUpdateUserRow, NewPasswordIsInvalid, UserNotEnabled}
import app.ThalesUtils.GenUtils as U
import app.auth.Permissions
import app.auth.Permissions.{CompiledPermissionAlgebra, PermissionAlgebra}
import app.entrypoints.FetchUserByPermissionsUtils.FetchUserPermissionsAlg
import app.entrypoints.smithy.{CreateUserOutput, FetchAllLiveSessionsOutput, FetchAllUsersAssociatedWithRolesOutput, FetchUsersByLoginNamesOutput, FetchUsersByUserIdsOutput, LoginNameList, ResetPasswordToken, RoleIdList, User, UserId, UserIdList, UserInDb, UserPassword, UserServices, UserSession}
import app.model.AppModel.AuthenticatedUser
import app.model.JavaInstant

private final class UserServicesSmithyEp[F[_]: Async as async] private (
    jobHandler: JobHandler[F],
    epErrors: EntryPointErrors[F],
) extends UserServices[[A] =>> Kleisli[F, AuthenticatedUser, A]]:
  override def createUser(user: User): Kleisli[F, AuthenticatedUser, CreateUserOutput] =
    def successResult(userId: UserId): F[CreateUserOutput] =
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
      jobHandler.jobHandlerWithAuth(
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
        case FetchUsersByLoginNamesResult(res) => successResult(res.map((k, v) => (k.toString, v)))
        case _ => epErrors.internalServerError("FetchUsersByLoginNames: Bad pattern match for result.")
      }
    end resultToResponse

    Kleisli { authUser =>
      jobHandler.jobHandlerWithAuth(
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
    def successResult(users: Map[UserId, UserInDb]): F[FetchUsersByUserIdsOutput] =
      async.pure(FetchUsersByUserIdsOutput(users.map((userId, user) => (userId.value.toString, user))))
    end successResult

    def resultToResponse(jobResult: JobResult): F[FetchUsersByUserIdsOutput] =
      jobResult match {
        case FetchUsersByUserIdsResult(res) => successResult(res)
        case _ => epErrors.internalServerError("FetchUsersByUserIds: Bad pattern match for result.")
      }
    end resultToResponse

    Kleisli { authUser =>
      jobHandler.jobHandlerWithAuth(
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
      jobHandler.jobHandlerWithAuth(
        authUser,
        FetchAllUsersAssociatedWithRolesPermissionsAlg,
        FetchAllUsersAssociatedWithRolesRequest(roleIds.value),
        resultToResponse,
      )
    }
  end fetchAllUsersAssociatedWithRoles

  private val FetchAllUsersAssociatedWithRolesPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra
      .And(
        NonEmptyVector.of(PermissionAlgebra.Has(Permissions.CanSeeAllRoles), PermissionAlgebra.Has(Permissions.CanSeeUsers)),
      )
      .compile
  end FetchAllUsersAssociatedWithRolesPermissionsAlg

  override def resetMyPassword(newPassword: UserPassword): Kleisli[F, AuthenticatedUser, Unit] =
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
      jobHandler.jobHandlerWithAuth(
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

  override def checkResetUserPasswordToken(token: ResetPasswordToken): Kleisli[F, AuthenticatedUser, Unit] =
    def resultToResponse(jobResult: JobResult): F[Unit] =
      jobResult match {
        case CheckResetUserPasswordTokenResult(res) =>
          res.fold({ case ExpiredToken => epErrors.checkResetUserPasswordTokenGone }, async.pure)
        case _ => epErrors.internalServerError("CheckResetUserPasswordToken: Bad pattern match for result.")
      }
    end resultToResponse

    Kleisli { authUser =>
      jobHandler.jobHandlerWithAuth(
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

  override def fetchAllLiveSessions(): Kleisli[F, AuthenticatedUser, FetchAllLiveSessionsOutput] =
    fetchAllLiveSessionsProgram
  end fetchAllLiveSessions

  private val fetchAllLiveSessionsProgram: Kleisli[F, AuthenticatedUser, FetchAllLiveSessionsOutput] =
    def resultToResponse(jobResult: JobResult): F[FetchAllLiveSessionsOutput] =
      jobResult match {
        case FetchAllLiveSessionsResult(sessionsVec) =>
          async.pure(FetchAllLiveSessionsOutput(sessionsVec.map((user, ins) => UserSession(user, JavaInstant(ins)))))
        case _ => epErrors.internalServerError("CheckResetUserPasswordToken: Bad pattern match for result.")
      }
    end resultToResponse

    Kleisli { authUser =>
      jobHandler.jobHandlerWithAuth(
        authUser,
        FetchAllLiveSessionsPermissionsAlg,
        FetchAllLiveSessionsRequest,
        resultToResponse,
      )
    }
  end fetchAllLiveSessionsProgram

  private val FetchAllLiveSessionsPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permissions.CanFetchAllLiveSessions).compile
  end FetchAllLiveSessionsPermissionsAlg
end UserServicesSmithyEp

object UserServicesSmithyEp:
  def create[F[_]: Async](
      jobHandler: JobHandler[F],
      epErrors: EntryPointErrors[F],
  ): UserServices[[A] =>> Kleisli[F, AuthenticatedUser, A]] =
    UserServicesSmithyEp[F](jobHandler, epErrors)
  end create
end UserServicesSmithyEp
