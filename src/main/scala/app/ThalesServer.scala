package app

import cats.data.{Kleisli, OptionT}
import cats.effect.*
import cats.effect.kernel.{Async, Resource}
import cats.effect.std.{Env, Supervisor}
import cats.syntax.all.*

import scala.collection.View
import scala.concurrent.duration.*

import app.entrypoints.{CreateBoUserEp, DeleteRoleByIdEp, FetchAllBoPermissionsEp, FetchAllBoRolesEp, FetchAllLiveSessionsEp, FetchAllUsersAssociatedWithRoleEp, FetchBoUserByLoginNameEp, FetchBoUserByUserIdEp, FetchMultipleBoUsersByUserIdEp, LoginRequestEp, RenewJwtTokenEp, ResetBoUserPasswordEp}
import app.entrypoints.{JobHandler, ThalesEntryPoint}
import app.services.*
import app.serviceslive.*
import app.uuid.UUIDGenerator
import app.Config.AppConfig.*
import app.Database.DoobieUtils
import app.ThalesUtils.ExtensionMethodUtils.*
import app.ThalesUtils.GenUtils as U
import com.comcast.ip4s.{Ipv4Address, Port}
import fs2.io.net.tls.*
import fs2.io.net.Network
import org.http4s
import org.http4s.*
import org.http4s.client.middleware.FollowRedirect
import org.http4s.client.Client
import org.http4s.dsl.Http4sDsl
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.implicits.*
import org.http4s.server.Router
import org.typelevel.log4cats.{Logger, LoggerName}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pureconfig.ConfigSource
import sttp.tapir.server.http4s.Http4sServerInterpreter
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.swagger.bundle.SwaggerInterpreter

private final class ThalesServer[F[_]: { Async as async, Logger as logger }] private (
    deps: AppDependencies[F],
    dsl: Http4sDsl[F],
):
  private val jobHandler: JobHandler[F] = JobHandler[F](deps.serverState, deps.uuidGen)

  private val FiberName = "http4sFiber"

  private def logi(s: String): F[Unit] =
    U.logi(FiberName, s)
  end logi

  private def loge(e: Throwable, uuid: String, s: String): F[Unit] =
    U.loge(e, FiberName, uuid, s)
  end loge

  private def logi(uuid: String, s: String): F[Unit] =
    U.logi(FiberName, uuid, s)
  end logi

  private val allNonAuthedEndPoints: View[ThalesEntryPoint[F]] = View(
    LoginRequestEp.create(jobHandler, deps.serverState),
    ResetBoUserPasswordEp.create(jobHandler),
  )

  private val allAuthedEndPoints: View[ThalesEntryPoint[F]] = {
    val authService = deps.authService

    View(
      CreateBoUserEp.create(jobHandler, authService),
      FetchBoUserByLoginNameEp.create(jobHandler, authService),
      FetchBoUserByUserIdEp.create(jobHandler, authService),
      FetchMultipleBoUsersByUserIdEp.create(jobHandler, authService),
      FetchAllLiveSessionsEp.create(jobHandler, authService),
      RenewJwtTokenEp.create(jobHandler, authService),
      FetchAllBoPermissionsEp.create(jobHandler, authService),
      FetchAllBoRolesEp.create(jobHandler, authService),
      DeleteRoleByIdEp.create(jobHandler, authService),
      FetchAllUsersAssociatedWithRoleEp.create(jobHandler, authService),
    )
  }

  private val allRouteEndPoints: List[ServerEndpoint[Any, F]] =
    (allNonAuthedEndPoints ++ allAuthedEndPoints).map(_.getEntryPoint).toList

  private val tapirRoutes: HttpRoutes[F] =
    Http4sServerInterpreter[F]().toRoutes(allRouteEndPoints)

  private val swaggerRoutes: HttpRoutes[F] =
    Http4sServerInterpreter[F]().toRoutes(
      SwaggerInterpreter().fromServerEndpoints[F](allRouteEndPoints, ThalesServer.AppName, ThalesServer.AppVersion),
    )

  private val allRoutesPath: (String, HttpRoutes[F]) =
    val allPublicRoutes: HttpRoutes[F] = tapirRoutes <+> swaggerRoutes

    "/" -> allPublicRoutes

  val allRoutes: HttpApp[F] = Router[F](allRoutesPath).orNotFound
end ThalesServer

object ThalesServer:
  private def getServerHostIPPort[F[_]: Async as async](
      serverConnectionConfig: ServerConnectionConfig,
  ): F[(Ipv4Address, Port)] =
    val (host, port) = (serverConnectionConfig.getHost, serverConnectionConfig.getPort)

    (Ipv4Address.fromString(host), Port.fromInt(port)) match {
      case (Some(ipv4Address), Some(port)) => async.pure((ipv4Address, port))
      case (None, _) => async.raiseError(AssertionError(s"Illegal ServerHostIP: '$host'."))
      case (_, None) => async.raiseError(AssertionError(s"Illegal ServerHostPort: '$port'."))
    }
  end getServerHostIPPort

  private val AppName: String = "Thales Server API"

  private val AppVersion = "1.0"

  private val MainFiberName: String = "MainFiber"

  private val AppEnvs: Set[String] = Set("dev", "prod")

  private def createConfigResource[F[_]: { Async as async, Env as env, Logger }]: Resource[F, AppConfig] =
    val loadConfig = for {
      appEnvOpt <- env.get("APP_ENV")
      env = appEnvOpt.getOrElse("dev")
      _ <- (!AppEnvs.contains(env)).whenA(
        async.raiseError(AssertionError(s"Bad configuration environment: '$env'.")),
      )
      config <- async.fromEither(
        ConfigSource
          .resources(s"application-$env.conf")
          .withFallback(ConfigSource.resources("application.conf"))
          .at("app-config")
          .load[AppConfig]
          .left
          .map(pureconfig.error.ConfigReaderException[AppConfig]),
      )
      _ <- U.logi(MainFiberName, config.toString)
    } yield config

    Resource.eval(loadConfig)
  end createConfigResource

  private def createServerResource[F[_]: { Async, Network }](
      serverHostIP: Ipv4Address,
      serverHostPort: Port,
      keyStoreFile: String,
      keyStorePassword: String,
      httpApp: HttpApp[F],
  ): Resource[F, http4s.server.Server] =
    val keyStoreFileNio = java.nio.file.Paths.get(keyStoreFile)
    val keyStorePasswordArray = keyStorePassword.toCharArray

    Resource.eval(
      TLSContext.Builder
        .forAsync[F]
        .fromKeyStoreFile(keyStoreFileNio, keyStorePasswordArray, keyStorePasswordArray),
    ) >>= { tlsContext =>
      EmberServerBuilder
        .default[F]
        .withHost(serverHostIP)
        .withPort(serverHostPort)
        .withShutdownTimeout(5.seconds)
        .withHttpApp(httpApp)
        .withTLS(tlsContext)
        .build
    }
  end createServerResource

  private def createHttpApp[F[_]: { Async, Logger }](deps: AppDependencies[F]): HttpApp[F] =
    val dsl: Http4sDsl[F] = Http4sDsl[F]

    ThalesServer(deps, dsl).allRoutes
  end createHttpApp

  private def createServer[F[_]: { Async as async, Network, Logger }](
      appConfig: AppConfig,
      deps: AppDependencies[F],
  ): F[ExitCode] =
    val serverConnectionConfig = appConfig.getServerConnectionConfig
    val keyStoreFile = serverConnectionConfig.getKeystoreFile
    val keyStorePassword = serverConnectionConfig.getKeystorePassword
    val httpApp = createHttpApp[F](deps)

    getServerHostIPPort[F](serverConnectionConfig) >>= { (serverHostIP, serverHostPort) =>
      createServerResource(serverHostIP, serverHostPort, keyStoreFile, keyStorePassword, httpApp)
        .use(server => U.logi(MainFiberName, s"Server started with base uri: '${server.baseUri.toString}'.") *> async.never)
        .as(ExitCode.Success)
    }
  end createServer

  // This is the number of redirects Ember will perform when a response
  // specifies that it needs a redirection.
  inline private val MaxHttpClientRedirects = 5

  private def createLogger[F[_]: Async as async]: F[Logger[F]] =
    val thalesServerLoggerName = LoggerName("ThalesServerLogger")

    Slf4jLogger.create[F](using async, thalesServerLoggerName).widen[Logger[F]]
  end createLogger

  val run: IO[ExitCode] =
    type F = IO

    // We declare these two implicits here, to avoid the numerous calls to fetch them for each function below.
    implicit val async: Async[F] = IO.asyncForIO
    implicit val env: Env[F] = IO.envForIO // env is actually used -- intellij claims it is not, but it's a bug in intellij

    createLogger[F] >>= { implicit logger =>
      val appDeps: Resource[F, (AppConfig, AppDependencies[F])] = for {
        appConfig <- createConfigResource[F]
        serverState <- Resource.eval[F, ServerState[F]](ServerStateLive.create[F](appConfig.getBackendServerConfig))
        httpClient <- EmberClientBuilder.default[F].build.map(FollowRedirect[F](MaxHttpClientRedirects))
        supervisor <- Supervisor[F](await = false)
        xa <- DoobieUtils.xaResource[F](appConfig.getDbConnectionConfig)
        uuidGen <- UUIDGenerator.create[F]
        uuidScope <- Resource.eval[F, TraceIdScope[F, Option[String]]](TraceIdScope.fromIOLocal[Option[String]](None))
      } yield {
        val externalApiClientService: ExternalApiClientService[F] = ExternalApiClientServiceLive.create[F](httpClient)
        val boRepoService: BoRepositoryService[F] = BoRepositoryServiceLive.create[F](xa)
        val passwordHasherService: PasswordHasherService[F] = PasswordHasherServiceLive.create[F]
        val authService: AuthService[F] = AuthServiceLive.create[F](appConfig.getAuthConfig, boRepoService)

        (
          appConfig,
          AppDependencies(
            serverState,
            supervisor,
            uuidGen,
            uuidScope,
            externalApiClientService,
            boRepoService,
            passwordHasherService,
            authService,
          ),
        )
      }

      appDeps.use { (appConfig, deps) =>
        HttpWorker.createWorkers[F](appConfig, deps) *>
          createServer[F](appConfig, deps)
      }
    }
  end run
end ThalesServer
