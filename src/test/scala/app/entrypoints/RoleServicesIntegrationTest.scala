package app.entrypoints

import cats.effect.{IO, Resource}
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*

import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import TestUtils.given
import app.ThalesServer
import app.entrypoints.TestUtils as TU
import app.entrypoints.smithy.{LoginName, Role, RoleId, RoleName, RoleServices, UserPassword}
import org.http4s.client.Client
import org.http4s.client.middleware.GZip
import smithy4s.http4s.SimpleRestJsonBuilder

final class RoleServicesIntegrationTest extends AsyncFreeSpec with AsyncIOSpec with Matchers:
  private def roleServicesResource(client: Client[IO]): Resource[IO, RoleServices[IO]] =
    SimpleRestJsonBuilder(app.entrypoints.smithy.RoleServices)
      .client(client)
      .uri(TestUtils.serverUri)
      .resource
  end roleServicesResource

  private def createRole(roleServices: RoleServices[IO], role: Role): IO[RoleId] =
    roleServices.createRole(role).map(_.roleId)
  end createRole

  "RoleServices Integration" - {
    "Create Role" in {
      ThalesServer.createLogger[IO] >>= { implicit logger =>
        val baseClientResource =
          for
            _ <- TestUtils.setEnvVariables.toResource
            _ <- TestUtils.resetDatabase.toResource
            _ <- ThalesServer.applicationResource[IO]
            client <- TestUtils.clientResource
          yield client

        val (u0, p0) = (LoginName("neo"), UserPassword("AReal235711Secret!"))
        val newRole = Role(roleName = RoleName("Architect"))

        baseClientResource.use: baseClient =>
          for
            authClient <- TU
              .loginAndGetToken(baseClient, u0, p0)
              .map: token =>
                GZip()(TU.mkAuthedClient(baseClient, token))

            roleId <- roleServicesResource(authClient).use: roleServices =>
              createRole(roleServices, newRole)
          yield roleId.value should be > 0L
      }
    }
  }
end RoleServicesIntegrationTest
