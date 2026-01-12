package mechanoid.persistence.timeout

import java.time.Instant

/** A lease record for leader election.
  *
  * Leases implement distributed mutual exclusion. A node acquires a lease to become the leader and must renew it
  * periodically. If the node fails, the lease expires and another node can acquire it.
  *
  * ==Lease Lifecycle==
  *
  * {{{
  *   acquire     renew       renew       expire
  *      │         │           │           │
  *      ▼         ▼           ▼           ▼
  * [Available] → [Held by A] → [Held by A] → [Available]
  *                    │
  *              (node A dies)
  *                    │
  *                    ▼
  *              [Expires after leaseDuration]
  *                    │
  *                    ▼
  *              [Held by B] (node B acquires)
  * }}}
  *
  * @param key
  *   The lease key (namespace for leader election)
  * @param holder
  *   The node ID currently holding the lease
  * @param expiresAt
  *   When the lease expires if not renewed
  * @param acquiredAt
  *   When the lease was originally acquired
  */
final case class Lease(
    key: String,
    holder: String,
    expiresAt: Instant,
    acquiredAt: Instant,
):
  /** Check if this lease is currently valid. */
  def isValid(now: Instant): Boolean =
    expiresAt.isAfter(now)

  /** Check if this lease has expired. */
  def isExpired(now: Instant): Boolean =
    !isValid(now)

  /** Check if this lease is held by the given node. */
  def isHeldBy(nodeId: String): Boolean =
    holder == nodeId
end Lease
