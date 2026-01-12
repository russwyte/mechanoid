package mechanoid.persistence.lock

import java.time.Instant

/** A token representing a successfully acquired lock on an FSM instance.
  *
  * Lock tokens are proof of ownership and must be used to release the lock. They contain expiration information for
  * automatic cleanup if the holder crashes.
  *
  * @param instanceId
  *   The FSM instance that is locked
  * @param nodeId
  *   The node that holds the lock
  * @param acquiredAt
  *   When the lock was acquired
  * @param expiresAt
  *   When the lock will automatically expire
  */
final case class LockToken[Id](
    instanceId: Id,
    nodeId: String,
    acquiredAt: Instant,
    expiresAt: Instant,
):
  /** Check if the lock is still valid (not expired). */
  def isValid(now: Instant): Boolean = now.isBefore(expiresAt)

  /** Check if the lock has expired. */
  def isExpired(now: Instant): Boolean = !isValid(now)

  /** Duration until expiration. */
  def remainingTime(now: Instant): java.time.Duration =
    java.time.Duration.between(now, expiresAt)
end LockToken
