package mechanoid.dsl

import mechanoid.core.*

/** Builder for transitions from a specific state.
  *
  * @tparam S
  *   The state type
  * @tparam E
  *   The event type
  * @tparam R
  *   The ZIO environment required
  * @tparam Err
  *   The error type
  */
final class WhenBuilder[S <: MState, E <: MEvent, R, Err](
    private val definition: FSMDefinition[S, E, R, Err],
    private val fromStateOrdinal: Int,
):

  /** Define a transition triggered by a specific event. */
  def on(event: E): TransitionBuilder[S, E, R, Err] =
    new TransitionBuilder(definition, fromStateOrdinal, event.timed, None)

  /** Define a transition triggered by the timeout event. */
  def onTimeout: TransitionBuilder[S, E, R, Err] =
    new TransitionBuilder(definition, fromStateOrdinal, Timed.TimeoutEvent, None)
end WhenBuilder
