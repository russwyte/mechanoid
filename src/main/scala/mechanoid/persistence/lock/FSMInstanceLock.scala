package mechanoid.persistence.lock

import zio.*
import java.time.Instant
import mechanoid.core.MechanoidError

/** Distributed lock for FSM instances to ensure exactly-once transition semantics.
  *
  * When multiple nodes might process events for the same FSM instance concurrently, use this lock to ensure mutual
  * exclusion. Only the node holding the lock can process events; others wait or fail fast.
  *
  * ==Why Use Instance Locking?==
  *
  * Without locking, concurrent event processing relies on optimistic locking (sequence numbers) which detects conflicts
  * ''after'' they happen. This leads to:
  *   - Wasted work (processing that gets rejected)
  *   - Retry overhead
  *   - Potential for subtle race conditions
  *
  * With instance locking, conflicts are ''prevented'' rather than detected.
  *
  * ==Lock Semantics==
  *
  *   - '''Mutual exclusion''': Only one node can hold the lock at a time
  *   - '''Lease-based''': Locks expire automatically (crash recovery)
  *   - '''Reentrant-safe''': Same node can extend its own lock
  *   - '''Fair ordering''': Not guaranteed (depends on implementation)
  *
  * ==Usage==
  *
  * The lock is typically used automatically by `PersistentFSMRuntime.withLocking`:
  *
  * {{{
  * val program = ZIO.scoped {
  *   for
  *     fsm <- PersistentFSMRuntime.withLocking(orderId, definition, Pending)
  *     _   <- fsm.send(Pay)  // Lock acquired automatically
  *   yield ()
  * }.provide(eventStoreLayer, lockLayer)
  * }}}
  *
  * Or use directly for fine-grained control:
  *
  * {{{
  * lock.withLock(orderId, nodeId, 30.seconds) {
  *   // Exclusive access to this FSM instance
  *   fsm.send(Pay)
  * }
  * }}}
  *
  * ==PostgreSQL Implementation==
  *
  * {{{
  * CREATE TABLE fsm_instance_locks (
  *   instance_id  TEXT PRIMARY KEY,
  *   node_id      TEXT NOT NULL,
  *   acquired_at  TIMESTAMPTZ NOT NULL,
  *   expires_at   TIMESTAMPTZ NOT NULL
  * );
  *
  * -- Acquire lock (atomic upsert if expired or same node)
  * INSERT INTO fsm_instance_locks (instance_id, node_id, acquired_at, expires_at)
  * VALUES ($1, $2, NOW(), NOW() + $3::interval)
  * ON CONFLICT (instance_id) DO UPDATE
  *   SET node_id = EXCLUDED.node_id,
  *       acquired_at = EXCLUDED.acquired_at,
  *       expires_at = EXCLUDED.expires_at
  *   WHERE fsm_instance_locks.expires_at < NOW()
  *      OR fsm_instance_locks.node_id = EXCLUDED.node_id
  * RETURNING *;
  * }}}
  *
  * @tparam Id
  *   The FSM instance identifier type
  */
trait FSMInstanceLock[Id]:

  /** Try to acquire the lock without waiting.
    *
    * @param instanceId
    *   The FSM instance to lock
    * @param nodeId
    *   The node requesting the lock
    * @param duration
    *   How long the lock should be held
    * @param now
    *   Current timestamp
    * @return
    *   Acquired with token, or Busy if held by another node
    */
  def tryAcquire(
      instanceId: Id,
      nodeId: String,
      duration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, LockResult[Id]]

  /** Acquire the lock, waiting if necessary.
    *
    * Polls for the lock until acquired or timeout. Uses exponential backoff to reduce contention.
    *
    * @param instanceId
    *   The FSM instance to lock
    * @param nodeId
    *   The node requesting the lock
    * @param duration
    *   How long the lock should be held once acquired
    * @param timeout
    *   Maximum time to wait for the lock
    * @return
    *   Acquired with token, or TimedOut if couldn't acquire in time
    */
  def acquire(
      instanceId: Id,
      nodeId: String,
      duration: Duration,
      timeout: Duration,
  ): ZIO[Any, MechanoidError, LockResult[Id]]

  /** Release a held lock.
    *
    * Should be called when done processing to allow other nodes to proceed. The lock will also expire automatically
    * after its duration.
    *
    * @param token
    *   The lock token from a successful acquire
    * @return
    *   true if released, false if lock was already released/expired
    */
  def release(token: LockToken[Id]): ZIO[Any, MechanoidError, Boolean]

  /** Extend the duration of a held lock.
    *
    * Use this for long-running operations to prevent expiration.
    *
    * @param token
    *   The current lock token
    * @param additionalDuration
    *   How much longer to hold the lock
    * @param now
    *   Current timestamp
    * @return
    *   New token with extended expiration, or None if lock was lost
    */
  def extend(
      token: LockToken[Id],
      additionalDuration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, Option[LockToken[Id]]]

  /** Get the current lock holder for an instance.
    *
    * @param instanceId
    *   The FSM instance to check
    * @param now
    *   Current timestamp (to filter expired locks)
    * @return
    *   The lock token if locked, None if available
    */
  def get(instanceId: Id, now: Instant): ZIO[Any, MechanoidError, Option[LockToken[Id]]]

  /** Execute an effect while holding the lock.
    *
    * This is the recommended way to use locking. The lock is automatically acquired before the effect and released
    * after (even on failure).
    *
    * {{{
    * lock.withLock(orderId, nodeId, 30.seconds) {
    *   fsm.send(Pay)
    * }
    * }}}
    *
    * @param instanceId
    *   The FSM instance to lock
    * @param nodeId
    *   The node requesting the lock
    * @param duration
    *   How long the lock should be held
    * @param timeout
    *   Maximum time to wait for the lock (default: same as duration)
    * @param effect
    *   The effect to execute while holding the lock
    * @return
    *   The effect result, or fails with LockError if couldn't acquire
    */
  def withLock[R, E, A](
      instanceId: Id,
      nodeId: String,
      duration: Duration,
      timeout: Option[Duration] = None,
  )(effect: ZIO[R, E, A]): ZIO[R, E | LockError, A] =
    ZIO.acquireReleaseWith(
      acquire(instanceId, nodeId, duration, timeout.getOrElse(duration))
        .flatMap {
          case LockResult.Acquired(token)     => ZIO.succeed(token)
          case LockResult.Busy(heldBy, until) =>
            ZIO.fail(LockError.LockBusy(instanceId.toString, heldBy, until))
          case LockResult.TimedOut() =>
            ZIO.fail(LockError.LockTimeout(instanceId.toString, timeout.getOrElse(duration)))
        }
        .mapError {
          case e: LockError => e
          case e: Throwable => LockError.LockAcquisitionFailed(instanceId.toString, e)
        }
    )(token => release(token).ignore)(_ => effect)
end FSMInstanceLock

object FSMInstanceLock:

  /** Default lock duration for automatic locking. */
  val DefaultLockDuration: Duration = Duration.fromSeconds(30)

  /** Default timeout for acquiring locks. */
  val DefaultLockTimeout: Duration = Duration.fromSeconds(10)
