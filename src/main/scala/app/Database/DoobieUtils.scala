package app.Database

import cats.effect.*

import app.Config.AppConfig.DbConnectionConfig
import com.zaxxer.hikari.HikariConfig
import doobie.hikari.HikariTransactor

object DoobieUtils:
  private final val DriverName: String = "com.microsoft.sqlserver.jdbc.SQLServerDriver"

  private def createTransactorResource[F[_]: Async](dbConfig: DbConnectionConfig): Resource[F, HikariTransactor[F]] =
    val (host, port, databaseName) = (dbConfig.getHost, dbConfig.getPort, dbConfig.getDatabaseName)

    val databaseURL = s"jdbc:sqlserver://$host:$port;databaseName=$databaseName;trustServerCertificate=true"
    // val databaseURL = s"jdbc:sqlserver://localhost\\sqlexpress;databaseName=$databaseName;integratedSecurity=true;trustServerCertificate=true;"

    val user = dbConfig.getUser
    val password = dbConfig.getPassword
    val maxConnections = dbConfig.getMaxConnections
    val minIdleConnections = dbConfig.getMinIdleConnections

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
