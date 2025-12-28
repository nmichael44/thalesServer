package app

import cats.~>
import cats.data.{EitherT, NonEmptyVector, Validated, ValidatedNec}
import cats.effect.{Async, Resource}
import cats.effect.std.Queue
import cats.implicits.*
import cats.syntax.all.*

import scala.concurrent.duration.*
import scala.util.control.NoStackTrace

import app.AppDependencies
import app.Config.AppConfig.AppConfig
import app.JobSpecs.{CreateRoleError, CreateUserError, DeleteRoleByIdError, FetchAllUsersAssociatedWithRoleError, FetchRoleByError, JobKind, JobResult, LoginError, RenewJwtTokenError, ResetUserPasswordError}
import app.JobSpecs.JobResult.FetchAllLiveSessionsResult
import app.ThalesUtils.{GenUtils as U, PasswordValidationUtils, TimeUtils}
import app.ThalesUtils.ExtensionMethodUtils.*
import app.entrypoints.smithy.{PermissionInDb, Role, User, UserInDb}
import app.model.AppModel
import app.services.{AuthService, CreateRoleDbError, CreateUserDbError, ExternalApiClientService, PasswordHasherService, RenewalError, RepositoryService, ServerState}
import app.services.given
import doobie.{ConnectionIO, WeakAsync}
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
    private val xa: Transactor[F] = deps.xa
    private val fToConnectionIO: Resource[F, F ~> ConnectionIO] = WeakAsync.liftK[F, ConnectionIO]

    val uuidScope: TraceIdScope[F, Option[String]] = deps.uuidScope

    private val workerFiberName = "Http Worker"

    def logi(s: String): F[Unit] =
      uuidScope.get >>= (uuidOpt => uuidOpt.fold(U.logi(workerFiberName, s))(U.logi(workerFiberName, _, s)))
    end logi

    def loge(e: Throwable, s: String): F[Unit] =
      uuidScope.get >>= (uuidOpt => uuidOpt.fold(U.loge(e, workerFiberName, s))(U.loge(e, workerFiberName, _, s)))
    end loge

    private def validateUserParameters(user: User): EitherT[F, CreateUserError, Unit] =
      def verifyNonEmpty(s: String, name: String): ValidatedNec[(String, String), Unit] =
        s.nonEmpty.valid((), (name, "cannot be empty."))
      end verifyNonEmpty

      EitherT.fromEither[F](
        (
          verifyNonEmpty(user.loginName, "LoginName"),
          verifyNonEmpty(user.firstName, "FirstName"),
          verifyNonEmpty(user.lastName, "LastName"),
          verifyNonEmpty(user.email, "Email"),
          verifyNonEmpty(user.phone, "Phone"),
          verifyNonEmpty(user.password, "Password"),
        ).mapN(U.const6(()))
          .leftMap(errChain => CreateUserError.InvalidParameters(errChain.toNonEmptyVector))
          .toEither,
      )
    end validateUserParameters

    private def validatePassword[E](password: String, e: NonEmptyVector[String] => E): EitherT[F, E, Unit] =
      EitherT.fromEither(
        PasswordValidationUtils
          .isPasswordGoodEnough(password)
          .toEither
          .leftMap(e),
      )
    end validatePassword

    private val logCreatingUser: EitherT[F, Nothing, Unit] =
      EitherT.liftF(logi("Creating user."))
    end logCreatingUser

    private val logCheckingParamsPasswordValidity: EitherT[F, Nothing, Unit] =
      EitherT.liftF(logi("Checking params/password validity."))
    end logCheckingParamsPasswordValidity

    private val logParamsValid: EitherT[F, Nothing, Unit] =
      EitherT.liftF(logi(s"Parameters look valid/non-empty."))
    end logParamsValid

    private def createUser(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.CreateUserRequest]
      val (user, creatingUserId) = (j.user, j.creatingUserId)
      val (loginName, password) = (user.loginName, user.password)

      val res: EitherT[F, CreateUserError, Long] = for {
        _ <- logCreatingUser
        _ <- logCheckingParamsPasswordValidity
        _ <- validateUserParameters(user)
        _ <- logParamsValid
        _ <- validatePassword(password, CreateUserError.BadPassword.apply)
        _ <- EitherT.liftF(logi(s"Password is valid. Creating user '$loginName'."))
        hashedPassword <- EitherT.liftF(passwordHasherService.hashPassword(password))
        _ <- EitherT.liftF(logi(hashedPassword))
        creationTime <- EitherT.liftF(TimeUtils.nowInstant)
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

    private val logCreatingRole: EitherT[F, Nothing, Unit] =
      EitherT.liftF(logi("Creating role."))
    end logCreatingRole

    private val logRoleParamsLookFine: EitherT[F, Nothing, Unit] =
      EitherT.liftF(logi("Parameters look valid/non-empty."))
    end logRoleParamsLookFine

    private def validateRoleParameters(role: Role): EitherT[F, CreateRoleError, Unit] =
      EitherT.cond[F](
        role.roleName.nonEmpty,
        (),
        CreateRoleError.InvalidParameters(NonEmptyVector.one(("RoleName", "cannot be empty."))),
      )
    end validateRoleParameters

    private def createRoleInDb(roleName: String, userId: Long): EitherT[F, CreateRoleDbError, Long] =
      EitherT(
        TimeUtils.nowInstant >>= { now => repoService.createRole(roleName, userId, now).transact(xa) },
      )
    end createRoleInDb

    private def createRole(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.CreateRoleRequest]
      val (role, userId) = (j.role, j.userId)

      val res: EitherT[F, CreateRoleError, Long] = for {
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

    private def deleteRoleImpl(roleId: Long): ConnectionIO[Either[DeleteRoleByIdError, Unit]] =
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

    private def deleteRole(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.DeleteRoleByIdRequest]
      val roleId = j.roleId

      for {
        _ <- logDeletingRoleF
        res <- deleteRoleImpl(roleId).transact(xa)
      } yield JobResult.DeleteRoleByIdResult(res)
    end deleteRole

    private val logFetchingUserByLoginNameF: F[Unit] = logi("Fetching user by loginName.")

    private def fetchUsersByLoginNames(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.FetchUsersByLoginNamesRequest]
      val loginNames = j.loginNames

      for {
        _ <- logFetchingUserByLoginNameF
        res <- repoService
          .fetchUsersByLoginNames(loginNames)
          .transact(xa)
          .map(_.view.map(U.mapToFirst(_.loginName)).toMap)
      } yield JobResult.FetchUsersByLoginNamesResult(res)
    end fetchUsersByLoginNames

    private val logFetchUsersByUserIds: F[Unit] = logi("Fetching user by userId.")

    private def fetchUsersByUserIds(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.FetchUsersByUserIdsRequest]
      val userIds = j.userIds

      for {
        _ <- logFetchUsersByUserIds
        res <- repoService
          .fetchUsersByUserIds(userIds)
          .transact(xa)
          .map(_.view.map(U.mapToFirst(_.userId)).toMap)
      } yield JobResult.FetchUsersByUserIdsResult(res)
    end fetchUsersByUserIds

    private def logLoginFailed[E](e: E): F[Unit] = logi("Login failed. Invalid password!")

    private def logLoginSuccessful(b: Boolean): F[Unit] = logi("Login was successful!")

    private def checkPassword[Error](
        password: String,
        userInDb: UserInDb,
        e: Error,
    ): EitherT[F, Error, Boolean] =
      EitherT
        .liftF(
          passwordHasherService
            .checkPassword(password, userInDb.hashedPassword),
        )
        .ensure(e)(identity)
        .biSemiflatTap(logLoginFailed, logLoginSuccessful)
    end checkPassword

    private def login(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.LoginRequest]
      val ud = j.loginUserDetails
      val (loginName, password) = (ud.loginName, ud.password)
      val loginNamesVec = NonEmptyVector.one(loginName)

      fToConnectionIO.use { implicit fToConIO =>
        val dbProgram: EitherT[ConnectionIO, LoginError, (Long, String)] = for {
          userInDb <-
            EitherT.fromOptionF(
              repoService.fetchUsersByLoginNames(loginNamesVec).map(_.headOption),
              LoginError.InvalidLoginPassword,
            )
          _ <- EitherT.cond[ConnectionIO](userInDb.enabled, (), LoginError.UserNotEnabled)
          _ <- EitherT.cond[ConnectionIO](!userInDb.mustResetPassword, (), LoginError.UserMustResetPassword)
          _ <- U.liftEitherT(checkPassword[LoginError](password, userInDb, LoginError.InvalidLoginPassword))
          permissionsInDb <-
            EitherT.liftF[ConnectionIO, LoginError, Vector[PermissionInDb]](
              repoService.fetchUserPermissions(userInDb.userId),
            )
          token <- U.liftPureF(authService.createToken(userInDb, permissionsInDb, None))
        } yield (userInDb.userId, token)

        dbProgram.value
          .transact(xa)
          .map(JobResult.LoginResult.apply)
      }
    end login

    private val renewErrorToResponse: Map[RenewalError, RenewJwtTokenError] = Map(
      RenewalError.NoSuchUser            -> RenewJwtTokenError.NoSuchUser,
      RenewalError.UserIsDisabled        -> RenewJwtTokenError.UserIsDisabled,
      RenewalError.UserMustResetPassword -> RenewJwtTokenError.UserMustResetPassword,
      RenewalError.RenewalTimeHasExpired -> RenewJwtTokenError.RenewalTimeHasExpired,
    )
    end renewErrorToResponse

    private def renewJwtToken(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.RenewJwtTokenRequest]
      val authenticatedUser = j.authenticatedUser
      val userId = authenticatedUser.userId

      authService
        .renewToken(authenticatedUser)
        .map(_.fold(e => Left(renewErrorToResponse(e)), Right.apply))
        .map(JobResult.RenewJwtTokenResult.apply)
    end renewJwtToken

    private val logFetchingUserFromDbF: F[Unit] = logi("Fetching user from database...")
    private val logCheckingOldPasswordF: F[Unit] = logi("Checking old password...")
    private val logCheckingValidityOfNewPasswordF: F[Unit] = logi("Checking validity of new password...")
    private val logComputingHashAndUpdatingDbF: F[Unit] = logi(s"Password is valid. Computing hash and updating db.")

    private def resetUserPassword(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.ResetUserPasswordRequest]
      val (loginName, oldPassword, newPassword) = (j.loginName, j.oldPassword, j.newPassword)
      val loginNamesVec = NonEmptyVector.one(loginName)

      fToConnectionIO.use { implicit fToConIO =>
        val dbProgram: EitherT[ConnectionIO, ResetUserPasswordError, Unit] = for {
          userInDb <- EitherT.fromOptionF(
            repoService.fetchUsersByLoginNames(loginNamesVec).map(_.headOption),
            ResetUserPasswordError.LoginNameNotFound,
          )
          _ <- U.liftPureF(logFetchingUserFromDbF)
          _ <- EitherT.cond[ConnectionIO](userInDb.enabled, (), ResetUserPasswordError.UserNotEnabled)
          _ <- U.liftPureF(logCheckingOldPasswordF)
          _ <- U.liftEitherT(
            checkPassword[ResetUserPasswordError](
              oldPassword,
              userInDb,
              ResetUserPasswordError.InvalidLoginPassword,
            ),
          )
          _ <- U.liftPureF(logCheckingValidityOfNewPasswordF)
          _ <- U.liftEitherT(validatePassword(newPassword, ResetUserPasswordError.NewPasswordInsufficient.apply))
          _ <- U.liftPureF(logComputingHashAndUpdatingDbF)
          hashedPassword <- U.liftPureF(passwordHasherService.hashPassword(newPassword))
          cnt <- EitherT.liftF(repoService.updateUserPasswordInDb(userInDb.userId, hashedPassword))
          _ <- EitherT.cond[ConnectionIO](
            cnt == 1,
            (),
            ResetUserPasswordError.FailedToUpdateUserRow(s"Expected 1 row to be updated, but in fact updated $cnt."),
          )
        } yield ()

        dbProgram.value
          .transact(xa)
          .map(JobResult.ResetUserPasswordResult.apply)
      }
    end resetUserPassword

    private def fetchAllLiveSessions(jk: JobKind): F[JobResult] =
      serverState.lastAccess.get >>= { lastAccess =>
        NonEmptyVector
          .fromVector(lastAccess.keys.toVector)
          .fold(async.pure(FetchAllLiveSessionsResult(Vector.empty))) { userIds =>
            repoService
              .fetchUsersByUserIds(userIds)
              .transact(xa)
              .map { users =>
                val res = users.view
                  .map(U.mapToSecond(u => lastAccess(u.userId)))
                  .toVector
                FetchAllLiveSessionsResult(res)
              }
          }
      }
    end fetchAllLiveSessions

    private val logFetchingAllPermissionsF: F[Unit] = logi("Fetching all permissions.")

    private def fetchAllPermissions(jk: JobKind): F[JobResult] =
      for {
        _ <- logFetchingAllPermissionsF
        res <- repoService.fetchAllPermissions.transact(xa)
      } yield JobResult.FetchAllPermissionsResult(res)
    end fetchAllPermissions

    private val logFetchingAllRolesF: F[Unit] = logi("Fetching all roles.")

    private def fetchAllRoles(jk: JobKind): F[JobResult] =
      for {
        _ <- logFetchingAllRolesF
        res <- repoService.fetchAllRoles.transact(xa)
      } yield JobResult.FetchAllRolesResult(res)
    end fetchAllRoles

    private val noSuchRoleF: ConnectionIO[Either[FetchAllUsersAssociatedWithRoleError, Vector[UserInDb]]] =
      doobie.FC.pure(Left(FetchAllUsersAssociatedWithRoleError.NoSuchRole))
    end noSuchRoleF

    private def fetchAllUsersAssociatedWithRole(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.FetchAllUsersAssociatedWithRoleRequest]
      val roleId = j.roleId

      val dbProgram: ConnectionIO[Either[FetchAllUsersAssociatedWithRoleError, Vector[UserInDb]]] =
        repoService.fetchRoleById(roleId) >>= {
          _.fold(noSuchRoleF)(_ => repoService.fetchAllUsersAssociatedWithRole(roleId).map(Right.apply))
        }

      for {
        _ <- logi(s"Fetching all users associated with roleId: $roleId")
        res <- dbProgram.transact(xa)
      } yield JobResult.FetchAllUsersAssociatedWithRoleResult(res)
    end fetchAllUsersAssociatedWithRole

    private val logFetchingRoleByIdF: F[Unit] = logi("Fetching role by id.")

    private def fetchRoleById(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.FetchRoleByIdRequest]
      val roleId = j.roleId
      for {
        _ <- logFetchingRoleByIdF
        res <- repoService.fetchRoleById(roleId).transact(xa)
      } yield JobResult.FetchRoleByIdResult(res.toRight(FetchRoleByError.RoleNotFound))
    end fetchRoleById

    private val jobHandlersMap: Map[Class[? <: JobKind], JobKind => F[JobResult]] = Map(
      classOf[JobKind.CreateUserRequest]                      -> createUser,
      classOf[JobKind.CreateRoleRequest]                      -> createRole,
      classOf[JobKind.ResetUserPasswordRequest]               -> resetUserPassword,
      classOf[JobKind.FetchUsersByLoginNamesRequest]          -> fetchUsersByLoginNames,
      classOf[JobKind.FetchUsersByUserIdsRequest]             -> fetchUsersByUserIds,
      classOf[JobKind.LoginRequest]                           -> login,
      classOf[JobKind.RenewJwtTokenRequest]                   -> renewJwtToken,
      JobKind.FetchAllLiveSessionsRequest.getClass            -> fetchAllLiveSessions,
      JobKind.FetchAllPermissionsRequest.getClass             -> fetchAllPermissions,
      JobKind.FetchAllRolesRequest.getClass                   -> fetchAllRoles,
      classOf[JobKind.DeleteRoleByIdRequest]                  -> deleteRole,
      classOf[JobKind.FetchAllUsersAssociatedWithRoleRequest] -> fetchAllUsersAssociatedWithRole,
      classOf[JobKind.FetchRoleByIdRequest]                   -> fetchRoleById,
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
