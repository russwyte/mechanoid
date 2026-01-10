package mechanoid.examples.petstore

import zio.*
import zio.stream.*
import mechanoid.core.*
import mechanoid.persistence.*
import java.time.Instant
import scala.collection.mutable

// ============================================
// In-Memory Event Store (for testing/demos)
// ============================================

/** In-memory EventStore for testing. Thread-safe via synchronized blocks. */
class InMemoryEventStore[Id, S <: MState, E <: MEvent] extends EventStore[Id, S, E]:
  private val events    = mutable.Map[Id, mutable.ArrayBuffer[StoredEvent[Id, Timed[E]]]]()
  private val snapshots = mutable.Map[Id, FSMSnapshot[Id, S]]()
  private var seqNr     = 0L

  override def append(
      instanceId: Id,
      event: Timed[E],
      expectedSeqNr: Long,
  ): ZIO[Any, MechanoidError, Long] =
    ZIO.succeed {
      synchronized {
        seqNr += 1
        val stored = StoredEvent(instanceId, seqNr, event, Instant.now())
        events.getOrElseUpdate(instanceId, mutable.ArrayBuffer.empty) += stored
        seqNr
      }
    }

  override def loadEvents(
      instanceId: Id
  ): ZStream[Any, MechanoidError, StoredEvent[Id, Timed[E]]] =
    ZStream.fromIterable(events.getOrElse(instanceId, Seq.empty))

  override def loadSnapshot(
      instanceId: Id
  ): ZIO[Any, MechanoidError, Option[FSMSnapshot[Id, S]]] =
    ZIO.succeed(snapshots.get(instanceId))

  override def saveSnapshot(
      snapshot: FSMSnapshot[Id, S]
  ): ZIO[Any, MechanoidError, Unit] =
    ZIO.succeed {
      synchronized {
        snapshots(snapshot.instanceId) = snapshot
      }
    }

  override def highestSequenceNr(instanceId: Id): ZIO[Any, MechanoidError, Long] =
    ZIO.succeed {
      events
        .get(instanceId)
        .flatMap(_.lastOption.map(_.sequenceNr))
        .getOrElse(0L)
    }

  /** Get all events for an instance (for testing) */
  def getEvents(instanceId: Id): List[StoredEvent[Id, Timed[E]]] =
    synchronized { events.getOrElse(instanceId, Seq.empty).toList }

  /** Clear all data (for testing) */
  def clear(): Unit = synchronized {
    events.clear()
    snapshots.clear()
    seqNr = 0L
  }
end InMemoryEventStore
