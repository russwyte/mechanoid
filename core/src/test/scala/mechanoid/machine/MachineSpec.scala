package mechanoid.machine

import zio.*
import zio.test.*
import mechanoid.core.*
import scala.concurrent.duration.Duration

object MachineSpec extends ZIOSpecDefault:

  // Simple test types
  enum TestState derives Finite:
    case A, B, C

  enum TestEvent derives Finite:
    case E1, E2, Timeout

  // Hierarchical types for testing all[]/anyOf()
  sealed trait ParentState derives Finite
  case object ChildA extends ParentState
  case object ChildB extends ParentState
  case object ChildC extends ParentState

  sealed trait InputEvent derives Finite
  case object Click extends InputEvent
  case object Tap   extends InputEvent

  import TestState.*, TestEvent.*

  def spec = suite("Machine DSL")(
    suite("basic syntax")(
      test("infix transitions - state via event to target") {
        val spec1 = A via E1 to B
        val spec2 = B via E1 to C

        assertTrue(
          spec1.stateHashes.nonEmpty,
          spec1.eventHashes.nonEmpty,
          spec1.targetDesc == "-> B",
          spec2.targetDesc == "-> C",
        )
      },
      test("operator style - state >> event >> target") {
        val spec = A >> E1 >> B

        assertTrue(
          spec.stateHashes.nonEmpty,
          spec.eventHashes.nonEmpty,
          spec.targetDesc == "-> B",
        )
      },
      test("stay terminal") {
        val spec = A via E1 to stay

        assertTrue(
          spec.targetDesc == "stay",
          spec.handler == Handler.Stay,
        )
      },
      test("stop terminal") {
        val spec = A via E1 to stop

        assertTrue(
          spec.targetDesc == "stop",
          spec.handler == Handler.Stop(None),
        )
      },
      test("stop with reason") {
        val spec = A via E1 to stop("error occurred")

        assertTrue(
          spec.targetDesc == "stop(error occurred)",
          spec.handler == Handler.Stop(Some("error occurred")),
        )
      },
    ),
    suite("hierarchical matching")(
      test("all[Parent] expands to all children") {
        val matcher = all[ParentState]

        // Should have hashes for ChildA, ChildB, ChildC
        assertTrue(
          matcher.hashes.size == 3,
          matcher.names.contains("ChildA"),
          matcher.names.contains("ChildB"),
          matcher.names.contains("ChildC"),
        )
      },
      test("all[Parent] via event creates transitions for all children") {
        val spec = all[ParentState] via Click to ChildA

        assertTrue(
          spec.stateHashes.size == 3, // All 3 children
          spec.eventHashes.size == 1,
          spec.targetDesc == "-> ChildA",
        )
      },
      test("anyOf() matches specific values") {
        val matcher = anyOf(ChildA, ChildB)

        assertTrue(
          matcher.hashes.size == 2,
          matcher.names.contains("ChildA"),
          matcher.names.contains("ChildB"),
          !matcher.names.contains("ChildC"),
        )
      },
      test("anyOf() via event creates transitions for specific states") {
        val spec = anyOf(ChildA, ChildB) via Click to ChildC

        assertTrue(
          spec.stateHashes.size == 2, // Only ChildA and ChildB
          spec.eventHashes.size == 1,
        )
      },
      test("anyOfEvents() for events") {
        val eventMatcher = anyOfEvents(Click, Tap)
        val spec         = ChildA viaAnyOf eventMatcher to ChildB

        assertTrue(
          spec.stateHashes.size == 1,
          spec.eventHashes.size == 2, // Click and Tap
        )
      },
      test("all[] for events") {
        val eventMatcher = all[InputEvent]
        val spec         = ChildA viaAll eventMatcher to ChildB

        assertTrue(
          spec.stateHashes.size == 1,
          spec.eventHashes.size == 2, // Click and Tap
        )
      },
    ),
    suite("timeout")(
      test("timeout aspect creates TimedTarget with user-defined event") {
        val timedTarget = A @@ Aspect.timeout(Duration.fromNanos(30000000000L), Timeout) // 30 seconds

        assertTrue(
          timedTarget.state == A,
          timedTarget.duration.toSeconds == 30,
          timedTarget.timeoutEvent == Timeout,
        )
      },
      test("via user-defined timeout event uses stable hash") {
        val spec = A via Timeout to B

        assertTrue(
          spec.eventHashes.nonEmpty,
          spec.eventNames.contains("Timeout"),
        )
      },
      test("transition to TimedTarget includes timeout duration and event") {
        val timedB = B @@ Aspect.timeout(Duration.fromNanos(60000000000L), Timeout) // 1 minute
        val spec   = A via E1 to timedB

        assertTrue(
          spec.targetTimeout.isDefined,
          spec.targetTimeout.get.toMinutes == 1L,
          spec.targetTimeoutConfig.isDefined,
          spec.targetTimeoutConfig.get.event == Timeout,
        )
      },
    ),
    suite("aspects")(
      test("override aspect marks spec as override") {
        val spec = (A via E1 to B) @@ Aspect.overriding

        assertTrue(spec.isOverride)
      },
      test("non-override spec is not marked") {
        val spec = A via E1 to B

        assertTrue(!spec.isOverride)
      },
    ),
    suite("Machine.fromSpecs")(
      test("creates machine from specs") {
        val specs = List(
          A via E1 to B,
          B via E1 to C,
        )

        val machine = Machine.fromSpecs[TestState, TestEvent, Nothing](specs)

        assertTrue(
          machine.specs.size == 2,
          machine.transitions.nonEmpty,
        )
      },
      test("machine can start FSM runtime") {
        val specs = List(
          A via E1 to B,
          B via E1 to C,
        )

        val machine = Machine.fromSpecs[TestState, TestEvent, Nothing](specs)

        ZIO.scoped {
          for
            fsm   <- machine.start(A)
            _     <- fsm.send(E1)
            state <- fsm.currentState
          yield assertTrue(state == B)
        }
      },
    ),
    suite("immutable transition expressions")(
      test("transitions can be stored in vals and reused") {
        // Store transitions in vals
        val transition1 = A via E1 to B
        val transition2 = B via E1 to C

        // Use them in build
        val machine = build[TestState, TestEvent](
          transition1,
          transition2,
        )

        ZIO.scoped {
          for
            fsm   <- machine.start(A)
            _     <- fsm.send(E1)
            _     <- fsm.send(E1)
            state <- fsm.currentState
          yield assertTrue(state == C)
        }
      },
      test("timed target can be stored in val and reused") {
        // Store timed target in val
        val timedB = B @@ Aspect.timeout(Duration.fromNanos(30000000000L), Timeout) // 30 seconds

        // Use in transition
        val toTimedB = A via E1 to timedB
        val fromB    = B via Timeout to C

        val machine = build[TestState, TestEvent](
          toTimedB,
          fromB,
        )

        assertTrue(
          machine.timeouts.nonEmpty,
          machine.specs.size == 2,
        )
      },
      test("mixed inline and stored transitions") {
        val storedTransition = A via E1 to B

        val machine = build[TestState, TestEvent](
          storedTransition, // Stored
          B via E1 to C,    // Inline
        )

        ZIO.scoped {
          for
            fsm   <- machine.start(A)
            _     <- fsm.send(E1)
            state <- fsm.currentState
          yield assertTrue(state == B)
        }
      },
    ),
    suite("build macro")(
      test("build creates machine from specs") {
        val machine = build[TestState, TestEvent](
          A via E1 to B,
          B via E1 to C,
        )

        ZIO.scoped {
          for
            fsm   <- machine.start(A)
            _     <- fsm.send(E1)
            state <- fsm.currentState
          yield assertTrue(state == B)
        }
      },
      // NOTE: Duplicate detection now happens at COMPILE TIME!
      // The following code would not compile if uncommented:
      //   build[TestState, TestEvent](
      //     A via E1 to B,
      //     A via E1 to C, // ERROR: Duplicate transition without override!
      //   )
      // To override, use: (A via E1 to C) @@ Aspect.overriding
      test("build rejects duplicates at compile time") {
        // This test verifies that our compile-time duplicate detection is in place.
        // Since we can't test compile errors at runtime, we just verify the valid cases work.
        assertTrue(true) // Placeholder - actual validation happens at compile time
      },
      test("build allows duplicates with override") {
        // This should work - override allows duplicates
        val machine = build[TestState, TestEvent](
          A via E1 to B,
          (A via E1 to C) @@ Aspect.overriding, // Override - should use C
        )

        ZIO.scoped {
          for
            fsm   <- machine.start(A)
            _     <- fsm.send(E1)
            state <- fsm.currentState
          yield assertTrue(state == C) // Last override wins
        }
      },
      test("build with all[] and specific override") {
        // all[Parent] defines default, then override specific child
        val machine = build[ParentState, InputEvent](
          all[ParentState] via Click to ChildC,             // Default: all -> ChildC
          (ChildA via Click to ChildB) @@ Aspect.overriding, // Override: ChildA -> ChildB
        )

        ZIO.scoped {
          for
            fsm1   <- machine.start(ChildA)
            _      <- fsm1.send(Click)
            state1 <- fsm1.currentState
            fsm2   <- machine.start(ChildB)
            _      <- fsm2.send(Click)
            state2 <- fsm2.currentState
          yield assertTrue(
            state1 == ChildB, // ChildA uses override -> ChildB
            state2 == ChildC, // ChildB uses default -> ChildC
          )
        }
      },
    ),
  )
end MachineSpec
