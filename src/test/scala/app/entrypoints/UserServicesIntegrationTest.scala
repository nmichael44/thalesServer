package app.entrypoints

import cats.effect.{IO, Resource}
import cats.effect.kernel.Async
import cats.effect.std.Env
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*

import scala.collection.View

import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import app.ThalesServer
import app.entrypoints.SmithyCodecs.given
import app.entrypoints.smithy.UserInDb
import fs2.compression.Compression
import fs2.io.net.Network
import fs2.io.net.tls.TLSContext
import io.circe.{Decoder, Json}
import io.circe.generic.auto.*
import org.http4s.{AuthScheme, Credentials, HttpVersion, Method, Request, Status, Uri}
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.headers.Authorization

final class UserServicesIntegrationTest extends AsyncFreeSpec with AsyncIOSpec with Matchers:
  type F = IO

  View(
    "DB_SERVER_HOST"       -> "localhost",
    "DB_SERVER_PORT"       -> "5432",
    "DB_USERNAME"          -> "thalesuser",
    "DB_USERNAME_PASSWORD" -> "thalesUser11",
    "DB_NAME"              -> "thalesdb",
    "JWT_SECRET_KEY"       -> "myTopSecretKey!",
    "KEYSTORE_PASSWORD"    -> "hgt67Y3!l9",
    "KEYSTORE_FILE"        -> "certs/keystore.p12",
  ).foreach { case (k, v) => System.setProperty(k, v) }

  // Implicits required by ThalesServer.applicationResource().
  given Async[F] = IO.asyncForIO
  given Env[F] = IO.envForIO
  given Network[F] = Network.forAsync[F]
  given Compression[F] = Compression.forSync[F]

  // Client that trusts the server's self-signed cert
  val clientResource: Resource[F, Client[F]] =
    Resource.eval(TLSContext.Builder.forAsync[F].insecure) >>= { tlsContext =>
      EmberClientBuilder
        .default[F]
        .withTLSContext(tlsContext)
        .withHttp2
        .build
    }

  private def loginAndGetToken(baseUri: Uri)(using client: Client[F]): IO[String] =
    val uri = baseUri / "login"
    val request = Request[IO](method = Method.POST, uri = uri, httpVersion = HttpVersion.`HTTP/2`)
      .withEntity("""{"loginName": "neo", "password": "AReal235711Secret!"}""")

    client.run(request).use { response =>
      response.status.code shouldBe Status.Ok.code
      response.as[Json].map { json =>
        json.hcursor
          .downField("token")
          .as[String]
          .getOrElse(throw new RuntimeException(s"Token not found in response: $json"))
      }
    }
  end loginAndGetToken

  private def fetchUserByName(baseUri: Uri, token: String, loginName: String)(using client: Client[F]): IO[String] =
    val uri = baseUri / "api" / "fetchUserByLoginNames"
    val request = Request[IO](method = Method.POST, uri = uri, httpVersion = HttpVersion.`HTTP/2`)
      .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
      .withEntity(s"""{"loginNames": ["$loginName"]}""")

    client.run(request).use { response =>
      response.status.code shouldBe Status.Ok.code
      response.as[Json].map { json =>
        println("I am here and json is:")
        println(json.toString)
        // Extract userId from JSON response: { "users": { "loginName": { "userId": "..." } } }
        json.hcursor
          .downField("users")
          .downField(loginName)
          .downField("userId")
          .as[String]
          .getOrElse(throw new RuntimeException(s"UserId not found in response: $json"))
      }
    }
  end fetchUserByName

  "UserServices Integration" - {
    "should fetch users by user IDs" in {
      ThalesServer.createLogger[F] >>= { implicit logger =>
        ThalesServer.applicationResource[F].use { case (server, _) =>
          clientResource.use { implicit client =>
            val baseUri = server.baseUri.copy(
              authority = server.baseUri.authority.map(_.copy(host = Uri.RegName("localhost"))),
            )

            for
              token <- loginAndGetToken(baseUri)
              userId <- fetchUserByName(baseUri, token, "neo")

              uri = baseUri / "api" / "fetchUsersByUserIds"
              req = Request[IO](method = Method.POST, uri = uri, httpVersion = HttpVersion.`HTTP/2`)
                .withHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
                .withEntity(s"""{"userIds": ["$userId"]}""")

              assertion <- client.run(req).use { response =>
                response.status.code shouldBe Status.Ok.code
                response.as[Json].map { json =>
                  val usersMap = json.hcursor
                    .downField("users")
                    .as[Map[String, UserInDb]]
                    .getOrElse(fail(s"Failed to decode users map from: $json"))

                  (usersMap should contain).key(userId)
                  usersMap(userId).loginName.value shouldBe "neo"
                }
              }
            yield assertion
          }
        }
      }
    }
  }
end UserServicesIntegrationTest
