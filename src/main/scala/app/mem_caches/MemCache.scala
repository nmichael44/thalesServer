package app.mem_caches

import cats.{FlatMap, Functor}
import cats.effect.Temporal
import cats.effect.kernel.Ref
import cats.effect.std.Supervisor
import cats.implicits.*

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
      val (_, minK) = lru0.min
      val CacheElem(_, expiryOpt, seqCounter) = m0(minK)
      val m1 = m0 - minK
      val s1 = expiryOpt.fold(s0)(expiry => s0 - ((expiry, minK)))
      val lru1 = lru0 - seqCounter

      (m1, s1, lru1)
    else (m0, s0, lru0)
  end evictIfNecessary

  private val unit: F[Unit] = temporal.pure(())

  private def checkDuration(durationOpt: Option[java.time.Duration]): F[Unit] =
    durationOpt match
      case Some(d) if d.compareTo(MemCache.ItemMinimumAllowedDuration) < 0 =>
        temporal.raiseError(
          new IllegalArgumentException(
            s"MemCache.put(): Duration cannot be less than ${TimeUtils.durationToString(MemCache.ItemMinimumAllowedDuration)}.",
          ), // We don't do NoStackTrace here because it's helpful to see the stack.
        )
      case _ => unit
  end checkDuration

  private def putAux(k: K, v: V, durationOpt: Option[java.time.Duration]): F[Unit] =
    checkDuration(durationOpt) *>
      r.update { case CacheState(m, s, lruMap, seqCounter0, now) =>
        val existingEntryOpt: Option[CacheElem[V]] = m.get(k)

        val (m0, s0, lruMap0) = if existingEntryOpt.isDefined then (m, s, lruMap) else evictIfNecessary(m, s, lruMap)

        val newExpiryOpt: Option[Instant] = durationOpt.map(now.plus)
        val m1 = m0.updated(k, CacheElem(v, newExpiryOpt, seqCounter0))

        val s1Aux = existingEntryOpt.flatMap(_.expiryOpt).fold(s0)(currExpiry => s0 - ((currExpiry, k)))
        val s1 = newExpiryOpt.fold(s1Aux)(newExpiry => s1Aux + ((newExpiry, k)))
        val lruMap1 = existingEntryOpt
          .fold(lruMap0) { case CacheElem(_, _, seqCount) => lruMap0 - seqCount }
          .updated(seqCounter0, k)
        val seqCounter1 = seqCounter0 + 1

        CacheState(m1, s1, lruMap1, seqCounter1, now)
      }
  end putAux

  // This function is to be used for testing only.
  def getInternalCacheState: F[(TreeMap[K, CacheElem[V]], TreeSet[(Instant, K)], TreeMap[Long, K], Long, Instant)] =
    r.get.map { case CacheState(m, s, lruMap, seqCounter, now) => (m, s, lruMap, seqCounter, now) }
  end getInternalCacheState
end MemCache

object MemCache:
  def create[F[_]: { Temporal as temporal, Logger }, K: Ordering, V](
      supervisor: Supervisor[F],
      memCacheName: String,
      capacity: Int,
      cleanupDuration: FiniteDuration,
      timeTickDuration: FiniteDuration,
  ): F[MemCache[F, K, V]] =
    if capacity <= 0 then temporal.raiseError(IllegalArgumentException("Capacity must be greater than 0."))
    else createImpl(supervisor, memCacheName, capacity, cleanupDuration, timeTickDuration)
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

  private def createImpl[F[_]: { Temporal as temporal, Logger }, K: Ordering, V](
      supervisor: Supervisor[F],
      memCacheName: String,
      capacity: Int,
      cleanupDuration: FiniteDuration,
      timeTickDuration: FiniteDuration,
  ): F[MemCache[F, K, V]] =
    for {
      _ <- ensureMinCleanupDuration(cleanupDuration)
      _ <- ensureMinTimeTickDuration(timeTickDuration)
      now <- temporal.realTimeInstant
      r <- Ref.of(
        CacheState(TreeMap.empty[K, CacheElem[V]], TreeSet.empty[(Instant, K)], TreeMap.empty[Long, K], 0L, now),
      )
      _ <- startCleanupWorker(supervisor, memCacheName, r, cleanupDuration)
      _ <- startTimeTickingWorker(supervisor, memCacheName, r, timeTickDuration)
    } yield MemCache(memCacheName, capacity, r)
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
    getSize(r) >>= { case (mSiz, tSiz) => U.logi(s"Sizes of cache '$memCacheName' $when worker touched it: ($mSiz, $tSiz).") }
  end reportSize

  private val CleanupWorkerName: String = "CleanupWorker"

  private def cleanupWorker[F[_]: { Temporal as temporal, Logger }, K: Ordering, V](
      memCacheName: String,
      r: Ref[F, CacheState[K, V]],
      cleanupInterval: FiniteDuration,
  ): F[Nothing] =
    val logi = U.logi(CleanupWorkerName, _)
    val loge = U.loge(_, CleanupWorkerName, _)

    val logGoingToSleep = logi(s"'$memCacheName': Going to sleep until it's time to work...")
    val logAwakeGoingToWork = logi(s"'$memCacheName': is awake and going to work...")
    val reportSizeBefore = reportSize(memCacheName, r, "before")
    val reportSizeAfter = reportSize(memCacheName, r, "after")
    val sleepForCleanupInterval = temporal.sleep(cleanupInterval)

    val logError =
      val errMsg = s"'$memCacheName': encountered an error during a cycle.  Worker will continue to run."
      loge(_, errMsg)

    (for {
      _ <- logGoingToSleep
      _ <- sleepForCleanupInterval
      _ <- logAwakeGoingToWork
      _ <- reportSizeBefore
      _ <- r.update { case currentState @ CacheState(m0, s0, lruMap0, seqCounter0, now) =>
        val expiredEntries = s0.view.takeWhile((expiry, _) => hasExpired(expiry, now)).toVector
        if expiredEntries.isEmpty then currentState
        else
          val expiredKeys = expiredEntries.view.map(_._2).toVector
          val expiredSeqs = expiredKeys.view.map(m0(_).seqCount)

          val m1 = m0 -- expiredKeys
          val s1 = s0 -- expiredEntries
          val lruMap1 = lruMap0 -- expiredSeqs
          val seqCounter1 = seqCounter0

          CacheState(m1, s1, lruMap1, seqCounter1, now)
      }
      _ <- reportSizeAfter
    } yield ())
      .handleErrorWith(logError)
      .foreverM
  end cleanupWorker

  private def startCleanupWorker[F[_]: { Temporal, Logger }, K: Ordering, V](
      supervisor: Supervisor[F],
      memCacheName: String,
      r: Ref[F, CacheState[K, V]],
      cleanupInterval: FiniteDuration,
  ): F[Unit] = for {
    _ <- U.logi(s"Starting memCache cleanup worker for '$memCacheName'...")
    cleanupFiber <- supervisor.supervise(cleanupWorker(memCacheName, r, cleanupInterval))
    _ <- U.logi(s"Cleanup worker started for '$memCacheName'. Fiber is '$cleanupFiber'.")
  } yield ()
  end startCleanupWorker

  private def timeTickWorker[F[_]: { Temporal as temporal, Logger }, K: Ordering, V](
      memCacheName: String,
      r: Ref[F, CacheState[K, V]],
      trueTimeUpdateCounter: Ref[F, Int],
      timeTickInterval: FiniteDuration,
  ): F[Nothing] =
    val logi = U.logi(TimeTickWorkerName, _)
    val loge = U.loge(_, TimeTickWorkerName, _)

    val temporalAmount = timeTickInterval.toJava
    val getNowOpt = Some(temporal.realTimeInstant)

    val logGoingToSleep = logi(s"'$memCacheName': Going to sleep until it's time to work...")
    val logGoingToWork = logi(s"'$memCacheName': is awake and going to work...")
    val logResettingClock = logi("Resetting internal memCache clock!")
    val logTickUpdated = logi(s"TimeTick for '$memCacheName', updated!")
    val sleepForTickInterval = temporal.sleep(timeTickInterval)

    val getRealTimeIfAppropriate: F[(Option[F[Instant]], Int)] =
      trueTimeUpdateCounter.get.map { c =>
        if c == UpdateNowWithTrueTimeAfterNUpdates then (getNowOpt, 0) else (None, c + 1)
      }

    val logError =
      val errMsg = s"'$memCacheName': encountered an error during a cycle.  Worker will continue to run."
      loge(_, errMsg)

    (for {
      _ <- logGoingToSleep
      _ <- sleepForTickInterval
      _ <- logGoingToWork
      (timeOpt, newCounterVal) <- getRealTimeIfAppropriate
      _ <- trueTimeUpdateCounter.set(newCounterVal)
      newNowOpt <- timeOpt.sequence
      _ <- r.update { case CacheState(m, s, lruMap, seqCounter, now) =>
        CacheState(m, s, lruMap, seqCounter, newNowOpt.getOrElse(now.plus(temporalAmount)))
      }
      _ <- (newCounterVal == 0).whenA(logResettingClock) // The counter was reset so log it.
      _ <- logTickUpdated
    } yield ())
      .handleErrorWith(logError)
      .foreverM
  end timeTickWorker

  private def startTimeTickingWorker[F[_]: { Temporal, Logger }, K: Ordering, V](
      supervisor: Supervisor[F],
      memCacheName: String,
      r: Ref[F, CacheState[K, V]],
      timeTickDuration: FiniteDuration,
  ): F[Unit] = for {
    _ <- U.logi(s"Starting memCache timeTick worker for '$memCacheName'...")
    trueTimeUpdateCounter <- Ref.of(0)
    timeTickFiber <- supervisor.supervise(timeTickWorker(memCacheName, r, trueTimeUpdateCounter, timeTickDuration))
    _ <- U.logi(s"TimeTick worker started for '$memCacheName'. Fiber is '$timeTickFiber'.")
  } yield ()
  end startTimeTickingWorker
end MemCache
