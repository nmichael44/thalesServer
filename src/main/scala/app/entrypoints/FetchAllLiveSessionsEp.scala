package app.entrypoints

import cats.effect.Async

import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra}
import app.model.AppModel
import app.JobSpecs.JobKind.FetchAllLiveSessionsRequest
import app.JobSpecs.JobResult.FetchAllLiveSessionsResult
import io.circe.*
import io.circe.generic.auto.*
import org.http4s.ContextRequest
import org.typelevel.log4cats.Logger

class FetchAllLiveSessionsEp[F[_]: { Async, Logger }](jobHandler: JobHandler[F]):
  def go(ctxReq: ContextRequest[F, AppModel.AuthenticatedBoUser]): F[WebServiceResult.WsrKind] =
    jobHandler.jobHandlerWithAuth[FetchAllLiveSessionsResult](
      ctxReq,
      FetchAllLiveSessionsPermissionsAlg,
      FetchAllLiveSessionsRequest(),
      { case FetchAllLiveSessionsResult(res) => WebServiceResult.okResult(res) },
    )
  end go

  private val FetchAllLiveSessionsPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanSeeAllLiveSessions).compile
end FetchAllLiveSessionsEp
