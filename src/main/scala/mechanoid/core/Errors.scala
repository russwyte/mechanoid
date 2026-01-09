package mechanoid.core

/** Base trait for all Mechanoid errors.
  *
  * Extend this trait to create custom error types that integrate with the Mechanoid error handling.
  */
trait MechanoidError

/** Error indicating an invalid state transition was attempted.
  *
  * @param currentState
  *   The state the FSM was in
  * @param event
  *   The event that was sent
  * @param message
  *   Additional context about why the transition is invalid
  */
final case class InvalidTransitionError(
    currentState: MState,
    event: MEvent,
    message: String = "No transition defined",
) extends MechanoidError

/** Error indicating a guard condition prevented the transition.
  *
  * @param currentState
  *   The state the FSM was in
  * @param event
  *   The event that was sent
  */
final case class GuardRejectedError(
    currentState: MState,
    event: MEvent,
) extends MechanoidError

/** Error indicating the FSM has been stopped. */
final case class FSMStoppedError(reason: Option[String]) extends MechanoidError

/** Error indicating a timeout occurred during event processing.
  *
  * @param currentState
  *   The state when the timeout occurred
  * @param timeoutMs
  *   The timeout duration in milliseconds
  */
final case class ProcessingTimeoutError(
    currentState: MState,
    timeoutMs: Long,
) extends MechanoidError

/** Error indicating a persistence operation failed.
  *
  * @param message
  *   A description of what went wrong
  * @param cause
  *   The underlying Throwable, if available (preserves stack trace)
  */
final case class PersistenceError(
    message: String,
    cause: Option[Throwable] = None,
) extends Exception(message, cause.orNull)
    with MechanoidError

object PersistenceError:
  /** Create from a Throwable, extracting its message and preserving the cause. */
  def apply(cause: Throwable): PersistenceError =
    PersistenceError(Option(cause.getMessage).getOrElse(cause.getClass.getSimpleName), Some(cause))

  /** Create from any error type, using its toString as the message. */
  def fromError[E](error: E): PersistenceError =
    PersistenceError(error.toString)

/** Error indicating a sequence conflict during distributed operation.
  *
  * This occurs when multiple FSM instances try to append events concurrently. The caller should reload events and
  * retry, or fail the operation.
  *
  * @param instanceId
  *   The FSM instance identifier (as String for type erasure)
  * @param expectedSeqNr
  *   The sequence number we expected
  * @param actualSeqNr
  *   The actual sequence number in the store
  */
final case class SequenceConflictError(
    instanceId: String,
    expectedSeqNr: Long,
    actualSeqNr: Long,
) extends Exception(
      s"Sequence conflict for $instanceId: expected $expectedSeqNr but found $actualSeqNr"
    )
    with MechanoidError

/** Error indicating an event from the store cannot be replayed.
  *
  * This occurs during recovery when a stored event doesn't match any transition in the current FSM definition. Common
  * causes:
  *   - FSM definition changed (states/events/transitions removed)
  *   - Event was persisted by a different FSM version
  *
  * To handle schema evolution, catch this error and implement your migration strategy (e.g., skip the event, transform
  * it, or fail).
  *
  * @param currentState
  *   The state during replay
  * @param event
  *   The event that couldn't be replayed
  * @param sequenceNr
  *   The sequence number of the problematic event
  */
final case class EventReplayError(
    currentState: MState,
    event: MEvent,
    sequenceNr: Long,
) extends Exception(
      s"Cannot replay event $event (seqNr=$sequenceNr) from state $currentState: no matching transition"
    )
    with MechanoidError

/** Error indicating a distributed lock operation failed.
  *
  * This wraps the underlying LockError from the locking subsystem. Common causes:
  *   - Lock busy (another node is processing this FSM)
  *   - Lock acquisition timeout
  *   - Lock service unavailable
  *
  * @param cause
  *   The underlying lock error
  */
final case class LockingError(cause: MechanoidError) extends MechanoidError:
  def message: String = s"Locking operation failed: $cause"
