package app.services

import cats.data.NonEmptyVector

import java.time.Instant

import app.auth.Permissions.PermissionInDb
import app.model.AppModel.{BoRole, BoRoleInDb, BoUserInDb}

enum CreateBoUserDbError:
  case DuplicateLoginName(loginName: String)
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

trait BoRepositoryService[F[_]]:
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
  ): F[Either[CreateBoUserDbError, Long]]

  def fetchBoUserByLoginName(loginName: String): F[Option[BoUserInDb]]

  def fetchBoUserById(userId: Long): F[Option[BoUserInDb]]

  def fetchMultipleBoUsersById(userIds: NonEmptyVector[Long]): F[Map[Long, BoUserInDb]]

  def fetchBoUserPermissions(userId: Long): F[Vector[PermissionInDb]]

  def createBoRole(roleName: String, createdBy: Long, creationTime: Instant): F[Either[CreateBoRoleDbError, Long]]

  def fetchAllBoRoles: F[Vector[BoRoleInDb]]

  def fetchBoRoleByName(roleName: String): F[Vector[BoRoleInDb]]

  def fetchBoRoleById(roleId: Long): F[Vector[BoRoleInDb]]

  def deleteBoRoleById(roleId: Long): F[Int]

  def fetchBoRolePermissionsByName(roleName: String): F[Vector[PermissionInDb]]

  def fetchBoRolePermissionsById(roleId: Long): F[Vector[PermissionInDb]]

  def isRoleAssignedToUsers(roleId: Long): F[Boolean]

  def fetchBoUsersThatHaveRole(roleId: Long): F[Vector[BoUserInDb]]

  def fetchAllBoPermissions: F[Vector[PermissionInDb]]

  def updateBoUserRolesById(userId: Long, roleIds: NonEmptyVector[Long]): F[Either[UpdateBoUserRolesDbError, Unit]]

  def updateBoUserPasswordInDb(userId: Long, hashedPassword: String): F[Int]
end BoRepositoryService
