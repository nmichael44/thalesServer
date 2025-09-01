package app.entrypoints

import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.EndpointOutput

object StatusCodeUtils:
  private val StatusCodeToString: Map[Int, String] = Map(
    StatusCode.Unauthorized.code        -> "Unauthorized",
    StatusCode.Forbidden.code           -> "Forbidden",
    StatusCode.Locked.code              -> "Locked",
    StatusCode.NotFound.code            -> "NotFound",
    StatusCode.BadRequest.code          -> "BadRequest",
    StatusCode.Conflict.code            -> "Conflict",
    StatusCode.InternalServerError.code -> "InternalServerError",
  )

  def statusCodeWithDescription(sc: StatusCode): EndpointOutput.FixedStatusCode[Unit] =
    statusCode(sc).description(StatusCodeToString(sc.code))
  end statusCodeWithDescription
