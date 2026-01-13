package mechanoid.machine

import mechanoid.core.*
import scala.concurrent.duration.Duration

/** Handler for what happens when a transition fires.
  *
  * The type parameter represents the target state type for Goto transitions.
  */
sealed trait Handler[+Target <: MState]

object Handler:
  /** Transition to a specific target state. */
  case class Goto[+S <: MState](target: S) extends Handler[S]

  /** Stay in the current state. */
  case object Stay extends Handler[Nothing]

  /** Stop the FSM. */
  case class Stop(reason: Option[String]) extends Handler[Nothing]

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
final case class TransitionSpec[-S <: MState, -E <: MEvent, +Cmd](
    stateHashes: Set[Int],          // Expanded from sealed hierarchies
    eventHashes: Set[Int],          // Expanded from sealed hierarchies
    stateNames: List[String],       // For error messages
    eventNames: List[String],       // For error messages
    targetDesc: String,             // "-> Paid", "stay", "stop" - for error messages
    isOverride: Boolean,            // If true, won't trigger duplicate error
    handler: Handler[MState],       // Handler can go to any state
    targetTimeout: Option[Duration], // If set, configure timeout on target state when entering
)

object TransitionSpec:
  /** Create a goto transition spec. */
  def goto[S <: MState](
      stateHashes: Set[Int],
      eventHashes: Set[Int],
      stateNames: List[String],
      eventNames: List[String],
      target: S,
      timeout: Option[Duration] = None,
  ): TransitionSpec[MState, MEvent, Nothing] =
    TransitionSpec(
      stateHashes = stateHashes,
      eventHashes = eventHashes,
      stateNames = stateNames,
      eventNames = eventNames,
      targetDesc = s"-> ${target.toString}",
      isOverride = false,
      handler = Handler.Goto(target),
      targetTimeout = timeout,
    )

  /** Create a goto transition spec to a timed target. */
  def gotoTimed[S <: MState](
      stateHashes: Set[Int],
      eventHashes: Set[Int],
      stateNames: List[String],
      eventNames: List[String],
      target: TimedTarget[S],
  ): TransitionSpec[MState, MEvent, Nothing] =
    TransitionSpec(
      stateHashes = stateHashes,
      eventHashes = eventHashes,
      stateNames = stateNames,
      eventNames = eventNames,
      targetDesc = s"-> ${target.state.toString} @@ timeout(${target.duration})",
      isOverride = false,
      handler = Handler.Goto(target.state),
      targetTimeout = Some(target.duration),
    )

  /** Create a stay transition spec. */
  def stay(
      stateHashes: Set[Int],
      eventHashes: Set[Int],
      stateNames: List[String],
      eventNames: List[String],
  ): TransitionSpec[MState, MEvent, Nothing] =
    TransitionSpec(
      stateHashes = stateHashes,
      eventHashes = eventHashes,
      stateNames = stateNames,
      eventNames = eventNames,
      targetDesc = "stay",
      isOverride = false,
      handler = Handler.Stay,
      targetTimeout = None,
    )

  /** Create a stop transition spec. */
  def stop(
      stateHashes: Set[Int],
      eventHashes: Set[Int],
      stateNames: List[String],
      eventNames: List[String],
      reason: Option[String] = None,
  ): TransitionSpec[MState, MEvent, Nothing] =
    TransitionSpec(
      stateHashes = stateHashes,
      eventHashes = eventHashes,
      stateNames = stateNames,
      eventNames = eventNames,
      targetDesc = reason.fold("stop")(r => s"stop($r)"),
      isOverride = false,
      handler = Handler.Stop(reason),
      targetTimeout = None,
    )
end TransitionSpec
