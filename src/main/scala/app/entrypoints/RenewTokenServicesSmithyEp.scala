package app.entrypoints

import app.JobSpecs.JobKind.RenewJwtTokenRequest
import app.JobSpecs.{JobResult, RenewJwtTokenError}
import app.JobSpecs.JobResult.RenewJwtTokenResult
import app.auth.Permissions.{CompiledPermissionAlgebra, PermissionAlgebra}
import cats.data.Kleisli
import cats.effect.Async
import app.entrypoints.smithy.{RenewJwtTokenOutput, RenewTokenServices}
import app.model.AppModel.AuthenticatedUser
import app.auth.Permissions

private final class RenewTokenServicesSmithyEp[F[_]: Async as async] private (
    jobHandler: JobHandler[F],
    epErrors: EntryPointErrors[F],
) extends RenewTokenServices[[A] =>> Kleisli[F, AuthenticatedUser, A]]:
  override def renewJwtToken(): Kleisli[F, AuthenticatedUser, RenewJwtTokenOutput] =
    def resultToResponse(jobResult: JobResult): F[RenewJwtTokenOutput] =
      jobResult match {
        case RenewJwtTokenResult(res) =>
          res.fold(
            {
              case RenewJwtTokenError.NoSuchUser => ???
              case RenewJwtTokenError.UserIsDisabled => ???
              case RenewJwtTokenError.UserMustResetPassword => ???
              case RenewJwtTokenError.RenewalTimeHasExpired => ???
            },
            newToken => async.pure(RenewJwtTokenOutput(newToken))
          )
        case _ => epErrors.internalServerErrorF("RenewJwtToken: Bad pattern match for result.")
      }
    end resultToResponse

    Kleisli { authUser =>
      jobHandler.jobHandlerWithAuth2(
        authUser,
        RenewJwtTokenPermissionsAlg,
        RenewJwtTokenRequest(authUser),
        resultToResponse,
      )
    }
  end renewJwtToken

  private val x = Map(
    RenewJwtTokenError.NoSuchUser -> ???,
    RenewJwtTokenError.UserIsDisabled -> ???,
    RenewJwtTokenError.UserMustResetPassword -> ???,
    RenewJwtTokenError.RenewalTimeHasExpired -> ???,
  )
  
  private val RenewJwtTokenPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permissions.CanRenewJwtToken).compile
  end RenewJwtTokenPermissionsAlg
end RenewTokenServicesSmithyEp

object RenewTokenServicesSmithyEp:
  def create[F[_]: Async](
      jobHandler: JobHandler[F],
      epErrors: EntryPointErrors[F],
  ): RenewTokenServices[[A] =>> Kleisli[F, AuthenticatedUser, A]] =
    RenewTokenServicesSmithyEp[F](jobHandler, epErrors)
  end create
end RenewTokenServicesSmithyEp
