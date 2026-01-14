package mechanoid.core

import zio.*
import scala.concurrent.duration.Duration

/** A transition definition from one state to another via an event.
  *
  * All transition actions return `MechanoidError` as the error type. User errors are wrapped in `ActionFailedError`.
  *
  * @tparam S
  *   The base state type
  * @tparam E
  *   The event type that triggers this transition
  * @tparam S2
  *   The target state type
  * @param action
  *   A function that receives the current state and event, returning the transition result
  * @param description
  *   Optional human-readable description of this transition
  */
final case class Transition[-S, -E, +S2](
    action: (S, E) => ZIO[Any, MechanoidError, TransitionResult[S2]],
    description: Option[String] = None,
)

object Transition:
  /** Create a simple transition that goes to a target state. */
  def goto[S, E, S2](target: S2): Transition[S, E, S2] =
    Transition((_, _) => ZIO.succeed(TransitionResult.Goto(target)), None)

  /** Create a transition that stays in the current state. */
  def stay[S, E]: Transition[S, E, S] =
    Transition((_, _) => ZIO.succeed(TransitionResult.Stay), None)

/** Entry and exit actions for a state.
  *
  * All lifecycle actions return `MechanoidError` as the error type. User errors are wrapped in `ActionFailedError`.
  *
  * @tparam S
  *   The state type
  * @tparam Cmd
  *   The command type for this FSM
  * @param onEntry
  *   Action to execute when entering this state
  * @param onExit
  *   Action to execute when exiting this state
  * @param commandFactory
  *   Factory to produce commands when entering this state (for transactional outbox pattern)
  * @param onEntryDescription
  *   Human-readable description of entry action (for visualization)
  * @param onExitDescription
  *   Human-readable description of exit action (for visualization)
  */
final case class StateLifecycle[-S, +Cmd](
    onEntry: Option[ZIO[Any, MechanoidError, Unit]] = None,
    onExit: Option[ZIO[Any, MechanoidError, Unit]] = None,
    commandFactory: Option[S => List[Cmd]] = None,
    onEntryDescription: Option[String] = None,
    onExitDescription: Option[String] = None,
)

object StateLifecycle:
  def empty[S, Cmd]: StateLifecycle[S, Cmd] =
    StateLifecycle(None, None, None, None, None)

/** Timeout configuration for a state.
  *
  * When the FSM enters a state with a timeout, if no event is received within the duration, the timeout action is
  * automatically executed.
  *
  * @tparam S
  *   The state type this timeout applies to
  * @tparam S2
  *   The potential target state type
  */
final case class StateTimeout[-S, +S2](
    duration: Duration,
    action: ZIO[Any, MechanoidError, TransitionResult[S2]],
)

/** Result of a transition including any generated commands.
  *
  * This is returned by `FSMRuntime.send` and includes:
  *   - The transition result (Stay, Goto, Stop)
  *   - Pre-transition commands (generated before state change)
  *   - Post-transition commands (generated after state change)
  *
  * @tparam S
  *   The state type
  * @tparam Cmd
  *   The command type
  */
final case class TransitionOutcome[+S, +Cmd](
    result: TransitionResult[S],
    preCommands: List[Cmd],
    postCommands: List[Cmd],
):
  /** All commands in execution order (pre first, then post). */
  def allCommands: List[Cmd] = preCommands ++ postCommands

  /** Whether any commands were generated. */
  def hasCommands: Boolean = preCommands.nonEmpty || postCommands.nonEmpty
end TransitionOutcome

object TransitionOutcome:
  /** Create an outcome with no commands (for FSMs without command support). */
  def noCommands[S](result: TransitionResult[S]): TransitionOutcome[S, Nothing] =
    TransitionOutcome(result, Nil, Nil)
