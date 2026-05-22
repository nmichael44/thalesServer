package app.entrypoints

import cats.effect.{IO, Ref}
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*

import scala.collection.View

import org.scalatest.Assertion
import org.scalatest.OptionValues.convertOptionToValuable
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import app.ThalesServer
import app.entrypoints.TestUtils as TU
import app.entrypoints.smithy.{InvalidUserNameOrPassword, LoginName, LoginServices, UserIsDisabled, UserMustResetPassword, UserPassword}
import org.http4s.Status
import org.http4s.client.Client

final class LoginServicesIntegrationTest extends AsyncFreeSpec with AsyncIOSpec with Matchers:
  private def checkStatusCode(
      loginService: LoginServices[IO],
      capturedStatus: Ref[IO, Option[Status]],
      loginName: LoginName,
      password: UserPassword,
      expectedStatus: Status,
      cOpt: Option[Class[? <: Throwable]],
  ): IO[Assertion] =
    for
      _ <- capturedStatus.set(None)
      result <- loginService.login(loginName, password).attempt
      actualStatusOpt <- capturedStatus.get
    yield
      actualStatusOpt match
        case Some(status) =>
          if (status.code != expectedStatus.code)
            fail(s"Protocol Error: Server returned HTTP $status, expected $expectedStatus.")
        case None =>
          result match
            case Left(e) =>
              e.printStackTrace()
              fail(s"Test Error: Client did not capture a status code (request might have failed locally). Exception: $e")
            case _ =>
              fail("Test Error: Client did not capture a status code (request might have failed locally).")

      result match {
        case Right(_) => cOpt shouldBe empty
        case Left(e) => cOpt.value.getName shouldBe e.getClass.getName
      }
  end checkStatusCode

  private def loginTests: Vector[(LoginName, UserPassword, Status, Option[Class[? <: Throwable]])] = View(
    // Non-existent user. Expecting 401 Unauthorized.
    ("non-existent-user", "abc", Status.Unauthorized, Some(classOf[InvalidUserNameOrPassword])),
    // Existent user but wrong password. Expecting 401 Unauthorized.
    ("neo", "wrong-password", Status.Unauthorized, Some(classOf[InvalidUserNameOrPassword])),
    // Existent user with correct password. Expecting 200 Ok.
    ("neo", "AReal235711Secret!", Status.Ok, None),
    // Disabled user. Expecting 403 Forbidden.
    ("DisabledLoginName", "AReal235711Secret!", Status.Forbidden, Some(classOf[UserIsDisabled])),
    // User with password reset required. Expecting 403 Forbidden.
    ("MustResetPasswordLoginName", "AReal235711Secret!", Status.Forbidden, Some(classOf[UserMustResetPassword])),
  ).map: (loginName, password, expectedStatus, cOpt) =>
    (LoginName(loginName), UserPassword(password), expectedStatus, cOpt)
  .toVector
  end loginTests

  "LoginServices Integration" - {
    "should handle login requests (example: reject invalid credentials)" in
      ThalesServer
        .createLogger[IO]
        .flatMap: logger =>
          val clientResource = TU.startServer(logger) *> TU.clientResource

          clientResource.use: baseClient =>
            loginTests
              .traverseVoid: (loginName, password, expectedStatus, cOpt) =>
                for
                  statusRef <- IO.ref(none[Status])
                  spyClient = Client[IO]: req =>
                    baseClient.run(req).evalTap(response => statusRef.set(Some(response.status)))

                  _ <- TU
                    .loginServicesResource(spyClient)
                    .use: loginService =>
                      checkStatusCode(loginService, statusRef, loginName, password, expectedStatus, cOpt)
                yield ()
              .as(succeed)
  }
end LoginServicesIntegrationTest
