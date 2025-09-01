package app.entrypoints

import cats.data.NonEmptyVector
import cats.effect.Async

import app.entrypoints.EndPointUtils.ApiError
import app.model.AppModel
import app.model.AppModel.{AuthenticatedBoUser, BoUserInDb}
import app.services.AuthService
import app.JobSpecs.JobKind.FetchMultipleBoUsersByIdRequest
import app.JobSpecs.JobResult.FetchMultipleBoUsersByIdResult
import app.ThalesUtils.JsonCodecs.given
import io.circe.*
import io.circe.generic.auto.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.integ.cats.codec.given
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint

private final class FetchMultipleBoUsersByUserIdEp[F[_]: Async] private (
    jobHandler: JobHandler[F],
    authService: AuthService[F],
) extends ThalesEntryPoint[F]:
  private enum FetchMultipleBoUsersByUserIdEpError:
    case UnauthenticatedError(error: ApiError)
    case UnauthorizedError(error: ApiError)
  end FetchMultipleBoUsersByUserIdEpError

  private val fetchMultipleBoUsersByUserIdEpErrorOut: EndpointOutput[FetchMultipleBoUsersByUserIdEpError] =
    oneOf(
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.Unauthorized)
          .and(jsonBody[ApiError].example(EndPointUtils.UnauthenticatedApiError))
          .mapTo[FetchMultipleBoUsersByUserIdEpError.UnauthenticatedError],
      ),
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.Forbidden)
          .and(jsonBody[ApiError].example(EndPointUtils.UnauthorizedApiError))
          .mapTo[FetchMultipleBoUsersByUserIdEpError.UnauthorizedError],
      ),
    )
  end fetchMultipleBoUsersByUserIdEpErrorOut

  private def strToAuthenticationError(str: String): FetchMultipleBoUsersByUserIdEpError =
    FetchMultipleBoUsersByUserIdEpError.UnauthenticatedError(ApiError(EndPointUtils.UnauthenticatedApiError.errorCode, str))
  end strToAuthenticationError

  val getEntryPoint: ServerEndpoint[Any, F] =
    endpoint
      .errorOut(fetchMultipleBoUsersByUserIdEpErrorOut)
      .in("api")
      .securityIn(auth.bearer[String]())
      .serverSecurityLogic(EndPointUtils.authenticate(authService, strToAuthenticationError, _))
      .get
      .in("fetchMultipleBoUsersByUserId")
      .in(jsonBody[NonEmptyVector[Long]].description("A non-empty vector of userIds to fetch. The userIds must be unique."))
      .out(
        jsonBody[Map[Long, BoUserInDb]].description(
          "A map of userIds to their corresponding BoUserInDb objects. If a userId is not found, then it is simply omitted from this Map.",
        ),
      )
      .serverLogic(fetchMultipleBoUsersByUserId)

  private val unauthorizedError: Either[FetchMultipleBoUsersByUserIdEpError, Map[Long, BoUserInDb]] =
    Left(FetchMultipleBoUsersByUserIdEpError.UnauthorizedError(EndPointUtils.UnauthorizedApiError))
  end unauthorizedError

  private def fetchMultipleBoUsersByUserId(authenticatedBoUser: AuthenticatedBoUser)(
      userIds: NonEmptyVector[Long],
  ): F[Either[FetchMultipleBoUsersByUserIdEpError, Map[Long, BoUserInDb]]] =
    jobHandler.jobHandlerWithAuth[FetchMultipleBoUsersByIdResult, FetchMultipleBoUsersByUserIdEpError, Map[Long, BoUserInDb]](
      authenticatedBoUser,
      FetchBoUserByPermissionsUtils.FetchBoUserPermissionsAlg,
      FetchMultipleBoUsersByIdRequest(userIds),
      { case FetchMultipleBoUsersByIdResult(res) => Right(res) },
      unauthorizedError,
    )
  end fetchMultipleBoUsersByUserId
end FetchMultipleBoUsersByUserIdEp

object FetchMultipleBoUsersByUserIdEp:
  def create[F[_]: Async](jobHandler: JobHandler[F], authService: AuthService[F]): ThalesEntryPoint[F] =
    FetchMultipleBoUsersByUserIdEp[F](jobHandler, authService)
  end create
end FetchMultipleBoUsersByUserIdEp
