package mechanoid.stores

import zio.*
import zio.stream.*
import mechanoid.core.*
import mechanoid.persistence.*

/** ZIO Ref-based in-memory EventStore implementation.
  *
  * Thread-safe via ZIO Refs. Suitable for testing and simple single-process deployments. For distributed deployments,
  * use a database-backed implementation like PostgresEventStore.
  *
  * ==Usage==
  * {{{
  * // Create directly
  * for
  *   store <- InMemoryEventStore.make[String, MyState, MyEvent]
  *   _     <- store.append("instance-1", event, 0L)
  * yield ()
  *
  * // Or as a ZLayer
  * val storeLayer = InMemoryEventStore.layer[String, MyState, MyEvent]
  * myProgram.provide(storeLayer)
  * }}}
  */
final class InMemoryEventStore[Id, S, E] private (
    eventsRef: Ref[Map[Id, Vector[StoredEvent[Id, E]]]],
    snapshotsRef: Ref[Map[Id, FSMSnapshot[Id, S]]],
    seqNrRef: Ref[Map[Id, Long]],
) extends EventStore[Id, S, E]:

  override def append(
      instanceId: Id,
      event: E,
      expectedSeqNr: Long,
  ): ZIO[Any, MechanoidError, Long] =
    for
      now   <- Clock.instant
      seqNr <- seqNrRef.modify { seqNrs =>
        val current  = seqNrs.getOrElse(instanceId, 0L)
        val newSeqNr = current + 1
        (newSeqNr, seqNrs + (instanceId -> newSeqNr))
      }
      stored = StoredEvent(instanceId, seqNr, event, now)
      _ <- eventsRef.update { events =>
        val instanceEvents = events.getOrElse(instanceId, Vector.empty)
        events + (instanceId -> (instanceEvents :+ stored))
      }
    yield seqNr

  override def loadEvents(
      instanceId: Id
  ): ZStream[Any, MechanoidError, StoredEvent[Id, E]] =
    ZStream.unwrap(
      eventsRef.get.map(events => ZStream.fromIterable(events.getOrElse(instanceId, Vector.empty)))
    )

  override def loadSnapshot(
      instanceId: Id
  ): ZIO[Any, MechanoidError, Option[FSMSnapshot[Id, S]]] =
    snapshotsRef.get.map(_.get(instanceId))

  override def saveSnapshot(
      snapshot: FSMSnapshot[Id, S]
  ): ZIO[Any, MechanoidError, Unit] =
    snapshotsRef.update(_ + (snapshot.instanceId -> snapshot))

  override def highestSequenceNr(instanceId: Id): ZIO[Any, MechanoidError, Long] =
    seqNrRef.get.map(_.getOrElse(instanceId, 0L))

  /** Get all events for an instance (for testing/debugging). */
  def getEvents(instanceId: Id): UIO[List[StoredEvent[Id, E]]] =
    eventsRef.get.map(_.getOrElse(instanceId, Vector.empty).toList)

  /** Clear all data (for testing). */
  def clear: UIO[Unit] =
    eventsRef.set(Map.empty) *> snapshotsRef.set(Map.empty) *> seqNrRef.set(Map.empty)
end InMemoryEventStore

object InMemoryEventStore:

  /** Create a new in-memory event store. */
  def make[Id, S, E]: UIO[InMemoryEventStore[Id, S, E]] =
    for
      eventsRef    <- Ref.make(Map.empty[Id, Vector[StoredEvent[Id, E]]])
      snapshotsRef <- Ref.make(Map.empty[Id, FSMSnapshot[Id, S]])
      seqNrRef     <- Ref.make(Map.empty[Id, Long])
    yield new InMemoryEventStore(eventsRef, snapshotsRef, seqNrRef)

end InMemoryEventStore
