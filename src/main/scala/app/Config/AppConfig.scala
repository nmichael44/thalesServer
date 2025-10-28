package app.Config

import cats.syntax.either.*

import app.ThalesUtils.GenUtils
import pureconfig.*
import pureconfig.error.{CannotConvert, ConfigReaderFailures, ConvertFailure}

object AppConfig:
  final case class DbConnectionConfig(
      private val host: String,
      private val port: Port,
      private val user: String,
      private val password: String,
      private val databaseName: String,
      private val maxConnections: Int,
      private val minIdleConnections: Int,
  ) derives ConfigReader:
    def getHost: String = host

    def getPort: Int = port.port

    def getUser: String = user

    def getPassword: String = password

    def getDatabaseName: String = databaseName

    def getMaxConnections: Int = maxConnections

    def getMinIdleConnections: Int = minIdleConnections
  end DbConnectionConfig

  final case class ServerConnectionConfig(
      private val host: String,
      private val port: Port,
      private val keystoreFile: String,
      private val keystorePassword: String,
  ) derives ConfigReader:
    def getHost: String = host

    def getPort: Int = port.port

    def getKeystoreFile: String = keystoreFile

    def getKeystorePassword: String = keystorePassword
  end ServerConnectionConfig

  final case class BackendServerConfig(
      private val numberOfWorkers: Int,
      private val boundedQueueCapacity: Int,
  ) derives ConfigReader:
    def getNumberOfWorkers: Int = numberOfWorkers

    def getBoundedQueueCapacity: Int = boundedQueueCapacity
  end BackendServerConfig

  final case class AuthConfig(
      private val secretKey: String,
      private val expirationPeriodInSeconds: Long,
      private val allowedRenewalPeriodInSeconds: Long,
      private val jwtEncodingAlgorithm: String,
  ):
    def getSecretKey: String = secretKey

    def getExpirationPeriodInSeconds: Long = expirationPeriodInSeconds

    def getAllowedRenewalPeriodInSeconds: Long = allowedRenewalPeriodInSeconds

    def getJwtEncodingAlgorithm: String = jwtEncodingAlgorithm
  end AuthConfig

  final case class AppConfig(
      private val name: String,
      private val dbConnectionConfig: DbConnectionConfig,
      private val serverConnectionConfig: ServerConnectionConfig,
      private val backendServerConfig: BackendServerConfig,
      private val authConfig: AuthConfig,
  ) derives ConfigReader:
    def getDbConnectionConfig: DbConnectionConfig = dbConnectionConfig

    def getServerConnectionConfig: ServerConnectionConfig = serverConnectionConfig

    def getBackendServerConfig: BackendServerConfig = backendServerConfig

    def getAuthConfig: AuthConfig = authConfig
  end AppConfig

  final case class Port(port: Int) extends AnyVal

  given ConfigReader[Port] = ConfigReader.fromCursor { cursor =>
    cursor.asInt
      .flatMap(intPort =>
        if GenUtils.isValidPort(intPort)
        then Port(intPort).asRight
        else
          ConfigReaderFailures(
            ConvertFailure(
              CannotConvert(
                intPort.toString,
                "Port",
                "Port value is outside the valid range (1-65535)",
              ),
              cursor,
            ),
          ).asLeft,
      )
  }
end AppConfig
