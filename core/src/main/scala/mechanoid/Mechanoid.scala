package mechanoid

import mechanoid.core.*
import mechanoid.dsl.FSMDefinition

object Mechanoid

// Re-export core types
export core.MState
export core.MEvent
export core.TransitionResult
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

// Re-export DSL
export dsl.FSMDefinition
export dsl.TypedDSL
export macros.TypedFSMValidation.validated
export macros.TypedFSMValidation.validatedWithCommands

// Re-export runtime
export runtime.FSMRuntime

// Re-export type-level
export typelevel.ValidTransition
export typelevel.TransitionSpec
export typelevel.Allow
export typelevel.TNil
export typelevel.::
export typelevel.TransitionSpecBuilder

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

// Re-export command types
export core.PendingCommand
export core.CommandStatus
export core.CommandResult

// Re-export in-memory stores
export stores.InMemoryEventStore
export stores.InMemoryCommandStore
export stores.InMemoryTimeoutStore
export stores.InMemoryFSMInstanceLock
