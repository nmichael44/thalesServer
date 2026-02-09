package app

import cats.effect.{IO, IOLocal, Resource}
import cats.syntax.functor.*

trait TraceIdScope[F[_], A]:
  def get: F[A]
  def scope(a: A): Resource[F, Unit]
end TraceIdScope

object TraceIdScope:
  def fromIOLocal[A](a: A): IO[TraceIdScope[IO, A]] =
    IOLocal(a).map { local =>
      new TraceIdScope[IO, A] {
        def get: IO[A] = local.get
        def scope(a: A): Resource[IO, Unit] =
          Resource.make(local.getAndSet(a))(previous => local.set(previous)).void
      }
    }
  end fromIOLocal
end TraceIdScope
