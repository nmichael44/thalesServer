package app.entrypoints

val x = 1
//import cats.data.NonEmptyVector
//import cats.effect.Async
//
//import app.JobSpecs.JobKind.ResetUserPasswordRequest
//import app.JobSpecs.JobResult
//import app.JobSpecs.ResetUserPasswordError.FailedToUpdateUserRow
//import app.JobSpecs.ResetUserPasswordError.InvalidLoginPassword
//import app.JobSpecs.ResetUserPasswordError.LoginNameNotFound
//import app.JobSpecs.ResetUserPasswordError.UserNotEnabled
//import app.ThalesUtils.ExtensionMethodUtils.*
//import app.entrypoints.EndPointUtils.ApiError
//import app.entrypoints.ThalesEntryPoint
//import io.circe.*
//import io.circe.{Decoder, Encoder}
//import io.circe.generic.auto.*
//import sttp.model.StatusCode
//import sttp.tapir.*
//import sttp.tapir.generic.auto.*
//import sttp.tapir.json.circe.jsonBody
//import sttp.tapir.server.ServerEndpoint
//
//private final class ResetBoUserPasswordEp[F[_]: Async as async] private (jobHandler: JobHandler[F]) extends ThalesEntryPoint[F]:
//  private val LoginNameNotFoundApiError: ApiError =
//    ApiError("LOGINNAME_NOT_FOUND", "The given loginName was not found in the system.")
//  end LoginNameNotFoundApiError
//
//  private val UserNotEnabledApiError: ApiError =
//    ApiError("USER_IS_NOT_ENABLED", "The user cannot login because she is not enabled.")
//  end UserNotEnabledApiError
//
//  private val InvalidLoginPasswordApiError: ApiError =
//    ApiError("INVALID_LOGINNAME_PASSWORD", "Invalid loginName/password specified.")
//  end InvalidLoginPasswordApiError
//
//  private val NewPasswordInsufficientApiError: ApiError =
//    ApiError("NEW_PASSWORD_INSUFFICIENT", "[reason1, reason2, ...]")
//  end NewPasswordInsufficientApiError
//
//  private final case class ResetBoUserPasswordInputs(
//      loginName: String,
//      oldPassword: String,
//      newPassword: String,
//  )
//  end ResetBoUserPasswordInputs
//
//  private val resetBoUserPasswordErrorOut: EndpointOutput[ApiError] =
//    oneOf(
//      oneOfVariant(
//        EndPointUtils
//          .statusCodeWithDescription(StatusCode.NotFound)
//          .and(jsonBody[ApiError].example(LoginNameNotFoundApiError)),
//      ),
//      oneOfVariant(
//        EndPointUtils
//          .statusCodeWithDescription(StatusCode.Locked)
//          .and(jsonBody[ApiError].example(UserNotEnabledApiError)),
//      ),
//      oneOfVariant(
//        EndPointUtils
//          .statusCodeWithDescription(StatusCode.Unauthorized)
//          .and(jsonBody[ApiError].example(InvalidLoginPasswordApiError)),
//      ),
//      oneOfVariant(
//        EndPointUtils
//          .statusCodeWithDescription(StatusCode.BadRequest)
//          .and(jsonBody[ApiError].example(NewPasswordInsufficientApiError)),
//      ),
//    )
//  end resetBoUserPasswordErrorOut
//
//  private def mkRequest(resetBoUserPasswordInputs: ResetBoUserPasswordInputs): ResetUserPasswordRequest =
//    ResetUserPasswordRequest(
//      resetBoUserPasswordInputs.loginName,
//      resetBoUserPasswordInputs.oldPassword,
//      resetBoUserPasswordInputs.newPassword,
//    )
//  end mkRequest
//
//  private val loginNameNotFoundF: F[Either[ApiError, Unit]] = async.pure(Left(LoginNameNotFoundApiError))
//
//  private val userNotEnabledF: F[Either[ApiError, Unit]] = async.pure(Left(UserNotEnabledApiError))
//
//  private val invalidLoginPasswordF: F[Either[ApiError, Unit]] = async.pure(Left(InvalidLoginPasswordApiError))
//
//  private def passwordInsufficientF(reasons: NonEmptyVector[String]): F[Either[ApiError, Unit]] =
//    async.pure(Left(ApiError(NewPasswordInsufficientApiError.errorCode, reasons.view.mkString("[\"", "\", \"", "\"]"))))
//  end passwordInsufficientF
//
//  private def failedToUpdateUserRowF(errStr: String): F[Either[ApiError, Unit]] =
//    async.raiseError(AssertionError(errStr))
//  end failedToUpdateUserRowF
//
//  private val successfulPasswordUpdateF: F[Either[ApiError, Unit]] = async.pure(Right(()))
//
//  override val getEntryPoint: ServerEndpoint[Any, F] =
//    endpoint.post
//      .errorOut(resetBoUserPasswordErrorOut)
//      .in("resetBoUserPassword")
//      .in(jsonBody[ResetBoUserPasswordInputs])
//      .out(emptyOutput.description("Successful reset (returns no content)."))
//      .description("Reset old password to new.")
//      .serverLogic(resetBoUserPassword)
//  end getEntryPoint
//
//  private def resetBoUserPassword(resetBoUserPasswordInputs: ResetBoUserPasswordInputs): F[Either[ApiError, Unit]] =
//    jobHandler.jobHandlerNoAuthF[JobResult.ResetUserPasswordResult, ApiError, Unit](
//      mkRequest(resetBoUserPasswordInputs),
//      { case JobResult.ResetUserPasswordResult(res) =>
//        res match {
//          case Left(LoginNameNotFound) => loginNameNotFoundF
//          case Left(UserNotEnabled) => userNotEnabledF
//          case Left(InvalidLoginPassword) => invalidLoginPasswordF
//          case Left(NewPasswordInsufficient(reasons)) => passwordInsufficientF(reasons)
//          case Left(FailedToUpdateUserRow(errStr)) => failedToUpdateUserRowF(errStr)
//          case Right(_) => successfulPasswordUpdateF
//        }
//      },
//    )
//  end resetBoUserPassword
//end ResetBoUserPasswordEp
//
//object ResetBoUserPasswordEp:
//  def create[F[_]: Async](jobHandler: JobHandler[F]): ThalesEntryPoint[F] =
//    ResetBoUserPasswordEp[F](jobHandler)
//  end create
//end ResetBoUserPasswordEp
