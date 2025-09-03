package app.entrypoints

import cats.effect.Async

import app.entrypoints.EndPointUtils.ApiError
import app.model.AppModel
import app.model.AppModel.{AuthenticatedBoUser, BoUserInDb}
import app.services.AuthService
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

private final class FetchBoUserByUserIdEp[F[_]: Async] private (jobHandler: JobHandler[F], authService: AuthService[F])
    extends ThalesEntryPoint[F]:
  private val UserNotFoundApiError: ApiError =
    ApiError("USER_DOES_NOT_EXIST", "No user with given userId was found in the system.")

  private enum FetchBoUserByUserIdEpError:
    case UnauthenticatedError(error: ApiError)
    case UnauthorizedError(error: ApiError)
    case UserNotFoundError(error: ApiError)
  end FetchBoUserByUserIdEpError

  private val fetchBoUserByUserIdEpErrorOut: EndpointOutput[FetchBoUserByUserIdEpError] =
    oneOf[FetchBoUserByUserIdEpError](
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.Unauthorized)
          .and(jsonBody[ApiError].example(EndPointUtils.UnauthenticatedApiError))
          .mapTo[FetchBoUserByUserIdEpError.UnauthenticatedError],
      ),
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.Forbidden)
          .and(jsonBody[ApiError].example(EndPointUtils.UnauthorizedApiError))
          .mapTo[FetchBoUserByUserIdEpError.UnauthorizedError],
      ),
      oneOfVariant(
        EndPointUtils
          .statusCodeWithDescription(StatusCode.NotFound)
          .and(jsonBody[ApiError].example(UserNotFoundApiError))
          .mapTo[FetchBoUserByUserIdEpError.UserNotFoundError],
      ),
    )
  end fetchBoUserByUserIdEpErrorOut

  private def strToAuthenticationError(str: String): FetchBoUserByUserIdEpError =
    FetchBoUserByUserIdEpError.UnauthenticatedError(ApiError(EndPointUtils.UnauthenticatedApiError.errorCode, str))
  end strToAuthenticationError

  override val getEntryPoint: ServerEndpoint[Any, F] =
    endpoint
      .errorOut(fetchBoUserByUserIdEpErrorOut)
      .in("api")
      .securityIn(auth.bearer[String]())
      .serverSecurityLogic(EndPointUtils.authenticate(authService, strToAuthenticationError, _))
      .get
      .in("fetchBoUserByUserId" / path[Long]("userId").description("The userId of the user to fetch."))
      .out(jsonBody[BoUserInDb])
      .serverLogic(fetchBoUserByUserId)

  private val doUserNotFound: Either[FetchBoUserByUserIdEpError, BoUserInDb] = Left(
    FetchBoUserByUserIdEpError.UserNotFoundError(UserNotFoundApiError),
  )
  end doUserNotFound

  private val unauthorizedError: Either[FetchBoUserByUserIdEpError, BoUserInDb] =
    Left(FetchBoUserByUserIdEpError.UnauthorizedError(EndPointUtils.UnauthorizedApiError))
  end unauthorizedError

  private def fetchBoUserByUserId(authenticatedBoUser: AuthenticatedBoUser)(
      userId: Long,
  ): F[Either[FetchBoUserByUserIdEpError, BoUserInDb]] =
    jobHandler.jobHandlerWithAuth[FetchBoUserByIdResult, FetchBoUserByUserIdEpError, BoUserInDb](
      authenticatedBoUser,
      FetchBoUserByPermissionsUtils.FetchBoUserPermissionsAlg,
      FetchBoUserByIdRequest(userId),
      { case FetchBoUserByIdResult(res) =>
        res match {
          case Left(FetchBoUserByError.UserNotFound()) => doUserNotFound
          case Right(boUserInDb) => Right(boUserInDb)
        }
      },
      unauthorizedError,
    )
  end fetchBoUserByUserId
end FetchBoUserByUserIdEp

object FetchBoUserByUserIdEp:
  def create[F[_]: Async](jobHandler: JobHandler[F], authService: AuthService[F]): ThalesEntryPoint[F] =
    FetchBoUserByUserIdEp[F](jobHandler, authService)
  end create
end FetchBoUserByUserIdEp
