package app.serviceslive

import cats.data.NonEmptyVector
import cats.effect.Async
import cats.implicits.*

import java.sql.SQLException
import java.time.Instant

import app.auth.Permissions.PermissionInDb
import app.entrypoints.smithy.RoleInDb
import app.model.AppModel.UserInDb
import app.model.given
import app.services.{CreateRoleDbError, CreateUserDbError, RepositoryService, UpdateUserRolesDbError}
import app.ThalesUtils.ExtensionMethodUtils.*
import doobie.*
import doobie.implicits.*
import doobie.implicits.javatimedrivernative.*
import doobie.syntax.all.toSqlInterpolator
import doobie.util.fragments
import io.circe.syntax.*

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
  ): ConnectionIO[Either[CreateUserDbError, Long]] =
    val userCreationTs = java.sql.Timestamp.from(userCreationTime)

    sql"""insert into neo.dbo.boUsers (loginName, firstName, lastName, email, phone, userCreationTime, hashedPassword, mustResetPassword, userPasswordUpdateTime, enabled)
          values ($loginName, $firstName, $lastName, $email, $phone, $userCreationTs, $hashedPassword, $mustResetPassword, $userPasswordUpdateTime, $enabled)""".update
      .withUniqueGeneratedKeys[Long]("userId")
      .attempt
      .flatMap {
        case Right(userId) => Right(userId).pureCon
        case Left(e: SQLException) if uniquenessViolated(e.getSQLState) => duplicateConstraintViolatedError(e.getMessage)
        case Left(e) => doobie.FC.raiseError(e)
      }
  end createUser

  override def fetchUserByLoginName(loginName: String): ConnectionIO[Option[UserInDb]] =
    sql"""select userId, loginName, firstName, lastName, email, phone, userCreationTime, hashedPassword, mustResetPassword, userPasswordUpdateTime, enabled from neo.dbo.boUsers where loginName = $loginName"""
      .query[UserInDb]
      .option
  end fetchUserByLoginName

  override def fetchUserById(userId: Long): ConnectionIO[Option[UserInDb]] =
    sql"""select userId, loginName, firstName, lastName, email, phone, userCreationTime, hashedPassword, mustResetPassword, userPasswordUpdateTime, enabled from neo.dbo.boUsers where userId = $userId"""
      .query[UserInDb]
      .option
  end fetchUserById

  override def fetchMultipleUsersById(userIds: NonEmptyVector[Long]): ConnectionIO[Map[Long, UserInDb]] =
    val userIdsJson = userIds.toVector.asJson.noSpaces
    val e = Map.empty[Long, UserInDb]

    sql"""select u.userId, u.loginName, u.firstName, u.lastName, u.email, u.phone,
                 u.userCreationTime, u.hashedPassword, u.mustResetPassword,
                 u.userPasswordUpdateTime, u.enabled
          from
            neo.dbo.boUsers u
          inner join
            OPENJSON($userIdsJson) WITH (id BIGINT '$$') AS ids ON u.userId = ids.id
       """
      .query[UserInDb]
      .stream
      .fold(e)((m, u) => m.updated(u.userId, u))
      .compile
      .lastOrError
  end fetchMultipleUsersById

  override def fetchUserPermissions(userId: Long): ConnectionIO[Vector[PermissionInDb]] =
    import app.auth.Permissions.given

    sql"""select bp.permissionId, bp.permissionName from neo.dbo.BoUserRoles ur
          join neo.dbo.BoRolePermissions rp on ur.roleId = rp.roleId
          join neo.dbo.BoPermissions bp on rp.permissionId = bp.permissionId
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

    sql"""insert into neo.dbo.BoRoles (roleName, createdBy, creationTime) values($roleName, $createdBy, $creationTimeTs)""".update
      .withUniqueGeneratedKeys[Long]("roleId")
      .attempt
      .flatMap {
        case Right(roleId) => Right(roleId).pureCon
        case Left(e: SQLException) if uniquenessViolated(e.getSQLState) => duplicateRoleNameError(roleName)
        case Left(e) => doobie.FC.raiseError(e)
      }
  end createRole

  override val fetchAllRoles: ConnectionIO[Vector[RoleInDb]] =
    sql"""select roleId, roleName, createdBy, creationTime from neo.dbo.BoRoles order by roleId"""
      .query[RoleInDb]
      .to[Vector]
  end fetchAllRoles

  override def fetchRoleByName(roleName: String): ConnectionIO[Vector[RoleInDb]] =
    sql"""select roleId, roleName, createdBy, creationTime from neo.dbo.BoRoles where roleName = $roleName"""
      .query[RoleInDb]
      .to[Vector]
  end fetchRoleByName

  override def fetchRoleById(roleId: Long): ConnectionIO[Option[RoleInDb]] =
    sql"""select roleId, roleName, createdBy, creationTime from neo.dbo.BoRoles where roleId = $roleId"""
      .query[RoleInDb]
      .option
  end fetchRoleById

  // Here we assume the role is not assigned to users.  If it still is, this command will fail.
  // The caller can use the isRoleAssignedToUsers() function to establish that not such association is there.
  override def deleteRoleById(roleId: Long): ConnectionIO[Int] =
    for {
      _ <- sql"delete from neo.dbo.BoRolePermissions where roleId = $roleId".update.run
      rowsDeleted <- sql"delete from neo.dbo.BoRoles where roleId = $roleId".update.run
    } yield rowsDeleted
  end deleteRoleById

  override def fetchRolePermissionsByName(roleName: String): ConnectionIO[Vector[PermissionInDb]] =
    sql"""select p.permissionId, p.permissionName from neo.dbo.BoRoles as rl
          join neo.dbo.BoRolePermissions as rp on rl.roleId = rp.roleId
          join neo.dbo.BoPermissions as p on rp.permissionId = p.permissionId
          where rl.roleName = $roleName"""
      .query[PermissionInDb]
      .to[Vector]
  end fetchRolePermissionsByName

  override def fetchRolePermissionsById(roleId: Long): ConnectionIO[Vector[PermissionInDb]] =
    sql"""select p.permissionId, p.permissionName from neo.dbo.BoRolePermissions as rp
          join neo.dbo.BoPermissions as p on rp.permissionId = p.permissionId
          where rp.roleId = $roleId"""
      .query[PermissionInDb]
      .to[Vector]
  end fetchRolePermissionsById

  def isRoleAssignedToUsers(roleId: Long): ConnectionIO[Boolean] =
    sql"""select case
          when exists (select 1 from neo.dbo.BoUserRoles where roleId = $roleId)
          then cast(1 as bit) else cast(0 as bit)
          end"""
      .query[Boolean]
      .unique
  end isRoleAssignedToUsers

  def fetchAllUsersAssociatedWithRole(roleId: Long): ConnectionIO[Vector[UserInDb]] =
    sql"""select u.userId, u.loginName, u.firstName, u.lastName, u.email, u.phone,
                 u.userCreationTime, u.hashedPassword, u.mustResetPassword,
                 u.userPasswordUpdateTime, u.enabled
          from neo.dbo.boUsers u
          join neo.dbo.BoUserRoles ur on u.userId = ur.userId
          where ur.roleId = $roleId"""
      .query[UserInDb]
      .to[Vector]
  end fetchAllUsersAssociatedWithRole

  override val fetchAllPermissions: ConnectionIO[Vector[PermissionInDb]] =
    sql"""select permissionId, permissionName from neo.dbo.BoPermissions order by permissionId"""
      .query[PermissionInDb]
      .to[Vector]
  end fetchAllPermissions

  override def updateUserRolesById(
      userId: Long,
      roleIds: NonEmptyVector[Long],
  ): ConnectionIO[Either[UpdateUserRolesDbError, Unit]] =
    for {
      userExistsCount <- sql"select count(*) from neo.dbo.BoUsers where userId = $userId".query[Int].unique

      result <-
        if userExistsCount == 0
        then Left(UpdateUserRolesDbError.NoSuchUserId(userId)).pureCon
        else
          val findValidRolesQuery = fr"select roleId from neo.dbo.BoRoles where" ++ fragments.in(fr"roleId", roleIds)
          for {
            validRoleIdsSet <- findValidRolesQuery.query[Long].to[Set]
            invalidRoleIds = roleIds.view.filterNot(n => validRoleIdsSet.contains(n)).toVector

            res <-
              if invalidRoleIds.nonEmpty
              then
                val invalidRoleIdsNonEmptyVec = NonEmptyVector.fromVectorUnsafe(invalidRoleIds)
                Left(UpdateUserRolesDbError.NoSuchRoleIds(invalidRoleIdsNonEmptyVec)).pureCon
              else
                val insertSql = "insert into neo.dbo.BoUserRoles (userId, roleId) values (?, ?)"
                val dataToInsert = roleIds.toVector.map((userId, _))

                for {
                  _ <- sql"delete from neo.dbo.BoUserRoles where userId = $userId".update.run
                  _ <- doobie.Update[(Long, Long)](insertSql).updateMany(dataToInsert)
                } yield Right(())
          } yield res
    } yield result
  end updateUserRolesById

  override def updateUserPasswordInDb(userId: Long, hashedPassword: String): ConnectionIO[Int] =
    sql"update neo.dbo.BoUsers set hashedPassword = $hashedPassword, mustResetPassword = 0 where userId = $userId".update.run
  end updateUserPasswordInDb
end RepositoryServiceLive

object RepositoryServiceLive:
  def create[F[_]: Async]: RepositoryService =
    new RepositoryServiceLive
  end create
end RepositoryServiceLive
