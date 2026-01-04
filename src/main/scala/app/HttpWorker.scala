package app

import cats.data.{EitherT, NonEmptyVector, Validated, ValidatedNec}
import cats.effect.Async
import cats.effect.std.Queue
import cats.implicits.*
import cats.syntax.all.*

import java.time.Instant
import scala.concurrent.duration.*
import scala.reflect.ClassTag
import scala.util.control.NoStackTrace

import app.AppDependencies
import app.Config.AppConfig.AppConfig
import app.JobSpecs.{CheckResetUserPasswordTokenError, CreateRoleError, CreateUserError, DeleteRoleByIdError, JobKind, JobResult, LoginError, RenewJwtTokenError, ResetMyPasswordError, ResetUserPasswordError}
import app.JobSpecs.JobResult.FetchAllLiveSessionsResult
import app.ThalesUtils.{GenUtils as U, PasswordValidationUtils, TimeUtils}
import app.ThalesUtils.ExtensionMethodUtils.*
import app.entrypoints.smithy.{HashedResetPasswordToken, HashedUserPassword, PermissionInDb, Role, RoleId, RoleName, User, UserId, UserInDb, UserPassword}
import app.services.{AuthService, ClockService, CreateRoleDbError, CreateUserDbError, ExternalApiClientService, PasswordHasherService, RenewalError, RepositoryService, ServerState, given}
import doobie.ConnectionIO
import doobie.implicits.*
import doobie.util.transactor.Transactor
import org.typelevel.log4cats.Logger

object HttpWorker:
  private final class JobExecutor[F[_]: { Async as async, Logger }](deps: AppDependencies[F]):
    private val repoService: RepositoryService = deps.repositoryService
    private val apiClient: ExternalApiClientService[F] = deps.externalApiClientService
    private val passwordHasherService: PasswordHasherService[F] = deps.passwordHasherService
    private val authService: AuthService[F] = deps.authService
    private val serverState: ServerState[F] = deps.serverState
    private val clockService: ClockService[F] = deps.clockService
    private val xa: Transactor[F] = deps.xa

    val uuidScope: TraceIdScope[F, Option[String]] = deps.uuidScope

    private val workerFiberName = "Http Worker"

    def logi(s: String): F[Unit] =
      uuidScope.get >>= (uuidOpt => uuidOpt.fold(U.logi(workerFiberName, s))(U.logi(workerFiberName, _, s)))
    end logi

    def loge(e: Throwable, s: String): F[Unit] =
      uuidScope.get >>= (uuidOpt => uuidOpt.fold(U.loge(e, workerFiberName, s))(U.loge(e, workerFiberName, _, s)))
    end loge

    private def logT(s: String): EitherT[F, Nothing, Unit] = EitherT.liftF(logi(s))

    private def validateUserParameters(user: User): EitherT[F, CreateUserError, Unit] =
      def verifyNonEmpty(s: String, name: String): ValidatedNec[(String, String), Unit] =
        s.nonEmpty.valid((), (name, "cannot be empty."))
      end verifyNonEmpty

      EitherT.fromEither[F](
        (
          verifyNonEmpty(user.loginName.value, "LoginName"),
          verifyNonEmpty(user.firstName, "FirstName"),
          verifyNonEmpty(user.lastName, "LastName"),
          verifyNonEmpty(user.email, "Email"),
          verifyNonEmpty(user.phone, "Phone"),
          verifyNonEmpty(user.password.value, "Password"),
        ).mapN(U.const6(()))
          .leftMap(errChain => CreateUserError.InvalidParameters(errChain.toNonEmptyVector))
          .toEither,
      )
    end validateUserParameters

    private def validatePassword[E](password: UserPassword, e: NonEmptyVector[String] => E): EitherT[F, E, Unit] =
      EitherT.fromEither(
        PasswordValidationUtils
          .isPasswordGoodEnough(password)
          .toEither
          .leftMap(e),
      )
    end validatePassword

    private val logCreatingUser: EitherT[F, Nothing, Unit] = logT("Creating user.")
    private val logCheckingParamsPasswordValidity: EitherT[F, Nothing, Unit] = logT("Checking params/password validity.")
    private val logParamsValid: EitherT[F, Nothing, Unit] = logT("Parameters look valid/non-empty.")

    private def createUser(j: JobKind.CreateUserRequest): F[JobResult] =
      val (user, creatingUserId) = (j.user, j.creatingUserId)
      val (loginName, password) = (user.loginName, user.password)

      val res: EitherT[F, CreateUserError, UserId] = for {
        _ <- logCreatingUser
        _ <- logCheckingParamsPasswordValidity
        _ <- validateUserParameters(user)
        _ <- logParamsValid
        _ <- validatePassword(password, CreateUserError.BadPassword.apply)
        _ <- logT(s"Password is valid. Creating user '$loginName'.")
        hashedPassword <- EitherT.liftF(passwordHasherService.hashPassword(password))
        _ <- logT(hashedPassword.value)
        creationTime <- getNow
        userId <-
          val dbProgram = repoService
            .createUser(
              loginName,
              user.firstName,
              user.lastName,
              user.email,
              user.phone,
              creationTime,
              hashedPassword,
              true,
              creationTime,
              true,
              creatingUserId,
            )
          EitherT(dbProgram.transact(xa))
            .leftMap { case CreateUserDbError.UniquenessConstraintViolated(nm) =>
              CreateUserError.UniquenessConstraintViolated(nm)
            }
      } yield userId

      res.value.map(JobResult.CreateUserResult.apply)
    end createUser

    private val logCreatingRole: EitherT[F, Nothing, Unit] = logT("Creating role.")
    private val logRoleParamsLookFine: EitherT[F, Nothing, Unit] = logT("Parameters look valid/non-empty.")

    private def validateRoleParameters(role: Role): EitherT[F, CreateRoleError, Unit] =
      EitherT.cond[F](
        role.roleName.value.nonEmpty,
        (),
        CreateRoleError.InvalidParameters(NonEmptyVector.one(("RoleName", "cannot be empty."))),
      )
    end validateRoleParameters

    private def createRoleInDb(roleName: RoleName, userId: UserId): EitherT[F, CreateRoleDbError, RoleId] =
      EitherT(
        clockService.nowInstant >>= { now => repoService.createRole(roleName, userId, now).transact(xa) },
      )
    end createRoleInDb

    private def createRole(j: JobKind.CreateRoleRequest): F[JobResult] =
      val (role, userId) = (j.role, j.userId)

      val res: EitherT[F, CreateRoleError, RoleId] = for {
        _ <- logCreatingRole
        _ <- validateRoleParameters(role)
        _ <- logRoleParamsLookFine
        roleId <- createRoleInDb(role.roleName, userId)
          .leftMap { case CreateRoleDbError.DuplicateRoleName => CreateRoleError.DuplicateRoleName }
      } yield roleId

      res.value.map(JobResult.CreateRoleResult.apply)
    end createRole

    private val logDeletingRoleF: F[Unit] = logi("Deleting role.")

    private val roleHasAssociatedUsersConIO: ConnectionIO[Either[DeleteRoleByIdError, Unit]] =
      doobie.FC.pure(Left(DeleteRoleByIdError.RoleHasAssociatedUsers))
    end roleHasAssociatedUsersConIO

    private val noSuchRoleIdConIO: ConnectionIO[Either[DeleteRoleByIdError, Unit]] =
      doobie.FC.pure(Left(DeleteRoleByIdError.NoSuchRoleId))
    end noSuchRoleIdConIO

    private val deleteRoleAllGoodConIO: ConnectionIO[Either[DeleteRoleByIdError, Unit]] =
      doobie.FC.pure(Right(()))
    end deleteRoleAllGoodConIO

    private val unexpectedNumberOfRowsDeletedConIO: ConnectionIO[Either[DeleteRoleByIdError, Unit]] =
      doobie.FC.raiseError(Exception("Unexpected number of rows deleted. Database consistency problem."))
    end unexpectedNumberOfRowsDeletedConIO

    private def deleteRoleImpl(roleId: RoleId): ConnectionIO[Either[DeleteRoleByIdError, Unit]] =
      for {
        isRoleAssignedToUsers <- repoService.isRoleAssignedToUsers(roleId)
        res <-
          if isRoleAssignedToUsers
          then roleHasAssociatedUsersConIO
          else
            repoService.deleteRoleById(roleId) >>= {
              case 0 => noSuchRoleIdConIO
              case 1 => deleteRoleAllGoodConIO
              case _ => unexpectedNumberOfRowsDeletedConIO
            }
      } yield res
    end deleteRoleImpl

    private def deleteRole(j: JobKind.DeleteRoleByIdRequest): F[JobResult] =
      val roleId = j.roleId

      for {
        _ <- logDeletingRoleF
        res <- deleteRoleImpl(roleId).transact(xa)
      } yield JobResult.DeleteRoleByIdResult(res)
    end deleteRole

    private val logFetchingUserByLoginNameF: F[Unit] = logi("Fetching user by loginName.")

    private def fetchUsersByLoginNames(j: JobKind.FetchUsersByLoginNamesRequest): F[JobResult] =
      val loginNames = j.loginNames

      for {
        _ <- logFetchingUserByLoginNameF
        res <- repoService
          .fetchUsersByLoginNames(loginNames)
          .transact(xa)
          .map(_.view.map(U.mapToFirst(_.loginName.value)).toMap)
      } yield JobResult.FetchUsersByLoginNamesResult(res)
    end fetchUsersByLoginNames

    private val logFetchUsersByUserIds: F[Unit] = logi("Fetching user by userId.")

    private def fetchUsersByUserIds(j: JobKind.FetchUsersByUserIdsRequest): F[JobResult] =
      val userIds = j.userIds

      for {
        _ <- logFetchUsersByUserIds
        res <- repoService
          .fetchUsersByUserIds(userIds)
          .transact(xa)
      } yield JobResult.FetchUsersByUserIdsResult(res)
    end fetchUsersByUserIds

    private def logLoginFailed[E](e: E): F[Unit] = logi("Login failed. Invalid password!")

    private def logLoginSuccessful(b: Boolean): F[Unit] = logi("Login was successful!")

    private def checkPassword[E](password: UserPassword, userInDb: UserInDb, e: E): EitherT[F, E, Boolean] =
      EitherT
        .liftF(passwordHasherService.checkPassword(password, userInDb.hashedPassword))
        .ensure(e)(identity)
        .biSemiflatTap(logLoginFailed, logLoginSuccessful)
    end checkPassword

    private def login(j: JobKind.LoginRequest): F[JobResult] =
      val (loginName, password) = (j.loginName, j.password)
      val loginNamesVec = NonEmptyVector.one(loginName)

      val fetchUserAndPermissionsDbProgram: ConnectionIO[Option[(UserInDb, Vector[PermissionInDb])]] =
        repoService.fetchUsersByLoginNames(loginNamesVec).map(_.headOption).flatMap {
          case Some(user) =>
            repoService.fetchUserPermissions(user.userId).map(perms => Some((user, perms)))
          case None =>
            doobie.FC.pure(None)
        }

      val program: EitherT[F, LoginError, (UserId, String)] = for {
        (userInDb, permissionsInDb) <- EitherT.fromOptionF(
          fetchUserAndPermissionsDbProgram.transact(xa),
          LoginError.InvalidLoginPassword,
        )
        _ <- EitherT.cond[F](userInDb.enabled, (), LoginError.UserNotEnabled)
        _ <- EitherT.cond[F](!userInDb.mustResetPassword, (), LoginError.UserMustResetPassword)
        _ <- checkPassword[LoginError](password, userInDb, LoginError.InvalidLoginPassword)
        token <- EitherT.liftF(authService.createToken(userInDb, permissionsInDb, None))
      } yield (userInDb.userId, token)

      program.value.map(JobResult.LoginResult.apply)
    end login

    private val renewErrorToResponse: Map[RenewalError, RenewJwtTokenError] = Map(
      RenewalError.NoSuchUser            -> RenewJwtTokenError.NoSuchUser,
      RenewalError.UserIsDisabled        -> RenewJwtTokenError.UserIsDisabled,
      RenewalError.UserMustResetPassword -> RenewJwtTokenError.UserMustResetPassword,
      RenewalError.RenewalTimeHasExpired -> RenewJwtTokenError.RenewalTimeHasExpired,
    )
    end renewErrorToResponse

    private def renewJwtToken(j: JobKind.RenewJwtTokenRequest): F[JobResult] =
      val authenticatedUser = j.authenticatedUser
      val userId = authenticatedUser.userId

      authService
        .renewToken(authenticatedUser)
        .map(_.fold(e => Left(renewErrorToResponse(e)), Right.apply))
        .map(JobResult.RenewJwtTokenResult.apply)
    end renewJwtToken

    private val logFetchingUserFromDb: EitherT[F, Nothing, Unit] = logT("Fetching user and checking enable status. Writing new password.")
    private val logCheckingValidityOfNewPassword: EitherT[F, Nothing, Unit] = logT("Checking validity of new password...")
    private val logComputingHashAndUpdatingDb: EitherT[F, Nothing, Unit] = logT(s"Password is valid. Computing hash and updating db.")

    private def resetMyPassword(j: JobKind.ResetMyPasswordRequest): F[JobResult] =
      val (userId, newPassword) = (j.authUser.userId, j.newPassword)
      val userIdsVec = NonEmptyVector.one(userId)

      val dbProgram: HashedUserPassword => EitherT[ConnectionIO, ResetMyPasswordError, Unit] = hashedPassword =>
        for {
          userInDb <- EitherT.fromOptionF(
            repoService.fetchUsersByUserIds(userIdsVec).map(_.get(userId)),
            ResetMyPasswordError.FailedToUpdateUserRow(s"User (${userId.value}) not found."),
          )
          _ <- EitherT.cond[ConnectionIO](userInDb.enabled, (), ResetMyPasswordError.UserNotEnabled)
          cnt <- EitherT.liftF(repoService.updateUserPasswordInDb(userId, hashedPassword))
          _ <- EitherT.cond[ConnectionIO](
            cnt == 1,
            (),
            ResetMyPasswordError.FailedToUpdateUserRow(s"Expected 1 row to be updated, but in fact updated $cnt."),
          )
        } yield ()

      val program: EitherT[F, ResetMyPasswordError, Unit] = for {
        _ <- logCheckingValidityOfNewPassword
        _ <- validatePassword(newPassword, ResetMyPasswordError.NewPasswordIsInvalid.apply)
        _ <- logComputingHashAndUpdatingDb
        hashedPassword <- EitherT.liftF(passwordHasherService.hashPassword(newPassword))
        _ <- logFetchingUserFromDb
        _ <- EitherT(dbProgram(hashedPassword).value.transact(xa))
      } yield ()

      program.value.map(JobResult.ResetMyPasswordResult.apply)
    end resetMyPassword

    private val getNow: EitherT[F, Nothing, Instant] =
      EitherT.liftF(clockService.nowInstant)
    end getNow

    private def checkResetUserPasswordToken(j: JobKind.CheckResetUserPasswordTokenRequest): F[JobResult] =
      val resetPasswordToken = j.resetPasswordToken
      val hashedToken = HashedResetPasswordToken(U.hashStringUrlEncoded(resetPasswordToken.value))

      val program: EitherT[F, CheckResetUserPasswordTokenError, Unit] = for {
        expiry <- EitherT.fromOptionF(
          repoService.getResetUserPasswordTokenExpiry(hashedToken).transact(xa),
          CheckResetUserPasswordTokenError.ExpiredToken,
        )
        now <- getNow
        _ <- EitherT.cond(expiry.isAfter(now), (), CheckResetUserPasswordTokenError.ExpiredToken)
      } yield ()

      program.value.map(JobResult.CheckResetUserPasswordTokenResult.apply)
    end checkResetUserPasswordToken

    private def fetchAllLiveSessions(j: JobKind.FetchAllLiveSessionsRequest.type): F[JobResult] =
      serverState.lastAccess.get >>= { lastAccess =>
        NonEmptyVector
          .fromVector(lastAccess.keys.toVector)
          .fold(async.pure(FetchAllLiveSessionsResult(Vector.empty))) { userIds =>
            repoService
              .fetchUsersByUserIds(userIds)
              .transact(xa)
              .map { users =>
                val res = users.keys
                  .map(U.mapToSecond(lastAccess.apply))
                  .toVector
                FetchAllLiveSessionsResult(res)
              }
          }
      }
    end fetchAllLiveSessions

    private val logFetchingAllPermissionsF: F[Unit] = logi("Fetching all permissions.")

    private def fetchAllPermissions(j: JobKind.FetchAllPermissionsRequest.type): F[JobResult] =
      for {
        _ <- logFetchingAllPermissionsF
        res <- repoService.fetchAllPermissions.transact(xa)
      } yield JobResult.FetchAllPermissionsResult(res)
    end fetchAllPermissions

    private val logFetchingAllRolesF: F[Unit] = logi("Fetching all roles.")

    private def fetchAllRoles(jk: JobKind.FetchAllRolesRequest.type): F[JobResult] =
      for {
        _ <- logFetchingAllRolesF
        res <- repoService.fetchAllRoles.transact(xa)
      } yield JobResult.FetchAllRolesResult(res)
    end fetchAllRoles

    private def fetchAllUsersAssociatedWithRoles(j: JobKind.FetchAllUsersAssociatedWithRolesRequest): F[JobResult] =
      val roleIds = j.roleIds

      val dbProgram: ConnectionIO[Map[RoleId, Vector[UserInDb]]] =
        repoService.fetchAllUsersAssociatedWithRoles(roleIds)

      for {
        _ <- logi(s"Fetching all users associated with roleIds: $roleIds")
        res <- dbProgram.transact(xa)
      } yield JobResult.FetchAllUsersAssociatedWithRolesResult(res)
    end fetchAllUsersAssociatedWithRoles

    private val logFetchingRoleByIdF: F[Unit] = logi("Fetching role by id.")

    private def fetchRolesByIds(j: JobKind.FetchRolesByIdsRequest): F[JobResult] =
      val roleIds = j.roleIds
      for {
        _ <- logFetchingRoleByIdF
        res <- repoService.fetchRolesByIds(roleIds).transact(xa)
      } yield JobResult.FetchRolesByIdsResult(res)
    end fetchRolesByIds

    private def registerHandler[J <: JobKind](handler: J => F[JobResult])(using
        ct: ClassTag[J],
    ): (Class[? <: JobKind], JobKind => F[JobResult]) =
      (ct.runtimeClass.asInstanceOf[Class[J]], (k: JobKind) => handler(k.asInstanceOf[J]))

    private def registerSingletonHandler[J <: JobKind](
        obj: J,
        handler: J => F[JobResult],
    ): (Class[? <: JobKind], JobKind => F[JobResult]) =
      (obj.getClass, (_: JobKind) => handler(obj))

    private val jobHandlersMap: Map[Class[? <: JobKind], JobKind => F[JobResult]] = Map(
      registerHandler(createUser),
      registerHandler(createRole),
      registerHandler(resetMyPassword),
      registerHandler(fetchUsersByLoginNames),
      registerHandler(fetchUsersByUserIds),
      registerHandler(login),
      registerHandler(renewJwtToken),
      registerHandler(deleteRole),
      registerHandler(fetchAllUsersAssociatedWithRoles),
      registerHandler(fetchRolesByIds),
      registerHandler(checkResetUserPasswordToken),
      registerSingletonHandler(JobKind.FetchAllLiveSessionsRequest, fetchAllLiveSessions),
      registerSingletonHandler(JobKind.FetchAllPermissionsRequest, fetchAllPermissions),
      registerSingletonHandler(JobKind.FetchAllRolesRequest, fetchAllRoles),
    )
    end jobHandlersMap

    private def missingJobError(job: JobKind): F[JobResult] =
      async.raiseError(
        new Exception(s"JobHandlersMap does not contain an implementation for class '${job.shortName}'.") with NoStackTrace,
      )
    end missingJobError

    def executeJob(job: JobKind): F[JobResult] =
      jobHandlersMap
        .get(job.getClass)
        .map(_(job))
        .getOrElse(missingJobError(job))
    end executeJob
  end JobExecutor

  private val sessionTimeoutDurationInSeconds: Long = 1.hour.toSeconds

  private def createWorker[F[_]: { Async as async }](queue: Queue[F, WorkerJob[F]], je: JobExecutor[F]): F[Nothing] =
    val logWaitingForWork = je.logi("Waiting for work.")
    val logSendingResultsBack = je.logi("Done. Sending results back...")
    val getJobFromQueue = queue.take.map(j => (j.job, j.deferred, j.uuid))
    val onErrorInner = je.loge(_, "Error while processing job. The job will be dropped.")
    val onErrorOuter = (e: Throwable) =>
      je.loge(e, "A non-recoverable error occurred in the worker loop. Restarting....") *>
        async.sleep(500.milliseconds)

    val processOneJob: F[Unit] = for {
      _ <- logWaitingForWork
      (job, deferred, uuid) <- getJobFromQueue
      _ <- je.uuidScope.scope(Some(uuid)).use { _ =>
        val jobExecution: F[Unit] = for {
          _ <- je.logi(s"Starting to work on ${job.shortName}...")
          outcome <- je.executeJob(job).attempt
          _ <- logSendingResultsBack
          _ <- deferred.complete(outcome)
        } yield ()

        jobExecution.handleErrorWith(onErrorInner)
      }
    } yield ()

    val processSafely = processOneJob.handleErrorWith(onErrorOuter)

    processSafely.foreverM
  end createWorker

  def createWorkers[F[_]: { Async, Logger }](appConfig: AppConfig, deps: AppDependencies[F]): F[Unit] =
    val jobExecutor: JobExecutor[F] = JobExecutor(deps)
    val serverState = deps.serverState

    val numberOfWorkers = appConfig.getBackendServerConfig.getNumberOfWorkers
    val worker = createWorker(serverState.jobQueue, jobExecutor)
    val supervisor = deps.supervisor

    Vector
      .from(0 until numberOfWorkers)
      .traverseVoid(_ => supervisor.supervise(worker))
  end createWorkers
end HttpWorker
