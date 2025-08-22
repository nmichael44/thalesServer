package app

import cats.effect.Deferred

import app.JobSpecs.{JobKind, JobResult}

final class WorkerJob[F[_]](
    val job: JobKind,
    val deferred: Deferred[F, Either[Throwable, JobResult]],
    val uuid: String,
)
