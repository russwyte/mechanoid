package mechanoid.persistence.postgres

import saferis.*
import zio.*
import zio.json.*
import zio.test.*
import mechanoid.PostgresTestContainer
import mechanoid.core.*
import mechanoid.persistence.*

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

  val xaLayer    = PostgresTestContainer.DataSourceProvider.transactor
  val storeLayer = xaLayer >>> PostgresEventStore.layer[TestState, TestEvent]

  def spec = suite("PostgresEventStore")(
    test("append persists an event with correct sequence number") {
      for
        store <- ZIO.service[EventStore[String, TestState, TestEvent]]
        // Pass expected current seq (0 for first event), get back new seq (1)
        seqNr <- store.append("event-test-1", TestEvent.Started("123").timed, 0)
      yield assertTrue(seqNr == 1L)
    },
    test("append increments sequence numbers") {
      for
        store <- ZIO.service[EventStore[String, TestState, TestEvent]]
        // Each append passes expected current and gets back new seq
        seq1 <- store.append("event-test-2", TestEvent.Started("abc").timed, 0)
        seq2 <- store.append("event-test-2", TestEvent.Processed("ok").timed, 1)
        seq3 <- store.append("event-test-2", TestEvent.Finished.timed, 2)
      yield assertTrue(seq1 == 1L, seq2 == 2L, seq3 == 3L)
    },
    test("append fails on sequence conflict") {
      for
        store <- ZIO.service[EventStore[String, TestState, TestEvent]]
        _     <- store.append("event-test-3", TestEvent.Started("x").timed, 0)
        // Try to append expecting seq 0, but it's now 1
        result <- store.append("event-test-3", TestEvent.Processed("y").timed, 0).either
      yield result match
        case Left(e: SequenceConflictError) => assertTrue(e.expectedSeqNr == 0L, e.actualSeqNr == 1L)
        case _                              => assertTrue(false)
    },
    test("append handles Timeout events") {
      for
        store  <- ZIO.service[EventStore[String, TestState, TestEvent]]
        seqNr  <- store.append("event-test-4", Timed.TimeoutEvent, 0)
        events <- store.loadEvents("event-test-4").runCollect
      yield assertTrue(
        seqNr == 1L,
        events.head.event == Timed.TimeoutEvent,
      )
    },
    test("loadEvents returns events in sequence order") {
      for
        store  <- ZIO.service[EventStore[String, TestState, TestEvent]]
        _      <- store.append("event-test-5", TestEvent.Started("order-test").timed, 0)
        _      <- store.append("event-test-5", TestEvent.Processed("step-1").timed, 1)
        _      <- store.append("event-test-5", TestEvent.Processed("step-2").timed, 2)
        _      <- store.append("event-test-5", TestEvent.Finished.timed, 3)
        events <- store.loadEvents("event-test-5").runCollect
      yield assertTrue(
        events.length == 4,
        events.map(_.sequenceNr) == Chunk(1L, 2L, 3L, 4L),
      )
    },
    test("loadEventsFrom returns events after sequence number") {
      for
        store  <- ZIO.service[EventStore[String, TestState, TestEvent]]
        _      <- store.append("event-test-6", TestEvent.Started("from-test").timed, 0)
        _      <- store.append("event-test-6", TestEvent.Processed("a").timed, 1)
        _      <- store.append("event-test-6", TestEvent.Processed("b").timed, 2)
        events <- store.loadEventsFrom("event-test-6", 1).runCollect
      yield assertTrue(
        events.length == 2,
        events.map(_.sequenceNr) == Chunk(2L, 3L),
      )
    },
    test("saveSnapshot and loadSnapshot roundtrip") {
      for
        store <- ZIO.service[EventStore[String, TestState, TestEvent]]
        now   <- Clock.instant
        snapshot = FSMSnapshot("snapshot-test-1", TestState.Processing, 5L, now)
        _      <- store.saveSnapshot(snapshot)
        loaded <- store.loadSnapshot("snapshot-test-1")
      yield loaded match
        case Some(s) =>
          assertTrue(
            s.instanceId == "snapshot-test-1",
            s.state == TestState.Processing,
            s.sequenceNr == 5L,
          )
        case None => assertTrue(false)
    },
    test("saveSnapshot replaces existing snapshot (upsert)") {
      for
        store  <- ZIO.service[EventStore[String, TestState, TestEvent]]
        now    <- Clock.instant
        _      <- store.saveSnapshot(FSMSnapshot("snapshot-test-2", TestState.Initial, 1L, now))
        _      <- store.saveSnapshot(FSMSnapshot("snapshot-test-2", TestState.Completed, 10L, now))
        loaded <- store.loadSnapshot("snapshot-test-2")
      yield loaded match
        case Some(s) => assertTrue(s.state == TestState.Completed, s.sequenceNr == 10L)
        case None    => assertTrue(false)
    },
    test("loadSnapshot returns None for nonexistent instance") {
      for
        store  <- ZIO.service[EventStore[String, TestState, TestEvent]]
        loaded <- store.loadSnapshot("does-not-exist")
      yield assertTrue(loaded.isEmpty)
    },
    test("deleteEventsTo removes old events") {
      for
        store  <- ZIO.service[EventStore[String, TestState, TestEvent]]
        _      <- store.append("delete-test-1", TestEvent.Started("d").timed, 0)
        _      <- store.append("delete-test-1", TestEvent.Processed("e").timed, 1)
        _      <- store.append("delete-test-1", TestEvent.Finished.timed, 2)
        _      <- store.deleteEventsTo("delete-test-1", 2)
        events <- store.loadEvents("delete-test-1").runCollect
      yield assertTrue(
        events.length == 1,
        events.head.sequenceNr == 3L,
      )
    },
    test("highestSequenceNr returns correct value") {
      for
        store   <- ZIO.service[EventStore[String, TestState, TestEvent]]
        _       <- store.append("highest-test-1", TestEvent.Started("h").timed, 0)
        _       <- store.append("highest-test-1", TestEvent.Processed("i").timed, 1)
        highest <- store.highestSequenceNr("highest-test-1")
      yield assertTrue(highest == 2L)
    },
    test("highestSequenceNr returns 0 for new instance") {
      for
        store   <- ZIO.service[EventStore[String, TestState, TestEvent]]
        highest <- store.highestSequenceNr("new-instance")
      yield assertTrue(highest == 0L)
    },
    test("concurrent appends - only one wins") {
      for
        store <- ZIO.service[EventStore[String, TestState, TestEvent]]
        instanceId = s"concurrent-test-${java.util.UUID.randomUUID()}"
        // All try to append expecting current seq = 0
        results <- ZIO.foreachPar(List("a", "b", "c")) { suffix =>
          store.append(instanceId, TestEvent.Started(suffix).timed, 0).either
        }
        successes = results.collect { case Right(seqNr) => seqNr }
        failures  = results.collect { case Left(_: SequenceConflictError) => () }
      yield assertTrue(
        successes.length == 1,
        failures.length == 2,
      )
    },
  ).provideShared(storeLayer) @@ TestAspect.sequential
end PostgresEventStoreSpec
