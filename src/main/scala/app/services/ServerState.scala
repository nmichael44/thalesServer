package app.services

import cats.effect.std.Queue
import cats.effect.Ref

import java.time.Instant

import app.WorkerJob

trait ServerState[F[_]]:
  val jobQueue: Queue[F, WorkerJob[F]]
  val lastAccess: Ref[F, Map[Long, Instant]]
end ServerState
