package app.entrypoints

import cats.effect.Async

import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra}
import app.entrypoints.EndPointsBases.ApiError
import app.model.AppModel
import app.model.AppModel.AuthenticatedBoUser
import app.JobSpecs.CreateBoUserError
import app.JobSpecs.JobKind.CreateBoUserRequest
import app.JobSpecs.JobResult.CreateBoUserResult
import app.ThalesUtils.ImplicitConversionUtils.view
import io.circe.*
import io.circe.{Decoder, Encoder}
import io.circe.generic.auto.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint

private final class CreateBoUserWithAuthEp[F[_]: Async] private (jobHandler: JobHandler[F], endPointsBases: EndPointsBases[F])
    extends ThalesEntryPoint[F]:
  val getEntryPoint: ServerEndpoint[Any, F] =
    endPointsBases.AuthenticatedEndPoint.post
      .in("createBoUser")
      .in(jsonBody[AppModel.BoUser])
      .out(jsonBody[CreateBoUserWithAuthEpResponse])
      .serverLogic(createBoUserWithAuth)

  private final case class CreateBoUserWithAuthEpResponse(userId: Long)

  private def createBoUserWithAuth(authenticatedBoUser: AuthenticatedBoUser)(
      boUser: AppModel.BoUser,
  ): F[Either[EndPointsBases.EndPointErrorResult, CreateBoUserWithAuthEpResponse]] =
    jobHandler.jobHandlerWithAuth[CreateBoUserResult, CreateBoUserWithAuthEpResponse](
      authenticatedBoUser,
      CreateBoUserPermissionsAlg,
      CreateBoUserRequest(boUser),
      { case CreateBoUserResult(res) =>
        res match {
          case Left(CreateBoUserError.InvalidParameters(invalidParams)) =>
            Left((StatusCode.BadRequest, ApiError("INVALID_PARAMETERS", s"Invalid parameters: [$invalidParams].")))
          case Left(CreateBoUserError.DuplicateLoginName(loginName)) =>
            Left(
              (
                StatusCode.Conflict,
                ApiError("LOGIN_ALREADY_EXISTS", s"The given loginName '$loginName' was already present in the database."),
              ),
            )
          case Left(CreateBoUserError.BadPassword(errorList)) =>
            val errorStr = errorList.view.mkString("\"", "\", \"", "\"")
            Left((StatusCode.BadRequest, ApiError("INVALID_PASSWORD", s"Invalid password. Errors: [$errorStr]")))
          case Right(userId) =>
            Right(CreateBoUserWithAuthEpResponse(userId))
        }
      },
    )
  end createBoUserWithAuth

  private val CreateBoUserPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanCreateBoUsers).compile
  end CreateBoUserPermissionsAlg
end CreateBoUserWithAuthEp

object CreateBoUserWithAuthEp:
  def create[F[_]: Async](jobHandler: JobHandler[F], endPointsBases: EndPointsBases[F]): ThalesEntryPoint[F] =
    CreateBoUserWithAuthEp[F](jobHandler, endPointsBases)
  end create
end CreateBoUserWithAuthEp
