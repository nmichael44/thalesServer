package app.serviceslive

import cats.data.NonEmptyVector
import cats.implicits.*

import java.sql.SQLException
import java.time.Instant

import app.ThalesUtils.ExtensionMethodUtils.*
import app.entrypoints.smithy.{PermissionInDb, RoleInDb}
import app.entrypoints.smithy.UserInDb
import app.model.given
import app.services.{CreateRoleDbError, CreateUserDbError, RepositoryService, UpdateUserRolesDbError}
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.syntax.all.toSqlInterpolator
import doobie.util.fragments

private final class RepositoryServiceLive private extends RepositoryService:
  inline private val UniqueViolation = "23505"

  private def uniquenessViolated(sqlState: String): Boolean =
    sqlState == UniqueViolation
  end uniquenessViolated

  extension [A](obj: A)
    inline private def pureCon: ConnectionIO[A] =
      obj.pure[ConnectionIO]
    end pureCon

  private def duplicateConstraintViolatedError(errMsg: String): ConnectionIO[Either[CreateUserDbError, Long]] =
    Left(CreateUserDbError.UniquenessConstraintViolated(errMsg)).pureCon
  end duplicateConstraintViolatedError

  override def createUser(
      loginName: String,
      firstName: String,
      lastName: String,
      email: String,
      phone: String,
      userCreationTime: Instant,
      hashedPassword: String,
      mustResetPassword: Boolean,
      userPasswordUpdateTime: Instant,
      enabled: Boolean,
      creatingUserId: Long,
  ): ConnectionIO[Either[CreateUserDbError, Long]] =
    val userCreationTs = java.sql.Timestamp.from(userCreationTime)

    sql"""insert into Users (loginName, firstName, lastName, email, phone, userCreationTime, hashedPassword, mustResetPassword, userPasswordUpdateTime, enabled, creatingUserId)
          values ($loginName, $firstName, $lastName, $email, $phone, $userCreationTs, $hashedPassword, $mustResetPassword, $userPasswordUpdateTime, $enabled, $creatingUserId)""".update
      .withUniqueGeneratedKeys[Long]("userId")
      .attempt
      .flatMap {
        case Right(userId) => Right(userId).pureCon
        case Left(e: SQLException) if uniquenessViolated(e.getSQLState) => duplicateConstraintViolatedError(e.getMessage)
        case Left(e) => doobie.FC.raiseError(e)
      }
  end createUser

  override def fetchUsersByLoginNames(loginNames: NonEmptyVector[String]): ConnectionIO[Vector[UserInDb]] =
    val namesVec = loginNames.toVector

    sql"""select userId, loginName, firstName, lastName, email, phone, userCreationTime, hashedPassword, mustResetPassword, userPasswordUpdateTime, enabled, creatingUserId from Users where loginName = ANY($namesVec)"""
      .query[UserInDb]
      .to[Vector]
  end fetchUsersByLoginNames

  override def fetchUsersByUserIds(userIds: NonEmptyVector[Long]): ConnectionIO[Vector[UserInDb]] = {
    val userIdsVec = userIds.toVector

    sql"""select userId, loginName, firstName, lastName, email, phone, userCreationTime, hashedPassword, mustResetPassword, userPasswordUpdateTime, enabled, creatingUserId from Users where userId = ANY($userIdsVec)"""
      .query[UserInDb]
      .to[Vector]
  }
  end fetchUsersByUserIds

  override def fetchUserPermissions(userId: Long): ConnectionIO[Vector[PermissionInDb]] =
    import app.auth.Permissions.given

    sql"""select bp.permissionId, bp.permissionName from UserRoles ur
          join RolePermissions rp on ur.roleId = rp.roleId
          join Permissions bp on rp.permissionId = bp.permissionId
          where ur.userId = $userId"""
      .query[PermissionInDb]
      .to[Vector]
  end fetchUserPermissions

  private def duplicateRoleNameError(roleName: String): ConnectionIO[Either[CreateRoleDbError, Long]] =
    Left(CreateRoleDbError.DuplicateRoleName).pureCon
  end duplicateRoleNameError

  override def createRole(
      roleName: String,
      createdBy: Long,
      creationTime: Instant,
  ): ConnectionIO[Either[CreateRoleDbError, Long]] =
    val creationTimeTs = java.sql.Timestamp.from(creationTime)

    sql"""insert into Roles (roleName, createdBy, creationTime) values($roleName, $createdBy, $creationTimeTs)""".update
      .withUniqueGeneratedKeys[Long]("roleId")
      .attempt
      .flatMap {
        case Right(roleId) => Right(roleId).pureCon
        case Left(e: SQLException) if uniquenessViolated(e.getSQLState) => duplicateRoleNameError(roleName)
        case Left(e) => doobie.FC.raiseError(e)
      }
  end createRole

  override val fetchAllRoles: ConnectionIO[Vector[RoleInDb]] =
    sql"""select roleId, roleName, createdBy, creationTime from Roles order by roleId"""
      .query[RoleInDb]
      .to[Vector]
  end fetchAllRoles

  override def fetchRoleByName(roleName: String): ConnectionIO[Vector[RoleInDb]] =
    sql"""select roleId, roleName, createdBy, creationTime from Roles where roleName = $roleName"""
      .query[RoleInDb]
      .to[Vector]
  end fetchRoleByName

  override def fetchRolesByIds(roleIds: NonEmptyVector[Long]): ConnectionIO[Map[Long, RoleInDb]] = {
    val roleIdsVec = roleIds.toVector

    sql"""select roleid, roleId, roleName, createdBy, creationTime from Roles where roleId = ANY($roleIdsVec)"""
      .query[(Long, RoleInDb)]
      .toMap
  }
  end fetchRolesByIds

  // Here we assume the role is not assigned to users.  If it still is, this command will fail.
  // The caller can use the isRoleAssignedToUsers() function to establish that not such association is there.
  override def deleteRoleById(roleId: Long): ConnectionIO[Int] =
    for {
      _ <- sql"delete from RolePermissions where roleId = $roleId".update.run
      rowsDeleted <- sql"delete from Roles where roleId = $roleId".update.run
    } yield rowsDeleted
  end deleteRoleById

  override def fetchRolePermissionsByName(roleName: String): ConnectionIO[Vector[PermissionInDb]] =
    sql"""select p.permissionId, p.permissionName from Roles as rl
          join RolePermissions as rp on rl.roleId = rp.roleId
          join Permissions as p on rp.permissionId = p.permissionId
          where rl.roleName = $roleName"""
      .query[PermissionInDb]
      .to[Vector]
  end fetchRolePermissionsByName

  override def fetchRolePermissionsById(roleId: Long): ConnectionIO[Vector[PermissionInDb]] =
    sql"""select p.permissionId, p.permissionName from RolePermissions as rp
          join Permissions as p on rp.permissionId = p.permissionId
          where rp.roleId = $roleId"""
      .query[PermissionInDb]
      .to[Vector]
  end fetchRolePermissionsById

  def isRoleAssignedToUsers(roleId: Long): ConnectionIO[Boolean] =
    sql"""select case
          when exists (select 1 from UserRoles where roleId = $roleId)
          then cast(1 as bit) else cast(0 as bit)
          end"""
      .query[Boolean]
      .unique
  end isRoleAssignedToUsers

  def fetchAllUsersAssociatedWithRoles(roleIds: NonEmptyVector[Long]): ConnectionIO[Map[Long, Vector[UserInDb]]] =
    val roleIdsVec = roleIds.toVector

    sql"""select ur.roleId, u.userId, u.loginName, u.firstName, u.lastName, u.email, u.phone,
                 u.userCreationTime, u.hashedPassword, u.mustResetPassword,
                 u.userPasswordUpdateTime, u.enabled, u.creatingUserId
          from Users u
          join UserRoles ur on u.userId = ur.userId
          where ur.roleId = ANY ($roleIdsVec)"""
      .query[(Long, UserInDb)]
      .stream
      .compile
      .fold(Map.empty[Long, Vector[UserInDb]]) { case (m, (roleId, user)) =>
        m.updatedWith(roleId) { usersOpt =>
          Some {
            usersOpt match {
              case Some(users) => users :+ user
              case None => Vector(user)
            }
          }
        }
      }
  end fetchAllUsersAssociatedWithRoles

  override val fetchAllPermissions: ConnectionIO[Vector[PermissionInDb]] =
    sql"""select permissionId, permissionName from Permissions order by permissionId"""
      .query[PermissionInDb]
      .to[Vector]
  end fetchAllPermissions

  override def updateUserRolesById(
      userId: Long,
      roleIds: NonEmptyVector[Long],
  ): ConnectionIO[Either[UpdateUserRolesDbError, Unit]] =
    for {
      userExistsCount <- sql"select count(*) from Users where userId = $userId".query[Int].unique

      result <-
        if userExistsCount == 0
        then Left(UpdateUserRolesDbError.NoSuchUserId(userId)).pureCon
        else
          val findValidRolesQuery = fr"select roleId from Roles where" ++ fragments.in(fr"roleId", roleIds)
          for {
            validRoleIdsSet <- findValidRolesQuery.query[Long].to[Set]
            invalidRoleIds = roleIds.view.filterNot(n => validRoleIdsSet.contains(n)).toVector

            res <-
              if invalidRoleIds.nonEmpty
              then
                val invalidRoleIdsNonEmptyVec = NonEmptyVector.fromVectorUnsafe(invalidRoleIds)
                Left(UpdateUserRolesDbError.NoSuchRoleIds(invalidRoleIdsNonEmptyVec)).pureCon
              else
                val insertSql = "insert into UserRoles (userId, roleId) values (?, ?)"
                val dataToInsert = roleIds.toVector.map((userId, _))

                for {
                  _ <- sql"delete from UserRoles where userId = $userId".update.run
                  _ <- doobie.Update[(Long, Long)](insertSql).updateMany(dataToInsert)
                } yield Right(())
          } yield res
    } yield result
  end updateUserRolesById

  override def updateUserPasswordInDb(userId: Long, hashedPassword: String): ConnectionIO[Int] =
    sql"update Users set hashedPassword = $hashedPassword, mustResetPassword = 0 where userId = $userId".update.run
  end updateUserPasswordInDb

  override def getResetUserPasswordTokenExpiry(hashedToken: String): ConnectionIO[Option[Instant]] =
    sql"""select expirationTime from ResetUserPasswordTokens where hashedToken = $hashedToken"""
      .query[Instant]
      .option
  end getResetUserPasswordTokenExpiry

  override def deleteExpiredResetUserPasswordTokens(now: Instant): ConnectionIO[Int] =
    sql"""delete from ResetUserPasswordTokens where expirationTime < $now""".update.run
  end deleteExpiredResetUserPasswordTokens
end RepositoryServiceLive

object RepositoryServiceLive:
  def create: RepositoryService =
    new RepositoryServiceLive
  end create
end RepositoryServiceLive
