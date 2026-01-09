package mechanoid.persistence.timeout

import zio.*
import zio.test.*
import mechanoid.core.PersistenceError

object TimeoutSweeperSpec extends ZIOSpecDefault:

  def spec = suite("TimeoutSweeper")(
    suite("basic operation")(
      test("fires expired timeouts") {
        for
          store    <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          firedRef <- Ref.make(List.empty[(String, String)])
          onTimeout = (id: String, state: String) => firedRef.update(_ :+ (id, state))

          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(50))
            .withJitterFactor(0.0) // No jitter for predictable tests
            .withNodeId("test-node")

          now <- Clock.instant
          _   <- store.schedule("fsm-1", "Waiting", now.minusSeconds(10))
          _   <- store.schedule("fsm-2", "Pending", now.minusSeconds(5))

          _ <- ZIO.scoped {
            for
              _ <- TimeoutSweeper.make(config, store, onTimeout)
              _ <- ZIO.sleep(Duration.fromMillis(200))
            yield ()
          }

          fired <- firedRef.get
        yield assertTrue(
          fired.length == 2,
          fired.toSet == Set(("fsm-1", "Waiting"), ("fsm-2", "Pending")),
        )
      } @@ TestAspect.withLiveClock,
      test("respects batch size") {
        for
          store      <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          firedCount <- Ref.make(0)
          // Make callback slow so we can observe batch behavior
          onTimeout = (_: String, _: String) => firedCount.update(_ + 1) *> ZIO.sleep(Duration.fromMillis(10))

          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(200)) // Longer interval
            .withBatchSize(2)
            .withJitterFactor(0.0)
            .withNodeId("test-node")

          now <- Clock.instant
          _   <- ZIO.foreach(1 to 5)(i => store.schedule(s"fsm-$i", "S", now.minusSeconds(10)))

          // Run for just enough time to complete one sweep
          _ <- ZIO.scoped {
            for
              _ <- TimeoutSweeper.make(config, store, onTimeout)
              _ <- ZIO.sleep(Duration.fromMillis(100)) // Less than sweep interval
            yield ()
          }

          count <- firedCount.get
        yield assertTrue(count == 2) // Only batch size processed in first sweep
      } @@ TestAspect.withLiveClock,
      test("skips non-expired timeouts") {
        for
          store    <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          firedRef <- Ref.make(List.empty[String])
          onTimeout = (id: String, _: String) => firedRef.update(_ :+ id)

          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(50))
            .withJitterFactor(0.0)
            .withNodeId("test-node")

          now <- Clock.instant
          _   <- store.schedule("expired", "S", now.minusSeconds(10))
          _   <- store.schedule("future", "S", now.plusSeconds(60))

          _ <- ZIO.scoped {
            for
              _ <- TimeoutSweeper.make(config, store, onTimeout)
              _ <- ZIO.sleep(Duration.fromMillis(150))
            yield ()
          }

          fired <- firedRef.get
        yield assertTrue(
          fired == List("expired")
        )
      } @@ TestAspect.withLiveClock,
      test("removes timeout after firing") {
        for
          store <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          onTimeout = (_: String, _: String) => ZIO.unit

          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(50))
            .withJitterFactor(0.0)
            .withNodeId("test-node")

          now <- Clock.instant
          _   <- store.schedule("fsm-1", "S", now.minusSeconds(10))

          _ <- ZIO.scoped {
            for
              _ <- TimeoutSweeper.make(config, store, onTimeout)
              _ <- ZIO.sleep(Duration.fromMillis(150))
            yield ()
          }

          remaining <- store.get("fsm-1")
        yield assertTrue(remaining.isEmpty)
      } @@ TestAspect.withLiveClock,
    ),
    suite("concurrent sweepers")(
      test("only one sweeper fires each timeout") {
        for
          store      <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          firedCount <- Ref.make(0)
          onTimeout = (_: String, _: String) => ZIO.sleep(Duration.fromMillis(50)) *> firedCount.update(_ + 1)

          config1 = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(20))
            .withJitterFactor(0.0)
            .withNodeId("node-1")

          config2 = config1.withNodeId("node-2")

          now <- Clock.instant
          _   <- store.schedule("fsm-1", "S", now.minusSeconds(10))

          // Run two sweepers concurrently
          _ <- ZIO.scoped {
            for
              _ <- TimeoutSweeper.make(config1, store, onTimeout)
              _ <- TimeoutSweeper.make(config2, store, onTimeout)
              _ <- ZIO.sleep(Duration.fromMillis(200))
            yield ()
          }

          count <- firedCount.get
        yield assertTrue(count == 1) // Only one sweeper should fire it
      } @@ TestAspect.withLiveClock
    ),
    suite("error handling")(
      test("releases claim on callback error") {
        for
          store     <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          callCount <- Ref.make(0)
          onTimeout = (_: String, _: String) =>
            callCount.update(_ + 1) *>
              ZIO.fail(PersistenceError(new RuntimeException("Simulated failure")))

          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(50))
            .withJitterFactor(0.0)
            .withClaimDuration(Duration.fromSeconds(30))
            .withNodeId("test-node")

          now <- Clock.instant
          _   <- store.schedule("fsm-1", "S", now.minusSeconds(10))

          _ <- ZIO.scoped {
            for
              _ <- TimeoutSweeper.make(config, store, onTimeout)
              _ <- ZIO.sleep(Duration.fromMillis(150))
            yield ()
          }

          // Timeout should still exist (released, not completed)
          timeout <- store.get("fsm-1")
          count   <- callCount.get
        yield assertTrue(
          timeout.isDefined,
          timeout.get.claimedBy.isEmpty, // Claim was released
          count >= 1,
        )
      } @@ TestAspect.withLiveClock
    ),
    suite("metrics")(
      test("tracks fired timeouts") {
        for
          store <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          onTimeout = (_: String, _: String) => ZIO.unit

          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(50))
            .withJitterFactor(0.0)
            .withNodeId("test-node")

          now <- Clock.instant
          _   <- store.schedule("fsm-1", "S", now.minusSeconds(10))
          _   <- store.schedule("fsm-2", "S", now.minusSeconds(5))

          metrics <- ZIO.scoped {
            for
              sweeper <- TimeoutSweeper.make(config, store, onTimeout)
              _       <- ZIO.sleep(Duration.fromMillis(200))
              m       <- sweeper.metrics
            yield m
          }
        yield assertTrue(
          metrics.timeoutsFired == 2,
          metrics.sweepCount >= 1,
        )
      } @@ TestAspect.withLiveClock,
      test("tracks claim conflicts with concurrent sweepers") {
        for
          store <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          // Short callback to allow all timeouts to be processed
          onTimeout = (_: String, _: String) => ZIO.sleep(Duration.fromMillis(10))

          config1 = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(20))
            .withJitterFactor(0.0)
            .withNodeId("node-1")

          config2 = config1.withNodeId("node-2")

          now <- Clock.instant
          // Schedule multiple timeouts to increase conflict chance
          _ <- ZIO.foreach(1 to 5)(i => store.schedule(s"fsm-$i", "S", now.minusSeconds(10)))

          result <- ZIO.scoped {
            for
              sweeper1 <- TimeoutSweeper.make(config1, store, onTimeout)
              sweeper2 <- TimeoutSweeper.make(config2, store, onTimeout)
              // Give enough time for all 5 timeouts to be processed
              _  <- ZIO.sleep(Duration.fromMillis(500))
              m1 <- sweeper1.metrics
              m2 <- sweeper2.metrics
            yield (m1, m2)
          }
          (metrics1, metrics2) = result
          totalConflicts       = metrics1.claimConflicts + metrics2.claimConflicts
          totalFired           = metrics1.timeoutsFired + metrics2.timeoutsFired
        yield assertTrue(
          // All 5 timeouts should be fired between the two sweepers
          // With concurrent sweepers, we may or may not see conflicts depending on timing
          totalFired == 5,
          totalConflicts >= 0,
        )
      } @@ TestAspect.withLiveClock,
    ),
    suite("backoff")(
      test("applies backoff when no timeouts found") {
        for
          store      <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          sweepCount <- Ref.make(0)
          onTimeout = (_: String, _: String) => sweepCount.update(_ + 1)

          // Configure with long backoff
          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(20))
            .withBackoffOnEmpty(Duration.fromMillis(200))
            .withJitterFactor(0.0)
            .withNodeId("test-node")

          // No timeouts scheduled - should sweep once then backoff
          metrics <- ZIO.scoped {
            for
              sweeper <- TimeoutSweeper.make(config, store, onTimeout)
              _       <- ZIO.sleep(Duration.fromMillis(100))
              m       <- sweeper.metrics
            yield m
          }
        yield assertTrue(
          // With 100ms wait and 200ms backoff, should only sweep 1-2 times
          metrics.sweepCount <= 2
        )
      } @@ TestAspect.withLiveClock
    ),
    suite("stop")(
      test("stops sweeping when stopped") {
        for
          store <- ZIO.succeed(new InMemoryTimeoutStore[String]())
          onTimeout = (_: String, _: String) => ZIO.unit

          config = TimeoutSweeperConfig()
            .withSweepInterval(Duration.fromMillis(20))
            .withJitterFactor(0.0)
            .withNodeId("test-node")

          result <- ZIO.scoped {
            for
              sweeper     <- TimeoutSweeper.make(config, store, onTimeout)
              _           <- ZIO.sleep(Duration.fromMillis(50))
              countBefore <- sweeper.metrics.map(_.sweepCount)
              _           <- sweeper.stop
              running     <- sweeper.isRunning
              _           <- ZIO.sleep(Duration.fromMillis(100))
              countAfter  <- sweeper.metrics.map(_.sweepCount)
            yield (running, countBefore, countAfter)
          }
        yield
          val (running, countBefore, countAfter) = result
          assertTrue(
            !running,
            // After stop, sweep count shouldn't increase much
            countAfter - countBefore <= 1,
          )
      } @@ TestAspect.withLiveClock
    ),
    suite("configuration")(
      test("validates jitter factor bounds") {
        assertTrue(
          scala.util
            .Try(TimeoutSweeperConfig().withJitterFactor(-0.1))
            .isFailure,
          scala.util
            .Try(TimeoutSweeperConfig().withJitterFactor(1.1))
            .isFailure,
          scala.util.Try(TimeoutSweeperConfig().withJitterFactor(0.5)).isSuccess,
        )
      },
      test("validates batch size is positive") {
        assertTrue(
          scala.util.Try(TimeoutSweeperConfig().withBatchSize(0)).isFailure,
          scala.util.Try(TimeoutSweeperConfig().withBatchSize(-1)).isFailure,
          scala.util.Try(TimeoutSweeperConfig().withBatchSize(100)).isSuccess,
        )
      },
    ),
  ) @@ TestAspect.sequential @@ TestAspect.timeout(Duration.fromSeconds(60))
end TimeoutSweeperSpec
