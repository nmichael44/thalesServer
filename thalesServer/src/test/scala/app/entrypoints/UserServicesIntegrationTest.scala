package app.entrypoints

import cats.data.NonEmptyVector
import cats.effect.{IO, Resource}
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers
import app.ThalesServer
import app.entrypoints.TestUtils as TU
import app.entrypoints.smithy.{InvalidOrMissingResetPasswordToken, LoginName, LoginNameList, ResetPasswordToken, RoleId, RoleIdList, User, UserId, UserIdList, UserInDb, UserPassword, UserServices, UserSession}
import app.model.JavaInstant
import org.http4s.client.Client
import smithy4s.http4s.SimpleRestJsonBuilder
import fs2.Stream

final class UserServicesIntegrationTest extends AsyncFreeSpec with AsyncIOSpec with Matchers:
  private def userServicesResource(client: Client[IO]): Resource[IO, UserServices[IO]] =
    SimpleRestJsonBuilder(app.entrypoints.smithy.UserServices)
      .client(client)
      .uri(TU.serverUri)
      .resource
  end userServicesResource

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

  "UserServices Integration" - {
    "All entry points" in {
      ThalesServer.createLogger[IO] >>= { logger =>
        val baseClientResource =
          for
            _ <- TU.startServer(logger)
            client <- TU.clientResource
          yield client

        val (userId0, u0) = (UserId(0L), LoginName("neo"))
        val p1 = UserPassword("AReal235711Secret!")
        val (userId1, u1) = (UserId(1L), LoginName("brent"))

        val (role0, role1) = (RoleId(0L), RoleId(1L))

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
            authClient <- TU
              .loginAndGetToken(baseClient, u0, p1)
              .map { token =>
                val debugClient = Client[IO]: req =>
                  IO.println(s"Request Headers: ${req.headers}").toResource *>
                    baseClient.run(req).evalTap(resp => IO.println(s"Response Headers: ${resp.headers}"))

                TU.mkAuthedClient(debugClient, token)
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

                  // Some parallel tests
                  tasks = Vector(
                    fetchUserByName(userServices, NonEmptyVector.of(u0)),
                    fetchUserByName(userServices, NonEmptyVector.of(u1)),
                    fetchUserById(userServices, NonEmptyVector.of(UserId(2L))),
                    fetchUserById(userServices, NonEmptyVector.of(UserId(3L))),
                    fetchAllLiveSessions(userServices),
                    fetchAllUsersAssociatedWithRoles(userServices, NonEmptyVector.of(RoleId(0L))),
                    fetchAllUsersAssociatedWithRoles(userServices, NonEmptyVector.of(RoleId(1L))),
                    fetchAllLiveSessions(userServices)
                  )
                  _ <- Stream
                    .emits(Vector.fill(60)(tasks).flatten) // 60 * 7 = 420 requests to the sever
                    .covary[IO]
                    .mapAsync(30)(identity) // executed 30 at a time.  There are some windows limits
                    .compile
                    .drain
                yield
                  (
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

            val failureReason = resForCheckResetUserPass.left.getOrElse(throw new AssertionError("expected failure"))
            failureReason.isInstanceOf[InvalidOrMissingResetPasswordToken] shouldBe true
            resForCheckResetUserPass.isLeft shouldBe true

            liveSessions.size shouldBe 1
            liveSessions.map(_.userId.value) should contain(0L)
        }
      }
    }
  }
end UserServicesIntegrationTest
