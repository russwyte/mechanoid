package mechanoid

import mechanoid.core.*
import zio.json.*

object Mechanoid

import scala.annotation.nowarn

// Auto-derive JsonCodec for any type with Finite
// The inline Mirror parameter allows derivation at the use site
@nowarn("msg=unused implicit parameter")
transparent inline given finiteJsonCodec[T](using
    inline f: Finite[T],
    inline m: scala.deriving.Mirror.Of[T],
): JsonCodec[T] =
  JsonCodec.derived[T]

// Re-export core types
export core.Finite
export core.TransitionResult
export core.TransitionOutcome
export core.Transition
export core.StateLifecycle
export core.StateTimeout
export core.FSMState
export core.MechanoidError
export core.InvalidTransitionError
export core.FSMStoppedError
export core.ActionFailedError
export core.ProcessingTimeoutError
export core.CaseHasher

// Re-export suite-style DSL
export machine.Machine
export machine.Assembly
export machine.assembly
export machine.assemblyAll
export machine.include
export machine.all
export machine.anyOf
export machine.via
export machine.Aspect
export machine.stay
export machine.stop
export machine.TransitionSpec as MachineTransitionSpec
export machine.TimeoutSpec
export machine.ViaBuilder
export machine.AllMatcher
export machine.AnyOfMatcher
export machine.AnyOfEventMatcher

// Re-export runtime
export runtime.FSMRuntime
export runtime.FSMRuntimeAspect
export runtime.`@@` // Extension method for aspect composition

// Re-export locking strategies
export runtime.locking.LockingStrategy
export runtime.locking.OptimisticLockingStrategy
export runtime.locking.DistributedLockingStrategy

// Re-export timeout strategies
export runtime.timeout.TimeoutStrategy
export runtime.timeout.FiberTimeoutStrategy
export runtime.timeout.DurableTimeoutStrategy

// Re-export persistence
export persistence.StoredEvent
export persistence.FSMSnapshot
export persistence.EventStore
export core.PersistenceError
export core.SequenceConflictError
export core.EventReplayError

// Re-export visualization
export visualization.TransitionKind
export visualization.TransitionMeta
export visualization.StateMeta
export visualization.FSMMeta
export visualization.TraceStep
export visualization.ExecutionTrace
export visualization.MermaidVisualizer
export visualization.GraphVizVisualizer

// Re-export redaction
export core.sensitive
export core.Redactor
export core.Redactor.redacted
export core.Redactor.redactedPretty

// Re-export in-memory stores
export stores.InMemoryEventStore
export stores.InMemoryTimeoutStore
export stores.InMemoryFSMInstanceLock

// Re-export locking types
export persistence.lock.FSMInstanceLock
export persistence.lock.LockedFSMRuntime
export persistence.lock.LockConfig
export persistence.lock.LockToken
export persistence.lock.LockResult
export persistence.lock.LockError
export persistence.lock.LockHeartbeatConfig
export persistence.lock.LockLostBehavior
export persistence.lock.AtomicTransactionContext
export core.LockingError
