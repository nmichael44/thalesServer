package app.entrypoints

import cats.implicits.*
import cats.Functor

import scala.collection.View

import app.model.AppModel.AuthenticatedBoUser
import app.services.AuthService
import io.circe.*
import io.circe.{Decoder, Encoder}
import io.circe.generic.auto.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.typelevel.ErasureSameAsType
import sttp.tapir.EndpointOutput
import app.ThalesUtils.ImplicitConversionUtils.*

object EndPointUtils:
  private val StatusCodeToString: Map[Int, String] = View(
    StatusCode.Unauthorized        -> "Unauthorized",
    StatusCode.Forbidden           -> "Forbidden",
    StatusCode.Locked              -> "Locked",
    StatusCode.NotFound            -> "NotFound",
    StatusCode.BadRequest          -> "BadRequest",
    StatusCode.Conflict            -> "Conflict",
    StatusCode.NotAcceptable       -> "NotAcceptable",
    StatusCode.InternalServerError -> "InternalServerError",
  ).map(_.mapFirst(_.code)).toMap

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

  val authenticatedStandardErrorOut: EndpointOutput[ApiError] =
    oneOf(
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.Unauthorized)
          .and(jsonBody[ApiError].example(UnauthenticatedApiError)),
      ),
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.Forbidden)
          .and(jsonBody[ApiError].example(UnauthorizedApiError)),
      ),
    )
  end authenticatedStandardErrorOut
end EndPointUtils
