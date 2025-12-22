package app.auth

import cats.data.NonEmptyVector
import cats.effect.kernel.{Async, Resource}
import cats.implicits.*
import cats.syntax.all.*

import app.ThalesUtils.ExtensionMethodUtils.*
import app.ThalesUtils.GenUtils as U
import app.auth.Permissions.PermissionInDb
import app.services.RepositoryService
import doobie.{ConnectionIO, Meta}
import doobie.implicits.toConnectionIOOps
import doobie.util.transactor.Transactor

final class Permissions private (permissions: Map[Long, PermissionInDb]):
  def getPermission(permissionId: Long): PermissionInDb =
    permissions(permissionId)
  end getPermission
end Permissions

object Permissions:
  enum Permission:
    // Capability permissions
    case CanCreateUsers
    case CanSeeUsers

    case CanCreateRoles
    case CanDeleteRoles
    // Admin permissions
    case CanSeeAllLiveSessions

    case CanRenewJwtToken
    case CanSeeAllPermissions
    case CanSeeAllRoles
  end Permission

  private val PermissionsMap: Map[String, Permission] =
    Permission.values.view.map(p => p.toString -> p).toMap
  end PermissionsMap

  def fromString(s: String): Permission =
    PermissionsMap.getOrElse(s, throw AssertionError(s"Bad permission '$s'."))
  end fromString

  def fromString(p: PermissionInDb): Permission =
    fromString(p.permissionName)
  end fromString

  given Meta[Permission] =
    Meta[String].imap[Permission](fromString)(_.toString)

  final case class PermissionInDb(permissionId: Long, permissionName: String):
    override def hashCode(): Int =
      permissionId.toInt
    end hashCode

    override def equals(obj: Any): Boolean =
      obj match {
        case PermissionInDb(id, _) => permissionId == id
        case _ => false
      }
    end equals
  end PermissionInDb

  enum PermissionAlgebra:
    case Has(permission: Permission)
    case And(permissionAlgebras: NonEmptyVector[PermissionAlgebra])
    case Or(permissionAlgebras: NonEmptyVector[PermissionAlgebra])
    case Not(permissionAlgebra: PermissionAlgebra)

    def compile: CompiledPermissionAlgebra =
      this match {
        case Has(permission) => _.contains(permission)
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

  opaque type CompiledPermissionAlgebra = Set[Permission] => Boolean

  extension (cpa: CompiledPermissionAlgebra)
    def isSatisfiedBy(userPermissions: Set[Permission]): Boolean =
      cpa(userPermissions)
    end isSatisfiedBy

  def create[F[_]: Async as async](repositoryService: RepositoryService, xa: Transactor[F]): Resource[F, Permissions] =
    Resource.eval(
      repositoryService.fetchAllPermissions
        .transact(xa)
        .map(v => v.view.map(U.mapToFirst(_.permissionId)).toMap)
        .map(Permissions(_)),
    )
  end create
end Permissions
