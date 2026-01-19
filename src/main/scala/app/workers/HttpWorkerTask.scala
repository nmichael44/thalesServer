package app.workers

import app.JobSpecs.{JobKind, JobResult}

trait HttpWorkerTask[F[_]] {
  def work(job: JobKind): F[JobResult]
}
