package app.entrypoints

import cats.effect.Async

import app.JobSpecs.FetchUserByError
import app.JobSpecs.JobKind.FetchUserByLoginNameRequest
import app.JobSpecs.JobResult.FetchUserByLoginNameResult
import app.entrypoints.EndPointUtils
import app.entrypoints.EndPointUtils.ApiError
import app.model.AppModel
import app.model.AppModel.AuthenticatedUser
import app.entrypoints.smithy.UserInDb

import app.services.AuthService
import io.circe.*
import io.circe.generic.auto.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint
import app.entrypoints.SmithyCodecs.given

private final class FetchBoUserByLoginNameEp[F[_]: Async] private (jobHandler: JobHandler[F], authService: AuthService[F])
    extends ThalesEntryPoint[F]:
  private val UserNotFoundApiError: ApiError =
    ApiError("USER_DOES_NOT_EXIST", "No user with given loginName was found in the system.")
  end UserNotFoundApiError

  private val fetchBoUserByLoginNameEpErrorOut: EndpointOutput[ApiError] =
    oneOf(
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.Unauthorized)
          .and(jsonBody[ApiError].example(EndPointUtils.UnauthenticatedApiError)),
      ),
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.Forbidden)
          .and(jsonBody[ApiError].example(EndPointUtils.UnauthorizedApiError)),
      ),
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.NotFound)
          .and(jsonBody[ApiError].example(UserNotFoundApiError)),
      ),
    )
  end fetchBoUserByLoginNameEpErrorOut

  private val strToAuthenticationError: String => ApiError = ApiError(EndPointUtils.UnauthenticatedApiError.errorCode, _)

  val getEntryPoint: ServerEndpoint[Any, F] =
    endpoint
      .errorOut(fetchBoUserByLoginNameEpErrorOut)
      .in("api")
      .securityIn(auth.bearer[String]())
      .serverSecurityLogic(EndPointUtils.authenticate(authService, strToAuthenticationError, _))
      .get
      .in("fetchBoUserByLoginName" / path[String]("loginName").description("The login name of the user."))
      .out(jsonBody[UserInDb])
      .serverLogic(fetchBoUserByLoginName)
  end getEntryPoint

  private val doUserNotFound: Either[ApiError, UserInDb] = Left(UserNotFoundApiError)

  private val unauthorizedError: Either[ApiError, UserInDb] = Left(EndPointUtils.UnauthorizedApiError)

  private def fetchBoUserByLoginName(authenticatedUser: AuthenticatedUser)(
      loginName: String,
  ): F[Either[ApiError, UserInDb]] =
    jobHandler.jobHandlerWithAuth[FetchUserByLoginNameResult, ApiError, UserInDb](
      authenticatedUser,
      FetchUserByPermissionsUtils.FetchUserPermissionsAlg,
      FetchUserByLoginNameRequest(loginName),
      { case FetchUserByLoginNameResult(res) =>
        res match {
          case Left(FetchUserByError.UserNotFound) => doUserNotFound
          case Right(user) => Right(user)
        }
      },
      unauthorizedError,
    )
  end fetchBoUserByLoginName
end FetchBoUserByLoginNameEp

object FetchBoUserByLoginNameEp:
  def create[F[_]: Async](jobHandler: JobHandler[F], authService: AuthService[F]): ThalesEntryPoint[F] =
    FetchBoUserByLoginNameEp[F](jobHandler, authService)
  end create
end FetchBoUserByLoginNameEp
