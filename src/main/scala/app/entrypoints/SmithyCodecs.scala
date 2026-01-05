package app.entrypoints

import app.entrypoints.smithy.{HashedUserPassword, LoginName, UserId}
import app.model.JavaInstant
import io.circe.{Decoder, Encoder}
import sttp.tapir.Schema

object SmithyCodecs:
  given encoderJavaInstant: Encoder[JavaInstant.T] = Encoder.encodeInstant.contramap(_.value)
  given decoderJavaInstant: Decoder[JavaInstant.T] = Decoder.decodeInstant.map(JavaInstant(_))
  given schemaJavaInstant: Schema[JavaInstant.T] = Schema.schemaForInstant.map(i => Some(JavaInstant(i)))(_.value)

  given encoderUserId: Encoder[UserId.T] = Encoder.encodeLong.contramap(_.value)
  given decoderUserId: Decoder[UserId.T] = Decoder.decodeLong.map(UserId(_))
  given encoderLoginName: Encoder[LoginName.T] = Encoder.encodeString.contramap(_.value)
  given decoderLoginName: Decoder[LoginName.T] = Decoder.decodeString.map(LoginName(_))

  given encoderHashedUserPassword: Encoder[HashedUserPassword.T] = Encoder.encodeString.contramap(_.value)
  given decoderHashedUserPassword: Decoder[HashedUserPassword.T] = Decoder.decodeString.map(HashedUserPassword(_))
end SmithyCodecs
