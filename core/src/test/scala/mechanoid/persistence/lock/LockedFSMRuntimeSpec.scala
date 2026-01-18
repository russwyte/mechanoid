package mechanoid.persistence.lock

import zio.*
import zio.test.*
import mechanoid.core.{ActionFailedError, Finite, MechanoidError}
import mechanoid.machine.{Machine, assembly, via}
import mechanoid.runtime.FSMRuntime
import mechanoid.runtime.timeout.TimeoutStrategy
import mechanoid.runtime.locking.LockingStrategy
import mechanoid.stores.{InMemoryEventStore, InMemoryFSMInstanceLock}
import mechanoid.persistence.EventStore

object LockedFSMRuntimeSpec extends ZIOSpecDefault:

  // Simple test state and events
  enum TestState derives Finite:
    case A, B, C, D

  enum TestEvent derives Finite:
    case E1, E2, E3

  import TestState.*
  import TestEvent.*

  // Create a simple machine for testing (no commands needed)
  val testMachine = Machine(
    assembly[TestState, TestEvent](
      A via E1 to B,
      B via E2 to C,
      C via E3 to D,
    )
  )

  // Helper to create a runtime with String ID
  def makeRuntime(id: String): ZIO[Scope, MechanoidError, FSMRuntime[String, TestState, TestEvent]] =
    for
      eventStore <- InMemoryEventStore.makeUnbounded[String, TestState, TestEvent]
      storeLayer = ZLayer.succeed[EventStore[String, TestState, TestEvent]](eventStore)
      runtime <- FSMRuntime(id, testMachine, A).provideSome[Scope](
        storeLayer ++ TimeoutStrategy.fiber[String] ++ LockingStrategy.optimistic[String]
      )
    yield runtime

  def spec = suite("LockedFSMRuntime")(
    suite("withAtomicTransitions")(
      test("holds lock across multiple sends") {
        for
          lock    <- InMemoryFSMInstanceLock.make[String]
          runtime <- makeRuntime("test-1")
          lockedRuntime = LockedFSMRuntime(runtime, lock, LockConfig(nodeId = "test-node"))

          // Execute multiple transitions atomically
          result <- lockedRuntime.withAtomicTransitions() { ctx =>
            for
              _      <- ctx.send(E1)
              state1 <- ctx.currentState
              _      <- ctx.send(E2)
              state2 <- ctx.currentState
            yield (state1.current, state2.current)
          }

          finalState <- lockedRuntime.currentState
        yield
          val (s1, s2) = result
          assertTrue(
            s1 == B,
            s2 == C,
            finalState == C,
          )
      },
      test("heartbeat renews lock during transaction") {
        for
          lock       <- InMemoryFSMInstanceLock.make[String]
          renewCount <- Ref.make(0)
          runtime    <- makeRuntime("test-2")

          // Wrap lock to track renewals
          trackingLock = new FSMInstanceLock[String]:
            def tryAcquire(instanceId: String, nodeId: String, duration: Duration, now: java.time.Instant) =
              lock.tryAcquire(instanceId, nodeId, duration, now)
            def acquire(instanceId: String, nodeId: String, duration: Duration, timeout: Duration) =
              lock.acquire(instanceId, nodeId, duration, timeout)
            def release(token: LockToken[String]) = lock.release(token)
            def extend(token: LockToken[String], additionalDuration: Duration, now: java.time.Instant) =
              renewCount.update(_ + 1) *> lock.extend(token, additionalDuration, now)
            def get(instanceId: String, now: java.time.Instant) = lock.get(instanceId, now)

          lockedRuntime = LockedFSMRuntime(runtime, trackingLock, LockConfig(nodeId = "test-node"))

          heartbeatConfig = LockHeartbeatConfig(
            renewalInterval = Duration.fromMillis(50),
            renewalDuration = Duration.fromMillis(200),
            jitterFactor = 0.0, // No jitter for more predictable timing
          )

          // Run the transaction that takes longer than the renewal interval
          _ <- lockedRuntime
            .withAtomicTransitions(heartbeatConfig) { _ =>
              ZIO.sleep(Duration.fromMillis(200))
            }

          count <- renewCount.get
        yield assertTrue(count >= 2) // Should have renewed at least twice
      } @@ TestAspect.withLiveClock,
      // Note: FailFast behavior is tested in FSMInstanceLockSpec.
      // The withAtomicTransitions method uses withLockAndHeartbeat, so the behavior is identical.
      test("releases lock after transaction completes") {
        for
          lock    <- InMemoryFSMInstanceLock.make[String]
          runtime <- makeRuntime("test-5")
          lockedRuntime = LockedFSMRuntime(runtime, lock, LockConfig(nodeId = "test-node"))

          _ <- lockedRuntime.withAtomicTransitions() { ctx =>
            ctx.send(E1)
          }

          now       <- Clock.instant
          lockAfter <- lock.get("test-5", now)
        yield assertTrue(lockAfter.isEmpty)
      },
      test("releases lock on transaction failure") {
        for
          lock    <- InMemoryFSMInstanceLock.make[String]
          runtime <- makeRuntime("test-6")
          lockedRuntime = LockedFSMRuntime(runtime, lock, LockConfig(nodeId = "test-node"))

          _ <- lockedRuntime
            .withAtomicTransitions() { ctx =>
              ctx.send(E1) *> ZIO.fail(ActionFailedError(new RuntimeException("Test error")))
            }
            .ignore

          now       <- Clock.instant
          lockAfter <- lock.get("test-6", now)
        yield assertTrue(lockAfter.isEmpty)
      },
    )
  ) @@ TestAspect.sequential @@ TestAspect.timeout(Duration.fromSeconds(30))
end LockedFSMRuntimeSpec
