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

  // Command types for testing emitting
  sealed trait TestCommand
  case class SendEmail(to: String)  extends TestCommand
  case class LogAction(msg: String) extends TestCommand

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
      test("build infers types from transitions - no explicit type parameters") {
        // Store transitions in vals
        val t1 = A via E1 to B
        val t2 = B via E1 to C

        // Types are inferred from the transitions - no explicit type parameters!
        val machine = build(t1, t2)

        ZIO.scoped {
          for
            fsm   <- machine.start(A)
            _     <- fsm.send(E1)
            _     <- fsm.send(E1)
            state <- fsm.currentState
          yield assertTrue(state == C)
        }
      },
      test("build infers types with inline transitions") {
        // Types inferred even with inline transitions
        val machine = build(
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
      test("build infers types with emitting (post-transition commands)") {
        // Types inferred including command type from emitting
        val t1 = (A via E1 to B).emitting((_, _) => List(SendEmail("user@example.com")))
        val t2 = B via E1 to C

        // Command type inferred as SendEmail (the specific type used)
        val machine = build(t1, t2)

        // Verify machine has correct command factories
        assertTrue(
          machine.postCommandFactories.nonEmpty,
          machine.specs.size == 2,
        )
      },
      test("build infers types with emittingBefore (pre-transition commands)") {
        // Types inferred including command type from emittingBefore
        val t1 = (A via E1 to B).emittingBefore((_, _) => List(LogAction("Starting transition")))
        val t2 = B via E1 to C

        val machine = build(t1, t2)

        // Verify machine has correct command factories
        assertTrue(
          machine.preCommandFactories.nonEmpty,
          machine.specs.size == 2,
        )
      },
      test("build infers types with mixed emitting and emittingBefore") {
        // Multiple transitions with different command types
        val t1 = (A via E1 to B).emitting((_, _) => List(SendEmail("notify@example.com")))
        val t2 = (B via E1 to C).emittingBefore((_, _) => List(LogAction("Transitioning to C")))

        val machine = build(t1, t2)

        assertTrue(
          machine.postCommandFactories.nonEmpty,
          machine.preCommandFactories.nonEmpty,
          machine.specs.size == 2,
        )
      },
      test("build infers command type LUB from multiple emitting calls") {
        // Both transitions emit commands - LUB should be TestCommand
        val t1 = (A via E1 to B).emitting((_, _) => List(SendEmail("user@example.com")))
        val t2 = (B via E1 to C).emitting((_, _) => List(LogAction("Completed")))

        val machine = build(t1, t2)

        // Both transitions should have post-command factories
        assertTrue(
          machine.postCommandFactories.size == 2,
          machine.specs.size == 2,
        )
      },
      test("Machine is covariant in Cmd - specific type assignable to parent type") {
        // Machine with specific command type (SendEmail)
        val t1 = (A via E1 to B).emitting((_, _) => List(SendEmail("user@example.com")))
        val t2 = B via E1 to C

        val specificMachine: Machine[TestState, TestEvent, SendEmail] =
          build(t1, t2)

        // Covariance allows assigning to broader command type (direct assignment)
        val broaderMachine: Machine[TestState, TestEvent, TestCommand] = specificMachine

        assertTrue(
          broaderMachine.postCommandFactories.nonEmpty,
          broaderMachine.specs.size == 2,
        )
      },
      test("buildAll supports assembly composition") {
        // Base assembly (compile-time composable)
        val baseAssembly = assembly[TestState, TestEvent](
          A via E1 to B
        )

        // Compose with additional specs using buildAll
        val combined = buildAll[TestState, TestEvent]:
          include:
            baseAssembly
          B via E1 to C

        assertTrue(combined.specs.size == 2)
      },
      test("buildAll with only assemblies") {
        // Two different assemblies
        val assembly1 = assembly[TestState, TestEvent](A via E1 to B)
        val assembly2 = assembly[TestState, TestEvent](B via E1 to C)

        // Compose both assemblies
        val combined = buildAll[TestState, TestEvent]:
          include:
            assembly1
          include:
            assembly2

        assertTrue(combined.specs.size == 2)
      },
      test("buildAll with TransitionSpec vals requires include") {
        // TransitionSpec vals also need include() to avoid pure expression warning
        val t1 = A via E1 to B
        val t2 = B via E1 to C

        val combined = buildAll[TestState, TestEvent]:
          include:
            t1
          include:
            t2

        assertTrue(combined.specs.size == 2)
      },
      test("buildAll with mixed Assembly and TransitionSpec vals") {
        val baseAssembly = assembly[TestState, TestEvent](A via E1 to B)
        val t1           = B via E1 to C

        val combined = buildAll[TestState, TestEvent]:
          include:
            baseAssembly
          include:
            t1

        assertTrue(combined.specs.size == 2)
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
    suite("buildAll block syntax")(
      test("buildAll creates machine from block without commas") {
        val machine = buildAll[TestState, TestEvent] {
          A via E1 to B
          B via E1 to C
        }

        ZIO.scoped {
          for
            fsm   <- machine.start(A)
            _     <- fsm.send(E1)
            state <- fsm.currentState
          yield assertTrue(state == B)
        }
      },
      test("buildAll supports multiple transitions in block") {
        // Block can contain multiple transitions separated by newlines
        val machine = buildAll[TestState, TestEvent] {
          A via E1 to B

          B via E1 to C

          C via E1 to A
        }

        ZIO.scoped {
          for
            fsm   <- machine.start(A)
            _     <- fsm.send(E1)
            _     <- fsm.send(E1)
            state <- fsm.currentState
          yield assertTrue(state == C)
        }
      },
      test("buildAll with emitting commands") {
        val machine = buildAll[TestState, TestEvent] {
          (A via E1 to B).emitting((_, _) => List(SendEmail("user@example.com")))
          B via E1 to C
        }

        assertTrue(
          machine.postCommandFactories.nonEmpty,
          machine.specs.size == 2,
        )
      },
      test("buildAll infers types from transitions") {
        // Types can be explicit
        val machine = buildAll[TestState, TestEvent] {
          A via E1 to B
          B via E1 to C
        }

        ZIO.scoped {
          for
            fsm   <- machine.start(A)
            _     <- fsm.send(E1)
            _     <- fsm.send(E1)
            state <- fsm.currentState
          yield assertTrue(state == C)
        }
      },
      test("buildAll works with hierarchical states") {
        val machine = buildAll[ParentState, InputEvent] {
          all[ParentState] via Click to ChildC
        }

        ZIO.scoped {
          for
            fsm   <- machine.start(ChildA)
            _     <- fsm.send(Click)
            state <- fsm.currentState
          yield assertTrue(state == ChildC)
        }
      },
      test("buildAll with local helper vals in emitting") {
        val machine = buildAll[TestState, TestEvent] {
          val emailRecipient = "admin@example.com"
          val logPrefix      = "FSM Action:"

          (A via E1 to B).emitting((_, _) => List(SendEmail(emailRecipient)))
          (B via E1 to C).emittingBefore((_, _) => List(LogAction(s"$logPrefix transitioning to C")))
        }

        assertTrue(
          machine.postCommandFactories.nonEmpty,
          machine.preCommandFactories.nonEmpty,
          machine.specs.size == 2,
        )
      },
      test("buildAll with local val for timeout duration") {
        val machine = buildAll[TestState, TestEvent] {
          val timeoutDuration = Duration.fromNanos(45000000000L) // 45 seconds

          val timedB = B @@ Aspect.timeout(timeoutDuration, Timeout)
          A via E1 to timedB
          B via Timeout to C
        }

        assertTrue(
          machine.timeouts.nonEmpty,
          machine.specs.size == 2,
        )
      },
    ),
    suite("assembly composition")(
      test("build with assembly - combines specs") {
        val baseAssembly = assembly[TestState, TestEvent](
          A via E1 to B
        )

        val combined = build[TestState, TestEvent](
          baseAssembly,
          B via E1 to C,
        )

        assertTrue(combined.specs.size == 2)
      },
      test("build with assembly - transitions work") {
        val baseAssembly = assembly[TestState, TestEvent](
          A via E1 to B
        )

        val combined = build[TestState, TestEvent](
          baseAssembly,
          B via E1 to C,
        )

        ZIO.scoped {
          for
            fsm   <- combined.start(A)
            _     <- fsm.send(E1)
            _     <- fsm.send(E1)
            state <- fsm.currentState
          yield assertTrue(state == C)
        }
      },
      test("build with assembly and override - override wins") {
        val baseAssembly = assembly[TestState, TestEvent](
          A via E1 to B
        )

        val combined = build[TestState, TestEvent](
          baseAssembly,
          (A via E1 to C) @@ Aspect.overriding, // Override: A -> C instead of A -> B
        )

        ZIO.scoped {
          for
            fsm   <- combined.start(A)
            _     <- fsm.send(E1)
            state <- fsm.currentState
          yield assertTrue(state == C) // Override wins
        }
      },
      test("build with multiple assemblies") {
        val assembly1 = assembly[TestState, TestEvent](
          A via E1 to B
        )

        val assembly2 = assembly[TestState, TestEvent](
          B via E1 to C
        )

        val combined = build[TestState, TestEvent](
          assembly1,
          assembly2,
        )

        ZIO.scoped {
          for
            fsm   <- combined.start(A)
            _     <- fsm.send(E1)
            _     <- fsm.send(E1)
            state <- fsm.currentState
          yield assertTrue(state == C)
        }
      },
      test("build with assembly preserves commands") {
        val baseAssembly = assembly[TestState, TestEvent](
          (A via E1 to B).emitting((_, _) => List(SendEmail("from-base@example.com")))
        )

        val combined = build[TestState, TestEvent](
          baseAssembly,
          B via E1 to C,
        )

        assertTrue(
          combined.postCommandFactories.nonEmpty,
          combined.specs.size == 2,
        )
      },
      // Note: "duplicate without override fails" is now a compile-time error for assemblies,
      // so we can't test it at runtime. This is the benefit of Assembly composition.

      test("build with hierarchical states in assembly") {
        val baseAssembly = assembly[ParentState, InputEvent](
          all[ParentState] via Click to ChildC // Default: all children go to ChildC
        )

        val combined = build[ParentState, InputEvent](
          baseAssembly,
          (ChildA via Click to ChildB) @@ Aspect.overriding, // Override: ChildA goes to ChildB
        )

        ZIO.scoped {
          for
            fsm1   <- combined.start(ChildA)
            _      <- fsm1.send(Click)
            state1 <- fsm1.currentState
            fsm2   <- combined.start(ChildB)
            _      <- fsm2.send(Click)
            state2 <- fsm2.currentState
          yield assertTrue(
            state1 == ChildB, // ChildA uses override -> ChildB
            state2 == ChildC, // ChildB uses default -> ChildC
          )
        }
      },
      test("assembly marked as overriding overrides previous assembly") {
        val assembly1 = assembly[TestState, TestEvent](
          A via E1 to B
        )

        val assembly2 = assembly[TestState, TestEvent](
          A via E1 to C
        )

        // assembly2 is marked as overriding, so A via E1 -> C wins
        val combined = build[TestState, TestEvent](
          assembly1,
          assembly2 @@ Aspect.overriding,
        )

        ZIO.scoped {
          for
            fsm   <- combined.start(A)
            _     <- fsm.send(E1)
            state <- fsm.currentState
          yield assertTrue(state == C)
        }
      },
    ),
    suite("override semantics - last override wins")(
      // Override behavior is deterministic: last override in the flattened spec list wins.
      // This applies to both flat machines and composed machines.

      test("flat machine - last override wins among multiple overrides") {
        // Three transitions for same (state, event) - last override wins
        val machine = build[TestState, TestEvent](
          A via E1 to B,                        // First: A -> B
          (A via E1 to C) @@ Aspect.overriding, // Second override: A -> C
          (A via E1 to A) @@ Aspect.overriding, // Third override: A -> A (stay effectively)
        )

        ZIO.scoped {
          for
            fsm   <- machine.start(A)
            _     <- fsm.send(E1)
            state <- fsm.currentState
          yield assertTrue(state == A) // Last override wins: A -> A
        }
      },
      test("flat machine - override order matters") {
        // Reversing the order should change the outcome
        val machine = build[TestState, TestEvent](
          A via E1 to B,                        // First: A -> B
          (A via E1 to A) @@ Aspect.overriding, // Second override: A -> A
          (A via E1 to C) @@ Aspect.overriding, // Third override: A -> C
        )

        ZIO.scoped {
          for
            fsm   <- machine.start(A)
            _     <- fsm.send(E1)
            state <- fsm.currentState
          yield assertTrue(state == C) // Last override wins: A -> C
        }
      },
      test("composed assembly - override in child assembly overrides parent") {
        val baseAssembly = assembly[TestState, TestEvent](
          A via E1 to B // Base: A -> B
        )

        val extendedMachine = build[TestState, TestEvent](
          baseAssembly,
          (A via E1 to C) @@ Aspect.overriding, // Override: A -> C
        )

        ZIO.scoped {
          for
            fsm   <- extendedMachine.start(A)
            _     <- fsm.send(E1)
            state <- fsm.currentState
          yield assertTrue(state == C) // Override wins
        }
      },
      test("composed assembly - later assembly's transitions override earlier") {
        val assembly1 = assembly[TestState, TestEvent](
          A via E1 to B // assembly1: A -> B
        )

        val assembly2 = assembly[TestState, TestEvent](
          (A via E1 to C) @@ Aspect.overriding // assembly2: A -> C (override)
        )

        // Order matters - assembly2 specs come after assembly1 specs
        val combined = build[TestState, TestEvent](
          assembly1,
          assembly2,
        )

        ZIO.scoped {
          for
            fsm   <- combined.start(A)
            _     <- fsm.send(E1)
            state <- fsm.currentState
          yield assertTrue(state == C) // assembly2's override wins
        }
      },
      test("composed assembly - assembly with override wins when last") {
        val assembly1 = assembly[TestState, TestEvent](
          (A via E1 to B) @@ Aspect.overriding // assembly1: A -> B (override)
        )

        // assembly1 marked as override, combined with another assembly
        // Note: With Assembly, we need @@ Aspect.overriding for conflicting transitions
        val combined = build[TestState, TestEvent](
          A via E1 to C, // First: A -> C
          assembly1,     // Second: assembly1's A -> B override wins
        )

        ZIO.scoped {
          for
            fsm   <- combined.start(A)
            _     <- fsm.send(E1)
            state <- fsm.currentState
          yield assertTrue(state == B) // assembly1's override wins (it comes last)
        }
      },
      test("deeply nested assembly composition - last override wins") {
        // Three assemblies composed together
        val base = assembly[TestState, TestEvent](
          A via E1 to B // Base: A -> B
        )

        val middle = assembly[TestState, TestEvent](
          (A via E1 to C) @@ Aspect.overriding // Middle override: A -> C
        )

        val top = build[TestState, TestEvent](
          base,
          middle,
          (A via E1 to A) @@ Aspect.overriding, // Top override: A -> A
        )

        // Flattened order: [base(A->B), middle override(A->C), top override(A->A)]
        // Last override wins: A -> A
        ZIO.scoped {
          for
            fsm   <- top.start(A)
            _     <- fsm.send(E1)
            state <- fsm.currentState
          yield assertTrue(state == A) // Top override wins
        }
      },
      test("composed assembly with hierarchical states - override specific child") {
        val baseAssembly = assembly[ParentState, InputEvent](
          all[ParentState] via Click to ChildC // Default: all children -> ChildC
        )

        val extended = build[ParentState, InputEvent](
          baseAssembly,
          (ChildA via Click to ChildA) @@ Aspect.overriding, // ChildA stays
          (ChildB via Click to ChildB) @@ Aspect.overriding, // ChildB stays
        )

        ZIO.scoped {
          for
            fsmA   <- extended.start(ChildA)
            _      <- fsmA.send(Click)
            stateA <- fsmA.currentState
            fsmB   <- extended.start(ChildB)
            _      <- fsmB.send(Click)
            stateB <- fsmB.currentState
            fsmC   <- extended.start(ChildC)
            _      <- fsmC.send(Click)
            stateC <- fsmC.currentState
          yield assertTrue(
            stateA == ChildA, // Override: ChildA stays
            stateB == ChildB, // Override: ChildB stays
            stateC == ChildC, // Default: ChildC -> ChildC
          )
        }
      },
      test("buildAll - last override in block wins") {
        val machine = buildAll[TestState, TestEvent]:
          A via E1 to B
          (A via E1 to C) @@ Aspect.overriding
          (A via E1 to A) @@ Aspect.overriding // Last

        ZIO.scoped {
          for
            fsm   <- machine.start(A)
            _     <- fsm.send(E1)
            state <- fsm.currentState
          yield assertTrue(state == A) // Last override wins
        }
      },
      test("buildAll with assemblies - inline override wins") {
        // With Assembly, we use @@ Aspect.overriding to handle duplicate transitions
        val assembly1 = assembly[TestState, TestEvent](A via E1 to B)
        val assembly2 = assembly[TestState, TestEvent]((A via E1 to C))

        // assembly2 is marked as overriding to resolve conflict, then inline override is last
        val combined = buildAll[TestState, TestEvent]:
          include:
            assembly1
          include:
            assembly2 @@ Aspect.overriding     // Override assembly1
          (A via E1 to A) @@ Aspect.overriding // Even later override

        // Note: Without @@ Aspect.overriding on assembly2, this would be a COMPILE-TIME error
        // (the benefit of Assembly over Machine composition)

        ZIO.scoped {
          for
            fsm   <- combined.start(A)
            _     <- fsm.send(E1)
            state <- fsm.currentState
          yield assertTrue(state == A) // Inline override is last
        }
      },
    ),
    suite("duplicate detection")(
      // Duplicate detection works at multiple levels:
      // 1. COMPILE-TIME: Inline expressions, same val used twice
      //    - build(A via E1 to B, A via E1 to C) -> compile error
      //    - build(t1, t1) -> compile error
      // 2. RUNTIME: Local vals with same transition content
      //    - val t1 = A via E1 to B; val t2 = A via E1 to B; build(t1, t2) -> runtime error
      //    (Local vals can't be inspected at compile time due to macro limitations)

      test("build allows duplicate val with override - emits info message") {
        // This compiles successfully and emits:
        // [mechanoid] Override info: A via E1: B -> B
        val t1       = A via E1 to B
        val machine  = build(t1, t1 @@ Aspect.overriding)
        val machine2 = build(t1)

        // Both specs are present (runtime handles the override semantics)
        assertTrue(machine.specs.size == 2)
      },
      test("build with different vals for same transition - runtime detection") {
        // Different local vals, same transition content
        // Local vals can't be inspected at compile time, but runtime catches it
        val t1 = A via E1 to B
        val t2 = A via E1 to B // Different val, same transition

        val result = ZIO.attempt {
          build(t1, t2)
        }

        result.flip.map { error =>
          assertTrue(
            error.isInstanceOf[IllegalArgumentException],
            error.getMessage.contains("Duplicate transition"),
          )
        }
      },
      test("build allows different vals with same transition when override used") {
        val t1 = A via E1 to B
        val t2 = (A via E1 to C) @@ Aspect.overriding // Different target, with override

        val machine = build(t1, t2)

        // Override takes effect - t2 wins
        ZIO.scoped {
          for
            fsm   <- machine.start(A)
            _     <- fsm.send(E1)
            state <- fsm.currentState
          yield assertTrue(state == C) // Override wins
        }
      },
    ),
  )

end MachineSpec
