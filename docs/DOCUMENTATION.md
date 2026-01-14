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
  - [Entry and Exit Actions](#entry-and-exit-actions)
  - [Timeouts](#timeouts)
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
- [Command Queue](#command-queue)
  - [The Side Effect Problem](#the-side-effect-problem)
  - [Transactional Outbox Pattern](#transactional-outbox-pattern)
  - [CommandStore Interface](#commandstore-interface)
  - [CommandWorker](#commandworker)
  - [Retry Policies](#retry-policies)
  - [Integration with FSM](#integration-with-fsm)
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
- **Effectful transitions** via ZIO
- **Optional persistence** through event sourcing
- **Durable timeouts** that survive node failures
- **Distributed coordination** with claim-based locking and leader election

```scala
import mechanoid.*
import zio.*

// Define states and events
enum OrderState extends MState:
  case Pending, Paid, Shipped, Delivered

enum OrderEvent extends MEvent:
  case Pay, Ship, Deliver

// Build FSM definition with compile-time validation
val orderFSM = build[OrderState, OrderEvent] {
  _.when(Pending).on(Pay).goto(Paid)
    .when(Paid).on(Ship).goto(Shipped)
    .when(Shipped).on(Deliver).goto(Delivered)
}

// Run the FSM
val program = ZIO.scoped {
  for
    runtime <- orderFSM.build(Pending)
    _   <- runtime.send(Pay)
    _   <- runtime.send(Ship)
    s   <- runtime.currentState
  yield s // Shipped
}
```

---

## Core Concepts

### States

States represent the possible conditions of your FSM. Define them by extending `MState`:

```scala
enum TrafficLight extends MState:
  case Red, Yellow, Green
```

States can also carry data (rich states):

```scala
enum OrderState extends MState:
  case Pending
  case Paid(transactionId: String)
  case Failed(reason: String)
```

When defining transitions, the state's "shape" (which case it is) is used for matching, not the exact value. This means `.when(Failed(""))` will match ANY `Failed(_)` state.

#### Hierarchical States

For complex domains, you can organize related states using nested sealed traits:

```scala
sealed trait OrderState extends MState

case object Created extends OrderState

// Group all processing-related states
sealed trait Processing extends OrderState
case object ValidatingPayment extends Processing
case object ChargingCard extends Processing

case object Completed extends OrderState
```

Benefits:
- **Code organization** - Related states are grouped together
- **Type safety** - Can pattern match on parent traits (e.g., `case _: Processing =>`)
- **IDE navigation** - Jump to parent trait to see all related states

**Important:** Transitions use leaf states only. Parent traits (`Processing`) are for organization - you cannot use them in `.when()`. The macro automatically discovers all leaf cases recursively.

#### Multi-State Transitions

For transitions that apply to multiple states, use `whenAny` or `whenStates`:

```scala
// Apply to all leaf states under a parent type
.whenAny[Processing].on(Cancel).goto(Cancelled)

// Apply to specific states under a parent type
.whenAny[Processing](ValidatingPayment, ChargingCard).on(Pause).goto(Paused)

// Apply to specific states (not based on hierarchy)
.whenStates(Created, Completed).on(Archive).goto(Archived)
```

Use `whenStates` when the states don't share a common parent but should have the same transition.

### Events

Events trigger transitions between states. Define them by extending `MEvent`:

```scala
enum TrafficEvent extends MEvent:
  case Timer, EmergencyOverride
```

**Timeouts:**

Timeout events are handled internally by the runtime. When a state's timeout expires, a timeout transition is automatically triggered if one is defined. You don't need to define or handle timeout events explicitly - just use `.onTimeout` in your FSM definition.

**Events with data:**

```scala
enum PaymentEvent extends MEvent:
  case Pay(amount: BigDecimal)
  case Refund(orderId: String, amount: BigDecimal)
```

**Event hierarchies:**

Like states, events can be organized into hierarchies using sealed traits:

```scala
sealed trait UserEvent extends MEvent
sealed trait InputEvent extends UserEvent
case object Click extends InputEvent
case object Tap extends InputEvent
case object Swipe extends InputEvent
```

#### Multi-Event Transitions

For transitions that are triggered by multiple events, use `onAny` or `onEvents`:

```scala
// Apply to all leaf events under a parent type
.when(Idle).onAny[InputEvent].goto(Processing)

// Apply to specific events under a parent type
.when(Idle).onAny[InputEvent](Click, Tap).goto(Processing)

// Apply to specific events (not based on hierarchy)
.when(Active).onEvents(Pause, Stop).goto(Paused)
```

Use `onEvents` when the events don't share a common parent but should trigger the same transition.

**Combining multi-state and multi-event:**

You can combine `whenAny`/`whenStates` with `onAny`/`onEvents` to create transitions for the cartesian product of states and events:

```scala
// All Working states respond to Cancel OR Abort
.whenAny[Working].onEvents(Cancel, Abort).goto(Stopped)

// All Processing states respond to any UserInput
.whenAny[Processing].onAny[UserInput].goto(Idle)
```

### Transitions

A transition defines what happens when an event is received in a specific state:

```scala
// Simple transition: Pending + Pay -> Paid
.when(Pending).on(Pay).goto(Paid)

// Stay in current state
.when(Pending).on(Heartbeat).stay

// Stop the FSM
.when(Failed).on(Shutdown).stop

// Multiple events trigger the same transition
.when(Active).onEvents(Pause, Suspend).goto(Paused)
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

Use the `build` macro to create FSM definitions with compile-time validation:

```scala
import mechanoid.*

val definition = build[MyState, MyEvent] {
  _.when(State1).on(Event1).goto(State2)
    .when(State1).on(Event2).stay
    .when(State2).on(Event3).goto(State3)
}
```

All FSM definitions share the same type: `FSMDefinition[S, E, Cmd]`. The third type parameter `Cmd` represents commands that can be enqueued for side effect execution:

- `build[S, E] { ... }` - FSM without commands, with compile-time validation (recommended)
- `build[S, E, Cmd] { ... }` - FSM with commands, with compile-time validation (recommended)
- `FSMDefinition[S, E]` - Direct construction without validation
- `FSMDefinition.withCommands[S, E, Cmd]` - Direct construction with commands

**Compile-time Validation:**

The `build` macro detects duplicate transitions at compile time:

```scala
// This will fail at compile time:
val bad = build[MyState, MyEvent] {
  _.when(State1).on(Event1).goto(State2)
    .when(State1).on(Event1).goto(State3)  // Error: Duplicate transition
}
```

To intentionally override a transition (e.g., after `whenAny`), use `.override`:

```scala
val definition = build[MyState, MyEvent] {
  _.whenAny[Parent].on(Cancel).goto(Draft)
    .when(SpecificState).on(Cancel).override.goto(Special)  // OK - intentional override
}
```

The library uses unified error types—all errors are represented as `MechanoidError`.

### Entry and Exit Actions

Define actions that run when entering or leaving a state:

```scala
val definition = build[State, Event] {
  _.when(Active).on(Deactivate).goto(Inactive)
    .onState(Active)
      .onEntry(ZIO.logInfo("Entered Active state"))
      .onExit(ZIO.logInfo("Leaving Active state"))
      .done
}
```

Action names are automatically extracted at compile time for visualization purposes. If you want custom descriptions, use `onEntryWithDescription` or `onExitWithDescription`:

```scala
.onState(Active)
  .onEntryWithDescription(myComplexAction, "Initialize active state resources")
  .done
```

### Timeouts

Schedule automatic timeout events:

```scala
import scala.concurrent.duration.*

val definition = build[State, Event] {
  _.when(WaitingForPayment).onTimeout.goto(Cancelled)
    .withTimeout(WaitingForPayment, 30.minutes)
}
```

When the FSM enters `WaitingForPayment`, a timer starts. If no other event arrives within 30 minutes, a `Timeout` event is automatically sent.

---

## Running FSMs

Mechanoid provides a unified `FSMRuntime[Id, S, E, Cmd]` interface for all FSM execution scenarios. The runtime has four type parameters:

- `Id` - The instance identifier type (`Unit` for simple FSMs, or a custom type like `String` or `UUID` for persistent FSMs)
- `S` - The state type (must extend `MState`)
- `E` - The event type (must extend `MEvent`)
- `Cmd` - The command type (`Nothing` for FSMs without commands)

### Simple Runtime

For simple, single-instance FSMs without persistence, use `definition.build(initialState)`:

```scala
val program = ZIO.scoped {
  for
    fsm <- definition.build(initialState)  // Returns FSMRuntime[Unit, S, E, Cmd]
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
    fsm <- FSMRuntime(orderId, definition, initialState)  // Returns FSMRuntime[String, S, E, Cmd]
    // Use the FSM...
  yield result
}.provide(eventStoreLayer)
```

The persistent runtime requires an `EventStore` in the environment and uses the provided ID to identify the FSM instance.

### Sending Events

```scala
for
  result <- fsm.send(MyEvent)
yield result match
  case TransitionResult.Goto(newState) => s"Transitioned to $newState"
  case TransitionResult.Stay           => "Stayed in current state"
  case TransitionResult.Stop(reason)   => s"Stopped: $reason"
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

```scala
import mechanoid.runtime.FSMRuntime

val program = ZIO.scoped {
  for
    fsm <- FSMRuntime(orderId, definition, Pending)
    _   <- fsm.send(Pay)    // Event persisted
    _   <- fsm.send(Ship)   // Event persisted
  yield ()
}.provide(eventStoreLayer)
```


### EventStore Interface

Implement `EventStore[Id, S, E]` for your storage backend:

```scala
trait EventStore[Id, S <: MState, E <: MEvent]:
  // Core method - events are wrapped in Timed[E] internally
  def append(instanceId: Id, event: Timed[E], expectedSeqNr: Long): ZIO[Any, MechanoidError, Long]

  // Convenience methods (built-in, no need to override)
  def appendEvent(instanceId: Id, event: E, expectedSeqNr: Long): ZIO[Any, MechanoidError, Long]
  def appendTimeout(instanceId: Id, expectedSeqNr: Long): ZIO[Any, MechanoidError, Long]

  def loadEvents(instanceId: Id): ZStream[Any, MechanoidError, StoredEvent[Id, Timed[E]]]
  def loadEventsFrom(instanceId: Id, fromSeqNr: Long): ZStream[Any, MechanoidError, StoredEvent[Id, Timed[E]]]
  def loadSnapshot(instanceId: Id): ZIO[Any, MechanoidError, Option[FSMSnapshot[Id, S]]]
  def saveSnapshot(snapshot: FSMSnapshot[Id, S]): ZIO[Any, MechanoidError, Unit]
  def highestSequenceNr(instanceId: Id): ZIO[Any, MechanoidError, Long]
```

**Note**: `Timed[E]` is an internal wrapper that distinguishes user events from timeout events. You only need to handle this in your `append` implementation - serialize both types appropriately.

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
    fsm <- FSMRuntime.withDurableTimeouts(orderId, definition, Pending)
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
3. Fire the timeout (append `Timeout` event to EventStore)
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
    fsm <- FSMRuntime.withLocking(orderId, definition, Pending)
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
      definition,
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

## Command Queue

### The Side Effect Problem

When using persistence, FSM state can be recovered by replaying events. However, **side effects** present a challenge.

Mechanoid's FSM transitions are statically resolvable—all target states are known at compile time. Side effects should be handled through:

| Action Type | Runs During Replay | Safe for Side Effects |
|------------|-------------------|----------------------|
| `.onState(s).onEntry(action)` | No | Yes |
| `.onState(s).onExit(action)` | No | Yes |
| **Command Queue** | No (commands persisted) | Yes |

**Solutions:**

1. **Entry/Exit actions**: Put side effects in lifecycle actions (they don't run during replay)
2. **Idempotent operations**: Design side effects to be safely repeatable
3. **Command Queue**: Persist commands for separate execution (recommended for complex workflows)

### Transactional Outbox Pattern

The command queue implements the **transactional outbox pattern**:

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
// Define your command type
enum OrderCommand:
  case ChargeCard(amount: BigDecimal, token: String)
  case SendEmail(to: String, template: String)
  case NotifyWarehouse(orderId: String)

// Define executor
val executor: OrderCommand => ZIO[Any, Throwable, CommandResult] = {
  case ChargeCard(amount, token) =>
    paymentService.charge(amount, token)
      .as(CommandResult.Success)
      .catchAll(e => ZIO.succeed(CommandResult.Failure(e.getMessage, retryable = true)))

  case SendEmail(to, template) =>
    emailService.send(to, template)
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

### Integration with FSM

The `StateBuilder` provides declarative methods to enqueue commands when entering a state. This is the recommended approach:

```scala
// Define command type
enum OrderCommand:
  case ChargeCard(amount: BigDecimal, token: String)
  case SendEmail(to: String, template: String)
  case NotifyWarehouse(orderId: String)

// Use build with commands type parameter
val definition = build[OrderState, OrderEvent, OrderCommand] {
  _.when(Pending).on(Pay).goto(Paid)
    .when(Paid).on(Ship).goto(Shipped)
    // Declaratively enqueue command when entering Paid state
    .onState(Paid)
      .enqueue(state => ChargeCard(state.total, state.paymentToken))
      .done
    // Enqueue multiple commands when entering Shipped state
    .onState(Shipped)
      .enqueue(_ => SendEmail(customerEmail, "shipped-notification"))
      .enqueue(state => NotifyWarehouse(state.orderId))
      .done
}
```

The `enqueue` method:
- Takes a function `S => Cmd` that produces a command from the current state
- Automatically generates idempotency keys based on `instanceId-seqNr`
- Commands are persisted atomically with the state transition

For multiple commands, you can chain `.enqueue` calls or use `.enqueueAll`:

```scala
.onState(Shipped)
  .enqueueAll { state =>
    List(
      SendEmail(state.email, "shipped"),
      NotifyWarehouse(state.orderId)
    )
  }
  .done
```

**Alternative: Manual enqueueing in entry actions**

For advanced use cases where you need more control (e.g., conditional enqueueing, custom idempotency keys), you can use entry actions:

```scala
val definition = build[OrderState, OrderEvent, OrderCommand] {
  _.when(Pending).on(Pay).goto(Paid)
    .onState(Paid).onEntry {
      for
        order <- getOrderDetails
        _ <- commandStore.enqueue(
          instanceId = order.id,
          command = ChargeCard(order.total, order.paymentToken),
          idempotencyKey = s"charge-order-${order.id}"
        )
      yield ()
    }.done
}
```

**Complete flow:**

1. FSM transitions from `Pending` to `Paid`
2. Entry action enqueues `ChargeCard` command (persisted atomically)
3. Event is persisted to EventStore
4. If node crashes here, command is still in queue
5. CommandWorker picks up command, executes it
6. On success, command marked complete
7. On failure, command retried according to policy

**Monitoring:**

```scala
for
  counts <- commandStore.countByStatus
  _ <- ZIO.logInfo(s"Commands - Pending: ${counts.getOrElse(CommandStatus.Pending, 0)}")
  _ <- ZIO.logInfo(s"Commands - Failed: ${counts.getOrElse(CommandStatus.Failed, 0)}")
yield ()
```

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
val diagram = orderDefinition.toMermaidStateDiagram(Some(OrderState.Created))

// Flowchart
val flowchart = orderDefinition.toMermaidFlowchart

// With execution trace highlighting
val highlighted = orderDefinition.toMermaidFlowchartWithTrace(trace)

// GraphViz
val dot = orderDefinition.toGraphViz(name = "OrderFSM", initialState = Some(OrderState.Created))
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
  fsm = orderDefinition,
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
  stateEnum = OrderState.sealedEnum,
  eventEnum = OrderEvent.sealedEnum
)
```

#### Flowchart

Shows the FSM as a flowchart with highlighted execution path:

```scala
val flowchart = MermaidVisualizer.flowchart(
  fsm = orderDefinition,
  trace = Some(executionTrace)  // Optional: highlights visited states
)
```

#### With Command Integration

For FSMs that use the command queue, enhanced visualizations show which commands are triggered:

```scala
// Map state ordinals to command type names
val stateCommands = Map(
  OrderState.PaymentProcessing.ordinal -> List("ProcessPayment"),
  OrderState.Paid.ordinal -> List("RequestShipping", "SendNotification"),
  OrderState.Shipped.ordinal -> List("SendNotification")
)

// State diagram with command annotations
val diagramWithCommands = MermaidVisualizer.stateDiagramWithCommands(
  fsm = orderDefinition,
  stateCommands = stateCommands,
  initialState = Some(OrderState.Created)
)

// Flowchart with command lanes
val flowchartWithCommands = MermaidVisualizer.flowchartWithCommands(
  fsm = orderDefinition,
  stateCommands = stateCommands,
  trace = Some(executionTrace)
)

// Sequence diagram interleaving FSM and command execution
val sequenceWithCommands = MermaidVisualizer.sequenceDiagramWithCommands(
  trace = executionTrace,
  stateEnum = OrderState.sealedEnum,
  eventEnum = OrderEvent.sealedEnum,
  commands = pendingCommands,
  commandName = cmd => cmd.toString
)
```

### GraphVizVisualizer

Generate [GraphViz DOT](https://graphviz.org/) format for high-quality rendered diagrams.

```scala
import mechanoid.visualization.GraphVizVisualizer

// Basic digraph
val dot = GraphVizVisualizer.digraph(
  fsm = orderDefinition,
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
  fsm = orderDefinition,
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

#### Command List by Instance

```scala
val commandList = CommandVisualizer.commandList(
  commands = allCommands,
  commandName = cmd => cmd.toString
)
```

#### Flowchart

Shows command types flowing to their final statuses:

```scala
val flowchart = CommandVisualizer.flowchart(
  commands = allCommands,
  commandType = cmd => cmd.getClass.getSimpleName
)
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

def generateVisualizations[S <: MState, E <: MEvent, R, Err](
    fsm: FSMDefinition[S, E, R, Err],
    initialState: S,
    outputDir: String
): ZIO[Any, Throwable, Unit] =
  for
    _ <- ZIO.attempt(Files.createDirectories(Paths.get(outputDir)))

    // Generate FSM structure diagram
    structureMd = s"""# FSM Structure
                     |
                     |## State Diagram
                     |
                     |```mermaid
                     |${MermaidVisualizer.stateDiagram(fsm, Some(initialState))}
                     |```
                     |
                     |## Flowchart
                     |
                     |```mermaid
                     |${MermaidVisualizer.flowchart(fsm)}
                     |```
                     |
                     |## GraphViz
                     |
                     |```dot
                     |${GraphVizVisualizer.digraph(fsm, Some(initialState))}
                     |```
                     |""".stripMargin

    _ <- ZIO.attempt(
      Files.writeString(Paths.get(s"$outputDir/fsm-structure.md"), structureMd)
    )
  yield ()

// Generate trace visualization after FSM execution
def generateTraceVisualization[S <: MState, E <: MEvent](
    trace: ExecutionTrace[S, E],
    stateEnum: Finite[S],
    eventEnum: Finite[E],
    outputPath: String
): ZIO[Any, Throwable, Unit] =
  val traceMd = s"""# Execution Trace: ${trace.instanceId}
                   |
                   |**Final State:** ${stateEnum.nameOf(trace.currentState)}
                   |
                   |## Sequence Diagram
                   |
                   |```mermaid
                   |${MermaidVisualizer.sequenceDiagram(trace, stateEnum, eventEnum)}
                   |```
                   |""".stripMargin

  ZIO.attempt(Files.writeString(Paths.get(outputPath), traceMd))
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
def processPayment[Id, S <: MState, E <: MEvent, Cmd](fsm: FSMRuntime[Id, S, E, Cmd], event: E)
                                                      (using ValidTransition[S, E]): ZIO[Any, MechanoidError, TransitionResult[S]] =
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

// Domain
enum OrderState extends MState:
  case Pending, AwaitingPayment, Paid, Shipped, Delivered, Cancelled

enum OrderEvent extends MEvent:
  case Create, RequestPayment, ConfirmPayment, Ship, Deliver, Cancel

// FSM Definition with compile-time validation
val orderDefinition = build[OrderState, OrderEvent] {
  // Happy path
  _.when(Pending).on(RequestPayment).goto(AwaitingPayment)
    .when(AwaitingPayment).on(ConfirmPayment).goto(Paid)
    .when(AwaitingPayment).onTimeout.goto(Cancelled)
    .when(Paid).on(Ship).goto(Shipped)
    .when(Shipped).on(Deliver).goto(Delivered)
    // Cancellation
    .when(Pending).on(Cancel).goto(Cancelled)
    .when(AwaitingPayment).on(Cancel).goto(Cancelled)
    // Timeout for payment
    .withTimeout(AwaitingPayment, scala.concurrent.duration.Duration(30, "minutes"))
    // Lifecycle hooks
    .onState(AwaitingPayment)
      .onEntry(ZIO.logInfo("Waiting for payment..."))
      .done
    .onState(Cancelled)
      .onEntry(ZIO.logInfo("Order cancelled"))
      .done
}

// Running with persistence and durable timeouts
val program = ZIO.scoped {
  for
    // Create FSM with durable timeouts
    fsm <- FSMRuntime.withDurableTimeouts(
      "order-123",
      orderDefinition,
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
