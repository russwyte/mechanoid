package mechanoid.runtime

import zio.*
import mechanoid.core.*
import mechanoid.machine.Machine
import mechanoid.persistence.{EventStore, FSMSnapshot, StoredEvent}
import mechanoid.stores.InMemoryEventStore
import mechanoid.runtime.timeout.{TimeoutStrategy, FiberTimeoutStrategy}
import mechanoid.runtime.locking.{LockingStrategy, OptimisticLockingStrategy}
import java.time.Instant

/** The runtime interface for an active FSM.
  *
  * All errors are returned as `MechanoidError`. User errors from lifecycle actions are wrapped in `ActionFailedError`.
  *
  * @tparam Id
  *   The FSM instance identifier type (use `Unit` for anonymous/ephemeral FSMs)
  * @tparam S
  *   The state type
  * @tparam E
  *   The event type
  * @tparam Cmd
  *   The command type (use `Nothing` for FSMs without commands)
  */
trait FSMRuntime[Id, S, E, +Cmd]:

  /** The FSM instance identifier. */
  def instanceId: Id

  /** Send an event to the FSM and get the transition outcome.
    *
    * Returns the outcome of processing the event:
    *   - result: The transition result (Stay, Goto, Stop)
    *   - preCommands: Commands generated before state change
    *   - postCommands: Commands generated after state change
    *
    * If no transition is defined for the current state and event, returns an InvalidTransitionError.
    */
  def send(event: E): ZIO[Any, MechanoidError, TransitionOutcome[S, Cmd]]

  /** Get the current state of the FSM. */
  def currentState: UIO[S]

  /** Get the full FSM state including metadata. */
  def state: UIO[FSMState[S]]

  /** Get the history of previous states (most recent first). */
  def history: UIO[List[S]]

  /** Get the last persisted sequence number.
    *
    * Useful for implementing snapshot strategies (e.g., snapshot every N events).
    */
  def lastSequenceNr: UIO[Long]

  /** Take a snapshot of the current state.
    *
    * Snapshots allow faster recovery by avoiding full event replay. On next startup, only events after this snapshot
    * are replayed.
    *
    * '''This is NOT called automatically.''' You decide when to snapshot:
    * {{{
    * // After every N events
    * seqNr <- fsm.lastSequenceNr
    * _     <- ZIO.when(seqNr % 100 == 0)(fsm.saveSnapshot)
    *
    * // Periodically
    * fsm.saveSnapshot.repeat(Schedule.fixed(5.minutes)).forkDaemon
    *
    * // On specific states
    * _ <- ZIO.when(fsm.currentState == FinalState)(fsm.saveSnapshot)
    * }}}
    *
    * After snapshotting, you may optionally delete old events via `EventStore.deleteEventsTo` to reclaim storage.
    */
  def saveSnapshot: ZIO[Any, MechanoidError, Unit]

  /** Stop the FSM gracefully. */
  def stop: UIO[Unit]

  /** Stop the FSM with a reason. */
  def stop(reason: String): UIO[Unit]

  /** Check if the FSM is currently running. */
  def isRunning: UIO[Boolean]

  /** Get the timeout configuration for a given state.
    *
    * Returns `Some((duration, event))` if the state has a timeout configured, `None` otherwise. Used by
    * [[mechanoid.runtime.aspects.DurableTimeoutRuntime]] to manage durable timeouts.
    *
    * @param state
    *   The state to check
    * @return
    *   Timeout duration and event if configured
    */
  def timeoutConfigForState(state: S): Option[(Duration, E)]
end FSMRuntime

object FSMRuntime:

  // ============================================
  // Simple In-Memory FSM (no persistence, no Id)
  // ============================================

  /** Create a simple in-memory FSM runtime.
    *
    * This is the simplest way to use an FSM - no persistence, no distributed features. State is held in memory and lost
    * when the scope closes.
    *
    * {{{
    * val machine = build[TrafficLight, TrafficEvent](
    *   Red via Timer to Green,
    *   Green via Timer to Yellow,
    *   Yellow via Timer to Red,
    * )
    *
    * val program = ZIO.scoped {
    *   for
    *     fsm   <- machine.start(Red)
    *     _     <- fsm.send(Timer)
    *     state <- fsm.currentState  // Green
    *   yield state
    * }
    * }}}
    *
    * @param machine
    *   The Machine definition
    * @param initial
    *   The initial state
    */
  def make[S, E, Cmd](
      machine: Machine[S, E, Cmd],
      initial: S,
  ): ZIO[Scope, MechanoidError, FSMRuntime[Unit, S, E, Cmd]] =
    for
      eventStore      <- InMemoryEventStore.make[Unit, S, E]
      timeoutStrategy <- FiberTimeoutStrategy.make[Unit]
      lockingStrategy = OptimisticLockingStrategy.make[Unit]
      runtime <- ZIO.acquireRelease(
        createRuntime((), machine, initial, eventStore, timeoutStrategy, lockingStrategy)
      )(_.stop)
    yield runtime

  // ============================================
  // Persistent FSM with EventStore from environment
  // ============================================

  /** Create a persistent FSM runtime, pulling dependencies from the environment.
    *
    * Provide the EventStore, TimeoutStrategy, and LockingStrategy via ZLayer:
    *
    * {{{
    * // Define your store layer (created once, reused)
    * val storeLayer: ZLayer[DataSource, Throwable, EventStore[OrderId, OrderState, OrderEvent]] =
    *   ZLayer.scoped {
    *     for
    *       ds <- ZIO.service[DataSource]
    *       store <- PostgresEventStore.make(ds)  // your implementation
    *     yield store
    *   }
    *
    * // Use the FSM with dependencies from the environment
    * val program = ZIO.scoped {
    *   for
    *     fsm <- FSMRuntime(orderId, orderMachine, Pending)
    *     _   <- fsm.send(Pay)
    *   yield ()
    * }.provide(
    *   storeLayer,
    *   dataSourceLayer,
    *   TimeoutStrategy.fiber[OrderId],    // or TimeoutStrategy.durable
    *   LockingStrategy.optimistic[OrderId] // or LockingStrategy.distributed
    * )
    * }}}
    *
    * This will:
    *   1. Load the latest snapshot (if any)
    *   2. Replay events since the snapshot to rebuild state
    *   3. Continue processing new events, persisting each one
    *
    * ==Timeout Strategy==
    *
    * You must provide a [[TimeoutStrategy]] to handle state timeouts:
    *   - [[timeout.FiberTimeoutStrategy]]: In-memory, fast, doesn't survive node failures
    *   - [[timeout.DurableTimeoutStrategy]]: Persists to TimeoutStore, survives failures
    *
    * ==Locking Strategy==
    *
    * You must provide a [[LockingStrategy]] to handle concurrent access:
    *   - [[locking.OptimisticLockingStrategy]]: No explicit locks, relies on EventStore conflict detection
    *   - [[locking.DistributedLockingStrategy]]: Acquires exclusive locks, prevents conflicts
    *
    * @param id
    *   The FSM instance identifier
    * @param machine
    *   The Machine definition
    * @param initialState
    *   The initial state for new instances
    */
  def apply[Id: Tag, S, E, Cmd](
      id: Id,
      machine: Machine[S, E, Cmd],
      initialState: S,
  )(using
      Tag[EventStore[Id, S, E]],
      Tag[TimeoutStrategy[Id]],
      Tag[LockingStrategy[Id]],
  ): ZIO[
    Scope & EventStore[Id, S, E] & TimeoutStrategy[Id] & LockingStrategy[Id],
    MechanoidError,
    FSMRuntime[Id, S, E, Cmd],
  ] =
    for
      store           <- ZIO.service[EventStore[Id, S, E]]
      timeoutStrategy <- ZIO.service[TimeoutStrategy[Id]]
      lockingStrategy <- ZIO.service[LockingStrategy[Id]]
      runtime         <- ZIO.acquireRelease(
        createRuntime(id, machine, initialState, store, timeoutStrategy, lockingStrategy)
      )(_.stop)
    yield runtime

  // ============================================
  // Implementation
  // ============================================

  /** Create and initialize an FSM runtime.
    *
    * This is the core factory method used by all the public factory methods above.
    *
    * @param id
    *   FSM instance identifier
    * @param machine
    *   Machine definition
    * @param initialState
    *   Initial state for new instances
    * @param store
    *   Event store for persistence
    * @param timeoutStrategy
    *   Strategy for handling state timeouts
    * @param lockingStrategy
    *   Strategy for handling concurrent access
    */
  private def createRuntime[Id, S, E, Cmd](
      id: Id,
      machine: Machine[S, E, Cmd],
      initialState: S,
      store: EventStore[Id, S, E],
      timeoutStrategy: TimeoutStrategy[Id],
      lockingStrategy: LockingStrategy[Id],
  ): ZIO[Any, MechanoidError, FSMRuntimeImpl[Id, S, E, Cmd]] =
    for
      // Load snapshot and events to rebuild state
      snapshot <- store.loadSnapshot(id)
      startState = snapshot.map(_.state).getOrElse(initialState)
      startSeqNr = snapshot.map(_.sequenceNr).getOrElse(0L)

      // Collect events to replay
      events <- store.loadEventsFrom(id, startSeqNr).runCollect

      // Rebuild state by applying events
      rebuiltState <- rebuildState(machine, startState, events.toList)

      // Initialize runtime state
      stateRef   <- Ref.make(rebuiltState)
      seqNrRef   <- Ref.make(events.lastOption.map(_.sequenceNr).getOrElse(startSeqNr))
      runningRef <- Ref.make(true)

      // Create self-reference for timeout handling
      runtimeRef <- Ref.make[Option[FSMRuntimeImpl[Id, S, E, Cmd]]](None)

      sendSelf: (E => ZIO[Any, MechanoidError, TransitionOutcome[S, Cmd]]) =
        (event: E) =>
          runtimeRef.get.flatMap(
            _.map(_.sendInternal(event))
              .getOrElse(ZIO.fail(FSMStoppedError(Some("Runtime not initialized"))))
          )

      runtime = new FSMRuntimeImpl(
        id,
        machine,
        store,
        timeoutStrategy,
        lockingStrategy,
        stateRef,
        seqNrRef,
        runningRef,
        sendSelf,
      )

      _ <- runtimeRef.set(Some(runtime))

      // Run entry action for initial state (only if this is a new instance, not recovery)
      _ <- ZIO.when(events.isEmpty && snapshot.isEmpty)(runtime.runEntryAction(initialState).ignore)

      // Start timeout for current state if configured
      _ <- runtime.startTimeout(rebuiltState.current)
    yield runtime

  /** Rebuild FSM state by replaying events.
    *
    * This applies each event in sequence to reconstruct the current state. Transition actions ARE executed to determine
    * the target state. Entry/exit actions are NOT executed during replay.
    *
    * @throws EventReplayError
    *   if an event doesn't match the current FSM definition
    */
  private def rebuildState[S, E, Cmd](
      machine: Machine[S, E, Cmd],
      startState: S,
      events: List[StoredEvent[?, E]],
  ): ZIO[Any, EventReplayError, FSMState[S]] =
    ZIO.foldLeft(events)(FSMState.initial(startState)) { (fsmState, stored) =>
      val currentCaseHash = machine.stateEnum.caseHash(fsmState.current)
      val eventCaseHash   = machine.eventEnum.caseHash(stored.event)
      machine.transitions.get((currentCaseHash, eventCaseHash)) match
        case Some(transition) =>
          // Execute the transition action to get the result
          // During replay, errors are ignored (events were already validated when stored)
          transition
            .action(fsmState.current, stored.event)
            .catchAll(_ => ZIO.succeed(TransitionResult.Stay))
            .map {
              case TransitionResult.Goto(newState) =>
                fsmState.transitionTo(newState, stored.timestamp)
              case _ => fsmState
            }
        case None =>
          // Event doesn't match current FSM definition - fail explicitly
          ZIO.fail(EventReplayError(fsmState.current, stored.event, stored.sequenceNr))
      end match
    }
end FSMRuntime

/** Internal implementation of FSMRuntime.
  *
  * All FSM runtimes (in-memory and persistent) use this same implementation, differing only in the EventStore,
  * TimeoutStrategy, and LockingStrategy provided.
  */
private[mechanoid] final class FSMRuntimeImpl[Id, S, E, Cmd](
    val instanceId: Id,
    machine: Machine[S, E, Cmd],
    store: EventStore[Id, S, E],
    timeoutStrategy: TimeoutStrategy[Id],
    lockingStrategy: LockingStrategy[Id],
    stateRef: Ref[FSMState[S]],
    seqNrRef: Ref[Long],
    runningRef: Ref[Boolean],
    sendSelf: E => ZIO[Any, MechanoidError, TransitionOutcome[S, Cmd]],
) extends FSMRuntime[Id, S, E, Cmd]:

  override def send(event: E): ZIO[Any, MechanoidError, TransitionOutcome[S, Cmd]] =
    sendSelf(event) // Direct call, no .timed wrapping

  private[mechanoid] def sendInternal(
      event: E
  ): ZIO[Any, MechanoidError, TransitionOutcome[S, Cmd]] =
    lockingStrategy.withLock(
      instanceId,
      for
        running <- runningRef.get
        result  <-
          if !running then ZIO.succeed(TransitionOutcome(TransitionResult.Stop(Some("FSM stopped")), Nil, Nil))
          else processEvent(event)
      yield result,
    )

  private def processEvent(
      event: E
  ): ZIO[Any, MechanoidError, TransitionOutcome[S, Cmd]] =
    for
      fsmState <- stateRef.get
      currentState    = fsmState.current
      currentCaseHash = machine.stateEnum.caseHash(currentState)
      eventCaseHash   = machine.eventEnum.caseHash(event)
      transition <- ZIO
        .fromOption(machine.transitions.get((currentCaseHash, eventCaseHash)))
        .orElseFail(InvalidTransitionError(currentState, event))
      outcome <- executeTransition(fsmState, event, transition, currentCaseHash, eventCaseHash)
    yield outcome

  private def executeTransition(
      fsmState: FSMState[S],
      event: E,
      transition: Transition[S, E, S],
      stateHash: Int,
      eventHash: Int,
  ): ZIO[Any, MechanoidError, TransitionOutcome[S, Cmd]] =
    for
      // Generate PRE-transition commands (before state change)
      preCommands <- ZIO.succeed {
        machine.preCommandFactories
          .get((stateHash, eventHash))
          .map { factory =>
            // Events are just E now - no unwrapping needed
            factory(event, fsmState.current).asInstanceOf[List[Cmd]]
          }
          .getOrElse(Nil)
      }

      // Execute the transition action FIRST
      // If it fails (e.g., external service call), the event is NOT persisted
      result <- transition.action(fsmState.current, event)

      // Only persist after successful action execution
      // Use optimistic locking to detect concurrent modifications
      currentSeqNr <- seqNrRef.get
      seqNr        <- store.append(instanceId, event, currentSeqNr)
      _            <- seqNrRef.set(seqNr)

      // Determine target state for post-commands
      targetState = result match
        case TransitionResult.Goto(s) => s
        case _                        => fsmState.current

      // Generate POST-transition commands (after state change)
      postCommands <- ZIO.succeed {
        machine.postCommandFactories
          .get((stateHash, eventHash))
          .map { factory =>
            // Events are just E now - no unwrapping needed
            factory(event, targetState).asInstanceOf[List[Cmd]]
          }
          .getOrElse(Nil)
      }

      // Update state
      _ <- handleTransitionResult(fsmState, result)
    yield TransitionOutcome(result, preCommands, postCommands)

  private def handleTransitionResult(
      fsmState: FSMState[S],
      result: TransitionResult[S],
  ): ZIO[Any, MechanoidError, Unit] =
    result match
      case TransitionResult.Goto(newState) =>
        for
          // Cancel any pending timeout for the current state
          _ <- cancelTimeout
          // Run exit action for current state
          _ <- runExitAction(fsmState.current)
          // Update state
          now = Instant.now()
          _ <- stateRef.update(_.transitionTo(newState, now))
          // Run entry action for new state
          _ <- runEntryAction(newState)
          // Start timeout for new state if configured
          _ <- startTimeout(newState)
        yield ()

      case TransitionResult.Stay =>
        ZIO.unit

      case TransitionResult.Stop(_) =>
        for
          _ <- cancelTimeout
          _ <- runExitAction(fsmState.current)
          _ <- runningRef.set(false)
        yield ()

  private[mechanoid] def runEntryAction(state: S): ZIO[Any, MechanoidError, Unit] =
    machine.lifecycles.get(machine.stateEnum.caseHash(state)).flatMap(_.onEntry).getOrElse(ZIO.unit)

  private def runExitAction(state: S): ZIO[Any, MechanoidError, Unit] =
    machine.lifecycles.get(machine.stateEnum.caseHash(state)).flatMap(_.onExit).getOrElse(ZIO.unit)

  /** Cancel any pending timeout for this FSM instance.
    *
    * Delegates to the [[TimeoutStrategy]] to handle cancellation.
    */
  private def cancelTimeout: ZIO[Any, Nothing, Unit] =
    timeoutStrategy.cancel(instanceId)

  /** Start a timeout for the given state.
    *
    * Delegates to the [[TimeoutStrategy]] to schedule the timeout. The callback checks that the FSM is still in the
    * same state before firing the timeout event.
    *
    * @param state
    *   The state to start a timeout for
    */
  private[mechanoid] def startTimeout(state: S): ZIO[Any, Nothing, Unit] =
    val stateCaseHash = machine.stateEnum.caseHash(state)
    // Get both duration and user-defined timeout event for this state
    val timeoutConfig = for
      duration <- machine.timeouts.get(stateCaseHash)
      event    <- machine.timeoutEvents.get(stateCaseHash)
    yield (duration, event)

    ZIO.foreachDiscard(timeoutConfig) { case (duration, timeoutEvent) =>
      // Create callback that fires timeout event if still in same state
      val onTimeout: UIO[Unit] = stateRef.get.flatMap { currentFsmState =>
        // Compare by caseHash (shape) not exact value - timeout fires if still in same state shape
        ZIO
          .when(machine.stateEnum.caseHash(currentFsmState.current) == stateCaseHash)(
            send(timeoutEvent).ignore
          )
          .unit
      }
      timeoutStrategy.schedule(instanceId, state.toString, duration, onTimeout)
    }
  end startTimeout

  override def currentState: UIO[S] = stateRef.get.map(_.current)

  override def state: UIO[FSMState[S]] = stateRef.get

  override def history: UIO[List[S]] = stateRef.get.map(_.history)

  override def lastSequenceNr: UIO[Long] = seqNrRef.get

  override def stop: UIO[Unit] = runningRef.set(false)

  override def stop(reason: String): UIO[Unit] = runningRef.set(false)

  override def isRunning: UIO[Boolean] = runningRef.get

  override def timeoutConfigForState(state: S): Option[(Duration, E)] =
    val stateCaseHash = machine.stateEnum.caseHash(state)
    for
      duration <- machine.timeouts.get(stateCaseHash)
      event    <- machine.timeoutEvents.get(stateCaseHash)
    yield (duration, event)

  override def saveSnapshot: ZIO[Any, MechanoidError, Unit] =
    for
      fsmState <- stateRef.get
      seqNr    <- seqNrRef.get
      snapshot = FSMSnapshot(
        instanceId = instanceId,
        state = fsmState.current,
        sequenceNr = seqNr,
        timestamp = Instant.now(),
      )
      _ <- store.saveSnapshot(snapshot)
    yield ()
end FSMRuntimeImpl
