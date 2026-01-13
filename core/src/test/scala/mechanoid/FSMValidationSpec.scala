package mechanoid

import zio.*
import zio.test.*
import mechanoid.dsl.TypedDSL

/** Tests for TypedDSL FSM definitions with compile-time validation.
  *
  * Use `validated[S, E] { ... }` for compile-time duplicate detection. Use `TypedDSL[S, E]...build` for runtime-only
  * (later definitions override earlier ones).
  *
  * To test that duplicates fail compilation, uncomment the code in the "should not compile" comments.
  */
object FSMValidationSpec extends ZIOSpecDefault:

  // Simple states and events for testing
  enum TestState extends MState:
    case A, B, C

  enum TestEvent extends MEvent:
    case E1, E2

  // Hierarchical states for parent type matching
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
    suite("validated macro")(
      test("accepts valid definition without duplicates") {
        val definition = validated[TestState, TestEvent] {
          _.when[A.type]
            .on[E1.type]
            .goto(B)
            .when[B.type]
            .on[E1.type]
            .goto(C)
            .when[C.type]
            .on[E1.type]
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
        val definition = validated[TestState, TestEvent] {
          _.when[A.type]
            .on[E1.type]
            .goto(B)
            .when[A.type]
            .on[E2.type]
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
        val definition = validated[TestState, TestEvent] {
          _.when[A.type]
            .on[E1.type]
            .goto(B)
            .when[B.type]
            .on[E1.type]
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
      // NOTE: Uncomment below to verify compile-time error detection:
      //
      // test("duplicate transition should fail to compile") {
      //   val definition = validated[TestState, TestEvent] {
      //     _.when[A.type].on[E1.type].goto(B)
      //       .when[A.type].on[E1.type].goto(C)  // ERROR: Duplicate transition
      //   }
      //   assertTrue(true)
      // }
      //
      // Expected error:
      // "Duplicate transition detected.
      //    State: type (conflicts with: type)
      //    Event: type (conflicts with: type)
      //    First definition:  goto(B)
      //    Second definition: goto(C)
      //  To fix: Remove duplicate OR use .override.goto(...)"
    ),
    suite(".override modifier")(
      test("later definition overrides earlier one") {
        // Without validation macro, later transitions simply override earlier ones
        val definition = TypedDSL[TestState, TestEvent]
          .when[A.type]
          .on[E1.type]
          .goto(B) // First definition
          .when[A.type]
          .on[E1.type]
          .`override`
          .goto(C) // Later definition wins
          .build

        ZIO.scoped {
          for
            fsm   <- definition.build(A)
            _     <- fsm.send(E1)
            state <- fsm.currentState
          yield assertTrue(state == C) // Later definition wins
        }
      },
      test("when[Parent] followed by specific override") {
        val definition = TypedDSL[HierState, HierEvent]
          .when[Root.type]
          .on[Go.type]
          .goto(ChildA)
          .when[Parent]
          .on[Reset.type]
          .goto(Root) // Default: all children go to Root
          .when[ChildA.type]
          .on[Reset.type]
          .`override`
          .goto(ChildB) // Override: ChildA goes to ChildB
          .build

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
            state2 == Root,   // Default from when[Parent]
          )
        }
      },
      test("override works with stay") {
        val definition = TypedDSL[TestState, TestEvent]
          .when[A.type]
          .on[E1.type]
          .goto(B)
          .when[A.type]
          .on[E1.type]
          .`override`
          .stay // Override to stay instead
          .build

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
        val definition = TypedDSL[TestState, TestEvent]
          .when[A.type]
          .on[E1.type]
          .goto(B)
          .when[A.type]
          .on[E1.type]
          .`override`
          .stop // Override to stop instead
          .build

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
  ) @@ TestAspect.sequential
end FSMValidationSpec
