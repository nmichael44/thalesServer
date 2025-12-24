package app.auth

import cats.data.NonEmptyVector
import cats.effect.kernel.Async
import cats.syntax.all.*

import app.ThalesUtils.ExtensionMethodUtils.*
import app.ThalesUtils.GenUtils as U
import app.auth.Permissions.PermissionInDb
import app.services.RepositoryService
import doobie.implicits.toConnectionIOOps
import doobie.util.transactor.Transactor

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

  private val AllPermissions: Map[Long, PermissionInDb] = Map(
    CanCreateUsers        -> PermissionInDb(CanCreateUsers, "CanCreateUsers"),
    CanSeeUsers           -> PermissionInDb(CanSeeUsers, "CanSeeUsers"),
    CanCreateRoles        -> PermissionInDb(CanCreateRoles, "CanCreateRoles"),
    CanDeleteRoles        -> PermissionInDb(CanDeleteRoles, "CanDeleteRoles"),
    CanSeeAllLiveSessions -> PermissionInDb(CanSeeAllLiveSessions, "CanSeeAllLiveSessions"),
    CanRenewJwtToken      -> PermissionInDb(CanRenewJwtToken, "CanRenewJwtToken"),
    CanSeeAllPermissions  -> PermissionInDb(CanSeeAllPermissions, "CanSeeAllPermissions"),
    CanSeeAllRoles        -> PermissionInDb(CanSeeAllRoles, "CanSeeAllRoles"),
  )

  final case class PermissionInDb(permissionId: Long, permissionName: String):
    override def hashCode(): Int =
      java.lang.Long.hashCode(permissionId)
    end hashCode

    override def equals(obj: Any): Boolean =
      obj match {
        case PermissionInDb(id, _) => permissionId == id
        case _ => false
      }
    end equals

    def isObjectFullyEqual(obj: Any): Boolean =
      obj match {
        case PermissionInDb(id, name) => permissionId == id && permissionName == name
        case _ => false
      }
    end isObjectFullyEqual
  end PermissionInDb

  type UserPermissions = java.util.BitSet

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

  // We can't just use == (regular equality) because it only compares permissionIds,
  // and we want it that way for the normal running of the system.
  // Here for extra protection, we want to compare the description as well.
  private def permissionsMapsAreEqual(m0: Map[Long, PermissionInDb], m1: Map[Long, PermissionInDb]): Boolean =
    if m0.size != m1.size then false
    else
      m0.view.forall { case (permissionId0, permissionInDb0) =>
        m1.get(permissionId0) match {
          case None => false
          case Some(permissionInDb1) => permissionInDb0.isObjectFullyEqual(permissionInDb1)
        }
      }
  end permissionsMapsAreEqual

  private def loadDbPermissions[F[_]: Async as async](
      repositoryService: RepositoryService,
      xa: Transactor[F],
  ): F[Map[Permission, PermissionInDb]] =
    repositoryService.fetchAllPermissions
      .transact(xa)
      .map(v => v.view.map(U.mapToFirst(_.permissionId)).toMap)
  end loadDbPermissions

  def verifyPermissions[F[_]: Async as async](repositoryService: RepositoryService, xa: Transactor[F]): F[Unit] = for {
    permissionsMapInDb <- loadDbPermissions(repositoryService, xa)
    _ <- async.whenA(!permissionsMapsAreEqual(permissionsMapInDb, AllPermissions))(
      async.raiseError(new AssertionError("Permission maps are not equal.")),
    )
  } yield ()
  end verifyPermissions
end Permissions
