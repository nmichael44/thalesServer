package app.uuid

import cats.effect.*
import cats.effect.{Async, Resource}
import cats.effect.implicits.*
import cats.effect.std.Queue
import cats.syntax.all.*

import java.util.{SplittableRandom, UUID}
import java.util.random.RandomGenerator

import app.uuid.UUIDGenerator.RandomnessSource

final class UUIDGenerator[F[_]: Async] private (queue: Queue[F, RandomnessSource[F]]):
  private val withItemFromQueue: Resource[F, RandomnessSource[F]] = Resource.make(queue.take)(queue.offer)

  private val generateUUID: F[UUID] = withItemFromQueue.use(UUIDGenerator.makeUUID)

  val generateUUIDAsString: F[String] = generateUUID.map(_.toString)
end UUIDGenerator

object UUIDGenerator:
  private final class RandomnessSource[F[_]: Async as async](rng: RandomGenerator):
    def nextLong(): F[Long] = async.delay(rng.nextLong())
  end RandomnessSource

  private def makeUUID[F[_]: Async](rndSrc: RandomnessSource[F]): F[UUID] =
    for {
      msb <- rndSrc.nextLong()
      lsb <- rndSrc.nextLong()
    } yield makeV4UUID(msb, lsb)
  end makeUUID

  private def makeV4UUID(msb: Long, lsb: Long): UUID = UUID(
    (msb & 0xffffffffffff0fffL) | 0x0000000000040000L,
    (lsb & 0x3fffffffffffffffL) | 0x8000000000000000L,
  )
  end makeV4UUID

  // The number of random number generators available for use.  If that there more than
  // LevelOfParallelism requests at the same time, the next fiber will wait until one
  // becomes available.
  inline private val LevelOfParallelism = 8

  private def getSeed[F[_]: Async as async](seedOpt: Option[Long]): F[Long] =
    seedOpt.fold(async.monotonic.map(_.toNanos))(async.pure)
  end getSeed

  private def populateQueue[F[_]: Async as async](queue: Queue[F, RandomnessSource[F]], seedOpt: Option[Long]): F[Unit] =
    for {
      masterRng <- getSeed(seedOpt).map(SplittableRandom(_))
      _ <- queue.offer(RandomnessSource[F](masterRng))
      _ <- async.replicateA_(LevelOfParallelism - 1, async.defer(queue.offer(RandomnessSource[F](masterRng.split()))))
    } yield ()
  end populateQueue

  private def createImpl[F[_]: Async as async](seedOpt: Option[Long]): Resource[F, UUIDGenerator[F]] =
    Queue
      .bounded[F, RandomnessSource[F]](LevelOfParallelism)
      .flatMap(queue => populateQueue(queue, seedOpt).as(UUIDGenerator[F](queue)))
      .toResource
  end createImpl

  def create[F[_]: Async]: Resource[F, UUIDGenerator[F]] =
    createImpl(None)
  end create

  def create[F[_]: Async](seed: Long): Resource[F, UUIDGenerator[F]] =
    createImpl(Some(seed))
  end create
end UUIDGenerator
