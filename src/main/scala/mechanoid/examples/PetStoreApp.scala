package mechanoid.examples

import zio.*
import zio.Console.printLine
import mechanoid.*
import mechanoid.core.{SealedEnum, Timed}
import mechanoid.persistence.*
import mechanoid.runtime.FSMRuntime
import mechanoid.persistence.command.*
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.nio.file.{Files, Paths}
import scala.annotation.unused
import mechanoid.visualization.*
// Import all types from the petstore package
import mechanoid.examples.petstore.*

// ============================================
// Pet Store Console Application
// ============================================

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
  // Event Display - Pretty printing for rich events
  // ============================================

  object EventDisplay:
    import OrderEvent.*

    def format(event: OrderEvent): String = event match
      case InitiatePayment(amount, method) =>
        s"${Yellow}InitiatePayment${Reset}(${Green}$$${amount}${Reset}, ${Cyan}$method${Reset})"
      case PaymentSucceeded(transactionId) =>
        s"${Green}PaymentSucceeded${Reset}(${Bold}$transactionId${Reset})"
      case PaymentFailed(reason) =>
        s"${Red}PaymentFailed${Reset}(${dim(reason)})"
      case RequestShipping(address) =>
        s"${Blue}RequestShipping${Reset}(${Cyan}$address${Reset})"
      case ShipmentDispatched(trackingId, carrier, eta) =>
        s"${Green}ShipmentDispatched${Reset}(${Bold}$trackingId${Reset}, ${Magenta}$carrier${Reset}, ETA: ${Cyan}$eta${Reset})"
      case DeliveryConfirmed(timestamp) =>
        s"${Green}DeliveryConfirmed${Reset}(${dim(timestamp)})"

    def shortFormat(event: OrderEvent): String = event match
      case InitiatePayment(amount, _) => s"${Yellow}InitiatePayment${Reset}(${Green}$$${amount}${Reset})"
      case PaymentSucceeded(txn)      => s"${Green}PaymentSucceeded${Reset}(${Bold}${txn.take(12)}...${Reset})"
      case PaymentFailed(reason)      => s"${Red}PaymentFailed${Reset}(${dim(reason.take(20))})"
      case RequestShipping(_)         => s"${Blue}RequestShipping${Reset}"
      case ShipmentDispatched(trackingId, _, _) => s"${Green}ShipmentDispatched${Reset}(${Bold}$trackingId${Reset})"
      case DeliveryConfirmed(_)                 => s"${Green}DeliveryConfirmed${Reset}"
  end EventDisplay

  // ============================================
  // Console Logger Service (App-specific with colors)
  // ============================================

  trait ConsoleLogger:
    def payment(msg: String): UIO[Unit]
    def shipping(msg: String): UIO[Unit]
    def notification(msg: String): UIO[Unit]
    def callback(msg: String): UIO[Unit]
    def worker(msg: String): UIO[Unit]
    def system(msg: String): UIO[Unit]
    def error(msg: String): UIO[Unit]
    def fsm(msg: String): UIO[Unit]
    def event(orderId: Int, event: OrderEvent): UIO[Unit]
  end ConsoleLogger

  case class ConsoleLoggerLive() extends ConsoleLogger:
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private def timestamp: UIO[String] =
      Clock.instant.map(i => timeFormatter.format(i.atZone(java.time.ZoneId.systemDefault())))

    private def log(prefix: String, color: String, message: String): UIO[Unit] =
      for
        ts <- timestamp
        _  <- printLine(s"${dim(ts)} $color$prefix$Reset $message").orDie
      yield ()

    def payment(msg: String): UIO[Unit]      = log("[PAYMENT]", BgGreen + Bold, msg)
    def shipping(msg: String): UIO[Unit]     = log("[SHIPPING]", BgBlue + Bold, msg)
    def notification(msg: String): UIO[Unit] = log("[NOTIFY]", BgMagenta + Bold, msg)
    def callback(msg: String): UIO[Unit]     = log("[CALLBACK]", BgYellow + Bold, msg)
    def worker(msg: String): UIO[Unit]       = log("[WORKER]", BgCyan + Bold, msg)
    def system(msg: String): UIO[Unit]       = log("[SYSTEM]", Bold, msg)
    def error(msg: String): UIO[Unit]        = log("[ERROR]", BgRed + Bold, msg)
    def fsm(msg: String): UIO[Unit]          = log("[FSM]", Bold + Yellow, msg)

    def event(orderId: Int, evt: OrderEvent): UIO[Unit] =
      log("[EVENT]", Bold + Cyan, s"${highlight(s"Order-$orderId")} <- ${EventDisplay.format(evt)}")
  end ConsoleLoggerLive

  object ConsoleLogger:
    val live: ULayer[ConsoleLogger] = ZLayer.succeed(ConsoleLoggerLive())

  // ============================================
  // Payment Processor with Logging (App-specific)
  // ============================================

  case class PaymentProcessorWithLogging(logger: ConsoleLogger) extends PaymentProcessor:
    // Lower success rate (70%) to ensure demo shows some failures
    private val config        = PaymentProcessor.Config(successRate = 0.70)
    private val paymentErrors = List(
      PaymentError.CardDeclined("Generic decline"),
      PaymentError.InsufficientFunds,
      PaymentError.NetworkTimeout,
      PaymentError.FraudCheckFailed,
    )

    def processPayment(
        customerName: String,
        amount: BigDecimal,
        method: String,
    ): IO[PaymentError, PaymentSuccess] =
      for
        _ <- logger.payment(s"Processing payment for ${highlight(customerName)}: ${info(s"$$${amount}")} via $method")
        delay   <- Random.nextIntBounded((config.maxDelayMs - config.minDelayMs).toInt).map(_ + config.minDelayMs)
        _       <- ZIO.sleep(Duration.fromMillis(delay))
        success <- Random.nextDouble.map(_ < config.successRate)
        result  <-
          if success then
            for
              txnNum <- Random.nextIntBounded(999999)
              txnId = s"TXN-$txnNum"
              _ <- logger.payment(Colors.success(s"Payment approved! Transaction: $txnId"))
            yield PaymentSuccess(txnId)
          else
            for
              errIdx <- Random.nextIntBounded(paymentErrors.length)
              err = paymentErrors(errIdx)
              _      <- logger.payment(Colors.error(s"Payment failed: $err"))
              result <- ZIO.fail(err)
            yield result
      yield result
  end PaymentProcessorWithLogging

  object PaymentProcessorApp:
    val live: URLayer[ConsoleLogger, PaymentProcessor] =
      ZLayer.fromFunction(PaymentProcessorWithLogging.apply)

  // ============================================
  // Shipper with Logging (App-specific)
  // ============================================

  case class ShipperWithLogging(logger: ConsoleLogger) extends Shipper:
    private val config   = Shipper.Config()
    private val carriers = List("PetExpress", "AnimalCare Logistics", "FurryFriends Delivery")

    def requestShipment(
        petName: String,
        customerName: String,
        address: String,
    ): IO[ShippingError, ShipmentAccepted] =
      for
        _       <- logger.shipping(s"Requesting shipment of ${highlight(petName)} to ${info(customerName)}")
        _       <- logger.shipping(dim(s"  Address: $address"))
        delay   <- Random.nextIntBounded((config.maxDelayMs - config.minDelayMs).toInt).map(_ + config.minDelayMs)
        _       <- ZIO.sleep(Duration.fromMillis(delay))
        success <- Random.nextDouble.map(_ < config.successRate)
        result  <-
          if success then
            for
              trackNum <- Random.nextIntBounded(999999)
              trackingId = s"TRACK-$trackNum"
              carrierIdx <- Random.nextIntBounded(carriers.length)
              carrier = carriers(carrierIdx)
              days <- Random.nextIntBounded(5).map(_ + 2)
              eta = s"$days business days"
              _ <- logger.shipping(Colors.success(s"Shipment request accepted: $trackingId"))
              _ <- logger.shipping(dim(s"  Carrier: $carrier, ETA: $eta"))
            yield ShipmentAccepted(trackingId, carrier, eta)
          else
            for
              errType <- Random.nextIntBounded(3)
              err = errType match
                case 0 => ShippingError.AddressInvalid("Could not validate address")
                case 1 => ShippingError.ServiceUnavailable
                case _ => ShippingError.DestinationUnreachable
              _      <- logger.shipping(Colors.error(s"Shipping failed: $err"))
              result <- ZIO.fail(err)
            yield result
      yield result
  end ShipperWithLogging

  object ShipperApp:
    val live: URLayer[ConsoleLogger, Shipper] =
      ZLayer.fromFunction(ShipperWithLogging.apply)

  // ============================================
  // Notification Service with Logging (App-specific)
  // ============================================

  case class NotificationServiceWithLogging(logger: ConsoleLogger) extends NotificationService:
    private val config                              = NotificationService.Config()
    private def emojiFor(notifType: String): String = notifType match
      case "order_confirmed" => "..."
      case "shipped"         => "..."
      case "delivered"       => "..."
      case _                 => "..."

    def sendNotification(
        email: String,
        notifType: String,
    ): IO[NotificationError, Unit] =
      for
        _       <- logger.notification(s"${emojiFor(notifType)} Sending '$notifType' email to ${info(email)}")
        delay   <- Random.nextIntBounded((config.maxDelayMs - config.minDelayMs).toInt).map(_ + config.minDelayMs)
        _       <- ZIO.sleep(Duration.fromMillis(delay))
        success <- Random.nextDouble.map(_ < config.successRate)
        _       <-
          if success then logger.notification(Colors.success(s"Email queued for delivery"))
          else
            for
              errType <- Random.nextIntBounded(3)
              err = errType match
                case 0 => NotificationError.InvalidEmail
                case 1 => NotificationError.Bounced("Mailbox full")
                case _ => NotificationError.RateLimited
              _      <- logger.notification(Colors.error(s"Notification failed: $err"))
              result <- ZIO.fail(err)
            yield result
      yield ()
  end NotificationServiceWithLogging

  object NotificationServiceApp:
    val live: URLayer[ConsoleLogger, NotificationService] =
      ZLayer.fromFunction(NotificationServiceWithLogging.apply)

  // ============================================
  // In-Memory Command Store (simplified for demo)
  // ============================================

  case class CommandRecord(
      cmd: PendingCommand[Int, PetStoreCommand],
      claimedBy: Option[String],
      claimedUntil: Option[Instant],
  )

  class InMemoryCommandStore private (
      commands: Ref[Map[Long, CommandRecord]],
      idCounter: Ref[Long],
      idempotencyIndex: Ref[Map[String, Long]],
  ) extends CommandStore[Int, PetStoreCommand]:

    override def enqueue(
        instanceId: Int,
        command: PetStoreCommand,
        idempotencyKey: String,
    ): UIO[PendingCommand[Int, PetStoreCommand]] =
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
    ): UIO[List[PendingCommand[Int, PetStoreCommand]]] =
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

    override def getByIdempotencyKey(idempotencyKey: String): UIO[Option[PendingCommand[Int, PetStoreCommand]]] =
      for
        maybeId <- idempotencyIndex.get.map(_.get(idempotencyKey))
        result  <- maybeId match
          case Some(id) => commands.get.map(cmds => cmds.get(id).map(_.cmd))
          case None     => ZIO.succeed(None)
      yield result

    override def getByInstanceId(instanceId: Int): UIO[List[PendingCommand[Int, PetStoreCommand]]] =
      commands.get.map(_.values.filter(_.cmd.instanceId == instanceId).map(_.cmd).toList)

    override def countByStatus: UIO[Map[CommandStatus, Long]] =
      commands.get.map(_.values.map(_.cmd).groupBy(_.status).view.mapValues(_.size.toLong).toMap)

    def getAllCommands: UIO[List[PendingCommand[Int, PetStoreCommand]]] =
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
  // Order FSM Manager
  // ============================================

  /** Manages Order FSM instances and coordinates with command processing. */
  class OrderFSMManager(
      eventStore: SimpleEventStore[Int, OrderState, OrderEvent],
      commandStore: InMemoryCommandStore,
      logger: ConsoleLogger,
      orderIdCounter: Ref[Int],
  ):
    import OrderState.*
    import OrderEvent.*

    private val orderDataRef = Unsafe.unsafe { implicit unsafe =>
      Ref.unsafe.make(Map.empty[Int, OrderData])
    }

    private def createDefinition(orderId: Int): FSMDefinition[OrderState, OrderEvent, Nothing] =
      OrderFSM.definition(
        onPaymentProcessing = enqueuePaymentCommand(orderId),
        onPaid = enqueueShippingCommands(orderId),
        onShipped = enqueueShippedNotification(orderId),
      )

    /** Get an FSM definition suitable for visualization. Uses a dummy orderId since the definition is only used for
      * generating diagrams.
      */
    def getDefinitionForVisualization: FSMDefinition[OrderState, OrderEvent, Nothing] =
      createDefinition(0)

    private def enqueuePaymentCommand(orderId: Int): ZIO[Any, Throwable, Unit] =
      for
        dataOpt <- orderDataRef.get.map(_.get(orderId))
        data    <- ZIO.fromOption(dataOpt).orElseFail(new RuntimeException(s"Order data not found: $orderId"))
        _       <- logger.fsm(s"${highlight(s"Order-$orderId")}: ${info("Created")} -> ${warning("PaymentProcessing")}")
        _       <- commandStore.enqueue(
          orderId,
          PetStoreCommand.ProcessPayment(
            orderId,
            data.customer.id,
            data.customer.name,
            data.pet.name,
            data.pet.price,
            "Visa ****4242",
          ),
          s"pay-$orderId",
        )
      yield ()

    private def enqueueShippingCommands(orderId: Int): ZIO[Any, Throwable, Unit] =
      for
        dataOpt <- orderDataRef.get.map(_.get(orderId))
        data    <- ZIO.fromOption(dataOpt).orElseFail(new RuntimeException(s"Order data not found: $orderId"))
        _       <- logger.fsm(
          s"${highlight(s"Order-$orderId")}: ${warning("PaymentProcessing")} -> ${Colors.success("Paid")}"
        )
        _ <- commandStore.enqueue(
          orderId,
          PetStoreCommand.RequestShipping(
            orderId,
            data.pet.name,
            data.customer.name,
            data.customer.address,
            data.correlationId,
          ),
          s"ship-$orderId",
        )
        _ <- commandStore.enqueue(
          orderId,
          PetStoreCommand.SendNotification(
            orderId,
            data.customer.email,
            data.customer.name,
            data.pet.name,
            "order_confirmed",
            data.messageId,
          ),
          s"notif-confirmed-$orderId",
        )
      yield ()

    private def enqueueShippedNotification(orderId: Int): ZIO[Any, Throwable, Unit] =
      for
        dataOpt <- orderDataRef.get.map(_.get(orderId))
        data    <- ZIO.fromOption(dataOpt).orElseFail(new RuntimeException(s"Order data not found: $orderId"))
        _       <- logger.fsm(
          s"${highlight(s"Order-$orderId")}: ${info("ShippingRequested")} -> ${Colors.success("Shipped")}"
        )
        _ <- commandStore.enqueue(
          orderId,
          PetStoreCommand.SendNotification(
            orderId,
            data.customer.email,
            data.customer.name,
            data.pet.name,
            "shipped",
            s"${data.messageId}-shipped",
          ),
          s"notif-shipped-$orderId",
        )
      yield ()

    def createOrder(pet: Pet, customer: Customer): ZIO[Scope, Throwable | MechanoidError, Int] =
      for
        orderId       <- orderIdCounter.updateAndGet(_ + 1)
        correlationId <- Random.nextUUID.map(_.toString)
        messageId     <- Random.nextUUID.map(_.toString)
        data = OrderData(orderId, pet, customer, correlationId, messageId)
        _ <- orderDataRef.update(_ + (orderId -> data))
        _ <- logger.system(s"${Bold}Creating new order: ${highlight(s"Order-$orderId")}$Reset")
        _ <- logger.system(s"  Customer: ${info(customer.name)}")
        _ <- logger.system(s"  Pet: ${highlight(pet.name)} (${pet.species}) - ${Colors.success(s"$$${pet.price}")}")
        storeLayer = ZLayer.succeed[EventStore[Int, OrderState, OrderEvent]](eventStore)
        definition = createDefinition(orderId)
        fsm <- FSMRuntime(orderId, definition, Created).provideSomeLayer[Scope](storeLayer)
        // Send rich event with actual payment details
        initiateEvent = InitiatePayment(pet.price, "Visa ****4242")
        _ <- logger.event(orderId, initiateEvent)
        _ <- fsm.send(initiateEvent)
      yield orderId

    def sendEvent(orderId: Int, event: OrderEvent): ZIO[Scope, Throwable | MechanoidError, Unit] =
      for
        _          <- logger.event(orderId, event)
        storeLayer <- ZIO.succeed(ZLayer.succeed[EventStore[Int, OrderState, OrderEvent]](eventStore))
        definition = createDefinition(orderId)
        fsm <- FSMRuntime(orderId, definition, Created).provideSomeLayer[Scope](storeLayer)
        _   <- fsm.send(event)
      yield ()

    def getState(orderId: Int): ZIO[Scope, MechanoidError, OrderState] =
      for
        storeLayer <- ZIO.succeed(ZLayer.succeed[EventStore[Int, OrderState, OrderEvent]](eventStore))
        definition = createDefinition(orderId)
        fsm   <- FSMRuntime(orderId, definition, Created).provideSomeLayer[Scope](storeLayer)
        state <- fsm.currentState
      yield state

    def getOrderData(orderId: Int): UIO[Option[OrderData]] =
      orderDataRef.get.map(_.get(orderId))

    def getAllOrderStates: ZIO[Any, MechanoidError, Map[Int, OrderState]] =
      for
        orderIds <- orderDataRef.get.map(_.keys.toList)
        states   <- ZIO.foreach(orderIds) { orderId =>
          ZIO
            .scoped(getState(orderId).map(state => orderId -> state))
            .orElse(ZIO.succeed(orderId -> Created))
        }
      yield states.toMap
  end OrderFSMManager

  // ============================================
  // Command Processor
  // ============================================

  class CommandProcessor(
      store: InMemoryCommandStore,
      fsmManager: OrderFSMManager,
      logger: ConsoleLogger,
      paymentProcessor: PaymentProcessor,
      shipper: Shipper,
      notificationService: NotificationService,
  ):
    import OrderEvent.*

    val workerId: String = Unsafe.unsafe { implicit unsafe =>
      s"worker-${java.util.UUID.randomUUID().toString.take(8)}"
    }

    def processCommand(cmd: PendingCommand[Int, PetStoreCommand]): UIO[Unit] =
      val orderId = cmd.instanceId
      cmd.command match
        case PetStoreCommand.ProcessPayment(_, _, customerName, _, amount, method) =>
          processPayment(cmd.id, orderId, customerName, amount, method)

        case PetStoreCommand.RequestShipping(_, petName, customerName, address, correlationId) =>
          processShipping(cmd.id, orderId, petName, customerName, address, correlationId)

        case PetStoreCommand.SendNotification(_, email, _, _, notifType, messageId) =>
          processNotification(cmd.id, orderId, email, notifType, messageId)

        case PetStoreCommand.ShippingCallback(correlationId, tracking, carrier, eta, succeeded, err) =>
          processShippingCallback(cmd.id, orderId, correlationId, tracking, carrier, eta, succeeded, err)

        case PetStoreCommand.NotificationCallback(messageId, delivered, err) =>
          processNotificationCallback(cmd.id, messageId, delivered, err)
      end match
    end processCommand

    private def processPayment(
        cmdId: Long,
        orderId: Int,
        customerName: String,
        amount: BigDecimal,
        method: String,
    ): UIO[Unit] =
      paymentProcessor
        .processPayment(customerName, amount, method)
        .flatMap { result =>
          store.complete(cmdId) *>
            ZIO.scoped(fsmManager.sendEvent(orderId, PaymentSucceeded(result.transactionId))).ignore
        }
        .catchAll {
          case err: Retryable =>
            Clock.instant.flatMap(now => store.fail(cmdId, err.toString, Some(now.plusSeconds(3)))).unit
          case err =>
            store.fail(cmdId, err.toString, None) *>
              ZIO.scoped(fsmManager.sendEvent(orderId, PaymentFailed(err.toString))).ignore
        }

    private def processShipping(
        cmdId: Long,
        orderId: Int,
        petName: String,
        customerName: String,
        address: String,
        correlationId: String,
    ): UIO[Unit] =
      shipper
        .requestShipment(petName, customerName, address)
        .flatMap { shipment =>
          for
            _ <- logger.shipping(dim(s"  Webhook callback scheduled..."))
            _ <- ZIO.scoped(fsmManager.sendEvent(orderId, RequestShipping(address))).ignore
            _ <- scheduleShippingCallback(orderId, correlationId, shipment).forkDaemon
            _ <- store.complete(cmdId)
          yield ()
        }
        .catchAll {
          case err: Retryable =>
            Clock.instant.flatMap(now => store.fail(cmdId, err.toString, Some(now.plusSeconds(5)))).unit
          case err =>
            store.fail(cmdId, err.toString, None).unit
        }

    private def scheduleShippingCallback(
        orderId: Int,
        correlationId: String,
        shipment: ShipmentAccepted,
    ): ZIO[Any, Nothing, Unit] =
      for
        delay <- Random.nextIntBounded(750).map(_ + 500)
        _     <- ZIO.sleep(Duration.fromMillis(delay.toLong))
        callback = PetStoreCommand.ShippingCallback(
          correlationId = correlationId,
          trackingNumber = shipment.trackingId,
          carrier = shipment.carrier,
          estimatedDelivery = shipment.estimatedDelivery,
          success = true,
          error = None,
        )
        _ <- store.enqueue(orderId, callback, s"ship-cb-$correlationId")
      yield ()

    private def processNotification(
        cmdId: Long,
        orderId: Int,
        email: String,
        notifType: String,
        messageId: String,
    ): UIO[Unit] =
      notificationService
        .sendNotification(email, notifType)
        .flatMap { _ =>
          for
            _ <- scheduleNotificationCallback(orderId, messageId).forkDaemon
            _ <- store.complete(cmdId)
          yield ()
        }
        .catchAll {
          case _: Retryable =>
            Clock.instant.flatMap(now => store.fail(cmdId, "Rate limited", Some(now.plusSeconds(2)))).unit
          case _ =>
            store.complete(cmdId).unit
        }

    private def scheduleNotificationCallback(orderId: Int, messageId: String): ZIO[Any, Nothing, Unit] =
      for
        delay <- Random.nextIntBounded(500).map(_ + 250)
        _     <- ZIO.sleep(Duration.fromMillis(delay.toLong))
        callback = PetStoreCommand.NotificationCallback(
          messageId = messageId,
          delivered = true,
          error = None,
        )
        _ <- store.enqueue(orderId, callback, s"notif-cb-$messageId")
      yield ()

    private def processShippingCallback(
        cmdId: Long,
        orderId: Int,
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
            logger.callback(Colors.success(s"Package dispatched via ${highlight(carrier)}")) *>
              logger.callback(dim(s"  Estimated delivery: $eta")) *>
              store.complete(cmdId) *>
              ZIO.scoped(fsmManager.sendEvent(orderId, ShipmentDispatched(tracking, carrier, eta))).ignore
          else
            logger.callback(Colors.error(s"Shipping failed: ${err.getOrElse("Unknown error")}")) *>
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
            logger.callback(Colors.success("Email delivered successfully")) *>
              store.complete(cmdId)
          else
            logger.callback(warning(s"Email bounced: ${err.getOrElse("Unknown")}")) *>
              store.complete(cmdId)
      yield ()

    def runWorkerLoop: UIO[Unit] =
      (for
        now     <- Clock.instant
        claimed <- store.claim(workerId, 5, Duration.fromSeconds(30), now)
        _       <- ZIO.when(claimed.nonEmpty)(logger.worker(dim(s"Claimed ${claimed.length} command(s)")))
        _       <- ZIO.foreach(claimed)(processCommand)
        _       <- ZIO.sleep(Duration.fromMillis(125))
      yield ()).forever
  end CommandProcessor

  // ============================================
  // Status Display
  // ============================================

  def displayStatus(store: InMemoryCommandStore, fsmManager: OrderFSMManager): ZIO[Any, MechanoidError, Unit] =
    for
      counts <- store.countByStatus
      pending    = counts.getOrElse(CommandStatus.Pending, 0L)
      processing = counts.getOrElse(CommandStatus.Processing, 0L)
      completed  = counts.getOrElse(CommandStatus.Completed, 0L)
      failed     = counts.getOrElse(CommandStatus.Failed, 0L)
      orderStates <- fsmManager.getAllOrderStates
      _           <- printLine("").orDie
      _ <- printLine(s"${Bold}=======================================================================$Reset").orDie
      _ <- printLine(s"${Bold}Command Queue:$Reset").orDie
      _ <- printLine(
        s"  ${warning(s"Pending: $pending")} | ${info(s"Processing: $processing")} | ${Colors.success(s"Completed: $completed")} | ${Colors.error(s"Failed: $failed")}"
      ).orDie
      _ <- printLine("").orDie
      _ <- printLine(s"${Bold}Order FSM States:$Reset").orDie
      _ <- ZIO.foreachDiscard(orderStates.toList.sortBy(_._1)) { case (orderId, state) =>
        val stateColor = state match
          case OrderState.Created           => dim(state.toString)
          case OrderState.PaymentProcessing => warning(state.toString)
          case OrderState.Paid              => Colors.success(state.toString)
          case OrderState.ShippingRequested => info(state.toString)
          case OrderState.Shipped           => info(state.toString)
          case OrderState.Delivered         => Colors.success(s"Done ${state.toString}")
          case OrderState.Cancelled         => Colors.error(s"X ${state.toString}")
        printLine(s"  ${highlight(s"Order-$orderId")}: $stateColor").orDie
      }
      _ <- printLine(s"${Bold}=======================================================================$Reset").orDie
    yield ()

  // ============================================
  // Execution Trace Builder
  // ============================================

  private def buildExecutionTrace(
      orderId: Int,
      events: List[StoredEvent[Int, Timed[OrderEvent]]],
      finalState: OrderState,
  ): ExecutionTrace[OrderState, OrderEvent] =
    import OrderState.*

    // Reconstruct state transitions from events
    val steps: List[TraceStep[OrderState, OrderEvent]] = events.zipWithIndex.flatMap { case (stored, idx) =>
      stored.event match
        case Timed.UserEvent(e) =>
          val fromState = if idx == 0 then Created else getStateAfterEvent(events.take(idx))
          val toState   = getStateAfterEvent(events.take(idx + 1))
          Some(
            TraceStep[OrderState, OrderEvent](
              sequenceNumber = idx + 1,
              from = fromState,
              to = toState,
              event = e,
              timestamp = stored.timestamp,
              isTimeout = false,
            )
          )
        case Timed.TimeoutEvent => None
    }

    ExecutionTrace[OrderState, OrderEvent](
      instanceId = s"Order-$orderId",
      initialState = Created,
      currentState = finalState,
      steps = steps,
    )
  end buildExecutionTrace

  private def getStateAfterEvent(events: List[StoredEvent[Int, Timed[OrderEvent]]]): OrderState =
    import OrderState.*, OrderEvent.*
    events.foldLeft[OrderState](Created) { (state, stored) =>
      stored.event match
        case Timed.UserEvent(e) =>
          (state, e) match
            case (Created, InitiatePayment(_, _))                 => PaymentProcessing
            case (PaymentProcessing, PaymentSucceeded(_))         => Paid
            case (PaymentProcessing, PaymentFailed(_))            => Cancelled
            case (Paid, RequestShipping(_))                       => ShippingRequested
            case (ShippingRequested, ShipmentDispatched(_, _, _)) => Shipped
            case (Shipped, DeliveryConfirmed(_))                  => Delivered
            case _                                                => state
        case _ => state
    }
  end getStateAfterEvent

  // ============================================
  // Main Application
  // ============================================

  def run: ZIO[Any, Any, Unit] =
    val program = for
      _ <- printLine(s"""
        |${Bold}${Cyan}+====================================================================+
        ||                    PET STORE DEMO                                 |
        ||                                                                   |
        ||  Demonstrating Durable FSM with Command Processing:               |
        ||  - FSM-driven order lifecycle (state machine)                     |
        ||  - Entry actions enqueue commands on state transitions            |
        ||  - Command completion triggers FSM events                         |
        ||  - Synchronous payments, async shipping, notifications            |
        ||  - Typed error handling with ZIO service layers                   |
        |+====================================================================+$Reset
        |""".stripMargin).orDie

      // Get services from environment
      logger              <- ZIO.service[ConsoleLogger]
      paymentProcessor    <- ZIO.service[PaymentProcessor]
      shipper             <- ZIO.service[Shipper]
      notificationService <- ZIO.service[NotificationService]

      // Create stores
      commandStore   <- InMemoryCommandStore.make
      eventStore     <- ZIO.succeed(new SimpleEventStore[Int, OrderState, OrderEvent])
      orderIdCounter <- Ref.make(0)

      // Create FSM manager
      fsmManager <- ZIO.succeed(new OrderFSMManager(eventStore, commandStore, logger, orderIdCounter))

      // Create command processor with injected services
      processor <- ZIO.succeed(
        new CommandProcessor(commandStore, fsmManager, logger, paymentProcessor, shipper, notificationService)
      )

      _ <- printLine(s"${dim("Starting worker...")}").orDie
      _ <- printLine("").orDie

      // Start worker in background
      workerFiber <- processor.runWorkerLoop.fork

      // Create orders periodically using FSM
      _ <- (for
        petIdx <- Random.nextIntBounded(Pet.catalog.length)
        pet = Pet.catalog(petIdx)
        customerIdx <- Random.nextIntBounded(Customer.samples.length)
        customer = Customer.samples(customerIdx)
        _ <- ZIO.scoped(fsmManager.createOrder(pet, customer))
        _ <- printLine("").orDie
        _ <- ZIO.sleep(Duration.fromMillis(1250))
        _ <- displayStatus(commandStore, fsmManager)
      yield ()).repeatN(4)

      // Let remaining commands process
      _ <- printLine("").orDie
      _ <- logger.system("Waiting for remaining commands to complete...")
      _ <- ZIO.sleep(Duration.fromMillis(2500))

      _ <- displayStatus(commandStore, fsmManager)

      _ <- workerFiber.interrupt

      // Write visualization files
      _ <- printLine("").orDie
      _ <- logger.system("Writing visualization files...")
      vizDir = Paths.get("docs", "visualizations")
      _ <- ZIO.attempt(Files.createDirectories(vizDir)).orDie

      // Write FSM structure diagram - uses actual action functions for accurate visualization
      fsmDef = fsmManager.getDefinitionForVisualization
      // Map states to command types they trigger (using caseHash for stable identification)
      stateEnum     = summon[SealedEnum[OrderState]]
      stateCommands = Map(
        stateEnum.caseHash(OrderState.PaymentProcessing) -> List("ProcessPayment"),
        stateEnum.caseHash(OrderState.Paid)              -> List("RequestShipping", "SendNotification"),
        stateEnum.caseHash(OrderState.Shipped)           -> List("SendNotification"),
      )
      structureMermaid   = MermaidVisualizer.stateDiagramWithCommands(fsmDef, stateCommands, Some(OrderState.Created))
      structureFlowchart = MermaidVisualizer.flowchartWithCommands(fsmDef, stateCommands)
      structureGraphViz  = GraphVizVisualizer.digraph(fsmDef, initialState = Some(OrderState.Created))
      _ <- ZIO.attempt {
        Files.writeString(
          vizDir.resolve("order-fsm-structure.md"),
          s"""# Order FSM Structure
             |
             |## State Diagram with Commands (Mermaid)
             |
             |```mermaid
             |$structureMermaid```
             |
             |## FSM + Commands Flowchart
             |
             |```mermaid
             |$structureFlowchart```
             |
             |## GraphViz
             |
             |```dot
             |$structureGraphViz```
             |""".stripMargin,
        )
      }.orDie

      // Write execution traces for each order (with command details)
      orderStates <- fsmManager.getAllOrderStates
      allCommands <- commandStore.getAllCommands
      commandNameFn = (cmd: PetStoreCommand) => cmd.redacted
      _ <- ZIO.foreachDiscard(orderStates.toList.sortBy(_._1)) { case (orderId, finalState) =>
        val events        = eventStore.getEvents(orderId)
        val trace         = buildExecutionTrace(orderId, events, finalState)
        val orderCommands = allCommands.filter(_.instanceId == orderId)

        // Basic sequence diagram (FSM only)
        val traceMermaid = MermaidVisualizer.sequenceDiagram(
          trace,
          summon[SealedEnum[OrderState]],
          summon[SealedEnum[OrderEvent]],
        )

        // Enhanced sequence diagram with commands
        val traceWithCommands = MermaidVisualizer.sequenceDiagramWithCommands(
          trace,
          summon[SealedEnum[OrderState]],
          summon[SealedEnum[OrderEvent]],
          orderCommands,
          commandNameFn,
        )

        // Flowchart with path highlighting and command connections
        val flowchart = MermaidVisualizer.flowchartWithCommands(fsmDef, stateCommands, Some(trace))

        // Command summary for this order
        val cmdSummary = orderCommands
          .groupBy(_.status)
          .map { case (status, cmds) =>
            s"- ${status}: ${cmds.size}"
          }
          .mkString("\n")

        ZIO.attempt {
          Files.writeString(
            vizDir.resolve(s"order-$orderId-trace.md"),
            s"""# Order $orderId Execution Trace
               |
               |**Final State:** $finalState
               |
               |## Command Summary
               |
               |$cmdSummary
               |
               |## FSM + Commands Sequence Diagram
               |
               |```mermaid
               |$traceWithCommands```
               |
               |## FSM-Only Sequence Diagram
               |
               |```mermaid
               |$traceMermaid```
               |
               |## Flowchart with Commands
               |
               |```mermaid
               |$flowchart```
               |""".stripMargin,
          )
        }.orDie
      }

      // Write command queue visualization (reuses allCommands from above)
      cmdCounts <- commandStore.countByStatus
      commandReport = CommandVisualizer.report(
        commands = allCommands,
        counts = cmdCounts,
        commandName = _.redacted,
        commandType = (cmd: PetStoreCommand) => cmd.getClass.getSimpleName.stripSuffix("$"),
        title = "Pet Store Command Processing Report",
      )
      _ <- ZIO.attempt {
        Files.writeString(vizDir.resolve("command-queue.md"), commandReport)
      }.orDie

      _ <- logger.system(s"Visualization files written to ${info("docs/visualizations/")}")

      _ <- printLine("").orDie
      _ <- printLine(s"${Bold}${Green}Demo complete! All orders processed.$Reset").orDie
    yield ()

    program.provide(
      ConsoleLogger.live,
      PaymentProcessorApp.live,
      ShipperApp.live,
      NotificationServiceApp.live,
    )
  end run
end PetStoreApp
