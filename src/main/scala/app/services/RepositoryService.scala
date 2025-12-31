package app.services

import cats.data.NonEmptyVector

import java.time.Instant

import app.entrypoints.smithy.{PermissionInDb, RoleInDb, UserInDb}
import doobie.ConnectionIO

enum CreateUserDbError:
  case UniquenessConstraintViolated(errMsg: String)
end CreateUserDbError

enum CreateRoleDbError:
  case DuplicateRoleName
end CreateRoleDbError

given CanEqual[CreateRoleDbError, CreateRoleDbError] = CanEqual.derived

enum UpdateUserRolesDbError:
  case NoSuchUserId(userId: Long)
  case NoSuchRoleIds(roleIds: NonEmptyVector[Long])
end UpdateUserRolesDbError

enum UpdateUserPasswordError:
  case NoSuchUserId(userId: Long)
end UpdateUserPasswordError

trait RepositoryService:
  def createUser(
      loginName: String,
      firstName: String,
      lastName: String,
      email: String,
      phone: String,
      creationTime: Instant,
      hashedPassword: String,
      mustResetPassword: Boolean,
      userPasswordUpdateTime: Instant,
      enabled: Boolean,
      creatingUserId: Long,
  ): ConnectionIO[Either[CreateUserDbError, Long]]

  def fetchUsersByLoginNames(loginNames: NonEmptyVector[String]): ConnectionIO[Vector[UserInDb]]

  def fetchUsersByUserIds(userIds: NonEmptyVector[Long]): ConnectionIO[Vector[UserInDb]]

  def fetchUserPermissions(userId: Long): ConnectionIO[Vector[PermissionInDb]]

  def createRole(roleName: String, createdBy: Long, creationTime: Instant): ConnectionIO[Either[CreateRoleDbError, Long]]

  def fetchAllRoles: ConnectionIO[Vector[RoleInDb]]

  def fetchRoleByName(roleName: String): ConnectionIO[Vector[RoleInDb]]

  def fetchRoleById(roleId: Long): ConnectionIO[Option[RoleInDb]]

  def deleteRoleById(roleId: Long): ConnectionIO[Int]

  def fetchRolePermissionsByName(roleName: String): ConnectionIO[Vector[PermissionInDb]]

  def fetchRolePermissionsById(roleId: Long): ConnectionIO[Vector[PermissionInDb]]

  def isRoleAssignedToUsers(roleId: Long): ConnectionIO[Boolean]

  def fetchAllUsersAssociatedWithRoles(roleIds: NonEmptyVector[Long]): ConnectionIO[Map[Long, Vector[UserInDb]]]

  def fetchAllPermissions: ConnectionIO[Vector[PermissionInDb]]

  def updateUserRolesById(userId: Long, roleIds: NonEmptyVector[Long]): ConnectionIO[Either[UpdateUserRolesDbError, Unit]]

  def updateUserPasswordInDb(userId: Long, hashedPassword: String): ConnectionIO[Int]

  def getResetUserPasswordTokenExpiry(hashedToken: String): ConnectionIO[Option[Instant]]

  def deleteExpiredResetUserPasswordTokens(now: Instant): ConnectionIO[Int]
end RepositoryService
