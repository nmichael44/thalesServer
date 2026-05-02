package app.auth

import cats.data.NonEmptyVector
import cats.effect.kernel.Async
import cats.syntax.all.*

import app.ThalesUtils.ExtensionMethodUtils.*
import app.ThalesUtils.GenUtils as U
import app.entrypoints.smithy.{PermissionId, PermissionInDb, PermissionName}
import app.services.RepositoryService
import doobie.implicits.toConnectionIOOps
import doobie.util.transactor.Transactor
import org.typelevel.log4cats.Logger

object Permissions:
  val CanCreateUsers: PermissionId = PermissionId(0)
  val CanSeeUsers: PermissionId = PermissionId(1)
  val CanCreateRoles: PermissionId = PermissionId(2)
  val CanDeleteRoles: PermissionId = PermissionId(3)
  val CanSeeUserRoles: PermissionId = PermissionId(4)
  val CanRenewJwtToken: PermissionId = PermissionId(5)
  val CanSeeAllPermissions: PermissionId = PermissionId(6)
  val CanSeeAllRoles: PermissionId = PermissionId(7)
  val CanResetMyPassword: PermissionId = PermissionId(8)
  val CanCheckResetUserPasswordToken: PermissionId = PermissionId(9)
  val CanSetMustResetUserPassword: PermissionId = PermissionId(10)
  val CanUpdateUserRoles: PermissionId = PermissionId(11)
  val CanSeeAllLiveSessions: PermissionId = PermissionId(12)

  private val AllPermissions: Map[PermissionId, PermissionInDb] =
    import PermissionMacros.mkPerm

    U.toMap(
      mkPerm(CanCreateUsers),
      mkPerm(CanSeeUsers),
      mkPerm(CanCreateRoles),
      mkPerm(CanDeleteRoles),
      mkPerm(CanSeeAllLiveSessions),
      mkPerm(CanRenewJwtToken),
      mkPerm(CanSeeAllPermissions),
      mkPerm(CanSeeAllRoles),
      mkPerm(CanResetMyPassword),
      mkPerm(CanCheckResetUserPasswordToken),
      mkPerm(CanSetMustResetUserPassword),
      mkPerm(CanUpdateUserRoles),
      mkPerm(CanSeeUserRoles),
    )
  end AllPermissions

  private type UserPermissions = java.util.BitSet

  opaque type CompiledPermissionAlgebra = UserPermissions => Boolean

  enum PermissionAlgebra:
    case Has(permission: PermissionId)
    case And(permissionAlgebras: NonEmptyVector[PermissionAlgebra])
    case Or(permissionAlgebras: NonEmptyVector[PermissionAlgebra])
    case Not(permissionAlgebra: PermissionAlgebra)

    def compile: CompiledPermissionAlgebra =
      this match
        case Has(permission) => _.get(permission.value.toInt)
        case And(pas) =>
          val cpas = pas.view.map(_.compile).toList
          cpas match
            case cpa :: Nil => cpa
            case cpa0 :: cpa1 :: Nil => s => cpa0(s) && cpa1(s)
            case _ => s => cpas.forall(_(s))
        case Or(pas) =>
          val cpas = pas.view.map(_.compile).toList
          cpas match
            case cpa :: Nil => cpa
            case cpa0 :: cpa1 :: Nil => s => cpa0(s) || cpa1(s)
            case _ => s => cpas.exists(_(s))
        case Not(pa) =>
          val cpa = pa.compile
          s => !cpa(s)
    end compile
  end PermissionAlgebra

  extension (cpa: CompiledPermissionAlgebra)
    def isSatisfiedBy(userPerms: java.util.BitSet): Boolean =
      cpa(userPerms)
    end isSatisfiedBy
  end extension

  private def loadDbPermissions[F[_]: Async](
      repositoryService: RepositoryService,
      xa: Transactor[F],
  ): F[Map[PermissionId, PermissionInDb]] =
    repositoryService.fetchAllPermissions.transact(xa)
  end loadDbPermissions

  private def logVerifyingDbPermissionsIntegrity[F[_]: Logger as logger]: F[Unit] =
    U.logi("Verifying permissions integrity...")
  end logVerifyingDbPermissionsIntegrity

  private def logDbPermissionsIntegrityVerified[F[_]: Logger as logger]: F[Unit] =
    U.logi("Db Permissions integrity verified.")
  end logDbPermissionsIntegrityVerified

  given CanEqual[PermissionId, PermissionId] = CanEqual.derived
  given CanEqual[Map[PermissionId, PermissionInDb], Map[PermissionId, PermissionInDb]] = CanEqual.derived

  def verifyPermissions[F[_]: { Async as async, Logger }](repositoryService: RepositoryService, xa: Transactor[F]): F[Unit] =
    for
      _ <- logVerifyingDbPermissionsIntegrity
      permissionsMapInDb <- loadDbPermissions(repositoryService, xa)
      _ <- async.whenA(permissionsMapInDb != AllPermissions)(
        async.raiseError(AssertionError("The Permissions table in the database defers from that in the code.")),
      )
      _ <- logDbPermissionsIntegrityVerified
    yield ()
  end verifyPermissions
end Permissions
