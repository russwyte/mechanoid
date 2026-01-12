package mechanoid.macros

import scala.quoted.*
import mechanoid.dsl.FSMDefinition
import mechanoid.core.{MState, MEvent, SealedEnum}

/** Macro for validating FSM definitions at compile time.
  *
  * The `build` macro creates an FSM definition using a functional configuration style and validates it for duplicate
  * transitions. Duplicates are detected when the same (state, event) pair is defined more than once without using the
  * `.override` modifier.
  *
  * Example:
  * {{{
  * val definition = build[MyState, MyEvent] {
  *   _.when(StateA).on(Event1).goto(StateB)
  *     .when(StateB).on(Event2).goto(StateC)
  * }
  *
  * // Compile error: duplicate transition
  * val bad = build[MyState, MyEvent] {
  *   _.when(StateA).on(Event1).goto(StateB)
  *     .when(StateA).on(Event1).goto(StateC)
  * }
  * }}}
  *
  * To intentionally override a transition (e.g., after `whenAny`), use `.override`:
  * {{{
  * val definition = build[MyState, MyEvent] {
  *   _.whenAny[Parent].on(Cancel).goto(Draft)
  *     .when(SpecificState).on(Cancel).override.goto(Special)  // OK
  * }
  * }}}
  *
  * For FSMs with commands, use the three-type-parameter version:
  * {{{
  * val definition = build[MyState, MyEvent, MyCommand] {
  *   _.when(Idle).on(Start).goto(Running)
  *     .onState(Running).enqueue(_ => NotifySystem).done
  * }
  * }}}
  */
object FSMValidation:

  /** Build and validate an FSM definition at compile time (without commands).
    *
    * This macro:
    *   1. Creates an empty FSMDefinition[S, E, Nothing]
    *   2. Applies your configuration function to it
    *   3. Validates the definition for duplicate transitions
    *   4. Returns the validated FSM definition
    *
    * @param configure
    *   A function that configures the FSM definition
    * @return
    *   The validated FSM definition
    */
  inline def build[S <: MState: SealedEnum, E <: MEvent: SealedEnum](
      inline configure: FSMDefinition[S, E, Nothing] => FSMDefinition[S, E, Nothing]
  ): FSMDefinition[S, E, Nothing] =
    ${ buildImpl[S, E, Nothing]('configure, '{ FSMDefinition[S, E] }) }

  /** Build and validate an FSM definition at compile time (with commands).
    *
    * This macro:
    *   1. Creates an empty FSMDefinition[S, E, Cmd]
    *   2. Applies your configuration function to it
    *   3. Validates the definition for duplicate transitions
    *   4. Returns the validated FSM definition
    *
    * @param configure
    *   A function that configures the FSM definition
    * @return
    *   The validated FSM definition
    */
  inline def build[S <: MState: SealedEnum, E <: MEvent: SealedEnum, Cmd](
      inline configure: FSMDefinition[S, E, Cmd] => FSMDefinition[S, E, Cmd]
  ): FSMDefinition[S, E, Cmd] =
    ${ buildImpl[S, E, Cmd]('configure, '{ FSMDefinition.withCommands[S, E, Cmd] }) }

  private def buildImpl[S <: MState: Type, E <: MEvent: Type, Cmd: Type](
      configure: Expr[FSMDefinition[S, E, Cmd] => FSMDefinition[S, E, Cmd]],
      initial: Expr[FSMDefinition[S, E, Cmd]],
  )(using Quotes): Expr[FSMDefinition[S, E, Cmd]] =
    import quotes.reflect.*

    /** Information about a single transition extracted from the AST. */
    case class TransitionInfo(
        stateNames: List[String], // List because whenAny can have multiple states
        eventName: String,
        targetDesc: String, // "goto(X)" or "stay" or "stop" or "stop(reason)"
        isOverride: Boolean,
        position: Position,
    )

    /** Extract the name of a term for display in error messages. */
    def extractName(term: Term): String =
      term match
        case Ident(name)           => name
        case Select(qual, "apply") => extractName(qual) // For case class constructors
        case Select(_, name)       => name
        case Apply(fn, _)          => extractName(fn)
        case TypeApply(fn, _)      => extractName(fn)
        case Inlined(_, _, expr)   => extractName(expr)
        case Typed(expr, _)        => extractName(expr)
        case Block(_, expr)        => extractName(expr)
        case _                     => term.show(using Printer.TreeShortCode)

    /** Extract the argument value as a string for display. */
    def extractArgName(term: Term): String =
      term match
        case Ident(name)                        => name
        case Select(qual, name)                 => s"${extractArgName(qual)}.$name"
        case Literal(const)                     => const.value.toString
        case Apply(Select(qual, "apply"), args) =>
          // Case class constructor: Success("msg") -> Success(msg)
          val typeName = extractName(qual)
          val argsStr  = args.map(extractArgName).mkString(", ")
          s"$typeName($argsStr)"
        case Apply(fn, args) =>
          val fnName  = extractName(fn)
          val argsStr = args.map(extractArgName).mkString(", ")
          s"$fnName($argsStr)"
        case Inlined(_, _, expr) => extractArgName(expr)
        case Typed(expr, _)      => extractArgName(expr)
        case _                   => term.show(using Printer.TreeShortCode)

    /** Recursively extract all transitions from the expression tree. */
    def extractTransitions(term: Term): List[TransitionInfo] =
      term match
        // Handle Inlined wrapper (common in macro expansion)
        case Inlined(_, _, expr) =>
          extractTransitions(expr)

        // Handle Block - need to look inside DefDef for lambda bodies
        case Block(stats, expr) =>
          val fromStats = stats.flatMap {
            case d: DefDef => d.rhs.map(extractTransitions).getOrElse(Nil)
            case t: Term   => extractTransitions(t)
            case _         => Nil
          }
          fromStats ++ extractTransitions(expr)

        // Pattern: .goto(target), .stay, .stop, .stop(reason)
        // These are the terminal transition methods
        case Apply(Select(receiver, "goto"), List(target)) =>
          val (states, event, isOverride) = extractTransitionContext(receiver)
          val targetName                  = extractArgName(target)
          List(TransitionInfo(states, event, s"goto($targetName)", isOverride, term.pos)) ++
            extractTransitions(receiver)

        case Select(receiver, "stay")
            if !receiver.tpe.typeSymbol.name.contains("Builder") || receiver.show.contains(".stay") =>
          // Check if this is actually a .stay call (not just accessing a builder)
          val (states, event, isOverride) = extractTransitionContext(receiver)
          if event.nonEmpty then
            List(TransitionInfo(states, event, "stay", isOverride, term.pos)) ++
              extractTransitions(receiver)
          else extractTransitions(receiver)

        case Apply(Select(receiver, "stop"), List(reason)) =>
          val (states, event, isOverride) = extractTransitionContext(receiver)
          val reasonStr                   = extractArgName(reason)
          List(TransitionInfo(states, event, s"stop($reasonStr)", isOverride, term.pos)) ++
            extractTransitions(receiver)

        case Select(receiver, "stop") =>
          val (states, event, isOverride) = extractTransitionContext(receiver)
          if event.nonEmpty then
            List(TransitionInfo(states, event, "stop", isOverride, term.pos)) ++
              extractTransitions(receiver)
          else extractTransitions(receiver)

        // Recurse into method chains
        case Apply(fn, args) =>
          extractTransitions(fn) ++ args.flatMap(a => extractTransitions(a))

        case TypeApply(fn, _) =>
          extractTransitions(fn)

        case Select(receiver, _) =>
          extractTransitions(receiver)

        case _ =>
          List.empty

    /** Check if a term or any of its subterms contain an "override" method call.
      *
      * Uses multiple detection strategies since the AST representation can vary.
      */
    def containsOverride(term: Term): Boolean =
      // Strategy 1: Check string representations (multiple printers)
      val shortStr  = term.show(using Printer.TreeShortCode)
      val structStr = term.show(using Printer.TreeStructure)

      if shortStr.contains("override") || structStr.contains("override") then return true

      // Strategy 2: Recursively check AST nodes for Select with "override"
      def checkNodes(t: Term): Boolean =
        t match
          case Select(inner, name) =>
            name == "override" || name.contains("override") || checkNodes(inner)
          case Apply(fn, args) =>
            checkNodes(fn) || args.exists(a => checkNodes(a.asInstanceOf[Term]))
          case TypeApply(fn, _) =>
            checkNodes(fn)
          case Inlined(_, bindings, expr) =>
            checkNodes(expr)
          case Block(stats, expr) =>
            checkNodes(expr)
          case Typed(expr, _) =>
            checkNodes(expr)
          case _ =>
            false

      checkNodes(term)
    end containsOverride

    /** Extract state names, event name, and override status from the receiver of a transition method.
      *
      * Walks back through the chain to find .when(state).on(event) or .whenAny[T].on(event) or .override.goto()
      * patterns.
      */
    def extractTransitionContext(term: Term): (List[String], String, Boolean) =
      // Check if override is present anywhere in the receiver chain
      val hasOverride = containsOverride(term)

      def extractContext(t: Term): (List[String], String) =
        t match
          // Skip .override - just continue to inner
          case Select(inner, name) if name == "override" =>
            extractContext(inner)

          // .on(event) - extract event name
          case Apply(Select(inner, "on"), List(eventArg)) =>
            val (states, _) = extractContext(inner)
            val eventName   = extractArgName(eventArg)
            (states, eventName)

          // .onTimeout - special timeout event
          case Select(inner, "onTimeout") =>
            val (states, _) = extractContext(inner)
            (states, "Timeout")

          // .when(state) - extract state name
          case Apply(Select(_, "when"), List(stateArg)) =>
            val stateName = extractArgName(stateArg)
            (List(stateName), "")

          // .whenAny[T] - extract type parameter name
          case TypeApply(Select(_, "whenAny"), List(typeTree)) =>
            val typeName = typeTree.tpe.typeSymbol.name
            (List(s"<any $typeName>"), "")

          // .whenAny[T](states*) or .whenStates(states*)
          case Apply(TypeApply(Select(_, "whenAny"), _), args) =>
            val stateNames = args.map(extractArgName)
            (stateNames, "")

          case Apply(Select(_, "whenStates"), args) =>
            val stateNames = args.map(extractArgName)
            (stateNames, "")

          // Recurse through other patterns
          case Inlined(_, _, expr) =>
            extractContext(expr)

          case Select(inner, _) =>
            extractContext(inner)

          case Apply(fn, _) =>
            extractContext(fn)

          case TypeApply(fn, _) =>
            extractContext(fn)

          case _ =>
            (List.empty, "")

      val (states, event) = extractContext(term)
      (states, event, hasOverride)
    end extractTransitionContext

    // Extract all transitions from the configure function
    val transitions = extractTransitions(configure.asTerm)

    // Filter to only valid transitions (have both state and event)
    // IMPORTANT: Reverse the order because AST extraction goes right-to-left (outer to inner),
    // but DSL semantics are left-to-right (first definition to last)
    val validTransitions = transitions.filter(t => t.stateNames.nonEmpty && t.eventName.nonEmpty).reverse

    // Check for duplicates among non-override transitions
    // Key: (stateName, eventName) -> first occurrence
    val seen = scala.collection.mutable.Map[(String, String), TransitionInfo]()

    for t <- validTransitions do
      for stateName <- t.stateNames do
        val key = (stateName, t.eventName)
        seen.get(key) match
          case Some(prev) if !t.isOverride =>
            report.errorAndAbort(
              s"""Duplicate transition detected for state '$stateName' on event '${t.eventName}'.
                 |
                 |  First definition:  ${prev.targetDesc}
                 |  Second definition: ${t.targetDesc}
                 |
                 |To fix this:
                 |  - Remove one of the duplicate definitions, OR
                 |  - Use .override.${t.targetDesc.takeWhile(_ != '(')}(...) if the override is intentional
                 |""".stripMargin,
              t.position,
            )
          case _ =>
            // Record this transition (overrides replace in the map)
            seen(key) = t
        end match
    end for

    // Validation passed - apply the configure function to the initial definition
    '{ $configure($initial) }
  end buildImpl

end FSMValidation
