package app.entrypoints

import cats.data.NonEmptyVector
import cats.effect.Async

import app.JobSpecs.JobKind.FetchMultipleUsersByIdRequest
import app.JobSpecs.JobResult.FetchMultipleUsersByIdResult
import app.ThalesUtils.JsonCodecs.given
import app.entrypoints.EndPointUtils.ApiError
import app.model.AppModel
import app.model.AppModel.{AuthenticatedUser, UserInDb}
import app.services.AuthService
import io.circe.*
import io.circe.generic.auto.*
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.integ.cats.codec.given
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint

private final class FetchMultipleUsersByUserIdEp[F[_]: Async] private (
    jobHandler: JobHandler[F],
    authService: AuthService[F],
) extends ThalesEntryPoint[F]:
  private val fetchMultipleUsersByUserIdEpErrorOut: EndpointOutput[ApiError] = EndPointUtils.authenticatedStandardErrorOut

  private def strToAuthenticationError: String => ApiError = ApiError(EndPointUtils.UnauthenticatedApiError.errorCode, _)

  val getEntryPoint: ServerEndpoint[Any, F] =
    endpoint
      .errorOut(fetchMultipleUsersByUserIdEpErrorOut)
      .in("api")
      .securityIn(auth.bearer[String]())
      .serverSecurityLogic(EndPointUtils.authenticate(authService, strToAuthenticationError, _))
      .get
      .in("fetchMultipleBoUsersByUserId")
      .in(jsonBody[NonEmptyVector[Long]].description("A non-empty vector of userIds to fetch. The userIds must be unique."))
      .out(
        jsonBody[Map[Long, UserInDb]].description(
          "A map of userIds to their corresponding BoUserInDb objects. If a userId is not found, then it is simply omitted from this Map.",
        ),
      )
      .serverLogic(fetchMultipleUsersByUserId)
  end getEntryPoint

  private val unauthorizedError: Either[ApiError, Map[Long, UserInDb]] = Left(EndPointUtils.UnauthorizedApiError)

  private def fetchMultipleUsersByUserId(authenticatedUser: AuthenticatedUser)(
      userIds: NonEmptyVector[Long],
  ): F[Either[ApiError, Map[Long, UserInDb]]] =
    jobHandler.jobHandlerWithAuth[FetchMultipleUsersByIdResult, ApiError, Map[Long, UserInDb]](
      authenticatedUser,
      FetchUserByPermissionsUtils.FetchUserPermissionsAlg,
      FetchMultipleUsersByIdRequest(userIds),
      { case FetchMultipleUsersByIdResult(res) => Right(res) },
      unauthorizedError,
    )
  end fetchMultipleUsersByUserId
end FetchMultipleUsersByUserIdEp

object FetchMultipleUsersByUserIdEp:
  def create[F[_]: Async](jobHandler: JobHandler[F], authService: AuthService[F]): ThalesEntryPoint[F] =
    FetchMultipleUsersByUserIdEp[F](jobHandler, authService)
  end create
end FetchMultipleUsersByUserIdEp
