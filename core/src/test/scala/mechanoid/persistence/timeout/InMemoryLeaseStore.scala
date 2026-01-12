package mechanoid.persistence.timeout

import zio.*
import mechanoid.core.MechanoidError
import java.time.Instant
import scala.collection.mutable

/** In-memory implementation of [[LeaseStore]] for testing.
  *
  * Uses synchronization for thread safety. Not suitable for production - use a database-backed implementation instead.
  *
  * ==Usage==
  * {{{
  * val store = new InMemoryLeaseStore()
  * val storeLayer = ZLayer.succeed[LeaseStore](store)
  *
  * ZIO.scoped {
  *   // ... tests using the store
  * }.provide(storeLayer)
  * }}}
  */
class InMemoryLeaseStore extends LeaseStore:
  private val leases = mutable.Map[String, Lease]()

  def tryAcquire(
      key: String,
      holder: String,
      duration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, Option[Lease]] =
    ZIO.succeed {
      synchronized {
        leases.get(key) match
          // No existing lease - acquire
          case None =>
            val lease = Lease(
              key = key,
              holder = holder,
              expiresAt = now.plusMillis(duration.toMillis),
              acquiredAt = now,
            )
            leases(key) = lease
            Some(lease)

          // Existing lease expired - acquire
          case Some(existing) if existing.isExpired(now) =>
            val lease = Lease(
              key = key,
              holder = holder,
              expiresAt = now.plusMillis(duration.toMillis),
              acquiredAt = now,
            )
            leases(key) = lease
            Some(lease)

          // Re-acquire by same holder
          case Some(existing) if existing.holder == holder =>
            val lease = Lease(
              key = key,
              holder = holder,
              expiresAt = now.plusMillis(duration.toMillis),
              acquiredAt = now,
            )
            leases(key) = lease
            Some(lease)

          // Held by another node - cannot acquire
          case Some(_) =>
            None
      }
    }

  def renew(
      key: String,
      holder: String,
      duration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, Boolean] =
    ZIO.succeed {
      synchronized {
        leases.get(key) match
          case Some(existing) if existing.holder == holder =>
            leases(key) = existing.copy(
              expiresAt = now.plusMillis(duration.toMillis)
            )
            true
          case _ =>
            false
      }
    }

  def release(key: String, holder: String): ZIO[Any, MechanoidError, Boolean] =
    ZIO.succeed {
      synchronized {
        leases.get(key) match
          case Some(existing) if existing.holder == holder =>
            leases.remove(key)
            true
          case _ =>
            false
      }
    }

  def get(key: String): ZIO[Any, MechanoidError, Option[Lease]] =
    ZIO.succeed {
      synchronized {
        leases.get(key)
      }
    }

  // Test helpers

  /** Get all leases (for assertions). */
  def getAll: Map[String, Lease] =
    synchronized {
      leases.toMap
    }

  /** Clear all leases (for test isolation). */
  def clear(): Unit =
    synchronized {
      leases.clear()
    }

  /** Expire a lease immediately (for testing failover). */
  def expireLease(key: String): Unit =
    synchronized {
      leases.get(key).foreach { lease =>
        leases(key) = lease.copy(expiresAt = Instant.MIN)
      }
    }
end InMemoryLeaseStore
