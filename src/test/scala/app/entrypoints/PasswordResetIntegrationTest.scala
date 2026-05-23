package app.entrypoints

import cats.effect.{IO, Resource}
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*

import java.sql.DriverManager
import java.time.Instant

import org.scalatest.EitherValues.convertLeftProjectionToValuable
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import app.ThalesServer
import app.ThalesUtils.GenUtils as U
import app.entrypoints.TestUtils as TU
import app.entrypoints.smithy.{InvalidUserNameOrPassword, LoginName, LoginServices, ResetPasswordToken, UserPassword, UserServices}
import org.http4s.client.Client
import smithy4s.http4s.SimpleRestJsonBuilder

final class PasswordResetIntegrationTest extends AsyncFreeSpec with AsyncIOSpec with Matchers:
  private val neoLogin: LoginName = LoginName("neo")
  private val originalPass: UserPassword = UserPassword("AReal235711Secret!")
  private val authedChangedPass: UserPassword = UserPassword("NewAuthedSecretPass1!")
  private val recoveryResetPass: UserPassword = UserPassword("NewSuperSecurePass1!")

  private def loginNeo(client: Client[IO], pass: UserPassword): IO[String] =
    TU.loginAndGetToken(client, neoLogin, pass)
  end loginNeo

  private def loginNeoAttempt(loginServices: LoginServices[IO], pass: UserPassword) =
    loginServices.login(neoLogin, pass).attempt
  end loginNeoAttempt

  private def initiateRecoveryNeo(loginServices: LoginServices[IO]) =
    loginServices.initiateRecoveryOfUserPassword(neoLogin)
  end initiateRecoveryNeo

  private def userServicesResource(client: Client[IO]): Resource[IO, UserServices[IO]] =
    SimpleRestJsonBuilder(app.entrypoints.smithy.UserServices)
      .client(client)
      .uri(TU.serverUri)
      .resource
  end userServicesResource

  private def getDbDetails: IO[(String, String, String)] =
    def requiredProp(key: String): IO[String] =
      IO.delay(U.getSystemProp(key))
        .flatMap:
          _.liftTo[IO](new RuntimeException(s"Environment variable or system property not set: $key"))
    end requiredProp

    for
      host <- requiredProp("DB_SERVER_HOST")
      port <- requiredProp("DB_SERVER_PORT")
      db <- requiredProp("DB_NAME")
      user <- requiredProp("DB_USERNAME")
      password <- requiredProp("DB_USERNAME_PASSWORD")
      url = s"jdbc:postgresql://$host:$port/$db"
    yield (url, user, password)
  end getDbDetails

  private def getConnection: IO[java.sql.Connection] = getDbDetails.flatMap: (url, user, password) =>
    IO.blocking:
      val conn = DriverManager.getConnection(url, user, password)
      conn.setAutoCommit(false)
      conn
  end getConnection

  private def countTokensForUser(userId: Long): IO[Int] =
    getConnection.flatMap: conn =>
      IO.blocking:
        val stmt = conn.prepareStatement("select count(*) from ResetUserPasswordTokens where userId = ?")
        try
          stmt.setLong(1, userId)
          val rs = stmt.executeQuery()
          rs.next()
          val count = rs.getInt(1)
          conn.commit()
          count
        catch
          case e: Throwable =>
            conn.rollback()
            throw e
        finally
          stmt.close()
          conn.close()
  end countTokensForUser

  private def insertKnownToken(token: String, userId: Long): IO[Unit] =
    val hashedToken = U.hashStringUrlEncoded(token)
    val expiry = Instant.now().plusSeconds(3600) // 1 hour
    getConnection.flatMap: conn =>
      IO.blocking:
        val stmt = conn.prepareStatement(
          "insert into ResetUserPasswordTokens (hashedToken, userId, expirationTime) values (?, ?, ?)",
        )
        try
          stmt.setString(1, hashedToken)
          stmt.setLong(2, userId)
          stmt.setTimestamp(3, java.sql.Timestamp.from(expiry))
          stmt.executeUpdate()
          conn.commit()
        catch
          case e: Throwable =>
            conn.rollback()
            throw e
        finally
          stmt.close()
          conn.close()
  end insertKnownToken

  private def clearTokensForUser(userId: Long): IO[Int] =
    getConnection.flatMap: conn =>
      IO.blocking:
        val stmt = conn.prepareStatement("delete from ResetUserPasswordTokens where userId = ?")
        try
          stmt.setLong(1, userId)
          val count = stmt.executeUpdate()
          conn.commit()
          count
        catch
          case e: Throwable =>
            conn.rollback()
            throw e
        finally
          stmt.close()
          conn.close()
  end clearTokensForUser

  "Password Reset & Change Flow Integration" - {
    "should handle authenticated password change, password recovery, checking reset tokens, and resetting password successfully" in
      ThalesServer
        .createLogger[IO]
        .flatMap: logger =>
          val baseClientResource = TU.startServer(logger) *> TU.clientResource

          baseClientResource.use: baseClient =>
            TU.loginServicesResource(baseClient)
              .use: loginServices =>
                for
                  // --- 1. Authenticated Password Change (ResetMyPassword) ---
                  neoToken <- loginNeo(baseClient, originalPass)
                  authedClient = TU.mkAuthedClient(baseClient, neoToken)
                  _ <- userServicesResource(authedClient).use: userServices =>
                    userServices.resetMyPassword(authedChangedPass)

                  // Verify old password fails
                  loginOldFailRes <- loginNeoAttempt(loginServices, originalPass)
                  // Verify new password works
                  newAuthedToken <- loginNeo(baseClient, authedChangedPass)

                  // --- 2. Initiate Recovery (InitiateRecoveryOfUserPassword) ---
                  initialTokens <- countTokensForUser(0L)
                  _ <- initiateRecoveryNeo(loginServices)
                  tokensAfterRecovery <- countTokensForUser(0L)

                  // --- 3. Check Reset Token (CheckResetUserPasswordToken) ---
                  knownToken = "known-test-reset-password-token"
                  _ <- insertKnownToken(knownToken, 0L)
                  token = ResetPasswordToken(knownToken)
                  newAuthedClient = TU.mkAuthedClient(baseClient, newAuthedToken)
                  _ <- userServicesResource(newAuthedClient).use: userServices =>
                    for
                      resValid <- userServices.checkResetUserPasswordToken(token).attempt
                      resInvalid <- userServices.checkResetUserPasswordToken(ResetPasswordToken("invalid-token")).attempt
                    yield
                      resValid.isRight shouldBe true
                      resInvalid.isLeft shouldBe true

                  // --- 4. Reset User Password (ResetUserPassword) ---
                  _ <- loginServices.resetUserPassword(token, recoveryResetPass)

                  // Verify new login behavior
                  loginAuthedFailRes <- loginNeoAttempt(loginServices, authedChangedPass)
                  loginNewSuccessToken <- loginNeo(baseClient, recoveryResetPass)

                  // --- 5. Clean up remaining token trash ---
                  // The auto-generated token from the Initiate Recovery step was never used or deleted
                  // (as we bypassed it by injecting and using our known test token instead).
                  // We manually clean it up here to leave the test database in a pristine state by
                  // deleting remaining active tokens only for this specific test user (neo, UserId 0).
                  // We assert that exactly 1 row (the auto-generated token) was cleaned up.
                  deletedCount <- clearTokensForUser(0L)

                  // --- 6. Revert user password back to original to avoid side-effects for other tests ---
                  finalAuthToken <- loginNeo(baseClient, recoveryResetPass)
                  finalAuthedClient = TU.mkAuthedClient(baseClient, finalAuthToken)
                  _ <- userServicesResource(finalAuthedClient).use: userServices =>
                    userServices.resetMyPassword(originalPass)
                yield
                  loginOldFailRes.left.value shouldBe a[InvalidUserNameOrPassword]
                  newAuthedToken should not be empty
                  initialTokens shouldBe 0
                  tokensAfterRecovery shouldBe 1
                  loginAuthedFailRes.left.value shouldBe a[InvalidUserNameOrPassword]
                  loginNewSuccessToken should not be empty
                  deletedCount shouldBe 1
  }
end PasswordResetIntegrationTest
