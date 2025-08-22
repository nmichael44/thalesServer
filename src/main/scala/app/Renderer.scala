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
  import dsl.*
  import org.http4s.circe.CirceEntityEncoder.*
  import WebServiceResult.*

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

  private def okJsonToResponse(wsr: WebServiceResult): F[Response[F]] =
    Ok(wsr.asInstanceOf[OkJsonRes].json)
  end okJsonToResponse

  private def notFoundToResponse(wsr: WebServiceResult): F[Response[F]] =
    ApiError(wsr.asInstanceOf[NotFoundRes].s, "NOTFOUND") >>= (apiErr => NotFound(apiErr))
  end notFoundToResponse

  private def conflictToResponse(wsr: WebServiceResult): F[Response[F]] =
    ApiError(wsr.asInstanceOf[ConflictRes].s, "CONFLICT") >>= (apiErr => Conflict(apiErr))
  end conflictToResponse

  private def badRequestToResponse(wsr: WebServiceResult): F[Response[F]] =
    ApiError(wsr.asInstanceOf[BadRequestRes].e, "BADREQUEST") >>= (apiErr => BadRequest(apiErr))
  end badRequestToResponse

  private def unauthorizedToResponse(wsr: WebServiceResult): F[Response[F]] =
    ApiError(wsr.asInstanceOf[UnauthorizedRes].e, "UNAUTHORIZED") >>= { apiErr =>
      Unauthorized(`WWW-Authenticate`(ErrorChallenge), apiErr)
    }
  end unauthorizedToResponse

  private def internalServerErrorToResponse(@unused wsr: WebServiceResult): F[Response[F]] =
    InternalServerError()
  end internalServerErrorToResponse

  private val ResultHandlerMap: Map[Class[? <: WebServiceResult], WebServiceResult => F[Response[F]]] = Map(
    classOf[OkJsonRes]              -> okJsonToResponse,
    classOf[NotFoundRes]            -> notFoundToResponse,
    classOf[ConflictRes]            -> conflictToResponse,
    classOf[BadRequestRes]          -> badRequestToResponse,
    classOf[UnauthorizedRes]        -> unauthorizedToResponse,
    classOf[InternalServerErrorRes] -> internalServerErrorToResponse,
  )
  end ResultHandlerMap

  private def notImplemented(c: Class[?]): Exception =
    Exception(s"Renderer not registered (in ResultHandlerMap) for class '$c'.")

  def apply(wsr: WebServiceResult): F[Response[F]] =
    val c = wsr.getClass
    ResultHandlerMap
      .get(c)
      .map(_(wsr))
      .getOrElse(async.raiseError(notImplemented(c)))
  end apply
end Renderer
