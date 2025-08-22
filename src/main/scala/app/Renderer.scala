package app

import cats.effect.kernel.Async
import cats.implicits.*

import scala.annotation.unused

import app.ThalesUtils.TimeUtils
import io.circe.*
import io.circe.generic.auto.*
import org.http4s.{Challenge, Response}
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`WWW-Authenticate`

final class Renderer[F[_]: Async as async](dsl: Http4sDsl[F]):
  import app.entrypoints.WebServiceResult
  import dsl.*
  import org.http4s.circe.CirceEntityEncoder.*

  private val ErrorChallenge: Challenge = Challenge(
    scheme = "Bearer",
    realm = "neo_token_service",
    params = Map("error" -> "invalid_grant", "error_description" -> "Invalid username or password"),
  )

  private final case class ApiError(message: String, errorCode: String, timestamp: java.time.Instant)

  private object ApiError:
    def apply(message: String, errorCode: String): F[ApiError] =
      TimeUtils.nowInstant.map(timestamp => ApiError(message, errorCode, timestamp))
    end apply
  end ApiError

  import WebServiceResult.WsrKind

  private def okJsonToResponse(wsr: WsrKind): F[Response[F]] =
    Ok(wsr.asInstanceOf[WsrKind.OkJsonRes].json)
  end okJsonToResponse

  private def notFoundToResponse(wsr: WsrKind): F[Response[F]] =
    ApiError(wsr.asInstanceOf[WsrKind.NotFoundRes].s, "NOTFOUND") >>= (apiErr => NotFound(apiErr))
  end notFoundToResponse

  private def conflictToResponse(wsr: WsrKind): F[Response[F]] =
    ApiError(wsr.asInstanceOf[WsrKind.ConflictRes].s, "CONFLICT") >>= (apiErr => Conflict(apiErr))
  end conflictToResponse

  private def badRequestToResponse(wsr: WsrKind): F[Response[F]] =
    ApiError(wsr.asInstanceOf[WsrKind.BadRequestRes].e, "BADREQUEST") >>= (apiErr => BadRequest(apiErr))
  end badRequestToResponse

  private def unauthorizedToResponse(wsr: WsrKind): F[Response[F]] =
    ApiError(wsr.asInstanceOf[WsrKind.UnauthorizedRes].e, "UNAUTHORIZED") >>= { apiErr =>
      Unauthorized(`WWW-Authenticate`(ErrorChallenge), apiErr)
    }
  end unauthorizedToResponse

  private def internalServerErrorToResponse(@unused wsr: WebServiceResult.WsrKind): F[Response[F]] =
    InternalServerError()
  end internalServerErrorToResponse

  private val ResultHandlerMap: Map[Class[? <: WebServiceResult.WsrKind], WebServiceResult.WsrKind => F[Response[F]]] = Map(
    classOf[WsrKind.OkJsonRes]              -> okJsonToResponse,
    classOf[WsrKind.NotFoundRes]            -> notFoundToResponse,
    classOf[WsrKind.ConflictRes]            -> conflictToResponse,
    classOf[WsrKind.BadRequestRes]          -> badRequestToResponse,
    classOf[WsrKind.UnauthorizedRes]        -> unauthorizedToResponse,
    classOf[WsrKind.InternalServerErrorRes] -> internalServerErrorToResponse,
  )
  end ResultHandlerMap

  private def notImplemented(c: Class[?]): Exception =
    Exception(s"Renderer not registered (in ResultHandlerMap) for class '$c'.")

  def apply(wsr: WebServiceResult.WsrKind): F[Response[F]] =
    val c = wsr.getClass
    ResultHandlerMap
      .get(c)
      .map(_(wsr))
      .getOrElse(async.raiseError(notImplemented(c)))
  end apply
end Renderer
