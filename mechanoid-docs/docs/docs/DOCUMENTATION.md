# Mechanoid Documentation

A type-safe, effect-oriented finite state machine library for Scala 3 built on ZIO.

## Table of Contents

- [Overview](#overview)
- [Core Concepts](#core-concepts)
  - [States](#states)
  - [Events](#events)
  - [Transitions](#transitions)
  - [FSM State Container](#fsm-state-container)
- [Defining FSMs](#defining-fsms)
  - [Basic Definition](#basic-definition)
  - [Compile-Time Safety](#compile-time-safety)
  - [The assemblyAll Block Syntax](#the-assemblyall-block-syntax)
  - [Entry and Exit Actions](#entry-and-exit-actions)
  - [Timeouts](#timeouts)
  - [Assembly Composition](#assembly-composition)
- [Running FSMs](#running-fsms)
  - [Simple Runtime](#simple-runtime)
  - [Persistent Runtime](#persistent-runtime)
  - [Sending Events](#sending-events)
- [Persistence](#persistence)
  - [Event Sourcing Model](#event-sourcing-model)
  - [EventStore Interface](#eventstore-interface)
  - [Snapshots](#snapshots)
  - [Recovery](#recovery)
  - [Optimistic Locking](#optimistic-locking)
- [Durable Timeouts](#durable-timeouts)
  - [The Problem](#the-problem)
  - [TimeoutStore](#timeoutstore)
  - [TimeoutSweeper](#timeoutsweeper)
  - [Sweeper Configuration](#sweeper-configuration)
  - [Leader Election](#leader-election)
- [Distributed Architecture](#distributed-architecture)
  - [Load-on-Demand Model](#load-on-demand-model)
  - [When Does a Node See Updates?](#when-does-a-node-see-updates)
  - [Design Benefits](#design-benefits)
  - [Conflict Handling](#conflict-handling)
- [Distributed Locking](#distributed-locking)
  - [Why Use Locking](#why-use-locking)
  - [FSMInstanceLock](#fsminstancelock)
  - [Lock Configuration](#lock-configuration)
  - [Node Failure Resilience](#node-failure-resilience)
  - [Combining Features](#combining-features)
- [Side Effects](#side-effects)
  - [Synchronous Entry Effects](#synchronous-entry-effects)
  - [Producing Effects](#producing-effects)
  - [Fault-Tolerant Patterns](#fault-tolerant-patterns)
  - [Per-State Lifecycle Actions](#per-state-lifecycle-actions)
- [Visualization](#visualization)
  - [Overview](#visualization-overview)
  - [MermaidVisualizer](#mermaidvisualizer)
  - [GraphVizVisualizer](#graphvizvisualizer)
  - [Generating Visualizations](#generating-visualizations)
- [Error Handling](#error-handling)
- [Complete Example](#complete-example)

---

## Overview

Mechanoid provides a declarative DSL for defining finite state machines with:

- **Type-safe states and events** using Scala 3 enums
- **Ergonomic infix syntax** - `State via Event to Target`
- **Composable assemblies** - build reusable FSM fragments and combine them with full compile-time validation
- **Effectful transitions** via ZIO
- **Optional persistence** through event sourcing
- **Durable timeouts** that survive node failures
- **Distributed coordination** with claim-based locking and leader election

```scala mdoc:silent
import mechanoid.*
import zio.*

// Define states and events as plain enums
enum OrderState derives Finite:
  case Pending, Paid, Shipped, Delivered

enum OrderEvent derives Finite:
  case Pay, Ship, Deliver

import OrderState.*, OrderEvent.*

// Create FSM with clean infix syntax
val orderMachine = Machine(assembly[OrderState, OrderEvent](
  Pending via Pay to Paid,
  Paid via Ship to Shipped,
  Shipped via Deliver to Delivered,
))
```

```scala mdoc:compile-only
// Run the FSM
val program = ZIO.scoped {
  for
    fsm   <- orderMachine.start(Pending)
    _     <- fsm.send(Pay)
    _     <- fsm.send(Ship)
    state <- fsm.currentState
  yield state // Shipped
}
```

---

## Core Concepts

### States

States represent the possible conditions of your FSM. Define them as plain Scala 3 enums:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*
```

```scala mdoc:compile-only
enum TrafficLight:
  case Red, Yellow, Green
```

States can also carry data (rich states):

```scala mdoc:compile-only
enum OrderState:
  case Pending
  case Paid(transactionId: String)
  case Failed(reason: String)
```

When defining transitions, the state's "shape" (which case it is) is used for matching, not the exact value. This means a transition from `Failed` will match ANY `Failed(_)` state.

#### Hierarchical States

For complex domains, organize related states using sealed traits:

```scala mdoc:compile-only
sealed trait OrderState

case object Created extends OrderState

// Group all processing-related states
sealed trait Processing extends OrderState
case object ValidatingPayment extends Processing
case object ChargingCard extends Processing

case object Completed extends OrderState
```

Benefits:
- **Code organization** - Related states are grouped together
- **Group transitions** - Use `all[Processing]` to define transitions for all processing states at once
- **Type safety** - Can pattern match on parent traits

#### Multi-State Transitions

Use `all[T]` to define transitions that apply to all subtypes of a sealed type:

```scala mdoc:silent
// Setup for multi-state transition examples
sealed trait OrderState derives Finite

case object Created extends OrderState
case object Cancelled extends OrderState
case object Archived extends OrderState

sealed trait Processing extends OrderState derives Finite
case object ValidatingPayment extends Processing
case object ChargingCard extends Processing

case object Completed extends OrderState

enum OrderEvent derives Finite:
  case Cancel, Archive
```

```scala mdoc:compile-only
import OrderEvent.*

// All Processing states can be cancelled
val transitions = assembly[OrderState, OrderEvent](
  all[Processing] via Cancel to Cancelled,
)
```

Use `anyOf(...)` for specific states that don't share a common parent:

```scala mdoc:compile-only
import OrderEvent.*

// These specific states can be archived
val transitions = assembly[OrderState, OrderEvent](
  anyOf(Created, Completed) via Archive to Archived,
)
```

### Events

Events trigger transitions between states. Define them as plain enums:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*
```

```scala mdoc:compile-only
enum TrafficEvent:
  case Timer, EmergencyOverride
```

**Events with data:**

```scala mdoc:compile-only
enum PaymentEvent:
  case Pay(amount: BigDecimal)
  case Refund(orderId: String, amount: BigDecimal)
```

**Event hierarchies:**

Like states, events can be organized hierarchically:

```scala mdoc:compile-only
sealed trait UserEvent
sealed trait InputEvent extends UserEvent
case object Click extends InputEvent
case object Tap extends InputEvent
case object Swipe extends InputEvent
```

#### Multi-Event Transitions

Use `event[T]` to match events by type (useful for events with data):

```scala mdoc:silent
enum OrderState derives Finite:
  case Pending, Processing

enum OrderEvent derives Finite:
  case Pay(amount: BigDecimal)

import OrderState.*, OrderEvent.*
```

```scala mdoc:compile-only
// Match any Pay event, regardless of amount
val transitions = assembly[OrderState, OrderEvent](
  Pending via event[Pay] to Processing,
)
```

Use `anyOfEvents(...)` for specific events:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum UIState derives Finite:
  case Idle, Active

sealed trait UIEvent derives Finite
case object Click extends UIEvent
case object Tap extends UIEvent
case object Swipe extends UIEvent

import UIState.*
```

```scala mdoc:compile-only
// Multiple events trigger same transition
val transitions = assembly[UIState, UIEvent](
  Idle viaAnyOf anyOfEvents(Click, Tap, Swipe) to Active,
)
```

### Transitions

Transitions define what happens when an event is received in a specific state. Use the clean infix syntax:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum MyState derives Finite:
  case Pending, Paid, Failed

enum MyEvent derives Finite:
  case Pay, Heartbeat, Shutdown

import MyState.*, MyEvent.*
```

```scala mdoc:compile-only
val transitions = assembly[MyState, MyEvent](
  // Simple transition: Pending + Pay -> Paid
  Pending via Pay to Paid,

  // Stay in current state
  Pending via Heartbeat to stay,

  // Stop the FSM
  Failed via Shutdown to stop,
)
```

**TransitionResult** represents the outcome:

| Result | Description |
|--------|-------------|
| `Goto(state)` | Transition to a new state |
| `Stay` | Remain in current state |
| `Stop(reason)` | Terminate the FSM |

### FSM State Container

`FSMState[S]` holds runtime information about the FSM:

```scala mdoc:compile-only
val machine = Machine(assembly[MyState, MyEvent](
  Pending via Pay to Paid,
))

val program = ZIO.scoped {
  for
    fsm <- machine.start(Pending)
    s   <- fsm.state
  yield {
    s.current            // Current state
    s.history            // List of previous states (most recent first)
    s.stateData          // Arbitrary key-value data
    s.startedAt          // When FSM was created
    s.lastTransitionAt   // When last transition occurred
    s.transitionCount    // Number of transitions
    s.previousState      // Option of previous state
  }
}
```

---

## Defining FSMs

### Basic Definition

Create FSM definitions using `assembly` to define transitions and `Machine` to make them runnable:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum MyState derives Finite:
  case State1, State2, State3

enum MyEvent derives Finite:
  case Event1, Event2, Event3

import MyState.*, MyEvent.*
```

```scala mdoc:compile-only
val machine = Machine(assembly[MyState, MyEvent](
  State1 via Event1 to State2,
  State1 via Event2 to stay,
  State2 via Event3 to State3,
))
```

The `assembly` macro performs compile-time validation of transitions, and `Machine(assembly)` creates the runnable FSM.

### Compile-Time Safety

Mechanoid leverages Scala 3 macros to catch errors at compile time rather than runtime. This section documents all compile-time guarantees.

#### Type Safety: Finite Derivation

States and events must derive `Finite` to be used in an FSM. The macro validates:

1. **Sealed requirement** - Type must be `sealed trait`, `sealed class`, or `enum`
2. **Non-empty cases** - Must have at least one case

```scala mdoc:fail
// Non-sealed types fail compilation:
trait NotSealed derives Finite
```

```scala mdoc:fail
// Sealed types with no cases fail:
sealed trait EmptySealed derives Finite
```

#### Duplicate Transition Detection

The `assembly` macro detects duplicate transitions at compile time:

```scala mdoc:fail
// This will fail at compile time:
val bad = assembly[MyState, MyEvent](
  State1 via Event1 to State2,
  State1 via Event1 to State3,  // Error: Duplicate transition for State1 + Event1
)
```

#### Override Validation

To intentionally override a transition (e.g., after using `all[T]`), use `@@ Aspect.overriding`:

```scala mdoc:silent
sealed trait MyState2 derives Finite

sealed trait Processing extends MyState2 derives Finite
case object SpecialState extends Processing
case object RegularState extends Processing

case object Cancelled extends MyState2
case object Special extends MyState2

enum MyEvent2 derives Finite:
  case Cancel

import MyEvent2.*
```

```scala mdoc:compile-only
val machine = Machine(assembly[MyState2, MyEvent2](
  all[Processing] via Cancel to Cancelled,
  (SpecialState via Cancel to Special) @@ Aspect.overriding,  // OK: Intentional override
))
```

**Orphan Override Warnings:**

If you mark a transition with `@@ Aspect.overriding` but there's nothing to override, the compiler emits a warning:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum MyState derives Finite:
  case State1, State2

enum MyEvent derives Finite:
  case Event1

import MyState.*, MyEvent.*
```

```scala mdoc:compile-only
// This produces a compile-time warning about orphan override:
val machine = Machine(assembly[MyState, MyEvent](
  (State1 via Event1 to State2) @@ Aspect.overriding,  // Warning: no duplicate to override
))
// Compiler emits: MyState.State1 via MyEvent.Event1: marked @@ Aspect.overriding but no duplicate to override
```

This helps catch typos or refactoring issues where an override becomes orphaned.

#### Assembly Inline Requirement

For orphan override detection to work, assemblies must be passed inline to `Machine()`:

```scala mdoc:fail
// Using a val prevents orphan detection - this fails:
val myAssembly = assembly[MyState, MyEvent](
  State1 via Event1 to State2
)
Machine(myAssembly)  // Error: Assembly must be passed inline
```

Use `inline def` if you need to store an assembly:

```scala mdoc:compile-only
inline def myAssembly = assembly[MyState, MyEvent](
  State1 via Event1 to State2
)
Machine(myAssembly)  // OK: inline def preserves the expression
```

#### Produced Event Type Validation

The `.producing` effect must return an event type that's part of the FSM's event hierarchy:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum MyState derives Finite:
  case A, B

enum MyEvent derives Finite:
  case E1
  case Produced

case class UnrelatedEvent(msg: String)

import MyState.*, MyEvent.*
```

```scala mdoc:compile-only
// OK: Produced is part of MyEvent
val good = assembly[MyState, MyEvent](
  (A via E1 to B).producing { (_, _) => ZIO.succeed(Produced) }
)
```

```scala mdoc:fail
// Error: UnrelatedEvent is not part of MyEvent hierarchy
val bad = assembly[MyState, MyEvent](
  (A via E1 to B).producing { (_, _) => ZIO.succeed(UnrelatedEvent("oops")) }
)
```

#### Val Reference in assemblyAll

In `assemblyAll` blocks, local vals holding assemblies must use `include()`:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum S derives Finite:
  case A, B, C

enum E derives Finite:
  case E1, E2

import S.*, E.*
```

```scala mdoc:fail
// Error: Val reference requires include() wrapper
val machine = Machine(assemblyAll[S, E]:
  val shared = assembly[S, E](A via E1 to B)
  shared  // Error! Should be: include(shared)
)
```

The correct way is to wrap val references with `include()`:

```scala
// OK: Using include() for val references
val machine = Machine(assemblyAll[S, E]:
  val shared = assembly[S, E](A via E1 to B)
  include(shared)  // Correct way to include assembly vals
  B via E2 to C
)
```

### The assemblyAll Block Syntax

For more complex definitions with local helper values, use `assemblyAll`:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum OrderState derives Finite:
  case Created, PaymentProcessing, Paid, Cancelled

enum OrderEvent derives Finite:
  case InitiatePayment(orderId: String, amount: BigDecimal)
  case PaymentSucceeded
  case PaymentFailed

import OrderState.*, OrderEvent.*
```

```scala mdoc:compile-only
val machine = Machine(assemblyAll[OrderState, OrderEvent]:
  // Local helper vals at the top
  val logPaymentStart: (OrderEvent, OrderState) => ZIO[Any, Nothing, Unit] = { (event, _) =>
    event match
      case e: InitiatePayment => ZIO.logInfo(s"Processing payment for ${e.orderId}: ${e.amount}")
      case _ => ZIO.unit
  }

  // Transitions use the helpers
  (Created via event[InitiatePayment] to PaymentProcessing).onEntry(logPaymentStart)
  PaymentProcessing via PaymentSucceeded to Paid
  PaymentProcessing via PaymentFailed to Cancelled
)
```

The `assemblyAll` block allows mixing val definitions with transition expressions. The vals are available for use in `.onEntry` effects and other parts of the definition. No commas are needed between transition specs.

**Important:** When including val references to `Assembly` in an `assemblyAll` block, use the `include()` wrapper:

```scala mdoc:compile-only
val baseAssembly = assembly[OrderState, OrderEvent](
  Created via event[InitiatePayment] to PaymentProcessing,
)

val fullMachine = Machine(assemblyAll[OrderState, OrderEvent]:
  include(baseAssembly)  // Use include() for assembly references
  PaymentProcessing via PaymentSucceeded to Paid
)
```

### Entry and Exit Actions

Define actions that run when entering or leaving a state using the `withEntry` and `withExit` methods on `Machine`:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum MyState derives Finite:
  case Idle, Running

enum MyEvent derives Finite:
  case Start, Stop

import MyState.*, MyEvent.*
```

```scala mdoc:compile-only
val machine = Machine(assembly[MyState, MyEvent](
  Idle via Start to Running,
  Running via Stop to Idle,
)).withEntry(Running)(ZIO.logInfo("Entered Running state"))
  .withExit(Running)(ZIO.logInfo("Exiting Running state"))
```

### Timeouts

Mechanoid provides a flexible timeout strategy where you define your own timeout events. This gives you complete control over timeout handling and enables powerful patterns.

#### Basic Timeout Usage

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum OrderState derives Finite:
  case Created, WaitingForPayment, Confirmed, Cancelled

enum OrderEvent derives Finite:
  case Pay, Paid, PaymentTimeout  // User-defined timeout event

import OrderState.*, OrderEvent.*
```

```scala mdoc:compile-only
// Apply timeout to the transition - when entering WaitingForPayment, a timeout is scheduled
val machine = Machine(assembly[OrderState, OrderEvent](
  (Created via Pay to WaitingForPayment) @@ Aspect.timeout(30.minutes, PaymentTimeout),
  WaitingForPayment via PaymentTimeout to Cancelled,  // Handle timeout
  WaitingForPayment via Paid to Confirmed,            // Or complete before timeout
))
```

The `@@ Aspect.timeout(duration, event)` syntax on transitions:
1. Schedules a timeout when the FSM enters the state
2. Fires the specified event when the timeout expires
3. Cancels the timeout if another event is processed first

#### Multiple Timeout Events

A key feature is that **different states can use different timeout events**. This enables rich timeout handling:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum OrderState derives Finite:
  case Created, PaymentPending, ShipmentPending, Delivered, Cancelled, Refunded

enum OrderEvent derives Finite:
  case Pay, Ship, Deliver, Confirm
  case PaymentTimeout     // Fired after 30 minutes in PaymentPending
  case ShipmentTimeout    // Fired after 7 days in ShipmentPending

import OrderState.*, OrderEvent.*
```

```scala mdoc:compile-only
val machine = Machine(assembly[OrderState, OrderEvent](
  (Created via Pay to PaymentPending) @@ Aspect.timeout(30.minutes, PaymentTimeout),
  PaymentPending via PaymentTimeout to Cancelled,
  (PaymentPending via Confirm to ShipmentPending) @@ Aspect.timeout(7.days, ShipmentTimeout),
  ShipmentPending via ShipmentTimeout to Refunded,
  ShipmentPending via Ship to Delivered,
))
```

#### Timeout Events with Data

Since timeout events are regular events in your enum, they can carry data:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*
import java.time.Instant

enum SessionState derives Finite:
  case Idle, Active, Expired

enum SessionEvent derives Finite:
  case Login
  case IdleTimeout(lastActivity: Instant)     // Carries the last activity time
  case AbsoluteTimeout(sessionStart: Instant) // Carries session start time

import SessionState.*, SessionEvent.*
```

```scala mdoc:compile-only
// Timeout events can carry data - useful for logging/debugging
val machine = Machine(assembly[SessionState, SessionEvent](
  (Idle via Login to Active) @@ Aspect.timeout(15.minutes, IdleTimeout(Instant.now())),
  Active via event[IdleTimeout] to Expired,
))
```

#### Different Outcomes for Same State

You can have multiple timeout types affecting the same state with different outcomes:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum AuctionState derives Finite:
  case Pending, Bidding, Extended, Sold

enum AuctionEvent derives Finite:
  case Bid(amount: BigDecimal)
  case StartAuction
  case ExtensionTimeout   // Short timeout - extends auction on late bids
  case FinalTimeout       // Long timeout - auction ends

import AuctionState.*, AuctionEvent.*
```

```scala mdoc:compile-only
val machine = Machine(assembly[AuctionState, AuctionEvent](
  // Start auction with 5-minute extension timeout
  (Pending via StartAuction to Bidding) @@ Aspect.timeout(5.minutes, ExtensionTimeout),

  // Late bid resets the 5-minute timer
  (Bidding via event[Bid] to Bidding) @@ Aspect.timeout(5.minutes, ExtensionTimeout),
  // Extension timeout moves to final phase with 1-minute timer
  (Bidding via ExtensionTimeout to Extended) @@ Aspect.timeout(1.minute, FinalTimeout),

  // Final phase: bid resets 1-minute timer
  (Extended via event[Bid] to Extended) @@ Aspect.timeout(1.minute, FinalTimeout),
  Extended via FinalTimeout to Sold,  // Auction ends
))
```

#### Why User-Defined Timeout Events?

This design provides several advantages over a built-in `Timeout` singleton:

1. **Type safety** - Different timeouts are distinct types, preventing mix-ups
2. **Rich handling** - Each timeout can trigger different transitions and side effects
3. **Data carrying** - Timeout events can include context (timestamps, reason codes)
4. **Clear intent** - Reading `PaymentTimeout` is clearer than `Timeout`
5. **Event sourcing** - All timeout events are persisted like regular events

### Assembly Composition

Use `assembly` to create reusable transition fragments that can be composed with full compile-time validation:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

sealed trait DocumentState derives Finite
case object Draft extends DocumentState
case object PendingReview extends DocumentState
case object UnderReview extends DocumentState
case object Cancelled extends DocumentState

sealed trait InReview extends DocumentState derives Finite
case object ReviewInProgress extends InReview
case object AwaitingFeedback extends InReview

sealed trait Approval extends DocumentState derives Finite
case object PendingApproval extends Approval
case object ApprovalGranted extends Approval

enum DocumentEvent derives Finite:
  case CancelReview, Abandon, SubmitForReview, AssignReviewer

import DocumentEvent.*
```

```scala mdoc:compile-only
// Reusable behaviors defined as assemblies
val cancelableBehaviors = assembly[DocumentState, DocumentEvent](
  all[InReview] via CancelReview to Draft,
  all[Approval] via Abandon to Cancelled,
)

// Compose assemblies with specific transitions using assemblyAll
val fullWorkflow = Machine(assemblyAll[DocumentState, DocumentEvent]:
  include(cancelableBehaviors)  // Include all transitions from assembly

  Draft via SubmitForReview to PendingReview
  PendingReview via AssignReviewer to UnderReview
)

// Or compose using assembly with include
val fullWorkflow2 = Machine(assembly[DocumentState, DocumentEvent](
  include(cancelableBehaviors),
  Draft via SubmitForReview to PendingReview,
  PendingReview via AssignReviewer to UnderReview,
))
```

**Key difference between `Assembly` and `Machine`:**
- `Assembly` is a reusable fragment that **cannot be run** directly
- `Machine(assembly)` creates a complete `Machine` that **can be run**

**Duplicate Detection:**

Mechanoid detects duplicate transitions (same state + event combination) at compile time in all scenarios:

| Scenario | Detection | When |
|----------|-----------|------|
| Inline specs (`A via E to B`) | **Compile time** | Macro can inspect AST |
| Same val used twice | **Compile time** | Symbol tracking |
| Assembly composition with `include()` | **Compile time** | Assembly specs are extracted at macro expansion |

**Two-Level Validation:**

1. **Level 1 (Assembly scope)**: Duplicates within a single `assembly()` call are detected
2. **Level 2 (Machine scope)**: Duplicates across multiple included assemblies and inline specs are detected

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum S derives Finite:
  case A, B, C

enum E derives Finite:
  case E1

import S.*, E.*
```

```scala mdoc:fail
// Level 1 - Compile ERROR within assembly
val bad = assembly[S, E](
  A via E1 to B,
  A via E1 to C,  // Compile ERROR: duplicate transition
)
```

```scala mdoc:fail
// Level 2 - Compile ERROR across assemblies
val a1 = assembly[S, E](A via E1 to B)
val a2 = assembly[S, E](A via E1 to C)
val machine = Machine(assembly[S, E](
  include(a1),
  include(a2),  // Compile ERROR: duplicate A via E1
))
```

```scala mdoc:compile-only
// Use @@ Aspect.overriding to allow intentional overrides at the transition level
val a1 = assembly[S, E](A via E1 to B)
val a2WithOverride = assembly[S, E]((A via E1 to C) @@ Aspect.overriding)
val machine = Machine(assembly[S, E](
  include(a1),
  include(a2WithOverride),  // OK: a2's transitions override a1's
))
```

```scala mdoc:compile-only
// Or override directly in the composed assembly
val a1 = assembly[S, E](A via E1 to B)
val machine = Machine(assembly[S, E](
  include(a1),
  (A via E1 to C) @@ Aspect.overriding,  // OK: inline override wins
))
```

When overrides are detected, the compiler emits informational messages showing which transitions are being overridden.

**Orphan Override Detection:**

If an assembly or transition is marked with `@@ Aspect.overriding` but doesn't actually override anything, the compiler emits a warning when `Machine(assembly)` is called:

```scala mdoc:compile-only
// Use inline def to preserve assembly for orphan override detection
inline def orphanAssembly = assembly[S, E](
  (A via E1 to B) @@ Aspect.overriding,  // No duplicate to override!
)
val machine = Machine(orphanAssembly)
// Compiler will warn: S.A via E.E1: marked @@ Aspect.overriding but no duplicate to override
```

This helps catch refactoring issues where an override becomes orphaned after the original transition is removed.

---

## Running FSMs

Mechanoid provides a unified `FSMRuntime[Id, S, E]` interface for all FSM execution scenarios. The runtime has three type parameters:

- `Id` - The instance identifier type (`Unit` for simple FSMs, or a custom type like `String` or `UUID` for persistent FSMs)
- `S` - The state type (sealed enum or sealed trait)
- `E` - The event type (sealed enum or sealed trait)

### Simple Runtime

For simple, single-instance FSMs without persistence, use `machine.start(initialState)`:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum MyState derives Finite:
  case Initial, Running, Done

enum MyEvent derives Finite:
  case Start, Finish

import MyState.*, MyEvent.*

val machine = Machine(assembly[MyState, MyEvent](
  Initial via Start to Running,
  Running via Finish to Done,
))
```

```scala mdoc:compile-only
val program = ZIO.scoped {
  for
    fsm <- machine.start(Initial)  // Returns FSMRuntime[Unit, S, E]
    // Use the FSM...
    _ <- fsm.send(Start)
  yield ()
}
```

This creates an in-memory FSM with `Unit` as the instance ID. The FSM is automatically stopped when the scope closes.

### Persistent Runtime

For persistent, identified FSMs, use `FSMRuntime.apply`:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum OrderState derives Finite:
  case Pending, Paid, Shipped

enum OrderEvent derives Finite:
  case Pay, Ship

import OrderState.*, OrderEvent.*

val machine = Machine(assembly[OrderState, OrderEvent](
  Pending via Pay to Paid,
  Paid via Ship to Shipped,
))

type OrderId = String
val orderId: OrderId = "order-1"
val eventStoreLayer: zio.ULayer[EventStore[OrderId, OrderState, OrderEvent]] =
  InMemoryEventStore.layer[OrderId, OrderState, OrderEvent]
```

```scala mdoc:compile-only
val program = ZIO.scoped {
  for
    fsm <- FSMRuntime(orderId, machine, Pending)  // Returns FSMRuntime[String, S, E]
    // Use the FSM...
    _ <- fsm.send(Pay)
  yield ()
}.provide(
  eventStoreLayer,
  TimeoutStrategy.fiber[OrderId],      // or TimeoutStrategy.durable for persistence
  LockingStrategy.optimistic[OrderId]  // or LockingStrategy.distributed for locking
)
```

The persistent runtime requires three dependencies in the environment:
- **`EventStore[Id, S, E]`** - Persists events and snapshots
- **`TimeoutStrategy[Id]`** - Handles state timeouts (fiber-based or durable)
- **`LockingStrategy[Id]`** - Handles concurrent access (optimistic or distributed)

### Sending Events

```scala mdoc:compile-only
// Using the machine already defined above
val program = ZIO.scoped {
  for
    fsm     <- machine.start(Pending)
    outcome <- fsm.send(Pay)
  yield outcome.result match
    case TransitionResult.Goto(newState) => s"Transitioned to $newState"
    case TransitionResult.Stay           => "Stayed in current state"
    case TransitionResult.Stop(reason)   => s"Stopped: $reason"
}
```

Possible errors:
- `InvalidTransitionError` - No transition defined for state/event

---

## Persistence

### Event Sourcing Model

Mechanoid supports event sourcing for durable FSMs:

1. Events are persisted *after* the transition action succeeds
2. State is reconstructed by replaying events
3. Snapshots reduce recovery time

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum OrderState derives Finite:
  case Pending, Paid, Shipped

enum OrderEvent derives Finite:
  case Pay, Ship

import OrderState.*, OrderEvent.*

val machine = Machine(assembly[OrderState, OrderEvent](
  Pending via Pay to Paid,
  Paid via Ship to Shipped,
))

type OrderId = String
val orderId: OrderId = "order-1"
val eventStoreLayer: zio.ULayer[EventStore[OrderId, OrderState, OrderEvent]] =
  InMemoryEventStore.layer[OrderId, OrderState, OrderEvent]
```

```scala mdoc:compile-only
val program = ZIO.scoped {
  for
    fsm <- FSMRuntime(orderId, machine, Pending)
    _   <- fsm.send(Pay)    // Event persisted
    _   <- fsm.send(Ship)   // Event persisted
  yield ()
}.provide(
  eventStoreLayer,
  TimeoutStrategy.fiber[OrderId],
  LockingStrategy.optimistic[OrderId]
)
```


### EventStore Interface

Implement `EventStore[Id, S, E]` for your storage backend:

```scala mdoc:compile-only
import zio.stream.ZStream

trait EventStore[Id, S, E]:
  def append(instanceId: Id, event: E, expectedSeqNr: Long): ZIO[Any, MechanoidError, Long]
  def loadEvents(instanceId: Id): ZStream[Any, MechanoidError, StoredEvent[Id, E]]
  def loadEventsFrom(instanceId: Id, fromSeqNr: Long): ZStream[Any, MechanoidError, StoredEvent[Id, E]]
  def loadSnapshot(instanceId: Id): ZIO[Any, MechanoidError, Option[FSMSnapshot[Id, S]]]
  def saveSnapshot(snapshot: FSMSnapshot[Id, S]): ZIO[Any, MechanoidError, Unit]
  def highestSequenceNr(instanceId: Id): ZIO[Any, MechanoidError, Long]
```

**Critical**: `append` must implement optimistic locking - atomically check that `expectedSeqNr` matches the current highest sequence number, then increment. This prevents lost updates in concurrent scenarios.

### Snapshots

Snapshots capture point-in-time state to speed up recovery:

```scala mdoc:compile-only
val program = ZIO.scoped {
  for
    fsm <- FSMRuntime(orderId, machine, Pending)

    // Manual snapshot (you control when)
    _ <- fsm.saveSnapshot

    // Example strategies:
    // After every N events
    seqNr <- fsm.lastSequenceNr
    _ <- ZIO.when(seqNr % 100 == 0)(fsm.saveSnapshot)

    // On specific states
    state <- fsm.currentState
    _ <- ZIO.when(state == Shipped)(fsm.saveSnapshot)
  yield ()
}.provide(
  eventStoreLayer,
  TimeoutStrategy.fiber[OrderId],
  LockingStrategy.optimistic[OrderId]
)
```

### Recovery

On startup, `FSMRuntime` (when provided with an EventStore):

1. Loads the latest snapshot (if any)
2. Replays only events *after* the snapshot's sequence number
3. Resumes normal operation

Recovery time is proportional to events since the last snapshot, not total events.

### Optimistic Locking

The persistence layer uses optimistic locking to detect concurrent modifications:

```scala mdoc:compile-only
// If another process modified the FSM between read and write:
val error = SequenceConflictError(instanceId = orderId, expectedSeqNr = 5, actualSeqNr = 6)
```

This error indicates a concurrent modification. The caller should reload state and retry.

---

## Durable Timeouts

### The Problem

In-memory timeouts (fiber-based) don't survive node failures. If a node crashes while an FSM is in a timed state, the timeout never fires.

### TimeoutStore

Persist timeout deadlines to a database:

```scala mdoc:compile-only
import java.time.Instant

trait TimeoutStore[Id]:
  def schedule(instanceId: Id, stateHash: Int, sequenceNr: Long, deadline: Instant): ZIO[Any, MechanoidError, ScheduledTimeout[Id]]
  def cancel(instanceId: Id): ZIO[Any, MechanoidError, Boolean]
  def queryExpired(limit: Int, now: Instant): ZIO[Any, MechanoidError, List[ScheduledTimeout[Id]]]
  def claim(instanceId: Id, nodeId: String, duration: Duration, now: Instant): ZIO[Any, MechanoidError, ClaimResult]
  def complete(instanceId: Id): ZIO[Any, MechanoidError, Boolean]
  def release(instanceId: Id): ZIO[Any, MechanoidError, Boolean]
```

The `stateHash` and `sequenceNr` parameters enable **state validation** - ensuring timeouts don't fire if the FSM has transitioned away from the timed state.

### TimeoutStrategy

Mechanoid uses a strategy pattern for timeout management. Choose the appropriate strategy for your deployment:

**Fiber-based (in-memory):**
```scala mdoc:compile-only
type OrderId = String
TimeoutStrategy.fiber[OrderId]  // Fast, but doesn't survive node failures
```

**Durable (persisted):**
```scala mdoc:compile-only
type OrderId = String
TimeoutStrategy.durable[OrderId]  // Requires TimeoutStore, survives node failures
```

Use `TimeoutStrategy.durable` for production deployments:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum OrderState derives Finite:
  case Pending, Started, Done

enum OrderEvent derives Finite:
  case StartPayment, Complete

import OrderState.*, OrderEvent.*

val machine = Machine(assembly[OrderState, OrderEvent](
  Pending via StartPayment to Started,
  Started via Complete to Done,
))

type OrderId = String
val orderId: OrderId = "order-1"
val eventStoreLayer: zio.ULayer[EventStore[OrderId, OrderState, OrderEvent]] =
  InMemoryEventStore.layer[OrderId, OrderState, OrderEvent]
val timeoutStoreLayer: zio.ULayer[TimeoutStore[OrderId]] =
  ZLayer.fromZIO(InMemoryTimeoutStore.make[OrderId])
```

```scala mdoc:compile-only
val program = ZIO.scoped {
  for
    fsm <- FSMRuntime(orderId, machine, Pending)
    _   <- fsm.send(StartPayment)
    // Timeout is now persisted - survives node restart
  yield ()
}.provide(
  eventStoreLayer,
  timeoutStoreLayer,
  TimeoutStrategy.durable[OrderId],
  LockingStrategy.optimistic[OrderId]
)
```

### TimeoutSweeper

A background service discovers and fires expired timeouts. It integrates directly with `FSMRuntime` for type-safe timeout handling:

```scala mdoc:compile-only
val config = TimeoutSweeperConfig()

val sweeper = ZIO.scoped {
  for
    timeoutStore <- ZIO.service[TimeoutStore[OrderId]]
    runtime <- FSMRuntime(orderId, machine, Pending)

    // TimeoutSweeper integrates directly with FSMRuntime
    sweeper <- TimeoutSweeper.make(config, timeoutStore, runtime)
    _ <- ZIO.never // Keep running
  yield ()
}.provide(
  eventStoreLayer,
  timeoutStoreLayer,
  TimeoutStrategy.durable[OrderId],
  LockingStrategy.optimistic[OrderId]
)
```

**Flow:**
1. Query for expired, unclaimed timeouts
2. Atomically claim each timeout (prevents duplicates)
3. **Validate FSM state**: compare current `(stateHash, sequenceNr)` with stored values
4. **If valid**: look up timeout event via `Machine.timeoutEvents(stateHash)`, fire via `runtime.send(event)`
5. **If stale** (state/seqNr changed): skip firing, increment `timeoutsSkipped` metric
6. Mark complete (removes from TimeoutStore)

**State Validation** prevents race conditions where a timeout fires after the FSM has already transitioned. The stored `sequenceNr` acts as a "generation counter" - if the FSM transitions away and back to the same state, old timeouts are correctly identified as stale.

### Sweeper Configuration

```scala mdoc:compile-only
val config = TimeoutSweeperConfig()
  .withSweepInterval(Duration.fromSeconds(5))     // Base interval
  .withJitterFactor(0.2)                          // 0.0-1.0, prevents thundering herd
  .withBatchSize(100)                             // Max per sweep
  .withClaimDuration(Duration.fromSeconds(30))   // How long to hold claims
  .withBackoffOnEmpty(Duration.fromSeconds(10)) // Extra wait when idle
  .withNodeId("node-1")                           // Unique node identifier
```

**Jitter algorithm:**
```
actualWait = sweepInterval + random(0, jitterFactor * sweepInterval)
           + (backoffOnEmpty if no timeouts found)
```

### Leader Election

For reduced database load, use single-active-sweeper mode:

```scala mdoc:compile-only
val leaseStore: LeaseStore = ??? // Your implementation

val config = TimeoutSweeperConfig()
  .withLeaderElection(
    LeaderElectionConfig()
      .withLeaseDuration(Duration.fromSeconds(30))
      .withRenewalInterval(Duration.fromSeconds(10))
  )

val sweeper = ZIO.scoped {
  for
    timeoutStore <- ZIO.service[TimeoutStore[OrderId]]
    runtime <- FSMRuntime(orderId, machine, Pending)
    sweeper <- TimeoutSweeper.make(config, timeoutStore, runtime, Some(leaseStore))
    _ <- ZIO.never
  yield ()
}.provide(
  eventStoreLayer,
  timeoutStoreLayer,
  TimeoutStrategy.durable[OrderId],
  LockingStrategy.optimistic[OrderId]
)
```

Only the leader node performs sweeps. If the leader fails, another node acquires the lease after expiration.

---

## Distributed Architecture

### Load-on-Demand Model

Mechanoid uses a **database as source of truth** pattern rather than in-memory cluster coordination. This means:

- **Nodes don't notify each other** - There's no pub/sub or cluster membership
- **State lives in the database** - The EventStore is the single source of truth
- **Load fresh on each operation** - FSMs are loaded from the database when needed
- **Stateless application nodes** - Any node can handle any FSM instance

```
Node A                    EventStore (DB)                 Node B
   │                           │                            │
   │── load events ───────────>│                            │
   │<── [event1, event2] ──────│                            │
   │                           │                            │
   │   (process event)         │                            │
   │                           │                            │
   │── append(event3, seq=2) ─>│                            │
   │<── success (seq=3) ───────│                            │
   │                           │                            │
   │                           │<── load events ────────────│
   │                           │──> [event1, event2, event3]│
```

### When Does a Node See Updates?

A node sees updates when it **next loads the FSM from the database**:

| Scenario | What Happens |
|----------|--------------|
| New request arrives | Loads latest events from EventStore |
| FSM already in scope | Uses cached state until scope closes |
| Timeout sweeper fires | Loads FSM fresh, checks state, fires if valid |
| After scope closes | Next request loads fresh state |

### Design Benefits

This architecture provides several advantages:

1. **Simplicity** - No cluster coordination protocol needed
2. **Horizontal scaling** - Add nodes without configuration changes
3. **Fault tolerance** - Node failures don't affect other nodes
4. **Consistency** - Database provides strong consistency guarantees
5. **Debugging** - All state changes are in the EventStore

### Conflict Handling

When two nodes try to modify the same FSM concurrently:

1. **Optimistic locking (always active)** - Sequence numbers detect conflicts at write time
2. **Distributed locking (optional)** - Prevents conflicts before they happen

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

type OrderId = String
val orderId: OrderId = "order-1"
```

```scala mdoc:compile-only
// Without distributed locking: conflict detected at write time
val error = SequenceConflictError(instanceId = orderId, expectedSeqNr = 5, actualSeqNr = 6)

// With distributed locking: conflict prevented upfront
LockingStrategy.distributed[OrderId]  // Acquires lock before each transition
```

---

## Distributed Locking

### Why Use Locking

Without locking, concurrent event processing for the same FSM instance relies on **optimistic locking** (sequence numbers), which detects conflicts *after* they happen:

1. Node A reads FSM state (seqNr = 5)
2. Node B reads FSM state (seqNr = 5)
3. Both process events concurrently
4. Node A writes (seqNr → 6) - succeeds
5. Node B writes (seqNr → 6) - fails with `SequenceConflictError`

This leads to:
- Wasted work (rejected processing)
- Retry overhead
- Potential confusion about "who won"

With **distributed locking**, conflicts are *prevented* rather than detected:

1. Node A acquires lock for FSM instance
2. Node B tries to acquire - waits or fails fast
3. Node A processes event, releases lock
4. Node B acquires lock, processes its event

### FSMInstanceLock

Implement `FSMInstanceLock[Id]` for your distributed lock backend:

```scala mdoc:compile-only
import java.time.Instant

trait FSMInstanceLock[Id]:
  def tryAcquire(instanceId: Id, nodeId: String, duration: Duration, now: Instant): ZIO[Any, Throwable, LockResult[Id]]
  def acquire(instanceId: Id, nodeId: String, duration: Duration, timeout: Duration): ZIO[Any, Throwable, LockResult[Id]]
  def release(token: LockToken[Id]): ZIO[Any, Throwable, Boolean]
  def extend(token: LockToken[Id], additionalDuration: Duration, now: Instant): ZIO[Any, Throwable, Option[LockToken[Id]]]
  def get(instanceId: Id, now: Instant): ZIO[Any, Throwable, Option[LockToken[Id]]]
```

### LockingStrategy

Mechanoid uses a strategy pattern for concurrency control. Choose the appropriate strategy for your deployment:

**Optimistic (default):**
```scala mdoc:compile-only
type OrderId = String
LockingStrategy.optimistic[OrderId]  // Relies on EventStore sequence conflict detection
```

**Distributed:**
```scala mdoc:compile-only
type OrderId = String
LockingStrategy.distributed[OrderId]  // Acquires exclusive lock before each transition
```

Use `LockingStrategy.distributed` for high-contention production deployments:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum OrderState derives Finite:
  case Pending, Paid, Shipped

enum OrderEvent derives Finite:
  case Pay, Ship

import OrderState.*, OrderEvent.*

val machine = Machine(assembly[OrderState, OrderEvent](
  Pending via Pay to Paid,
  Paid via Ship to Shipped,
))

type OrderId = String
val orderId: OrderId = "order-1"
val eventStoreLayer: zio.ULayer[EventStore[OrderId, OrderState, OrderEvent]] =
  InMemoryEventStore.layer[OrderId, OrderState, OrderEvent]
val lockServiceLayer: zio.ULayer[FSMInstanceLock[OrderId]] =
  ZLayer.fromZIO(InMemoryFSMInstanceLock.make[OrderId])
```

```scala mdoc:compile-only
val program = ZIO.scoped {
  for
    fsm <- FSMRuntime(orderId, machine, Pending)
    _   <- fsm.send(Pay)  // Lock acquired automatically before processing
  yield ()
}.provide(
  eventStoreLayer,
  lockServiceLayer,                     // FSMInstanceLock implementation
  TimeoutStrategy.fiber[OrderId],
  LockingStrategy.distributed[OrderId]  // Prevents concurrent modifications
)
```

### Lock Configuration

```scala mdoc:compile-only
val config = LockConfig()
  .withLockDuration(Duration.fromSeconds(30))    // How long to hold locks
  .withAcquireTimeout(Duration.fromSeconds(10))  // Max wait when acquiring
  .withRetryInterval(Duration.fromMillis(100))   // Retry frequency when busy
  .withValidateBeforeOperation(true)             // Double-check lock before each op
  .withNodeId("node-1")                          // Unique node identifier
```

**Preset configurations:**

```scala mdoc:compile-only
LockConfig.default      // 30s duration, 10s timeout
LockConfig.fast         // 10s duration, 5s timeout (for quick operations)
LockConfig.longRunning  // 5 min duration, 30s timeout (for batch jobs)
```

### Node Failure Resilience

Locks are **lease-based** and automatically expire. This handles several failure scenarios:

| Scenario | What Happens |
|----------|--------------|
| Node crash | Lock expires after `lockDuration`, other nodes proceed |
| Network partition | Same as crash - lock expires |
| Long GC pause | If pause exceeds `lockDuration`, lock expires |

**Zombie Node Protection:**

Even if a paused node wakes up after its lock expired:

1. **Lock validation**: If `validateBeforeOperation` is enabled, the node checks if it still holds the lock before writing
2. **EventStore optimistic locking**: Even if the zombie writes, `SequenceConflictError` is raised because another node already incremented the sequence number

**PostgreSQL Implementation:**

```sql
CREATE TABLE fsm_instance_locks (
  instance_id  TEXT PRIMARY KEY,
  node_id      TEXT NOT NULL,
  acquired_at  TIMESTAMPTZ NOT NULL,
  expires_at   TIMESTAMPTZ NOT NULL
);

-- Atomic acquire (succeeds if expired or same node)
INSERT INTO fsm_instance_locks (instance_id, node_id, acquired_at, expires_at)
VALUES ($1, $2, NOW(), NOW() + $3::interval)
ON CONFLICT (instance_id) DO UPDATE
  SET node_id = EXCLUDED.node_id,
      acquired_at = EXCLUDED.acquired_at,
      expires_at = EXCLUDED.expires_at
  WHERE fsm_instance_locks.expires_at < NOW()
     OR fsm_instance_locks.node_id = EXCLUDED.node_id
RETURNING *;
```

### Combining Features

For maximum robustness, combine distributed locking with durable timeouts:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum OrderState derives Finite:
  case Pending, Started, Done

enum OrderEvent derives Finite:
  case StartPayment, Complete

import OrderState.*, OrderEvent.*

val machine = Machine(assembly[OrderState, OrderEvent](
  Pending via StartPayment to Started,
  Started via Complete to Done,
))

type OrderId = String
val orderId: OrderId = "order-1"
val eventStoreLayer: zio.ULayer[EventStore[OrderId, OrderState, OrderEvent]] =
  InMemoryEventStore.layer[OrderId, OrderState, OrderEvent]
val timeoutStoreLayer: zio.ULayer[TimeoutStore[OrderId]] =
  ZLayer.fromZIO(InMemoryTimeoutStore.make[OrderId])
val lockServiceLayer: zio.ULayer[FSMInstanceLock[OrderId]] =
  ZLayer.fromZIO(InMemoryFSMInstanceLock.make[OrderId])
```

```scala mdoc:compile-only
val program = ZIO.scoped {
  for
    fsm <- FSMRuntime(orderId, machine, Pending)
    _   <- fsm.send(StartPayment)
    // - Lock ensures exactly-once processing
    // - Timeout persisted and survives node restart
  yield ()
}.provide(
  eventStoreLayer,
  timeoutStoreLayer,
  lockServiceLayer,
  TimeoutStrategy.durable[OrderId],       // Timeouts survive node failures
  LockingStrategy.distributed[OrderId]    // Prevents concurrent modifications
)
```

This provides:
- **Exactly-once transitions** via distributed locking
- **Durable timeouts** that survive node failures
- **Optimistic locking** as a final safety net (always active via EventStore)

---

## Lock Heartbeat and Atomic Transitions

### Automatic Lock Renewal

For operations that may take longer than the initial lock duration, use `withLockAndHeartbeat` which automatically renews the lock in the background:

```scala mdoc:compile-only
def processOrder(orderId: String): ZIO[Any, Nothing, Unit] = ZIO.unit

val lock: FSMInstanceLock[OrderId] = ???
val nodeId = "node-1"

val heartbeatConfig = LockHeartbeatConfig(
  renewalInterval = Duration.fromSeconds(10),
  renewalDuration = Duration.fromSeconds(30),
  jitterFactor = 0.1,
  onLockLost = LockLostBehavior.FailFast,
)

lock.withLockAndHeartbeat(orderId, nodeId, Duration.fromSeconds(30), heartbeat = heartbeatConfig) {
  // Long-running operation - lock is automatically renewed
  processOrder(orderId)
}
```

**Configuration:**

| Parameter | Description | Recommendation |
|-----------|-------------|----------------|
| `renewalInterval` | How often to renew | ≤ `renewalDuration / 3` |
| `renewalDuration` | Lock duration on each renewal | Same as or longer than initial duration |
| `jitterFactor` | Random jitter (0.0-1.0) | 0.1 to prevent thundering herd |
| `onLockLost` | Behavior when renewal fails | `FailFast` for safety |

### Lock Lost Behavior

When the heartbeat fails to renew the lock, there are two behaviors:

**FailFast (Default - Safe):**
```scala mdoc:compile-only
val behavior = LockLostBehavior.FailFast
```
The main effect is interrupted immediately. Use for non-idempotent operations where another node may have acquired the lock.

**Continue (Use with caution):**
```scala mdoc:compile-only
val behavior = LockLostBehavior.Continue(
  ZIO.logWarning("Lock lost but continuing...")
)
```
Runs the provided effect, then continues execution. Only use for idempotent operations where completing is more important than safety.

### Atomic Transitions

Use `withAtomicTransitions` on `LockedFSMRuntime` to execute multiple FSM transitions while holding a single lock with automatic renewal:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum OrderState derives Finite:
  case Pending, Validated, Approved, AutoApproved

  def needsApproval: Boolean = this == Validated

enum OrderEvent derives Finite:
  case ValidateOrder, RequestApproval, AutoApprove

import OrderState.*, OrderEvent.*

val machine = Machine(assembly[OrderState, OrderEvent](
  Pending via ValidateOrder to Validated,
  Validated via RequestApproval to Approved,
  Validated via AutoApprove to AutoApproved,
))

type OrderId = String
val orderId: OrderId = "order-1"
val eventStoreLayer: zio.ULayer[EventStore[OrderId, OrderState, OrderEvent]] =
  InMemoryEventStore.layer[OrderId, OrderState, OrderEvent]
val lockServiceLayer: zio.ULayer[FSMInstanceLock[OrderId]] =
  ZLayer.fromZIO(InMemoryFSMInstanceLock.make[OrderId])
```

```scala mdoc:compile-only
val program = ZIO.scoped {
  for
    fsm <- FSMRuntime(orderId, machine, Pending)
    lock <- ZIO.service[FSMInstanceLock[OrderId]]
    lockedFsm = LockedFSMRuntime(fsm, lock)
    _ <- lockedFsm.withAtomicTransitions() { ctx =>
      for
        outcome1 <- ctx.send(ValidateOrder)      // First transition
        state    <- ctx.currentState
        _        <- if state.current.needsApproval
                    then ctx.send(RequestApproval) // Conditional transition
                    else ctx.send(AutoApprove)     // Alternative transition
      yield ()
    }
  yield ()
  // Side effects from .producing are handled asynchronously as fire-and-forget
}.provide(
  eventStoreLayer,
  lockServiceLayer,
  TimeoutStrategy.fiber[OrderId],
  LockingStrategy.distributed[OrderId]
)
```

**When to Use:**
- Conditional transitions based on intermediate state
- Saga-like patterns where multiple events form one logical operation
- Reading state between transitions for branching logic

### Anti-Patterns to Avoid

**❌ WRONG - Don't do long-running work inside atomic transactions:**
```scala mdoc:compile-only
def callExternalPaymentAPI(): ZIO[Any, Nothing, Unit] = ZIO.unit

val badProgram = ZIO.scoped {
  for
    fsm <- FSMRuntime(orderId, machine, Pending)
    lock <- ZIO.service[FSMInstanceLock[OrderId]]
    lockedFsm = LockedFSMRuntime(fsm, lock)
    _ <- lockedFsm.withAtomicTransitions() { ctx =>
      for
        _ <- ctx.send(ValidateOrder)
        _ <- callExternalPaymentAPI()  // ❌ Should use .producing instead!
        _ <- ctx.send(RequestApproval)
      yield ()
    }
  yield ()
}.provide(
  eventStoreLayer,
  lockServiceLayer,
  TimeoutStrategy.fiber[OrderId],
  LockingStrategy.distributed[OrderId]
)
```

**✅ RIGHT - Fast orchestration with side effects via .producing:**
```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum OrderState derives Finite:
  case Processing, AwaitingResult, Succeeded, Failed

enum OrderEvent derives Finite:
  case CheckStatus
  case PaymentSucceeded(txnId: String)
  case PaymentFailed(reason: String)

import OrderState.*, OrderEvent.*

case class PaymentResult(success: Boolean, txnId: String, reason: String)
def callExternalPaymentAPI(): ZIO[Any, Nothing, PaymentResult] =
  ZIO.succeed(PaymentResult(true, "txn-123", ""))
```

```scala mdoc:compile-only
// Define transition with producing effect
val machine = Machine(assembly[OrderState, OrderEvent](
  (Processing via CheckStatus to AwaitingResult)
    .producing { (_, _) =>
      // This runs asynchronously after the transition
      callExternalPaymentAPI().map {
        case PaymentResult(true, txnId, _) => PaymentSucceeded(txnId)
        case PaymentResult(false, _, reason) => PaymentFailed(reason)
      }
    },
  AwaitingResult via event[PaymentSucceeded] to Succeeded,
  AwaitingResult via event[PaymentFailed] to Failed,
))

// In atomic transaction, just send the event - side effect runs asynchronously
val goodProgram = ZIO.scoped {
  for
    fsm <- machine.start(Processing)
    _ <- fsm.send(CheckStatus)
    // Lock released quickly, external API call runs in background
  yield ()
}
```

### Best Practices for Side Effects

Lock heartbeat and atomic transitions are for **fast orchestration** - quickly deciding what needs to happen and sending events. Long-running work should be handled via `.producing` effects:

| Concern | Handled By |
|---------|------------|
| Multiple FSM transitions atomically | `withAtomicTransitions` |
| Long-running external calls | `.producing` effects (fire-and-forget) |
| Synchronous logging/metrics | `.onEntry` effects |
| Lock renewal during orchestration | `LockHeartbeatConfig` |

This separation provides:
- **Fast lock release** - Locks are held only for state changes, not I/O
- **Non-blocking side effects** - `.producing` effects run as daemon fibers
- **Self-driving FSMs** - Produced events automatically sent back to FSM

---

## Side Effects

Mechanoid provides two mechanisms for executing side effects when transitions occur.

### Synchronous Entry Effects

Use `.onEntry` for side effects that should run synchronously when a transition fires:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum OrderState derives Finite:
  case Created, Processing

enum OrderEvent derives Finite:
  case StartPayment

import OrderState.*, OrderEvent.*
```

```scala mdoc:compile-only
val machine = Machine(assembly[OrderState, OrderEvent](
  (Created via StartPayment to Processing)
    .onEntry { (event, targetState) =>
      ZIO.logInfo(s"Starting payment processing for $event")
    },
))
```

Entry effects:
- Receive `(event: E, targetState: S)`
- Run synchronously during `send()`
- Failures cause `ActionFailedError` and the transition is NOT persisted
- Use for: logging, metrics, validation, quick synchronous operations

### Producing Effects

Use `.producing` for async operations that produce events to send back to the FSM:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum OrderState derives Finite:
  case Processing, AwaitingResult, Succeeded, Failed

enum OrderEvent derives Finite:
  case CheckPayment(orderId: String)
  case PaymentSucceeded(txnId: String)
  case PaymentFailed(message: String)

import OrderState.*, OrderEvent.*

case class PaymentStatus(success: Boolean, txnId: String, message: String)
object paymentService:
  def checkStatus(orderId: String): ZIO[Any, Nothing, PaymentStatus] =
    ZIO.succeed(PaymentStatus(true, "txn-123", ""))
```

```scala mdoc:compile-only
val machine = Machine(assembly[OrderState, OrderEvent](
  (Processing via event[CheckPayment] to AwaitingResult)
    .producing { (event, targetState) =>
      event match
        case CheckPayment(orderId) =>
          paymentService.checkStatus(orderId).map {
            case PaymentStatus(true, txnId, _) => PaymentSucceeded(txnId)
            case PaymentStatus(false, _, msg) => PaymentFailed(msg)
          }
        case _ => ZIO.succeed(PaymentFailed("unexpected event"))
    },
  AwaitingResult via event[PaymentSucceeded] to Succeeded,
  AwaitingResult via event[PaymentFailed] to Failed,
))
```

Producing effects:
- Receive `(event: E, targetState: S)`
- Return `ZIO[Any, Any, E2]` where `E2` is an event type
- Fork as daemon fiber (fire-and-forget)
- Produced event is automatically sent to the FSM
- Errors are logged but don't fail the original transition
- Use for: external API calls, async processing, health checks

### Fault-Tolerant Patterns

Combine `.producing` with timeouts for self-healing FSMs:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum ServiceState derives Finite:
  case Stopped, Started, Degraded, Critical

enum ServiceEvent derives Finite:
  case Start, Stop, Heartbeat, DegradedCheck, ManualReset
  case Healthy, Unstable, Failed

import ServiceState.*, ServiceEvent.*

object HealthChecker:
  def normalCheck: ZIO[Any, Nothing, ServiceEvent] = ZIO.succeed(Healthy)
```

```scala mdoc:silent
val machine = Machine(assemblyAll[ServiceState, ServiceEvent]:
  // Start the service
  Stopped via Start to Started

  // Normal operation: heartbeat fires every 10s, triggers health check
  (Started via Heartbeat to Started)
    .onEntry { (_, _) => ZIO.logInfo("Running health check...") }
    .producing { (_, _) => HealthChecker.normalCheck }  // Returns Healthy/Unstable/Failed
    @@ Aspect.timeout(10.seconds, Heartbeat)

  // Healthy → stay started, reset timeout
  (Started via Healthy to Started)
    .onEntry { (_, _) => ZIO.logInfo("Health check: HEALTHY") }
    @@ Aspect.timeout(10.seconds, Heartbeat)

  // Unstable → enter degraded mode with faster checks (3s)
  (Started via Unstable to Degraded)
    .onEntry { (_, _) => ZIO.logWarning("Entering degraded mode") }
    @@ Aspect.timeout(3.seconds, DegradedCheck)

  // Failed → critical state, wait for human intervention
  (Started via Failed to Critical)
    .onEntry { (_, _) => ZIO.logError("Awaiting intervention") }
    @@ Aspect.timeout(15.seconds, ManualReset)
)
```

**Why this works:**
- Timeouts are durable (survive node restarts with `TimeoutStrategy.durable`)
- Health checks are fire-and-forget (don't block transition)
- Produced events drive state machine forward
- Different states = different monitoring intensity
- No external command system needed - all orchestration via events

**Production setup:**
```scala mdoc:compile-only
type InstanceId = String
val instanceId: InstanceId = "service-1"
val eventStoreLayer: zio.ULayer[EventStore[InstanceId, ServiceState, ServiceEvent]] =
  InMemoryEventStore.layer[InstanceId, ServiceState, ServiceEvent]
val timeoutStoreLayer: zio.ULayer[TimeoutStore[InstanceId]] =
  ZLayer.fromZIO(InMemoryTimeoutStore.make[InstanceId])
val config = TimeoutSweeperConfig()

val program = ZIO.scoped {
  for
    timeoutStore <- ZIO.service[TimeoutStore[InstanceId]]
    fsm <- FSMRuntime(instanceId, machine, ServiceState.Stopped)
    sweeper <- TimeoutSweeper.make(config, timeoutStore, fsm)
    _ <- fsm.send(ServiceEvent.Start)
    _ <- ZIO.never  // Keep running
  yield ()
}.provide(
  eventStoreLayer,
  timeoutStoreLayer,
  TimeoutStrategy.durable[InstanceId],
  LockingStrategy.optimistic[InstanceId]
)
```

See the `examples/heartbeat` project for a complete working example.

### Per-State Lifecycle Actions

For effects that should run for ALL transitions entering or exiting a state (not per-transition), use `withEntry` and `withExit` on Machine:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum MyState derives Finite:
  case Idle, Running, Done

enum MyEvent derives Finite:
  case Start, Finish

import MyState.*, MyEvent.*
```

```scala mdoc:compile-only
val machine = Machine(assembly[MyState, MyEvent](
  Idle via Start to Running,
  Running via Finish to Done,
)).withEntry(Running)(ZIO.logInfo("Entered Running"))
  .withExit(Running)(ZIO.logInfo("Exiting Running"))
```

You can also use `withLifecycle` to set both:

```scala mdoc:compile-only
val machine = Machine(assembly[MyState, MyEvent](
  Idle via Start to Running,
  Running via Finish to Done,
)).withLifecycle(Running)(
  onEntry = Some(ZIO.logInfo("Entering Running")),
  onExit = Some(ZIO.logInfo("Exiting Running"))
)
```

---

## Visualization

Mechanoid provides built-in visualization tools to generate diagrams of your FSM structure and execution traces. These visualizations are invaluable for:

- **Documentation**: Auto-generate up-to-date FSM diagrams
- **Debugging**: Visualize execution traces to understand state transitions
- **Communication**: Share FSM designs with stakeholders using familiar diagram formats

### Visualization Overview

Two visualizers are available:

| Visualizer | Output Format | Best For |
|------------|---------------|----------|
| `MermaidVisualizer` | Mermaid markdown | GitHub/GitLab READMEs, documentation sites |
| `GraphVizVisualizer` | DOT format | High-quality rendered images, complex diagrams |

Both visualizers work with any FSM definition.

### MermaidVisualizer

Generate [Mermaid](https://mermaid.js.org/) diagrams that render directly in GitHub, GitLab, and many documentation tools.

#### Extension Methods

FSM definitions have extension methods for convenient visualization:

```scala mdoc:reset:silent
import mechanoid.*
import zio.*

enum OrderState derives Finite:
  case Created, Processing, Completed

enum OrderEvent derives Finite:
  case Start, Finish

import OrderState.*, OrderEvent.*

val machine = Machine(assembly[OrderState, OrderEvent](
  Created via Start to Processing,
  Processing via Finish to Completed,
))

val trace: ExecutionTrace[OrderState, OrderEvent] = ExecutionTrace.empty("instance-1", Created)
```

```scala mdoc:compile-only
// State diagram using extension method
val diagram = machine.toMermaidStateDiagram(Some(OrderState.Created))

// Flowchart
val flowchart = machine.toMermaidFlowchart

// With execution trace highlighting
val highlighted = machine.toMermaidFlowchartWithTrace(trace)

// GraphViz
val dot = machine.toGraphViz(name = "OrderFSM", initialState = Some(OrderState.Created))
```

Execution traces also have extension methods:

```scala mdoc:compile-only
val sequenceDiagram = trace.toMermaidSequenceDiagram
val timeline = trace.toGraphVizTimeline
```

#### State Diagram (Static Methods)

Shows the FSM structure with all states and transitions:

```scala mdoc:compile-only
// Basic state diagram using static method
val diagram = MermaidVisualizer.stateDiagram(
  fsm = machine,
  initialState = Some(OrderState.Created)
)

// Output:
// stateDiagram-v2
//     [*] --> Created
//     Created --> PaymentProcessing: InitiatePayment
//     PaymentProcessing --> Paid: PaymentSucceeded
//     ...
```

#### Sequence Diagram

Shows an execution trace as a sequence of state transitions:

```scala mdoc:compile-only
val sequenceDiagram = MermaidVisualizer.sequenceDiagram(
  trace = trace,
  stateEnum = summon[Finite[OrderState]],
  eventEnum = summon[Finite[OrderEvent]]
)
```

#### Flowchart

Shows the FSM as a flowchart with highlighted execution path:

```scala mdoc:compile-only
val flowchart = MermaidVisualizer.flowchart(
  fsm = machine,
  trace = Some(trace)  // Optional: highlights visited states
)
```

### GraphVizVisualizer

Generate [GraphViz DOT](https://graphviz.org/) format for high-quality rendered diagrams.

```scala mdoc:compile-only
// Basic digraph
val dot = GraphVizVisualizer.digraph(
  fsm = machine,
  initialState = Some(OrderState.Created)
)

// Output:
// digraph FSM {
//     rankdir=LR;
//     node [shape=ellipse];
//     Created -> PaymentProcessing [label="InitiatePayment"];
//     ...
// }

// With execution trace highlighting
val dotWithTrace = GraphVizVisualizer.digraphWithTrace(
  fsm = machine,
  trace = trace
)
```

Render the DOT output using GraphViz tools:

```bash
# Generate PNG
dot -Tpng fsm.dot -o fsm.png

# Generate SVG
dot -Tsvg fsm.dot -o fsm.svg
```

### Generating Visualizations

Here's a complete example that generates all visualization types:

```scala mdoc:compile-only
import java.nio.file.{Files, Paths}

def generateVisualizations[S, E](
    machine: Machine[S, E],
    initialState: S,
    outputDir: String
)(using Finite[S], Finite[E]): ZIO[Any, Throwable, Unit] =
  for
    _ <- ZIO.attempt(Files.createDirectories(Paths.get(outputDir)))

    // Generate FSM structure diagram
    structureMd = s"""# FSM Structure
                     |
                     |## State Diagram
                     |
                     |```mermaid
                     |${MermaidVisualizer.stateDiagram(machine, Some(initialState))}
                     |```
                     |
                     |## Flowchart
                     |
                     |```mermaid
                     |${MermaidVisualizer.flowchart(machine)}
                     |```
                     |
                     |## GraphViz
                     |
                     |```dot
                     |${GraphVizVisualizer.digraph(machine, initialState = Some(initialState))}
                     |```
                     |""".stripMargin

    _ <- ZIO.attempt(
      Files.writeString(Paths.get(s"$outputDir/fsm-structure.md"), structureMd)
    )
  yield ()
```

### Example Outputs

See the [visualizations directory](visualizations/) for complete examples:

- [Order FSM Structure](visualizations/order-fsm-structure.md) - FSM definition with state diagram, flowchart, and GraphViz
- [Order 1 Trace](visualizations/order-1-trace.md) - Successful order execution trace
- [Order 5 Trace](visualizations/order-5-trace.md) - Failed order (payment declined) trace

---

## Error Handling

| Error | Cause |
|-------|-------|
| `InvalidTransitionError(state, event)` | No transition defined for state/event combination |
| `FSMStoppedError(reason)` | FSM has been stopped |
| `ProcessingTimeoutError(state, duration)` | Timeout during event processing |
| `ActionFailedError(cause)` | User-defined error from lifecycle action |
| `PersistenceError(cause)` | Persistence operation failed |
| `SequenceConflictError(expected, actual, instanceId)` | Concurrent modification detected |
| `EventReplayError(state, event, sequenceNr)` | Stored event doesn't match FSM definition |
| `LockingError(cause)` | Distributed lock operation failed (busy, timeout, etc.) |

---

## Complete Example

```scala mdoc:reset:silent
import mechanoid.*
import zio.*
import java.time.Instant

// Domain - plain enums with Finite derivation
enum OrderState derives Finite:
  case Pending, AwaitingPayment, Paid, Shipped, Delivered, Cancelled

enum OrderEvent derives Finite:
  case Create, RequestPayment, ConfirmPayment, Ship, Deliver, Cancel, PaymentTimeout

import OrderState.*, OrderEvent.*

// Simulated services
object PaymentService:
  def charge(amount: BigDecimal): ZIO[Any, Nothing, Unit] =
    ZIO.logInfo(s"Charging $$${amount}")

object WarehouseService:
  def notify(orderId: String): ZIO[Any, Nothing, Unit] =
    ZIO.logInfo(s"Notifying warehouse for order $orderId")

object EmailService:
  def send(to: String, template: String): ZIO[Any, Nothing, Unit] =
    ZIO.logInfo(s"Sending $template email to $to")
```

```scala mdoc:compile-only
// FSM Definition with side effects
val orderMachine = Machine(assemblyAll[OrderState, OrderEvent]:
  // Happy path with timeout on entry to AwaitingPayment
  (Pending via RequestPayment to AwaitingPayment) @@ Aspect.timeout(30.minutes, PaymentTimeout)

  (AwaitingPayment via ConfirmPayment to Paid)
    .onEntry { (_, _) =>
      PaymentService.charge(BigDecimal(100))
    }

  (Paid via Ship to Shipped)
    .onEntry { (_, _) =>
      WarehouseService.notify("order-123")
    }

  (Shipped via Deliver to Delivered)
    .onEntry { (_, _) =>
      EmailService.send("customer@example.com", "delivered")
    }

  // Timeout handling
  AwaitingPayment via PaymentTimeout to Cancelled

  // Cancellation from multiple states
  anyOf(Pending, AwaitingPayment) via Cancel to Cancelled
)

// Add lifecycle actions for all transitions to/from a state
val machineWithActions = orderMachine
  .withEntry(AwaitingPayment)(ZIO.logInfo("Waiting for payment..."))
  .withEntry(Cancelled)(ZIO.logInfo("Order cancelled"))

// Running with persistence and durable timeouts
type OrderId = String
val eventStoreLayer: zio.ULayer[EventStore[OrderId, OrderState, OrderEvent]] =
  InMemoryEventStore.layer[OrderId, OrderState, OrderEvent]
val timeoutStoreLayer: zio.ULayer[TimeoutStore[OrderId]] =
  ZLayer.fromZIO(InMemoryTimeoutStore.make[OrderId])

val program = ZIO.scoped {
  for
    // Create FSM - strategies are provided via ZIO environment
    fsm <- FSMRuntime("order-123", machineWithActions, OrderState.Pending)

    // Process order
    _ <- fsm.send(OrderEvent.RequestPayment)

    // Wait for payment (will timeout after 30 minutes if not received)
    // Even if this node crashes, another node's sweeper will fire the timeout

    // Check current state
    state <- fsm.currentState
    _ <- ZIO.logInfo(s"Current state: $state")

  yield ()
}.provide(
  eventStoreLayer,
  timeoutStoreLayer,
  TimeoutStrategy.durable[String],      // Durable timeouts survive node failures
  LockingStrategy.optimistic[String]    // Or use LockingStrategy.distributed for high contention
)

// For long-running processes with sweeper, run alongside FSMRuntime:
// See examples/heartbeat for a complete working example
```

---

## Dependencies

```scala
libraryDependencies ++= Seq(
  "dev.zio" %% "zio"         % "2.1.24",
  "dev.zio" %% "zio-streams" % "2.1.24",
  "dev.zio" %% "zio-json"    % "0.7.42"  // For JSON serialization in persistence
)
```

For PostgreSQL persistence with [Saferis](https://github.com/russwyte/saferis):

```scala
libraryDependencies ++= Seq(
  "io.github.russwyte" %% "saferis"       % "0.1.1",
  "org.postgresql"      % "postgresql"    % "42.7.8"
)
```
