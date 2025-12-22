package app.serviceslive

import cats.effect.{Async, Ref}
import cats.effect.std.Queue
import cats.implicits.catsSyntaxTuple2Semigroupal

import java.time.Instant

import app.auth.Permissions
import app.services.ServerState
import app.Config.AppConfig.BackendServerConfig
import app.WorkerJob

private final class ServerStateLive[F[_]](
    val jobQueue: Queue[F, WorkerJob[F]],
    val lastAccess: Ref[F, Map[Long, Instant]],
    val permissions: Permissions,
) extends ServerState[F]

object ServerStateLive:
  def create[F[_]: Async as async](backendServer: BackendServerConfig, permissions: Permissions): F[ServerState[F]] =
    (
      Queue.bounded[F, WorkerJob[F]](backendServer.getBoundedQueueCapacity),
      Ref.of[F, Map[Long, Instant]](Map.empty),
    ).mapN((jobQueue, lastAccess) => ServerStateLive[F](jobQueue, lastAccess, permissions))
  end create
end ServerStateLive
