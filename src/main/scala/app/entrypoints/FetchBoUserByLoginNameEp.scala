package app.entrypoints

import cats.effect.Async

import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra}
import app.model.AppModel
import app.JobSpecs.FetchBoUserByError
import app.JobSpecs.JobKind.FetchBoUserByLoginNameRequest
import app.JobSpecs.JobResult.FetchBoUserByLoginNameResult
import io.circe.*
import io.circe.generic.auto.*
import org.http4s.{ContextRequest, EntityDecoder}
import org.typelevel.log4cats.Logger

final class FetchBoUserByLoginNameEp[F[_]: { Async, Logger }](jobHandler: JobHandler[F]):
  def go(
      ctxReq: ContextRequest[F, AppModel.AuthenticatedBoUser],
      loginName: String,
  ): F[WebServiceResult.WsrKind] =
    jobHandler.jobHandlerWithAuth[FetchBoUserByLoginNameResult](
      ctxReq,
      FetchBoUserByPermissionsUtils.FetchBoUserPermissionsAlg,
      FetchBoUserByLoginNameRequest(loginName),
      { case FetchBoUserByLoginNameResult(res) =>
        res match {
          case Left(FetchBoUserByError.UserNotFound()) =>
            WebServiceResult.notFoundResult(s"The given loginName '$loginName' was not found.")
          case Right(r) => WebServiceResult.okResult(r)
        }
      },
    )
  end go
end FetchBoUserByLoginNameEp
