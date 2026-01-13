package mechanoid

import zio.*
import zio.test.*
import scala.concurrent.duration.*
import mechanoid.dsl.TypedDSL

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
      val definition = TypedDSL[TrafficLight, TrafficEvent]
        .when[Red.type]
        .on[Timer.type]
        .goto(Green)
        .build

      ZIO.scoped {
        for
          fsm   <- definition.build(Red)
          state <- fsm.currentState
        yield assertTrue(state == Red)
      }
    },
    test("should transition on valid event") {
      val definition = TypedDSL[TrafficLight, TrafficEvent]
        .when[Red.type]
        .on[Timer.type]
        .goto(Green)
        .build

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
      val definition = TypedDSL[TrafficLight, TrafficEvent]
        .when[Red.type]
        .on[Timer.type]
        .goto(Green)
        .when[Green.type]
        .on[Timer.type]
        .goto(Yellow)
        .when[Yellow.type]
        .on[Timer.type]
        .goto(Red)
        .build

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
      val definition = TypedDSL[TrafficLight, TrafficEvent]
        .when[Red.type]
        .on[Emergency.type]
        .stay
        .build

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
      val definition = TypedDSL[TrafficLight, TrafficEvent]
        .when[Red.type]
        .on[Timer.type]
        .goto(Green)
        .build

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
        definition = TypedDSL[TrafficLight, TrafficEvent]
          .when[Red.type]
          .on[Timer.type]
          .goto(Green)
          .onState[Red.type]
          .onEntry(entryRef.set(true))
          .done
          .build
        _        <- ZIO.scoped(definition.build(Red))
        entryRan <- entryRef.get
      yield assertTrue(entryRan)
    },
    test("should execute entry and exit actions on transition") {
      for
        log <- Ref.make(List.empty[String])
        definition = TypedDSL[TrafficLight, TrafficEvent]
          .when[Red.type]
          .on[Timer.type]
          .goto(Green)
          .onState[Red.type]
          .onExit(log.update(_ :+ "exit-red"))
          .done
          .onState[Green.type]
          .onEntry(log.update(_ :+ "entry-green"))
          .done
          .build
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
      val definition = TypedDSL[TrafficLight, TrafficEvent]
        .when[Red.type]
        .on[Timer.type]
        .goto(Green)
        .build

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
      val definition = TypedDSL[TrafficLight, TrafficEvent]
        .when[Red.type]
        .on[Timer.type]
        .goto(Green)
        .build

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
      val definition = TypedDSL[TrafficLight, TrafficEvent]
        .when[Red.type]
        .on[Timer.type]
        .goto(Green)
        .build

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
        definition = TypedDSL[TrafficLight, TrafficEvent]
          .when[Red.type]
          .on[Timer.type]
          .goto(Yellow)
          .onState[Yellow.type]
          .onEntry(enteredYellow.set(true))
          .done
          .build
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
      val definition = TypedDSL[TrafficLight, TrafficEvent]
        .when[Red.type]
        .on[Timer.type]
        .goto(Green)
        .when[Red.type]
        .onTimeout
        .goto(Yellow)
        .withTimeout[Red.type](50.millis)
        .build

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
      val definition = TypedDSL[TrafficLight, TrafficEvent]
        .when[Red.type]
        .on[Timer.type]
        .goto(Green)
        .when[Red.type]
        .onTimeout
        .goto(Yellow)
        .withTimeout[Red.type](100.millis)
        .build

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
      val definition = TypedDSL[TrafficLight, TrafficEvent]
        .when[Red.type]
        .on[Emergency.type]
        .stay
        .build

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
      val definition = TypedDSL[DocumentState, DocumentEvent]
        .when[Draft.type]
        .on[SubmitForReview]
        .goto(UnderReview("reviewer1"))
        .when[UnderReview]
        .on[ApproveDoc]
        .goto(Approved("manager"))
        .when[Approved]
        .on[PublishDoc.type]
        .goto(Published)
        .build

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

      val definition = TypedDSL[ConnectionState, ConnectionEvent]
        .when[Disconnected.type]
        .on[Connect.type]
        .goto(Connecting(1))
        .when[Connecting]
        .on[Success]
        .goto(Connected("session-123"))
        .when[Connecting]
        .on[Failure]
        .goto(Failed("timeout"))
        .when[Connected]
        .on[Disconnect.type]
        .goto(Disconnected)
        .build

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

      // Define transitions - the actual data in template values doesn't matter for matching
      val definition = TypedDSL[ConnectionState, ConnectionEvent]
        .when[Failed]
        .on[Connect.type]
        .goto(Connecting(1)) // Will match ANY Failed(_)
        .when[Connecting]
        .on[Failure]
        .goto(Failed("retry")) // Will match ANY Connecting(_)
        .when[Connected]
        .on[Disconnect.type]
        .goto(Disconnected) // Will match ANY Connected(_)
        .build

      ZIO.scoped {
        for
          // Start in a Failed state with specific data
          fsm <- definition.build(Failed("initial timeout error"))

          // Send Connect - should match the transition from Failed because both have same shape
          _      <- fsm.send(Connect)
          state1 <- fsm.currentState

          // Now we're in Connecting(1), send Failure to go back to Failed
          _      <- fsm.send(Failure(""))
          state2 <- fsm.currentState

          // Even though we defined transition from Connected, let's verify
          // by starting fresh and reaching Connected with different data
          fsm2   <- definition.build(Connected("session-999"))
          _      <- fsm2.send(Disconnect)
          state3 <- fsm2.currentState
        yield assertTrue(
          state1 == Connecting(1),   // Transition worked even though we started with different Failed
          state2 == Failed("retry"), // Went to the specified target state
          state3 == Disconnected,    // Connected("session-999") matched Connected
        )
      }
    },
    test("rich states - entry/exit actions work with shape matching") {
      import ConnectionState.*
      import ConnectionEvent.*

      for
        actionLog <- Ref.make(List.empty[String])

        // Define entry/exit actions with template states - they match by shape
        definition = TypedDSL[ConnectionState, ConnectionEvent]
          .when[Disconnected.type]
          .on[Connect.type]
          .goto(Connecting(1))
          .when[Connecting]
          .on[Success]
          .goto(Connected("session"))
          // Actions defined with parent type match ANY state of that shape
          .onState[Connecting]
          .onEntry(actionLog.update(_ :+ "entering-connecting"))
          .onExit(actionLog.update(_ :+ "exiting-connecting"))
          .done
          .onState[Connected]
          .onEntry(actionLog.update(_ :+ "entering-connected"))
          .done
          .build

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

      // Define transitions - type parameters match by shape
      val definition = TypedDSL[ConnectionState, ConnectionEvent]
        .when[Disconnected.type]
        .on[Connect.type]
        .goto(Connecting(1))
        .when[Connecting]
        .on[Success]
        .goto(Connected("session-from-event"))
        .when[Connecting]
        .on[Failure]
        .goto(Failed("failure-happened"))
        .build

      ZIO.scoped {
        for
          fsm <- definition.build(Disconnected)

          // Send Connect (case object, works as before)
          _      <- fsm.send(Connect)
          state1 <- fsm.currentState

          // Send Success with DIFFERENT data than what might be expected - should still match!
          // This is the key test: Success("real-session-id-123") should match Success
          _      <- fsm.send(Success("real-session-id-123"))
          state2 <- fsm.currentState

          // Start fresh and test Failure with different data
          fsm2   <- definition.build(Connecting(5)) // Different retry count
          _      <- fsm2.send(Failure("network timeout after 30s"))
          state3 <- fsm2.currentState
        yield assertTrue(
          state1 == Connecting(1),
          state2 == Connected("session-from-event"), // Matched even though event had different data
          state3 == Failed("failure-happened"),      // Matched Failure("network...") to Failure
        )
      }
    },

    // when[Parent] tests - hierarchical state transitions
    suite("when[Parent] - hierarchical transitions")(
      test("when[Parent] applies to all leaf states under a parent") {
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

        // when[Inactive] should apply to both Paused and Stopped
        val definition = TypedDSL[HierState, HierEvent]
          .when[Active.type]
          .on[Pause.type]
          .goto(Paused)
          .when[Active.type]
          .on[Stop.type]
          .goto(Stopped)
          .when[Inactive]
          .on[Activate.type]
          .goto(Active) // Both Paused and Stopped can Activate -> Active
          .build

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
      test("leaf-level transition overrides when[Parent]") {
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
        val definition = TypedDSL[OverrideState, OverrideEvent]
          .when[Ready.type]
          .on[Start.type]
          .goto(Running)
          .when[Processing]
          .on[Cancel.type]
          .goto(Ready) // Default for all Processing
          .when[Running.type]
          .on[Cancel.type]
          .`override`
          .goto(Done) // Override for Running specifically
          .build

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
            state2 == Ready, // Default from when[Processing]
          )
        }
      },
      test("when[Parent] works with stay") {
        sealed trait StayState extends MState
        case object Idle       extends StayState
        sealed trait Busy      extends StayState
        case object Working    extends Busy
        case object Blocked    extends Busy

        sealed trait StayEvent extends MEvent
        case object Ping       extends StayEvent
        case object Start      extends StayEvent

        val definition = TypedDSL[StayState, StayEvent]
          .when[Idle.type]
          .on[Start.type]
          .goto(Working)
          .when[Busy]
          .on[Ping.type]
          .stay // All Busy states stay on Ping
          .build

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
      test("when[Parent] works with stop") {
        sealed trait StopState       extends MState
        case object Running          extends StopState
        sealed trait Error           extends StopState
        case object RecoverableError extends Error
        case object FatalError       extends Error

        sealed trait StopEvent extends MEvent
        case object Fail       extends StopEvent
        case object Shutdown   extends StopEvent

        val definition = TypedDSL[StopState, StopEvent]
          .when[Running.type]
          .on[Fail.type]
          .goto(FatalError)
          .when[Error]
          .on[Shutdown.type]
          .stop // All Error states can shutdown
          .build

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
      test("when[Parent] with nested hierarchy") {
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

        // when[Level1] should hit Leaf1, Leaf2A, and Leaf2B
        val definition = TypedDSL[DeepState, DeepEvent]
          .when[Root.type]
          .on[GoDeep.type]
          .goto(Leaf2A)
          .when[Level1]
          .on[Reset.type]
          .goto(Root) // All Level1 descendants (including Level2 leaves)
          .build

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
      test("multiple when[] calls for explicit state list") {
        sealed trait SelectState extends MState
        case object Idle         extends SelectState
        sealed trait Active      extends SelectState
        case object Running      extends Active
        case object Paused       extends Active
        case object Blocked      extends Active

        sealed trait SelectEvent extends MEvent
        case object Cancel       extends SelectEvent
        case object Start        extends SelectEvent

        // Only Running and Paused can cancel, NOT Blocked
        // Use multiple when[] calls to achieve the same as whenAny[Active](Running, Paused)
        val definition = TypedDSL[SelectState, SelectEvent]
          .when[Idle.type]
          .on[Start.type]
          .goto(Running)
          .when[Running.type]
          .on[Cancel.type]
          .goto(Idle)
          .when[Paused.type]
          .on[Cancel.type]
          .goto(Idle)
          // Blocked has no Cancel transition
          .build

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
      test("on[Parent] applies to all events under parent type") {
        sealed trait HierarchyEventState extends MState
        case object Waiting              extends HierarchyEventState
        case object Handling             extends HierarchyEventState
        case object Cancelled            extends HierarchyEventState

        sealed trait HierarchyEvent extends MEvent
        sealed trait UserAction     extends HierarchyEvent
        case object Click           extends UserAction
        case object Tap             extends UserAction
        sealed trait SystemAction   extends HierarchyEvent
        case object SystemCancel    extends SystemAction

        // on[UserAction] triggers Waiting -> Handling for any UserAction
        val definition = TypedDSL[HierarchyEventState, HierarchyEvent]
          .when[Waiting.type]
          .on[UserAction]
          .goto(Handling)
          .when[Waiting.type]
          .on[SystemCancel.type]
          .goto(Cancelled)
          .build

        ZIO.scoped {
          for
            // Click triggers transition
            fsm1   <- definition.build(Waiting)
            _      <- fsm1.send(Click)
            state1 <- fsm1.currentState

            // Tap also triggers transition
            fsm2   <- definition.build(Waiting)
            _      <- fsm2.send(Tap)
            state2 <- fsm2.currentState

            // SystemCancel goes to Cancelled
            fsm3   <- definition.build(Waiting)
            _      <- fsm3.send(SystemCancel)
            state3 <- fsm3.currentState
          yield assertTrue(
            state1 == Handling,
            state2 == Handling,
            state3 == Cancelled,
          )
        }
      },
      test("when[Parent] combined with on[Parent] for cartesian product") {
        sealed trait CartesianState extends MState
        sealed trait Working        extends CartesianState
        case object Task1           extends Working
        case object Task2           extends Working
        case object Stopped         extends CartesianState

        sealed trait CartesianEvent extends MEvent
        sealed trait StopSignal     extends CartesianEvent
        case object Cancel          extends StopSignal
        case object Abort           extends StopSignal
        case object Complete        extends CartesianEvent

        // All Working states respond to any StopSignal (Cancel or Abort)
        val definition = TypedDSL[CartesianState, CartesianEvent]
          .when[Working]
          .on[StopSignal]
          .goto(Stopped)
          .when[Task1.type]
          .on[Complete.type]
          .goto(Stopped)
          .build

        ZIO.scoped {
          for
            // Task1 + Cancel
            fsm1   <- definition.build(Task1)
            _      <- fsm1.send(Cancel)
            state1 <- fsm1.currentState

            // Task1 + Abort
            fsm2   <- definition.build(Task1)
            _      <- fsm2.send(Abort)
            state2 <- fsm2.currentState

            // Task2 + Cancel
            fsm3   <- definition.build(Task2)
            _      <- fsm3.send(Cancel)
            state3 <- fsm3.currentState

            // Task2 + Abort
            fsm4   <- definition.build(Task2)
            _      <- fsm4.send(Abort)
            state4 <- fsm4.currentState

            // Task2 + Complete not defined
            fsm5  <- definition.build(Task2)
            error <- fsm5.send(Complete).flip
          yield assertTrue(
            state1 == Stopped,
            state2 == Stopped,
            state3 == Stopped,
            state4 == Stopped,
            error.isInstanceOf[InvalidTransitionError],
          )
        }
      },
    ),
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(
    5.seconds
  )
end FSMSpec
