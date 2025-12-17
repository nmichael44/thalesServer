package app.services

import cats.data.NonEmptyVector

import java.time.Instant

import app.auth.Permissions.PermissionInDb
import app.entrypoints.smithy.BoRoleInDb
import app.model.AppModel.{BoRole, BoUserInDb}
import doobie.ConnectionIO

enum CreateBoUserDbError:
  case UniquenessConstraintViolated(errMsg: String)
end CreateBoUserDbError

enum CreateBoRoleDbError:
  case DuplicateRoleName(roleName: String)
end CreateBoRoleDbError

enum UpdateBoUserRolesDbError:
  case NoSuchUserId(userId: Long)
  case NoSuchRoleIds(roleIds: NonEmptyVector[Long])
end UpdateBoUserRolesDbError

enum UpdateBoUserPasswordError:
  case NoSuchUserId(userId: Long)
end UpdateBoUserPasswordError

trait BoRepositoryService:
  def createBoUser(
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
  ): ConnectionIO[Either[CreateBoUserDbError, Long]]

  def fetchBoUserByLoginName(loginName: String): ConnectionIO[Option[BoUserInDb]]

  def fetchBoUserById(userId: Long): ConnectionIO[Option[BoUserInDb]]

  def fetchMultipleBoUsersById(userIds: NonEmptyVector[Long]): ConnectionIO[Map[Long, BoUserInDb]]

  def fetchBoUserPermissions(userId: Long): ConnectionIO[Vector[PermissionInDb]]

  def createBoRole(roleName: String, createdBy: Long, creationTime: Instant): ConnectionIO[Either[CreateBoRoleDbError, Long]]

  def fetchAllBoRoles: ConnectionIO[Vector[BoRoleInDb]]

  def fetchBoRoleByName(roleName: String): ConnectionIO[Vector[BoRoleInDb]]

  def fetchBoRoleById(roleId: Long): ConnectionIO[Option[BoRoleInDb]]

  def deleteBoRoleById(roleId: Long): ConnectionIO[Int]

  def fetchBoRolePermissionsByName(roleName: String): ConnectionIO[Vector[PermissionInDb]]

  def fetchBoRolePermissionsById(roleId: Long): ConnectionIO[Vector[PermissionInDb]]

  def isRoleAssignedToUsers(roleId: Long): ConnectionIO[Boolean]

  def fetchAllUsersAssociatedWithRole(roleId: Long): ConnectionIO[Vector[BoUserInDb]]

  def fetchAllBoPermissions: ConnectionIO[Vector[PermissionInDb]]

  def updateBoUserRolesById(userId: Long, roleIds: NonEmptyVector[Long]): ConnectionIO[Either[UpdateBoUserRolesDbError, Unit]]

  def updateBoUserPasswordInDb(userId: Long, hashedPassword: String): ConnectionIO[Int]
end BoRepositoryService
