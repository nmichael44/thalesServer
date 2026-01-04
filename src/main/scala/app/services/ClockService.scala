package app.services

import java.time.Instant

trait ClockService[F[_]]:
  def nowInstant: F[Instant]

  def nowEpochSeconds: F[Long]
end ClockService
