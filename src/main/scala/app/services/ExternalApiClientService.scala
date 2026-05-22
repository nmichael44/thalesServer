package app.services

import scala.concurrent.duration.*

import fs2.Stream
import org.http4s.{EntityDecoder, EntityEncoder, Headers, Request, Response, Uri}

trait ExternalApiClientService[F[_]]:
  /**
   * Performs a simple GET request on the specified URI and compiles the response body into a raw String. Raises an error in F if the response status is not
   * successful.
   */
  def fetchUri(
      uri: Uri,
      headers: Headers,
      timeout: Option[FiniteDuration],
  ): F[String]

  /** Performs a GET request on the specified URI and automatically decodes the successful response body to type A using the implicit EntityDecoder. */
  def getAs[A](
      uri: Uri,
      headers: Headers,
      timeout: Option[FiniteDuration],
  )(using EntityDecoder[F, A]): F[A]

  /**
   * Performs a POST request on the specified URI, sending a body of type Req (encoded automatically) and decoding the successful response body to type Res
   * using the implicit EntityDecoder.
   */
  def postAs[Req, Res](
      uri: Uri,
      body: Req,
      headers: Headers,
      timeout: Option[FiniteDuration],
  )(using EntityEncoder[F, Req], EntityDecoder[F, Res]): F[Res]

  /**
   * General-purpose HTTP request executor. Runs the custom request and safely processes the response within a managed lifecycle block, ensuring connection
   * release.
   */
  def run[A](
      request: Request[F],
      timeout: Option[FiniteDuration],
  )(process: Response[F] => F[A]): F[A]

  /**
   * Performs a GET request on the specified URI and streams the response body as a memory-safe stream of bytes via FS2, cleanly closing the connection when the
   * stream completes or fails.
   */
  def streamUri(
      uri: Uri,
      headers: Headers,
      timeout: Option[FiniteDuration],
  ): Stream[F, Byte]
end ExternalApiClientService

object ExternalApiClientService:
  import cats.effect.Concurrent
  import cats.syntax.all.*

  import com.github.plokhotnyuk.jsoniter_scala.core.*
  import org.http4s.MediaType
  import org.http4s.headers.`Content-Type`

  object jsoniter:
    given jsoniterDecoder[F[_]: Concurrent, A](using codec: JsonValueCodec[A]): EntityDecoder[F, A] =
      EntityDecoder.decodeBy(MediaType.application.json): msg =>
        org.http4s.DecodeResult:
          msg.body.compile
            .to(Array)
            .map: bytes =>
              Either
                .catchNonFatal(readFromArray[A](bytes))
                .leftMap(t => org.http4s.InvalidMessageBodyFailure("Failed to parse JSON using jsoniter", Some(t)))

    given jsoniterEncoder[F[_], A](using codec: JsonValueCodec[A]): EntityEncoder[F, A] =
      EntityEncoder
        .byteArrayEncoder[F]
        .contramap[A](x => writeToArray[A](x))
        .withContentType(`Content-Type`(MediaType.application.json))
  end jsoniter
end ExternalApiClientService
