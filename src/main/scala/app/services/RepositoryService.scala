package app.services

import cats.data.NonEmptyVector

import java.time.Instant

import app.entrypoints.smithy.{HashedResetPasswordToken, HashedUserPassword, LoginName, PermissionId, PermissionInDb, RoleId, RoleInDb, RoleName, UserId, UserInDb}
import doobie.ConnectionIO

enum CreateUserDbError:
  case UniquenessConstraintViolated(errMsg: String)
end CreateUserDbError

enum CreateRoleDbError:
  case DuplicateRoleName
end CreateRoleDbError

given CanEqual[CreateRoleDbError, CreateRoleDbError] = CanEqual.derived

enum UpdateUserRolesByIdDbError:
  case NoSuchUserId
  case NoSuchRoleIds(roleIds: NonEmptyVector[RoleId])
end UpdateUserRolesByIdDbError

given CanEqual[UpdateUserRolesByIdDbError, UpdateUserRolesByIdDbError] = CanEqual.derived

enum UpdateUserPasswordError:
  case NoSuchUserId(userId: Long)
end UpdateUserPasswordError

trait RepositoryService:
  def createUser(
      loginName: LoginName,
      firstName: String,
      lastName: String,
      email: String,
      phone: String,
      creationTime: Instant,
      hashedPassword: HashedUserPassword,
      mustResetPassword: Boolean,
      userPasswordUpdateTime: Instant,
      enabled: Boolean,
      creatingUserId: UserId,
  ): ConnectionIO[Either[CreateUserDbError, UserId]]

  def fetchUsersByLoginNames(loginNames: NonEmptyVector[LoginName]): ConnectionIO[Map[LoginName, UserInDb]]

  def fetchUsersByUserIds(userIds: NonEmptyVector[UserId]): ConnectionIO[Map[UserId, UserInDb]]

  def fetchUserPermissions(userId: UserId): ConnectionIO[Vector[PermissionInDb]]

  def createRole(roleName: RoleName, createdBy: UserId, creationTime: Instant): ConnectionIO[Either[CreateRoleDbError, RoleId]]

  def fetchAllRoles: ConnectionIO[Vector[RoleInDb]]

  def fetchRoleByName(roleName: RoleName): ConnectionIO[Option[RoleInDb]]

  def fetchRolesByIds(roleIds: NonEmptyVector[RoleId]): ConnectionIO[Map[RoleId, RoleInDb]]

  def deleteRoleById(roleId: RoleId): ConnectionIO[Int]

  def fetchRolesPermissionsById(roleId: NonEmptyVector[RoleId]): ConnectionIO[Map[RoleId, Vector[PermissionInDb]]]

  def isRoleAssignedToUsers(roleId: RoleId): ConnectionIO[Boolean]

  def fetchAllUsersAssociatedWithRoles(roleIds: NonEmptyVector[RoleId]): ConnectionIO[Map[RoleId, Vector[UserInDb]]]

  def fetchAllPermissions: ConnectionIO[Map[PermissionId, PermissionInDb]]

  def fetchUserRoleIds(userIds: NonEmptyVector[UserId]): ConnectionIO[Map[UserId, Vector[RoleId]]]

  def updateUserRolesById(userId: UserId, roleIds: NonEmptyVector[RoleId]): ConnectionIO[Either[UpdateUserRolesByIdDbError, Unit]]

  def updateUserPasswordInDb(userId: UserId, hashedPassword: HashedUserPassword): ConnectionIO[Int]

  def setMustResetUserPassword(userId: UserId, mustResetPassword: Boolean): ConnectionIO[Int]

  def insertResetUserPasswordToken(
      hashedToken: HashedResetPasswordToken,
      userId: UserId,
      expirationTime: Instant,
  ): ConnectionIO[Unit]

  def getResetUserPasswordTokenExpiry(hashedToken: HashedResetPasswordToken): ConnectionIO[Option[(UserId, Instant)]]

  def deleteResetUserPasswordToken(hashedToken: HashedResetPasswordToken): ConnectionIO[Unit]

  def deleteExpiredResetUserPasswordTokens(now: Instant): ConnectionIO[Int]

  def deleteOldLoginFailedAttempts(now: Instant, minutes: Int): ConnectionIO[Int]

  def deleteFailedAttemptsForLoginName(loginName: LoginName): ConnectionIO[Unit]

  def fetchCountOfFailedAttempts(loginName: LoginName, now: Instant, minutes: Int): ConnectionIO[Int]

  def insertFailedAttempt(loginName: LoginName, now: Instant): ConnectionIO[Unit]
end RepositoryService
