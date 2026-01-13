package mechanoid

import zio.*
import zio.test.*
import mechanoid.dsl.TypedDSL

/** Tests for the experimental type-parameter-based FSM DSL. */
object TypedDSLSpec extends ZIOSpecDefault:

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

  def spec = suite("TypedDSL")(
    suite("basic transitions")(
      test("type-parameter-based DSL builds valid definition") {
        val fsm = TypedDSL[TestState, TestEvent]
          .when[A.type]
          .on[E1.type]
          .goto(B)
          .when[B.type]
          .on[E1.type]
          .goto(C)
          .when[C.type]
          .on[E1.type]
          .goto(A)
          .build

        ZIO.scoped {
          for
            runtime <- fsm.build(A)
            _       <- runtime.send(E1) // A -> B
            state   <- runtime.currentState
          yield assertTrue(state == B)
        }
      },
      test("different events on same state work") {
        val fsm = TypedDSL[TestState, TestEvent]
          .when[A.type]
          .on[E1.type]
          .goto(B)
          .when[A.type]
          .on[E2.type]
          .goto(C)
          .build

        ZIO.scoped {
          for
            runtime <- fsm.build(A)
            _       <- runtime.send(E2) // A -> C via E2
            state   <- runtime.currentState
          yield assertTrue(state == C)
        }
      },
    ),
    suite("parent type matching")(
      test("when[Parent] matches all children") {
        val fsm = TypedDSL[HierState, HierEvent]
          .when[Root.type]
          .on[Go.type]
          .goto(ChildA)
          .when[Parent]
          .on[Reset.type]
          .goto(Root) // Matches ChildA, ChildB, ChildC
          .build

        ZIO.scoped {
          for
            // ChildA + Reset -> Root
            r1 <- fsm.build(ChildA)
            _  <- r1.send(Reset)
            s1 <- r1.currentState

            // ChildB + Reset -> Root
            r2 <- fsm.build(ChildB)
            _  <- r2.send(Reset)
            s2 <- r2.currentState

            // ChildC + Reset -> Root
            r3 <- fsm.build(ChildC)
            _  <- r3.send(Reset)
            s3 <- r3.currentState
          yield assertTrue(
            s1 == Root,
            s2 == Root,
            s3 == Root,
          )
        }
      },
      test("later transitions override earlier ones") {
        // Without validation macro, later transitions simply override earlier ones
        val fsm = TypedDSL[HierState, HierEvent]
          .when[Root.type]
          .on[Go.type]
          .goto(ChildA)
          .when[Parent]
          .on[Reset.type]
          .goto(Root) // Default for all Parent children
          .when[ChildA.type]
          .on[Reset.type]
          .goto(ChildB) // Overrides for ChildA
          .build

        ZIO.scoped {
          for
            // ChildA uses later definition -> ChildB
            r1 <- fsm.build(ChildA)
            _  <- r1.send(Reset)
            s1 <- r1.currentState

            // ChildB uses Parent definition -> Root
            r2 <- fsm.build(ChildB)
            _  <- r2.send(Reset)
            s2 <- r2.currentState
          yield assertTrue(
            s1 == ChildB, // Later definition wins
            s2 == Root,   // From when[Parent]
          )
        }
      },
    ),
    suite("stay and stop")(
      test("stay keeps current state") {
        val fsm = TypedDSL[TestState, TestEvent]
          .when[A.type]
          .on[E1.type]
          .stay
          .build

        ZIO.scoped {
          for
            runtime <- fsm.build(A)
            result  <- runtime.send(E1)
            state   <- runtime.currentState
          yield assertTrue(
            result == TransitionResult.Stay,
            state == A,
          )
        }
      },
      test("stop terminates FSM") {
        val fsm = TypedDSL[TestState, TestEvent]
          .when[A.type]
          .on[E1.type]
          .stop
          .build

        ZIO.scoped {
          for
            runtime <- fsm.build(A)
            result  <- runtime.send(E1)
            running <- runtime.isRunning
          yield assertTrue(
            result.isInstanceOf[TransitionResult.Stop[?]],
            !running,
          )
        }
      },
    ),
  ) @@ TestAspect.sequential
end TypedDSLSpec
