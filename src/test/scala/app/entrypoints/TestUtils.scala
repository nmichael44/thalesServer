package app.entrypoints

import cats.effect.{Async, IO, Resource}
import cats.effect.std.Env
import cats.implicits.{catsSyntaxOption, catsSyntaxTuple5Semigroupal}
import cats.syntax.all.*

import java.sql.DriverManager
import scala.collection.View
import scala.sys.process.{Process, ProcessLogger}

import app.ThalesUtils.GenUtils as U
import fs2.compression.Compression
import fs2.io.file.{Files, Path}
import fs2.io.net.Network
import fs2.io.net.tls.TLSContext
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.implicits.uri

object TestUtils:
  Class.forName("org.postgresql.Driver")

  val setEnvVariables: IO[Unit] =
    import U.-->

    IO.delay {
      View(
        "DB_SERVER_HOST"       --> "localhost",
        "DB_SERVER_PORT"       --> "5432",
        "DB_USERNAME"          --> "thalesuser",
        "DB_USERNAME_PASSWORD" --> "thalesUser11",
        "DB_NAME"              --> "thalesdb",
        "JWT_SECRET_KEY"       --> "myTopSecretKey!",
        "KEYSTORE_PASSWORD"    --> "hgt67Y3!l9",
        "KEYSTORE_FILE"        --> "certs/keystore.p12",
      ).foreach { case (k, v) => System.setProperty(k, v) }
    }
  end setEnvVariables

  val clientResource: Resource[IO, Client[IO]] = for {
    tlsContext <- TLSContext.Builder.forAsync[IO].insecure.toResource
    client <- EmberClientBuilder
      .default[IO]
      .withTLSContext(tlsContext)
      // .withHttp2 -- We turn this off because of the spurious exceptions that show up in the log.
      // Since the client dies first, the server records it in the log as an exception.
      // In production, we want to turn it on.
      .build
  } yield client
  end clientResource

  val serverUri: org.http4s.Uri = uri"https://localhost:443"

  private final case class DbDetails(host: String, port: String, db: String, user: String, password: String)

  private def getDbDetails: IO[DbDetails] =
    def requiredProp(key: String): IO[String] =
      IO.delay(U.getSystemProp(key)) >>= (_.liftTo[IO](RuntimeException(s"Env variable not set: $key")))

    (
      requiredProp("DB_SERVER_HOST"),
      requiredProp("DB_SERVER_PORT"),
      requiredProp("DB_NAME"),
      requiredProp("DB_USERNAME"),
      requiredProp("DB_USERNAME_PASSWORD"),
    )
      .mapN(DbDetails.apply)
  end getDbDetails

  private val dbResetScriptPath: Path =
    fs2.io.file.Path.fromNioPath(
      java.nio.file.Paths.get("src", "main", "resources", "AppSchema.sql"),
    )
  end dbResetScriptPath

  private val dbResetScriptPathStr = dbResetScriptPath.toString

  def resetDatabase: IO[Unit] = resetDatabasePSql

  private def resetDatabaseJdbc: IO[Unit] =
    for {
      DbDetails(host, port, db, user, password) <- getDbDetails

      script <- filesIo
        .readAll(dbResetScriptPath)
        .through(fs2.text.utf8.decode)
        .compile
        .string

      _ <- IO.blocking {
        val url = s"jdbc:postgresql://$host:$port/$db"
        val connection = DriverManager.getConnection(url, user, password)
        try {
          val stmt = connection.createStatement()
          try stmt.execute(script)
          finally stmt.close()
        } finally connection.close()
      }
    } yield ()
  end resetDatabaseJdbc

  private val pSqlPath: String =
    "D:\\Program Files\\PostgreSQL\\15\\bin\\psql.exe"

  private def resetDatabasePSql: IO[Unit] =
    def teeLog(sb: StringBuilder, stream: java.io.PrintStream): String => Unit =
      s => {
        sb.append(s).append("\n")
        stream.println(s)
      }

    for {
      DbDetails(host, port, db, user, password) <- getDbDetails
      _ <- IO.blocking {
        val uri = s"postgresql://$user@$host:$port/$db"
        val (stdout, stderr) = (new StringBuilder, new StringBuilder)
        val logger = ProcessLogger(
          teeLog(stdout, System.out),
          teeLog(stderr, System.err),
        )

        val proc = Process(
          command = Seq(pSqlPath, "-d", uri, "-v", "ON_ERROR_STOP=1", "-f", dbResetScriptPathStr),
          cwd = None,
          extraEnv = ("PGPASSWORD", password),
        )

        val exitCode = proc.!(logger)

        if exitCode != 0 then
          throw RuntimeException(
            s"""Database reset failed (Exit Code: $exitCode).
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
