package mechanoid.runtime

import zio.*
import mechanoid.core.*
import mechanoid.dsl.FSMDefinition
import java.time.Instant

private[runtime] final class FSMRuntimeImpl[S <: MState, E <: MEvent, R, Err](
    definition: FSMDefinition[S, E, R, Err],
    stateRef: Ref[FSMState[S]],
    runningRef: Ref[Boolean],
    sendSelf: Timed[E] => ZIO[R, Err | MechanoidError, TransitionResult[S]],
) extends FSMRuntime[S, E, R, Err]:

  override def send(
      event: E
  ): ZIO[R, Err | MechanoidError, TransitionResult[S]] =
    sendSelf(event.timed)

  private[runtime] def sendInternal(
      event: Timed[E]
  ): ZIO[R, Err | MechanoidError, TransitionResult[S]] =
    for
      running <- runningRef.get
      result  <-
        if !running then ZIO.succeed(TransitionResult.Stop(Some("FSM stopped")))
        else processEvent(event)
    yield result

  private def processEvent(
      event: Timed[E]
  ): ZIO[R, Err | MechanoidError, TransitionResult[S]] =
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
      transition: Transition[S, Timed[E], S, R, Err],
  ): ZIO[R, Err | MechanoidError, TransitionResult[S]] =
    for
      // Check guard if present - use fold to preserve types
      _ <- transition.guard.fold(ZIO.unit)(
        _.filterOrFail(identity)(GuardRejectedError(fsmState.current, event: MEvent)).unit
      )
      result <- transition.action
      _      <- handleTransitionResult(fsmState, result)
    yield result

  private def handleTransitionResult(
      fsmState: FSMState[S],
      result: TransitionResult[S],
  ): ZIO[R, Err | MechanoidError, Unit] =
    result match
      case TransitionResult.Goto(newState) =>
        for
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

      case TransitionResult.Stop(reason) =>
        for
          _ <- runExitAction(fsmState.current)
          _ <- runningRef.set(false)
        yield ()

  private def runEntryAction(state: S): ZIO[R, Err, Unit] =
    definition.lifecycles.get(definition.stateEnum.ordinal(state)).flatMap(_.onEntry).getOrElse(ZIO.unit)

  private def runExitAction(state: S): ZIO[R, Err, Unit] =
    definition.lifecycles.get(definition.stateEnum.ordinal(state)).flatMap(_.onExit).getOrElse(ZIO.unit)

  /** Start a timeout for the given state.
    *
    * Uses a simple approach: fork a fiber that sleeps, then checks if we're still in the same state shape. If yes, send
    * the timeout event. If the state shape has changed, the timeout is effectively cancelled (no-op).
    *
    * This avoids the complexity of manually tracking and interrupting fibers.
    */
  private[runtime] def startTimeout(state: S): ZIO[R, Nothing, Unit] =
    val stateOrd = definition.stateEnum.ordinal(state)
    ZIO.foreachDiscard(definition.timeouts.get(stateOrd)) { duration =>
      (ZIO.sleep(zio.Duration.fromScala(duration)) *>
        stateRef.get.flatMap { currentFsmState =>
          // Compare by ordinal (shape) not exact value - timeout fires if still in same state shape
          ZIO.when(definition.stateEnum.ordinal(currentFsmState.current) == stateOrd)(
            sendInternal(Timed.TimeoutEvent).ignore
          )
        }).forkDaemon
    }
  end startTimeout

  override def currentState: UIO[S] = stateRef.get.map(_.current)

  override def state: UIO[FSMState[S]] = stateRef.get

  override def history: UIO[List[S]] = stateRef.get.map(_.history)

  override def stop: UIO[Unit] = runningRef.set(false)

  override def stop(reason: String): UIO[Unit] = runningRef.set(false)

  override def isRunning: UIO[Boolean] = runningRef.get
end FSMRuntimeImpl

object FSMRuntimeImpl:
  def make[S <: MState, E <: MEvent, R, Err](
      definition: FSMDefinition[S, E, R, Err],
      initial: S,
  ): ZIO[R & Scope, Nothing, FSMRuntime[S, E, R, Err]] =
    ZIO.acquireRelease(createRuntime(definition, initial))(_.stop)

  /** Create and initialize an FSM runtime.
    *
    * This is separated from `make` to make the acquire/release pattern clear.
    */
  private def createRuntime[S <: MState, E <: MEvent, R, Err](
      definition: FSMDefinition[S, E, R, Err],
      initial: S,
  ): ZIO[R, Nothing, FSMRuntimeImpl[S, E, R, Err]] =
    for
      stateRef   <- Ref.make(FSMState.initial(initial))
      runningRef <- Ref.make(true)

      // Create the runtime with self-reference for timeout handling
      runtimeRef <- Ref.make[Option[FSMRuntimeImpl[S, E, R, Err]]](None)

      sendSelf: (
          Timed[E] => ZIO[R, Err | MechanoidError, TransitionResult[S]]
      ) =
        (event: Timed[E]) =>
          runtimeRef.get.flatMap(
            _.map(_.sendInternal(event))
              .getOrElse(ZIO.fail(FSMStoppedError(Some("Runtime not initialized"))))
          )

      runtime = new FSMRuntimeImpl(
        definition,
        stateRef,
        runningRef,
        sendSelf,
      )

      _ <- runtimeRef.set(Some(runtime))

      // Run entry action for initial state
      _ <- runtime.runEntryAction(initial).ignore

      // Start timeout for initial state if configured
      _ <- runtime.startTimeout(initial)
    yield runtime
end FSMRuntimeImpl
