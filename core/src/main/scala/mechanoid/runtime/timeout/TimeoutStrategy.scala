package mechanoid.runtime.timeout

import zio.*

/** Strategy for managing FSM state timeouts.
  *
  * This trait defines how timeouts are scheduled and cancelled for FSM instances. Different implementations provide
  * different durability and failure semantics:
  *
  *   - [[FiberTimeoutStrategy]]: In-memory, uses ZIO fibers. Fast but doesn't survive node failures.
  *   - [[DurableTimeoutStrategy]]: Persists to a [[mechanoid.persistence.timeout.TimeoutStore]]. Survives node failures
  *     when combined with a [[mechanoid.persistence.timeout.TimeoutSweeper]].
  *
  * ==Usage==
  *
  * The timeout strategy is an environment dependency for FSMRuntime. Use the companion object layer helpers:
  *
  * {{{
  * // In-memory timeouts (development, testing, single-node)
  * val program = ZIO.scoped {
  *   for
  *     fsm <- FSMRuntime(id, machine, initial)
  *     _   <- fsm.send(event)
  *   yield ()
  * }.provide(
  *   eventStoreLayer,
  *   TimeoutStrategy.fiber[OrderId],
  *   LockingStrategy.optimistic[OrderId]
  * )
  *
  * // Durable timeouts (production, multi-node)
  * val program = ZIO.scoped {
  *   for
  *     fsm <- FSMRuntime(id, machine, initial)
  *     _   <- fsm.send(event)
  *   yield ()
  * }.provide(
  *   eventStoreLayer,
  *   timeoutStoreLayer,
  *   TimeoutStrategy.durable[OrderId],
  *   LockingStrategy.optimistic[OrderId]
  * )
  * }}}
  *
  * ==Choosing a Strategy==
  *
  * | Strategy  | When to Use                                                        |
  * |:----------|:-------------------------------------------------------------------|
  * | `fiber`   | Development, testing, single-node deployments, low-stakes timeouts |
  * | `durable` | Production multi-node deployments, business-critical timeouts      |
  *
  * @tparam Id
  *   FSM instance identifier type
  */
trait TimeoutStrategy[Id]:

  /** Schedule a timeout for the given FSM instance.
    *
    * When the timeout fires, it should invoke the provided callback. The implementation determines how the timeout is
    * tracked (in-memory vs persisted).
    *
    * @param instanceId
    *   The FSM instance identifier
    * @param stateName
    *   String representation of the state (for debugging/logging)
    * @param duration
    *   How long until the timeout fires
    * @param onTimeout
    *   Callback to invoke when the timeout expires
    * @return
    *   An effect that completes when the timeout is scheduled
    */
  def schedule(
      instanceId: Id,
      stateName: String,
      duration: Duration,
      onTimeout: UIO[Unit],
  ): UIO[Unit]

  /** Cancel any pending timeout for the given FSM instance.
    *
    * @param instanceId
    *   The FSM instance identifier
    * @return
    *   An effect that completes when cancellation is processed
    */
  def cancel(instanceId: Id): UIO[Unit]

end TimeoutStrategy

object TimeoutStrategy:

  /** Access the timeout strategy from the environment. */
  def schedule[Id: Tag](
      instanceId: Id,
      stateName: String,
      duration: Duration,
      onTimeout: UIO[Unit],
  ): ZIO[TimeoutStrategy[Id], Nothing, Unit] =
    ZIO.serviceWithZIO[TimeoutStrategy[Id]](_.schedule(instanceId, stateName, duration, onTimeout))

  /** Cancel a pending timeout. */
  def cancel[Id: Tag](instanceId: Id): ZIO[TimeoutStrategy[Id], Nothing, Unit] =
    ZIO.serviceWithZIO[TimeoutStrategy[Id]](_.cancel(instanceId))

  // ============================================
  // Layer constructors
  // ============================================

  /** Layer providing a fiber-based (in-memory) timeout strategy.
    *
    * Fast but doesn't survive node failures. Good for development, testing, and single-node deployments.
    */
  def fiber[Id: Tag]: ULayer[TimeoutStrategy[Id]] =
    FiberTimeoutStrategy.layer[Id]

  /** Layer providing a durable timeout strategy backed by a TimeoutStore.
    *
    * Survives node failures when combined with a TimeoutSweeper. Requires `TimeoutStore[Id]` in the environment.
    */
  def durable[Id: Tag]: URLayer[mechanoid.persistence.timeout.TimeoutStore[Id], TimeoutStrategy[Id]] =
    DurableTimeoutStrategy.layer[Id]

end TimeoutStrategy
