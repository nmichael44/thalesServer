package app.mem_caches

import cats.{FlatMap, Functor}
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

import app.ThalesUtils.ExtensionMethodUtils.*
import app.ThalesUtils.GenUtils as U
import app.ThalesUtils.TimeUtils
import app.mem_caches.MemCache.{CacheElem, CacheState}
import org.typelevel.log4cats.Logger

final class MemCache[F[_]: { Temporal as temporal, Logger }, K: Ordering, V] private (
    memCacheName: String,
    capacity: Int,
    r: Ref[F, CacheState[K, V]],
):
  def get(k: K): F[Option[V]] =
    r.modify { case cacheState0 @ CacheState(m0, s0, lruMap0, seqCounter0, now) =>
      m0.get(k).fold((cacheState0, None)) { case MemCache.CacheElem(v, expiryOpt, seqCount) =>
        expiryOpt match
          // Item has an expiry, AND it is currently expired.
          case Some(expiry) if MemCache.hasExpired(expiry, now) =>
            val m1 = m0 - k
            val s1 = s0 - ((expiry, k))
            val lruMap1 = lruMap0 - seqCount
            (CacheState(m1, s1, lruMap1, seqCounter0, now), None)
          // This case covers two cases:
          //   1. Item has an expiry, AND it is NOT currently expired.
          //   2. Item has NO expiry (expiryOpt is None).
          case _ =>
            val m1 = m0.updated(k, CacheElem(v, expiryOpt, seqCounter0))
            val s1 = s0
            val lruMap1 = (lruMap0 - seqCount).updated(seqCounter0, k)
            val seqCounter1 = seqCounter0 + 1
            (CacheState(m1, s1, lruMap1, seqCounter1, now), Some(v))
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

  private def evictIfNecessary(m0: TreeMap[K, CacheElem[V]], s0: TreeSet[(Instant, K)], lru0: TreeMap[Long, K]) =
    if m0.size == capacity
    then
      val minK = lru0.head._2
      val CacheElem(_, expiryOpt, seqCounter) = m0(minK)
      val m1 = m0 - minK
      val s1 = expiryOpt.fold(s0)(expiry => s0 - ((expiry, minK)))
      val lru1 = lru0 - seqCounter

      (m1, s1, lru1)
    else (m0, s0, lru0)
  end evictIfNecessary

  private def isDurationTooShort(durationOpt: Option[java.time.Duration]): Boolean =
    durationOpt.exists(_.compareTo(MemCache.ItemMinimumAllowedDuration) < 0)
  end isDurationTooShort

  private val durationTooShortError: F[Unit] =
    temporal.raiseError(
      new IllegalArgumentException(
        s"MemCache '$memCacheName': Duration cannot be less than 10 seconds due to cache clock resolution constraints."
      ) with NoStackTrace
    )

  private def putAux(k: K, v: V, durationOpt: Option[java.time.Duration]): F[Unit] =
    if isDurationTooShort(durationOpt) then durationTooShortError
    else
      r.update:
        case CacheState(m, s, lruMap, seqCounter0, now) =>
          val existingEntryOpt: Option[CacheElem[V]] = m.get(k)

          val (m0, s0, lruMap0) =
            if existingEntryOpt.isDefined then (m, s, lruMap) else evictIfNecessary(m, s, lruMap)

          val newExpiryOpt: Option[Instant] = durationOpt.map(now.plus)
          val m1 = m0.updated(k, CacheElem(v, newExpiryOpt, seqCounter0))

          val s1Aux = existingEntryOpt.flatMap(_.expiryOpt).fold(s0)(currExpiry => s0 - ((currExpiry, k)))
          val s1 = newExpiryOpt.fold(s1Aux)(newExpiry => s1Aux + ((newExpiry, k)))
          val lruMap1 = existingEntryOpt
            .fold(lruMap0) { case CacheElem(_, _, seqCount) => lruMap0 - seqCount }
            .updated(seqCounter0, k)
          val seqCounter1 = seqCounter0 + 1

          CacheState(m1, s1, lruMap1, seqCounter1, now)
  end putAux

  // This function is to be used for testing only.
  def getInternalCacheState: F[(TreeMap[K, CacheElem[V]], TreeSet[(Instant, K)], TreeMap[Long, K], Long, Instant)] =
    r.get.map:
      case CacheState(m, s, lruMap, seqCounter, now) => (m, s, lruMap, seqCounter, now)
  end getInternalCacheState
end MemCache

object MemCache:
  def create[F[_]: { Temporal as temporal, Logger }, K: Ordering, V](
      memCacheName: String,
      capacity: Int,
      cleanupDuration: FiniteDuration,
      timeTickDuration: FiniteDuration,
  ): Resource[F, MemCache[F, K, V]] =
    if capacity <= 0 then Resource.eval(temporal.raiseError(IllegalArgumentException(s"Capacity must be greater than 0. Instead it was '$capacity'.")))
    else createImpl(memCacheName, capacity, cleanupDuration, timeTickDuration)
  end create

  private final case class CacheState[K: Ordering, V](
      mainMap: TreeMap[K, CacheElem[V]],
      expirySet: TreeSet[(Instant, K)],
      lruMap: TreeMap[Long, K],
      seqCounter: Long,
      cachedNow: Instant,
  )

  // This is not private because we use it in unit tests.
  final case class CacheElem[V](
      v: V,
      expiryOpt: Option[Instant],
      seqCount: Long,
  )

  private val TimeTickWorkerName: String = "TimeTickWorker"

  private val ItemMinimumAllowedDuration: java.time.Duration = 10.seconds.toJava

  private val MinimumCleanupDuration: FiniteDuration = 30.seconds

  private val MinimumTimeTickDuration: FiniteDuration = 5.seconds

  // After how many updates with the approximate increment (coming from cats effect's sleep + scheduler)
  // do we correct the actual time.
  // Setting this to 0, results in using only true time.
  private val UpdateNowWithTrueTimeAfterNUpdates = 32

  private def ensureMinCleanupDuration[F[_]: Temporal as temporal](cleanupDuration: FiniteDuration): F[Unit] =
    (cleanupDuration < MinimumCleanupDuration).whenA(
      temporal.raiseError(
        new IllegalArgumentException(
          s"MemCache: Cleanup duration cannot be less than ${TimeUtils.durationToString(MinimumCleanupDuration)}.",
        ) with NoStackTrace,
      ),
    )
  end ensureMinCleanupDuration

  private def ensureMinTimeTickDuration[F[_]: Temporal as temporal](timeTickDuration: FiniteDuration): F[Unit] =
    (timeTickDuration < MinimumTimeTickDuration).whenA(
      temporal.raiseError(
        new IllegalArgumentException(
          s"MemCache: TimeTick duration cannot be less than ${TimeUtils.durationToString(MinimumTimeTickDuration)}.",
        ) with NoStackTrace,
      ),
    )
  end ensureMinTimeTickDuration

  private def validateDurations[F[_]: Temporal](
      cleanupDuration: FiniteDuration,
      timeTickDuration: FiniteDuration,
  ): F[Unit] =
    ensureMinCleanupDuration(cleanupDuration) *> ensureMinTimeTickDuration(timeTickDuration)
  end validateDurations

  private def createImpl[F[_]: { Temporal as temporal, Logger }, K: Ordering, V](
      memCacheName: String,
      capacity: Int,
      cleanupDuration: FiniteDuration,
      timeTickDuration: FiniteDuration,
  ): Resource[F, MemCache[F, K, V]] =
    val sleepAfterErrorAction: F[Unit] = temporal.sleep(200.milliseconds)

    for
      _ <- Resource.eval(validateDurations(cleanupDuration, timeTickDuration))
      now <- Resource.eval(temporal.realTimeInstant)
      r <- Resource.eval(
        Ref.of(
          CacheState(TreeMap.empty[K, CacheElem[V]], TreeSet.empty[(Instant, K)], TreeMap.empty[Long, K], 0L, now),
        ),
      )
      _ <- startCleanupWorker(memCacheName, r, cleanupDuration, sleepAfterErrorAction)
      _ <- startTimeTickingWorker(memCacheName, r, timeTickDuration, sleepAfterErrorAction)
    yield MemCache(memCacheName, capacity, r)
  end createImpl

  private def hasExpired(expiry: Instant, now: Instant): Boolean =
    !now.isBefore(expiry)
  end hasExpired

  private def getSize[F[_]: Functor, K, V](r: Ref[F, CacheState[K, V]]): F[(Int, Int)] =
    r.get.map(cs => (cs.mainMap.size, cs.expirySet.size))
  end getSize

  private def reportSize[F[_]: { FlatMap, Logger }, K, V](
      memCacheName: String,
      r: Ref[F, CacheState[K, V]],
      when: String,
  ): F[Unit] =
    getSize(r).flatMap: (mSiz, tSiz) =>
      U.logi(s"Sizes of cache '$memCacheName' $when worker touched it: ($mSiz, $tSiz).")
  end reportSize

  private val CleanupWorkerName: String = "CleanupWorker"

  private final val CleanupWorkerErrorMsgFormat: String =
    "MemCache '%s': CleanupWorker encountered an error during a cycle.  Worker will continue to run."

  private final val CleanupWorkerRemovedMsgFormat: String =
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
    temporal.whenA(removedCount > 0) {
      U.logi(CleanupWorkerName, CleanupWorkerRemovedMsgFormat.format(memCacheName, removedCount))
    }
  end logCleanupSuccess

  private def cleanupWorker[F[_]: { Temporal as temporal, Logger }, K: Ordering, V](
      memCacheName: String,
      r: Ref[F, CacheState[K, V]],
      cleanupInterval: FiniteDuration,
      sleepAfterErrorAction: F[Unit],
  ): Resource[F, Unit] =
    val sleepForCleanupInterval = temporal.sleep(cleanupInterval)

    (for
      _ <- sleepForCleanupInterval
      removedCount <- r.modify { case currentState @ CacheState(m0, s0, lruMap0, seqCounter0, now) =>
        val expiredEntries = s0.view.takeWhile((expiry, _) => hasExpired(expiry, now)).toVector
        if expiredEntries.isEmpty then (currentState, 0)
        else
          val expiredKeys = expiredEntries.view.map(_._2).toVector
          val expiredSeqs = expiredKeys.view.map(m0(_).seqCount)

          val m1 = m0 -- expiredKeys
          val s1 = s0 -- expiredEntries
          val lruMap1 = lruMap0 -- expiredSeqs
          val seqCounter1 = seqCounter0

          (CacheState(m1, s1, lruMap1, seqCounter1, now), expiredKeys.size)
      }
      _ <- logCleanupSuccess(memCacheName, removedCount)
    yield ())
      .handleErrorWith(e => logCleanupError(memCacheName, e) *> sleepAfterErrorAction)
      .foreverM
      .background
      .void
  end cleanupWorker

  private def startCleanupWorker[F[_]: { Temporal, Logger }, K: Ordering, V](
      memCacheName: String,
      r: Ref[F, CacheState[K, V]],
      cleanupInterval: FiniteDuration,
      sleepAfterErrorAction: F[Unit],
  ): Resource[F, Unit] =
    for
      _ <- Resource.eval(U.logi(s"Starting memCache cleanup worker for '$memCacheName'..."))
      _ <- cleanupWorker(memCacheName, r, cleanupInterval, sleepAfterErrorAction)
      _ <- Resource.eval(U.logi(s"Cleanup worker started for '$memCacheName'."))
    yield ()
  end startCleanupWorker

  private final val TimeTickErrorMsgFormat: String =
    "MemCache '%s': TimeTickWorker encountered an error during a cycle.  Worker will continue to run."

  private final val TimeTickResetClockMsgFormat: String =
    "MemCache '%s': Resetting internal memCache clock!"

  private def logTimeTickError[F[_]: Logger](
      memCacheName: String,
      e: Throwable,
  ): F[Unit] =
    U.loge(e, TimeTickWorkerName, TimeTickErrorMsgFormat.format(memCacheName))
  end logTimeTickError

  private def logTimeTickReset[F[_]: Logger](
      memCacheName: String,
  ): F[Unit] =
    U.logi(TimeTickWorkerName, TimeTickResetClockMsgFormat.format(memCacheName))
  end logTimeTickReset

  private def timeTickWorker[F[_]: { Temporal as temporal, Logger }, K: Ordering, V](
      memCacheName: String,
      r: Ref[F, CacheState[K, V]],
      trueTimeUpdateCounter: Ref[F, Int],
      timeTickInterval: FiniteDuration,
      sleepAfterErrorAction: F[Unit],
  ): Resource[F, Unit] =
    val temporalAmount = timeTickInterval.toJava
    val getNowOpt = Some(temporal.realTimeInstant)
    val sleepForTickInterval = temporal.sleep(timeTickInterval)

    val getRealTimeIfAppropriate: F[(Option[F[Instant]], Int)] =
      trueTimeUpdateCounter.get.map { c =>
        if c == UpdateNowWithTrueTimeAfterNUpdates then (getNowOpt, 0) else (None, c + 1)
      }

    val logClockReset = logTimeTickReset(memCacheName)

    (for
      _ <- sleepForTickInterval
      (timeOpt, newCounterVal) <- getRealTimeIfAppropriate
      _ <- trueTimeUpdateCounter.set(newCounterVal)
      newNowOpt <- timeOpt.sequence
      _ <- r.update { case CacheState(m, s, lruMap, seqCounter, now) =>
        CacheState(m, s, lruMap, seqCounter, newNowOpt.getOrElse(now.plus(temporalAmount)))
      }
      _ <- temporal.whenA(newCounterVal == 0)(logClockReset)
    yield ())
      .handleErrorWith(e => logTimeTickError(memCacheName, e) *> sleepAfterErrorAction)
      .foreverM
      .background
      .void
  end timeTickWorker

  private def startTimeTickingWorker[F[_]: { Temporal, Logger }, K: Ordering, V](
      memCacheName: String,
      r: Ref[F, CacheState[K, V]],
      timeTickDuration: FiniteDuration,
      sleepAfterErrorAction: F[Unit],
  ): Resource[F, Unit] =
    for
      _ <- Resource.eval(U.logi(s"Starting memCache timeTick worker for '$memCacheName'..."))
      trueTimeUpdateCounter <- Resource.eval(Ref.of(0))
      _ <- timeTickWorker(memCacheName, r, trueTimeUpdateCounter, timeTickDuration, sleepAfterErrorAction)
      _ <- Resource.eval(U.logi(s"TimeTick worker started for '$memCacheName'."))
    yield ()
  end startTimeTickingWorker
end MemCache
