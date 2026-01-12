package mechanoid.persistence.postgres

import saferis.*
import zio.*
import zio.json.*
import java.sql.SQLException

/** Provides integration between zio-json codecs and Saferis database codecs.
  *
  * Users provide zio-json JsonEncoder/JsonDecoder for their domain types, and these helpers create the necessary
  * Saferis Encoder/Decoder instances for transparent JSON serialization to/from JSONB columns.
  *
  * Usage:
  * {{{
  * import mechanoid.persistence.postgres.JsonCodecs.given
  *
  * // Your domain types with zio-json codecs
  * enum MyEvent derives JsonCodec:
  *   case Created(id: String)
  *   case Updated(value: Int)
  *
  * // Now Saferis can automatically encode/decode MyEvent to/from JSONB
  * }}}
  */
object JsonCodecs:

  /** Creates a Saferis Codec from a zio-json JsonCodec. Use this for SQL interpolation parameters and result set
    * decoding.
    */
  def jsonCodec[T](using codec: zio.json.JsonCodec[T]): Codec[T] =
    Codec.string.transform(s => ZIO.fromEither(s.fromJson[T]).mapError(e => new SQLException(e)))(t =>
      ZIO.succeed(t.toJson)
    )

  /** Creates a Saferis Decoder from a zio-json JsonDecoder. Use this for reading from result sets.
    */
  def jsonDecoder[T](using decoder: JsonDecoder[T]): Decoder[T] =
    Decoder.string.transform(s => ZIO.fromEither(s.fromJson[T]).mapError(e => new SQLException(e)))

  /** Creates a Saferis Encoder from a zio-json JsonEncoder. Use this for writing to parameters.
    */
  def jsonEncoder[T](using encoder: JsonEncoder[T]): Encoder[T] =
    Encoder.string.transform[T](t => ZIO.succeed(t.toJson))
end JsonCodecs
