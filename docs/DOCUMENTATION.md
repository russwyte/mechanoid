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
  - [Guards](#guards)
  - [Actions](#actions)
  - [Entry and Exit Actions](#entry-and-exit-actions)
  - [Timeouts](#timeouts)
- [Running FSMs](#running-fsms)
  - [In-Memory Runtime](#in-memory-runtime)
  - [Sending Events](#sending-events)
  - [Subscribing to State Changes](#subscribing-to-state-changes)
  - [Stream Processing](#stream-processing)
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

// Build FSM definition
val orderFSM = FSMDefinition[OrderState, OrderEvent]()
  .when(Pending).on(Pay).goto(Paid)
  .when(Paid).on(Ship).goto(Shipped)
  .when(Shipped).on(Deliver).goto(Delivered)

// Run the FSM
val program = ZIO.scoped {
  for
    fsm <- orderFSM.build(Pending)
    _   <- fsm.send(Pay)
    _   <- fsm.send(Ship)
    s   <- fsm.currentState
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

**Special state traits:**

- `TerminalState` - Marker for states that cannot transition further
- `StateData[D]` - For states carrying associated data

```scala
enum ConnectionState extends MState:
  case Disconnected extends ConnectionState with TerminalState
  case Connected(sessionId: String) extends ConnectionState with StateData[String]:
    def data = sessionId
```

### Events

Events trigger transitions between states. Define them by extending `MEvent`:

```scala
enum TrafficEvent extends MEvent:
  case Timer, EmergencyOverride
```

**Built-in events:**

- `Timeout` - Automatically sent when a state's timeout expires

**Event data:**

```scala
enum PaymentEvent extends MEvent:
  case Pay(amount: BigDecimal) extends PaymentEvent with EventData[BigDecimal]:
    def data = amount
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

Use `FSMDefinition` to build your FSM:

```scala
val definition = FSMDefinition[MyState, MyEvent]()
  .when(State1).on(Event1).goto(State2)
  .when(State1).on(Event2).stay
  .when(State2).on(Event3).goto(State3)
```

For FSMs that require environment or custom error types:

```scala
val definition = FSMDefinition.typed[MyState, MyEvent, MyEnv, MyError]()
```

### Guards

Guards are predicates that must be true for a transition to occur:

```scala
// Simple boolean guard
.when(Pending).on(Pay).when(amount > 0).goto(Paid)

// Lazy evaluation
.when(Pending).on(Pay).whenEval(checkInventory()).goto(Paid)

// ZIO-based guard (can access environment, fail with errors)
.when(Pending)
  .on(Pay)
  .when(
    ZIO.serviceWithZIO[PaymentService](_.isValid(payment))
  )
  .goto(Paid)
```

If a guard returns `false`, the transition is rejected with `GuardRejectedError`.

### Actions

Execute effects during transitions:

```scala
// Execute action that determines the outcome
.when(Processing)
  .on(Complete)
  .execute {
    for
      result <- processOrder
    yield
      if result.success then TransitionResult.Goto(Completed)
      else TransitionResult.Goto(Failed)
  }

// Execute action then transition
.when(Pending)
  .on(Pay)
  .executing(notifyPaymentReceived)
  .goto(Paid)
```

### Entry and Exit Actions

Define actions that run when entering or leaving a state:

```scala
val definition = FSMDefinition[State, Event]()
  .when(Active).on(Deactivate).goto(Inactive)
  .onState(Active)
    .onEntry(ZIO.logInfo("Entered Active state"))
    .onExit(ZIO.logInfo("Leaving Active state"))
    .done
```

### Timeouts

Schedule automatic timeout events:

```scala
import scala.concurrent.duration.*

val definition = FSMDefinition[State, Event]()
  .when(WaitingForPayment).onTimeout.goto(Cancelled)
  .withTimeout(WaitingForPayment, 30.minutes)
```

When the FSM enters `WaitingForPayment`, a timer starts. If no other event arrives within 30 minutes, a `Timeout` event is automatically sent.

---

## Running FSMs

### In-Memory Runtime

Build and run an FSM:

```scala
val program = ZIO.scoped {
  for
    fsm <- definition.build(initialState)
    // Use the FSM...
  yield result
}
```

The FSM is automatically stopped when the scope closes.

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
- `GuardRejectedError` - Guard condition failed

### Subscribing to State Changes

React to state transitions:

```scala
fsm.subscribe.foreach { change =>
  ZIO.logInfo(s"${change.from} -> ${change.to} via ${change.triggeredBy}")
}.fork
```

`StateChange` contains:
- `from` / `to` - States
- `triggeredBy` - The event (may be `Timeout`)
- `timestamp` - When the transition occurred

### Stream Processing

Process a stream of events:

```scala
val events: ZStream[Any, Nothing, MyEvent] = ???

fsm.processStream(events).foreach { change =>
  ZIO.logInfo(s"Processed: $change")
}
```

---

## Persistence

### Event Sourcing Model

Mechanoid supports event sourcing for durable FSMs:

1. Events are persisted *after* the transition action succeeds
2. State is reconstructed by replaying events
3. Snapshots reduce recovery time

```scala
val program = ZIO.scoped {
  for
    fsm <- PersistentFSMRuntime(orderId, definition, Pending)
    _   <- fsm.send(Pay)    // Event persisted
    _   <- fsm.send(Ship)   // Event persisted
  yield ()
}.provide(eventStoreLayer)
```

### EventStore Interface

Implement `EventStore[Id, S, E]` for your storage backend:

```scala
trait EventStore[Id, S <: MState, E <: MEvent]:
  def append(instanceId: Id, event: E | Timeout.type, expectedSeqNr: Long): ZIO[Any, Throwable, Long]
  def loadEvents(instanceId: Id): ZStream[Any, Throwable, StoredEvent[Id, E | Timeout.type]]
  def loadEventsFrom(instanceId: Id, fromSeqNr: Long): ZStream[Any, Throwable, StoredEvent[Id, E | Timeout.type]]
  def loadSnapshot(instanceId: Id): ZIO[Any, Throwable, Option[FSMSnapshot[Id, S]]]
  def saveSnapshot(snapshot: FSMSnapshot[Id, S]): ZIO[Any, Throwable, Unit]
  def highestSequenceNr(instanceId: Id): ZIO[Any, Throwable, Long]
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

On startup, `PersistentFSMRuntime`:

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

Use `withDurableTimeouts` to enable:

```scala
val program = ZIO.scoped {
  for
    fsm <- PersistentFSMRuntime.withDurableTimeouts(orderId, definition, Pending)
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

Use `withLocking` to enable automatic lock acquisition around event processing:

```scala
val program = ZIO.scoped {
  for
    fsm <- PersistentFSMRuntime.withLocking(orderId, definition, Pending)
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
val program = ZIO.scoped {
  for
    fsm <- PersistentFSMRuntime.withLockingAndTimeouts(
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

When using persistence, FSM state can be recovered by replaying events. However, **side effects** present a challenge:

| Action Type | Runs During Replay | Safe for Side Effects |
|------------|-------------------|----------------------|
| `.executing(action)` | Yes | No - will re-run |
| `.onState(s).onEntry(action)` | No | Yes |
| `.onState(s).onExit(action)` | No | Yes |

If your transition executes an expensive or non-idempotent operation (like charging a credit card), it could run again during recovery.

**Solutions:**

1. **Entry actions**: Put side effects in entry actions (they don't run during replay)
2. **Idempotent operations**: Design side effects to be safely repeatable
3. **Command Queue**: Persist commands for separate execution (this section)

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

Enqueue commands in **entry actions** (they don't run during replay):

```scala
val definition = FSMDefinition[OrderState, OrderEvent]()
  .when(Pending).on(Pay).goto(Paid)
  .when(Paid).on(Ship).goto(Shipped)
  // Enqueue command when entering Paid state
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
  // Enqueue notification when entering Shipped state
  .onState(Shipped).onEntry {
    for
      order <- getOrderDetails
      _ <- commandStore.enqueue(
        order.id,
        SendEmail(order.customerEmail, "shipped-notification"),
        s"ship-email-${order.id}"
      )
      _ <- commandStore.enqueue(
        order.id,
        NotifyWarehouse(order.id),
        s"warehouse-notify-${order.id}"
      )
    yield ()
  }.done
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

## Type-Level Safety

### ValidTransition

Enforce valid transitions at compile time:

```scala
def processPayment[S <: MState, E <: MEvent](fsm: FSMRuntime[S, E, Any, Nothing], event: E)
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
| `GuardRejectedError(state, event)` | Guard condition returned false |
| `FSMStoppedError(reason)` | FSM has been stopped |
| `ProcessingTimeoutError(state, event, duration)` | Timeout during event processing |
| `PersistenceError(cause)` | Persistence operation failed |
| `SequenceConflictError(expected, actual, instanceId)` | Concurrent modification detected |
| `EventReplayError(state, event, sequenceNr)` | Stored event doesn't match FSM definition |
| `LockingError(cause)` | Distributed lock operation failed (busy, timeout, etc.) |

---

## Complete Example

```scala
import mechanoid.*
import mechanoid.persistence.*
import mechanoid.persistence.timeout.*
import zio.*
import java.time.Instant

// Domain
enum OrderState extends MState:
  case Pending, AwaitingPayment, Paid, Shipped, Delivered, Cancelled

enum OrderEvent extends MEvent:
  case Create, RequestPayment, ConfirmPayment, Ship, Deliver, Cancel

// FSM Definition
val orderDefinition = FSMDefinition[OrderState, OrderEvent]()
  // Happy path
  .when(Pending).on(RequestPayment).goto(AwaitingPayment)
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

// Running with persistence and durable timeouts
val program = ZIO.scoped {
  for
    // Create FSM with durable timeouts
    fsm <- PersistentFSMRuntime.withDurableTimeouts(
      "order-123",
      orderDefinition,
      OrderState.Pending
    )

    // Subscribe to changes
    _ <- fsm.subscribe.foreach { change =>
      ZIO.logInfo(s"Order transitioned: ${change.from} -> ${change.to}")
    }.fork

    // Process order
    _ <- fsm.send(OrderEvent.RequestPayment)

    // Wait for payment (will timeout after 30 minutes if not received)
    // Even if this node crashes, another node's sweeper will fire the timeout

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
