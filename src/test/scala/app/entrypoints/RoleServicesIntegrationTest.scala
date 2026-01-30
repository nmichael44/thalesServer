package app.entrypoints

import cats.data.NonEmptyVector
import cats.effect.{IO, Resource}
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*

import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import app.ThalesServer
import app.entrypoints.TestUtils.roleNameEq
import app.entrypoints.TestUtils as TU
import app.entrypoints.smithy.{LoginName, Role, RoleId, RoleIdVector, RoleInDb, RoleName, RoleServices, UserPassword}
import org.http4s.client.Client
import org.http4s.client.middleware.GZip
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

  private def getRoleName(role: Option[RoleInDb]): Option[RoleName] =
    role.map(_.roleName)
  end getRoleName

  "RoleServices Integration" - {
    "Full Lifecycle" in {
      ThalesServer.createLogger[IO] >>= { logger =>
        val baseClientResource = TU.startServer(logger) *> TU.clientResource

        val (u0, p0) = (LoginName("neo"), UserPassword("AReal235711Secret!"))
        val roleArchitect = Role(roleName = RoleName("Architect"))

        baseClientResource.use: baseClient =>
          for
            authClient <- TU
              .loginAndGetToken(baseClient, u0, p0)
              .map: token =>
                GZip()(TU.mkAuthedClient(baseClient, token))

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
              yield ()
          yield succeed
      }
    }
  }
end RoleServicesIntegrationTest
