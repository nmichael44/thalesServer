package app.entrypoints

import cats.data.NonEmptyVector
import cats.effect.{IO, Resource}
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*

import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import app.ThalesServer
import app.entrypoints.TestUtils.given
import app.entrypoints.TestUtils as TU
import app.entrypoints.smithy.{LoginName, PermissionId, PermissionInDb, PermissionName, Role, RoleId, RoleIdVector, RoleInDb, RoleName, RoleServices, UserPassword}
import org.http4s.client.Client
import smithy4s.http4s.SimpleRestJsonBuilder

final class RoleServicesIntegrationTest extends AsyncFreeSpec with AsyncIOSpec with Matchers:
  private def roleServicesResource(client: Client[IO]): Resource[IO, RoleServices[IO]] =
    SimpleRestJsonBuilder(app.entrypoints.smithy.RoleServices)
      .client(client)
      .uri(TU.serverUri)
      .resource
  end roleServicesResource

  private def fetchRole(roleServices: RoleServices[IO], roleId: RoleId): IO[Option[RoleInDb]] =
    roleServices
      .fetchRolesByIds(RoleIdVector(NonEmptyVector.of(roleId)))
      .map(_.roleIdToRole.get(roleId.toString))
  end fetchRole

  private def createRole(roleServices: RoleServices[IO], role: Role): IO[RoleId] =
    roleServices.createRole(role).map(_.roleId)
  end createRole

  private def fetchAllRoles(roleServices: RoleServices[IO]): IO[Vector[RoleInDb]] =
    roleServices.fetchAllRoles().map(_.roles)
  end fetchAllRoles

  private def deleteRoleById(roleServices: RoleServices[IO], roleId: RoleId): IO[Unit] =
    roleServices.deleteRoleById(roleId)
  end deleteRoleById

  private def fetchRolesPermissionsById(roleServices: RoleServices[IO], roleId: RoleId): IO[Option[Vector[PermissionInDb]]] =
    roleServices
      .fetchRolesPermissionsById(RoleIdVector(NonEmptyVector.of(roleId)))
      .map(_.roleIdToPermissions.get(roleId.toString))
  end fetchRolesPermissionsById

  private def getRoleName(role: Option[RoleInDb]): Option[RoleName] =
    role.map(_.roleName)
  end getRoleName

  "RoleServices Integration" - {
    "Full Lifecycle" in {
      ThalesServer.createLogger[IO] >>= { logger =>
        val baseClientResource = TU.startServer(logger) *> TU.clientResource

        val (u0, p0) = (LoginName("neo"), UserPassword("AReal235711Secret!"))
        val roleArchitect = Role(roleName = RoleName("Architect"))

        val permissionsOfRole0 = Vector(
          PermissionInDb(PermissionId(1), PermissionName("CanSeeUsers")),
          PermissionInDb(PermissionId(2), PermissionName("CanCreateRoles")),
          PermissionInDb(PermissionId(3), PermissionName("CanDeleteRoles")),
          PermissionInDb(PermissionId(4), PermissionName("CanSeeAllLiveSessions")),
          PermissionInDb(PermissionId(5), PermissionName("CanRenewJwtToken")),
          PermissionInDb(PermissionId(6), PermissionName("CanSeeAllPermissions")),
          PermissionInDb(PermissionId(7), PermissionName("CanSeeAllRoles")),
          PermissionInDb(PermissionId(8), PermissionName("CanResetMyPassword")),
          PermissionInDb(PermissionId(9), PermissionName("CanCheckResetUserPasswordToken")),
          PermissionInDb(PermissionId(10), PermissionName("CanSetMustResetUserPassword")),
          PermissionInDb(PermissionId(0), PermissionName("CanCreateUsers")),
        )

        def sortPermissionsVecByRoleId(perms: Vector[PermissionInDb]): Vector[PermissionInDb] =
          perms.sortBy(_.permissionId.value)
        end sortPermissionsVecByRoleId

        baseClientResource.use: baseClient =>
          for
            authClient <-
              TU.loginAndGetToken(baseClient, u0, p0)
                .map: token =>
                  TU.mkAuthedClient(baseClient, token)

            _ <- roleServicesResource(authClient).use: roleServices =>
              for
                // 0. Fetch role
                role0 <- fetchRole(roleServices, RoleId(0))
                _ = getRoleName(role0) shouldBe Some(RoleName("Admin"))

                // 1. Create Role
                roleId1 <- createRole(roleServices, roleArchitect)
                fetched1 <- fetchRole(roleServices, roleId1)
                _ = roleId1.value should be > 0L
                _ = getRoleName(fetched1) shouldBe Some(roleArchitect.roleName)

                // 2. Delete Role
                roleDeletable = Role(roleName = RoleName("DeletableRole"))
                roleId2 <- createRole(roleServices, roleDeletable)
                _ <- deleteRoleById(roleServices, roleId2)
                fetched2 <- fetchRole(roleServices, roleId2)
                _ = fetched2 shouldBe None

                // 3. Fetch All Roles
                roleListable = Role(roleName = RoleName("ListableRole"))
                roleId3 <- createRole(roleServices, roleListable)
                allRoles <- fetchAllRoles(roleServices)
                _ = allRoles.exists(_.roleName == roleListable.roleName) shouldBe true

                // 4. Fetch Permissions
                permissions0 <- fetchRolesPermissionsById(roleServices, RoleId(0L))
                _ = permissions0.map(sortPermissionsVecByRoleId) shouldBe Some(sortPermissionsVecByRoleId(permissionsOfRole0))
                permissions1 <- fetchRolesPermissionsById(roleServices, roleId1)
                _ = permissions1 shouldBe Some(Vector.empty[PermissionInDb])
              yield ()
          yield succeed
      }
    }
  }
end RoleServicesIntegrationTest
