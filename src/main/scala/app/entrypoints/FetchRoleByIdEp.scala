package app.entrypoints

import cats.effect.Async

import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra}
import app.entrypoints.smithy.RoleInDb
import app.entrypoints.EndPointUtils.ApiError
import app.model.AppModel
import app.model.AppModel.AuthenticatedUser
import app.services.AuthService
import app.JobSpecs.FetchRoleByError
import app.JobSpecs.JobKind.FetchRoleByIdRequest
import app.JobSpecs.JobResult.FetchRoleByIdResult
import io.circe.*
import io.circe.generic.auto.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint

private final class FetchRoleByIdEp[F[_]: Async] private (jobHandler: JobHandler[F], authService: AuthService[F])
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
      // foo
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
      .out(jsonBody[RoleInDb])
      .serverLogic(fetchRoleById)
  end getEntryPoint

  private val doRoleNotFound: Either[ApiError, RoleInDb] = Left(RoleNotFoundApiError)

  private val unauthorizedError: Either[ApiError, RoleInDb] = Left(EndPointUtils.UnauthorizedApiError)

  private val FetchRolePermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanSeeAllBoRoles).compile
  end FetchRolePermissionsAlg

  private def fetchRoleById(authenticatedBoUser: AuthenticatedUser)(
      roleId: Long,
  ): F[Either[ApiError, RoleInDb]] =
    jobHandler.jobHandlerWithAuth[FetchRoleByIdResult, ApiError, RoleInDb](
      authenticatedBoUser,
      FetchRolePermissionsAlg,
      FetchRoleByIdRequest(roleId),
      { case FetchRoleByIdResult(res) =>
        res match {
          case Left(FetchRoleByError.RoleNotFound) => doRoleNotFound
          case Right(boRoleInDb) => Right(boRoleInDb)
        }
      },
      unauthorizedError,
    )
  end fetchRoleById
end FetchRoleByIdEp

object FetchRoleByIdEp:
  def create[F[_]: Async](jobHandler: JobHandler[F], authService: AuthService[F]): ThalesEntryPoint[F] =
    FetchRoleByIdEp[F](jobHandler, authService)
  end create
end FetchRoleByIdEp
