package mechanoid

/** Mechanoid - A type-safe, effect-driven finite state machine library for ZIO.
  *
  * ==Quick Start==
  *
  * {{{
  * import mechanoid.*
  * import zio.*
  *
  * // Define states
  * enum LightState extends MState:
  *   case Off, On
  *
  * // Define events
  * enum LightEvent extends MEvent:
  *   case Toggle
  *
  * // Define the FSM
  * val lightFSM = FSMDefinition[LightState, LightEvent]
  *   .when(LightState.Off).on(LightEvent.Toggle).goto(LightState.On)
  *   .when(LightState.On).on(LightEvent.Toggle).goto(LightState.Off)
  *
  * // Run it
  * val program = ZIO.scoped {
  *   for
  *     fsm <- lightFSM.build(LightState.Off)
  *     _   <- fsm.send(LightEvent.Toggle)
  *     state <- fsm.currentState
  *   yield state // LightState.On
  * }
  * }}}
  */
object Mechanoid

// Re-export core types
export core.MState
export core.MEvent
export core.Timeout
export core.TransitionResult
export core.Transition
export core.StateLifecycle
export core.StateTimeout
export core.FSMState
export core.StateChange
export core.MechanoidError
export core.InvalidTransitionError
export core.GuardRejectedError
export core.FSMStoppedError
export core.ProcessingTimeoutError
export core.StateData
export core.EventData
export core.TerminalState

// Re-export DSL
export dsl.FSMDefinition

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
export persistence.PersistentFSMRuntime
export core.PersistenceError
export core.SequenceConflictError
export core.EventReplayError
