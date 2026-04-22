package app.entrypoints

import cats.data.{Kleisli, NonEmptyVector}
import cats.effect.Async

import app.JobSpecs.CheckResetUserPasswordTokenError.ExpiredToken
import app.JobSpecs.CreateUserError.{BadPassword, InvalidParameters, UniquenessConstraintViolated}
import app.JobSpecs.JobKind.{CheckResetUserPasswordTokenRequest, CreateUserRequest, FetchAllLiveSessionsRequest, FetchAllUsersAssociatedWithRolesRequest, FetchUserRoleIdsRequest, FetchUsersByLoginNamesRequest, FetchUsersByUserIdsRequest, ResetMyPasswordRequest, SetMustResetUserPasswordRequest, UpdateUserRolesByIdRequest}
import app.JobSpecs.JobResult
import app.JobSpecs.JobResult.{CheckResetUserPasswordTokenResult, CreateUserResult, FetchAllLiveSessionsResult, FetchAllUsersAssociatedWithRolesResult, FetchUsersByLoginNamesResult, FetchUsersByUserIdsResult, ResetMyPasswordResult, ResetUserPasswordResult, SetMustResetUserPasswordResult}
import app.JobSpecs.ResetMyPasswordError.{FailedToUpdateUserRow, NewPasswordIsInvalid, UserNotEnabled}
import app.JobSpecs.SetMustResetUserPasswordError
import app.JobSpecs.UpdateUserRolesByIdError
import app.ThalesUtils.ExtensionMethodUtils.mkString
import app.ThalesUtils.GenUtils as U
import app.auth.Permissions
import app.auth.Permissions.{CompiledPermissionAlgebra, PermissionAlgebra}
import app.entrypoints.EntryPointUtils as EPU
import app.entrypoints.smithy.{CreateUserOutput, FetchAllLiveSessionsOutput, FetchAllUsersAssociatedWithRolesOutput, FetchUserRoleIdsOutput, FetchUsersByLoginNamesOutput, FetchUsersByUserIdsOutput, LoginNameList, ResetPasswordToken, RoleId, RoleIdList, User, UserId, UserIdList, UserInDb, UserPassword, UserServices, UserSession}
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
      jobResult match
        case CreateUserResult(res) =>
          res.fold(
            {
              case UniquenessConstraintViolated(errMsg: String) => epErrors.duplicateParamEncountered(errMsg)
              case BadPassword(reasons: NonEmptyVector[String]) => epErrors.usersPasswordIsInvalid(reasons)
              case InvalidParameters(invalidParams: NonEmptyVector[(String, String)]) => epErrors.invalidInputParameters(invalidParams)
            },
            successResult,
          )
        case _ => EPU.invalidResultType(epErrors, "CreateUser")
    end resultToResponse

    Kleisli: authUser =>
      jobHandler.jobHandlerWithAuth(
        authUser,
        CreateUserPermissionsAlg,
        CreateUserRequest(user, authUser.userId),
        resultToResponse,
      )
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
      jobResult match
        case FetchUsersByLoginNamesResult(res) => successResult(res.map((k, v) => (k.toString, v)))
        case _ => EPU.invalidResultType(epErrors, "FetchUsersByLoginNames")
    end resultToResponse

    Kleisli: authUser =>
      jobHandler.jobHandlerWithAuth(
        authUser,
        FetchUserPermissionsAlg,
        FetchUsersByLoginNamesRequest(loginNames.value),
        resultToResponse,
      )
  end fetchUsersByLoginNames

  override def fetchUsersByUserIds(
      userIds: UserIdList,
  ): Kleisli[F, AuthenticatedUser, FetchUsersByUserIdsOutput] =
    def successResult(users: Map[UserId, UserInDb]): F[FetchUsersByUserIdsOutput] =
      async.pure(FetchUsersByUserIdsOutput(users.map(U.mapFirst(_.value.toString))))
    end successResult

    def resultToResponse(jobResult: JobResult): F[FetchUsersByUserIdsOutput] =
      jobResult match
        case FetchUsersByUserIdsResult(res) => successResult(res)
        case _ => EPU.invalidResultType(epErrors, "FetchUsersByUserIds")
    end resultToResponse

    Kleisli: authUser =>
      jobHandler.jobHandlerWithAuth(
        authUser,
        FetchUserPermissionsAlg,
        FetchUsersByUserIdsRequest(userIds.value),
        resultToResponse,
      )
  end fetchUsersByUserIds

  private val FetchUserPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permissions.CanSeeUsers).compile
  end FetchUserPermissionsAlg

  override def fetchAllUsersAssociatedWithRoles(
      roleIds: RoleIdList,
  ): Kleisli[F, AuthenticatedUser, FetchAllUsersAssociatedWithRolesOutput] =
    def resultToResponse(jobResult: JobResult): F[FetchAllUsersAssociatedWithRolesOutput] =
      jobResult match
        case FetchAllUsersAssociatedWithRolesResult(res) =>
          async.pure(FetchAllUsersAssociatedWithRolesOutput(res.map(U.mapFirst(_.toString))))
        case _ => EPU.invalidResultType(epErrors, "FetchAllUsersAssociatedWithRoles")
    end resultToResponse

    Kleisli: authUser =>
      jobHandler.jobHandlerWithAuth(
        authUser,
        FetchAllUsersAssociatedWithRolesPermissionsAlg,
        FetchAllUsersAssociatedWithRolesRequest(roleIds.value),
        resultToResponse,
      )
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
      jobResult match
        case ResetMyPasswordResult(res) =>
          res.fold(
            {
              case UserNotEnabled => epErrors.userIsDisabled
              case NewPasswordIsInvalid(errMsgs) => epErrors.usersPasswordIsInvalid(errMsgs)
              case FailedToUpdateUserRow(errStr) => epErrors.internalServerError(errStr)
            },
            async.pure,
          )
        case _ => EPU.invalidResultType(epErrors, "ResetMyPassword")
    end resultToResponse

    Kleisli: authUser =>
      jobHandler.jobHandlerWithAuth(
        authUser,
        ResetMyPasswordPermissionsAlg,
        ResetMyPasswordRequest(authUser, newPassword),
        resultToResponse,
      )
  end resetMyPassword

  private val ResetMyPasswordPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permissions.CanResetMyPassword).compile
  end ResetMyPasswordPermissionsAlg

  override def checkResetUserPasswordToken(token: ResetPasswordToken): Kleisli[F, AuthenticatedUser, Unit] =
    def resultToResponse(jobResult: JobResult): F[Unit] =
      jobResult match
        case CheckResetUserPasswordTokenResult(res) =>
          res.fold({ case ExpiredToken => epErrors.invalidOrMissingResetPasswordToken }, async.pure)
        case _ => EPU.invalidResultType(epErrors, "CheckResetUserPasswordToken")
    end resultToResponse

    Kleisli: authUser =>
      jobHandler.jobHandlerWithAuth(
        authUser,
        CheckResetUserPasswordTokenPermissionsAlg,
        CheckResetUserPasswordTokenRequest(token),
        resultToResponse,
      )
  end checkResetUserPasswordToken

  private val CheckResetUserPasswordTokenPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permissions.CanCheckResetUserPasswordToken).compile
  end CheckResetUserPasswordTokenPermissionsAlg

  override def fetchAllLiveSessions(): Kleisli[F, AuthenticatedUser, FetchAllLiveSessionsOutput] =
    fetchAllLiveSessionsProgram
  end fetchAllLiveSessions

  private val fetchAllLiveSessionsProgram: Kleisli[F, AuthenticatedUser, FetchAllLiveSessionsOutput] =
    def resultToResponse(jobResult: JobResult): F[FetchAllLiveSessionsOutput] =
      jobResult match
        case FetchAllLiveSessionsResult(sessionsVec) =>
          async.pure(
            FetchAllLiveSessionsOutput(
              sessionsVec.view
                .map(U.mapSecond(JavaInstant.apply))
                .map(UserSession.apply)
                .toVector,
            ),
          )
        case _ => EPU.invalidResultType(epErrors, "FetchAllLiveSessions")
    end resultToResponse

    Kleisli: authUser =>
      jobHandler.jobHandlerWithAuth(
        authUser,
        FetchAllLiveSessionsPermissionsAlg,
        FetchAllLiveSessionsRequest,
        resultToResponse,
      )
  end fetchAllLiveSessionsProgram

  private val FetchAllLiveSessionsPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permissions.CanSeeAllLiveSessions).compile
  end FetchAllLiveSessionsPermissionsAlg

  override def setMustResetUserPassword(userId: UserId, mustResetPassword: Boolean): Kleisli[F, AuthenticatedUser, Unit] =
    def resultToResponse(jobResult: JobResult): F[Unit] =
      jobResult match
        case SetMustResetUserPasswordResult(res) =>
          res.fold(
            { case SetMustResetUserPasswordError.UserNotFound => epErrors.userNotFound },
            async.pure,
          )
        case _ => EPU.invalidResultType(epErrors, "SetMustResetUserPassword")
    end resultToResponse

    Kleisli: authUser =>
      jobHandler.jobHandlerWithAuth(
        authUser,
        SetMustResetUserPasswordPermissionsAlg,
        SetMustResetUserPasswordRequest(userId, mustResetPassword),
        resultToResponse,
      )
  end setMustResetUserPassword

  private val SetMustResetUserPasswordPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permissions.CanSetMustResetUserPassword).compile
  end SetMustResetUserPasswordPermissionsAlg

  override def updateUserRolesById(userId: UserId, roleIds: Vector[RoleId]): Kleisli[F, AuthenticatedUser, Unit] =
    def resultToResponse(jobResult: JobResult): F[Unit] =
      jobResult match
        case JobResult.UpdateUserRolesByIdResult(res) =>
          res.fold(
            {
              case UpdateUserRolesByIdError.NoSuchUserId => epErrors.userNotFound
              case UpdateUserRolesByIdError.NoSuchRoleIds(roleIds) => epErrors.roleIdsNotFound(roleIds)
              case UpdateUserRolesByIdError.DuplicateRoleIds(duplicateRoleIds) => epErrors.duplicateRoleIds(duplicateRoleIds)
            },
            async.pure,
          )
        case _ => EPU.invalidResultType(epErrors, "UpdateUserRolesById")
    end resultToResponse

    Kleisli: authUser =>
      NonEmptyVector.fromVector(roleIds) match
        case None => epErrors.invalidInputParameters(NonEmptyVector.one(("roleIds", "Role list cannot be empty")))
        case Some(nevRoleIds) =>
          jobHandler.jobHandlerWithAuth(
            authUser,
            UpdateUserRolesByIdPermissionsAlg,
            UpdateUserRolesByIdRequest(userId, nevRoleIds),
            resultToResponse,
          )
  end updateUserRolesById

  private val UpdateUserRolesByIdPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra
      .And(
        NonEmptyVector.of(
          PermissionAlgebra.Has(Permissions.CanSeeUsers),
          PermissionAlgebra.Has(Permissions.CanSeeAllRoles),
          PermissionAlgebra.Has(Permissions.CanUpdateUserRoles),
        ),
      )
      .compile
  end UpdateUserRolesByIdPermissionsAlg

  override def fetchUserRoleIds(userIds: UserIdList): Kleisli[F, AuthenticatedUser, FetchUserRoleIdsOutput] =
    def resultToResponse(jobResult: JobResult): F[FetchUserRoleIdsOutput] =
      jobResult match
        case JobResult.FetchUserRoleIdsResult(res) =>
          async.pure(FetchUserRoleIdsOutput(res.map(U.mapFirst(_.value.toString))))
        case _ => EPU.invalidResultType(epErrors, "FetchUserRoleIds")
    end resultToResponse

    Kleisli: authUser =>
      jobHandler.jobHandlerWithAuth(
        authUser,
        FetchUserRoleIdsPermissionsAlg,
        FetchUserRoleIdsRequest(userIds.value),
        resultToResponse,
      )
  end fetchUserRoleIds

  private val FetchUserRoleIdsPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra
      .And(
        NonEmptyVector.of(
          PermissionAlgebra.Has(Permissions.CanSeeUsers),
          PermissionAlgebra.Has(Permissions.CanSeeUserRoles),
        ),
      )
      .compile
  end FetchUserRoleIdsPermissionsAlg
end UserServicesSmithyEp

object UserServicesSmithyEp:
  def create[F[_]: Async](
      jobHandler: JobHandler[F],
      epErrors: EntryPointErrors[F],
  ): UserServices[[A] =>> Kleisli[F, AuthenticatedUser, A]] =
    UserServicesSmithyEp[F](jobHandler, epErrors)
  end create
end UserServicesSmithyEp
