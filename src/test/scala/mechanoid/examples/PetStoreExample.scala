package mechanoid.examples

import saferis.*
import zio.*
import zio.json.*
import zio.test.*
import mechanoid.PostgresTestContainer
import mechanoid.core.*
import mechanoid.persistence.command.*
import mechanoid.persistence.postgres.*
import java.util.UUID
import scala.annotation.unused

/** Pet Store Example - End-to-End Durable FSM with Commands
  *
  * This example demonstrates how to model a real-world system using:
  *   - Durable FSM for order lifecycle
  *   - Command queue for reliable service integration
  *   - Synchronous services (payment gateway)
  *   - Asynchronous services with callbacks (shipping, notifications)
  *
  * ==Domain Model==
  *
  * A pet store where customers can:
  *   1. Reserve a pet (immediate)
  *   2. Pay for the pet (sync payment gateway)
  *   3. Request shipping (async - webhook callback)
  *   4. Receive adoption certificate (async email)
  *
  * ==Service Integration Patterns==
  *
  * '''Synchronous (REST-like):'''
  *   - ProcessPayment: Call payment gateway, wait for response
  *   - Worker claims command, makes blocking call, completes/fails immediately
  *
  * '''Asynchronous (Webhook-like):'''
  *   - RequestShipping: Send request, shipping service calls back when ready
  *   - Worker claims command, sends request, leaves "awaiting callback"
  *   - Callback handler completes the command when webhook arrives
  *
  * '''Fire-and-Forget with Confirmation:'''
  *   - SendNotification: Send email, get delivery confirmation later
  *   - Similar to webhook but confirmation is optional
  */
object PetStoreExample extends ZIOSpecDefault:

  // ============================================
  // Domain Types
  // ============================================

  case class Pet(id: String, name: String, species: String, price: BigDecimal)
  object Pet:
    given JsonCodec[Pet] = DeriveJsonCodec.gen[Pet]

  case class Customer(id: String, name: String, email: String, address: String)
  object Customer:
    given JsonCodec[Customer] = DeriveJsonCodec.gen[Customer]

  // ============================================
  // Order FSM - State & Events
  // ============================================

  /** Order states - the lifecycle of a pet purchase */
  enum OrderState extends MState derives JsonCodec:
    case Created(orderId: String, pet: Pet, customer: Customer)
    case PaymentPending(orderId: String, pet: Pet, customer: Customer)
    case PaymentProcessing(orderId: String, pet: Pet, customer: Customer, paymentId: String)
    case Paid(orderId: String, pet: Pet, customer: Customer, paymentId: String)
    case ShippingRequested(orderId: String, pet: Pet, customer: Customer, trackingId: String)
    case Shipped(orderId: String, pet: Pet, customer: Customer, trackingId: String)
    case Delivered(orderId: String, pet: Pet, customer: Customer)
    case Cancelled(orderId: String, reason: String)

  /** Order events - what happened to change state */
  enum OrderEvent extends MEvent derives JsonCodec:
    case OrderPlaced(orderId: String, pet: Pet, customer: Customer)
    case PaymentInitiated(paymentId: String)
    case PaymentSucceeded(paymentId: String, transactionRef: String)
    case PaymentFailed(paymentId: String, reason: String)
    case ShippingRequested(trackingId: String)
    case ShipmentDispatched(carrier: String, estimatedDelivery: String)
    case DeliveryConfirmed(signature: String)
    case OrderCancelled(reason: String)

  // ============================================
  // Commands - Work to be done by services
  // ============================================

  /** Commands represent work that needs reliable execution.
    *
    * Each command type maps to a service integration pattern:
    *   - Sync: ProcessPayment (blocks until done)
    *   - Async: RequestShipping (webhook callback)
    *   - Fire-and-forget: SendNotification
    */
  enum PetStoreCommand derives JsonCodec:
    // Synchronous - blocks until payment gateway responds
    case ProcessPayment(
        orderId: String,
        customerId: String,
        amount: BigDecimal,
        paymentMethod: String,
    )

    // Asynchronous - shipping service will call back via webhook
    case RequestShipping(
        orderId: String,
        petId: String,
        customerAddress: String,
        correlationId: String, // Used to match webhook callback
    )

    // Fire-and-forget with optional confirmation
    case SendNotification(
        orderId: String,
        customerEmail: String,
        notificationType: String, // "order_confirmed", "shipped", "delivered"
        messageId: String,
    )

    // Callback handlers - these complete async commands
    case ShippingCallback(
        correlationId: String,
        trackingNumber: String,
        carrier: String,
        estimatedDelivery: String,
        success: Boolean,
        error: Option[String],
    )

    case NotificationCallback(
        messageId: String,
        delivered: Boolean,
        error: Option[String],
    )
  end PetStoreCommand

  val commandCodec = CommandCodec.fromJson[PetStoreCommand]

  // ============================================
  // Fake Services - Simulating External Systems
  // ============================================

  /** Simulated Payment Gateway (Synchronous)
    *
    * Like Stripe/PayPal - you call it, it blocks, returns result.
    */
  trait PaymentGateway:
    def processPayment(
        customerId: String,
        amount: BigDecimal,
        method: String,
    ): ZIO[Any, PaymentError, PaymentResult]

  case class PaymentResult(
      transactionId: String,
      status: String,
      authCode: String,
  )

  enum PaymentError:
    case InsufficientFunds(message: String)
    case CardDeclined(message: String)
    case NetworkError(message: String)
    case FraudDetected(message: String)

  class FakePaymentGateway(
      successRate: Double = 0.9,
      latencyMs: Long = 50,
  ) extends PaymentGateway:
    private val random = new scala.util.Random()

    def processPayment(
        customerId: String,
        amount: BigDecimal,
        method: String,
    ): ZIO[Any, PaymentError, PaymentResult] =
      for
        // Simulate network latency
        _ <- ZIO.sleep(Duration.fromMillis(latencyMs))

        // Randomly succeed or fail
        result <-
          if random.nextDouble() < successRate then
            ZIO.succeed(
              PaymentResult(
                transactionId = s"txn-${UUID.randomUUID()}",
                status = "approved",
                authCode = s"AUTH-${random.nextInt(999999)}",
              )
            )
          else
            val errors = List(
              PaymentError.InsufficientFunds("Not enough balance"),
              PaymentError.CardDeclined("Card was declined"),
              PaymentError.NetworkError("Gateway timeout"),
            )
            ZIO.fail(errors(random.nextInt(errors.size)))
      yield result
  end FakePaymentGateway

  /** Simulated Shipping Service (Asynchronous with Webhook)
    *
    * You send a request, it returns immediately with a tracking ID. Later, the shipping service calls your webhook when
    * status changes.
    */
  trait ShippingService:
    def requestShipment(
        petId: String,
        address: String,
        correlationId: String,
    ): ZIO[Any, ShippingError, ShipmentRequest]

  case class ShipmentRequest(
      requestId: String,
      status: String,           // "accepted", "pending"
      estimatedCallback: String, // When to expect webhook
  )

  enum ShippingError:
    case InvalidAddress(message: String)
    case ServiceUnavailable(message: String)

  class FakeShippingService(
      callbackDelay: Duration,
      webhookHandler: PetStoreCommand.ShippingCallback => UIO[Unit],
  ) extends ShippingService:
    private val random = new scala.util.Random()

    def requestShipment(
        petId: String,
        address: String,
        correlationId: String,
    ): ZIO[Any, ShippingError, ShipmentRequest] =
      for
        requestId <- ZIO.succeed(s"ship-${UUID.randomUUID()}")

        // Schedule the webhook callback (simulates async processing)
        _ <- (ZIO.sleep(callbackDelay) *> webhookHandler(
          PetStoreCommand.ShippingCallback(
            correlationId = correlationId,
            trackingNumber = s"TRACK-${random.nextInt(999999)}",
            carrier = "PetExpress",
            estimatedDelivery = "2-3 business days",
            success = random.nextDouble() < 0.95,
            error = None,
          )
        )).forkDaemon // Fire and forget the callback
      yield ShipmentRequest(
        requestId = requestId,
        status = "accepted",
        estimatedCallback = "30 seconds",
      )
  end FakeShippingService

  /** Simulated Notification Service (Fire-and-Forget)
    *
    * Send email/SMS, optionally get delivery confirmation.
    */
  trait NotificationService:
    def send(
        email: String,
        notificationType: String,
        messageId: String,
    ): ZIO[Any, NotificationError, NotificationResult]

  case class NotificationResult(messageId: String, status: String)

  enum NotificationError:
    case InvalidEmail(message: String)
    case RateLimited(message: String)

  class FakeNotificationService(
      confirmationDelay: Duration,
      confirmationHandler: PetStoreCommand.NotificationCallback => UIO[Unit],
  ) extends NotificationService:
    private val random = new scala.util.Random()

    def send(
        email: String,
        notificationType: String,
        messageId: String,
    ): ZIO[Any, NotificationError, NotificationResult] =
      for
        // Simulate sending
        _ <- ZIO.sleep(Duration.fromMillis(10))

        // Schedule delivery confirmation callback
        _ <- (ZIO.sleep(confirmationDelay) *> confirmationHandler(
          PetStoreCommand.NotificationCallback(
            messageId = messageId,
            delivered = random.nextDouble() < 0.99,
            error = None,
          )
        )).forkDaemon
      yield NotificationResult(messageId, "queued")
  end FakeNotificationService

  // ============================================
  // Command Worker - Processes Commands
  // ============================================

  /** Command worker that processes pet store commands.
    *
    * Demonstrates different patterns:
    *   - Sync: Process and complete in one step
    *   - Async: Process, leave pending, complete on callback
    */
  class PetStoreCommandWorker(
      commandStore: CommandStore[String, PetStoreCommand],
      paymentGateway: PaymentGateway,
      shippingService: ShippingService,
      notificationService: NotificationService,
      // Track results for testing
      resultsRef: Ref[Map[String, Either[String, String]]],
  ):
    val workerId = s"worker-${UUID.randomUUID().toString.take(8)}"

    /** Process a single command based on its type */
    def processCommand(cmd: PendingCommand[String, PetStoreCommand]): ZIO[Any, Throwable, Unit] =
      cmd.command match
        case PetStoreCommand.ProcessPayment(orderId, customerId, amount, method) =>
          processPaymentCommand(cmd.id, orderId, customerId, amount, method)

        case PetStoreCommand.RequestShipping(orderId, petId, address, correlationId) =>
          processShippingCommand(cmd.id, orderId, petId, address, correlationId)

        case PetStoreCommand.SendNotification(orderId, email, notifType, messageId) =>
          processNotificationCommand(cmd.id, orderId, email, notifType, messageId)

        case callback: PetStoreCommand.ShippingCallback =>
          processShippingCallback(cmd.id, callback)

        case callback: PetStoreCommand.NotificationCallback =>
          processNotificationCallback(cmd.id, callback)

    /** Synchronous payment processing */
    private def processPaymentCommand(
        commandId: Long,
        orderId: String,
        customerId: String,
        amount: BigDecimal,
        method: String,
    ): ZIO[Any, Throwable, Unit] =
      paymentGateway.processPayment(customerId, amount, method).either.flatMap {
        case Right(result) =>
          // Payment succeeded - complete the command
          resultsRef.update(_.updated(orderId, Right(result.transactionId))) *>
            commandStore.complete(commandId).unit

        case Left(error) =>
          // Payment failed - determine if retryable
          val (errorMsg, shouldRetry) = error match
            case PaymentError.NetworkError(msg)      => (msg, true)
            case PaymentError.InsufficientFunds(msg) => (msg, false)
            case PaymentError.CardDeclined(msg)      => (msg, false)
            case PaymentError.FraudDetected(msg)     => (msg, false)

          if shouldRetry then
            // Retry in 5 seconds
            Clock.instant.flatMap { now =>
              commandStore.fail(commandId, errorMsg, Some(now.plusSeconds(5))).unit
            }
          else
            // Permanent failure - mark as failed with no retry
            resultsRef.update(_.updated(orderId, Left(errorMsg))) *>
              commandStore.fail(commandId, errorMsg, None).unit
      }

    /** Asynchronous shipping request */
    private def processShippingCommand(
        commandId: Long,
        @unused orderId: String,
        petId: String,
        address: String,
        correlationId: String,
    ): ZIO[Any, Throwable, Unit] =
      shippingService.requestShipment(petId, address, correlationId).either.flatMap {
        case Right(result) =>
          // Request accepted - complete this command
          // The actual shipping status comes via callback
          resultsRef.update(_.updated(s"shipping-$correlationId", Right(result.requestId))) *>
            commandStore.complete(commandId).unit

        case Left(error) =>
          val errorMsg = error match
            case ShippingError.InvalidAddress(msg)     => msg
            case ShippingError.ServiceUnavailable(msg) => msg

          // Retry on service unavailable
          error match
            case ShippingError.ServiceUnavailable(_) =>
              Clock.instant.flatMap { now =>
                commandStore.fail(commandId, errorMsg, Some(now.plusSeconds(10))).unit
              }
            case _ =>
              resultsRef.update(_.updated(s"shipping-$correlationId", Left(errorMsg))) *>
                commandStore.fail(commandId, errorMsg, None).unit
      }

    /** Fire-and-forget notification */
    private def processNotificationCommand(
        commandId: Long,
        @unused orderId: String,
        email: String,
        notifType: String,
        messageId: String,
    ): ZIO[Any, Throwable, Unit] =
      notificationService.send(email, notifType, messageId).either.flatMap {
        case Right(_) =>
          // Notification queued - complete immediately
          // Delivery confirmation comes via callback (but we don't wait)
          resultsRef.update(_.updated(s"notif-$messageId", Right("queued"))) *>
            commandStore.complete(commandId).unit

        case Left(error) =>
          val errorMsg = error match
            case NotificationError.InvalidEmail(msg) => msg
            case NotificationError.RateLimited(msg)  => msg

          // Rate limited - retry, invalid email - skip
          error match
            case NotificationError.RateLimited(_) =>
              Clock.instant.flatMap { now =>
                commandStore.fail(commandId, errorMsg, Some(now.plusSeconds(30))).unit
              }
            case _ =>
              commandStore.skip(commandId, errorMsg).unit
      }

    /** Handle shipping webhook callback */
    private def processShippingCallback(
        commandId: Long,
        callback: PetStoreCommand.ShippingCallback,
    ): ZIO[Any, Throwable, Unit] =
      if callback.success then
        resultsRef.update(
          _.updated(
            s"shipped-${callback.correlationId}",
            Right(s"${callback.carrier}:${callback.trackingNumber}"),
          )
        ) *>
          commandStore.complete(commandId).unit
      else
        resultsRef.update(
          _.updated(
            s"shipped-${callback.correlationId}",
            Left(callback.error.getOrElse("Unknown error")),
          )
        ) *>
          commandStore.fail(commandId, callback.error.getOrElse("Shipping failed"), None).unit

    /** Handle notification delivery callback */
    private def processNotificationCallback(
        commandId: Long,
        callback: PetStoreCommand.NotificationCallback,
    ): ZIO[Any, Throwable, Unit] =
      val status = if callback.delivered then "delivered" else "bounced"
      resultsRef.update(_.updated(s"notif-delivered-${callback.messageId}", Right(status))) *>
        commandStore.complete(commandId).unit

    /** Run the worker loop - claim and process commands */
    def run: ZIO[Any, Nothing, Unit] =
      (for
        now     <- Clock.instant
        claimed <- commandStore.claim(workerId, 10, Duration.fromSeconds(30), now)
        _       <- ZIO.foreach(claimed)(processCommand(_).catchAll { error =>
          ZIO.logError(s"Command processing error: ${error.getMessage}")
        })
      yield claimed.length).repeatWhile(_ >= 0).ignore
  end PetStoreCommandWorker

  // ============================================
  // Test Helpers
  // ============================================

  private def uniqueId(prefix: String) = s"$prefix-${UUID.randomUUID()}"

  // Test layers
  val xaLayer           = PostgresTestContainer.DataSourceProvider.default >>> Transactor.default
  val commandStoreLayer = xaLayer >>> PostgresCommandStore.layer[PetStoreCommand](commandCodec)

  // ============================================
  // Tests
  // ============================================

  def spec = suite("Pet Store Example")(
    test("synchronous payment processing - success flow") {
      for
        store      <- ZIO.service[CommandStore[String, PetStoreCommand]]
        resultsRef <- Ref.make(Map.empty[String, Either[String, String]])

        // Create fake payment gateway that always succeeds
        paymentGateway = new FakePaymentGateway(successRate = 1.0, latencyMs = 10)

        // Dummy services (not used in this test)
        shippingCallbackRef <- Ref.make(Option.empty[PetStoreCommand.ShippingCallback])
        notifCallbackRef    <- Ref.make(Option.empty[PetStoreCommand.NotificationCallback])
        shippingService = new FakeShippingService(
          Duration.fromMillis(100),
          cb => shippingCallbackRef.set(Some(cb)),
        )
        notificationService = new FakeNotificationService(
          Duration.fromMillis(100),
          cb => notifCallbackRef.set(Some(cb)),
        )

        // Create worker
        worker = new PetStoreCommandWorker(
          store,
          paymentGateway,
          shippingService,
          notificationService,
          resultsRef,
        )

        // Enqueue a payment command
        orderId = uniqueId("order")
        _ <- store.enqueue(
          orderId,
          PetStoreCommand.ProcessPayment(orderId, "customer-123", BigDecimal(99.99), "visa"),
          uniqueId("pay"),
        )

        // Start worker
        workerFiber <- worker.run.fork

        // Wait for processing
        _ <- ZIO.sleep(Duration.fromMillis(200))
        _ <- workerFiber.interrupt

        // Check results
        results <- resultsRef.get
        paymentResult = results.get(orderId)
      yield assertTrue(
        paymentResult.isDefined,
        paymentResult.get.isRight,
        paymentResult.get.toOption.get.startsWith("txn-"),
      )
    },
    test("synchronous payment processing - retry on network error") {
      for
        store           <- ZIO.service[CommandStore[String, PetStoreCommand]]
        attemptCountRef <- Ref.make(0)
        successRef      <- Ref.make(Option.empty[String])

        // Enqueue payment
        orderId        = uniqueId("retry-order")
        idempotencyKey = uniqueId("retry-pay")
        _ <- store.enqueue(
          orderId,
          PetStoreCommand.ProcessPayment(orderId, "customer-456", BigDecimal(50.00), "mastercard"),
          idempotencyKey,
        )

        // Custom inline processing with very short retry delay (10ms instead of 5s)
        workerId = s"retry-worker-${UUID.randomUUID().toString.take(8)}"
        _ <- (for
          now     <- Clock.instant
          claimed <- store.claim(workerId, 10, Duration.fromSeconds(30), now)
          // Only process our command
          ourCmd = claimed.find(_.idempotencyKey == idempotencyKey)
          _ <- ZIO.foreach(ourCmd) { cmd =>
            for
              count <- attemptCountRef.updateAndGet(_ + 1)
              _     <-
                if count < 3 then
                  // Fail with short retry delay (10ms)
                  Clock.instant.flatMap { failNow =>
                    store.fail(cmd.id, "Network timeout", Some(failNow.plusMillis(10)))
                  }
                else
                  // Success on 3rd attempt
                  store.complete(cmd.id) *> successRef.set(Some(s"txn-$count"))
            yield ()
          }
          // Wait for retry time to pass
          _ <- ZIO.sleep(Duration.fromMillis(20))
        yield claimed.length).repeatN(10) // Multiple rounds to handle retries

        // Check results
        attempts <- attemptCountRef.get
        success  <- successRef.get
      yield assertTrue(
        attempts == 3, // Took 3 attempts
        success.isDefined,
        success.get == "txn-3",
      )
    },
    test("asynchronous shipping with webhook callback") {
      for
        store      <- ZIO.service[CommandStore[String, PetStoreCommand]]
        resultsRef <- Ref.make(Map.empty[String, Either[String, String]])

        // Webhook callback handler - enqueues callback as new command
        callbackHandler = (callback: PetStoreCommand.ShippingCallback) =>
          store
            .enqueue(
              callback.correlationId,
              callback,
              uniqueId(s"ship-callback-${callback.correlationId}"),
            )
            .ignore

        paymentGateway      = new FakePaymentGateway()
        shippingService     = new FakeShippingService(Duration.fromMillis(50), callbackHandler)
        notificationService = new FakeNotificationService(Duration.fromMillis(100), _ => ZIO.unit)

        worker = new PetStoreCommandWorker(
          store,
          paymentGateway,
          shippingService,
          notificationService,
          resultsRef,
        )

        // Enqueue shipping request
        orderId       = uniqueId("ship-order")
        correlationId = uniqueId("correlation")
        _ <- store.enqueue(
          orderId,
          PetStoreCommand.RequestShipping(orderId, "pet-fluffy", "123 Main St", correlationId),
          uniqueId("ship-req"),
        )

        // Start worker
        workerFiber <- worker.run.fork

        // Wait for request + callback processing
        _ <- ZIO.sleep(Duration.fromMillis(500))
        _ <- workerFiber.interrupt

        // Check both request and callback were processed
        results <- resultsRef.get
        requestResult  = results.get(s"shipping-$correlationId")
        callbackResult = results.get(s"shipped-$correlationId")
      yield assertTrue(
        requestResult.exists(_.isRight), // Initial request succeeded
        callbackResult.isDefined,        // Callback was processed (may succeed or fail ~5% of time)
        // If callback succeeded, verify it has carrier info
        callbackResult.forall(r => r.isLeft || r.toOption.exists(_.contains("PetExpress"))),
      )
    },
    test("full order flow - reserve, pay, ship, notify") {
      for
        store      <- ZIO.service[CommandStore[String, PetStoreCommand]]
        resultsRef <- Ref.make(Map.empty[String, Either[String, String]])

        // Callback handlers that enqueue follow-up commands
        shippingCallbackHandler = (callback: PetStoreCommand.ShippingCallback) =>
          store
            .enqueue(
              callback.correlationId,
              callback,
              uniqueId(s"ship-cb-${callback.correlationId}"),
            )
            .ignore

        notifCallbackHandler = (callback: PetStoreCommand.NotificationCallback) =>
          store
            .enqueue(
              callback.messageId,
              callback,
              uniqueId(s"notif-cb-${callback.messageId}"),
            )
            .ignore

        paymentGateway      = new FakePaymentGateway(successRate = 1.0, latencyMs = 10)
        shippingService     = new FakeShippingService(Duration.fromMillis(50), shippingCallbackHandler)
        notificationService = new FakeNotificationService(Duration.fromMillis(50), notifCallbackHandler)

        worker = new PetStoreCommandWorker(
          store,
          paymentGateway,
          shippingService,
          notificationService,
          resultsRef,
        )

        // Create order data
        orderId       = uniqueId("full-order")
        correlationId = uniqueId("corr")
        messageId     = uniqueId("msg")

        // Enqueue the full workflow
        _ <- store.enqueue(
          orderId,
          PetStoreCommand.ProcessPayment(
            orderId,
            "customer-789",
            BigDecimal(149.99),
            "amex",
          ),
          uniqueId("pay"),
        )

        _ <- store.enqueue(
          orderId,
          PetStoreCommand.RequestShipping(
            orderId,
            "pet-whiskers",
            "456 Oak Ave",
            correlationId,
          ),
          uniqueId("ship"),
        )

        _ <- store.enqueue(
          orderId,
          PetStoreCommand.SendNotification(
            orderId,
            "customer@example.com",
            "order_confirmed",
            messageId,
          ),
          uniqueId("notif"),
        )

        // Start multiple workers for parallelism
        workers <- ZIO.foreach(1 to 3)(_ => worker.run.fork)

        // Wait for all processing including callbacks
        _ <- ZIO.sleep(Duration.fromMillis(1000))
        _ <- ZIO.foreach(workers)(_.interrupt)

        // Verify full flow completed
        results <- resultsRef.get
      yield assertTrue(
        // Payment processed
        results.get(orderId).exists(_.isRight),
        // Shipping requested
        results.get(s"shipping-$correlationId").exists(_.isRight),
        // Shipping callback processed
        results.get(s"shipped-$correlationId").exists(_.isRight),
        // Notification queued
        results.get(s"notif-$messageId").exists(_.isRight),
        // Notification delivery confirmed
        results.get(s"notif-delivered-$messageId").exists(_.isRight),
      )
    },
    test("multiple orders processed concurrently") {
      for
        store      <- ZIO.service[CommandStore[String, PetStoreCommand]]
        resultsRef <- Ref.make(Map.empty[String, Either[String, String]])

        shippingCallbackHandler = (cb: PetStoreCommand.ShippingCallback) =>
          store.enqueue(cb.correlationId, cb, uniqueId(s"cb-${cb.correlationId}")).ignore

        notifCallbackHandler = (cb: PetStoreCommand.NotificationCallback) =>
          store.enqueue(cb.messageId, cb, uniqueId(s"cb-${cb.messageId}")).ignore

        paymentGateway      = new FakePaymentGateway(successRate = 0.9, latencyMs = 20)
        shippingService     = new FakeShippingService(Duration.fromMillis(30), shippingCallbackHandler)
        notificationService = new FakeNotificationService(Duration.fromMillis(30), notifCallbackHandler)

        worker = new PetStoreCommandWorker(
          store,
          paymentGateway,
          shippingService,
          notificationService,
          resultsRef,
        )

        // Create 20 orders with full workflow
        orderIds <- ZIO.foreach(1 to 20) { i =>
          val orderId       = uniqueId(s"order-$i")
          val correlationId = uniqueId(s"corr-$i")
          val messageId     = uniqueId(s"msg-$i")

          for
            _ <- store.enqueue(
              orderId,
              PetStoreCommand.ProcessPayment(
                orderId,
                s"customer-$i",
                BigDecimal(50 + i),
                "visa",
              ),
              uniqueId(s"pay-$i"),
            )

            _ <- store.enqueue(
              orderId,
              PetStoreCommand.RequestShipping(
                orderId,
                s"pet-$i",
                s"$i Main St",
                correlationId,
              ),
              uniqueId(s"ship-$i"),
            )

            _ <- store.enqueue(
              orderId,
              PetStoreCommand.SendNotification(
                orderId,
                s"customer$i@test.com",
                "order_confirmed",
                messageId,
              ),
              uniqueId(s"notif-$i"),
            )
          yield (orderId, correlationId, messageId)
          end for
        }

        // Run 5 workers concurrently
        workers <- ZIO.foreach(1 to 5)(_ => worker.run.fork)

        // Wait for processing
        _ <- ZIO.sleep(Duration.fromMillis(2000))
        _ <- ZIO.foreach(workers)(_.interrupt)

        // Check results
        results <- resultsRef.get

        // Count successes (some payments may fail due to 90% success rate)
        paymentSuccesses = orderIds.count { case (orderId, _, _) =>
          results.get(orderId).exists(_.isRight)
        }
        shippingSuccesses = orderIds.count { case (_, correlationId, _) =>
          results.get(s"shipping-$correlationId").exists(_.isRight)
        }
        notificationSuccesses = orderIds.count { case (_, _, messageId) =>
          results.get(s"notif-$messageId").exists(_.isRight)
        }
      yield assertTrue(
        paymentSuccesses >= 15,     // At least 75% payments succeeded (90% rate)
        shippingSuccesses == 20,    // All shipping requests should succeed
        notificationSuccesses == 20, // All notifications should be queued
      )
    },
  ).provideShared(commandStoreLayer) @@ TestAspect.sequential @@ TestAspect.withLiveClock
end PetStoreExample
