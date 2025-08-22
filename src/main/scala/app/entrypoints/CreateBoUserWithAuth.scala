package app.entrypoints

import cats.effect.Async
import cats.implicits.*

import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra}
import app.model.AppModel
import app.JobSpecs.CreateBoUserError
import app.JobSpecs.JobKind.CreateBoUserRequest
import app.JobSpecs.JobResult.CreateBoUserResult
import app.ThalesUtils.ImplicitConversionUtils.view
import io.circe.*
import io.circe.syntax.*
import org.http4s.{ContextRequest, EntityDecoder}
import org.typelevel.log4cats.Logger

final class CreateBoUserWithAuth[F[_]: { Async, Logger }](jobHandler: JobHandler[F])(using
    EntityDecoder[F, AppModel.BoUser],
):
  private val CreateBoUserPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanCreateBoUsers).compile

  def go(ctxReq: ContextRequest[F, AppModel.AuthenticatedBoUser]): F[WebServiceResult.WsrKind] =
    ctxReq.req.as[AppModel.BoUser].attempt >>= {
      case Left(_) => WebServiceResult.badRequestResultF("Invalid request body")
      case Right(boUser) =>
        jobHandler.jobHandlerWithAuth[CreateBoUserResult](
          ctxReq,
          CreateBoUserPermissionsAlg,
          CreateBoUserRequest(boUser),
          { case CreateBoUserResult(res) =>
            res match {
              case Left(CreateBoUserError.InvalidParameters(invalidParams)) =>
                WebServiceResult.badRequestResult(s"Invalid parameters: $invalidParams]")
              case Left(CreateBoUserError.DuplicateLoginName(loginName)) =>
                WebServiceResult.conflictResult(s"The given loginName '$loginName' was already present in the database.")
              case Left(CreateBoUserError.BadPassword(errorList)) =>
                val errorStr = errorList.view.mkString("\"", "\", \"", "\"")
                WebServiceResult.badRequestResult(s"Invalid password. Errors: [$errorStr]")
              case Right(userId) =>
                WebServiceResult.okResult(Json.obj("userId" -> userId.asJson))
            }
          },
        )
    }
  end go
end CreateBoUserWithAuth
