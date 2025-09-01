package app.entrypoints

import cats.effect.Async
import cats.implicits.*

import app.entrypoints.EndPointsBases.{ApiError, EndPointErrorResult}
import app.model.AppModel
import app.JobSpecs.CreateBoUserError
import app.JobSpecs.JobKind.CreateBoUserRequest
import app.JobSpecs.JobResult.CreateBoUserResult
import app.ThalesUtils.ImplicitConversionUtils.*
import io.circe.*
import io.circe.{Decoder, Encoder}
import io.circe.generic.auto.*
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.jsonBody
import sttp.tapir.server.ServerEndpoint

final class CreateBoUserWithNoAuthEp[F[_]: Async as async](
    jobHandler: JobHandler[F],
    endPointsBases: EndPointsBases[F],
) extends ThalesEntryPoint[F]:
  override val getEntryPoint: ServerEndpoint[Any, F] =
    endPointsBases.PublicEndPoint.post
      .in("createBoUser")
      .in(jsonBody[AppModel.BoUser])
      .out(jsonBody[CreateBoUserWithNoAuthEpResponse])
      .description("Login into the system using loginName and password.")
      .serverLogic(createBoUserWithNoAuth)

  private final case class CreateBoUserWithNoAuthEpResponse(userId: Long)

  private def createBoUserWithNoAuth(
      boUser: AppModel.BoUser,
  ): F[Either[EndPointErrorResult, CreateBoUserWithNoAuthEpResponse]] = ???
//    jobHandler.jobHandlerNoAuthF[CreateBoUserResult, CreateBoUserWithNoAuthEpResponse](
//      CreateBoUserRequest(boUser),
//      { case CreateBoUserResult(res) =>
//        async.pure(res match {
//          case Left(CreateBoUserError.InvalidParameters(invalidParams)) =>
//            Left((StatusCode.BadRequest, ApiError("INVALID_PARAMETERS", s"Invalid parameters: [$invalidParams].")))
//          case Left(CreateBoUserError.DuplicateLoginName(loginName)) =>
//            Left(
//              (
//                StatusCode.Conflict,
//                ApiError("LOGIN_ALREADY_EXISTS", s"The given loginName '$loginName' was already present in the database."),
//              ),
//            )
//          case Left(CreateBoUserError.BadPassword(errorList)) =>
//            val errorStr = errorList.view.mkString("\"", "\", \"", "\"")
//            Left((StatusCode.BadRequest, ApiError("INVALID_PASSWORD", s"Invalid password. Errors: [$errorStr]")))
//          case Right(userId) =>
//            Right(CreateBoUserWithNoAuthEpResponse(userId))
//        })
//      },
//    )
end CreateBoUserWithNoAuthEp
