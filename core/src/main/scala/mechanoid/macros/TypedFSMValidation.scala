package mechanoid.macros

import scala.quoted.*
import mechanoid.dsl.{FSMDefinition, TypedDSL}
import mechanoid.core.{MState, MEvent, SealedEnum}

/** Compile-time validation for TypedDSL FSM definitions.
  *
  * The `validated` macro wraps a TypedDSL definition and validates it for duplicate transitions at compile time.
  * Duplicates are detected when the same (state, event) pair is defined more than once without using the `.override`
  * modifier.
  *
  * Example:
  * {{{
  * val fsm = TypedDSL.validated[MyState, MyEvent] {
  *   _.when[Idle.type].on[Start.type].goto(Running)
  *     .when[Running.type].on[Stop.type].goto(Idle)
  * }
  *
  * // Compile error: duplicate transition
  * val bad = TypedDSL.validated[MyState, MyEvent] {
  *   _.when[A.type].on[E1.type].goto(B)
  *     .when[A.type].on[E1.type].goto(C)
  * }
  * }}}
  */
object TypedFSMValidation:

  /** Validate and build a TypedDSL FSM definition. */
  inline def validated[S <: MState: SealedEnum, E <: MEvent: SealedEnum](
      inline configure: TypedDSL.TypedFSMBuilder[S, E, Nothing] => TypedDSL.TypedFSMBuilder[S, E, Nothing]
  ): FSMDefinition[S, E, Nothing] =
    ${ validatedImpl[S, E, Nothing]('configure, '{ TypedDSL[S, E] }) }

  /** Validate and build a TypedDSL FSM definition with commands. */
  inline def validatedWithCommands[S <: MState: SealedEnum, E <: MEvent: SealedEnum, Cmd](
      inline configure: TypedDSL.TypedFSMBuilder[S, E, Cmd] => TypedDSL.TypedFSMBuilder[S, E, Cmd]
  ): FSMDefinition[S, E, Cmd] =
    ${ validatedImpl[S, E, Cmd]('configure, '{ TypedDSL.withCommands[S, E, Cmd] }) }

  private def validatedImpl[S <: MState: Type, E <: MEvent: Type, Cmd: Type](
      configure: Expr[TypedDSL.TypedFSMBuilder[S, E, Cmd] => TypedDSL.TypedFSMBuilder[S, E, Cmd]],
      initial: Expr[TypedDSL.TypedFSMBuilder[S, E, Cmd]],
  )(using Quotes): Expr[FSMDefinition[S, E, Cmd]] =
    import quotes.reflect.*

    /** Information about a transition extracted from the AST. */
    case class TransitionInfo(
        stateTypes: List[String], // Type names for error messages
        eventTypes: List[String], // Type names for error messages
        stateHashes: Set[Int],    // Hashes for duplicate detection
        eventHashes: Set[Int],    // Hashes for duplicate detection
        targetDesc: String,       // "goto(X)" or "stay" or "stop"
        isOverride: Boolean,
        position: Position,
    )

    /** Compute hash using the default hasher (same as CaseHasher.Default). */
    def computeHash(fullName: String): Int = fullName.hashCode

    /** Normalize a full name by stripping the $ suffix from module names. */
    def normalizeFullName(name: String): String =
      if name.endsWith("$") then name.dropRight(1) else name

    /** Find all leaf case symbols under a sealed type symbol. */
    def findLeafCases(sym: Symbol): List[Symbol] =
      if !sym.exists then Nil
      else
        sym.children.flatMap { child =>
          if child.flags.is(Flags.Sealed) then findLeafCases(child)
          else if child.flags.is(Flags.Case) || child.flags.is(Flags.Enum) then List(child)
          else Nil
        }

    /** Get the hash for a type, expanding sealed hierarchies to leaf hashes. */
    def getTypeHashes(tpe: TypeRepr): Set[Int] =
      val sym       = tpe.typeSymbol
      val dealiased = tpe.dealias

      // For singleton types (A.type), get the term symbol
      val targetSym = dealiased match
        case TermRef(_, _) =>
          val termSym = tpe.termSymbol
          if termSym.exists then termSym else sym
        case _ => sym

      if targetSym.flags.is(Flags.Sealed) then
        val leaves = findLeafCases(targetSym)
        if leaves.nonEmpty then leaves.map(c => computeHash(normalizeFullName(c.fullName))).toSet
        else Set(computeHash(normalizeFullName(targetSym.fullName)))
      else Set(computeHash(normalizeFullName(targetSym.fullName)))
    end getTypeHashes

    /** Get a readable name for a type. */
    def getTypeName(tpe: TypeRepr): String =
      val dealiased = tpe.dealias
      dealiased match
        case TermRef(_, name) => name
        case _                => tpe.typeSymbol.name

    /** Hash for the Timeout event. */
    val timeoutHash: Int = computeHash("mechanoid.core.Timed.TimeoutEvent")

    /** Check if a term contains an "override" call. */
    def containsOverride(term: Term): Boolean =
      val str = term.show(using Printer.TreeShortCode)
      str.contains(".override.") || str.contains("`override`")

    /** Extract state/event context by walking up the call chain. */
    def extractContext(term: Term): (List[String], List[String], Set[Int], Set[Int]) =
      var stateTypes  = List.empty[String]
      var eventTypes  = List.empty[String]
      var stateHashes = Set.empty[Int]
      var eventHashes = Set.empty[Int]

      def walk(t: Term): Unit = t match
        // STOP at transition boundaries - don't walk past previous transitions
        case Apply(Select(_, "goto"), _) => ()
        case Select(_, "stay")           => ()
        case Apply(Select(_, "stop"), _) => ()
        case Select(_, "stop")           => ()

        // Skip override
        case Select(inner, name) if name == "override" =>
          walk(inner)

        // Type-parameter when[T] - direct call
        case TypeApply(Select(inner, "when"), List(typeTree)) =>
          val tpe = typeTree.tpe
          stateTypes = getTypeName(tpe) :: stateTypes
          stateHashes = stateHashes ++ getTypeHashes(tpe)
          walk(inner)

        // Value-parameter when(states*) - but filter out empty varargs
        case Apply(Select(inner, "when"), args) =>
          val realArgs = args.filterNot {
            case Typed(Repeated(Nil, _), _) => true // Empty varargs
            case _                          => false
          }
          for arg <- realArgs do
            val name = arg.show(using Printer.TreeShortCode)
            stateTypes = name :: stateTypes
            stateHashes = stateHashes + computeHash(normalizeFullName(arg.tpe.typeSymbol.fullName))
          walk(inner)

        // Type-parameter on[T] - direct call
        case TypeApply(Select(inner, "on"), List(typeTree)) =>
          val tpe = typeTree.tpe
          eventTypes = getTypeName(tpe) :: eventTypes
          eventHashes = eventHashes ++ getTypeHashes(tpe)
          walk(inner)

        // Value-parameter on(events*) - but filter out empty varargs
        case Apply(Select(inner, "on"), args) =>
          val realArgs = args.filterNot {
            case Typed(Repeated(Nil, _), _) => true
            case _                          => false
          }
          for arg <- realArgs do
            val name = arg.show(using Printer.TreeShortCode)
            eventTypes = name :: eventTypes
            eventHashes = eventHashes + computeHash(normalizeFullName(arg.tpe.typeSymbol.fullName))
          walk(inner)

        // onTimeout
        case Select(inner, "onTimeout") =>
          eventTypes = "Timeout" :: eventTypes
          eventHashes = eventHashes + timeoutHash
          walk(inner)

        // Inlined nodes may contain the original type-parameter call in their `call` field
        case Inlined(Some(call), _, expr) =>
          // Check if the call was a when[T] or on[T], and continue walking up the chain
          call match
            case TypeApply(Select(inner, "when"), List(typeTree)) =>
              val tpe = typeTree.tpe
              stateTypes = getTypeName(tpe) :: stateTypes
              stateHashes = stateHashes ++ getTypeHashes(tpe)
              // Continue walking the inner (receiver of when call)
              walk(inner)
            case TypeApply(Select(inner, "on"), List(typeTree)) =>
              val tpe = typeTree.tpe
              eventTypes = getTypeName(tpe) :: eventTypes
              eventHashes = eventHashes ++ getTypeHashes(tpe)
              // Continue walking the inner (receiver of on call)
              walk(inner)
            case _ => ()
          end match
          // Also walk the expr in case there's more info there
          walk(expr)

        case Inlined(None, _, expr) =>
          walk(expr)

        // Recurse through other patterns
        case Apply(fn, _)     => walk(fn)
        case TypeApply(fn, _) => walk(fn)
        case Select(inner, _) => walk(inner)
        case Block(_, expr)   => walk(expr)
        case Typed(expr, _)   => walk(expr)
        case _                => ()

      walk(term)
      (stateTypes, eventTypes, stateHashes, eventHashes)
    end extractContext

    /** Recursively extract all transitions from the AST using TreeAccumulator. */
    def extractTransitions(term: Term): List[TransitionInfo] =
      val accumulator = new TreeAccumulator[List[TransitionInfo]]:
        def foldTree(acc: List[TransitionInfo], tree: Tree)(owner: Symbol): List[TransitionInfo] =
          tree match
            case Apply(Select(receiver, "goto"), args) if args.nonEmpty =>
              val target                                             = args.head
              val (stateTypes, eventTypes, stateHashes, eventHashes) = extractContext(receiver)
              val isOverride                                         = containsOverride(receiver)
              val targetDesc = s"goto(${target.show(using Printer.TreeShortCode)})"
              val info       =
                if stateTypes.nonEmpty && eventTypes.nonEmpty then
                  List(
                    TransitionInfo(stateTypes, eventTypes, stateHashes, eventHashes, targetDesc, isOverride, tree.pos)
                  )
                else Nil
              foldOverTree(acc ++ info, tree)(owner)

            case Select(receiver, "stay") =>
              val (stateTypes, eventTypes, stateHashes, eventHashes) = extractContext(receiver)
              val isOverride                                         = containsOverride(receiver)
              val info                                               =
                if stateTypes.nonEmpty && eventTypes.nonEmpty then
                  List(TransitionInfo(stateTypes, eventTypes, stateHashes, eventHashes, "stay", isOverride, tree.pos))
                else Nil
              foldOverTree(acc ++ info, tree)(owner)

            case Apply(Select(receiver, "stop"), args) =>
              val (stateTypes, eventTypes, stateHashes, eventHashes) = extractContext(receiver)
              val isOverride                                         = containsOverride(receiver)
              val targetDesc = if args.isEmpty then "stop" else s"stop(${args.head.show(using Printer.TreeShortCode)})"
              val info       =
                if stateTypes.nonEmpty && eventTypes.nonEmpty then
                  List(
                    TransitionInfo(stateTypes, eventTypes, stateHashes, eventHashes, targetDesc, isOverride, tree.pos)
                  )
                else Nil
              foldOverTree(acc ++ info, tree)(owner)

            case Select(receiver, "stop") =>
              val (stateTypes, eventTypes, stateHashes, eventHashes) = extractContext(receiver)
              val isOverride                                         = containsOverride(receiver)
              val info                                               =
                if stateTypes.nonEmpty && eventTypes.nonEmpty then
                  List(TransitionInfo(stateTypes, eventTypes, stateHashes, eventHashes, "stop", isOverride, tree.pos))
                else Nil
              foldOverTree(acc ++ info, tree)(owner)

            case _ =>
              foldOverTree(acc, tree)(owner)

      accumulator.foldTree(Nil, term)(Symbol.spliceOwner)
    end extractTransitions

    // Extract transitions from the configure function
    val term        = configure.asTerm
    val transitions = extractTransitions(term)

    // Filter valid transitions
    val validTransitions = transitions
      .filter(t => t.stateHashes.nonEmpty && t.eventHashes.nonEmpty)
      .reverse

    // Check for duplicates
    val seen = scala.collection.mutable.Map[(Int, Int), (TransitionInfo, String, String)]()

    for t <- validTransitions do
      val hashPairs = for
        stateHash <- t.stateHashes
        eventHash <- t.eventHashes
      yield (stateHash, eventHash)

      val stateNameForError = t.stateTypes.headOption.getOrElse("<unknown>")
      val eventNameForError = t.eventTypes.headOption.getOrElse("<unknown>")

      for (stateHash, eventHash) <- hashPairs do
        val key = (stateHash, eventHash)
        seen.get(key) match
          case Some((prev, prevStateName, prevEventName)) if !t.isOverride =>
            report.errorAndAbort(
              s"""Duplicate transition detected.
                 |
                 |  State: $stateNameForError (conflicts with: $prevStateName)
                 |  Event: $eventNameForError (conflicts with: $prevEventName)
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
            seen(key) = (t, stateNameForError, eventNameForError)
        end match
      end for
    end for

    // Validation passed - apply the configure function and build
    '{ $configure($initial).build }
  end validatedImpl

end TypedFSMValidation
