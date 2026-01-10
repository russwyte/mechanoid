package mechanoid.dsl

import zio.*
import mechanoid.core.*
import mechanoid.macros.ExpressionName

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
    * The entry action runs after the transition completes but before any new events are processed. The action name is
    * automatically extracted at compile time for visualization purposes.
    */
  inline def onEntry[R1 <: R, Err1 >: Err](inline action: ZIO[R1, Err1, Unit]): StateBuilder[S, E, R1, Err1] =
    val description = ExpressionName.of(action)
    onEntryWithDescription(action, description)

  /** Define an action with explicit description to execute when entering this state.
    *
    * Use this when you want to provide a custom description instead of the auto-extracted name.
    */
  def onEntryWithDescription[R1 <: R, Err1 >: Err](
      action: ZIO[R1, Err1, Unit],
      description: String,
  ): StateBuilder[S, E, R1, Err1] =
    definition = definition
      .asInstanceOf[FSMDefinition[S, E, R1, Err1]]
      .updateLifecycle(stateOrdinal, lc => lc.copy(onEntry = Some(action), onEntryDescription = Some(description)))
      .asInstanceOf[FSMDefinition[S, E, R, Err]]
    this.asInstanceOf[StateBuilder[S, E, R1, Err1]]

  /** Define an action to execute when exiting this state.
    *
    * The exit action runs before the transition to the new state begins. The action name is automatically extracted at
    * compile time for visualization purposes.
    */
  inline def onExit[R1 <: R, Err1 >: Err](inline action: ZIO[R1, Err1, Unit]): StateBuilder[S, E, R1, Err1] =
    val description = ExpressionName.of(action)
    onExitWithDescription(action, description)

  /** Define an action with explicit description to execute when exiting this state.
    *
    * Use this when you want to provide a custom description instead of the auto-extracted name.
    */
  def onExitWithDescription[R1 <: R, Err1 >: Err](
      action: ZIO[R1, Err1, Unit],
      description: String,
  ): StateBuilder[S, E, R1, Err1] =
    definition = definition
      .asInstanceOf[FSMDefinition[S, E, R1, Err1]]
      .updateLifecycle(stateOrdinal, lc => lc.copy(onExit = Some(action), onExitDescription = Some(description)))
      .asInstanceOf[FSMDefinition[S, E, R, Err]]
    this.asInstanceOf[StateBuilder[S, E, R1, Err1]]

  /** Complete the state configuration and return to the FSM definition. */
  def done: FSMDefinition[S, E, R, Err] = definition
end StateBuilder
