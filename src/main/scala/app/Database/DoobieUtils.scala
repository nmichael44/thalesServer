package app.Database

import cats.effect.*

import app.Config.AppConfig.DbConnectionConfig
import com.zaxxer.hikari.HikariConfig
import doobie.hikari.HikariTransactor

object DoobieUtils:
  private final val DriverName: String = "org.postgresql.Driver"

  private def createTransactorResource[F[_]: Async](dbConfig: DbConnectionConfig): Resource[F, HikariTransactor[F]] =
    val (host, port, databaseName) = (dbConfig.getHost, dbConfig.getPort, dbConfig.getDatabaseName)

    val (user, password) = (dbConfig.getUser, dbConfig.getPassword)
    val (maxConnections, minIdleConnections) = (dbConfig.getMaxConnections, dbConfig.getMinIdleConnections)

    val databaseURL = s"jdbc:postgresql://$host:$port/$databaseName"

    val hikariConfig = new HikariConfig()
    hikariConfig.setDriverClassName(DriverName)
    hikariConfig.setJdbcUrl(databaseURL)
    hikariConfig.setUsername(user)
    hikariConfig.setPassword(password)

    // Set the maximum pool size
    hikariConfig.setMaximumPoolSize(maxConnections)
    hikariConfig.setMinimumIdle(minIdleConnections)

    HikariTransactor.fromHikariConfig[F](hikariConfig)
  end createTransactorResource

  def xaResource[F[_]: Async](dbConfig: DbConnectionConfig): Resource[F, HikariTransactor[F]] =
    createTransactorResource[F](dbConfig)
  end xaResource
end DoobieUtils
