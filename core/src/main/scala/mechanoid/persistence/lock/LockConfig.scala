package mechanoid.persistence.lock

import zio.Duration

/** Configuration for FSM instance locking.
  *
  * ==Resilience to Node Failures==
  *
  * The locking mechanism uses lease-based locks that automatically expire. This handles several failure scenarios:
  *
  *   1. '''Node crash''': Lock expires after `lockDuration`, other nodes can proceed
  *   2. '''Network partition''': Same as crash - lock expires
  *   3. '''Long GC pause''': If pause exceeds `lockDuration`, lock expires
  *
  * ==Zombie Node Protection==
  *
  * A "zombie" scenario occurs when:
  *   1. Node A acquires lock
  *   2. Node A pauses (GC, network issue)
  *   3. Lock expires
  *   4. Node B acquires lock, processes events
  *   5. Node A wakes up, thinks it still has the lock
  *
  * Protection is provided by two mechanisms:
  *
  *   - '''Lock validation''': Before each operation, check if lock is still valid
  *   - '''EventStore optimistic locking''': Even if zombie writes, sequence conflict detected
  *
  * ==Choosing Lock Duration==
  *
  * The `lockDuration` should be:
  *   - Long enough to complete normal operations (with margin for GC pauses)
  *   - Short enough that failures don't block the system too long
  *
  * Typical values:
  *   - Fast operations: 10-30 seconds
  *   - Complex operations: 1-5 minutes
  *   - Background jobs: Consider using `extend()` for heartbeating
  *
  * @param lockDuration
  *   How long to hold locks (default: 30 seconds)
  * @param acquireTimeout
  *   Maximum time to wait when acquiring (default: 10 seconds)
  * @param retryInterval
  *   How often to retry when lock is busy (default: 100ms)
  * @param validateBeforeOperation
  *   Whether to check lock validity before each operation
  * @param nodeId
  *   Unique identifier for this node (auto-generated if not set)
  */
final case class LockConfig(
    lockDuration: Duration = Duration.fromSeconds(30),
    acquireTimeout: Duration = Duration.fromSeconds(10),
    retryInterval: Duration = Duration.fromMillis(100),
    validateBeforeOperation: Boolean = true,
    nodeId: String = LockConfig.generateNodeId(),
):
  require(
    lockDuration.toMillis > 0,
    "lockDuration must be positive",
  )
  require(
    acquireTimeout.toMillis > 0,
    "acquireTimeout must be positive",
  )
  require(
    retryInterval.toMillis > 0,
    "retryInterval must be positive",
  )
  require(
    lockDuration.toMillis > retryInterval.toMillis,
    "lockDuration must be greater than retryInterval",
  )

  def withLockDuration(duration: Duration): LockConfig =
    copy(lockDuration = duration)

  def withAcquireTimeout(timeout: Duration): LockConfig =
    copy(acquireTimeout = timeout)

  def withRetryInterval(interval: Duration): LockConfig =
    copy(retryInterval = interval)

  def withValidateBeforeOperation(validate: Boolean): LockConfig =
    copy(validateBeforeOperation = validate)

  def withNodeId(id: String): LockConfig =
    copy(nodeId = id)
end LockConfig

object LockConfig:
  /** Generate a unique node ID using hostname and random suffix. */
  def generateNodeId(): String =
    val hostname =
      try java.net.InetAddress.getLocalHost.getHostName
      catch case _: Exception => "unknown"
    val suffix = java.util.UUID.randomUUID().toString.take(8)
    s"$hostname-$suffix"

  /** Default configuration suitable for most use cases. */
  val default: LockConfig = LockConfig()

  /** Configuration for fast operations with short lock duration. */
  val fast: LockConfig = LockConfig(
    lockDuration = Duration.fromSeconds(10),
    acquireTimeout = Duration.fromSeconds(5),
  )

  /** Configuration for long-running operations. */
  val longRunning: LockConfig = LockConfig(
    lockDuration = Duration.fromSeconds(300),
    acquireTimeout = Duration.fromSeconds(30),
  )
end LockConfig
