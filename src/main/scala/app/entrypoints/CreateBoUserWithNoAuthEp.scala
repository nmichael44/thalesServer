package app.entrypoints

import cats.effect.Async
import cats.implicits.*

import app.model.AppModel
import app.JobSpecs.CreateBoUserError
import app.JobSpecs.JobKind.CreateBoUserRequest
import app.JobSpecs.JobResult.CreateBoUserResult
import app.ThalesUtils.ImplicitConversionUtils.*
import io.circe.*
import io.circe.syntax.*
import org.http4s.{EntityDecoder, Request}
import org.typelevel.log4cats.Logger

final class CreateBoUserWithNoAuthEp[F[_]: { Async, Logger }](jobHandler: JobHandler[F])(using
    EntityDecoder[F, AppModel.BoUser],
):
  def go(req: Request[F]): F[WebServiceResult.WsrKind] =
    req.as[AppModel.BoUser].attempt >>= {
      case Left(e) => WebServiceResult.badRequestResultF(s"Invalid request body: ${e.getMessage}")
      case Right(boUser) =>
        jobHandler.jobHandlerNoAuthF[CreateBoUserResult](
          req,
          CreateBoUserRequest(boUser),
          { case CreateBoUserResult(res) =>
            res match {
              case Left(CreateBoUserError.InvalidParameters(invalidParams)) =>
                WebServiceResult.badRequestResult(s"Invalid parameters: $invalidParams]").pure
              case Left(CreateBoUserError.DuplicateLoginName(loginName)) =>
                WebServiceResult.conflictResult(s"The given loginName '$loginName' was already present in the database.").pure
              case Left(CreateBoUserError.BadPassword(errorList)) =>
                val errorStr = errorList.view.mkString("\"", "\", \"", "\"")
                WebServiceResult.badRequestResult(s"Invalid password. Errors: [$errorStr]").pure
              case Right(userId) =>
                WebServiceResult.okResult(Json.obj("userId" -> userId.asJson)).pure
            }
          },
        )
    }
  end go
end CreateBoUserWithNoAuthEp
