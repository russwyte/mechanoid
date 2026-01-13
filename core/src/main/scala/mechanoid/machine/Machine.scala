package mechanoid.machine

import zio.*
import mechanoid.core.*
import mechanoid.dsl.FSMDefinition
import mechanoid.runtime.FSMRuntime
import mechanoid.visualization.{TransitionMeta, TransitionKind}

/** A finite state machine definition using the suite-style DSL.
  *
  * Machine is a friendlier wrapper around FSMDefinition that:
  *   - Stores transition specs for compile-time validation
  *   - Converts to FSMDefinition for runtime execution
  *   - Supports composition via the `build` macro
  *
  * @tparam S
  *   The state type (must extend MState and be sealed)
  * @tparam E
  *   The event type (must extend MEvent and be sealed)
  * @tparam Cmd
  *   The command type for transactional outbox pattern (use Nothing for FSMs without commands)
  */
final class Machine[S <: MState, E <: MEvent, Cmd] private[machine] (
    private[machine] val specs: List[TransitionSpec[S, E, Cmd]],
    private[machine] val timeoutSpecs: List[TimeoutSpec[S]],
    private[machine] val underlying: FSMDefinition[S, E, Cmd],
)(using
    private[machine] val stateEnum: SealedEnum[S],
    private[machine] val eventEnum: SealedEnum[Timed[E]],
):

  /** Apply an aspect to ALL transition specs in this machine.
    *
    * Usage: `myMachine @@ overriding` marks all transitions as overrides.
    */
  def @@(aspect: Aspect): Machine[S, E, Cmd] = aspect match
    case Aspect.overriding =>
      val newSpecs = specs.map(_.copy(isOverride = true))
      new Machine(newSpecs, timeoutSpecs, underlying)
    case _: Aspect.timeout =>
      // timeout aspect doesn't apply to machines (only individual states)
      this

  /** Get the underlying FSMDefinition for visualization or advanced use. */
  def definition: FSMDefinition[S, E, Cmd] = underlying

  /** Build and start the FSM runtime with the given initial state.
    *
    * Creates an in-memory FSM runtime suitable for testing or single-process use.
    */
  def start(initial: S): ZIO[Scope, MechanoidError, FSMRuntime[Unit, S, E, Cmd]] =
    underlying.build(initial)

  /** Get transition metadata for visualization. */
  def transitionMeta: List[TransitionMeta] = underlying.transitionMeta

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
    val stateHash     = stateEnum.caseHash(state)
    val newDefinition = underlying.updateLifecycle(
      stateHash,
      lc => lc.copy(onEntry = Some(action)),
    )
    new Machine(specs, timeoutSpecs, newDefinition)

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
    val stateHash     = stateEnum.caseHash(state)
    val newDefinition = underlying.updateLifecycle(
      stateHash,
      lc => lc.copy(onExit = Some(action)),
    )
    new Machine(specs, timeoutSpecs, newDefinition)

  /** Add both entry and exit actions for a state.
    *
    * Convenience method combining `withEntry` and `withExit`.
    */
  def withLifecycle(state: S)(
      onEntry: Option[ZIO[Any, MechanoidError, Unit]] = None,
      onExit: Option[ZIO[Any, MechanoidError, Unit]] = None,
  ): Machine[S, E, Cmd] =
    val stateHash     = stateEnum.caseHash(state)
    val newDefinition = underlying.updateLifecycle(
      stateHash,
      lc =>
        lc.copy(
          onEntry = onEntry.orElse(lc.onEntry),
          onExit = onExit.orElse(lc.onExit),
        ),
    )
    new Machine(specs, timeoutSpecs, newDefinition)
  end withLifecycle
end Machine

object Machine:

  /** Create a Machine from validated specs.
    *
    * This is called by the `build` macro after validation. It converts TransitionSpecs to an FSMDefinition.
    */
  private[machine] def fromSpecs[S <: MState: SealedEnum, E <: MEvent: SealedEnum, Cmd](
      specs: List[TransitionSpec[S, E, Cmd]],
      timeoutSpecs: List[TimeoutSpec[S]] = Nil,
  )(using SealedEnum[Timed[E]]): Machine[S, E, Cmd] =
    // Build the FSMDefinition from specs
    var definition = FSMDefinition[S, E].asInstanceOf[FSMDefinition[S, E, Cmd]]

    val stateEnumInstance = summon[SealedEnum[S]]

    // Convert each TransitionSpec to a Transition in FSMDefinition
    for spec <- specs do
      val transition = spec.handler match
        case Handler.Goto(target) =>
          val targetState = target.asInstanceOf[S]
          Transition[S, Timed[E], S](
            (_, _) => ZIO.succeed(TransitionResult.Goto(targetState)),
            None,
          )
        case Handler.Stay =>
          Transition[S, Timed[E], S](
            (_, _) => ZIO.succeed(TransitionResult.Stay),
            None,
          )
        case Handler.Stop(reason) =>
          Transition[S, Timed[E], S](
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
        definition = definition.addTransitionWithMetaByHash(stateHash, eventHash, transition, meta)

      // Configure timeout on target state if specified via TimedTarget
      (spec.targetTimeout, spec.handler) match
        case (Some(duration), Handler.Goto(target)) =>
          val targetStateHash = stateEnumInstance.caseHash(target.asInstanceOf[S])
          definition = definition.setStateTimeout(targetStateHash, duration)
        case _ => // No timeout or not a goto transition
    end for

    // Convert TimeoutSpecs to state timeouts in FSMDefinition (legacy support)
    // Note: This just sets the timeout duration. The actual transition on timeout
    // is defined via normal transition rules: `State via Timeout to Target`
    for timeoutSpec <- timeoutSpecs do
      for stateHash <- timeoutSpec.stateHashes do
        definition = definition.setStateTimeout(stateHash, timeoutSpec.duration)

    new Machine(specs, timeoutSpecs, definition)
  end fromSpecs

  /** Create an empty Machine. */
  def empty[S <: MState: SealedEnum, E <: MEvent: SealedEnum](using SealedEnum[Timed[E]]): Machine[S, E, Nothing] =
    new Machine(Nil, Nil, FSMDefinition[S, E])

  /** Create an empty Machine with commands. */
  def emptyWithCommands[S <: MState: SealedEnum, E <: MEvent: SealedEnum, Cmd](using
      SealedEnum[Timed[E]]
  ): Machine[S, E, Cmd] =
    new Machine(Nil, Nil, FSMDefinition.withCommands[S, E, Cmd])
end Machine
