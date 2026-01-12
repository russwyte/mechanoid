package mechanoid.persistence.command

import zio.Duration
import java.time.Instant
import java.util.UUID

/** Configuration for [[CommandWorker]].
  *
  * Use the builder pattern to customize behavior:
  *
  * {{{
  * val config = CommandWorkerConfig()
  *   .withBatchSize(50)
  *   .withPollInterval(Duration.fromSeconds(5))
  *   .withRetryPolicy(RetryPolicy.exponentialBackoff(
  *     initialDelay = Duration.fromSeconds(1),
  *     maxDelay = Duration.fromSeconds(5),
  *     maxAttempts = 10
  *   ))
  * }}}
  */
final case class CommandWorkerConfig(
    /** Maximum commands to process per batch. */
    batchSize: Int = 100,

    /** How often to poll for new commands when idle. */
    pollInterval: Duration = Duration.fromSeconds(5),

    /** Jitter factor (0.0-1.0) to prevent thundering herd. */
    jitterFactor: Double = 0.2,

    /** How long a claimed command is locked to this worker. */
    claimDuration: Duration = Duration.fromSeconds(60),

    /** Retry policy for failed commands. */
    retryPolicy: RetryPolicy = RetryPolicy.exponentialBackoff(),

    /** Unique identifier for this worker node. */
    nodeId: String = CommandWorkerConfig.generateNodeId(),

    /** How often to release expired claims from dead workers. */
    claimCleanupInterval: Duration = Duration.fromSeconds(1),
):
  require(batchSize > 0, "batchSize must be positive")
  require(jitterFactor >= 0.0 && jitterFactor <= 1.0, "jitterFactor must be between 0.0 and 1.0")

  def withBatchSize(size: Int): CommandWorkerConfig =
    copy(batchSize = size)

  def withPollInterval(interval: Duration): CommandWorkerConfig =
    copy(pollInterval = interval)

  def withJitterFactor(factor: Double): CommandWorkerConfig =
    copy(jitterFactor = factor)

  def withClaimDuration(duration: Duration): CommandWorkerConfig =
    copy(claimDuration = duration)

  def withRetryPolicy(policy: RetryPolicy): CommandWorkerConfig =
    copy(retryPolicy = policy)

  def withNodeId(id: String): CommandWorkerConfig =
    copy(nodeId = id)

  def withClaimCleanupInterval(interval: Duration): CommandWorkerConfig =
    copy(claimCleanupInterval = interval)
end CommandWorkerConfig

object CommandWorkerConfig:
  private def generateNodeId(): String =
    s"worker-${UUID.randomUUID().toString.take(8)}"

  /** Fast configuration for low-latency command processing. */
  val fast: CommandWorkerConfig = CommandWorkerConfig(
    batchSize = 50,
    pollInterval = Duration.fromSeconds(1),
    claimDuration = Duration.fromSeconds(30),
    retryPolicy = RetryPolicy.exponentialBackoff(
      maxAttempts = 5,
      maxDelay = Duration.fromSeconds(1),
    ),
  )

  /** Conservative configuration for reliable processing. */
  val reliable: CommandWorkerConfig = CommandWorkerConfig(
    batchSize = 100,
    pollInterval = Duration.fromSeconds(10),
    claimDuration = Duration.fromSeconds(2),
    retryPolicy = RetryPolicy.exponentialBackoff(
      maxAttempts = 10,
      maxDelay = Duration.fromSeconds(30),
    ),
  )
end CommandWorkerConfig

/** Policy for retrying failed commands. */
sealed trait RetryPolicy:
  /** Calculate the next retry time after a failure.
    *
    * @param attempt
    *   The attempt number (1-based, so first failure is attempt 1)
    * @param now
    *   Current time
    * @return
    *   When to retry, or None if no more retries
    */
  def nextRetry(attempt: Int, now: Instant): Option[Instant]

  /** Maximum number of attempts before giving up. */
  def maxAttempts: Int
end RetryPolicy

object RetryPolicy:

  /** No retries - fail immediately. */
  case object NoRetry extends RetryPolicy:
    override def nextRetry(attempt: Int, now: Instant): Option[Instant] = None
    override def maxAttempts: Int                                       = 1

  /** Fixed delay between retries. */
  final case class FixedDelay(
      delay: Duration,
      override val maxAttempts: Int = 3,
  ) extends RetryPolicy:
    override def nextRetry(attempt: Int, now: Instant): Option[Instant] =
      if attempt >= maxAttempts then None
      else Some(now.plusMillis(delay.toMillis))

  /** Exponential backoff with configurable parameters. */
  final case class ExponentialBackoff(
      initialDelay: Duration = Duration.fromSeconds(1),
      maxDelay: Duration = Duration.fromSeconds(5),
      multiplier: Double = 2.0,
      override val maxAttempts: Int = 5,
  ) extends RetryPolicy:
    override def nextRetry(attempt: Int, now: Instant): Option[Instant] =
      if attempt >= maxAttempts then None
      else
        val delayMs = math.min(
          initialDelay.toMillis * math.pow(multiplier, attempt - 1).toLong,
          maxDelay.toMillis,
        )
        Some(now.plusMillis(delayMs))
  end ExponentialBackoff

  /** Convenience constructor for exponential backoff. */
  def exponentialBackoff(
      initialDelay: Duration = Duration.fromSeconds(1),
      maxDelay: Duration = Duration.fromSeconds(300),
      multiplier: Double = 2.0,
      maxAttempts: Int = 5,
  ): RetryPolicy =
    ExponentialBackoff(initialDelay, maxDelay, multiplier, maxAttempts)

  /** Convenience constructor for fixed delay. */
  def fixedDelay(delay: Duration, maxAttempts: Int = 3): RetryPolicy =
    FixedDelay(delay, maxAttempts)
end RetryPolicy
