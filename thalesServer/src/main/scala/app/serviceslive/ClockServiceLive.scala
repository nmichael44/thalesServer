package app.serviceslive

import cats.Functor
import cats.effect.Clock
import cats.syntax.all.*

import java.time.Instant

import app.services.ClockService

final class ClockServiceLive[F[_]: { Functor, Clock as clock }] extends ClockService[F]:
  val nowInstant: F[Instant] = clock.realTimeInstant

  val nowEpochSeconds: F[Long] = nowInstant.map(_.getEpochSecond)
end ClockServiceLive

object ClockServiceLive:
  def create[F[_]: { Functor, Clock as clock }]: ClockService[F] =
    ClockServiceLive[F]
  end create
end ClockServiceLive
