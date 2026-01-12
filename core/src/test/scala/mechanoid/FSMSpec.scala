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
      val definition = fsm[TrafficLight, TrafficEvent]
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
      val definition = fsm[TrafficLight, TrafficEvent]
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
      val definition = fsm[TrafficLight, TrafficEvent]
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
      val definition = fsm[TrafficLight, TrafficEvent]
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
      val definition = fsm[TrafficLight, TrafficEvent]
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
    // Entry/Exit action tests
    test("should execute entry action on initial state") {
      for
        entryRef <- Ref.make(false)
        definition =
          fsm[TrafficLight, TrafficEvent]
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
        definition =
          fsm[TrafficLight, TrafficEvent]
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
      val definition = fsm[TrafficLight, TrafficEvent]
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
      val definition = fsm[TrafficLight, TrafficEvent]
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

    // FSMState metadata tests
    test("should track state metadata") {
      val definition = fsm[TrafficLight, TrafficEvent]
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
        definition =
          fsm[TrafficLight, TrafficEvent]
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
      val definition = fsm[TrafficLight, TrafficEvent]
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
      val definition = fsm[TrafficLight, TrafficEvent]
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

    // Stay does not add to history
    test("should not add to history on stay") {
      val definition = fsm[TrafficLight, TrafficEvent]
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

    // Sealed trait hierarchy tests
    test("should handle sealed trait hierarchy with case classes") {
      // Build FSM with transitions between different state types
      val definition = fsm[DocumentState, DocumentEvent]
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

      val definition = fsm[ConnectionState, ConnectionEvent]
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
      val definition = fsm[ConnectionState, ConnectionEvent]
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
        definition =
          fsm[ConnectionState, ConnectionEvent]
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
      val definition = fsm[ConnectionState, ConnectionEvent]
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

    // whenAny tests - hierarchical state transitions
    suite("whenAny - hierarchical transitions")(
      test("whenAny applies to all leaf states under a parent") {
        // Define hierarchical states
        sealed trait HierState extends MState
        case object Active     extends HierState
        sealed trait Inactive  extends HierState
        case object Paused     extends Inactive
        case object Stopped    extends Inactive

        sealed trait HierEvent extends MEvent
        case object Activate   extends HierEvent
        case object Pause      extends HierEvent
        case object Stop       extends HierEvent
        case object Resume     extends HierEvent

        // whenAny[Inactive] should apply to both Paused and Stopped
        val definition = fsm[HierState, HierEvent]
          .when(Active)
          .on(Pause)
          .goto(Paused)
          .when(Active)
          .on(Stop)
          .goto(Stopped)
          .whenAny[Inactive]
          .on(Activate)
          .goto(Active) // Both Paused and Stopped can Activate -> Active

        ZIO.scoped {
          for
            // Test from Paused
            fsm1   <- definition.build(Paused)
            _      <- fsm1.send(Activate)
            state1 <- fsm1.currentState

            // Test from Stopped
            fsm2   <- definition.build(Stopped)
            _      <- fsm2.send(Activate)
            state2 <- fsm2.currentState
          yield assertTrue(
            state1 == Active,
            state2 == Active,
          )
        }
      },
      test("leaf-level transition overrides whenAny") {
        sealed trait OverrideState extends MState
        case object Ready          extends OverrideState
        sealed trait Processing    extends OverrideState
        case object Running        extends Processing
        case object Waiting        extends Processing
        case object Done           extends OverrideState

        sealed trait OverrideEvent extends MEvent
        case object Cancel         extends OverrideEvent
        case object Start          extends OverrideEvent

        // Default: any Processing state cancels to Ready
        // Override: Running cancels to Done (different behavior)
        val definition = fsm[OverrideState, OverrideEvent]
          .when(Ready)
          .on(Start)
          .goto(Running)
          .whenAny[Processing]
          .on(Cancel)
          .goto(Ready) // Default for all Processing
          .when(Running)
          .on(Cancel)
          .goto(Done) // Override for Running specifically

        ZIO.scoped {
          for
            // Running should use override -> Done
            fsm1   <- definition.build(Running)
            _      <- fsm1.send(Cancel)
            state1 <- fsm1.currentState

            // Waiting should use default -> Ready
            fsm2   <- definition.build(Waiting)
            _      <- fsm2.send(Cancel)
            state2 <- fsm2.currentState
          yield assertTrue(
            state1 == Done, // Override won
            state2 == Ready, // Default from whenAny
          )
        }
      },
      test("whenAny works with stay") {
        sealed trait StayState extends MState
        case object Idle       extends StayState
        sealed trait Busy      extends StayState
        case object Working    extends Busy
        case object Blocked    extends Busy

        sealed trait StayEvent extends MEvent
        case object Ping       extends StayEvent
        case object Start      extends StayEvent

        val definition = fsm[StayState, StayEvent]
          .when(Idle)
          .on(Start)
          .goto(Working)
          .whenAny[Busy]
          .on(Ping)
          .stay // All Busy states stay on Ping

        ZIO.scoped {
          for
            fsm    <- definition.build(Working)
            result <- fsm.send(Ping)
            state  <- fsm.currentState
          yield assertTrue(
            result == TransitionResult.Stay,
            state == Working,
          )
        }
      },
      test("whenAny works with stop") {
        sealed trait StopState       extends MState
        case object Running          extends StopState
        sealed trait Error           extends StopState
        case object RecoverableError extends Error
        case object FatalError       extends Error

        sealed trait StopEvent extends MEvent
        case object Fail       extends StopEvent
        case object Shutdown   extends StopEvent

        val definition = fsm[StopState, StopEvent]
          .when(Running)
          .on(Fail)
          .goto(FatalError)
          .whenAny[Error]
          .on(Shutdown)
          .stop // All Error states can shutdown

        ZIO.scoped {
          for
            fsm     <- definition.build(FatalError)
            result  <- fsm.send(Shutdown)
            running <- fsm.isRunning
          yield assertTrue(
            result.isInstanceOf[TransitionResult.Stop[?]],
            !running,
          )
        }
      },
      test("whenAny with nested hierarchy") {
        // Three-level hierarchy
        sealed trait DeepState extends MState
        case object Root       extends DeepState
        sealed trait Level1    extends DeepState
        sealed trait Level2    extends Level1
        case object Leaf1      extends Level1
        case object Leaf2A     extends Level2
        case object Leaf2B     extends Level2

        sealed trait DeepEvent extends MEvent
        case object Reset      extends DeepEvent
        case object GoDeep     extends DeepEvent

        // whenAny[Level1] should hit Leaf1, Leaf2A, and Leaf2B
        val definition = fsm[DeepState, DeepEvent]
          .when(Root)
          .on(GoDeep)
          .goto(Leaf2A)
          .whenAny[Level1]
          .on(Reset)
          .goto(Root) // All Level1 descendants (including Level2 leaves)

        ZIO.scoped {
          for
            fsm1   <- definition.build(Leaf1)
            _      <- fsm1.send(Reset)
            state1 <- fsm1.currentState

            fsm2   <- definition.build(Leaf2A)
            _      <- fsm2.send(Reset)
            state2 <- fsm2.currentState

            fsm3   <- definition.build(Leaf2B)
            _      <- fsm3.send(Reset)
            state3 <- fsm3.currentState
          yield assertTrue(
            state1 == Root,
            state2 == Root,
            state3 == Root,
          )
        }
      },
      test("whenAny[T](states*) applies only to listed states with type safety") {
        sealed trait SelectState extends MState
        case object Idle         extends SelectState
        sealed trait Active      extends SelectState
        case object Running      extends Active
        case object Paused       extends Active
        case object Blocked      extends Active

        sealed trait SelectEvent extends MEvent
        case object Cancel       extends SelectEvent
        case object Start        extends SelectEvent

        // Only Running and Paused can cancel, NOT Blocked (even though it's also Active)
        val definition = fsm[SelectState, SelectEvent]
          .when(Idle)
          .on(Start)
          .goto(Running)
          .whenAny[Active](Running, Paused) // Type-safe: must be Active subtypes
          .on(Cancel)
          .goto(Idle)

        ZIO.scoped {
          for
            // Running can cancel
            fsm1   <- definition.build(Running)
            _      <- fsm1.send(Cancel)
            state1 <- fsm1.currentState

            // Paused can cancel
            fsm2   <- definition.build(Paused)
            _      <- fsm2.send(Cancel)
            state2 <- fsm2.currentState

            // Blocked cannot cancel (invalid transition)
            fsm3  <- definition.build(Blocked)
            error <- fsm3.send(Cancel).flip
          yield assertTrue(
            state1 == Idle,
            state2 == Idle,
            error.isInstanceOf[InvalidTransitionError],
          )
        }
      },
      test("whenStates applies to explicit list regardless of hierarchy") {
        sealed trait MixedState extends MState
        case object StateA      extends MixedState
        sealed trait GroupB     extends MixedState
        case object StateB1     extends GroupB
        case object StateB2     extends GroupB
        sealed trait GroupC     extends MixedState
        case object StateC1     extends GroupC
        case object StateC2     extends GroupC

        sealed trait MixedEvent extends MEvent
        case object Reset       extends MixedEvent

        // Mix states from different groups - not possible with whenAny[T]
        val definition = fsm[MixedState, MixedEvent]
          .whenStates(StateA, StateB1, StateC2) // From different hierarchy branches
          .on(Reset)
          .goto(StateA)

        ZIO.scoped {
          for
            // StateA can reset
            fsm1   <- definition.build(StateA)
            _      <- fsm1.send(Reset)
            state1 <- fsm1.currentState

            // StateB1 can reset
            fsm2   <- definition.build(StateB1)
            _      <- fsm2.send(Reset)
            state2 <- fsm2.currentState

            // StateC2 can reset
            fsm3   <- definition.build(StateC2)
            _      <- fsm3.send(Reset)
            state3 <- fsm3.currentState

            // StateB2 cannot reset (not in list)
            fsm4  <- definition.build(StateB2)
            error <- fsm4.send(Reset).flip
          yield assertTrue(
            state1 == StateA,
            state2 == StateA,
            state3 == StateA,
            error.isInstanceOf[InvalidTransitionError],
          )
        }
      },
    ),
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(
    5.seconds
  )
end FSMSpec
