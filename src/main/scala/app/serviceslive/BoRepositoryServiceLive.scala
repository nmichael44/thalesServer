package app.serviceslive

import cats.data.NonEmptyVector
import cats.effect.Async
import cats.implicits.*

import java.time.Instant
import java.sql.SQLException

import app.auth.Permissions.PermissionInDb
import app.entrypoints.smithy.BoRoleInDb
import app.model.AppModel.BoUserInDb
import app.services.{BoRepositoryService, CreateBoRoleDbError, CreateBoUserDbError, UpdateBoUserRolesDbError}
import app.ThalesUtils.ExtensionMethodUtils.*
import doobie.*
import doobie.implicits.*
import doobie.implicits.javatimedrivernative.*
import doobie.syntax.all.toSqlInterpolator
import doobie.util.fragments
import io.circe.syntax.*

private final class BoRepositoryServiceLive private extends BoRepositoryService:
  inline private val UniqueViolation = "23505"

  private def uniquenessViolated(sqlState: String): Boolean =
    sqlState == UniqueViolation
  end uniquenessViolated

  extension [A](obj: A)
    inline private def pureCon: ConnectionIO[A] =
      obj.pure[ConnectionIO]
    end pureCon

  private def duplicateConstraintViolatedError(errMsg: String): ConnectionIO[Either[CreateBoUserDbError, Long]] =
    Left(CreateBoUserDbError.UniquenessConstraintViolated(errMsg)).pureCon
  end duplicateConstraintViolatedError

  override def createBoUser(
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
  ): ConnectionIO[Either[CreateBoUserDbError, Long]] =
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
  end createBoUser

  override def fetchBoUserByLoginName(loginName: String): ConnectionIO[Option[BoUserInDb]] =
    sql"""select userId, loginName, firstName, lastName, email, phone, userCreationTime, hashedPassword, mustResetPassword, userPasswordUpdateTime, enabled from neo.dbo.boUsers where loginName = $loginName"""
      .query[BoUserInDb]
      .option
  end fetchBoUserByLoginName

  override def fetchBoUserById(userId: Long): ConnectionIO[Option[BoUserInDb]] =
    sql"""select userId, loginName, firstName, lastName, email, phone, userCreationTime, hashedPassword, mustResetPassword, userPasswordUpdateTime, enabled from neo.dbo.boUsers where userId = $userId"""
      .query[BoUserInDb]
      .option
  end fetchBoUserById

  override def fetchMultipleBoUsersById(userIds: NonEmptyVector[Long]): ConnectionIO[Map[Long, BoUserInDb]] =
    val userIdsJson = userIds.toVector.asJson.noSpaces
    val e = Map.empty[Long, BoUserInDb]

    sql"""select u.userId, u.loginName, u.firstName, u.lastName, u.email, u.phone,
                 u.userCreationTime, u.hashedPassword, u.mustResetPassword,
                 u.userPasswordUpdateTime, u.enabled
          from
            neo.dbo.boUsers u
          inner join
            OPENJSON($userIdsJson) WITH (id BIGINT '$$') AS ids ON u.userId = ids.id
       """
      .query[BoUserInDb]
      .stream
      .fold(e)((m, u) => m.updated(u.userId, u))
      .compile
      .lastOrError
  end fetchMultipleBoUsersById

  override def fetchBoUserPermissions(userId: Long): ConnectionIO[Vector[PermissionInDb]] =
    import app.auth.Permissions.given

    sql"""select bp.permissionId, bp.permissionName from neo.dbo.BoUserRoles ur
          join neo.dbo.BoRolePermissions rp on ur.roleId = rp.roleId
          join neo.dbo.BoPermissions bp on rp.permissionId = bp.permissionId
          where ur.userId = $userId"""
      .query[PermissionInDb]
      .to[Vector]
  end fetchBoUserPermissions

  private def duplicateRoleNameError(roleName: String): ConnectionIO[Either[CreateBoRoleDbError, Long]] =
    Left(CreateBoRoleDbError.DuplicateRoleName(roleName)).pureCon
  end duplicateRoleNameError

  override def createBoRole(
      roleName: String,
      createdBy: Long,
      creationTime: Instant,
  ): ConnectionIO[Either[CreateBoRoleDbError, Long]] =
    val creationTimeTs = java.sql.Timestamp.from(creationTime)

    sql"""insert into neo.dbo.BoRoles (roleName, createdBy, creationTime) values($roleName, $createdBy, $creationTimeTs)""".update
      .withUniqueGeneratedKeys[Long]("roleId")
      .attempt
      .flatMap {
        case Right(roleId) => Right(roleId).pureCon
        case Left(e: SQLException) if uniquenessViolated(e.getSQLState) => duplicateRoleNameError(roleName)
        case Left(e) => doobie.FC.raiseError(e)
      }
  end createBoRole

  override val fetchAllBoRoles: ConnectionIO[Vector[BoRoleInDb]] =
    sql"""select roleId, roleName, createdBy, creationTime from neo.dbo.BoRoles order by roleId"""
      .query[BoRoleInDb]
      .to[Vector]
  end fetchAllBoRoles

  override def fetchBoRoleByName(roleName: String): ConnectionIO[Vector[BoRoleInDb]] =
    sql"""select roleId, roleName, createdBy, creationTime from neo.dbo.BoRoles where roleName = $roleName"""
      .query[BoRoleInDb]
      .to[Vector]
  end fetchBoRoleByName

  override def fetchBoRoleById(roleId: Long): ConnectionIO[Option[BoRoleInDb]] =
    sql"""select roleId, roleName, createdBy, creationTime from neo.dbo.BoRoles where roleId = $roleId"""
      .query[BoRoleInDb]
      .option
  end fetchBoRoleById

  // Here we assume the role is not assigned to users.  If it still is, this command will fail.
  // The caller can use the isRoleAssignedToUsers() function to establish that not such association is there.
  override def deleteBoRoleById(roleId: Long): ConnectionIO[Int] =
    for {
      _ <- sql"delete from neo.dbo.BoRolePermissions where roleId = $roleId".update.run
      rowsDeleted <- sql"delete from neo.dbo.BoRoles where roleId = $roleId".update.run
    } yield rowsDeleted
  end deleteBoRoleById

  override def fetchBoRolePermissionsByName(roleName: String): ConnectionIO[Vector[PermissionInDb]] =
    sql"""select p.permissionId, p.permissionName from neo.dbo.BoRoles as rl
          join neo.dbo.BoRolePermissions as rp on rl.roleId = rp.roleId
          join neo.dbo.BoPermissions as p on rp.permissionId = p.permissionId
          where rl.roleName = $roleName"""
      .query[PermissionInDb]
      .to[Vector]
  end fetchBoRolePermissionsByName

  override def fetchBoRolePermissionsById(roleId: Long): ConnectionIO[Vector[PermissionInDb]] =
    sql"""select p.permissionId, p.permissionName from neo.dbo.BoRolePermissions as rp
          join neo.dbo.BoPermissions as p on rp.permissionId = p.permissionId
          where rp.roleId = $roleId"""
      .query[PermissionInDb]
      .to[Vector]
  end fetchBoRolePermissionsById

  def isRoleAssignedToUsers(roleId: Long): ConnectionIO[Boolean] =
    sql"""select case
          when exists (select 1 from neo.dbo.BoUserRoles where roleId = $roleId)
          then cast(1 as bit) else cast(0 as bit)
          end"""
      .query[Boolean]
      .unique
  end isRoleAssignedToUsers

  def fetchAllUsersAssociatedWithRole(roleId: Long): ConnectionIO[Vector[BoUserInDb]] =
    sql"""select u.userId, u.loginName, u.firstName, u.lastName, u.email, u.phone,
                 u.userCreationTime, u.hashedPassword, u.mustResetPassword,
                 u.userPasswordUpdateTime, u.enabled
          from neo.dbo.boUsers u
          join neo.dbo.BoUserRoles ur on u.userId = ur.userId
          where ur.roleId = $roleId"""
      .query[BoUserInDb]
      .to[Vector]
  end fetchAllUsersAssociatedWithRole

  override val fetchAllBoPermissions: ConnectionIO[Vector[PermissionInDb]] =
    sql"""select permissionId, permissionName from neo.dbo.BoPermissions order by permissionId"""
      .query[PermissionInDb]
      .to[Vector]
  end fetchAllBoPermissions

  override def updateBoUserRolesById(
      userId: Long,
      roleIds: NonEmptyVector[Long],
  ): ConnectionIO[Either[UpdateBoUserRolesDbError, Unit]] =
    for {
      userExistsCount <- sql"select count(*) from neo.dbo.BoUsers where userId = $userId".query[Int].unique

      result <-
        if userExistsCount == 0
        then Left(UpdateBoUserRolesDbError.NoSuchUserId(userId)).pureCon
        else
          val findValidRolesQuery = fr"select roleId from neo.dbo.BoRoles where" ++ fragments.in(fr"roleId", roleIds)
          for {
            validRoleIdsSet <- findValidRolesQuery.query[Long].to[Set]
            invalidRoleIds = roleIds.view.filterNot(n => validRoleIdsSet.contains(n)).toVector

            res <-
              if invalidRoleIds.nonEmpty
              then
                val invalidRoleIdsNonEmptyVec = NonEmptyVector.fromVectorUnsafe(invalidRoleIds)
                Left(UpdateBoUserRolesDbError.NoSuchRoleIds(invalidRoleIdsNonEmptyVec)).pureCon
              else
                val insertSql = "insert into neo.dbo.BoUserRoles (userId, roleId) values (?, ?)"
                val dataToInsert = roleIds.toVector.map((userId, _))

                for {
                  _ <- sql"delete from neo.dbo.BoUserRoles where userId = $userId".update.run
                  _ <- doobie.Update[(Long, Long)](insertSql).updateMany(dataToInsert)
                } yield Right(())
          } yield res
    } yield result
  end updateBoUserRolesById

  override def updateBoUserPasswordInDb(userId: Long, hashedPassword: String): ConnectionIO[Int] =
    sql"update neo.dbo.BoUsers set hashedPassword = $hashedPassword, mustResetPassword = 0 where userId = $userId".update.run
  end updateBoUserPasswordInDb
end BoRepositoryServiceLive

object BoRepositoryServiceLive:
  def create[F[_]: Async]: BoRepositoryService =
    new BoRepositoryServiceLive
  end create
end BoRepositoryServiceLive
