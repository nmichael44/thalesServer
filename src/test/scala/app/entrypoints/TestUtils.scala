package app.entrypoints

import cats.effect.{Async, IO, Resource}
import cats.effect.std.Env

import scala.collection.View

import fs2.compression.Compression
import fs2.io.net.Network
import fs2.io.net.tls.TLSContext
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder

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

  val clientResource: Resource[IO, Client[IO]] = for {
    tlsContext <- Resource.eval(TLSContext.Builder.forAsync[IO].insecure)
    client <- EmberClientBuilder
      .default[IO]
      .withTLSContext(tlsContext)
      // .withHttp2 -- We turn this off because of the spurious exceptions that show up in the log.
      // Since the client dies first, the server records it in the log as an exception.
      // In production, we want to turn it on.
      .build
  } yield client
  end clientResource

  given Async[IO] = IO.asyncForIO
  given Env[IO] = IO.envForIO
  given Network[IO] = Network.forAsync[IO]
  given Compression[IO] = Compression.forSync[IO]
end TestUtils
