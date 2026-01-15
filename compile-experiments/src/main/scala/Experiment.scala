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

end Experiment
