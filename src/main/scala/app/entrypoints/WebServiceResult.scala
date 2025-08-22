package app.entrypoints

import cats.effect.Async

import io.circe.*
import io.circe.syntax.*
import io.circe.Encoder
import io.circe.Json

object WebServiceResult:
  enum WsrKind:
    case OkJsonRes(json: Json)
    case NotFoundRes(s: String)
    case ConflictRes(s: String)
    case BadRequestRes(e: String)
    case UnauthorizedRes(e: String)
    case InternalServerErrorRes()
  end WsrKind

  def badRequestResult(errMsg: String): WsrKind =
    WsrKind.BadRequestRes(errMsg)
  end badRequestResult

  def badRequestResultF[F[_]: Async as async](errMsg: String): F[WebServiceResult.WsrKind] =
    async.pure(WebServiceResult.badRequestResult(errMsg))
  end badRequestResultF

  def okResult(json: Json): WsrKind =
    WsrKind.OkJsonRes(json)
  end okResult

  def okResult[A: Encoder](a: A): WsrKind =
    okResult(a.asJson)
  end okResult

  def unauthorizedResult(errMsg: String): WsrKind =
    WsrKind.UnauthorizedRes(errMsg)
  end unauthorizedResult

  def notFoundResult(errMsg: String): WsrKind =
    WsrKind.NotFoundRes(errMsg)
  end notFoundResult

  def conflictResult(errMsg: String): WsrKind =
    WsrKind.ConflictRes(errMsg)
  end conflictResult

  def internalServerErrorResult(): WsrKind =
    WsrKind.InternalServerErrorRes()
  end internalServerErrorResult
end WebServiceResult
