package app.workerTasks

import app.JobSpecs.{JobKind, JobResult}

trait WorkerTask[F[_], J <: JobKind]:
  def work(job: J): F[JobResult]
end WorkerTask
