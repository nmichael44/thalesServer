package app.workerTasks

import app.JobSpecs.{JobKind, JobResult}

trait WorkerTask[F[_]]:
  def work(job: JobKind): F[JobResult]
end WorkerTask
