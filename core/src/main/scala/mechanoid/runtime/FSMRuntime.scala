package mechanoid.runtime

import zio.*
import mechanoid.core.*
import mechanoid.dsl.FSMDefinition
import mechanoid.persistence.{EventStore, FSMSnapshot, StoredEvent}
import mechanoid.persistence.timeout.TimeoutStore
import mechanoid.persistence.lock.{FSMInstanceLock, LockConfig, LockedFSMRuntime}
import mechanoid.stores.InMemoryEventStore
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
trait FSMRuntime[Id, S <: MState, E <: MEvent, Cmd]:

  /** The FSM instance identifier. */
  def instanceId: Id

  /** Send an event to the FSM and get the transition result.
    *
    * Returns the outcome of processing the event:
    *   - Stay: FSM remained in current state
    *   - Goto: FSM transitioned to a new state
    *   - Stop: FSM has stopped
    *
    * If no transition is defined for the current state and event, returns an InvalidTransitionError.
    */
  def send(event: E): ZIO[Any, MechanoidError, TransitionResult[S]]

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
    * val definition = fsm[TrafficLight, TrafficEvent]
    *   .when(Red).on(Timer).goto(Green)
    *   .when(Green).on(Timer).goto(Yellow)
    *   .when(Yellow).on(Timer).goto(Red)
    *
    * val program = ZIO.scoped {
    *   for
    *     fsm   <- FSMRuntime.make(definition, Red)
    *     _     <- fsm.send(Timer)
    *     state <- fsm.currentState  // Green
    *   yield state
    * }
    * }}}
    *
    * @param definition
    *   The FSM definition
    * @param initial
    *   The initial state
    */
  def make[S <: MState, E <: MEvent, Cmd](
      definition: FSMDefinition[S, E, Cmd],
      initial: S,
  ): ZIO[Scope, MechanoidError, FSMRuntime[Unit, S, E, Cmd]] =
    for
      eventStore <- InMemoryEventStore.make[Unit, S, E]
      runtime    <- ZIO.acquireRelease(
        createRuntime((), definition, initial, eventStore, None)
      )(_.stop)
    yield runtime

  // ============================================
  // Persistent FSM with EventStore from environment
  // ============================================

  /** Create a persistent FSM runtime, pulling EventStore from the environment.
    *
    * Provide the EventStore via ZLayer:
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
    * // Use the FSM with the store from the environment
    * val program = ZIO.scoped {
    *   for
    *     fsm <- FSMRuntime(orderId, orderDefinition, Pending)
    *     _   <- fsm.send(Pay)
    *   yield ()
    * }.provide(storeLayer, dataSourceLayer)
    * }}}
    *
    * This will:
    *   1. Load the latest snapshot (if any)
    *   2. Replay events since the snapshot to rebuild state
    *   3. Continue processing new events, persisting each one
    *
    * @param id
    *   The FSM instance identifier
    * @param definition
    *   The FSM definition
    * @param initialState
    *   The initial state for new instances
    */
  def apply[Id, S <: MState, E <: MEvent, Cmd](
      id: Id,
      definition: FSMDefinition[S, E, Cmd],
      initialState: S,
  )(using
      Tag[EventStore[Id, S, E]]
  ): ZIO[Scope & EventStore[Id, S, E], MechanoidError, FSMRuntime[Id, S, E, Cmd]] =
    ZIO.serviceWithZIO[EventStore[Id, S, E]] { store =>
      ZIO.acquireRelease(
        createRuntime(id, definition, initialState, store, None)
      )(_.stop)
    }

  // ============================================
  // Persistent FSM with durable timeouts
  // ============================================

  /** Create a persistent FSM runtime with durable timeouts.
    *
    * Unlike the standard [[apply]] method, this version persists timeout deadlines to a [[TimeoutStore]]. Combined with
    * a [[mechanoid.persistence.timeout.TimeoutSweeper]], this ensures timeouts fire even if the node processing the FSM
    * crashes.
    *
    * ==How It Works==
    *
    *   1. When entering a state with a timeout, the deadline is persisted to TimeoutStore
    *   2. When exiting a state, the timeout is cancelled in TimeoutStore
    *   3. The TimeoutSweeper periodically queries for expired timeouts and fires them
    *   4. In-memory fiber-based timeouts are NOT used (sweeper handles everything)
    *
    * ==Usage==
    *
    * {{{
    * // Layers
    * val eventStoreLayer: ZLayer[..., EventStore[...]] = ???
    * val timeoutStoreLayer: ZLayer[..., TimeoutStore[...]] = ???
    *
    * // Create FSM with durable timeouts
    * val program = ZIO.scoped {
    *   for
    *     fsm <- FSMRuntime.withDurableTimeouts(orderId, orderDef, Pending)
    *     _   <- fsm.send(StartPayment)
    *     // Timeout is now persisted - survives node restart
    *   yield ()
    * }.provide(eventStoreLayer, timeoutStoreLayer)
    *
    * // Don't forget to run the sweeper somewhere!
    * val sweeper = TimeoutSweeper.make(config, timeoutStore, onTimeout)
    * }}}
    *
    * @param id
    *   The FSM instance identifier
    * @param definition
    *   The FSM definition
    * @param initialState
    *   The initial state for new instances
    */
  def withDurableTimeouts[Id, S <: MState, E <: MEvent, Cmd](
      id: Id,
      definition: FSMDefinition[S, E, Cmd],
      initialState: S,
  )(using
      Tag[EventStore[Id, S, E]],
      Tag[TimeoutStore[Id]],
  ): ZIO[Scope & EventStore[Id, S, E] & TimeoutStore[Id], MechanoidError, FSMRuntime[Id, S, E, Cmd]] =
    for
      eventStore   <- ZIO.service[EventStore[Id, S, E]]
      timeoutStore <- ZIO.service[TimeoutStore[Id]]
      runtime      <- ZIO.acquireRelease(
        createRuntime(id, definition, initialState, eventStore, Some(timeoutStore))
      )(_.stop)
    yield runtime

  // ============================================
  // Persistent FSM with distributed locking
  // ============================================

  /** Create a persistent FSM runtime with distributed locking.
    *
    * This variant wraps event processing with distributed locks to ensure '''exactly-once''' transition semantics. Only
    * one node can process events for a given FSM instance at a time.
    *
    * ==Why Use Locking?==
    *
    * Without locking:
    *   - Multiple nodes can process events concurrently
    *   - Conflicts detected ''after'' via optimistic locking (wasted work)
    *
    * With locking:
    *   - Only one node processes at a time
    *   - No wasted work from rejected writes
    *   - Guaranteed exactly-once delivery per event
    *
    * ==Node Failure Resilience==
    *
    * Locks are lease-based and automatically expire:
    *   1. Node A acquires lock, starts processing
    *   2. Node A crashes
    *   3. Lock expires after `lockDuration` (default: 30 seconds)
    *   4. Node B acquires lock, continues processing
    *
    * ==Usage==
    *
    * {{{
    * val program = ZIO.scoped {
    *   for
    *     fsm <- FSMRuntime.withLocking(orderId, orderDef, Pending)
    *     _   <- fsm.send(Pay)  // Lock acquired automatically
    *   yield ()
    * }.provide(eventStoreLayer, lockLayer)
    * }}}
    *
    * @param id
    *   The FSM instance identifier
    * @param definition
    *   The FSM definition
    * @param initialState
    *   The initial state for new instances
    * @param lockConfig
    *   Lock configuration (duration, timeout, etc.)
    */
  def withLocking[Id, S <: MState, E <: MEvent, Cmd](
      id: Id,
      definition: FSMDefinition[S, E, Cmd],
      initialState: S,
      lockConfig: LockConfig = LockConfig.default,
  )(using
      Tag[EventStore[Id, S, E]],
      Tag[FSMInstanceLock[Id]],
  ): ZIO[
    Scope & EventStore[Id, S, E] & FSMInstanceLock[Id],
    MechanoidError,
    FSMRuntime[Id, S, E, Cmd],
  ] =
    for
      eventStore <- ZIO.service[EventStore[Id, S, E]]
      lock       <- ZIO.service[FSMInstanceLock[Id]]
      runtime    <- ZIO.acquireRelease(
        createRuntime(id, definition, initialState, eventStore, None)
      )(_.stop)
    yield LockedFSMRuntime(runtime, lock, lockConfig)

  // ============================================
  // Persistent FSM with locking AND durable timeouts
  // ============================================

  /** Create a persistent FSM runtime with locking and durable timeouts.
    *
    * This is the most robust configuration, combining:
    *   - '''Distributed locking''' for exactly-once transitions
    *   - '''Durable timeouts''' that survive node failures
    *
    * ==Usage==
    *
    * {{{
    * val program = ZIO.scoped {
    *   for
    *     fsm <- FSMRuntime.withLockingAndTimeouts(orderId, orderDef, Pending)
    *     _   <- fsm.send(StartPayment)
    *     // - Lock protects against concurrent processing
    *     // - Timeout persisted and survives node restart
    *   yield ()
    * }.provide(eventStoreLayer, timeoutStoreLayer, lockLayer)
    * }}}
    *
    * @param id
    *   The FSM instance identifier
    * @param definition
    *   The FSM definition
    * @param initialState
    *   The initial state for new instances
    * @param lockConfig
    *   Lock configuration (duration, timeout, etc.)
    */
  def withLockingAndTimeouts[Id, S <: MState, E <: MEvent, Cmd](
      id: Id,
      definition: FSMDefinition[S, E, Cmd],
      initialState: S,
      lockConfig: LockConfig = LockConfig.default,
  )(using
      Tag[EventStore[Id, S, E]],
      Tag[TimeoutStore[Id]],
      Tag[FSMInstanceLock[Id]],
  ): ZIO[
    Scope & EventStore[Id, S, E] & TimeoutStore[Id] & FSMInstanceLock[Id],
    MechanoidError,
    FSMRuntime[Id, S, E, Cmd],
  ] =
    for
      eventStore   <- ZIO.service[EventStore[Id, S, E]]
      timeoutStore <- ZIO.service[TimeoutStore[Id]]
      lock         <- ZIO.service[FSMInstanceLock[Id]]
      runtime      <- ZIO.acquireRelease(
        createRuntime(id, definition, initialState, eventStore, Some(timeoutStore))
      )(_.stop)
    yield LockedFSMRuntime(runtime, lock, lockConfig)

  // ============================================
  // Implementation
  // ============================================

  /** Create and initialize an FSM runtime.
    *
    * This is the core factory method used by all the public factory methods above.
    */
  private def createRuntime[Id, S <: MState, E <: MEvent, Cmd](
      id: Id,
      definition: FSMDefinition[S, E, Cmd],
      initialState: S,
      store: EventStore[Id, S, E],
      timeoutStore: Option[TimeoutStore[Id]],
  ): ZIO[Any, MechanoidError, FSMRuntimeImpl[Id, S, E, Cmd]] =
    for
      // Load snapshot and events to rebuild state
      snapshot <- store.loadSnapshot(id)
      startState = snapshot.map(_.state).getOrElse(initialState)
      startSeqNr = snapshot.map(_.sequenceNr).getOrElse(0L)

      // Collect events to replay
      events <- store.loadEventsFrom(id, startSeqNr).runCollect

      // Rebuild state by applying events
      rebuiltState <- rebuildState(definition, startState, events.toList)

      // Initialize runtime state
      stateRef   <- Ref.make(rebuiltState)
      seqNrRef   <- Ref.make(events.lastOption.map(_.sequenceNr).getOrElse(startSeqNr))
      runningRef <- Ref.make(true)

      // Create self-reference for timeout handling
      runtimeRef <- Ref.make[Option[FSMRuntimeImpl[Id, S, E, Cmd]]](None)

      sendSelf: (Timed[E] => ZIO[Any, MechanoidError, TransitionResult[S]]) =
        (event: Timed[E]) =>
          runtimeRef.get.flatMap(
            _.map(_.sendInternal(event))
              .getOrElse(ZIO.fail(FSMStoppedError(Some("Runtime not initialized"))))
          )

      runtime = new FSMRuntimeImpl(
        id,
        definition,
        store,
        timeoutStore,
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
  private def rebuildState[S <: MState, E <: MEvent, Cmd](
      definition: FSMDefinition[S, E, Cmd],
      startState: S,
      events: List[StoredEvent[?, Timed[E]]],
  ): ZIO[Any, EventReplayError, FSMState[S]] =
    ZIO.foldLeft(events)(FSMState.initial(startState)) { (fsmState, stored) =>
      val currentCaseHash = definition.stateEnum.caseHash(fsmState.current)
      val eventCaseHash   = definition.eventEnum.caseHash(stored.event)
      definition.transitions.get((currentCaseHash, eventCaseHash)) match
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
          ZIO.fail(EventReplayError(fsmState.current, stored.event: MEvent, stored.sequenceNr))
      end match
    }
end FSMRuntime

/** Internal implementation of FSMRuntime.
  *
  * All FSM runtimes (in-memory and persistent) use this same implementation, differing only in the EventStore and
  * optional stores provided.
  */
private[mechanoid] final class FSMRuntimeImpl[Id, S <: MState, E <: MEvent, Cmd](
    val instanceId: Id,
    definition: FSMDefinition[S, E, Cmd],
    store: EventStore[Id, S, E],
    timeoutStore: Option[TimeoutStore[Id]],
    stateRef: Ref[FSMState[S]],
    seqNrRef: Ref[Long],
    runningRef: Ref[Boolean],
    sendSelf: Timed[E] => ZIO[Any, MechanoidError, TransitionResult[S]],
) extends FSMRuntime[Id, S, E, Cmd]:

  override def send(event: E): ZIO[Any, MechanoidError, TransitionResult[S]] =
    sendSelf(event.timed)

  private[mechanoid] def sendInternal(
      event: Timed[E]
  ): ZIO[Any, MechanoidError, TransitionResult[S]] =
    for
      running <- runningRef.get
      result  <-
        if !running then ZIO.succeed(TransitionResult.Stop(Some("FSM stopped")))
        else processEvent(event)
    yield result

  private def processEvent(
      event: Timed[E]
  ): ZIO[Any, MechanoidError, TransitionResult[S]] =
    for
      fsmState <- stateRef.get
      currentState    = fsmState.current
      currentCaseHash = definition.stateEnum.caseHash(currentState)
      eventCaseHash   = definition.eventEnum.caseHash(event)
      transition <- ZIO
        .fromOption(definition.transitions.get((currentCaseHash, eventCaseHash)))
        .orElseFail(InvalidTransitionError(currentState, event: MEvent))
      result <- executeTransition(fsmState, event, transition)
    yield result

  private def executeTransition(
      fsmState: FSMState[S],
      event: Timed[E],
      transition: Transition[S, Timed[E], S],
  ): ZIO[Any, MechanoidError, TransitionResult[S]] =
    for
      // Execute the transition action FIRST
      // If it fails (e.g., external service call), the event is NOT persisted
      result <- transition.action(fsmState.current, event)
      // Only persist after successful action execution
      // Use optimistic locking to detect concurrent modifications
      currentSeqNr <- seqNrRef.get
      seqNr        <- store.append(instanceId, event, currentSeqNr)
      _            <- seqNrRef.set(seqNr)
      // Update state
      _ <- handleTransitionResult(fsmState, result)
    yield result

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
    definition.lifecycles.get(definition.stateEnum.caseHash(state)).flatMap(_.onEntry).getOrElse(ZIO.unit)

  private def runExitAction(state: S): ZIO[Any, MechanoidError, Unit] =
    definition.lifecycles.get(definition.stateEnum.caseHash(state)).flatMap(_.onExit).getOrElse(ZIO.unit)

  /** Cancel any pending timeout for this FSM instance.
    *
    * When using durable timeouts (TimeoutStore), this removes the timeout from the store. For in-memory timeouts, no
    * explicit cancellation is needed since the fiber checks state before firing.
    */
  private def cancelTimeout: ZIO[Any, Nothing, Unit] =
    timeoutStore.fold(ZIO.unit)(_.cancel(instanceId).ignore)

  /** Start a timeout for the given state.
    *
    * When using durable timeouts (TimeoutStore is present):
    *   - Persists the timeout deadline to the store
    *   - The TimeoutSweeper will fire it when it expires
    *   - Survives node failures
    *
    * When using in-memory timeouts (TimeoutStore is None):
    *   - Forks a fiber that sleeps, then fires if still in same state shape
    *   - Does NOT survive node failures
    */
  private[mechanoid] def startTimeout(state: S): ZIO[Any, Nothing, Unit] =
    val stateCaseHash = definition.stateEnum.caseHash(state)
    ZIO.foreachDiscard(definition.timeouts.get(stateCaseHash)) { duration =>
      timeoutStore match
        case Some(store) =>
          // Durable timeout: persist to TimeoutStore
          Clock.instant.flatMap { now =>
            val deadline = now.plusMillis(duration.toMillis)
            store.schedule(instanceId, state.toString, deadline)
          }.ignore

        case None =>
          // In-memory timeout: use fiber-based approach
          (ZIO.sleep(zio.Duration.fromScala(duration)) *>
            stateRef.get.flatMap { currentFsmState =>
              // Compare by caseHash (shape) not exact value - timeout fires if still in same state shape
              ZIO.when(definition.stateEnum.caseHash(currentFsmState.current) == stateCaseHash)(
                sendInternal(Timed.TimeoutEvent).ignore
              )
            }).forkDaemon.unit
    }
  end startTimeout

  override def currentState: UIO[S] = stateRef.get.map(_.current)

  override def state: UIO[FSMState[S]] = stateRef.get

  override def history: UIO[List[S]] = stateRef.get.map(_.history)

  override def lastSequenceNr: UIO[Long] = seqNrRef.get

  override def stop: UIO[Unit] = runningRef.set(false)

  override def stop(reason: String): UIO[Unit] = runningRef.set(false)

  override def isRunning: UIO[Boolean] = runningRef.get

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
