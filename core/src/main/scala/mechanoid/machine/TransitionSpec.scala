package mechanoid.machine

import mechanoid.core.*
import zio.{Duration, ZIO}

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
  */
final case class TransitionSpec[+S, +E](
    stateHashes: Set[Int],           // Expanded from sealed hierarchies
    eventHashes: Set[Int],           // Expanded from sealed hierarchies
    stateNames: List[String],        // For error messages
    eventNames: List[String],        // For error messages
    targetDesc: String,              // "-> Paid", "stay", "stop" - for error messages
    isOverride: Boolean,             // If true, won't trigger duplicate error
    handler: Handler[Any],           // Handler can go to any state
    targetTimeout: Option[Duration], // If set, configure timeout on target state when entering
    targetTimeoutConfig: Option[TimeoutEventConfig[?]] = None,       // Type-safe timeout event holder
    entryEffect: Option[(Any, Any) => ZIO[Any, Any, Unit]] = None,   // (event, targetState) => effect
    producingEffect: Option[(Any, Any) => ZIO[Any, Any, Any]] = None, // (event, targetState) => ZIO[.., E]
):
  /** Synchronous side effect on entry. Receives (event, targetState).
    *
    * Use this for side effects that should run when the transition occurs. The function receives the triggering event
    * and the new (target) state.
    *
    * @example
    *   {{{
    * val spec = (A via E1 to B).onEntry { (event, state) =>
    *   ZIO.logInfo(s"Transitioned to $state")
    * }
    *   }}}
    *
    * @param f
    *   Function that runs side effect from (event, targetState)
    * @return
    *   A new TransitionSpec with the entry effect configured
    */
  infix def onEntry(f: (E, S) => ZIO[Any, Any, Unit]): TransitionSpec[S, E] =
    copy(entryEffect = Some(f.asInstanceOf[(Any, Any) => ZIO[Any, Any, Unit]]))

  /** Async effect that produces an event. Receives (event, targetState).
    *
    * Use this for async operations that should produce an event when complete. The function receives the triggering
    * event and the new (target) state, and returns a ZIO that produces an event. The produced event is automatically
    * sent to the FSM when the effect completes.
    *
    * @example
    *   {{{
    * val spec = (Created via StartPayment to Processing).producing { (event, state) =>
    *   paymentService.charge(event.amount).map {
    *     case Success(txnId) => PaymentSucceeded(txnId)
    *     case Failure(err)   => PaymentFailed(err)
    *   }
    * }
    *   }}}
    *
    * @param f
    *   Function that produces an event from (event, targetState)
    * @return
    *   A new TransitionSpec with the producing effect configured
    */
  infix def producing[E2](f: (E, S) => ZIO[Any, Any, E2]): TransitionSpec[S, E] =
    copy(producingEffect = Some(f.asInstanceOf[(Any, Any) => ZIO[Any, Any, Any]]))

  /** Configure timeout when entering target state. */
  def withTimeout(duration: Duration): TransitionSpec[S, E] =
    copy(targetTimeout = Some(duration))

  /** Apply an aspect to this transition spec.
    *
    * @example
    *   {{{
    * val override_ = (A via E1 to B) @@ Aspect.overriding
    * val timed = (A via E1 to B) @@ Aspect.timeout(30.seconds, TimeoutEvent)
    *   }}}
    */
  infix def @@(aspect: Aspect): TransitionSpec[S, E] = aspect match
    case Aspect.overriding               => copy(isOverride = true)
    case Aspect.timeout(duration, event) =>
      // Compute hash at runtime using the same logic as anyOf matchers
      val className = event.getClass.getName.stripSuffix("$").replace('$', '.')
      val hash      = event match
        case _: scala.reflect.Enum =>
          val caseName = event.toString
          s"$className.$caseName".hashCode
        case _ =>
          className.hashCode
      copy(
        targetTimeout = Some(duration),
        targetTimeoutConfig = Some(TimeoutEventConfig(event, hash)),
      )
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
  ): TransitionSpec[Any, Any] =
    TransitionSpec(
      stateHashes = stateHashes,
      eventHashes = eventHashes,
      stateNames = stateNames,
      eventNames = eventNames,
      targetDesc = s"-> ${target.toString}",
      isOverride = false,
      handler = Handler.Goto(target),
      targetTimeout = timeout,
      entryEffect = None,
      producingEffect = None,
    )

  /** Create a goto transition spec to a timed target with user-defined timeout event. */
  def gotoTimed[S, TE](
      stateHashes: Set[Int],
      eventHashes: Set[Int],
      stateNames: List[String],
      eventNames: List[String],
      target: TimedTarget[S, TE],
  )(using se: Finite[TE]): TransitionSpec[Any, Any] =
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
      entryEffect = None,
      producingEffect = None,
    )

  /** Create a stay transition spec. */
  def stay(
      stateHashes: Set[Int],
      eventHashes: Set[Int],
      stateNames: List[String],
      eventNames: List[String],
  ): TransitionSpec[Any, Any] =
    TransitionSpec(
      stateHashes = stateHashes,
      eventHashes = eventHashes,
      stateNames = stateNames,
      eventNames = eventNames,
      targetDesc = "stay",
      isOverride = false,
      handler = Handler.Stay,
      targetTimeout = None,
      entryEffect = None,
      producingEffect = None,
    )

  /** Create a stop transition spec. */
  def stop(
      stateHashes: Set[Int],
      eventHashes: Set[Int],
      stateNames: List[String],
      eventNames: List[String],
      reason: Option[String] = None,
  ): TransitionSpec[Any, Any] =
    TransitionSpec(
      stateHashes = stateHashes,
      eventHashes = eventHashes,
      stateNames = stateNames,
      eventNames = eventNames,
      targetDesc = reason.fold("stop")(r => s"stop($r)"),
      isOverride = false,
      handler = Handler.Stop(reason),
      targetTimeout = None,
      entryEffect = None,
      producingEffect = None,
    )
end TransitionSpec
