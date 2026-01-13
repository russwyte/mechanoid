package mechanoid.machine

import mechanoid.core.*
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

  /** Configure a timeout for a target state.
    *
    * Use with a state to create a `TimedTarget` that can be used in transitions. When the FSM enters the target state
    * via such a transition, a timeout timer starts. If no other transition occurs before the duration, a Timeout event
    * is automatically fired.
    *
    * Usage:
    * {{{
    * val timedWaiting = Waiting @@ timeout(30.seconds)
    *
    * build[State, Event](
    *   Idle via Start to timedWaiting,       // Timer starts when entering Waiting
    *   Waiting via Response to Done,
    *   Waiting via Timeout to TimedOut,      // Handle the timeout event
    * )
    * }}}
    *
    * @param duration
    *   How long to wait before firing the Timeout event
    */
  case class timeout(duration: Duration) extends Aspect
end Aspect

/** Wrapper for a state with timeout configuration.
  *
  * Created via `State @@ timeout(duration)`. Use as target in transitions: `Source via Event to timedTarget`
  *
  * When a transition targets a TimedTarget, the timeout timer starts when entering that state.
  *
  * @tparam S
  *   The state type
  * @param state
  *   The target state
  * @param duration
  *   How long before timeout fires
  */
final case class TimedTarget[S <: MState](state: S, duration: Duration)

/** Timeout configuration for a state (internal, used by Machine.fromSpecs).
  *
  * @tparam S
  *   The source state type(s) this timeout applies to
  * @param stateHashes
  *   Hashes of states that have this timeout
  * @param stateNames
  *   Names of states for error messages
  * @param duration
  *   How long before timeout fires (then Timeout event is sent)
  */
final case class TimeoutSpec[S <: MState](
    stateHashes: Set[Int],
    stateNames: List[String],
    duration: Duration,
)

// Extension methods for @@ are defined in Macros.scala to keep all extensions together
