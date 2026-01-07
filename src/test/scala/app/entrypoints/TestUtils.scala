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
import app.ThalesUtils.ExtensionMethodUtils.*

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

  def resetDatabase(scriptPath: String): IO[Unit] = for {
      (url, user, password) <-
        val hostOpt = getSystemProp("DB_SERVER_HOST")
        val portOpt = getSystemProp("DB_SERVER_PORT")
        val dbOpt = getSystemProp("DB_NAME")
        val userOpt = getSystemProp("DB_USERNAME")
        val passwordOpt = getSystemProp("DB_USERNAME_PASSWORD")

        (hostOpt, portOpt, dbOpt, userOpt, passwordOpt) match {
          case (Some(host), Some(port), Some(db), Some(user), Some(password)) =>
            val url = s"jdbc:postgresql://$host:$port/$db"
            IO.pure((url, user, password))
          case _ =>
            IO.raiseError(AssertionError("Env variables were not set up properly."))
        }

      script <- filesIo
        .readAll(Path(scriptPath))
        .through(fs2.text.utf8.decode)
        .compile
        .string

      _ <- IO.blocking {
        val conn = DriverManager.getConnection(url, user, password)
        try {
          val stmt = conn.createStatement()
          try stmt.execute(script)
          finally stmt.close()
        } finally conn.close()
      }
    } yield ()
  end resetDatabase

  private def getSystemProp(s: String): Option[String] =
    Option(System.getProperty(s))
  end getSystemProp

  private val pSqlPath = "psql.exe"

  def resetDatabasePSql(scriptPath: String): IO[Unit] =
    def mkStreamBuildAppender(sb: StringBuilder): String => Unit =
      s => sb.append(s).append("\n").ignore
    end mkStreamBuildAppender

    for {
      (uri, password) <- {
        val hostOpt = getSystemProp("DB_SERVER_HOST")
        val portOpt = getSystemProp("DB_SERVER_PORT")
        val dbOpt = getSystemProp("DB_NAME")
        val userOpt = getSystemProp("DB_USERNAME")
        val passwordOpt = getSystemProp("DB_USERNAME_PASSWORD")

        (hostOpt, portOpt, dbOpt, userOpt, passwordOpt) match {
          case (Some(host), Some(port), Some(db), Some(user), Some(password)) =>
            val uri = s"postgresql://$user@$host:$port/$db"
            IO.pure((uri, password))
          case _ =>
            IO.raiseError(AssertionError("Env variables were not set up properly."))
        }
      }

      _ <- IO.blocking {
        val (stdout, stderr) = (new StringBuilder, new StringBuilder)

        // Logger captures output instead of printing to console immediately
        val logger = ProcessLogger(mkStreamBuildAppender(stdout), mkStreamBuildAppender(stderr))

        val proc = Process(
          command = Seq(pSqlPath, uri, "-f", scriptPath),
          cwd = None,
          extraEnv = ("PGPASSWORD", password),
        )

        val exitCode = proc.!(logger)

        if (exitCode != 0)
          throw RuntimeException(
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
