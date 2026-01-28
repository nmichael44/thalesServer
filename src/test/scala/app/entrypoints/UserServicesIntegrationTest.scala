package app.entrypoints

import cats.data.NonEmptyVector
import cats.effect.{IO, Resource}
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*

import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import TestUtils.given
import app.ThalesServer
import app.entrypoints.smithy.{LoginName, LoginNameList, LoginServices, ResetPasswordToken, RoleId, RoleIdList, User, UserId, UserIdList, UserInDb, UserPassword, UserServices, UserSession}
import app.model.JavaInstant
import org.http4s.{AuthScheme, Credentials, Request}
import org.http4s.client.Client
import org.http4s.client.middleware.GZip
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

  private def fetchUserById(userServices: UserServices[IO], userIds: NonEmptyVector[UserId]): IO[Map[String, UserInDb]] =
    userServices.fetchUsersByUserIds(UserIdList(userIds)).map(_.users)
  end fetchUserById

  private def fetchAllUsersAssociatedWithRoles(
      userServices: UserServices[IO],
      roleIds: NonEmptyVector[RoleId],
  ): IO[Map[String, Vector[UserInDb]]] =
    userServices.fetchAllUsersAssociatedWithRoles(RoleIdList(roleIds)).map(_.roleIdToUsers)
  end fetchAllUsersAssociatedWithRoles

  private def createUser(userServices: UserServices[IO], user: User): IO[UserId] =
    userServices.createUser(user).map(_.userId)
  end createUser

  private def resetMyPassword(userServices: UserServices[IO], newPassword: UserPassword): IO[Unit] =
    userServices.resetMyPassword(newPassword)
  end resetMyPassword

  private def checkResetUserPasswordToken(
      userServices: UserServices[IO],
      token: ResetPasswordToken,
  ): IO[Either[Throwable, Unit]] =
    userServices.checkResetUserPasswordToken(token).attempt
  end checkResetUserPasswordToken

  private def fetchAllLiveSessions(userServices: UserServices[IO]): IO[Vector[UserSession]] =
    userServices.fetchAllLiveSessions().map(_.userSessions)
  end fetchAllLiveSessions

  private def addAuthHeader(req: Request[IO], token: String): Request[IO] =
    req.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, token)))
  end addAuthHeader

  private def mkAuthedClient(client: Client[IO], token: String): Client[IO] =
    Client[IO](req => client.run(addAuthHeader(req, token)))
  end mkAuthedClient

  "UserServices Integration" - {
    "All entry points" in {
      ThalesServer.createLogger[IO] >>= { implicit logger =>
        val baseClientResource =
          for
            _ <- TestUtils.setEnvVariables.toResource
            _ <- TestUtils.resetDatabase.toResource
            _ <- ThalesServer.applicationResource[IO]
            client <- TestUtils.clientResource
          yield client

        val (userId0, u0) = (UserId(0), LoginName("neo"))
        val p1 = UserPassword("AReal235711Secret!")
        val (userId1, u1) = (UserId(1), LoginName("brent"))

        val (role0, role1) = (RoleId(0), RoleId(1))

        val now = JavaInstant(java.time.Instant.now)
        val newUser = User(
          loginName = LoginName("trinity"),
          firstName = "Trinity",
          lastName = "Moss",
          email = "abc@foo.com",
          phone = "555-555-5555",
          creationTime = now,
          password = UserPassword("NewSecret123!"),
          mustResetPassword = true,
          userPasswordUpdateTime = now,
          enabled = true,
          creatingUserId = userId0,
        )

        baseClientResource.use { baseClient =>
          for
            authClient <- loginAndGetToken(baseClient, u0, p1).map { token =>
              val debugClient = Client[IO] { req =>
                IO.println(s"REQ HEADERS: ${req.headers}").toResource *>
                  baseClient.run(req).evalTap(resp => IO.println(s"RESP HEADERS: ${resp.headers}"))
              }
              GZip()(mkAuthedClient(debugClient, token))
            }

            (usersByName, usersById, roleIdToUsers, createdUserId, createdUserById, resForCheckResetUserPass, liveSessions) <-
              userServicesResource(authClient).use { userServices =>
                for
                  usersByName <- fetchUserByName(userServices, NonEmptyVector.of(u0, u1))
                  usersById <- fetchUserById(userServices, NonEmptyVector.of(userId0, userId1))
                  roleIdToUsers <- fetchAllUsersAssociatedWithRoles(userServices, NonEmptyVector.of(role0, role1))
                  createdUserId <- createUser(userServices, newUser)
                  createdUserById <- fetchUserById(userServices, NonEmptyVector.of(createdUserId))
                  _ <- resetMyPassword(userServices, UserPassword("NewSecret123!"))
                  resForCheckResetUserPass <- checkResetUserPasswordToken(userServices, ResetPasswordToken("invalid-token"))
                  liveSessions <- fetchAllLiveSessions(userServices)
                yield (
                  usersByName,
                  usersById,
                  roleIdToUsers,
                  createdUserId,
                  createdUserById,
                  resForCheckResetUserPass,
                  liveSessions,
                )
              }
          yield
            // Fetch users by loginName
            usersByName should contain.key("neo").and(contain.key("brent"))
            usersByName("neo").firstName shouldBe "Neophytos"
            usersByName("brent").firstName shouldBe "Brent"

            // Fetch users by userId
            usersById should contain.key("0").and(contain.key("1"))
            usersById("0").firstName shouldBe "Neophytos"
            usersById("1").firstName shouldBe "Brent"

            // Fetch users by roleId
            roleIdToUsers should contain.key("0").and(contain.key("1"))
            roleIdToUsers("0").head.userId.value shouldBe 0
            roleIdToUsers("1").head.userId.value shouldBe 1

            // Create user
            createdUserId.value shouldBe 4
            createdUserById.size shouldBe 1
            {
              val u = createdUserById("4")

              u.loginName.value shouldBe "trinity"
              u.firstName shouldBe "Trinity"
              u.lastName shouldBe "Moss"
              u.email shouldBe "abc@foo.com"
              u.phone shouldBe "555-555-5555"
              u.mustResetPassword shouldBe true
              u.enabled shouldBe true
              u.creatingUserId.value shouldBe userId0.value
            }
        }
      }
    }
  }
end UserServicesIntegrationTest
