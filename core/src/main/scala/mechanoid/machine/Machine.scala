package mechanoid.machine

import zio.*
import mechanoid.core.*
import mechanoid.runtime.FSMRuntime
import mechanoid.visualization.{TransitionMeta, TransitionKind}
import scala.concurrent.duration.Duration

/** A finite state machine definition using the suite-style DSL.
  *
  * Machine holds all runtime data for an FSM:
  *   - Transitions map (state hash, event hash) → action
  *   - State lifecycles (entry/exit actions)
  *   - State timeouts
  *   - Command factories for the transactional outbox pattern
  *   - Visualization metadata
  *
  * @tparam S
  *   The state type (must extend MState and be sealed)
  * @tparam E
  *   The event type (must extend MEvent and be sealed)
  * @tparam Cmd
  *   The command type for transactional outbox pattern (use Nothing for FSMs without commands)
  */
final class Machine[S, E, Cmd] private[machine] (
    // Runtime data - events are just E now (no Timed wrapper)
    private[mechanoid] val transitions: Map[(Int, Int), Transition[S, E, S]],
    private[mechanoid] val lifecycles: Map[Int, StateLifecycle[S, Cmd]],
    private[mechanoid] val timeouts: Map[Int, Duration],
    private[mechanoid] val timeoutEvents: Map[Int, E], // state hash -> event to fire on timeout
    private[mechanoid] val transitionMeta: List[TransitionMeta],
    // Command factories for transactional outbox pattern
    private[mechanoid] val preCommandFactories: Map[(Int, Int), (Any, Any) => List[Any]],
    private[mechanoid] val postCommandFactories: Map[(Int, Int), (Any, Any) => List[Any]],
    // Spec data for compile-time validation and introspection
    private[machine] val specs: List[TransitionSpec[S, E, Cmd]],
    private[machine] val timeoutSpecs: List[TimeoutSpec[S, E]],
)(using
    private[mechanoid] val stateEnum: Finite[S],
    private[mechanoid] val eventEnum: Finite[E], // Just E, no Timed wrapper
):

  /** Apply an aspect to ALL transition specs in this machine.
    *
    * Usage: `myMachine @@ overriding` marks all transitions as overrides.
    */
  def @@(aspect: Aspect): Machine[S, E, Cmd] = aspect match
    case Aspect.overriding =>
      val newSpecs = specs.map(_.copy(isOverride = true))
      new Machine(
        transitions,
        lifecycles,
        timeouts,
        timeoutEvents,
        transitionMeta,
        preCommandFactories,
        postCommandFactories,
        newSpecs,
        timeoutSpecs,
      )
    case _: Aspect.timeout[?] =>
      // timeout aspect doesn't apply to machines (only individual states)
      this

  /** Build and start the FSM runtime with the given initial state.
    *
    * Creates an in-memory FSM runtime suitable for testing or single-process use.
    */
  def start(initial: S): ZIO[Scope, MechanoidError, FSMRuntime[Unit, S, E, Cmd]] =
    FSMRuntime.make(this, initial)

  /** Get state names for visualization (caseHash -> name). */
  def stateNames: Map[Int, String] = stateEnum.caseNames

  /** Get event names for visualization (caseHash -> name, includes Timeout). */
  def eventNames: Map[Int, String] = eventEnum.caseNames

  /** Add an entry action for a state.
    *
    * The action runs when the FSM enters the specified state. Multiple calls for the same state will replace the
    * previous action.
    *
    * Usage:
    * {{{
    * val machine = build[State, Event](
    *   Idle via Start to Running
    * ).withEntry(Running)(ZIO.logInfo("Entered running state"))
    * }}}
    */
  def withEntry(state: S)(action: ZIO[Any, MechanoidError, Unit]): Machine[S, E, Cmd] =
    val stateHash = stateEnum.caseHash(state)
    updateLifecycle(stateHash, lc => lc.copy(onEntry = Some(action)))

  /** Add an exit action for a state.
    *
    * The action runs when the FSM exits the specified state. Multiple calls for the same state will replace the
    * previous action.
    *
    * Usage:
    * {{{
    * val machine = build[State, Event](
    *   Idle via Start to Running
    * ).withExit(Idle)(ZIO.logInfo("Exited idle state"))
    * }}}
    */
  def withExit(state: S)(action: ZIO[Any, MechanoidError, Unit]): Machine[S, E, Cmd] =
    val stateHash = stateEnum.caseHash(state)
    updateLifecycle(stateHash, lc => lc.copy(onExit = Some(action)))

  /** Add both entry and exit actions for a state.
    *
    * Convenience method combining `withEntry` and `withExit`.
    */
  def withLifecycle(state: S)(
      onEntry: Option[ZIO[Any, MechanoidError, Unit]] = None,
      onExit: Option[ZIO[Any, MechanoidError, Unit]] = None,
  ): Machine[S, E, Cmd] =
    val stateHash = stateEnum.caseHash(state)
    updateLifecycle(
      stateHash,
      lc =>
        lc.copy(
          onEntry = onEntry.orElse(lc.onEntry),
          onExit = onExit.orElse(lc.onExit),
        ),
    )
  end withLifecycle

  // ============================================
  // Internal mutation methods (for building)
  // ============================================

  /** Add a transition using event hash directly (for multi-event builders). */
  private[mechanoid] def addTransitionWithMetaByHash(
      fromCaseHash: Int,
      eventCaseHash: Int,
      transition: Transition[S, E, S],
      meta: TransitionMeta,
  ): Machine[S, E, Cmd] =
    new Machine(
      transitions + ((fromCaseHash, eventCaseHash) -> transition),
      lifecycles,
      timeouts,
      timeoutEvents,
      transitionMeta :+ meta,
      preCommandFactories,
      postCommandFactories,
      specs,
      timeoutSpecs,
    )

  /** Update lifecycle for a state. */
  private[mechanoid] def updateLifecycle(
      stateCaseHash: Int,
      f: StateLifecycle[S, Cmd] => StateLifecycle[S, Cmd],
  ): Machine[S, E, Cmd] =
    val current = lifecycles.getOrElse(stateCaseHash, StateLifecycle.empty[S, Cmd])
    new Machine(
      transitions,
      lifecycles + (stateCaseHash -> f(current)),
      timeouts,
      timeoutEvents,
      transitionMeta,
      preCommandFactories,
      postCommandFactories,
      specs,
      timeoutSpecs,
    )
  end updateLifecycle

  /** Set timeout for a state. */
  private[mechanoid] def setStateTimeout(
      stateCaseHash: Int,
      timeout: Duration,
  ): Machine[S, E, Cmd] =
    new Machine(
      transitions,
      lifecycles,
      timeouts + (stateCaseHash -> timeout),
      timeoutEvents,
      transitionMeta,
      preCommandFactories,
      postCommandFactories,
      specs,
      timeoutSpecs,
    )
end Machine

object Machine:

  /** Create a Machine from validated specs.
    *
    * This is called by the `build` macro after validation. It converts TransitionSpecs to runtime data.
    */
  private[machine] def fromSpecs[S: Finite, E: Finite, Cmd](
      specs: List[TransitionSpec[S, E, Cmd]],
      timeoutSpecs: List[TimeoutSpec[S, E]] = Nil,
  ): Machine[S, E, Cmd] =
    var transitions          = Map.empty[(Int, Int), Transition[S, E, S]]
    var transitionMetaList   = List.empty[TransitionMeta]
    var stateTimeouts        = Map.empty[Int, Duration]
    var stateTimeoutEvents   = Map.empty[Int, E] // state hash -> event to fire on timeout
    var preCommandFactories  = Map.empty[(Int, Int), (Any, Any) => List[Any]]
    var postCommandFactories = Map.empty[(Int, Int), (Any, Any) => List[Any]]

    val stateEnumInstance = summon[Finite[S]]

    // Convert each TransitionSpec to runtime data
    for spec <- specs do
      val transition = spec.handler match
        case Handler.Goto(target) =>
          val targetState = target.asInstanceOf[S]
          Transition[S, E, S](
            (_, _) => ZIO.succeed(TransitionResult.Goto(targetState)),
            None,
          )
        case Handler.Stay =>
          Transition[S, E, S](
            (_, _) => ZIO.succeed(TransitionResult.Stay),
            None,
          )
        case Handler.Stop(reason) =>
          Transition[S, E, S](
            (_, _) => ZIO.succeed(TransitionResult.Stop(reason)),
            None,
          )

      // Determine target hash for metadata
      val targetHash = spec.handler match
        case Handler.Goto(target) =>
          Some(stateEnumInstance.caseHash(target.asInstanceOf[S]))
        case _ => None

      val kind = spec.handler match
        case Handler.Goto(_)      => TransitionKind.Goto
        case Handler.Stay         => TransitionKind.Stay
        case Handler.Stop(reason) => TransitionKind.Stop(reason)

      // Add transition for each (state, event) pair
      for
        stateHash <- spec.stateHashes
        eventHash <- spec.eventHashes
      do
        val meta = TransitionMeta(stateHash, eventHash, targetHash, kind)
        transitions = transitions + ((stateHash, eventHash) -> transition)
        transitionMetaList = transitionMetaList :+ meta

        // Extract command factories
        spec.preCommandFactory.foreach { f =>
          preCommandFactories = preCommandFactories + ((stateHash, eventHash) -> f)
        }
        spec.postCommandFactory.foreach { f =>
          postCommandFactories = postCommandFactories + ((stateHash, eventHash) -> f)
        }
      end for

      // Configure timeout on target state if specified via TimedTarget
      (spec.targetTimeout, spec.handler) match
        case (Some(duration), Handler.Goto(target)) =>
          val targetStateHash = stateEnumInstance.caseHash(target.asInstanceOf[S])
          stateTimeouts = stateTimeouts + (targetStateHash -> duration)
          // Store the user-defined timeout event if provided
          // Safe cast: the event was created in TransitionSpec.gotoTimed with type TE <: E
          spec.targetTimeoutConfig.foreach { config =>
            stateTimeoutEvents = stateTimeoutEvents + (targetStateHash -> config.event.asInstanceOf[E])
          }
        case _ => // No timeout or not a goto transition
      end match
    end for

    // Convert TimeoutSpecs to state timeouts (legacy support)
    for timeoutSpec <- timeoutSpecs do
      for stateHash <- timeoutSpec.stateHashes do
        stateTimeouts = stateTimeouts + (stateHash           -> timeoutSpec.duration)
        stateTimeoutEvents = stateTimeoutEvents + (stateHash -> timeoutSpec.eventInstance)

    new Machine(
      transitions,
      Map.empty,
      stateTimeouts,
      stateTimeoutEvents,
      transitionMetaList,
      preCommandFactories,
      postCommandFactories,
      specs,
      timeoutSpecs,
    )
  end fromSpecs

  /** Create an empty Machine. */
  def empty[S: Finite, E: Finite]: Machine[S, E, Nothing] =
    new Machine(Map.empty, Map.empty, Map.empty, Map.empty, Nil, Map.empty, Map.empty, Nil, Nil)

  /** Create an empty Machine with commands. */
  def emptyWithCommands[S: Finite, E: Finite, Cmd]: Machine[S, E, Cmd] =
    new Machine(Map.empty, Map.empty, Map.empty, Map.empty, Nil, Map.empty, Map.empty, Nil, Nil)
end Machine
