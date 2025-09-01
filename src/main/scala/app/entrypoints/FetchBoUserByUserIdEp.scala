package app.entrypoints

import cats.effect.Async

import app.entrypoints.EndPointsBases.ApiError
import app.model.AppModel
import app.model.AppModel.{AuthenticatedBoUser, BoUserInDb}
import app.JobSpecs.FetchBoUserByError
import app.JobSpecs.JobKind.FetchBoUserByIdRequest
import app.JobSpecs.JobResult.FetchBoUserByIdResult
import io.circe.*
import io.circe.generic.auto.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint

private final class FetchBoUserByUserIdEp[F[_]: Async] private (jobHandler: JobHandler[F], endPointsBases: EndPointsBases[F])
    extends ThalesEntryPoint[F]:
  val getEntryPoint: ServerEndpoint[Any, F] =
    endPointsBases.AuthenticatedEndPoint.get
      .in("fetchBoUserByUserId" / path[Long]("userId").description("The userId of the user to fetch."))
      .out(jsonBody[BoUserInDb])
      .serverLogic(fetchBoUserByUserId)

  def fetchBoUserByUserId(authenticatedBoUser: AuthenticatedBoUser)(
      userId: Long,
  ): F[Either[EndPointsBases.EndPointErrorResult, BoUserInDb]] =
    jobHandler.jobHandlerWithAuth[FetchBoUserByIdResult, BoUserInDb](
      authenticatedBoUser,
      FetchBoUserByPermissionsUtils.FetchBoUserPermissionsAlg,
      FetchBoUserByIdRequest(userId),
      { case FetchBoUserByIdResult(res) =>
        res match {
          case Left(FetchBoUserByError.UserNotFound()) =>
            Left((StatusCode.NotFound, ApiError("USER_ID_DOES_NOT_EXIST", s"The given userId '$userId' was not found.")))
          case Right(_) => res.asInstanceOf[Either[EndPointsBases.EndPointErrorResult, BoUserInDb]]
        }
      },
    )
  end fetchBoUserByUserId
end FetchBoUserByUserIdEp

object FetchBoUserByUserIdEp:
  def create[F[_]: Async](jobHandler: JobHandler[F], endPointsBases: EndPointsBases[F]): ThalesEntryPoint[F] =
    FetchBoUserByUserIdEp[F](jobHandler, endPointsBases)
  end create
end FetchBoUserByUserIdEp
