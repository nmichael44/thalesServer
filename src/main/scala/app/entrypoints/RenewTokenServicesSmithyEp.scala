package app.entrypoints

import cats.data.Kleisli
import cats.effect.Async

import app.JobSpecs.{JobResult, RenewJwtTokenError}
import app.JobSpecs.JobKind.RenewJwtTokenRequest
import app.JobSpecs.JobResult.RenewJwtTokenResult
import app.ThalesUtils.GenUtils as U
import app.auth.Permissions
import app.auth.Permissions.{CompiledPermissionAlgebra, PermissionAlgebra}
import app.entrypoints.smithy.{RenewJwtTokenOutput, RenewTokenServices}
import app.model.AppModel.AuthenticatedUser

private final class RenewTokenServicesSmithyEp[F[_]: Async as async] private (
    jobHandler: JobHandler[F],
    epErrors: EntryPointErrors[F],
) extends RenewTokenServices[[A] =>> Kleisli[F, AuthenticatedUser, A]]:
  override def renewJwtToken(): Kleisli[F, AuthenticatedUser, RenewJwtTokenOutput] =
    renewJwtTokenProgram
  end renewJwtToken

  private val renewJwtTokenProgram: Kleisli[F, AuthenticatedUser, RenewJwtTokenOutput] =
    def resultToResponse(jobResult: JobResult): F[RenewJwtTokenOutput] =
      jobResult match
        case RenewJwtTokenResult(res) =>
          res.fold(jwtErrorToHttpError.apply, newToken => async.pure(RenewJwtTokenOutput(newToken)))
        case _ => epErrors.internalServerError("RenewJwtToken: Bad pattern match for result.")
    end resultToResponse

    Kleisli { authUser =>
      jobHandler.jobHandlerWithAuth(
        authUser,
        renewJwtTokenPermissionsAlg,
        RenewJwtTokenRequest(authUser),
        resultToResponse,
      )
    }
  end renewJwtTokenProgram

  private val jwtErrorToHttpError: Map[RenewJwtTokenError, F[RenewJwtTokenOutput]] =
    import U.->

    Map(
      RenewJwtTokenError.NoSuchUser            -> epErrors.userNotFound,
      RenewJwtTokenError.UserIsDisabled        -> epErrors.userIsDisabled,
      RenewJwtTokenError.UserMustResetPassword -> epErrors.userMustResetPassword,
      RenewJwtTokenError.RenewalTimeHasExpired -> epErrors.userMustLoginAgainTokenExpired,
    )
  end jwtErrorToHttpError

  private val renewJwtTokenPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permissions.CanRenewJwtToken).compile
  end renewJwtTokenPermissionsAlg
end RenewTokenServicesSmithyEp

object RenewTokenServicesSmithyEp:
  def create[F[_]: Async](
      jobHandler: JobHandler[F],
      epErrors: EntryPointErrors[F],
  ): RenewTokenServices[[A] =>> Kleisli[F, AuthenticatedUser, A]] =
    RenewTokenServicesSmithyEp[F](jobHandler, epErrors)
  end create
end RenewTokenServicesSmithyEp
