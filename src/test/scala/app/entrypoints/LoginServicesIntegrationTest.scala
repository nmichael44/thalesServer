package app.entrypoints

import cats.effect.{IO, Resource}
import cats.effect.kernel.Async
import cats.effect.std.Env
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*

import scala.collection.View

import org.scalatest.Assertion
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import app.ThalesServer
import fs2.compression.Compression
import fs2.io.net.Network
import fs2.io.net.tls.TLSContext
import org.http4s.{HttpVersion, Method, Request, Status, Uri}
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder

final class LoginServicesIntegrationTest extends AsyncFreeSpec with AsyncIOSpec with Matchers:
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

  private def checkStatusCode(user: String, pass: String, expectedStatus: Status)(using
      client: Client[F],
      uri: Uri,
  ): IO[Assertion] =
    val request = Request[IO](method = Method.POST, uri = uri, httpVersion = HttpVersion.`HTTP/2`)
      .withEntity(s"""{"loginName": "$user", "password": "$pass"}""")

    client.run(request).use { response =>
      response.body.compile.drain *>
        IO(response.status.code shouldBe expectedStatus.code)
    }
  end checkStatusCode

  "LoginServices Integration" - {
    "should handle login requests (example: reject invalid credentials)" in {
      ThalesServer.createLogger[F] >>= { implicit logger =>
        ThalesServer.applicationResource[F].use { case (server, _) =>
          clientResource.use { implicit client =>
            implicit val uri: Uri =
              server.baseUri.copy(
                authority = server.baseUri.authority.map(_.copy(host = Uri.RegName("localhost"))),
              ) / "login"

            // Non-existent user. Expecting 401 Unauthorized.
            val req1 = checkStatusCode("invalid-user", "wrong-password", Status.Unauthorized)

            // Existent user but wrong password. Expecting 401 Unauthorized.
            val req2 = checkStatusCode("neo", "wrong-password", Status.Unauthorized)

            // Expecting 200 Ok. Existent user with correct password.
            val req3 = checkStatusCode("neo", "AReal235711Secret!", Status.Ok)

            List(req1, req2, req3).sequenceVoid.as(succeed)
          }
        }
      }
    }
  }
end LoginServicesIntegrationTest
