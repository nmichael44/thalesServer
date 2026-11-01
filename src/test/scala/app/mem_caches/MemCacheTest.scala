package app.mem_caches

import cats.effect.IO
import cats.effect.testing.scalatest.AsyncIOSpec
import cats.kernel.Hash
import cats.syntax.all.*

import scala.concurrent.duration.*

import org.scalatest.Assertion
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.matchers.should.Matchers

import app.ThalesServer
import org.typelevel.log4cats.Logger

final class MemCacheTest extends AsyncFreeSpec with AsyncIOSpec with Matchers:
  given [T]: CanEqual[Option[T], Option[T]] = CanEqual.derived

  private def withCache[K: { Ordering, Hash }, V](
      capacity: Int = 8,
      numShards: Int = 2,
      cleanupDuration: FiniteDuration = 30.seconds,
  )(fn: MemCache[IO, K, V] => IO[Assertion]): IO[Assertion] =
    ThalesServer.createLogger[IO].flatMap:
      case given Logger[IO] =>
        MemCache.create[IO, K, V]("testCache", capacity, numShards, cleanupDuration).use(fn)
  end withCache

  "MemCache" - {
    "creation and duration validation" - {
      "should fail when capacity is zero or negative" in {
        ThalesServer.createLogger[IO].flatMap:
          case given Logger[IO] =>
            for
              resZero <- MemCache.create[IO, String, Int]("testCache", 0, 1, 30.seconds).use(_ => IO.unit).attempt
              resNeg <- MemCache.create[IO, String, Int]("testCache", -2, 1, 30.seconds).use(_ => IO.unit).attempt
            yield
              resZero.isLeft shouldBe true
              resZero.left.toOption.get shouldBe a[IllegalArgumentException]
              resNeg.isLeft shouldBe true
              resNeg.left.toOption.get shouldBe a[IllegalArgumentException]
      }

      "should fail when numShards is zero or negative" in {
        ThalesServer.createLogger[IO].flatMap:
          case given Logger[IO] =>
            for
              resZero <- MemCache.create[IO, String, Int]("testCache", 10, 0, 30.seconds).use(_ => IO.unit).attempt
              resNeg <- MemCache.create[IO, String, Int]("testCache", 10, -4, 30.seconds).use(_ => IO.unit).attempt
            yield
              resZero.isLeft shouldBe true
              resZero.left.toOption.get shouldBe a[IllegalArgumentException]
              resNeg.isLeft shouldBe true
              resNeg.left.toOption.get shouldBe a[IllegalArgumentException]
      }

      "should fail when numShards is greater than capacity" in {
        ThalesServer.createLogger[IO].flatMap:
          case given Logger[IO] =>
            MemCache.create[IO, String, Int]("testCache", 10, 11, 30.seconds).use(_ => IO.unit).attempt.map: res =>
              res.isLeft shouldBe true
              res.left.toOption.get shouldBe a[IllegalArgumentException]
      }

      "should fail when cleanupDuration is less than 30 seconds" in {
        ThalesServer.createLogger[IO].flatMap:
          case given Logger[IO] =>
            MemCache.create[IO, String, Int]("testCache", 10, 2, 29.seconds).use(_ => IO.unit).attempt.map: res =>
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
          yield
            v1 shouldBe Some("v1")
            v2 shouldBe Some("v2")
      }

      "should allow short durations under 10 seconds and retrieve before expiration" in {
        withCache[String, String](): cache =>
          for
            _ <- cache.put("shortLived", "value", 2.seconds)
            v <- cache.get("shortLived")
          yield v shouldBe Some("value")
      }

      "should update value, expiration, and LRU order cleanly when overwriting an existing key" in {
        withCache[String, String](capacity = 2, numShards = 1): cache =>
          for
            _ <- cache.put("k1", "v1", 50.milliseconds)
            _ <- cache.put("k2", "v2", 5.seconds)
            // Overwrite k1 with a new value and no expiry (should not evict k2, and should make k2 the LRU entry)
            _ <- cache.put("k1", "v1-updated")
            _ <- IO.sleep(100.milliseconds)
            v1AfterOldTtl <- cache.get("k1")
            v2BeforeEvict <- cache.get("k2")
            // Touch k1 again so k2 is LRU, then insert k3 to verify k2 is evicted
            _ <- cache.get("k1")
            _ <- cache.put("k3", "v3")
            v1AfterEvict <- cache.get("k1")
            v2AfterEvict <- cache.get("k2")
            v3AfterEvict <- cache.get("k3")
          yield
            v1AfterOldTtl shouldBe Some("v1-updated")
            v2BeforeEvict shouldBe Some("v2")
            v1AfterEvict shouldBe Some("v1-updated")
            v2AfterEvict shouldBe None
            v3AfterEvict shouldBe Some("v3")
      }
    }

    "LRU eviction" - {
      "should evict the least recently used entry when capacity is reached" in {
        withCache[String, Int](capacity = 3, numShards = 1): cache =>
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
          yield
            v1 shouldBe Some(1)
            v2 shouldBe Some(2)
            v3 shouldBe None
            v4 shouldBe Some(4)
      }

      "should bound total entries across multiple shards to total capacity" in {
        val keys = (1 to 40).map(i => s"key-$i").toVector
        withCache[String, Int](capacity = 10, numShards = 4): cache =>
          for
            _ <- keys.zipWithIndex.traverse_((k, i) => cache.put(k, i, 10.seconds))
            presentValues <- keys.traverse(cache.get)
          yield presentValues.flatten.size shouldBe 10
      }
    }

    "expiration on get" - {
      "should return None when an item has expired" in {
        withCache[String, String](): cache =>
          for
            _ <- cache.put("ephemeral", "temp", 50.milliseconds)
            _ <- IO.sleep(100.milliseconds)
            v <- cache.get("ephemeral")
          yield v shouldBe None
      }
    }
  }
end MemCacheTest
