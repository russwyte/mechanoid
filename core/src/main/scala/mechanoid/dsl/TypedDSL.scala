package mechanoid.dsl

import zio.*
import mechanoid.core.*
import mechanoid.macros.SealedEnumMacros
import mechanoid.visualization.{TransitionMeta, TransitionKind}
import scala.concurrent.duration.Duration

/** Experimental type-parameter-based FSM DSL.
  *
  * This approach uses type parameters instead of value parameters for state/event matching, which enables:
  *   1. Compile-time validation can directly inspect types (no inline expansion issues)
  *   2. Union types for multiple states/events: `when[ChildA | ChildB]`
  *   3. No need for separate `whenAny` - just use parent type: `when[Parent]`
  *
  * Example:
  * {{{
  * val definition = TypedDSL[MyState, MyEvent]
  *   .when[Idle].on[Start].goto(Running("init"))
  *   .when[Running].on[Stop].goto(Stopped)
  *   .when[Parent].on[Reset].goto(Initial)  // matches all children of Parent
  * }}}
  *
  * For case classes, `goto` still takes a value since we need the actual instance at runtime.
  */
object TypedDSL:

  /** Builder for type-parameter-based FSM definition. */
  final class TypedFSMBuilder[S <: MState, E <: MEvent, Cmd](
      private[dsl] val definition: FSMDefinition[S, E, Cmd]
  ):
    /** Start defining transitions from states matching type T.
      *
      * T can be:
      *   - A specific leaf state: `when[Idle.type]`
      *   - A parent sealed trait: `when[Parent]` (matches all children)
      *   - A union type: `when[ChildA | ChildB]` (matches specified types)
      */
    inline def when[T <: S]: TypedWhenBuilder[S, E, Cmd, T] =
      val parentHash = SealedEnumMacros.typeHash[T](CaseHasher.Default)
      val leafHashes = definition.stateEnum.leafHashesFor(parentHash)
      // If T is a leaf state (no children), use its own hash; otherwise use leaf hashes
      val hashes = if leafHashes.nonEmpty then leafHashes else Set(parentHash)
      new TypedWhenBuilder(definition, hashes)

    /** Start defining transitions from specific state values.
      *
      * Use this for case objects where you don't want to write `.type`: {{{\n * .when(A, B).on(E1).goto(C) // handles A
      * or B }}}
      */
    def when(first: S, rest: S*): TypedWhenBuilder[S, E, Cmd, S] =
      val hashes = (first +: rest).map(s => definition.stateEnum.caseHash(s)).toSet
      new TypedWhenBuilder(definition, hashes)

    /** Configure lifecycle actions (onEntry/onExit) for states matching type T.
      *
      * Example: {{{\n * .onState[Connected].onEntry(ZIO.logInfo("Connected!")).done }}}
      */
    inline def onState[T <: S]: TypedStateBuilder[S, E, Cmd] =
      val parentHash = SealedEnumMacros.typeHash[T](CaseHasher.Default)
      val leafHashes = definition.stateEnum.leafHashesFor(parentHash)
      val hashes     = if leafHashes.nonEmpty then leafHashes else Set(parentHash)
      new TypedStateBuilder(definition, hashes)

    /** Configure lifecycle actions for specific state values.
      *
      * Example: {{{\n * .onState(Running, Paused).onEntry(logActive).done }}}
      */
    def onState(first: S, rest: S*): TypedStateBuilder[S, E, Cmd] =
      val hashes = (first +: rest).map(s => definition.stateEnum.caseHash(s)).toSet
      new TypedStateBuilder(definition, hashes)

    /** Set a timeout for states matching type T.
      *
      * When the FSM stays in a matching state for longer than the timeout, a timeout event is generated.
      *
      * Example: {{{\n * .withTimeout[Waiting](5.seconds) }}}
      */
    inline def withTimeout[T <: S](timeout: Duration): TypedFSMBuilder[S, E, Cmd] =
      val parentHash = SealedEnumMacros.typeHash[T](CaseHasher.Default)
      val leafHashes = definition.stateEnum.leafHashesFor(parentHash)
      val hashes     = if leafHashes.nonEmpty then leafHashes else Set(parentHash)
      var updatedDef = definition
      for hash <- hashes do updatedDef = updatedDef.setStateTimeout(hash, timeout)
      new TypedFSMBuilder(updatedDef)

    /** Set a timeout for specific state values.
      *
      * Example: {{{\n * .withTimeout(5.seconds, Waiting, Pending) }}}
      */
    def withTimeout(timeout: Duration, first: S, rest: S*): TypedFSMBuilder[S, E, Cmd] =
      var updatedDef = definition
      for state <- first +: rest do
        val hash = definition.stateEnum.caseHash(state)
        updatedDef = updatedDef.setStateTimeout(hash, timeout)
      new TypedFSMBuilder(updatedDef)

    /** Get the underlying FSMDefinition. */
    def build: FSMDefinition[S, E, Cmd] = definition
  end TypedFSMBuilder

  /** Builder after specifying the source state type. */
  final class TypedWhenBuilder[S <: MState, E <: MEvent, Cmd, FromState <: S](
      private val definition: FSMDefinition[S, E, Cmd],
      private val fromStateHashes: Set[Int],
  ):
    /** Define a transition triggered by events matching type T.
      *
      * T can be:
      *   - A specific leaf event: `on[Click.type]`
      *   - A parent sealed trait: `on[UserInput]` (matches all children)
      *   - A union type: `on[Click | Tap]` (matches specified types)
      */
    inline def on[T <: E]: TypedTransitionBuilder[S, E, Cmd, FromState, T] =
      val parentHash = SealedEnumMacros.typeHash[T](CaseHasher.Default)
      val leafHashes = definition.eventEnum.leafHashesFor(parentHash)
      val hashes     = if leafHashes.nonEmpty then leafHashes else Set(parentHash)
      new TypedTransitionBuilder(definition, fromStateHashes, hashes)

    /** Define a transition triggered by specific event values.
      *
      * Use this for case objects where you don't want to write `.type`: {{{\n * .when(A).on(E1, E2).goto(B) // handles
      * E1 or E2 }}}
      */
    def on(first: E, rest: E*): TypedTransitionBuilder[S, E, Cmd, FromState, E] =
      val hashes = (first +: rest).map(e => definition.eventEnum.caseHash(Timed.UserEvent(e))).toSet
      new TypedTransitionBuilder(definition, fromStateHashes, hashes)

    /** Define a transition triggered by timeout. */
    def onTimeout: TypedTransitionBuilder[S, E, Cmd, FromState, Nothing] =
      val timeoutHash = definition.eventEnum.caseHash(Timed.TimeoutEvent)
      new TypedTransitionBuilder(definition, fromStateHashes, Set(timeoutHash))
  end TypedWhenBuilder

  /** Builder for configuring a transition with known source and event types. */
  final class TypedTransitionBuilder[S <: MState, E <: MEvent, Cmd, FromState <: S, OnEvent <: E](
      private val definition: FSMDefinition[S, E, Cmd],
      private val fromStateHashes: Set[Int],
      private val eventHashes: Set[Int],
  ):
    /** Transition to a specific state value.
      *
      * Use this for all state transitions:
      * {{{
      * .when[Idle].on[Connect].goto(Connected("session-123"))
      * .when[Running].on[Stop].goto(Stopped)  // case objects too
      * }}}
      */
    def goto(targetState: S): TypedFSMBuilder[S, E, Cmd] =
      val action     = (_: S, _: Timed[E]) => ZIO.succeed(TransitionResult.Goto(targetState))
      val transition = Transition[S, Timed[E], S](action, None)
      val targetHash = definition.stateEnum.caseHash(targetState)
      var updatedDef = definition
      for
        fromHash  <- fromStateHashes
        eventHash <- eventHashes
      do
        val meta = TransitionMeta(fromHash, eventHash, Some(targetHash), TransitionKind.Goto)
        updatedDef = updatedDef.addTransitionWithMetaByHash(fromHash, eventHash, transition, meta)
      new TypedFSMBuilder(updatedDef)
    end goto

    /** Stay in the current state. */
    def stay: TypedFSMBuilder[S, E, Cmd] =
      val action     = (_: S, _: Timed[E]) => ZIO.succeed(TransitionResult.Stay)
      val transition = Transition[S, Timed[E], S](action, None)
      var updatedDef = definition
      for
        fromHash  <- fromStateHashes
        eventHash <- eventHashes
      do
        val meta = TransitionMeta(fromHash, eventHash, None, TransitionKind.Stay)
        updatedDef = updatedDef.addTransitionWithMetaByHash(fromHash, eventHash, transition, meta)
      new TypedFSMBuilder(updatedDef)
    end stay

    /** Stop the FSM. */
    def stop: TypedFSMBuilder[S, E, Cmd] =
      val action     = (_: S, _: Timed[E]) => ZIO.succeed(TransitionResult.Stop(None))
      val transition = Transition[S, Timed[E], S](action, None)
      var updatedDef = definition
      for
        fromHash  <- fromStateHashes
        eventHash <- eventHashes
      do
        val meta = TransitionMeta(fromHash, eventHash, None, TransitionKind.Stop(None))
        updatedDef = updatedDef.addTransitionWithMetaByHash(fromHash, eventHash, transition, meta)
      new TypedFSMBuilder(updatedDef)
    end stop

    /** Stop the FSM with a reason. */
    def stop(reason: String): TypedFSMBuilder[S, E, Cmd] =
      val action     = (_: S, _: Timed[E]) => ZIO.succeed(TransitionResult.Stop(Some(reason)))
      val transition = Transition[S, Timed[E], S](action, None)
      var updatedDef = definition
      for
        fromHash  <- fromStateHashes
        eventHash <- eventHashes
      do
        val meta = TransitionMeta(fromHash, eventHash, None, TransitionKind.Stop(Some(reason)))
        updatedDef = updatedDef.addTransitionWithMetaByHash(fromHash, eventHash, transition, meta)
      new TypedFSMBuilder(updatedDef)
    end stop

    /** Mark this as an intentional override (for duplicate detection). */
    def `override`: TypedOverrideBuilder[S, E, Cmd, FromState, OnEvent] =
      new TypedOverrideBuilder(definition, fromStateHashes, eventHashes)
  end TypedTransitionBuilder

  /** Builder for override transitions. */
  final class TypedOverrideBuilder[S <: MState, E <: MEvent, Cmd, FromState <: S, OnEvent <: E](
      private val definition: FSMDefinition[S, E, Cmd],
      private val fromStateHashes: Set[Int],
      private val eventHashes: Set[Int],
  ):
    def goto(targetState: S): TypedFSMBuilder[S, E, Cmd] =
      val action     = (_: S, _: Timed[E]) => ZIO.succeed(TransitionResult.Goto(targetState))
      val transition = Transition[S, Timed[E], S](action, None)
      val targetHash = definition.stateEnum.caseHash(targetState)
      var updatedDef = definition
      for
        fromHash  <- fromStateHashes
        eventHash <- eventHashes
      do
        val meta = TransitionMeta(fromHash, eventHash, Some(targetHash), TransitionKind.Goto)
        updatedDef = updatedDef.addTransitionWithMetaByHash(fromHash, eventHash, transition, meta)
      new TypedFSMBuilder(updatedDef)
    end goto

    def stay: TypedFSMBuilder[S, E, Cmd] =
      val action     = (_: S, _: Timed[E]) => ZIO.succeed(TransitionResult.Stay)
      val transition = Transition[S, Timed[E], S](action, None)
      var updatedDef = definition
      for
        fromHash  <- fromStateHashes
        eventHash <- eventHashes
      do
        val meta = TransitionMeta(fromHash, eventHash, None, TransitionKind.Stay)
        updatedDef = updatedDef.addTransitionWithMetaByHash(fromHash, eventHash, transition, meta)
      new TypedFSMBuilder(updatedDef)
    end stay

    def stop: TypedFSMBuilder[S, E, Cmd] =
      val action     = (_: S, _: Timed[E]) => ZIO.succeed(TransitionResult.Stop(None))
      val transition = Transition[S, Timed[E], S](action, None)
      var updatedDef = definition
      for
        fromHash  <- fromStateHashes
        eventHash <- eventHashes
      do
        val meta = TransitionMeta(fromHash, eventHash, None, TransitionKind.Stop(None))
        updatedDef = updatedDef.addTransitionWithMetaByHash(fromHash, eventHash, transition, meta)
      new TypedFSMBuilder(updatedDef)
    end stop
  end TypedOverrideBuilder

  /** Builder for configuring state lifecycle actions that returns to TypedFSMBuilder. */
  final class TypedStateBuilder[S <: MState, E <: MEvent, Cmd](
      private var definition: FSMDefinition[S, E, Cmd],
      private val stateHashes: Set[Int],
  ):
    /** Define an action to execute when entering these states. */
    inline def onEntry[Err1](inline action: ZIO[Any, Err1, Unit]): TypedStateBuilder[S, E, Cmd] =
      import mechanoid.macros.ExpressionName
      val description = ExpressionName.of(action)
      onEntryWithDescription(action, description)

    /** Define an entry action with explicit description. */
    def onEntryWithDescription[Err1](
        action: ZIO[Any, Err1, Unit],
        description: String,
    ): TypedStateBuilder[S, E, Cmd] =
      val wrappedAction = action.mapError(wrapError)
      for hash <- stateHashes do
        definition = definition.updateLifecycle(
          hash,
          lc => lc.copy(onEntry = Some(wrappedAction), onEntryDescription = Some(description)),
        )
      this
    end onEntryWithDescription

    /** Define an action to execute when exiting these states. */
    inline def onExit[Err1](inline action: ZIO[Any, Err1, Unit]): TypedStateBuilder[S, E, Cmd] =
      import mechanoid.macros.ExpressionName
      val description = ExpressionName.of(action)
      onExitWithDescription(action, description)

    /** Define an exit action with explicit description. */
    def onExitWithDescription[Err1](
        action: ZIO[Any, Err1, Unit],
        description: String,
    ): TypedStateBuilder[S, E, Cmd] =
      val wrappedAction = action.mapError(wrapError)
      for hash <- stateHashes do
        definition = definition.updateLifecycle(
          hash,
          lc => lc.copy(onExit = Some(wrappedAction), onExitDescription = Some(description)),
        )
      this
    end onExitWithDescription

    /** Enqueue a command when entering these states. */
    def enqueue(factory: S => Cmd): TypedStateBuilder[S, E, Cmd] =
      for hash <- stateHashes do
        definition = definition.updateLifecycle(
          hash,
          lc =>
            val newFactory: S => List[Cmd] = lc.commandFactory match
              case Some(existing) => s => existing(s) :+ factory(s)
              case None           => s => List(factory(s))
            lc.copy(commandFactory = Some(newFactory)),
        )
      this
    end enqueue

    /** Enqueue multiple commands when entering these states. */
    def enqueueAll(factory: S => List[Cmd]): TypedStateBuilder[S, E, Cmd] =
      for hash <- stateHashes do
        definition = definition.updateLifecycle(
          hash,
          lc =>
            val newFactory: S => List[Cmd] = lc.commandFactory match
              case Some(existing) => s => existing(s) ++ factory(s)
              case None           => factory
            lc.copy(commandFactory = Some(newFactory)),
        )
      this
    end enqueueAll

    /** Complete state configuration and return to the FSM builder. */
    def done: TypedFSMBuilder[S, E, Cmd] = new TypedFSMBuilder(definition)

    private def wrapError[E](e: E): MechanoidError = e match
      case me: MechanoidError => me
      case other              => ActionFailedError(other)
  end TypedStateBuilder

  /** Create a typed FSM builder. */
  def apply[S <: MState: SealedEnum, E <: MEvent: SealedEnum]: TypedFSMBuilder[S, E, Nothing] =
    new TypedFSMBuilder(FSMDefinition[S, E])

  def withCommands[S <: MState: SealedEnum, E <: MEvent: SealedEnum, Cmd]: TypedFSMBuilder[S, E, Cmd] =
    new TypedFSMBuilder(FSMDefinition.withCommands[S, E, Cmd])

end TypedDSL
