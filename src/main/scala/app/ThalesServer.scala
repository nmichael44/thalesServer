package app

import cats.data.{EitherT, Kleisli, NonEmptyVector, OptionT}
import cats.effect.*
import cats.effect.kernel.{Async, Resource}
import cats.effect.std.{Env, Supervisor}
import cats.syntax.all.*

import scala.concurrent.duration.*

import app.auth.Permissions.{CompiledPermissionAlgebra, Permission, PermissionAlgebra}
import app.entrypoints.WebServiceResult
import app.model.AppModel
import app.model.AppModel.AuthenticatedBoUser
import app.services.*
import app.serviceslive.*
import app.uuid.UUIDGenerator
import app.Config.AppConfig.*
import app.Database.DoobieUtils
import app.JobSpecs.{FetchBoUserByError, JobKind, JobResult, LoginError}
import app.JobSpecs.CreateBoUserError
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

  private val DeferredF: F[Deferred[F, Either[Throwable, JobResult]]] =
    Deferred[F, Either[Throwable, JobResult]]

  private val logFindingXRequestIdHeader: F[Unit] = logi("Finding XRequestId header.")
  private val logNotFound: F[Unit] = logi("... not found -- generating.")
  private val logFound: F[Unit] = logi("... found!")

  private def getUUIDForRequest(req: Request[F], uuidGen: UUIDGenerator[F]): F[String] =
    RequestHeaderUtils
      .getXRequestId(req)
      .fold(logNotFound *> uuidGen.generateUUIDAsString)(logFound.as)
  end getUUIDForRequest

  extension (user: AppModel.AuthenticatedBoUser)
    inline private def hasPermissions(jobPermissionAlgebra: CompiledPermissionAlgebra): Boolean =
      jobPermissionAlgebra.isSatisfiedBy(user.permissions)
    end hasPermissions

  private def reportUnauthorizedUser(
      user: AppModel.AuthenticatedBoUser,
      uuid: String,
      jobName: String,
  ): F[WebServiceResult.WsrKind] =
    val userId = user.userId

    logi(uuid, s"Authorization failure for user with id: $userId")
      .as(WebServiceResult.unauthorizedResult(s"User ($userId) is not authorized to execute job '$jobName'."))
  end reportUnauthorizedUser

  private def logSuccessOrFailure(outcome: Either[Throwable, JobResult], uuid: String): F[Unit] =
    outcome match {
      case Right(_) => logi(uuid, "Successful response.")
      case Left(e) => loge(e, uuid, "Failed with exception.")
    }
  end logSuccessOrFailure

  private def jobHandlerWithAuth[T <: JobResult](
      ctxReq: ContextRequest[F, AppModel.AuthenticatedBoUser],
      jobPermissionAlgebra: CompiledPermissionAlgebra,
      serverState: ServerState[F],
      uuidGen: UUIDGenerator[F],
      job: JobKind,
      f: T => WebServiceResult.WsrKind,
  ): F[WebServiceResult.WsrKind] =
    val req: Request[F] = ctxReq.req
    val authBoUser = ctxReq.context

    for {
      _ <- logFindingXRequestIdHeader
      uuid <- getUUIDForRequest(req, uuidGen)
      _ <- logi(uuid, "Processing request.")
      res <-
        if authBoUser.hasPermissions(jobPermissionAlgebra) then
          for {
            deferred <- DeferredF
            _ <- logi(uuid, "Permission validated. Request being queued.")
            _ <- serverState.jobQueue.offer(WorkerJob(job, deferred, uuid))
            _ <- logi(uuid, "Waiting for response.")
            outcome <- deferred.get // Wait for the answer
            _ <- logi(uuid, "Response received.")
            _ <- logSuccessOrFailure(outcome, uuid)
          } yield mkResponse(outcome, f)
        else reportUnauthorizedUser(authBoUser, uuid, job.shortName)
    } yield res
  end jobHandlerWithAuth

  private def mkResponse[T](
      resEither: Either[Throwable, JobResult],
      f: T => WebServiceResult.WsrKind,
  ): WebServiceResult.WsrKind =
    resEither.fold(_ => WebServiceResult.internalServerErrorResult(), jr => f(jr.asInstanceOf[T]))
  end mkResponse

  private def jobHandlerNoAuthF[T <: JobResult](
      req: Request[F],
      serverState: ServerState[F],
      uuidGen: UUIDGenerator[F],
      job: JobKind,
      f: T => F[WebServiceResult.WsrKind],
  ): F[WebServiceResult.WsrKind] = for {
    _ <- logFindingXRequestIdHeader
    uuid <- getUUIDForRequest(req, uuidGen)
    _ <- logi(uuid, "Processing request.")
    deferred <- DeferredF
    _ <- logi(uuid, "Request being queued.")
    _ <- serverState.jobQueue.offer(WorkerJob(job, deferred, uuid))
    _ <- logi(uuid, "Waiting for response.")
    outcome <- deferred.get // Wait for the answer
    _ <- logi(uuid, "Response received.")
    _ <- logSuccessOrFailure(outcome, uuid)
    res <- mkResponseF(outcome, f)
  } yield res
  end jobHandlerNoAuthF

  private def mkResponseF[T](
      resEither: Either[Throwable, JobResult],
      f: T => F[WebServiceResult.WsrKind],
  ): F[WebServiceResult.WsrKind] =
    resEither.fold(_ => WebServiceResult.internalServerErrorResult().pure, jr => f(jr.asInstanceOf[T]))
  end mkResponseF

  private def ensureOnlyAllowedParams(allowedParams: Set[String], req: Request[F]): Option[F[WebServiceResult.WsrKind]] =
    val providedParams = req.multiParams.keySet
    val extraParams = providedParams -- allowedParams

    Option.when(extraParams.nonEmpty)(
      WebServiceResult.badRequestResultF(s"Extra params found in quest: ${extraParams.mkString(", ")}."),
    )
  end ensureOnlyAllowedParams

  private val firstNameParam: String = "firstName"

  private object firstNameOptionalQueryParamDecoderMatcher extends OptionalQueryParamDecoderMatcher[String](firstNameParam)

  private val lastNameParam: String = "lastName"

  private object lastNameOptionalQueryParamDecoderMatcher extends OptionalQueryParamDecoderMatcher[String](lastNameParam)

  private val allowedParamsForGetDirectors: Set[String] = Set(firstNameParam, lastNameParam)

  private object titleQueryParamDecoderMatcher extends QueryParamDecoderMatcher[String]("title")

  private object yearQueryParamDecoderMatcher extends QueryParamDecoderMatcher[Int]("year")

  private given EntityDecoder[F, AppModel.BoUser] =
    jsonOf[F, AppModel.BoUser]

  private val CreateBoUserPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanCreateBoUsers).compile

  private def createBoUser(
      serverState: ServerState[F],
      ctxReq: ContextRequest[F, AppModel.AuthenticatedBoUser],
      uuidGen: UUIDGenerator[F],
  ): F[WebServiceResult.WsrKind] =
    ctxReq.req.as[AppModel.BoUser].attempt >>= {
      case Left(_) => WebServiceResult.badRequestResultF("Invalid request body")
      case Right(boUser) =>
        jobHandlerWithAuth[CreateBoUserResult](
          ctxReq,
          CreateBoUserPermissionsAlg,
          serverState,
          uuidGen,
          CreateBoUserRequest(boUser),
          { case CreateBoUserResult(res) =>
            res match {
              case Left(CreateBoUserError.InvalidParameters(invalidParams)) =>
                WebServiceResult.badRequestResult(s"Invalid parameters: $invalidParams]")
              case Left(CreateBoUserError.DuplicateLoginName(loginName)) =>
                WebServiceResult.conflictResult(s"The given loginName '$loginName' was already present in the database.")
              case Left(CreateBoUserError.BadPassword(errorList)) =>
                val errorStr = errorList.view.mkString("\"", "\", \"", "\"")
                WebServiceResult.badRequestResult(s"Invalid password. Errors: [$errorStr]")
              case Right(userId) =>
                WebServiceResult.okResult(Json.obj("userId" -> userId.asJson))
            }
          },
        )
    }
  end createBoUser

  private def createBoUser(
      serverState: ServerState[F],
      req: Request[F],
      uuidGen: UUIDGenerator[F],
  ): F[WebServiceResult.WsrKind] =
    req.as[AppModel.BoUser].attempt >>= {
      case Left(e) => WebServiceResult.badRequestResultF(s"Invalid request body: ${e.getMessage}")
      case Right(boUser) =>
        jobHandlerNoAuthF[CreateBoUserResult](
          req,
          serverState,
          uuidGen,
          CreateBoUserRequest(boUser),
          { case CreateBoUserResult(res) =>
            res match {
              case Left(CreateBoUserError.InvalidParameters(invalidParams)) =>
                WebServiceResult.badRequestResult(s"Invalid parameters: $invalidParams]").pure
              case Left(CreateBoUserError.DuplicateLoginName(loginName)) =>
                WebServiceResult.conflictResult(s"The given loginName '$loginName' was already present in the database.").pure
              case Left(CreateBoUserError.BadPassword(errorList)) =>
                val errorStr = errorList.view.mkString("\"", "\", \"", "\"")
                WebServiceResult.badRequestResult(s"Invalid password. Errors: [$errorStr]").pure
              case Right(userId) =>
                WebServiceResult.okResult(Json.obj("userId" -> userId.asJson)).pure
            }
          },
        )
    }
  end createBoUser

  private val FetchBoUserPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanFetchBoUsers).compile

  def fetchBoUserByLoginName(
      serverState: ServerState[F],
      ctxReq: ContextRequest[F, AppModel.AuthenticatedBoUser],
      uuidGen: UUIDGenerator[F],
      loginName: String,
  ): F[WebServiceResult.WsrKind] =
    jobHandlerWithAuth[FetchBoUserByLoginNameResult](
      ctxReq,
      FetchBoUserPermissionsAlg,
      serverState,
      uuidGen,
      FetchBoUserByLoginNameRequest(loginName),
      { case FetchBoUserByLoginNameResult(res) =>
        res match {
          case Left(FetchBoUserByError.UserNotFound()) =>
            WebServiceResult.notFoundResult(s"The given loginName '$loginName' was not found.")
          case Right(r) => WebServiceResult.okResult(r)
        }
      },
    )
  end fetchBoUserByLoginName

  private def fetchBoUserByUserId(
      serverState: ServerState[F],
      ctxReq: ContextRequest[F, AppModel.AuthenticatedBoUser],
      uuidGen: UUIDGenerator[F],
      userId: Long,
  ): F[WebServiceResult.WsrKind] =
    jobHandlerWithAuth[FetchBoUserByIdResult](
      ctxReq,
      FetchBoUserPermissionsAlg,
      serverState,
      uuidGen,
      FetchBoUserByIdRequest(userId),
      { case FetchBoUserByIdResult(res) =>
        res match {
          case Left(FetchBoUserByError.UserNotFound()) =>
            WebServiceResult.notFoundResult(s"The given userId '$userId' was not found.")
          case Right(boUserIdDb) => WebServiceResult.okResult(boUserIdDb)
        }
      },
    )
  end fetchBoUserByUserId

  private given EntityDecoder[F, NonEmptyVector[Long]] =
    jsonOf[F, NonEmptyVector[Long]]

  private def fetchMultipleBoUsersByUserId(
      serverState: ServerState[F],
      ctxReq: ContextRequest[F, AppModel.AuthenticatedBoUser],
      uuidGen: UUIDGenerator[F],
  ): F[WebServiceResult.WsrKind] =
    ctxReq.req.as[NonEmptyVector[Long]].attempt >>= {
      case Left(e) => WebServiceResult.badRequestResultF(s"Invalid request body: ${e.getMessage}")
      case Right(boUsers) =>
        jobHandlerWithAuth[FetchMultipleBoUsersByIdResult](
          ctxReq,
          FetchBoUserPermissionsAlg,
          serverState,
          uuidGen,
          FetchMultipleBoUsersByIdRequest(boUsers),
          { case FetchMultipleBoUsersByIdResult(res) => WebServiceResult.okResult(res) },
        )
    }
  end fetchMultipleBoUsersByUserId

  private val InvalidRequestBody: F[WebServiceResult.WsrKind] = WebServiceResult.badRequestResultF("Invalid request body")
  private val InvalidLoginNamePassword: WebServiceResult.WsrKind =
    WebServiceResult.unauthorizedResult("Invalid loginName/password specified.")
  private val InactiveUser: WebServiceResult.WsrKind = WebServiceResult.unauthorizedResult("Inactive User.")

  private given EntityDecoder[F, AppModel.LoginUserDetails] =
    jsonOf[F, AppModel.LoginUserDetails]

  private def loginRequest(
      serverState: ServerState[F],
      req: Request[F],
      uuidGen: UUIDGenerator[F],
  ): F[WebServiceResult.WsrKind] =
    req.as[AppModel.LoginUserDetails].attempt >>= {
      case Left(_) => InvalidRequestBody
      case Right(userDetails) =>
        jobHandlerNoAuthF[LoginResult](
          req,
          serverState,
          uuidGen,
          LoginRequest(userDetails),
          { case LoginResult(res) =>
            res match {
              case Left(LoginError.InvalidLoginPassword()) => InvalidLoginNamePassword.pure
              case Left(LoginError.UserNotEnabled(loginName)) => InactiveUser.pure
              case Right((userId, token)) =>
                for {
                  now <- TimeUtils.nowInstant
                  _ <- serverState.lastAccess.update(_ + (userId -> now))
                } yield WebServiceResult.okResult(Json.obj("token" -> token.asJson))
            }
          },
        )
    }
  end loginRequest

  private val FetchAllLiveSessionsPermissionsAlg: CompiledPermissionAlgebra =
    PermissionAlgebra.Has(Permission.CanSeeAllLiveSessions).compile

  private def fetchAllLiveSessions(
      serverState: ServerState[F],
      ctxReq: ContextRequest[F, AppModel.AuthenticatedBoUser],
      uuidGen: UUIDGenerator[F],
  ): F[WebServiceResult.WsrKind] =
    jobHandlerWithAuth[FetchAllLiveSessionsResult](
      ctxReq,
      FetchAllLiveSessionsPermissionsAlg,
      serverState,
      uuidGen,
      FetchAllLiveSessionsRequest(),
      { case FetchAllLiveSessionsResult(res) => WebServiceResult.okResult(res) },
    )
  end fetchAllLiveSessions

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

        now <- TimeUtils.nowInstant.lifte
        accessMap <- lastAccess.get.lifte
        _ <- accessMap.get(authUser.userId) match {
          case Some(lastSeen) =>
            if java.time.Duration.between(lastSeen, now).toSeconds < SessionTimeoutDurationInSeconds then
              lastAccess.update(_ + (authUser.userId -> now)).lifte
            else
              lastAccess.update(_ - authUser.userId).lifte *>
                EitherT.leftT[F, Unit]("Session has expired due to inactivity.")
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
      case req @ POST -> Root / "login" => loginRequest(serverState, req, uuidGen)
      case req @ POST -> Root / "createBoUser" =>
        createBoUser(serverState, req, uuidGen)
    }

//  private val createBoUser = app.entrypoints.CreateBoUser(deps.serverState, ).go

  private val authedRoutes: CtxReqToWsr[F] =
    val serverState = deps.serverState
    val uuidGen = deps.uuidGen
    {
      case ctxReq @ POST -> Root / "createBoUser" as _ =>
        createBoUser(deps.serverState, ctxReq, deps.uuidGen)
      case ctxReq @ GET -> Root / "fetchBoUserByLoginName" / loginName as _ =>
        fetchBoUserByLoginName(serverState, ctxReq, uuidGen, loginName)
      case ctxReq @ GET -> Root / "fetchBoUserByUserId" / LongVar(userId) as _ =>
        fetchBoUserByUserId(serverState, ctxReq, uuidGen, userId)
      case ctxReq @ POST -> Root / "fetchMultipleBoUsersByUserId" as _ =>
        fetchMultipleBoUsersByUserId(serverState, ctxReq, uuidGen)
      case ctxReq @ GET -> Root / "fetchAllLiveSessions" as _ =>
        fetchAllLiveSessions(serverState, ctxReq, uuidGen)
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
