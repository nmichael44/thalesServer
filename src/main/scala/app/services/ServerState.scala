package app.services

import cats.effect.Ref
import cats.effect.std.Queue

import java.time.Instant

import app.WorkerJob
import app.entrypoints.smithy.UserId

trait ServerState[F[_]]:
  val jobQueue: Queue[F, WorkerJob[F]]
  val lastAccess: Ref[F, Map[UserId, Instant]]
end ServerState
