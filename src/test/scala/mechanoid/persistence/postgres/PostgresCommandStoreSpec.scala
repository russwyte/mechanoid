package mechanoid.persistence.postgres

import saferis.*
import zio.*
import zio.json.*
import zio.test.*
import mechanoid.PostgresTestContainer
import mechanoid.persistence.command.*

object PostgresCommandStoreSpec extends ZIOSpecDefault:

  // Test command type with zio-json codec
  enum TestCommand derives JsonCodec:
    case SendEmail(to: String, subject: String)
    case ChargePayment(amount: BigDecimal)
    case NotifyWebhook(url: String)

  // Create codec using the factory method - much simpler!
  val testCodec = CommandCodec.fromJson[TestCommand]

  val xaLayer    = PostgresTestContainer.DataSourceProvider.default >>> Transactor.default
  val storeLayer = xaLayer >>> PostgresCommandStore.layer[TestCommand](testCodec)

  // Helper to generate unique IDs for test isolation
  private def uniqueId(prefix: String) = s"$prefix-${java.util.UUID.randomUUID()}"

  def spec = suite("PostgresCommandStore")(
    test("enqueue creates a new command") {
      for
        store <- ZIO.service[CommandStore[String, TestCommand]]
        cmd   <- store.enqueue("instance-1", TestCommand.SendEmail("test@example.com", "Hello"), "email-1")
      yield assertTrue(
        cmd.instanceId == "instance-1",
        cmd.idempotencyKey == "email-1",
        cmd.status == CommandStatus.Pending,
        cmd.attempts == 0,
        cmd.command == TestCommand.SendEmail("test@example.com", "Hello"),
      )
    },
    test("enqueue with duplicate idempotency key returns existing command") {
      for
        store <- ZIO.service[CommandStore[String, TestCommand]]
        cmd1  <- store.enqueue("instance-2", TestCommand.ChargePayment(100), "charge-1")
        cmd2  <- store.enqueue("instance-2", TestCommand.ChargePayment(200), "charge-1")
      yield assertTrue(
        cmd1.id == cmd2.id,
        cmd2.command == TestCommand.ChargePayment(100), // Original command, not the new one
      )
    },
    test("claim returns pending commands") {
      for
        store <- ZIO.service[CommandStore[String, TestCommand]]
        now   <- Clock.instant
        key = uniqueId("webhook")
        _       <- store.enqueue(uniqueId("instance"), TestCommand.NotifyWebhook("http://example.com"), key)
        claimed <- store.claim("node-1", 100, Duration.fromSeconds(30), now)
        // Find our specific command in the claimed list
        ourCommand = claimed.find(_.idempotencyKey == key)
      yield assertTrue(
        ourCommand.isDefined,
        ourCommand.exists(_.status == CommandStatus.Processing),
        ourCommand.exists(_.attempts == 1),
      )
    },
    test("claim respects limit") {
      for
        store   <- ZIO.service[CommandStore[String, TestCommand]]
        now     <- Clock.instant
        _       <- store.enqueue("instance-4", TestCommand.SendEmail("a@test.com", "A"), "limit-1")
        _       <- store.enqueue("instance-4", TestCommand.SendEmail("b@test.com", "B"), "limit-2")
        _       <- store.enqueue("instance-4", TestCommand.SendEmail("c@test.com", "C"), "limit-3")
        claimed <- store.claim("node-1", 2, Duration.fromSeconds(30), now)
      yield assertTrue(claimed.length == 2)
    },
    test("claim skips already claimed commands") {
      for
        store <- ZIO.service[CommandStore[String, TestCommand]]
        now   <- Clock.instant
        key = uniqueId("skip")
        _        <- store.enqueue(uniqueId("instance"), TestCommand.SendEmail("test@test.com", "Test"), key)
        claimed1 <- store.claim("node-1", 100, Duration.fromSeconds(300), now)
        claimed2 <- store.claim("node-2", 100, Duration.fromSeconds(30), now)
        // Check our specific command was claimed by node-1 and not by node-2
        ourInClaim1 = claimed1.find(_.idempotencyKey == key)
        ourInClaim2 = claimed2.find(_.idempotencyKey == key)
      yield assertTrue(
        ourInClaim1.isDefined,
        ourInClaim2.isEmpty,
      )
    },
    test("complete marks command as completed") {
      for
        store     <- ZIO.service[CommandStore[String, TestCommand]]
        now       <- Clock.instant
        _         <- store.enqueue("instance-6", TestCommand.ChargePayment(50), "complete-1")
        claimed   <- store.claim("node-1", 10, Duration.fromSeconds(30), now)
        completed <- store.complete(claimed.head.id)
        retrieved <- store.getByIdempotencyKey("complete-1")
      yield assertTrue(
        completed,
        retrieved.exists(_.status == CommandStatus.Completed),
      )
    },
    test("fail marks command as failed") {
      for
        store     <- ZIO.service[CommandStore[String, TestCommand]]
        now       <- Clock.instant
        _         <- store.enqueue("instance-7", TestCommand.NotifyWebhook("http://fail.com"), "fail-1")
        claimed   <- store.claim("node-1", 10, Duration.fromSeconds(30), now)
        failed    <- store.fail(claimed.head.id, "Connection refused", None)
        retrieved <- store.getByIdempotencyKey("fail-1")
      yield assertTrue(
        failed,
        retrieved.exists(c => c.status == CommandStatus.Failed && c.lastError.contains("Connection refused")),
      )
    },
    test("fail with retryAt marks command as pending for retry") {
      for
        store <- ZIO.service[CommandStore[String, TestCommand]]
        now   <- Clock.instant
        retryAt = now.plusSeconds(60)
        _         <- store.enqueue("instance-8", TestCommand.SendEmail("retry@test.com", "Retry"), "retry-1")
        claimed   <- store.claim("node-1", 10, Duration.fromSeconds(30), now)
        failed    <- store.fail(claimed.head.id, "Temporary failure", Some(retryAt))
        retrieved <- store.getByIdempotencyKey("retry-1")
      yield assertTrue(
        failed,
        retrieved.exists(c => c.status == CommandStatus.Pending && c.nextRetryAt.contains(retryAt)),
      )
    },
    test("skip marks command as skipped") {
      for
        store     <- ZIO.service[CommandStore[String, TestCommand]]
        now       <- Clock.instant
        _         <- store.enqueue("instance-9", TestCommand.ChargePayment(25), "skip-cmd-1")
        claimed   <- store.claim("node-1", 10, Duration.fromSeconds(30), now)
        skipped   <- store.skip(claimed.head.id, "Duplicate detected")
        retrieved <- store.getByIdempotencyKey("skip-cmd-1")
      yield assertTrue(
        skipped,
        retrieved.exists(_.status == CommandStatus.Skipped),
      )
    },
    test("getByInstanceId returns all commands for an instance") {
      for
        store    <- ZIO.service[CommandStore[String, TestCommand]]
        _        <- store.enqueue("instance-10", TestCommand.SendEmail("a@test.com", "A"), "by-instance-1")
        _        <- store.enqueue("instance-10", TestCommand.ChargePayment(100), "by-instance-2")
        _        <- store.enqueue("other-instance", TestCommand.NotifyWebhook("http://other.com"), "by-instance-3")
        commands <- store.getByInstanceId("instance-10")
      yield assertTrue(
        commands.length == 2,
        commands.forall(_.instanceId == "instance-10"),
      )
    },
    test("countByStatus returns correct counts") {
      for
        store <- ZIO.service[CommandStore[String, TestCommand]]
        now   <- Clock.instant
        // Create some commands in various states
        _       <- store.enqueue("count-instance", TestCommand.SendEmail("count@test.com", "Count"), "count-1")
        _       <- store.enqueue("count-instance", TestCommand.ChargePayment(10), "count-2")
        claimed <- store.claim("node-1", 1, Duration.fromSeconds(30), now)
        _       <- store.complete(claimed.head.id)
        counts  <- store.countByStatus
      yield assertTrue(
        counts.getOrElse(CommandStatus.Completed, 0L) >= 1,
        counts.getOrElse(CommandStatus.Pending, 0L) >= 1,
      )
    },
    test("releaseExpiredClaims releases stale claims") {
      for
        store <- ZIO.service[CommandStore[String, TestCommand]]
        now   <- Clock.instant
        past = now.minusSeconds(60)
        _ <- store.enqueue("expired-instance", TestCommand.NotifyWebhook("http://expired.com"), "expired-claim-1")
        // Claim with a time in the past so it's already expired
        _         <- store.claim("node-1", 10, Duration.fromMillis(1), past)
        released  <- store.releaseExpiredClaims(now)
        retrieved <- store.getByIdempotencyKey("expired-claim-1")
      yield assertTrue(
        released >= 1,
        retrieved.exists(_.status == CommandStatus.Pending),
      )
    },
  ).provideShared(storeLayer) @@ TestAspect.sequential
end PostgresCommandStoreSpec
