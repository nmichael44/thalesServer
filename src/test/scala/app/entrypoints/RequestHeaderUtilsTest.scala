package app.entrypoints

import cats.data.Kleisli
import cats.effect.{IO, Resource}
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.syntax.all.*

import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import app.ThalesUtils.RequestHeaderUtils
import app.TraceIdScope
import app.uuid.UUIDGenerator
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.implicits.*
import org.typelevel.ci.CIString

final class RequestHeaderUtilsTest extends AsyncFreeSpec with AsyncIOSpec with Matchers:
  given CanEqual[Option[String], Option[String]] = CanEqual.derived
  given CanEqual[String, String] = CanEqual.derived

  "RequestHeaderUtils" - {
    "getXRequestId" - {
      "should extract X-Request-ID header when present" in {
        val req = Request[IO](Method.GET, uri"/test").putHeaders(
          Header.Raw(RequestHeaderUtils.XRequestId, "trace-abc-123"),
        )
        IO(RequestHeaderUtils.getXRequestId(req) shouldBe Some("trace-abc-123"))
      }

      "should extract X-Request-ID header case-insensitively" in {
        val req = Request[IO](Method.GET, uri"/test").putHeaders(
          Header.Raw(CIString("x-request-id"), "lowercase-header-id"),
        )
        IO(RequestHeaderUtils.getXRequestId(req) shouldBe Some("lowercase-header-id"))
      }

      "should return None when X-Request-ID header is missing" in {
        val req = Request[IO](Method.GET, uri"/test")
        IO(RequestHeaderUtils.getXRequestId(req) shouldBe None)
      }

      "should return None when X-Request-ID header is blank" in {
        val reqEmpty = Request[IO](Method.GET, uri"/test").putHeaders(
          Header.Raw(RequestHeaderUtils.XRequestId, ""),
        )
        val reqSpaces = Request[IO](Method.GET, uri"/test").putHeaders(
          Header.Raw(RequestHeaderUtils.XRequestId, "   "),
        )
        IO {
          RequestHeaderUtils.getXRequestId(reqEmpty) shouldBe None
          RequestHeaderUtils.getXRequestId(reqSpaces) shouldBe None
        }
      }
    }

    "Trace ID scoping and response header propagation" - {
      def createTestMiddleware(
          uuidScope: TraceIdScope[IO, Option[String]],
          uuidGen: UUIDGenerator[IO],
      )(httpApp: HttpApp[IO]): HttpApp[IO] =
        Kleisli: (req: Request[IO]) =>
          val getRequestId: IO[String] =
            RequestHeaderUtils
              .getXRequestId(req)
              .fold(uuidGen.generateUUIDAsString)(IO.pure)

          for
            requestId <- getRequestId
            response <- uuidScope
              .scope(Some(requestId))
              .use: _ =>
                httpApp.run(req).map(_.putHeaders(Header.Raw(RequestHeaderUtils.XRequestId, requestId)))
          yield response

      "should preserve client X-Request-ID in scope and in the response header" in {
        for
          uuidScope <- TraceIdScope.fromIOLocal[Option[String]](None)
          uuidGen <- UUIDGenerator.create[IO](4).use(IO.pure)
          testApp = HttpApp[IO]: req =>
            uuidScope.get.flatMap: scopedId =>
              Ok(scopedId.getOrElse("none"))
          tracedApp = createTestMiddleware(uuidScope, uuidGen)(testApp)
          clientTraceId = "client-trace-777"
          req = Request[IO](Method.GET, uri"/hello").putHeaders(
            Header.Raw(RequestHeaderUtils.XRequestId, clientTraceId),
          )
          resp <- tracedApp.run(req)
          body <- resp.as[String]
        yield
          body shouldBe clientTraceId
          resp.headers.get(RequestHeaderUtils.XRequestId).map(_.head.value) shouldBe Some(clientTraceId)
      }

      "should generate new UUID when X-Request-ID is absent and attach it to scope and response" in {
        for
          uuidScope <- TraceIdScope.fromIOLocal[Option[String]](None)
          uuidGen <- UUIDGenerator.create[IO](4).use(IO.pure)
          testApp = HttpApp[IO]: req =>
            uuidScope.get.flatMap: scopedId =>
              Ok(scopedId.getOrElse("none"))
          tracedApp = createTestMiddleware(uuidScope, uuidGen)(testApp)
          req = Request[IO](Method.GET, uri"/hello")
          resp <- tracedApp.run(req)
          body <- resp.as[String]
          responseHeader = resp.headers.get(RequestHeaderUtils.XRequestId).map(_.head.value)
        yield
          body should not be empty
          responseHeader shouldBe Some(body)
      }
    }
  }
end RequestHeaderUtilsTest
