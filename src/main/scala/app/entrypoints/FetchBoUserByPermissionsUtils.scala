package app.entrypoints

import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra}

object FetchBoUserByPermissionsUtils:
  val FetchBoUserPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanSeeBoUsers).compile
  end FetchBoUserPermissionsAlg
end FetchBoUserByPermissionsUtils
