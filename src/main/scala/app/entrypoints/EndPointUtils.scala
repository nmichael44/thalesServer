package app.entrypoints

import cats.effect.Async
import cats.implicits.*
import cats.Functor

import app.model.AppModel.AuthenticatedBoUser
import app.services.AuthService
import io.circe.*
import io.circe.generic.auto.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.integ.cats.codec.given
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.EndpointOutput

object EndPointUtils:
  private val StatusCodeToString: Map[Int, String] = Map(
    StatusCode.Unauthorized.code        -> "Unauthorized",
    StatusCode.Forbidden.code           -> "Forbidden",
    StatusCode.Locked.code              -> "Locked",
    StatusCode.NotFound.code            -> "NotFound",
    StatusCode.BadRequest.code          -> "BadRequest",
    StatusCode.Conflict.code            -> "Conflict",
    StatusCode.InternalServerError.code -> "InternalServerError",
  )

  def statusCodeWithDescription(sc: StatusCode): EndpointOutput.FixedStatusCode[Unit] =
    statusCode(sc).description(StatusCodeToString(sc.code))
  end statusCodeWithDescription

  final case class ApiError(errorCode: String, message: String)

  type EndPointErrorResult = (StatusCode, ApiError)

  val UnauthenticatedApiError: ApiError =
    ApiError("UNAUTHENTICATED", "The user is not authenticated.")

  val UnauthorizedApiError: ApiError =
    ApiError("UNAUTHORIZED", "The user does is not authorized to perform this action.")

  def authenticate[F[_]: Functor, AuthenticationError](
      authService: AuthService[F],
      err: String => AuthenticationError,
      token: String,
  ): F[Either[AuthenticationError, AuthenticatedBoUser]] =
    authService
      .validateToken(token)
      .map(_.left.map(e => err(e.getMessage)))
  end authenticate

  val x: EndpointOutput[ApiError] =
    EndPointUtils
      .statusCodeWithDescription(StatusCode.Unauthorized)
      .and(jsonBody[ApiError].example(UnauthenticatedApiError))
end EndPointUtils
