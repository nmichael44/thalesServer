package app.serviceslive

import cats.effect.Async
import cats.effect.syntax.all.*

import scala.concurrent.duration.*
import scala.util.control.NoStackTrace

import app.services.ExternalApiClientService
import fs2.Stream
import org.http4s.{EntityDecoder, EntityEncoder, Headers, Method, Request, Response, Uri}
import org.http4s.client.Client

private final class ExternalApiClientServiceLive[F[_]: Async as async] private (
    client: Client[F],
) extends ExternalApiClientService[F]:

  override def fetchUri(uri: Uri, headers: Headers, timeout: Option[FiniteDuration]): F[String] =
    val request: Request[F] = Request[F](Method.GET, uri, headers = headers)
    run(request, timeout): response =>
      if response.status.isSuccess then response.bodyText.compile.string
      else fail("GET", uri, response)
  end fetchUri

  override def getAs[A](uri: Uri, headers: Headers, timeout: Option[FiniteDuration])(using EntityDecoder[F, A]): F[A] =
    val request: Request[F] = Request[F](Method.GET, uri, headers = headers)
    run(request, timeout): response =>
      if response.status.isSuccess then response.as[A]
      else fail("GET", uri, response)
  end getAs

  override def postAs[Req, Res](
      uri: Uri,
      body: Req,
      headers: Headers,
      timeout: Option[FiniteDuration],
  )(using EntityEncoder[F, Req], EntityDecoder[F, Res]): F[Res] =
    val request: Request[F] = Request[F](Method.POST, uri, headers = headers).withEntity(body)
    run(request, timeout): response =>
      if response.status.isSuccess then response.as[Res]
      else fail("POST", uri, response)
  end postAs

  override def run[A](request: Request[F], timeout: Option[FiniteDuration])(process: Response[F] => F[A]): F[A] =
    val action = client.run(request).use(process)
    timeout.fold(action)(t => action.timeout(t))
  end run

  override def streamUri(uri: Uri, headers: Headers, timeout: Option[FiniteDuration]): Stream[F, Byte] =
    val request = Request[F](Method.GET, uri, headers = headers)
    val stream = Stream
      .resource(client.run(request))
      .flatMap: response =>
        if response.status.isSuccess then response.body
        else Stream.eval(fail[Byte]("Stream GET", uri, response))
    timeout.fold(stream)(t => stream.timeout(t))
  end streamUri

  private def fail[A](method: String, uri: Uri, response: Response[F]): F[A] =
    async.raiseError(new RuntimeException(s"$method $uri failed with status: ${response.status}.") with NoStackTrace)
  end fail
end ExternalApiClientServiceLive

object ExternalApiClientServiceLive:
  def create[F[_]: Async](
      client: Client[F],
  ): ExternalApiClientService[F] =
    ExternalApiClientServiceLive[F](client)
  end create
end ExternalApiClientServiceLive
