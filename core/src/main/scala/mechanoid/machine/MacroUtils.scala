package mechanoid.machine

import scala.quoted.*

/** Shared utilities for mechanoid macro implementations.
  *
  * This object provides common helpers for AST extraction, type checking, and validation that are used across
  * `assemblyImpl`, `buildWithInferredCmdImpl`, and `buildAllImpl` macros.
  */
private[machine] object MacroUtils:

  // ====== Type Name Constants ======

  val AssemblyTypeName: String       = "mechanoid.machine.Assembly"
  val MachineTypeName: String        = "mechanoid.machine.Machine"
  val TransitionSpecTypeName: String = "TransitionSpec"

  // ====== Type Checking Helpers ======

  /** Check if a type is Assembly[_, _, _] */
  def isAssemblyType(using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean =
    import quotes.reflect.*
    tpe.dealias.widen match
      case AppliedType(base, _) =>
        base.typeSymbol.fullName == AssemblyTypeName
      case _ => false

  /** Check if a type is Machine[_, _, _] */
  def isMachineType(using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean =
    import quotes.reflect.*
    tpe.dealias.widen match
      case AppliedType(base, _) =>
        base.typeSymbol.fullName == MachineTypeName
      case _ => false

  /** Check if a type is TransitionSpec[_, _, _] */
  def isTransitionSpecType(using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean =
    import quotes.reflect.*
    tpe.dealias.widen match
      case AppliedType(base, _) =>
        base.typeSymbol.fullName.contains(TransitionSpecTypeName)
      case _ => false

  // ====== AST Extraction Helpers ======

  /** Extract int literals from a Set expression like Set(hash1, hash2).
    *
    * Uses a TreeAccumulator to find all IntConstant literals in the term.
    */
  def extractSetInts(using Quotes)(setTerm: quotes.reflect.Term): Set[Int] =
    import quotes.reflect.*
    val ints = scala.collection.mutable.Set[Int]()
    object Collector extends TreeAccumulator[Unit]:
      def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
        tree match
          case Literal(IntConstant(v)) => ints += v
          case _                       => foldOverTree((), tree)(owner)
    Collector.foldTree((), setTerm)(Symbol.spliceOwner)
    ints.toSet
  end extractSetInts

  /** Extract string literals from a List expression like List("name1", "name2").
    *
    * Uses a TreeAccumulator to find all non-empty StringConstant literals in the term.
    */
  def extractListStrings(using Quotes)(listTerm: quotes.reflect.Term): List[String] =
    import quotes.reflect.*
    val strs = scala.collection.mutable.ListBuffer[String]()
    object Collector extends TreeAccumulator[Unit]:
      def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
        tree match
          case Literal(StringConstant(v)) if v.nonEmpty => strs += v
          case _                                        => foldOverTree((), tree)(owner)
    Collector.foldTree((), listTerm)(Symbol.spliceOwner)
    strs.toList
  end extractListStrings

  /** Extract List elements from a List constructor term.
    *
    * Handles various forms:
    *   - `List(elem1, elem2, ...)`
    *   - `scala.List.apply(...)`
    *   - `Nil`
    *   - Typed/Inlined wrappers
    *   - Scala 3 Repeated nodes for varargs
    */
  def extractListElements(using Quotes)(listTerm: quotes.reflect.Term): List[quotes.reflect.Term] =
    import quotes.reflect.*

    // Helper to unwrap elements from potential Repeated/Typed wrappers (Scala 3 varargs)
    def unwrapElements(elems: List[Term]): List[Term] =
      elems.flatMap {
        case Repeated(innerElems, _) =>
          innerElems.flatMap(e => unwrapElements(List(e)))
        case Typed(Repeated(innerElems, _), _) =>
          // Handle Typed(Repeated(...), _) - common from Expr.ofList
          innerElems.flatMap(e => unwrapElements(List(e)))
        case Typed(inner, tpt) =>
          // Check if this is a Seq/repeated type wrapper
          if tpt.show.contains("Seq[") || tpt.show.contains("<repeated>") then unwrapElements(List(inner))
          else List(inner)
        case other =>
          List(other)
      }

    val result = listTerm match
      // Handle .flatten[T] call: List(List(a), List(b)).flatten[T]
      case Apply(TypeApply(Select(innerList, "flatten"), _), _) =>
        extractListElements(innerList).flatMap(elem => extractListElements(elem))
      // Handle .flatten call without type args: List(List(a), List(b)).flatten
      case Apply(Select(innerList, "flatten"), _) =>
        extractListElements(innerList).flatMap(elem => extractListElements(elem))
      case Apply(TypeApply(Select(Ident("List"), "apply"), _), elems)                  => unwrapElements(elems)
      case Apply(TypeApply(Select(Select(Ident("scala"), "List"), "apply"), _), elems) => unwrapElements(elems)
      case Apply(TypeApply(Select(Ident("Nil"), ":::"), _), List(other))               => extractListElements(other)
      case Apply(Select(Ident("List"), "apply"), elems)                                => unwrapElements(elems)
      case Apply(Select(Select(Ident("scala"), "List"), "apply"), elems)               => unwrapElements(elems)
      case Select(Ident("scala"), "Nil")                                               => Nil
      case Ident("Nil")                                                                => Nil
      case Typed(inner, _)                                                             => extractListElements(inner)
      case Inlined(_, _, inner)                                                        => extractListElements(inner)
      case other                                                                       =>
        // Handle SeqLiteral by checking class name (no extractor in quotes API)
        if other.getClass.getName.contains("SeqLiteral") then
          // SeqLiteral is a varargs sequence - extract children via tree traversal
          val children = scala.collection.mutable.ListBuffer[Term]()
          object ChildCollector extends TreeAccumulator[Unit]:
            def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
              tree match
                case t: Term if t ne other =>
                  // Only collect direct children that look like specs
                  if t.tpe.dealias.widen.show.contains("TransitionSpec") then children += t
                  else foldOverTree((), tree)(owner)
                case _ => foldOverTree((), tree)(owner)
          ChildCollector.foldTree((), other)(Symbol.spliceOwner)
          children.toList
        else Nil
    result
  end extractListElements

  /** Extract specs from Assembly constructor: `Assembly.apply(List(...))` or `new Assembly(List(...))`.
    *
    * This is key for compile-time visibility - the assembly macro generates literal constructor calls that can be
    * pattern matched.
    */
  def extractAssemblySpecTerms(using Quotes)(term: quotes.reflect.Term): List[quotes.reflect.Term] =
    import quotes.reflect.*

    val result = term match
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
      // Inlined wrapper - the actual content might be in bindings or the call tree
      case Inlined(call, bindings, inner) =>
        // First try to extract from inner
        val fromInner = extractAssemblySpecTerms(inner)
        if fromInner.nonEmpty then fromInner
        else
          // If inner didn't work, try bindings
          bindings.flatMap {
            case ValDef(_, _, Some(rhs)) if isAssemblyType(rhs.tpe) =>
              extractAssemblySpecTerms(rhs)
            case _ => Nil
          }
      // Block wrapper
      case Block(_, expr) => extractAssemblySpecTerms(expr)
      // Typed wrapper
      case Typed(inner, _) => extractAssemblySpecTerms(inner)
      // Val reference
      case Ident(name) =>
        try
          term.symbol.tree match
            case ValDef(_, _, Some(rhs)) =>
              extractAssemblySpecTerms(rhs)
            case _ =>
              Nil
        catch
          case _: Exception =>
            Nil
      // Assembly with @@ applied
      case Apply(Select(base, "@@"), _) => extractAssemblySpecTerms(base)
      // new Included(assembly) - extract from wrapped assembly
      case Apply(Select(New(tpt), "<init>"), List(assemblyArg)) if tpt.show.contains("Included") =>
        extractAssemblySpecTerms(assemblyArg)
      case Apply(TypeApply(Select(New(tpt), "<init>"), _), List(assemblyArg)) if tpt.show.contains("Included") =>
        extractAssemblySpecTerms(assemblyArg)
      case _ =>
        Nil
    result
  end extractAssemblySpecTerms

  /** Check if assembly has `@@ Aspect.overriding` applied. */
  def hasAssemblyOverride(using Quotes)(term: quotes.reflect.Term): Boolean =
    import quotes.reflect.*
    term match
      case Apply(Select(_, "@@"), List(aspectArg)) => aspectArg.show.contains("overriding")
      case Inlined(_, _, inner)                    => hasAssemblyOverride(inner)
      case _                                       => false

  // ====== Hash Info Extraction ======

  /** Compile-time info extracted from a TransitionSpec expression for duplicate detection. */
  case class SpecHashInfo(
      stateHashes: Set[Int],
      eventHashes: Set[Int],
      stateNames: List[String],
      eventNames: List[String],
      targetDesc: String,
      isOverride: Boolean,
      sourceDesc: String,
  )

  /** Extract target name from a term (for error messages). */
  def extractTargetName(using Quotes)(t: quotes.reflect.Term): String =
    import quotes.reflect.*
    t match
      case Select(_, name)      => name
      case Ident(name)          => name
      case Inlined(_, _, inner) => extractTargetName(inner.asInstanceOf[Term])
      case _                    => "?"

  /** Extract hash info from unexpanded DSL pattern like `A via E1 to B`.
    *
    * The DSL inline macros (via, to, etc.) are NOT expanded when the outer macro sees them. So we see patterns like:
    * `via[S](A)(NotGiven.value)[E](E1).to[S](B)`
    *
    * This function walks the AST to find:
    *   - Terminal methods: `to`, `stay`, `stop`, `emitting`
    *   - The `via` call with state and event
    *   - Computes hashes from symbol fullNames
    */
  def extractHashInfoFromDSL(using Quotes)(term: quotes.reflect.Term): Option[SpecHashInfo] =
    import quotes.reflect.*

    // Compute hash from a symbol's full name
    def computeSymbolHash(sym: Symbol): Int =
      sym.fullName.hashCode

    // Extract symbol from a term that represents a state or event value
    def extractValueSymbol(t: Term): Option[Symbol] =
      t match
        case Ident(_)             => Some(t.symbol)
        case Select(_, _)         => Some(t.symbol)
        case Inlined(_, _, inner) => extractValueSymbol(inner)
        case Typed(inner, _)      => extractValueSymbol(inner)
        case _                    => None

    // Extract all state symbols from a term (handles single values and anyOf patterns)
    def extractStateSymbols(t: Term): Set[Symbol] =
      t match
        case Apply(TypeApply(Ident("anyOf"), _), args) =>
          args.flatMap(extractValueSymbol).toSet
        case Apply(Ident("anyOf"), args) =>
          args.flatMap(extractValueSymbol).toSet
        case other =>
          extractValueSymbol(other).toSet

    // Extract all event symbols from a term
    def extractEventSymbols(t: Term): Set[Symbol] =
      t match
        case Apply(TypeApply(Ident("anyOfEvents"), _), args) =>
          args.flatMap(extractValueSymbol).toSet
        case Apply(Ident("anyOfEvents"), args) =>
          args.flatMap(extractValueSymbol).toSet
        case Apply(TypeApply(Ident("event"), _), _) =>
          // event[T] matches by type - we need to extract the type parameter
          Set.empty // Will be handled by type extraction
        case other =>
          extractValueSymbol(other).toSet

    // Extract type symbol from event[T] pattern
    def extractEventTypeSymbol(t: Term): Option[Symbol] =
      t match
        case Apply(TypeApply(Ident("event"), List(tpt)), _) =>
          Some(tpt.tpe.typeSymbol)
        case Apply(TypeApply(fn, List(tpt)), _) if fn.show.contains("event") =>
          Some(tpt.tpe.typeSymbol)
        case _ => None

    // Try to find via/to/stay/stop pattern in the AST
    var stateSymbols    = Set.empty[Symbol]
    var eventSymbols    = Set.empty[Symbol]
    var targetDesc      = "?"
    var isOverride      = false
    var foundTransition = false

    object DSLFinder extends TreeAccumulator[Unit]:
      def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
        tree match
          // Match @@ for override detection
          case Apply(Select(base, "@@"), List(aspectArg)) =>
            if aspectArg.show.contains("overriding") then isOverride = true
            foldTree((), base)(owner)

          // Match extension method @@ pattern: Apply(Apply(inner, [specArg]), [aspectArg])
          case Apply(Apply(inner, specArgs), aspectArgs) if isAtAtMethod(inner) && aspectArgs.nonEmpty =>
            if aspectArgs.head.show.contains("overriding") then isOverride = true
            // Continue traversal on the spec argument, not the whole tree
            if specArgs.nonEmpty then foldTree((), specArgs.head)(owner)
            else foldOverTree((), tree)(owner)

          // Match .to(target) - DSL terminal with target
          case Apply(TypeApply(Select(qual, "to"), _), List(targetArg)) =>
            foundTransition = true
            targetDesc = extractTargetName(targetArg) match
              case "?" => "-> ?"
              case n   => s"-> $n"
            foldTree((), qual)(owner)

          case Apply(Select(qual, "to"), List(targetArg)) =>
            foundTransition = true
            targetDesc = extractTargetName(targetArg) match
              case "?" => "-> ?"
              case n   => s"-> $n"
            foldTree((), qual)(owner)

          // Match .stay - DSL terminal
          case Select(qual, "stay") =>
            foundTransition = true
            targetDesc = "stay"
            foldTree((), qual)(owner)

          // Match .stop - DSL terminal
          case Select(qual, "stop") =>
            foundTransition = true
            targetDesc = "stop"
            foldTree((), qual)(owner)

          // Match .emitting(...) - continues the chain
          case Apply(Select(qual, "emitting"), _) =>
            foldTree((), qual)(owner)

          case Apply(TypeApply(Select(qual, "emitting"), _), _) =>
            foldTree((), qual)(owner)

          // Match via[S](state)(using NotGiven)[E](event) pattern
          // This is the complex DSL chain: via[S](A)(NotGiven.value)[E](E1)
          case Apply(TypeApply(Apply(Apply(TypeApply(viaFn, _), List(stateArg)), _), _), List(eventArg))
              if viaFn.show.contains("via") =>
            val stateSyms = extractStateSymbols(stateArg)
            stateSymbols = stateSymbols ++ stateSyms

            // Check if event is event[T] pattern
            extractEventTypeSymbol(eventArg) match
              case Some(typeSym) =>
                eventSymbols = eventSymbols + typeSym
              case None =>
                eventSymbols = eventSymbols ++ extractEventSymbols(eventArg)

            foldOverTree((), tree)(owner)

          // Match simpler via patterns
          case Apply(TypeApply(Select(_, "via"), _), List(eventArg)) =>
            extractEventTypeSymbol(eventArg) match
              case Some(typeSym) =>
                eventSymbols = eventSymbols + typeSym
              case None =>
                eventSymbols = eventSymbols ++ extractEventSymbols(eventArg)
            foldOverTree((), tree)(owner)

          case Apply(Select(_, "via"), List(eventArg)) =>
            extractEventTypeSymbol(eventArg) match
              case Some(typeSym) =>
                eventSymbols = eventSymbols + typeSym
              case None =>
                eventSymbols = eventSymbols ++ extractEventSymbols(eventArg)
            foldOverTree((), tree)(owner)

          // Match Ident that represents state (e.g., in via[S](A))
          case Ident(name) if tree.symbol.exists && !tree.symbol.isPackageDef && !tree.symbol.isClassDef =>
            // This might be a state reference in via(state)
            foldOverTree((), tree)(owner)

          case Inlined(_, _, inner) =>
            foldTree((), inner)(owner)

          case _ =>
            foldOverTree((), tree)(owner)
    end DSLFinder

    DSLFinder.foldTree((), term)(Symbol.spliceOwner)

    if foundTransition && (stateSymbols.nonEmpty || eventSymbols.nonEmpty) then
      val stateHashes = stateSymbols.map(computeSymbolHash)
      val eventHashes = eventSymbols.map(computeSymbolHash)
      val stateNames  = stateSymbols.map(_.name).toList
      val eventNames  = eventSymbols.map(_.name).toList
      Some(SpecHashInfo(stateHashes, eventHashes, stateNames, eventNames, targetDesc, isOverride, "?"))
    else None
  end extractHashInfoFromDSL

  /** Extract hash info from a TransitionSpec term (either inline expr or val definition).
    *
    * Uses a TreeAccumulator to find ViaBuilder, TransitionSpec, AllMatcher constructors and extract their compile-time
    * hash values. Also handles unexpanded DSL patterns like `A via E1 to B`.
    */
  def extractHashInfo(using Quotes)(term: quotes.reflect.Term): Option[SpecHashInfo] =
    import quotes.reflect.*

    // First try to extract from DSL pattern (unexpanded inline macro)
    extractHashInfoFromDSL(term) match
      case Some(info) => return Some(info)
      case None       => ()

    object HashFinder extends TreeAccumulator[Option[SpecHashInfo]]:
      def foldTree(found: Option[SpecHashInfo], tree: Tree)(owner: Symbol): Option[SpecHashInfo] =
        found.orElse {
          tree match
            // Match TransitionSpec.goto/stay/stop calls - try this first
            case Apply(Select(_, methodName), args)
                if methodName == "goto" || methodName == "stay" || methodName == "stop" =>
              if args.length >= 4 then
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
              else foldOverTree(None, tree)(owner)

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

            // Match TransitionSpec constructor: TransitionSpec.apply(stateHashes, eventHashes, stateNames, eventNames, targetDesc, ...)
            case Apply(Apply(TypeApply(Select(Ident("TransitionSpec"), "apply"), _), args), _) if args.length >= 5 =>
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

    HashFinder.foldTree(None, term)(Symbol.spliceOwner)
  end extractHashInfo

  /** For val references, try to get the definition tree.
    *
    * This allows extracting hash info from `val t1 = A via E to B` when `t1` is referenced.
    */
  def getDefinitionTree(using Quotes)(term: quotes.reflect.Term): Option[quotes.reflect.Term] =
    import quotes.reflect.*
    term match
      case Ident(_) =>
        val sym = term.symbol
        try
          sym.tree match
            case ValDef(_, _, Some(rhs)) => Some(rhs)
            case _                       => None
        catch case _: Exception => None
      case Apply(Select(base, "@@"), _) =>
        // For t1 @@ Aspect.overriding, get the definition of t1
        getDefinitionTree(base)
      case _ => None
    end match
  end getDefinitionTree

  /** Walk the entire AST and collect ALL hash infos from ViaBuilder/TransitionSpec constructors.
    *
    * This is used by `includeImpl` to extract hash info from an assembly expression. Since inline macros have been
    * expanded, the hash values are embedded in the constructors somewhere in the tree.
    */
  def collectAllHashInfos(using Quotes)(term: quotes.reflect.Term): List[SpecHashInfo] =
    import quotes.reflect.*
    val infos = scala.collection.mutable.ListBuffer[SpecHashInfo]()

    object HashCollector extends TreeAccumulator[Unit]:
      def foldTree(u: Unit, tree: Tree)(owner: Symbol): Unit =
        tree match
          // Match new ViaBuilder(...) constructor - this has the hash values!
          case Apply(TypeApply(Select(New(tpt), "<init>"), _), args)
              if args.length >= 4 && tpt.show.contains("ViaBuilder") =>
            val stateHashes = extractSetInts(args(0))
            val eventHashes = extractSetInts(args(1))
            val stateNames  = extractListStrings(args(2))
            val eventNames  = extractListStrings(args(3))
            if stateHashes.nonEmpty && eventHashes.nonEmpty then
              infos += SpecHashInfo(stateHashes, eventHashes, stateNames, eventNames, "?", false, "?")
            foldOverTree((), tree)(owner)

          // Match TransitionSpec.goto/stay/stop calls
          case Apply(Select(_, methodName), args)
              if (methodName == "goto" || methodName == "stay" || methodName == "stop") && args.length >= 4 =>
            val stateHashes = extractSetInts(args(0))
            val eventHashes = extractSetInts(args(1))
            val stateNames  = extractListStrings(args(2))
            val eventNames  = extractListStrings(args(3))
            val targetDesc  = methodName match
              case "goto" => if args.length >= 5 then s"-> ${extractTargetName(args(4))}" else "-> ?"
              case "stay" => "stay"
              case "stop" => "stop"
              case _      => "?"
            if stateHashes.nonEmpty && eventHashes.nonEmpty then
              infos += SpecHashInfo(stateHashes, eventHashes, stateNames, eventNames, targetDesc, false, "?")
            foldOverTree((), tree)(owner)

          // Match new AllMatcher(...) - for all[T] patterns
          // AllMatcher only has state hashes; event hashes will be added by ViaBuilder
          // Don't add incomplete info here - it will be captured by ViaBuilder/TransitionSpec
          case Apply(TypeApply(Select(New(tpt), "<init>"), _), _) if tpt.show.contains("AllMatcher") =>
            foldOverTree((), tree)(owner)

          // Match @@ for override detection - update most recent info
          case Apply(Apply(inner, _), aspectArgs) if isAtAtMethod(inner) && aspectArgs.nonEmpty =>
            val isOverride = aspectArgs.head.show.contains("overriding")
            if isOverride && infos.nonEmpty then
              val last = infos.remove(infos.length - 1)
              infos += last.copy(isOverride = true)
            foldOverTree((), tree)(owner)

          case Apply(Select(_, "@@"), List(aspectArg)) =>
            val isOverride = aspectArg.show.contains("overriding")
            if isOverride && infos.nonEmpty then
              val last = infos.remove(infos.length - 1)
              infos += last.copy(isOverride = true)
            foldOverTree((), tree)(owner)

          // Follow val references to find assembly definitions
          case ident @ Ident(_) if ident.symbol.exists =>
            try
              ident.symbol.tree match
                case ValDef(_, _, Some(rhs)) =>
                  foldTree((), rhs)(owner)
                case _ =>
                  foldOverTree((), tree)(owner)
            catch
              case _: Exception =>
                foldOverTree((), tree)(owner)

          // Handle Inlined nodes - the expansion might be here
          case Inlined(_, bindings, inner) =>
            bindings.foreach(b => foldTree((), b)(owner))
            foldTree((), inner)(owner)

          case _ =>
            foldOverTree((), tree)(owner)
    end HashCollector

    HashCollector.foldTree((), term)(Symbol.spliceOwner)

    // Deduplicate based on state+event hash pairs (keep last occurrence for override handling)
    val seen = scala.collection.mutable.Map[(Set[Int], Set[Int]), SpecHashInfo]()
    for info <- infos do
      val key = (info.stateHashes, info.eventHashes)
      seen(key) = info

    seen.values.toList
  end collectAllHashInfos

  // ====== Command Type Inference ======

  /** Extract command type from a TransitionSpec[S, E, Cmd] or Assembly[S, E, Cmd] type.
    *
    * Returns None if the command type is Nothing (default when no commands).
    */
  def extractCmdType(using Quotes)(tpe: quotes.reflect.TypeRepr): Option[quotes.reflect.TypeRepr] =
    import quotes.reflect.*
    tpe.dealias.widen match
      case AppliedType(_, List(_, _, cmdType)) =>
        if cmdType =:= TypeRepr.of[Nothing] then None
        else Some(cmdType)
      case _ => None

  /** Compute the LUB (least upper bound / nearest common ancestor) of all command types.
    *
    * Used to infer the Machine's command type from all specs and assemblies.
    */
  def inferCommandType(using Quotes)(cmdTypes: List[quotes.reflect.TypeRepr]): quotes.reflect.TypeRepr =
    import quotes.reflect.*
    if cmdTypes.isEmpty then TypeRepr.of[Nothing]
    else if cmdTypes.size == 1 then cmdTypes.head
    else
      // Find the nearest common ancestor by intersecting base classes
      def baseClasses(tpe: TypeRepr): List[Symbol] = tpe.baseClasses
      val baseClassLists                           = cmdTypes.map(baseClasses)
      val commonBases                              = baseClassLists.reduce { (a, b) =>
        a.filter(sym => b.contains(sym))
      }
      val skipSymbols = Set("scala.Any", "scala.AnyRef", "scala.Matchable", "java.lang.Object")
      val usefulBases = commonBases.filterNot(sym => skipSymbols.contains(sym.fullName))

      if usefulBases.nonEmpty then usefulBases.head.typeRef
      else if commonBases.nonEmpty then TypeRepr.of[Any]
      else TypeRepr.of[Any]
    end if
  end inferCommandType

  // ====== Symbol-based Duplicate Detection ======

  /** Check if a term is the @@ method (used in extension method pattern matching). */
  def isAtAtMethod(using Quotes)(t: quotes.reflect.Term): Boolean =
    import quotes.reflect.*
    t match
      case Ident(name)         => name == "@@"
      case Select(_, "@@")     => true
      case TypeApply(inner, _) => isAtAtMethod(inner)
      case _                   => false

  /** Get the base symbol from a term (handling @@ extension method patterns). */
  def getBaseSymbol(using Quotes)(term: quotes.reflect.Term): Option[quotes.reflect.Symbol] =
    import quotes.reflect.*
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
    end match
  end getBaseSymbol

  /** Check if a term has override at the call site (e.g., `t1 @@ Aspect.overriding`). */
  def hasOverrideAtCallSite(using Quotes)(term: quotes.reflect.Term): Boolean =
    import quotes.reflect.*
    term match
      // Extension method pattern with double Apply
      case Apply(Apply(inner, _), aspectArgs) if isAtAtMethod(inner) && aspectArgs.nonEmpty =>
        aspectArgs.head.show.contains("overriding")
      // Single Apply pattern
      case Apply(Select(_, "@@"), List(aspectArg)) =>
        aspectArg.show.contains("overriding")
      case _ => false
  end hasOverrideAtCallSite

  // ====== Duplicate Detection Logic ======

  /** Result of duplicate detection - contains override info messages or triggers error. */
  case class DuplicateCheckResult(
      overrideInfos: List[String],
      symbolOverrideInfos: List[String],
  )

  /** Perform hash-based duplicate detection on a list of specs.
    *
    * @param specInfos
    *   List of (SpecHashInfo, index) pairs
    * @param errorPrefix
    *   Prefix for error messages (e.g., "Duplicate transition" or "Duplicate transition in assembly")
    * @return
    *   DuplicateCheckResult with override info, or aborts compilation on error
    */
  def checkDuplicates(using
      Quotes
  )(
      specInfos: List[(SpecHashInfo, Int)],
      errorPrefix: String,
  ): DuplicateCheckResult =
    import quotes.reflect.*

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
            s"""$errorPrefix without override!
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

    DuplicateCheckResult(overrideInfos.toList, Nil)
  end checkDuplicates

end MacroUtils
