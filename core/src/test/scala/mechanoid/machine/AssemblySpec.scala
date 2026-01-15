package mechanoid.machine

import zio.test.*
import mechanoid.core.Finite

object AssemblySpec extends ZIOSpecDefault:

  // Test state and event types
  enum TestState derives Finite:
    case A, B, C

  enum TestEvent derives Finite:
    case E1, E2, E3

  import TestState.*
  import TestEvent.*

  def spec = suite("AssemblySpec")(
    suite("assembly creation")(
      test("creates assembly from single spec") {
        val asm = assembly[TestState, TestEvent](A via E1 to B)
        assertTrue(asm.specs.size == 1)
      },
      test("creates assembly from multiple specs") {
        val asm = assembly[TestState, TestEvent](
          A via E1 to B,
          B via E2 to C,
        )
        assertTrue(asm.specs.size == 2)
      },
      test("preserves spec order") {
        val asm = assembly[TestState, TestEvent](
          A via E1 to B,
          B via E2 to C,
          C via E3 to A,
        )
        assertTrue(
          asm.specs.size == 3,
          asm.specs(0).stateNames.head == "A",
          asm.specs(1).stateNames.head == "B",
          asm.specs(2).stateNames.head == "C",
        )
      },
      test("allows duplicate with @@ Aspect.overriding") {
        val asm = assembly[TestState, TestEvent](
          A via E1 to B,
          (A via E1 to C) @@ Aspect.overriding,
        )
        // Should compile without error, last one wins
        assertTrue(asm.specs.size == 2)
      },
    ),
    suite("assemblyAll block syntax")(
      test("creates assembly from block without commas") {
        val asm = assemblyAll[TestState, TestEvent]:
          A via E1 to B
          B via E2 to C
        assertTrue(asm.specs.size == 2)
      },
      test("supports multiple transitions") {
        val asm = assemblyAll[TestState, TestEvent]:
          A via E1 to B
          B via E2 to C
          C via E3 to A
        assertTrue(asm.specs.size == 3)
      },
    ),
    suite("include composition")(
      test("include flattens assembly specs") {
        val base     = assembly[TestState, TestEvent](A via E1 to B)
        val combined = assembly[TestState, TestEvent](
          include(base),
          B via E2 to C,
        )
        assertTrue(combined.specs.size == 2)
      },
      test("include multiple assemblies") {
        val asm1     = assembly[TestState, TestEvent](A via E1 to B)
        val asm2     = assembly[TestState, TestEvent](B via E2 to C)
        val combined = assembly[TestState, TestEvent](
          include(asm1),
          include(asm2),
        )
        assertTrue(combined.specs.size == 2)
      },
      test("include with spec-level @@ Aspect.overriding") {
        // When both assemblies are defined inline, the override flag should be properly detected
        val combined = assembly[TestState, TestEvent](
          include(assembly[TestState, TestEvent](A via E1 to B)),
          include(assembly[TestState, TestEvent]((A via E1 to C) @@ Aspect.overriding)),
        )
        assertTrue(combined.specs.size == 2)
      },
    ),
    suite("hierarchical matching")(
      test("all[Parent] matches all children") {
        // Create a sealed hierarchy
        sealed trait ParentState derives Finite
        case object Child1 extends ParentState
        case object Child2 extends ParentState
        case object Target extends ParentState

        enum SimpleEvent derives Finite:
          case Reset

        val asm = assembly[ParentState, SimpleEvent](
          all[ParentState] via SimpleEvent.Reset to Target
        )
        // all[ParentState] should expand to match Child1, Child2, Target
        assertTrue(asm.specs.nonEmpty)
      },
      test("anyOf() matches specific states") {
        val asm = assembly[TestState, TestEvent](
          anyOf(A, B) via E1 to C
        )
        // Should have specs for both A and B
        assertTrue(asm.specs.head.stateHashes.size == 2)
      },
      test("anyOfEvents() matches specific events") {
        val asm = assembly[TestState, TestEvent](
          A viaAnyOf anyOfEvents(E1, E2) to B
        )
        assertTrue(asm.specs.head.eventHashes.size == 2)
      },
    ),
    suite("DSL syntax")(
      test("state via event to target") {
        val asm = assembly[TestState, TestEvent](A via E1 to B)
        assertTrue(asm.specs.size == 1)
      },
      test("stop terminal") {
        val asm = assembly[TestState, TestEvent](A via E1 to stop("done"))
        assertTrue(asm.specs.head.handler.isInstanceOf[Handler.Stop])
      },
      test("stay terminal") {
        val asm = assembly[TestState, TestEvent](A via E1 to stay)
        assertTrue(asm.specs.head.handler == Handler.Stay)
      },
    ),
  )

end AssemblySpec
