package mechanoid.dsl

import zio.*
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
    private val fromState: S,
):

  /** Define a transition triggered by a specific event. */
  def on(event: E): TransitionBuilder[S, E, R, Err] =
    new TransitionBuilder(definition, fromState, event, None)

  /** Define a transition triggered by the timeout event. */
  def onTimeout: TransitionBuilder[S, E, R, Err] =
    new TransitionBuilder(definition, fromState, Timeout, None)
end WhenBuilder
