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
  * Note: Types with `derives Finite` automatically get JsonCodec via the `finiteJsonCodec` given in
  * `mechanoid.postgres`. This is included automatically when using `import mechanoid.postgres.*`.
  *
  * Usage:
  * {{{
  * import mechanoid.*
  * import mechanoid.postgres.*  // brings in finiteJsonCodec
  *
  * // Your domain types with Finite derive JsonCodec automatically
  * enum MyEvent derives Finite:
  *   case Created(id: String)
  *   case Updated(value: Int)
  *
  * // JsonCodec is derived automatically from Finite when using postgres persistence
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
