package mechanoid.dsl

import mechanoid.core.*

/** Builder for transitions from a specific state.
  *
  * @tparam S
  *   The state type
  * @tparam E
  *   The event type
  * @tparam Cmd
  *   The command type
  */
final class WhenBuilder[S <: MState, E <: MEvent, Cmd](
    private val definition: FSMDefinition[S, E, Cmd],
    private val fromStateCaseHash: Int,
):

  /** Define a transition triggered by a specific event. */
  def on(event: E): TransitionBuilder[S, E, Cmd] =
    new TransitionBuilder(definition, fromStateCaseHash, event.timed)

  /** Define a transition triggered by the timeout event. */
  def onTimeout: TransitionBuilder[S, E, Cmd] =
    new TransitionBuilder(definition, fromStateCaseHash, Timed.TimeoutEvent)
end WhenBuilder
