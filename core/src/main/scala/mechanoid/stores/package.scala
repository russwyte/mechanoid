package mechanoid

/** In-memory store implementations for testing and simple single-process deployments.
  *
  * These implementations are thread-safe via ZIO Refs and suitable for:
  *   - Unit testing
  *   - Integration testing
  *   - Simple demos and prototypes
  *   - Single-process deployments without persistence requirements
  *
  * For production distributed deployments, use database-backed implementations like:
  *   - `mechanoid.persistence.postgres.PostgresEventStore`
  *   - `mechanoid.persistence.postgres.PostgresTimeoutStore`
  *   - `mechanoid.persistence.postgres.PostgresInstanceLock`
  *
  * ==Usage==
  *
  * {{{
  * import mechanoid.*
  * import mechanoid.stores.*
  *
  * // Create individual stores
  * for
  *   eventStore   <- InMemoryEventStore.make[String, MyState, MyEvent]
  *   timeoutStore <- InMemoryTimeoutStore.make[String]
  * yield (eventStore, timeoutStore)
  *
  * // Or use layers
  * val layers = InMemoryEventStore.layer[String, MyState, MyEvent] ++
  *              InMemoryTimeoutStore.layer[String]
  * }}}
  */
package object stores
