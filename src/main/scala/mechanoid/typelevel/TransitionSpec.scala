package mechanoid.typelevel

import mechanoid.core.*
import scala.annotation.nowarn
import scala.compiletime.*
import scala.compiletime.ops.boolean.*

/** Type-level specification of allowed transitions.
  *
  * This enables defining a complete transition table at the type level, which can be used to derive ValidTransition
  * instances automatically.
  */
sealed trait TransitionSpec

/** Specifies an allowed transition from state S to state T via event E. */
final class Allow[S <: MState, E <: MEvent, T <: MState] extends TransitionSpec

/** Empty transition specification. */
final class TNil extends TransitionSpec

/** Cons cell for building transition specification lists. */
final class ::[H <: TransitionSpec, Tail <: TransitionSpec] extends TransitionSpec

object TransitionSpec:
  /** Check at compile time if a transition is in the specification. */
  type Contains[Spec <: TransitionSpec, S <: MState, E <: MEvent] <: Boolean = Spec match
    case Allow[S, E, ?] => true
    case Allow[?, ?, ?] => false
    case h :: t         => Contains[h, S, E] || Contains[t, S, E]
    case TNil           => false

  /** Get the target state for a transition in the specification. */
  type TargetOf[Spec <: TransitionSpec, S <: MState, E <: MEvent] <: MState = Spec match
    case Allow[S, E, t] => t
    case Allow[?, ?, ?] => Nothing
    case h :: t         => TargetOf[h, S, E] | TargetOf[t, S, E]
    case TNil           => Nothing

  /** Derive a ValidTransition from a TransitionSpec at compile time.
    *
    * This will fail to compile if the transition is not in the spec.
    */
  @nowarn("msg=New anonymous class definition will be duplicated")
  inline def derive[Spec <: TransitionSpec, S <: MState, E <: MEvent](using
      inline ev: Contains[Spec, S, E] =:= true
  ): ValidTransition[S, E] =
    new ValidTransition[S, E]:
      type Target = TargetOf[Spec, S, E]
end TransitionSpec

/** Builder for defining transition specifications using a fluent API. */
class TransitionSpecBuilder[Spec <: TransitionSpec]:
  /** Add an allowed transition to the specification. */
  def allow[S <: MState, E <: MEvent, T <: MState]: TransitionSpecBuilder[Allow[S, E, T] :: Spec] =
    new TransitionSpecBuilder[Allow[S, E, T] :: Spec]

  /** Get the built specification type. */
  type Build = Spec

object TransitionSpecBuilder:
  /** Start building a new transition specification. */
  def start: TransitionSpecBuilder[TNil] = new TransitionSpecBuilder[TNil]
