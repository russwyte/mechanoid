package mechanoid.persistence.postgres

import saferis.*
import zio.*
import zio.json.*
import zio.stream.*
import mechanoid.core.{*, given}
import mechanoid.persistence.*
import java.time.Instant
import scala.annotation.unused

/** Codec for serializing/deserializing events and states to JSON.
  *
  * The simplest way to create one is using the companion object's `fromJson` method with zio-json codecs:
  *
  * {{{
  * enum MyState extends MState derives JsonCodec:
  *   case Idle, Running, Done
  *
  * enum MyEvent extends MEvent derives JsonCodec:
  *   case Started(id: String)
  *   case Completed
  *
  * val codec = EventCodec.fromJson[MyState, MyEvent]
  * }}}
  */
trait EventCodec[S <: MState, E <: MEvent]:
  def encodeEvent(event: E | Timeout.type): String
  def decodeEvent(json: String): Either[Throwable, E | Timeout.type]
  def encodeState(state: S): String
  def decodeState(json: String): Either[Throwable, S]

object EventCodec:
  /** Creates an EventCodec from zio-json codecs.
    *
    * Events are stored with a wrapper to handle the Timeout singleton:
    *   - Regular events: `{"event": <encoded event>}`
    *   - Timeout: `{"timeout": true}`
    */
  def fromJson[S <: MState, E <: MEvent](using
      stateCodec: JsonCodec[S],
      eventCodec: JsonCodec[E],
  ): EventCodec[S, E] = new EventCodec[S, E]:
    // Wrapper for events to distinguish Timeout from regular events
    private case class EventWrapper(event: Option[E], timeout: Option[Boolean])
    private object EventWrapper:
      given JsonEncoder[EventWrapper] = JsonEncoder.derived
      given JsonDecoder[EventWrapper] = JsonDecoder.derived

    def encodeEvent(event: E | Timeout.type): String = event match
      case Timeout         => """{"timeout":true}"""
      case e: E @unchecked => s"""{"event":${e.toJson}}"""

    def decodeEvent(json: String): Either[Throwable, E | Timeout.type] =
      json.fromJson[EventWrapper] match
        case Right(EventWrapper(_, Some(true))) => Right(Timeout)
        case Right(EventWrapper(Some(e), _))    => Right(e)
        case Right(_)                           => Left(new RuntimeException(s"Invalid event wrapper: $json"))
        case Left(err)                          => Left(new RuntimeException(err))

    def encodeState(state: S): String = state.toJson

    def decodeState(json: String): Either[Throwable, S] =
      json.fromJson[S].left.map(err => new RuntimeException(err))
end EventCodec

/** PostgreSQL implementation of EventStore using Saferis.
  *
  * This implementation uses optimistic locking via sequence number checks to ensure exactly-once event persistence in
  * distributed environments.
  *
  * @param transactor
  *   The Saferis transactor for database operations
  * @param codec
  *   Codec for serializing/deserializing events and states
  */
class PostgresEventStore[S <: MState, E <: MEvent](
    transactor: Transactor,
    codec: EventCodec[S, E],
) extends EventStore[String, S, E]:

  @unused private val events    = Table[EventRow]
  @unused private val snapshots = Table[SnapshotRow]

  override def append(
      instanceId: String,
      event: E | Timeout.type,
      expectedSeqNr: Long,
  ): ZIO[Any, MechanoidError, Long] =
    val eventJson = codec.encodeEvent(event)

    transactor
      .transact {
        for
          now <- Clock.instant
          // Check current sequence number (optimistic locking)
          currentSeqOpt <- sql"""
          SELECT COALESCE(MAX(sequence_nr), 0)
          FROM fsm_events
          WHERE instance_id = $instanceId
        """.queryValue[Long]

          currentSeq = currentSeqOpt.getOrElse(0L)

          _ <- ZIO.when(currentSeq >= expectedSeqNr) {
            ZIO.fail(SequenceConflictError(instanceId, expectedSeqNr, currentSeq))
          }

          // Insert new event
          _ <- sql"""
          INSERT INTO fsm_events (instance_id, sequence_nr, event_data, created_at)
          VALUES ($instanceId, $expectedSeqNr, $eventJson::jsonb, $now)
        """.dml
        yield expectedSeqNr
      }
      .catchSome {
        // Handle unique constraint violation from concurrent inserts
        case e: java.sql.SQLException if e.getSQLState == "23505" =>
          ZIO.fail(SequenceConflictError(instanceId, expectedSeqNr, expectedSeqNr))
      }
      .mapError {
        case e: MechanoidError => e
        case e: Throwable      => PersistenceError(e)
      }
  end append

  override def loadEvents(instanceId: String): ZStream[Any, MechanoidError, StoredEvent[String, E | Timeout.type]] =
    ZStream.fromIterableZIO {
      transactor
        .run {
          sql"""
          SELECT id, instance_id, sequence_nr, event_data, created_at
          FROM fsm_events
          WHERE instance_id = $instanceId
          ORDER BY sequence_nr ASC
        """.query[EventRow]
        }
        .flatMap { rows =>
          ZIO.foreach(rows)(rowToStoredEvent)
        }
        .mapError(PersistenceError(_))
    }

  override def loadEventsFrom(
      instanceId: String,
      fromSequenceNr: Long,
  ): ZStream[Any, MechanoidError, StoredEvent[String, E | Timeout.type]] =
    ZStream.fromIterableZIO {
      transactor
        .run {
          sql"""
          SELECT id, instance_id, sequence_nr, event_data, created_at
          FROM fsm_events
          WHERE instance_id = $instanceId
            AND sequence_nr > $fromSequenceNr
          ORDER BY sequence_nr ASC
        """.query[EventRow]
        }
        .flatMap { rows =>
          ZIO.foreach(rows)(rowToStoredEvent)
        }
        .mapError(PersistenceError(_))
    }

  override def loadSnapshot(instanceId: String): ZIO[Any, MechanoidError, Option[FSMSnapshot[String, S]]] =
    transactor
      .run {
        sql"""
        SELECT instance_id, state_data, sequence_nr, created_at
        FROM fsm_snapshots
        WHERE instance_id = $instanceId
      """.queryOne[SnapshotRow]
      }
      .flatMap {
        case None      => ZIO.none
        case Some(row) =>
          ZIO
            .fromEither(codec.decodeState(row.stateData))
            .mapError(e => new RuntimeException(s"Failed to decode state: ${e.getMessage}", e))
            .map { state =>
              Some(
                FSMSnapshot(
                  instanceId = row.instanceId,
                  state = state,
                  sequenceNr = row.sequenceNr,
                  timestamp = row.createdAt,
                )
              )
            }
      }
      .mapError(PersistenceError(_))

  override def saveSnapshot(snapshot: FSMSnapshot[String, S]): ZIO[Any, MechanoidError, Unit] =
    val stateJson = codec.encodeState(snapshot.state)

    transactor
      .run {
        sql"""
        INSERT INTO fsm_snapshots (instance_id, state_data, sequence_nr, created_at)
        VALUES (${snapshot.instanceId}, $stateJson::jsonb, ${snapshot.sequenceNr}, ${snapshot.timestamp})
        ON CONFLICT (instance_id) DO UPDATE SET
          state_data = EXCLUDED.state_data,
          sequence_nr = EXCLUDED.sequence_nr,
          created_at = EXCLUDED.created_at
      """.dml
      }
      .unit
      .mapError(PersistenceError(_))
  end saveSnapshot

  override def deleteEventsTo(instanceId: String, toSequenceNr: Long): ZIO[Any, MechanoidError, Unit] =
    transactor
      .run {
        sql"""
        DELETE FROM fsm_events
        WHERE instance_id = $instanceId
          AND sequence_nr <= $toSequenceNr
      """.dml
      }
      .unit
      .mapError(PersistenceError(_))

  override def highestSequenceNr(instanceId: String): ZIO[Any, MechanoidError, Long] =
    transactor
      .run {
        sql"""
        SELECT COALESCE(MAX(sequence_nr), 0)
        FROM fsm_events
        WHERE instance_id = $instanceId
      """.queryValue[Long]
      }
      .map(_.getOrElse(0L))
      .mapError(PersistenceError(_))

  private def rowToStoredEvent(row: EventRow): ZIO[Any, Throwable, StoredEvent[String, E | Timeout.type]] =
    ZIO
      .fromEither(codec.decodeEvent(row.eventData))
      .mapError(e => new RuntimeException(s"Failed to decode event: ${e.getMessage}", e))
      .map { event =>
        StoredEvent(
          instanceId = row.instanceId,
          sequenceNr = row.sequenceNr,
          event = event,
          timestamp = row.createdAt,
        )
      }
end PostgresEventStore

object PostgresEventStore:
  def layer[S <: MState: Tag, E <: MEvent: Tag](
      codec: EventCodec[S, E]
  ): ZLayer[Transactor, Nothing, EventStore[String, S, E]] =
    ZLayer.fromFunction((xa: Transactor) => new PostgresEventStore[S, E](xa, codec))
