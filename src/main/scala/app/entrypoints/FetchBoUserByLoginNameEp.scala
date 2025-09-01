package app.entrypoints

import cats.effect.Async

import app.entrypoints.EndPointsBases.ApiError
import app.model.AppModel
import app.model.AppModel.AuthenticatedBoUser
import app.model.AppModel.BoUserInDb
import app.JobSpecs.FetchBoUserByError
import app.JobSpecs.JobKind.FetchBoUserByLoginNameRequest
import app.JobSpecs.JobResult.FetchBoUserByLoginNameResult
import io.circe.*
import io.circe.generic.auto.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint

private final class FetchBoUserByLoginNameEp[F[_]: Async] private (jobHandler: JobHandler[F], endPointsBases: EndPointsBases[F])
    extends ThalesEntryPoint[F]:
  val getEntryPoint: ServerEndpoint[Any, F] =
    endPointsBases.AuthenticatedEndPoint.get
      .in("fetchBoUserByLoginName" / path[String]("loginName").description("The login name of the user."))
      .out(jsonBody[BoUserInDb])
      .serverLogic(fetchBoUserByLoginName)

  private def fetchBoUserByLoginName(authenticatedBoUser: AuthenticatedBoUser)(
      loginName: String,
  ): F[Either[EndPointsBases.EndPointErrorResult, BoUserInDb]] =
    jobHandler.jobHandlerWithAuth[FetchBoUserByLoginNameResult, BoUserInDb](
      authenticatedBoUser,
      FetchBoUserByPermissionsUtils.FetchBoUserPermissionsAlg,
      FetchBoUserByLoginNameRequest(loginName),
      { case FetchBoUserByLoginNameResult(res) =>
        res match {
          case Left(FetchBoUserByError.UserNotFound()) =>
            Left(
              (StatusCode.NotFound, ApiError("LOGINNAME_DOES_NOT_EXIST", s"The given loginName '$loginName' was not found.")),
            )
          case Right(_) => res.asInstanceOf[Either[EndPointsBases.EndPointErrorResult, BoUserInDb]]
        }
      },
    )
  end fetchBoUserByLoginName
end FetchBoUserByLoginNameEp

object FetchBoUserByLoginNameEp:
  def create[F[_]: Async](jobHandler: JobHandler[F], endPointsBases: EndPointsBases[F]): ThalesEntryPoint[F] =
    FetchBoUserByLoginNameEp[F](jobHandler, endPointsBases)
  end create
end FetchBoUserByLoginNameEp
