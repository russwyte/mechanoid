package mechanoid

import zio.*
import zio.test.*
import scala.concurrent.duration.*
import mechanoid.core.Timeout
import mechanoid.machine.*

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
      val machine = build[TrafficLight, TrafficEvent](
        Red via Timer to Green
      )

      ZIO.scoped {
        for
          fsm   <- machine.start(Red)
          state <- fsm.currentState
        yield assertTrue(state == Red)
      }
    },
    test("should transition on valid event") {
      val machine = build[TrafficLight, TrafficEvent](
        Red via Timer to Green
      )

      ZIO.scoped {
        for
          fsm    <- machine.start(Red)
          result <- fsm.send(Timer)
          state  <- fsm.currentState
        yield assertTrue(
          result == TransitionResult.Goto(Green),
          state == Green,
        )
      }
    },
    test("should follow transition sequence") {
      val machine = build[TrafficLight, TrafficEvent](
        Red via Timer to Green,
        Green via Timer to Yellow,
        Yellow via Timer to Red,
      )

      ZIO.scoped {
        for
          fsm     <- machine.start(Red)
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
      val machine = build[TrafficLight, TrafficEvent](
        Red via Emergency to stay
      )

      ZIO.scoped {
        for
          fsm    <- machine.start(Red)
          result <- fsm.send(Emergency)
          state  <- fsm.currentState
        yield assertTrue(
          result == TransitionResult.Stay,
          state == Red,
        )
      }
    },
    test("should fail on invalid transition") {
      val machine = build[TrafficLight, TrafficEvent](
        Red via Timer to Green
      )

      ZIO.scoped {
        for
          fsm   <- machine.start(Red)
          _     <- fsm.send(Timer)      // Red -> Green
          error <- fsm.send(Timer).flip // Green + Timer = undefined
        yield assertTrue(error.isInstanceOf[InvalidTransitionError])
      }
    },
    // Entry/Exit action tests
    test("should execute entry action on initial state") {
      for
        entryRef <- Ref.make(false)
        machine = build[TrafficLight, TrafficEvent](
          Red via Timer to Green
        ).withEntry(Red)(entryRef.set(true))
        _        <- ZIO.scoped(machine.start(Red))
        entryRan <- entryRef.get
      yield assertTrue(entryRan)
    },
    test("should execute entry and exit actions on transition") {
      for
        log <- Ref.make(List.empty[String])
        machine = build[TrafficLight, TrafficEvent](
          Red via Timer to Green
        ).withExit(Red)(log.update(_ :+ "exit-red"))
          .withEntry(Green)(log.update(_ :+ "entry-green"))
        _ <- ZIO.scoped {
          for
            fsm <- machine.start(Red)
            _   <- fsm.send(Timer)
          yield ()
        }
        events <- log.get
      yield assertTrue(events == List("exit-red", "entry-green"))
    },

    // Stop tests
    test("should stop FSM and report not running") {
      val machine = build[TrafficLight, TrafficEvent](
        Red via Timer to Green
      )

      ZIO.scoped {
        for
          fsm           <- machine.start(Red)
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
      val machine = build[TrafficLight, TrafficEvent](
        Red via Timer to Green
      )

      ZIO.scoped {
        for
          fsm    <- machine.start(Red)
          _      <- fsm.stop
          result <- fsm.send(Timer)
        yield assertTrue(result.isInstanceOf[TransitionResult.Stop[?]])
      }
    },

    // FSMState metadata tests
    test("should track state metadata") {
      val machine = build[TrafficLight, TrafficEvent](
        Red via Timer to Green
      )

      ZIO.scoped {
        for
          fsm         <- machine.start(Red)
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
        machine = build[TrafficLight, TrafficEvent](
          Red via Timer to Yellow
        ).withEntry(Yellow)(enteredYellow.set(true))
        result <- ZIO.scoped {
          for
            fsm     <- machine.start(Red)
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
    // Note: Timeouts are configured on transitions TO a state using TimedTarget
    test("should trigger timeout event after duration") {
      val timedRed = Red @@ Aspect.timeout(50.millis)
      val machine  = build[TrafficLight, TrafficEvent](
        Green via Timer to timedRed, // Entering Red starts 50ms timeout
        Red via Timer to Green,
        Red via Timeout to Yellow,
      )

      ZIO.scoped {
        for
          fsm         <- machine.start(Green)
          _           <- fsm.send(Timer) // Green -> Red, timeout starts
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
      val timedRed = Red @@ Aspect.timeout(100.millis)
      val machine  = build[TrafficLight, TrafficEvent](
        Green via Timer to timedRed, // Entering Red starts 100ms timeout
        Red via Timer to Green,
        Red via Timeout to Yellow,
      )

      ZIO.scoped {
        for
          fsm <- machine.start(Green)
          _   <- fsm.send(Timer) // Green -> Red, timeout starts
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
      val machine = build[TrafficLight, TrafficEvent](
        Red via Emergency to stay
      )

      ZIO.scoped {
        for
          fsm     <- machine.start(Red)
          _       <- fsm.send(Emergency)
          _       <- fsm.send(Emergency)
          history <- fsm.history
        yield assertTrue(history.isEmpty)
      }
    },

    // event[T] and state[T] type matchers for parameterized case classes
    suite("event[T] and state[T] - type matchers for parameterized types")(
      test("event[T] matches any instance of parameterized event") {
        import ConnectionState.*, ConnectionEvent.*

        // Use event[T] for parameterized events, state[T] for parameterized source states
        // When types carry data, use type matchers - the values are for runtime/handlers
        val machine = build[ConnectionState, ConnectionEvent](
          Disconnected via Connect to Connecting(0),
          state[Connecting] via event[Success] to Connected("default"), // Matches ANY Connecting + ANY Success
          state[Connecting] via event[Failure] to Failed("error"),      // Matches ANY Connecting + ANY Failure
        )

        ZIO.scoped {
          for
            // Test Success path - start with different attempt count
            fsm1   <- machine.start(Connecting(5))
            _      <- fsm1.send(Success("session-123")) // Actual value
            state1 <- fsm1.currentState

            // Test Failure path - start with yet another attempt count
            fsm2   <- machine.start(Connecting(10))
            _      <- fsm2.send(Failure("network error")) // Actual value
            state2 <- fsm2.currentState
          yield assertTrue(
            state1 == Connected("default"), // Target from DSL
            state2 == Failed("error"),      // Target from DSL
          )
        }
      },
      test("state[T] matches any instance of parameterized state") {
        import ConnectionState.*, ConnectionEvent.*

        // Use state[T] for matching source states
        val machine = build[ConnectionState, ConnectionEvent](
          Disconnected via Connect to Connecting(0),
          state[Connecting] via event[Success] to Connected("ok"), // ANY Connecting(_)
          state[Connecting] via event[Failure] to Failed("err"),   // ANY Connecting(_)
        )

        ZIO.scoped {
          for
            // Start in Connecting(5) - different attempt count
            fsm1   <- machine.start(Connecting(5))
            _      <- fsm1.send(Success("sess-456"))
            state1 <- fsm1.currentState

            // Start in Connecting(10)
            fsm2   <- machine.start(Connecting(10))
            _      <- fsm2.send(Failure("timeout"))
            state2 <- fsm2.currentState
          yield assertTrue(
            state1 == Connected("ok"),
            state2 == Failed("err"),
          )
        }
      },
      test("mixed case objects and event[T] in same machine") {
        import ConnectionState.*, ConnectionEvent.*

        // Mix case objects (Connect, Disconnect) with parameterized events
        val machine = build[ConnectionState, ConnectionEvent](
          Disconnected via Connect to Connecting(0),
          state[Connecting] via event[Success] to Connected("default"),
          state[Connected] via Disconnect to Disconnected, // Case object event directly
        )

        ZIO.scoped {
          for
            fsm    <- machine.start(Disconnected)
            _      <- fsm.send(Connect)
            state1 <- fsm.currentState
            _      <- fsm.send(Success("real-session"))
            state2 <- fsm.currentState
            _      <- fsm.send(Disconnect)
            state3 <- fsm.currentState
          yield assertTrue(
            state1 == Connecting(0),
            state2 == Connected("default"),
            state3 == Disconnected,
          )
        }
      },
      test("state[T] via Timeout for parameterized states") {
        import ConnectionState.*, ConnectionEvent.*

        val timedConnecting = Connecting(0) @@ Aspect.timeout(50.millis)

        val machine = build[ConnectionState, ConnectionEvent](
          Disconnected via Connect to timedConnecting,
          state[Connecting] via Timeout to Disconnected, // Timeout from any Connecting
          state[Connecting] via event[Success] to Connected("ok"),
        )

        ZIO.scoped {
          for
            fsm         <- machine.start(Disconnected)
            _           <- fsm.send(Connect)
            stateBefore <- fsm.currentState
            // Wait for timeout to fire
            _          <- ZIO.sleep(100.millis)
            stateAfter <- fsm.currentState
          yield assertTrue(
            stateBefore == Connecting(0),
            stateAfter == Disconnected,
          )
        }
      },
      test("event[T] with all[Parent] state matcher") {
        // Define hierarchical states with parameterized events
        sealed trait TaskState extends MState
        case object Idle       extends TaskState
        sealed trait Running   extends TaskState
        case object Step1      extends Running
        case object Step2      extends Running
        case object Completed  extends TaskState

        sealed trait TaskEvent            extends MEvent
        case class Advance(data: String)  extends TaskEvent
        case class Finish(result: String) extends TaskEvent
        case object Reset                 extends TaskEvent

        // all[Running] responds to event[Finish]
        val machine = build[TaskState, TaskEvent](
          Idle via event[Advance] to Step1,
          Step1 via event[Advance] to Step2,
          all[Running] via event[Finish] to Completed, // All Running states finish on Finish event
          all[Running] via Reset to Idle,
        )

        ZIO.scoped {
          for
            // From Step1
            fsm1   <- machine.start(Step1)
            _      <- fsm1.send(Finish("done from step1"))
            state1 <- fsm1.currentState

            // From Step2
            fsm2   <- machine.start(Step2)
            _      <- fsm2.send(Finish("done from step2"))
            state2 <- fsm2.currentState
          yield assertTrue(
            state1 == Completed,
            state2 == Completed,
          )
        }
      },
    ),

    // all[Parent] tests - hierarchical state transitions
    suite("all[Parent] - hierarchical transitions")(
      test("all[Parent] applies to all leaf states under a parent") {
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

        // all[Inactive] should apply to both Paused and Stopped
        val machine = build[HierState, HierEvent](
          Active via Pause to Paused,
          Active via Stop to Stopped,
          all[Inactive] via Activate to Active, // Both Paused and Stopped can Activate -> Active
        )

        ZIO.scoped {
          for
            // Test from Paused
            fsm1   <- machine.start(Paused)
            _      <- fsm1.send(Activate)
            state1 <- fsm1.currentState

            // Test from Stopped
            fsm2   <- machine.start(Stopped)
            _      <- fsm2.send(Activate)
            state2 <- fsm2.currentState
          yield assertTrue(
            state1 == Active,
            state2 == Active,
          )
        }
      },
      test("leaf-level transition overrides all[Parent]") {
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
        val machine = build[OverrideState, OverrideEvent](
          Ready via Start to Running,
          all[Processing] via Cancel to Ready,              // Default for all Processing
          (Running via Cancel to Done) @@ Aspect.overriding, // Override for Running specifically
        )

        ZIO.scoped {
          for
            // Running should use override -> Done
            fsm1   <- machine.start(Running)
            _      <- fsm1.send(Cancel)
            state1 <- fsm1.currentState

            // Waiting should use default -> Ready
            fsm2   <- machine.start(Waiting)
            _      <- fsm2.send(Cancel)
            state2 <- fsm2.currentState
          yield assertTrue(
            state1 == Done, // Override won
            state2 == Ready, // Default from all[Processing]
          )
        }
      },
      test("all[Parent] works with stay") {
        sealed trait StayState extends MState
        case object Idle       extends StayState
        sealed trait Busy      extends StayState
        case object Working    extends Busy
        case object Blocked    extends Busy

        sealed trait StayEvent extends MEvent
        case object Ping       extends StayEvent
        case object Start      extends StayEvent

        val machine = build[StayState, StayEvent](
          Idle via Start to Working,
          all[Busy] via Ping to stay, // All Busy states stay on Ping
        )

        ZIO.scoped {
          for
            fsm    <- machine.start(Working)
            result <- fsm.send(Ping)
            state  <- fsm.currentState
          yield assertTrue(
            result == TransitionResult.Stay,
            state == Working,
          )
        }
      },
      test("all[Parent] works with stop") {
        sealed trait StopState       extends MState
        case object Running          extends StopState
        sealed trait Error           extends StopState
        case object RecoverableError extends Error
        case object FatalError       extends Error

        sealed trait StopEvent extends MEvent
        case object Fail       extends StopEvent
        case object Shutdown   extends StopEvent

        val machine = build[StopState, StopEvent](
          Running via Fail to FatalError,
          all[Error] via Shutdown to stop, // All Error states can shutdown
        )

        ZIO.scoped {
          for
            fsm     <- machine.start(FatalError)
            result  <- fsm.send(Shutdown)
            running <- fsm.isRunning
          yield assertTrue(
            result.isInstanceOf[TransitionResult.Stop[?]],
            !running,
          )
        }
      },
      test("all[Parent] with nested hierarchy") {
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

        // all[Level1] should hit Leaf1, Leaf2A, and Leaf2B
        val machine = build[DeepState, DeepEvent](
          Root via GoDeep to Leaf2A,
          all[Level1] via Reset to Root, // All Level1 descendants (including Level2 leaves)
        )

        ZIO.scoped {
          for
            fsm1   <- machine.start(Leaf1)
            _      <- fsm1.send(Reset)
            state1 <- fsm1.currentState

            fsm2   <- machine.start(Leaf2A)
            _      <- fsm2.send(Reset)
            state2 <- fsm2.currentState

            fsm3   <- machine.start(Leaf2B)
            _      <- fsm3.send(Reset)
            state3 <- fsm3.currentState
          yield assertTrue(
            state1 == Root,
            state2 == Root,
            state3 == Root,
          )
        }
      },
      test("multiple explicit transitions for specific states") {
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
        val machine = build[SelectState, SelectEvent](
          Idle via Start to Running,
          Running via Cancel to Idle,
          Paused via Cancel to Idle,
          // Blocked has no Cancel transition
        )

        ZIO.scoped {
          for
            // Running can cancel
            fsm1   <- machine.start(Running)
            _      <- fsm1.send(Cancel)
            state1 <- fsm1.currentState

            // Paused can cancel
            fsm2   <- machine.start(Paused)
            _      <- fsm2.send(Cancel)
            state2 <- fsm2.currentState

            // Blocked cannot cancel (invalid transition)
            fsm3  <- machine.start(Blocked)
            error <- fsm3.send(Cancel).flip
          yield assertTrue(
            state1 == Idle,
            state2 == Idle,
            error.isInstanceOf[InvalidTransitionError],
          )
        }
      },
      test("all[Parent] for events applies to all events under parent type") {
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

        // all[UserAction] triggers Waiting -> Handling for any UserAction
        val machine = build[HierarchyEventState, HierarchyEvent](
          Waiting viaAll all[UserAction] to Handling,
          Waiting via SystemCancel to Cancelled,
        )

        ZIO.scoped {
          for
            // Click triggers transition
            fsm1   <- machine.start(Waiting)
            _      <- fsm1.send(Click)
            state1 <- fsm1.currentState

            // Tap also triggers transition
            fsm2   <- machine.start(Waiting)
            _      <- fsm2.send(Tap)
            state2 <- fsm2.currentState

            // SystemCancel goes to Cancelled
            fsm3   <- machine.start(Waiting)
            _      <- fsm3.send(SystemCancel)
            state3 <- fsm3.currentState
          yield assertTrue(
            state1 == Handling,
            state2 == Handling,
            state3 == Cancelled,
          )
        }
      },
      test("all[Parent] combined with all events for cartesian product") {
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
        val machine = build[CartesianState, CartesianEvent](
          all[Working] viaAll all[StopSignal] to Stopped,
          Task1 via Complete to Stopped,
        )

        ZIO.scoped {
          for
            // Task1 + Cancel
            fsm1   <- machine.start(Task1)
            _      <- fsm1.send(Cancel)
            state1 <- fsm1.currentState

            // Task1 + Abort
            fsm2   <- machine.start(Task1)
            _      <- fsm2.send(Abort)
            state2 <- fsm2.currentState

            // Task2 + Cancel
            fsm3   <- machine.start(Task2)
            _      <- fsm3.send(Cancel)
            state3 <- fsm3.currentState

            // Task2 + Abort
            fsm4   <- machine.start(Task2)
            _      <- fsm4.send(Abort)
            state4 <- fsm4.currentState

            // Task2 + Complete not defined
            fsm5  <- machine.start(Task2)
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
