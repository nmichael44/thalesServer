package app.serviceslive

import cats.data.NonEmptyVector
import cats.effect.Async
import cats.implicits.{catsSyntaxApplicativeError, catsSyntaxApplicativeId}

import java.time.Instant

import app.auth.Permissions.Permission
import app.model.AppModel.{BoRole, BoUserInDb}
import app.services.{BoRepositoryService, CreateBoUserDbError, CreationBoRoleDbError, DeleteBoRoleDbError, UpdateBoUserRolesDbError}
import app.ThalesUtils.ImplicitConversionUtils.*
import com.microsoft.sqlserver.jdbc.SQLServerException
import doobie.*
import doobie.implicits.*
import doobie.implicits.javatimedrivernative.*
import doobie.syntax.all.toSqlInterpolator
import doobie.util.fragments
import io.circe.syntax.*

private final class BoRepositoryServiceLive[F[_]: Async as async] private (xa: Transactor[F]) extends BoRepositoryService[F]:
  inline private val UniqueConstraintViolation = 2627
  inline private val UniqueIndexViolation = 2601

  private def uniquenessViolated(errCode: Int): Boolean =
    errCode == UniqueConstraintViolation || errCode == UniqueIndexViolation
  end uniquenessViolated

  extension [A](obj: A)
    inline private def pureCon: ConnectionIO[A] =
      obj.pure[ConnectionIO]
    end pureCon

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
  ): F[Either[CreateBoUserDbError, Long]] =
    val userCreationTs = java.sql.Timestamp.from(userCreationTime)

    sql"""insert into neo.dbo.boUsers (loginName, firstName, lastName, email, phone, userCreationTime, hashedPassword, mustResetPassword, userPasswordUpdateTime, enabled)
          values ($loginName, $firstName, $lastName, $email, $phone, $userCreationTs, $hashedPassword, $mustResetPassword, $userPasswordUpdateTime, $enabled)""".update
      .withUniqueGeneratedKeys[Long]("userId")
      .attempt
      .flatMap {
        case Right(userId) =>
          Right(userId).pureCon
        case Left(e: SQLServerException) if uniquenessViolated(e.getErrorCode) =>
          Left(CreateBoUserDbError.DuplicateLoginName(loginName)).pureCon
        case Left(e) =>
          doobie.FC.raiseError(e) // Re-throw any other exceptions
      }
      .transact(xa)
  end createBoUser

  override def fetchBoUserByLoginName(loginName: String): F[Option[BoUserInDb]] =
    sql"""select userId, loginName, firstName, lastName, email, phone, userCreationTime, hashedPassword, mustResetPassword, userPasswordUpdateTime, enabled from neo.dbo.boUsers where loginName = $loginName"""
      .query[BoUserInDb]
      .option
      .transact(xa)
  end fetchBoUserByLoginName

  override def fetchBoUserById(userId: Long): F[Option[BoUserInDb]] =
    sql"""select userId, loginName, firstName, lastName, email, phone, userCreationTime, hashedPassword, mustResetPassword, userPasswordUpdateTime, enabled from neo.dbo.boUsers where userId = $userId"""
      .query[BoUserInDb]
      .option
      .transact(xa)
  end fetchBoUserById

  override def fetchMultipleBoUsersById(userIds: NonEmptyVector[Long]): F[Map[Long, BoUserInDb]] =
    val userIdsJson = userIds.toVector.asJson.noSpaces
    val e = Map.empty[Long, BoUserInDb]

    sql"""
      SELECT
        u.userId, u.loginName, u.firstName, u.lastName, u.email, u.phone,
        u.userCreationTime, u.hashedPassword, u.mustResetPassword,
        u.userPasswordUpdateTime, u.enabled
      FROM
        neo.dbo.boUsers u
      INNER JOIN
        OPENJSON($userIdsJson) WITH (id BIGINT '$$') AS ids ON u.userId = ids.id
    """
      .query[BoUserInDb]
      .stream
      .fold(e)((m, u) => m.updated(u.userId, u))
      .compile
      .lastOrError
      .transact(xa)
  end fetchMultipleBoUsersById

  override def fetchBoUserPermissions(userId: Long): F[Vector[Permission]] =
    import app.auth.Permissions.given

    sql"""select bp.permissionName from neo.dbo.BoUserRoles ur
          join neo.dbo.BoRolePermissions rp on ur.roleId = rp.roleId
          join neo.dbo.BoPermissions bp on rp.permissionId = bp.permissionId
          where ur.userId = $userId"""
      .query[Permission]
      .to[Vector]
      .transact(xa)
  end fetchBoUserPermissions

  override def createBoRole(roleName: String): F[Either[CreationBoRoleDbError, Long]] =
    sql"""insert into neo.dbo.BoRoles (roleName) values($roleName)""".update
      .withUniqueGeneratedKeys[Long]("roleId")
      .attempt
      .flatMap {
        case Right(roleId) =>
          Right(roleId).pureCon
        case Left(e: SQLServerException) if uniquenessViolated(e.getErrorCode) =>
          Left(CreationBoRoleDbError.DuplicateRoleName(roleName)).pureCon
        case Left(e) =>
          doobie.FC.raiseError(e)
      }
      .transact(xa)
  end createBoRole

  override val fetchAllBoRoles: F[Vector[BoRole]] =
    sql"""select roleId, roleName from neo.dbo.BoRoles"""
      .query[BoRole]
      .to[Vector]
      .transact(xa)
  end fetchAllBoRoles

  override def fetchBoRoleByName(roleName: String): F[Vector[BoRole]] =
    sql"""select roleId, roleName from neo.dbo.BoRoles where roleName = $roleName"""
      .query[BoRole]
      .to[Vector]
      .transact(xa)
  end fetchBoRoleByName

  override def fetchBoRoleById(roleId: Long): F[Vector[BoRole]] =
    sql"""select roleId, roleName from neo.dbo.BoRoles where roleId = $roleId"""
      .query[BoRole]
      .to[Vector]
      .transact(xa)
  end fetchBoRoleById

  override def deleteBoRoleById(roleId: Long): F[Either[DeleteBoRoleDbError, Unit]] =
    val transaction = for {
      numUsersUsingRole <- sql"select count(*) from neo.dbo.BoUserRoles where roleId = $roleId".query[Int].unique
      res0 <-
        if numUsersUsingRole > 0
        then Left(DeleteBoRoleDbError.RoleStillInUse(roleId)).pureCon
        else
          for {
            _ <- sql"delete from neo.dbo.BoRolePermissions where roleId = $roleId".update.run
            rowsDeleted <- sql"delete from neo.dbo.BoRoles where roleId = $roleId".update.run
            res1 <- rowsDeleted match {
              case 0 => Left(DeleteBoRoleDbError.NoSuchBoRole(roleId)).pureCon
              case 1 => Right(()).pureCon
              case _ => doobie.FC.raiseError(AssertionError("Data integrity error.  More than 1 row deleted in deleteBoRole()"))
            }
          } yield res1
    } yield res0

    transaction.transact(xa)

  override def fetchBoRolePermissionsByName(roleName: String): F[Vector[Permission]] =
    import app.auth.Permissions.given

    sql"""select p.permissionName from neo.dbo.BoRoles as rl
          join neo.dbo.BoRolePermissions as rp on rl.roleId = rp.roleId
          join neo.dbo.BoPermissions as p on rp.permissionId = p.permissionId
          where rl.roleName = $roleName"""
      .query[Permission]
      .to[Vector]
      .transact(xa)
  end fetchBoRolePermissionsByName

  override def fetchBoRolePermissionsById(roleId: Long): F[Vector[Permission]] =
    import app.auth.Permissions.given

    sql"""select p.permissionName from neo.dbo.BoRolePermissions as rp
          join neo.dbo.BoPermissions as p on rp.permissionId = p.permissionId
          where rp.roleId = $roleId"""
      .query[Permission]
      .to[Vector]
      .transact(xa)
  end fetchBoRolePermissionsById

  override val fetchAllBoPermissions: F[Vector[Permission]] =
    sql"""select permissionId, permissionName from neo.dbo.BoPermissions"""
      .query[Permission]
      .to[Vector]
      .transact(xa)
  end fetchAllBoPermissions

  override def updateBoUserRolesById(userId: Long, roleIds: NonEmptyVector[Long]): F[Either[UpdateBoUserRolesDbError, Unit]] =
    val transaction = for {
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
                for {
                  _ <- sql"delete from neo.dbo.BoUserRoles where userId = $userId".update.run

                  insertSql = "insert into neo.dbo.BoUserRoles (userId, roleId) values (?, ?)"
                  dataToInsert = roleIds.view.map(roleId => (userId, roleId)).toVector
                  _ <- doobie.Update[(Long, Long)](insertSql).updateMany(dataToInsert)
                } yield Right(())
          } yield res
    } yield result

    transaction.transact(xa)
  end updateBoUserRolesById

  override def updateBoUserPasswordInDb(userId: Long, hashedPassword: String): F[Int] =
    sql"update neo.dbo.BoUsers set hashedPassword = $hashedPassword, mustResetPassword = 0 where userId = $userId".update.run
      .transact(xa)
  end updateBoUserPasswordInDb
end BoRepositoryServiceLive

object BoRepositoryServiceLive:
  def create[F[_]: Async](xa: Transactor[F]): BoRepositoryService[F] =
    BoRepositoryServiceLive[F](xa)
  end create
end BoRepositoryServiceLive
