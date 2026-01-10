package mechanoid.visualization

import scala.concurrent.duration.Duration
import java.time.Instant
import mechanoid.core.*

/** Metadata about a transition for visualization purposes. */
case class TransitionMeta(
    fromStateOrdinal: Int,
    eventOrdinal: Int,
    targetStateOrdinal: Option[Int], // None for Stay, Some for Goto
    hasGuard: Boolean,
    description: Option[String] = None,
)

/** Metadata about a state for visualization. */
case class StateMeta(
    ordinal: Int,
    name: String,
    hasEntryAction: Boolean,
    hasExitAction: Boolean,
    timeout: Option[Duration],
)

/** Complete FSM metadata for static structure visualization. */
case class FSMMeta(
    transitions: List[TransitionMeta],
    states: List[StateMeta],
    stateNames: Map[Int, String],
    eventNames: Map[Int, String],
)

/** A single step in an FSM execution trace. */
case class TraceStep[S <: MState, E <: MEvent](
    sequenceNumber: Int,
    from: S,
    to: S,
    event: E,
    timestamp: Instant,
    isTimeout: Boolean,
):
  def isSelfTransition: Boolean = from == to

/** Complete execution trace for runtime visualization. */
case class ExecutionTrace[S <: MState, E <: MEvent](
    instanceId: String,
    initialState: S,
    currentState: S,
    steps: List[TraceStep[S, E]],
):
  def isEmpty: Boolean        = steps.isEmpty
  def stepCount: Int          = steps.size
  def isTerminal: Boolean     = steps.lastOption.exists(s => s.to == s.from && s.isTimeout)
  def visitedStates: Set[S]   = steps.flatMap(s => Set(s.from, s.to)).toSet + initialState
  def triggeredEvents: Set[E] = steps.map(_.event).toSet
end ExecutionTrace

object ExecutionTrace:
  /** Create an empty trace starting from an initial state. */
  def empty[S <: MState, E <: MEvent](
      instanceId: String,
      initialState: S,
  ): ExecutionTrace[S, E] =
    ExecutionTrace(instanceId, initialState, initialState, Nil)
end ExecutionTrace
