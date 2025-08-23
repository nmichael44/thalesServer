package app

import cats.data.{EitherT, Kleisli, NonEmptyVector, OptionT}
import cats.effect.*
import cats.effect.kernel.{Async, Resource}
import cats.effect.std.{Env, Supervisor}
import cats.syntax.all.*

import scala.concurrent.duration.*

import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra}
import app.entrypoints.{JobHandler, WebServiceResult}
import app.model.AppModel
import app.model.AppModel.AuthenticatedBoUser
import app.services.*
import app.serviceslive.*
import app.uuid.UUIDGenerator
import app.Config.AppConfig.*
import app.Database.DoobieUtils
import app.JobSpecs.{JobKind, JobResult, LoginError}
import app.JobSpecs.JobKind.*
import app.JobSpecs.JobResult.*
import app.Renderer
import app.ThalesUtils.{GenUtils as U, RequestHeaderUtils, TimeUtils}
import app.ThalesUtils.ImplicitConversionUtils.*
import com.comcast.ip4s.{Ipv4Address, Port}
import fs2.io.net.tls.*
import fs2.io.net.Network
import io.circe.*
import io.circe.generic.auto.*
import io.circe.syntax.*
import org.http4s
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.middleware.FollowRedirect
import org.http4s.client.Client
import org.http4s.dsl.impl.OptionalQueryParamDecoderMatcher
import org.http4s.dsl.io.*
import org.http4s.dsl.Http4sDsl
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.headers.Authorization
import org.http4s.implicits.*
import org.http4s.server.{AuthMiddleware, Router}
import org.typelevel.ci.CIString
import org.typelevel.log4cats.{Logger, LoggerName}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pureconfig.ConfigSource

private final class ThalesServer[F[_]: { Async as async, Logger as logger }] private (
    deps: AppDependencies[F],
    dsl: Http4sDsl[F],
    renderer: Renderer[F],
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

  private def ensureOnlyAllowedParams(allowedParams: Set[String], req: Request[F]): Option[F[WebServiceResult.WsrKind]] =
    val providedParams = req.multiParams.keySet
    val extraParams = providedParams -- allowedParams

    Option.when(extraParams.nonEmpty)(
      WebServiceResult.badRequestResultF(s"Extra params found in quest: ${extraParams.mkString(", ")}."),
    )
  end ensureOnlyAllowedParams

  private given EntityDecoder[F, AppModel.BoUser] =
    jsonOf[F, AppModel.BoUser]

  private val createBoUserWithAuth: ContextRequest[F, AuthenticatedBoUser] => F[WebServiceResult.WsrKind] =
    entrypoints.CreateBoUserWithAuthEp(jobHandler).go

  private val createBoUserWithNoAuth: Request[F] => F[WebServiceResult.WsrKind] =
    entrypoints.CreateBoUserWithNoAuthEp(jobHandler).go

  private given EntityDecoder[F, NonEmptyVector[Long]] =
    jsonOf[F, NonEmptyVector[Long]]

  private val fetchBoUserByLoginName: (ContextRequest[F, AuthenticatedBoUser], String) => F[WebServiceResult.WsrKind] =
    entrypoints.FetchBoUserByLoginNameEp(jobHandler).go

  private val fetchBoUserByUserId: (ContextRequest[F, AuthenticatedBoUser], Long) => F[WebServiceResult.WsrKind] =
    entrypoints.FetchBoUserByUserIdEp(jobHandler).go

  private val fetchMultipleBoUsersByUserId: ContextRequest[F, AuthenticatedBoUser] => F[WebServiceResult.WsrKind] =
    entrypoints.FetchMultipleBoUsersByUserIdEp(jobHandler).go

  private val loginRequest: Request[F] => F[WebServiceResult.WsrKind] =
    entrypoints.LoginRequestEp(jobHandler, deps.serverState).go

  private given EntityDecoder[F, AppModel.LoginUserDetails] =
    jsonOf[F, AppModel.LoginUserDetails]

  private val fetchAllLiveSessions: ContextRequest[F, AuthenticatedBoUser] => F[WebServiceResult.WsrKind] =
    entrypoints.FetchAllLiveSessionsEp(jobHandler).go

  given CanEqual[CIString, CIString] = CanEqual.derived

  private val SessionTimeoutDurationInSeconds: Long = 1.hour.toSeconds

  private def createAuthMiddleware(authService: AuthService[F]): AuthMiddleware[F, AppModel.AuthenticatedBoUser] =
    import dsl.*

    val reqToAuthUser: Kleisli[F, Request[F], Either[String, AppModel.AuthenticatedBoUser]] = Kleisli { request =>
      val eitherToken: Either[String, String] =
        request.headers.get[Authorization] match {
          case Some(Authorization(Credentials.Token(AuthScheme.Bearer, token))) => Right(token)
          case _ => Left("Bearer token in Authorization header not found.")
        }

      val lastAccess = deps.serverState.lastAccess

      (for {
        _ <- logi("Checking token").lifte
        tokenStr <- eitherToken.toEitherT
        _ <- logi(s"Token found: $tokenStr").lifte
        authUser <- authService.validateToken(tokenStr).map(_.left.map(_.getMessage)).toEitherT
        _ <- logi(s"AuthUser: $authUser").lifte

        accessMap <- lastAccess.get.lifte
        _ <- accessMap.get(authUser.userId) match {
          case Some(lastSeen) =>
            TimeUtils.nowInstant.lifte >>= { now =>
              if java.time.Duration.between(lastSeen, now).toSeconds < SessionTimeoutDurationInSeconds then
                lastAccess.update(_ + (authUser.userId -> now)).lifte
              else
                lastAccess.update(_ - authUser.userId).lifte *>
                  EitherT.leftT[F, Unit]("Session has expired due to inactivity.")
            }
          case _ =>
            EitherT.leftT[F, Unit]("Session not found in in-memory cache.")
        }
      } yield authUser).value
    }

    val onFailure: AuthedRoutes[String, F] = Kleisli { (authedRequest: AuthedRequest[F, String]) =>
      val errorMsg = authedRequest.context
      val logAndRespond =
        logi(s"Authentication Failed: $errorMsg") *> Forbidden("Invalid token or session expired!")

      OptionT.liftF(logAndRespond)
    }

    AuthMiddleware(reqToAuthUser, onFailure)
  end createAuthMiddleware

  private type PF[T, R] = PartialFunction[T, R]
  private type ReqToWsr[G[_]] = PF[Request[G], G[WebServiceResult.WsrKind]]
  private type CtxReqToWsr[G[_]] = PF[ContextRequest[G, AppModel.AuthenticatedBoUser], G[WebServiceResult.WsrKind]]

  given CanEqual[Method, Method] = CanEqual.derived

  given CanEqual[Uri.Path, Uri.Path] = CanEqual.derived

  private val publicRoutes: ReqToWsr[F] =
    val serverState = deps.serverState
    val uuidGen = deps.uuidGen
    {
      case req @ POST -> Root / "login" => loginRequest(req)
      case req @ POST -> Root / "createBoUser" => createBoUserWithNoAuth(req)
    }

  private val authedRoutes: CtxReqToWsr[F] =
    val serverState = deps.serverState
    val uuidGen = deps.uuidGen
    {
      case ctxReq @ POST -> Root / "createBoUser" as _ => createBoUserWithAuth(ctxReq)
      case ctxReq @ GET -> Root / "fetchBoUserByLoginName" / loginName as _ => fetchBoUserByLoginName(ctxReq, loginName)
      case ctxReq @ GET -> Root / "fetchBoUserByUserId" / LongVar(userId) as _ => fetchBoUserByUserId(ctxReq, userId)
      case ctxReq @ POST -> Root / "fetchMultipleBoUsersByUserId" as _ => fetchMultipleBoUsersByUserId(ctxReq)
      case ctxReq @ GET -> Root / "fetchAllLiveSessions" as _ => fetchAllLiveSessions(ctxReq)
    }

  private val publicRoutesPath: (String, HttpRoutes[F]) =
    "/" -> HttpRoutes.of[F](publicRoutes.andThen(_ >>= renderer.apply))

  private val apiRoutesPath: (String, HttpRoutes[F]) =
    val authRoutes: AuthedRoutes[AppModel.AuthenticatedBoUser, F] =
      AuthedRoutes.of[AppModel.AuthenticatedBoUser, F](authedRoutes.andThen(_ >>= renderer.apply))

    "/api" -> createAuthMiddleware(deps.authService)(authRoutes)

  val allRoutes: HttpApp[F] =
    Router[F](publicRoutesPath, apiRoutesPath).orNotFound
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
    val renderer: Renderer[F] = Renderer(dsl)

    ThalesServer(deps, dsl, renderer).allRoutes
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
