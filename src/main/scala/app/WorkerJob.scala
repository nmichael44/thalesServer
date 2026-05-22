package app

import cats.effect.Deferred

import scala.concurrent.duration.FiniteDuration

import app.JobSpecs.{JobKind, JobResult}

final class WorkerJob[F[_]](
    val job: JobKind,
    val deferred: Deferred[F, Either[Throwable, JobResult]],
    val uuid: String,
    val delayOpt: Option[FiniteDuration],
)
