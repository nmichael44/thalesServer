package app.entrypoints

import cats.effect.Async

import java.time.Instant

import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra}
import app.model.AppModel
import app.model.AppModel.{AuthenticatedBoUser, BoUserInDb}
import app.JobSpecs.JobKind.FetchAllLiveSessionsRequest
import app.JobSpecs.JobResult.FetchAllLiveSessionsResult
import io.circe.*
import io.circe.generic.auto.*
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint

final class FetchAllLiveSessionsEp[F[_]: Async](jobHandler: JobHandler[F], endPointsBases: EndPointsBases[F])
    extends ThalesEntryPoint[F]:
  val getEntryPoint: ServerEndpoint[Any, F] =
    endPointsBases.AuthenticatedEndPoint.get
      .in("fetchAllLiveSessions")
      .out(jsonBody[Vector[(BoUserInDb, Instant)]])
      .serverLogic(fetchAllLiveSessions)

  private def fetchAllLiveSessions(
      authenticatedBoUser: AuthenticatedBoUser,
  )(u: Unit): F[Either[EndPointsBases.EndPointErrorResult, Vector[(BoUserInDb, Instant)]]] =
    jobHandler.jobHandlerWithAuth[FetchAllLiveSessionsResult, Vector[(BoUserInDb, Instant)]](
      authenticatedBoUser,
      FetchAllLiveSessionsPermissionsAlg,
      FetchAllLiveSessionsRequest(),
      { case FetchAllLiveSessionsResult(res) => Right(res) },
    )
  end fetchAllLiveSessions

  private val FetchAllLiveSessionsPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanSeeAllLiveSessions).compile
end FetchAllLiveSessionsEp
