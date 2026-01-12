package mechanoid.typelevel

import mechanoid.core.*
import scala.annotation.implicitNotFound

/** Type class witnessing that a transition from state S via event E is valid.
  *
  * This enables compile-time validation of FSM transitions. Users can define allowed transitions using given instances,
  * and the compiler will reject any attempt to send an invalid event for the current state.
  *
  * @tparam S
  *   The source state type
  * @tparam E
  *   The event type
  */
@implicitNotFound(
  "No valid transition defined from state ${S} via event ${E}. " +
    "Define a given ValidTransition[${S}, ${E}] to allow this transition."
)
trait ValidTransition[S <: MState, E <: MEvent]:
  /** The target state type after the transition. */
  type Target <: MState

object ValidTransition:
  /** Create a ValidTransition instance with a specified target state. */
  def apply[S <: MState, E <: MEvent, T <: MState]: ValidTransition[S, E] { type Target = T } =
    new ValidTransition[S, E]:
      type Target = T

  /** Auxiliary type for specifying target state. */
  type Aux[S <: MState, E <: MEvent, T <: MState] = ValidTransition[S, E] { type Target = T }

  /** Create a transition that allows going to any state. */
  def any[S <: MState, E <: MEvent]: ValidTransition[S, E] =
    new ValidTransition[S, E]:
      type Target = MState

  /** Derive a ValidTransition for timeout events from any state. */
  inline given timeoutFromAny[S <: MState]: ValidTransition[S, Timeout.type] =
    ValidTransition.any[S, Timeout.type]
end ValidTransition
