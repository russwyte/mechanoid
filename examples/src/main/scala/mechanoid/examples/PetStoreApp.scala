package mechanoid.examples

import zio.*
import zio.Console.printLine
import mechanoid.core.{MechanoidError, Finite}
import mechanoid.core.Redactor.redacted
import mechanoid.machine.*
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
  // Event Display - Pretty printing for FSM events
  // ============================================

  object EventDisplay:
    import OrderEvent.*

    /** Format an event with rich metadata for console display. */
    def format(event: OrderEvent): String = event match
      case InitiatePayment(orderId, _, _, _, _, petName, amount, method, _, _) =>
        s"${Yellow}InitiatePayment${Reset}(${Green}$$${amount}${Reset}, ${Cyan}$method${Reset}, pet: $petName)"
      case PaymentSucceeded(orderId, transactionId, _, _, _, _, _, _, _) =>
        s"${Green}PaymentSucceeded${Reset}(${Bold}$transactionId${Reset})"
      case PaymentFailed(orderId, reason) =>
        s"${Red}PaymentFailed${Reset}(${dim(reason)})"
      case RequestShipping(orderId, address) =>
        s"${Blue}RequestShipping${Reset}(${dim(address.take(30))}...)"
      case ShipmentDispatched(orderId, trackingId, carrier, eta, _, _, _, _) =>
        s"${Green}ShipmentDispatched${Reset}(${Bold}$trackingId${Reset}, $carrier, ETA: $eta)"
      case DeliveryConfirmed(orderId, timestamp) =>
        s"${Green}DeliveryConfirmed${Reset}(${dim(timestamp)})"

    /** Short format for compact display - just event name. */
    def shortFormat(event: OrderEvent): String = event match
      case _: InitiatePayment    => s"${Yellow}InitiatePayment${Reset}"
      case _: PaymentSucceeded   => s"${Green}PaymentSucceeded${Reset}"
      case _: PaymentFailed      => s"${Red}PaymentFailed${Reset}"
      case _: RequestShipping    => s"${Blue}RequestShipping${Reset}"
      case _: ShipmentDispatched => s"${Green}ShipmentDispatched${Reset}"
      case _: DeliveryConfirmed  => s"${Green}DeliveryConfirmed${Reset}"
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

  /** Manages Order FSM instances and coordinates with command processing.
    *
    * Uses the declarative command emission pattern: the FSM definition declares what commands to emit via `emitting`,
    * and this manager handles enqueuing them from the TransitionOutcome.
    */
  class OrderFSMManager(
      eventStore: SimpleEventStore[Int, OrderState, OrderEvent],
      commandStore: InMemoryCommandStore,
      logger: ConsoleLogger,
      orderIdCounter: Ref[Int],
  ):
    import OrderState.*
    import OrderEvent.*

    // Order data is still needed for CommandProcessor callbacks to build rich events
    private val orderDataRef = Unsafe.unsafe { implicit unsafe =>
      Ref.unsafe.make(Map.empty[Int, OrderData])
    }

    // Use the declarative machine directly - no need for createMachine with entry actions
    private val machine = OrderFSM.machine

    /** Get the FSM definition suitable for visualization. */
    def getMachineForVisualization: Machine[OrderState, OrderEvent, PetStoreCommand] = machine

    /** Enqueue commands from a TransitionOutcome. */
    private def enqueueCommands(orderId: Int, commands: List[PetStoreCommand], seqNr: Long): UIO[Unit] =
      ZIO.foreachDiscard(commands.zipWithIndex) { (cmd, idx) =>
        val idempotencyKey = s"cmd-$orderId-$seqNr-$idx"
        commandStore.enqueue(orderId, cmd, idempotencyKey)
      }

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
        fsm <- FSMRuntime(orderId, machine, Created).provideSomeLayer[Scope](storeLayer)

        // Build rich event with all context needed for command generation
        initiateEvent = InitiatePayment(
          orderId = orderId,
          customerId = customer.id,
          customerName = customer.name,
          customerEmail = customer.email,
          customerAddress = customer.address,
          petName = pet.name,
          amount = pet.price,
          paymentMethod = "Visa ****4242",
          correlationId = correlationId,
          messageId = messageId,
        )
        _       <- logger.event(orderId, initiateEvent)
        outcome <- fsm.send(initiateEvent)
        seqNr   <- fsm.lastSequenceNr

        // Log FSM transition
        _ <- logger.fsm(s"${highlight(s"Order-$orderId")}: ${info("Created")} -> ${warning("PaymentProcessing")}")

        // Enqueue commands from the outcome (declaratively generated by the machine)
        _ <- enqueueCommands(orderId, outcome.allCommands, seqNr)
      yield orderId

    def sendEvent(orderId: Int, event: OrderEvent): ZIO[Scope, Throwable | MechanoidError, Unit] =
      for
        _          <- logger.event(orderId, event)
        storeLayer <- ZIO.succeed(ZLayer.succeed[EventStore[Int, OrderState, OrderEvent]](eventStore))
        fsm        <- FSMRuntime(orderId, machine, Created).provideSomeLayer[Scope](storeLayer)
        outcome    <- fsm.send(event)
        seqNr      <- fsm.lastSequenceNr

        // Log FSM transition based on event type
        _ <- logTransition(orderId, event)

        // Enqueue commands from the outcome
        _ <- enqueueCommands(orderId, outcome.allCommands, seqNr)
      yield ()

    private def logTransition(orderId: Int, event: OrderEvent): UIO[Unit] =
      event match
        case _: PaymentSucceeded =>
          logger.fsm(s"${highlight(s"Order-$orderId")}: ${warning("PaymentProcessing")} -> ${Colors.success("Paid")}")
        case _: PaymentFailed =>
          logger.fsm(
            s"${highlight(s"Order-$orderId")}: ${warning("PaymentProcessing")} -> ${Colors.error("Cancelled")}"
          )
        case _: RequestShipping =>
          logger.fsm(s"${highlight(s"Order-$orderId")}: ${Colors.success("Paid")} -> ${info("ShippingRequested")}")
        case _: ShipmentDispatched =>
          logger.fsm(s"${highlight(s"Order-$orderId")}: ${info("ShippingRequested")} -> ${Colors.success("Shipped")}")
        case _: DeliveryConfirmed =>
          logger.fsm(s"${highlight(s"Order-$orderId")}: ${Colors.success("Shipped")} -> ${Colors.success("Delivered")}")
        case _ => ZIO.unit

    def getState(orderId: Int): ZIO[Scope, MechanoidError, OrderState] =
      for
        storeLayer <- ZIO.succeed(ZLayer.succeed[EventStore[Int, OrderState, OrderEvent]](eventStore))
        fsm        <- FSMRuntime(orderId, machine, Created).provideSomeLayer[Scope](storeLayer)
        state      <- fsm.currentState
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
          // Build rich event with all context for command generation
          fsmManager.getOrderData(orderId).flatMap {
            case Some(data) =>
              val event = PaymentSucceeded(
                orderId = orderId,
                transactionId = result.transactionId,
                customerId = data.customer.id,
                customerName = data.customer.name,
                customerEmail = data.customer.email,
                customerAddress = data.customer.address,
                petName = data.pet.name,
                correlationId = data.correlationId,
                messageId = data.messageId,
              )
              store.complete(cmdId) *>
                ZIO.scoped(fsmManager.sendEvent(orderId, event)).ignore
            case None =>
              store.fail(cmdId, s"Order data not found: $orderId", None).unit
          }
        }
        .catchAll {
          case err: Retryable =>
            Clock.instant.flatMap(now => store.fail(cmdId, err.toString, Some(now.plusSeconds(3)))).unit
          case err =>
            // Build rich event with failure reason
            store.fail(cmdId, err.toString, None) *>
              ZIO.scoped(fsmManager.sendEvent(orderId, PaymentFailed(orderId, err.toString))).ignore
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
            // Build rich event with address
            event = RequestShipping(orderId, address)
            _ <- ZIO.scoped(fsmManager.sendEvent(orderId, event)).ignore
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
            // Build rich event with all context for notification command generation
            fsmManager.getOrderData(orderId).flatMap {
              case Some(data) =>
                val event = ShipmentDispatched(
                  orderId = orderId,
                  trackingId = tracking,
                  carrier = carrier,
                  eta = eta,
                  customerEmail = data.customer.email,
                  customerName = data.customer.name,
                  petName = data.pet.name,
                  messageId = data.messageId,
                )
                logger.callback(Colors.success(s"Package dispatched via ${highlight(carrier)}")) *>
                  logger.callback(dim(s"  Estimated delivery: $eta")) *>
                  store.complete(cmdId) *>
                  ZIO.scoped(fsmManager.sendEvent(orderId, event)).ignore
              case None =>
                logger.callback(Colors.error(s"Order data not found: $orderId")) *>
                  store.fail(cmdId, s"Order data not found: $orderId", None)
            }
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
      events: List[StoredEvent[Int, OrderEvent]],
      finalState: OrderState,
  ): ExecutionTrace[OrderState, OrderEvent] =
    import OrderState.*

    // Reconstruct state transitions from events
    val steps: List[TraceStep[OrderState, OrderEvent]] = events.zipWithIndex.map { case (stored, idx) =>
      val fromState = if idx == 0 then Created else getStateAfterEvent(events.take(idx))
      val toState   = getStateAfterEvent(events.take(idx + 1))
      TraceStep[OrderState, OrderEvent](
        sequenceNumber = idx + 1,
        from = fromState,
        to = toState,
        event = stored.event,
        timestamp = stored.timestamp,
        isTimeout = false,
      )
    }

    ExecutionTrace[OrderState, OrderEvent](
      instanceId = s"Order-$orderId",
      initialState = Created,
      currentState = finalState,
      steps = steps,
    )
  end buildExecutionTrace

  private def getStateAfterEvent(events: List[StoredEvent[Int, OrderEvent]]): OrderState =
    import OrderState.*, OrderEvent.*
    events.foldLeft[OrderState](Created) { (state, stored) =>
      // Match on event types (ignoring parameter values)
      (state, stored.event) match
        case (Created, _: InitiatePayment)              => PaymentProcessing
        case (PaymentProcessing, _: PaymentSucceeded)   => Paid
        case (PaymentProcessing, _: PaymentFailed)      => Cancelled
        case (Paid, _: RequestShipping)                 => ShippingRequested
        case (ShippingRequested, _: ShipmentDispatched) => Shipped
        case (Shipped, _: DeliveryConfirmed)            => Delivered
        case _                                          => state
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
      fsmDef = fsmManager.getMachineForVisualization
      // Map states to command types they trigger (using caseHash for stable identification)
      stateEnum     = summon[Finite[OrderState]]
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
          summon[Finite[OrderState]],
          summon[Finite[OrderEvent]],
        )

        // Enhanced sequence diagram with commands
        val traceWithCommands = MermaidVisualizer.sequenceDiagramWithCommands(
          trace,
          summon[Finite[OrderState]],
          summon[Finite[OrderEvent]],
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
