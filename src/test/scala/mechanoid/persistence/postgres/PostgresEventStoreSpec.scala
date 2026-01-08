package mechanoid.persistence.postgres

import saferis.*
import zio.*
import zio.json.*
import zio.test.*
import zio.test.Assertion.*
import mechanoid.PostgresTestContainer
import mechanoid.core.*
import mechanoid.persistence.*
import java.time.Instant

object PostgresEventStoreSpec extends ZIOSpecDefault:

  // Test state and event types with zio-json codecs
  enum TestState extends MState derives JsonCodec:
    case Initial
    case Processing
    case Completed
    case Failed

  enum TestEvent extends MEvent derives JsonCodec:
    case Started(id: String)
    case Processed(result: String)
    case Finished
    case Error(message: String)

  // Create codec using the factory method - much simpler!
  val testCodec = EventCodec.fromJson[TestState, TestEvent]

  val xaLayer = PostgresTestContainer.DataSourceProvider.default >>> Transactor.default
  val storeLayer = xaLayer >>> PostgresEventStore.layer[TestState, TestEvent](testCodec)

  def spec = suite("PostgresEventStore")(
    test("append persists an event with correct sequence number") {
      for
        store <- ZIO.service[EventStore[String, TestState, TestEvent]]
        seqNr <- store.append("event-test-1", TestEvent.Started("123"), 1)
      yield assertTrue(seqNr == 1L)
    },

    test("append increments sequence numbers") {
      for
        store <- ZIO.service[EventStore[String, TestState, TestEvent]]
        seq1 <- store.append("event-test-2", TestEvent.Started("abc"), 1)
        seq2 <- store.append("event-test-2", TestEvent.Processed("ok"), 2)
        seq3 <- store.append("event-test-2", TestEvent.Finished, 3)
      yield assertTrue(seq1 == 1L, seq2 == 2L, seq3 == 3L)
    },

    test("append fails on sequence conflict") {
      for
        store <- ZIO.service[EventStore[String, TestState, TestEvent]]
        _ <- store.append("event-test-3", TestEvent.Started("x"), 1)
        result <- store.append("event-test-3", TestEvent.Processed("y"), 1).either
      yield result match
        case Left(e: SequenceConflictError) => assertTrue(e.expectedSeqNr == 1L, e.actualSeqNr == 1L)
        case _ => assertTrue(false)
    },

    test("append handles Timeout events") {
      for
        store <- ZIO.service[EventStore[String, TestState, TestEvent]]
        seqNr <- store.append("event-test-4", Timeout, 1)
        events <- store.loadEvents("event-test-4").runCollect
      yield assertTrue(
        seqNr == 1L,
        events.head.event == Timeout
      )
    },

    test("loadEvents returns events in sequence order") {
      for
        store <- ZIO.service[EventStore[String, TestState, TestEvent]]
        _ <- store.append("event-test-5", TestEvent.Started("order-test"), 1)
        _ <- store.append("event-test-5", TestEvent.Processed("step-1"), 2)
        _ <- store.append("event-test-5", TestEvent.Processed("step-2"), 3)
        _ <- store.append("event-test-5", TestEvent.Finished, 4)
        events <- store.loadEvents("event-test-5").runCollect
      yield assertTrue(
        events.length == 4,
        events.map(_.sequenceNr) == Chunk(1L, 2L, 3L, 4L)
      )
    },

    test("loadEventsFrom returns events after sequence number") {
      for
        store <- ZIO.service[EventStore[String, TestState, TestEvent]]
        _ <- store.append("event-test-6", TestEvent.Started("from-test"), 1)
        _ <- store.append("event-test-6", TestEvent.Processed("a"), 2)
        _ <- store.append("event-test-6", TestEvent.Processed("b"), 3)
        events <- store.loadEventsFrom("event-test-6", 1).runCollect
      yield assertTrue(
        events.length == 2,
        events.map(_.sequenceNr) == Chunk(2L, 3L)
      )
    },

    test("saveSnapshot and loadSnapshot roundtrip") {
      for
        store <- ZIO.service[EventStore[String, TestState, TestEvent]]
        now <- Clock.instant
        snapshot = FSMSnapshot("snapshot-test-1", TestState.Processing, 5L, now)
        _ <- store.saveSnapshot(snapshot)
        loaded <- store.loadSnapshot("snapshot-test-1")
      yield loaded match
        case Some(s) => assertTrue(
          s.instanceId == "snapshot-test-1",
          s.state == TestState.Processing,
          s.sequenceNr == 5L
        )
        case None => assertTrue(false)
    },

    test("saveSnapshot replaces existing snapshot (upsert)") {
      for
        store <- ZIO.service[EventStore[String, TestState, TestEvent]]
        now <- Clock.instant
        _ <- store.saveSnapshot(FSMSnapshot("snapshot-test-2", TestState.Initial, 1L, now))
        _ <- store.saveSnapshot(FSMSnapshot("snapshot-test-2", TestState.Completed, 10L, now))
        loaded <- store.loadSnapshot("snapshot-test-2")
      yield loaded match
        case Some(s) => assertTrue(s.state == TestState.Completed, s.sequenceNr == 10L)
        case None => assertTrue(false)
    },

    test("loadSnapshot returns None for nonexistent instance") {
      for
        store <- ZIO.service[EventStore[String, TestState, TestEvent]]
        loaded <- store.loadSnapshot("does-not-exist")
      yield assertTrue(loaded.isEmpty)
    },

    test("deleteEventsTo removes old events") {
      for
        store <- ZIO.service[EventStore[String, TestState, TestEvent]]
        _ <- store.append("delete-test-1", TestEvent.Started("d"), 1)
        _ <- store.append("delete-test-1", TestEvent.Processed("e"), 2)
        _ <- store.append("delete-test-1", TestEvent.Finished, 3)
        _ <- store.deleteEventsTo("delete-test-1", 2)
        events <- store.loadEvents("delete-test-1").runCollect
      yield assertTrue(
        events.length == 1,
        events.head.sequenceNr == 3L
      )
    },

    test("highestSequenceNr returns correct value") {
      for
        store <- ZIO.service[EventStore[String, TestState, TestEvent]]
        _ <- store.append("highest-test-1", TestEvent.Started("h"), 1)
        _ <- store.append("highest-test-1", TestEvent.Processed("i"), 2)
        highest <- store.highestSequenceNr("highest-test-1")
      yield assertTrue(highest == 2L)
    },

    test("highestSequenceNr returns 0 for new instance") {
      for
        store <- ZIO.service[EventStore[String, TestState, TestEvent]]
        highest <- store.highestSequenceNr("new-instance")
      yield assertTrue(highest == 0L)
    },

    test("concurrent appends - only one wins") {
      for
        store <- ZIO.service[EventStore[String, TestState, TestEvent]]
        instanceId = s"concurrent-test-${java.util.UUID.randomUUID()}"
        results <- ZIO.foreachPar(List("a", "b", "c")) { suffix =>
          store.append(instanceId, TestEvent.Started(suffix), 1).either
        }
        successes = results.collect { case Right(seqNr) => seqNr }
        failures = results.collect { case Left(_: SequenceConflictError) => () }
      yield assertTrue(
        successes.length == 1,
        failures.length == 2
      )
    }
  ).provideShared(storeLayer) @@ TestAspect.sequential
