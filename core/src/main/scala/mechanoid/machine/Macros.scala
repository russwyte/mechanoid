package mechanoid.machine

import scala.quoted.*
import scala.util.NotGiven
import mechanoid.core.*

/** Macros for the suite-style DSL. */
object Macros:

  /** Implementation of `all[T]` - expands sealed type to all leaf children. */
  def allImpl[T: Type](using Quotes): Expr[AllMatcher[T]] =
    import quotes.reflect.*

    val tpe = TypeRepr.of[T]
    val sym = tpe.typeSymbol

    // Find all leaf case children of a sealed type
    def findLeafCases(s: Symbol): List[Symbol] =
      if !s.exists then Nil
      else
        s.children.flatMap { child =>
          if child.flags.is(Flags.Sealed) then findLeafCases(child)
          else if child.flags.is(Flags.Case) || child.flags.is(Flags.Enum) then List(child)
          else Nil
        }

    // Compute hash using the same algorithm as CaseHasher.Default
    def computeHash(fullName: String): Int =
      val normalized = if fullName.endsWith("$") then fullName.dropRight(1) else fullName
      normalized.hashCode

    val leaves =
      if sym.flags.is(Flags.Sealed) then findLeafCases(sym)
      else List(sym) // Not sealed, just use the type itself

    if leaves.isEmpty then
      report.errorAndAbort(
        s"Type ${sym.name} has no case children. Use a sealed trait/class or enum.",
        Position.ofMacroExpansion,
      )

    val hashes = leaves.map(c => computeHash(c.fullName))
    val names  = leaves.map(_.name)

    val hashesExpr = Expr(hashes.toSet)
    val namesExpr  = Expr(names)

    '{ new AllMatcher[T]($hashesExpr, $namesExpr) }
  end allImpl

  /** Implementation of `anyOf` for states - computes hashes for given values at compile time. */
  inline def anyOfStatesImpl[S](inline first: S, inline rest: S*): AnyOfMatcher[S] =
    ${ anyOfStatesImplMacro[S]('first, 'rest) }

  def anyOfStatesImplMacro[S: Type](
      first: Expr[S],
      rest: Expr[Seq[S]],
  )(using Quotes): Expr[AnyOfMatcher[S]] =
    // For varargs, we compute hashes at runtime since we can't easily extract symbols from Seq
    // The hash computation uses className + enum case name to match compile-time Symbol.fullName
    '{
      val allValues = $first +: $rest
      val hashes    = allValues.map { v =>
        val className = v.getClass.getName.stripSuffix("$").replace('$', '.')
        // For enum members, append the case name to get the full path
        v match
          case _: scala.reflect.Enum =>
            val caseName = v.toString
            s"$className.$caseName".hashCode
          case _ =>
            className.hashCode
      }.toSet
      val names = allValues.map(_.toString).toList
      new AnyOfMatcher[S](allValues, hashes, names)
    }
  end anyOfStatesImplMacro

  /** Implementation of `anyOf` for events - computes hashes for given values at compile time. */
  inline def anyOfEventsImpl[E](inline first: E, inline rest: E*): AnyOfEventMatcher[E] =
    ${ anyOfEventsImplMacro[E]('first, 'rest) }

  def anyOfEventsImplMacro[E: Type](
      first: Expr[E],
      rest: Expr[Seq[E]],
  )(using Quotes): Expr[AnyOfEventMatcher[E]] =
    '{
      val allValues = $first +: $rest
      val hashes    = allValues.map { v =>
        val className = v.getClass.getName.stripSuffix("$").replace('$', '.')
        v match
          case _: scala.reflect.Enum =>
            val caseName = v.toString
            s"$className.$caseName".hashCode
          case _ =>
            className.hashCode
      }.toSet
      val names = allValues.map(_.toString).toList
      new AnyOfEventMatcher[E](allValues, hashes, names)
    }
  end anyOfEventsImplMacro

  /** Implementation of state `via` event - computes hashes at compile time from symbols. */
  inline def stateViaEventImpl[S, E](inline state: S, inline event: E): ViaBuilder[S, E] =
    ${ stateViaEventImplMacro[S, E]('state, 'event) }

  def stateViaEventImplMacro[S: Type, E: Type](
      state: Expr[S],
      event: Expr[E],
  )(using Quotes): Expr[ViaBuilder[S, E]] =
    import quotes.reflect.*

    // Extract the full name from an expression's symbol
    def extractFullName(expr: Expr[?]): String =
      val term                        = expr.asTerm
      def findSymbol(t: Term): Symbol = t match
        case Ident(_)             => t.symbol
        case Select(_, _)         => t.symbol
        case Inlined(_, _, inner) => findSymbol(inner)
        case Apply(fn, _)         => findSymbol(fn) // For case class constructors
        case TypeApply(fn, _)     => findSymbol(fn)
        case _                    => t.symbol

      val sym = findSymbol(term)
      if sym.exists then sym.fullName
      else report.errorAndAbort(s"Cannot extract symbol from expression: ${term.show}")
    end extractFullName

    val stateFullName = extractFullName(state)
    val eventFullName = extractFullName(event)
    val stateHash     = Expr(stateFullName.hashCode)
    val eventHash     = Expr(eventFullName.hashCode)

    '{
      new ViaBuilder[S, E](
        Set($stateHash),
        Set($eventHash),
        List($state.toString),
        List($event.toString),
      )
    }
  end stateViaEventImplMacro

  /** Compute hash for a single value at compile time. */
  inline def computeHashFor[T](inline value: T): Int =
    ${ computeHashForImpl[T]('value) }

  def computeHashForImpl[T: Type](value: Expr[T])(using Quotes): Expr[Int] =
    import quotes.reflect.*

    def findSymbol(t: Term): Symbol = t match
      case Ident(_)             => t.symbol
      case Select(_, _)         => t.symbol
      case Inlined(_, _, inner) => findSymbol(inner)
      case Apply(fn, _)         => findSymbol(fn)
      case TypeApply(fn, _)     => findSymbol(fn)
      case _                    => t.symbol

    val term = value.asTerm
    val sym  = findSymbol(term)

    if sym.exists then Expr(sym.fullName.hashCode)
    else report.errorAndAbort(s"Cannot extract symbol from expression: ${term.show}")
  end computeHashForImpl

  /** Compile-time info extracted from a TransitionSpec expression. */
  private case class SpecInfo(
      stateHashes: Set[Int],
      eventHashes: Set[Int],
      isOverride: Boolean,
      targetDesc: String,
      sourcePos: String,
  )

  /** Implementation of `build` macro - validates specs at COMPILE TIME. */
  def buildImpl[S: Type, E: Type](
      specs: Expr[Seq[TransitionSpec[S, E, ?]]]
  )(using Quotes): Expr[Machine[S, E, Nothing]] =
    import quotes.reflect.*

    // Extract individual spec expressions from varargs
    val specExprs: List[Expr[TransitionSpec[S, E, ?]]] = specs match
      case Varargs(exprs) => exprs.toList
      case other          =>
        report.errorAndAbort(s"Expected varargs of TransitionSpec, got: ${other.show}")

    // Extract compile-time info from each spec expression by walking the AST
    // This handles the complex inlined code with blocks and variable bindings
    def extractSpecInfo(expr: Expr[TransitionSpec[S, E, ?]], idx: Int): SpecInfo =
      val term = expr.asTerm

      // Helper to extract int literals from a Set expression like Set(hash1, hash2)
      def extractSetInts(setTerm: Term): Set[Int] =
        val ints = scala.collection.mutable.Set[Int]()
        object Collector extends TreeAccumulator[Unit]:
          def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
            tree match
              case Literal(IntConstant(v)) => ints += v
              case _                       => foldOverTree((), tree)(owner)
        Collector.foldTree((), setTerm)(Symbol.spliceOwner)
        ints.toSet

      // Extract string literals from a List expression
      def extractListStrings(listTerm: Term): List[String] =
        val strs = scala.collection.mutable.ListBuffer[String]()
        object Collector extends TreeAccumulator[Unit]:
          def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
            tree match
              case Literal(StringConstant(v)) if v.nonEmpty => strs += v
              case _                                        => foldOverTree((), tree)(owner)
        Collector.foldTree((), listTerm)(Symbol.spliceOwner)
        strs.toList

      // Find TransitionSpec apply/goto/stay/stop calls and extract their arguments
      // TransitionSpec.apply(stateHashes, eventHashes, stateNames, eventNames, targetDesc, isOverride, handler)
      // TransitionSpec.goto(stateHashes, eventHashes, stateNames, eventNames, target)
      case class SpecArgs(
          stateHashes: Set[Int],
          eventHashes: Set[Int],
          stateNames: List[String],
          eventNames: List[String],
          targetDesc: String,
          isOverride: Boolean,
      )

      object SpecArgsFinder extends TreeAccumulator[Option[SpecArgs]]:
        def foldTree(found: Option[SpecArgs], tree: Tree)(owner: Symbol): Option[SpecArgs] =
          found.orElse {
            tree match
              // Match @@ extension method call: @@(spec)(aspect)
              // Extension methods are curried: Apply(Apply(fn, List(spec)), List(aspect))
              case Apply(Apply(TypeApply(Select(_, "@@"), _), specArgs), aspectArgs)
                  if specArgs.nonEmpty && aspectArgs.nonEmpty =>
                val isOverride = isAspectOverriding(aspectArgs(0))
                foldTree(None, specArgs(0))(owner).map(spec =>
                  if isOverride then spec.copy(isOverride = true) else spec
                )

              case Apply(Apply(Select(_, "@@"), specArgs), aspectArgs) if specArgs.nonEmpty && aspectArgs.nonEmpty =>
                val isOverride = isAspectOverriding(aspectArgs(0))
                foldTree(None, specArgs(0))(owner).map(spec =>
                  if isOverride then spec.copy(isOverride = true) else spec
                )

              // Fallback: single Apply for @@ (less common but handle it)
              case Apply(TypeApply(Select(_, "@@"), _), args) if args.length >= 2 =>
                val isOverride = isAspectOverriding(args(1))
                foldTree(None, args(0))(owner).map(spec => if isOverride then spec.copy(isOverride = true) else spec)

              case Apply(Select(_, "@@"), args) if args.length >= 2 =>
                val isOverride = isAspectOverriding(args(1))
                foldTree(None, args(0))(owner).map(spec => if isOverride then spec.copy(isOverride = true) else spec)

              // Match new ViaBuilder(...) constructor
              // AST: new mechanoid.machine.ViaBuilder[...](stateHashesSet, eventHashesSet, stateNamesList, eventNamesList)
              case Apply(TypeApply(Select(New(tpt), "<init>"), _), args)
                  if args.length >= 4 && tpt.show.contains("ViaBuilder") =>
                val stateHashes = extractSetInts(args(0))
                val eventHashes = extractSetInts(args(1))
                val stateNames  = extractListStrings(args(2))
                val eventNames  = extractListStrings(args(3))
                // We'll set targetDesc later when we find the TransitionSpec
                Some(SpecArgs(stateHashes, eventHashes, stateNames, eventNames, "?", false))

              // Match new AllMatcher(...) constructor (used by all[T])
              // AST: new AllMatcher[...](hashesSet, namesList)
              case Apply(TypeApply(Select(New(tpt), "<init>"), _), args)
                  if args.length >= 2 && tpt.show.contains("AllMatcher") =>
                val stateHashes = extractSetInts(args(0))
                val stateNames  = extractListStrings(args(1))
                // AllMatcher is used for states; event hashes will be added by the ViaBuilder
                Some(SpecArgs(stateHashes, Set.empty, stateNames, Nil, "?", false))

              // Match new StateMatcher(...) constructor (used by state[T])
              // AST: new StateMatcher[S](hash, name)
              case Apply(TypeApply(Select(New(tpt), "<init>"), _), args)
                  if args.length >= 2 && tpt.show.contains("StateMatcher") =>
                for
                  stateHash <- extractSingleInt(args(0))
                  stateName <- extractStringLiteral(args(1))
                yield SpecArgs(Set(stateHash), Set.empty, List(stateName), Nil, "?", false)

              // Match new EventMatcher(...) constructor (used by event[T])
              // AST: new EventMatcher[E](hash, name)
              case Apply(TypeApply(Select(New(tpt), "<init>"), _), args)
                  if args.length >= 2 && tpt.show.contains("EventMatcher") =>
                for
                  eventHash <- extractSingleInt(args(0))
                  eventName <- extractStringLiteral(args(1))
                yield SpecArgs(Set.empty, Set(eventHash), Nil, List(eventName), "?", false)

              // Match TransitionSpec.goto/stay/stop calls for target info
              case Apply(Select(qual, methodName), args) if qual.show.contains("TransitionSpec") && args.length >= 4 =>
                val stateHashes = extractSetInts(args(0))
                val eventHashes = extractSetInts(args(1))
                val stateNames  = extractListStrings(args(2))
                val eventNames  = extractListStrings(args(3))

                val targetDesc = methodName match
                  case "goto" if args.length >= 5 =>
                    s"-> ${extractTargetName(args(4))}"
                  case "stay" =>
                    "stay"
                  case "stop" =>
                    val reason = if args.length >= 5 then extractStringLiteral(args(4)) else None
                    reason.fold("stop")(r => s"stop($r)")
                  case _ =>
                    "?"

                Some(SpecArgs(stateHashes, eventHashes, stateNames, eventNames, targetDesc, false))

              // Match spec.copy(isOverride = true)
              case Apply(Select(inner, "copy"), args) =>
                val innerSpec = foldTree(None, inner)(owner)
                innerSpec.map { spec =>
                  val hasOverride = args.exists {
                    case NamedArg("isOverride", Literal(BooleanConstant(true))) => true
                    case _                                                      => false
                  }
                  if hasOverride then spec.copy(isOverride = true) else spec
                }

              case _ =>
                foldOverTree(None, tree)(owner)
          }
      end SpecArgsFinder

      // Check if a term is Aspect.overriding
      def isAspectOverriding(t: Term): Boolean =
        t.show.contains("Aspect.overriding") || t.show.contains("overriding")

      def extractTargetName(t: Term): String = t match
        case Select(_, name)                              => name
        case Ident(name)                                  => name
        case Inlined(_, _, inner)                         => extractTargetName(inner.asInstanceOf[Term])
        case Apply(Select(_, name), _) if name != "apply" => name
        case Apply(TypeApply(Select(_, name), _), _)      => name
        case _                                            => "?"

      def extractStringLiteral(t: Term): Option[String] = t match
        case Literal(StringConstant(v)) => Some(v)
        case Inlined(_, _, inner)       => extractStringLiteral(inner.asInstanceOf[Term])
        case _                          => None

      // Extract a single integer literal from a term
      def extractSingleInt(t: Term): Option[Int] = t match
        case Literal(IntConstant(v)) => Some(v)
        case Inlined(_, _, inner)    => extractSingleInt(inner.asInstanceOf[Term])
        case _                       => None

      // Try to extract spec args from the AST
      val specArgs = SpecArgsFinder.foldTree(None, term)(Symbol.spliceOwner)

      // Check if this looks like an @@ call at the top level
      val hasOverrideAspect = term.show.contains("Aspect.overriding") || term.show.contains(".overriding")

      specArgs match
        case Some(args) =>
          val sourcePos =
            if args.stateNames.nonEmpty && args.eventNames.nonEmpty then
              s"${args.stateNames.mkString(",")} via ${args.eventNames.mkString(",")}"
            else s"spec #${idx + 1}"
          // If we found @@ in the AST but didn't detect override through pattern matching, use the string check
          val effectiveOverride = args.isOverride || hasOverrideAspect
          SpecInfo(args.stateHashes, args.eventHashes, effectiveOverride, args.targetDesc, sourcePos)
        case None =>
          // Fallback: couldn't parse the AST structure, warn and use empty sets
          // This means no compile-time duplicate detection for this spec
          report.warning(
            s"Cannot analyze spec #${idx + 1} at compile time. " +
              s"Duplicate detection will be limited. AST: ${term.show.take(500)}",
            expr,
          )
          SpecInfo(Set.empty, Set.empty, hasOverrideAspect, "?", s"spec #${idx + 1}")
      end match
    end extractSpecInfo

    // Extract info from all specs
    val specInfos = specExprs.zipWithIndex.map { case (expr, idx) =>
      (extractSpecInfo(expr, idx), idx)
    }

    // Build registry: (stateHash, eventHash) -> List[(SpecInfo, index)]
    val registry = scala.collection.mutable.Map[(Int, Int), List[(SpecInfo, Int)]]()

    for (info, idx) <- specInfos do
      for
        stateHash <- info.stateHashes
        eventHash <- info.eventHashes
      do
        val key = (stateHash, eventHash)
        registry(key) = registry.getOrElse(key, Nil) :+ (info, idx)

    // Check for duplicates at compile time
    val overrideInfos = scala.collection.mutable.ListBuffer[String]()

    for (_, specList) <- registry if specList.size > 1 do
      val (first, firstIdx) = specList.head

      for (info, idx) <- specList.tail do
        if !info.isOverride then
          // Compile error for duplicate without override
          report.errorAndAbort(
            s"""Duplicate transition without override!
               |  Transition: ${info.sourcePos} ${info.targetDesc}
               |  First defined at spec #${firstIdx + 1}: ${first.targetDesc}
               |  Duplicate at spec #${idx + 1}: ${info.targetDesc}
               |
               |  To override, use: (${info.sourcePos} to ...) @@ Aspect.overriding""".stripMargin
          )
        else
          // Track override for info message
          val prevTarget = first.targetDesc.stripPrefix("-> ")
          val newTarget  = info.targetDesc.stripPrefix("-> ")
          overrideInfos += s"  ${info.sourcePos}: $prevTarget (spec #${firstIdx + 1}) -> $newTarget (spec #${idx + 1})"
      end for
    end for

    // Emit info about overrides if any
    if overrideInfos.nonEmpty then
      report.info(s"[mechanoid] Override info (${overrideInfos.size} overrides):\n${overrideInfos.mkString("\n")}")

    // Generate the Machine
    '{ Machine.fromSpecs[S, E, Nothing]($specs.toList.asInstanceOf[List[TransitionSpec[S, E, Nothing]]]) }
  end buildImpl

  /** Implementation of `build` macro with inferred command type.
    *
    * This macro:
    *   1. Accepts both TransitionSpec and Machine arguments
    *   2. Extracts specs from nested machines at runtime
    *   3. Performs compile-time duplicate detection for inline specs
    *   4. Extracts command types from `emitting` and `emittingBefore` calls
    *   5. Computes the LUB (least upper bound) of all command types
    *   6. Returns Machine[S, E, Cmd] with the inferred Cmd type
    *
    * Note: Duplicate detection across nested machines happens at runtime in fromSpecs.
    */
  def buildWithInferredCmdImpl[S: Type, E: Type](
      args: Expr[Seq[TransitionSpec[S, E, ?] | Machine[S, E, ?]]]
  )(using Quotes): Expr[Machine[S, E, ?]] =
    import quotes.reflect.*

    // Check if a type is Machine[_, _, _]
    def isMachineType(tpe: TypeRepr): Boolean =
      tpe.dealias.widen match
        case AppliedType(base, _) =>
          base.typeSymbol.fullName == "mechanoid.machine.Machine"
        case _ => false

    // Check if a type is TransitionSpec[_, _, _]
    def isTransitionSpecType(tpe: TypeRepr): Boolean =
      tpe.dealias.widen match
        case AppliedType(base, _) =>
          base.typeSymbol.fullName == "mechanoid.machine.TransitionSpec"
        case _ => false

    // Extract individual expressions from varargs
    val rawExprs: List[Expr[?]] = args match
      case Varargs(exprs) => exprs.toList
      case other          =>
        report.errorAndAbort(s"Expected varargs, got: ${other.show}")

    // Separate machines from inline specs
    val (machineExprs, specExprs): (List[Expr[Machine[S, E, ?]]], List[Expr[TransitionSpec[S, E, ?]]]) =
      (rawExprs.foldLeft((List.empty[Expr[Machine[S, E, ?]]], List.empty[Expr[TransitionSpec[S, E, ?]]])) {
        case ((machines, specs), expr) =>
          val tpe = expr.asTerm.tpe.widen
          if isMachineType(tpe) then (machines :+ expr.asInstanceOf[Expr[Machine[S, E, ?]]], specs)
          else if isTransitionSpecType(tpe) then (machines, specs :+ expr.asInstanceOf[Expr[TransitionSpec[S, E, ?]]])
          else
            report.errorAndAbort(
              s"build() expects TransitionSpec or Machine arguments, got: ${tpe.show}"
            )
      }): @unchecked

    // Compile-time duplicate detection based on state/event hashes
    // Works for both inline expressions AND val references by looking up val definitions

    // Helper to extract int literals from a Set expression
    def extractSetInts(setTerm: Term): Set[Int] =
      val ints = scala.collection.mutable.Set[Int]()
      object Collector extends TreeAccumulator[Unit]:
        def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
          tree match
            case Literal(IntConstant(v)) => ints += v
            case _                       => foldOverTree((), tree)(owner)
      Collector.foldTree((), setTerm)(Symbol.spliceOwner)
      ints.toSet

    // Extract string literals from a List expression
    def extractListStrings(listTerm: Term): List[String] =
      val strs = scala.collection.mutable.ListBuffer[String]()
      object Collector extends TreeAccumulator[Unit]:
        def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
          tree match
            case Literal(StringConstant(v)) if v.nonEmpty => strs += v
            case _                                        => foldOverTree((), tree)(owner)
      Collector.foldTree((), listTerm)(Symbol.spliceOwner)
      strs.toList

    case class SpecHashInfo(
        stateHashes: Set[Int],
        eventHashes: Set[Int],
        stateNames: List[String],
        eventNames: List[String],
        targetDesc: String,
        isOverride: Boolean,
        sourceDesc: String,
    )

    // Extract hash info from a term (either inline expr or val definition)
    def extractHashInfo(term: Term): Option[SpecHashInfo] =
      object HashFinder extends TreeAccumulator[Option[SpecHashInfo]]:
        def foldTree(found: Option[SpecHashInfo], tree: Tree)(owner: Symbol): Option[SpecHashInfo] =
          found.orElse {
            tree match
              // Match @@ extension method call
              case Apply(Select(base, "@@"), List(aspectArg)) =>
                val isOverride = aspectArg.show.contains("overriding")
                foldTree(None, base)(owner).map(_.copy(isOverride = isOverride))

              // Match new ViaBuilder(...) constructor
              case Apply(TypeApply(Select(New(tpt), "<init>"), _), args)
                  if args.length >= 4 && tpt.show.contains("ViaBuilder") =>
                val stateHashes = extractSetInts(args(0))
                val eventHashes = extractSetInts(args(1))
                val stateNames  = extractListStrings(args(2))
                val eventNames  = extractListStrings(args(3))
                Some(SpecHashInfo(stateHashes, eventHashes, stateNames, eventNames, "?", false, "?"))

              // Match TransitionSpec.goto/stay/stop calls
              case Apply(Select(qual, methodName), args) if qual.show.contains("TransitionSpec") && args.length >= 4 =>
                val stateHashes = extractSetInts(args(0))
                val eventHashes = extractSetInts(args(1))
                val stateNames  = extractListStrings(args(2))
                val eventNames  = extractListStrings(args(3))
                val targetDesc  = methodName match
                  case "goto" => if args.length >= 5 then s"-> ${extractTargetName(args(4))}" else "-> ?"
                  case "stay" => "stay"
                  case "stop" => "stop"
                  case _      => "?"
                Some(SpecHashInfo(stateHashes, eventHashes, stateNames, eventNames, targetDesc, false, "?"))

              // Match new AllMatcher(...) constructor
              case Apply(TypeApply(Select(New(tpt), "<init>"), _), args)
                  if args.length >= 2 && tpt.show.contains("AllMatcher") =>
                val stateHashes = extractSetInts(args(0))
                val stateNames  = extractListStrings(args(1))
                Some(SpecHashInfo(stateHashes, Set.empty, stateNames, Nil, "?", false, "?"))

              // Match spec.copy for override detection
              case Apply(Select(inner, "copy"), args) =>
                val innerInfo = foldTree(None, inner)(owner)
                innerInfo.map { info =>
                  val hasOverride = args.exists {
                    case NamedArg("isOverride", Literal(BooleanConstant(true))) => true
                    case _                                                      => false
                  }
                  if hasOverride then info.copy(isOverride = true) else info
                }

              case _ => foldOverTree(None, tree)(owner)
          }
      end HashFinder

      def extractTargetName(t: Term): String = t match
        case Select(_, name)      => name
        case Ident(name)          => name
        case Inlined(_, _, inner) => extractTargetName(inner.asInstanceOf[Term])
        case _                    => "?"

      HashFinder.foldTree(None, term)(Symbol.spliceOwner)
    end extractHashInfo

    // For val references, try to get the definition tree
    def getDefinitionTree(term: Term): Option[Term] =
      term match
        case Ident(_) =>
          val sym = term.symbol
          // Try to get the val's RHS from its definition
          try
            sym.tree match
              case ValDef(_, _, Some(rhs)) => Some(rhs)
              case _                       => None
          catch case _: Exception => None
        case Apply(Select(base, "@@"), _) =>
          // For t1 @@ Aspect.overriding, get the definition of t1
          getDefinitionTree(base)
        case _ => None

    // Extract info for each spec, handling both inline and val references
    def getSpecInfo(expr: Expr[TransitionSpec[S, E, ?]], idx: Int): SpecHashInfo =
      val term = expr.asTerm

      // First try direct extraction from the term
      val directInfo = extractHashInfo(term)

      // If that fails (e.g., for plain val reference), try to get the definition tree
      val info = directInfo.orElse {
        getDefinitionTree(term).flatMap(extractHashInfo)
      }

      // Check for override at the call site (e.g., t1 @@ Aspect.overriding)
      val hasOverrideAtCallSite = term.show.contains("overriding")

      info match
        case Some(h) =>
          val sourceDesc =
            if h.stateNames.nonEmpty && h.eventNames.nonEmpty then
              s"${h.stateNames.mkString(",")} via ${h.eventNames.mkString(",")}"
            else s"spec #${idx + 1}"
          h.copy(
            isOverride = h.isOverride || hasOverrideAtCallSite,
            sourceDesc = sourceDesc,
          )
        case None =>
          // Fallback - couldn't extract hashes, runtime detection will catch duplicates
          SpecHashInfo(Set.empty, Set.empty, Nil, Nil, "?", hasOverrideAtCallSite, s"spec #${idx + 1}")
      end match
    end getSpecInfo

    // Extract info from all specs
    val specInfos = specExprs.zipWithIndex.map { case (expr, idx) =>
      (getSpecInfo(expr, idx), idx)
    }

    // Build registry: (stateHash, eventHash) -> List[(SpecHashInfo, index)]
    val registry = scala.collection.mutable.Map[(Int, Int), List[(SpecHashInfo, Int)]]()

    for (info, idx) <- specInfos do
      for
        stateHash <- info.stateHashes
        eventHash <- info.eventHashes
      do
        val key = (stateHash, eventHash)
        registry(key) = registry.getOrElse(key, Nil) :+ (info, idx)

    // Check for duplicates at compile time
    val overrideInfos = scala.collection.mutable.ListBuffer[String]()

    for (_, specList) <- registry if specList.size > 1 do
      val (first, firstIdx) = specList.head

      for (info, idx) <- specList.tail do
        if !info.isOverride then
          // Compile error for duplicate without override
          report.errorAndAbort(
            s"""Duplicate transition without override!
               |  Transition: ${info.sourceDesc} ${info.targetDesc}
               |  First defined at spec #${firstIdx + 1}: ${first.targetDesc}
               |  Duplicate at spec #${idx + 1}: ${info.targetDesc}
               |
               |  To override, use: (...) @@ Aspect.overriding""".stripMargin
          )
        else
          // Track override for info message
          val prevTarget = first.targetDesc.stripPrefix("-> ")
          val newTarget  = info.targetDesc.stripPrefix("-> ")
          overrideInfos += s"  ${info.sourceDesc}: $prevTarget (spec #${firstIdx + 1}) -> $newTarget (spec #${idx + 1})"
      end for
    end for

    // Emit info about overrides if any (from hash-based detection)
    if overrideInfos.nonEmpty then
      report.info(s"[mechanoid] Override info (${overrideInfos.size} overrides):\n${overrideInfos.mkString("\n")}")

    // ALSO do symbol-based detection for when same val is used twice
    // This catches: build(t1, t2, t1 @@ Aspect.overriding) even when hashes can't be extracted
    def isAtAtMethod(t: Term): Boolean = t match
      case Ident(name)         => name == "@@"
      case Select(_, "@@")     => true
      case TypeApply(inner, _) => isAtAtMethod(inner)
      case _                   => false

    def getBaseSymbol(term: Term): Option[Symbol] =
      term match
        // Extension method pattern: Apply(Apply(TypeApply(Ident(@@), _), [specArg]), [aspectArg])
        // or: Apply(Apply(Ident(@@), [specArg]), [aspectArg])
        case Apply(Apply(inner, specArgs), _) if isAtAtMethod(inner) && specArgs.nonEmpty =>
          specArgs.head match
            case Ident(_) => Some(specArgs.head.symbol)
            case _        => getBaseSymbol(specArgs.head)
        // Single Apply pattern (older encoding): Apply(Select(base, "@@"), _)
        case Apply(Select(base, "@@"), _) => getBaseSymbol(base)
        // Plain val reference
        case Ident(_) => Some(term.symbol)
        case _        => None

    def hasOverrideAtCallSite(term: Term): Boolean =
      term match
        // Extension method pattern with double Apply
        case Apply(Apply(inner, _), aspectArgs) if isAtAtMethod(inner) && aspectArgs.nonEmpty =>
          aspectArgs.head.show.contains("overriding")
        // Single Apply pattern
        case Apply(Select(_, "@@"), List(aspectArg)) =>
          aspectArg.show.contains("overriding")
        case _ => false

    // Track seen symbols: symbol -> (index, name)
    // Note: Machine duplicate detection happens at runtime in fromSpecs after flattening.
    // This allows machines with override specs to be used multiple times.
    var seenSymbols         = Map.empty[Symbol, (Int, String)]
    val symbolOverrideInfos = scala.collection.mutable.ListBuffer[String]()

    // Process symbol-based duplicate detection for SPECS
    for (expr, idx) <- specExprs.zipWithIndex do
      val term        = expr.asTerm
      val foundSymbol = getBaseSymbol(term)
      foundSymbol.foreach { sym =>
        val hasOverride = hasOverrideAtCallSite(term)
        seenSymbols.get(sym) match
          case Some((firstIdx, _)) if !hasOverride =>
            // Duplicate without override - compile error
            report.errorAndAbort(
              s"""Duplicate transition: val '${sym.name}' used at positions ${firstIdx + 1} and ${idx + 1}.
                 |
                 |To override, use: ${sym.name} @@ Aspect.overriding""".stripMargin
            )
          case Some((firstIdx, _)) if hasOverride =>
            // Duplicate with override - emit info
            symbolOverrideInfos += s"  val '${sym.name}' at position ${idx + 1} overrides position ${firstIdx + 1}"
          case _ =>
            seenSymbols = seenSymbols + (sym -> (idx, sym.name))
        end match
      }
    end for

    // Emit symbol-based override info
    if symbolOverrideInfos.nonEmpty then
      report.info(
        s"[mechanoid] Val override (${symbolOverrideInfos.size} overrides):\n${symbolOverrideInfos.mkString("\n")}"
      )

    // Extract command type from TransitionSpec[S, E, Cmd] type
    def extractCmdTypeFromSpec(expr: Expr[TransitionSpec[S, E, ?]]): Option[TypeRepr] =
      val tpe = expr.asTerm.tpe.widen
      tpe match
        case AppliedType(_, List(_, _, cmdType)) =>
          // Skip Nothing (the default when no commands)
          if cmdType =:= TypeRepr.of[Nothing] then None
          else Some(cmdType)
        case _ => None

    // Extract command type from Machine[S, E, Cmd] type
    def extractCmdTypeFromMachine(expr: Expr[Machine[S, E, ?]]): Option[TypeRepr] =
      val tpe = expr.asTerm.tpe.widen
      tpe match
        case AppliedType(_, List(_, _, cmdType)) =>
          if cmdType =:= TypeRepr.of[Nothing] then None
          else Some(cmdType)
        case _ => None

    // Collect all command types from specs and machines
    val specCmdTypes    = specExprs.flatMap(extractCmdTypeFromSpec)
    val machineCmdTypes = machineExprs.flatMap(extractCmdTypeFromMachine)
    val allCmdTypes     = specCmdTypes ++ machineCmdTypes

    // Compute LUB (least upper bound / nearest common ancestor) of all command types
    val inferredCmdType: TypeRepr =
      if allCmdTypes.isEmpty then TypeRepr.of[Nothing]
      else if allCmdTypes.size == 1 then allCmdTypes.head
      else
        // Find the nearest common ancestor by intersecting base classes
        def baseClasses(tpe: TypeRepr): List[Symbol] = tpe.baseClasses
        val baseClassLists                           = allCmdTypes.map(baseClasses)
        val commonBases                              = baseClassLists.reduce { (a, b) =>
          a.filter(sym => b.contains(sym))
        }
        val skipSymbols = Set("scala.Any", "scala.AnyRef", "scala.Matchable", "java.lang.Object")
        val usefulBases = commonBases.filterNot(sym => skipSymbols.contains(sym.fullName))

        if usefulBases.nonEmpty then usefulBases.head.typeRef
        else if commonBases.nonEmpty then TypeRepr.of[Any]
        else TypeRepr.of[Any]

    // Summon the required instances
    val stateEnumExpr = Expr
      .summon[Finite[S]]
      .getOrElse(
        report.errorAndAbort(s"Cannot find Finite instance for state type")
      )
    val eventEnumExpr = Expr
      .summon[Finite[E]]
      .getOrElse(
        report.errorAndAbort(s"Cannot find Finite instance for event type")
      )

    // Build with the inferred command type
    inferredCmdType.asType match
      case '[cmd] =>
        // Create expressions for inline specs list
        val inlineSpecsExpr: Expr[List[TransitionSpec[S, E, cmd]]] =
          Expr.ofList(specExprs.map(_.asInstanceOf[Expr[TransitionSpec[S, E, cmd]]]))

        // Create expression that extracts and combines all specs
        if machineExprs.isEmpty then
          // No machines - just use inline specs
          '{
            given Finite[S] = $stateEnumExpr
            given Finite[E] = $eventEnumExpr
            Machine.fromSpecs[S, E, cmd]($inlineSpecsExpr)
          }
        else
          // Has machines - combine machine specs with inline specs
          val machineListExpr: Expr[List[Machine[S, E, ?]]] = Expr.ofList(machineExprs)
          '{
            given Finite[S]  = $stateEnumExpr
            given Finite[E]  = $eventEnumExpr
            val machineSpecs = $machineListExpr.flatMap(_.specs.asInstanceOf[List[TransitionSpec[S, E, cmd]]])
            val allSpecs     = machineSpecs ++ $inlineSpecsExpr
            Machine.fromSpecs[S, E, cmd](allSpecs)
          }
        end if
    end match
  end buildWithInferredCmdImpl

  /** Implementation of `buildAll` macro - collects specs and machines from a block.
    *
    * This macro has full parity with `build`:
    *   1. Pattern matches on Block(statements, finalExpr)
    *   2. Preserves local val definitions in the generated code
    *   3. Extracts all TransitionSpec AND Machine expressions from the block
    *   4. Performs hash-based duplicate detection at compile time
    *   5. Performs symbol-based duplicate detection (same val used twice)
    *   6. Emits override info messages via report.info()
    *   7. Infers command type from emitting/emittingBefore calls and machines
    *   8. Returns Machine[S, E, Cmd]
    *   9. Requires include() wrapper for val references (enforced at compile time)
    *
    * Unlike a naive extraction approach, this preserves the block structure so local vals can be referenced by
    * TransitionSpec expressions (e.g., in emitting functions).
    */
  def buildAllImpl[S: Type, E: Type](
      block: Expr[Any]
  )(using Quotes): Expr[Machine[S, E, ?]] =
    import quotes.reflect.*

    val term                   = block.asTerm
    val transitionSpecTypeName = "TransitionSpec"
    val machineTypeName        = "mechanoid.machine.Machine"

    // Check if a term has TransitionSpec type
    def isTransitionSpec(term: Term): Boolean =
      term.tpe.dealias.widen match
        case AppliedType(base, _) =>
          base.typeSymbol.fullName.contains(transitionSpecTypeName)
        case _ => false

    // Check if a term has Machine type
    def isMachine(term: Term): Boolean =
      term.tpe.dealias.widen match
        case AppliedType(base, _) =>
          base.typeSymbol.fullName == machineTypeName
        case _ => false

    // Check if a term is wrapped in include()
    // Since include is an inline def, it gets inlined as Inlined(Some(call), _, innerValue)
    def isIncludeCall(term: Term): Boolean =
      term match
        case Apply(Ident("include"), _)                   => true
        case Apply(Select(_, "include"), _)               => true
        case Apply(TypeApply(Ident("include"), _), _)     => true
        case Apply(TypeApply(Select(_, "include"), _), _) => true
        // Check if the Inlined node came from an include() call
        case Inlined(Some(call), _, _) =>
          call.show.contains("include") || call.symbol.name == "include"
        case Inlined(None, _, inner) => isIncludeCall(inner)
        case _                       => false

    // Check if a term is a simple val reference (Ident) to a Machine or TransitionSpec
    // that is NOT wrapped in include()
    def isUnwrappedValRef(term: Term): Boolean =
      // First check if this is an include() call - if so, it's not unwrapped
      if isIncludeCall(term) then false
      else
        term match
          case Ident(_) =>
            isMachine(term) || isTransitionSpec(term)
          // Handle inlined expressions - look inside
          case Inlined(_, _, inner) =>
            isUnwrappedValRef(inner)
          case _ => false

    // Check if a statement is a ValDef that is neither TransitionSpec nor Machine
    // These are helper vals that should be preserved
    def isHelperValDef(stmt: Statement): Boolean = stmt match
      case ValDef(_, _, Some(rhs)) => !isTransitionSpec(rhs) && !isMachine(rhs)
      case _                       => false

    // Validate statements - val refs must use include()
    def validateStatement(stmt: Statement): Unit = stmt match
      case term: Term if isUnwrappedValRef(term) =>
        val name = term match
          case Ident(n) => n
          case _        => "value"
        report.errorAndAbort(
          s"""Val reference '$name' requires include() wrapper in buildAll block.
             |
             |Use: include($name)
             |
             |This eliminates the compiler warning about pure expressions.""".stripMargin,
          term.pos,
        )
      case _ => () // OK - inline expression or already wrapped

    // Collect TransitionSpec expressions, Machine expressions, and helper vals from the block
    def processBlock(t: Term): (List[Statement], List[Term], List[Term]) = t match
      case Block(statements, finalExpr) =>
        // Validate each statement - val refs must use include()
        statements.foreach(validateStatement)
        // Also validate finalExpr if it's an unwrapped val ref
        finalExpr match
          case term: Term if isUnwrappedValRef(term) => validateStatement(term)
          case _                                     => ()

        // Collect helper vals (non-TransitionSpec, non-Machine vals)
        val helperVals = statements.filter(isHelperValDef)

        // Collect TransitionSpec expressions
        val specTerms = statements.flatMap {
          case term: Term if isTransitionSpec(term)                => List(term)
          case ValDef(name, _, Some(rhs)) if isTransitionSpec(rhs) =>
            // Create reference to the val (so the spec is captured properly)
            List(
              Ref(Symbol.requiredModule("scala.Predef"))
                .select(Symbol.requiredMethod("identity"))
                .appliedToType(rhs.tpe)
                .appliedTo(rhs)
            )
          case _ => Nil
        }

        // Collect Machine expressions
        val machineTerms = statements.flatMap {
          case term: Term if isMachine(term)                => List(term)
          case ValDef(name, _, Some(rhs)) if isMachine(rhs) =>
            List(
              Ref(Symbol.requiredModule("scala.Predef"))
                .select(Symbol.requiredMethod("identity"))
                .appliedToType(rhs.tpe)
                .appliedTo(rhs)
            )
          case _ => Nil
        }

        val finalSpecs    = if isTransitionSpec(finalExpr) then List(finalExpr) else Nil
        val finalMachines = if isMachine(finalExpr) then List(finalExpr) else Nil

        (helperVals, specTerms ++ finalSpecs, machineTerms ++ finalMachines)

      case Inlined(_, bindings, inner) =>
        val (innerVals, innerSpecs, innerMachines) = processBlock(inner)
        (bindings ++ innerVals, innerSpecs, innerMachines)

      case other if isTransitionSpec(other) =>
        (Nil, List(other), Nil)

      case other if isMachine(other) =>
        (Nil, Nil, List(other))

      case _ => (Nil, Nil, Nil)

    val (helperVals, specTerms, machineTerms) = processBlock(term)

    if specTerms.isEmpty && machineTerms.isEmpty then
      report.errorAndAbort(
        "No TransitionSpec or Machine expressions found in block. " +
          "Each line should be a transition (State via Event to Target) or a Machine."
      )

    // Summon the required instances
    val stateEnumExpr = Expr
      .summon[Finite[S]]
      .getOrElse(
        report.errorAndAbort(s"Cannot find Finite instance for state type")
      )
    val eventEnumExpr = Expr
      .summon[Finite[E]]
      .getOrElse(
        report.errorAndAbort(s"Cannot find Finite instance for event type")
      )

    // ============================================
    // Hash-based duplicate detection (same as buildWithInferredCmdImpl)
    // ============================================

    // Helper to extract int literals from a Set expression
    def extractSetInts(setTerm: Term): Set[Int] =
      val ints = scala.collection.mutable.Set[Int]()
      object Collector extends TreeAccumulator[Unit]:
        def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
          tree match
            case Literal(IntConstant(v)) => ints += v
            case _                       => foldOverTree((), tree)(owner)
      Collector.foldTree((), setTerm)(Symbol.spliceOwner)
      ints.toSet

    // Extract string literals from a List expression
    def extractListStrings(listTerm: Term): List[String] =
      val strs = scala.collection.mutable.ListBuffer[String]()
      object Collector extends TreeAccumulator[Unit]:
        def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
          tree match
            case Literal(StringConstant(v)) if v.nonEmpty => strs += v
            case _                                        => foldOverTree((), tree)(owner)
      Collector.foldTree((), listTerm)(Symbol.spliceOwner)
      strs.toList

    case class SpecHashInfo(
        stateHashes: Set[Int],
        eventHashes: Set[Int],
        stateNames: List[String],
        eventNames: List[String],
        targetDesc: String,
        isOverride: Boolean,
        sourceDesc: String,
    )

    // Extract hash info from a term (either inline expr or val definition)
    def extractHashInfo(term: Term): Option[SpecHashInfo] =
      object HashFinder extends TreeAccumulator[Option[SpecHashInfo]]:
        def foldTree(found: Option[SpecHashInfo], tree: Tree)(owner: Symbol): Option[SpecHashInfo] =
          found.orElse {
            tree match
              // Match @@ extension method call
              case Apply(Select(base, "@@"), List(aspectArg)) =>
                val isOverride = aspectArg.show.contains("overriding")
                foldTree(None, base)(owner).map(_.copy(isOverride = isOverride))

              // Match new ViaBuilder(...) constructor
              case Apply(TypeApply(Select(New(tpt), "<init>"), _), args)
                  if args.length >= 4 && tpt.show.contains("ViaBuilder") =>
                val stateHashes = extractSetInts(args(0))
                val eventHashes = extractSetInts(args(1))
                val stateNames  = extractListStrings(args(2))
                val eventNames  = extractListStrings(args(3))
                Some(SpecHashInfo(stateHashes, eventHashes, stateNames, eventNames, "?", false, "?"))

              // Match TransitionSpec.goto/stay/stop calls
              case Apply(Select(qual, methodName), args) if qual.show.contains("TransitionSpec") && args.length >= 4 =>
                val stateHashes = extractSetInts(args(0))
                val eventHashes = extractSetInts(args(1))
                val stateNames  = extractListStrings(args(2))
                val eventNames  = extractListStrings(args(3))
                val targetDesc  = methodName match
                  case "goto" => if args.length >= 5 then s"-> ${extractTargetName(args(4))}" else "-> ?"
                  case "stay" => "stay"
                  case "stop" => "stop"
                  case _      => "?"
                Some(SpecHashInfo(stateHashes, eventHashes, stateNames, eventNames, targetDesc, false, "?"))

              // Match new AllMatcher(...) constructor
              case Apply(TypeApply(Select(New(tpt), "<init>"), _), args)
                  if args.length >= 2 && tpt.show.contains("AllMatcher") =>
                val stateHashes = extractSetInts(args(0))
                val stateNames  = extractListStrings(args(1))
                Some(SpecHashInfo(stateHashes, Set.empty, stateNames, Nil, "?", false, "?"))

              // Match spec.copy for override detection
              case Apply(Select(inner, "copy"), args) =>
                val innerInfo = foldTree(None, inner)(owner)
                innerInfo.map { info =>
                  val hasOverride = args.exists {
                    case NamedArg("isOverride", Literal(BooleanConstant(true))) => true
                    case _                                                      => false
                  }
                  if hasOverride then info.copy(isOverride = true) else info
                }

              case _ => foldOverTree(None, tree)(owner)
          }
      end HashFinder

      def extractTargetName(t: Term): String = t match
        case Select(_, name)      => name
        case Ident(name)          => name
        case Inlined(_, _, inner) => extractTargetName(inner.asInstanceOf[Term])
        case _                    => "?"

      HashFinder.foldTree(None, term)(Symbol.spliceOwner)
    end extractHashInfo

    // For val references, try to get the definition tree
    def getDefinitionTree(term: Term): Option[Term] =
      term match
        case Ident(_) =>
          val sym = term.symbol
          try
            sym.tree match
              case ValDef(_, _, Some(rhs)) => Some(rhs)
              case _                       => None
          catch case _: Exception => None
        case Apply(Select(base, "@@"), _) =>
          getDefinitionTree(base)
        case _ => None

    // Extract info for each spec, handling both inline and val references
    def getSpecInfo(term: Term, idx: Int): SpecHashInfo =
      // First try direct extraction from the term
      val directInfo = extractHashInfo(term)

      // If that fails (e.g., for plain val reference), try to get the definition tree
      val info = directInfo.orElse {
        getDefinitionTree(term).flatMap(extractHashInfo)
      }

      // Check for override at the call site (e.g., t1 @@ Aspect.overriding)
      val hasOverrideAtCallSiteTerm = term.show.contains("overriding")

      info match
        case Some(h) =>
          val sourceDesc =
            if h.stateNames.nonEmpty && h.eventNames.nonEmpty then
              s"${h.stateNames.mkString(",")} via ${h.eventNames.mkString(",")}"
            else s"spec #${idx + 1}"
          h.copy(
            isOverride = h.isOverride || hasOverrideAtCallSiteTerm,
            sourceDesc = sourceDesc,
          )
        case None =>
          // Fallback - couldn't extract hashes, runtime detection will catch duplicates
          SpecHashInfo(Set.empty, Set.empty, Nil, Nil, "?", hasOverrideAtCallSiteTerm, s"spec #${idx + 1}")
      end match
    end getSpecInfo

    // Extract info from all specs
    val specInfos = specTerms.zipWithIndex.map { case (term, idx) =>
      (getSpecInfo(term, idx), idx)
    }

    // Build registry: (stateHash, eventHash) -> List[(SpecHashInfo, index)]
    val registry = scala.collection.mutable.Map[(Int, Int), List[(SpecHashInfo, Int)]]()

    for (info, idx) <- specInfos do
      for
        stateHash <- info.stateHashes
        eventHash <- info.eventHashes
      do
        val key = (stateHash, eventHash)
        registry(key) = registry.getOrElse(key, Nil) :+ (info, idx)

    // Check for duplicates at compile time
    val overrideInfos = scala.collection.mutable.ListBuffer[String]()

    for (_, specList) <- registry if specList.size > 1 do
      val (first, firstIdx) = specList.head

      for (info, idx) <- specList.tail do
        if !info.isOverride then
          // Compile error for duplicate without override
          report.errorAndAbort(
            s"""Duplicate transition without override!
               |  Transition: ${info.sourceDesc} ${info.targetDesc}
               |  First defined at spec #${firstIdx + 1}: ${first.targetDesc}
               |  Duplicate at spec #${idx + 1}: ${info.targetDesc}
               |
               |  To override, use: (...) @@ Aspect.overriding""".stripMargin
          )
        else
          // Track override for info message
          val prevTarget = first.targetDesc.stripPrefix("-> ")
          val newTarget  = info.targetDesc.stripPrefix("-> ")
          overrideInfos += s"  ${info.sourceDesc}: $prevTarget (spec #${firstIdx + 1}) -> $newTarget (spec #${idx + 1})"
      end for
    end for

    // Emit info about overrides if any (from hash-based detection)
    if overrideInfos.nonEmpty then
      report.info(s"[mechanoid] Override info (${overrideInfos.size} overrides):\n${overrideInfos.mkString("\n")}")

    // ============================================
    // Symbol-based duplicate detection
    // ============================================

    def isAtAtMethod(t: Term): Boolean = t match
      case Ident(name)         => name == "@@"
      case Select(_, "@@")     => true
      case TypeApply(inner, _) => isAtAtMethod(inner)
      case _                   => false

    def getBaseSymbol(term: Term): Option[Symbol] =
      term match
        // Extension method pattern: Apply(Apply(TypeApply(Ident(@@), _), [specArg]), [aspectArg])
        case Apply(Apply(inner, specArgs), _) if isAtAtMethod(inner) && specArgs.nonEmpty =>
          specArgs.head match
            case Ident(_) => Some(specArgs.head.symbol)
            case _        => getBaseSymbol(specArgs.head)
        // Single Apply pattern: Apply(Select(base, "@@"), _)
        case Apply(Select(base, "@@"), _) => getBaseSymbol(base)
        // Plain val reference
        case Ident(_) => Some(term.symbol)
        case _        => None

    def hasOverrideAtCallSite(term: Term): Boolean =
      term match
        // Extension method pattern with double Apply
        case Apply(Apply(inner, _), aspectArgs) if isAtAtMethod(inner) && aspectArgs.nonEmpty =>
          aspectArgs.head.show.contains("overriding")
        // Single Apply pattern
        case Apply(Select(_, "@@"), List(aspectArg)) =>
          aspectArg.show.contains("overriding")
        case _ => false

    // Track seen symbols: symbol -> (index, name)
    var seenSymbols         = Map.empty[Symbol, (Int, String)]
    val symbolOverrideInfos = scala.collection.mutable.ListBuffer[String]()

    // Process symbol-based duplicate detection for SPECS
    for (term, idx) <- specTerms.zipWithIndex do
      val foundSymbol = getBaseSymbol(term)
      foundSymbol.foreach { sym =>
        val hasOverride = hasOverrideAtCallSite(term)
        seenSymbols.get(sym) match
          case Some((firstIdx, _)) if !hasOverride =>
            // Duplicate without override - compile error
            report.errorAndAbort(
              s"""Duplicate transition: val '${sym.name}' used at positions ${firstIdx + 1} and ${idx + 1}.
                 |
                 |To override, use: ${sym.name} @@ Aspect.overriding""".stripMargin
            )
          case Some((firstIdx, _)) if hasOverride =>
            // Duplicate with override - emit info
            symbolOverrideInfos += s"  val '${sym.name}' at position ${idx + 1} overrides position ${firstIdx + 1}"
          case _ =>
            seenSymbols = seenSymbols + (sym -> (idx, sym.name))
        end match
      }
    end for

    // Emit symbol-based override info
    if symbolOverrideInfos.nonEmpty then
      report.info(
        s"[mechanoid] Val override (${symbolOverrideInfos.size} overrides):\n${symbolOverrideInfos.mkString("\n")}"
      )

    // ============================================
    // Command type inference
    // ============================================

    // Extract command type from all specs to compute LUB
    def extractCmdTypeFromTerm(term: Term): Option[TypeRepr] =
      val tpe = term.tpe.dealias.widen
      tpe match
        case AppliedType(_, List(_, _, cmdType)) =>
          if cmdType =:= TypeRepr.of[Nothing] then None
          else Some(cmdType)
        case _ => None

    // Collect command types from specs and machines
    val specCmdTypes    = specTerms.flatMap(extractCmdTypeFromTerm)
    val machineCmdTypes = machineTerms.flatMap(extractCmdTypeFromTerm)
    val allCmdTypes     = specCmdTypes ++ machineCmdTypes

    val inferredCmdType: TypeRepr =
      if allCmdTypes.isEmpty then TypeRepr.of[Nothing]
      else if allCmdTypes.size == 1 then allCmdTypes.head
      else
        def baseClasses(tpe: TypeRepr): List[Symbol] = tpe.baseClasses
        val baseClassLists                           = allCmdTypes.map(baseClasses)
        val commonBases                              = baseClassLists.reduce { (a, b) =>
          a.filter(sym => b.contains(sym))
        }
        val skipSymbols = Set("scala.Any", "scala.AnyRef", "scala.Matchable", "java.lang.Object")
        val usefulBases = commonBases.filterNot(sym => skipSymbols.contains(sym.fullName))

        if usefulBases.nonEmpty then usefulBases.head.typeRef
        else if commonBases.nonEmpty then TypeRepr.of[Any]
        else TypeRepr.of[Any]

    // ============================================
    // Code generation
    // ============================================

    inferredCmdType.asType match
      case '[cmd] =>
        // Use quote interpolation to generate the code properly
        val specExprs = specTerms.map(_.asExpr.asInstanceOf[Expr[TransitionSpec[S, E, cmd]]])
        val specsListExpr: Expr[List[TransitionSpec[S, E, cmd]]] = Expr.ofList(specExprs)

        // Generate the Machine creation expression
        val fromSpecsExpr: Expr[Machine[S, E, cmd]] =
          if machineTerms.isEmpty then
            // No machines - just use inline specs
            '{
              given Finite[S] = $stateEnumExpr
              given Finite[E] = $eventEnumExpr
              Machine.fromSpecs[S, E, cmd]($specsListExpr)
            }
          else
            // Has machines - combine machine specs with inline specs
            val machineExprs = machineTerms.map(_.asExpr.asInstanceOf[Expr[Machine[S, E, ?]]])
            val machineListExpr: Expr[List[Machine[S, E, ?]]] = Expr.ofList(machineExprs)
            '{
              given Finite[S]  = $stateEnumExpr
              given Finite[E]  = $eventEnumExpr
              val machineSpecs = $machineListExpr.flatMap(_.specs.asInstanceOf[List[TransitionSpec[S, E, cmd]]])
              val allSpecs     = machineSpecs ++ $specsListExpr
              Machine.fromSpecs[S, E, cmd](allSpecs)
            }

        // If there are helper vals, wrap in a block; otherwise just return the expression
        if helperVals.nonEmpty then
          val fromSpecsTerm = fromSpecsExpr.asTerm
          Block(helperVals.toList, fromSpecsTerm).asExprOf[Machine[S, E, cmd]]
        else fromSpecsExpr
    end match
  end buildAllImpl

  /** Implementation of `event[T]` - creates a type-based event matcher. */
  def eventMatcherImpl[E: Type](using Quotes): Expr[EventMatcher[E]] =
    import quotes.reflect.*
    val tpe  = TypeRepr.of[E]
    val sym  = tpe.typeSymbol
    val hash = sym.fullName.hashCode
    val name = sym.name
    '{ new EventMatcher[E](${ Expr(hash) }, ${ Expr(name) }) }

  /** Implementation of `state[T]` - creates a type-based state matcher. */
  def stateMatcherImpl[S: Type](using Quotes): Expr[StateMatcher[S]] =
    import quotes.reflect.*
    val tpe  = TypeRepr.of[S]
    val sym  = tpe.typeSymbol
    val hash = sym.fullName.hashCode
    val name = sym.name
    '{ new StateMatcher[S](${ Expr(hash) }, ${ Expr(name) }) }

end Macros

// ============================================
// Type Matchers for parameterized case classes
// ============================================

/** Matcher for a specific event type (including parameterized case classes).
  *
  * Created via `event[EventType]`. Matches ANY instance of the event type by shape, ignoring parameter values.
  *
  * Usage:
  * {{{
  * enum OrderEvent derives Finite:
  *   case PaymentSucceeded(transactionId: String)
  *
  * build[State, OrderEvent](
  *   Processing via event[PaymentSucceeded] to Done,  // Matches any PaymentSucceeded
  * )
  * }}}
  */
final class EventMatcher[E](val hash: Int, val name: String) extends IsMatcher:
  override def toString: String = s"event[$name]"

/** Matcher for a specific state type (including parameterized case classes).
  *
  * Created via `state[StateType]`. Matches ANY instance of the state type by shape, ignoring parameter values.
  *
  * Usage:
  * {{{
  * sealed trait ConnectionState derives Finite
  * case class Connecting(attempt: Int) extends ConnectionState
  *
  * build[ConnectionState, Event](
  *   state[Connecting] via Reset to Disconnected,  // Matches any Connecting(n)
  * )
  * }}}
  */
final class StateMatcher[S](val hash: Int, val name: String) extends IsMatcher:
  override def toString: String = s"state[$name]"

  /** Start building a transition from a state type matcher. */
  inline infix def via[E](inline event: E): ViaBuilder[S, E] =
    val eventHash = Macros.computeHashFor(event)
    val eventName = event.toString
    new ViaBuilder[S, E](Set(hash), Set(eventHash), List(name), List(eventName))

  /** Handle event matcher for parameterized case classes. */
  infix def via[E](eventMatcher: EventMatcher[E]): ViaBuilder[S, E] =
    new ViaBuilder[S, E](Set(hash), Set(eventMatcher.hash), List(name), List(eventMatcher.name))

  /** Handle anyOf events. */
  infix def viaAnyOf[E](events: AnyOfEventMatcher[E]): ViaBuilder[S, E] =
    new ViaBuilder[S, E](Set(hash), events.hashes, List(name), events.names)

  /** Handle all events. */
  infix def viaAll[E](events: AllMatcher[E]): ViaBuilder[S, E] =
    new ViaBuilder[S, E](Set(hash), events.hashes, List(name), events.names)
end StateMatcher

// ============================================
// Top-level DSL functions
// ============================================

/** Match ALL children of a sealed parent type.
  *
  * Usage: `all[ParentState] via Event to Target`
  */
inline def all[T]: AllMatcher[T] = ${ Macros.allImpl[T] }

/** Create a type-based event matcher for parameterized case classes.
  *
  * Matches ANY instance of the event type by shape, ignoring parameter values. Use this when your events carry data
  * (like transaction IDs, amounts, etc.) but the Machine transition doesn't care about the specific values.
  *
  * Usage:
  * {{{
  * enum OrderEvent derives Finite:
  *   case PaymentSucceeded(transactionId: String)
  *
  * build[State, OrderEvent](
  *   Processing via event[PaymentSucceeded] to Done,  // Matches any PaymentSucceeded("...")
  * )
  *
  * // At runtime, send events with actual data:
  * fsm.send(PaymentSucceeded("txn-12345"))  // Triggers the transition
  * }}}
  */
inline def event[E]: EventMatcher[E] = ${ Macros.eventMatcherImpl[E] }

/** Create a type-based state matcher for parameterized case classes.
  *
  * Matches ANY instance of the state type by shape, ignoring parameter values. Use this when your states carry data
  * (like attempt counters, session IDs, etc.) but the Machine transition doesn't care about the specific values.
  *
  * Usage:
  * {{{
  * sealed trait ConnectionState derives Finite
  * case class Connecting(attempt: Int) extends ConnectionState
  *
  * build[ConnectionState, Event](
  *   state[Connecting] via Reset to Disconnected,  // Matches any Connecting(n)
  * )
  * }}}
  */
inline def state[S]: StateMatcher[S] = ${ Macros.stateMatcherImpl[S] }

/** Match SPECIFIC state values (no .type needed!).
  *
  * Usage: `anyOf(ChildA, ChildB) via Event to Target`
  */
inline def anyOf[S](inline first: S, inline rest: S*): AnyOfMatcher[S] =
  Macros.anyOfStatesImpl(first, rest*)

/** Match SPECIFIC event values (no .type needed!).
  *
  * Usage: `State via anyOfEvents(Click, Tap) to Target`
  */
inline def anyOfEvents[E](inline first: E, inline rest: E*): AnyOfEventMatcher[E] =
  Macros.anyOfEventsImpl(first, rest*)

/** Build a Machine from transition specs and/or other machines with duplicate detection.
  *
  * Accepts both `TransitionSpec` and `Machine` arguments for composition:
  *   - TransitionSpec: inline transition definitions
  *   - Machine: nested machines whose specs are merged
  *
  * Duplicate detection:
  *   - Inline specs: COMPILE-TIME detection
  *   - Across machines: RUNTIME detection (at machine construction)
  *   - Duplicates without `@@ Aspect.overriding` cause an error
  *   - Duplicates with `@@ Aspect.overriding` are allowed; last one wins
  *
  * Usage:
  * {{{
  * // Simple usage with inline specs
  * val machine = build[State, Event](
  *   Idle via Start to Running,
  *   Running via Stop to Idle,
  * )
  *
  * // Composition with nested machines
  * val baseMachine = build[State, Event](
  *   all[State] via Reset to Idle,  // Default reset behavior
  * )
  *
  * val fullMachine = build[State, Event](
  *   baseMachine,
  *   Idle via Start to Running,
  *   (Running via Reset to stay) @@ Aspect.overriding,  // Override reset for Running
  * )
  * }}}
  */
transparent inline def build[S, E](
    inline args: (TransitionSpec[S, E, ?] | Machine[S, E, ?])*
): Machine[S, E, ?] =
  ${ Macros.buildWithInferredCmdImpl[S, E]('args) }

/** Build a Machine from transition specs in a block - no commas needed!
  *
  * Like zio-test's `suiteAll`, this allows defining transitions as expressions in a block without explicit commas or
  * parentheses.
  *
  * Features:
  *   - No parentheses, no commas between specs
  *   - Can define local vals/helpers within the block
  *   - Same compile-time duplicate detection as `build`
  *   - Same Cmd type inference from `emitting` calls
  *
  * Usage:
  * {{{
  * val machine = buildAll[State, Event] {
  *   val timeout = 30.seconds
  *
  *   Idle via Start to Running
  *
  *   Running via Stop to Idle
  *
  *   Running via Timeout to Idle
  * }
  * }}}
  */
transparent inline def buildAll[S, E](
    inline block: Any
): Machine[S, E, ?] =
  ${ Macros.buildAllImpl[S, E]('block) }

/** Include a Machine or TransitionSpec in a buildAll block.
  *
  * This is an identity function that eliminates the "pure expression does nothing" warning when including machines or
  * specs in buildAll blocks.
  *
  * Usage:
  * {{{
  * val combined = buildAll[S, E]:
  *   include(baseMachine)
  *   State via Event to Target
  * }}}
  */
inline def include[T](value: T): T = value

// ============================================
// Extension methods for infix syntax
// ============================================

/** Extension on state values for `State via Event to Target` syntax. Uses NotGiven[S <:< IsMatcher] to exclude matcher
  * types and avoid ambiguity.
  */
extension [S](inline state: S)(using NotGiven[S <:< IsMatcher])
  /** Start building a transition: `State via Event`. */
  inline infix def via[E](inline event: E): ViaBuilder[S, E] =
    Macros.stateViaEventImpl(state, event)

  /** Handle anyOf events. */
  inline infix def viaAnyOf[E](events: AnyOfEventMatcher[E]): ViaBuilder[S, E] =
    val stateHash = Macros.computeHashFor(state)
    val stateName = state.toString
    new ViaBuilder[S, E](
      Set(stateHash),
      events.hashes,
      List(stateName),
      events.names,
    )

  /** Handle all events. */
  inline infix def viaAll[E](events: AllMatcher[E]): ViaBuilder[S, E] =
    val stateHash = Macros.computeHashFor(state)
    val stateName = state.toString
    new ViaBuilder[S, E](
      Set(stateHash),
      events.hashes,
      List(stateName),
      events.names,
    )

  /** Alias for `via` using >> operator. */
  inline def >>[E](inline event: E): ViaBuilder[S, E] = via(event)

  /** Handle event matcher for parameterized case classes. */
  inline infix def via[E](matcher: EventMatcher[E]): ViaBuilder[S, E] =
    val stateHash = Macros.computeHashFor(state)
    val stateName = state.toString
    new ViaBuilder[S, E](
      Set(stateHash),
      Set(matcher.hash),
      List(stateName),
      List(matcher.name),
    )

  /** Apply timeout aspect to a state to create a TimedTarget.
    *
    * Usage:
    * {{{
    * val timedWaiting = Waiting @@ timeout(30.seconds, TimeoutEvent)
    * Idle via Start to timedWaiting  // Timer starts when entering Waiting
    * }}}
    */
  inline infix def @@[E](aspect: Aspect.timeout[E]): TimedTarget[S, E] =
    TimedTarget(state, aspect.duration, aspect.event)
end extension

/** Extension on TransitionSpec for @@ aspect application. */
extension [S, E, Cmd](spec: TransitionSpec[S, E, Cmd])
  /** Apply an aspect to this transition spec. */
  infix def @@(aspect: Aspect): TransitionSpec[S, E, Cmd] = aspect match
    case Aspect.overriding    => spec.copy(isOverride = true)
    case _: Aspect.timeout[?] => spec // timeout aspect doesn't apply to transitions
