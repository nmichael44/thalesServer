package app.entrypoints

import cats.effect.{Async, IO, Resource}
import cats.effect.std.Env
import cats.syntax.all.*

import java.sql.DriverManager
import scala.collection.View
import scala.sys.process.{Process, ProcessLogger}

import fs2.compression.Compression
import fs2.io.file.{Files, Path}
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

  def resetDatabase(scriptPath: String): IO[Unit] =
    for {
      (url, user, pass) <- IO.delay {
        val host = System.getProperty("DB_SERVER_HOST", "localhost")
        val port = System.getProperty("DB_SERVER_PORT", "5432")
        val db = System.getProperty("DB_NAME", "thalesdb")
        val user = System.getProperty("DB_USERNAME", "thalesuser")
        val pass = System.getProperty("DB_USERNAME_PASSWORD", "thalesUser11")
        val url = s"jdbc:postgresql://$host:$port/$db"
        (url, user, pass)
      }

      script <- filesIo
        .readAll(Path(scriptPath))
        .through(fs2.text.utf8.decode)
        .compile
        .string

      _ <- IO.blocking {
        val conn = DriverManager.getConnection(url, user, pass)
        try {
          val stmt = conn.createStatement()
          try stmt.execute(script)
          finally stmt.close()
        } finally conn.close()
      }
    } yield ()
  end resetDatabase

  private val pSqlPath = ""

  def resetDatabasePSql(scriptPath: String): IO[Unit] =
    for {
      (uri, password) <- {
        val hostOpt = Option(System.getProperty("DB_SERVER_HOST"))
        val portOpt = Option(System.getProperty("DB_SERVER_PORT"))
        val dbOpt = Option(System.getProperty("DB_NAME"))
        val userOpt = Option(System.getProperty("DB_USERNAME"))
        val passwordOpt = Option(System.getProperty("DB_USERNAME_PASSWORD"))

        (hostOpt, portOpt, dbOpt, userOpt, passwordOpt) match {
          case (Some(host), Some(port), Some(db), Some(user), Some(password)) =>
            val uri = s"postgresql://$user@$host:$port/$db"
            IO.pure((uri, password))
          case _ =>
            IO.raiseError(AssertionError("Env variables were not set up properly."))
        }
      }

      _ <- IO.blocking {
        val stdout = new StringBuilder
        val stderr = new StringBuilder

        // Logger captures output instead of printing to console immediately
        val logger = ProcessLogger(
          (o: String) => stdout.append(o).append("\n"),
          (e: String) => stderr.append(e).append("\n"),
        )

        val proc = Process(
          command = Seq(s"$pSqlPath\\psql", uri, "-f", scriptPath),
          cwd = None,
          extraEnv = ("PGPASSWORD", password),
        )

        val exitCode = proc.!(logger)

        if (exitCode != 0)
          // 3. detailed failure reporting
          throw new RuntimeException(
            s"""Database reset failed (Exit Code: $exitCode).
              |Script: $scriptPath
              |Error Output:
              |${stderr.toString}
              |Standard Output:
              |${stdout.toString}""".stripMargin,
          )
      }
    } yield ()
  end resetDatabasePSql

  given Async[IO] = IO.asyncForIO
  given Env[IO] = IO.envForIO
  given Network[IO] = Network.forAsync[IO]
  given Compression[IO] = Compression.forSync[IO]
  given filesIo: Files[IO] = Files.forAsync[IO]
end TestUtils
