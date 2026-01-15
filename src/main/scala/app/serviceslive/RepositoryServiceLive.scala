package app.serviceslive

import cats.data.NonEmptyVector
import cats.implicits.*

import java.sql.SQLException
import java.time.Instant

import app.ThalesUtils.ExtensionMethodUtils.*
import app.ThalesUtils.GenUtils
import app.ThalesUtils.GenUtils as U
import app.entrypoints.smithy.{HashedResetPasswordToken, HashedUserPassword, LoginName, PermissionId, PermissionInDb, PermissionName, RoleId, RoleInDb, RoleName, UserId, UserInDb, UserServices}
import app.model.given
import app.services.{CreateRoleDbError, CreateUserDbError, RepositoryService, UpdateUserRolesDbError}
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.syntax.all.toSqlInterpolator

private final class RepositoryServiceLive private extends RepositoryService:
  inline private val UniqueViolation = "23505"

  private def uniquenessViolated(sqlState: String): Boolean =
    sqlState == UniqueViolation
  end uniquenessViolated

  given Meta[UserId] = Meta[Long].imap(UserId.apply)(_.value)
  given Meta[LoginName] = Meta[String].imap(LoginName.apply)(_.value)
  given Meta[RoleId] = Meta[Long].imap(RoleId.apply)(_.value)
  given Meta[RoleName] = Meta[String].imap(RoleName.apply)(_.value)
  given Meta[PermissionId] = Meta[Long].imap(PermissionId.apply)(_.value)
  given Meta[HashedUserPassword] = Meta[String].imap(HashedUserPassword.apply)(_.value)
  given Meta[PermissionName] = Meta[String].imap(PermissionName.apply)(_.value)

  extension [A](obj: A)
    inline private def pureCon: ConnectionIO[A] =
      doobie.FC.pure(obj)
    end pureCon

  extension [K, V: Read](sql: Fragment)
    private def toIdxMap(fIdx: V => K): ConnectionIO[Map[K, V]] =
      sql
        .query[V]
        .stream
        .compile
        .fold(Map.empty[K, V])((m, e) => m.updated(fIdx(e), e))
    end toIdxMap

    private def toVec[A: Read]: ConnectionIO[Vector[A]] =
      sql.query[A].to[Vector]
    end toVec

    private def toOpt[A: Read]: ConnectionIO[Option[A]] =
      sql.query[A].option
    end toOpt

    private def toUnique[A: Read]: ConnectionIO[A] =
      sql.query[A].unique
    end toUnique
  end extension

  private def duplicateConstraintViolatedError(errMsg: String): ConnectionIO[Either[CreateUserDbError, UserId]] =
    Left(CreateUserDbError.UniquenessConstraintViolated(errMsg)).pureCon
  end duplicateConstraintViolatedError

  override def createUser(
      loginName: LoginName,
      firstName: String,
      lastName: String,
      email: String,
      phone: String,
      userCreationTime: Instant,
      hashedPassword: HashedUserPassword,
      mustResetPassword: Boolean,
      userPasswordUpdateTime: Instant,
      enabled: Boolean,
      creatingUserId: UserId,
  ): ConnectionIO[Either[CreateUserDbError, UserId]] =
    sql"""insert into Users (loginName, firstName, lastName, email, phone, userCreationTime, hashedPassword, mustResetPassword, userPasswordUpdateTime, enabled, creatingUserId)
          values (${loginName.value}, $firstName, $lastName, $email, $phone, $userCreationTime, ${hashedPassword.value}, $mustResetPassword, $userPasswordUpdateTime, $enabled, ${creatingUserId.value})""".update
      .withUniqueGeneratedKeys[Long]("userid")
      .attempt
      .flatMap {
        case Right(userId) => Right(UserId(userId)).pureCon
        case Left(e: SQLException) if uniquenessViolated(e.getSQLState) => duplicateConstraintViolatedError(e.getMessage)
        case Left(e) => doobie.FC.raiseError(e)
      }
  end createUser

  override def fetchUsersByLoginNames(loginNames: NonEmptyVector[LoginName]): ConnectionIO[Map[LoginName, UserInDb]] =
    val namesVec = loginNames.view.map(_.value).toVector

    sql"select userId, loginName, firstName, lastName, email, phone, userCreationTime, hashedPassword, mustResetPassword, userPasswordUpdateTime, enabled, creatingUserId from Users where loginName = ANY($namesVec)"
      .toIdxMap(_.loginName)
  end fetchUsersByLoginNames

  override def fetchUsersByUserIds(userIds: NonEmptyVector[UserId]): ConnectionIO[Map[UserId, UserInDb]] =
    val userIdsVec = userIds.view.map(_.value).toVector

    sql"select userId, loginName, firstName, lastName, email, phone, userCreationTime, hashedPassword, mustResetPassword, userPasswordUpdateTime, enabled, creatingUserId from Users where userId = ANY($userIdsVec)"
      .toIdxMap(_.userId)
  end fetchUsersByUserIds

  override def fetchUserPermissions(userId: UserId): ConnectionIO[Vector[PermissionInDb]] =
    import app.auth.Permissions.given

    sql"""select bp.permissionId, bp.permissionName from UserRoles ur
          join RolePermissions rp on ur.roleId = rp.roleId
          join Permissions bp on rp.permissionId = bp.permissionId
          where ur.userId = ${userId.value}""".toVec
  end fetchUserPermissions

  override def createRole(
      roleName: RoleName,
      createdBy: UserId,
      creationTime: Instant,
  ): ConnectionIO[Either[CreateRoleDbError, RoleId]] =
    sql"insert into Roles (roleName, createdBy, creationTime) values(${roleName.value}, ${createdBy.value}, $creationTime)".update
      .withUniqueGeneratedKeys[Long]("roleId")
      .attempt
      .flatMap {
        case Right(roleId) =>
          Right(RoleId(roleId)).pureCon
        case Left(e: SQLException) if uniquenessViolated(e.getSQLState) =>
          Left(CreateRoleDbError.DuplicateRoleName).pureCon
        case Left(e) =>
          doobie.FC.raiseError(e)
      }
  end createRole

  override val fetchAllRoles: ConnectionIO[Vector[RoleInDb]] =
    sql"select roleId, roleName, createdBy, creationTime from Roles order by roleId".toVec
  end fetchAllRoles

  override def fetchRoleByName(roleName: RoleName): ConnectionIO[Option[RoleInDb]] =
    sql"select roleId, roleName, createdBy, creationTime from Roles where roleName = ${roleName.value}".toOpt
  end fetchRoleByName

  override def fetchRolesByIds(roleIds: NonEmptyVector[RoleId]): ConnectionIO[Map[RoleId, RoleInDb]] =
    val roleIdsVec = roleIds.view.map(_.value).toVector

    sql"select roleId, roleName, createdBy, creationTime from Roles where roleId = ANY($roleIdsVec)"
      .toIdxMap(_.roleId)
  end fetchRolesByIds

  // Here we assume the role is not assigned to users.  If it still is, this command will fail.
  // The caller can use the isRoleAssignedToUsers() function to establish that not such association is there.
  override def deleteRoleById(roleId: RoleId): ConnectionIO[Int] = {
    val roleIdLong = roleId.value
    for {
      _ <- sql"delete from RolePermissions where roleId = $roleIdLong".update.run
      rowsDeleted <- sql"delete from Roles where roleId = $roleIdLong".update.run
    } yield rowsDeleted
  }
  end deleteRoleById

  override def fetchRolesPermissionsById(roleIds: NonEmptyVector[RoleId]): ConnectionIO[Map[RoleId, Vector[PermissionInDb]]] =
    val roleIdsVec = roleIds.view.map(_.value).toVector
    val sql = sql"""SELECT rp.roleId, p.permissionId, p.permissionName
                    FROM RolePermissions rp
                    JOIN Permissions p ON rp.permissionId = p.permissionId
                    WHERE rp.roleId = ANY($roleIdsVec)"""

    sql
      .query[(RoleId, PermissionInDb)]
      .to[Vector]
      .map { rows =>
        val found = rows.groupMap(_._1)(_._2)

        if found.size == roleIds.size then found
        else roleIds.view.map(U.mapToSecond(found.getOrElse(_, Vector.empty))).toMap
      }
  end fetchRolesPermissionsById

  def isRoleAssignedToUsers(roleId: RoleId): ConnectionIO[Boolean] =
    sql"""select exists (select 1 from UserRoles where roleId = ${roleId.value})""".toUnique
  end isRoleAssignedToUsers

  def fetchAllUsersAssociatedWithRoles(roleIds: NonEmptyVector[RoleId]): ConnectionIO[Map[RoleId, Vector[UserInDb]]] =
    val roleIdsVec = roleIds.view.map(_.value).toVector

    sql"""select ur.roleId, u.userId, u.loginName, u.firstName, u.lastName, u.email, u.phone,
                 u.userCreationTime, u.hashedPassword, u.mustResetPassword,
                 u.userPasswordUpdateTime, u.enabled, u.creatingUserId
          from Users u
          join UserRoles ur on u.userId = ur.userId
          where ur.roleId = ANY($roleIdsVec)"""
      .query[(Long, UserInDb)]
      .stream
      .compile
      .fold(Map.empty[RoleId, Vector[UserInDb]]) { case (m, (roleId, user)) =>
        m.updatedWith(RoleId(roleId)) { usersOpt =>
          Some {
            usersOpt match {
              case Some(users) => users :+ user
              case None => Vector(user)
            }
          }
        }
      }
  end fetchAllUsersAssociatedWithRoles

  override val fetchAllPermissions: ConnectionIO[Map[PermissionId, PermissionInDb]] =
    sql"select permissionId, permissionName from Permissions order by permissionId"
      .toIdxMap(_.permissionId)
  end fetchAllPermissions

  override def updateUserRolesById(
      userId: UserId,
      roleIds: NonEmptyVector[RoleId],
  ): ConnectionIO[Either[UpdateUserRolesDbError, Unit]] = {
    val roleIdsVec = roleIds.view.map(_.value).toVector

    for {
      userExistsCount <- sql"select count(*) from Users where userId = ${userId.value}".query[Int].unique

      result <-
        if userExistsCount == 0
        then Left(UpdateUserRolesDbError.NoSuchUserId).pureCon
        else
          val findValidRolesQuery = sql"""select roleId from Roles where roleId = ANY($roleIdsVec)"""
          for {
            validRoleIdsSet <- findValidRolesQuery.query[Long].to[Set]
            invalidRoleIds = roleIdsVec.view.filterNot(n => validRoleIdsSet.contains(n)).toVector

            res <-
              if invalidRoleIds.nonEmpty
              then
                val invalidRoleIdsNonEmptyVec = NonEmptyVector.fromVectorUnsafe(invalidRoleIds)
                Left(UpdateUserRolesDbError.NoSuchRoleIds(invalidRoleIdsNonEmptyVec)).pureCon
              else
                val insertSql = "insert into UserRoles (userId, roleId) values (?, ?)"
                val userIdLong = userId.value
                val dataToInsert = roleIdsVec.map((userIdLong, _))

                for {
                  _ <- sql"delete from UserRoles where userId = $userIdLong".update.run
                  _ <- doobie.Update[(Long, Long)](insertSql).updateMany(dataToInsert)
                } yield Right(())
          } yield res
    } yield result
  }
  end updateUserRolesById

  override def updateUserPasswordInDb(userId: UserId, hashedPassword: HashedUserPassword): ConnectionIO[Int] =
    sql"update Users set hashedPassword = ${hashedPassword.value}, mustResetPassword = false where userId = ${userId.value}".update.run
  end updateUserPasswordInDb

  override def insertResetUserPasswordToken(
      hashedToken: HashedResetPasswordToken,
      userId: UserId,
      expirationTime: Instant,
  ): ConnectionIO[Unit] =
    sql"""insert into ResetUserPasswordTokens (hashedToken, userId, expirationTime)
          values (${hashedToken.value}, ${userId.value}, $expirationTime)""".update.run.void
  end insertResetUserPasswordToken

  override def getResetUserPasswordTokenExpiry(hashedToken: HashedResetPasswordToken): ConnectionIO[Option[(UserId, Instant)]] =
    sql"select userId, expirationTime from ResetUserPasswordTokens where hashedToken = ${hashedToken.value}".toOpt
  end getResetUserPasswordTokenExpiry

  override def deleteResetUserPasswordToken(hashedToken: HashedResetPasswordToken): ConnectionIO[Unit] =
    sql"delete from ResetUserPasswordTokens where hashedToken = ${hashedToken.value}".update.run.void
  end deleteResetUserPasswordToken

  override def deleteExpiredResetUserPasswordTokens(now: Instant): ConnectionIO[Int] =
    sql"delete from ResetUserPasswordTokens where expirationTime < $now".update.run
  end deleteExpiredResetUserPasswordTokens
end RepositoryServiceLive

object RepositoryServiceLive:
  def create: RepositoryService =
    new RepositoryServiceLive
  end create
end RepositoryServiceLive
