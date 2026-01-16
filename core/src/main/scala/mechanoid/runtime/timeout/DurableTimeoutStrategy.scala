package mechanoid.runtime.timeout

import zio.*
import mechanoid.persistence.timeout.TimeoutStore

/** Durable timeout strategy that persists deadlines to a [[TimeoutStore]].
  *
  * This implementation schedules timeouts by writing deadline records to persistent storage. A separate
  * [[mechanoid.persistence.timeout.TimeoutSweeper]] process polls for expired deadlines and fires the timeout events.
  *
  * ==Characteristics==
  *
  *   - '''Durable''': Timeouts survive node failures
  *   - '''Distributed''': Works correctly in multi-node deployments
  *   - '''Requires sweeper''': A background process must poll and fire expired timeouts
  *
  * ==When to Use==
  *
  *   - Production deployments
  *   - Multi-node/distributed systems
  *   - When timeout guarantees are important for business logic
  *
  * ==How It Works==
  *
  *   1. `schedule()` writes a deadline record to the TimeoutStore
  *   2. The TimeoutSweeper periodically polls for expired deadlines
  *   3. When a deadline expires, the sweeper sends the timeout event to the FSM
  *   4. `cancel()` removes the deadline record
  *
  * ==Important==
  *
  * The `onTimeout` callback passed to `schedule()` is '''not used''' by this strategy. Instead, the TimeoutSweeper is
  * responsible for sending timeout events. The callback parameter exists for interface compatibility with
  * [[FiberTimeoutStrategy]].
  *
  * @tparam Id
  *   FSM instance identifier type
  */
final class DurableTimeoutStrategy[Id] private (
    timeoutStore: TimeoutStore[Id]
) extends TimeoutStrategy[Id]:

  override def schedule(
      instanceId: Id,
      stateHash: Int,
      sequenceNr: Long,
      duration: Duration,
      onTimeout: UIO[Unit],
  ): UIO[Unit] =
    // Note: onTimeout is ignored - the TimeoutSweeper handles firing
    Clock.instant.flatMap { now =>
      val deadline = now.plusMillis(duration.toMillis)
      timeoutStore.schedule(instanceId, stateHash, sequenceNr, deadline).ignore
    }

  override def cancel(instanceId: Id): UIO[Unit] =
    timeoutStore.cancel(instanceId).ignore

end DurableTimeoutStrategy

object DurableTimeoutStrategy:

  /** Create a durable timeout strategy backed by a TimeoutStore.
    *
    * @param timeoutStore
    *   The persistent storage for timeout deadlines
    * @return
    *   A new strategy instance
    */
  def make[Id](timeoutStore: TimeoutStore[Id]): DurableTimeoutStrategy[Id] =
    new DurableTimeoutStrategy(timeoutStore)

  /** Layer providing a durable timeout strategy.
    *
    * Requires `TimeoutStore[Id]` in the environment.
    *
    * @return
    *   A layer that provides `TimeoutStrategy[Id]`
    */
  def layer[Id: Tag]: URLayer[TimeoutStore[Id], TimeoutStrategy[Id]] =
    ZLayer.fromFunction(make[Id])

end DurableTimeoutStrategy
