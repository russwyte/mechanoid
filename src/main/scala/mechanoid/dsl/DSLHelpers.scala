package mechanoid.dsl

import zio.*
import mechanoid.core.*

/** Convenience functions for use inside executeWith blocks.
  *
  * These helpers reduce verbosity when writing transition logic:
  *
  * {{{
  * import mechanoid.*
  *
  * .when(Processing).on(Complete).executeWith { (state, event) =>
  *   state match
  *     case Processing(orderId, items) =>
  *       processItems(items) *> goto(Completed)
  *     case _ =>
  *       stay
  * }
  * }}}
  */
object DSLHelpers:
  /** Return a Goto transition result. */
  def goto[S <: MState](state: S): UIO[TransitionResult[S]] =
    ZIO.succeed(TransitionResult.Goto(state))

  /** Return a Stay transition result. */
  def stay[S <: MState]: UIO[TransitionResult[S]] =
    ZIO.succeed(TransitionResult.Stay)

  /** Return a Stop transition result. */
  def stop[S <: MState]: UIO[TransitionResult[S]] =
    ZIO.succeed(TransitionResult.Stop(None))

  /** Return a Stop transition result with reason. */
  def stop[S <: MState](reason: String): UIO[TransitionResult[S]] =
    ZIO.succeed(TransitionResult.Stop(Some(reason)))
end DSLHelpers
