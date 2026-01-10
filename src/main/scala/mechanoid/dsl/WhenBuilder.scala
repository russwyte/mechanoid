package mechanoid.dsl

import mechanoid.core.*

/** Builder for transitions from a specific state.
  *
  * @tparam S
  *   The state type
  * @tparam E
  *   The event type
  */
final class WhenBuilder[S <: MState, E <: MEvent](
    private val definition: FSMDefinition[S, E],
    private val fromStateOrdinal: Int,
):

  /** Define a transition triggered by a specific event. */
  def on(event: E): TransitionBuilder[S, E] =
    new TransitionBuilder(definition, fromStateOrdinal, event.timed)

  /** Define a transition triggered by the timeout event. */
  def onTimeout: TransitionBuilder[S, E] =
    new TransitionBuilder(definition, fromStateOrdinal, Timed.TimeoutEvent)
end WhenBuilder
