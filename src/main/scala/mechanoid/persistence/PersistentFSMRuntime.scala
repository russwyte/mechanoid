package mechanoid.persistence

import zio.*
import mechanoid.core.*
import mechanoid.dsl.FSMDefinition
import mechanoid.runtime.FSMRuntime
import mechanoid.persistence.timeout.TimeoutStore
import mechanoid.persistence.lock.{FSMInstanceLock, LockConfig, LockedFSMRuntime}
import java.time.Instant

/** A persistent FSM runtime that uses event sourcing.
  *
  * Events are persisted to an EventStore before state changes occur. State can be reconstructed by replaying events
  * from the store.
  *
  * All errors are returned as `MechanoidError`. User errors from lifecycle actions are wrapped in `ActionFailedError`.
  *
  * ==Recovery Behavior==
  *
  * On startup via [[PersistentFSMRuntime.apply]]:
  *   1. Loads the latest snapshot (if any) from the store
  *   2. Replays only events ''after'' the snapshot's sequence number
  *   3. Continues processing new events normally
  *
  * This means recovery time is proportional to events since the last snapshot, not total events. Without snapshots, all
  * events are replayed.
  *
  * ==Snapshotting==
  *
  * '''You control when to snapshot.''' The runtime does NOT snapshot automatically. Call [[saveSnapshot]] based on your
  * application's needs. See [[EventStore]] for snapshotting strategy examples.
  *
  * @tparam Id
  *   The FSM instance identifier type
  * @tparam S
  *   The state type
  * @tparam E
  *   The event type
  */
trait PersistentFSMRuntime[Id, S <: MState, E <: MEvent] extends FSMRuntime[S, E]:

  /** The FSM instance identifier. */
  def instanceId: Id

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
end PersistentFSMRuntime

object PersistentFSMRuntime:

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
    *     fsm <- PersistentFSMRuntime(orderId, orderDefinition, Pending)
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
  def apply[Id, S <: MState, E <: MEvent](
      id: Id,
      definition: FSMDefinition[S, E],
      initialState: S,
  )(using
      Tag[EventStore[Id, S, E]]
  ): ZIO[Scope & EventStore[Id, S, E], MechanoidError, PersistentFSMRuntime[Id, S, E]] =
    ZIO.serviceWithZIO[EventStore[Id, S, E]] { store =>
      ZIO.acquireRelease(
        createRuntime(id, definition, initialState, store, None)
      )(_.stop)
    }

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
    *     fsm <- PersistentFSMRuntime.withDurableTimeouts(orderId, orderDef, Pending)
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
  def withDurableTimeouts[Id, S <: MState, E <: MEvent](
      id: Id,
      definition: FSMDefinition[S, E],
      initialState: S,
  )(using
      Tag[EventStore[Id, S, E]],
      Tag[TimeoutStore[Id]],
  ): ZIO[Scope & EventStore[Id, S, E] & TimeoutStore[Id], MechanoidError, PersistentFSMRuntime[Id, S, E]] =
    for
      eventStore   <- ZIO.service[EventStore[Id, S, E]]
      timeoutStore <- ZIO.service[TimeoutStore[Id]]
      runtime      <- ZIO.acquireRelease(
        createRuntime(id, definition, initialState, eventStore, Some(timeoutStore))
      )(_.stop)
    yield runtime

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
    *     fsm <- PersistentFSMRuntime.withLocking(orderId, orderDef, Pending)
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
  def withLocking[Id, S <: MState, E <: MEvent](
      id: Id,
      definition: FSMDefinition[S, E],
      initialState: S,
      lockConfig: LockConfig = LockConfig.default,
  )(using
      Tag[EventStore[Id, S, E]],
      Tag[FSMInstanceLock[Id]],
  ): ZIO[
    Scope & EventStore[Id, S, E] & FSMInstanceLock[Id],
    MechanoidError,
    PersistentFSMRuntime[Id, S, E],
  ] =
    for
      eventStore <- ZIO.service[EventStore[Id, S, E]]
      lock       <- ZIO.service[FSMInstanceLock[Id]]
      runtime    <- ZIO.acquireRelease(
        createRuntime(id, definition, initialState, eventStore, None)
      )(_.stop)
    yield LockedFSMRuntime(runtime, lock, lockConfig)

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
    *     fsm <- PersistentFSMRuntime.withLockingAndTimeouts(orderId, orderDef, Pending)
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
  def withLockingAndTimeouts[Id, S <: MState, E <: MEvent](
      id: Id,
      definition: FSMDefinition[S, E],
      initialState: S,
      lockConfig: LockConfig = LockConfig.default,
  )(using
      Tag[EventStore[Id, S, E]],
      Tag[TimeoutStore[Id]],
      Tag[FSMInstanceLock[Id]],
  ): ZIO[
    Scope & EventStore[Id, S, E] & TimeoutStore[Id] & FSMInstanceLock[Id],
    MechanoidError,
    PersistentFSMRuntime[Id, S, E],
  ] =
    for
      eventStore   <- ZIO.service[EventStore[Id, S, E]]
      timeoutStore <- ZIO.service[TimeoutStore[Id]]
      lock         <- ZIO.service[FSMInstanceLock[Id]]
      runtime      <- ZIO.acquireRelease(
        createRuntime(id, definition, initialState, eventStore, Some(timeoutStore))
      )(_.stop)
    yield LockedFSMRuntime(runtime, lock, lockConfig)

  /** Create and initialize a persistent FSM runtime.
    *
    * This is separated from `apply` to make the acquire/release pattern clear.
    */
  private def createRuntime[Id, S <: MState, E <: MEvent](
      id: Id,
      definition: FSMDefinition[S, E],
      initialState: S,
      store: EventStore[Id, S, E],
      timeoutStore: Option[TimeoutStore[Id]],
  ): ZIO[Any, MechanoidError, PersistentFSMRuntimeImpl[Id, S, E]] =
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
      runtimeRef <- Ref.make[Option[PersistentFSMRuntimeImpl[Id, S, E]]](None)

      sendSelf: (Timed[E] => ZIO[Any, MechanoidError, TransitionResult[S]]) =
        (event: Timed[E]) =>
          runtimeRef.get.flatMap(
            _.map(_.sendInternal(event))
              .getOrElse(ZIO.fail(FSMStoppedError(Some("Runtime not initialized"))))
          )

      runtime = new PersistentFSMRuntimeImpl(
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
  private def rebuildState[S <: MState, E <: MEvent](
      definition: FSMDefinition[S, E],
      startState: S,
      events: List[StoredEvent[?, Timed[E]]],
  ): ZIO[Any, EventReplayError, FSMState[S]] =
    ZIO.foldLeft(events)(FSMState.initial(startState)) { (fsmState, stored) =>
      val currentOrdinal = definition.stateEnum.ordinal(fsmState.current)
      val eventOrdinal   = definition.eventEnum.ordinal(stored.event)
      definition.transitions.get((currentOrdinal, eventOrdinal)) match
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
end PersistentFSMRuntime

private[persistence] final class PersistentFSMRuntimeImpl[Id, S <: MState, E <: MEvent](
    val instanceId: Id,
    definition: FSMDefinition[S, E],
    store: EventStore[Id, S, E],
    timeoutStore: Option[TimeoutStore[Id]],
    stateRef: Ref[FSMState[S]],
    seqNrRef: Ref[Long],
    runningRef: Ref[Boolean],
    sendSelf: Timed[E] => ZIO[Any, MechanoidError, TransitionResult[S]],
) extends PersistentFSMRuntime[Id, S, E]:

  override def send(event: E): ZIO[Any, MechanoidError, TransitionResult[S]] =
    sendSelf(event.timed)

  private[persistence] def sendInternal(
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
      currentState   = fsmState.current
      currentOrdinal = definition.stateEnum.ordinal(currentState)
      eventOrdinal   = definition.eventEnum.ordinal(event)
      transition <- ZIO
        .fromOption(definition.transitions.get((currentOrdinal, eventOrdinal)))
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

  private def runEntryAction(state: S): ZIO[Any, MechanoidError, Unit] =
    definition.lifecycles.get(definition.stateEnum.ordinal(state)).flatMap(_.onEntry).getOrElse(ZIO.unit)

  private def runExitAction(state: S): ZIO[Any, MechanoidError, Unit] =
    definition.lifecycles.get(definition.stateEnum.ordinal(state)).flatMap(_.onExit).getOrElse(ZIO.unit)

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
  private[persistence] def startTimeout(state: S): ZIO[Any, Nothing, Unit] =
    val stateOrd = definition.stateEnum.ordinal(state)
    ZIO.foreachDiscard(definition.timeouts.get(stateOrd)) { duration =>
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
              // Compare by ordinal (shape) not exact value - timeout fires if still in same state shape
              ZIO.when(definition.stateEnum.ordinal(currentFsmState.current) == stateOrd)(
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
end PersistentFSMRuntimeImpl
