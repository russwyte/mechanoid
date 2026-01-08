package mechanoid.persistence.lock

import zio.*
import zio.test.*
import java.time.Instant

object FSMInstanceLockSpec extends ZIOSpecDefault:

  def spec = suite("FSMInstanceLock")(
    suite("tryAcquire")(
      test("succeeds for unlocked instance") {
        val lock = new InMemoryFSMInstanceLock[String]()
        for
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
        val lock = new InMemoryFSMInstanceLock[String]()
        for
          now    <- Clock.instant
          _      <- lock.tryAcquire("fsm-1", "node-A", Duration.fromSeconds(30), now)
          result <- lock.tryAcquire("fsm-1", "node-B", Duration.fromSeconds(30), now)
        yield result match
          case LockResult.Busy(heldBy, _) =>
            assertTrue(heldBy == "node-A")
          case _ => assertTrue(false)
      },
      test("succeeds when same node re-acquires") {
        val lock = new InMemoryFSMInstanceLock[String]()
        for
          now    <- Clock.instant
          _      <- lock.tryAcquire("fsm-1", "node-A", Duration.fromSeconds(30), now)
          result <- lock.tryAcquire("fsm-1", "node-A", Duration.fromSeconds(60), now)
        yield result match
          case LockResult.Acquired(token) =>
            assertTrue(token.nodeId == "node-A")
          case _ => assertTrue(false)
      },
      test("succeeds after lock expires") {
        val lock = new InMemoryFSMInstanceLock[String]()
        val past = Instant.now().minusSeconds(60)
        val now  = Instant.now()
        for
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
        val lock = new InMemoryFSMInstanceLock[String]()
        for result <- lock.acquire("fsm-1", "node-A", Duration.fromSeconds(30), Duration.fromSeconds(5))
        yield result match
          case LockResult.Acquired(_) => assertTrue(true)
          case _                      => assertTrue(false)
      },
      test("waits and retries when busy") {
        val lock = new InMemoryFSMInstanceLock[String]()
        for
          now <- Clock.instant
          // Acquire with very short duration so it expires quickly
          _ <- lock.tryAcquire("fsm-1", "node-A", Duration.fromMillis(50), now)
          // This should wait for expiry then acquire
          result <- lock.acquire(
            "fsm-1",
            "node-B",
            Duration.fromSeconds(30),
            Duration.fromMillis(200),
          )
        yield result match
          case LockResult.Acquired(token) =>
            assertTrue(token.nodeId == "node-B")
          case _ => assertTrue(false)
        end for
      } @@ TestAspect.withLiveClock,
      test("times out when lock not released") {
        val lock = new InMemoryFSMInstanceLock[String]()
        for
          now <- Clock.instant
          // Acquire with long duration
          _ <- lock.tryAcquire("fsm-1", "node-A", Duration.fromSeconds(60), now)
          // Try to acquire with short timeout - should fail
          result <- lock.acquire(
            "fsm-1",
            "node-B",
            Duration.fromSeconds(30),
            Duration.fromMillis(100),
          )
        yield result match
          case LockResult.TimedOut() => assertTrue(true)
          case _                     => assertTrue(false)
        end for
      } @@ TestAspect.withLiveClock,
    ),
    suite("release")(
      test("releases held lock") {
        val lock = new InMemoryFSMInstanceLock[String]()
        for
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
        val lock      = new InMemoryFSMInstanceLock[String]()
        val fakeToken = LockToken("fsm-1", "node-A", Instant.now(), Instant.now().plusSeconds(30))
        for released <- lock.release(fakeToken)
        yield assertTrue(!released)
      },
    ),
    suite("extend")(
      test("extends held lock") {
        val lock = new InMemoryFSMInstanceLock[String]()
        for
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
        val lock = new InMemoryFSMInstanceLock[String]()
        for
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
        val lock = new InMemoryFSMInstanceLock[String]()
        for result <- lock.withLock("fsm-1", "node-A", Duration.fromSeconds(30)) {
            ZIO.succeed("success")
          }
        yield assertTrue(result == "success")
      },
      test("releases lock after effect completes") {
        val lock = new InMemoryFSMInstanceLock[String]()
        for
          _ <- lock.withLock("fsm-1", "node-A", Duration.fromSeconds(30)) {
            ZIO.unit
          }
          now       <- Clock.instant
          lockAfter <- lock.get("fsm-1", now)
        yield assertTrue(lockAfter.isEmpty)
      },
      test("releases lock on effect failure") {
        val lock = new InMemoryFSMInstanceLock[String]()
        for
          _ <- lock
            .withLock("fsm-1", "node-A", Duration.fromSeconds(30)) {
              ZIO.fail(new RuntimeException("Test error"))
            }
            .ignore
          now       <- Clock.instant
          lockAfter <- lock.get("fsm-1", now)
        yield assertTrue(lockAfter.isEmpty)
      },
      test("fails with LockBusy when already locked") {
        val lock = new InMemoryFSMInstanceLock[String]()
        for
          now    <- Clock.instant
          _      <- lock.tryAcquire("fsm-1", "node-A", Duration.fromSeconds(60), now)
          result <- lock
            .withLock("fsm-1", "node-B", Duration.fromSeconds(30), Some(Duration.fromMillis(50))) {
              ZIO.succeed("should not reach")
            }
            .either
        yield result match
          case Left(_: LockError) => assertTrue(true)
          case _                  => assertTrue(false)
        end for
      } @@ TestAspect.withLiveClock,
    ),
    suite("concurrent access")(
      test("only one node succeeds with concurrent acquire") {
        val lock = new InMemoryFSMInstanceLock[String]()
        for
          results <- ZIO.foreachPar(1 to 10) { i =>
            lock.acquire(
              "fsm-1",
              s"node-$i",
              Duration.fromSeconds(30),
              Duration.fromMillis(100),
            )
          }
          acquiredCount = results.count {
            case LockResult.Acquired(_) => true
            case _                      => false
          }
        yield assertTrue(acquiredCount == 1)
        end for
      } @@ TestAspect.withLiveClock
    ),
  ) @@ TestAspect.sequential @@ TestAspect.timeout(Duration.fromSeconds(30))
end FSMInstanceLockSpec
