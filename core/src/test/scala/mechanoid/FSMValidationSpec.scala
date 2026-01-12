package mechanoid

import zio.*
import zio.test.*

/** Tests for the build macro and duplicate transition detection.
  *
  * Note: Compile-time error tests cannot be run as regular tests. The tests here verify that valid definitions work
  * correctly with build, and that .override allows intentional overrides.
  *
  * To verify compile-time error detection, try uncommenting the code in the "should not compile" comments.
  */
object FSMValidationSpec extends ZIOSpecDefault:

  // Simple states and events for testing
  enum TestState extends MState:
    case A, B, C

  enum TestEvent extends MEvent:
    case E1, E2

  // Hierarchical states for whenAny testing
  sealed trait HierState extends MState
  case object Root       extends HierState
  sealed trait Parent    extends HierState
  case object ChildA     extends Parent
  case object ChildB     extends Parent
  case object ChildC     extends Parent

  sealed trait HierEvent extends MEvent
  case object Go         extends HierEvent
  case object Reset      extends HierEvent

  import TestState.*
  import TestEvent.*

  def spec = suite("FSMValidation")(
    suite("build macro")(
      test("accepts valid definition without duplicates") {
        val definition = build[TestState, TestEvent] {
          _.when(A)
            .on(E1)
            .goto(B)
            .when(B)
            .on(E1)
            .goto(C)
            .when(C)
            .on(E1)
            .goto(A)
        }

        ZIO.scoped {
          for
            fsm   <- definition.build(A)
            _     <- fsm.send(E1) // A -> B
            state <- fsm.currentState
          yield assertTrue(state == B)
        }
      },
      test("accepts definition with different events on same state") {
        val definition = build[TestState, TestEvent] {
          _.when(A)
            .on(E1)
            .goto(B)
            .when(A)
            .on(E2)
            .goto(C) // Different event, same state - OK
        }

        ZIO.scoped {
          for
            fsm   <- definition.build(A)
            _     <- fsm.send(E2) // A -> C via E2
            state <- fsm.currentState
          yield assertTrue(state == C)
        }
      },
      test("accepts definition with same event on different states") {
        val definition = build[TestState, TestEvent] {
          _.when(A)
            .on(E1)
            .goto(B)
            .when(B)
            .on(E1)
            .goto(C) // Same event, different state - OK
        }

        ZIO.scoped {
          for
            fsm   <- definition.build(A)
            _     <- fsm.send(E1) // A -> B
            _     <- fsm.send(E1) // B -> C
            state <- fsm.currentState
          yield assertTrue(state == C)
        }
      },
    ),
    suite(".override modifier")(
      test("allows explicit override of previous transition") {
        val definition = build[TestState, TestEvent] {
          _.when(A)
            .on(E1)
            .goto(B) // First definition
            .when(A)
            .on(E1)
            .`override`
            .goto(C) // Explicit override - should go to C, not B
        }

        ZIO.scoped {
          for
            fsm   <- definition.build(A)
            _     <- fsm.send(E1)
            state <- fsm.currentState
          yield assertTrue(state == C) // Override wins
        }
      },
      test("allows whenAny followed by specific override") {
        val definition = build[HierState, HierEvent] {
          _.when(Root)
            .on(Go)
            .goto(ChildA)
            .whenAny[Parent]
            .on(Reset)
            .goto(Root) // Default: all children go to Root
            .when(ChildA)
            .on(Reset)
            .`override`
            .goto(ChildB) // Override: ChildA goes to ChildB instead
        }

        ZIO.scoped {
          for
            // ChildA uses override -> ChildB
            fsm1   <- definition.build(ChildA)
            _      <- fsm1.send(Reset)
            state1 <- fsm1.currentState

            // ChildB uses default -> Root
            fsm2   <- definition.build(ChildB)
            _      <- fsm2.send(Reset)
            state2 <- fsm2.currentState
          yield assertTrue(
            state1 == ChildB, // Override
            state2 == Root,   // Default from whenAny
          )
        }
      },
      test("override works with stay") {
        val definition = build[TestState, TestEvent] {
          _.when(A)
            .on(E1)
            .goto(B)
            .when(A)
            .on(E1)
            .`override`
            .stay // Override to stay instead
        }

        ZIO.scoped {
          for
            fsm    <- definition.build(A)
            result <- fsm.send(E1)
            state  <- fsm.currentState
          yield assertTrue(
            result == TransitionResult.Stay,
            state == A,
          )
        }
      },
      test("override works with stop") {
        val definition = build[TestState, TestEvent] {
          _.when(A)
            .on(E1)
            .goto(B)
            .when(A)
            .on(E1)
            .`override`
            .stop // Override to stop instead
        }

        ZIO.scoped {
          for
            fsm     <- definition.build(A)
            result  <- fsm.send(E1)
            running <- fsm.isRunning
          yield assertTrue(
            result.isInstanceOf[TransitionResult.Stop[?]],
            !running,
          )
        }
      },
    ),
    // NOTE: The following definitions should NOT compile due to duplicate transitions.
    // Uncomment to verify compile-time error detection:
    //
    // test("duplicate transition should fail to compile") {
    //   val definition = build[TestState, TestEvent] {
    //     _.when(A).on(E1).goto(B)
    //       .when(A).on(E1).goto(C)  // ERROR: Duplicate transition
    //   }
    //   assertTrue(true)
    // }
    //
    // The expected compile error:
    // "Duplicate transition detected for state 'A' on event 'E1'.
    //    First definition:  goto(B)
    //    Second definition: goto(C)
    //  To fix this:
    //    - Remove one of the duplicate definitions, OR
    //    - Use .override.goto(...) if the override is intentional"
  ) @@ TestAspect.sequential
end FSMValidationSpec
