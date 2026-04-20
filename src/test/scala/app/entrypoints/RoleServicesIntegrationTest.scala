package app.entrypoints

import cats.data.NonEmptyVector
import cats.effect.{IO, Resource}
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*

import org.scalatest.OptionValues.*
import org.scalatest.EitherValues.*
import org.scalatest.Inside.*
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import app.ThalesServer
import app.entrypoints.TestUtils.given
import app.entrypoints.TestUtils as TU
import app.entrypoints.smithy.{LoginName, PermissionId, PermissionInDb, PermissionName, Role, RoleId, RoleIdVector, RoleInDb, RoleName, RoleNotFound, RoleServices, UserPassword}
import fs2.Stream
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
      .fetchRolesPermissionsById(RoleIdVector(NonEmptyVector.one(roleId)))
      .map(_.roleIdToPermissions.get(roleId.toString))
  end fetchRolesPermissionsById

  "RoleServices Integration" - {
    "Full Lifecycle" in
      ThalesServer
        .createLogger[IO]
        .flatMap: logger =>
          val baseClientResource = TU.startServer(logger) *> TU.clientResource

          val (u0, p0) = (LoginName("neo"), UserPassword("AReal235711Secret!"))
          val roleArchitect = Role(roleName = RoleName("Architect"))
          val roleDeletable = Role(roleName = RoleName("DeletableRole"))
          val roleListable = Role(roleName = RoleName("ListableRole"))

          val permissionsOfRole0 = Vector(
            PermissionInDb(PermissionId(1), PermissionName("CanSeeUsers")),
            PermissionInDb(PermissionId(2), PermissionName("CanCreateRoles")),
            PermissionInDb(PermissionId(3), PermissionName("CanDeleteRoles")),
            PermissionInDb(PermissionId(4), PermissionName("CanSeeUserRoles")),
            PermissionInDb(PermissionId(5), PermissionName("CanRenewJwtToken")),
            PermissionInDb(PermissionId(6), PermissionName("CanSeeAllPermissions")),
            PermissionInDb(PermissionId(7), PermissionName("CanSeeAllRoles")),
            PermissionInDb(PermissionId(8), PermissionName("CanResetMyPassword")),
            PermissionInDb(PermissionId(9), PermissionName("CanCheckResetUserPasswordToken")),
            PermissionInDb(PermissionId(10), PermissionName("CanSetMustResetUserPassword")),
            PermissionInDb(PermissionId(11), PermissionName("CanUpdateUserRoles")),
            PermissionInDb(PermissionId(12), PermissionName("CanSeeAllLiveSessions")),
            PermissionInDb(PermissionId(0), PermissionName("CanCreateUsers")),
          )

          baseClientResource.use: baseClient =>
            for
              authClient <-
                TU.loginAndGetToken(baseClient, u0, p0)
                  .map: token =>
                    TU.mkAuthedClient(baseClient, token)

              (role0, roleId1, roleId2, fetched1, fetched2, deletedPermissionsAttempt, mixedPermissionsAttempt, allRoles, permissions0, permissions1) <-
                roleServicesResource(authClient).use: roleServices =>
                  for
                    // 0. Fetch role
                    role0 <- fetchRole(roleServices, RoleId(0L))

                    // 1. Create Role
                    roleId1 <- createRole(roleServices, roleArchitect)
                    fetched1 <- fetchRole(roleServices, roleId1)

                    // 2. Delete Role
                    roleId2 <- createRole(roleServices, roleDeletable)
                    _ <- deleteRoleById(roleServices, roleId2)
                    fetched2 <- fetchRole(roleServices, roleId2)
                    deletedPermissionsAttempt <- fetchRolesPermissionsById(roleServices, roleId2).attempt
                    mixedPermissionsAttempt <- roleServices.fetchRolesPermissionsById(RoleIdVector(NonEmptyVector.of(roleId1, roleId2))).attempt

                    // 3. Fetch All Roles
                    _ <- createRole(roleServices, roleListable)
                    allRoles <- fetchAllRoles(roleServices)

                    // 4. Fetch Permissions
                    permissions0 <- fetchRolesPermissionsById(roleServices, RoleId(0L))
                    permissions1 <- fetchRolesPermissionsById(roleServices, roleId1)

                    // 5. Parallel tests
                    tasks = Vector(
                      fetchRole(roleServices, RoleId(0L)),
                      fetchAllRoles(roleServices),
                      fetchRolesPermissionsById(roleServices, RoleId(0L)),
                      fetchRolesPermissionsById(roleServices, roleId1),
                    )
                    _ <- Stream
                      .emits(Vector.fill(20)(tasks).flatten) // 20 * 4 = 80 requests to the server
                      .covary[IO]
                      .mapAsync(15)(identity) // executed 15 at a time
                      .compile
                      .drain
                  yield (role0, roleId1, roleId2, fetched1, fetched2, deletedPermissionsAttempt, mixedPermissionsAttempt, allRoles, permissions0, permissions1)
            yield
              // 0. Fetch role
              role0.value.roleName shouldBe RoleName("Admin")

              // 1. Create Role
              roleId1.value should be > 0L
              fetched1.value.roleName shouldBe roleArchitect.roleName

              // 2. Delete Role
              fetched2 shouldBe empty
              deletedPermissionsAttempt.isLeft shouldBe true
              inside(mixedPermissionsAttempt.left.value):
                case RoleNotFound(msg) =>
                  msg shouldBe s"The following role ids were not present in the database: [${roleId2.value}]."

              // 3. Fetch All Roles
              allRoles.map(_.roleName) should contain(roleListable.roleName)

              // 4. Fetch Permissions
              permissions0.value should contain theSameElementsAs permissionsOfRole0
              permissions1.value shouldBe empty
  }
end RoleServicesIntegrationTest
