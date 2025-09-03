package app.auth

import cats.data.NonEmptyVector

import app.ThalesUtils.ExtensionMethodUtils.*
import doobie.Meta

object Permissions:
  enum Permission:
    // App Level permissions
    case CanSeeAdminApp
    case CanUseRiskApp
    case CanUseBoApp

    // Capability permissions
    case CanCreateBoUsers
    case CanFetchBoUsers

    case CanCreateBoRoles
    case CanDeleteBoRoles
    // Admin permissions
    case CanSeeAllLiveSessions

    case CanRenewJwtToken
    case CanSeeAllBoPermissions
    case CanSeeAllBoRoles
  end Permission

  private val PermissionsMap: Map[String, Permission] =
    Permission.values.view.map(p => p.toString -> p).toMap
  end PermissionsMap

  def fromString(s: String): Permission =
    PermissionsMap.getOrElse(s, throw AssertionError(s"Bad permission '$s'."))
  end fromString

  given Meta[Permission] =
    Meta[String].imap[Permission](fromString)(_.toString)

  final case class PermissionInDb(permissionId: Long, permissionName: String)

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
end Permissions
