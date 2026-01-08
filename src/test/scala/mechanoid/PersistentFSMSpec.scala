package mechanoid

import zio.*
import zio.stream.*
import zio.test.*
import mechanoid.persistence.*
import mechanoid.core.Timeout
import java.time.Instant
import scala.collection.mutable

object PersistentFSMSpec extends ZIOSpecDefault:

  // Define states for a simple order workflow
  enum OrderState extends MState:
    case Pending, Paid, Shipped, Delivered

  // Define events
  enum OrderEvent extends MEvent:
    case Pay, Ship, Deliver

  import OrderState.*
  import OrderEvent.*

  // In-memory EventStore for testing (simple, ignores expectedSeqNr for basic tests)
  class InMemoryEventStore[Id, S <: MState, E <: MEvent] extends EventStore[Id, S, E]:

    private val events =
      mutable.Map[Id, mutable.ArrayBuffer[StoredEvent[Id, E | Timeout.type]]]()
    private val snapshots = mutable.Map[Id, FSMSnapshot[Id, S]]()
    private var seqNr     = 0L

    override def append(
        instanceId: Id,
        event: E | Timeout.type,
        expectedSeqNr: Long,
    ): ZIO[Any, Throwable, Long] =
      ZIO.succeed {
        // Simple implementation - just append without strict conflict detection
        // Use ConcurrentEventStore for tests that need real optimistic locking
        seqNr += 1
        val stored = StoredEvent(instanceId, seqNr, event, Instant.now())
        events.getOrElseUpdate(instanceId, mutable.ArrayBuffer.empty) += stored
        seqNr
      }

    override def loadEvents(
        instanceId: Id
    ): ZStream[Any, Throwable, StoredEvent[Id, E | Timeout.type]] =
      ZStream.fromIterable(events.getOrElse(instanceId, Seq.empty))

    override def loadSnapshot(
        instanceId: Id
    ): ZIO[Any, Throwable, Option[FSMSnapshot[Id, S]]] =
      ZIO.succeed(snapshots.get(instanceId))

    override def saveSnapshot(
        snapshot: FSMSnapshot[Id, S]
    ): ZIO[Any, Throwable, Unit] =
      ZIO.succeed {
        snapshots(snapshot.instanceId) = snapshot
      }

    override def highestSequenceNr(instanceId: Id): ZIO[Any, Throwable, Long] =
      ZIO.succeed {
        events
          .get(instanceId)
          .flatMap(_.lastOption.map(_.sequenceNr))
          .getOrElse(0L)
      }

    // For test assertions
    def getEvents(instanceId: Id): List[StoredEvent[Id, E | Timeout.type]] =
      events.getOrElse(instanceId, Seq.empty).toList

    def getSnapshot(instanceId: Id): Option[FSMSnapshot[Id, S]] =
      snapshots.get(instanceId)
  end InMemoryEventStore

  def spec = suite("PersistentFSM Spec")(
    test("should persist events on transition") {
      val store      = new InMemoryEventStore[String, OrderState, OrderEvent]
      val storeLayer = ZLayer.succeed[EventStore[String, OrderState, OrderEvent]](store)
      val definition = FSMDefinition[OrderState, OrderEvent]
        .when(Pending)
        .on(Pay)
        .goto(Paid)
        .when(Paid)
        .on(Ship)
        .goto(Shipped)

      ZIO
        .scoped {
          for
            fsm   <- PersistentFSMRuntime("order-1", definition, Pending)
            _     <- fsm.send(Pay)
            state <- fsm.currentState
            seqNr <- fsm.lastSequenceNr
          yield
            val events = store.getEvents("order-1")
            assertTrue(
              state == Paid,
              seqNr == 1L,
              events.length == 1,
              events.head.event == Pay,
            )
        }
        .provide(storeLayer)
    },
    test("should rebuild state from persisted events") {
      val store      = new InMemoryEventStore[String, OrderState, OrderEvent]
      val storeLayer = ZLayer.succeed[EventStore[String, OrderState, OrderEvent]](store)
      val definition = FSMDefinition[OrderState, OrderEvent]
        .when(Pending)
        .on(Pay)
        .goto(Paid)
        .when(Paid)
        .on(Ship)
        .goto(Shipped)
        .when(Shipped)
        .on(Deliver)
        .goto(Delivered)

      (for
        // First FSM instance - make some transitions
        _ <- ZIO.scoped {
          for
            fsm <- PersistentFSMRuntime("order-2", definition, Pending)
            _   <- fsm.send(Pay)
            _   <- fsm.send(Ship)
          yield ()
        }

        // Verify events were stored
        storedEvents = store.getEvents("order-2")
        _            = assertTrue(storedEvents.length == 2)

        // Second FSM instance - should rebuild from events
        result <- ZIO.scoped {
          for
            fsm   <- PersistentFSMRuntime("order-2", definition, Pending)
            state <- fsm.currentState
            seqNr <- fsm.lastSequenceNr
          yield (state, seqNr)
        }
      yield assertTrue(
        result._1 == Shipped,
        result._2 == 2L,
      )).provide(storeLayer)
    },
    test("should save and restore from snapshot") {
      val store      = new InMemoryEventStore[String, OrderState, OrderEvent]
      val storeLayer = ZLayer.succeed[EventStore[String, OrderState, OrderEvent]](store)
      val definition = FSMDefinition[OrderState, OrderEvent]
        .when(Pending)
        .on(Pay)
        .goto(Paid)
        .when(Paid)
        .on(Ship)
        .goto(Shipped)

      (for
        // First FSM - make transition and save snapshot
        _ <- ZIO.scoped {
          for
            fsm <- PersistentFSMRuntime("order-3", definition, Pending)
            _   <- fsm.send(Pay)
            _   <- fsm.saveSnapshot
          yield ()
        }

        // Verify snapshot was saved
        snapshot = store.getSnapshot("order-3")
        _        = assertTrue(snapshot.isDefined)
        _        = assertTrue(snapshot.get.state == Paid)

        // Second FSM - should restore from snapshot
        result <- ZIO.scoped {
          for
            fsm   <- PersistentFSMRuntime("order-3", definition, Pending)
            state <- fsm.currentState
          yield state
        }
      yield assertTrue(result == Paid)).provide(storeLayer)
    },
    test("should return instance ID") {
      val store      = new InMemoryEventStore[String, OrderState, OrderEvent]
      val storeLayer = ZLayer.succeed[EventStore[String, OrderState, OrderEvent]](store)
      val definition = FSMDefinition[OrderState, OrderEvent]
        .when(Pending)
        .on(Pay)
        .goto(Paid)

      ZIO
        .scoped {
          for
            fsm <- PersistentFSMRuntime("my-order-id", definition, Pending)
            id = fsm.instanceId
          yield assertTrue(id == "my-order-id")
        }
        .provide(storeLayer)
    },
    test("should lookup current state directly from store") {
      val store      = new InMemoryEventStore[String, OrderState, OrderEvent]
      val storeLayer = ZLayer.succeed[EventStore[String, OrderState, OrderEvent]](store)
      val definition = FSMDefinition[OrderState, OrderEvent]
        .when(Pending)
        .on(Pay)
        .goto(Paid)
        .when(Paid)
        .on(Ship)
        .goto(Shipped)

      (for
        // No state initially
        initialState <- store.currentState("order-lookup")

        // Create FSM, make transitions, save snapshot
        _ <- ZIO.scoped {
          for
            fsm <- PersistentFSMRuntime("order-lookup", definition, Pending)
            _   <- fsm.send(Pay)
            _   <- fsm.send(Ship)
            _   <- fsm.saveSnapshot
          yield ()
        }

        // Quick lookup without loading runtime
        lookupState <- store.currentState("order-lookup")
      yield assertTrue(
        initialState.isEmpty,
        lookupState == Some(Shipped),
      )).provide(storeLayer)
    },

    // Guard tests
    test("should execute transition when guard passes") {
      val store                             = new InMemoryEventStore[String, OrderState, OrderEvent]
      val storeLayer                        = ZLayer.succeed[EventStore[String, OrderState, OrderEvent]](store)
      val guard: ZIO[Any, Nothing, Boolean] = ZIO.succeed(true)
      val definition: FSMDefinition[OrderState, OrderEvent, Any, Nothing] =
        FSMDefinition[OrderState, OrderEvent]
          .when(Pending)
          .on(Pay)
          .when(guard)
          .goto(Paid)

      ZIO
        .scoped {
          for
            fsm   <- PersistentFSMRuntime("order-guard-pass", definition, Pending)
            _     <- fsm.send(Pay)
            state <- fsm.currentState
            events = store.getEvents("order-guard-pass")
          yield assertTrue(
            state == Paid,
            events.length == 1,
            events.head.event == Pay,
          )
        }
        .provide(storeLayer)
    },
    test("should reject transition when guard fails and not persist event") {
      val store                             = new InMemoryEventStore[String, OrderState, OrderEvent]
      val storeLayer                        = ZLayer.succeed[EventStore[String, OrderState, OrderEvent]](store)
      val guard: ZIO[Any, Nothing, Boolean] = ZIO.succeed(false)
      val definition: FSMDefinition[OrderState, OrderEvent, Any, Nothing] =
        FSMDefinition[OrderState, OrderEvent]
          .when(Pending)
          .on(Pay)
          .when(guard)
          .goto(Paid)

      ZIO
        .scoped {
          for
            fsm   <- PersistentFSMRuntime("order-guard-fail", definition, Pending)
            error <- fsm.send(Pay).flip
            state <- fsm.currentState
            events = store.getEvents("order-guard-fail")
          yield assertTrue(
            error.isInstanceOf[GuardRejectedError],
            state == Pending,
            events.isEmpty, // Event should NOT be persisted when guard fails
          )
        }
        .provide(storeLayer)
    },

    // Stay/Stop tests
    test("should stay in state when configured") {
      val store      = new InMemoryEventStore[String, OrderState, OrderEvent]
      val storeLayer = ZLayer.succeed[EventStore[String, OrderState, OrderEvent]](store)
      val definition = FSMDefinition[OrderState, OrderEvent]
        .when(Pending)
        .on(Pay)
        .stay

      ZIO
        .scoped {
          for
            fsm    <- PersistentFSMRuntime("order-stay", definition, Pending)
            result <- fsm.send(Pay)
            state  <- fsm.currentState
            events = store.getEvents("order-stay")
          yield assertTrue(
            result == TransitionResult.Stay,
            state == Pending,
            events.length == 1, // Event is still persisted even on stay
          )
        }
        .provide(storeLayer)
    },
    test("should stop FSM and report not running") {
      val store      = new InMemoryEventStore[String, OrderState, OrderEvent]
      val storeLayer = ZLayer.succeed[EventStore[String, OrderState, OrderEvent]](store)
      val definition = FSMDefinition[OrderState, OrderEvent]
        .when(Pending)
        .on(Pay)
        .goto(Paid)

      ZIO
        .scoped {
          for
            fsm           <- PersistentFSMRuntime("order-stop", definition, Pending)
            runningBefore <- fsm.isRunning
            _             <- fsm.stop
            runningAfter  <- fsm.isRunning
          yield assertTrue(
            runningBefore,
            !runningAfter,
          )
        }
        .provide(storeLayer)
    },
    test("should return Stop result after FSM is stopped") {
      val store      = new InMemoryEventStore[String, OrderState, OrderEvent]
      val storeLayer = ZLayer.succeed[EventStore[String, OrderState, OrderEvent]](store)
      val definition = FSMDefinition[OrderState, OrderEvent]
        .when(Pending)
        .on(Pay)
        .goto(Paid)

      ZIO
        .scoped {
          for
            fsm    <- PersistentFSMRuntime("order-stop-result", definition, Pending)
            _      <- fsm.stop
            result <- fsm.send(Pay)
          yield assertTrue(result.isInstanceOf[TransitionResult.Stop[?]])
        }
        .provide(storeLayer)
    },

    // Snapshot + events rebuild test
    test("should rebuild from snapshot plus subsequent events") {
      val store      = new InMemoryEventStore[String, OrderState, OrderEvent]
      val storeLayer = ZLayer.succeed[EventStore[String, OrderState, OrderEvent]](store)
      val definition = FSMDefinition[OrderState, OrderEvent]
        .when(Pending)
        .on(Pay)
        .goto(Paid)
        .when(Paid)
        .on(Ship)
        .goto(Shipped)
        .when(Shipped)
        .on(Deliver)
        .goto(Delivered)

      (for
        // First FSM - make one transition and save snapshot
        _ <- ZIO.scoped {
          for
            fsm <- PersistentFSMRuntime("order-snapshot-events", definition, Pending)
            _   <- fsm.send(Pay)    // Pending -> Paid
            _   <- fsm.saveSnapshot // Snapshot at Paid with seqNr 1
          yield ()
        }

        // Second FSM - make more transitions (after snapshot)
        _ <- ZIO.scoped {
          for
            fsm         <- PersistentFSMRuntime("order-snapshot-events", definition, Pending)
            stateBefore <- fsm.currentState
            _ = assertTrue(stateBefore == Paid) // Should restore from snapshot
            _ <- fsm.send(Ship) // Paid -> Shipped
          yield ()
        }

        // Verify stored data
        snapshot = store.getSnapshot("order-snapshot-events")
        events   = store.getEvents("order-snapshot-events")

        // Third FSM - should rebuild from snapshot + subsequent events
        result <- ZIO.scoped {
          for
            fsm     <- PersistentFSMRuntime("order-snapshot-events", definition, Pending)
            state   <- fsm.currentState
            seqNr   <- fsm.lastSequenceNr
            history <- fsm.history
          yield (state, seqNr, history)
        }
      yield assertTrue(
        snapshot.isDefined,
        snapshot.get.state == Paid,
        snapshot.get.sequenceNr == 1L,
        events.length == 2,     // Pay + Ship
        result._1 == Shipped,   // Current state
        result._2 == 2L,        // Sequence number
        result._3 == List(Paid), // History only contains Paid (from replay of Ship event)
      )).provide(storeLayer)
    },

    // Entry/Exit actions during replay
    test("should not execute entry/exit actions during replay") {
      for
        actionLog <- Ref.make(List.empty[String])
        store      = new InMemoryEventStore[String, OrderState, OrderEvent]
        storeLayer = ZLayer.succeed[EventStore[String, OrderState, OrderEvent]](store)
        definition: FSMDefinition[OrderState, OrderEvent, Any, Nothing] =
          FSMDefinition[OrderState, OrderEvent]
            .when(Pending)
            .on(Pay)
            .goto(Paid)
            .onState(Pending)
            .onExit(actionLog.update(_ :+ "exit-pending"))
            .done
            .onState(Paid)
            .onEntry(actionLog.update(_ :+ "entry-paid"))
            .done

        // First FSM - actions should run
        _ <- ZIO
          .scoped {
            for
              fsm <- PersistentFSMRuntime("order-actions", definition, Pending)
              _   <- fsm.send(Pay)
            yield ()
          }
          .provide(storeLayer)
        actionsAfterFirst <- actionLog.get

        // Clear log
        _ <- actionLog.set(List.empty)

        // Second FSM - replay should NOT run actions
        result <- ZIO
          .scoped {
            for
              fsm   <- PersistentFSMRuntime("order-actions", definition, Pending)
              state <- fsm.currentState
            yield state
          }
          .provide(storeLayer)
        actionsAfterReplay <- actionLog.get
      yield assertTrue(
        actionsAfterFirst == List("exit-pending", "entry-paid"),
        actionsAfterReplay.isEmpty, // No actions during replay
        result == Paid,
      )
    },

    // EventStore highestSequenceNr test
    test("should track highest sequence number") {
      val store      = new InMemoryEventStore[String, OrderState, OrderEvent]
      val storeLayer = ZLayer.succeed[EventStore[String, OrderState, OrderEvent]](store)
      val definition = FSMDefinition[OrderState, OrderEvent]
        .when(Pending)
        .on(Pay)
        .goto(Paid)
        .when(Paid)
        .on(Ship)
        .goto(Shipped)

      (for
        seqNrBefore <- store.highestSequenceNr("order-seq")

        _ <- ZIO.scoped {
          for
            fsm <- PersistentFSMRuntime("order-seq", definition, Pending)
            _   <- fsm.send(Pay)
            _   <- fsm.send(Ship)
          yield ()
        }

        seqNrAfter <- store.highestSequenceNr("order-seq")
      yield assertTrue(
        seqNrBefore == 0L,
        seqNrAfter == 2L,
      )).provide(storeLayer)
    },

    // Error handling tests
    test("should not persist event when transition action fails") {
      // Define the failing action with error type that will be wrapped
      val failingAction: ZIO[Any, String, TransitionResult[OrderState]] =
        ZIO.fail("External service unavailable")

      // Build definition that uses the failing action
      val definition: FSMDefinition[OrderState, OrderEvent, Any, String] =
        FSMDefinition[OrderState, OrderEvent]
          .when(Pending)
          .on(Pay)
          .execute(failingAction)

      val store      = new InMemoryEventStore[String, OrderState, OrderEvent]
      val storeLayer = ZLayer.succeed[EventStore[String, OrderState, OrderEvent]](store)

      // The test logic
      val testEffect = ZIO
        .scoped {
          for
            fsm           <- PersistentFSMRuntime("order-fail", definition, Pending)
            errorOrResult <- fsm.send(Pay).either
            state         <- fsm.currentState
          yield (errorOrResult, state, store.getEvents("order-fail"))
        }
        .provide(storeLayer)

      testEffect.map { case (errorOrResult, state, events) =>
        val error = errorOrResult.left.toOption.get
        assertTrue(
          error.isInstanceOf[PersistenceError[?]], // The String error is wrapped
          state == Pending,                        // State unchanged
          events.isEmpty,                          // Event NOT persisted on failure
        )
      }
    },
    test("should allow retry of failed transition using ZIO combinators") {
      // Create a mutable counter effect for tracking retry attempts
      val testEffect = for
        attemptCount <- Ref.make(0)
        store      = new InMemoryEventStore[String, OrderState, OrderEvent]
        storeLayer = ZLayer.succeed[EventStore[String, OrderState, OrderEvent]](store)

        // Service that fails twice, then succeeds
        serviceThatEventuallySucceeds: ZIO[Any, String, TransitionResult[OrderState]] =
          attemptCount.updateAndGet(_ + 1).flatMap { count =>
            if count < 3 then ZIO.fail("Service temporarily unavailable")
            else ZIO.succeed(TransitionResult.Goto(Paid))
          }

        definition: FSMDefinition[OrderState, OrderEvent, Any, String] =
          FSMDefinition[OrderState, OrderEvent]
            .when(Pending)
            .on(Pay)
            .execute(serviceThatEventuallySucceeds)

        result <- ZIO
          .scoped {
            for
              fsm <- PersistentFSMRuntime("order-retry", definition, Pending)
              // Use ZIO's retry combinator - idiomatic error handling!
              _        <- fsm.send(Pay).retry(Schedule.recurs(3)).either // Convert to Either to avoid error propagation
              state    <- fsm.currentState
              attempts <- attemptCount.get
            yield (state, attempts, store.getEvents("order-retry").length)
          }
          .provide(storeLayer)
      yield result

      testEffect.map { case (state, attempts, eventCount) =>
        assertTrue(
          state == Paid,  // Eventually succeeded
          attempts == 3,  // Took 3 attempts
          eventCount == 1, // Only ONE event persisted (the successful one)
        )
      }
    },
    test("should allow transitioning to error state on failure") {
      // Define an error state
      val failingPayment: ZIO[Any, String, TransitionResult[OrderState]] =
        ZIO.fail("Payment gateway timeout")

      val definition: FSMDefinition[OrderState, OrderEvent, Any, String] =
        FSMDefinition[OrderState, OrderEvent]
          .when(Pending)
          .on(Pay)
          .execute(failingPayment)
          .when(Pending)
          .on(Ship)      // Use Ship as "mark failed" for this test
          .goto(Shipped) // Using Shipped as a "Failed" state for simplicity

      val store      = new InMemoryEventStore[String, OrderState, OrderEvent]
      val storeLayer = ZLayer.succeed[EventStore[String, OrderState, OrderEvent]](store)

      val testEffect = ZIO
        .scoped {
          for
            fsm <- PersistentFSMRuntime("order-error-state", definition, Pending)
            // Try payment, catch error, transition to error state
            _ <- fsm.send(Pay).either.flatMap {
              case Left(_)  => fsm.send(Ship).either // Ignore result, we just want to transition
              case Right(_) => ZIO.unit
            }
            state <- fsm.currentState
          yield (state, store.getEvents("order-error-state"))
        }
        .provide(storeLayer)

      testEffect.map { case (state, events) =>
        assertTrue(
          state == Shipped,   // Transitioned to error state
          events.length == 1, // Only the Ship event was persisted
          events.head.event == Ship,
        )
      }
    },

    // Distributed safety tests
    suite("Distributed Safety")(
      test("should detect sequence conflicts with optimistic locking") {
        // EventStore with optimistic locking enabled
        val store      = new ConcurrentEventStore[String, OrderState, OrderEvent]
        val storeLayer = ZLayer.succeed[EventStore[String, OrderState, OrderEvent]](store)
        val definition = FSMDefinition[OrderState, OrderEvent]
          .when(Pending)
          .on(Pay)
          .goto(Paid)
          .when(Paid)
          .on(Ship)
          .goto(Shipped)

        (for
          // Create first FSM instance
          result1 <- ZIO.scoped {
            for
              fsm1  <- PersistentFSMRuntime("order-concurrent", definition, Pending)
              _     <- fsm1.send(Pay) // Pending -> Paid, seqNr becomes 1
              seqNr <- fsm1.lastSequenceNr
            yield seqNr
          }

          // Simulate another node that loaded state at seqNr=0 and tries to append
          // This should fail because expected seqNr (0) doesn't match actual (1)
          conflictResult <- store
            .append("order-concurrent", Pay, 0L)
            .either
        yield assertTrue(
          result1 == 1L,
          conflictResult.isLeft,
          conflictResult.left.toOption.get.isInstanceOf[SequenceConflictError],
        )).provide(storeLayer)
      },
      test("should allow append when sequence matches") {
        val store      = new ConcurrentEventStore[String, OrderState, OrderEvent]
        val storeLayer = ZLayer.succeed[EventStore[String, OrderState, OrderEvent]](store)
        val definition = FSMDefinition[OrderState, OrderEvent]
          .when(Pending)
          .on(Pay)
          .goto(Paid)
          .when(Paid)
          .on(Ship)
          .goto(Shipped)

        ZIO
          .scoped {
            for
              fsm        <- PersistentFSMRuntime("order-seq-match", definition, Pending)
              _          <- fsm.send(Pay)  // seqNr 0 -> 1
              _          <- fsm.send(Ship) // seqNr 1 -> 2
              finalSeqNr <- fsm.lastSequenceNr
              events = store.getEvents("order-seq-match")
            yield assertTrue(
              finalSeqNr == 2L,
              events.length == 2,
            )
          }
          .provide(storeLayer)
      },
      test("should rebuild consistent state across multiple instances") {
        val store      = new ConcurrentEventStore[String, OrderState, OrderEvent]
        val storeLayer = ZLayer.succeed[EventStore[String, OrderState, OrderEvent]](store)
        val definition = FSMDefinition[OrderState, OrderEvent]
          .when(Pending)
          .on(Pay)
          .goto(Paid)
          .when(Paid)
          .on(Ship)
          .goto(Shipped)

        (for
          // First instance makes transitions
          _ <- ZIO.scoped {
            for
              fsm1 <- PersistentFSMRuntime("order-consistent", definition, Pending)
              _    <- fsm1.send(Pay)
              _    <- fsm1.send(Ship)
            yield ()
          }

          // Second instance loads and should see same state
          state2 <- ZIO.scoped {
            for
              fsm2  <- PersistentFSMRuntime("order-consistent", definition, Pending)
              state <- fsm2.currentState
              seqNr <- fsm2.lastSequenceNr
            yield (state, seqNr)
          }

          // Third instance also loads - verifies consistency
          state3 <- ZIO.scoped {
            for
              fsm3  <- PersistentFSMRuntime("order-consistent", definition, Pending)
              state <- fsm3.currentState
            yield state
          }
        yield assertTrue(
          state2._1 == Shipped,
          state2._2 == 2L,
          state3 == Shipped, // All instances see same state
        )).provide(storeLayer)
      },
      test("should handle conflict with retry pattern") {
        // This test demonstrates the recommended pattern for handling conflicts
        val store      = new ConcurrentEventStore[String, OrderState, OrderEvent]
        val storeLayer = ZLayer.succeed[EventStore[String, OrderState, OrderEvent]](store)
        val definition = FSMDefinition[OrderState, OrderEvent]
          .when(Pending)
          .on(Pay)
          .goto(Paid)

        // Simulate a conflict scenario:
        // 1. FSM loads at seqNr=0
        // 2. External write happens (seqNr becomes 1)
        // 3. FSM tries to write but gets conflict
        // 4. FSM retries by reloading and succeeding

        (for
          // Manually insert an event to simulate external write (seqNr starts at 0)
          _ <- store.append("order-retry-conflict", Pay, 0L)

          // Now create FSM - it loads events, sees Pay already happened
          result <- ZIO.scoped {
            for
              fsm <- PersistentFSMRuntime("order-retry-conflict", definition, Pending)
              // FSM should have rebuilt to Paid state from existing event
              state <- fsm.currentState
              seqNr <- fsm.lastSequenceNr
            yield (state, seqNr)
          }
        yield assertTrue(
          result._1 == Paid, // State rebuilt from existing event
          result._2 == 1L,
        )).provide(storeLayer)
      },
    ),
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential

  // EventStore with optimistic locking for distributed safety tests
  class ConcurrentEventStore[Id, S <: MState, E <: MEvent] extends EventStore[Id, S, E]:

    private val events =
      mutable.Map[Id, mutable.ArrayBuffer[StoredEvent[Id, E | Timeout.type]]]()
    private val snapshots   = mutable.Map[Id, FSMSnapshot[Id, S]]()
    private var globalSeqNr = 0L

    override def append(
        instanceId: Id,
        event: E | Timeout.type,
        expectedSeqNr: Long,
    ): ZIO[Any, Throwable, Long] =
      ZIO.suspend {
        synchronized {
          val currentSeqNr = events
            .get(instanceId)
            .flatMap(_.lastOption.map(_.sequenceNr))
            .getOrElse(0L)

          if currentSeqNr != expectedSeqNr then
            ZIO.fail(
              SequenceConflictError(
                instanceId.toString,
                expectedSeqNr,
                currentSeqNr,
              )
            )
          else
            globalSeqNr += 1
            val stored = StoredEvent(instanceId, globalSeqNr, event, Instant.now())
            events.getOrElseUpdate(instanceId, mutable.ArrayBuffer.empty) += stored
            ZIO.succeed(globalSeqNr)
          end if
        }
      }

    override def loadEvents(
        instanceId: Id
    ): ZStream[Any, Throwable, StoredEvent[Id, E | Timeout.type]] =
      ZStream.fromIterable(events.getOrElse(instanceId, Seq.empty))

    override def loadSnapshot(
        instanceId: Id
    ): ZIO[Any, Throwable, Option[FSMSnapshot[Id, S]]] =
      ZIO.succeed(snapshots.get(instanceId))

    override def saveSnapshot(
        snapshot: FSMSnapshot[Id, S]
    ): ZIO[Any, Throwable, Unit] =
      ZIO.succeed {
        snapshots(snapshot.instanceId) = snapshot
      }

    override def highestSequenceNr(instanceId: Id): ZIO[Any, Throwable, Long] =
      ZIO.succeed {
        events
          .get(instanceId)
          .flatMap(_.lastOption.map(_.sequenceNr))
          .getOrElse(0L)
      }

    def getEvents(instanceId: Id): List[StoredEvent[Id, E | Timeout.type]] =
      events.getOrElse(instanceId, Seq.empty).toList
  end ConcurrentEventStore
end PersistentFSMSpec
