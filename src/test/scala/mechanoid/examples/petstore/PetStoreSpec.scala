package mechanoid.examples.petstore

import zio.*
import zio.test.*
import mechanoid.core.*
import mechanoid.dsl.*
import mechanoid.persistence.*
import mechanoid.persistence.command.*
import java.time.Instant
import scala.collection.mutable

/** Pet Store FSM and Service Tests
  *
  * Tests demonstrating:
  *   - FSM transitions with rich events (ordinal-based matching)
  *   - Entry actions for side effects on state transitions
  *   - Configurable services for deterministic testing
  *   - End-to-end order processing with TestRandom
  */
object PetStoreSpec extends ZIOSpecDefault:

  def spec = suite("Pet Store")(
    fsmSuite,
    serviceSuite,
    endToEndSuite,
  )

  // ============================================
  // FSM Tests
  // ============================================

  def fsmSuite = suite("Order FSM")(
    test("basic state transitions") {
      for
        eventStore <- ZIO.succeed(new InMemoryEventStore[String, OrderState, OrderEvent])
        orderId    = "order-1"
        definition = OrderFSM.definition()
        storeLayer = ZLayer.succeed[EventStore[String, OrderState, OrderEvent]](eventStore)

        finalState <- ZIO.scoped {
          for
            fsm <- PersistentFSMRuntime(orderId, definition, OrderState.Created)
              .provideSomeLayer[Scope](storeLayer)
            _     <- fsm.send(OrderEvent.InitiatePayment(BigDecimal(100), "visa"))
            _     <- fsm.send(OrderEvent.PaymentSucceeded("TXN-123"))
            state <- fsm.currentState
          yield state
        }
      yield assertTrue(finalState == OrderState.Paid)
    },
    test("full order lifecycle") {
      for
        eventStore     <- ZIO.succeed(new InMemoryEventStore[String, OrderState, OrderEvent])
        transitionsRef <- Ref.make(List.empty[String])

        orderId    = "lifecycle-order"
        definition = OrderFSM.definition(
          onPaymentProcessing = transitionsRef.update(_ :+ "PaymentProcessing"),
          onPaid = transitionsRef.update(_ :+ "Paid"),
          onShipped = transitionsRef.update(_ :+ "Shipped"),
        )

        storeLayer = ZLayer.succeed[EventStore[String, OrderState, OrderEvent]](eventStore)

        finalState <- ZIO.scoped {
          for
            fsm <- PersistentFSMRuntime(orderId, definition, OrderState.Created)
              .provideSomeLayer[Scope](storeLayer)
            _     <- fsm.send(OrderEvent.InitiatePayment(BigDecimal(149.99), "Visa ****4242"))
            _     <- fsm.send(OrderEvent.PaymentSucceeded("TXN-ABC123"))
            _     <- fsm.send(OrderEvent.RequestShipping("123 Main St"))
            _     <- fsm.send(OrderEvent.ShipmentDispatched("TRACK-999", "PetExpress", "3-5 days"))
            _     <- fsm.send(OrderEvent.DeliveryConfirmed("2024-01-15T10:30:00Z"))
            state <- fsm.currentState
          yield state
        }

        transitions <- transitionsRef.get
      yield assertTrue(
        finalState == OrderState.Delivered,
        transitions == List("PaymentProcessing", "Paid", "Shipped"),
      )
    },
    test("payment failure cancels order") {
      for
        eventStore <- ZIO.succeed(new InMemoryEventStore[String, OrderState, OrderEvent])
        orderId    = "cancel-order"
        definition = OrderFSM.definition()
        storeLayer = ZLayer.succeed[EventStore[String, OrderState, OrderEvent]](eventStore)

        finalState <- ZIO.scoped {
          for
            fsm <- PersistentFSMRuntime(orderId, definition, OrderState.Created)
              .provideSomeLayer[Scope](storeLayer)
            _     <- fsm.send(OrderEvent.InitiatePayment(BigDecimal(100), "visa"))
            _     <- fsm.send(OrderEvent.PaymentFailed("Card declined"))
            state <- fsm.currentState
          yield state
        }
      yield assertTrue(finalState == OrderState.Cancelled)
    },
    test("event store captures rich event data") {
      for
        eventStore <- ZIO.succeed(new InMemoryEventStore[String, OrderState, OrderEvent])
        orderId    = "rich-events"
        definition = OrderFSM.definition()
        storeLayer = ZLayer.succeed[EventStore[String, OrderState, OrderEvent]](eventStore)

        _ <- ZIO.scoped {
          for
            fsm <- PersistentFSMRuntime(orderId, definition, OrderState.Created)
              .provideSomeLayer[Scope](storeLayer)
            _ <- fsm.send(OrderEvent.InitiatePayment(BigDecimal(299.99), "MasterCard ****1234"))
            _ <- fsm.send(OrderEvent.PaymentSucceeded("TXN-XYZ789"))
          yield ()
        }

        events = eventStore.getEvents(orderId)
      yield assertTrue(
        events.length == 2,
        events.exists(_.event match
          case OrderEvent.InitiatePayment(amount, method) =>
            amount == BigDecimal(299.99) && method == "MasterCard ****1234"
          case _ => false),
        events.exists(_.event match
          case OrderEvent.PaymentSucceeded(txnId) => txnId == "TXN-XYZ789"
          case _                                  => false),
      )
    },
  )

  // ============================================
  // Service Tests
  // ============================================

  def serviceSuite = suite("Services")(
    test("payment processor with 100% success") {
      val processor = PaymentProcessor.make(
        PaymentProcessor.Config(successRate = 1.0, minDelayMs = 1, maxDelayMs = 5)
      )
      for result <- processor.processPayment("Alice", BigDecimal(100), "visa")
      yield assertTrue(result.transactionId.startsWith("TXN-"))
    },
    test("shipper with 100% success") {
      val shipper = Shipper.make(
        Shipper.Config(successRate = 1.0, minDelayMs = 1, maxDelayMs = 5)
      )
      for result <- shipper.requestShipment("Fluffy", "Alice", "123 Main St")
      yield assertTrue(
        result.trackingId.startsWith("TRACK-"),
        result.carrier.nonEmpty,
      )
    },
    test("notification service with 100% success") {
      val notifier = NotificationService.make(
        NotificationService.Config(successRate = 1.0, minDelayMs = 1, maxDelayMs = 5)
      )
      for _ <- notifier.sendNotification("alice@example.com", "order_confirmed")
      yield assertTrue(true)
    },
  ) @@ TestAspect.withLiveClock

  // ============================================
  // End-to-End Test with Deterministic Random
  // ============================================

  /** Test command store for tracking results */
  class TestCommandStore extends CommandStore[String, PetStoreCommand]:
    private val commands         = mutable.Map.empty[Long, PendingCommand[String, PetStoreCommand]]
    private val byIdempotencyKey = mutable.Map.empty[String, Long]
    private val claims           = mutable.Map.empty[Long, (String, Instant)]
    private var nextId           = 1L

    override def enqueue(
        instanceId: String,
        command: PetStoreCommand,
        idempotencyKey: String,
    ): UIO[PendingCommand[String, PetStoreCommand]] =
      Clock.instant.map { now =>
        synchronized {
          byIdempotencyKey.get(idempotencyKey) match
            case Some(existingId) =>
              commands(existingId)
            case None =>
              val id = nextId
              nextId += 1
              val pending = PendingCommand(
                id = id,
                instanceId = instanceId,
                command = command,
                idempotencyKey = idempotencyKey,
                enqueuedAt = now,
                status = CommandStatus.Pending,
                attempts = 0,
                lastAttemptAt = None,
                lastError = None,
                nextRetryAt = None,
              )
              commands(id) = pending
              byIdempotencyKey(idempotencyKey) = id
              pending
        }
      }

    override def claim(
        nodeId: String,
        limit: Int,
        claimDuration: Duration,
        now: Instant,
    ): UIO[List[PendingCommand[String, PetStoreCommand]]] =
      ZIO.succeed {
        synchronized {
          val expiresAt = now.plusMillis(claimDuration.toMillis)
          val toClaim   = commands.values
            .filter { cmd =>
              cmd.status == CommandStatus.Pending &&
              cmd.isReady(now) &&
              !claims.get(cmd.id).exists { case (_, until) => now.isBefore(until) }
            }
            .take(limit)
            .toList

          toClaim.foreach { cmd =>
            claims(cmd.id) = (nodeId, expiresAt)
            commands(cmd.id) = cmd.copy(
              status = CommandStatus.Processing,
              attempts = cmd.attempts + 1,
              lastAttemptAt = Some(now),
            )
          }

          toClaim.map(cmd => commands(cmd.id))
        }
      }

    override def complete(commandId: Long): UIO[Boolean] =
      ZIO.succeed {
        synchronized {
          commands.get(commandId) match
            case Some(cmd) if cmd.status == CommandStatus.Processing =>
              commands(commandId) = cmd.copy(status = CommandStatus.Completed)
              claims.remove(commandId)
              true
            case _ => false
        }
      }

    override def fail(commandId: Long, error: String, retryAt: Option[Instant]): UIO[Boolean] =
      ZIO.succeed {
        synchronized {
          commands.get(commandId) match
            case Some(cmd) if cmd.status == CommandStatus.Processing =>
              val newStatus = if retryAt.isDefined then CommandStatus.Pending else CommandStatus.Failed
              commands(commandId) = cmd.copy(status = newStatus, lastError = Some(error), nextRetryAt = retryAt)
              claims.remove(commandId)
              true
            case _ => false
        }
      }

    override def skip(commandId: Long, reason: String): UIO[Boolean] =
      ZIO.succeed {
        synchronized {
          commands.get(commandId) match
            case Some(cmd) if cmd.status == CommandStatus.Processing =>
              commands(commandId) = cmd.copy(status = CommandStatus.Skipped, lastError = Some(reason))
              claims.remove(commandId)
              true
            case _ => false
        }
      }

    override def getByIdempotencyKey(key: String): UIO[Option[PendingCommand[String, PetStoreCommand]]] =
      ZIO.succeed(synchronized { byIdempotencyKey.get(key).flatMap(commands.get) })

    override def getByInstanceId(instanceId: String): UIO[List[PendingCommand[String, PetStoreCommand]]] =
      ZIO.succeed(synchronized { commands.values.filter(_.instanceId == instanceId).toList.sortBy(_.enqueuedAt) })

    override def countByStatus: UIO[Map[CommandStatus, Long]] =
      ZIO.succeed(synchronized { commands.values.groupBy(_.status).map { case (s, c) => s -> c.size.toLong }.toMap })

    override def releaseExpiredClaims(now: Instant): UIO[Int] =
      ZIO.succeed {
        synchronized {
          val expired = claims.filter { case (_, (_, until)) => !now.isBefore(until) }
          expired.foreach { case (cmdId, _) =>
            commands.get(cmdId).foreach { cmd =>
              if cmd.status == CommandStatus.Processing then commands(cmdId) = cmd.copy(status = CommandStatus.Pending)
            }
            claims.remove(cmdId)
          }
          expired.size
        }
      }

    // Test helpers
    def all: List[PendingCommand[String, PetStoreCommand]] = synchronized { commands.values.toList.sortBy(_.id) }
    def completedCount: Int = synchronized { commands.values.count(_.status == CommandStatus.Completed) }
    def failedCount: Int    = synchronized { commands.values.count(_.status == CommandStatus.Failed) }
  end TestCommandStore

  /** Simple command processor for testing */
  class TestCommandProcessor(
      store: TestCommandStore,
      eventStore: InMemoryEventStore[String, OrderState, OrderEvent],
      paymentProcessor: PaymentProcessor,
      shipper: Shipper,
      notificationService: NotificationService,
      orderDataRef: Ref[Map[String, OrderData]],
      definitionsRef: Ref[Map[String, FSMDefinition[OrderState, OrderEvent, Any, Throwable]]],
  ):
    def processCommand(cmd: PendingCommand[String, PetStoreCommand]): UIO[Unit] =
      val orderId = cmd.instanceId
      cmd.command match
        case PetStoreCommand.ProcessPayment(_, _, customerName, _, amount, method) =>
          processPayment(cmd.id, orderId, amount, method)

        case PetStoreCommand.RequestShipping(_, petName, customerName, address, correlationId) =>
          processShipping(cmd.id, orderId, petName, customerName, address, correlationId)

        case PetStoreCommand.SendNotification(_, email, _, _, notifType, messageId) =>
          processNotification(cmd.id, email, notifType)

        case PetStoreCommand.ShippingCallback(_, tracking, carrier, eta, success, _) =>
          processShippingCallback(cmd.id, orderId, tracking, carrier, eta, success)

        case PetStoreCommand.NotificationCallback(_, _, _) =>
          store.complete(cmd.id).unit
      end match
    end processCommand

    private def processPayment(cmdId: Long, orderId: String, amount: BigDecimal, method: String): UIO[Unit] =
      paymentProcessor.processPayment("customer", amount, method).either.flatMap {
        case Right(result) =>
          store.complete(cmdId) *>
            sendFSMEvent(orderId, OrderEvent.PaymentSucceeded(result.transactionId)).ignore
        case Left(err) =>
          err match
            case _: Retryable =>
              Clock.instant.flatMap(now => store.fail(cmdId, err.toString, Some(now.plusMillis(100)))).unit
            case _ =>
              store.fail(cmdId, err.toString, None) *>
                sendFSMEvent(orderId, OrderEvent.PaymentFailed(err.toString)).ignore
      }

    private def processShipping(
        cmdId: Long,
        orderId: String,
        petName: String,
        customerName: String,
        address: String,
        correlationId: String,
    ): UIO[Unit] =
      shipper.requestShipment(petName, customerName, address).either.flatMap {
        case Right(result) =>
          sendFSMEvent(orderId, OrderEvent.RequestShipping(address)).ignore *>
            // Immediately schedule callback (no delay for test)
            store.enqueue(
              orderId,
              PetStoreCommand.ShippingCallback(
                correlationId,
                result.trackingId,
                result.carrier,
                result.estimatedDelivery,
                true,
                None,
              ),
              s"ship-cb-$correlationId",
            ) *>
            store.complete(cmdId).unit
        case Left(err) =>
          store.fail(cmdId, err.toString, None).unit
      }

    private def processNotification(cmdId: Long, email: String, notifType: String): UIO[Unit] =
      notificationService.sendNotification(email, notifType).either.flatMap {
        case Right(_) => store.complete(cmdId).unit
        case Left(_)  => store.complete(cmdId).unit // Notifications are fire-and-forget
      }

    private def processShippingCallback(
        cmdId: Long,
        orderId: String,
        tracking: String,
        carrier: String,
        eta: String,
        success: Boolean,
    ): UIO[Unit] =
      if success then
        store.complete(cmdId) *>
          sendFSMEvent(orderId, OrderEvent.ShipmentDispatched(tracking, carrier, eta)).ignore
      else store.fail(cmdId, "Shipping failed", None).unit

    private def sendFSMEvent(orderId: String, event: OrderEvent): ZIO[Any, Throwable | MechanoidError, Unit] =
      ZIO.scoped {
        for
          dataOpt <- orderDataRef.get.map(_.get(orderId))
          defOpt  <- definitionsRef.get.map(_.get(orderId))
          _       <- ZIO.when(dataOpt.isDefined && defOpt.isDefined) {
            val storeLayer = ZLayer.succeed[EventStore[String, OrderState, OrderEvent]](eventStore)
            val definition = defOpt.get
            PersistentFSMRuntime(orderId, definition, OrderState.Created)
              .provideSomeLayer[Scope](storeLayer)
              .flatMap(_.send(event))
          }
        yield ()
      }

    def runOnce: UIO[Int] =
      for
        now     <- Clock.instant
        claimed <- store.claim("test-worker", 10, Duration.fromSeconds(30), now)
        _       <- ZIO.foreach(claimed)(processCommand)
      yield claimed.length
  end TestCommandProcessor

  def endToEndSuite = suite("End-to-End Order Processing")(
    test("complete order flow with deterministic random") {
      for
        // Seed TestRandom for deterministic results
        _ <- TestRandom.setSeed(12345L)

        // Create stores
        eventStore     <- ZIO.succeed(new InMemoryEventStore[String, OrderState, OrderEvent])
        commandStore   <- ZIO.succeed(new TestCommandStore)
        orderDataRef   <- Ref.make(Map.empty[String, OrderData])
        definitionsRef <- Ref.make(Map.empty[String, FSMDefinition[OrderState, OrderEvent, Any, Throwable]])

        // Create services with 100% success for deterministic test
        paymentProcessor = PaymentProcessor.make(
          PaymentProcessor.Config(successRate = 1.0, minDelayMs = 1, maxDelayMs = 2)
        )
        shipper             = Shipper.make(Shipper.Config(successRate = 1.0, minDelayMs = 1, maxDelayMs = 2))
        notificationService = NotificationService.make(
          NotificationService.Config(successRate = 1.0, minDelayMs = 1, maxDelayMs = 2)
        )

        // Create processor
        processor = new TestCommandProcessor(
          commandStore,
          eventStore,
          paymentProcessor,
          shipper,
          notificationService,
          orderDataRef,
          definitionsRef,
        )

        // Create an order
        pet       = Pet.catalog.head      // Whiskers the Cat
        customer  = Customer.samples.head // Alice Smith
        orderId   = "ORD-TEST-001"
        orderData = OrderData(orderId, pet, customer, "corr-001", "msg-001")
        _ <- orderDataRef.update(_ + (orderId -> orderData))

        // Create FSM and initiate the order
        storeLayer = ZLayer.succeed[EventStore[String, OrderState, OrderEvent]](eventStore)
        definition = OrderFSM.definition(
          onPaymentProcessing = commandStore
            .enqueue(
              orderId,
              PetStoreCommand.ProcessPayment(orderId, customer.id, customer.name, pet.name, pet.price, "Visa ****4242"),
              s"pay-$orderId",
            )
            .unit,
          onPaid = commandStore
            .enqueue(
              orderId,
              PetStoreCommand.RequestShipping(orderId, pet.name, customer.name, customer.address, "corr-001"),
              s"ship-$orderId",
            )
            .unit *> commandStore
            .enqueue(
              orderId,
              PetStoreCommand
                .SendNotification(orderId, customer.email, customer.name, pet.name, "order_confirmed", "msg-001"),
              s"notif-$orderId",
            )
            .unit,
          onShipped = commandStore
            .enqueue(
              orderId,
              PetStoreCommand
                .SendNotification(orderId, customer.email, customer.name, pet.name, "shipped", "msg-shipped"),
              s"notif-shipped-$orderId",
            )
            .unit,
        )

        // Register the definition for use in sendFSMEvent
        _ <- definitionsRef.update(_ + (orderId -> definition))

        // Start the order (Created -> PaymentProcessing)
        _ <- ZIO.scoped {
          for
            fsm <- PersistentFSMRuntime(orderId, definition, OrderState.Created).provideSomeLayer[Scope](storeLayer)
            _   <- fsm.send(OrderEvent.InitiatePayment(pet.price, "Visa ****4242"))
          yield ()
        }

        // Process commands in rounds until no more pending
        _ <- processor.runOnce.repeatWhile(_ > 0).timeout(Duration.fromSeconds(5))

        // Get final state
        finalState <- ZIO.scoped {
          for
            fsm   <- PersistentFSMRuntime(orderId, definition, OrderState.Created).provideSomeLayer[Scope](storeLayer)
            state <- fsm.currentState
          yield state
        }

        // Get command statistics
        completedCount = commandStore.completedCount
        failedCount    = commandStore.failedCount

        // Verify events in store
        events = eventStore.getEvents(orderId)
      yield assertTrue(
        // Order reached Shipped state
        finalState == OrderState.Shipped,
        // All commands completed (payment, shipping request, shipping callback, 2 notifications)
        completedCount >= 4,
        failedCount == 0,
        // Events were recorded
        events.nonEmpty,
        // Payment event recorded
        events.exists(_.event match
          case OrderEvent.PaymentSucceeded(_) => true
          case _                              => false),
        // Shipping event recorded
        events.exists(_.event match
          case OrderEvent.ShipmentDispatched(_, _, _) => true
          case _                                      => false),
      )
    },
    test("multiple orders processed concurrently") {
      for
        _ <- TestRandom.setSeed(67890L)

        eventStore     <- ZIO.succeed(new InMemoryEventStore[String, OrderState, OrderEvent])
        commandStore   <- ZIO.succeed(new TestCommandStore)
        orderDataRef   <- Ref.make(Map.empty[String, OrderData])
        definitionsRef <- Ref.make(Map.empty[String, FSMDefinition[OrderState, OrderEvent, Any, Throwable]])

        paymentProcessor = PaymentProcessor.make(
          PaymentProcessor.Config(successRate = 1.0, minDelayMs = 1, maxDelayMs = 2)
        )
        shipper             = Shipper.make(Shipper.Config(successRate = 1.0, minDelayMs = 1, maxDelayMs = 2))
        notificationService = NotificationService.make(
          NotificationService.Config(successRate = 1.0, minDelayMs = 1, maxDelayMs = 2)
        )

        processor = new TestCommandProcessor(
          commandStore,
          eventStore,
          paymentProcessor,
          shipper,
          notificationService,
          orderDataRef,
          definitionsRef,
        )
        storeLayer = ZLayer.succeed[EventStore[String, OrderState, OrderEvent]](eventStore)

        // Create 3 orders
        orderIds = List("ORD-001", "ORD-002", "ORD-003")
        _ <- ZIO.foreach(orderIds.zipWithIndex) { case (orderId, idx) =>
          val pet       = Pet.catalog(idx % Pet.catalog.length)
          val customer  = Customer.samples(idx % Customer.samples.length)
          val orderData = OrderData(orderId, pet, customer, s"corr-$idx", s"msg-$idx")

          val definition = OrderFSM.definition(
            onPaymentProcessing = commandStore
              .enqueue(
                orderId,
                PetStoreCommand.ProcessPayment(orderId, customer.id, customer.name, pet.name, pet.price, "visa"),
                s"pay-$orderId",
              )
              .unit,
            onPaid = commandStore
              .enqueue(
                orderId,
                PetStoreCommand.RequestShipping(orderId, pet.name, customer.name, customer.address, s"corr-$idx"),
                s"ship-$orderId",
              )
              .unit,
          )

          orderDataRef.update(_ + (orderId -> orderData)) *>
            definitionsRef.update(_ + (orderId -> definition)) *>
            ZIO.scoped {
              for
                fsm <- PersistentFSMRuntime(orderId, definition, OrderState.Created).provideSomeLayer[Scope](storeLayer)
                _   <- fsm.send(OrderEvent.InitiatePayment(pet.price, "visa"))
              yield ()
            }
        }

        // Process all commands
        _ <- processor.runOnce.repeatWhile(_ > 0).timeout(Duration.fromSeconds(5))

        // Check final states
        finalStates <- ZIO.foreach(orderIds) { orderId =>
          definitionsRef.get.flatMap { defs =>
            ZIO.scoped {
              val definition = defs(orderId)
              for
                fsm <- PersistentFSMRuntime(orderId, definition, OrderState.Created).provideSomeLayer[Scope](storeLayer)
                state <- fsm.currentState
              yield orderId -> state
            }
          }
        }

        stateMap = finalStates.toMap
      yield assertTrue(
        // All orders reached Shipped state
        stateMap.values.forall(_ == OrderState.Shipped),
        // All 3 orders processed
        stateMap.size == 3,
      )
    },
    test("payment failure results in cancelled order") {
      for
        _ <- TestRandom.setSeed(11111L)

        eventStore     <- ZIO.succeed(new InMemoryEventStore[String, OrderState, OrderEvent])
        commandStore   <- ZIO.succeed(new TestCommandStore)
        orderDataRef   <- Ref.make(Map.empty[String, OrderData])
        definitionsRef <- Ref.make(Map.empty[String, FSMDefinition[OrderState, OrderEvent, Any, Throwable]])

        // Payment processor that always fails (0% success)
        paymentProcessor = PaymentProcessor.make(
          PaymentProcessor.Config(successRate = 0.0, minDelayMs = 1, maxDelayMs = 2)
        )
        shipper             = Shipper.make(Shipper.Config(successRate = 1.0, minDelayMs = 1, maxDelayMs = 2))
        notificationService = NotificationService.make(
          NotificationService.Config(successRate = 1.0, minDelayMs = 1, maxDelayMs = 2)
        )

        processor = new TestCommandProcessor(
          commandStore,
          eventStore,
          paymentProcessor,
          shipper,
          notificationService,
          orderDataRef,
          definitionsRef,
        )
        storeLayer = ZLayer.succeed[EventStore[String, OrderState, OrderEvent]](eventStore)

        orderId   = "ORD-FAIL-001"
        pet       = Pet.catalog.head
        customer  = Customer.samples.head
        orderData = OrderData(orderId, pet, customer, "corr-fail", "msg-fail")
        _ <- orderDataRef.update(_ + (orderId -> orderData))

        definition = OrderFSM.definition(
          onPaymentProcessing = commandStore
            .enqueue(
              orderId,
              PetStoreCommand.ProcessPayment(orderId, customer.id, customer.name, pet.name, pet.price, "visa"),
              s"pay-$orderId",
            )
            .unit
        )

        _ <- definitionsRef.update(_ + (orderId -> definition))

        _ <- ZIO.scoped {
          for
            fsm <- PersistentFSMRuntime(orderId, definition, OrderState.Created).provideSomeLayer[Scope](storeLayer)
            _   <- fsm.send(OrderEvent.InitiatePayment(pet.price, "visa"))
          yield ()
        }

        // Process commands (payment will fail)
        _ <- processor.runOnce.repeatWhile(_ > 0).timeout(Duration.fromSeconds(5))

        finalState <- ZIO.scoped {
          for
            fsm   <- PersistentFSMRuntime(orderId, definition, OrderState.Created).provideSomeLayer[Scope](storeLayer)
            state <- fsm.currentState
          yield state
        }

        failedCount = commandStore.failedCount
      yield assertTrue(
        finalState == OrderState.Cancelled,
        failedCount >= 1,
      )
    },
  ) @@ TestAspect.withLiveClock

end PetStoreSpec
