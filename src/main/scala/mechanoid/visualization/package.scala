package mechanoid

import mechanoid.core.*
import mechanoid.dsl.FSMDefinition

/** Visualization extensions and utilities for FSM definitions and execution traces. */
package object visualization:

  /** Extension methods for visualizing FSM definitions. */
  extension [S <: MState, E <: MEvent, R, Err](fsm: FSMDefinition[S, E, R, Err])

    /** Generate a Mermaid state diagram. */
    def toMermaidStateDiagram(initialState: Option[S] = None): String =
      MermaidVisualizer.stateDiagram(fsm, initialState)

    /** Generate a Mermaid flowchart. */
    def toMermaidFlowchart: String =
      MermaidVisualizer.flowchart(fsm)

    /** Generate a Mermaid flowchart with execution trace highlighting. */
    def toMermaidFlowchartWithTrace(trace: ExecutionTrace[S, E]): String =
      MermaidVisualizer.flowchart(fsm, Some(trace))

    /** Generate a GraphViz digraph. */
    def toGraphViz(
        name: String = "FSM",
        initialState: Option[S] = None,
        config: GraphVizVisualizer.Config = GraphVizVisualizer.Config.default,
    ): String =
      GraphVizVisualizer.digraph(fsm, name, initialState, config)

    /** Generate a GraphViz digraph with execution trace highlighting. */
    def toGraphVizWithTrace(
        trace: ExecutionTrace[S, E],
        name: String = "FSM",
        config: GraphVizVisualizer.Config = GraphVizVisualizer.Config.default,
    ): String =
      GraphVizVisualizer.digraphWithTrace(fsm, trace, name, config)
  end extension

  /** Extension methods for visualizing execution traces. */
  extension [S <: MState, E <: MEvent](trace: ExecutionTrace[S, E])

    /** Generate a Mermaid sequence diagram. */
    def toMermaidSequenceDiagram(using stateEnum: SealedEnum[S], eventEnum: SealedEnum[E]): String =
      MermaidVisualizer.sequenceDiagram(trace, stateEnum, eventEnum)

    /** Generate a GraphViz timeline. */
    def toGraphVizTimeline(using stateEnum: SealedEnum[S], eventEnum: SealedEnum[E]): String =
      GraphVizVisualizer.timeline(trace, stateEnum, eventEnum)
end visualization
