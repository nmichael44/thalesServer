package app

import cats.~>
import cats.data.{EitherT, Kleisli, OptionT}
import cats.effect.*
import cats.effect.implicits.*
import cats.effect.std.{Env, Supervisor}
import cats.syntax.all.*

import scala.concurrent.duration.*

import app.Config.AppConfig.*
import app.Database.DoobieUtils
import app.ThalesUtils.ExtensionMethodUtils.*
import app.ThalesUtils.GenUtils as U
import app.auth.Permissions
import app.entrypoints.{EntryPointErrors, JobHandler, LoginServicesSmithyEp, PermissionServicesSmithyEp, RenewTokenServicesSmithyEp, RoleServicesSmithyEp, UserServicesSmithyEp}
import app.entrypoints.smithy.{LoginServices, PermissionServices, RenewTokenServices, RoleServices, UserServices}
import app.entrypoints.smithy.Unauthenticated
import app.model.AppModel.AuthenticatedUser
import app.services.*
import app.serviceslive.*
import app.uuid.UUIDGenerator
import com.comcast.ip4s.{Ipv4Address, Port}
import fs2.compression.Compression
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
import org.http4s.headers.`Content-Type`
import org.http4s.headers.`WWW-Authenticate`
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import org.http4s.server.AuthMiddleware
import org.http4s.server.middleware.GZip
import org.typelevel.ci.CIString
import org.typelevel.log4cats.{Logger, LoggerName}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pureconfig.ConfigSource
import smithy4s.http4s.SimpleRestJsonBuilder
import smithy4s.http4s.swagger.docs
import smithy4s.json.Json

private final class ThalesServer[F[_]: { Async as async, Logger as logger }] private (
    deps: AppDependencies[F],
    dsl: Http4sDsl[F],
):
  private val epErrors: EntryPointErrors[F] = EntryPointErrors.create[F]
  private val serverState: ServerState[F] = deps.serverState
  private val authService: AuthService[F] = deps.authService
  private val clockService: ClockService[F] = deps.clockService

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
    import Unauthenticated.given

    val mediaJson = `Content-Type`(MediaType.application.json)

    // For idiotic reasons, the http standard calls Unauthorized what it should be calling
    // Unauthenticated.
    def unAuthenticatedError(challenge: `WWW-Authenticate`, errMsg: String): OptionT[F, Response[F]] =
      val payload = Json.writeBlob(Unauthenticated(errMsg))
      Unauthorized(challenge, payload.toArray).map(_.withContentType(mediaJson)).liftO
    end unAuthenticatedError

    def mkChallenge(errMsg: String): `WWW-Authenticate` =
      `WWW-Authenticate`(
        Challenge(
          scheme = "Bearer",
          realm = ThalesServer.AppName,
          params = Map("error" -> "invalid_token", "error_description" -> errMsg),
        ),
      )
    end mkChallenge

    val onFailure: AuthedRoutes[String, F] = Kleisli { req =>
      val errMsg = req.context
      val challenge: `WWW-Authenticate` = mkChallenge(errMsg)

      unAuthenticatedError(challenge, errMsg)
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

  private def getNonAuthedRoutes: Resource[F, HttpRoutes[F]] =
    val loginRoutesService: LoginServices[F] = LoginServicesSmithyEp.create(jobHandler, serverState, clockService, epErrors)

    val res = SimpleRestJsonBuilder
      .routes(loginRoutesService)
      .resource

    val docsRoutes = docs[F](
      LoginServices,
      RoleServices,
      PermissionServices,
      RenewTokenServices,
      UserServices,
    )

    res.map(_ <+> docsRoutes)
  end getNonAuthedRoutes

  private def getAuthedRoutes: Resource[F, HttpRoutes[F]] =
    val roleServices: RoleServices[AuthEffect] = RoleServicesSmithyEp.create[F](jobHandler, epErrors)
    val permissionServices: PermissionServices[AuthEffect] = PermissionServicesSmithyEp.create[F](jobHandler, epErrors)
    val renewTokenServices: RenewTokenServices[AuthEffect] = RenewTokenServicesSmithyEp.create[F](jobHandler, epErrors)
    val userServices: UserServices[AuthEffect] = UserServicesSmithyEp.create[F](jobHandler, epErrors)

    val routes = List(
      SimpleRestJsonBuilder.routes(roleServices),
      SimpleRestJsonBuilder.routes(permissionServices),
      SimpleRestJsonBuilder.routes(renewTokenServices),
      SimpleRestJsonBuilder.routes(userServices),
    )

    routes
      .traverse(r => r.make.liftTo[F])
      .map(_.foldK)
      .map(routes => authMiddleware(bridgeSmithyAndHttp4s(routes)))
      .toResource
  end getAuthedRoutes

  private def mkHttpApp: Resource[F, HttpApp[F]] =
    for {
      auth <- getAuthedRoutes
      nonAuth <- getNonAuthedRoutes
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
  private val appName: String = "thales-app"

  private def getServerHostIPPort[F[_]: Async](cfg: ServerConnectionConfig): F[(Ipv4Address, Port)] =
    (cfg.getHost, cfg.getPort)
      .bimap(
        host => Ipv4Address.fromString(host).liftTo[F](IllegalArgumentException(s"Illegal ServerHostIP: '$host'.")),
        port => Port.fromInt(port).liftTo[F](IllegalArgumentException(s"Illegal ServerHostPort: '$port'.")),
      )
      .tupled
  end getServerHostIPPort

  private val FiberName: String = "http4sFiber"

  private val AppName: String = "Thales Server API"

  private val AppVersion: String = "1.0"

  private val MainFiberName: String = "MainFiber"

  private val AppEnvs: Set[String] = Set("dev", "prod")

  private def getEnvVariableOpt[F[_]: Env as env]: F[Option[String]] = env.get("APP_ENV")

  private def readConfigFile[F[_]: Async as async](env: String): F[AppConfig] =
    ConfigSource
      .resources(s"application-$env.conf")
      .withFallback(ConfigSource.resources("application.conf"))
      .withFallback(ConfigSource.systemProperties)
      .at("app-config")
      .load[AppConfig]
      .left
      .map(pureconfig.error.ConfigReaderException[AppConfig])
      .liftTo[F]
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

    loadConfig.toResource
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

    TLSContext.Builder
      .forAsync[F]
      .fromKeyStoreFile(keyStoreFileNio, keyStorePasswordArray, keyStorePasswordArray)
      .toResource
      >>= { tlsContext =>
        EmberServerBuilder
          .default[F]
          .withHost(serverHostIP)
          .withPort(serverHostPort)
          .withHttp2
          .withMaxHeaderSize(16384)
          .withShutdownTimeout(5.seconds)
          .withHttpApp(httpApp)
          .withTLS(tlsContext)
          .build
      }
  end createServerResource

  private def createHttpApp[F[_]: { Async, Logger, Compression }](deps: AppDependencies[F]): Resource[F, HttpApp[F]] =
    val dsl: Http4sDsl[F] = Http4sDsl[F]

    ThalesServer(deps, dsl).mkHttpApp
      .map(httpApp => GZip(httpApp))
  end createHttpApp

  private def createServerResource[F[_]: { Async as async, Network, Logger, Compression }](
      appConfig: AppConfig,
      deps: AppDependencies[F],
  ): Resource[F, http4s.server.Server] =
    val serverConnectionConfig = appConfig.getServerConnectionConfig
    val keyStoreFile = serverConnectionConfig.getKeystoreFile
    val keyStorePassword = serverConnectionConfig.getKeystorePassword
    val httpAppRes = createHttpApp[F](deps)

    getServerHostIPPort[F](serverConnectionConfig).toResource >>= { (serverHostIP, serverHostPort) =>
      for {
        httpApp <- httpAppRes
        server <- createServerResource(serverHostIP, serverHostPort, keyStoreFile, keyStorePassword, httpApp)
      } yield server
    }
  end createServerResource

  // This is the number of redirects Ember will perform when a response
  // specifies that it needs a redirection.
  inline private val MaxHttpClientRedirects = 5

  def createLogger[F[_]: Async as async]: F[Logger[F]] =
    val thalesServerLoggerName = LoggerName("ThalesServerLogger")

    Slf4jLogger.create[F](using async, thalesServerLoggerName).widen[Logger[F]]
  end createLogger

  private def dependenciesResource[F[_]: { Async, Env, Network, Logger }]: Resource[F, (AppConfig, AppDependencies[F])] =
    val repoService: RepositoryService = RepositoryServiceLive.create

    for {
      appConfig <- createConfigResource[F]
      xa <- DoobieUtils.xaResource[F](appConfig.getDbConnectionConfig)
      _ <- Permissions.verifyPermissions(repoService, xa).toResource
      serverState <- ServerStateLive.create[F](appConfig.getBackendServerConfig).toResource
      httpClient <- EmberClientBuilder.default[F].build.map(FollowRedirect[F](MaxHttpClientRedirects))
      supervisor <- Supervisor[F](await = false)
      uuidGen <- UUIDGenerator.create[F]
      uuidScope <-
        Resource
          .eval(TraceIdScope.fromIOLocal[Option[String]](None))
          .asInstanceOf[Resource[F, TraceIdScope[F, Option[String]]]]
    } yield {
      val externalApiClientService = ExternalApiClientServiceLive.create[F](httpClient)
      val passwordHasherService = PasswordHasherServiceLive.create[F]
      val clockService = ClockServiceLive.create[F]
      val authService = AuthServiceLive.create[F](appName, appConfig.getAuthConfig, clockService, repoService, xa)

      val deps = AppDependencies(
        serverState,
        supervisor,
        uuidGen,
        uuidScope,
        externalApiClientService,
        repoService,
        passwordHasherService,
        authService,
        clockService,
        xa,
      )
      (appConfig, deps)
    }
  end dependenciesResource

  def applicationResource[F[_]: { Async, Env, Network, Compression, Logger }]
      : Resource[F, (http4s.server.Server, AppDependencies[F])] =
    for {
      (config, deps) <- dependenciesResource[F]
      _ <- ResetUserPasswordTokensWorker.create(deps).toResource
      _ <- HttpWorker.createWorkers[F](config, deps).toResource
      server <- createServerResource(config, deps)
    } yield (server, deps)
  end applicationResource

  def run: IO[ExitCode] =
    type F = IO

    implicit val async: Async[F] = IO.asyncForIO
    implicit val env: Env[F] = IO.envForIO
    implicit val compression: Compression[F] = Compression.forSync
    implicit val network: Network[F] = Network.forAsync[F]

    createLogger[F] >>= { implicit logger =>
      applicationResource[F]
        .use { (server, _) =>
          U.logi(MainFiberName, s"Server started with base uri: '${server.baseUri}'.") *>
            IO.never
        }
        .as(ExitCode.Success)
    }
  end run
end ThalesServer
