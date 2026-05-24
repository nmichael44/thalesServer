package app.serviceslive

import cats.data.{EitherT, NonEmptyVector}
import cats.implicits.*

import java.sql.SQLException
import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.collection.View
import scala.util.control.NoStackTrace

import app.ThalesUtils.DbUtils.*
import app.ThalesUtils.DbUtils.given
import app.ThalesUtils.ExtensionMethodUtils.*
import app.entrypoints.smithy.{HashedResetPasswordToken, HashedUserPassword, LoginName, PermissionId, PermissionInDb, RoleId, RoleInDb, RoleName, UserId, UserInDb}
import app.model.AppModel.EmailOutboxEntry
import app.model.AppModel.OutboxStatus
import app.model.AppModel.RecipientType
import app.services.{CreateRoleDbError, CreateUserDbError, RepositoryService, UpdateUserRolesByIdDbError}
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.syntax.all.toSqlInterpolator

private final class RepositoryServiceLive private extends RepositoryService:
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
      .flatMap:
        case Right(userId) => Right(UserId(userId)).pureCon
        case Left(e: SQLException) if uniquenessViolated(e.getSQLState) => duplicateConstraintViolatedError(e.getMessage)
        case Left(e) => doobie.FC.raiseError(e)
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
      .withUniqueGeneratedKeys[Long]("roleid")
      .attempt
      .flatMap:
        case Right(roleId) =>
          Right(RoleId(roleId)).pureCon
        case Left(e: SQLException) if uniquenessViolated(e.getSQLState) =>
          Left(CreateRoleDbError.DuplicateRoleName).pureCon
        case Left(e) =>
          doobie.FC.raiseError(e)
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
    for
      _ <- sql"delete from RolePermissions where roleId = $roleIdLong".exec
      rowsDeleted <- sql"delete from Roles where roleId = $roleIdLong".exec
    yield rowsDeleted
  }
  end deleteRoleById

  override def fetchRolesPermissionsById(roleIds: NonEmptyVector[RoleId]): ConnectionIO[Map[RoleId, Vector[PermissionInDb]]] =
    val roleIdsVec = roleIds.view.map(_.value).toVector

    sql"""SELECT rp.roleId, p.permissionId, p.permissionName
                    FROM RolePermissions rp
                    JOIN Permissions p ON rp.permissionId = p.permissionId
                    WHERE rp.roleId = ANY($roleIdsVec)"""
      .toVec[(RoleId, PermissionInDb)]
      .map(_.toGroupedMapForNev(roleIds))
  end fetchRolesPermissionsById

  def isRoleAssignedToUsers(roleId: RoleId): ConnectionIO[Boolean] =
    sql"select exists (select 1 from UserRoles where roleId = ${roleId.value})".toUnique
  end isRoleAssignedToUsers

  def fetchAllUsersAssociatedWithRoles(roleIds: NonEmptyVector[RoleId]): ConnectionIO[Map[RoleId, Vector[UserInDb]]] =
    val roleIdsVec = roleIds.view.map(_.value).toVector

    sql"""select ur.roleId, u.userId, u.loginName, u.firstName, u.lastName, u.email, u.phone,
                 u.userCreationTime, u.hashedPassword, u.mustResetPassword,
                 u.userPasswordUpdateTime, u.enabled, u.creatingUserId
          from Users u
          join UserRoles ur on u.userId = ur.userId
          where ur.roleId = ANY($roleIdsVec)"""
      .toVec[(RoleId, UserInDb)]
      .map(_.toGroupedMapForNev(roleIds))
  end fetchAllUsersAssociatedWithRoles

  override val fetchAllPermissions: ConnectionIO[Map[PermissionId, PermissionInDb]] =
    sql"select permissionId, permissionName from Permissions order by permissionId"
      .toIdxMap(_.permissionId)
  end fetchAllPermissions

  override def fetchUserRoleIds(userIds: NonEmptyVector[UserId]): ConnectionIO[Map[UserId, Vector[RoleId]]] =
    val userIdsVec = userIds.view.map(_.value).toVector

    sql"select userId, roleId from UserRoles where userId = ANY($userIdsVec)"
      .toVec[(UserId, RoleId)]
      .map(_.toGroupedMapForNev(userIds))
  end fetchUserRoleIds

  private type R = EitherT[ConnectionIO, UpdateUserRolesByIdDbError, Unit]

  private val allRolesIdsFoundInDb: R = EitherT.rightT(())

  private val rolesIdsNotFoundInDb: NonEmptyVector[RoleId] => R =
    missingRoles => EitherT.leftT(UpdateUserRolesByIdDbError.NoSuchRoleIds(missingRoles))
  end rolesIdsNotFoundInDb

  override def updateUserRolesById(userId: UserId, roleIds: NonEmptyVector[RoleId]): ConnectionIO[Either[UpdateUserRolesByIdDbError, Unit]] =
    val roleIdsVec = roleIds.view.map(_.value).toVector
    val userIdLong = userId.value

    val program: R =
      for
        _ <- EitherT(
          sql"select exists (select 1 from Users where userId = ${userId.value})"
            .toUnique[Boolean]
            .map(ue => Either.cond(ue, (), UpdateUserRolesByIdDbError.NoSuchUserId)),
        )

        validRoleIdsSet <- EitherT.liftF(
          sql"select roleId from Roles where roleId = ANY($roleIdsVec)"
            .toSet[RoleId],
        )

        _ <- NonEmptyVector
          .fromVector(roleIds.filterNot(validRoleIdsSet.contains))
          .fold(allRolesIdsFoundInDb)(rolesIdsNotFoundInDb)

        _ <- EitherT.liftF(sql"delete from UserRoles where userId = $userIdLong".exec)
        _ <- EitherT.liftF[ConnectionIO, UpdateUserRolesByIdDbError, Unit] {
          val insertSql = "insert into UserRoles (userId, roleId) values (?, ?)"
          val dataToInsert = roleIdsVec.map((userIdLong, _))

          doobie.Update[(Long, Long)](insertSql).updateMany(dataToInsert).void
        }
      yield ()

    program.value
  end updateUserRolesById

  override def updateUserPasswordInDb(userId: UserId, hashedPassword: HashedUserPassword): ConnectionIO[Int] =
    sql"update Users set hashedPassword = ${hashedPassword.value}, mustResetPassword = false where userId = ${userId.value}".exec
  end updateUserPasswordInDb

  override def setMustResetUserPassword(userId: UserId, mustResetPassword: Boolean): ConnectionIO[Int] =
    sql"update Users set mustResetPassword = $mustResetPassword where userId = ${userId.value}".exec
  end setMustResetUserPassword

  override def insertResetUserPasswordToken(
      hashedToken: HashedResetPasswordToken,
      userId: UserId,
      expirationTime: Instant,
  ): ConnectionIO[Unit] =
    sql"""insert into ResetUserPasswordTokens (hashedToken, userId, expirationTime)
          values (${hashedToken.value}, ${userId.value}, $expirationTime)""".execToUnit
  end insertResetUserPasswordToken

  override def getResetUserPasswordTokenExpiry(hashedToken: HashedResetPasswordToken): ConnectionIO[Option[(UserId, Instant)]] =
    sql"select userId, expirationTime from ResetUserPasswordTokens where hashedToken = ${hashedToken.value}".toOpt
  end getResetUserPasswordTokenExpiry

  override def deleteResetUserPasswordToken(hashedToken: HashedResetPasswordToken): ConnectionIO[Unit] =
    sql"delete from ResetUserPasswordTokens where hashedToken = ${hashedToken.value}".execToUnit
  end deleteResetUserPasswordToken

  override def deleteExpiredResetUserPasswordTokens(now: Instant): ConnectionIO[Int] =
    sql"delete from ResetUserPasswordTokens where expirationTime < $now".exec
  end deleteExpiredResetUserPasswordTokens

  override def deleteOldLoginFailedAttempts(now: Instant, minutes: Int): ConnectionIO[Int] =
    val cutoff = now.minus(minutes.toLong, ChronoUnit.MINUTES)
    sql"delete from LoginFailedAttempts where failedAttemptTime < $cutoff".exec
  end deleteOldLoginFailedAttempts

  override def deleteFailedAttemptsForLoginName(loginName: LoginName): ConnectionIO[Unit] =
    sql"delete from LoginFailedAttempts where loginName = ${loginName.value}".execToUnit
  end deleteFailedAttemptsForLoginName

  override def fetchCountOfFailedAttempts(loginName: LoginName, now: Instant, minutes: Int): ConnectionIO[Int] =
    val cutoff = now.minus(minutes.toLong, ChronoUnit.MINUTES)
    sql"select count(*) from LoginFailedAttempts where loginName = ${loginName.value} and failedAttemptTime >= $cutoff".toUnique
  end fetchCountOfFailedAttempts

  override def insertFailedAttempt(loginName: LoginName, now: Instant): ConnectionIO[Unit] =
    sql"insert into LoginFailedAttempts (loginName, failedAttemptTime) values (${loginName.value}, $now)".execToUnit
  end insertFailedAttempt

  override def insertEmailIntoOutbox(
      from: String,
      tos: Seq[String],
      ccs: Seq[String],
      bccs: Seq[String],
      subject: String,
      body: String,
      now: Instant,
  ): ConnectionIO[Long] =
    val status = OutboxStatus.Pending

    val insertEmailQuery = sql"""insert into EmailOutbox (fromAddress, subject, body, status, attempts, nextAttemptTime, creationTime)
                                 values ($from, $subject, $body, $status, 0, $now, $now)""".update
      .withUniqueGeneratedKeys[Long]("emailid")

    for
      emailId <- insertEmailQuery
      recipients = Seq((tos, RecipientType.To), (ccs, RecipientType.Cc), (bccs, RecipientType.Bcc))
        .flatMap: (addrs, rtype) =>
          addrs.view.map(addr => (emailId, addr, rtype))
      _ <-
        if recipients.isEmpty
        then doobie.FC.raiseError(new IllegalArgumentException("Cannot insert an email with zero recipients.") with NoStackTrace)
        else
          val insertSql = "insert into EmailRecipients (emailId, emailAddress, recipientType) values (?, ?, ?)"
          doobie.Update[(Long, String, RecipientType)](insertSql).updateMany(recipients).as(emailId)
    yield emailId
  end insertEmailIntoOutbox

  override def fetchEligibleEmailsFromOutbox(now: Instant, maxAttempts: Int, limit: Int): ConnectionIO[Vector[EmailOutboxEntry]] =
    val pendingStatus = OutboxStatus.Pending
    val failedStatus = OutboxStatus.Failed
    for
      outboxRows <- sql"""select emailId, fromAddress, subject, body, status, attempts, lastAttemptTime, nextAttemptTime, creationTime, errorMessage
                          from EmailOutbox
                          where status = $pendingStatus or (status = $failedStatus and attempts < $maxAttempts and nextAttemptTime <= $now)
                          order by creationTime asc
                          limit $limit"""
        .query[(Long, String, String, String, OutboxStatus, Int, Option[Instant], Instant, Instant, Option[String])]
        .to[Vector]

      emailIds = outboxRows.map(_._1)

      recipients <-
        if emailIds.nonEmpty then
          sql"""select emailId, emailAddress, recipientType
                from EmailRecipients
                where emailId = ANY($emailIds)"""
            .query[(Long, String, RecipientType)]
            .to[Vector]
        else Vector.empty[(Long, String, RecipientType)].pureCon
    yield
      val groupedRecipients = recipients.groupBy(_._1)

      outboxRows.map: (emailId, fromAddress, subject, body, status, attempts, lastAttemptTime, nextAttemptTime, creationTime, errorMessage) =>
        val emailRecs = groupedRecipients(emailId)
        val recsByType: Map[RecipientType, Vector[String]] = emailRecs.groupMap(_._3)(_._2)
        val tos = recsByType.getOrElse(RecipientType.To, Vector.empty)
        val ccs = recsByType.getOrElse(RecipientType.Cc, Vector.empty)
        val bccs = recsByType.getOrElse(RecipientType.Bcc, Vector.empty)

        EmailOutboxEntry(
          emailId = emailId,
          fromAddress = fromAddress,
          toAddresses = tos,
          ccAddresses = ccs,
          bccAddresses = bccs,
          subject = subject,
          body = body,
          status = status,
          attempts = attempts,
          lastAttemptTime = lastAttemptTime,
          nextAttemptTime = nextAttemptTime,
          creationTime = creationTime,
          errorMessage = errorMessage,
        )
  end fetchEligibleEmailsFromOutbox

  override def markEmailAsSent(emailId: Long, now: Instant): ConnectionIO[Unit] =
    val status = app.model.AppModel.OutboxStatus.Sent
    sql"""update EmailOutbox
          set status = $status, lastAttemptTime = $now
          where emailId = $emailId""".execToUnit
  end markEmailAsSent

  override def markEmailAsFailed(
      emailId: Long,
      now: Instant,
      attempts: Int,
      nextAttemptTime: Instant,
      errorMessage: String,
  ): ConnectionIO[Unit] =
    val status = app.model.AppModel.OutboxStatus.Failed
    sql"""update EmailOutbox
          set status = $status, attempts = $attempts, lastAttemptTime = $now, nextAttemptTime = $nextAttemptTime, errorMessage = $errorMessage
          where emailId = $emailId""".execToUnit
  end markEmailAsFailed
end RepositoryServiceLive

object RepositoryServiceLive:

  def create: RepositoryService =
    new RepositoryServiceLive
  end create
end RepositoryServiceLive
