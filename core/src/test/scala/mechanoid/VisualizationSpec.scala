package mechanoid

import zio.*
import zio.test.*
import mechanoid.core.Finite
import mechanoid.machine.*
import mechanoid.visualization.*
import java.time.Instant

object VisualizationSpec extends ZIOSpecDefault:

  // Simple test FSM
  enum TestState derives Finite:
    case Idle, Running, Completed, Failed

  enum TestEvent derives Finite:
    case Start, Finish, Error

  // Use the new DSL - Machine is used directly for visualization
  val testMachine: Machine[TestState, TestEvent, Nothing] =
    import TestState.*, TestEvent.*
    build[TestState, TestEvent](
      Idle via Start to Running,
      Running via Finish to Completed,
      Running via Error to Failed,
    )
  end testMachine

  // Machine is used directly for visualization
  val testFSM = testMachine

  def spec = suite("Visualization")(
    suite("Finite name extraction")(
      test("extracts state names correctly") {
        val se = summon[Finite[TestState]]
        assertTrue(
          se.caseNames.values.toSet == Set("Idle", "Running", "Completed", "Failed"),
          se.nameOf(TestState.Idle) == "Idle",
          se.nameOf(TestState.Running) == "Running",
        )
      },
      test("extracts event names correctly") {
        val ee = summon[Finite[TestEvent]]
        assertTrue(
          ee.caseNames.values.toSet == Set("Start", "Finish", "Error"),
          ee.nameOf(TestEvent.Start) == "Start",
          ee.nameOf(TestEvent.Error) == "Error",
        )
      },
      test("caseHash is stable and based on full name") {
        val se = summon[Finite[TestState]]
        // caseHash should be the hashCode of the fully qualified name
        val idleHash    = se.caseHash(TestState.Idle)
        val runningHash = se.caseHash(TestState.Running)
        assertTrue(
          idleHash != runningHash,
          se.nameFor(idleHash) == "Idle",
          se.nameFor(runningHash) == "Running",
        )
      },
    ),
    suite("TransitionMeta tracking")(
      test("FSM captures transition metadata") {
        assertTrue(
          testFSM.transitionMeta.nonEmpty,
          testFSM.transitionMeta.size == 3,
        )
      },
      test("metadata includes correct source states") {
        val sources         = testFSM.transitionMeta.map(_.fromStateCaseHash).toSet
        val idleCaseHash    = testFSM.stateEnum.caseHash(TestState.Idle)
        val runningCaseHash = testFSM.stateEnum.caseHash(TestState.Running)
        assertTrue(sources == Set(idleCaseHash, runningCaseHash))
      },
      test("metadata includes correct target states") {
        val targets           = testFSM.transitionMeta.flatMap(_.targetStateCaseHash).toSet
        val runningCaseHash   = testFSM.stateEnum.caseHash(TestState.Running)
        val completedCaseHash = testFSM.stateEnum.caseHash(TestState.Completed)
        val failedCaseHash    = testFSM.stateEnum.caseHash(TestState.Failed)
        assertTrue(targets == Set(runningCaseHash, completedCaseHash, failedCaseHash))
      },
    ),
    suite("MermaidVisualizer")(
      test("generates valid state diagram") {
        val diagram = MermaidVisualizer.stateDiagram(testFSM)
        assertTrue(
          diagram.contains("stateDiagram-v2"),
          diagram.contains("Idle --> Running: Start"),
          diagram.contains("Running --> Completed: Finish"),
          diagram.contains("Running --> Failed: Error"),
        )
      },
      test("state diagram includes initial state arrow") {
        val diagram = MermaidVisualizer.stateDiagram(testFSM, Some(TestState.Idle))
        assertTrue(diagram.contains("[*] --> Idle"))
      },
      test("generates valid flowchart") {
        val flowchart = MermaidVisualizer.flowchart(testFSM)
        assertTrue(
          flowchart.contains("flowchart LR"),
          flowchart.contains("Idle((Idle))"),
          flowchart.contains("Running((Running))"),
          flowchart.contains("Idle -->|Start| Running"),
        )
      },
      test("generates sequence diagram from trace") {
        val trace = ExecutionTrace(
          instanceId = "test-1",
          initialState = TestState.Idle,
          currentState = TestState.Running,
          steps = List(
            TraceStep(1, TestState.Idle, TestState.Running, TestEvent.Start, Instant.now, false)
          ),
        )
        val seq = MermaidVisualizer.sequenceDiagram(
          trace,
          summon[Finite[TestState]],
          summon[Finite[TestEvent]],
        )
        assertTrue(
          seq.contains("sequenceDiagram"),
          seq.contains("participant FSM as test-1"),
          seq.contains("Note over FSM: Idle"),
          seq.contains("FSM->>FSM: Start"),
          seq.contains("Note over FSM: Running"),
        )
      },
    ),
    suite("GraphVizVisualizer")(
      test("generates valid digraph") {
        val dot = GraphVizVisualizer.digraph(testFSM)
        assertTrue(
          dot.contains("digraph FSM {"),
          dot.contains("rankdir=LR"),
          dot.contains("Idle -> Running [label=\"Start\"]"),
          dot.contains("Running -> Completed [label=\"Finish\"]"),
          dot.contains("Running -> Failed [label=\"Error\"]"),
        )
      },
      test("digraph includes initial state marker") {
        val dot = GraphVizVisualizer.digraph(testFSM, initialState = Some(TestState.Idle))
        assertTrue(
          dot.contains("__start__ [shape=point"),
          dot.contains("__start__ -> Idle"),
        )
      },
      test("generates timeline from trace") {
        val trace = ExecutionTrace(
          instanceId = "test-1",
          initialState = TestState.Idle,
          currentState = TestState.Completed,
          steps = List(
            TraceStep(1, TestState.Idle, TestState.Running, TestEvent.Start, Instant.now, false),
            TraceStep(2, TestState.Running, TestState.Completed, TestEvent.Finish, Instant.now, false),
          ),
        )
        val timeline = GraphVizVisualizer.timeline(
          trace,
          summon[Finite[TestState]],
          summon[Finite[TestEvent]],
        )
        assertTrue(
          timeline.contains("digraph Timeline"),
          timeline.contains("s0 [label=\"Idle\""),
          timeline.contains("s1 [label=\"Running\""),
          timeline.contains("s2 [label=\"Completed\""),
          timeline.contains("s0 -> s1 [label=\"Start\"]"),
          timeline.contains("s1 -> s2 [label=\"Finish\"]"),
        )
      },
    ),
    suite("Extension methods")(
      test("FSMDefinition has toMermaidStateDiagram extension") {
        val diagram = testFSM.toMermaidStateDiagram()
        assertTrue(diagram.contains("stateDiagram-v2"))
      },
      test("FSMDefinition has toGraphViz extension") {
        val dot = testFSM.toGraphViz()
        assertTrue(dot.contains("digraph FSM"))
      },
      test("ExecutionTrace has toMermaidSequenceDiagram extension") {
        given Finite[TestState] = summon
        given Finite[TestEvent] = summon
        val trace               = ExecutionTrace(
          instanceId = "test-1",
          initialState = TestState.Idle,
          currentState = TestState.Running,
          steps = List(
            TraceStep(1, TestState.Idle, TestState.Running, TestEvent.Start, Instant.now, false)
          ),
        )
        val seq = trace.toMermaidSequenceDiagram
        assertTrue(seq.contains("sequenceDiagram"))
      },
    ),
    suite("ExecutionTrace")(
      test("empty creates trace with no steps") {
        val trace = ExecutionTrace.empty[TestState, TestEvent]("test-1", TestState.Idle)
        assertTrue(
          trace.instanceId == "test-1",
          trace.initialState == TestState.Idle,
          trace.currentState == TestState.Idle,
          trace.isEmpty,
          trace.stepCount == 0,
          trace.visitedStates == Set(TestState.Idle),
        )
      }
    ),
  ) @@ TestAspect.sequential
end VisualizationSpec
