package app.serviceslive

import cats.effect.{Async, Ref}
import cats.effect.std.Queue
import cats.implicits.catsSyntaxTuple2Semigroupal

import java.time.Instant

import app.services.ServerState
import app.Config.AppConfig.BackendServerConfig
import app.WorkerJob

private final case class ServerStateLive[F[_]](jobQueue: Queue[F, WorkerJob[F]], lastAccess: Ref[F, Map[Long, Instant]])
    extends ServerState[F]

object ServerStateLive:
  def create[F[_]: Async as async](backendServer: BackendServerConfig): F[ServerState[F]] =
    (
      Queue.bounded[F, WorkerJob[F]](backendServer.getBoundedQueueCapacity),
      Ref.of[F, Map[Long, Instant]](Map.empty),
    ).mapN((jobQueue, lastAccess) => ServerStateLive[F](jobQueue, lastAccess))
  end create
end ServerStateLive
