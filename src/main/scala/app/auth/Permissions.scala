package app.auth

import cats.data.NonEmptyVector
import cats.effect.kernel.Async
import cats.syntax.all.*

import app.ThalesUtils.ExtensionMethodUtils.*
import app.ThalesUtils.GenUtils as U
import app.entrypoints.smithy.PermissionInDb
import app.services.RepositoryService
import doobie.implicits.toConnectionIOOps
import doobie.util.transactor.Transactor
import org.typelevel.log4cats.Logger

final class Permissions private (permissions: Map[Long, PermissionInDb]):
  def getPermission(permissionId: Long): PermissionInDb =
    permissions(permissionId)
  end getPermission
end Permissions

object Permissions:
  opaque type Permission = Long

  val CanCreateUsers: Permission = 0
  val CanSeeUsers: Permission = 1
  val CanCreateRoles: Permission = 2
  val CanDeleteRoles: Permission = 3
  val CanSeeAllLiveSessions: Permission = 4
  val CanRenewJwtToken: Permission = 5
  val CanSeeAllPermissions: Permission = 6
  val CanSeeAllRoles: Permission = 7
  val CanResetMyPassword: Permission = 8
  val CanCheckResetUserPasswordToken: Permission = 9

  private val AllPermissions: Map[Long, PermissionInDb] = Map(
    CanCreateUsers                 -> PermissionInDb(CanCreateUsers, "CanCreateUsers"),
    CanSeeUsers                    -> PermissionInDb(CanSeeUsers, "CanSeeUsers"),
    CanCreateRoles                 -> PermissionInDb(CanCreateRoles, "CanCreateRoles"),
    CanDeleteRoles                 -> PermissionInDb(CanDeleteRoles, "CanDeleteRoles"),
    CanSeeAllLiveSessions          -> PermissionInDb(CanSeeAllLiveSessions, "CanSeeAllLiveSessions"),
    CanRenewJwtToken               -> PermissionInDb(CanRenewJwtToken, "CanRenewJwtToken"),
    CanSeeAllPermissions           -> PermissionInDb(CanSeeAllPermissions, "CanSeeAllPermissions"),
    CanSeeAllRoles                 -> PermissionInDb(CanSeeAllRoles, "CanSeeAllRoles"),
    CanResetMyPassword             -> PermissionInDb(CanResetMyPassword, "CanResetMyPassword"),
    CanCheckResetUserPasswordToken -> PermissionInDb(CanCheckResetUserPasswordToken, "CanCheckResetUserPasswordToken"),
  )

  private type UserPermissions = java.util.BitSet

  opaque type CompiledPermissionAlgebra = UserPermissions => Boolean

  enum PermissionAlgebra:
    case Has(permission: Permission)
    case And(permissionAlgebras: NonEmptyVector[PermissionAlgebra])
    case Or(permissionAlgebras: NonEmptyVector[PermissionAlgebra])
    case Not(permissionAlgebra: PermissionAlgebra)

    def compile: CompiledPermissionAlgebra =
      this match {
        case Has(permission) => _.get(permission.toInt)
        case And(pas) =>
          val cpas = pas.view.map(_.compile).toList
          cpas match {
            case cpa :: Nil => cpa
            case cpa0 :: cpa1 :: Nil => s => cpa0(s) && cpa1(s)
            case _ => s => cpas.forall(_(s))
          }
        case Or(pas) =>
          val cpas = pas.view.map(_.compile).toList
          cpas match {
            case cpa :: Nil => cpa
            case cpa0 :: cpa1 :: Nil => s => cpa0(s) || cpa1(s)
            case _ => s => cpas.exists(_(s))
          }
        case Not(pa) =>
          val cpa = pa.compile
          s => !cpa(s)
      }
    end compile
  end PermissionAlgebra

  extension (cpa: CompiledPermissionAlgebra)
    def isSatisfiedBy(userPerms: java.util.BitSet): Boolean =
      cpa(userPerms)
    end isSatisfiedBy

  private def loadDbPermissions[F[_]: Async as async](
      repositoryService: RepositoryService,
      xa: Transactor[F],
  ): F[Map[Permission, PermissionInDb]] =
    repositoryService.fetchAllPermissions
      .transact(xa)
      .map(v => v.view.map(U.mapToFirst(_.permissionId)).toMap)
  end loadDbPermissions

  private def logVerifyingDbPermissionsIntegrity[F[_]: Logger as logger]: F[Unit] =
    logger.info("Verifying permissions integrity...")
  end logVerifyingDbPermissionsIntegrity

  private def logDbPermissionsIntegrityVerified[F[_]: Logger as logger]: F[Unit] =
    logger.info("Db Permissions integrity verified.")
  end logDbPermissionsIntegrityVerified

  given CanEqual[Map[Long, PermissionInDb], Map[Long, PermissionInDb]] = CanEqual.derived

  def verifyPermissions[F[_]: { Async as async, Logger as logger }](
      repositoryService: RepositoryService,
      xa: Transactor[F],
  ): F[Unit] = for {
    _ <- logVerifyingDbPermissionsIntegrity
    permissionsMapInDb <- loadDbPermissions(repositoryService, xa)
    _ <- async.whenA(permissionsMapInDb != AllPermissions)(
      async.raiseError(AssertionError("The Permissions table in the database defers from that in the code.")),
    )
    _ <- logDbPermissionsIntegrityVerified
  } yield ()
  end verifyPermissions
end Permissions
