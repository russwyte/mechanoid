package mechanoid.persistence.lock

import zio.*
import zio.test.*
import java.time.Instant
import mechanoid.stores.InMemoryFSMInstanceLock

object FSMInstanceLockSpec extends ZIOSpecDefault:

  def spec = suite("FSMInstanceLock")(
    suite("tryAcquire")(
      test("succeeds for unlocked instance") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          now    <- Clock.instant
          result <- lock.tryAcquire("fsm-1", "node-A", Duration.fromSeconds(30), now)
        yield result match
          case LockResult.Acquired(token) =>
            assertTrue(
              token.instanceId == "fsm-1",
              token.nodeId == "node-A",
              token.isValid(now),
            )
          case _ => assertTrue(false)
        end for
      },
      test("fails when locked by another node") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          now    <- Clock.instant
          _      <- lock.tryAcquire("fsm-1", "node-A", Duration.fromSeconds(30), now)
          result <- lock.tryAcquire("fsm-1", "node-B", Duration.fromSeconds(30), now)
        yield result match
          case LockResult.Busy(heldBy, _) =>
            assertTrue(heldBy == "node-A")
          case _ => assertTrue(false)
      },
      test("succeeds when same node re-acquires") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          now    <- Clock.instant
          _      <- lock.tryAcquire("fsm-1", "node-A", Duration.fromSeconds(30), now)
          result <- lock.tryAcquire("fsm-1", "node-A", Duration.fromSeconds(60), now)
        yield result match
          case LockResult.Acquired(token) =>
            assertTrue(token.nodeId == "node-A")
          case _ => assertTrue(false)
      },
      test("succeeds after lock expires") {
        val past = Instant.now().minusSeconds(60)
        val now  = Instant.now()
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          _      <- lock.tryAcquire("fsm-1", "node-A", Duration.fromSeconds(30), past)
          result <- lock.tryAcquire("fsm-1", "node-B", Duration.fromSeconds(30), now)
        yield result match
          case LockResult.Acquired(token) =>
            assertTrue(token.nodeId == "node-B")
          case _ => assertTrue(false)
      },
    ),
    suite("acquire")(
      test("acquires immediately when available") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          result <- lock.acquire("fsm-1", "node-A", Duration.fromSeconds(30), Duration.fromSeconds(5))
        yield result match
          case LockResult.Acquired(_) => assertTrue(true)
          case _                      => assertTrue(false)
      },
      test("waits and retries when busy") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]
          now  <- Clock.instant
          // Acquire with very short duration so it expires quickly
          _ <- lock.tryAcquire("fsm-1", "node-A", Duration.fromMillis(50), now)
          // Fork the acquire call which will retry and wait
          fiber <- lock
            .acquire(
              "fsm-1",
              "node-B",
              Duration.fromSeconds(30),
              Duration.fromMillis(200),
            )
            .fork
          // Advance time past the lock expiry
          _ <- TestClock.adjust(Duration.fromMillis(60))
          // Now the acquire should succeed
          result <- fiber.join
        yield result match
          case LockResult.Acquired(token) =>
            assertTrue(token.nodeId == "node-B")
          case _ => assertTrue(false)
        end for
      },
      test("times out when lock not released") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]
          now  <- Clock.instant
          // Acquire with long duration
          _ <- lock.tryAcquire("fsm-1", "node-A", Duration.fromSeconds(60), now)
          // Fork the acquire which will retry until timeout
          fiber <- lock
            .acquire(
              "fsm-1",
              "node-B",
              Duration.fromSeconds(30),
              Duration.fromMillis(100),
            )
            .fork
          // Advance time past the timeout in multiple steps to give fiber time to run
          _ <- TestClock.adjust(Duration.fromMillis(20)).repeatN(10)
          // Now the acquire should time out
          result <- fiber.join
        yield result match
          case LockResult.TimedOut() => assertTrue(true)
          case _                     => assertTrue(false)
        end for
      },
    ),
    suite("release")(
      test("releases held lock") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          now    <- Clock.instant
          result <- lock.tryAcquire("fsm-1", "node-A", Duration.fromSeconds(30), now)
          token = result match
            case LockResult.Acquired(t) => t
            case _                      => throw new Exception("Expected Acquired")
          released     <- lock.release(token)
          afterRelease <- lock.get("fsm-1", now)
        yield assertTrue(
          released,
          afterRelease.isEmpty,
        )
        end for
      },
      test("returns false for non-existent lock") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]
          fakeToken = LockToken("fsm-1", "node-A", Instant.now(), Instant.now().plusSeconds(30))
          released <- lock.release(fakeToken)
        yield assertTrue(!released)
      },
    ),
    suite("extend")(
      test("extends held lock") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          now    <- Clock.instant
          result <- lock.tryAcquire("fsm-1", "node-A", Duration.fromSeconds(30), now)
          token = result match
            case LockResult.Acquired(t) => t
            case _                      => throw new Exception("Expected Acquired")
          extended <- lock.extend(token, Duration.fromSeconds(60), now)
        yield extended match
          case Some(newToken) =>
            assertTrue(
              newToken.nodeId == "node-A",
              newToken.expiresAt.isAfter(token.expiresAt),
            )
          case None => assertTrue(false)
        end for
      },
      test("returns None when lock lost") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          now    <- Clock.instant
          result <- lock.tryAcquire("fsm-1", "node-A", Duration.fromSeconds(30), now)
          token = result match
            case LockResult.Acquired(t) => t
            case _                      => throw new Exception("Expected Acquired")
          _        <- lock.release(token)
          extended <- lock.extend(token, Duration.fromSeconds(60), now)
        yield assertTrue(extended.isEmpty)
      },
    ),
    suite("withLock")(
      test("executes effect while holding lock") {
        for
          lock   <- InMemoryFSMInstanceLock.make[String]
          result <- lock.withLock("fsm-1", "node-A", Duration.fromSeconds(30)) {
            ZIO.succeed("success")
          }
        yield assertTrue(result == "success")
      },
      test("releases lock after effect completes") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]
          _    <- lock.withLock("fsm-1", "node-A", Duration.fromSeconds(30)) {
            ZIO.unit
          }
          now       <- Clock.instant
          lockAfter <- lock.get("fsm-1", now)
        yield assertTrue(lockAfter.isEmpty)
      },
      test("releases lock on effect failure") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]
          _    <- lock
            .withLock("fsm-1", "node-A", Duration.fromSeconds(30)) {
              ZIO.fail(new RuntimeException("Test error"))
            }
            .ignore
          now       <- Clock.instant
          lockAfter <- lock.get("fsm-1", now)
        yield assertTrue(lockAfter.isEmpty)
      },
      test("fails with LockBusy when already locked") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]
          now  <- Clock.instant
          _    <- lock.tryAcquire("fsm-1", "node-A", Duration.fromSeconds(60), now)
          // Fork the withLock which will retry until timeout
          fiber <- lock
            .withLock("fsm-1", "node-B", Duration.fromSeconds(30), Some(Duration.fromMillis(50))) {
              ZIO.succeed("should not reach")
            }
            .either
            .fork
          // Advance time past the timeout
          _      <- TestClock.adjust(Duration.fromMillis(100))
          result <- fiber.join
        yield result match
          case Left(_: LockError) => assertTrue(true)
          case _                  => assertTrue(false)
        end for
      },
    ),
    suite("concurrent access")(
      test("only one node succeeds with concurrent acquire") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]
          // Fork all concurrent acquire calls
          fiber <- ZIO
            .foreachPar(1 to 10) { i =>
              lock.acquire(
                "fsm-1",
                s"node-$i",
                Duration.fromSeconds(30),
                Duration.fromMillis(100),
              )
            }
            .fork
          // Advance time past the timeout in multiple steps to give fibers time to run
          _       <- TestClock.adjust(Duration.fromMillis(20)).repeatN(10)
          results <- fiber.join
          acquiredCount = results.count {
            case LockResult.Acquired(_) => true
            case _                      => false
          }
        yield assertTrue(acquiredCount == 1)
        end for
      }
    ),
    suite("withLockAndHeartbeat")(
      test("renews lock during long operation") {
        for
          lock       <- InMemoryFSMInstanceLock.make[String]
          renewCount <- Ref.make(0)

          // Create a wrapper lock that tracks extend calls
          trackingLock = new FSMInstanceLock[String]:
            def tryAcquire(
                instanceId: String,
                nodeId: String,
                duration: Duration,
                now: Instant,
            ) = lock.tryAcquire(instanceId, nodeId, duration, now)
            def acquire(instanceId: String, nodeId: String, duration: Duration, timeout: Duration) =
              lock.acquire(instanceId, nodeId, duration, timeout)
            def release(token: LockToken[String])                                            = lock.release(token)
            def extend(token: LockToken[String], additionalDuration: Duration, now: Instant) =
              renewCount.update(_ + 1) *> lock.extend(token, additionalDuration, now)
            def get(instanceId: String, now: Instant) = lock.get(instanceId, now)

          heartbeatConfig = LockHeartbeatConfig(
            renewalInterval = Duration.fromMillis(50),
            renewalDuration = Duration.fromMillis(200),
            jitterFactor = 0.0, // No jitter for predictable testing
            onLockLost = LockLostBehavior.FailFast,
          )

          // Run an operation that takes longer than the renewal interval
          _ <- trackingLock.withLockAndHeartbeat(
            "fsm-1",
            "node-A",
            Duration.fromMillis(200),
            heartbeat = heartbeatConfig,
          ) {
            ZIO.sleep(Duration.fromMillis(200))
          }

          count <- renewCount.get
        yield assertTrue(count >= 2) // Should have renewed at least twice in 200ms with 50ms interval
      } @@ TestAspect.withLiveClock,
      test("stops renewal when operation completes") {
        for
          lock       <- InMemoryFSMInstanceLock.make[String]
          renewCount <- Ref.make(0)

          trackingLock = new FSMInstanceLock[String]:
            def tryAcquire(
                instanceId: String,
                nodeId: String,
                duration: Duration,
                now: Instant,
            ) = lock.tryAcquire(instanceId, nodeId, duration, now)
            def acquire(instanceId: String, nodeId: String, duration: Duration, timeout: Duration) =
              lock.acquire(instanceId, nodeId, duration, timeout)
            def release(token: LockToken[String])                                            = lock.release(token)
            def extend(token: LockToken[String], additionalDuration: Duration, now: Instant) =
              renewCount.update(_ + 1) *> lock.extend(token, additionalDuration, now)
            def get(instanceId: String, now: Instant) = lock.get(instanceId, now)

          heartbeatConfig = LockHeartbeatConfig(
            renewalInterval = Duration.fromMillis(20),
            renewalDuration = Duration.fromMillis(100),
            jitterFactor = 0.0,
          )

          // Complete immediately (no sleeps, so no heartbeats should fire)
          _ <- trackingLock.withLockAndHeartbeat(
            "fsm-1",
            "node-A",
            Duration.fromMillis(100),
            heartbeat = heartbeatConfig,
          ) {
            ZIO.succeed("done")
          }

          countAfterComplete <- renewCount.get

          // Try to advance the clock and verify no more renewals happen
          // (heartbeat fiber should be stopped since main effect completed)
          _              <- TestClock.adjust(Duration.fromMillis(100))
          countAfterWait <- renewCount.get
        yield assertTrue(
          countAfterWait == countAfterComplete // No additional renewals after completion
        )
      },
      test("FailFast interrupts main effect when lock lost") {
        for
          lock          <- InMemoryFSMInstanceLock.make[String]
          failExtend    <- Ref.make(false)
          effectStarted <- Promise.make[Nothing, Unit]
          extendCalled  <- Ref.make(0)

          // Wrapper lock that can be made to fail extend (also tracks calls)
          testLock = new FSMInstanceLock[String]:
            def tryAcquire(instanceId: String, nodeId: String, duration: Duration, now: Instant) =
              lock.tryAcquire(instanceId, nodeId, duration, now)
            def acquire(instanceId: String, nodeId: String, duration: Duration, timeout: Duration) =
              lock.acquire(instanceId, nodeId, duration, timeout)
            def release(token: LockToken[String])                                            = lock.release(token)
            def extend(token: LockToken[String], additionalDuration: Duration, now: Instant) =
              extendCalled.update(_ + 1) *>
                failExtend.get.flatMap { shouldFail =>
                  if shouldFail then ZIO.succeed(None)
                  else lock.extend(token, additionalDuration, now)
                }
            def get(instanceId: String, now: Instant) = lock.get(instanceId, now)

          heartbeatConfig = LockHeartbeatConfig(
            renewalInterval = Duration.fromMillis(50),
            renewalDuration = Duration.fromMillis(200),
            jitterFactor = 0.0,
            onLockLost = LockLostBehavior.FailFast,
          )

          fiber <- testLock
            .withLockAndHeartbeat(
              "fsm-1",
              "node-A",
              Duration.fromMillis(200),
              heartbeat = heartbeatConfig,
            ) {
              effectStarted.succeed(()) *>
                ZIO.sleep(Duration.fromSeconds(10))
            }
            .fork

          // Wait for effect to start
          _ <- effectStarted.await

          // Wait for at least one successful heartbeat to fire
          _ <- extendCalled.get.repeatUntil(_ >= 1).timeout(Duration.fromMillis(200))

          // Make extend fail, which triggers FailFast on next heartbeat
          _ <- failExtend.set(true)

          // Wait for the fiber to complete (should be interrupted)
          exit <- fiber.await.timeout(Duration.fromSeconds(2))
        yield assertTrue(
          exit.isDefined,        // Fiber completed (didn't timeout)
          exit.get.isInterrupted, // Fiber was interrupted
        )
      } @@ TestAspect.withLiveClock,
      test("Continue runs onLockLost effect and continues") {
        for
          lock            <- InMemoryFSMInstanceLock.make[String]
          lockLostCalled  <- Ref.make(false)
          effectCompleted <- Ref.make(false)
          failExtend      <- Ref.make(false)
          effectStarted   <- Promise.make[Nothing, Unit]

          // Wrapper lock that can be made to fail extend
          testLock = new FSMInstanceLock[String]:
            def tryAcquire(instanceId: String, nodeId: String, duration: Duration, now: Instant) =
              lock.tryAcquire(instanceId, nodeId, duration, now)
            def acquire(instanceId: String, nodeId: String, duration: Duration, timeout: Duration) =
              lock.acquire(instanceId, nodeId, duration, timeout)
            def release(token: LockToken[String])                                            = lock.release(token)
            def extend(token: LockToken[String], additionalDuration: Duration, now: Instant) =
              failExtend.get.flatMap { shouldFail =>
                if shouldFail then ZIO.succeed(None)
                else lock.extend(token, additionalDuration, now)
              }
            def get(instanceId: String, now: Instant) = lock.get(instanceId, now)

          heartbeatConfig = LockHeartbeatConfig(
            renewalInterval = Duration.fromMillis(20),
            renewalDuration = Duration.fromMillis(100),
            jitterFactor = 0.0,
            onLockLost = LockLostBehavior.Continue(
              lockLostCalled.set(true)
            ),
          )

          fiber <- testLock
            .withLockAndHeartbeat(
              "fsm-1",
              "node-A",
              Duration.fromMillis(100),
              heartbeat = heartbeatConfig,
            ) {
              effectStarted.succeed(()) *>
                ZIO.sleep(Duration.fromMillis(100)) *>
                effectCompleted.set(true).as("done")
            }
            .fork

          // Wait for effect to start
          _ <- effectStarted.await

          // Wait for first heartbeat to fire
          _ <- ZIO.sleep(Duration.fromMillis(25))

          // Make extend fail, which triggers Continue behavior
          _ <- failExtend.set(true)

          // Poll for lockLostCalled to become true
          _ <- lockLostCalled.get
            .repeatUntil(identity)
            .timeoutFail(new RuntimeException("onLockLost was not called"))(Duration.fromMillis(500))

          // Wait for operation to complete
          result <- fiber.join.either

          didComplete <- effectCompleted.get
        yield assertTrue(
          didComplete,
          result.isRight, // Operation completed successfully despite lock loss
        )
      } @@ TestAspect.withLiveClock,
      test("releases lock after effect completes") {
        for
          lock <- InMemoryFSMInstanceLock.make[String]

          heartbeatConfig = LockHeartbeatConfig(
            renewalInterval = Duration.fromMillis(50),
            renewalDuration = Duration.fromMillis(200),
            jitterFactor = 0.0,
          )

          _ <- lock
            .withLockAndHeartbeat(
              "fsm-1",
              "node-A",
              Duration.fromMillis(200),
              heartbeat = heartbeatConfig,
            ) {
              ZIO.sleep(Duration.fromMillis(100))
            }

          now       <- Clock.instant
          lockAfter <- lock.get("fsm-1", now)
        yield assertTrue(lockAfter.isEmpty)
      } @@ TestAspect.withLiveClock,
    ),
  ) @@ TestAspect.sequential @@ TestAspect.timeout(Duration.fromSeconds(30))
end FSMInstanceLockSpec
