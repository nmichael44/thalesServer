package app.entrypoints

import cats.effect.Async

import java.time.Instant

import app.JobSpecs.JobKind.FetchAllLiveSessionsRequest
import app.JobSpecs.JobResult.FetchAllLiveSessionsResult
import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra}
import app.entrypoints.EndPointUtils.ApiError
import app.model.AppModel
import app.model.AppModel.{AuthenticatedUser, UserInDb}
import app.services.AuthService
import io.circe.*
import io.circe.generic.auto.*
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint

private final class FetchAllLiveSessionsEp[F[_]: Async] private (jobHandler: JobHandler[F], authService: AuthService[F])
    extends ThalesEntryPoint[F]:
  private val fetchBoUserByUserIdEpErrorOut: EndpointOutput[ApiError] = EndPointUtils.authenticatedStandardErrorOut

  private val strToAuthenticationError: String => ApiError = ApiError(EndPointUtils.UnauthenticatedApiError.errorCode, _)

  val getEntryPoint: ServerEndpoint[Any, F] =
    endpoint
      .errorOut(fetchBoUserByUserIdEpErrorOut)
      .in("api")
      .securityIn(auth.bearer[String]())
      .serverSecurityLogic(EndPointUtils.authenticate(authService, strToAuthenticationError, _))
      .get
      .in("fetchAllLiveSessions")
      .out(jsonBody[Vector[(UserInDb, Instant)]])
      .serverLogic(fetchAllLiveSessions)
  end getEntryPoint

  private val unauthorizedError: Either[ApiError, Vector[(UserInDb, Instant)]] = Left(EndPointUtils.UnauthorizedApiError)

  private val FetchAllLiveSessionsPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanSeeAllLiveSessions).compile
  end FetchAllLiveSessionsPermissionsAlg

  private def fetchAllLiveSessions(
      authenticatedBoUser: AuthenticatedUser,
  )(u: Unit): F[Either[ApiError, Vector[(UserInDb, Instant)]]] =
    jobHandler.jobHandlerWithAuth[FetchAllLiveSessionsResult, ApiError, Vector[(UserInDb, Instant)]](
      authenticatedBoUser,
      FetchAllLiveSessionsPermissionsAlg,
      FetchAllLiveSessionsRequest,
      { case FetchAllLiveSessionsResult(res) => Right(res) },
      unauthorizedError,
    )
  end fetchAllLiveSessions
end FetchAllLiveSessionsEp

object FetchAllLiveSessionsEp:
  def create[F[__]: Async](jobHandler: JobHandler[F], authService: AuthService[F]): ThalesEntryPoint[F] =
    FetchAllLiveSessionsEp[F](jobHandler, authService)
  end create
end FetchAllLiveSessionsEp
