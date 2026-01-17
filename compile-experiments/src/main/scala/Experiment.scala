package experiments

import mechanoid.machine.*
import mechanoid.core.Finite

/** Test cases for compile-time assembly validation.
  *
  * This module exists for quick iteration on macro debugging. Use `sbt compileExperiments/compile` to test.
  */
object Experiment:

  enum TestState derives Finite:
    case A, B, C

  enum TestEvent derives Finite:
    case E1, E2, E3

  import TestState.*
  import TestEvent.*

  // Test case 1: Top-level vals with include and override
  val base     = assembly[TestState, TestEvent](A via E1 to B)
  val combined = assembly[TestState, TestEvent](
    include(base),
    (A via E1 to C) @@ Aspect.overriding,
  )

  // Test case 2: Local vals with include (no duplicate)
  def localTest(): Unit =
    val base     = assembly[TestState, TestEvent](A via E1 to B)
    val combined = assembly[TestState, TestEvent](
      include(base),
      B via E2 to C,
    )
    val _ = combined // suppress unused warning
    ()

  // Test case 3: Local vals with include and override (the previously failing case)
  def localOverrideTest(): Unit =
    val base     = assembly[TestState, TestEvent](A via E1 to B)
    val combined = assembly[TestState, TestEvent](
      include(base),
      (A via E1 to C) @@ Aspect.overriding,
    )
    val _ = combined // suppress unused warning
    ()

  // Test case 4: Orphan override via inline def - should work!
  inline def orphanAssemblyInline = assembly[TestState, TestEvent](
    (A via E1 to B) @@ Aspect.overriding // No duplicate - orphan!
  )
  val machineWithOrphanInline = Machine(orphanAssemblyInline) // Should emit warning with inline def

  // Test case 4a: Regular val would cause compile error (commented out to allow compilation)
  // val orphanAssembly = assembly[TestState, TestEvent](
  //   (A via E1 to B) @@ Aspect.overriding
  // )
  // val machineWithOrphan = Machine(orphanAssembly) // ERROR: must use inline

  // Test case 4b: Inline orphan override - should emit compile-time warning
  val machineWithInlineOrphan = Machine(
    assembly[TestState, TestEvent](
      (A via E1 to B) @@ Aspect.overriding // No duplicate - orphan!
    )
  )

  // Test case 4c: Local val with inline Machine call - should emit warning
  def localOrphanTest(): Unit =
    val localOrphan = assembly[TestState, TestEvent](
      (B via E2 to C) @@ Aspect.overriding // No duplicate - orphan!
    )
    // Inline assembly in Machine - should warn
    val localMachine = Machine(
      assembly[TestState, TestEvent](
        (B via E2 to C) @@ Aspect.overriding
      )
    )
    val _ = (localOrphan, localMachine)
    ()
  end localOrphanTest

  // Test case 5: Non-orphan override (composed) - should NOT warn
  // Only the composed assembly needs to be inline! The included assemblies can be regular vals.
  val baseForCompose          = assembly[TestState, TestEvent](A via E1 to B)
  val overrideFragment        = assembly[TestState, TestEvent]((A via E1 to C) @@ Aspect.overriding)
  inline def composedAssembly = assembly[TestState, TestEvent](
    include(baseForCompose),
    include(overrideFragment),
  )
  val machineComposed = Machine(composedAssembly) // No warning - orphan resolved!

  // Test case 6: Inline composed assembly - should NOT warn (orphan resolved)
  val machineInlineComposed = Machine(
    assembly[TestState, TestEvent](
      include(assembly[TestState, TestEvent](A via E2 to B)),
      include(assembly[TestState, TestEvent](A via E2 to B)),
//      include(assembly[TestState, TestEvent]((A via E2 to C) @@ Aspect.overriding)),
    )
  )

end Experiment
