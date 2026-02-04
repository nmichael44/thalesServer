package app.entrypoints

import cats.effect.{IO, Resource}
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*

import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import app.ThalesServer
import app.ThalesUtils.GenUtils as U
import app.entrypoints.TestUtils.given
import app.entrypoints.TestUtils as TU
import app.entrypoints.smithy.{LoginName, PermissionId, PermissionInDb, PermissionName, PermissionServices, UserPassword}
import org.http4s.client.Client
import smithy4s.http4s.SimpleRestJsonBuilder

final class PermissionServicesIntegrationTest extends AsyncFreeSpec with AsyncIOSpec with Matchers:
  private def permissionServicesResource(client: Client[IO]): Resource[IO, PermissionServices[IO]] =
    SimpleRestJsonBuilder(app.entrypoints.smithy.PermissionServices)
      .client(client)
      .uri(TU.serverUri)
      .resource
  end permissionServicesResource

  private def fetchAllPermissions(permissionServices: PermissionServices[IO]): IO[Map[String, PermissionInDb]] =
    permissionServices.fetchAllPermissions().map(_.permissions)
  end fetchAllPermissions

  "PermissionServices Integration" - {
    "Fetch All Permissions" in {
      ThalesServer.createLogger[IO] >>= { logger =>
        val baseClientResource = TU.startServer(logger) *> TU.clientResource

        val (u0, p0) = (LoginName("neo"), UserPassword("AReal235711Secret!"))

        baseClientResource.use: baseClient =>
          for
            authClient <-
              TU.loginAndGetToken(baseClient, u0, p0)
                .map: token =>
                  TU.mkAuthedClient(baseClient, token)

            permissions <- permissionServicesResource(authClient).use: permissionServices =>
              fetchAllPermissions(permissionServices).map(_.map(U.mapFirst(_.toInt)))
          yield
            permissions should not be empty
            (permissions should contain).key(0)
            permissions(0) shouldBe PermissionInDb(PermissionId(0), PermissionName("CanCreateUsers"))
            (permissions should contain).key(9)
            permissions(9) shouldBe PermissionInDb(PermissionId(9), PermissionName("CanCheckResetUserPasswordToken"))
      }
    }
  }
end PermissionServicesIntegrationTest
