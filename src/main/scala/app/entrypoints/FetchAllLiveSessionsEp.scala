package app.entrypoints

import cats.effect.Async

import java.time.Instant

import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra}
import app.entrypoints.EndPointUtils.ApiError
import app.model.AppModel
import app.model.AppModel.{AuthenticatedBoUser, BoUserInDb}
import app.services.AuthService
import app.JobSpecs.JobKind.FetchAllLiveSessionsRequest
import app.JobSpecs.JobResult.FetchAllLiveSessionsResult
import io.circe.*
import io.circe.generic.auto.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint

private final class FetchAllLiveSessionsEp[F[_]: Async] private (jobHandler: JobHandler[F], authService: AuthService[F])
    extends ThalesEntryPoint[F]:
  private enum FetchAllLiveSessionsEpError:
    case UnauthenticatedError(error: ApiError)
    case UnauthorizedError(error: ApiError)
  end FetchAllLiveSessionsEpError

  private val fetchBoUserByUserIdEpErrorOut: EndpointOutput[FetchAllLiveSessionsEpError] =
    oneOf(
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.Unauthorized)
          .and(jsonBody[ApiError].example(EndPointUtils.UnauthenticatedApiError))
          .mapTo[FetchAllLiveSessionsEpError.UnauthenticatedError],
      ),
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.Forbidden)
          .and(jsonBody[ApiError].example(EndPointUtils.UnauthorizedApiError))
          .mapTo[FetchAllLiveSessionsEpError.UnauthorizedError],
      ),
    )
  end fetchBoUserByUserIdEpErrorOut

  private def strToAuthenticationError(str: String): FetchAllLiveSessionsEpError =
    FetchAllLiveSessionsEpError.UnauthenticatedError(ApiError(EndPointUtils.UnauthenticatedApiError.errorCode, str))
  end strToAuthenticationError

  val getEntryPoint: ServerEndpoint[Any, F] =
    endpoint
      .errorOut(fetchBoUserByUserIdEpErrorOut)
      .in("api")
      .securityIn(auth.bearer[String]())
      .serverSecurityLogic(EndPointUtils.authenticate(authService, strToAuthenticationError, _))
      .get
      .in("fetchAllLiveSessions")
      .out(jsonBody[Vector[(BoUserInDb, Instant)]])
      .serverLogic(fetchAllLiveSessions)

  private val unauthorizedError: Either[FetchAllLiveSessionsEpError, Vector[(BoUserInDb, Instant)]] =
    Left(FetchAllLiveSessionsEpError.UnauthorizedError(EndPointUtils.UnauthorizedApiError))
  end unauthorizedError

  private def fetchAllLiveSessions(
      authenticatedBoUser: AuthenticatedBoUser,
  )(u: Unit): F[Either[FetchAllLiveSessionsEpError, Vector[(BoUserInDb, Instant)]]] =
    jobHandler.jobHandlerWithAuth[FetchAllLiveSessionsResult, FetchAllLiveSessionsEpError, Vector[(BoUserInDb, Instant)]](
      authenticatedBoUser,
      FetchAllLiveSessionsPermissionsAlg,
      FetchAllLiveSessionsRequest(),
      { case FetchAllLiveSessionsResult(res) => Right(res) },
      unauthorizedError,
    )
  end fetchAllLiveSessions

  private val FetchAllLiveSessionsPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanSeeAllLiveSessions).compile
end FetchAllLiveSessionsEp

object FetchAllLiveSessionsEp:
  def create[F[__]: Async](jobHandler: JobHandler[F], authService: AuthService[F]): ThalesEntryPoint[F] =
    FetchAllLiveSessionsEp[F](jobHandler, authService)
  end create
end FetchAllLiveSessionsEp
