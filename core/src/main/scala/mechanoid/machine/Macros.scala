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
    *   1. Accepts TransitionSpec, Assembly, and Machine arguments
    *   2. Extracts specs from Assembly at compile time (Level 2 validation)
    *   3. Extracts specs from nested machines at runtime
    *   4. Performs compile-time duplicate detection for inline specs and assemblies
    *   5. Extracts command types from `emitting` and `emittingBefore` calls
    *   6. Computes the LUB (least upper bound) of all command types
    *   7. Returns Machine[S, E, Cmd] with the inferred Cmd type
    *
    * Note: Duplicate detection across nested machines happens at runtime in fromSpecs.
    */
  def buildWithInferredCmdImpl[S: Type, E: Type](
      args: Expr[Seq[TransitionSpec[S, E, ?] | Assembly[S, E, ?]]]
  )(using Quotes): Expr[Machine[S, E, ?]] =
    import quotes.reflect.*

    // Check if a type is Machine[_, _, _]
    def isMachineType(tpe: TypeRepr): Boolean =
      tpe.dealias.widen match
        case AppliedType(base, _) =>
          base.typeSymbol.fullName == "mechanoid.machine.Machine"
        case _ => false

    // Check if a type is Assembly[_, _, _]
    def isAssemblyType(tpe: TypeRepr): Boolean =
      tpe.dealias.widen match
        case AppliedType(base, _) =>
          base.typeSymbol.fullName == "mechanoid.machine.Assembly"
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

    // Separate assemblies and inline specs, reject Machines with helpful error
    case class SeparatedArgs(
        assemblies: List[Expr[Assembly[S, E, ?]]],
        specs: List[Expr[TransitionSpec[S, E, ?]]],
    )

    val separated = rawExprs.foldLeft(SeparatedArgs(Nil, Nil)) { case (acc, expr) =>
      val tpe = expr.asTerm.tpe.widen
      if isAssemblyType(tpe) then acc.copy(assemblies = acc.assemblies :+ expr.asInstanceOf[Expr[Assembly[S, E, ?]]])
      else if isMachineType(tpe) then
        report.errorAndAbort(
          s"""Machine composition is not supported. Use Assembly instead.
             |
             |  BEFORE (no longer works):
             |    val base = build[S, E](...)
             |    val extended = build[S, E](base, ...)
             |
             |  AFTER (use Assembly for reusable fragments):
             |    val base = assembly[S, E](...)
             |    val extended = build[S, E](base, ...)
             |
             |Assembly provides compile-time duplicate detection that Machine composition cannot.""".stripMargin
        )
      else if isTransitionSpecType(tpe) then
        acc.copy(specs = acc.specs :+ expr.asInstanceOf[Expr[TransitionSpec[S, E, ?]]])
      else
        report.errorAndAbort(
          s"build() expects TransitionSpec or Assembly arguments, got: ${tpe.show}"
        )
      end if
    }

    val assemblyExprs = separated.assemblies
    val specExprs     = separated.specs

    // ============================================
    // Extract specs from Assemblies at compile time (Level 2 validation)
    // ============================================

    // Extract specs from Assembly constructor: Assembly.apply(List(...)) or new Assembly(List(...))
    def extractAssemblySpecTerms(term: Term): List[Term] =
      def extractListElements(listTerm: Term): List[Term] =
        listTerm match
          case Apply(TypeApply(Select(Ident("List"), "apply"), _), elems)                  => elems
          case Apply(TypeApply(Select(Select(Ident("scala"), "List"), "apply"), _), elems) => elems
          case Apply(TypeApply(Select(Ident("Nil"), ":::"), _), List(other))               => extractListElements(other)
          case Apply(Select(Ident("List"), "apply"), elems)                                => elems
          case Apply(Select(Select(Ident("scala"), "List"), "apply"), elems)               => elems
          case Select(Ident("scala"), "Nil")                                               => Nil
          case Ident("Nil")                                                                => Nil
          case Typed(inner, _)                                                             => extractListElements(inner)
          case Inlined(_, _, inner)                                                        => extractListElements(inner)
          case _                                                                           => Nil

      term match
        // Assembly.apply(List(...)) - various patterns
        case Apply(TypeApply(Select(Ident("Assembly"), "apply"), _), List(listArg)) =>
          extractListElements(listArg)
        case Apply(TypeApply(Select(Select(_, "Assembly"), "apply"), _), List(listArg)) =>
          extractListElements(listArg)
        case Apply(Select(Ident("Assembly"), "apply"), List(listArg)) =>
          extractListElements(listArg)
        case Apply(Select(Select(_, "Assembly"), "apply"), List(listArg)) =>
          extractListElements(listArg)
        // Match by type - if term shows Assembly.apply
        case Apply(fn, List(listArg)) if fn.show.contains("Assembly") && fn.show.contains("apply") =>
          extractListElements(listArg)
        // new Assembly(List(...))
        case Apply(Select(New(tpt), "<init>"), List(listArg)) if tpt.show.contains("Assembly") =>
          extractListElements(listArg)
        case Apply(TypeApply(Select(New(tpt), "<init>"), _), List(listArg)) if tpt.show.contains("Assembly") =>
          extractListElements(listArg)
        // Inlined wrapper
        case Inlined(_, _, inner) => extractAssemblySpecTerms(inner)
        // Block wrapper
        case Block(_, expr) => extractAssemblySpecTerms(expr)
        // Typed wrapper
        case Typed(inner, _) => extractAssemblySpecTerms(inner)
        // Val reference
        case Ident(_) =>
          try
            term.symbol.tree match
              case ValDef(_, _, Some(rhs)) => extractAssemblySpecTerms(rhs)
              case _                       => Nil
          catch
            case _: Exception => Nil
        // Assembly with @@ applied
        case Apply(Select(base, "@@"), _) => extractAssemblySpecTerms(base)
        case _                            => Nil
      end match
    end extractAssemblySpecTerms

    // Check if assembly has @@ Aspect.overriding applied
    def hasAssemblyOverride(term: Term): Boolean =
      term match
        case Apply(Select(_, "@@"), List(aspectArg)) => aspectArg.show.contains("overriding")
        case Inlined(_, _, inner)                    => hasAssemblyOverride(inner)
        case _                                       => false

    // Extract specs from each assembly and track override status
    case class AssemblySpecInfo(
        specTerms: List[Term],
        isOverriding: Boolean,
        sourceName: String,
    )

    val assemblySpecInfos: List[AssemblySpecInfo] = assemblyExprs.zipWithIndex.map { case (expr, idx) =>
      val term         = expr.asTerm
      val specTerms    = extractAssemblySpecTerms(term)
      val isOverriding = hasAssemblyOverride(term)
      val sourceName   = term match
        case Ident(name) => name
        case _           => s"assembly${idx + 1}"
      AssemblySpecInfo(specTerms, isOverriding, sourceName)
    }

    // Build map from spec index to assembly override status
    var assemblySpecOverrideFlags: Map[Int, (Boolean, String)] = Map.empty
    var assemblySpecIdx                                        = specExprs.size
    for info <- assemblySpecInfos do
      for _ <- info.specTerms do
        assemblySpecOverrideFlags =
          assemblySpecOverrideFlags + (assemblySpecIdx -> (info.isOverriding, info.sourceName))
        assemblySpecIdx += 1

    // Flatten all assembly spec terms
    val assemblySpecTerms: List[Term] = assemblySpecInfos.flatMap(_.specTerms)

    // Convert to expressions for unified processing
    val assemblySpecExprs: List[Expr[TransitionSpec[S, E, ?]]] =
      assemblySpecTerms.map(_.asExpr.asInstanceOf[Expr[TransitionSpec[S, E, ?]]])

    // Combine inline specs with assembly specs for duplicate detection
    val allSpecExprsForValidation = specExprs ++ assemblySpecExprs

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

      // Check if this spec came from an overriding assembly
      val (hasOverrideFromAssembly, assemblySourceName) =
        assemblySpecOverrideFlags.getOrElse(idx, (false, ""))

      val isOverrideMarked = hasOverrideAtCallSite || hasOverrideFromAssembly

      info match
        case Some(h) =>
          val sourceDesc =
            if assemblySourceName.nonEmpty then
              val transitionDesc =
                if h.stateNames.nonEmpty && h.eventNames.nonEmpty then
                  s"${h.stateNames.mkString(",")} via ${h.eventNames.mkString(",")}"
                else s"spec #${idx + 1}"
              s"$transitionDesc ($assemblySourceName)"
            else if h.stateNames.nonEmpty && h.eventNames.nonEmpty then
              s"${h.stateNames.mkString(",")} via ${h.eventNames.mkString(",")}"
            else s"spec #${idx + 1}"
          h.copy(
            isOverride = h.isOverride || isOverrideMarked,
            sourceDesc = sourceDesc,
          )
        case None =>
          // Fallback - couldn't extract hashes, runtime detection will catch duplicates
          val sourceDesc =
            if assemblySourceName.nonEmpty then s"spec #${idx + 1} ($assemblySourceName)"
            else s"spec #${idx + 1}"
          SpecHashInfo(Set.empty, Set.empty, Nil, Nil, "?", isOverrideMarked, sourceDesc)
      end match
    end getSpecInfo

    // Extract info from all specs (inline + assembly)
    val specInfos = allSpecExprsForValidation.zipWithIndex.map { case (expr, idx) =>
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

    // Extract command type from TransitionSpec[S, E, Cmd] or Assembly[S, E, Cmd] type
    def extractCmdType(expr: Expr[?]): Option[TypeRepr] =
      val tpe = expr.asTerm.tpe.widen
      tpe match
        case AppliedType(_, List(_, _, cmdType)) =>
          // Skip Nothing (the default when no commands)
          if cmdType =:= TypeRepr.of[Nothing] then None
          else Some(cmdType)
        case _ => None

    // Collect all command types from specs and assemblies
    val specCmdTypes     = specExprs.flatMap(extractCmdType)
    val assemblyCmdTypes = assemblyExprs.flatMap(extractCmdType)
    val allCmdTypes      = specCmdTypes ++ assemblyCmdTypes

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
        // Generate the Machine creation expression
        // Preserve original argument order - iterate through rawExprs and expand each in place
        val orderedSpecLists: List[Expr[List[TransitionSpec[S, E, cmd]]]] = rawExprs.map { expr =>
          val tpe = expr.asTerm.tpe.widen
          if isAssemblyType(tpe) then
            // Assembly - access .specs at runtime
            '{ ${ expr.asExprOf[Assembly[S, E, cmd]] }.specs }
          else
            // TransitionSpec - wrap in single-element list
            '{ List(${ expr.asExprOf[TransitionSpec[S, E, cmd]] }) }
        }
        val orderedSpecListsExpr: Expr[List[List[TransitionSpec[S, E, cmd]]]] =
          Expr.ofList(orderedSpecLists)

        '{
          given Finite[S] = $stateEnumExpr
          given Finite[E] = $eventEnumExpr
          val allSpecs    = $orderedSpecListsExpr.flatten
          Machine.fromSpecs[S, E, cmd](allSpecs)
        }
    end match
  end buildWithInferredCmdImpl

  /** Implementation of `buildAll` macro - collects specs and assemblies from a block.
    *
    * This macro has full parity with `build`:
    *   1. Pattern matches on Block(statements, finalExpr)
    *   2. Preserves local val definitions in the generated code
    *   3. Extracts all TransitionSpec AND Assembly expressions from the block
    *   4. Performs hash-based duplicate detection at compile time (Level 2 validation)
    *   5. Performs symbol-based duplicate detection (same val used twice)
    *   6. Emits override info messages via report.info()
    *   7. Infers command type from specs and assemblies
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
    val assemblyTypeName       = "mechanoid.machine.Assembly"

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

    // Check if a term has Assembly type
    def isAssembly(term: Term): Boolean =
      term.tpe.dealias.widen match
        case AppliedType(base, _) =>
          base.typeSymbol.fullName == assemblyTypeName
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

    // Check if a term is a simple val reference (Ident) to an Assembly or TransitionSpec
    // that is NOT wrapped in include(). Machine check kept for error detection.
    def isUnwrappedValRef(term: Term): Boolean =
      // First check if this is an include() call - if so, it's not unwrapped
      if isIncludeCall(term) then false
      else
        term match
          case Ident(_) =>
            isMachine(term) || isAssembly(term) || isTransitionSpec(term)
          // Handle inlined expressions - look inside
          case Inlined(_, _, inner) =>
            isUnwrappedValRef(inner)
          case _ => false

    // Check if a statement is a ValDef that is neither TransitionSpec nor Assembly
    // These are helper vals that should be preserved. Machine check kept for filtering.
    def isHelperValDef(stmt: Statement): Boolean = stmt match
      case ValDef(_, _, Some(rhs)) => !isTransitionSpec(rhs) && !isMachine(rhs) && !isAssembly(rhs)
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

    // Result type for processBlock
    // orderedTerms preserves the original order of specs and assemblies for code generation
    case class BlockContents(
        helperVals: List[Statement],
        specTerms: List[Term],
        assemblyTerms: List[Term],
        orderedTerms: List[Term], // All specs and assemblies in original order
    )

    // Collect TransitionSpec, Machine, Assembly expressions, and helper vals from the block
    def processBlock(t: Term): BlockContents = t match
      case Block(statements, finalExpr) =>
        // Validate each statement - val refs must use include()
        statements.foreach(validateStatement)
        // Also validate finalExpr if it's an unwrapped val ref
        finalExpr match
          case term: Term if isUnwrappedValRef(term) => validateStatement(term)
          case _                                     => ()

        // Collect helper vals (non-TransitionSpec, non-Machine, non-Assembly vals)
        val helperVals = statements.filter(isHelperValDef)

        // Collect TransitionSpec expressions
        val specTerms = statements.flatMap {
          case term: Term if isTransitionSpec(term)                => List(term)
          case ValDef(name, _, Some(rhs)) if isTransitionSpec(rhs) =>
            List(
              Ref(Symbol.requiredModule("scala.Predef"))
                .select(Symbol.requiredMethod("identity"))
                .appliedToType(rhs.tpe)
                .appliedTo(rhs)
            )
          case _ => Nil
        }

        // Check for Machine expressions and emit error
        statements.foreach {
          case term: Term if isMachine(term) =>
            report.errorAndAbort(
              s"""Machine composition is not supported. Use Assembly instead.
                 |
                 |  BEFORE (no longer works):
                 |    buildAll[S, E]:
                 |      include(someMachine)
                 |
                 |  AFTER (use Assembly for reusable fragments):
                 |    val reusable = assembly[S, E](...)
                 |    buildAll[S, E]:
                 |      include(reusable)
                 |
                 |Assembly provides compile-time duplicate detection that Machine composition cannot.""".stripMargin,
              term.pos,
            )
          case ValDef(name, _, Some(rhs)) if isMachine(rhs) =>
            report.errorAndAbort(
              s"""Machine composition is not supported. Use Assembly instead.
                 |
                 |  BEFORE (no longer works):
                 |    buildAll[S, E]:
                 |      val m = someMachine
                 |
                 |  AFTER (use Assembly for reusable fragments):
                 |    val reusable = assembly[S, E](...)
                 |    buildAll[S, E]:
                 |      include(reusable)
                 |
                 |Assembly provides compile-time duplicate detection that Machine composition cannot.""".stripMargin,
              rhs.pos,
            )
          case _ => ()
        }

        // Collect Assembly expressions
        val assemblyTerms = statements.flatMap {
          case term: Term if isAssembly(term)                => List(term)
          case ValDef(name, _, Some(rhs)) if isAssembly(rhs) =>
            List(
              Ref(Symbol.requiredModule("scala.Predef"))
                .select(Symbol.requiredMethod("identity"))
                .appliedToType(rhs.tpe)
                .appliedTo(rhs)
            )
          case _ => Nil
        }

        // Collect ordered terms (specs and assemblies in original order)
        val orderedTerms = statements.flatMap { stmt =>
          stmt match
            case term: Term if isTransitionSpec(term)                                   => List(term)
            case term: Term if isAssembly(term)                                         => List(term)
            case ValDef(name, _, Some(rhs)) if isTransitionSpec(rhs) || isAssembly(rhs) =>
              List(
                Ref(Symbol.requiredModule("scala.Predef"))
                  .select(Symbol.requiredMethod("identity"))
                  .appliedToType(rhs.tpe)
                  .appliedTo(rhs)
              )
            case _ => Nil
        }

        val finalSpecs      = if isTransitionSpec(finalExpr) then List(finalExpr) else Nil
        val finalAssemblies = if isAssembly(finalExpr) then List(finalExpr) else Nil
        val finalOrdered    = if isTransitionSpec(finalExpr) || isAssembly(finalExpr) then List(finalExpr) else Nil

        // Check for Machine in final expression
        if isMachine(finalExpr) then
          report.errorAndAbort(
            s"""Machine composition is not supported. Use Assembly instead.
               |
               |Assembly provides compile-time duplicate detection that Machine composition cannot.""".stripMargin,
            finalExpr.pos,
          )

        BlockContents(
          helperVals,
          specTerms ++ finalSpecs,
          assemblyTerms ++ finalAssemblies,
          orderedTerms ++ finalOrdered,
        )

      case Inlined(_, bindings, inner) =>
        val BlockContents(innerVals, innerSpecs, innerAssemblies, innerOrdered) = processBlock(inner)
        BlockContents(bindings.toList ++ innerVals, innerSpecs, innerAssemblies, innerOrdered)

      case other if isTransitionSpec(other) =>
        BlockContents(Nil, List(other), Nil, List(other))

      case other if isMachine(other) =>
        report.errorAndAbort(
          s"""Machine composition is not supported. Use Assembly instead.
             |
             |Assembly provides compile-time duplicate detection that Machine composition cannot.""".stripMargin,
          other.pos,
        )

      case other if isAssembly(other) =>
        BlockContents(Nil, Nil, List(other), List(other))

      case _ => BlockContents(Nil, Nil, Nil, Nil)

    val BlockContents(helperVals, specTerms, assemblyTerms, orderedTerms) = processBlock(term)

    if specTerms.isEmpty && assemblyTerms.isEmpty then
      report.errorAndAbort(
        "No TransitionSpec or Assembly expressions found in block. " +
          "Each line should be a transition (State via Event to Target) or an Assembly."
      )

    // ============================================
    // Extract specs from Assemblies at compile time (Level 2 validation)
    // ============================================

    // Extract specs from Assembly constructor: Assembly.apply(List(...)) or new Assembly(List(...))
    def extractAssemblySpecTerms(term: Term): List[Term] =
      def extractListElements(listTerm: Term): List[Term] =
        listTerm match
          case Apply(TypeApply(Select(Ident("List"), "apply"), _), elems)                  => elems
          case Apply(TypeApply(Select(Select(Ident("scala"), "List"), "apply"), _), elems) => elems
          case Apply(TypeApply(Select(Ident("Nil"), ":::"), _), List(other))               => extractListElements(other)
          case Apply(Select(Ident("List"), "apply"), elems)                                => elems
          case Apply(Select(Select(Ident("scala"), "List"), "apply"), elems)               => elems
          case Select(Ident("scala"), "Nil")                                               => Nil
          case Ident("Nil")                                                                => Nil
          case Typed(inner, _)                                                             => extractListElements(inner)
          case Inlined(_, _, inner)                                                        => extractListElements(inner)
          case _                                                                           => Nil

      term match
        // Assembly.apply(List(...)) - various patterns
        case Apply(TypeApply(Select(Ident("Assembly"), "apply"), _), List(listArg)) =>
          extractListElements(listArg)
        case Apply(TypeApply(Select(Select(_, "Assembly"), "apply"), _), List(listArg)) =>
          extractListElements(listArg)
        case Apply(Select(Ident("Assembly"), "apply"), List(listArg)) =>
          extractListElements(listArg)
        case Apply(Select(Select(_, "Assembly"), "apply"), List(listArg)) =>
          extractListElements(listArg)
        // Match by type - if term shows Assembly.apply
        case Apply(fn, List(listArg)) if fn.show.contains("Assembly") && fn.show.contains("apply") =>
          extractListElements(listArg)
        // new Assembly(List(...))
        case Apply(Select(New(tpt), "<init>"), List(listArg)) if tpt.show.contains("Assembly") =>
          extractListElements(listArg)
        case Apply(TypeApply(Select(New(tpt), "<init>"), _), List(listArg)) if tpt.show.contains("Assembly") =>
          extractListElements(listArg)
        // Inlined wrapper
        case Inlined(_, _, inner) => extractAssemblySpecTerms(inner)
        // Block wrapper
        case Block(_, expr) => extractAssemblySpecTerms(expr)
        // Typed wrapper
        case Typed(inner, _) => extractAssemblySpecTerms(inner)
        // Val reference
        case Ident(_) =>
          try
            term.symbol.tree match
              case ValDef(_, _, Some(rhs)) => extractAssemblySpecTerms(rhs)
              case _                       => Nil
          catch
            case _: Exception => Nil
        // Assembly with @@ applied
        case Apply(Select(base, "@@"), _) => extractAssemblySpecTerms(base)
        case _                            => Nil
      end match
    end extractAssemblySpecTerms

    // Check if assembly has @@ Aspect.overriding applied
    def hasAssemblyOverride(term: Term): Boolean =
      term match
        case Apply(Select(_, "@@"), List(aspectArg)) => aspectArg.show.contains("overriding")
        case Inlined(_, _, inner)                    => hasAssemblyOverride(inner)
        case _                                       => false

    // Extract specs from each assembly and track override status
    case class AssemblySpecInfo(
        specTerms: List[Term],
        isOverriding: Boolean,
        sourceName: String,
    )

    val assemblySpecInfos: List[AssemblySpecInfo] = assemblyTerms.zipWithIndex.map { case (term, idx) =>
      val extractedSpecs = extractAssemblySpecTerms(term)
      val isOverriding   = hasAssemblyOverride(term)
      val sourceName     = term match
        case Ident(name)          => name
        case Inlined(_, _, inner) =>
          inner match
            case Ident(name) => name
            case _           => s"assembly${idx + 1}"
        case _ => s"assembly${idx + 1}"
      AssemblySpecInfo(extractedSpecs, isOverriding, sourceName)
    }

    // Build map from spec index to assembly override status
    // Index starts after inline specs
    var assemblySpecOverrideFlags: Map[Int, (Boolean, String)] = Map.empty
    var assemblySpecIdx                                        = specTerms.size
    for info <- assemblySpecInfos do
      for _ <- info.specTerms do
        assemblySpecOverrideFlags =
          assemblySpecOverrideFlags + (assemblySpecIdx -> (info.isOverriding, info.sourceName))
        assemblySpecIdx += 1

    // Flatten all assembly spec terms
    val flattenedAssemblySpecTerms: List[Term] = assemblySpecInfos.flatMap(_.specTerms)

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
    // This version includes DSL string parsing for inline transitions
    def extractHashInfo(term: Term): Option[SpecHashInfo] =
      // Helper to extract state/event names from raw DSL form using the show string
      // The DSL show string looks like:
      // "mechanoid.machine.Macros$package.via[...TestState](TestState.A)(NotGiven)[...TestEvent](TestEvent.E1).to[...](TestState.B)"
      // Key insight: state/event VALUES are in parentheses AFTER type params in brackets
      def extractFromDslShowString(showStr: String): Option[SpecHashInfo] =
        // Pattern to find arguments in parentheses that look like qualified enum cases
        // e.g., "(TestState.A)" or "(mechanoid.machine.MachineSpec.TestState.A)"
        // We want the LAST segment before the closing paren that looks like TypeName.CaseName
        val argInParensPattern   = """\(([^()]+)\)""".r
        val qualifiedCasePattern = """(\w+(?:\.\w+)*)\.([A-Z][A-Za-z0-9_]*)$""".r

        // Extract all parenthesized arguments
        val allArgs = argInParensPattern.findAllMatchIn(showStr).map(_.group(1)).toList

        // Filter to only those that look like qualified enum cases (not NotGiven, not type params)
        val qualifiedArgs = allArgs.flatMap { arg =>
          qualifiedCasePattern.findFirstMatchIn(arg.trim).map { m =>
            (m.group(1), m.group(2), s"${m.group(1)}.${m.group(2)}")
          }
        }

        // The pattern is: state value, then event value, then target value
        // Filter out items that look like type names (contain "State" or "Event" as the case name itself)
        val valueArgs = qualifiedArgs.filterNot { case (_, caseName, _) =>
          caseName == "NotGiven" || caseName.endsWith("$")
        }

        if valueArgs.size < 2 then None
        else
          val (statePrefix, stateName, stateFullName) = valueArgs.head
          // Find the event - it should be from a different type (TestEvent vs TestState)
          val eventOpt = valueArgs.tail.find { case (prefix, _, _) =>
            // Event should have "Event" in its prefix or be different from state prefix
            prefix.contains("Event") || !prefix.contains(statePrefix.split('.').lastOption.getOrElse(""))
          }

          eventOpt match
            case Some((_, eventName, eventFullName)) =>
              // Find target if present (usually the last one)
              val targetOpt = valueArgs.lastOption.filter { case (prefix, _, _) =>
                prefix.contains("State") || prefix == statePrefix.split('.').init.mkString(".")
              }
              val targetDesc = targetOpt.map { case (_, name, _) => s"-> $name" }.getOrElse("?")

              val stateHash = stateFullName.hashCode
              val eventHash = eventFullName.hashCode

              Some(
                SpecHashInfo(Set(stateHash), Set(eventHash), List(stateName), List(eventName), targetDesc, false, "?")
              )
            case None => None
          end match
        end if
      end extractFromDslShowString

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

      // First try the AST-based extraction
      val astResult = HashFinder.foldTree(None, term)(Symbol.spliceOwner)

      // If AST extraction fails (e.g., for raw DSL form), try string-based extraction
      astResult.orElse {
        val showStr = term.show
        if showStr.contains(".via") then extractFromDslShowString(showStr)
        else None
      }
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

      // Check if this spec came from an overriding assembly
      val (hasOverrideFromAssembly, assemblySourceName) =
        assemblySpecOverrideFlags.getOrElse(idx, (false, ""))

      val isOverrideMarked = hasOverrideAtCallSiteTerm || hasOverrideFromAssembly

      info match
        case Some(h) =>
          val sourceDesc =
            if assemblySourceName.nonEmpty then
              val transitionDesc =
                if h.stateNames.nonEmpty && h.eventNames.nonEmpty then
                  s"${h.stateNames.mkString(",")} via ${h.eventNames.mkString(",")}"
                else s"spec #${idx + 1}"
              s"$transitionDesc ($assemblySourceName)"
            else if h.stateNames.nonEmpty && h.eventNames.nonEmpty then
              s"${h.stateNames.mkString(",")} via ${h.eventNames.mkString(",")}"
            else s"spec #${idx + 1}"
          h.copy(
            isOverride = h.isOverride || isOverrideMarked,
            sourceDesc = sourceDesc,
          )
        case None =>
          // Fallback - couldn't extract hashes, runtime detection will catch duplicates
          val sourceDesc =
            if assemblySourceName.nonEmpty then s"spec #${idx + 1} ($assemblySourceName)"
            else s"spec #${idx + 1}"
          SpecHashInfo(Set.empty, Set.empty, Nil, Nil, "?", isOverrideMarked, sourceDesc)
      end match
    end getSpecInfo

    // Note: Compile-time duplicate detection across machine composition is not possible due to
    // cyclic macro dependencies. When we try to look up a machine val's definition via sym.tree,
    // and that machine was created with build(), the compiler needs to expand that build() first,
    // creating a cycle. Runtime detection in Machine.fromSpecs handles this case.
    // However, Assembly specs ARE validated at compile time since they're extracted directly.

    // Extract info from inline specs
    val inlineSpecInfos = specTerms.zipWithIndex.map { case (term, idx) =>
      (getSpecInfo(term, idx), idx)
    }

    // Extract info from assembly specs (index continues after inline specs)
    val assemblySpecInfosForValidation = flattenedAssemblySpecTerms.zipWithIndex.map { case (term, idx) =>
      val actualIdx = specTerms.size + idx
      (getSpecInfo(term, actualIdx), actualIdx)
    }

    // Combine inline specs and assembly specs for validation
    val allSpecInfos: List[(SpecHashInfo, Int)] = inlineSpecInfos ++ assemblySpecInfosForValidation

    // Build registry: (stateHash, eventHash) -> List[(SpecHashInfo, index)]
    val registry = scala.collection.mutable.Map[(Int, Int), List[(SpecHashInfo, Int)]]()

    for (info, idx) <- allSpecInfos do
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

    // Collect command types from inline specs and assembly terms
    val specCmdTypes     = specTerms.flatMap(extractCmdTypeFromTerm)
    val assemblyCmdTypes = assemblyTerms.flatMap(extractCmdTypeFromTerm)
    val allCmdTypes      = specCmdTypes ++ assemblyCmdTypes

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
        // Generate the Machine creation expression
        // Preserve original order - iterate through orderedTerms and expand each in place
        val orderedSpecLists: List[Expr[List[TransitionSpec[S, E, cmd]]]] = orderedTerms.map { term =>
          if isAssembly(term) then
            // Assembly - access .specs at runtime
            '{ ${ term.asExpr.asInstanceOf[Expr[Assembly[S, E, cmd]]] }.specs }
          else
            // TransitionSpec - wrap in single-element list
            '{ List(${ term.asExpr.asInstanceOf[Expr[TransitionSpec[S, E, cmd]]] }) }
        }
        val orderedSpecListsExpr: Expr[List[List[TransitionSpec[S, E, cmd]]]] =
          Expr.ofList(orderedSpecLists)

        val fromSpecsExpr: Expr[Machine[S, E, cmd]] =
          '{
            given Finite[S] = $stateEnumExpr
            given Finite[E] = $eventEnumExpr
            val allSpecs    = $orderedSpecListsExpr.flatten
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

  /** Implementation of `assembly` macro - creates a compile-time composable collection of specs.
    *
    * This macro:
    *   1. Accepts TransitionSpec and Assembly arguments (flattens nested assemblies)
    *   2. Performs Level 1 duplicate detection (within this assembly)
    *   3. Infers command type from all specs
    *   4. Generates a literal `new Assembly(List(...))` expression for compile-time visibility
    *
    * Key insight: The generated expression is a literal constructor call, allowing other macros (like `build`) to
    * pattern match on the AST and extract specs without cyclic dependencies.
    */
  def assemblyImpl[S: Type, E: Type](
      args: Expr[Seq[TransitionSpec[S, E, ?] | Assembly[S, E, ?]]]
  )(using Quotes): Expr[Assembly[S, E, ?]] =
    import quotes.reflect.*

    val assemblyTypeName       = "mechanoid.machine.Assembly"
    val transitionSpecTypeName = "TransitionSpec"

    // Check if a type is Assembly
    def isAssemblyType(tpe: TypeRepr): Boolean =
      tpe.dealias.widen match
        case AppliedType(base, _) =>
          base.typeSymbol.fullName == assemblyTypeName
        case _ => false

    // Check if a type is TransitionSpec
    def isTransitionSpecType(tpe: TypeRepr): Boolean =
      tpe.dealias.widen match
        case AppliedType(base, _) =>
          base.typeSymbol.fullName.contains(transitionSpecTypeName)
        case _ => false

    // Extract individual arg expressions from varargs
    val argTerms: List[Term] = args match
      case Varargs(exprs) => exprs.toList.map(_.asTerm)
      case other          =>
        report.errorAndAbort(s"Expected varargs, got: ${other.show}")

    // Separate specs and assemblies
    val specTerms     = argTerms.filter(t => isTransitionSpecType(t.tpe))
    val assemblyTerms = argTerms.filter(t => isAssemblyType(t.tpe))

    // Extract specs from nested assemblies at compile time
    // Pattern matches on: new Assembly(List(spec1, spec2, ...))
    def extractAssemblySpecTerms(term: Term): List[Term] =
      def extractListElements(listTerm: Term): List[Term] =
        listTerm match
          // List(elem1, elem2, ...)
          case Apply(TypeApply(Select(Ident("List"), "apply"), _), elems) =>
            elems
          // List.apply(elem1, elem2, ...)
          case Apply(TypeApply(Select(Select(Ident("scala"), "List"), "apply"), _), elems) =>
            elems
          // Nil
          case Select(Ident("scala"), "Nil") => Nil
          case Ident("Nil")                  => Nil
          // Typed wrapper
          case Typed(inner, _) => extractListElements(inner)
          case _               => Nil

      term match
        // new Assembly(List(...))
        case Apply(Select(New(tpt), "<init>"), List(listArg)) if tpt.show.contains("Assembly") =>
          extractListElements(listArg)
        // new Assembly[S, E, Cmd](List(...))
        case Apply(TypeApply(Select(New(tpt), "<init>"), _), List(listArg)) if tpt.show.contains("Assembly") =>
          extractListElements(listArg)
        // Handle Inlined wrappers
        case Inlined(_, _, inner) =>
          extractAssemblySpecTerms(inner)
        // Handle Block wrappers
        case Block(_, expr) =>
          extractAssemblySpecTerms(expr)
        // Val reference - try to look up definition
        case Ident(_) =>
          try
            term.symbol.tree match
              case ValDef(_, _, Some(rhs)) => extractAssemblySpecTerms(rhs)
              case _                       => Nil
          catch
            case _: Exception => Nil
        // Assembly with @@ applied
        case Apply(Select(base, "@@"), _) =>
          extractAssemblySpecTerms(base)
        case _ => Nil
      end match
    end extractAssemblySpecTerms

    // Check if assembly has @@ Aspect.overriding applied
    def hasAssemblyOverride(term: Term): Boolean =
      term match
        case Apply(Select(_, "@@"), List(aspectArg)) =>
          aspectArg.show.contains("overriding")
        case Inlined(_, _, inner) => hasAssemblyOverride(inner)
        case _                    => false

    // Mark extracted specs as overrides if the assembly was marked
    // Note: Instead of wrapping with @@, we track override status separately during info extraction
    val assemblyOverrideStatus  = assemblyTerms.map(hasAssemblyOverride)
    val markedAssemblySpecTerms = assemblyTerms.zip(assemblyOverrideStatus).flatMap { case (assemblyTerm, _) =>
      extractAssemblySpecTerms(assemblyTerm)
    }

    // Track which specs came from overriding assemblies
    var assemblySpecOverrideFlags: Map[Int, Boolean] = Map.empty
    var specIdx                                      = specTerms.size
    for (assemblyTerm, isOverride) <- assemblyTerms.zip(assemblyOverrideStatus) do
      val specs = extractAssemblySpecTerms(assemblyTerm)
      for _ <- specs do
        assemblySpecOverrideFlags = assemblySpecOverrideFlags + (specIdx -> isOverride)
        specIdx += 1

    // Combine all spec terms for duplicate detection
    val allSpecTerms = specTerms ++ markedAssemblySpecTerms

    if allSpecTerms.isEmpty then
      // Return empty assembly
      '{ Assembly.empty[S, E] }
    else
      // ============================================
      // Hash-based duplicate detection (Level 1 - Assembly scope)
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

      // Extract hash info from a TransitionSpec term
      def extractHashInfo(term: Term): Option[SpecHashInfo] =
        object HashFinder extends TreeAccumulator[Option[SpecHashInfo]]:
          def foldTree(found: Option[SpecHashInfo], tree: Tree)(owner: Symbol): Option[SpecHashInfo] =
            found.orElse {
              tree match
                // Match @@ extension method call
                case Apply(Select(base, "@@"), List(aspectArg)) =>
                  val isOverride = aspectArg.show.contains("overriding")
                  foldTree(None, base)(owner).map(_.copy(isOverride = isOverride))

                // Match TransitionSpec constructor: TransitionSpec(stateHashes, eventHashes, stateNames, eventNames, targetDesc, ...)
                case Apply(Apply(TypeApply(Select(Ident("TransitionSpec"), "apply"), _), args), _)
                    if args.length >= 5 =>
                  val stateHashes = extractSetInts(args(0))
                  val eventHashes = extractSetInts(args(1))
                  val stateNames  = extractListStrings(args(2))
                  val eventNames  = extractListStrings(args(3))
                  val targetDesc  = args(4) match
                    case Literal(StringConstant(s)) => s
                    case _                          => "?"
                  Some(SpecHashInfo(stateHashes, eventHashes, stateNames, eventNames, targetDesc, false, "?"))

                // Match new TransitionSpec(...)
                case Apply(Select(New(_), "<init>"), args) if args.length >= 5 =>
                  val stateHashes = extractSetInts(args(0))
                  val eventHashes = extractSetInts(args(1))
                  val stateNames  = extractListStrings(args(2))
                  val eventNames  = extractListStrings(args(3))
                  val targetDesc  = args(4) match
                    case Literal(StringConstant(s)) => s
                    case _                          => "?"
                  Some(SpecHashInfo(stateHashes, eventHashes, stateNames, eventNames, targetDesc, false, "?"))

                case _ => foldOverTree(None, tree)(owner)
            }
        end HashFinder
        HashFinder.foldTree(None, term)(Symbol.spliceOwner)
      end extractHashInfo

      // Get spec info for each term
      def getSpecInfo(term: Term, idx: Int): SpecHashInfo =
        val hasOverrideAtCallSite   = term.show.contains("overriding")
        val hasOverrideFromAssembly = assemblySpecOverrideFlags.getOrElse(idx, false)
        val isOverrideMarked        = hasOverrideAtCallSite || hasOverrideFromAssembly
        extractHashInfo(term) match
          case Some(h) =>
            val sourceDesc =
              if h.stateNames.nonEmpty && h.eventNames.nonEmpty then
                s"${h.stateNames.mkString(",")} via ${h.eventNames.mkString(",")}"
              else s"spec #${idx + 1}"
            h.copy(
              isOverride = h.isOverride || isOverrideMarked,
              sourceDesc = sourceDesc,
            )
          case None =>
            SpecHashInfo(Set.empty, Set.empty, Nil, Nil, "?", isOverrideMarked, s"spec #${idx + 1}")
        end match
      end getSpecInfo

      val specInfos = allSpecTerms.zipWithIndex.map { case (term, idx) =>
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
              s"""Duplicate transition in assembly without override!
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

      // Emit info about overrides if any
      if overrideInfos.nonEmpty then
        report.info(s"[mechanoid] Override in assembly (${overrideInfos.size}):\n${overrideInfos.mkString("\n")}")

      // ============================================
      // Command type inference
      // ============================================

      def extractCmdTypeFromTerm(term: Term): Option[TypeRepr] =
        val tpe = term.tpe.dealias.widen
        tpe match
          case AppliedType(_, List(_, _, cmdType)) =>
            if cmdType =:= TypeRepr.of[Nothing] then None
            else Some(cmdType)
          case _ => None

      val allCmdTypes = allSpecTerms.flatMap(extractCmdTypeFromTerm)

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
      // Code generation - create Assembly.apply(List(...))
      // ============================================

      inferredCmdType.asType match
        case '[cmd] =>
          val specExprs = allSpecTerms.map(_.asExpr.asInstanceOf[Expr[TransitionSpec[S, E, cmd]]])
          val specsListExpr: Expr[List[TransitionSpec[S, E, cmd]]] = Expr.ofList(specExprs)

          // Use factory method for compile-time visibility
          '{ Assembly.apply[S, E, cmd]($specsListExpr) }
      end match
    end if
  end assemblyImpl

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

/** Match multiple specific state values in a single transition.
  *
  * Use this when you want a transition to apply to several specific states but not all states in a sealed hierarchy.
  * This is different from `all[Parent]` which matches all subtypes of a sealed type.
  *
  * @example
  *   {{{
  * // Handle same event from multiple specific states
  * anyOf(Draft, InReview, Approved) via Cancel to Cancelled
  *
  * // More readable than separate transitions:
  * // Draft via Cancel to Cancelled,
  * // InReview via Cancel to Cancelled,
  * // Approved via Cancel to Cancelled,
  *   }}}
  *
  * @param first
  *   The first state to match
  * @param rest
  *   Additional states to match
  * @return
  *   A matcher that expands to all specified states at compile time
  */
inline def anyOf[S](inline first: S, inline rest: S*): AnyOfMatcher[S] =
  Macros.anyOfStatesImpl(first, rest*)

/** Match multiple specific event values in a single transition.
  *
  * Use this when you want multiple events to trigger the same transition. The events are expanded at compile time.
  *
  * @example
  *   {{{
  * // Multiple events trigger same transition
  * Idle via anyOfEvents(Click, Tap, KeyPress) to Active
  *
  * // More readable than separate transitions:
  * // Idle via Click to Active,
  * // Idle via Tap to Active,
  * // Idle via KeyPress to Active,
  *   }}}
  *
  * @param first
  *   The first event to match
  * @param rest
  *   Additional events to match
  * @return
  *   A matcher that expands to all specified events at compile time
  */
inline def anyOfEvents[E](inline first: E, inline rest: E*): AnyOfEventMatcher[E] =
  Macros.anyOfEventsImpl(first, rest*)

/** Build a Machine from transition specs and/or assemblies with compile-time duplicate detection.
  *
  * Accepts `TransitionSpec` and `Assembly` arguments for composition:
  *   - TransitionSpec: inline transition definitions
  *   - Assembly: reusable transition fragments (compile-time composable)
  *
  * '''Two-Level Compile-Time Validation:'''
  *   - Level 1 (Assembly scope): Each assembly validates its own specs at compile time
  *   - Level 2 (Build scope): This macro validates across all assemblies + inline specs
  *   - Duplicates without `@@ Aspect.overriding` cause a compile error
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
  * // Composition with assemblies (compile-time validated!)
  * val baseAssembly = assembly[State, Event](
  *   all[State] via Reset to Idle,  // Default reset behavior
  * )
  *
  * val fullMachine = build[State, Event](
  *   baseAssembly,
  *   Idle via Start to Running,
  *   (Running via Reset to stay) @@ Aspect.overriding,  // Override reset for Running
  * )
  * }}}
  *
  * @note
  *   Machine composition is not supported. Use `assembly[S, E](...)` instead of `build[S, E](...)` for reusable
  *   fragments. Assemblies provide full compile-time duplicate detection that Machine composition cannot.
  */
transparent inline def build[S, E](
    inline args: (TransitionSpec[S, E, ?] | Assembly[S, E, ?])*
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

/** Create a compile-time composable collection of transition specifications.
  *
  * Assembly provides a way to define reusable transition fragments that can be composed together with full compile-time
  * validation. Unlike Machine composition, Assembly holds specs directly and can be analyzed at compile time.
  *
  * ==Two-Level Validation==
  *
  * '''Level 1 (Assembly scope)''': This macro validates specs within the assembly:
  * {{{
  * // COMPILE ERROR - duplicate without override
  * val bad = assembly[S, E](
  *   A via E1 to B,
  *   A via E1 to C,  // ERROR: Duplicate transition
  * )
  *
  * // OK - override explicitly requested
  * val ok = assembly[S, E](
  *   A via E1 to B,
  *   (A via E1 to C) @@ Aspect.overriding,
  * )
  * }}}
  *
  * '''Level 2 (Build scope)''': The `build` macro validates across all assemblies when composing.
  *
  * ==Nested Composition==
  *
  * Assemblies can include other assemblies, flattened at compile time:
  * {{{
  * val errors = assembly[S, E](all[Error] via Reset to Idle)
  * val happy = assembly[S, E](Idle via Start to Running)
  *
  * // Compose assemblies
  * val combined = assembly[S, E](errors, happy, Running via Complete to Done)
  * }}}
  *
  * @example
  *   {{{
  * import mechanoid.Mechanoid.*
  *
  * val cancelBehaviors = assembly[State, Event](
  *   all[InProgress] via Cancel to Cancelled,
  *   all[Pending] via Timeout to Failed,
  * )
  *
  * val happyPath = assembly[State, Event](
  *   Idle via Start to Running,
  *   Running via Complete to Done,
  * )
  *
  * // Compose into a runnable machine
  * val machine = build[State, Event](
  *   cancelBehaviors,
  *   happyPath,
  * )
  *   }}}
  *
  * @tparam S
  *   The state type
  * @tparam E
  *   The event type
  * @param args
  *   TransitionSpec or Assembly arguments (assemblies are flattened)
  * @return
  *   An Assembly containing all specs, validated at compile time
  *
  * @see
  *   [[mechanoid.machine.Assembly]] for the resulting type
  * @see
  *   [[mechanoid.machine.build]] for creating runnable Machines from assemblies
  */
transparent inline def assembly[S, E](
    inline args: (TransitionSpec[S, E, ?] | Assembly[S, E, ?])*
): Assembly[S, E, ?] =
  ${ Macros.assemblyImpl[S, E]('args) }

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
