package app.entrypoints

import cats.effect.Async
import cats.implicits.*

import app.entrypoints.EndPointsBases.{ApiError, EndPointErrorResult}
import app.model.AppModel.AuthenticatedBoUser
import app.services.AuthService
import io.circe.*
import io.circe.{Decoder, Encoder}
import io.circe.generic.auto.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.PartialServerEndpoint

final class EndPointsBases[F[_]: Async](authService: AuthService[F]):
  val PublicEndPoint: Endpoint[Unit, Unit, EndPointErrorResult, Unit, Any] =
    endpoint.errorOut(statusCode.and(jsonBody[ApiError]))
  end PublicEndPoint

  val AuthenticatedEndPoint: PartialServerEndpoint[String, AuthenticatedBoUser, Unit, EndPointErrorResult, Unit, Any, F] =
    PublicEndPoint
      .in("api")
      .securityIn(auth.bearer[String]())
      .serverSecurityLogic(authenticate)
  end AuthenticatedEndPoint

  private def authenticate(token: String): F[Either[EndPointErrorResult, AuthenticatedBoUser]] =
    authService
      .validateToken(token)
      .map(_.left.map(e => (StatusCode.Forbidden, ApiError("INVALID LOGINNAME_PASSWORD", e.getMessage))))
  end authenticate
end EndPointsBases

object EndPointsBases:
  final case class ApiError(errorCode: String, message: String)

  type EndPointErrorResult = (StatusCode, ApiError)
end EndPointsBases
