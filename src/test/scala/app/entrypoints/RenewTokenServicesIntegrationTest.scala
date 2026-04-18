package app.entrypoints

import cats.effect.{IO, Resource}
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*

import org.scalatest.EitherValues.convertLeftProjectionToValuable
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import app.ThalesServer
import app.entrypoints.TestUtils as TU
import app.entrypoints.smithy.{LoginName, RenewTokenServices, User, UserForbiddenFromCallingEntryPoint, UserId, UserPassword, UserServices}
import app.model.JavaInstant
import org.http4s.client.Client
import fs2.Stream
import smithy4s.http4s.SimpleRestJsonBuilder

final class RenewTokenServicesIntegrationTest extends AsyncFreeSpec with AsyncIOSpec with Matchers:
  private def mkRenewTokenServicesResource(client: Client[IO]): Resource[IO, RenewTokenServices[IO]] =
    SimpleRestJsonBuilder(app.entrypoints.smithy.RenewTokenServices)
      .client(client)
      .uri(TU.serverUri)
      .resource
  end mkRenewTokenServicesResource

  private def mkUserServicesResource(client: Client[IO]): Resource[IO, UserServices[IO]] =
    SimpleRestJsonBuilder(app.entrypoints.smithy.UserServices)
      .client(client)
      .uri(TU.serverUri)
      .resource
  end mkUserServicesResource

  private def renewJwtToken(renewTokenServices: RenewTokenServices[IO]): IO[String] =
    renewTokenServices.renewJwtToken().map(_.newToken)
  end renewJwtToken

  "RenewTokenServices Integration" - {
    "Renew JWT Token" in
      ThalesServer
        .createLogger[IO]
        .flatMap: logger =>
          val baseClientResource = TU.startServer(logger) *> TU.clientResource

          val (u0, p0) = (LoginName("neo"), UserPassword("AReal235711Secret!"))

          baseClientResource.use: baseClient =>
            for
              token <- TU.loginAndGetToken(baseClient, u0, p0)
              authClient = TU.mkAuthedClient(baseClient, token)

              newToken <- mkRenewTokenServicesResource(authClient).use: renewTokenServices =>
                renewJwtToken(renewTokenServices)
            yield
              newToken should not be empty
              newToken should not be token

    "Cannot renew token if password reset is required" in
      ThalesServer
        .createLogger[IO]
        .flatMap: logger =>
          val baseClientResource = TU.startServer(logger) *> TU.clientResource

          val (adminUser, adminPass) = (LoginName("neo"), UserPassword("AReal235711Secret!"))
          val now = JavaInstant(java.time.Instant.now)
          val newUser = User(
            loginName = LoginName("morpheus"),
            firstName = "Morpheus",
            lastName = "Nebuchadnezzar",
            email = "morpheus@zion.org",
            phone = "555-000-0000",
            creationTime = now,
            password = UserPassword("BluePillRedPill1!"),
            mustResetPassword = false,
            userPasswordUpdateTime = now,
            enabled = true,
            creatingUserId = UserId(0L),
          )

          baseClientResource.use: baseClient =>
            for
              adminToken <- TU.loginAndGetToken(baseClient, adminUser, adminPass)
              adminClient = TU.mkAuthedClient(baseClient, adminToken)
              adminUserServicesResource = mkUserServicesResource(adminClient)

              result <- adminUserServicesResource.use: adminUserServices =>
                for
                  createdUserId <- adminUserServices.createUser(newUser).map(_.userId)
                  userToken <- TU.loginAndGetToken(baseClient, newUser.loginName, newUser.password)
                  userClient = TU.mkAuthedClient(baseClient, userToken)
                  renewTokenServicesResource = mkRenewTokenServicesResource(userClient)
                  _ <- adminUserServices.setMustResetUserPassword(createdUserId, true)
                  res <- renewTokenServicesResource.use(_.renewJwtToken().attempt)
                yield res
            yield result.left.value shouldBe a[UserForbiddenFromCallingEntryPoint]
  }
end RenewTokenServicesIntegrationTest
