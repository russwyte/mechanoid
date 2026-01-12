package mechanoid.persistence.postgres

import saferis.*
import zio.*
import zio.test.*
import mechanoid.PostgresTestContainer.DataSourceProvider

object PostgresSchemaSpec extends ZIOSpecDefault:

  // Use a plain connection provider without auto-initialization for these tests
  val plainXaLayer = DataSourceProvider.default >>> Transactor.default

  // Helper to create all required tables except the one being tested
  private def createOtherTables(xa: Transactor) =
    for
      _ <- xa.run(sql"""CREATE TABLE fsm_snapshots (
             instance_id TEXT PRIMARY KEY,
             state_data JSONB NOT NULL,
             sequence_nr BIGINT NOT NULL,
             created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
           )""".dml)
      _ <- xa.run(sql"""CREATE TABLE scheduled_timeouts (
             instance_id TEXT PRIMARY KEY,
             state TEXT NOT NULL,
             deadline TIMESTAMPTZ NOT NULL,
             created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
             claimed_by TEXT,
             claimed_until TIMESTAMPTZ
           )""".dml)
      _ <- xa.run(sql"""CREATE TABLE fsm_instance_locks (
             instance_id TEXT PRIMARY KEY,
             node_id TEXT NOT NULL,
             acquired_at TIMESTAMPTZ NOT NULL,
             expires_at TIMESTAMPTZ NOT NULL
           )""".dml)
      _ <- xa.run(sql"""CREATE TABLE leases (
             key TEXT PRIMARY KEY,
             holder TEXT NOT NULL,
             expires_at TIMESTAMPTZ NOT NULL,
             acquired_at TIMESTAMPTZ NOT NULL
           )""".dml)
      _ <- xa.run(sql"""CREATE TABLE commands (
             id BIGSERIAL PRIMARY KEY,
             instance_id TEXT NOT NULL,
             command_data JSONB NOT NULL,
             idempotency_key TEXT NOT NULL UNIQUE,
             enqueued_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
             status TEXT NOT NULL DEFAULT 'pending',
             attempts INT NOT NULL DEFAULT 0,
             last_attempt_at TIMESTAMPTZ,
             last_error TEXT,
             next_retry_at TIMESTAMPTZ,
             claimed_by TEXT,
             claimed_until TIMESTAMPTZ
           )""".dml)
    yield ()

  def spec = suite("PostgresSchema")(
    test("initialize creates tables on empty database") {
      for result <- PostgresSchema.initialize
      yield assertTrue(result == PostgresSchema.InitResult.Created)
    }.provide(plainXaLayer),
    test("initialize verifies existing tables on second call") {
      for
        result1 <- PostgresSchema.initialize
        result2 <- PostgresSchema.initialize
      yield assertTrue(
        result1 == PostgresSchema.InitResult.Created,
        result2 == PostgresSchema.InitResult.Verified,
      )
    }.provide(plainXaLayer),
    test("createIfNotExists returns true when tables created") {
      for created <- PostgresSchema.createIfNotExists
      yield assertTrue(created)
    }.provide(plainXaLayer),
    test("createIfNotExists returns false when tables exist") {
      for
        _       <- PostgresSchema.createIfNotExists
        created <- PostgresSchema.createIfNotExists
      yield assertTrue(!created)
    }.provide(plainXaLayer),
    test("verify passes when schema is correct") {
      for
        _      <- PostgresSchema.createIfNotExists
        result <- PostgresSchema.verify.either
      yield assertTrue(result.isRight)
    }.provide(plainXaLayer),
    test("verify detects missing table") {
      for
        xa <- ZIO.service[Transactor]
        // Create only fsm_events, not all tables
        _ <- xa.run(sql"""CREATE TABLE fsm_events (
               id BIGSERIAL PRIMARY KEY,
               instance_id TEXT NOT NULL,
               sequence_nr BIGINT NOT NULL,
               event_data JSONB NOT NULL,
               created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
               UNIQUE (instance_id, sequence_nr)
             )""".dml)
        result <- PostgresSchema.verify.either
      yield result match
        case Left(SchemaValidationError(issues)) =>
          assertTrue(
            issues.exists {
              case SchemaIssue.MissingTable(name) => name == "fsm_snapshots" || name == "commands"
              case _                              => false
            }
          )
        case Right(_) => assertTrue(false)
    }.provide(plainXaLayer),
    test("verify detects missing column") {
      for
        xa <- ZIO.service[Transactor]
        // Create fsm_events with missing event_data column
        _ <- xa.run(sql"""CREATE TABLE fsm_events (
               id BIGSERIAL PRIMARY KEY,
               instance_id TEXT NOT NULL,
               sequence_nr BIGINT NOT NULL,
               created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
             )""".dml)
        // Create other required tables with correct schema
        _      <- createOtherTables(xa)
        result <- PostgresSchema.verify.either
      yield result match
        case Left(SchemaValidationError(issues)) =>
          assertTrue(
            issues.exists {
              case SchemaIssue.MissingColumn("fsm_events", "event_data") => true
              case _                                                     => false
            }
          )
        case Right(_) => assertTrue(false)
    }.provide(plainXaLayer),
    test("verify detects type mismatch") {
      for
        xa <- ZIO.service[Transactor]
        // Create fsm_events with wrong type for sequence_nr (TEXT instead of BIGINT)
        _ <- xa.run(sql"""CREATE TABLE fsm_events (
               id BIGSERIAL PRIMARY KEY,
               instance_id TEXT NOT NULL,
               sequence_nr TEXT NOT NULL,
               event_data JSONB NOT NULL,
               created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
             )""".dml)
        // Create other required tables
        _      <- createOtherTables(xa)
        result <- PostgresSchema.verify.either
      yield result match
        case Left(SchemaValidationError(issues)) =>
          assertTrue(
            issues.exists {
              case SchemaIssue.TypeMismatch("fsm_events", "sequence_nr", _, _) => true
              case _                                                           => false
            }
          )
        case Right(_) => assertTrue(false)
    }.provide(plainXaLayer),
    test("all managed tables are verified") {
      for
        _      <- PostgresSchema.initialize
        result <- PostgresSchema.verify.either
      yield assertTrue(
        result.isRight,
        PostgresSchema.ManagedTables.length == 6,
      )
    }.provide(plainXaLayer),
  ) @@ TestAspect.sequential
end PostgresSchemaSpec
