package app

import cats.~>
import cats.data.{EitherT, Kleisli, OptionT}
import cats.effect.*
import cats.effect.kernel.{Async, Resource}
import cats.effect.std.{Env, Supervisor}
import cats.syntax.all.*

import scala.concurrent.duration.*

import app.Config.AppConfig.*
import app.Database.DoobieUtils
import app.ThalesUtils.ExtensionMethodUtils.*
import app.ThalesUtils.GenUtils as U
import app.auth.Permissions
import app.entrypoints.{EntryPointErrors, JobHandler, LoginServicesSmithyEp, RoleServicesSmithyEp}
import app.entrypoints.smithy.{LoginServices, RoleServices}
import app.model.AppModel.AuthenticatedUser
import app.services.*
import app.serviceslive.*
import app.uuid.UUIDGenerator
import com.comcast.ip4s.{Ipv4Address, Port}
import fs2.io.net.Network
import fs2.io.net.tls.*
import org.http4s
import org.http4s.*
import org.http4s.Challenge
import org.http4s.client.Client
import org.http4s.client.middleware.FollowRedirect
import org.http4s.dsl.Http4sDsl
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.`WWW-Authenticate`
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import org.http4s.server.AuthMiddleware
import org.typelevel.ci.CIString
import org.typelevel.log4cats.{Logger, LoggerName}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pureconfig.ConfigSource
import smithy4s.UnsupportedProtocolError
import smithy4s.http4s.SimpleRestJsonBuilder

private final class ThalesServer[F[_]: { Async as async, Logger as logger }] private (
    deps: AppDependencies[F],
    dsl: Http4sDsl[F],
):
  private val epErrors: EntryPointErrors[F] = EntryPointErrors.create[F]
  private val serverState: ServerState[F] = deps.serverState
  private val authService: AuthService[F] = deps.authService

  private val jobHandler: JobHandler[F] =
    JobHandler.create[F](serverState.jobQueue, deps.uuidGen, epErrors)
  end jobHandler

  private given CanEqual[CIString, CIString] = CanEqual.derived

  private val badBearerToken: Either[String, String] = Left("Bearer token in Authorization header not found.")

  private val headerSelect: Header.Select[Authorization] =
    Header.Select.singleHeaders(using Authorization.headerInstance)
  end headerSelect

  private val authUser: Kleisli[F, Request[F], Either[String, AuthenticatedUser]] =
    Kleisli { (req: Request[F]) =>
      val eitherToken: Either[String, String] =
        req.headers.get[Authorization](using headerSelect) match {
          case Some(Authorization(Credentials.Token(AuthScheme.Bearer, token))) => Right(token)
          case _ => badBearerToken
        }

      (for {
        tokenStr <- EitherT.fromEither(eitherToken)
        authUser <- EitherT(authService.validateToken(tokenStr).map(_.left.map(_.getMessage)))
      } yield authUser).value
    }
  end authUser

  private val authMiddleware: AuthMiddleware[F, AuthenticatedUser] =
    import dsl.*

    val onFailure: AuthedRoutes[String, F] = Kleisli { req =>
      // req.context contains the error string (e.g. "Bearer token... not found")
      val errorMsg = req.context

      val challenge = `WWW-Authenticate`(
        Challenge(
          scheme = "Bearer",
          realm = ThalesServer.AppName,
          params = Map("error" -> "invalid_token", "error_description" -> errorMsg),
        ),
      )

      // You can return the error in the body AND the header
      OptionT.liftF(Unauthorized(challenge, errorMsg))
    }

    AuthMiddleware(authUser, onFailure)
  end authMiddleware

  private type AuthEffect[A] = Kleisli[F, AuthenticatedUser, A]

  private def bridgeSmithyAndHttp4s(smithyRoutes: HttpRoutes[AuthEffect]): AuthedRoutes[AuthenticatedUser, F] =
    def natTransformAuthEffectToF(user: AuthenticatedUser): AuthEffect ~> F =
      new ~>[AuthEffect, F]:
        def apply[A](fa: AuthEffect[A]): F[A] = fa.run(user)
    end natTransformAuthEffectToF

    Kleisli { (authedReq: AuthedRequest[F, AuthenticatedUser]) =>
      val (user, req) = (authedReq.context, authedReq.req)

      // Lift request body stream from F to AuthEffect
      val liftedReq: Request[AuthEffect] = req.mapK(Kleisli.liftK[F, AuthenticatedUser])

      // Run the Smithy router
      val result: OptionT[AuthEffect, Response[AuthEffect]] = smithyRoutes(liftedReq)

      // Lower the result back to F using the user
      // We interpret the Kleisli effect by supplying the 'user'
      val authToF = natTransformAuthEffectToF(user)

      result
        .mapK(authToF)        // Lower OptionT context
        .map(_.mapK(authToF)) // Lower Response body stream
    }
  end bridgeSmithyAndHttp4s

  private val loginRoutesService: LoginServices[F] =
    LoginServicesSmithyEp.create(jobHandler, serverState)
  end loginRoutesService

  private val roleServices: RoleServices[AuthEffect] =
    RoleServicesSmithyEp.create[F](jobHandler, epErrors)
  end roleServices

  private val nonAuthedRoutes: Resource[F, HttpRoutes[F]] =
    SimpleRestJsonBuilder
      .routes(loginRoutesService)
      .resource
  end nonAuthedRoutes

  private val authedRoutes: Resource[F, HttpRoutes[F]] =
    val routesOrError: Either[UnsupportedProtocolError, HttpRoutes[AuthEffect]] =
      SimpleRestJsonBuilder.routes(roleServices).make

    Resource
      .eval(async.fromEither(routesOrError))
      .map(routes => authMiddleware(bridgeSmithyAndHttp4s(routes)))
  end authedRoutes

  private val mkHttpApp: Resource[F, HttpApp[F]] =
    for {
      auth <- authedRoutes
      nonAuth <- nonAuthedRoutes
    } yield (nonAuth <+> auth).orNotFound
  end mkHttpApp

  private def logi(s: String): F[Unit] =
    U.logi(ThalesServer.FiberName, s)
  end logi

  private def loge(e: Throwable, uuid: String, s: String): F[Unit] =
    U.loge(e, ThalesServer.FiberName, uuid, s)
  end loge

  private def logi(uuid: String, s: String): F[Unit] =
    U.logi(ThalesServer.FiberName, uuid, s)
  end logi
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

  private val FiberName: String = "http4sFiber"

  private val AppName: String = "Thales Server API"

  private val AppVersion: String = "1.0"

  private val MainFiberName: String = "MainFiber"

  private val AppEnvs: Set[String] = Set("dev", "prod")

  private def getEnvVariableOpt[F[_]: Env as env]: F[Option[String]] = env.get("APP_ENV")

  private def readConfigFile[F[_]: Async as async](env: String): F[AppConfig] =
    async.fromEither(
      ConfigSource
        .resources(s"application-$env.conf")
        .withFallback(ConfigSource.resources("application.conf"))
        .at("app-config")
        .load[AppConfig]
        .left
        .map(pureconfig.error.ConfigReaderException[AppConfig]),
    )
  end readConfigFile

  private def createConfigResource[F[_]: { Async as async, Env as env, Logger }]: Resource[F, AppConfig] =
    val loadConfig = for {
      appEnvOpt <- getEnvVariableOpt[F]
      env = appEnvOpt.getOrElse("dev")
      _ <- (!AppEnvs.contains(env)).whenA(
        async.raiseError(AssertionError(s"Bad configuration environment: '$env'.")),
      )
      config <- readConfigFile[F](env)
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
        .withMaxHeaderSize(16384)
        .withShutdownTimeout(5.seconds)
        .withHttpApp(httpApp)
        .withTLS(tlsContext)
        .build
    }
  end createServerResource

  private def createHttpApp[F[_]: { Async, Logger }](deps: AppDependencies[F]): Resource[F, HttpApp[F]] =
    val dsl: Http4sDsl[F] = Http4sDsl[F]

    ThalesServer(deps, dsl).mkHttpApp
  end createHttpApp

  private def createServer[F[_]: { Async as async, Network, Logger }](
      appConfig: AppConfig,
      deps: AppDependencies[F],
  ): F[ExitCode] =
    val serverConnectionConfig = appConfig.getServerConnectionConfig
    val keyStoreFile = serverConnectionConfig.getKeystoreFile
    val keyStorePassword = serverConnectionConfig.getKeystorePassword
    val httpAppRes = createHttpApp[F](deps)

    getServerHostIPPort[F](serverConnectionConfig) >>= { (serverHostIP, serverHostPort) =>
      (for {
        httpApp <- httpAppRes
        server <- createServerResource(serverHostIP, serverHostPort, keyStoreFile, keyStorePassword, httpApp)
      } yield server)
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
    implicit val env: Env[F] = IO.envForIO

    createLogger[F] >>= { implicit logger =>
      val boRepoService: RepositoryService = RepositoryServiceLive.create

      val appDeps: Resource[F, (AppConfig, AppDependencies[F])] = for {
        appConfig <- createConfigResource[F]
        xa <- DoobieUtils.xaResource[F](appConfig.getDbConnectionConfig)
        permissions <- Permissions.create[F](boRepoService, xa)
        serverState <- Resource.eval[F, ServerState[F]](
          ServerStateLive.create[F](appConfig.getBackendServerConfig, permissions),
        )
        httpClient <- EmberClientBuilder.default[F].build.map(FollowRedirect[F](MaxHttpClientRedirects))
        supervisor <- Supervisor[F](await = false)
        uuidGen <- UUIDGenerator.create[F]
        uuidScope <- Resource.eval[F, TraceIdScope[F, Option[String]]](TraceIdScope.fromIOLocal[Option[String]](None))
      } yield {
        val externalApiClientService: ExternalApiClientService[F] = ExternalApiClientServiceLive.create[F](httpClient)
        val passwordHasherService: PasswordHasherService[F] = PasswordHasherServiceLive.create[F]
        val authService: AuthService[F] = AuthServiceLive.create[F](appConfig.getAuthConfig, boRepoService, xa)

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
            xa,
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
