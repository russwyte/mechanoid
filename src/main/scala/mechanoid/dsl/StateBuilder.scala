package mechanoid.dsl

import zio.*
import mechanoid.core.*

/** Builder for configuring state lifecycle actions (entry/exit).
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
final class StateBuilder[S <: MState, E <: MEvent, R, Err](
    private var definition: FSMDefinition[S, E, R, Err],
    private val stateOrdinal: Int,
):

  /** Define an action to execute when entering this state.
    *
    * The entry action runs after the transition completes but before any new events are processed.
    */
  def onEntry[R1 <: R, Err1 >: Err](action: ZIO[R1, Err1, Unit]): StateBuilder[S, E, R1, Err1] =
    definition = definition
      .asInstanceOf[FSMDefinition[S, E, R1, Err1]]
      .updateLifecycle(stateOrdinal, lc => lc.copy(onEntry = Some(action)))
      .asInstanceOf[FSMDefinition[S, E, R, Err]]
    this.asInstanceOf[StateBuilder[S, E, R1, Err1]]

  /** Define an action to execute when exiting this state.
    *
    * The exit action runs before the transition to the new state begins.
    */
  def onExit[R1 <: R, Err1 >: Err](action: ZIO[R1, Err1, Unit]): StateBuilder[S, E, R1, Err1] =
    definition = definition
      .asInstanceOf[FSMDefinition[S, E, R1, Err1]]
      .updateLifecycle(stateOrdinal, lc => lc.copy(onExit = Some(action)))
      .asInstanceOf[FSMDefinition[S, E, R, Err]]
    this.asInstanceOf[StateBuilder[S, E, R1, Err1]]

  /** Complete the state configuration and return to the FSM definition. */
  def done: FSMDefinition[S, E, R, Err] = definition
end StateBuilder
