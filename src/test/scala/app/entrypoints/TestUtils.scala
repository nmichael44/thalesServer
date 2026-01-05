package app.entrypoints

import cats.effect.{Async, IO}
import cats.effect.std.Env

import scala.collection.View

import fs2.compression.Compression
import fs2.io.net.Network

object TestUtils:
  val setEnvVariables: IO[Unit] =
    IO.delay {
      View(
        "DB_SERVER_HOST"       -> "localhost",
        "DB_SERVER_PORT"       -> "5432",
        "DB_USERNAME"          -> "thalesuser",
        "DB_USERNAME_PASSWORD" -> "thalesUser11",
        "DB_NAME"              -> "thalesdb",
        "JWT_SECRET_KEY"       -> "myTopSecretKey!",
        "KEYSTORE_PASSWORD"    -> "hgt67Y3!l9",
        "KEYSTORE_FILE"        -> "certs/keystore.p12",
      ).foreach { case (k, v) => System.setProperty(k, v) }
    }
  end setEnvVariables

  given Async[IO] = IO.asyncForIO
  given Env[IO] = IO.envForIO
  given Network[IO] = Network.forAsync[IO]
  given Compression[IO] = Compression.forSync[IO]
end TestUtils
