package mechanoid.persistence

import zio.*
import zio.stream.*
import mechanoid.core.*
import scala.annotation.unused

/** Abstract storage trait for event sourcing with distributed safety guarantees.
  *
  * Implement this trait to persist FSM state using your chosen database (PostgreSQL, DynamoDB, Cassandra, etc.). The
  * implementation enables:
  *
  *   - '''Durability''': FSM state survives restarts via event replay
  *   - '''Horizontal scaling''': Multiple nodes can access the same FSM instance
  *   - '''Conflict detection''': Optimistic locking prevents lost updates
  *
  * ==Implementation Requirements==
  *
  * '''CRITICAL''': The [[append]] method MUST implement optimistic concurrency control. Without this, concurrent
  * modifications from multiple nodes will cause data corruption.
  *
  * ==Snapshotting Strategy==
  *
  * Event lists can grow unboundedly. Snapshotting avoids replaying the full history on startup. The runtime calls
  * [[loadSnapshot]] on startup and only replays events after the snapshot's sequence number via [[loadEventsFrom]].
  *
  * '''Snapshotting is the caller's responsibility.''' The runtime exposes `saveSnapshot` but does NOT call it
  * automatically. You control when to snapshot based on your needs:
  *
  * {{{
  * // Example: Snapshot every 100 events
  * for
  *   _     <- fsm.send(event)
  *   seqNr <- fsm.lastSequenceNr
  *   _     <- ZIO.when(seqNr % 100 == 0)(fsm.saveSnapshot)
  * yield ()
  *
  * // Example: Snapshot on specific state transitions (use entry actions)
  * // In your FSM definition:
  * // .onState(CompletedState).onEntry(fsm.saveSnapshot).done
  *
  * // Example: Periodic snapshots with ZIO Schedule
  * fsm.saveSnapshot
  *   .repeat(Schedule.fixed(5.minutes))
  *   .forkDaemon
  * }}}
  *
  * '''Optional cleanup''': After snapshotting, you may delete old events to reclaim storage using [[deleteEventsTo]].
  * This is optional - keeping events allows full audit trails.
  *
  * ==Schema Evolution==
  *
  * When your FSM definition changes over time (adding/removing states or events, changing transitions), be aware of
  * implications for event replay:
  *
  * '''Safe changes:'''
  *   - Adding new states (old events won't transition to them)
  *   - Adding new events (old events unaffected)
  *   - Adding new transitions (old events won't trigger them)
  *
  * '''Potentially unsafe changes:'''
  *   - Removing states: Old events may reference them during replay
  *   - Removing events: Old events of that type exist in the log
  *   - Changing transition targets: Replay may produce different state than original
  *
  * '''Current behavior:''' During replay, events that don't match the current FSM definition cause an
  * [[EventReplayError]]. You must handle this explicitly:
  *
  * {{{
  * FSMRuntime(id, definition, initialState)
  *   .catchSome { case e: EventReplayError =>
  *     // Option 1: Log and skip (dangerous but sometimes needed)
  *     ZIO.logWarning(s"Skipping unrecognized event: ${e.event}") *>
  *       fallbackRecovery(id)
  *
  *     // Option 2: Fail fast (safest)
  *     ZIO.fail(e)
  *   }
  *   .provide(storeLayer)
  * }}}
  *
  * '''Recommended migration strategy:'''
  * {{{
  * // 1. Take a snapshot with the OLD FSM definition
  * fsm.saveSnapshot
  *
  * // 2. Deploy the NEW FSM definition
  * // 3. New instances will load from snapshot + replay only recent events
  * // 4. Optionally delete old events after confirming stability
  * store.deleteEventsTo(id, snapshotSeqNr)
  * }}}
  *
  * For complex migrations, consider implementing event upcasting in your [[EventStore]] implementation by transforming
  * events during [[loadEvents]].
  *
  * ==ZLayer Integration==
  *
  * For efficient resource management, create your EventStore as a ZLayer:
  *
  * {{{
  * // Your implementation with managed resources
  * class PostgresEventStore[Id, S, E](
  *     pool: HikariTransactor[Task]
  * ) extends EventStore[Id, S, E]:
  *   // ... implementation
  *
  * object PostgresEventStore:
  *   def layer[Id: Tag, S: Tag, E: Tag]
  *       : ZLayer[HikariTransactor[Task], Nothing, EventStore[Id, S, E]] =
  *     ZLayer.fromFunction(new PostgresEventStore[Id, S, E](_))
  *
  * // Usage - store is created once and shared
  * val program = ZIO.scoped {
  *   for
  *     fsm <- FSMRuntime(orderId, definition, Pending)
  *     _   <- fsm.send(Pay)
  *   yield ()
  * }.provide(
  *   PostgresEventStore.layer[OrderId, OrderState, OrderEvent],
  *   HikariTransactor.live,
  *   // ... other layers
  * )
  * }}}
  *
  * ==Example PostgreSQL Implementation==
  * {{{
  * class PostgresEventStore[Id, S, E](
  *     xa: Transactor[Task]
  * ) extends EventStore[Id, S, E]:
  *
  *   def append(id: Id, event: E, expectedSeqNr: Long) =
  *     sql"""
  *       INSERT INTO events (instance_id, seq_nr, event_data, timestamp)
  *       SELECT $$id, COALESCE(MAX(seq_nr), 0) + 1, $$event, NOW()
  *       FROM events
  *       WHERE instance_id = $$id
  *       HAVING COALESCE(MAX(seq_nr), 0) = $$expectedSeqNr
  *       RETURNING seq_nr
  *     """.query[Long].option.transact(xa).flatMap {
  *       case Some(seqNr) => ZIO.succeed(seqNr)
  *       case None =>
  *         highestSequenceNr(id).flatMap { actual =>
  *           ZIO.fail(SequenceConflictError(id.toString, expectedSeqNr, actual))
  *         }
  *     }
  * }}}
  *
  * ==Handling Conflicts==
  *
  * When a `SequenceConflictError` occurs, the caller should typically:
  * {{{
  * fsm.send(event)
  *   .retry(Schedule.recurs(3) && Schedule.spaced(100.millis))
  *   .catchSome { case _: SequenceConflictError =>
  *     // Reload state and retry, or fail gracefully
  *   }
  * }}}
  *
  * @tparam Id
  *   The FSM instance identifier type (e.g., UUID, String, Long)
  * @tparam S
  *   The state type (must extend MState)
  * @tparam E
  *   The event type (must extend MEvent)
  */
trait EventStore[Id, S, E]:

  /** Append an event with optimistic concurrency control.
    *
    * '''CRITICAL REQUIREMENTS''' - implementations MUST:
    *
    *   1. '''Atomically check''' that the current highest sequence number for this instance equals `expectedSeqNr`. If
    *      not, fail with [[SequenceConflictError]].
    *   2. '''Atomically assign''' a new sequence number (expectedSeqNr + 1) and persist the event. These must be atomic
    *      to prevent race conditions.
    *   3. '''Return''' the newly assigned sequence number on success.
    *
    * '''Why This Matters''':
    *
    * Without optimistic locking, two nodes could simultaneously read seqNr=5, both try to write seqNr=6, and one write
    * would be lost. The `expectedSeqNr` parameter ensures only one succeeds.
    *
    * '''Failure Semantics''':
    *
    *   - On conflict: Fail with `SequenceConflictError(instanceId, expectedSeqNr, actualSeqNr)`
    *   - On DB error: Fail with the underlying exception
    *   - On success: Return the new sequence number
    *
    * '''Example SQL Pattern''' (PostgreSQL):
    * {{{
    * INSERT INTO events (instance_id, seq_nr, event, timestamp)
    * SELECT $id, seq_nr + 1, $event, NOW()
    * FROM (SELECT COALESCE(MAX(seq_nr), 0) AS seq_nr FROM events WHERE instance_id = $id) s
    * WHERE s.seq_nr = $expectedSeqNr
    * RETURNING seq_nr
    * -- If 0 rows inserted, a conflict occurred
    * }}}
    *
    * @param instanceId
    *   The FSM instance identifier
    * @param event
    *   The event to persist
    * @param expectedSeqNr
    *   The sequence number we expect to be current (0 for new instances)
    * @return
    *   The newly assigned sequence number, or fails with SequenceConflictError
    */
  def append(
      instanceId: Id,
      event: E,
      expectedSeqNr: Long,
  ): ZIO[Any, MechanoidError, Long]

  /** Load all events for an FSM instance in sequence order.
    *
    * Events should be returned in ascending sequence number order.
    *
    * @param instanceId
    *   The FSM instance identifier
    * @return
    *   Stream of stored events
    */
  def loadEvents(instanceId: Id): ZStream[Any, MechanoidError, StoredEvent[Id, E]]

  /** Load events starting from a specific sequence number.
    *
    * Used for incremental loading after a snapshot.
    *
    * @param instanceId
    *   The FSM instance identifier
    * @param fromSequenceNr
    *   Load events with sequence number > this value
    * @return
    *   Stream of stored events after the given sequence
    */
  def loadEventsFrom(
      instanceId: Id,
      fromSequenceNr: Long,
  ): ZStream[Any, MechanoidError, StoredEvent[Id, E]] =
    loadEvents(instanceId).filter(_.sequenceNr > fromSequenceNr)

  /** Get the latest snapshot for an FSM instance.
    *
    * Snapshots are optional optimization. Return None if snapshots are not supported or no snapshot exists.
    *
    * @param instanceId
    *   The FSM instance identifier
    * @return
    *   The latest snapshot if available
    */
  def loadSnapshot(instanceId: Id): ZIO[Any, MechanoidError, Option[FSMSnapshot[Id, S]]]

  /** Save a state snapshot.
    *
    * Snapshots allow faster recovery by avoiding full event replay. Implementations may choose to keep only the latest
    * snapshot or maintain a history.
    *
    * @param snapshot
    *   The snapshot to persist
    */
  def saveSnapshot(snapshot: FSMSnapshot[Id, S]): ZIO[Any, MechanoidError, Unit]

  /** Delete events up to a sequence number (optional cleanup).
    *
    * Used after taking a snapshot to reclaim storage. Default implementation does nothing.
    *
    * @param instanceId
    *   The FSM instance identifier
    * @param toSequenceNr
    *   Delete events with sequence number <= this value
    */
  def deleteEventsTo(@unused instanceId: Id, @unused toSequenceNr: Long): ZIO[Any, MechanoidError, Unit] =
    ZIO.unit

  /** Get the highest sequence number for an FSM instance.
    *
    * Returns 0 if no events exist.
    *
    * @param instanceId
    *   The FSM instance identifier
    * @return
    *   The highest sequence number
    */
  def highestSequenceNr(instanceId: Id): ZIO[Any, MechanoidError, Long]

  /** Get the current state without loading the full FSM runtime.
    *
    * Useful for quick lookups and queries (e.g., REST API endpoints). Default implementation reads from the latest
    * snapshot.
    *
    * For PostgreSQL, you might override this to query a dedicated current_state table or the snapshots table directly
    * for performance.
    *
    * @param instanceId
    *   The FSM instance identifier
    * @return
    *   The current state if the instance exists, None otherwise
    */
  def currentState(instanceId: Id): ZIO[Any, MechanoidError, Option[S]] =
    loadSnapshot(instanceId).map(_.map(_.state))
end EventStore
