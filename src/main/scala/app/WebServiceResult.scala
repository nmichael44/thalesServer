package app

import io.circe.Json

enum WebServiceResult:
  case OkJsonRes(json: Json)
  case NotFoundRes(s: String)
  case ConflictRes(s: String)
  case BadRequestRes(e: String)
  case UnauthorizedRes(e: String)
  case InternalServerErrorRes()
end WebServiceResult
