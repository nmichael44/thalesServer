package app.entrypoints

import cats.data.NonEmptyVector
import cats.effect.Async
import cats.implicits.*

import app.model.AppModel
import app.JobSpecs.JobKind.FetchMultipleBoUsersByIdRequest
import app.JobSpecs.JobResult.FetchMultipleBoUsersByIdResult
import io.circe.*
import io.circe.generic.auto.*
import org.http4s.{ContextRequest, EntityDecoder}
import org.typelevel.log4cats.Logger

final class FetchMultipleBoUsersByUserIdEp[F[_]: { Async, Logger }](jobHandler: JobHandler[F])(using
    EntityDecoder[F, NonEmptyVector[Long]],
):
  def go(ctxReq: ContextRequest[F, AppModel.AuthenticatedBoUser]): F[WebServiceResult.WsrKind] =
    ctxReq.req.as[NonEmptyVector[Long]].attempt >>= {
      case Left(e) => WebServiceResult.badRequestResultF(s"Invalid request body: ${e.getMessage}")
      case Right(boUsers) =>
        jobHandler.jobHandlerWithAuth[FetchMultipleBoUsersByIdResult](
          ctxReq,
          FetchBoUserByPermissionsUtils.FetchBoUserPermissionsAlg,
          FetchMultipleBoUsersByIdRequest(boUsers),
          { case FetchMultipleBoUsersByIdResult(res) => WebServiceResult.okResult(res) },
        )
    }
  end go
end FetchMultipleBoUsersByUserIdEp
