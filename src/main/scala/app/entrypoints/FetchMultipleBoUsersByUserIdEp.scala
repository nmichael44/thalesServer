package app.entrypoints

import cats.data.NonEmptyVector
import cats.effect.Async

import app.model.AppModel
import app.model.AppModel.{AuthenticatedBoUser, BoUserInDb}
import app.JobSpecs.JobKind.FetchMultipleBoUsersByIdRequest
import app.JobSpecs.JobResult.FetchMultipleBoUsersByIdResult
import app.ThalesUtils.JsonCodecs.given
import io.circe.*
import io.circe.generic.auto.*
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.integ.cats.codec.given
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint

final class FetchMultipleBoUsersByUserIdEp[F[_]: Async](
    jobHandler: JobHandler[F],
    endPointsBases: EndPointsBases[F],
) extends ThalesEntryPoint[F]:
  val getEntryPoint: ServerEndpoint[Any, F] =
    endPointsBases.AuthenticatedEndPoint.get
      .in("fetchMultipleBoUsersByUserId")
      .in(jsonBody[NonEmptyVector[Long]])
      .out(jsonBody[Map[Long, BoUserInDb]])
      .serverLogic(fetchMultipleBoUsersByUserId)

  private def fetchMultipleBoUsersByUserId(authenticatedBoUser: AuthenticatedBoUser)(
      userIds: NonEmptyVector[Long],
  ): F[Either[EndPointsBases.EndPointErrorResult, Map[Long, BoUserInDb]]] =
    jobHandler.jobHandlerWithAuth[FetchMultipleBoUsersByIdResult, Map[Long, BoUserInDb]](
      authenticatedBoUser,
      FetchBoUserByPermissionsUtils.FetchBoUserPermissionsAlg,
      FetchMultipleBoUsersByIdRequest(userIds),
      { case FetchMultipleBoUsersByIdResult(res) => Right(res) },
    )
  end fetchMultipleBoUsersByUserId
end FetchMultipleBoUsersByUserIdEp
