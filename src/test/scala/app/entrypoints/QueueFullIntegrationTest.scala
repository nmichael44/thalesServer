package app.entrypoints

import cats.data.NonEmptyVector
import cats.effect.{IO, Resource}
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*

import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import app.ThalesServer
import app.entrypoints.TestUtils.given
import app.entrypoints.TestUtils as TU
import app.entrypoints.smithy.{LoginName, RoleId, RoleIdVector, RoleInDb, RoleName, RoleServices, UserPassword}
import org.http4s.Status
import org.http4s.client.Client
import org.typelevel.ci.CIString
import smithy4s.http4s.SimpleRestJsonBuilder

final class QueueFullIntegrationTest extends AsyncFreeSpec with AsyncIOSpec with Matchers:
  private enum RequestOutcome:
    case QueueFull(status: Status, headers: org.http4s.Headers, body: String)
    case Processed(role: RoleInDb)
    case UnexpectedState(msg: String)

  private def roleServicesResource(client: Client[IO]): Resource[IO, RoleServices[IO]] =
    SimpleRestJsonBuilder(app.entrypoints.smithy.RoleServices)
      .client(client)
      .uri(TU.serverUri)
      .resource
  end roleServicesResource

  private def fetchRole(roleServices: RoleServices[IO], roleId: RoleId): IO[Option[RoleInDb]] =
    roleServices
      .fetchRolesByIds(RoleIdVector(NonEmptyVector.of(roleId)))
      .map(_.roleIdToRole.get(roleId.toString))
  end fetchRole

  private case class RawResponseException(status: Status, headers: org.http4s.Headers, body: String) extends scala.util.control.NoStackTrace

  private def interceptingClient(baseClient: Client[IO]): Client[IO] = Client[IO]: req =>
    baseClient
      .run(req)
      .evalMap: resp =>
        if resp.status.code == Status.ServiceUnavailable.code then
          resp.as[String].flatMap(body => IO.raiseError(RawResponseException(resp.status, resp.headers, body)))
        else IO.pure(resp)
  end interceptingClient

  "Queue Full integration test" - {
    "should fail fast with HTTP 503 when the bounded job queue is full" in {
      val numWorkersKey = "app-config.backend-server-config.number-of-workers"
      val boundedQueueCapacityKey = "app-config.backend-server-config.bounded-queue-capacity"
      val fetchRolesByIdsRequestKey = "app-config.backend-server-config.endpoint-delays.FetchRolesByIdsRequest"

      val setupSystemProperties: Resource[IO, Unit] = Resource.make(
        IO.delay:
          System.setProperty(numWorkersKey, "4")
          System.setProperty(boundedQueueCapacityKey, "4")
          System.setProperty(fetchRolesByIdsRequestKey, "600ms")
          com.typesafe.config.ConfigFactory.invalidateCaches(),
      )(_ =>
        IO.delay:
          System.clearProperty(numWorkersKey)
          System.clearProperty(boundedQueueCapacityKey)
          System.clearProperty(fetchRolesByIdsRequestKey)
          com.typesafe.config.ConfigFactory.invalidateCaches(),
      )

      val (u0, p0) = (LoginName("neo"), UserPassword("AReal235711Secret!"))

      ThalesServer
        .createLogger[IO]
        .flatMap: logger =>
          val baseClientResource = setupSystemProperties *> TU.startServer(logger) *> TU.clientResource

          baseClientResource.use: baseClient =>
            for
              authClient <- TU.loginAndGetToken(baseClient, u0, p0).map(TU.mkAuthedClient(baseClient, _))
              results <- roleServicesResource(interceptingClient(authClient)).use: roleServices =>
                (1 to 15).toVector.parTraverse: _ =>
                  fetchRole(roleServices, RoleId(0L)).attempt.map:
                    case Right(Some(role)) => RequestOutcome.Processed(role)
                    case Right(None) => RequestOutcome.UnexpectedState("Role unexpectedly not found (got None)")
                    case Left(e: RawResponseException) => RequestOutcome.QueueFull(e.status, e.headers, e.body)
                    case Left(e) => RequestOutcome.UnexpectedState(s"Unexpected error: ${e.getMessage}")
            yield
              val queueFullResults = results.collect:
                case r: RequestOutcome.QueueFull => r

              val processedResults = results.collect:
                case r: RequestOutcome.Processed => r

              val unexpectedResults = results.collect:
                case r: RequestOutcome.UnexpectedState => r

              if unexpectedResults.nonEmpty then
                val errorMessages = unexpectedResults.map(_.msg).mkString("\n")
                fail(s"Received unexpected responses:\n$errorMessages")

              // Verify that exactly 7 requests were rejected with 503 Service Unavailable
              queueFullResults.size shouldBe 7

              // Verify that exactly 8 requests were successfully processed by the server (queue + workers)
              processedResults.size shouldBe 8

              processedResults.foreach: result =>
                result.role.roleName shouldBe RoleName("Admin")

              // Verify that all 15 requests completed with either their normal response or 503
              (queueFullResults.size + processedResults.size) shouldBe 15

              queueFullResults.foreach: result =>
                // Verify the Retry-After header is exactly "5"
                result.headers.get(CIString("Retry-After")).map(_.head.value) shouldBe Some("5")
                // Verify the JSON error payload
                result.body shouldBe """{"error": "The server is temporarily busy. Bounded job queue is full."}"""

              succeed
    }
  }
end QueueFullIntegrationTest
