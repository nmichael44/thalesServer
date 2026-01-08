package app.entrypoints

import cats.data.NonEmptyVector
import cats.effect.{IO, Resource}
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*

import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import TestUtils.given
import app.ThalesServer
import app.entrypoints.smithy.{LoginName, LoginNameList, LoginServices, UserInDb, UserPassword, UserServices}
import org.http4s.{AuthScheme, Credentials, Request}
import org.http4s.client.Client
import org.http4s.headers.Authorization
import smithy4s.http4s.SimpleRestJsonBuilder

final class UserServicesIntegrationTest extends AsyncFreeSpec with AsyncIOSpec with Matchers:
  private def loginServicesResource(client: Client[IO]): Resource[IO, LoginServices[IO]] =
    SimpleRestJsonBuilder(app.entrypoints.smithy.LoginServices)
      .client(client)
      .uri(TestUtils.serverUri)
      .resource
  end loginServicesResource

  private def userServicesResource(client: Client[IO]): Resource[IO, UserServices[IO]] =
    SimpleRestJsonBuilder(app.entrypoints.smithy.UserServices)
      .client(client)
      .uri(TestUtils.serverUri)
      .resource
  end userServicesResource

  private def loginAndGetToken(client: Client[IO], loginName: LoginName, password: UserPassword): IO[String] =
    loginServicesResource(client).use(_.login(loginName, password).map(_.token))
  end loginAndGetToken

  private def fetchUserByName(
      userServices: UserServices[IO],
      loginNames: NonEmptyVector[LoginName],
  ): IO[Map[String, UserInDb]] =
    userServices.fetchUsersByLoginNames(LoginNameList(loginNames)).map(_.users)
  end fetchUserByName

  private def addAuthHeader(req: Request[IO], token: String): Request[IO] =
    req.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
  end addAuthHeader

  "UserServices Integration" - {
    "should fetch users by user IDs" in {
      ThalesServer.createLogger[IO] >>= { implicit logger =>
        val baseClientResource = for {
          _ <- TestUtils.setEnvVariables.toResource
          _ <- TestUtils.resetDatabase.toResource
          _ <- ThalesServer.applicationResource[IO]
          client <- TestUtils.clientResource
        } yield client

        baseClientResource.use { baseClient =>
          for {
            token <- loginAndGetToken(baseClient, LoginName("neo"), UserPassword("AReal235711Secret!"))
            authClient = Client[IO] { req =>
              val reqWithAuth = addAuthHeader(req, token)
              baseClient.run(reqWithAuth)
            }
            users <- userServicesResource(authClient).use { userServices =>
              fetchUserByName(userServices, NonEmptyVector.of(LoginName("neo"), LoginName("brent")))
            }
          } yield {
            users should (contain.key("neo").and(contain.key("brent")))
            users("neo").firstName shouldBe "Neophytos"
            users("brent").firstName shouldBe "Brent"
          }
        }
      }
    }
  }
end UserServicesIntegrationTest
