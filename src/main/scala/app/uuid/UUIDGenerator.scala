package app.uuid

import cats.effect.*
import cats.effect.implicits.*
import cats.effect.std.Queue
import cats.syntax.all.*

import java.util.{SplittableRandom, UUID}
import java.util.random.RandomGenerator
import scala.util.control.NoStackTrace

import app.ThalesUtils.GenUtils as U

final class UUIDGenerator[F[_]: Async] private (queue: Queue[F, RandomGenerator]):
  private val generateUUID: F[UUID] =
    queue.take.flatMap: rng =>
      UUIDGenerator.makeUUID(rng).guarantee(queue.offer(rng))

  val generateUUIDAsString: F[String] = generateUUID.map(_.toString)
end UUIDGenerator

object UUIDGenerator:
  private def makeUUID[F[_]: Async as async](rndSrc: RandomGenerator): F[UUID] =
    async.delay:
      val msb = rndSrc.nextLong()
      val lsb = rndSrc.nextLong()

      makeV4UUID(msb, lsb)
  end makeUUID

  private def makeV4UUID(msb: Long, lsb: Long): UUID = UUID(
    (msb & 0xffffffffffff0fffL) | 0x0000000000004000L,
    (lsb & 0x3fffffffffffffffL) | 0x8000000000000000L,
  )
  end makeV4UUID

  // levelOfParallelism is the number of random number generators available for use.
  // If that there more than levelOfParallelism requests at the same time, the next fiber
  // will wait until one becomes available.
  private def populateQueue[F[_]: Async as async](
      queue: Queue[F, RandomGenerator],
      seedOpt: Option[Long],
      levelOfParallelism: Int,
  ): F[Unit] =
    async.delay:
      val masterRng = seedOpt.fold(new SplittableRandom)(new SplittableRandom(_))
      Vector.fill(levelOfParallelism - 1)(masterRng.split()).appended(masterRng)
    >>= (_.traverseVoid(queue.offer))
  end populateQueue

  private def createImpl[F[_]: Async as async](seedOpt: Option[Long], levelOfParallelism: Int): Resource[F, UUIDGenerator[F]] =
    if levelOfParallelism > 0 then
      for {
        queue <- Queue.bounded[F, RandomGenerator](levelOfParallelism).toResource
        _ <- populateQueue(queue, seedOpt, levelOfParallelism).toResource
      } yield UUIDGenerator[F](queue)
    else
      async
        .raiseError(
          new IllegalArgumentException(s"levelOfParallelism must be positive but got $levelOfParallelism.") with NoStackTrace,
        )
        .toResource
  end createImpl

  def create[F[_]: Async](levelOfParallelism: Int): Resource[F, UUIDGenerator[F]] =
    createImpl(None, levelOfParallelism)
  end create

  def create[F[_]: Async](seed: Long, levelOfParallelism: Int): Resource[F, UUIDGenerator[F]] =
    createImpl(Some(seed), levelOfParallelism)
  end create
end UUIDGenerator
