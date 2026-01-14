package mechanoid.machine

import mechanoid.core.*
import scala.concurrent.duration.Duration

/** Handler for what happens when a transition fires.
  *
  * The type parameter represents the target state type for Goto transitions.
  */
sealed trait Handler[+Target]

object Handler:
  /** Transition to a specific target state. */
  case class Goto[+S](target: S) extends Handler[S]

  /** Stay in the current state. */
  case object Stay extends Handler[Nothing]

  /** Stop the FSM. */
  case class Stop(reason: Option[String]) extends Handler[Nothing]

/** Type-safe holder for timeout event configuration.
  *
  * This preserves the event type while allowing storage in contravariant TransitionSpec. The existential type `?`
  * allows safe storage while Machine can extract and properly type the events when building its lookup table.
  */
final case class TimeoutEventConfig[E](event: E, hash: Int)

/** A single transition specification for the suite DSL.
  *
  * Captures state/event hash information for compile-time duplicate detection, along with the handler that determines
  * what happens when the transition fires.
  *
  * @tparam S
  *   The source state type (for matching)
  * @tparam E
  *   The event type (for matching)
  * @tparam Cmd
  *   The command type (for transactional outbox pattern)
  */
final case class TransitionSpec[+S, +E, +Cmd](
    stateHashes: Set[Int],           // Expanded from sealed hierarchies
    eventHashes: Set[Int],           // Expanded from sealed hierarchies
    stateNames: List[String],        // For error messages
    eventNames: List[String],        // For error messages
    targetDesc: String,              // "-> Paid", "stay", "stop" - for error messages
    isOverride: Boolean,             // If true, won't trigger duplicate error
    handler: Handler[Any],           // Handler can go to any state
    targetTimeout: Option[Duration], // If set, configure timeout on target state when entering
    targetTimeoutConfig: Option[TimeoutEventConfig[?]] = None, // Type-safe timeout event holder
    preCommandFactory: Option[(Any, Any) => List[Any]] = None, // (event, sourceState) => commands
    postCommandFactory: Option[(Any, Any) => List[Any]] = None, // (event, targetState) => commands
):
  /** Commands to emit BEFORE state change. Receives (event, sourceState).
    *
    * Use this for commands that must be generated before the state transition occurs. The function receives the
    * triggering event and the current (source) state.
    *
    * @example
    *   {{{
    * val spec = A via E1 to B emittingBefore { (event, state) =>
    *   List(LogTransition(state, event))
    * }
    *   }}}
    *
    * @param f
    *   Function that generates commands from (event, sourceState)
    * @return
    *   A new TransitionSpec with the pre-command factory configured
    */
  infix def emittingBefore[C](f: (E, S) => List[C]): TransitionSpec[S, E, C] =
    copy(preCommandFactory = Some(f.asInstanceOf[(Any, Any) => List[Any]]))
      .asInstanceOf[TransitionSpec[S, E, C]]

  /** Commands to emit AFTER state change. Receives (event, targetState).
    *
    * Use this for commands that should be generated after the state transition occurs. The function receives the
    * triggering event and the new (target) state. This is the most common pattern for generating notifications or side
    * effects based on state changes.
    *
    * @example
    *   {{{
    * val spec = Created via Pay to Paid emitting { (event, state) =>
    *   List(SendNotification(s"Order is now $state"))
    * }
    *   }}}
    *
    * @param f
    *   Function that generates commands from (event, targetState)
    * @return
    *   A new TransitionSpec with the post-command factory configured
    */
  infix def emitting[C](f: (E, S) => List[C]): TransitionSpec[S, E, C] =
    copy(postCommandFactory = Some(f.asInstanceOf[(Any, Any) => List[Any]]))
      .asInstanceOf[TransitionSpec[S, E, C]]

  /** Configure timeout when entering target state. */
  def withTimeout(duration: Duration): TransitionSpec[S, E, Cmd] =
    copy(targetTimeout = Some(duration))
end TransitionSpec

object TransitionSpec:
  /** Create a goto transition spec. */
  def goto[S](
      stateHashes: Set[Int],
      eventHashes: Set[Int],
      stateNames: List[String],
      eventNames: List[String],
      target: S,
      timeout: Option[Duration] = None,
  ): TransitionSpec[Any, Any, Nothing] =
    TransitionSpec(
      stateHashes = stateHashes,
      eventHashes = eventHashes,
      stateNames = stateNames,
      eventNames = eventNames,
      targetDesc = s"-> ${target.toString}",
      isOverride = false,
      handler = Handler.Goto(target),
      targetTimeout = timeout,
      preCommandFactory = None,
      postCommandFactory = None,
    )

  /** Create a goto transition spec to a timed target with user-defined timeout event. */
  def gotoTimed[S, TE](
      stateHashes: Set[Int],
      eventHashes: Set[Int],
      stateNames: List[String],
      eventNames: List[String],
      target: TimedTarget[S, TE],
  )(using se: Finite[TE]): TransitionSpec[Any, Any, Nothing] =
    TransitionSpec(
      stateHashes = stateHashes,
      eventHashes = eventHashes,
      stateNames = stateNames,
      eventNames = eventNames,
      targetDesc = s"-> ${target.state.toString} @@ timeout(${target.duration}, ${target.timeoutEvent})",
      isOverride = false,
      handler = Handler.Goto(target.state),
      targetTimeout = Some(target.duration),
      targetTimeoutConfig = Some(TimeoutEventConfig(target.timeoutEvent, se.caseHash(target.timeoutEvent))),
      preCommandFactory = None,
      postCommandFactory = None,
    )

  /** Create a stay transition spec. */
  def stay(
      stateHashes: Set[Int],
      eventHashes: Set[Int],
      stateNames: List[String],
      eventNames: List[String],
  ): TransitionSpec[Any, Any, Nothing] =
    TransitionSpec(
      stateHashes = stateHashes,
      eventHashes = eventHashes,
      stateNames = stateNames,
      eventNames = eventNames,
      targetDesc = "stay",
      isOverride = false,
      handler = Handler.Stay,
      targetTimeout = None,
      preCommandFactory = None,
      postCommandFactory = None,
    )

  /** Create a stop transition spec. */
  def stop(
      stateHashes: Set[Int],
      eventHashes: Set[Int],
      stateNames: List[String],
      eventNames: List[String],
      reason: Option[String] = None,
  ): TransitionSpec[Any, Any, Nothing] =
    TransitionSpec(
      stateHashes = stateHashes,
      eventHashes = eventHashes,
      stateNames = stateNames,
      eventNames = eventNames,
      targetDesc = reason.fold("stop")(r => s"stop($r)"),
      isOverride = false,
      handler = Handler.Stop(reason),
      targetTimeout = None,
      preCommandFactory = None,
      postCommandFactory = None,
    )
end TransitionSpec
