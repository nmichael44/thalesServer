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

  def badRequestResultF[F[_]: Async as async](errMsg: String): F[WebServiceResult.WsrKind] =
    async.pure(WebServiceResult.badRequestResult(errMsg))

  def okResult(json: Json): WsrKind =
    WsrKind.OkJsonRes(json)

  def okResult[A: Encoder](a: A): WsrKind =
    okResult(a.asJson)

  def unauthorizedResult(errMsg: String): WsrKind =
    WsrKind.UnauthorizedRes(errMsg)

  def notFoundResult(errMsg: String): WsrKind =
    WsrKind.NotFoundRes(errMsg)

  def conflictResult(errMsg: String): WsrKind =
    WsrKind.ConflictRes(errMsg)

  def internalServerErrorResult(): WsrKind =
    WsrKind.InternalServerErrorRes()
end WebServiceResult
