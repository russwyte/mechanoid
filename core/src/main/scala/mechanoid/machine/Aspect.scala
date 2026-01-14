package mechanoid.machine

import scala.concurrent.duration.Duration

/** Aspect that can modify transition specs or state configurations.
  *
  * Inspired by zio-test's TestAspect, aspects allow modifying specs with the `@@` operator.
  */
sealed trait Aspect

object Aspect:
  /** Mark a transition as an intentional override.
    *
    * When duplicate transitions are detected, those marked with `@@ overriding` will not trigger a compile error.
    * Instead, they will override any previous definition for the same (state, event) pair. The last override wins.
    */
  case object overriding extends Aspect

  /** Configure a timeout for a target state with a user-defined timeout event.
    *
    * Use with a state to create a `TimedTarget` that can be used in transitions. When the FSM enters the target state
    * via such a transition, a timeout timer starts. If no other transition occurs before the duration, the specified
    * event is automatically fired.
    *
    * Usage:
    * {{{
    * enum OrderEvent derives Finite:
    *   case Pay, PaymentTimeout
    *
    * val timedProcessing = Processing @@ timeout(30.seconds, PaymentTimeout)
    *
    * build[State, OrderEvent](
    *   Pending via Pay to timedProcessing,        // Timer starts when entering Processing
    *   Processing via Complete to Done,
    *   Processing via PaymentTimeout to TimedOut, // Handle the timeout with user event
    * )
    * }}}
    *
    * @param duration
    *   How long to wait before firing the timeout event
    * @param event
    *   The event to fire when the timeout expires
    */
  case class timeout[E](duration: Duration, event: E) extends Aspect
end Aspect

/** Wrapper for a state with timeout configuration.
  *
  * Created via `State @@ timeout(duration, event)`. Use as target in transitions: `Source via Event to timedTarget`
  *
  * When a transition targets a TimedTarget, the timeout timer starts when entering that state. After the duration
  * elapses, the specified event is fired.
  *
  * @tparam S
  *   The state type
  * @tparam E
  *   The timeout event type
  * @param state
  *   The target state
  * @param duration
  *   How long before timeout fires
  * @param timeoutEvent
  *   The event to fire when the timeout expires
  */
final case class TimedTarget[S, E](state: S, duration: Duration, timeoutEvent: E)

/** Timeout configuration for a state (internal, used by Machine.fromSpecs).
  *
  * @tparam S
  *   The source state type(s) this timeout applies to
  * @tparam E
  *   The timeout event type
  * @param stateHashes
  *   Hashes of states that have this timeout
  * @param stateNames
  *   Names of states for error messages
  * @param duration
  *   How long before timeout fires
  * @param eventHash
  *   Hash of the event to fire on timeout
  * @param eventInstance
  *   The actual event instance to fire (stored for runtime use)
  */
final case class TimeoutSpec[S, E](
    stateHashes: Set[Int],
    stateNames: List[String],
    duration: Duration,
    eventHash: Int,
    eventInstance: E,
)

// Extension methods for @@ are defined in Macros.scala to keep all extensions together
