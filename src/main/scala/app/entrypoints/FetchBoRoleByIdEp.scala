package app.entrypoints

import cats.effect.Async

import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra}
import app.entrypoints.EndPointUtils.ApiError
import app.model.AppModel
import app.model.AppModel.{AuthenticatedBoUser, BoRoleInDb}
import app.services.AuthService
import app.JobSpecs.FetchBoRoleByError
import app.JobSpecs.JobKind.FetchBoRoleByIdRequest
import app.JobSpecs.JobResult.FetchBoRoleByIdResult
import io.circe.*
import io.circe.generic.auto.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint

private final class FetchBoRoleByIdEp[F[_]: Async] private (jobHandler: JobHandler[F], authService: AuthService[F])
    extends ThalesEntryPoint[F]:
  private val RoleNotFoundApiError: ApiError =
    ApiError("ROLE_DOES_NOT_EXIST", "No role with given roleId was found in the system.")
  end RoleNotFoundApiError

  private val fetchBoRoleByIdEpErrorOut: EndpointOutput[ApiError] =
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
          .and(jsonBody[ApiError].example(RoleNotFoundApiError)),
      ),
    )
  end fetchBoRoleByIdEpErrorOut

  private def strToAuthenticationError(str: String): ApiError =
    ApiError(EndPointUtils.UnauthenticatedApiError.errorCode, str)
  end strToAuthenticationError

  override val getEntryPoint: ServerEndpoint[Any, F] =
    endpoint
      .errorOut(fetchBoRoleByIdEpErrorOut)
      .in("api")
      .securityIn(auth.bearer[String]())
      .serverSecurityLogic(EndPointUtils.authenticate(authService, strToAuthenticationError, _))
      .get
      .in("fetchBoRoleById" / path[Long]("roleId").description("The roleId of the role to fetch."))
      .out(jsonBody[BoRoleInDb])
      .serverLogic(fetchBoRoleById)
  end getEntryPoint

  private val doRoleNotFound: Either[ApiError, BoRoleInDb] = Left(RoleNotFoundApiError)

  private val unauthorizedError: Either[ApiError, BoRoleInDb] = Left(EndPointUtils.UnauthorizedApiError)

  private val FetchBoRolePermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanSeeAllBoRoles).compile
  end FetchBoRolePermissionsAlg

  private def fetchBoRoleById(authenticatedBoUser: AuthenticatedBoUser)(
      roleId: Long,
  ): F[Either[ApiError, BoRoleInDb]] =
    jobHandler.jobHandlerWithAuth[FetchBoRoleByIdResult, ApiError, BoRoleInDb](
      authenticatedBoUser,
      FetchBoRolePermissionsAlg,
      FetchBoRoleByIdRequest(roleId),
      { case FetchBoRoleByIdResult(res) =>
        res match {
          case Left(FetchBoRoleByError.NoSuchRole) => doRoleNotFound
          case Right(boRoleInDb) => Right(boRoleInDb)
        }
      },
      unauthorizedError,
    )
  end fetchBoRoleById
end FetchBoRoleByIdEp

object FetchBoRoleByIdEp:
  def create[F[_]: Async](jobHandler: JobHandler[F], authService: AuthService[F]): ThalesEntryPoint[F] =
    FetchBoRoleByIdEp[F](jobHandler, authService)
  end create
end FetchBoRoleByIdEp
