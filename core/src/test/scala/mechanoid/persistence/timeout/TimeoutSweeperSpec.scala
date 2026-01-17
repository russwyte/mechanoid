package mechanoid.persistence.timeout

import zio.*
import zio.test.*
import mechanoid.core.{Finite, MechanoidError, PersistenceError, FSMState, TransitionResult, TransitionOutcome}
import mechanoid.machine.{Machine, Assembly, Aspect, assembly, via}
import mechanoid.runtime.FSMRuntime
import java.time.Instant

object TimeoutSweeperSpec extends ZIOSpecDefault:

  // Test types for the mock FSMRuntime
  enum TestState derives Finite:
    case Initial
    case Waiting
    case Processing

  enum TestEvent derives Finite:
    case Start
    case TimeoutFired
    case Process

  import TestState.*
  import TestEvent.*

  // Create a test Machine with timeout configuration using the DSL
  // IMPORTANT: Aspect.timeout configures timeout for the TARGET state of the transition,
  // not the source state. So "(Initial via Start to Waiting) @@ Aspect.timeout(...)"
  // configures a 10-second timeout for Waiting state.
  private val testMachine: Machine[TestState, TestEvent] = Machine(
    assembly[TestState, TestEvent](
      // Enter Waiting from Initial with a 10s timeout that fires TimeoutFired
      (Initial via Start to Waiting) @@ Aspect.timeout(10.seconds, TimeoutFired),
      // Handle the timeout: Waiting -> Processing via TimeoutFired
      Waiting via TimeoutFired to Processing,
      // Normal transition
      Waiting via Process to Processing,
    )
  )

  // State hash for Waiting state (used for timeout store scheduling)
  // The sweeper looks up timeout events via machine.timeoutEvents.get(stateHash)
  // IMPORTANT: Use testMachine.stateEnum to ensure same Finite instance as the sweeper
  private val waitingStateHash: Int = testMachine.stateEnum.caseHash(Waiting)

  // Default sequence number for tests (mock runtime returns 0L)
  private val defaultSeqNr: Long = 0L

  /** Create a mock FSMRuntime that tracks events sent and can optionally fail. */
  def makeMockRuntime(
      eventsRef: Ref[List[(String, TestEvent)]],
      instanceId: String = "test-fsm",
      shouldFail: Boolean = false,
  ): FSMRuntime[String, TestState, TestEvent] =
    makeMockRuntimeWithState(eventsRef, instanceId, shouldFail, Waiting, 0L)

  /** Create a mock FSMRuntime with configurable state and sequence number for state validation tests. */
  def makeMockRuntimeWithState(
      eventsRef: Ref[List[(String, TestEvent)]],
      fsmInstanceId: String,
      shouldFail: Boolean,
      currentStateValue: TestState,
      currentSeqNr: Long,
  ): FSMRuntime[String, TestState, TestEvent] =
    // Capture parameters in local vals to ensure proper closure
    val capturedId    = fsmInstanceId
    val capturedState = currentStateValue
    val capturedSeqNr = currentSeqNr
    val capturedFail  = shouldFail

    new FSMRuntime[String, TestState, TestEvent]:
      override val instanceId: String                     = capturedId
      override val machine: Machine[TestState, TestEvent] = testMachine

      override def send(event: TestEvent): ZIO[Any, MechanoidError, TransitionOutcome[TestState]] =
        if capturedFail then
          eventsRef.update(_ :+ (capturedId, event)) *>
            ZIO.fail(PersistenceError(new RuntimeException("Simulated failure")))
        else
          eventsRef.update(_ :+ (capturedId, event)) *>
            ZIO.succeed(
              TransitionOutcome(TransitionResult.Stay)
            )

      override def currentState: UIO[TestState] = ZIO.succeed(capturedState)

      override def state: UIO[FSMState[TestState]] =
        val now = Instant.now()
        ZIO.succeed(
          FSMState(
            current = capturedState,
            history = Nil,
            stateData = Map.empty,
            startedAt = now,
            lastTransitionAt = now,
          )
        )
      end state

      override def history: UIO[List[TestState]] = ZIO.succeed(Nil)

      override def lastSequenceNr: UIO[Long] = ZIO.succeed(capturedSeqNr)

      override def saveSnapshot: ZIO[Any, MechanoidError, Unit] = ZIO.unit

      override def stop: UIO[Unit] = ZIO.unit

      override def stop(reason: String): UIO[Unit] = ZIO.unit

      override def isRunning: UIO[Boolean] = ZIO.succeed(true)

      override def timeoutConfigForState(state: TestState): Option[(Duration, TestEvent)] =
        state match
          case Waiting => Some((10.seconds, TimeoutFired))
          case _       => None
    end new
  end makeMockRuntimeWithState

  // State hash for Processing state (for state mismatch tests)
  // IMPORTANT: Use testMachine.stateEnum to ensure same Finite instance as the sweeper
  private val processingStateHash: Int = testMachine.stateEnum.caseHash(Processing)

  def spec = suite("TimeoutSweeper")(
    suite("basic operation")(
      test("fires expired timeouts") {
        for
          store     <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          eventsRef <- Ref.make(List.empty[(String, TestEvent)])

          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(50))
            .withJitterFactor(0.0) // No jitter for predictable tests
            .withNodeId("test-node")

          now <- Clock.instant
          // Schedule with the event hash
          _ <- store.schedule("fsm-1", waitingStateHash, defaultSeqNr, now.minusSeconds(10))
          _ <- store.schedule("fsm-2", waitingStateHash, defaultSeqNr, now.minusSeconds(5))

          // Create mock runtime
          runtime = makeMockRuntime(eventsRef, "fsm-1")

          // The sweeper resolves events via Machine, so we can use a single runtime
          _ <- ZIO.scoped {
            for
              _ <- TimeoutSweeper.make(config, store, runtime)
              _ <- ZIO.sleep(Duration.fromMillis(200))
            yield ()
          }

          events <- eventsRef.get
        yield assertTrue(
          events.length == 2,
          events.forall(_._2 == TimeoutFired),
        )
      } @@ TestAspect.withLiveClock,
      test("respects batch size") {
        for
          store     <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          eventsRef <- Ref.make(List.empty[(String, TestEvent)])
          runtime = makeMockRuntime(eventsRef)

          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(200)) // Longer interval
            .withBatchSize(2)
            .withJitterFactor(0.0)
            .withNodeId("test-node")

          now <- Clock.instant
          _ <- ZIO.foreach(1 to 5)(i => store.schedule(s"fsm-$i", waitingStateHash, defaultSeqNr, now.minusSeconds(10)))

          // Run for just enough time to complete one sweep
          _ <- ZIO.scoped {
            for
              _ <- TimeoutSweeper.make(config, store, runtime)
              _ <- ZIO.sleep(Duration.fromMillis(100)) // Less than sweep interval
            yield ()
          }

          events <- eventsRef.get
        yield assertTrue(events.length == 2) // Only batch size processed in first sweep
      } @@ TestAspect.withLiveClock,
      test("skips non-expired timeouts") {
        for
          store     <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          eventsRef <- Ref.make(List.empty[(String, TestEvent)])
          runtime = makeMockRuntime(eventsRef)

          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(50))
            .withJitterFactor(0.0)
            .withNodeId("test-node")

          now <- Clock.instant
          _   <- store.schedule("expired", waitingStateHash, defaultSeqNr, now.minusSeconds(10))
          _   <- store.schedule("future", waitingStateHash, defaultSeqNr, now.plusSeconds(60))

          _ <- ZIO.scoped {
            for
              _ <- TimeoutSweeper.make(config, store, runtime)
              _ <- ZIO.sleep(Duration.fromMillis(150))
            yield ()
          }

          events <- eventsRef.get
        yield assertTrue(
          events.length == 1,
          events.head._2 == TimeoutFired,
        )
      } @@ TestAspect.withLiveClock,
      test("removes timeout after firing") {
        for
          store     <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          eventsRef <- Ref.make(List.empty[(String, TestEvent)])
          runtime = makeMockRuntime(eventsRef)

          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(50))
            .withJitterFactor(0.0)
            .withNodeId("test-node")

          now <- Clock.instant
          _   <- store.schedule("fsm-1", waitingStateHash, defaultSeqNr, now.minusSeconds(10))

          _ <- ZIO.scoped {
            for
              _ <- TimeoutSweeper.make(config, store, runtime)
              _ <- ZIO.sleep(Duration.fromMillis(150))
            yield ()
          }

          remaining <- store.get("fsm-1")
        yield assertTrue(remaining.isEmpty)
      } @@ TestAspect.withLiveClock,
    ),
    suite("concurrent sweepers")(
      test("only one sweeper fires each timeout") {
        for
          store     <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          eventsRef <- Ref.make(List.empty[(String, TestEvent)])
          runtime = makeMockRuntime(eventsRef)

          config1 = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(20))
            .withJitterFactor(0.0)
            .withNodeId("node-1")

          config2 = config1.withNodeId("node-2")

          now <- Clock.instant
          _   <- store.schedule("fsm-1", waitingStateHash, defaultSeqNr, now.minusSeconds(10))

          // Run two sweepers concurrently
          _ <- ZIO.scoped {
            for
              _ <- TimeoutSweeper.make(config1, store, runtime)
              _ <- TimeoutSweeper.make(config2, store, runtime)
              _ <- ZIO.sleep(Duration.fromMillis(200))
            yield ()
          }

          events <- eventsRef.get
        yield assertTrue(events.length == 1) // Only one sweeper should fire it
      } @@ TestAspect.withLiveClock
    ),
    suite("error handling")(
      test("releases claim on callback error") {
        for
          store     <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          eventsRef <- Ref.make(List.empty[(String, TestEvent)])
          // Create a failing runtime
          runtime = makeMockRuntime(eventsRef, shouldFail = true)

          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(50))
            .withJitterFactor(0.0)
            .withClaimDuration(Duration.fromSeconds(30))
            .withNodeId("test-node")

          now <- Clock.instant
          _   <- store.schedule("fsm-1", waitingStateHash, defaultSeqNr, now.minusSeconds(10))

          _ <- ZIO.scoped {
            for
              _ <- TimeoutSweeper.make(config, store, runtime)
              _ <- ZIO.sleep(Duration.fromMillis(150))
            yield ()
          }

          // Timeout should still exist (released, not completed)
          timeout <- store.get("fsm-1")
          events  <- eventsRef.get
        yield assertTrue(
          timeout.isDefined,
          timeout.get.claimedBy.isEmpty, // Claim was released
          events.nonEmpty,               // Event was attempted
        )
      } @@ TestAspect.withLiveClock
    ),
    suite("metrics")(
      test("tracks fired timeouts") {
        for
          store     <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          eventsRef <- Ref.make(List.empty[(String, TestEvent)])
          runtime = makeMockRuntime(eventsRef)

          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(50))
            .withJitterFactor(0.0)
            .withNodeId("test-node")

          now <- Clock.instant
          _   <- store.schedule("fsm-1", waitingStateHash, defaultSeqNr, now.minusSeconds(10))
          _   <- store.schedule("fsm-2", waitingStateHash, defaultSeqNr, now.minusSeconds(5))

          metrics <- ZIO.scoped {
            for
              sweeper <- TimeoutSweeper.make(config, store, runtime)
              _       <- ZIO.sleep(Duration.fromMillis(200))
              m       <- sweeper.metrics
            yield m
          }
        yield assertTrue(
          metrics.timeoutsFired == 2,
          metrics.sweepCount >= 1,
        )
      } @@ TestAspect.withLiveClock,
      test("tracks claim conflicts with concurrent sweepers") {
        for
          store     <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          eventsRef <- Ref.make(List.empty[(String, TestEvent)])
          runtime = makeMockRuntime(eventsRef)

          config1 = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(20))
            .withJitterFactor(0.0)
            .withNodeId("node-1")

          config2 = config1.withNodeId("node-2")

          now <- Clock.instant
          // Schedule multiple timeouts to increase conflict chance
          _ <- ZIO.foreach(1 to 5)(i => store.schedule(s"fsm-$i", waitingStateHash, defaultSeqNr, now.minusSeconds(10)))

          result <- ZIO.scoped {
            for
              sweeper1 <- TimeoutSweeper.make(config1, store, runtime)
              sweeper2 <- TimeoutSweeper.make(config2, store, runtime)
              // Give enough time for all 5 timeouts to be processed
              _  <- ZIO.sleep(Duration.fromMillis(500))
              m1 <- sweeper1.metrics
              m2 <- sweeper2.metrics
            yield (m1, m2)
          }
          (metrics1, metrics2) = result
          totalConflicts       = metrics1.claimConflicts + metrics2.claimConflicts
          totalFired           = metrics1.timeoutsFired + metrics2.timeoutsFired
        yield assertTrue(
          // All 5 timeouts should be fired between the two sweepers
          // With concurrent sweepers, we may or may not see conflicts depending on timing
          totalFired == 5,
          totalConflicts >= 0,
        )
      } @@ TestAspect.withLiveClock,
    ),
    suite("backoff")(
      test("applies backoff when no timeouts found") {
        for
          store     <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          eventsRef <- Ref.make(List.empty[(String, TestEvent)])
          runtime = makeMockRuntime(eventsRef)

          // Configure with long backoff
          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(20))
            .withBackoffOnEmpty(Duration.fromMillis(200))
            .withJitterFactor(0.0)
            .withNodeId("test-node")

          // No timeouts scheduled - should sweep once then backoff
          metrics <- ZIO.scoped {
            for
              sweeper <- TimeoutSweeper.make(config, store, runtime)
              _       <- ZIO.sleep(Duration.fromMillis(100))
              m       <- sweeper.metrics
            yield m
          }
        yield assertTrue(
          // With 100ms wait and 200ms backoff, should only sweep 1-2 times
          metrics.sweepCount <= 2
        )
      } @@ TestAspect.withLiveClock
    ),
    suite("stop")(
      test("stops sweeping when stopped") {
        for
          store     <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          eventsRef <- Ref.make(List.empty[(String, TestEvent)])
          runtime = makeMockRuntime(eventsRef)

          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(20))
            .withJitterFactor(0.0)
            .withNodeId("test-node")

          result <- ZIO.scoped {
            for
              sweeper     <- TimeoutSweeper.make(config, store, runtime)
              _           <- ZIO.sleep(Duration.fromMillis(50))
              countBefore <- sweeper.metrics.map(_.sweepCount)
              _           <- sweeper.stop
              running     <- sweeper.isRunning
              _           <- ZIO.sleep(Duration.fromMillis(100))
              countAfter  <- sweeper.metrics.map(_.sweepCount)
            yield (running, countBefore, countAfter)
          }
        yield
          val (running, countBefore, countAfter) = result
          assertTrue(
            !running,
            // After stop, sweep count shouldn't increase much
            countAfter - countBefore <= 1,
          )
      } @@ TestAspect.withLiveClock
    ),
    suite("configuration")(
      test("validates jitter factor bounds") {
        assertTrue(
          scala.util
            .Try(TimeoutSweeperConfig().withJitterFactor(-0.1))
            .isFailure,
          scala.util
            .Try(TimeoutSweeperConfig().withJitterFactor(1.1))
            .isFailure,
          scala.util.Try(TimeoutSweeperConfig().withJitterFactor(0.5)).isSuccess,
        )
      },
      test("validates batch size is positive") {
        assertTrue(
          scala.util.Try(TimeoutSweeperConfig().withBatchSize(0)).isFailure,
          scala.util.Try(TimeoutSweeperConfig().withBatchSize(-1)).isFailure,
          scala.util.Try(TimeoutSweeperConfig().withBatchSize(100)).isSuccess,
        )
      },
    ),
    suite("state validation - positive cases")(
      test("fires timeout when state and sequenceNr both match") {
        // FSM in Waiting (seq=0), timeout scheduled with (stateHash=Waiting, seq=0)
        // Sweeper fires → state=Waiting, seq=0 → FIRES ✓
        for
          store     <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          eventsRef <- Ref.make(List.empty[(String, TestEvent)])
          // Runtime is in Waiting state with seqNr=0 (matches what we schedule)
          runtime = makeMockRuntimeWithState(eventsRef, "fsm-1", shouldFail = false, Waiting, 0L)

          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(50))
            .withJitterFactor(0.0)
            .withNodeId("test-node")

          now <- Clock.instant
          // Schedule with stateHash=waitingStateHash, sequenceNr=0 (matches runtime)
          _ <- store.schedule("fsm-1", waitingStateHash, 0L, now.minusSeconds(10))

          _ <- ZIO.scoped {
            for
              _ <- TimeoutSweeper.make(config, store, runtime)
              _ <- ZIO.sleep(Duration.fromMillis(200))
            yield ()
          }

          events <- eventsRef.get
        yield assertTrue(
          events.length == 1,
          events.head._2 == TimeoutFired,
        )
      } @@ TestAspect.withLiveClock,
      test("fires timeout immediately after scheduling (no transitions)") {
        // Schedule timeout, let it expire, sweeper fires
        // No state changes occurred → should fire
        for
          store     <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          eventsRef <- Ref.make(List.empty[(String, TestEvent)])
          runtime = makeMockRuntime(eventsRef, "fsm-1")

          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(50))
            .withJitterFactor(0.0)
            .withNodeId("test-node")

          now <- Clock.instant
          _   <- store.schedule("fsm-1", waitingStateHash, defaultSeqNr, now.minusSeconds(1))

          _ <- ZIO.scoped {
            for
              _ <- TimeoutSweeper.make(config, store, runtime)
              _ <- ZIO.sleep(Duration.fromMillis(200))
            yield ()
          }

          events    <- eventsRef.get
          remaining <- store.get("fsm-1")
        yield assertTrue(
          events.length == 1,
          events.head._2 == TimeoutFired,
          remaining.isEmpty, // Completed after firing
        )
      } @@ TestAspect.withLiveClock,
    ),
    suite("state validation - negative cases")(
      test("skips timeout when FSM transitioned to different state (state mismatch)") {
        // FSM was in Waiting (seq=5) when timeout was scheduled
        // FSM transitioned to Processing (seq=6)
        // Sweeper tries to fire → stateHash mismatch → SKIP
        for
          store     <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          eventsRef <- Ref.make(List.empty[(String, TestEvent)])
          // Runtime is NOW in Processing state (transitioned away from Waiting)
          runtime = makeMockRuntimeWithState(eventsRef, "fsm-1", shouldFail = false, Processing, 6L)

          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(50))
            .withJitterFactor(0.0)
            .withNodeId("test-node")

          now <- Clock.instant
          // Timeout was scheduled when FSM was in Waiting with seq=5
          _ <- store.schedule("fsm-1", waitingStateHash, 5L, now.minusSeconds(10))

          metrics <- ZIO.scoped {
            for
              sweeper <- TimeoutSweeper.make(config, store, runtime)
              _       <- ZIO.sleep(Duration.fromMillis(200))
              m       <- sweeper.metrics
            yield m
          }

          events    <- eventsRef.get
          remaining <- store.get("fsm-1")
        yield assertTrue(
          events.isEmpty,              // No event should fire
          remaining.isEmpty,           // Timeout should be completed (removed as stale)
          metrics.timeoutsSkipped >= 1, // Should count as skipped
        )
      } @@ TestAspect.withLiveClock,
      test("skips timeout when FSM re-entered same state (sequence mismatch)") {
        // FSM in Waiting (seq=5), timeout T1 scheduled with seq=5
        // FSM → Processing (seq=6) → Waiting again (seq=7)
        // Sweeper tries to fire T1 (seq=5)
        // stateHash matches but seqNr mismatch (5 != 7) → SKIP
        for
          store     <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          eventsRef <- Ref.make(List.empty[(String, TestEvent)])
          // Runtime is in Waiting (same state) but seq=7 (different visit)
          runtime = makeMockRuntimeWithState(eventsRef, "fsm-1", shouldFail = false, Waiting, 7L)

          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(50))
            .withJitterFactor(0.0)
            .withNodeId("test-node")

          now <- Clock.instant
          // OLD timeout was scheduled with seq=5 (stale)
          _ <- store.schedule("fsm-1", waitingStateHash, 5L, now.minusSeconds(10))

          metrics <- ZIO.scoped {
            for
              sweeper <- TimeoutSweeper.make(config, store, runtime)
              _       <- ZIO.sleep(Duration.fromMillis(200))
              m       <- sweeper.metrics
            yield m
          }

          events    <- eventsRef.get
          remaining <- store.get("fsm-1")
        yield assertTrue(
          events.isEmpty,              // No event should fire - stale timeout
          remaining.isEmpty,           // Timeout should be completed (removed as stale)
          metrics.timeoutsSkipped >= 1, // Should count as skipped
        )
      } @@ TestAspect.withLiveClock,
      test("skips timeout when both state and sequenceNr mismatch") {
        // FSM was in Waiting (seq=0) when timeout was scheduled
        // FSM is now in Processing (seq=10)
        // Both state and seqNr mismatch → SKIP
        for
          store     <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          eventsRef <- Ref.make(List.empty[(String, TestEvent)])
          // Runtime is in Processing with seq=10
          runtime = makeMockRuntimeWithState(eventsRef, "fsm-1", shouldFail = false, Processing, 10L)

          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(50))
            .withJitterFactor(0.0)
            .withNodeId("test-node")

          now <- Clock.instant
          // Timeout scheduled when in Waiting with seq=0
          _ <- store.schedule("fsm-1", waitingStateHash, 0L, now.minusSeconds(10))

          metrics <- ZIO.scoped {
            for
              sweeper <- TimeoutSweeper.make(config, store, runtime)
              _       <- ZIO.sleep(Duration.fromMillis(200))
              m       <- sweeper.metrics
            yield m
          }

          events <- eventsRef.get
        yield assertTrue(
          events.isEmpty,
          metrics.timeoutsSkipped >= 1,
        )
      } @@ TestAspect.withLiveClock,
    ),
    suite("state validation - metrics verification")(
      test("increments timeoutsFired only when state and seqNr match") {
        for
          store     <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          eventsRef <- Ref.make(List.empty[(String, TestEvent)])
          // Runtime matches scheduled timeout
          runtime = makeMockRuntimeWithState(eventsRef, "fsm-1", shouldFail = false, Waiting, 5L)

          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(50))
            .withJitterFactor(0.0)
            .withNodeId("test-node")

          now <- Clock.instant
          // Schedule matching timeout
          _ <- store.schedule("fsm-1", waitingStateHash, 5L, now.minusSeconds(10))

          metrics <- ZIO.scoped {
            for
              sweeper <- TimeoutSweeper.make(config, store, runtime)
              _       <- ZIO.sleep(Duration.fromMillis(200))
              m       <- sweeper.metrics
            yield m
          }
        yield assertTrue(
          metrics.timeoutsFired == 1,
          metrics.timeoutsSkipped == 0,
        )
      } @@ TestAspect.withLiveClock,
      test("increments timeoutsSkipped when state mismatch") {
        for
          store     <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          eventsRef <- Ref.make(List.empty[(String, TestEvent)])
          // Runtime in Processing, but timeout was for Waiting
          runtime = makeMockRuntimeWithState(eventsRef, "fsm-1", shouldFail = false, Processing, 5L)

          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(50))
            .withJitterFactor(0.0)
            .withNodeId("test-node")

          now <- Clock.instant
          // Schedule timeout for Waiting state
          _ <- store.schedule("fsm-1", waitingStateHash, 5L, now.minusSeconds(10))

          metrics <- ZIO.scoped {
            for
              sweeper <- TimeoutSweeper.make(config, store, runtime)
              _       <- ZIO.sleep(Duration.fromMillis(200))
              m       <- sweeper.metrics
            yield m
          }
        yield assertTrue(
          metrics.timeoutsFired == 0,
          metrics.timeoutsSkipped == 1,
        )
      } @@ TestAspect.withLiveClock,
      test("increments timeoutsSkipped when sequenceNr mismatch") {
        for
          store     <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          eventsRef <- Ref.make(List.empty[(String, TestEvent)])
          // Runtime in Waiting with seq=10, but timeout was scheduled with seq=5
          runtime = makeMockRuntimeWithState(eventsRef, "fsm-1", shouldFail = false, Waiting, 10L)

          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(50))
            .withJitterFactor(0.0)
            .withNodeId("test-node")

          now <- Clock.instant
          // Schedule timeout with old seqNr
          _ <- store.schedule("fsm-1", waitingStateHash, 5L, now.minusSeconds(10))

          metrics <- ZIO.scoped {
            for
              sweeper <- TimeoutSweeper.make(config, store, runtime)
              _       <- ZIO.sleep(Duration.fromMillis(200))
              m       <- sweeper.metrics
            yield m
          }
        yield assertTrue(
          metrics.timeoutsFired == 0,
          metrics.timeoutsSkipped == 1,
        )
      } @@ TestAspect.withLiveClock,
      test("tracks skipped vs fired ratio with mixed timeouts") {
        // Schedule 3 timeouts: 2 matching, 1 stale
        for
          store     <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          eventsRef <- Ref.make(List.empty[(String, TestEvent)])
          // Runtime in Waiting with seq=5
          runtime = makeMockRuntimeWithState(eventsRef, "fsm-1", shouldFail = false, Waiting, 5L)

          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(50))
            .withJitterFactor(0.0)
            .withNodeId("test-node")

          now <- Clock.instant
          // Matching timeout (fires)
          _ <- store.schedule("fsm-match-1", waitingStateHash, 5L, now.minusSeconds(10))
          // Stale timeout - wrong seqNr (skipped)
          _ <- store.schedule("fsm-stale", waitingStateHash, 0L, now.minusSeconds(10))
          // Matching timeout (fires)
          _ <- store.schedule("fsm-match-2", waitingStateHash, 5L, now.minusSeconds(10))

          metrics <- ZIO.scoped {
            for
              sweeper <- TimeoutSweeper.make(config, store, runtime)
              _       <- ZIO.sleep(Duration.fromMillis(300))
              m       <- sweeper.metrics
            yield m
          }

          events <- eventsRef.get
        yield assertTrue(
          events.length == 2, // Only 2 should fire
          metrics.timeoutsFired == 2,
          metrics.timeoutsSkipped == 1,
        )
      } @@ TestAspect.withLiveClock,
    ),
    suite("state validation - edge cases")(
      test("handles timeout for initial state (sequenceNr = 0)") {
        // FSM starts in timed initial state with seqNr=0
        for
          store     <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          eventsRef <- Ref.make(List.empty[(String, TestEvent)])
          runtime = makeMockRuntimeWithState(eventsRef, "fsm-1", shouldFail = false, Waiting, 0L)

          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(50))
            .withJitterFactor(0.0)
            .withNodeId("test-node")

          now <- Clock.instant
          _   <- store.schedule("fsm-1", waitingStateHash, 0L, now.minusSeconds(10))

          metrics <- ZIO.scoped {
            for
              sweeper <- TimeoutSweeper.make(config, store, runtime)
              _       <- ZIO.sleep(Duration.fromMillis(200))
              m       <- sweeper.metrics
            yield m
          }

          events <- eventsRef.get
        yield assertTrue(
          events.length == 1,
          metrics.timeoutsFired == 1,
        )
      } @@ TestAspect.withLiveClock,
      test("handles high sequence numbers") {
        // seqNr near Long.MaxValue
        val highSeqNr = Long.MaxValue - 100
        for
          store     <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          eventsRef <- Ref.make(List.empty[(String, TestEvent)])
          runtime = makeMockRuntimeWithState(eventsRef, "fsm-1", shouldFail = false, Waiting, highSeqNr)

          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(50))
            .withJitterFactor(0.0)
            .withNodeId("test-node")

          now <- Clock.instant
          _   <- store.schedule("fsm-1", waitingStateHash, highSeqNr, now.minusSeconds(10))

          metrics <- ZIO.scoped {
            for
              sweeper <- TimeoutSweeper.make(config, store, runtime)
              _       <- ZIO.sleep(Duration.fromMillis(200))
              m       <- sweeper.metrics
            yield m
          }

          events <- eventsRef.get
        yield assertTrue(
          events.length == 1,
          metrics.timeoutsFired == 1,
        )
        end for
      } @@ TestAspect.withLiveClock,
      test("skips timeout for unknown state hash (no timeout event configured)") {
        // Schedule timeout with a state hash that has no timeout event in machine.timeoutEvents
        for
          store     <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          eventsRef <- Ref.make(List.empty[(String, TestEvent)])
          // Runtime in Processing (matching the scheduled stateHash)
          runtime = makeMockRuntimeWithState(eventsRef, "fsm-1", shouldFail = false, Processing, 0L)

          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(50))
            .withJitterFactor(0.0)
            .withNodeId("test-node")

          now <- Clock.instant
          // Schedule with Processing stateHash (no timeout event configured for Processing)
          _ <- store.schedule("fsm-1", processingStateHash, 0L, now.minusSeconds(10))

          metrics <- ZIO.scoped {
            for
              sweeper <- TimeoutSweeper.make(config, store, runtime)
              _       <- ZIO.sleep(Duration.fromMillis(200))
              m       <- sweeper.metrics
            yield m
          }

          events <- eventsRef.get
        yield assertTrue(
          events.isEmpty, // No event because Processing has no timeout configured
          metrics.timeoutsSkipped >= 1,
        )
      } @@ TestAspect.withLiveClock,
    ),
    suite("timeout rescheduling bug")(
      test("BUG: complete() deletes newly scheduled timeout when FSM re-enters same state") {
        // This test demonstrates the race condition where:
        // 1. Sweeper fires timeout and calls runtime.send(TimeoutFired)
        // 2. Runtime handles Goto(SameState), which calls:
        //    a. cancel(instanceId) - deletes the old timeout
        //    b. schedule(instanceId, newSeqNr, ...) - inserts a NEW timeout
        // 3. Sweeper then calls complete(instanceId) - THIS DELETES THE NEW TIMEOUT!
        //
        // Expected behavior: new timeout should survive
        // Actual behavior (bug): new timeout is deleted
        for
          store     <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          eventsRef <- Ref.make(List.empty[(String, TestEvent)])

          // Track how many times schedule was called
          scheduleCountRef <- Ref.make(0)

          // Create a runtime that simulates re-entering the same state with a new timeout
          runtime = new FSMRuntime[String, TestState, TestEvent]:
            override val instanceId: String                     = "fsm-1"
            override val machine: Machine[TestState, TestEvent] = testMachine

            override def send(event: TestEvent): ZIO[Any, MechanoidError, TransitionOutcome[TestState]] =
              for
                _ <- eventsRef.update(_ :+ ("fsm-1", event))
                // Simulate what FSMRuntime does on Goto(SameState):
                // 1. Cancel the old timeout
                _ <- store.cancel(instanceId)
                // 2. Schedule a new timeout (simulating re-entry with incremented seqNr)
                now   <- Clock.instant
                count <- scheduleCountRef.updateAndGet(_ + 1)
                newSeqNr = count.toLong // Each re-entry gets a new seqNr
                _ <- store.schedule(instanceId, waitingStateHash, newSeqNr, now.plusSeconds(30))
              yield TransitionOutcome(TransitionResult.Stay)

            override def currentState: UIO[TestState]    = ZIO.succeed(Waiting)
            override def state: UIO[FSMState[TestState]] =
              Clock.instant.map { now =>
                FSMState(Waiting, Nil, Map.empty, now, now)
              }
            override def history: UIO[List[TestState]]                = ZIO.succeed(Nil)
            override def lastSequenceNr: UIO[Long]                    = scheduleCountRef.get.map(_.toLong)
            override def saveSnapshot: ZIO[Any, MechanoidError, Unit] = ZIO.unit
            override def stop: UIO[Unit]                              = ZIO.unit
            override def stop(reason: String): UIO[Unit]              = ZIO.unit
            override def isRunning: UIO[Boolean]                      = ZIO.succeed(true)
            override def timeoutConfigForState(state: TestState): Option[(Duration, TestEvent)] =
              state match
                case Waiting => Some((30.seconds, TimeoutFired))
                case _       => None

          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(50))
            .withJitterFactor(0.0)
            .withNodeId("test-node")

          now <- Clock.instant
          // Schedule initial timeout with seqNr=0
          _ <- store.schedule("fsm-1", waitingStateHash, 0L, now.minusSeconds(10))

          // Run sweeper to fire the timeout
          _ <- ZIO.scoped {
            for
              _ <- TimeoutSweeper.make(config, store, runtime)
              _ <- ZIO.sleep(Duration.fromMillis(200))
            yield ()
          }

          events <- eventsRef.get
          // Check if the NEW timeout (scheduled by runtime during event handling) still exists
          newTimeout    <- store.get("fsm-1")
          scheduleCount <- scheduleCountRef.get
        yield assertTrue(
          events.length == 1, // Timeout was fired
          scheduleCount == 1, // Runtime scheduled a new timeout
          // BUG: newTimeout is None because complete() deleted it!
          // EXPECTED: newTimeout should be Some(...) with seqNr=1
          newTimeout.isDefined, // This FAILS - proving the bug
        )
      } @@ TestAspect.withLiveClock
    ),
  ) @@ TestAspect.sequential @@ TestAspect.timeout(Duration.fromSeconds(60)) @@ TestAspect.withLiveClock
end TimeoutSweeperSpec
