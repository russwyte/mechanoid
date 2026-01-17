package mechanoid.persistence.postgres

import saferis.*
import zio.*
import zio.json.*
import zio.stream.*
import mechanoid.core.*
import mechanoid.persistence.*
import java.time.Instant

/** PostgreSQL implementation of EventStore using Saferis.
  *
  * This implementation uses optimistic locking via sequence number checks to ensure exactly-once event persistence in
  * distributed environments.
  *
  * ==Usage==
  * {{{
  * // Define your types with Finite (JsonCodec is auto-derived)
  * enum MyState derives Finite:
  *   case Idle, Running, Done
  *
  * enum MyEvent derives Finite:
  *   case Started(id: String)
  *   case Completed
  *
  * // Create the store layer using the helper
  * val storeLayer = PostgresEventStore.layer[MyState, MyEvent]
  *
  * // Use with FSMRuntime
  * FSMRuntime(id, definition, initialState).provide(storeLayer, transactorLayer)
  * }}}
  */
class PostgresEventStore[S: JsonCodec, E: JsonCodec](transactor: Transactor) extends EventStore[String, S, E]:

  override def append(
      instanceId: String,
      event: E,
      expectedSeqNr: Long,
  ): ZIO[Any, MechanoidError, Long] =
    val eventJson = event.toJson
    // New sequence number is expectedSeqNr + 1
    // expectedSeqNr is the expected CURRENT highest sequence number
    val newSeqNr = expectedSeqNr + 1

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

          _ <- ZIO.when(currentSeq != expectedSeqNr) {
            ZIO.fail(SequenceConflictError(instanceId, expectedSeqNr, currentSeq))
          }

          // Insert new event with next sequence number
          _ <- sql"""
          INSERT INTO fsm_events (instance_id, sequence_nr, event_data, created_at)
          VALUES ($instanceId, $newSeqNr, $eventJson::jsonb, $now)
        """.dml
        yield newSeqNr
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

  override def loadEvents(instanceId: String): ZStream[Any, MechanoidError, StoredEvent[String, E]] =
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
  ): ZStream[Any, MechanoidError, StoredEvent[String, E]] =
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
            .fromEither(row.stateData.fromJson[S])
            .mapError(e => new RuntimeException(s"Failed to decode state: $e"))
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
    val stateJson = snapshot.state.toJson

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

  private def rowToStoredEvent(row: EventRow): ZIO[Any, Throwable, StoredEvent[String, E]] =
    ZIO
      .fromEither(row.eventData.fromJson[E])
      .mapError(e => new RuntimeException(s"Failed to decode event: $e"))
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

  /** Create a ZLayer for PostgresEventStore with the given state and event types.
    *
    * Usage:
    * {{{
    * val storeLayer = PostgresEventStore.makeLayer[MyState, MyEvent]
    * }}}
    */
  def makeLayer[S: JsonCodec: Tag, E: JsonCodec: Tag]: ZLayer[Transactor, Nothing, EventStore[String, S, E]] =
    ZLayer.fromFunction((xa: Transactor) => new PostgresEventStore[S, E](xa))

end PostgresEventStore
