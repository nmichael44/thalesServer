package app.entrypoints

import cats.effect.Async
import cats.implicits.*

import app.model.AppModel
import app.services.ServerState
import app.uuid.UUIDGenerator
import app.JobSpecs.JobKind.LoginRequest
import app.JobSpecs.JobResult.LoginResult
import app.JobSpecs.LoginError
import app.ThalesUtils.TimeUtils
import io.circe.syntax.*
import io.circe.Json
import org.http4s.{EntityDecoder, Request}
import org.typelevel.log4cats.Logger

final class LoginRequestEp[F[_]: { Async, Logger }](jobHandler: JobHandler[F], serverState: ServerState[F])(using
    EntityDecoder[F, AppModel.LoginUserDetails],
):
  def go(req: Request[F]): F[WebServiceResult.WsrKind] =
    req.as[AppModel.LoginUserDetails].attempt >>= {
      case Left(_) => InvalidRequestBody
      case Right(userDetails) =>
        jobHandler.jobHandlerNoAuthF[LoginResult](
          req,
          LoginRequest(userDetails),
          { case LoginResult(res) =>
            res match {
              case Left(LoginError.InvalidLoginPassword()) => InvalidLoginNamePassword.pure
              case Left(LoginError.UserNotEnabled(loginName)) => InactiveUser.pure
              case Right((userId, token)) =>
                for {
                  now <- TimeUtils.nowInstant
                  _ <- serverState.lastAccess.update(_ + (userId -> now))
                } yield WebServiceResult.okResult(Json.obj("token" -> token.asJson))
            }
          },
        )
    }
  end go

  private val InvalidRequestBody: F[WebServiceResult.WsrKind] = WebServiceResult.badRequestResultF("Invalid request body")

  private val InvalidLoginNamePassword: WebServiceResult.WsrKind =
    WebServiceResult.unauthorizedResult("Invalid loginName/password specified.")

  private val InactiveUser: WebServiceResult.WsrKind = WebServiceResult.unauthorizedResult("Inactive User.")
end LoginRequestEp
