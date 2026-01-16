package mechanoid.runtime

import zio.*
import mechanoid.core.MechanoidError

/** An aspect that transforms an FSMRuntime-creating effect, potentially adding environment requirements.
  *
  * Aspects are composable via `@@` and can add capabilities like:
  *   - Distributed locking (adds `FSMInstanceLock[Id]` requirement)
  *   - Durable timeouts (adds `TimeoutStore[Id]` requirement)
  *   - Metrics, tracing, rate limiting (future)
  *
  * ==Dual-Mode Configuration==
  *
  * Each aspect can be parameterized in two ways:
  *   1. '''Explicit config''': `withLocking(LockConfig.fast)` - config passed directly, no extra env requirement
  *   2. '''Environment config''': `withLocking` - config pulled from ZIO environment, adds config type to requirements
  *
  * ==Usage==
  *
  * {{{
  * // Base runtime with EventStore from environment
  * val base = FSMRuntime(orderId, orderMachine, Pending)
  *
  * // Add locking with explicit config
  * val locked = base @@ withLocking(LockConfig.fast)
  *
  * // Add locking with config from environment
  * val lockedEnv = base @@ withLocking
  *
  * // Compose multiple aspects
  * val full = base @@ withLocking(config) @@ withDurableTimeouts
  * }}}
  *
  * @tparam Id
  *   FSM instance identifier type
  * @tparam S
  *   State type
  * @tparam E
  *   Event type
  * @tparam Cmd
  *   Command type
  * @tparam ROut
  *   Additional environment requirements this aspect adds
  */
trait FSMRuntimeAspect[Id, S, E, Cmd, ROut]:

  /** Apply this aspect to a runtime-creating effect.
    *
    * @param makeRuntime
    *   The effect that creates the base runtime
    * @return
    *   An effect that creates the transformed runtime with additional requirements
    */
  def apply[R](
      makeRuntime: ZIO[Scope & R, MechanoidError, FSMRuntime[Id, S, E, Cmd]]
  ): ZIO[Scope & R & ROut, MechanoidError, FSMRuntime[Id, S, E, Cmd]]

  /** Compose this aspect with another.
    *
    * The resulting aspect first applies `this`, then applies `that`.
    *
    * @param that
    *   The aspect to apply after this one
    * @return
    *   A combined aspect
    */
  def >>>[ROut2](
      that: FSMRuntimeAspect[Id, S, E, Cmd, ROut2]
  ): FSMRuntimeAspect[Id, S, E, Cmd, ROut & ROut2] =
    FSMRuntimeAspect.andThen(this, that)

  /** Alias for `>>>` composition. */
  def andThen[ROut2](
      that: FSMRuntimeAspect[Id, S, E, Cmd, ROut2]
  ): FSMRuntimeAspect[Id, S, E, Cmd, ROut & ROut2] =
    this >>> that

end FSMRuntimeAspect

object FSMRuntimeAspect:

  /** Create an aspect from a transformation function that doesn't change environment requirements.
    *
    * @param f
    *   Function to transform the runtime
    * @return
    *   An aspect that applies the transformation
    */
  def transform[Id, S, E, Cmd](
      f: FSMRuntime[Id, S, E, Cmd] => FSMRuntime[Id, S, E, Cmd]
  ): FSMRuntimeAspect[Id, S, E, Cmd, Any] =
    new FSMRuntimeAspect[Id, S, E, Cmd, Any]:
      def apply[R](
          makeRuntime: ZIO[Scope & R, MechanoidError, FSMRuntime[Id, S, E, Cmd]]
      ): ZIO[Scope & R & Any, MechanoidError, FSMRuntime[Id, S, E, Cmd]] =
        makeRuntime.map(f)

  /** Create an aspect from an effectful transformation that may add environment requirements.
    *
    * @param f
    *   Effectful function to transform the runtime
    * @return
    *   An aspect that applies the transformation and adds `RNew` to requirements
    */
  def transformZIO[Id, S, E, Cmd, RNew](
      f: FSMRuntime[Id, S, E, Cmd] => ZIO[RNew, MechanoidError, FSMRuntime[Id, S, E, Cmd]]
  ): FSMRuntimeAspect[Id, S, E, Cmd, RNew] =
    new FSMRuntimeAspect[Id, S, E, Cmd, RNew]:
      def apply[R](
          makeRuntime: ZIO[Scope & R, MechanoidError, FSMRuntime[Id, S, E, Cmd]]
      ): ZIO[Scope & R & RNew, MechanoidError, FSMRuntime[Id, S, E, Cmd]] =
        makeRuntime.flatMap(f)

  /** Compose two aspects sequentially.
    *
    * @param first
    *   The aspect to apply first
    * @param second
    *   The aspect to apply second
    * @return
    *   A combined aspect
    */
  def andThen[Id, S, E, Cmd, R1, R2](
      first: FSMRuntimeAspect[Id, S, E, Cmd, R1],
      second: FSMRuntimeAspect[Id, S, E, Cmd, R2],
  ): FSMRuntimeAspect[Id, S, E, Cmd, R1 & R2] =
    new FSMRuntimeAspect[Id, S, E, Cmd, R1 & R2]:
      def apply[R](
          makeRuntime: ZIO[Scope & R, MechanoidError, FSMRuntime[Id, S, E, Cmd]]
      ): ZIO[Scope & R & R1 & R2, MechanoidError, FSMRuntime[Id, S, E, Cmd]] =
        second(first(makeRuntime))

  /** Identity aspect that passes through unchanged. */
  def identity[Id, S, E, Cmd]: FSMRuntimeAspect[Id, S, E, Cmd, Any] =
    transform(rt => rt)

end FSMRuntimeAspect

/** Extension methods for composing FSMRuntime effects with aspects. */
extension [Id, S, E, Cmd, R](makeRuntime: ZIO[Scope & R, MechanoidError, FSMRuntime[Id, S, E, Cmd]])

  /** Apply an aspect to this runtime-creating effect.
    *
    * @example
    *   {{{
    * FSMRuntime(id, machine, initial) @@ withLocking(config)
    * FSMRuntime(id, machine, initial) @@ withLocking(config) @@ withDurableTimeouts
    *   }}}
    */
  infix def @@[ROut](
      aspect: FSMRuntimeAspect[Id, S, E, Cmd, ROut]
  ): ZIO[Scope & R & ROut, MechanoidError, FSMRuntime[Id, S, E, Cmd]] =
    aspect(makeRuntime)
end extension
