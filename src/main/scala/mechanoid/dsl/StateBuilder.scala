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
  */
final class StateBuilder[S <: MState, E <: MEvent](
    private val definition: FSMDefinition[S, E],
    private val stateOrdinal: Int,
):

  /** Define an action to execute when entering this state.
    *
    * The entry action runs after the transition completes but before any new events are processed. The action name is
    * automatically extracted at compile time for visualization purposes.
    *
    * User errors are wrapped in `ActionFailedError`.
    */
  inline def onEntry[Err1](inline action: ZIO[Any, Err1, Unit]): StateBuilder[S, E] =
    val description = ExpressionName.of(action)
    onEntryWithDescription(action, description)

  /** Define an action with explicit description to execute when entering this state.
    *
    * Use this when you want to provide a custom description instead of the auto-extracted name.
    *
    * User errors are wrapped in `ActionFailedError`.
    */
  def onEntryWithDescription[Err1](
      action: ZIO[Any, Err1, Unit],
      description: String,
  ): StateBuilder[S, E] =
    val wrappedAction = action.mapError(wrapError)
    val newDef        = definition.updateLifecycle(
      stateOrdinal,
      lc => lc.copy(onEntry = Some(wrappedAction), onEntryDescription = Some(description)),
    )
    new StateBuilder(newDef, stateOrdinal)
  end onEntryWithDescription

  /** Define an action to execute when exiting this state.
    *
    * The exit action runs before the transition to the new state begins. The action name is automatically extracted at
    * compile time for visualization purposes.
    *
    * User errors are wrapped in `ActionFailedError`.
    */
  inline def onExit[Err1](inline action: ZIO[Any, Err1, Unit]): StateBuilder[S, E] =
    val description = ExpressionName.of(action)
    onExitWithDescription(action, description)

  /** Define an action with explicit description to execute when exiting this state.
    *
    * Use this when you want to provide a custom description instead of the auto-extracted name.
    *
    * User errors are wrapped in `ActionFailedError`.
    */
  def onExitWithDescription[Err1](
      action: ZIO[Any, Err1, Unit],
      description: String,
  ): StateBuilder[S, E] =
    val wrappedAction = action.mapError(wrapError)
    val newDef        = definition.updateLifecycle(
      stateOrdinal,
      lc => lc.copy(onExit = Some(wrappedAction), onExitDescription = Some(description)),
    )
    new StateBuilder(newDef, stateOrdinal)
  end onExitWithDescription

  /** Complete the state configuration and return to the FSM definition. */
  def done: FSMDefinition[S, E] = definition

  /** Wrap an error as MechanoidError - pass through if already one, otherwise wrap in ActionFailedError. */
  private def wrapError[E](e: E): MechanoidError = e match
    case me: MechanoidError => me
    case other              => ActionFailedError(other)
end StateBuilder
