package app.auth

import scala.quoted.*

import app.entrypoints.smithy.{PermissionId, PermissionInDb, PermissionName}

object PermissionMacros:
  inline def mkPerm(inline permId: PermissionId): (PermissionId, PermissionInDb) =
    ${ mkPermImpl('permId) }
  end mkPerm

  private def mkPermImpl(permIdExpr: Expr[PermissionId])(using Quotes): Expr[(PermissionId, PermissionInDb)] =
    import quotes.reflect.*

    val name = permIdExpr.asTerm.underlyingArgument match
      case Ident(n) => n
      case Select(_, n) => n // Strips prefixes if someone calls `Permissions.CanCreateUsers`
      case other => report.errorAndAbort(s"Expected an identifier but got: '${other.show}'.")

    '{ ($permIdExpr, PermissionInDb($permIdExpr, PermissionName(${ Expr(name) }))) }
  end mkPermImpl
end PermissionMacros
