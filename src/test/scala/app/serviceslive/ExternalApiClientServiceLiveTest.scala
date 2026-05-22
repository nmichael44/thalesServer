package app.serviceslive

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec

import scala.concurrent.duration.*

import org.scalatest.freespec.AsyncFreeSpec

import app.services.ExternalApiClientService
import app.services.ExternalApiClientService.jsoniter.given
import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.implicits.*

object ExternalApiClientServiceLiveTest:
  final case class TestUser(id: Int, name: String)
  object TestUser:
    given codec: JsonValueCodec[TestUser] = JsonCodecMaker.make
    given CanEqual[TestUser, TestUser] = CanEqual.derived
  end TestUser

  final case class TestRequest(payload: String)
  object TestRequest:
    given codec: JsonValueCodec[TestRequest] = JsonCodecMaker.make
    given CanEqual[TestRequest, TestRequest] = CanEqual.derived
  end TestRequest

  final case class TestResponse(status: String)
  object TestResponse:
    given codec: JsonValueCodec[TestResponse] = JsonCodecMaker.make
    given CanEqual[TestResponse, TestResponse] = CanEqual.derived
  end TestResponse

  final case class Todo(userId: Int, id: Int, title: String, completed: Boolean)
  object Todo:
    given codec: JsonValueCodec[Todo] = JsonCodecMaker.make
    given CanEqual[Todo, Todo] = CanEqual.derived
  end Todo
end ExternalApiClientServiceLiveTest

final class ExternalApiClientServiceLiveTest extends AsyncFreeSpec with AsyncIOSpec:
  import ExternalApiClientServiceLiveTest.*

  given CanEqual[Method, Method] = CanEqual.derived
  given CanEqual[Uri.Path, Uri.Path] = CanEqual.derived
  given CanEqual[Status, Status] = CanEqual.derived

  private val testUser = TestUser(42, "Arthur Dent")

  private val mockHttpApp: HttpApp[IO] = HttpApp[IO]:
    case req @ GET -> Root / "plain" =>
      val headerNames = req.headers.headers.map(_.name.toString).mkString(",")
      Ok(s"Hello Plain! Headers: $headerNames")

    case req @ GET -> Root / "user" =>
      val authHeader = req.headers.get(org.typelevel.ci.CIString("Authorization")).map(_.head.value).getOrElse("")
      if authHeader == "Bearer secret-token" then Ok(writeToString(testUser))
      else Forbidden("Unauthorized user request")

    case req @ POST -> Root / "echo" =>
      req
        .as[String]
        .flatMap: bodyStr =>
          val requestBody = readFromString[TestRequest](bodyStr)
          Ok(writeToString(TestResponse(s"Echo: ${requestBody.payload}")))

    case GET -> Root / "stream" =>
      Ok(fs2.Stream.emits("Streaming data chunk by chunk".getBytes).covary[IO])

    case GET -> Root / "error" =>
      InternalServerError("Something went wrong on the server")

    case POST -> Root / "error" =>
      InternalServerError("Something went wrong on the server")

    case GET -> Root / "hang" =>
      IO.never

    case _ =>
      NotFound("Route not found")
  end mockHttpApp

  private val httpClient: Client[IO] = Client.fromHttpApp(mockHttpApp)
  private val apiClient: ExternalApiClientService[IO] = ExternalApiClientServiceLive.create[IO](httpClient)

  "ExternalApiClientServiceLive" - {

    "fetchUri" - {
      "should fetch plain text successfully and include optional headers" in {
        val uri = uri"/plain"
        val customHeaders = Headers(Header.Raw(org.typelevel.ci.CIString("X-Custom-Header"), "Value"))

        apiClient
          .fetchUri(uri, customHeaders, timeout = None)
          .map: response =>
            assert(response.contains("Hello Plain!"))
            assert(response.contains("X-Custom-Header"))
      }

      "should fail when HTTP status is not successful" in {
        val uri = uri"/error"
        apiClient
          .fetchUri(uri, Headers.empty, timeout = None)
          .attempt
          .map:
            case Left(err) =>
              assert(err.getMessage.contains("failed with status: 500"))
            case Right(_) =>
              fail("Expected a failure status but request succeeded")
      }
    }

    "getAs" - {
      "should decode typed JSON values successfully with required headers" in {
        val uri = uri"/user"
        val authHeaders = Headers(Header.Raw(org.typelevel.ci.CIString("Authorization"), "Bearer secret-token"))

        apiClient
          .getAs[TestUser](uri, authHeaders, timeout = None)
          .map: user =>
            assert(user == testUser)
      }

      "should fail when authentication headers are missing" in {
        val uri = uri"/user"
        apiClient
          .getAs[TestUser](uri, Headers.empty, timeout = None)
          .attempt
          .map:
            case Left(err) =>
              assert(err.getMessage.contains("failed with status: 403"))
            case Right(_) =>
              fail("Expected an auth failure but request succeeded")
      }

      "should fail when parsing invalid JSON" in {
        val uri = uri"/plain"
        apiClient
          .getAs[TestUser](uri, Headers.empty, timeout = None)
          .attempt
          .map:
            case Left(err) =>
              assert(err.getMessage.contains("Failed to parse JSON using jsoniter"))
            case Right(_) =>
              fail("Expected a parsing failure but request succeeded")
      }
    }

    "postAs" - {
      "should encode request body and decode response body successfully" in {
        val uri = uri"/echo"
        val requestPayload = TestRequest("Scala http4s")

        apiClient
          .postAs[TestRequest, TestResponse](uri, requestPayload, Headers.empty, timeout = None)
          .map: response =>
            assert(response == TestResponse("Echo: Scala http4s"))
      }

      "should propagate server failures" in {
        val uri = uri"/error"
        val requestPayload = TestRequest("trigger error")

        apiClient
          .postAs[TestRequest, TestResponse](uri, requestPayload, Headers.empty, timeout = None)
          .attempt
          .map:
            case Left(err) =>
              assert(err.getMessage.contains("failed with status: 500"))
            case Right(_) =>
              fail("Expected a failure but request succeeded")
      }
    }

    "run" - {
      "should allow running customized requests safely" in {
        val request = Request[IO](Method.GET, uri"/plain")
        apiClient.run(request, timeout = None): response =>
          assert(response.status == Status.Ok)
          response
            .as[String]
            .map: body =>
              assert(body.contains("Hello Plain!"))
      }
    }

    "streamUri" - {
      "should stream raw bytes successfully" in {
        val uri = uri"/stream"
        apiClient
          .streamUri(uri, Headers.empty, timeout = None)
          .compile
          .to(Array)
          .map: bytes =>
            assert(new String(bytes) == "Streaming data chunk by chunk")
      }

      "should fail when stream source is unsuccessful" in {
        val uri = uri"/error"
        apiClient
          .streamUri(uri, Headers.empty, timeout = None)
          .compile
          .drain
          .attempt
          .map:
            case Left(err) => assert(err.getMessage.contains("failed with status: 500"))
            case Right(_) => fail("Expected streaming to fail but it succeeded")
      }
    }

    "request timeout behavior" - {
      "should fail with a TimeoutException when the request exceeds the configured timeout" in {
        val uri = uri"/hang"
        apiClient
          .fetchUri(uri, Headers.empty, timeout = Some(50.milliseconds))
          .attempt
          .map:
            case Left(_: java.util.concurrent.TimeoutException) => assert(true)
            case Left(err) => fail(s"Expected java.util.concurrent.TimeoutException, but got: $err")
            case Right(_) => fail("Expected the request to time out, but it succeeded")
      }
    }

    "real HTTP client remote integration" - {
      "should fetch a real Todo from a public API successfully" in
        org.http4s.ember.client.EmberClientBuilder
          .default[IO]
          .build
          .use: realHttpClient =>
            val realApiClient = ExternalApiClientServiceLive.create[IO](realHttpClient)
            val uri = Uri.unsafeFromString("https://jsonplaceholder.typicode.com/todos/1")

            realApiClient
              .getAs[Todo](uri, Headers.empty, timeout = Some(5.seconds))
              .map: todo =>
                assert(todo == Todo(1, 1, "delectus aut autem", false))
    }
  }
end ExternalApiClientServiceLiveTest
