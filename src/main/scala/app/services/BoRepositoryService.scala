package app.services

import cats.data.NonEmptyVector

import java.time.Instant

import app.auth.Permissions.Permission
import app.model.AppModel.{BoRole, BoUserInDb}

enum CreateBoUserDbError:
  case DuplicateLoginName(loginName: String)
end CreateBoUserDbError

enum CreationBoRoleDbError:
  case DuplicateRoleName(roleName: String)
end CreationBoRoleDbError

enum DeleteBoRoleDbError:
  case NoSuchBoRole(roleId: Long)
  case RoleStillInUse(roleId: Long)
end DeleteBoRoleDbError

enum UpdateBoUserRolesDbError:
  case NoSuchUserId(userId: Long)
  case NoSuchRoleIds(roleIds: NonEmptyVector[Long])
end UpdateBoUserRolesDbError

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

  def fetchBoUserPermissions(userId: Long): F[Vector[Permission]]

  def createBoRole(roleName: String): F[Either[CreationBoRoleDbError, Long]]

  def fetchAllBoRoles: F[Vector[BoRole]]

  def fetchBoRoleByName(roleName: String): F[Vector[BoRole]]

  def fetchBoRoleById(roleId: Long): F[Vector[BoRole]]

  def deleteBoRoleById(roleId: Long): F[Either[DeleteBoRoleDbError, Unit]]

  def fetchBoRolePermissionsByName(roleName: String): F[Vector[Permission]]

  def fetchBoRolePermissionsById(roleId: Long): F[Vector[Permission]]

  def fetchAllBoPermissions: F[Vector[Permission]]

  def updateBoUserRolesById(userId: Long, roleIds: NonEmptyVector[Long]): F[Either[UpdateBoUserRolesDbError, Unit]]
end BoRepositoryService
