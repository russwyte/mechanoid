package mechanoid.persistence.postgres

import saferis.*
import java.time.Instant

/** Row model for fsm_events table. */
@tableName("fsm_events")
final case class EventRow(
    @generated @key id: Long,
    @label("instance_id") instanceId: String,
    @label("sequence_nr") sequenceNr: Long,
    @label("event_data") eventData: String,
    @label("created_at") createdAt: Instant,
) derives Table

/** Row model for fsm_snapshots table. */
@tableName("fsm_snapshots")
final case class SnapshotRow(
    @key @label("instance_id") instanceId: String,
    @label("state_data") stateData: String,
    @label("sequence_nr") sequenceNr: Long,
    @label("created_at") createdAt: Instant,
) derives Table

/** Row model for scheduled_timeouts table. */
@tableName("scheduled_timeouts")
final case class TimeoutRow(
    @key @label("instance_id") instanceId: String,
    state: String,
    deadline: Instant,
    @label("created_at") createdAt: Instant,
    @label("claimed_by") claimedBy: Option[String],
    @label("claimed_until") claimedUntil: Option[Instant],
) derives Table

/** Row model for fsm_instance_locks table. */
@tableName("fsm_instance_locks")
final case class LockRow(
    @key @label("instance_id") instanceId: String,
    @label("node_id") nodeId: String,
    @label("acquired_at") acquiredAt: Instant,
    @label("expires_at") expiresAt: Instant,
) derives Table

/** Row model for leases table. */
@tableName("leases")
final case class LeaseRow(
    @key key: String,
    holder: String,
    @label("expires_at") expiresAt: Instant,
    @label("acquired_at") acquiredAt: Instant,
) derives Table

/** Row model for commands table. */
@tableName("commands")
final case class CommandRow(
    @generated @key id: Long,
    @label("instance_id") instanceId: String,
    @label("command_data") commandData: String,
    @label("idempotency_key") idempotencyKey: String,
    @label("enqueued_at") enqueuedAt: Instant,
    status: String,
    attempts: Int,
    @label("last_attempt_at") lastAttemptAt: Option[Instant],
    @label("last_error") lastError: Option[String],
    @label("next_retry_at") nextRetryAt: Option[Instant],
    @label("claimed_by") claimedBy: Option[String],
    @label("claimed_until") claimedUntil: Option[Instant],
) derives Table
