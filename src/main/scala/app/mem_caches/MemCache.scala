package app.mem_caches

import cats.Monad
import cats.effect.Temporal
import cats.effect.implicits.*
import cats.effect.kernel.{Ref, Resource}
import cats.implicits.*
import cats.syntax.all.*

import java.time.Instant
import scala.collection.immutable.{TreeMap, TreeSet}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.jdk.DurationConverters.ScalaDurationOps
import scala.util.control.NoStackTrace
import scala.util.hashing

import app.ThalesUtils.GenUtils as U
import app.ThalesUtils.TimeUtils
import app.mem_caches.MemCache.{CacheElem, CacheState, Shard}
import org.typelevel.log4cats.Logger

final class MemCache[F[_]: { Temporal as temporal, Logger }, K: Ordering, V] private (
    memCacheName: String,
    shards: Vector[Shard[F, K, V]],
):
  private val numShards: Int = shards.size

  private def shardFor(k: K): Shard[F, K, V] =
    val idx = Math.floorMod(hashing.byteswap32(k.hashCode), numShards)
    shards(idx)
  end shardFor

  def get(k: K): F[Option[V]] =
    val shard = shardFor(k)
    temporal.realTimeInstant.flatMap: now =>
      shard.r.modify { case cacheState0 @ CacheState(m0, s0, lruMap0, seqCounter0) =>
        m0.get(k).fold((cacheState0, None)) { case MemCache.CacheElem(v, expiryOpt, seqCount) =>
          expiryOpt match
            // Item has an expiry, AND it is currently expired.
            case Some(expiry) if MemCache.hasExpired(expiry, now) =>
              val m1 = m0 - k
              val s1 = s0 - ((expiry, k))
              val lruMap1 = lruMap0 - seqCount
              (CacheState(m1, s1, lruMap1, seqCounter0), None)
            // This case covers two cases:
            //   1. Item has an expiry, AND it is NOT currently expired.
            //   2. Item has NO expiry (expiryOpt is None).
            case _ =>
              val m1 = m0.updated(k, CacheElem(v, expiryOpt, seqCounter0))
              val s1 = s0
              val lruMap1 = (lruMap0 - seqCount).updated(seqCounter0, k)
              val seqCounter1 = seqCounter0 + 1
              (CacheState(m1, s1, lruMap1, seqCounter1), Some(v))
        }
      }
  end get

  def put(k: K, v: V): F[Unit] =
    putAux(k, v, None)
  end put

  def put(k: K, v: V, duration: FiniteDuration): F[Unit] =
    put(k, v, duration.toJava)
  end put

  def put(k: K, v: V, duration: java.time.Duration): F[Unit] =
    putAux(k, v, Some(duration))
  end put

  private def evictIfNecessary(
      shardCapacity: Int,
      m0: TreeMap[K, CacheElem[V]],
      s0: TreeSet[(Instant, K)],
      lru0: TreeMap[Long, K],
  ) =
    if m0.size == shardCapacity
    then
      val minK = lru0.head._2
      val CacheElem(_, expiryOpt, seqCounter) = m0(minK)
      val m1 = m0 - minK
      val s1 = expiryOpt.fold(s0)(expiry => s0 - ((expiry, minK)))
      val lru1 = lru0 - seqCounter

      (m1, s1, lru1)
    else (m0, s0, lru0)
  end evictIfNecessary

  private def isDurationNonPositive(durationOpt: Option[java.time.Duration]): Boolean =
    durationOpt.exists(_.compareTo(java.time.Duration.ZERO) <= 0)
  end isDurationNonPositive

  private val durationNonPositiveError: F[Unit] =
    MemCache.fail(
      new IllegalArgumentException(s"MemCache '$memCacheName': Duration must be greater than zero.")
        with NoStackTrace,
    )
  end durationNonPositiveError

  private def putAux(k: K, v: V, durationOpt: Option[java.time.Duration]): F[Unit] =
    if isDurationNonPositive(durationOpt) then durationNonPositiveError
    else
      val shard = shardFor(k)
      durationOpt.traverse(d => temporal.realTimeInstant.map(_.plus(d))).flatMap: newExpiryOpt =>
        shard.r.update:
          case CacheState(m, s, lruMap, seqCounter0) =>
            val existingEntryOpt: Option[CacheElem[V]] = m.get(k)

            val (m0, s0, lruMap0) =
              if existingEntryOpt.isDefined then (m, s, lruMap)
              else evictIfNecessary(shard.capacity, m, s, lruMap)

            val m1 = m0.updated(k, CacheElem(v, newExpiryOpt, seqCounter0))

            val s1Aux = existingEntryOpt.flatMap(_.expiryOpt).fold(s0)(currExpiry => s0 - ((currExpiry, k)))
            val s1 = newExpiryOpt.fold(s1Aux)(newExpiry => s1Aux + ((newExpiry, k)))

            val lruMap1 = existingEntryOpt
              .fold(lruMap0) { case CacheElem(_, _, seqCount) => lruMap0 - seqCount }
              .updated(seqCounter0, k)
            val seqCounter1 = seqCounter0 + 1

            CacheState(m1, s1, lruMap1, seqCounter1)
  end putAux

  // This function is to be used for testing only.
  def getInternalCacheState: F[(TreeMap[K, CacheElem[V]], TreeSet[(Instant, K)], TreeMap[Long, K], Long)] =
    shards.traverse(_.r.get).map: states =>
      val mainMap = states.foldLeft(TreeMap.empty[K, CacheElem[V]])(_ ++ _.mainMap)
      val expirySet = states.foldLeft(TreeSet.empty[(Instant, K)])(_ ++ _.expirySet)

      val lruMap = states.zipWithIndex.foldLeft(TreeMap.empty[Long, K]):
        case (accLru, (cs, shardIdx)) =>
          cs.lruMap.foldLeft(accLru):
            case (acc, (seq, k)) => acc.updated(seq * shards.size + shardIdx, k)

      val totalSeq = states.foldLeft(0L)(_ + _.seqCounter)

      (mainMap, expirySet, lruMap, totalSeq)
  end getInternalCacheState
end MemCache

object MemCache:
  def create[F[_]: { Temporal as temporal, Logger }, K: Ordering, V](
      memCacheName: String,
      capacity: Int,
      numShards: Int,
      cleanupDuration: FiniteDuration,
  ): Resource[F, MemCache[F, K, V]] =
    if capacity <= 0 then failWithIllegalArg(s"Capacity must be greater than 0. Instead it was '$capacity'.")
    else if numShards <= 0 then failWithIllegalArg(s"Number of shards must be greater than 0. Instead it was '$numShards'.")
    else if numShards > capacity then failWithIllegalArg(s"Number of shards ($numShards) cannot be greater than capacity ($capacity).")
    else createImpl(memCacheName, capacity, numShards, cleanupDuration)
  end create

  private final case class Shard[F[_], K, V](
      capacity: Int,
      r: Ref[F, CacheState[K, V]],
  )

  private final case class CacheState[K: Ordering, V](
      mainMap: TreeMap[K, CacheElem[V]],
      expirySet: TreeSet[(Instant, K)],
      lruMap: TreeMap[Long, K],
      seqCounter: Long,
  )

  // This is not private because we use it in unit tests.
  final case class CacheElem[V](
      v: V,
      expiryOpt: Option[Instant],
      seqCount: Long,
  )

  private val MinimumCleanupDuration: FiniteDuration = 30.seconds

  private def ensureMinCleanupDuration[F[_]: Temporal as temporal](cleanupDuration: FiniteDuration): F[Unit] =
    temporal.whenA(cleanupDuration < MinimumCleanupDuration):
      fail(
        new IllegalArgumentException(
          s"MemCache: Cleanup duration cannot be less than ${TimeUtils.durationToString(MinimumCleanupDuration)}.",
        ) with NoStackTrace,
      )
  end ensureMinCleanupDuration

  private def computeShardCapacities(capacity: Int, numShards: Int): Vector[Int] =
    val baseCapacity = capacity / numShards
    val remainder = capacity % numShards
    Vector.tabulate(numShards): idx =>
      if idx < remainder then baseCapacity + 1 else baseCapacity
  end computeShardCapacities

  private def createImpl[F[_]: { Temporal as temporal, Logger }, K: Ordering, V](
      memCacheName: String,
      capacity: Int,
      numShards: Int,
      cleanupDuration: FiniteDuration,
  ): Resource[F, MemCache[F, K, V]] =
    val sleepAfterErrorAction: F[Unit] = temporal.sleep(200.milliseconds)
    val shardCapacities = computeShardCapacities(capacity, numShards)

    val createEmptyStateRef: F[Ref[F, CacheState[K, V]]] =
      Ref.of(CacheState(TreeMap.empty[K, CacheElem[V]], TreeSet.empty[(Instant, K)], TreeMap.empty[Long, K], 0L))

    val createShard: Int => F[Shard[F, K, V]] =
      shardCap => createEmptyStateRef.map(r => Shard(shardCap, r))

    for
      _ <- Resource.eval(ensureMinCleanupDuration(cleanupDuration))
      shards <- Resource.eval(shardCapacities.traverse(createShard))
      _ <- startCleanupWorker(memCacheName, shards, cleanupDuration, sleepAfterErrorAction)
    yield MemCache(memCacheName, shards)
  end createImpl

  private def hasExpired(expiry: Instant, now: Instant): Boolean =
    !now.isBefore(expiry)
  end hasExpired

  private def getSize[F[_]: Monad, K, V](shards: Vector[Shard[F, K, V]]): F[(Int, Int)] =
    shards.traverse(_.r.get).map: states =>
      states.foldLeft((0, 0)):
        case ((mAcc, sAcc), cs) => (mAcc + cs.mainMap.size, sAcc + cs.expirySet.size)
  end getSize

  private def reportSize[F[_]: { Monad, Logger }, K, V](
      memCacheName: String,
      shards: Vector[Shard[F, K, V]],
      when: String,
  ): F[Unit] =
    getSize(shards).flatMap: (mSiz, tSiz) =>
      U.logi(s"Sizes of cache '$memCacheName' $when worker touched it: ($mSiz, $tSiz).")
  end reportSize

  private inline val CleanupWorkerName = "CleanupWorker"

  private inline val CleanupWorkerErrorMsgFormat =
    "MemCache '%s': CleanupWorker encountered an error during a cycle.  Worker will continue to run."

  private inline val CleanupWorkerRemovedMsgFormat =
    "MemCache '%s': Removed %d expired cache entries."

  private def logCleanupError[F[_]: Logger](
      memCacheName: String,
      e: Throwable,
  ): F[Unit] =
    U.loge(e, CleanupWorkerName, CleanupWorkerErrorMsgFormat.format(memCacheName))
  end logCleanupError

  private def logCleanupSuccess[F[_]: { Temporal as temporal, Logger }](
      memCacheName: String,
      removedCount: Int,
  ): F[Unit] =
    temporal.whenA(removedCount > 0):
      U.logi(CleanupWorkerName, CleanupWorkerRemovedMsgFormat.format(memCacheName, removedCount))
  end logCleanupSuccess

  private def cleanupShard[F[_], K: Ordering, V](
      shard: Shard[F, K, V],
      now: Instant,
  ): F[Int] =
    shard.r.modify:
      case currentState @ CacheState(m0, s0, lruMap0, seqCounter0) =>
        val expiredEntries = s0.view.takeWhile((expiry, _) => hasExpired(expiry, now)).toVector
        if expiredEntries.isEmpty then (currentState, 0)
        else
          val expiredKeys = expiredEntries.view.map(_._2).toVector
          val expiredSeqs = expiredKeys.view.map(m0(_).seqCount)
  
          val m1 = m0 -- expiredKeys
          val s1 = s0 -- expiredEntries
          val lruMap1 = lruMap0 -- expiredSeqs
          val seqCounter1 = seqCounter0
  
          (CacheState(m1, s1, lruMap1, seqCounter1), expiredKeys.size)
  end cleanupShard

  private def cleanupWorker[F[_]: { Temporal as temporal, Logger }, K: Ordering, V](
      memCacheName: String,
      shards: Vector[Shard[F, K, V]],
      cleanupInterval: FiniteDuration,
      sleepAfterErrorAction: F[Unit],
  ): Resource[F, Unit] =
    val sleepForCleanupInterval = temporal.sleep(cleanupInterval)

    (for
      _ <- sleepForCleanupInterval
      now <- temporal.realTimeInstant
      removedCounts <- shards.traverse(shard => cleanupShard(shard, now))
      _ <- logCleanupSuccess(memCacheName, removedCounts.sum)
    yield ())
      .handleErrorWith(e => logCleanupError(memCacheName, e) *> sleepAfterErrorAction)
      .foreverM
      .background
      .void
  end cleanupWorker

  private def startCleanupWorker[F[_]: { Temporal, Logger }, K: Ordering, V](
      memCacheName: String,
      shards: Vector[Shard[F, K, V]],
      cleanupInterval: FiniteDuration,
      sleepAfterErrorAction: F[Unit],
  ): Resource[F, Unit] =
    for
      _ <- Resource.eval(U.logi(s"Starting memCache cleanup worker for '$memCacheName'..."))
      _ <- cleanupWorker(memCacheName, shards, cleanupInterval, sleepAfterErrorAction)
      _ <- Resource.eval(U.logi(s"Cleanup worker started for '$memCacheName'."))
    yield ()
  end startCleanupWorker

  private def fail[F[_]: Temporal as temporal, A](e: Throwable): F[A] =
    temporal.raiseError[A](e)
  end fail

  private def failWithIllegalArg[F[_]: Temporal, A](msg: String): Resource[F, A] =
    Resource.eval(fail(IllegalArgumentException(msg)))
  end failWithIllegalArg
end MemCache
