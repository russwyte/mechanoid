package mechanoid.examples

import zio.*
import zio.Console.{printLine, readLine}
import mechanoid.machine.*
import mechanoid.persistence.*
import mechanoid.runtime.FSMRuntime
import mechanoid.runtime.timeout.{TimeoutStrategy, FiberTimeoutStrategy}
import mechanoid.runtime.locking.LockingStrategy
import mechanoid.persistence.command.*
import mechanoid.stores.{InMemoryEventStore, InMemoryCommandStore}
import mechanoid.examples.petstore.*

/** PetStore Command Pattern Demo
  *
  * An interactive demo that clearly shows the transactional outbox pattern: FSM → CommandQueue → Worker
  *
  * Each step pauses for user input to clearly demonstrate the decoupled flow.
  *
  * Run with: sbt "examples/runMain mechanoid.examples.PetStoreCommandApp"
  */
object PetStoreCommandApp extends ZIOAppDefault:

  // ANSI formatting
  val Reset  = "\u001b[0m"
  val Bold   = "\u001b[1m"
  val Dim    = "\u001b[2m"
  val Green  = "\u001b[32m"
  val Yellow = "\u001b[33m"
  val Blue   = "\u001b[34m"
  val Cyan   = "\u001b[36m"
  val Box    = "═" * 60

  def header(title: String): UIO[Unit] =
    printLine(s"\n$Bold$Blue$Box$Reset\n$Bold  $title$Reset\n$Bold$Blue$Box$Reset\n").orDie

  def section(title: String): UIO[Unit] =
    printLine(s"\n$Bold$Yellow▶ $title$Reset").orDie

  def fsm(msg: String): UIO[Unit] =
    printLine(s"  $Cyan[FSM]$Reset $msg").orDie

  def queue(msg: String): UIO[Unit] =
    printLine(s"  $Yellow[QUEUE]$Reset $msg").orDie

  def worker(msg: String): UIO[Unit] =
    printLine(s"  $Green[WORKER]$Reset $msg").orDie

  def info(msg: String): UIO[Unit] =
    printLine(s"  $Dim$msg$Reset").orDie

  def pause(msg: String = "Press Enter to continue..."): UIO[Unit] =
    printLine(s"\n  $Dim$msg$Reset").orDie *> readLine.orDie.unit

  def showQueueStatus(store: InMemoryCommandStore[Int, PetStoreCommand]): UIO[Unit] =
    for
      status <- store.countByStatus
      pending    = status.getOrElse(CommandStatus.Pending, 0L)
      processing = status.getOrElse(CommandStatus.Processing, 0L)
      completed  = status.getOrElse(CommandStatus.Completed, 0L)
      failed     = status.getOrElse(CommandStatus.Failed, 0L)
      _ <- queue(
        s"Status: ${Yellow}Pending=$pending$Reset | Processing=$processing | ${Green}Completed=$completed$Reset | Failed=$failed"
      )
    yield ()

  // Command executor - simulates external services
  def executeCommand(cmd: PetStoreCommand): ZIO[Any, Nothing, (String, Option[String])] =
    import PetStoreCommand.*
    cmd match
      case ProcessPayment(orderId, _, _, petName, amount, _) =>
        for
          _ <- worker(s"Processing payment for $petName: $$$amount")
          _ <- ZIO.sleep(200.millis)
          txnId = s"TXN-${scala.util.Random.nextInt(100000)}"
          _ <- worker(s"${Green}Payment successful!$Reset Transaction: $Bold$txnId$Reset")
        yield ("PaymentSucceeded", Some(txnId))

      case RequestShipping(_, petName, _, _, _) =>
        for
          _ <- worker(s"Requesting shipment of $petName")
          _ <- ZIO.sleep(200.millis)
          trackingId = s"TRACK-${scala.util.Random.nextInt(100000)}"
          _ <- worker(s"${Green}Shipment dispatched!$Reset Tracking: $Bold$trackingId$Reset")
        yield ("ShipmentDispatched", Some(trackingId))

      case SendNotification(orderId, _, customerName, petName, notifType, _) =>
        for
          _ <- worker(s"Sending '$notifType' notification to $customerName")
          _ <- ZIO.sleep(100.millis)
          _ <- worker(s"${Green}Notification sent!$Reset")
        yield ("NotificationSent", None)

      case ShippingCallback(_, trackingNumber, _, _, _, _) =>
        for
          _ <- worker(s"Processing shipping callback for $trackingNumber")
          _ <- ZIO.sleep(100.millis)
        yield ("CallbackProcessed", None)

      case NotificationCallback(messageId, delivered, _) =>
        for
          _ <- worker(s"Processing notification callback")
          _ <- ZIO.sleep(50.millis)
        yield ("CallbackProcessed", None)
    end match
  end executeCommand

  // Process a single pending command
  def processOneCommand(
      store: InMemoryCommandStore[Int, PetStoreCommand]
  ): ZIO[Any, Nothing, Option[(Long, String, Option[String])]] =
    for
      now     <- Clock.instant
      claimed <- store.claim("worker-1", 1, 30.seconds, now)
      result  <- claimed.headOption match
        case Some(cmd) =>
          for
            _      <- worker(s"Claimed: ${Bold}${cmd.command.getClass.getSimpleName}$Reset (id=${cmd.id})")
            result <- executeCommand(cmd.command)
            _      <- store.complete(cmd.id)
          yield Some((cmd.id, result._1, result._2))
        case None =>
          ZIO.succeed(None)
    yield result

  // Process all pending commands
  def processAllPending(
      store: InMemoryCommandStore[Int, PetStoreCommand]
  ): ZIO[Any, Nothing, Int] =
    def loop(count: Int): ZIO[Any, Nothing, Int] =
      processOneCommand(store).flatMap {
        case Some(_) => loop(count + 1)
        case None    => ZIO.succeed(count)
      }
    loop(0)

  def run: ZIO[Any, Any, Unit] =
    val program = ZIO.scoped {
      for
        _ <- header("PetStore Command Pattern Demo")
        _ <- info("This demo shows the transactional outbox pattern:")
        _ <- info("  1. FSM transitions trigger entry actions")
        _ <- info("  2. Entry actions enqueue commands (atomic with state)")
        _ <- info("  3. Workers claim and execute commands asynchronously")
        _ <- info("  4. Results flow back to FSM as new events")

        // Create stores
        eventStore      <- InMemoryEventStore.make[Int, OrderState, OrderEvent]
        commandStore    <- InMemoryCommandStore.make[Int, PetStoreCommand]
        timeoutStrategy <- FiberTimeoutStrategy.make[Int]

        // Create FSM with entry actions that enqueue commands
        orderId      = 1
        storeLayer   = ZLayer.succeed[EventStore[Int, OrderState, OrderEvent]](eventStore)
        timeoutLayer = ZLayer.succeed[TimeoutStrategy[Int]](timeoutStrategy)

        // Build machine with entry actions that enqueue commands
        machine <- ZIO.succeed {
          OrderFSM.machine
            .withEntry(OrderState.PaymentProcessing)(
              fsm(s"Entering ${Yellow}PaymentProcessing$Reset") *>
                commandStore.enqueue(
                  orderId,
                  PetStoreCommand.ProcessPayment(orderId, "cust-1", "Alice", "Buddy", BigDecimal(99.99), "Visa"),
                  s"pay-$orderId",
                ) *>
                queue(s"Enqueued: ${Bold}ProcessPayment$Reset (orderId=$orderId, amount=$$99.99)")
            )
            .withEntry(OrderState.Paid)(
              fsm(s"Entering ${Green}Paid$Reset") *>
                commandStore.enqueue(
                  orderId,
                  PetStoreCommand.RequestShipping(orderId, "Buddy", "Alice", "123 Main St", "corr-1"),
                  s"ship-$orderId",
                ) *>
                queue(s"Enqueued: ${Bold}RequestShipping$Reset") *>
                commandStore.enqueue(
                  orderId,
                  PetStoreCommand
                    .SendNotification(orderId, "alice@example.com", "Alice", "Buddy", "order_confirmed", "msg-1"),
                  s"notif-$orderId",
                ) *>
                queue(s"Enqueued: ${Bold}SendNotification$Reset (order_confirmed)")
            )
            .withEntry(OrderState.Shipped)(
              fsm(s"Entering ${Green}Shipped$Reset") *>
                commandStore.enqueue(
                  orderId,
                  PetStoreCommand.SendNotification(orderId, "alice@example.com", "Alice", "Buddy", "shipped", "msg-2"),
                  s"notif-shipped-$orderId",
                ) *>
                queue(s"Enqueued: ${Bold}SendNotification$Reset (shipped)")
            )
        }

        fsmRuntime <- FSMRuntime(orderId, machine, OrderState.Created)
          .provideSome[Scope](storeLayer ++ timeoutLayer ++ LockingStrategy.optimistic[Int])

        // ═══════════════════════════════════════════════════════════
        // Phase 1: Initial State
        // ═══════════════════════════════════════════════════════════
        _ <- section("Phase 1: Initial State")
        _ <- fsm(s"Order-$orderId created in state: ${Cyan}Created$Reset")
        _ <- showQueueStatus(commandStore)
        _ <- pause()

        // ═══════════════════════════════════════════════════════════
        // Phase 2: Initiate Payment (FSM transition + command enqueue)
        // ═══════════════════════════════════════════════════════════
        _ <- section("Phase 2: Initiate Payment")
        _ <- fsm(s"Sending event: ${Yellow}InitiatePayment($$99.99, Visa)$Reset")
        _ <- fsmRuntime.send(
          OrderEvent.InitiatePayment(
            orderId = orderId,
            customerId = "cust-001",
            customerName = "Alice",
            customerEmail = "alice@example.com",
            customerAddress = "123 Main St",
            petName = "Buddy the Golden Retriever",
            amount = BigDecimal(99.99),
            paymentMethod = "Visa",
            correlationId = s"corr-$orderId",
            messageId = s"msg-$orderId-init",
          )
        )
        state1 <- fsmRuntime.currentState
        _      <- fsm(s"Transitioned: ${Cyan}Created$Reset → ${Yellow}$state1$Reset")
        _      <- showQueueStatus(commandStore)
        _      <- pause("Press Enter to have worker process the payment command...")

        // ═══════════════════════════════════════════════════════════
        // Phase 3: Worker processes payment
        // ═══════════════════════════════════════════════════════════
        _       <- section("Phase 3: Worker Claims & Executes")
        result1 <- processOneCommand(commandStore)
        _       <- showQueueStatus(commandStore)
        txnId = result1.flatMap(_._3).getOrElse("TXN-UNKNOWN")
        _ <- pause("Press Enter to send payment result back to FSM...")

        // ═══════════════════════════════════════════════════════════
        // Phase 4: Payment success event (triggers more commands)
        // ═══════════════════════════════════════════════════════════
        _ <- section("Phase 4: Payment Result → FSM")
        _ <- fsm(s"Sending event: ${Green}PaymentSucceeded($txnId)$Reset")
        _ <- fsmRuntime.send(
          OrderEvent.PaymentSucceeded(
            orderId = orderId,
            transactionId = txnId,
            customerId = "cust-001",
            customerName = "Alice",
            customerEmail = "alice@example.com",
            customerAddress = "123 Main St",
            petName = "Buddy the Golden Retriever",
            correlationId = s"corr-$orderId",
            messageId = s"msg-$orderId-paid",
          )
        )
        state2 <- fsmRuntime.currentState
        _      <- fsm(s"Transitioned: ${Yellow}PaymentProcessing$Reset → ${Green}$state2$Reset")
        _      <- showQueueStatus(commandStore)
        _      <- pause("Press Enter to process shipping and notification commands...")

        // ═══════════════════════════════════════════════════════════
        // Phase 5: Worker processes shipping + notification
        // ═══════════════════════════════════════════════════════════
        _     <- section("Phase 5: Worker Processes Commands")
        count <- processAllPending(commandStore)
        _     <- info(s"Processed $count command(s)")
        _     <- showQueueStatus(commandStore)
        _     <- pause("Press Enter to simulate shipment dispatched...")

        // ═══════════════════════════════════════════════════════════
        // Phase 6: Request Shipping (FSM transition Paid → ShippingRequested)
        // ═══════════════════════════════════════════════════════════
        _      <- section("Phase 6: Request Shipping → FSM")
        _      <- fsm(s"Sending event: ${Yellow}RequestShipping(123 Main St)$Reset")
        _      <- fsmRuntime.send(OrderEvent.RequestShipping(orderId, "123 Main St"))
        state3 <- fsmRuntime.currentState
        _      <- fsm(s"Transitioned: Paid → ${Yellow}$state3$Reset")
        _      <- showQueueStatus(commandStore)
        _      <- pause("Press Enter to simulate shipment dispatched...")

        // ═══════════════════════════════════════════════════════════
        // Phase 7: Shipment dispatched event
        // ═══════════════════════════════════════════════════════════
        _ <- section("Phase 7: Shipment Dispatched → FSM")
        trackingId = "TRACK-12345"
        _ <- fsm(s"Sending event: ${Green}ShipmentDispatched($trackingId)$Reset")
        _ <- fsmRuntime.send(
          OrderEvent.ShipmentDispatched(
            orderId = orderId,
            trackingId = trackingId,
            carrier = "FastShip",
            eta = "2 days",
            customerEmail = "alice@example.com",
            customerName = "Alice",
            petName = "Buddy the Golden Retriever",
            messageId = s"msg-$orderId-shipped",
          )
        )
        state4 <- fsmRuntime.currentState
        _      <- fsm(s"Transitioned: ShippingRequested → ${Green}$state4$Reset")
        _      <- showQueueStatus(commandStore)
        _      <- pause("Press Enter to process final notification...")

        // ═══════════════════════════════════════════════════════════
        // Phase 8: Final notification
        // ═══════════════════════════════════════════════════════════
        _ <- section("Phase 8: Final Processing")
        _ <- processAllPending(commandStore)
        _ <- showQueueStatus(commandStore)
        _ <- pause("Press Enter for summary...")

        // ═══════════════════════════════════════════════════════════
        // Summary
        // ═══════════════════════════════════════════════════════════
        _          <- header("Summary")
        finalState <- fsmRuntime.currentState
        status     <- commandStore.countByStatus
        _          <- fsm(s"Final FSM State: ${Green}$Bold$finalState$Reset")
        _          <- queue(
          s"Commands: ${Green}${status.getOrElse(CommandStatus.Completed, 0L)} completed$Reset, ${status.getOrElse(CommandStatus.Failed, 0L)} failed"
        )
        _ <- printLine("").orDie
        _ <- info("The command pattern ensures:")
        _ <- info("  ✓ Commands are persisted atomically with state changes")
        _ <- info("  ✓ Idempotency keys prevent duplicate execution")
        _ <- info("  ✓ Workers can crash and recover without losing commands")
        _ <- info("  ✓ External systems are called exactly once")
        _ <- printLine("").orDie
      yield ()
    }

    program
  end run

end PetStoreCommandApp
