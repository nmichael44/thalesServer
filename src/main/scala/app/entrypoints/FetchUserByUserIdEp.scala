package app.entrypoints

import cats.effect.Async

import app.JobSpecs.FetchUserByError
import app.JobSpecs.JobKind.FetchUserByIdRequest
import app.JobSpecs.JobResult.FetchUserByIdResult
import app.entrypoints.EndPointUtils.ApiError
import app.model.AppModel
import app.model.AppModel.AuthenticatedUser
import app.services.AuthService
import io.circe.*
import io.circe.generic.auto.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint
import app.entrypoints.smithy.UserInDb
import app.entrypoints.SmithyCodecs.given

private final class FetchBoUserByUserIdEp[F[_]: Async] private (jobHandler: JobHandler[F], authService: AuthService[F])
    extends ThalesEntryPoint[F]:
  private val UserNotFoundApiError: ApiError =
    ApiError("USER_DOES_NOT_EXIST", "No user with given userId was found in the system.")
  end UserNotFoundApiError

  private val fetchBoUserByUserIdEpErrorOut: EndpointOutput[ApiError] =
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
  end fetchBoUserByUserIdEpErrorOut

  private def strToAuthenticationError(str: String): ApiError =
    ApiError(EndPointUtils.UnauthenticatedApiError.errorCode, str)
  end strToAuthenticationError

  override val getEntryPoint: ServerEndpoint[Any, F] =
    endpoint
      .errorOut(fetchBoUserByUserIdEpErrorOut)
      .in("api")
      .securityIn(auth.bearer[String]())
      .serverSecurityLogic(EndPointUtils.authenticate(authService, strToAuthenticationError, _))
      .get
      .in("fetchBoUserByUserId" / path[Long]("userId").description("The userId of the user to fetch."))
      .out(jsonBody[UserInDb])
      .serverLogic(fetchBoUserByUserId)
  end getEntryPoint

  private val doUserNotFound: Either[ApiError, UserInDb] = Left(UserNotFoundApiError)

  private val unauthorizedError: Either[ApiError, UserInDb] = Left(EndPointUtils.UnauthorizedApiError)

  private def fetchBoUserByUserId(authenticatedBoUser: AuthenticatedUser)(
      userId: Long,
  ): F[Either[ApiError, UserInDb]] =
    jobHandler.jobHandlerWithAuth[FetchUserByIdResult, ApiError, UserInDb](
      authenticatedBoUser,
      FetchUserByPermissionsUtils.FetchUserPermissionsAlg,
      FetchUserByIdRequest(userId),
      { case FetchUserByIdResult(res) =>
        res match {
          case Left(FetchUserByError.UserNotFound) => doUserNotFound
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
