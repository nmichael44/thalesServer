package app.entrypoints

import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra}

object FetchUserByPermissionsUtils:
  val FetchUserPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanSeeBoUsers).compile
  end FetchUserPermissionsAlg
end FetchUserByPermissionsUtils
