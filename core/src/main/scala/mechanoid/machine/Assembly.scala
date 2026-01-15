package mechanoid.machine

/** A compile-time composable collection of transition specifications.
  *
  * Assembly provides a way to define reusable transition fragments that can be composed together with full compile-time
  * validation. Unlike Machine composition, which suffers from cyclic macro dependencies, Assembly holds specs directly
  * and can be analyzed at compile time.
  *
  * ==Key Properties==
  *
  *   - '''Compile-time composable''': Assemblies can be combined in `build` or `buildAll` with full duplicate detection
  *     at compile time
  *   - '''Cannot run''': Assemblies are fragments, not complete FSMs. Use `build` to create a runnable Machine
  *   - '''Nested composition''': Assemblies can include other assemblies, flattened at compile time
  *
  * ==Two-Level Validation==
  *
  * '''Level 1 (Assembly scope)''': The `assembly` macro validates specs within a single assembly:
  * {{{
  * // COMPILE ERROR - duplicate without override
  * val bad = assembly[S, E](
  *   A via E1 to B,
  *   A via E1 to C,  // ERROR
  * )
  *
  * // OK - override explicitly requested
  * val ok = assembly[S, E](
  *   A via E1 to B,
  *   (A via E1 to C) @@ Aspect.overriding,
  * )
  * }}}
  *
  * '''Level 2 (Build scope)''': The `build` macro validates across all assemblies:
  * {{{
  * val a1 = assembly[S, E](A via E1 to B)
  * val a2 = assembly[S, E](A via E1 to C)
  *
  * // COMPILE ERROR - conflict between assemblies
  * val bad = build[S, E](a1, a2)
  *
  * // OK - a2 overrides a1
  * val ok = build[S, E](a1, a2 @@ Aspect.overriding)
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
  * val machine = build[State, Event](
  *   errorHandling,
  *   happyPath,
  *   // Override specific behavior
  *   (Running via Cancel to Cancelled) @@ Aspect.overriding,
  * )
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
    val specs: List[TransitionSpec[S, E, Cmd]]
):

  /** Mark all specs in this assembly as overriding.
    *
    * When an assembly is marked with `@@ Aspect.overriding`, all its transitions will override any previous definitions
    * for the same (state, event) pairs. This is useful when composing assemblies where one should take precedence.
    *
    * @example
    *   {{{
    * val base = assembly[S, E](A via E1 to B)
    * val override = assembly[S, E](A via E1 to C)
    *
    * // override's transitions will replace base's
    * val machine = build[S, E](base, override @@ Aspect.overriding)
    *   }}}
    *
    * @param aspect
    *   The aspect to apply (only `Aspect.overriding` has an effect)
    * @return
    *   A new Assembly with all specs marked as overriding
    */
  infix def @@(aspect: Aspect): Assembly[S, E, Cmd] = aspect match
    case Aspect.overriding =>
      new Assembly(specs.map(_.copy(isOverride = true)))
    case _: Aspect.timeout[?] => this // timeout aspect doesn't apply to assemblies
end Assembly

object Assembly:

  /** Create an assembly from a list of specs.
    *
    * This factory method is used by the `assembly` macro to construct Assembly instances.
    *
    * @tparam S
    *   The state type
    * @tparam E
    *   The event type
    * @tparam Cmd
    *   The command type
    * @param specs
    *   The list of transition specifications
    * @return
    *   An Assembly containing the specs
    */
  def apply[S, E, Cmd](specs: List[TransitionSpec[S, E, Cmd]]): Assembly[S, E, Cmd] =
    new Assembly(specs)

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
  def empty[S, E]: Assembly[S, E, Nothing] = new Assembly(Nil)

end Assembly
