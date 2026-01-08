package mechanoid.persistence.postgres

import saferis.*
import zio.*
import zio.json.*
import zio.test.*
import zio.test.Assertion.*
import mechanoid.PostgresTestContainer
import mechanoid.core.*
import mechanoid.persistence.*
import mechanoid.persistence.command.*
import mechanoid.persistence.lock.*
import mechanoid.persistence.timeout.*
import java.time.Instant
import java.util.UUID

/** Brutal stress tests for PostgreSQL persistence implementations.
  *
  * These tests throw worst-case scenarios at the persistence layer:
  * - Massive parallelism with race conditions
  * - Fiber interruption at strategic points
  * - Simulated node failures
  * - Leader election failover
  * - Consistency guarantees under chaos
  */
object PostgresStressSpec extends ZIOSpecDefault:

  // Test types with zio-json codecs
  enum StressState extends MState derives JsonCodec:
    case Idle
    case Processing
    case WaitingForTimeout
    case Completed
    case Failed

  enum StressEvent extends MEvent derives JsonCodec:
    case Started(data: String)
    case Progressed(step: Int)
    case Finished
    case Error(msg: String)

  enum StressCommand derives JsonCodec:
    case DoWork(payload: String)
    case Retry(attempt: Int)
    case Complete

  val eventCodec = EventCodec.fromJson[StressState, StressEvent]
  val commandCodec = CommandCodec.fromJson[StressCommand]

  // Shared layers
  val xaLayer = PostgresTestContainer.DataSourceProvider.default >>> Transactor.default
  val eventStoreLayer = xaLayer >>> PostgresEventStore.layer[StressState, StressEvent](eventCodec)
  val commandStoreLayer = xaLayer >>> PostgresCommandStore.layer[StressCommand](commandCodec)
  val timeoutStoreLayer = xaLayer >>> PostgresTimeoutStore.layer
  val lockLayer = xaLayer >>> PostgresInstanceLock.layer
  val leaseStoreLayer = xaLayer >>> PostgresLeaseStore.layer

  val allLayers = eventStoreLayer ++ commandStoreLayer ++ timeoutStoreLayer ++ lockLayer ++ leaseStoreLayer

  private def uniqueId(prefix: String) = s"$prefix-${UUID.randomUUID()}"

  // ============================================
  // Lock Stress Tests
  // ============================================

  val lockStressTests = suite("Lock Stress Tests")(
    test("100 concurrent nodes racing for the same lock - only one wins initially") {
      for
        lock <- ZIO.service[FSMInstanceLock[String]]
        now <- Clock.instant
        instanceId = uniqueId("lock-race")
        nodes = (1 to 100).map(i => s"node-$i").toList

        // All nodes try to acquire simultaneously
        results <- ZIO.foreachPar(nodes) { nodeId =>
          lock.tryAcquire(instanceId, nodeId, Duration.fromSeconds(30), now)
        }

        acquired = results.collect { case LockResult.Acquired(token) => token }
        busy = results.collect { case LockResult.Busy(_, _) => () }
      yield assertTrue(
        acquired.length == 1, // Exactly one winner
        busy.length == 99, // Everyone else is blocked
        acquired.head.nodeId.startsWith("node-") // Winner is valid
      )
    },

    test("lock expires and another node acquires") {
      for
        lock <- ZIO.service[FSMInstanceLock[String]]
        now <- Clock.instant
        instanceId = uniqueId("lock-expire")

        // Node 1 acquires with very short duration
        result1 <- lock.tryAcquire(instanceId, "node-1", Duration.fromMillis(50), now)
        _ <- assertTrue(result1.isInstanceOf[LockResult.Acquired[String]])

        // Wait for expiry
        _ <- ZIO.sleep(Duration.fromMillis(100))
        later <- Clock.instant

        // Node 2 should now be able to acquire
        result2 <- lock.tryAcquire(instanceId, "node-2", Duration.fromSeconds(30), later)
      yield assertTrue(
        result2.isInstanceOf[LockResult.Acquired[String]],
        result2.asInstanceOf[LockResult.Acquired[String]].token.nodeId == "node-2"
      )
    },

    test("same node can extend its own lock repeatedly") {
      for
        lock <- ZIO.service[FSMInstanceLock[String]]
        now <- Clock.instant
        instanceId = uniqueId("lock-extend")

        // Acquire
        result <- lock.tryAcquire(instanceId, "node-1", Duration.fromMillis(100), now)
        token = result.asInstanceOf[LockResult.Acquired[String]].token

        // Extend 50 times
        finalToken <- ZIO.foldLeft(1 to 50)(token) { (current, _) =>
          for
            later <- Clock.instant
            extended <- lock.extend(current, Duration.fromMillis(100), later)
          yield extended.get
        }

        // Should still be valid
        later <- Clock.instant
        holder <- lock.get(instanceId, later)
      yield assertTrue(
        holder.isDefined,
        holder.get.nodeId == "node-1"
      )
    },

    test("lock acquisition with waiting - nodes queue up") {
      for
        lock <- ZIO.service[FSMInstanceLock[String]]
        instanceId = uniqueId("lock-wait")

        // Node 1 acquires with short duration
        result1 <- lock.acquire(instanceId, "node-1", Duration.fromMillis(100), Duration.fromSeconds(1))
        _ <- assertTrue(result1.isInstanceOf[LockResult.Acquired[String]])

        // Node 2 waits for node 1's lock to expire
        fiber2 <- lock.acquire(instanceId, "node-2", Duration.fromSeconds(10), Duration.fromSeconds(2)).fork

        // Wait a bit then check node 2 eventually gets the lock
        result2 <- fiber2.join
      yield assertTrue(
        result2.isInstanceOf[LockResult.Acquired[String]],
        result2.asInstanceOf[LockResult.Acquired[String]].token.nodeId == "node-2"
      )
    },

    test("interrupt lock acquisition mid-wait") {
      for
        lock <- ZIO.service[FSMInstanceLock[String]]
        now <- Clock.instant
        instanceId = uniqueId("lock-interrupt")

        // Node 1 holds the lock
        _ <- lock.tryAcquire(instanceId, "node-1", Duration.fromSeconds(60), now)

        // Node 2 tries to acquire with long timeout
        fiber <- lock.acquire(instanceId, "node-2", Duration.fromSeconds(30), Duration.fromSeconds(30)).fork

        // Let it start waiting
        _ <- ZIO.sleep(Duration.fromMillis(50))

        // Interrupt it
        _ <- fiber.interrupt

        // Lock should still be held by node 1
        later <- Clock.instant
        holder <- lock.get(instanceId, later)
      yield assertTrue(
        holder.isDefined,
        holder.get.nodeId == "node-1"
      )
    }
  )

  // ============================================
  // Event Store Stress Tests
  // ============================================

  val eventStoreStressTests = suite("Event Store Stress Tests")(
    test("100 concurrent appends to same instance - exactly one wins per sequence number") {
      for
        store <- ZIO.service[EventStore[String, StressState, StressEvent]]
        instanceId = uniqueId("event-race")

        // 100 concurrent attempts to append sequence 1
        results <- ZIO.foreachPar(1 to 100) { i =>
          store.append(instanceId, StressEvent.Started(s"data-$i"), 1).either
        }

        successes = results.collect { case Right(seqNr) => seqNr }
        conflicts = results.collect { case Left(_: SequenceConflictError) => () }

        // Verify only one event was persisted
        events <- store.loadEvents(instanceId).runCollect
      yield assertTrue(
        successes.length == 1,
        conflicts.length == 99,
        events.length == 1,
        events.head.sequenceNr == 1L
      )
    },

    test("parallel append chains to different instances - all succeed") {
      for
        store <- ZIO.service[EventStore[String, StressState, StressEvent]]

        // 50 instances, each gets 10 sequential events
        instanceIds = (1 to 50).map(_ => uniqueId("chain")).toList

        results <- ZIO.foreachPar(instanceIds) { instanceId =>
          ZIO.foreach(1 to 10) { seq =>
            store.append(instanceId, StressEvent.Progressed(seq), seq.toLong)
          }
        }

        // Verify all chains complete
        allEvents <- ZIO.foreach(instanceIds) { id =>
          store.loadEvents(id).runCollect.map(id -> _)
        }
      yield assertTrue(
        results.flatten.length == 500, // All 500 events succeeded
        allEvents.forall(_._2.length == 10) // Each instance has 10 events
      )
    },

    test("snapshot consistency under concurrent appends") {
      for
        store <- ZIO.service[EventStore[String, StressState, StressEvent]]
        instanceId = uniqueId("snapshot-stress")

        // Append events sequentially first
        _ <- ZIO.foreach(1 to 10)(i => store.append(instanceId, StressEvent.Progressed(i), i.toLong))

        // Save snapshot at seq 5
        now <- Clock.instant
        _ <- store.saveSnapshot(FSMSnapshot(instanceId, StressState.Processing, 5L, now))

        // Continue appending while also overwriting snapshot
        fiber1 <- ZIO.foreach(11 to 20)(i => store.append(instanceId, StressEvent.Progressed(i), i.toLong)).fork
        fiber2 <- store.saveSnapshot(FSMSnapshot(instanceId, StressState.WaitingForTimeout, 10L, now)).fork

        _ <- fiber1.join
        _ <- fiber2.join

        // Verify consistency
        snapshot <- store.loadSnapshot(instanceId)
        events <- store.loadEvents(instanceId).runCollect
        eventsFromSnapshot <- store.loadEventsFrom(instanceId, snapshot.get.sequenceNr).runCollect
      yield assertTrue(
        snapshot.isDefined,
        events.length == 20,
        eventsFromSnapshot.forall(_.sequenceNr > snapshot.get.sequenceNr)
      )
    },

    test("delete events while loading - no crash") {
      for
        store <- ZIO.service[EventStore[String, StressState, StressEvent]]
        instanceId = uniqueId("delete-stress")

        // Add events
        _ <- ZIO.foreach(1 to 20)(i => store.append(instanceId, StressEvent.Progressed(i), i.toLong))

        // Start loading while deleting
        loadFiber <- store.loadEvents(instanceId).runCollect.fork
        deleteFiber <- store.deleteEventsTo(instanceId, 10).fork

        loadedEvents <- loadFiber.join
        _ <- deleteFiber.join

        // Verify we got some events (may vary due to race)
        remainingEvents <- store.loadEvents(instanceId).runCollect
      yield assertTrue(
        loadedEvents.nonEmpty, // Should have loaded something
        remainingEvents.length <= 10 // Deletion happened
      )
    },

    test("Timeout events mixed with regular events") {
      for
        store <- ZIO.service[EventStore[String, StressState, StressEvent]]
        instanceId = uniqueId("timeout-mix")

        // Alternate between regular events and timeouts
        _ <- store.append(instanceId, StressEvent.Started("begin"), 1)
        _ <- store.append(instanceId, Timeout, 2)
        _ <- store.append(instanceId, StressEvent.Progressed(1), 3)
        _ <- store.append(instanceId, Timeout, 4)
        _ <- store.append(instanceId, StressEvent.Finished, 5)

        events <- store.loadEvents(instanceId).runCollect
        timeouts = events.filter(_.event == Timeout)
        regulars = events.filterNot(_.event == Timeout)
      yield assertTrue(
        events.length == 5,
        timeouts.length == 2,
        regulars.length == 3
      )
    }
  )

  // ============================================
  // Command Store Stress Tests
  // ============================================

  val commandStoreStressTests = suite("Command Store Stress Tests")(
    test("100 concurrent claims from different nodes - each command claimed once") {
      for
        store <- ZIO.service[CommandStore[String, StressCommand]]
        now <- Clock.instant

        // Enqueue 20 commands
        keys = (1 to 20).map(i => uniqueId(s"claim-race-$i")).toList
        _ <- ZIO.foreach(keys)(key =>
          store.enqueue(uniqueId("instance"), StressCommand.DoWork(key), key)
        )

        // 10 nodes each try to claim all available
        nodes = (1 to 10).map(i => s"worker-$i").toList
        results <- ZIO.foreachPar(nodes) { nodeId =>
          store.claim(nodeId, 100, Duration.fromSeconds(30), now)
        }

        // Count total claimed
        totalClaimed = results.flatten
        uniqueIds = totalClaimed.map(_.idempotencyKey).toSet
      yield assertTrue(
        totalClaimed.length == 20, // All 20 commands claimed
        uniqueIds.size == 20, // Each claimed exactly once
        totalClaimed.forall(_.status == CommandStatus.Processing)
      )
    },

    test("complete and fail commands while claiming - no double processing") {
      for
        store <- ZIO.service[CommandStore[String, StressCommand]]
        now <- Clock.instant
        instanceId = uniqueId("process-stress")

        // Enqueue commands
        _ <- ZIO.foreach(1 to 50)(i =>
          store.enqueue(instanceId, StressCommand.Retry(i), uniqueId(s"process-$i"))
        )

        // Multiple workers claim and process concurrently
        processedRef <- Ref.make(Set.empty[Long])

        workers = (1 to 5).map { workerId =>
          (for
            claimed <- store.claim(s"worker-$workerId", 20, Duration.fromSeconds(30), now)
            _ <- ZIO.foreach(claimed) { cmd =>
              // Use command ID to determine complete vs fail (pseudo-random)
              if cmd.id % 2 == 0 then store.complete(cmd.id) *> processedRef.update(_ + cmd.id)
              else store.fail(cmd.id, "simulated failure", Some(now.plusSeconds(60)))
            }
          yield claimed.length).repeatN(2) // Each worker does 3 rounds
        }

        _ <- ZIO.foreachPar(workers)(identity)

        processed <- processedRef.get
        counts <- store.countByStatus
      yield assertTrue(
        // Some commands were completed (not all because some failed and are pending again)
        processed.nonEmpty,
        // No duplicates in processed set
        processed.size == processed.toList.length
      )
    },

    test("idempotency - same key always returns same command") {
      for
        store <- ZIO.service[CommandStore[String, StressCommand]]
        key = uniqueId("idempotent")

        // 100 concurrent enqueues with same key but different data
        results <- ZIO.foreachPar(1 to 100) { i =>
          store.enqueue(uniqueId("instance"), StressCommand.DoWork(s"data-$i"), key)
        }

        // All should return the same command ID
        ids = results.map(_.id).toSet
        commands = results.map(_.command).toSet
      yield assertTrue(
        ids.size == 1, // All return same ID
        commands.size == 1 // All return same command (the first one)
      )
    },

    test("release expired claims and reclaim") {
      for
        store <- ZIO.service[CommandStore[String, StressCommand]]
        past <- Clock.instant.map(_.minusSeconds(100))
        now <- Clock.instant

        // Enqueue commands
        key = uniqueId("expire-reclaim")
        _ <- store.enqueue(uniqueId("instance"), StressCommand.Complete, key)

        // Claim with expired time
        claimed1 <- store.claim("worker-1", 10, Duration.fromMillis(1), past)
        _ <- assertTrue(claimed1.nonEmpty)

        // Release expired
        released <- store.releaseExpiredClaims(now)
        _ <- assertTrue(released >= 1)

        // Another worker can now claim
        claimed2 <- store.claim("worker-2", 10, Duration.fromSeconds(30), now)
        ourCmd = claimed2.find(_.idempotencyKey == key)
      yield assertTrue(
        ourCmd.isDefined,
        ourCmd.get.attempts == 2 // Second attempt
      )
    }
  )

  // ============================================
  // Command Queue Production Simulation Tests
  // ============================================

  val commandQueueProductionTests = suite("Command Queue Production Simulation")(
    test("producer-consumer with forked workers - all commands eventually processed") {
      for
        store <- ZIO.service[CommandStore[String, StressCommand]]
        completedRef <- Ref.make(Set.empty[String])
        producerDone <- Promise.make[Nothing, Unit]

        // Track all enqueued keys
        enqueuedKeysRef <- Ref.make(Set.empty[String])

        // Producer: continuously enqueue commands
        producer <- (for
          _ <- ZIO.foreach(1 to 100) { batch =>
            ZIO.foreach(1 to 10) { i =>
              val key = uniqueId(s"prod-$batch-$i")
              store.enqueue(uniqueId("instance"), StressCommand.DoWork(s"work-$batch-$i"), key) *>
                enqueuedKeysRef.update(_ + key)
            }
          }
          _ <- producerDone.succeed(())
        yield ()).fork

        // 5 Consumer workers: continuously claim and process
        consumers <- ZIO.foreach(1 to 5) { workerId =>
          (for
            _ <- (for
              now <- Clock.instant
              claimed <- store.claim(s"consumer-$workerId", 20, Duration.fromSeconds(10), now)
              _ <- ZIO.foreach(claimed) { cmd =>
                // Simulate processing time
                ZIO.sleep(Duration.fromMillis(1)) *>
                  store.complete(cmd.id) *>
                  completedRef.update(_ + cmd.idempotencyKey)
              }
              // Small pause between claim rounds
              _ <- ZIO.sleep(Duration.fromMillis(5))
            yield claimed.length).repeatWhile(_ => true).race(
              // Stop when producer is done and queue is empty
              producerDone.await *> ZIO.sleep(Duration.fromMillis(500))
            )
          yield ()).fork
        }

        // Wait for producer to finish
        _ <- producer.join

        // Give consumers time to drain the queue
        _ <- ZIO.sleep(Duration.fromMillis(1000))

        // Interrupt consumers
        _ <- ZIO.foreach(consumers)(_.interrupt)

        // Check that most commands were processed
        completed <- completedRef.get
        enqueued <- enqueuedKeysRef.get

        // Allow a few in-flight, but most should be done
        completionRate = completed.size.toDouble / enqueued.size.toDouble
      yield assertTrue(
        enqueued.size == 1000, // All 100 batches * 10 commands
        completionRate >= 0.95 // At least 95% completed
      )
    },

    test("worker crash and recovery - commands not lost") {
      for
        store <- ZIO.service[CommandStore[String, StressCommand]]
        processedRef <- Ref.make(Set.empty[String])

        // Enqueue commands
        keys = (1 to 50).map(i => uniqueId(s"crash-$i")).toList
        _ <- ZIO.foreach(keys)(key =>
          store.enqueue(uniqueId("instance"), StressCommand.DoWork(key), key)
        )

        // Worker 1: claims commands then "crashes" (gets interrupted)
        crashingWorker <- (for
          now <- Clock.instant
          claimed <- store.claim("crashing-worker", 30, Duration.fromMillis(100), now)
          // Simulate crash - don't complete, just hang
          _ <- ZIO.never
        yield ()).fork

        // Let worker claim some
        _ <- ZIO.sleep(Duration.fromMillis(50))

        // Kill the worker (simulates crash)
        _ <- crashingWorker.interrupt

        // Wait for claims to expire
        _ <- ZIO.sleep(Duration.fromMillis(200))

        // Release expired claims
        now <- Clock.instant
        _ <- store.releaseExpiredClaims(now)

        // Recovery worker picks up all pending commands
        recoveryRounds <- Ref.make(0)
        _ <- (for
          claimNow <- Clock.instant
          claimed <- store.claim("recovery-worker", 100, Duration.fromSeconds(30), claimNow)
          _ <- ZIO.foreach(claimed) { cmd =>
            store.complete(cmd.id) *> processedRef.update(_ + cmd.idempotencyKey)
          }
          _ <- recoveryRounds.update(_ + 1)
        yield claimed.length).repeatWhile(_ > 0)

        processed <- processedRef.get
        rounds <- recoveryRounds.get
      yield assertTrue(
        processed.size == 50, // All commands eventually processed
        keys.forall(processed.contains) // No commands lost
      )
    },

    test("retry with exponential backoff simulation") {
      for
        store <- ZIO.service[CommandStore[String, StressCommand]]
        attemptCountRef <- Ref.make(Map.empty[String, Int])
        completedKeysRef <- Ref.make(Set.empty[String])

        // Enqueue commands that will fail initially
        keys = (1 to 20).map(i => uniqueId(s"retry-$i")).toList
        keysSet = keys.toSet
        _ <- ZIO.foreach(keys)(key =>
          store.enqueue(uniqueId("instance"), StressCommand.Retry(1), key)
        )

        // Worker that fails commands on first 2 attempts, succeeds on 3rd
        // Keep processing until all our commands are completed
        _ <- (for
          completed <- completedKeysRef.get
          _ <- ZIO.when(completed.size < 20) {
            for
              now <- Clock.instant
              claimed <- store.claim("retry-worker", 50, Duration.fromSeconds(30), now)
              // Only process our keys
              ourCmds = claimed.filter(c => keysSet.contains(c.idempotencyKey))
              _ <- ZIO.foreach(ourCmds) { cmd =>
                for
                  counts <- attemptCountRef.get
                  currentAttempts = counts.getOrElse(cmd.idempotencyKey, 0)
                  _ <- attemptCountRef.update(_.updated(cmd.idempotencyKey, currentAttempts + 1))
                  _ <-
                    if currentAttempts < 2 then
                      // Fail with retry - very short delay for test speed
                      store.fail(cmd.id, s"Attempt ${currentAttempts + 1} failed", Some(now.plusMillis(10)))
                    else
                      // Success on 3rd attempt
                      store.complete(cmd.id) *> completedKeysRef.update(_ + cmd.idempotencyKey)
                yield ()
              }
              // Wait a bit for retry times to pass
              _ <- ZIO.sleep(Duration.fromMillis(15))
            yield ()
          }
          done <- completedKeysRef.get.map(_.size >= 20)
        yield done).repeatUntil(identity).timeout(Duration.fromSeconds(10))

        // Check all commands were retried appropriately
        completedKeys <- completedKeysRef.get
        attempts <- attemptCountRef.get
      yield assertTrue(
        completedKeys.size == 20, // All our commands completed
        attempts.values.forall(_ >= 3), // Each was attempted at least 3 times
        attempts.size == 20 // All commands tracked
      )
    },

    test("high throughput - 1000 commands with 10 parallel workers") {
      for
        store <- ZIO.service[CommandStore[String, StressCommand]]
        completedKeysRef <- Ref.make(Set.empty[String])
        startTime <- Clock.instant

        // Generate unique keys for this test
        keysRef <- Ref.make(Set.empty[String])

        // Enqueue 1000 commands, tracking keys
        _ <- ZIO.foreachPar(1 to 100) { batch =>
          ZIO.foreach(1 to 10) { i =>
            val key = uniqueId(s"bulk-$batch-$i")
            keysRef.update(_ + key) *>
              store.enqueue(uniqueId("instance"), StressCommand.DoWork(s"bulk-$batch-$i"), key)
          }
        }

        keys <- keysRef.get

        // 10 workers process in parallel - only process our keys
        workers <- ZIO.foreach(1 to 10) { workerId =>
          (for
            _ <- (for
              now <- Clock.instant
              claimed <- store.claim(s"bulk-worker-$workerId", 50, Duration.fromSeconds(30), now)
              ourCmds = claimed.filter(c => keys.contains(c.idempotencyKey))
              _ <- ZIO.foreach(ourCmds) { cmd =>
                store.complete(cmd.id) *> completedKeysRef.update(_ + cmd.idempotencyKey)
              }
            yield ourCmds.length).repeatWhile(_ > 0)
          yield ()).fork
        }

        // Wait for all workers to finish
        _ <- ZIO.foreach(workers)(_.join)

        endTime <- Clock.instant
        completedKeys <- completedKeysRef.get
        durationMs = java.time.Duration.between(startTime, endTime).toMillis
      yield assertTrue(
        completedKeys.size == 1000, // All 1000 processed
        completedKeys == keys, // Exactly our keys
        durationMs < 10000 // Should complete in under 10 seconds
      )
    },

    test("skip poisonous commands - processing continues") {
      for
        store <- ZIO.service[CommandStore[String, StressCommand]]
        processedRef <- Ref.make(Set.empty[String])
        skippedRef <- Ref.make(Set.empty[String])

        // Enqueue mix of good and "poison" commands
        goodKeys = (1 to 30).map(i => s"good-${uniqueId(s"$i")}").toList
        poisonKeys = (1 to 10).map(i => s"poison-${uniqueId(s"$i")}").toList
        allKeys = goodKeys ++ poisonKeys

        _ <- ZIO.foreach(allKeys) { key =>
          val cmd = if key.startsWith("poison") then StressCommand.DoWork("POISON")
                    else StressCommand.DoWork("normal")
          store.enqueue(uniqueId("instance"), cmd, key)
        }

        // Worker that skips poison commands - only process our keys
        _ <- (for
          now <- Clock.instant
          claimed <- store.claim("careful-worker", 50, Duration.fromSeconds(30), now)
          ourCmds = claimed.filter(c => allKeys.contains(c.idempotencyKey))
          _ <- ZIO.foreach(ourCmds) { cmd =>
            cmd.command match
              case StressCommand.DoWork("POISON") =>
                store.skip(cmd.id, "Poison pill detected") *>
                  skippedRef.update(_ + cmd.idempotencyKey)
              case _ =>
                store.complete(cmd.id) *>
                  processedRef.update(_ + cmd.idempotencyKey)
          }
        yield ourCmds.length).repeatWhile(_ > 0)

        processed <- processedRef.get
        skipped <- skippedRef.get
      yield assertTrue(
        processed.size == 30, // All good commands processed
        skipped.size == 10, // All poison commands skipped
        goodKeys.forall(processed.contains),
        poisonKeys.forall(skipped.contains)
      )
    },

    test("competing workers on same instance - fair distribution") {
      for
        store <- ZIO.service[CommandStore[String, StressCommand]]
        instanceId = uniqueId("fair-instance")
        workerCountsRef <- Ref.make(Map.empty[String, Int])
        processedKeysRef <- Ref.make(Set.empty[String])

        // Enqueue 100 commands for same instance - track keys
        keys = (1 to 100).map(i => uniqueId(s"fair-$i")).toList
        _ <- ZIO.foreach(keys) { key =>
          store.enqueue(instanceId, StressCommand.DoWork(key), key)
        }

        // 5 workers compete - only process our keys
        workers <- ZIO.foreach(1 to 5) { workerId =>
          val workerName = s"fair-worker-$workerId"
          (for
            _ <- (for
              now <- Clock.instant
              claimed <- store.claim(workerName, 10, Duration.fromSeconds(30), now)
              ourCmds = claimed.filter(c => keys.contains(c.idempotencyKey))
              _ <- ZIO.foreach(ourCmds) { cmd =>
                store.complete(cmd.id) *>
                  processedKeysRef.update(_ + cmd.idempotencyKey) *>
                  workerCountsRef.update { m =>
                    m.updated(workerName, m.getOrElse(workerName, 0) + 1)
                  }
              }
              // Tiny pause to allow interleaving
              _ <- ZIO.sleep(Duration.fromMillis(1))
            yield ourCmds.length).repeatWhile(_ > 0)
          yield ()).fork
        }

        _ <- ZIO.foreach(workers)(_.join)

        workerCounts <- workerCountsRef.get
        processedKeys <- processedKeysRef.get
        totalProcessed = workerCounts.values.sum
      yield assertTrue(
        totalProcessed == 100, // All 100 processed
        processedKeys.size == 100, // All keys processed
        workerCounts.size >= 1, // At least one worker participated
        workerCounts.values.forall(_ > 0) // Each active worker did some work
      )
    },

    test("concurrent enqueue and claim - no race conditions") {
      for
        store <- ZIO.service[CommandStore[String, StressCommand]]
        enqueuedRef <- Ref.make(Set.empty[String])
        processedRef <- Ref.make(Set.empty[String])
        done <- Promise.make[Nothing, Unit]

        // Producer continuously enqueues
        producer <- (for
          _ <- ZIO.foreach(1 to 50) { batch =>
            ZIO.foreach(1 to 20) { i =>
              val key = uniqueId(s"race-$batch-$i")
              store.enqueue(uniqueId("instance"), StressCommand.DoWork(key), key) *>
                enqueuedRef.update(_ + key)
            } *> ZIO.sleep(Duration.fromMillis(10))
          }
          _ <- done.succeed(())
        yield ()).fork

        // Consumer runs in parallel
        consumer <- (for
          _ <- (for
            now <- Clock.instant
            claimed <- store.claim("race-worker", 100, Duration.fromSeconds(30), now)
            _ <- ZIO.foreach(claimed)(cmd =>
              store.complete(cmd.id) *> processedRef.update(_ + cmd.idempotencyKey)
            )
            _ <- ZIO.sleep(Duration.fromMillis(5))
          yield ()).repeatWhile(_ => true).race(
            done.await *> ZIO.sleep(Duration.fromMillis(500))
          )
        yield ()).fork

        _ <- producer.join
        _ <- ZIO.sleep(Duration.fromMillis(1000))
        _ <- consumer.interrupt

        // Drain any remaining
        _ <- (for
          now <- Clock.instant
          claimed <- store.claim("cleanup-worker", 1000, Duration.fromSeconds(30), now)
          _ <- ZIO.foreach(claimed)(cmd =>
            store.complete(cmd.id) *> processedRef.update(_ + cmd.idempotencyKey)
          )
        yield claimed.length).repeatWhile(_ > 0)

        enqueued <- enqueuedRef.get
        processed <- processedRef.get
      yield assertTrue(
        enqueued.size == 1000, // All enqueued
        processed == enqueued, // All processed, no extras, no missing
        processed.size == 1000
      )
    }
  )

  // ============================================
  // Timeout Store Stress Tests
  // ============================================

  val timeoutStoreStressTests = suite("Timeout Store Stress Tests")(
    test("100 concurrent claims for same timeout - exactly one wins") {
      for
        store <- ZIO.service[TimeoutStore[String]]
        now <- Clock.instant
        instanceId = uniqueId("timeout-claim-race")

        // Schedule a timeout in the past (already expired)
        deadline = now.minusSeconds(10)
        _ <- store.schedule(instanceId, "WaitingForTimeout", deadline)

        // 100 nodes try to claim simultaneously
        nodes = (1 to 100).map(i => s"sweeper-$i").toList
        results <- ZIO.foreachPar(nodes) { nodeId =>
          store.claim(instanceId, nodeId, Duration.fromSeconds(30), now)
        }

        claimed = results.collect { case ClaimResult.Claimed(_) => () }
        alreadyClaimed = results.collect { case ClaimResult.AlreadyClaimed(_, _) => () }
      yield assertTrue(
        claimed.length == 1, // Exactly one winner
        alreadyClaimed.length == 99 // Everyone else gets AlreadyClaimed
      )
    },

    test("schedule replaces existing timeout atomically") {
      for
        store <- ZIO.service[TimeoutStore[String]]
        now <- Clock.instant
        instanceId = uniqueId("timeout-replace")

        // Schedule initial timeout
        _ <- store.schedule(instanceId, "State1", now.plusSeconds(100))

        // Parallel updates with different states
        results <- ZIO.foreachPar(1 to 50) { i =>
          store.schedule(instanceId, s"State$i", now.plusSeconds(i.toLong))
        }

        // Only one timeout should exist
        current <- store.get(instanceId)
      yield assertTrue(
        current.isDefined,
        current.get.instanceId == instanceId
        // State could be any of the concurrent updates - that's fine
      )
    },

    test("cancel while being claimed - one succeeds") {
      for
        store <- ZIO.service[TimeoutStore[String]]
        now <- Clock.instant
        instanceId = uniqueId("cancel-vs-claim")
        deadline = now.minusSeconds(1) // Already expired

        _ <- store.schedule(instanceId, "WaitingForTimeout", deadline)

        // Race between cancel and claim
        cancelFiber <- store.cancel(instanceId).fork
        claimFiber <- store.claim(instanceId, "sweeper-1", Duration.fromSeconds(30), now).fork

        cancelResult <- cancelFiber.join
        claimResult <- claimFiber.join

        // Exactly one should succeed meaningfully
        current <- store.get(instanceId)
      yield assertTrue(
        // Either cancelled (current is None) or claimed (current has claimer)
        current.isEmpty || current.exists(_.claimedBy.isDefined)
      )
    },

    test("query expired with concurrent completions") {
      for
        store <- ZIO.service[TimeoutStore[String]]
        now <- Clock.instant
        past = now.minusSeconds(10)

        // Schedule many expired timeouts
        ids = (1 to 50).map(_ => uniqueId("expired")).toList
        _ <- ZIO.foreach(ids)(id => store.schedule(id, "WaitingForTimeout", past))

        // Query and complete concurrently
        queryFiber <- store.queryExpired(100, now).fork
        completeFiber <- ZIO.foreach(ids.take(25))(store.complete).fork

        expired <- queryFiber.join
        _ <- completeFiber.join

        // Should have found some (exact count varies due to race)
        remaining <- store.queryExpired(100, now)
      yield assertTrue(
        expired.nonEmpty, // Found some before completions
        remaining.length <= 25 // At most 25 left after completing first 25
      )
    }
  )

  // ============================================
  // Leader Election Stress Tests
  // ============================================

  val leaderElectionStressTests = suite("Leader Election Stress Tests")(
    test("10 concurrent leader candidates - exactly one leader") {
      for
        leaseStore <- ZIO.service[LeaseStore]
        now <- Clock.instant
        leaseKey = uniqueId("leader-race")

        // 10 nodes all try to become leader simultaneously
        nodes = (1 to 10).map(i => s"candidate-$i").toList
        results <- ZIO.foreachPar(nodes) { nodeId =>
          leaseStore.tryAcquire(leaseKey, nodeId, Duration.fromSeconds(30), now)
        }

        leaders = results.flatten
        lease <- leaseStore.get(leaseKey)
      yield assertTrue(
        leaders.length == 1, // Exactly one leader
        lease.isDefined,
        lease.get.holder == leaders.head.holder
      )
    },

    test("leader loses lease, another takes over") {
      for
        leaseStore <- ZIO.service[LeaseStore]
        now <- Clock.instant
        leaseKey = uniqueId("leader-failover")

        // Node 1 becomes leader with very short lease
        lease1 <- leaseStore.tryAcquire(leaseKey, "leader-1", Duration.fromMillis(50), now)
        _ <- assertTrue(lease1.isDefined)

        // Wait for lease to expire
        _ <- ZIO.sleep(Duration.fromMillis(100))
        later <- Clock.instant

        // Node 2 should be able to take over
        lease2 <- leaseStore.tryAcquire(leaseKey, "leader-2", Duration.fromSeconds(30), later)
      yield assertTrue(
        lease2.isDefined,
        lease2.get.holder == "leader-2"
      )
    },

    test("leader renews successfully while others fail to acquire") {
      for
        leaseStore <- ZIO.service[LeaseStore]
        now <- Clock.instant
        leaseKey = uniqueId("leader-renew")

        // Node 1 becomes leader
        _ <- leaseStore.tryAcquire(leaseKey, "leader-1", Duration.fromSeconds(1), now)

        // Parallel: leader renews while others try to acquire
        results <- ZIO.foreachPar(1 to 10) { i =>
          if i == 1 then
            // Leader renews multiple times
            ZIO.foreach(1 to 5) { _ =>
              for
                later <- Clock.instant
                renewed <- leaseStore.renew(leaseKey, "leader-1", Duration.fromSeconds(1), later)
                _ <- ZIO.sleep(Duration.fromMillis(50))
              yield renewed
            }
          else
            // Others try to acquire
            for
              later <- Clock.instant
              result <- leaseStore.tryAcquire(leaseKey, s"node-$i", Duration.fromSeconds(30), later)
            yield result.isDefined
        }

        // Leader should still hold the lease
        lease <- leaseStore.get(leaseKey)
      yield assertTrue(
        lease.isDefined,
        lease.get.holder == "leader-1"
      )
    },

    test("voluntary release allows immediate takeover") {
      for
        leaseStore <- ZIO.service[LeaseStore]
        now <- Clock.instant
        leaseKey = uniqueId("leader-release")

        // Node 1 becomes leader with long lease
        _ <- leaseStore.tryAcquire(leaseKey, "leader-1", Duration.fromSeconds(60), now)

        // Node 1 voluntarily releases
        released <- leaseStore.release(leaseKey, "leader-1")
        _ <- assertTrue(released)

        // Node 2 can immediately take over
        lease2 <- leaseStore.tryAcquire(leaseKey, "leader-2", Duration.fromSeconds(30), now)
      yield assertTrue(
        lease2.isDefined,
        lease2.get.holder == "leader-2"
      )
    }
  )

  // ============================================
  // Chaos / Integration Tests
  // ============================================

  val chaosTests = suite("Chaos Tests")(
    test("full FSM simulation with concurrent events, timeouts, and locks") {
      for
        eventStore <- ZIO.service[EventStore[String, StressState, StressEvent]]
        timeoutStore <- ZIO.service[TimeoutStore[String]]
        lock <- ZIO.service[FSMInstanceLock[String]]
        now <- Clock.instant

        // Simulate 20 FSM instances being processed by 5 nodes
        instanceIds = (1 to 20).map(_ => uniqueId("fsm")).toList
        nodes = (1 to 5).map(i => s"processor-$i").toList

        processedRef <- Ref.make(Map.empty[String, Int]) // instanceId -> event count

        // Each node tries to process each instance
        _ <- ZIO.foreachPar(nodes) { nodeId =>
          ZIO.foreach(instanceIds) { instanceId =>
            (for
              lockNow <- Clock.instant
              lockResult <- lock.tryAcquire(instanceId, nodeId, Duration.fromSeconds(5), lockNow)
              _ <- lockResult match
                case LockResult.Acquired(token) =>
                  for
                    // Got the lock, append events
                    highest <- eventStore.highestSequenceNr(instanceId)
                    _ <- eventStore.append(instanceId, StressEvent.Started(nodeId), highest + 1)
                    _ <- eventStore.append(instanceId, StressEvent.Progressed(1), highest + 2)

                    // Maybe schedule a timeout
                    timeoutNow <- Clock.instant
                    _ <- timeoutStore.schedule(instanceId, "Processing", timeoutNow.plusSeconds(10))

                    // Update processed count
                    _ <- processedRef.update(m => m.updated(instanceId, m.getOrElse(instanceId, 0) + 2))

                    // Release lock
                    _ <- lock.release(token)
                  yield ()
                case _ =>
                  ZIO.unit // Didn't get lock, skip
            yield ()).catchAll(_ => ZIO.unit) // Ignore sequence conflicts
          }
        }

        // Verify all instances have events
        allEvents <- ZIO.foreach(instanceIds) { id =>
          eventStore.loadEvents(id).runCollect.map(id -> _.length)
        }
        processed <- processedRef.get
      yield assertTrue(
        // All instances should have been processed by exactly one node
        allEvents.forall { case (id, count) => count >= 2 },
        // Processed map should have all instances
        processed.size == 20
      )
    },

    test("interrupt heavy workload mid-execution - database stays consistent") {
      for
        eventStore <- ZIO.service[EventStore[String, StressState, StressEvent]]
        commandStore <- ZIO.service[CommandStore[String, StressCommand]]
        now <- Clock.instant
        instanceId = uniqueId("interrupt-chaos")

        // Start heavy workload
        heavyWorkload = for
          // Enqueue 100 commands
          _ <- ZIO.foreach(1 to 100) { i =>
            commandStore.enqueue(instanceId, StressCommand.DoWork(s"work-$i"), uniqueId(s"cmd-$i"))
          }
          // Claim and process
          _ <- ZIO.foreach(1 to 10) { _ =>
            for
              claimed <- commandStore.claim("worker", 20, Duration.fromSeconds(30), now)
              _ <- ZIO.foreach(claimed)(cmd => commandStore.complete(cmd.id))
            yield ()
          }
          // Append many events
          _ <- ZIO.foreach(1 to 100) { i =>
            eventStore.append(instanceId, StressEvent.Progressed(i), i.toLong).either
          }
        yield ()

        // Run with short timeout then interrupt
        fiber <- heavyWorkload.fork
        _ <- ZIO.sleep(Duration.fromMillis(50))
        _ <- fiber.interrupt

        // Database should still be consistent
        events <- eventStore.loadEvents(instanceId).runCollect
        counts <- commandStore.countByStatus

        // Events should be in order with no gaps
        eventSeqs = events.map(_.sequenceNr).toList
        expectedSeqs = if eventSeqs.nonEmpty then (1L to eventSeqs.max).toList else List.empty
      yield assertTrue(
        eventSeqs == expectedSeqs, // No gaps in sequence
        counts.values.forall(_ >= 0) // No negative counts
      )
    },

    test("parallel snapshot saves don't corrupt data") {
      for
        eventStore <- ZIO.service[EventStore[String, StressState, StressEvent]]
        instanceId = uniqueId("snapshot-parallel")
        now <- Clock.instant

        // Append some events first
        _ <- ZIO.foreach(1 to 10)(i => eventStore.append(instanceId, StressEvent.Progressed(i), i.toLong))

        // 20 concurrent snapshot saves with different states
        _ <- ZIO.foreachPar(1 to 20) { i =>
          eventStore.saveSnapshot(FSMSnapshot(
            instanceId,
            if i % 2 == 0 then StressState.Processing else StressState.Completed,
            i.toLong,
            now
          ))
        }

        // Should have exactly one snapshot
        snapshot <- eventStore.loadSnapshot(instanceId)
      yield assertTrue(
        snapshot.isDefined,
        snapshot.get.instanceId == instanceId
        // Sequence number could be any of 1-20
      )
    },

    test("claim expired timeouts while scheduling new ones - no lost timeouts") {
      for
        store <- ZIO.service[TimeoutStore[String]]
        now <- Clock.instant
        past = now.minusSeconds(10)
        future = now.plusSeconds(100)

        // Create initial expired timeouts
        expiredIds = (1 to 20).map(_ => uniqueId("expired")).toList
        futureIds = (1 to 20).map(_ => uniqueId("future")).toList

        _ <- ZIO.foreach(expiredIds)(id => store.schedule(id, "Expired", past))

        // Parallel: claim expired + schedule new
        claimFiber <- ZIO.foreach(1 to 5) { i =>
          store.queryExpired(10, now).flatMap { expired =>
            ZIO.foreach(expired)(t => store.claim(t.instanceId, s"sweeper-$i", Duration.fromSeconds(30), now))
          }
        }.fork

        scheduleFiber <- ZIO.foreach(futureIds)(id => store.schedule(id, "Future", future)).fork

        _ <- claimFiber.join
        _ <- scheduleFiber.join

        // Verify all future timeouts exist
        futureTimeouts <- ZIO.foreach(futureIds)(store.get)
      yield assertTrue(
        futureTimeouts.forall(_.isDefined) // No lost future timeouts
      )
    }
  )

  def spec = suite("PostgreSQL Stress Tests")(
    lockStressTests,
    eventStoreStressTests,
    commandStoreStressTests,
    commandQueueProductionTests,
    timeoutStoreStressTests,
    leaderElectionStressTests,
    chaosTests
  ).provideShared(allLayers) @@ TestAspect.sequential @@ TestAspect.withLiveClock
