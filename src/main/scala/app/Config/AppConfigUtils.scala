package app.Config

import cats.syntax.all.*

import scala.concurrent.duration.FiniteDuration

import app.ThalesUtils.GenUtils
import pureconfig.*
import pureconfig.error.{CannotConvert, ConfigReaderFailures}

object AppConfigUtils:
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
      private val endpointDelays: Map[String, FiniteDuration],
  ) derives ConfigReader:
    def getNumberOfWorkers: Int = numberOfWorkers

    def getBoundedQueueCapacity: Int = boundedQueueCapacity

    def getEndpointDelays: Map[String, FiniteDuration] = endpointDelays
  end BackendServerConfig

  final case class AuthConfig(
      private val secretKey: String,
      private val expirationPeriodInSeconds: Long,
      private val allowedRenewalPeriodInSeconds: Long,
      private val jwtEncodingAlgorithm: String,
      private val authMemCacheCapacity: Int,
      private val authMemCacheCleanupDurationInSeconds: Int,
      private val authMemCacheCleanupTimeTickDurationInSeconds: Int,
  ):
    def getSecretKey: String = secretKey

    def getExpirationPeriodInSeconds: Long = expirationPeriodInSeconds

    def getAllowedRenewalPeriodInSeconds: Long = allowedRenewalPeriodInSeconds

    def getJwtEncodingAlgorithm: String = jwtEncodingAlgorithm

    def getAuthMemCacheCapacity: Int = authMemCacheCapacity

    def getAuthMemCacheCleanupDurationInSeconds: Int = authMemCacheCleanupDurationInSeconds

    def getAuthMemCacheCleanupTimeTickDurationInSeconds: Int = authMemCacheCleanupTimeTickDurationInSeconds
  end AuthConfig

  final case class EmailOutboxWorkerConfig(
      private val pollingInterval: FiniteDuration,
      private val baseBackoff: FiniteDuration,
  ) derives ConfigReader:
    def getPollingInterval: FiniteDuration = pollingInterval

    def getBaseBackoff: FiniteDuration = baseBackoff
  end EmailOutboxWorkerConfig

  final case class AppConfig(
      private val name: String,
      private val dbConnectionConfig: DbConnectionConfig,
      private val serverConnectionConfig: ServerConnectionConfig,
      private val backendServerConfig: BackendServerConfig,
      private val authConfig: AuthConfig,
      private val emailOutboxWorkerConfig: EmailOutboxWorkerConfig,
  ) derives ConfigReader:
    def getDbConnectionConfig: DbConnectionConfig = dbConnectionConfig

    def getServerConnectionConfig: ServerConnectionConfig = serverConnectionConfig

    def getBackendServerConfig: BackendServerConfig = backendServerConfig

    def getAuthConfig: AuthConfig = authConfig

    def getEmailOutboxWorkerConfig: EmailOutboxWorkerConfig = emailOutboxWorkerConfig
  end AppConfig

  final case class Port(port: Int) extends AnyVal

  given ConfigReader[Port] = ConfigReader.fromCursor: cursor =>
    val parsedInt = cursor.asInt.orElse:
      cursor.asString.flatMap: s =>
        s.toIntOption match
          case Some(intVal) => Right(intVal)
          case None => cursor.failed(CannotConvert(s, "Int", "Not a valid integer string"))

    parsedInt.flatMap: intPort =>
      if GenUtils.isValidPort(intPort)
      then Port(intPort).asRight
      else
        cursor.failed(
          CannotConvert(
            intPort.toString,
            "Port",
            "Port value is outside the valid range (1-65535)",
          ),
        )
  end given
end AppConfigUtils
