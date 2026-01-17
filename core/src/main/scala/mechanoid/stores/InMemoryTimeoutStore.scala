package mechanoid.stores

import zio.*
import mechanoid.core.*
import mechanoid.persistence.timeout.*
import java.time.Instant

/** ZIO Ref-based in-memory TimeoutStore implementation.
  *
  * Thread-safe via ZIO Refs. Suitable for testing and simple single-process deployments. For distributed deployments,
  * use a database-backed implementation like PostgresTimeoutStore.
  *
  * ==Usage==
  * {{{
  * // Create directly
  * for
  *   store <- InMemoryTimeoutStore.make[String]
  *   _     <- store.schedule("instance-1", stateHash, sequenceNr, deadline)
  * yield ()
  *
  * // Or as a ZLayer
  * val storeLayer = InMemoryTimeoutStore.layer[String]
  * myProgram.provide(storeLayer)
  * }}}
  */
final class InMemoryTimeoutStore[Id] private (
    timeoutsRef: Ref[Map[Id, ScheduledTimeout[Id]]]
) extends TimeoutStore[Id]:

  override def schedule(
      instanceId: Id,
      stateHash: Int,
      sequenceNr: Long,
      deadline: Instant,
  ): ZIO[Any, MechanoidError, ScheduledTimeout[Id]] =
    for
      now <- Clock.instant
      timeout = ScheduledTimeout(
        instanceId = instanceId,
        stateHash = stateHash,
        sequenceNr = sequenceNr,
        deadline = deadline,
        createdAt = now,
      )
      _ <- timeoutsRef.update(_ + (instanceId -> timeout))
    yield timeout

  override def cancel(instanceId: Id): ZIO[Any, MechanoidError, Boolean] =
    timeoutsRef.modify { timeouts =>
      val existed = timeouts.contains(instanceId)
      (existed, timeouts - instanceId)
    }

  override def queryExpired(
      limit: Int,
      now: Instant,
  ): ZIO[Any, MechanoidError, List[ScheduledTimeout[Id]]] =
    timeoutsRef.get.map { timeouts =>
      timeouts.values
        .filter(_.canBeClaimed(now))
        .toList
        .sortBy(_.deadline)
        .take(limit)
    }

  override def claim(
      instanceId: Id,
      nodeId: String,
      claimDuration: Duration,
      now: Instant,
  ): ZIO[Any, MechanoidError, ClaimResult] =
    timeoutsRef.modify { timeouts =>
      timeouts.get(instanceId) match
        case None =>
          (ClaimResult.NotFound, timeouts)

        case Some(t) if t.isClaimed(now) =>
          (ClaimResult.AlreadyClaimed(t.claimedBy.get, t.claimedUntil.get), timeouts)

        case Some(t) =>
          val claimed = t.copy(
            claimedBy = Some(nodeId),
            claimedUntil = Some(now.plusMillis(claimDuration.toMillis)),
          )
          (ClaimResult.Claimed(claimed), timeouts + (instanceId -> claimed))
    }

  override def complete(instanceId: Id, sequenceNr: Long): ZIO[Any, MechanoidError, Boolean] =
    timeoutsRef.modify { timeouts =>
      timeouts.get(instanceId) match
        case Some(t) if t.sequenceNr == sequenceNr =>
          (true, timeouts - instanceId)
        case _ =>
          (false, timeouts)
    }

  override def release(instanceId: Id): ZIO[Any, MechanoidError, Boolean] =
    timeoutsRef.modify { timeouts =>
      timeouts.get(instanceId) match
        case Some(t) =>
          val released = t.copy(claimedBy = None, claimedUntil = None)
          (true, timeouts + (instanceId -> released))
        case None =>
          (false, timeouts)
    }

  override def get(instanceId: Id): ZIO[Any, MechanoidError, Option[ScheduledTimeout[Id]]] =
    timeoutsRef.get.map(_.get(instanceId))

  /** Get all timeouts (for testing). */
  def getAll: UIO[Map[Id, ScheduledTimeout[Id]]] =
    timeoutsRef.get

  /** Clear all data (for testing). */
  def clear: UIO[Unit] =
    timeoutsRef.set(Map.empty)

  /** Get the count of scheduled timeouts (for testing). */
  def size: UIO[Int] =
    timeoutsRef.get.map(_.size)
end InMemoryTimeoutStore

object InMemoryTimeoutStore:

  /** Create a new in-memory timeout store. */
  def make[Id]: UIO[InMemoryTimeoutStore[Id]] =
    Ref.make(Map.empty[Id, ScheduledTimeout[Id]]).map(new InMemoryTimeoutStore(_))
