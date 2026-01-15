package mechanoid.machine

/** A compile-time composable collection of transition specifications.
  *
  * Assembly provides a way to define reusable transition fragments that can be composed together with full compile-time
  * validation.
  *
  * ==Key Properties==
  *
  *   - '''Compile-time composable''': Assemblies can be combined with `include` with full duplicate detection at
  *     compile time
  *   - '''Cannot run''': Assemblies are fragments, not complete FSMs. Use `Machine(assembly)` to create a runnable
  *     Machine
  *   - '''Nested composition''': Assemblies can include other assemblies, flattened at compile time
  *
  * ==Duplicate Detection==
  *
  * The `assembly` macro validates specs and detects duplicate transitions:
  * {{{
  * // COMPILE ERROR - duplicate without override
  * val bad = assembly[S, E](
  *   A via E1 to B,
  *   A via E1 to C,  // ERROR: duplicate
  * )
  *
  * // OK - override explicitly requested on the transition
  * val ok = assembly[S, E](
  *   A via E1 to B,
  *   (A via E1 to C) @@ Aspect.overriding,
  * )
  * }}}
  *
  * ==Composition with Include==
  *
  * Assemblies can include other assemblies. Override conflicts must be resolved at the transition level:
  * {{{
  * val base = assembly[S, E](A via E1 to B)
  * val override_ = assembly[S, E](
  *   (A via E1 to C) @@ Aspect.overriding  // Override at transition level
  * )
  *
  * val combined = assembly[S, E](
  *   include(base),
  *   include(override_),
  * )
  * }}}
  *
  * @example
  *   {{{
  * import mechanoid.Mechanoid.*
  *
  * // Define reusable transition fragments
  * val errorHandling = assembly[State, Event](
  *   all[ErrorState] via Reset to Idle,
  *   all[ErrorState] via Retry to Retrying,
  * )
  *
  * val happyPath = assembly[State, Event](
  *   Idle via Start to Running,
  *   Running via Complete to Done,
  * )
  *
  * // Compose into a runnable machine
  * val machine = Machine(assembly[State, Event](
  *   include(errorHandling),
  *   include(happyPath),
  *   // Override specific behavior at transition level
  *   (Running via Cancel to Cancelled) @@ Aspect.overriding,
  * ))
  *   }}}
  *
  * @tparam S
  *   The state type for this assembly
  * @tparam E
  *   The event type for this assembly
  * @tparam Cmd
  *   The command type emitted by transitions (covariant)
  * @param specs
  *   The list of transition specifications in this assembly
  *
  * @see
  *   [[mechanoid.machine.Machine]] for runnable FSMs
  * @see
  *   [[mechanoid.machine.TransitionSpec]] for individual transition definitions
  * @see
  *   [[mechanoid.machine.Aspect.overriding]] for the override aspect
  */
final class Assembly[S, E, +Cmd] private[machine] (
    val specs: List[TransitionSpec[S, E, Cmd]],
    val hashInfos: List[IncludedHashInfo],
    val orphanOverrides: Set[OrphanInfo] = Set.empty,
):

  /** Validate the assembly for duplicate transitions.
    *
    * This performs runtime validation that complements compile-time detection. Use this when combining assemblies via
    * `include()` to catch duplicates that can't be detected at compile time.
    *
    * @throws IllegalArgumentException
    *   if duplicate transitions are found without override markers
    * @return
    *   this assembly (for chaining)
    */
  def validated: Assembly[S, E, Cmd] =
    // Track (stateHash, eventHash) -> (specIndex, description)
    var seenTransitions = Map.empty[(Int, Int), (Int, String)]

    // First pass: collect all pairs that have any override marker
    val overridePairs: Set[(Int, Int)] = specs.zipWithIndex.flatMap { case (spec, _) =>
      if spec.isOverride then
        for
          stateHash <- spec.stateHashes
          eventHash <- spec.eventHashes
        yield (stateHash, eventHash)
      else Set.empty[(Int, Int)]
    }.toSet

    // Second pass: check for duplicates
    for (spec, idx) <- specs.zipWithIndex do
      val specDesc = s"${spec.stateNames.mkString(",")} via ${spec.eventNames.mkString(",")} ${spec.targetDesc}"

      for
        stateHash <- spec.stateHashes
        eventHash <- spec.eventHashes
      do
        val key         = (stateHash, eventHash)
        val hasOverride = spec.isOverride || overridePairs.contains(key)

        seenTransitions.get(key) match
          case Some((firstIdx, firstDesc)) if !hasOverride =>
            throw new IllegalArgumentException(
              s"""Duplicate transition without override!
                 |  Transition: $specDesc
                 |  First defined at spec #${firstIdx + 1}: $firstDesc
                 |  Duplicate at spec #${idx + 1}: $specDesc
                 |
                 |  To override, use: (...) @@ Aspect.overriding""".stripMargin
            )
          case _ =>
            seenTransitions = seenTransitions + (key -> (idx, specDesc))
        end match
      end for
    end for

    this
  end validated

end Assembly

object Assembly:

  /** Create an assembly from a list of specs with compile-time hash info.
    *
    * This factory method is used by the `assembly` macro to construct Assembly instances. The hashInfos parameter
    * carries compile-time computed hash values for duplicate detection.
    *
    * @tparam S
    *   The state type
    * @tparam E
    *   The event type
    * @tparam Cmd
    *   The command type
    * @param specs
    *   The list of transition specifications
    * @param hashInfos
    *   Compile-time computed hash info for duplicate detection in include()
    * @return
    *   An Assembly containing the specs
    */
  def apply[S, E, Cmd](
      specs: List[TransitionSpec[S, E, Cmd]],
      hashInfos: List[IncludedHashInfo],
      orphanOverrides: Set[OrphanInfo] = Set.empty,
  ): Assembly[S, E, Cmd] =
    new Assembly(specs, hashInfos, orphanOverrides)

  /** Create an empty assembly with no transitions.
    *
    * Useful as a starting point for conditional assembly building or as a no-op placeholder.
    *
    * @tparam S
    *   The state type
    * @tparam E
    *   The event type
    * @return
    *   An empty Assembly with no specs
    */
  def empty[S, E]: Assembly[S, E, Nothing] = new Assembly(Nil, Nil)

end Assembly

/** Hash info for a single transition spec, used for compile-time duplicate detection.
  *
  * This is stored in `Included` by the `include` macro so that the `assembly` macro can detect duplicates across
  * included assemblies without needing to traverse val references.
  */
final case class IncludedHashInfo(
    stateHashes: Set[Int],
    eventHashes: Set[Int],
    stateNames: List[String],
    eventNames: List[String],
    targetDesc: String,
    isOverride: Boolean,
)

/** Info about an orphan override (a spec with `@@ Aspect.overriding` that doesn't override anything).
  *
  * Orphan overrides are tracked in Assembly and resolved when assemblies compose. At Machine construction, any
  * remaining orphans trigger compile-time warnings.
  */
final case class OrphanInfo(
    stateHashes: Set[Int],
    eventHashes: Set[Int],
    stateNames: List[String],
    eventNames: List[String],
):
  /** Human-readable description for warning messages. */
  def description: String = s"${stateNames.mkString(",")} via ${eventNames.mkString(",")}"

/** Wrapper for an assembly that has been explicitly included via `include()`.
  *
  * This type marker allows macros to distinguish between:
  *   - Direct `Assembly` references (which require `include()` wrapper in buildAll blocks)
  *   - Explicitly included assemblies via `include(assembly)`
  *
  * The wrapper preserves the assembly's type parameters and provides access to specs at runtime. It also carries
  * compile-time hash info for duplicate detection across includes.
  *
  * @tparam S
  *   The state type
  * @tparam E
  *   The event type
  * @tparam Cmd
  *   The command type (covariant)
  * @param assembly
  *   The wrapped assembly
  * @param hashInfos
  *   Hash info for each spec in the assembly, used for compile-time duplicate detection
  */
final class Included[S, E, +Cmd](
    val assembly: Assembly[S, E, Cmd],
    val hashInfos: List[IncludedHashInfo],
):
  /** Get the specs from the wrapped assembly. */
  def specs: List[TransitionSpec[S, E, Cmd]] = assembly.specs
