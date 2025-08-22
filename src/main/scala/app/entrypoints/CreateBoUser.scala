package app.entrypoints

import cats.effect.Async
import cats.implicits.*

import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra}
import app.model.AppModel
import app.services.ServerState
import app.uuid.UUIDGenerator
import app.JobSpecs.JobKind.CreateBoUserRequest
import app.JobSpecs.JobResult.CreateBoUserResult
import app.WebServiceResult
import org.http4s.{ContextRequest, EntityDecoder}
import org.typelevel.log4cats.Logger

final class CreateBoUser[F[_]: { Async, Logger }](serverState: ServerState[F], uuidGen: UUIDGenerator[F])(using
    EntityDecoder[F, AppModel.BoUser],
)
//final class CreateBoUser[F[_]: { Async, Logger }](serverState: ServerState[F], uuidGen: UUIDGenerator[F])(using
//    EntityDecoder[F, AppModel.BoUser],
//):
//  private val CreateBoUserPermissionsAlg: CompiledPermissionAlgebra =
//    PermissionAlgebra.Has(Permission.CanCreateBoUsers).compile
//
//  def go(ctxReq: ContextRequest[F, AppModel.AuthenticatedBoUser]): F[WebServiceResult] =
//    ctxReq.req.as[AppModel.BoUser].attempt >>= {
//      case Left(_) => badRequestResultF("Invalid request body")
//      case Right(boUser) =>
//        jobHandlerWithAuth[CreateBoUserResult](
//          ctxReq,
//          CreateBoUserPermissionsAlg,
//          serverState,
//          uuidGen,
//          CreateBoUserRequest(boUser),
//          { case CreateBoUserResult(res) =>
//            res match {
//              case Left(CreateBoUserError.InvalidParameters(invalidParams)) =>
//                badRequestResult(s"Invalid parameters: $invalidParams]")
//              case Left(CreateBoUserError.DuplicateLoginName(loginName)) =>
//                conflictResult(s"The given loginName '$loginName' was already present in the database.")
//              case Left(CreateBoUserError.BadPassword(errorList)) =>
//                val errorStr = errorList.view.mkString("\"", "\", \"", "\"")
//                badRequestResult(s"Invalid password. Errors: [$errorStr]")
//              case Right(userId) =>
//                okResult(Json.obj("userId" -> userId.asJson))
//            }
//          },
//        )
//    }
//  end go
//end CreateBoUser
