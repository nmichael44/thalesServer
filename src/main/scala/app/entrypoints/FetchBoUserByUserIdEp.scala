package app.entrypoints

import cats.effect.Async

import app.model.AppModel
import app.JobSpecs.FetchBoUserByError
import app.JobSpecs.JobKind.FetchBoUserByIdRequest
import app.JobSpecs.JobResult.FetchBoUserByIdResult
import io.circe.*
import io.circe.generic.auto.*
import org.http4s.ContextRequest
import org.typelevel.log4cats.Logger

final class FetchBoUserByUserIdEp[F[_]: { Async, Logger }](jobHandler: JobHandler[F]):
  def go(
      ctxReq: ContextRequest[F, AppModel.AuthenticatedBoUser],
      userId: Long,
  ): F[WebServiceResult.WsrKind] =
    jobHandler.jobHandlerWithAuth[FetchBoUserByIdResult](
      ctxReq,
      FetchBoUserByPermissionsUtils.FetchBoUserPermissionsAlg,
      FetchBoUserByIdRequest(userId),
      { case FetchBoUserByIdResult(res) =>
        res match {
          case Left(FetchBoUserByError.UserNotFound()) =>
            WebServiceResult.notFoundResult(s"The given userId '$userId' was not found.")
          case Right(boUserIdDb) => WebServiceResult.okResult(boUserIdDb)
        }
      },
    )
  end go

  private val InvalidRequestBody: F[WebServiceResult.WsrKind] =
    WebServiceResult.badRequestResultF("Invalid request body")

  private val InvalidLoginNamePassword: WebServiceResult.WsrKind =
    WebServiceResult.unauthorizedResult("Invalid loginName/password specified.")

  private val InactiveUser: WebServiceResult.WsrKind =
    WebServiceResult.unauthorizedResult("Inactive User.")
end FetchBoUserByUserIdEp
