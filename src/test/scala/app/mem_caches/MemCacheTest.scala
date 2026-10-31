package app.mem_caches

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec

import scala.concurrent.duration.*

import org.scalatest.Assertion
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import app.ThalesServer
import org.typelevel.log4cats.Logger

final class MemCacheTest extends AsyncFreeSpec with AsyncIOSpec with Matchers:
  given [T]: CanEqual[Option[T], Option[T]] = CanEqual.derived
  given CanEqual[Set[String], Set[String]] = CanEqual.derived

  private def withCache[K: Ordering, V](
      capacity: Int = 3,
      cleanupDuration: FiniteDuration = 30.seconds,
  )(fn: MemCache[IO, K, V] => IO[Assertion]): IO[Assertion] =
    ThalesServer.createLogger[IO].flatMap:
      case given Logger[IO] =>
        MemCache.create[IO, K, V]("testCache", capacity, cleanupDuration).use(fn)
  end withCache

  "MemCache" - {
    "creation and duration validation" - {
      "should fail when capacity is zero or negative" in {
        ThalesServer.createLogger[IO].flatMap:
          case given Logger[IO] =>
            for
              resZero <- MemCache.create[IO, String, Int]("testCache", 0, 30.seconds).use(_ => IO.unit).attempt
              resNeg <- MemCache.create[IO, String, Int]("testCache", -2, 30.seconds).use(_ => IO.unit).attempt
            yield
              resZero.isLeft shouldBe true
              resZero.left.toOption.get shouldBe a[IllegalArgumentException]
              resNeg.isLeft shouldBe true
              resNeg.left.toOption.get shouldBe a[IllegalArgumentException]
      }

      "should fail when cleanupDuration is less than 30 seconds" in {
        ThalesServer.createLogger[IO].flatMap:
          case given Logger[IO] =>
            MemCache.create[IO, String, Int]("testCache", 10, 29.seconds).use(_ => IO.unit).attempt.map: res =>
              res.isLeft shouldBe true
              res.left.toOption.get shouldBe a[IllegalArgumentException]
      }

      "should fail when put is called with zero or negative duration" in {
        withCache[String, Int](): cache =>
          for
            resZero <- cache.put("k1", 1, 0.seconds).attempt
            resNeg <- cache.put("k2", 2, java.time.Duration.ofSeconds(-5)).attempt
          yield
            resZero.isLeft shouldBe true
            resZero.left.toOption.get shouldBe a[IllegalArgumentException]
            resNeg.isLeft shouldBe true
            resNeg.left.toOption.get shouldBe a[IllegalArgumentException]
      }
    }

    "put and get" - {
      "should return None for a missing key" in {
        withCache[String, String](): cache =>
          cache.get("nonexistent").map(_ shouldBe None)
      }

      "should store and retrieve entries without expiration" in {
        withCache[String, String](): cache =>
          for
            _ <- cache.put("k1", "v1")
            _ <- cache.put("k2", "v2")
            v1 <- cache.get("k1")
            v2 <- cache.get("k2")
            (mainMap, expirySet, lruMap, _) <- cache.getInternalCacheState
          yield
            v1 shouldBe Some("v1")
            v2 shouldBe Some("v2")
            mainMap.size shouldBe 2
            expirySet.size shouldBe 0
            lruMap.size shouldBe 2
      }

      "should allow short durations under 10 seconds and retrieve before expiration" in {
        withCache[String, String](): cache =>
          for
            _ <- cache.put("shortLived", "value", 2.seconds)
            v <- cache.get("shortLived")
            (mainMap, expirySet, lruMap, _) <- cache.getInternalCacheState
          yield
            v shouldBe Some("value")
            mainMap.size shouldBe 1
            expirySet.size shouldBe 1
            lruMap.size shouldBe 1
      }

      "should update value, expirySet, and lruMap cleanly when overwriting an existing key" in {
        withCache[String, String](capacity = 2): cache =>
          for
            _ <- cache.put("k1", "v1", 5.seconds)
            _ <- cache.put("k2", "v2", 5.seconds)
            // Overwrite k1 with a new value and no expiry (should not evict k2)
            _ <- cache.put("k1", "v1-updated")
            v1 <- cache.get("k1")
            v2 <- cache.get("k2")
            (mainMap, expirySet, lruMap, _) <- cache.getInternalCacheState
          yield
            v1 shouldBe Some("v1-updated")
            v2 shouldBe Some("v2")
            mainMap.keySet shouldBe Set("k1", "k2")
            expirySet.size shouldBe 1
            expirySet.head._2 shouldBe "k2"
            lruMap.size shouldBe 2
      }
    }

    "LRU eviction" - {
      "should evict the least recently used entry when capacity is reached" in {
        withCache[String, Int](capacity = 3): cache =>
          for
            _ <- cache.put("k1", 1, 10.seconds)
            _ <- cache.put("k2", 2, 10.seconds)
            _ <- cache.put("k3", 3, 10.seconds)
            // Touch k1 and k2 so k3 becomes the least recently used
            _ <- cache.get("k1")
            _ <- cache.get("k2")
            // Inserting k4 should evict k3
            _ <- cache.put("k4", 4, 10.seconds)
            v1 <- cache.get("k1")
            v2 <- cache.get("k2")
            v3 <- cache.get("k3")
            v4 <- cache.get("k4")
            (mainMap, expirySet, lruMap, _) <- cache.getInternalCacheState
          yield
            v1 shouldBe Some(1)
            v2 shouldBe Some(2)
            v3 shouldBe None
            v4 shouldBe Some(4)
            mainMap.keySet shouldBe Set("k1", "k2", "k4")
            expirySet.map(_._2) shouldBe Set("k1", "k2", "k4")
            lruMap.values.toSet shouldBe Set("k1", "k2", "k4")
      }
    }

    "expiration on get" - {
      "should return None and remove internal state entries when an item has expired" in {
        withCache[String, String](): cache =>
          for
            _ <- cache.put("ephemeral", "temp", 50.milliseconds)
            _ <- IO.sleep(100.milliseconds)
            v <- cache.get("ephemeral")
            (mainMap, expirySet, lruMap, _) <- cache.getInternalCacheState
          yield
            v shouldBe None
            mainMap.isEmpty shouldBe true
            expirySet.isEmpty shouldBe true
            lruMap.isEmpty shouldBe true
      }
    }
  }
end MemCacheTest
