package app.entrypoints

import app.model.JavaInstant
import io.circe.{Decoder, Encoder}
import sttp.tapir.Schema

object SmithyCodecs:
  given Encoder[JavaInstant.T] = Encoder.encodeInstant.contramap(_.value)
  given Decoder[JavaInstant.T] = Decoder.decodeInstant.map(JavaInstant(_))
  given Schema[JavaInstant.T] = Schema.schemaForInstant.map(i => Some(JavaInstant(i)))(_.value)
end SmithyCodecs
