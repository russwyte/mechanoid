package mechanoid.persistence.timeout

import zio.*
import mechanoid.core.MechanoidError
import java.time.Instant
import scala.collection.mutable

/** In-memory implementation of [[TimeoutStore]] for testing.
  *
  * Uses synchronization for thread safety. Not suitable for production - use a database-backed implementation instead.
  *
  * ==Usage==
  * {{{
  * val store = new InMemoryTimeoutStore[String]()
  * val storeLayer = ZLayer.succeed[TimeoutStore[String]](store)
  *
  * ZIO.scoped {
  *   // ... tests using the store
  * }.provide(storeLayer)
  * }}}
  */
class InMemoryTimeoutStore[Id] extends TimeoutStore[Id]:
  private val timeouts = mutable.Map[Id, ScheduledTimeout[Id]]()

  def schedule(
      instanceId: Id,
      stateHash: Int,
      sequenceNr: Long,
      deadline: Instant,
  ): ZIO[Any, MechanoidError, ScheduledTimeout[Id]] =
    ZIO.succeed {
      synchronized {
        val timeout = ScheduledTimeout(
          instanceId = instanceId,
          stateHash = stateHash,
          sequenceNr = sequenceNr,
          deadline = deadline,
          createdAt = Instant.now(),
        )
        timeouts(instanceId) = timeout
        timeout
      }
    }

  def cancel(instanceId: Id): ZIO[Any, MechanoidError, Boolean] =
    ZIO.succeed {
      synchronized {
        timeouts.remove(instanceId).isDefined
      }
    }

  def queryExpired(
      limit: Int,
      now: Instant,
  ): ZIO[Any, MechanoidError, List[ScheduledTimeout[Id]]] =
    ZIO.succeed {
      synchronized {
        timeouts.values
          .filter(_.canBeClaimed(now))
          .toList
          .sortBy(_.deadline)
          .take(limit)
      }
    }

  def claim(
      instanceId: Id,
      nodeId: String,
      claimDuration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, ClaimResult] =
    ZIO.succeed {
      synchronized {
        timeouts.get(instanceId) match
          case None =>
            ClaimResult.NotFound

          case Some(t) if t.isClaimed(now) =>
            ClaimResult.AlreadyClaimed(t.claimedBy.get, t.claimedUntil.get)

          case Some(t) =>
            val claimed = t.copy(
              claimedBy = Some(nodeId),
              claimedUntil = Some(now.plusMillis(claimDuration.toMillis)),
            )
            timeouts(instanceId) = claimed
            ClaimResult.Claimed(claimed)
      }
    }

  def complete(instanceId: Id, sequenceNr: Long): ZIO[Any, MechanoidError, Boolean] =
    ZIO.succeed {
      synchronized {
        timeouts.get(instanceId) match
          case Some(t) if t.sequenceNr == sequenceNr =>
            timeouts.remove(instanceId)
            true
          case _ =>
            false
      }
    }

  def release(instanceId: Id): ZIO[Any, MechanoidError, Boolean] =
    ZIO.succeed {
      synchronized {
        timeouts.get(instanceId) match
          case Some(t) =>
            timeouts(instanceId) = t.copy(claimedBy = None, claimedUntil = None)
            true
          case None =>
            false
      }
    }

  def get(instanceId: Id): ZIO[Any, MechanoidError, Option[ScheduledTimeout[Id]]] =
    ZIO.succeed {
      synchronized {
        timeouts.get(instanceId)
      }
    }

  // Test helpers

  /** Get all timeouts (for assertions). */
  def getAll: Map[Id, ScheduledTimeout[Id]] =
    synchronized {
      timeouts.toMap
    }

  /** Clear all timeouts (for test isolation). */
  def clear(): Unit =
    synchronized {
      timeouts.clear()
    }

  /** Get the count of scheduled timeouts. */
  def size: Int =
    synchronized {
      timeouts.size
    }
end InMemoryTimeoutStore
