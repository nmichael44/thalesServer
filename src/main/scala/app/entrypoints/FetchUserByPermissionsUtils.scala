package app.entrypoints

import app.auth.Permissions
import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra}

object FetchUserByPermissionsUtils:
  val FetchUserPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permissions.CanSeeUsers).compile
  end FetchUserPermissionsAlg
end FetchUserByPermissionsUtils
