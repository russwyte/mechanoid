package mechanoid.dsl

import zio.*
import mechanoid.core.*
import mechanoid.macros.ExpressionName

/** Builder for configuring state lifecycle actions (entry/exit) and commands.
  *
  * @tparam S
  *   The state type
  * @tparam E
  *   The event type
  * @tparam Cmd
  *   The command type
  */
final class StateBuilder[S <: MState, E <: MEvent, Cmd](
    private val definition: FSMDefinition[S, E, Cmd],
    private val stateCaseHash: Int,
):

  /** Define an action to execute when entering this state.
    *
    * The entry action runs after the transition completes but before any new events are processed. The action name is
    * automatically extracted at compile time for visualization purposes.
    *
    * User errors are wrapped in `ActionFailedError`.
    */
  inline def onEntry[Err1](inline action: ZIO[Any, Err1, Unit]): StateBuilder[S, E, Cmd] =
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
  ): StateBuilder[S, E, Cmd] =
    val wrappedAction = action.mapError(wrapError)
    val newDef        = definition.updateLifecycle(
      stateCaseHash,
      lc => lc.copy(onEntry = Some(wrappedAction), onEntryDescription = Some(description)),
    )
    new StateBuilder(newDef, stateCaseHash)
  end onEntryWithDescription

  /** Define an action to execute when exiting this state.
    *
    * The exit action runs before the transition to the new state begins. The action name is automatically extracted at
    * compile time for visualization purposes.
    *
    * User errors are wrapped in `ActionFailedError`.
    */
  inline def onExit[Err1](inline action: ZIO[Any, Err1, Unit]): StateBuilder[S, E, Cmd] =
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
  ): StateBuilder[S, E, Cmd] =
    val wrappedAction = action.mapError(wrapError)
    val newDef        = definition.updateLifecycle(
      stateCaseHash,
      lc => lc.copy(onExit = Some(wrappedAction), onExitDescription = Some(description)),
    )
    new StateBuilder(newDef, stateCaseHash)
  end onExitWithDescription

  /** Enqueue a single command when entering this state.
    *
    * Commands are enqueued for later execution via the transactional outbox pattern. The idempotency key is
    * auto-generated as `instanceId-seqNr`.
    *
    * @param factory
    *   A function that produces a command from the current state
    */
  def enqueue(factory: S => Cmd): StateBuilder[S, E, Cmd] =
    val newDef = definition.updateLifecycle(
      stateCaseHash,
      lc =>
        val newFactory: S => List[Cmd] = lc.commandFactory match
          case Some(existing) => s => existing(s) :+ factory(s)
          case None           => s => List(factory(s))
        lc.copy(commandFactory = Some(newFactory)),
    )
    new StateBuilder(newDef, stateCaseHash)
  end enqueue

  /** Enqueue multiple commands when entering this state.
    *
    * Commands are enqueued for later execution via the transactional outbox pattern. Each command gets an
    * auto-generated idempotency key based on `instanceId-seqNr-index`.
    *
    * @param factory
    *   A function that produces a list of commands from the current state
    */
  def enqueueAll(factory: S => List[Cmd]): StateBuilder[S, E, Cmd] =
    val newDef = definition.updateLifecycle(
      stateCaseHash,
      lc =>
        val newFactory: S => List[Cmd] = lc.commandFactory match
          case Some(existing) => s => existing(s) ++ factory(s)
          case None           => factory
        lc.copy(commandFactory = Some(newFactory)),
    )
    new StateBuilder(newDef, stateCaseHash)
  end enqueueAll

  /** Complete the state configuration and return to the FSM definition. */
  def done: FSMDefinition[S, E, Cmd] = definition

  /** Wrap an error as MechanoidError - pass through if already one, otherwise wrap in ActionFailedError. */
  private def wrapError[E](e: E): MechanoidError = e match
    case me: MechanoidError => me
    case other              => ActionFailedError(other)
end StateBuilder
