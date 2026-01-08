package app.entrypoints

import cats.effect.{IO, Ref, Resource}
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*

import scala.collection.View

import org.scalatest.Assertion
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import TestUtils.given
import app.ThalesServer
import app.entrypoints.smithy.{LoginName, LoginServices, PasswordResetRequired, Unauthenticated, UserNotEnabled, UserPassword}
import org.http4s.Status
import org.http4s.client.Client
import smithy4s.http4s.SimpleRestJsonBuilder

final class LoginServicesIntegrationTest extends AsyncFreeSpec with AsyncIOSpec with Matchers:
  private def loginServicesResource(client: Client[IO]): Resource[IO, LoginServices[IO]] =
    SimpleRestJsonBuilder(app.entrypoints.smithy.LoginServices)
      .client(client)
      .uri(TestUtils.serverUri)
      .resource
  end loginServicesResource

  private def checkStatusCode(
      loginService: LoginServices[IO],
      capturedStatus: Ref[IO, Option[Status]],
      loginName: LoginName,
      password: UserPassword,
      expectedStatus: Status,
  ): IO[Assertion] = for {
    _ <- capturedStatus.set(None)
    result <- loginService.login(loginName, password).attempt
    actualStatusOpt <- capturedStatus.get
  } yield {
    actualStatusOpt match {
      case Some(status) =>
        if (status.code != expectedStatus.code)
          fail(s"Protocol Error: Server returned HTTP $status, expected $expectedStatus.")
      case None =>
        fail("Test Error: Client did not capture a status code (request might have failed locally).")
    }

    (result, expectedStatus.code) match {
      case (Right(_), Status.Ok.code) => succeed
      case (Left(_: Unauthenticated), Status.Unauthorized.code) => succeed
      case (Left(_: PasswordResetRequired), Status.Forbidden.code) => succeed
      case (Left(_: UserNotEnabled), Status.Locked.code) => succeed
      case (other, _) =>
        fail(s"Logic Error: Status code was correct, but Smithy4s returned unexpected result: $other")
    }
  }
  end checkStatusCode

  private def loginTests: Vector[(LoginName, UserPassword, Status)] = View(
    ("non-existent-user", "abc", Status.Unauthorized),      // Non-existent user. Expecting 401 Unauthorized.
    ("neo", "wrong-password", Status.Unauthorized),         // Existent user but wrong password. Expecting 401 Unauthorized.
    ("neo", "AReal235711Secret!", Status.Ok),               // Existent user with correct password. Expecting 200 Ok.
    ("DisabledLoginName", "abc", Status.Locked),            // Disabled user. Expecting 423 Locked.
    ("MustResetPasswordLoginName", "abc", Status.Forbidden), // User with password reset required. Expecting 403 Forbidden.
  ).map((loginName, password, expectedStatus) => (LoginName(loginName), UserPassword(password), expectedStatus)).toVector

  "LoginServices Integration" - {
    "should handle login requests (example: reject invalid credentials)" in {
      ThalesServer.createLogger[IO] >>= { implicit logger =>
        val baseClientResource = for {
          _ <- TestUtils.setEnvVariables.toResource
          _ <- TestUtils.resetDatabase.toResource
          (server, _) <- ThalesServer.applicationResource[IO]
          baseClient <- TestUtils.clientResource
        } yield baseClient

        baseClientResource.use { baseClient =>
          loginTests
            .parTraverse { case (loginName, password, expectedStatus) =>
              for {
                statusRef <- Ref.of[IO, Option[Status]](None)
                spyClient = Client[IO] { req =>
                  baseClient.run(req).evalTap(response => statusRef.set(Some(response.status)))
                }
                result <- loginServicesResource(spyClient).use { loginService =>
                  checkStatusCode(loginService, statusRef, loginName, password, expectedStatus)
                }
              } yield result
            }
            .as(succeed)
        }
      }
    }
  }
end LoginServicesIntegrationTest
