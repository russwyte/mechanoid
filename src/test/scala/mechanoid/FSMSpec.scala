package mechanoid

import zio.*
import zio.test.*
import scala.concurrent.duration.*

object FSMSpec extends ZIOSpecDefault:

  // Define states for a simple traffic light
  enum TrafficLight extends MState:
    case Red, Yellow, Green

  // Define events
  enum TrafficEvent extends MEvent:
    case Timer, Emergency

  import TrafficLight.*
  import TrafficEvent.*

  // Complex state hierarchy with data-carrying states (for variance tests)
  sealed trait DocumentState                 extends MState
  case object Draft                          extends DocumentState
  case class UnderReview(reviewerId: String) extends DocumentState
  case class Approved(approvedBy: String)    extends DocumentState
  case object Published                      extends DocumentState

  sealed trait DocumentEvent                     extends MEvent
  case class SubmitForReview(reviewerId: String) extends DocumentEvent
  case class ApproveDoc(approvedBy: String)      extends DocumentEvent
  case object PublishDoc                         extends DocumentEvent
  case object Reject                             extends DocumentEvent

  // Enum with parameterized cases (Scala 3 feature)
  enum ConnectionState extends MState:
    case Disconnected
    case Connecting(attempt: Int)
    case Connected(sessionId: String)
    case Failed(reason: String)

  enum ConnectionEvent extends MEvent:
    case Connect
    case Success(sessionId: String)
    case Failure(reason: String)
    case Disconnect

  def spec = suite("FSM Spec")(
    test("should start in initial state") {
      val definition = FSMDefinition[TrafficLight, TrafficEvent]
        .when(Red)
        .on(Timer)
        .goto(Green)

      ZIO.scoped {
        for
          fsm   <- definition.build(Red)
          state <- fsm.currentState
        yield assertTrue(state == Red)
      }
    },
    test("should transition on valid event") {
      val definition = FSMDefinition[TrafficLight, TrafficEvent]
        .when(Red)
        .on(Timer)
        .goto(Green)

      ZIO.scoped {
        for
          fsm    <- definition.build(Red)
          result <- fsm.send(Timer)
          state  <- fsm.currentState
        yield assertTrue(
          result == TransitionResult.Goto(Green),
          state == Green,
        )
      }
    },
    test("should follow transition sequence") {
      val definition = FSMDefinition[TrafficLight, TrafficEvent]
        .when(Red)
        .on(Timer)
        .goto(Green)
        .when(Green)
        .on(Timer)
        .goto(Yellow)
        .when(Yellow)
        .on(Timer)
        .goto(Red)

      ZIO.scoped {
        for
          fsm     <- definition.build(Red)
          _       <- fsm.send(Timer) // Red -> Green
          _       <- fsm.send(Timer) // Green -> Yellow
          _       <- fsm.send(Timer) // Yellow -> Red
          state   <- fsm.currentState
          history <- fsm.history
        yield assertTrue(
          state == Red,
          history == List(Yellow, Green, Red),
        )
      }
    },
    test("should stay in state when configured") {
      val definition = FSMDefinition[TrafficLight, TrafficEvent]
        .when(Red)
        .on(Emergency)
        .stay

      ZIO.scoped {
        for
          fsm    <- definition.build(Red)
          result <- fsm.send(Emergency)
          state  <- fsm.currentState
        yield assertTrue(
          result == TransitionResult.Stay,
          state == Red,
        )
      }
    },
    test("should fail on invalid transition") {
      val definition = FSMDefinition[TrafficLight, TrafficEvent]
        .when(Red)
        .on(Timer)
        .goto(Green)

      ZIO.scoped {
        for
          fsm   <- definition.build(Red)
          _     <- fsm.send(Timer)      // Red -> Green
          error <- fsm.send(Timer).flip // Green + Timer = undefined
        yield assertTrue(error.isInstanceOf[InvalidTransitionError])
      }
    },
    test("should execute transition when guard passes") {
      val guard: ZIO[Any, Nothing, Boolean]                                   = ZIO.succeed(true)
      val definition: FSMDefinition[TrafficLight, TrafficEvent, Any, Nothing] =
        FSMDefinition[TrafficLight, TrafficEvent]
          .when(Red)
          .on(Timer)
          .when(guard)
          .goto(Green)

      ZIO.scoped {
        for
          fsm   <- definition.build(Red)
          _     <- fsm.send(Timer)
          state <- fsm.currentState
        yield assertTrue(state == Green)
      }
    },
    test("should reject transition when guard fails") {
      val guard: ZIO[Any, Nothing, Boolean]                                   = ZIO.succeed(false)
      val definition: FSMDefinition[TrafficLight, TrafficEvent, Any, Nothing] =
        FSMDefinition[TrafficLight, TrafficEvent]
          .when(Red)
          .on(Timer)
          .when(guard)
          .goto(Green)

      ZIO.scoped {
        for
          fsm   <- definition.build(Red)
          error <- fsm.send(Timer).flip
        yield assertTrue(error.isInstanceOf[GuardRejectedError])
      }
    },

    // Entry/Exit action tests
    test("should execute entry action on initial state") {
      for
        entryRef <- Ref.make(false)
        definition: FSMDefinition[TrafficLight, TrafficEvent, Any, Nothing] =
          FSMDefinition[TrafficLight, TrafficEvent]
            .when(Red)
            .on(Timer)
            .goto(Green)
            .onState(Red)
            .onEntry(entryRef.set(true))
            .done
        _        <- ZIO.scoped(definition.build(Red))
        entryRan <- entryRef.get
      yield assertTrue(entryRan)
    },
    test("should execute entry and exit actions on transition") {
      for
        log <- Ref.make(List.empty[String])
        definition: FSMDefinition[TrafficLight, TrafficEvent, Any, Nothing] =
          FSMDefinition[TrafficLight, TrafficEvent]
            .when(Red)
            .on(Timer)
            .goto(Green)
            .onState(Red)
            .onExit(log.update(_ :+ "exit-red"))
            .done
            .onState(Green)
            .onEntry(log.update(_ :+ "entry-green"))
            .done
        _ <- ZIO.scoped {
          for
            fsm <- definition.build(Red)
            _   <- fsm.send(Timer)
          yield ()
        }
        events <- log.get
      yield assertTrue(events == List("exit-red", "entry-green"))
    },

    // Stop tests
    test("should stop FSM and report not running") {
      val definition = FSMDefinition[TrafficLight, TrafficEvent]
        .when(Red)
        .on(Timer)
        .goto(Green)

      ZIO.scoped {
        for
          fsm           <- definition.build(Red)
          runningBefore <- fsm.isRunning
          _             <- fsm.stop
          runningAfter  <- fsm.isRunning
        yield assertTrue(
          runningBefore,
          !runningAfter,
        )
      }
    },
    test("should return Stop result after FSM is stopped") {
      val definition = FSMDefinition[TrafficLight, TrafficEvent]
        .when(Red)
        .on(Timer)
        .goto(Green)

      ZIO.scoped {
        for
          fsm    <- definition.build(Red)
          _      <- fsm.stop
          result <- fsm.send(Timer)
        yield assertTrue(result.isInstanceOf[TransitionResult.Stop[?]])
      }
    },

    // Subscription tests
    test("should publish state changes to subscribers") {
      val definition = FSMDefinition[TrafficLight, TrafficEvent]
        .when(Red)
        .on(Timer)
        .goto(Green)
        .when(Green)
        .on(Timer)
        .goto(Yellow)

      ZIO.scoped {
        for
          fsm <- definition.build(Red)
          // Use a queue to collect state changes
          queue <- Queue.unbounded[StateChange[TrafficLight, TrafficEvent | Timeout.type]]
          // Start collecting in background
          _ <- fsm.subscribe.foreach(queue.offer).fork
          // Give subscriber time to connect - needs to be long enough for slow CI
          _ <- ZIO.sleep(200.millis)
          _ <- fsm.send(Timer) // Red -> Green
          _ <- fsm.send(Timer) // Green -> Yellow
          // Wait for exactly 2 events with timeout
          change1 <- queue.take.timeout(5.seconds).someOrFail(new RuntimeException("Timeout waiting for change 1"))
          change2 <- queue.take.timeout(5.seconds).someOrFail(new RuntimeException("Timeout waiting for change 2"))
        yield assertTrue(
          change1.from == Red,
          change1.to == Green,
          change2.from == Green,
          change2.to == Yellow,
        )
      }
    } @@ TestAspect.flaky(3),

    // FSMState metadata tests
    test("should track state metadata") {
      val definition = FSMDefinition[TrafficLight, TrafficEvent]
        .when(Red)
        .on(Timer)
        .goto(Green)

      ZIO.scoped {
        for
          fsm         <- definition.build(Red)
          stateBefore <- fsm.state
          _           <- fsm.send(Timer)
          stateAfter  <- fsm.state
        yield assertTrue(
          stateBefore.current == Red,
          stateBefore.history.isEmpty,
          stateAfter.current == Green,
          stateAfter.history == List(Red),
          stateAfter.transitionCount == 1,
        )
      }
    },

    // Test entry action works with direct transition
    test("should run entry action when transitioning directly") {
      for
        enteredYellow <- Ref.make(false)
        definition: FSMDefinition[TrafficLight, TrafficEvent, Any, Nothing] =
          FSMDefinition[TrafficLight, TrafficEvent]
            .when(Red)
            .on(Timer)
            .goto(Yellow)
            .onState(Yellow)
            .onEntry(enteredYellow.set(true))
            .done
        result <- ZIO.scoped {
          for
            fsm     <- definition.build(Red)
            _       <- fsm.send(Timer) // Red -> Yellow
            state   <- fsm.currentState
            entered <- enteredYellow.get
          yield (state, entered)
        }
      yield assertTrue(
        result._1 == Yellow,
        result._2, // Entry action ran
      )
    },

    // Timeout tests - using live clock
    test("should trigger timeout event after duration") {
      val definition = FSMDefinition[TrafficLight, TrafficEvent]
        .when(Red)
        .on(Timer)
        .goto(Green)
        .when(Red)
        .onTimeout
        .goto(Yellow)
        .withTimeout(Red, 50.millis)

      ZIO.scoped {
        for
          fsm         <- definition.build(Red)
          stateBefore <- fsm.currentState
          // Wait for timeout to trigger
          _     <- ZIO.sleep(100.millis)
          state <- fsm.currentState
        yield assertTrue(
          stateBefore == Red,
          state == Yellow,
        )
      }
    },
    test("should cancel timeout when transitioning to new state") {
      val definition = FSMDefinition[TrafficLight, TrafficEvent]
        .when(Red)
        .on(Timer)
        .goto(Green)
        .when(Red)
        .onTimeout
        .goto(Yellow)
        .withTimeout(Red, 100.millis)

      ZIO.scoped {
        for
          fsm <- definition.build(Red)
          // Transition before timeout
          _ <- fsm.send(Timer) // Red -> Green
          // Wait past original timeout
          _     <- ZIO.sleep(150.millis)
          state <- fsm.currentState
        yield assertTrue(state == Green) // Should NOT be Yellow
      }
    },

    // Dynamic guard tests
    test("should evaluate guard dynamically") {
      for
        permitRef <- Ref.make(false)
        guard                                                               = permitRef.get
        definition: FSMDefinition[TrafficLight, TrafficEvent, Any, Nothing] =
          FSMDefinition[TrafficLight, TrafficEvent]
            .when(Red)
            .on(Timer)
            .when(guard)
            .goto(Green)
        result <- ZIO.scoped {
          for
            fsm <- definition.build(Red)
            // First attempt - guard fails
            error1 <- fsm.send(Timer).flip
            // Enable guard
            _ <- permitRef.set(true)
            // Second attempt - guard passes
            _     <- fsm.send(Timer)
            state <- fsm.currentState
          yield (error1, state)
        }
      yield assertTrue(
        result._1.isInstanceOf[GuardRejectedError],
        result._2 == Green,
      )
    },

    // Stay does not add to history
    test("should not add to history on stay") {
      val definition = FSMDefinition[TrafficLight, TrafficEvent]
        .when(Red)
        .on(Emergency)
        .stay

      ZIO.scoped {
        for
          fsm     <- definition.build(Red)
          _       <- fsm.send(Emergency)
          _       <- fsm.send(Emergency)
          history <- fsm.history
        yield assertTrue(history.isEmpty)
      }
    },

    // Ergonomic guard tests
    test("should use simple boolean guard") {
      // No ZIO wrapping needed for simple guards
      val definition = FSMDefinition[TrafficLight, TrafficEvent]
        .when(Red)
        .on(Timer)
        .when(true) // Simple boolean - no ZIO.succeed needed
        .goto(Green)
        .when(Green)
        .on(Timer)
        .when(false) // Guard fails
        .goto(Yellow)

      ZIO.scoped {
        for
          fsm    <- definition.build(Red)
          _      <- fsm.send(Timer)      // Should succeed (guard is true)
          state1 <- fsm.currentState
          error  <- fsm.send(Timer).flip // Should fail (guard is false)
        yield assertTrue(
          state1 == Green,
          error.isInstanceOf[GuardRejectedError],
        )
      }
    },
    test("should use whenEval for lazy guard evaluation") {
      val definition = FSMDefinition[TrafficLight, TrafficEvent]
        .when(Red)
        .on(Timer)
        .whenEval {
          // This runs inside ZIO.succeed, so we use unsafe get
          // In real code, use `when(ZIO[...])` for effectful guards
          true
        }
        .goto(Green)
      for result <- ZIO.scoped {
          for
            fsm   <- definition.build(Red)
            _     <- fsm.send(Timer)
            state <- fsm.currentState
          yield state
        }
      yield assertTrue(result == Green)
    },

    // Sealed trait hierarchy tests
    test("should handle sealed trait hierarchy with case classes") {
      // Build FSM with transitions between different state types
      val definition = FSMDefinition[DocumentState, DocumentEvent]
        .when(Draft)
        .on(SubmitForReview("reviewer1"))
        .goto(UnderReview("reviewer1"))
        .when(UnderReview("reviewer1"))
        .on(ApproveDoc("manager"))
        .goto(Approved("manager"))
        .when(Approved("manager"))
        .on(PublishDoc)
        .goto(Published)

      ZIO.scoped {
        for
          fsm        <- definition.build(Draft)
          _          <- fsm.send(SubmitForReview("reviewer1"))
          state1     <- fsm.currentState
          _          <- fsm.send(ApproveDoc("manager"))
          state2     <- fsm.currentState
          _          <- fsm.send(PublishDoc)
          finalState <- fsm.currentState
          history    <- fsm.history
        yield assertTrue(
          state1 == UnderReview("reviewer1"),
          state2 == Approved("manager"),
          finalState == Published,
          history == List(Approved("manager"), UnderReview("reviewer1"), Draft),
        )
      }
    },
    test("should handle enum with parameterized cases") {
      import ConnectionState.*
      import ConnectionEvent.*

      val definition = FSMDefinition[ConnectionState, ConnectionEvent]
        .when(Disconnected)
        .on(Connect)
        .goto(Connecting(1))
        .when(Connecting(1))
        .on(Success("session-123"))
        .goto(Connected("session-123"))
        .when(Connecting(1))
        .on(Failure("timeout"))
        .goto(Failed("timeout"))
        .when(Connected("session-123"))
        .on(Disconnect)
        .goto(Disconnected)

      ZIO.scoped {
        for
          fsm    <- definition.build(Disconnected)
          _      <- fsm.send(Connect)
          state1 <- fsm.currentState
          _      <- fsm.send(Success("session-123"))
          state2 <- fsm.currentState
        yield assertTrue(
          state1 == Connecting(1),
          state2 == Connected("session-123"),
        )
      }
    },
    test("rich states - transition matches by shape, not exact value") {
      // This test demonstrates the key feature: transitions match by state "shape" (ordinal),
      // not by exact value. So a transition defined for Failed("") will match Failed("timeout"),
      // Failed("network error"), or any other Failed(_).
      import ConnectionState.*
      import ConnectionEvent.*

      // Define transitions with "template" values - actual data doesn't matter for matching
      val definition = FSMDefinition[ConnectionState, ConnectionEvent]
        .when(Failed("")) // Will match ANY Failed(_)
        .on(Connect)
        .goto(Connecting(1))
        .when(Connecting(0)) // Will match ANY Connecting(_)
        .on(Failure(""))     // Will match ANY Failure(_) - events also use ordinal matching
        .goto(Failed("retry"))
        .when(Connected("")) // Will match ANY Connected(_)
        .on(Disconnect)
        .goto(Disconnected)

      ZIO.scoped {
        for
          // Start in a Failed state with specific data
          fsm <- definition.build(Failed("initial timeout error"))

          // Send Connect - should match the transition from Failed("") because both have ordinal 3
          _      <- fsm.send(Connect)
          state1 <- fsm.currentState

          // Now we're in Connecting(1), send Failure to go back to Failed
          _      <- fsm.send(Failure(""))
          state2 <- fsm.currentState

          // Even though we defined transition from Connected(""), let's verify
          // by starting fresh and reaching Connected with different data
          fsm2   <- definition.build(Connected("session-999"))
          _      <- fsm2.send(Disconnect)
          state3 <- fsm2.currentState
        yield assertTrue(
          state1 == Connecting(1),   // Transition worked even though we started with different Failed
          state2 == Failed("retry"), // Went to the specified target state
          state3 == Disconnected,    // Connected("session-999") matched Connected("")
        )
      }
    },
    test("rich states - entry/exit actions work with shape matching") {
      import ConnectionState.*
      import ConnectionEvent.*

      for
        actionLog <- Ref.make(List.empty[String])

        // Define entry/exit actions with template states - they match by shape
        definition: FSMDefinition[ConnectionState, ConnectionEvent, Any, Nothing] =
          FSMDefinition[ConnectionState, ConnectionEvent]
            .when(Disconnected)
            .on(Connect)
            .goto(Connecting(1))
            .when(Connecting(0)) // Template - matches any Connecting(_)
            .on(Success(""))
            .goto(Connected("session"))
            // Actions defined with template values match ANY state of that shape
            .onState(Connecting(999))
            .onEntry(actionLog.update(_ :+ "entering-connecting"))
            .onExit(actionLog.update(_ :+ "exiting-connecting"))
            .done
            .onState(Connected("template"))
            .onEntry(actionLog.update(_ :+ "entering-connected"))
            .done

        result <- ZIO.scoped {
          for
            fsm <- definition.build(Disconnected)
            _   <- fsm.send(Connect)     // -> Connecting(1), entry action should run
            _   <- fsm.send(Success("")) // -> Connected("session"), exit then entry actions
            log <- actionLog.get
          yield log
        }
      yield assertTrue(
        result == List("entering-connecting", "exiting-connecting", "entering-connected")
      )
      end for
    },
    test("rich events - transition matches by event shape, not exact value") {
      // This test verifies that events with data (like PaymentSucceeded(transactionId))
      // match by their shape (ordinal), not by exact value. This is critical for
      // event sourcing where you define transitions with template events but send
      // events with real data.
      import ConnectionState.*
      import ConnectionEvent.*

      // Define transitions with "template" event values
      val definition = FSMDefinition[ConnectionState, ConnectionEvent]
        .when(Disconnected)
        .on(Connect) // Simple case object
        .goto(Connecting(1))
        .when(Connecting(0))
        .on(Success("")) // Template: empty string
        .goto(Connected("session-from-event"))
        .when(Connecting(0))
        .on(Failure("")) // Template: empty string
        .goto(Failed("failure-happened"))

      ZIO.scoped {
        for
          fsm <- definition.build(Disconnected)

          // Send Connect (case object, works as before)
          _      <- fsm.send(Connect)
          state1 <- fsm.currentState

          // Send Success with DIFFERENT data than the template - should still match!
          // This is the key test: Success("real-session-id-123") should match Success("")
          _      <- fsm.send(Success("real-session-id-123"))
          state2 <- fsm.currentState

          // Start fresh and test Failure with different data
          fsm2   <- definition.build(Connecting(5)) // Different retry count
          _      <- fsm2.send(Failure("network timeout after 30s"))
          state3 <- fsm2.currentState
        yield assertTrue(
          state1 == Connecting(1),
          state2 == Connected("session-from-event"), // Matched even though event had different data
          state3 == Failed("failure-happened"),      // Matched Failure("network...") to Failure("")
        )
      }
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(
    5.seconds
  )
end FSMSpec
