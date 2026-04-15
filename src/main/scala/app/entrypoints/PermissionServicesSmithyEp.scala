package app.entrypoints

import cats.data.Kleisli
import cats.effect.Async

import app.JobSpecs.JobKind.FetchAllPermissionsRequest
import app.JobSpecs.JobResult
import app.JobSpecs.JobResult.FetchAllPermissionsResult
import app.ThalesUtils.GenUtils as U
import app.auth.Permissions
import app.auth.Permissions.{CompiledPermissionAlgebra, PermissionAlgebra}
import app.entrypoints.EntryPointUtils as EPU
import app.entrypoints.smithy.{FetchAllPermissionsOutput, PermissionServices}
import app.model.AppModel.AuthenticatedUser

private final class PermissionServicesSmithyEp[F[_]: Async as async] private (
    jobHandler: JobHandler[F],
    epErrors: EntryPointErrors[F],
) extends PermissionServices[[A] =>> Kleisli[F, AuthenticatedUser, A]]:
  override def fetchAllPermissions(): Kleisli[F, AuthenticatedUser, FetchAllPermissionsOutput] =
    fetchAllPermissionsProgram
  end fetchAllPermissions

  private val fetchAllPermissionsProgram: Kleisli[F, AuthenticatedUser, FetchAllPermissionsOutput] =
    def resultToResponse(jobResult: JobResult): F[FetchAllPermissionsOutput] =
      jobResult match
        case FetchAllPermissionsResult(res) => async.pure(FetchAllPermissionsOutput(res.map(U.mapFirst(_.value.toString))))
        case _ => EPU.internalServerError(epErrors, "FetchAllPermissions")
    end resultToResponse

    Kleisli: authUser =>
      jobHandler.jobHandlerWithAuth(
        authUser,
        FetchAllPermissionsPermissionsAlg,
        FetchAllPermissionsRequest,
        resultToResponse,
      )
  end fetchAllPermissionsProgram

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
