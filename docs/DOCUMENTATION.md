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
  - [The buildAll Block Syntax](#the-buildall-block-syntax)
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
- [Distributed Locking](#distributed-locking)
  - [Why Use Locking](#why-use-locking)
  - [FSMInstanceLock](#fsminstancelock)
  - [Lock Configuration](#lock-configuration)
  - [Node Failure Resilience](#node-failure-resilience)
  - [Combining Features](#combining-features)
- [Command Pattern](#command-pattern)
  - [Declarative Commands with emitting](#declarative-commands-with-emitting)
  - [Transactional Outbox Pattern](#transactional-outbox-pattern)
  - [CommandStore Interface](#commandstore-interface)
  - [CommandWorker](#commandworker)
  - [Retry Policies](#retry-policies)
- [Visualization](#visualization)
  - [Overview](#visualization-overview)
  - [MermaidVisualizer](#mermaidvisualizer)
  - [GraphVizVisualizer](#graphvizvisualizer)
  - [CommandVisualizer](#commandvisualizer)
  - [Generating Visualizations](#generating-visualizations)
- [Type-Level Safety](#type-level-safety)
  - [ValidTransition](#validtransition)
  - [TransitionSpec](#transitionspec)
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

```scala
import mechanoid.*
import zio.*

// Define states and events as plain enums
enum OrderState:
  case Pending, Paid, Shipped, Delivered

enum OrderEvent:
  case Pay, Ship, Deliver

// Build FSM with clean infix syntax
val orderMachine = build(
  Pending via Pay to Paid,
  Paid via Ship to Shipped,
  Shipped via Deliver to Delivered,
)

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

```scala
enum TrafficLight:
  case Red, Yellow, Green
```

States can also carry data (rich states):

```scala
enum OrderState:
  case Pending
  case Paid(transactionId: String)
  case Failed(reason: String)
```

When defining transitions, the state's "shape" (which case it is) is used for matching, not the exact value. This means a transition from `Failed` will match ANY `Failed(_)` state.

#### Hierarchical States

For complex domains, organize related states using sealed traits:

```scala
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

```scala
// All Processing states can be cancelled
all[Processing] via Cancel to Cancelled
```

Use `anyOf(...)` for specific states that don't share a common parent:

```scala
// These specific states can be archived
anyOf(Created, Completed) via Archive to Archived
```

### Events

Events trigger transitions between states. Define them as plain enums:

```scala
enum TrafficEvent:
  case Timer, EmergencyOverride
```

**Events with data:**

```scala
enum PaymentEvent:
  case Pay(amount: BigDecimal)
  case Refund(orderId: String, amount: BigDecimal)
```

**Event hierarchies:**

Like states, events can be organized hierarchically:

```scala
sealed trait UserEvent
sealed trait InputEvent extends UserEvent
case object Click extends InputEvent
case object Tap extends InputEvent
case object Swipe extends InputEvent
```

#### Multi-Event Transitions

Use `event[T]` to match events by type (useful for events with data):

```scala
// Match any Pay event, regardless of amount
Pending via event[Pay] to Processing
```

Use `anyOfEvents(...)` for specific events:

```scala
// Multiple events trigger same transition
Idle via anyOfEvents(Click, Tap, Swipe) to Active
```

### Transitions

Transitions define what happens when an event is received in a specific state. Use the clean infix syntax:

```scala
// Simple transition: Pending + Pay -> Paid
Pending via Pay to Paid

// Stay in current state
Pending via Heartbeat to stay

// Stop the FSM
Failed via Shutdown to stop
```

**TransitionResult** represents the outcome:

| Result | Description |
|--------|-------------|
| `Goto(state)` | Transition to a new state |
| `Stay` | Remain in current state |
| `Stop(reason)` | Terminate the FSM |

### FSM State Container

`FSMState[S]` holds runtime information about the FSM:

```scala
fsm.state.map { s =>
  s.current            // Current state
  s.history            // List of previous states (most recent first)
  s.stateData          // Arbitrary key-value data
  s.startedAt          // When FSM was created
  s.lastTransitionAt   // When last transition occurred
  s.transitionCount    // Number of transitions
  s.previousState      // Option of previous state
}
```

---

## Defining FSMs

### Basic Definition

Use the `build` function to create FSM definitions with clean infix syntax:

```scala
import mechanoid.*

val machine = build(
  State1 via Event1 to State2,
  State1 via Event2 to stay,
  State2 via Event3 to State3,
)
```

Type parameters are inferred from the transitions. If you need to specify them explicitly:

```scala
val machine = build[MyState, MyEvent](
  State1 via Event1 to State2,
)
```

**Compile-time Validation:**

The `build` macro detects duplicate transitions at compile time:

```scala
// This will fail at compile time:
val bad = build(
  State1 via Event1 to State2,
  State1 via Event1 to State3,  // Error: Duplicate transition
)
```

To intentionally override a transition (e.g., after using `all[T]`), use `@@ Aspect.overriding`:

```scala
val machine = build(
  all[Processing] via Cancel to Cancelled,
  (SpecialState via Cancel to Special) @@ Aspect.overriding,  // Intentional override
)
```

When overrides are detected, the compiler emits informational messages showing which transitions are being overridden.

### The buildAll Block Syntax

For more complex definitions with local helper values, use `buildAll`:

```scala
val machine = buildAll[OrderState, OrderEvent]:
  // Local helper vals at the top
  val buildPaymentCommand: (OrderEvent, OrderState) => List[Command] = { (event, _) =>
    event match
      case e: InitiatePayment => List(ProcessPayment(e.orderId, e.amount))
      case _ => Nil
  }

  // Transitions use the helpers
  Created via event[InitiatePayment] to PaymentProcessing emitting buildPaymentCommand
  PaymentProcessing via event[PaymentSucceeded] to Paid
  PaymentProcessing via event[PaymentFailed] to Cancelled
```

The `buildAll` block allows mixing val definitions with transition expressions. The vals are available for use in emitting functions and other parts of the definition.

**Important:** When including val references to `Assembly` or `TransitionSpec` in a `buildAll` block, use the `include()` wrapper to avoid compiler warnings:

```scala
val baseAssembly = assembly[OrderState, OrderEvent](
  Pending via Pay to Paid,
)

val fullMachine = buildAll[OrderState, OrderEvent]:
  include(baseAssembly)  // Use include() for val references
  Paid via Ship to Shipped
```

### Entry and Exit Actions

Define actions that run when entering or leaving a state using the `withEntry` and `withExit` methods on `Machine`:

```scala
val machine = build(
  Idle via Start to Running,
  Running via Stop to Idle,
).withEntry(Running)(ZIO.logInfo("Entered Running state"))
 .withExit(Running)(ZIO.logInfo("Exiting Running state"))
```

### Timeouts

Mechanoid provides a flexible timeout strategy where you define your own timeout events. This gives you complete control over timeout handling and enables powerful patterns.

#### Basic Timeout Usage

```scala
import scala.concurrent.duration.*

enum OrderEvent:
  case Pay, PaymentTimeout  // User-defined timeout event

// Create a timed target - when entering this state, a timeout is scheduled
val timedWaiting = WaitingForPayment @@ timeout(30.minutes, PaymentTimeout)

val machine = build(
  Created via Pay to timedWaiting,           // Enter timed state
  WaitingForPayment via PaymentTimeout to Cancelled,  // Handle timeout
  WaitingForPayment via Paid to Confirmed,   // Or complete before timeout
)
```

The `@@ timeout(duration, event)` syntax creates a `TimedTarget` that:
1. Schedules a timeout when the FSM enters the state
2. Fires the specified event when the timeout expires
3. Cancels the timeout if another event is processed first

#### Multiple Timeout Events

A key feature is that **different states can use different timeout events**. This enables rich timeout handling:

```scala
enum OrderState:
  case Created, PaymentPending, ShipmentPending, Delivered

enum OrderEvent:
  case Pay, Ship, Deliver
  case PaymentTimeout     // Fired after 30 minutes in PaymentPending
  case ShipmentTimeout    // Fired after 7 days in ShipmentPending

val timedPayment = PaymentPending @@ timeout(30.minutes, PaymentTimeout)
val timedShipment = ShipmentPending @@ timeout(7.days, ShipmentTimeout)

val machine = build(
  Created via Pay to timedPayment,
  PaymentPending via PaymentTimeout to Cancelled,      // Cancel if payment times out
  PaymentPending via Confirm to timedShipment,
  ShipmentPending via ShipmentTimeout to Refunded,     // Refund if shipment times out
  ShipmentPending via Ship to Delivered,
)
```

#### Timeout Events with Data

Since timeout events are regular events in your enum, they can carry data:

```scala
enum SessionEvent:
  case Action(userId: String)
  case IdleTimeout(lastActivity: Instant)  // Carries the last activity time
  case AbsoluteTimeout(sessionStart: Instant)  // Carries session start time

// Different timeouts with different data
val idleTimedSession = Active @@ timeout(15.minutes, IdleTimeout(Instant.now()))
val absoluteTimedSession = Active @@ timeout(8.hours, AbsoluteTimeout(Instant.now()))
```

#### Different Outcomes for Same State

You can have multiple timeout types affecting the same state with different outcomes:

```scala
enum AuctionState:
  case Bidding, Extended, Sold, Expired

enum AuctionEvent:
  case Bid(amount: BigDecimal)
  case ExtensionTimeout   // Short timeout - extends auction on late bids
  case FinalTimeout       // Long timeout - auction ends

// Start with extension timeout (5 minutes)
val biddingWithExtension = Bidding @@ timeout(5.minutes, ExtensionTimeout)

// After extension, use final timeout (1 minute)
val extendedBidding = Extended @@ timeout(1.minute, FinalTimeout)

val machine = build(
  Pending via StartAuction to biddingWithExtension,

  // Late bid extends the auction
  Bidding via event[Bid] to biddingWithExtension,  // Reset 5-minute timer
  Bidding via ExtensionTimeout to extendedBidding, // Move to final phase

  // Final phase
  Extended via event[Bid] to extendedBidding,      // Reset 1-minute timer
  Extended via FinalTimeout to Sold,               // Auction ends
)
```

#### Why User-Defined Timeout Events?

This design provides several advantages over a built-in `Timeout` singleton:

1. **Type safety** - Different timeouts are distinct types, preventing mix-ups
2. **Rich handling** - Each timeout can trigger different transitions and commands
3. **Data carrying** - Timeout events can include context (timestamps, reason codes)
4. **Clear intent** - Reading `PaymentTimeout` is clearer than `Timeout`
5. **Event sourcing** - All timeout events are persisted like regular events

### Assembly Composition

Use `assembly` to create reusable transition fragments that can be composed with full compile-time validation:

```scala
// Reusable behaviors defined as assemblies
val cancelableBehaviors = assembly[DocumentState, DocumentEvent](
  all[InReview] via CancelReview to Draft,
  all[Approval] via Abandon to Cancelled,
)

// Compose assemblies with specific transitions
val fullWorkflow = buildAll[DocumentState, DocumentEvent]:
  include(cancelableBehaviors)  // Include all transitions from assembly

  Draft via SubmitForReview to PendingReview
  PendingReview via AssignReviewer to UnderReview
```

**Key difference between `assembly` and `build`:**
- `assembly` creates a reusable fragment that **cannot be run** directly
- `build` creates a complete `Machine` that **can be run**

**Duplicate Detection:**

Mechanoid detects duplicate transitions (same state + event combination) at compile time in all scenarios:

| Scenario | Detection | When |
|----------|-----------|------|
| Inline specs (`A via E to B`) | **Compile time** | Macro can inspect AST |
| Same val used twice (`build(t1, t1)`) | **Compile time** | Symbol tracking |
| Assembly composition (`build(myAssembly, ...)`) | **Compile time** | Assembly specs are extracted at macro expansion |

**Two-Level Validation:**

1. **Level 1 (Assembly scope)**: Duplicates within a single `assembly()` call are detected
2. **Level 2 (Build scope)**: Duplicates across multiple assemblies and inline specs are detected

```scala
// Level 1 - Compile ERROR within assembly
val bad = assembly[S, E](
  A via E1 to B,
  A via E1 to C,  // Compile ERROR: duplicate transition
)

// Level 2 - Compile ERROR across assemblies
val a1 = assembly[S, E](A via E1 to B)
val a2 = assembly[S, E](A via E1 to C)
val machine = build[S, E](a1, a2)  // Compile ERROR: duplicate A via E1

// Use @@ Aspect.overriding to allow intentional overrides
val machine = build[S, E](
  a1,
  a2 @@ Aspect.overriding,  // OK: a2's transitions override a1's
)

// Or override at the spec level
val machine = build[S, E](
  a1,
  (A via E1 to C) @@ Aspect.overriding,  // OK: inline override wins
)
```

When overrides are detected, the compiler emits informational messages showing which transitions are being overridden.

---

## Running FSMs

Mechanoid provides a unified `FSMRuntime[Id, S, E, Cmd]` interface for all FSM execution scenarios. The runtime has four type parameters:

- `Id` - The instance identifier type (`Unit` for simple FSMs, or a custom type like `String` or `UUID` for persistent FSMs)
- `S` - The state type (sealed enum or sealed trait)
- `E` - The event type (sealed enum or sealed trait)
- `Cmd` - The command type (`Nothing` for FSMs without commands)

### Simple Runtime

For simple, single-instance FSMs without persistence, use `machine.start(initialState)`:

```scala
val program = ZIO.scoped {
  for
    fsm <- machine.start(initialState)  // Returns FSMRuntime[Unit, S, E, Cmd]
    // Use the FSM...
  yield result
}
```

This creates an in-memory FSM with `Unit` as the instance ID. The FSM is automatically stopped when the scope closes.

### Persistent Runtime

For persistent, identified FSMs, use `FSMRuntime.apply`:

```scala
import mechanoid.runtime.FSMRuntime

val program = ZIO.scoped {
  for
    fsm <- FSMRuntime(orderId, machine, initialState)  // Returns FSMRuntime[String, S, E, Cmd]
    // Use the FSM...
  yield result
}.provide(eventStoreLayer)
```

The persistent runtime requires an `EventStore` in the environment and uses the provided ID to identify the FSM instance.

### Sending Events

```scala
for
  outcome <- fsm.send(MyEvent)
yield outcome.result match
  case TransitionResult.Goto(newState) => s"Transitioned to $newState"
  case TransitionResult.Stay           => "Stayed in current state"
  case TransitionResult.Stop(reason)   => s"Stopped: $reason"
```

The `TransitionOutcome` also includes any commands generated:
- `outcome.preCommands` - Commands generated before state change
- `outcome.postCommands` - Commands generated after state change

Possible errors:
- `InvalidTransitionError` - No transition defined for state/event

---

## Persistence

### Event Sourcing Model

Mechanoid supports event sourcing for durable FSMs:

1. Events are persisted *after* the transition action succeeds
2. State is reconstructed by replaying events
3. Snapshots reduce recovery time

```scala
import mechanoid.runtime.FSMRuntime

val program = ZIO.scoped {
  for
    fsm <- FSMRuntime(orderId, machine, Pending)
    _   <- fsm.send(Pay)    // Event persisted
    _   <- fsm.send(Ship)   // Event persisted
  yield ()
}.provide(eventStoreLayer)
```


### EventStore Interface

Implement `EventStore[Id, S, E]` for your storage backend:

```scala
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

```scala
// Manual snapshot (you control when)
_ <- fsm.saveSnapshot

// Example strategies:
// After every N events
seqNr <- fsm.lastSequenceNr
_ <- ZIO.when(seqNr % 100 == 0)(fsm.saveSnapshot)

// Periodically
fsm.saveSnapshot.repeat(Schedule.fixed(5.minutes)).forkDaemon

// On specific states
state <- fsm.currentState
_ <- ZIO.when(state == Completed)(fsm.saveSnapshot)
```

### Recovery

On startup, `FSMRuntime` (when provided with an EventStore):

1. Loads the latest snapshot (if any)
2. Replays only events *after* the snapshot's sequence number
3. Resumes normal operation

Recovery time is proportional to events since the last snapshot, not total events.

### Optimistic Locking

The persistence layer uses optimistic locking to detect concurrent modifications:

```scala
// If another process modified the FSM between read and write:
SequenceConflictError(expected = 5, actual = 6, instanceId = orderId)
```

This error indicates a concurrent modification. The caller should reload state and retry.

---

## Durable Timeouts

### The Problem

In-memory timeouts (fiber-based) don't survive node failures. If a node crashes while an FSM is in a timed state, the timeout never fires.

### TimeoutStore

Persist timeout deadlines to a database:

```scala
trait TimeoutStore[Id]:
  def schedule(instanceId: Id, state: String, deadline: Instant): ZIO[Any, Throwable, ScheduledTimeout[Id]]
  def cancel(instanceId: Id): ZIO[Any, Throwable, Boolean]
  def queryExpired(limit: Int, now: Instant): ZIO[Any, Throwable, List[ScheduledTimeout[Id]]]
  def claim(instanceId: Id, nodeId: String, duration: Duration, now: Instant): ZIO[Any, Throwable, ClaimResult]
  def complete(instanceId: Id): ZIO[Any, Throwable, Boolean]
  def release(instanceId: Id): ZIO[Any, Throwable, Boolean]
```

Use `FSMRuntime.withDurableTimeouts` to enable:

```scala
import mechanoid.runtime.FSMRuntime

val program = ZIO.scoped {
  for
    fsm <- FSMRuntime.withDurableTimeouts(orderId, machine, Pending)
    _   <- fsm.send(StartPayment)
    // Timeout is now persisted - survives node restart
  yield ()
}.provide(eventStoreLayer, timeoutStoreLayer)
```

### TimeoutSweeper

A background service discovers and fires expired timeouts:

```scala
val sweeper = ZIO.scoped {
  for
    timeoutStore <- ZIO.service[TimeoutStore[OrderId]]
    eventStore <- ZIO.service[EventStore[OrderId, OrderState, OrderEvent]]

    onTimeout = TimeoutFiring.makeCallback(eventStore)

    sweeper <- TimeoutSweeper.make(config, timeoutStore, onTimeout)
    _ <- ZIO.never // Keep running
  yield ()
}
```

**Flow:**
1. Query for expired, unclaimed timeouts
2. Atomically claim each timeout (prevents duplicates)
3. Fire the timeout (send the user-defined timeout event)
4. Mark complete (removes from TimeoutStore)

### Sweeper Configuration

```scala
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

```scala
val config = TimeoutSweeperConfig()
  .withLeaderElection(
    LeaderElectionConfig()
      .withLeaseDuration(Duration.fromSeconds(30))
      .withRenewalInterval(Duration.fromSeconds(10))
  )

val sweeper = TimeoutSweeper.make(config, timeoutStore, onTimeout, Some(leaseStore))
```

Only the leader node performs sweeps. If the leader fails, another node acquires the lease after expiration.

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

```scala
trait FSMInstanceLock[Id]:
  def tryAcquire(instanceId: Id, nodeId: String, duration: Duration, now: Instant): ZIO[Any, Throwable, LockResult[Id]]
  def acquire(instanceId: Id, nodeId: String, duration: Duration, timeout: Duration): ZIO[Any, Throwable, LockResult[Id]]
  def release(token: LockToken[Id]): ZIO[Any, Throwable, Boolean]
  def extend(token: LockToken[Id], additionalDuration: Duration, now: Instant): ZIO[Any, Throwable, Option[LockToken[Id]]]
  def get(instanceId: Id, now: Instant): ZIO[Any, Throwable, Option[LockToken[Id]]]
```

Use `FSMRuntime.withLocking` to enable automatic lock acquisition around event processing:

```scala
import mechanoid.runtime.FSMRuntime

val program = ZIO.scoped {
  for
    fsm <- FSMRuntime.withLocking(orderId, machine, Pending)
    _   <- fsm.send(Pay)  // Lock acquired automatically before processing
  yield ()
}.provide(eventStoreLayer, lockLayer)
```

### Lock Configuration

```scala
val config = LockConfig()
  .withLockDuration(Duration.fromSeconds(30))    // How long to hold locks
  .withAcquireTimeout(Duration.fromSeconds(10))  // Max wait when acquiring
  .withRetryInterval(Duration.fromMillis(100))   // Retry frequency when busy
  .withValidateBeforeOperation(true)             // Double-check lock before each op
  .withNodeId("node-1")                          // Unique node identifier
```

**Preset configurations:**

```scala
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

For maximum robustness, combine locking with durable timeouts:

```scala
import mechanoid.runtime.FSMRuntime

val program = ZIO.scoped {
  for
    fsm <- FSMRuntime.withLockingAndTimeouts(
      orderId,
      machine,
      Pending,
      LockConfig.default
    )
    _   <- fsm.send(StartPayment)
    // - Lock ensures exactly-once processing
    // - Timeout persisted and survives node restart
  yield ()
}.provide(eventStoreLayer, timeoutStoreLayer, lockLayer)
```

This provides:
- **Exactly-once transitions** via distributed locking
- **Durable timeouts** that survive node failures
- **Optimistic locking** as a final safety net

---

## Command Pattern

### Declarative Commands with emitting

The recommended way to generate side-effect commands is using the `emitting` DSL on transitions:

```scala
enum OrderCommand:
  case ProcessPayment(orderId: String, amount: BigDecimal)
  case SendNotification(message: String)
  case NotifyWarehouse(orderId: String)

val machine = buildAll[OrderState, OrderEvent]:
  // Helper function for payment commands
  val buildPaymentCommand: (OrderEvent, OrderState) => List[OrderCommand] = { (event, _) =>
    event match
      case e: InitiatePayment => List(ProcessPayment(e.orderId, e.amount))
      case _ => Nil
  }

  // Emit commands when transitioning
  Created via event[InitiatePayment] to PaymentProcessing emitting buildPaymentCommand

  // Or inline
  Paid via Ship to Shipped emitting { (event, state) =>
    List(
      SendNotification(s"Order shipped!"),
      NotifyWarehouse(state.orderId)
    )
  }
```

**`emitting` vs `emittingBefore`:**

- `emitting` - Commands generated AFTER state change. Receives `(event, targetState)`.
- `emittingBefore` - Commands generated BEFORE state change. Receives `(event, sourceState)`.

```scala
// Log before transition, notify after
Pending via Pay to Paid
  emittingBefore { (event, state) => List(LogTransition(state)) }
  emitting { (event, state) => List(SendNotification(state)) }
```

### Transactional Outbox Pattern

The command pattern implements the **transactional outbox pattern**:

```
┌─────────────────────────────────────────────────────────────┐
│                     FSM Transition                          │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐              │
│  │ Pending  │───▶│  Event   │───▶│   Paid   │              │
│  └──────────┘    │ Persisted│    └──────────┘              │
│                  └────┬─────┘                               │
│                       │                                     │
│                       ▼                                     │
│                  ┌──────────┐                               │
│                  │ Command  │  ◀── Same transaction         │
│                  │ Persisted│                               │
│                  └────┬─────┘                               │
└───────────────────────┼─────────────────────────────────────┘
                        │
                        ▼ (async, separate process)
                   ┌──────────┐
                   │ Worker   │───▶ Execute side effect
                   │ Process  │───▶ Mark command complete
                   └──────────┘
```

**Key benefits:**

- **Exactly-once event persistence** - guaranteed by EventStore
- **At-least-once command execution** - worker retries on failure
- **Effectively exactly-once side effects** - via idempotency keys
- **Audit trail** - all commands are logged with status

### CommandStore Interface

Implement `CommandStore[Id, Cmd]` for your storage backend:

```scala
trait CommandStore[Id, Cmd]:
  def enqueue(instanceId: Id, command: Cmd, idempotencyKey: String): ZIO[Any, Throwable, PendingCommand[Id, Cmd]]
  def claim(nodeId: String, limit: Int, claimDuration: Duration, now: Instant): ZIO[Any, Throwable, List[PendingCommand[Id, Cmd]]]
  def complete(commandId: Long): ZIO[Any, Throwable, Boolean]
  def fail(commandId: Long, error: String, retryAt: Option[Instant]): ZIO[Any, Throwable, Boolean]
  def skip(commandId: Long, reason: String): ZIO[Any, Throwable, Boolean]
  def getByIdempotencyKey(idempotencyKey: String): ZIO[Any, Throwable, Option[PendingCommand[Id, Cmd]]]
  def getByInstanceId(instanceId: Id): ZIO[Any, Throwable, List[PendingCommand[Id, Cmd]]]
  def countByStatus: ZIO[Any, Throwable, Map[CommandStatus, Long]]
  def releaseExpiredClaims(now: Instant): ZIO[Any, Throwable, Int]
```

**PostgreSQL Schema:**

```sql
CREATE TABLE commands (
  id              BIGSERIAL PRIMARY KEY,
  instance_id     TEXT NOT NULL,
  command         JSONB NOT NULL,
  idempotency_key TEXT NOT NULL UNIQUE,
  enqueued_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  status          TEXT NOT NULL DEFAULT 'pending',
  attempts        INT NOT NULL DEFAULT 0,
  last_attempt_at TIMESTAMPTZ,
  last_error      TEXT,
  next_retry_at   TIMESTAMPTZ,
  claimed_by      TEXT,
  claimed_until   TIMESTAMPTZ
);

CREATE INDEX idx_commands_pending ON commands (next_retry_at)
  WHERE status = 'pending';
CREATE INDEX idx_commands_instance ON commands (instance_id);
```

### CommandWorker

The worker polls for pending commands and executes them:

```scala
// Define executor
val executor: OrderCommand => ZIO[Any, Throwable, CommandResult] = {
  case ProcessPayment(orderId, amount) =>
    paymentService.charge(orderId, amount)
      .as(CommandResult.Success)
      .catchAll(e => ZIO.succeed(CommandResult.Failure(e.getMessage, retryable = true)))

  case SendNotification(message) =>
    notificationService.send(message)
      .as(CommandResult.Success)

  case NotifyWarehouse(orderId) =>
    // Check if already notified (external idempotency)
    warehouseApi.checkNotified(orderId).flatMap {
      case true  => ZIO.succeed(CommandResult.AlreadyExecuted)
      case false => warehouseApi.notify(orderId).as(CommandResult.Success)
    }
}

// Start worker
val workerProgram = ZIO.scoped {
  for
    commandStore <- ZIO.service[CommandStore[String, OrderCommand]]
    config = CommandWorkerConfig()
      .withPollInterval(Duration.fromSeconds(5))
      .withBatchSize(100)
      .withRetryPolicy(RetryPolicy.exponentialBackoff())
    worker <- CommandWorker.make(config, commandStore, executor)
    _ <- ZIO.never // Keep running
  yield ()
}
```

**CommandResult options:**

| Result | Behavior |
|--------|----------|
| `Success` | Command marked complete |
| `Failure(msg, retryable = true)` | Retry according to policy |
| `Failure(msg, retryable = false)` | Mark as permanently failed |
| `AlreadyExecuted` | Mark as skipped (idempotency) |

### Retry Policies

Configure how failed commands are retried:

```scala
// No retries
RetryPolicy.NoRetry

// Fixed delay between retries
RetryPolicy.fixedDelay(
  delay = Duration.fromSeconds(10),
  maxAttempts = 3
)

// Exponential backoff
RetryPolicy.exponentialBackoff(
  initialDelay = Duration.fromSeconds(1),
  maxDelay = Duration.fromSeconds(300),  // 5 minutes cap
  multiplier = 2.0,
  maxAttempts = 5
)
```

**Example retry schedule with exponential backoff:**

| Attempt | Delay |
|---------|-------|
| 1 | 1s |
| 2 | 2s |
| 3 | 4s |
| 4 | 8s |
| 5 | 16s (or maxDelay) |

---

## Visualization

Mechanoid provides built-in visualization tools to generate diagrams of your FSM structure and execution traces. These visualizations are invaluable for:

- **Documentation**: Auto-generate up-to-date FSM diagrams
- **Debugging**: Visualize execution traces to understand state transitions
- **Communication**: Share FSM designs with stakeholders using familiar diagram formats

### Visualization Overview

Three visualizers are available:

| Visualizer | Output Format | Best For |
|------------|---------------|----------|
| `MermaidVisualizer` | Mermaid markdown | GitHub/GitLab READMEs, documentation sites |
| `GraphVizVisualizer` | DOT format | High-quality rendered images, complex diagrams |
| `CommandVisualizer` | Mermaid + Markdown | Command queue monitoring and reports |

All visualizers work with any FSM definition, whether or not it uses commands.

### MermaidVisualizer

Generate [Mermaid](https://mermaid.js.org/) diagrams that render directly in GitHub, GitLab, and many documentation tools.

#### Extension Methods

FSM definitions have extension methods for convenient visualization:

```scala
import mechanoid.visualization.*

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

```scala
val sequenceDiagram = trace.toMermaidSequenceDiagram
val timeline = trace.toGraphVizTimeline
```

#### State Diagram (Static Methods)

Shows the FSM structure with all states and transitions:

```scala
import mechanoid.visualization.MermaidVisualizer

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

```scala
// After running an FSM, get its trace
val trace: ExecutionTrace[OrderState, OrderEvent] = fsm.getTrace

val sequenceDiagram = MermaidVisualizer.sequenceDiagram(
  trace = trace,
  stateEnum = summon[Finite[OrderState]],
  eventEnum = summon[Finite[OrderEvent]]
)
```

#### Flowchart

Shows the FSM as a flowchart with highlighted execution path:

```scala
val flowchart = MermaidVisualizer.flowchart(
  fsm = machine,
  trace = Some(executionTrace)  // Optional: highlights visited states
)
```

### GraphVizVisualizer

Generate [GraphViz DOT](https://graphviz.org/) format for high-quality rendered diagrams.

```scala
import mechanoid.visualization.GraphVizVisualizer

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
  trace = executionTrace
)
```

Render the DOT output using GraphViz tools:

```bash
# Generate PNG
dot -Tpng fsm.dot -o fsm.png

# Generate SVG
dot -Tsvg fsm.dot -o fsm.svg
```

### CommandVisualizer

Generate reports and diagrams for command queue processing.

#### Summary Table

```scala
import mechanoid.visualization.CommandVisualizer

val counts = commandStore.countByStatus

val summaryTable = CommandVisualizer.summaryTable(counts)
// | Status | Count |
// |--------|-------|
// | ⏳ Pending | 0 |
// | ✅ Completed | 28 |
// | ❌ Failed | 1 |
```

#### Complete Report

Combines summary, flowchart, and detailed list:

```scala
val report = CommandVisualizer.report(
  commands = allCommands,
  counts = statusCounts,
  commandName = cmd => cmd.toString,
  commandType = cmd => cmd.getClass.getSimpleName,
  title = "Order Processing Commands"
)
```

### Generating Visualizations

Here's a complete example that generates all visualization types:

```scala
import mechanoid.visualization.*
import java.nio.file.{Files, Paths}

def generateVisualizations[S, E](
    machine: Machine[S, E, ?],
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
                     |${GraphVizVisualizer.digraph(machine, Some(initialState))}
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
- [Command Queue Report](visualizations/command-queue.md) - Command processing summary and details

---

## Type-Level Safety

### ValidTransition

Enforce valid transitions at compile time:

```scala
def processPayment[Id, S, E, Cmd](fsm: FSMRuntime[Id, S, E, Cmd], event: E)
                                  (using ValidTransition[S, E]): ZIO[Any, MechanoidError, TransitionOutcome[S, Cmd]] =
  fsm.send(event)
```

### TransitionSpec

Define allowed transitions as a type:

```scala
import mechanoid.typelevel.*

type OrderTransitions =
  Allow[Pending, Pay, Paid] ::
  Allow[Paid, Ship, Shipped] ::
  Allow[Shipped, Deliver, Delivered] ::
  TNil

// Derive ValidTransition instances
given ValidTransition[Pending, Pay] = TransitionSpec.derive[OrderTransitions, Pending, Pay]
```

Or use the builder API:

```scala
val spec = TransitionSpecBuilder.start
  .allow[Pending, Pay, Paid]
  .allow[Paid, Ship, Shipped]
  .allow[Shipped, Deliver, Delivered]
```

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

```scala
import mechanoid.*
import mechanoid.runtime.FSMRuntime
import mechanoid.persistence.timeout.*
import zio.*
import java.time.Instant
import scala.concurrent.duration.*

// Domain - plain enums, no marker traits needed
enum OrderState:
  case Pending, AwaitingPayment, Paid, Shipped, Delivered, Cancelled

enum OrderEvent:
  case Create, RequestPayment, ConfirmPayment, Ship, Deliver, Cancel, PaymentTimeout

// Commands for side effects
enum OrderCommand:
  case ChargeCard(amount: BigDecimal)
  case SendEmail(to: String, template: String)
  case NotifyWarehouse(orderId: String)

// Timed state - will timeout after 30 minutes
val awaitingWithTimeout = AwaitingPayment @@ timeout(30.minutes, PaymentTimeout)

// FSM Definition with new DSL
val orderMachine = buildAll[OrderState, OrderEvent]:
  // Happy path
  Pending via RequestPayment to awaitingWithTimeout
  AwaitingPayment via ConfirmPayment to Paid emitting { (_, _) =>
    List(OrderCommand.ChargeCard(BigDecimal(100)))
  }
  Paid via Ship to Shipped emitting { (_, _) =>
    List(OrderCommand.NotifyWarehouse("order-123"))
  }
  Shipped via Deliver to Delivered emitting { (_, _) =>
    List(OrderCommand.SendEmail("customer@example.com", "delivered"))
  }

  // Timeout handling
  AwaitingPayment via PaymentTimeout to Cancelled

  // Cancellation from multiple states
  anyOf(Pending, AwaitingPayment) via Cancel to Cancelled

// Add lifecycle actions
val machineWithActions = orderMachine
  .withEntry(AwaitingPayment)(ZIO.logInfo("Waiting for payment..."))
  .withEntry(Cancelled)(ZIO.logInfo("Order cancelled"))

// Running with persistence and durable timeouts
val program = ZIO.scoped {
  for
    // Create FSM with durable timeouts
    fsm <- FSMRuntime.withDurableTimeouts(
      "order-123",
      machineWithActions,
      OrderState.Pending
    )

    // Process order
    _ <- fsm.send(OrderEvent.RequestPayment)

    // Wait for payment (will timeout after 30 minutes if not received)
    // Even if this node crashes, another node's sweeper will fire the timeout

    // Check current state
    state <- fsm.currentState
    _ <- ZIO.logInfo(s"Current state: $state")

  yield ()
}.provide(eventStoreLayer, timeoutStoreLayer)

// Run the sweeper (typically in a separate long-running process)
val sweeperProgram = ZIO.scoped {
  for
    timeoutStore <- ZIO.service[TimeoutStore[String]]
    eventStore <- ZIO.service[EventStore[String, OrderState, OrderEvent]]

    config = TimeoutSweeperConfig()
      .withSweepInterval(Duration.fromSeconds(5))
      .withJitterFactor(0.2)

    onTimeout = TimeoutFiring.makeCallback(eventStore)

    _ <- TimeoutSweeper.make(config, timeoutStore, onTimeout)
    _ <- ZIO.never
  yield ()
}.provide(timeoutStoreLayer, eventStoreLayer)
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
