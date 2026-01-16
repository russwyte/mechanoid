package mechanoid.persistence.timeout

import java.time.Instant

/** A persisted timeout record for durable timeout handling.
  *
  * Unlike in-memory fiber-based timeouts, `ScheduledTimeout` survives node failures by persisting the deadline to a
  * database. A background sweeper process queries for expired timeouts and fires them.
  *
  * ==Upsert Semantics==
  *
  * Only one timeout per FSM instance is active at a time. Scheduling a new timeout replaces any existing one. This
  * matches the FSM semantics where entering a new state cancels the previous state's timeout.
  *
  * ==State Validation==
  *
  * The `stateHash` and `sequenceNr` fields enable the sweeper to validate that the FSM is still in the expected state
  * before firing the timeout:
  *
  *   - '''stateHash''': Hash of the state the FSM should be in (via Finite typeclass)
  *   - '''sequenceNr''': The sequence number when the timeout was scheduled (acts as a generation counter)
  *
  * Both must match for the timeout to fire. This prevents stale timeouts from firing after the FSM has:
  *   - Transitioned to a different state (stateHash mismatch)
  *   - Re-entered the same state (sequenceNr mismatch - a new timeout was scheduled)
  *
  * ==Claim Mechanism==
  *
  * In distributed deployments, multiple sweeper nodes may discover the same expired timeout. The [[claimedBy]] and
  * [[claimedUntil]] fields implement distributed coordination:
  *
  *   - '''Unclaimed''': `claimedBy = None` - any sweeper can claim it
  *   - '''Claimed''': `claimedBy = Some(nodeId)` with `claimedUntil` in the future
  *   - '''Expired claim''': `claimedUntil` in the past - can be re-claimed
  *
  * This ensures exactly-once firing even with multiple sweepers, while allowing recovery if a sweeper crashes while
  * processing a timeout.
  *
  * @tparam Id
  *   The FSM instance identifier type
  * @param instanceId
  *   The FSM instance this timeout belongs to
  * @param stateHash
  *   Hash of the state the FSM should be in when this timeout fires
  * @param sequenceNr
  *   The sequence number when timeout was scheduled (generation counter for this visit to the state)
  * @param deadline
  *   When the timeout should fire
  * @param createdAt
  *   When this timeout was scheduled
  * @param claimedBy
  *   Optional node ID that has claimed this timeout for processing
  * @param claimedUntil
  *   When the claim expires (allows re-claiming on node failure)
  */
final case class ScheduledTimeout[Id](
    instanceId: Id,
    stateHash: Int,
    sequenceNr: Long,
    deadline: Instant,
    createdAt: Instant,
    claimedBy: Option[String] = None,
    claimedUntil: Option[Instant] = None,
):
  /** Check if this timeout is currently claimed by a node. */
  def isClaimed(now: Instant): Boolean =
    claimedBy.isDefined && claimedUntil.exists(_.isAfter(now))

  /** Check if this timeout has expired and is ready to fire. */
  def isExpired(now: Instant): Boolean =
    deadline.isBefore(now) || deadline == now

  /** Check if this timeout can be claimed (expired deadline and not currently claimed). */
  def canBeClaimed(now: Instant): Boolean =
    isExpired(now) && !isClaimed(now)
end ScheduledTimeout
