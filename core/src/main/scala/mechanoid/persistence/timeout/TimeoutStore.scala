package mechanoid.persistence.timeout

import zio.*
import java.time.Instant
import mechanoid.core.MechanoidError

/** Abstract storage trait for durable timeouts in distributed environments.
  *
  * Implement this trait to persist timeout deadlines to your chosen database (PostgreSQL, DynamoDB, Cassandra, etc.).
  * Combined with a [[TimeoutSweeper]], this enables timeouts that survive node failures.
  *
  * ==Why Separate from EventStore?==
  *
  * This trait is intentionally separate from [[mechanoid.persistence.EventStore]]. Timeouts have different access
  * patterns:
  *
  *   - '''EventStore''': Append-only log, queried by instance ID
  *   - '''TimeoutStore''': Indexed by deadline, requires atomic claims, frequently updated
  *
  * ==Implementation Requirements==
  *
  * '''CRITICAL''': The [[claim]] method MUST implement atomic claim-or-fail semantics. Without this, multiple sweeper
  * nodes could fire the same timeout.
  *
  * ==Recommended Schema (PostgreSQL)==
  *
  * {{{
  * CREATE TABLE scheduled_timeouts (
  *   instance_id    TEXT PRIMARY KEY,
  *   state_hash     INT NOT NULL,
  *   sequence_nr    BIGINT NOT NULL,
  *   deadline       TIMESTAMPTZ NOT NULL,
  *   created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  *   claimed_by     TEXT,
  *   claimed_until  TIMESTAMPTZ
  * );
  *
  * -- Index for efficient expired timeout queries
  * CREATE INDEX idx_timeouts_deadline ON scheduled_timeouts (deadline)
  *   WHERE claimed_by IS NULL OR claimed_until < NOW();
  * }}}
  *
  * ==Example PostgreSQL Implementation==
  *
  * {{{
  * class PostgresTimeoutStore[Id](xa: Transactor[Task]) extends TimeoutStore[Id]:
  *
  *   def claim(instanceId: Id, nodeId: String, claimDuration: Duration, now: Instant) =
  *     sql"""
  *       UPDATE scheduled_timeouts
  *       SET claimed_by = $nodeId,
  *           claimed_until = ${now.plusMillis(claimDuration.toMillis)}
  *       WHERE instance_id = $instanceId
  *         AND (claimed_by IS NULL OR claimed_until < $now)
  *       RETURNING *
  *     """.query[ScheduledTimeout[Id]].option.transact(xa).map {
  *       case Some(t) => ClaimResult.Claimed(t)
  *       case None    => // Check if exists but claimed, or not found
  *         // ... determine appropriate ClaimResult
  *     }
  * }}}
  *
  * ==Coordination with EventStore==
  *
  * When the sweeper fires a timeout, it should use the normal FSM event path (via EventStore's `append` with optimistic
  * locking). This ensures:
  *
  *   - State transitions follow the FSM definition
  *   - Sequence numbers are maintained
  *   - Concurrent modifications are detected
  *
  * @tparam Id
  *   The FSM instance identifier type (e.g., UUID, String, Long)
  */
trait TimeoutStore[Id]:

  /** Schedule a timeout for an FSM instance.
    *
    * This is called when the FSM enters a state that has a timeout configured. If a timeout already exists for this
    * instance, it MUST be replaced (upsert). Only one active timeout per instance is supported.
    *
    * The `stateHash` and `sequenceNr` are used by the sweeper to validate that the FSM is still in the expected state
    * before firing the timeout. This prevents stale timeouts from firing after the FSM has transitioned or re-entered
    * the same state.
    *
    * @param instanceId
    *   The FSM instance identifier
    * @param stateHash
    *   Hash of the state the FSM should be in when this timeout fires
    * @param sequenceNr
    *   The sequence number when timeout was scheduled (generation counter)
    * @param deadline
    *   When the timeout should fire
    * @return
    *   The created/updated timeout record
    */
  def schedule(
      instanceId: Id,
      stateHash: Int,
      sequenceNr: Long,
      deadline: Instant,
  ): ZIO[Any, MechanoidError, ScheduledTimeout[Id]]

  /** Cancel a timeout for an FSM instance.
    *
    * Called when exiting a state with a timeout (either via event or timeout). No-op if no timeout exists - this is not
    * an error.
    *
    * @param instanceId
    *   The FSM instance identifier
    * @return
    *   true if a timeout was cancelled, false if none existed
    */
  def cancel(instanceId: Id): ZIO[Any, MechanoidError, Boolean]

  /** Query expired timeouts that are not currently claimed.
    *
    * Returns timeouts where:
    * {{{
    * deadline <= now AND (claimed_by IS NULL OR claimed_until < now)
    * }}}
    *
    * Results should be ordered by deadline (oldest first) to ensure fairness.
    *
    * '''Performance Note''': This query should use an index. See the recommended schema above for PostgreSQL partial
    * index example.
    *
    * @param limit
    *   Maximum number of timeouts to return (batch size)
    * @param now
    *   The current timestamp (passed explicitly for testability)
    * @return
    *   Expired, unclaimed timeouts ordered by deadline
    */
  def queryExpired(
      limit: Int,
      now: Instant,
  ): ZIO[Any, MechanoidError, List[ScheduledTimeout[Id]]]

  /** Atomically claim a timeout for processing.
    *
    * '''CRITICAL''': This MUST be atomic. Use optimistic locking or database-level atomicity (e.g.,
    * `UPDATE ... WHERE claimed_by IS NULL RETURNING *`).
    *
    * The claim grants exclusive processing rights for `claimDuration`. If the sweeper crashes, the claim expires and
    * another node can retry.
    *
    * ==Implementation Pattern (PostgreSQL)==
    * {{{
    * UPDATE scheduled_timeouts
    * SET claimed_by = $nodeId, claimed_until = $now + $claimDuration
    * WHERE instance_id = $instanceId
    *   AND (claimed_by IS NULL OR claimed_until < $now)
    * RETURNING *
    * }}}
    *
    * @param instanceId
    *   The FSM instance to claim
    * @param nodeId
    *   The claiming node's identifier
    * @param claimDuration
    *   How long to hold the claim
    * @param now
    *   The current timestamp
    * @return
    *   ClaimResult indicating success or failure reason
    */
  def claim(
      instanceId: Id,
      nodeId: String,
      claimDuration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, ClaimResult]

  /** Complete (remove) a timeout after successful processing.
    *
    * Called after the timeout event has been successfully fired.
    *
    * @param instanceId
    *   The FSM instance identifier
    * @return
    *   true if deleted, false if not found
    */
  def complete(instanceId: Id): ZIO[Any, MechanoidError, Boolean]

  /** Release a claim without completing.
    *
    * Called when timeout processing fails and should be retried by another node. Clears the `claimedBy` and
    * `claimedUntil` fields so the timeout can be re-claimed immediately.
    *
    * @param instanceId
    *   The FSM instance identifier
    * @return
    *   true if released, false if not found
    */
  def release(instanceId: Id): ZIO[Any, MechanoidError, Boolean]

  /** Get the current timeout for an instance (if any).
    *
    * Useful for debugging, testing, and diagnostics.
    *
    * @param instanceId
    *   The FSM instance identifier
    * @return
    *   The scheduled timeout if one exists
    */
  def get(instanceId: Id): ZIO[Any, MechanoidError, Option[ScheduledTimeout[Id]]]
end TimeoutStore
