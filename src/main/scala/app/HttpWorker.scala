package app

import cats.~>
import cats.data.{EitherT, NonEmptyVector, Validated}
import cats.effect.{Async, Resource}
import cats.effect.std.Queue
import cats.implicits.*
import cats.syntax.all.*

import scala.concurrent.duration.*
import scala.util.control.NoStackTrace

import app.auth.Permissions
import app.auth.Permissions.PermissionInDb
import app.model.AppModel
import app.model.AppModel.UserInDb
import app.services.{AuthService, ExternalApiClientService, PasswordHasherService, RenewalError, RepositoryService, ServerState}
import app.services.{CreateRoleDbError, CreateUserDbError}
import app.services.given
import app.AppDependencies
import app.Config.AppConfig.AppConfig
import app.JobSpecs.{CreateRoleError, CreateUserError, DeleteRoleByIdError, FetchAllUsersAssociatedWithRoleError, FetchRoleByError, FetchUserByError, JobKind, JobResult, LoginError, RenewJwtTokenError, ResetUserPasswordError}
import app.JobSpecs.JobResult.FetchAllLiveSessionsResult
import app.JobSpecs.JobResult.FetchMultipleUsersByIdResult
import app.ThalesUtils.{GenUtils, GenUtils as U, PasswordValidationUtils, TimeUtils}
import app.ThalesUtils.ExtensionMethodUtils.*
import doobie.{ConnectionIO, WeakAsync}
import doobie.implicits.*
import doobie.util.transactor.Transactor
import org.typelevel.log4cats.Logger

object HttpWorker:
  final case class UserSessionTimedOut(loginName: String) extends RuntimeException(s"User '$loginName' session timed out.")

  private final class JobExecutor[F[_]: { Async as async, Logger }](deps: AppDependencies[F]):
    private val repoService: RepositoryService = deps.repositoryService
    private val apiClient: ExternalApiClientService[F] = deps.externalApiClientService
    private val passwordHasherService: PasswordHasherService[F] = deps.passwordHasherService
    private val authService: AuthService[F] = deps.authService
    private val serverState: ServerState[F] = deps.serverState
    private val xa: Transactor[F] = deps.xa
    private val fToConnectionIO: Resource[F, F ~> ConnectionIO] = WeakAsync.liftK[F, ConnectionIO]

    val uuidScope: TraceIdScope[F, Option[String]] = deps.uuidScope

    private val WorkerFiberName = "Http Worker"

    def logi(s: String): F[Unit] =
      uuidScope.get >>= (uuidOpt => uuidOpt.fold(U.logi(WorkerFiberName, s))(U.logi(WorkerFiberName, _, s)))
    end logi

    def loge(e: Throwable, s: String): F[Unit] =
      uuidScope.get >>= (uuidOpt => uuidOpt.fold(U.loge(e, WorkerFiberName, s))(U.loge(e, WorkerFiberName, _, s)))
    end loge

    private def validateUserParameters(user: AppModel.User): EitherT[F, CreateUserError, Unit] =
      val cannotBeEmpty = "cannot be empty."
      val unit: Unit = ()

      EitherT.fromEither[F](
        (
          user.loginName.nonEmpty.valid(unit, ("LoginName", cannotBeEmpty)),
          user.firstName.nonEmpty.valid(unit, ("FirstName", cannotBeEmpty)),
          user.lastName.nonEmpty.valid(unit, ("LastName", cannotBeEmpty)),
          user.email.nonEmpty.valid(unit, ("Email", cannotBeEmpty)),
          user.phone.nonEmpty.valid(unit, ("Phone", cannotBeEmpty)),
          user.password.nonEmpty.valid(unit, ("Password", cannotBeEmpty)),
        ).mapN(GenUtils.const6(unit))
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
      val user = j.user
      val (loginName, password) = (user.loginName, user.password)

      val res: EitherT[F, CreateUserError, Long] = for {
        _ <- logCreatingUser
        _ <- logCheckingParamsPasswordValidity
        _ <- validateUserParameters(user)
        _ <- logParamsValid
        _ <- validatePassword(password, CreateUserError.BadPassword.apply)
        _ <- EitherT.liftF(logi(s"Password is valid. Creating user '$loginName'."))
        hashedPassword <- EitherT.liftF(passwordHasherService.hashPassword(password))
        _ <- logi(hashedPassword).lifte
        creationTime <- TimeUtils.nowInstant.lifte
        userId <-
          repoService
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
            )
            .transact(xa)
            .toEitherT
            .leftMap { case CreateUserDbError.UniquenessConstraintViolated(nm) =>
              CreateUserError.UniquenessConstraintViolated(nm)
            }
      } yield userId

      res.value.map(JobResult.CreateUserResult.apply)
    end createUser

    private val logCreatingRole: EitherT[F, Nothing, Unit] = logi("Creating role.").lifte
    private val logRoleParamsLookFine: EitherT[F, Nothing, Unit] = logi(s"Parameters look valid/non-empty.").lifte

    private def validateRoleParameters(role: AppModel.Role): EitherT[F, CreateRoleError, Unit] =
      EitherT.cond[F](
        role.roleName.nonEmpty,
        (),
        CreateRoleError.InvalidParameters(NonEmptyVector.one(("RoleName", "cannot be empty."))),
      )
    end validateRoleParameters

    private def createRole(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.CreateRoleRequest]
      val (role, userId) = (j.role, j.userId)

      val res: EitherT[F, CreateRoleError, Long] = for {
        _ <- logCreatingRole
        _ <- validateRoleParameters(role)
        _ <- logRoleParamsLookFine
        roleId <- (TimeUtils.nowInstant >>= { now =>
          repoService
            .createRole(role.roleName, userId, now)
            .transact(xa)
        }).toEitherT
          .leftMap { case CreateRoleDbError.DuplicateRoleName(nm) => CreateRoleError.DuplicateRoleName(nm) }
      } yield roleId

      res.value.map(JobResult.CreateRoleResult.apply)
    end createRole

    private val logDeletingRole: F[Unit] = logi("Deleting role.")

    private def deleteRoleImpl(roleId: Long): ConnectionIO[Either[DeleteRoleByIdError, Unit]] =
      for {
        isRoleAssignedToUsers <- repoService.isRoleAssignedToUsers(roleId)
        res <-
          if isRoleAssignedToUsers
          then doobie.FC.pure(Left(DeleteRoleByIdError.RoleHasAssociatedUsers))
          else
            repoService.deleteRoleById(roleId) >>= {
              case 0 => doobie.FC.pure(Left(DeleteRoleByIdError.NoSuchRoleId))
              case 1 => doobie.FC.pure(Right(()))
              case _ => doobie.FC.raiseError(Exception("Unexpected number of rows deleted. Database consistency problem."))
            }
      } yield res
    end deleteRoleImpl

    private def deleteRole(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.DeleteRoleByIdRequest]
      val roleId = j.roleId

      for {
        _ <- logDeletingRole
        res <- deleteRoleImpl(roleId).transact(xa)
      } yield JobResult.DeleteRoleByIdResult(res)
    end deleteRole

    private val logFetchingUserByLoginName: F[Unit] = logi("Fetching user by loginName.")

    private def fetchUserByLoginName(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.FetchUserByLoginNameRequest]
      val loginName = j.loginName

      for {
        _ <- logFetchingUserByLoginName
        res <- repoService
          .fetchUserByLoginName(loginName)
          .transact(xa)
          .map(_.toRight(FetchUserByError.UserNotFound))
      } yield JobResult.FetchUserByLoginNameResult(res)
    end fetchUserByLoginName

    private val logFetchingUserByUserId: F[Unit] = logi("Fetching user by userId.")

    private def fetchUserByUserId(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.FetchUserByIdRequest]
      val userId = j.userId

      for {
        _ <- logFetchingUserByUserId
        res <- repoService
          .fetchUserById(userId)
          .transact(xa)
          .map(_.toRight(FetchUserByError.UserNotFound))
      } yield JobResult.FetchUserByIdResult(res)
    end fetchUserByUserId

    private val logFetchMultipleUsersById: F[Unit] = logi("Fetching user by userId.")

    private def fetchMultipleUsersById(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.FetchMultipleUsersByIdRequest]
      val userIds = j.userIds

      for {
        _ <- logFetchMultipleUsersById
        res <- repoService.fetchMultipleUsersById(userIds).transact(xa)
      } yield FetchMultipleUsersByIdResult(res)
    end fetchMultipleUsersById

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

      fToConnectionIO.use { implicit fToConIO =>
        val dbProgram: EitherT[ConnectionIO, LoginError, (Long, String)] = for {
          userInDb <- EitherT.fromOptionF(repoService.fetchUserByLoginName(loginName), LoginError.InvalidLoginPassword)
          _ <- EitherT.cond[ConnectionIO](userInDb.enabled, (), LoginError.UserNotEnabled)
          _ <- EitherT.cond[ConnectionIO](!userInDb.mustResetPassword, (), LoginError.UserMustResetPassword)
          _ <- U.liftEitherT(checkPassword[LoginError](password, userInDb, LoginError.InvalidLoginPassword))
          permissionsInDb <- EitherT.liftF[ConnectionIO, LoginError, Vector[PermissionInDb]](
            repoService.fetchUserPermissions(userInDb.userId),
          )
          token <- {
            val permissions = permissionsInDb.map(Permissions.fromString)
            U.liftPureF(authService.createToken(userInDb, permissions, None))
          }
        } yield (userInDb.userId, token)

        dbProgram.value
          .transact(xa)
          .map(JobResult.LoginResult.apply)
      }
    end login

    private def renewJwtToken(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.RenewJwtTokenRequest]
      val authenticatedUser = j.authenticatedUser
      val userId = authenticatedUser.userId

      authService
        .renewToken(authenticatedUser)
        .map {
          _.fold(
            e =>
              Left(e match {
                case RenewalError.NoSuchUser => RenewJwtTokenError.NoSuchUser(userId)
                case RenewalError.UserIsDisabled => RenewJwtTokenError.UserIsDisabled(userId)
                case RenewalError.UserMustResetPassword => RenewJwtTokenError.UserMustResetPassword(userId)
                case RenewalError.RenewalTimeHasExpired => RenewJwtTokenError.RenewalTimeHasExpired
              }),
            Right(_),
          )
        }
        .map(JobResult.RenewJwtTokenResult.apply)
    end renewJwtToken

    private val logFetchingUserFromDb: F[Unit] = logi("Fetching user from database...")
    private val logCheckingOldPassword: F[Unit] = logi("Checking old password...")
    private val logCheckingValidityOfNewPassword: F[Unit] = logi("Checking validity of new password...")
    private val logComputingHashAndUpdatingDb: F[Unit] = logi(s"Password is valid. Computing hash and updating db.")

    private def resetUserPassword(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.ResetUserPasswordRequest]
      val loginName = j.loginName
      val oldPassword = j.oldPassword
      val newPassword = j.newPassword

      fToConnectionIO.use { implicit fToConIO =>
        val dbProgram: EitherT[ConnectionIO, ResetUserPasswordError, Unit] = for {
          userInDb <- EitherT.fromOptionF(
            repoService.fetchUserByLoginName(loginName),
            ResetUserPasswordError.LoginNameNotFound,
          )
          _ <- U.liftPureF(logFetchingUserFromDb)
          _ <- EitherT.cond[ConnectionIO](userInDb.enabled, (), ResetUserPasswordError.UserNotEnabled)
          _ <- U.liftPureF(logCheckingOldPassword)
          _ <- U.liftEitherT(
            checkPassword[ResetUserPasswordError](
              oldPassword,
              userInDb,
              ResetUserPasswordError.InvalidLoginPassword,
            ),
          )
          _ <- U.liftPureF(logCheckingValidityOfNewPassword)
          _ <- U.liftEitherT(validatePassword(newPassword, ResetUserPasswordError.NewPasswordInsufficient.apply))
          _ <- U.liftPureF(logComputingHashAndUpdatingDb)
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
        NonEmptyVector.fromVector(lastAccess.keys.toVector).fold(FetchAllLiveSessionsResult(Vector.empty).pure) { userIds =>
          repoService.fetchMultipleUsersById(userIds).transact(xa).map { users =>
            val res = users.view.map((userId, user) => (user, lastAccess(userId))).toVector
            FetchAllLiveSessionsResult(res)
          }
        }
      }
    end fetchAllLiveSessions

    private val logFetchingAllPermissions: F[Unit] = logi("Fetching all permissions.")

    private def fetchAllPermissions(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.FetchAllPermissionsRequest]

      for {
        _ <- logFetchingAllPermissions
        res <- repoService.fetchAllPermissions.transact(xa)
      } yield JobResult.FetchAllPermissionsResult(res)
    end fetchAllPermissions

    private val logFetchingAllRoles: F[Unit] = logi("Fetching all roles.")

    private def fetchAllRoles(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.FetchAllRolesRequest]
      for {
        _ <- logFetchingAllRoles
        res <- repoService.fetchAllRoles.transact(xa)
      } yield JobResult.FetchAllRolesResult(res)
    end fetchAllRoles

    private val NoSuchRoleF: ConnectionIO[Either[FetchAllUsersAssociatedWithRoleError, Vector[UserInDb]]] =
      doobie.FC.pure(Left(FetchAllUsersAssociatedWithRoleError.NoSuchRole))
    end NoSuchRoleF

    private def fetchAllUsersAssociatedWithRole(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.FetchAllUsersAssociatedWithRoleRequest]
      val roleId = j.roleId

      val dbProgram: ConnectionIO[Either[FetchAllUsersAssociatedWithRoleError, Vector[UserInDb]]] =
        repoService.fetchRoleById(roleId) >>= {
          _.fold(NoSuchRoleF)(_ => repoService.fetchAllUsersAssociatedWithRole(roleId).map(Right.apply))
        }

      for {
        _ <- logi(s"Fetching all users associated with roleId: $roleId")
        res <- dbProgram.transact(xa)
      } yield JobResult.FetchAllUsersAssociatedWithRoleResult(res)
    end fetchAllUsersAssociatedWithRole

    private val logFetchingRoleById: F[Unit] = logi("Fetching role by id.")

    private def fetchRoleById(jk: JobKind): F[JobResult] =
      val j = jk.asInstanceOf[JobKind.FetchRoleByIdRequest]
      val roleId = j.roleId
      for {
        _ <- logFetchingRoleById
        res <- repoService.fetchRoleById(roleId).transact(xa)
      } yield JobResult.FetchRoleByIdResult(res.toRight(FetchRoleByError.RoleNotFound))
    end fetchRoleById

    private val JobHandlersMap: Map[Class[? <: JobKind], JobKind => F[JobResult]] = Map(
      classOf[JobKind.CreateUserRequest]                      -> createUser,
      classOf[JobKind.CreateRoleRequest]                      -> createRole,
      classOf[JobKind.ResetUserPasswordRequest]               -> resetUserPassword,
      classOf[JobKind.FetchUserByLoginNameRequest]            -> fetchUserByLoginName,
      classOf[JobKind.FetchUserByIdRequest]                   -> fetchUserByUserId,
      classOf[JobKind.FetchMultipleUsersByIdRequest]          -> fetchMultipleUsersById,
      classOf[JobKind.LoginRequest]                           -> login,
      classOf[JobKind.RenewJwtTokenRequest]                   -> renewJwtToken,
      classOf[JobKind.FetchAllLiveSessionsRequest]            -> fetchAllLiveSessions,
      classOf[JobKind.FetchAllPermissionsRequest]             -> fetchAllPermissions,
      classOf[JobKind.FetchAllRolesRequest]                   -> fetchAllRoles,
      classOf[JobKind.DeleteRoleByIdRequest]                  -> deleteRole,
      classOf[JobKind.FetchAllUsersAssociatedWithRoleRequest] -> fetchAllUsersAssociatedWithRole,
      classOf[JobKind.FetchRoleByIdRequest]                   -> fetchRoleById,
    )
    end JobHandlersMap

    private def misingJobImplementationException(job: JobKind): Exception =
      new Exception(s"JobHandlersMap does not contain an implementation for class '${job.shortName}'.") with NoStackTrace
    end misingJobImplementationException

    def executeJob(job: JobKind): F[JobResult] =
      JobHandlersMap
        .get(job.getClass)
        .map(_(job))
        .getOrElse(async.raiseError(misingJobImplementationException(job)))
    end executeJob
  end JobExecutor

  private val SessionTimeoutDurationInSeconds: Long = 1.hour.toSeconds

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

    val numberOfWorkers = appConfig.getBackendServerConfig.getNumberOfWorkers
    val worker = createWorker(deps.serverState.jobQueue, jobExecutor)
    val supervisor = deps.supervisor

    Vector
      .from(0 until numberOfWorkers)
      .traverseVoid(_ => supervisor.supervise(worker))
  end createWorkers
end HttpWorker
