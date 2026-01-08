package mechanoid.examples

import zio.*
import zio.json.*
import zio.Console.printLine
import mechanoid.persistence.command.*
import java.util.UUID
import java.time.Instant
import java.time.format.DateTimeFormatter
import scala.annotation.unused

/** Pet Store Console Application
  *
  * An interactive demo showing durable command processing with:
  *   - Visual console output with colors
  *   - Live worker status updates
  *   - Simulated service integrations (payment, shipping, notifications)
  *
  * Run with: sbt "runMain mechanoid.examples.PetStoreApp"
  */
object PetStoreApp extends ZIOAppDefault:

  // ============================================
  // ANSI Colors for Console Output
  // ============================================

  object Colors:
    val Reset = "\u001b[0m"
    val Bold  = "\u001b[1m"
    val Dim   = "\u001b[2m"

    val Red     = "\u001b[31m"
    val Green   = "\u001b[32m"
    val Yellow  = "\u001b[33m"
    val Blue    = "\u001b[34m"
    val Magenta = "\u001b[35m"
    val Cyan    = "\u001b[36m"
    val White   = "\u001b[37m"

    val BgRed     = "\u001b[41m"
    val BgGreen   = "\u001b[42m"
    val BgYellow  = "\u001b[43m"
    val BgBlue    = "\u001b[44m"
    val BgMagenta = "\u001b[45m"
    val BgCyan    = "\u001b[46m"

    def success(s: String)   = s"$Green$s$Reset"
    def error(s: String)     = s"$Red$s$Reset"
    def warning(s: String)   = s"$Yellow$s$Reset"
    def info(s: String)      = s"$Cyan$s$Reset"
    def highlight(s: String) = s"$Bold$Magenta$s$Reset"
    def dim(s: String)       = s"$Dim$s$Reset"
    def header(s: String)    = s"$Bold$Blue$s$Reset"
  end Colors

  import Colors.*

  // ============================================
  // Domain Types
  // ============================================

  case class Pet(id: String, name: String, species: String, price: BigDecimal)
  object Pet:
    given JsonCodec[Pet] = DeriveJsonCodec.gen[Pet]

    val catalog = List(
      Pet("pet-1", "Whiskers", "Cat", BigDecimal(150.00)),
      Pet("pet-2", "Buddy", "Dog", BigDecimal(250.00)),
      Pet("pet-3", "Goldie", "Fish", BigDecimal(25.00)),
      Pet("pet-4", "Tweety", "Bird", BigDecimal(75.00)),
      Pet("pet-5", "Hoppy", "Rabbit", BigDecimal(100.00)),
    )
  end Pet

  case class Customer(id: String, name: String, email: String, address: String)
  object Customer:
    given JsonCodec[Customer] = DeriveJsonCodec.gen[Customer]

    val samples = List(
      Customer("cust-1", "Alice Smith", "alice@example.com", "123 Main St, Springfield"),
      Customer("cust-2", "Bob Jones", "bob@example.com", "456 Oak Ave, Riverside"),
      Customer("cust-3", "Carol White", "carol@example.com", "789 Pine Rd, Lakewood"),
    )

  // ============================================
  // Commands
  // ============================================

  enum PetStoreCommand derives JsonCodec:
    case ProcessPayment(
        orderId: String,
        customerId: String,
        customerName: String,
        petName: String,
        amount: BigDecimal,
        paymentMethod: String,
    )
    case RequestShipping(
        orderId: String,
        petName: String,
        customerName: String,
        customerAddress: String,
        correlationId: String,
    )
    case SendNotification(
        orderId: String,
        customerEmail: String,
        customerName: String,
        petName: String,
        notificationType: String,
        messageId: String,
    )
    case ShippingCallback(
        correlationId: String,
        trackingNumber: String,
        carrier: String,
        estimatedDelivery: String,
        success: Boolean,
        error: Option[String],
    )
    case NotificationCallback(messageId: String, delivered: Boolean, error: Option[String])
  end PetStoreCommand

  // ============================================
  // In-Memory Command Store (simplified for demo)
  // ============================================

  // Internal command record with claim tracking
  case class CommandRecord(
      cmd: PendingCommand[String, PetStoreCommand],
      claimedBy: Option[String],
      claimedUntil: Option[Instant],
  )

  class InMemoryCommandStore private (
      commands: Ref[Map[Long, CommandRecord]],
      idCounter: Ref[Long],
      idempotencyIndex: Ref[Map[String, Long]],
  ) extends CommandStore[String, PetStoreCommand]:

    override def enqueue(
        instanceId: String,
        command: PetStoreCommand,
        idempotencyKey: String,
    ): UIO[PendingCommand[String, PetStoreCommand]] =
      for
        existingId <- idempotencyIndex.get.map(_.get(idempotencyKey))
        result     <- existingId match
          case Some(id) => commands.get.map(_(id).cmd)
          case None     =>
            for
              id  <- idCounter.updateAndGet(_ + 1)
              now <- Clock.instant
              cmd = PendingCommand(
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
              record = CommandRecord(cmd, None, None)
              _ <- commands.update(_ + (id -> record))
              _ <- idempotencyIndex.update(_ + (idempotencyKey -> id))
            yield cmd
      yield result

    override def claim(
        workerId: String,
        limit: Int,
        claimDuration: Duration,
        now: Instant,
    ): UIO[List[PendingCommand[String, PetStoreCommand]]] =
      commands.modify { cmds =>
        val claimUntil = now.plusMillis(claimDuration.toMillis)
        val pending    = cmds.values
          .filter { record =>
            record.cmd.status == CommandStatus.Pending &&
            record.claimedBy.isEmpty &&
            record.cmd.nextRetryAt.forall(_.isBefore(now))
          }
          .take(limit)
          .toList

        val updated = pending.foldLeft(cmds) { (acc, record) =>
          val newCmd = record.cmd.copy(
            status = CommandStatus.Processing,
            attempts = record.cmd.attempts + 1,
            lastAttemptAt = Some(now),
          )
          acc + (record.cmd.id -> CommandRecord(newCmd, Some(workerId), Some(claimUntil)))
        }

        val claimedCmds = pending.map { record =>
          record.cmd.copy(
            status = CommandStatus.Processing,
            attempts = record.cmd.attempts + 1,
            lastAttemptAt = Some(now),
          )
        }

        (claimedCmds, updated)
      }

    override def complete(commandId: Long): UIO[Boolean] =
      commands.modify { cmds =>
        cmds.get(commandId) match
          case Some(record) =>
            val newCmd = record.cmd.copy(status = CommandStatus.Completed)
            (true, cmds + (commandId -> CommandRecord(newCmd, None, None)))
          case None => (false, cmds)
      }

    override def fail(commandId: Long, error: String, retryAt: Option[Instant]): UIO[Boolean] =
      commands.modify { cmds =>
        cmds.get(commandId) match
          case Some(record) =>
            val newStatus = if retryAt.isDefined then CommandStatus.Pending else CommandStatus.Failed
            val newCmd    = record.cmd.copy(
              status = newStatus,
              lastError = Some(error),
              nextRetryAt = retryAt,
            )
            (true, cmds + (commandId -> CommandRecord(newCmd, None, None)))
          case None => (false, cmds)
      }

    override def skip(commandId: Long, reason: String): UIO[Boolean] =
      commands.modify { cmds =>
        cmds.get(commandId) match
          case Some(record) =>
            val newCmd = record.cmd.copy(status = CommandStatus.Skipped, lastError = Some(reason))
            (true, cmds + (commandId -> CommandRecord(newCmd, None, None)))
          case None => (false, cmds)
      }

    override def releaseExpiredClaims(now: Instant): UIO[Int] =
      commands.modify { cmds =>
        val expired = cmds.values.filter { record =>
          record.claimedUntil.exists(_.isBefore(now))
        }.toList

        val updated = expired.foldLeft(cmds) { (acc, record) =>
          val newCmd = record.cmd.copy(status = CommandStatus.Pending)
          acc + (record.cmd.id -> CommandRecord(newCmd, None, None))
        }

        (expired.length, updated)
      }

    override def getByIdempotencyKey(idempotencyKey: String): UIO[Option[PendingCommand[String, PetStoreCommand]]] =
      for
        maybeId <- idempotencyIndex.get.map(_.get(idempotencyKey))
        result  <- maybeId match
          case Some(id) => commands.get.map(cmds => cmds.get(id).map(_.cmd))
          case None     => ZIO.succeed(None)
      yield result

    override def getByInstanceId(instanceId: String): UIO[List[PendingCommand[String, PetStoreCommand]]] =
      commands.get.map(_.values.filter(_.cmd.instanceId == instanceId).map(_.cmd).toList)

    override def countByStatus: UIO[Map[CommandStatus, Long]] =
      commands.get.map(_.values.map(_.cmd).groupBy(_.status).view.mapValues(_.size.toLong).toMap)

    def getAllCommands: UIO[List[PendingCommand[String, PetStoreCommand]]] =
      commands.get.map(_.values.map(_.cmd).toList.sortBy(_.id))
  end InMemoryCommandStore

  object InMemoryCommandStore:
    def make: UIO[InMemoryCommandStore] =
      for
        commands         <- Ref.make(Map.empty[Long, CommandRecord])
        idCounter        <- Ref.make(0L)
        idempotencyIndex <- Ref.make(Map.empty[String, Long])
      yield new InMemoryCommandStore(commands, idCounter, idempotencyIndex)

  // ============================================
  // Console Logger
  // ============================================

  class ConsoleLogger:
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    def timestamp: UIO[String] =
      Clock.instant.map(i => timeFormatter.format(i.atZone(java.time.ZoneId.systemDefault())))

    def log(prefix: String, color: String, message: String): UIO[Unit] =
      for
        ts <- timestamp
        _  <- printLine(s"${dim(ts)} $color$prefix$Reset $message").orDie
      yield ()

    def payment(msg: String)      = log("[PAYMENT]", BgGreen + Bold, msg)
    def shipping(msg: String)     = log("[SHIPPING]", BgBlue + Bold, msg)
    def notification(msg: String) = log("[NOTIFY]", BgMagenta + Bold, msg)
    def callback(msg: String)     = log("[CALLBACK]", BgYellow + Bold, msg)
    def worker(msg: String)       = log("[WORKER]", BgCyan + Bold, msg)
    def system(msg: String)       = log("[SYSTEM]", Bold, msg)
    def error(msg: String)        = log("[ERROR]", BgRed + Bold, msg)
  end ConsoleLogger

  // ============================================
  // Command Processor
  // ============================================

  class CommandProcessor(
      store: InMemoryCommandStore,
      logger: ConsoleLogger,
      random: scala.util.Random,
  ):
    val workerId = s"worker-${UUID.randomUUID().toString.take(8)}"

    def processCommand(cmd: PendingCommand[String, PetStoreCommand]): ZIO[Any, Nothing, Unit] =
      cmd.command match
        case PetStoreCommand.ProcessPayment(orderId, customerId, customerName, petName, amount, method) =>
          processPayment(cmd.id, orderId, customerName, petName, amount, method)

        case PetStoreCommand.RequestShipping(orderId, petName, customerName, address, correlationId) =>
          processShipping(cmd.id, orderId, petName, customerName, address, correlationId)

        case PetStoreCommand.SendNotification(orderId, email, customerName, petName, notifType, messageId) =>
          processNotification(cmd.id, orderId, email, customerName, petName, notifType, messageId)

        case PetStoreCommand.ShippingCallback(correlationId, tracking, carrier, eta, succeeded, err) =>
          processShippingCallback(cmd.id, correlationId, tracking, carrier, eta, succeeded, err)

        case PetStoreCommand.NotificationCallback(messageId, delivered, err) =>
          processNotificationCallback(cmd.id, messageId, delivered, err)

    private def processPayment(
        cmdId: Long,
        @unused orderId: String,
        customerName: String,
        @unused petName: String,
        amount: BigDecimal,
        method: String,
    ): UIO[Unit] =
      for
        _ <- logger.payment(s"Processing payment for ${highlight(customerName)}: ${info(s"$$${amount}")} via $method")
        _ <- ZIO.sleep(Duration.fromMillis(500 + random.nextInt(1000))) // Simulate API call

        paymentSucceeded = random.nextDouble() < 0.85 // 85% success rate
        _ <-
          if paymentSucceeded then
            val txnId = s"TXN-${random.nextInt(999999)}"
            logger.payment(success(s"Payment approved! Transaction: $txnId")) *>
              store.complete(cmdId)
          else
            val errors = List("Card declined", "Insufficient funds", "Network timeout", "Fraud check failed")
            val err    = errors(random.nextInt(errors.length))
            logger.payment(error(s"Payment failed: $err")) *>
              (if err == "Network timeout" then
                 Clock.instant.flatMap(now => store.fail(cmdId, err, Some(now.plusSeconds(3))))
               else store.fail(cmdId, err, None))
      yield ()

    private def processShipping(
        cmdId: Long,
        orderId: String,
        petName: String,
        customerName: String,
        address: String,
        correlationId: String,
    ): UIO[Unit] =
      for
        _ <- logger.shipping(s"Requesting shipment of ${highlight(petName)} to ${info(customerName)}")
        _ <- logger.shipping(dim(s"  Address: $address"))
        _ <- ZIO.sleep(Duration.fromMillis(300 + random.nextInt(500)))

        trackingId = s"TRACK-${random.nextInt(999999)}"
        _ <- logger.shipping(success(s"Shipment request accepted: $trackingId"))
        _ <- logger.shipping(dim(s"  Webhook callback scheduled..."))

        // Schedule callback
        _ <- (for
          _ <- ZIO.sleep(Duration.fromMillis(2000 + random.nextInt(3000)))
          callback = PetStoreCommand.ShippingCallback(
            correlationId = correlationId,
            trackingNumber = trackingId,
            carrier = List("PetExpress", "AnimalCare Logistics", "FurryFriends Delivery")(random.nextInt(3)),
            estimatedDelivery = s"${2 + random.nextInt(5)} business days",
            success = random.nextDouble() < 0.95,
            error = None,
          )
          _ <- store.enqueue(orderId, callback, s"ship-cb-$correlationId")
        yield ()).forkDaemon

        _ <- store.complete(cmdId)
      yield ()

    private def processNotification(
        cmdId: Long,
        @unused orderId: String,
        email: String,
        @unused customerName: String,
        @unused petName: String,
        notifType: String,
        messageId: String,
    ): UIO[Unit] =
      val emoji = notifType match
        case "order_confirmed" => "📧"
        case "shipped"         => "📦"
        case "delivered"       => "🎉"
        case _                 => "📬"

      for
        _ <- logger.notification(s"$emoji Sending '$notifType' email to ${info(email)}")
        _ <- ZIO.sleep(Duration.fromMillis(100 + random.nextInt(200)))
        _ <- logger.notification(success(s"Email queued for delivery"))

        // Schedule delivery confirmation callback
        _ <- (for
          _ <- ZIO.sleep(Duration.fromMillis(1000 + random.nextInt(2000)))
          callback = PetStoreCommand.NotificationCallback(
            messageId = messageId,
            delivered = random.nextDouble() < 0.98,
            error = None,
          )
          _ <- store.enqueue(orderId, callback, s"notif-cb-$messageId")
        yield ()).forkDaemon

        _ <- store.complete(cmdId)
      yield ()
      end for
    end processNotification

    private def processShippingCallback(
        cmdId: Long,
        @unused correlationId: String,
        tracking: String,
        carrier: String,
        eta: String,
        succeeded: Boolean,
        err: Option[String],
    ): UIO[Unit] =
      for
        _ <- logger.callback(s"Shipping webhook received for $tracking")
        _ <-
          if succeeded then
            logger.callback(success(s"Package dispatched via ${highlight(carrier)}")) *>
              logger.callback(dim(s"  Estimated delivery: $eta")) *>
              store.complete(cmdId)
          else
            logger.callback(error(s"Shipping failed: ${err.getOrElse("Unknown error")}")) *>
              store.fail(cmdId, err.getOrElse("Shipping failed"), None)
      yield ()

    private def processNotificationCallback(
        cmdId: Long,
        messageId: String,
        delivered: Boolean,
        err: Option[String],
    ): UIO[Unit] =
      for
        _ <- logger.callback(s"Email delivery status for $messageId")
        _ <-
          if delivered then
            logger.callback(success("Email delivered successfully")) *>
              store.complete(cmdId)
          else
            logger.callback(warning(s"Email bounced: ${err.getOrElse("Unknown")}")) *>
              store.complete(cmdId) // Still complete, just log the bounce
      yield ()

    def runWorkerLoop: UIO[Unit] =
      (for
        now     <- Clock.instant
        claimed <- store.claim(workerId, 5, Duration.fromSeconds(30), now)
        _       <- ZIO.when(claimed.nonEmpty)(
          logger.worker(dim(s"Claimed ${claimed.length} command(s)"))
        )
        _ <- ZIO.foreach(claimed)(processCommand)
        _ <- ZIO.sleep(Duration.fromMillis(500))
      yield ()).forever
  end CommandProcessor

  // ============================================
  // Order Creator
  // ============================================

  def createOrder(store: InMemoryCommandStore, logger: ConsoleLogger, random: scala.util.Random): UIO[String] =
    for
      // Generate unique IDs inside the ZIO effect
      orderId       <- ZIO.succeed(s"ORD-${UUID.randomUUID().toString.take(8).toUpperCase}")
      correlationId <- ZIO.succeed(UUID.randomUUID().toString)
      messageId     <- ZIO.succeed(UUID.randomUUID().toString)

      // Pick random pet and customer
      pet      <- ZIO.succeed(Pet.catalog(random.nextInt(Pet.catalog.length)))
      customer <- ZIO.succeed(Customer.samples(random.nextInt(Customer.samples.length)))

      _ <- logger.system(s"${Bold}Creating new order: ${highlight(orderId)}$Reset")
      _ <- logger.system(s"  Customer: ${info(customer.name)}")
      _ <- logger.system(s"  Pet: ${highlight(pet.name)} (${pet.species}) - ${success(s"$$${pet.price}")}")
      _ <- printLine("").orDie

      // Enqueue payment command
      _ <- store.enqueue(
        orderId,
        PetStoreCommand.ProcessPayment(orderId, customer.id, customer.name, pet.name, pet.price, "Visa ****4242"),
        s"pay-$orderId",
      )

      // Enqueue shipping command
      _ <- store.enqueue(
        orderId,
        PetStoreCommand.RequestShipping(orderId, pet.name, customer.name, customer.address, correlationId),
        s"ship-$orderId",
      )

      // Enqueue notification command
      _ <- store.enqueue(
        orderId,
        PetStoreCommand
          .SendNotification(orderId, customer.email, customer.name, pet.name, "order_confirmed", messageId),
        s"notif-$orderId",
      )
    yield orderId

  // ============================================
  // Status Display
  // ============================================

  def displayStatus(store: InMemoryCommandStore): UIO[Unit] =
    for
      counts <- store.countByStatus
      pending    = counts.getOrElse(CommandStatus.Pending, 0)
      processing = counts.getOrElse(CommandStatus.Processing, 0)
      completed  = counts.getOrElse(CommandStatus.Completed, 0)
      failed     = counts.getOrElse(CommandStatus.Failed, 0)

      _ <- printLine("").orDie
      _ <- printLine(s"${Bold}Command Queue Status:$Reset").orDie
      _ <- printLine(
        s"  ${warning(s"Pending: $pending")} | ${info(s"Processing: $processing")} | ${success(s"Completed: $completed")} | ${error(s"Failed: $failed")}"
      ).orDie
      _ <- printLine("").orDie
    yield ()

  // ============================================
  // Main Application
  // ============================================

  def run: ZIO[Any, Any, Unit] =
    val random = new scala.util.Random()

    for
      _ <- printLine(s"""
        |${Bold}${Cyan}╔════════════════════════════════════════════════════════════════╗
        |║                    🐾 PET STORE DEMO 🐾                       ║
        |║                                                                ║
        |║  Demonstrating Durable Command Processing with:               ║
        |║  • Synchronous payments (REST-like)                           ║
        |║  • Async shipping with webhooks                               ║
        |║  • Fire-and-forget notifications                              ║
        |╚════════════════════════════════════════════════════════════════╝$Reset
        |""".stripMargin).orDie

      store     <- InMemoryCommandStore.make
      logger    <- ZIO.succeed(new ConsoleLogger)
      processor <- ZIO.succeed(new CommandProcessor(store, logger, random))

      _ <- printLine(s"${dim("Starting worker...")}").orDie
      _ <- printLine("").orDie

      // Start worker in background
      workerFiber <- processor.runWorkerLoop.fork

      // Create orders periodically
      _ <- (for
        _ <- createOrder(store, logger, random)
        _ <- ZIO.sleep(Duration.fromSeconds(5))
        _ <- displayStatus(store)
      yield ()).repeatN(4) // Create 5 orders total

      // Let remaining commands process
      _ <- printLine("").orDie
      _ <- logger.system("Waiting for remaining commands to complete...")
      _ <- ZIO.sleep(Duration.fromSeconds(10))

      _ <- displayStatus(store)

      _ <- workerFiber.interrupt
      _ <- printLine("").orDie
      _ <- printLine(s"${Bold}${Green}Demo complete! All orders processed.$Reset").orDie
    yield ()
    end for
  end run
end PetStoreApp
