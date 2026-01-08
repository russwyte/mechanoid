package mechanoid.persistence.command

import zio.*
import zio.test.*
import zio.test.Assertion.*
import java.time.Instant

object CommandQueueSpec extends ZIOSpecDefault:

  // Test command type
  enum TestCommand:
    case DoWork(value: String)
    case SendNotification(to: String)
    case FailingCommand(shouldRetry: Boolean)

  def spec = suite("CommandQueue")(
    suite("CommandStore")(
      test("enqueues commands with unique IDs") {
        for
          store <- ZIO.succeed(new InMemoryCommandStore[String, TestCommand]())
          cmd1 <- store.enqueue("fsm-1", TestCommand.DoWork("a"), "key-1")
          cmd2 <- store.enqueue("fsm-1", TestCommand.DoWork("b"), "key-2")
        yield assertTrue(
          cmd1.id == 1L,
          cmd2.id == 2L,
          cmd1.status == CommandStatus.Pending,
          cmd1.attempts == 0
        )
      },
      test("returns existing command for duplicate idempotency key") {
        for
          store <- ZIO.succeed(new InMemoryCommandStore[String, TestCommand]())
          cmd1 <- store.enqueue("fsm-1", TestCommand.DoWork("a"), "same-key")
          cmd2 <- store.enqueue("fsm-1", TestCommand.DoWork("b"), "same-key")
        yield assertTrue(
          cmd1.id == cmd2.id,
          cmd1.command == TestCommand.DoWork("a") // Original command preserved
        )
      },
      test("claims pending commands") {
        for
          store <- ZIO.succeed(new InMemoryCommandStore[String, TestCommand]())
          _ <- store.enqueue("fsm-1", TestCommand.DoWork("a"), "key-1")
          _ <- store.enqueue("fsm-1", TestCommand.DoWork("b"), "key-2")
          now <- Clock.instant
          claimed <- store.claim("node-1", 10, Duration.fromSeconds(30), now)
        yield assertTrue(
          claimed.length == 2,
          claimed.forall(_.status == CommandStatus.Processing),
          claimed.forall(_.attempts == 1)
        )
      },
      test("respects claim limit") {
        for
          store <- ZIO.succeed(new InMemoryCommandStore[String, TestCommand]())
          _ <- ZIO.foreach(1 to 5)(i =>
            store.enqueue("fsm-1", TestCommand.DoWork(s"$i"), s"key-$i")
          )
          now <- Clock.instant
          claimed <- store.claim("node-1", 2, Duration.fromSeconds(30), now)
        yield assertTrue(claimed.length == 2)
      },
      test("does not claim already claimed commands") {
        for
          store <- ZIO.succeed(new InMemoryCommandStore[String, TestCommand]())
          _ <- store.enqueue("fsm-1", TestCommand.DoWork("a"), "key-1")
          now <- Clock.instant
          claimed1 <- store.claim("node-1", 10, Duration.fromSeconds(30), now)
          claimed2 <- store.claim("node-2", 10, Duration.fromSeconds(30), now)
        yield assertTrue(
          claimed1.length == 1,
          claimed2.isEmpty
        )
      },
      test("completes commands") {
        for
          store <- ZIO.succeed(new InMemoryCommandStore[String, TestCommand]())
          cmd <- store.enqueue("fsm-1", TestCommand.DoWork("a"), "key-1")
          now <- Clock.instant
          _ <- store.claim("node-1", 10, Duration.fromSeconds(30), now)
          success <- store.complete(cmd.id)
          updatedOpt <- store.findById(cmd.id)
        yield
          val updated = updatedOpt.get
          assertTrue(
            success,
            updated.status == CommandStatus.Completed
          )
      },
      test("fails commands with retry") {
        for
          store <- ZIO.succeed(new InMemoryCommandStore[String, TestCommand]())
          cmd <- store.enqueue("fsm-1", TestCommand.DoWork("a"), "key-1")
          now <- Clock.instant
          _ <- store.claim("node-1", 10, Duration.fromSeconds(30), now)
          retryAt = now.plusSeconds(60)
          success <- store.fail(cmd.id, "Network error", Some(retryAt))
          updatedOpt <- store.findById(cmd.id)
        yield
          val updated = updatedOpt.get
          assertTrue(
            success,
            updated.status == CommandStatus.Pending, // Back to pending for retry
            updated.lastError.contains("Network error"),
            updated.nextRetryAt.contains(retryAt)
          )
      },
      test("fails commands permanently") {
        for
          store <- ZIO.succeed(new InMemoryCommandStore[String, TestCommand]())
          cmd <- store.enqueue("fsm-1", TestCommand.DoWork("a"), "key-1")
          now <- Clock.instant
          _ <- store.claim("node-1", 10, Duration.fromSeconds(30), now)
          success <- store.fail(cmd.id, "Permanent error", None)
          updatedOpt <- store.findById(cmd.id)
        yield
          val updated = updatedOpt.get
          assertTrue(
            success,
            updated.status == CommandStatus.Failed
          )
      },
      test("skips commands") {
        for
          store <- ZIO.succeed(new InMemoryCommandStore[String, TestCommand]())
          cmd <- store.enqueue("fsm-1", TestCommand.DoWork("a"), "key-1")
          now <- Clock.instant
          _ <- store.claim("node-1", 10, Duration.fromSeconds(30), now)
          success <- store.skip(cmd.id, "Duplicate")
          updatedOpt <- store.findById(cmd.id)
        yield
          val updated = updatedOpt.get
          assertTrue(
            success,
            updated.status == CommandStatus.Skipped
          )
      },
      test("releases expired claims") {
        for
          store <- ZIO.succeed(new InMemoryCommandStore[String, TestCommand]())
          _ <- store.enqueue("fsm-1", TestCommand.DoWork("a"), "key-1")
          now <- Clock.instant
          _ <- store.claim("node-1", 10, Duration.fromMillis(100), now)
          later = now.plusMillis(200)
          released <- store.releaseExpiredClaims(later)
          cmdOpt <- store.findById(1L)
        yield
          val cmd = cmdOpt.get
          assertTrue(
            released == 1,
            cmd.status == CommandStatus.Pending // Released back to pending
          )
      },
      test("queries by instance ID") {
        for
          store <- ZIO.succeed(new InMemoryCommandStore[String, TestCommand]())
          _ <- store.enqueue("fsm-1", TestCommand.DoWork("a"), "key-1")
          _ <- store.enqueue("fsm-1", TestCommand.DoWork("b"), "key-2")
          _ <- store.enqueue("fsm-2", TestCommand.DoWork("c"), "key-3")
          cmds <- store.getByInstanceId("fsm-1")
        yield assertTrue(cmds.length == 2)
      },
      test("counts by status") {
        for
          store <- ZIO.succeed(new InMemoryCommandStore[String, TestCommand]())
          cmd1 <- store.enqueue("fsm-1", TestCommand.DoWork("a"), "key-1")
          _ <- store.enqueue("fsm-1", TestCommand.DoWork("b"), "key-2")
          now <- Clock.instant
          _ <- store.claim("node-1", 1, Duration.fromSeconds(30), now)
          _ <- store.complete(cmd1.id)
          counts <- store.countByStatus
        yield assertTrue(
          counts.getOrElse(CommandStatus.Completed, 0L) == 1L,
          counts.getOrElse(CommandStatus.Pending, 0L) == 1L
        )
      }
    ),
    suite("CommandWorker")(
      test("processes commands successfully") {
        for
          store <- ZIO.succeed(new InMemoryCommandStore[String, TestCommand]())
          processedRef <- Ref.make(List.empty[String])
          executor = (cmd: TestCommand) =>
            cmd match
              case TestCommand.DoWork(value) =>
                processedRef.update(_ :+ value).as(CommandResult.Success)
              case _ => ZIO.succeed(CommandResult.Success)

          config = CommandWorkerConfig()
            .withPollInterval(Duration.fromMillis(50))
            .withBatchSize(10)

          _ <- store.enqueue("fsm-1", TestCommand.DoWork("task-1"), "key-1")
          _ <- store.enqueue("fsm-1", TestCommand.DoWork("task-2"), "key-2")

          _ <- ZIO.scoped {
            for
              _ <- CommandWorker.make(config, store, executor)
              _ <- ZIO.sleep(Duration.fromMillis(200))
            yield ()
          }

          processed <- processedRef.get
          counts <- store.countByStatus
        yield assertTrue(
          processed.toSet == Set("task-1", "task-2"),
          counts.getOrElse(CommandStatus.Completed, 0L) == 2L
        )
      } @@ TestAspect.withLiveClock,
      test("retries failed commands") {
        for
          store <- ZIO.succeed(new InMemoryCommandStore[String, TestCommand]())
          attemptCount <- Ref.make(0)
          executor = (cmd: TestCommand) =>
            attemptCount.updateAndGet(_ + 1).flatMap { count =>
              if count < 3 then
                ZIO.succeed(CommandResult.Failure("Temporary error", retryable = true))
              else ZIO.succeed(CommandResult.Success)
            }

          config = CommandWorkerConfig()
            .withPollInterval(Duration.fromMillis(30))
            .withRetryPolicy(RetryPolicy.fixedDelay(Duration.fromMillis(50), maxAttempts = 5))

          _ <- store.enqueue("fsm-1", TestCommand.DoWork("retry-me"), "key-1")

          _ <- ZIO.scoped {
            for
              _ <- CommandWorker.make(config, store, executor)
              _ <- ZIO.sleep(Duration.fromMillis(500))
            yield ()
          }

          attempts <- attemptCount.get
          cmdOpt <- store.findById(1L)
        yield
          val cmd = cmdOpt.get
          assertTrue(
            attempts >= 3,
            cmd.status == CommandStatus.Completed
          )
      } @@ TestAspect.withLiveClock,
      test("fails permanently after max retries") {
        for
          store <- ZIO.succeed(new InMemoryCommandStore[String, TestCommand]())
          executor = (_: TestCommand) =>
            ZIO.succeed(CommandResult.Failure("Always fails", retryable = true))

          config = CommandWorkerConfig()
            .withPollInterval(Duration.fromMillis(30))
            .withRetryPolicy(RetryPolicy.fixedDelay(Duration.fromMillis(20), maxAttempts = 2))

          _ <- store.enqueue("fsm-1", TestCommand.DoWork("fail-me"), "key-1")

          _ <- ZIO.scoped {
            for
              _ <- CommandWorker.make(config, store, executor)
              _ <- ZIO.sleep(Duration.fromMillis(300))
            yield ()
          }

          cmdOpt <- store.findById(1L)
        yield
          val cmd = cmdOpt.get
          assertTrue(
            cmd.status == CommandStatus.Failed,
            cmd.lastError.contains("Always fails")
          )
      } @@ TestAspect.withLiveClock,
      test("skips already executed commands") {
        for
          store <- ZIO.succeed(new InMemoryCommandStore[String, TestCommand]())
          executor = (_: TestCommand) => ZIO.succeed(CommandResult.AlreadyExecuted)

          config = CommandWorkerConfig()
            .withPollInterval(Duration.fromMillis(50))

          _ <- store.enqueue("fsm-1", TestCommand.DoWork("skip-me"), "key-1")

          _ <- ZIO.scoped {
            for
              worker <- CommandWorker.make(config, store, executor)
              _ <- ZIO.sleep(Duration.fromMillis(200))
              metrics <- worker.metrics
            yield metrics
          }

          cmdOpt <- store.findById(1L)
        yield
          val cmd = cmdOpt.get
          assertTrue(cmd.status == CommandStatus.Skipped)
      } @@ TestAspect.withLiveClock,
      test("tracks metrics") {
        for
          store <- ZIO.succeed(new InMemoryCommandStore[String, TestCommand]())
          executor = (cmd: TestCommand) =>
            cmd match
              case TestCommand.DoWork(_) => ZIO.succeed(CommandResult.Success)
              case TestCommand.FailingCommand(_) =>
                ZIO.succeed(CommandResult.Failure("Error", retryable = false))
              case _ => ZIO.succeed(CommandResult.AlreadyExecuted)

          config = CommandWorkerConfig()
            .withPollInterval(Duration.fromMillis(50))

          _ <- store.enqueue("fsm-1", TestCommand.DoWork("ok"), "key-1")
          _ <- store.enqueue("fsm-1", TestCommand.FailingCommand(false), "key-2")
          _ <- store.enqueue("fsm-1", TestCommand.SendNotification("x"), "key-3")

          metrics <- ZIO.scoped {
            for
              worker <- CommandWorker.make(config, store, executor)
              _ <- ZIO.sleep(Duration.fromMillis(300))
              m <- worker.metrics
            yield m
          }
        yield assertTrue(
          metrics.commandsSucceeded == 1,
          metrics.commandsFailed == 1,
          metrics.commandsSkipped == 1,
          metrics.commandsProcessed == 3
        )
      } @@ TestAspect.withLiveClock,
      test("stops gracefully") {
        for
          store <- ZIO.succeed(new InMemoryCommandStore[String, TestCommand]())
          executor = (_: TestCommand) =>
            ZIO.sleep(Duration.fromMillis(50)).as(CommandResult.Success)

          config = CommandWorkerConfig()
            .withPollInterval(Duration.fromMillis(20))

          result <- ZIO.scoped {
            for
              worker <- CommandWorker.make(config, store, executor)
              running1 <- worker.isRunning
              _ <- worker.stop
              running2 <- worker.isRunning
            yield (running1, running2)
          }
        yield
          val (running1, running2) = result
          assertTrue(running1, !running2)
      } @@ TestAspect.withLiveClock
    ),
    suite("RetryPolicy")(
      test("NoRetry returns None immediately") {
        val now = Instant.now()
        assertTrue(RetryPolicy.NoRetry.nextRetry(1, now).isEmpty)
      },
      test("FixedDelay returns consistent delays") {
        val now = Instant.now()
        val policy = RetryPolicy.fixedDelay(Duration.fromSeconds(10), maxAttempts = 3)

        val retry1 = policy.nextRetry(1, now)
        val retry2 = policy.nextRetry(2, now)
        val retry3 = policy.nextRetry(3, now)

        assertTrue(
          retry1.isDefined,
          retry2.isDefined,
          retry3.isEmpty, // Exceeded max attempts
          retry1.get.toEpochMilli - now.toEpochMilli == 10000L
        )
      },
      test("ExponentialBackoff increases delay") {
        val now = Instant.now()
        val policy = RetryPolicy.exponentialBackoff(
          initialDelay = Duration.fromSeconds(1),
          maxDelay = Duration.fromSeconds(60),
          multiplier = 2.0,
          maxAttempts = 5
        )

        val delay1 = policy.nextRetry(1, now).map(_.toEpochMilli - now.toEpochMilli)
        val delay2 = policy.nextRetry(2, now).map(_.toEpochMilli - now.toEpochMilli)
        val delay3 = policy.nextRetry(3, now).map(_.toEpochMilli - now.toEpochMilli)

        assertTrue(
          delay1.contains(1000L), // 1s
          delay2.contains(2000L), // 2s
          delay3.contains(4000L) // 4s
        )
      },
      test("ExponentialBackoff respects maxDelay") {
        val now = Instant.now()
        val policy = RetryPolicy.exponentialBackoff(
          initialDelay = Duration.fromSeconds(10),
          maxDelay = Duration.fromSeconds(30),
          multiplier = 10.0,
          maxAttempts = 5
        )

        val delay2 = policy.nextRetry(2, now).map(_.toEpochMilli - now.toEpochMilli)

        assertTrue(delay2.contains(30000L)) // Capped at maxDelay
      }
    )
  ) @@ TestAspect.sequential @@ TestAspect.timeout(Duration.fromSeconds(60))
