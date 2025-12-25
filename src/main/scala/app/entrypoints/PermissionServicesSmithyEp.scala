package app.entrypoints

import cats.data.Kleisli
import cats.effect.Async

import app.JobSpecs.JobKind.FetchAllPermissionsRequest
import app.JobSpecs.JobResult
import app.JobSpecs.JobResult.FetchAllPermissionsResult
import app.auth.Permissions
import app.auth.Permissions.{CompiledPermissionAlgebra, PermissionAlgebra}
import app.entrypoints.smithy.{FetchAllPermissionsOutput, PermissionServices}
import app.model.AppModel.AuthenticatedUser

private final class PermissionServicesSmithyEp[F[_]: Async as async] private (
    jobHandler: JobHandler[F],
    epErrors: EntryPointErrors[F],
) extends PermissionServices[[A] =>> Kleisli[F, AuthenticatedUser, A]]:
  override def fetchAllPermissions(): Kleisli[F, AuthenticatedUser, FetchAllPermissionsOutput] =
    def resultToResponse(jobResult: JobResult): F[FetchAllPermissionsOutput] =
      jobResult match {
        case FetchAllPermissionsResult(res) => async.pure(FetchAllPermissionsOutput(res))
        case _ => epErrors.internalServerErrorF("FetchAllPermissions: Bad pattern match for result.")
      }
    end resultToResponse

    Kleisli { authUser =>
      jobHandler.jobHandlerWithAuth2(
        authUser,
        FetchAllPermissionsPermissionsAlg,
        FetchAllPermissionsRequest,
        resultToResponse,
      )
    }
  end fetchAllPermissions

  private val FetchAllPermissionsPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permissions.CanSeeAllPermissions).compile
  end FetchAllPermissionsPermissionsAlg

  private val successResult: F[Unit] = async.pure(())
end PermissionServicesSmithyEp

object PermissionServicesSmithyEp:
  def create[F[_]: Async](
      jobHandler: JobHandler[F],
      epErrors: EntryPointErrors[F],
  ): PermissionServices[[A] =>> Kleisli[F, AuthenticatedUser, A]] =
    PermissionServicesSmithyEp[F](jobHandler, epErrors)
  end create
end PermissionServicesSmithyEp
