package mechanoid.examples.petstore

import zio.*
import zio.json.*
import mechanoid.Finite
import mechanoid.machine.*

// ============================================
// Order FSM - States and Events
// ============================================

/** Order lifecycle states - simple status indicators */
enum OrderState derives JsonCodec:
  case Created
  case PaymentProcessing
  case Paid
  case ShippingRequested
  case Shipped
  case Delivered
  case Cancelled

/** Order lifecycle events with rich metadata.
  *
  * Events carry all context needed for command generation. This enables the FSM to declaratively emit commands using
  * the `emitting` pattern without needing external lookups.
  *
  * The suite-style DSL uses `event[T]` to match on TYPE (shape), ignoring the parameter values. This allows the Machine
  * definition to be declarative while runtime events carry actual data.
  *
  * Timeout events are simple case objects - they're fired automatically by the FSM runtime when a state's timeout
  * expires. Define handlers for them like any other event.
  *
  * Example:
  * {{{
  * // DSL matches ANY PaymentSucceeded by type
  * PaymentProcessing via event[PaymentSucceeded] to Paid emitting { case (e: PaymentSucceeded, _) =>
  *   List(PetStoreCommand.RequestShipping(e.orderId, e.petName, e.customerName, e.customerAddress, e.correlationId))
  * }
  *
  * // Timeout handling - simple case object
  * PaymentProcessing via PaymentTimeout to Cancelled
  *
  * // At runtime, send with actual transaction data
  * fsm.send(PaymentSucceeded(orderId, txnId, customerId, customerName, ...))
  * }}}
  */
enum OrderEvent derives JsonCodec:
  /** Initiate payment - carries full order context for command generation */
  case InitiatePayment(
      orderId: Int,
      customerId: String,
      customerName: String,
      customerEmail: String,
      customerAddress: String,
      petName: String,
      amount: BigDecimal,
      paymentMethod: String,
      correlationId: String,
      messageId: String,
  )

  /** Payment completed - carries context for shipping/notification commands */
  case PaymentSucceeded(
      orderId: Int,
      transactionId: String,
      customerId: String,
      customerName: String,
      customerEmail: String,
      customerAddress: String,
      petName: String,
      correlationId: String,
      messageId: String,
  )

  /** Payment failed with error reason */
  case PaymentFailed(orderId: Int, reason: String)

  /** Request shipping to an address */
  case RequestShipping(orderId: Int, address: String)

  /** Shipment dispatched - carries context for shipped notification */
  case ShipmentDispatched(
      orderId: Int,
      trackingId: String,
      carrier: String,
      eta: String,
      customerEmail: String,
      customerName: String,
      petName: String,
      messageId: String,
  )

  /** Delivery confirmed at a timestamp */
  case DeliveryConfirmed(orderId: Int, timestamp: String)

  // ═══════════════════════════════════════════════════════════════════
  // Timeout events - fired automatically when state timeout expires
  // ═══════════════════════════════════════════════════════════════════

  /** Payment processing timed out - order will be cancelled */
  case PaymentTimeout

  /** Shipping request timed out - escalate to operations */
  case ShippingTimeout
end OrderEvent

/** Order FSM with declarative command emission and timeouts.
  *
  * Commands are declared as part of the transition specification using `emitting`. The FSM runtime generates commands
  * and returns them in TransitionOutcome. The caller is responsible for enqueuing commands to a command store.
  *
  * Timeouts are configured using the `@@ Aspect.timeout(duration, event)` syntax on transitions. When the FSM enters
  * the target state, a timer starts. If no other event fires before the timeout, the specified event is automatically
  * sent to the FSM.
  *
  * This pattern separates concerns:
  *   - FSM defines WHAT commands to emit (declarative)
  *   - FSM defines WHEN to timeout and what event to fire
  *   - Caller decides HOW to handle commands (enqueue, process, etc.)
  *
  * Note: The `emitting` lambda receives the parent event type (OrderEvent), so we pattern match to extract the specific
  * event fields.
  *
  * This example uses the `assemblyAll` block syntax which allows:
  *   - Local helper vals for command factory functions
  *   - Clean separation of concerns
  *   - No commas between transition definitions
  */
object OrderFSM:
  import OrderState.*
  import OrderEvent.{
    InitiatePayment,
    PaymentSucceeded,
    PaymentFailed,
    ShipmentDispatched,
    DeliveryConfirmed,
    PaymentTimeout,
    ShippingTimeout,
  }
  // Don't import OrderEvent.RequestShipping to avoid ambiguity with PetStoreCommand.RequestShipping

  val machine = Machine(assemblyAll[OrderState, OrderEvent]:
    // ═══════════════════════════════════════════════════════════════════
    // Command factory helpers - local vals defined at the top of the block
    // ═══════════════════════════════════════════════════════════════════

    /** Build ProcessPayment command from InitiatePayment event. */
    val buildPaymentCommand: (OrderEvent, OrderState) => List[PetStoreCommand] = {
      case (e: InitiatePayment, _) =>
        List(
          PetStoreCommand.ProcessPayment(
            orderId = e.orderId,
            customerId = e.customerId,
            customerName = e.customerName,
            petName = e.petName,
            amount = e.amount,
            paymentMethod = e.paymentMethod,
          )
        )
      case _ => Nil
    }

    /** Build shipping + confirmation commands from PaymentSucceeded event. */
    val buildPaidCommands: (OrderEvent, OrderState) => List[PetStoreCommand] = {
      case (e: PaymentSucceeded, _) =>
        List(
          PetStoreCommand.RequestShipping(
            orderId = e.orderId,
            petName = e.petName,
            customerName = e.customerName,
            customerAddress = e.customerAddress,
            correlationId = e.correlationId,
          ),
          PetStoreCommand.SendNotification(
            orderId = e.orderId,
            customerEmail = e.customerEmail,
            customerName = e.customerName,
            petName = e.petName,
            notificationType = "order_confirmed",
            messageId = e.messageId,
          ),
        )
      case _ => Nil
    }

    /** Build shipped notification command from ShipmentDispatched event. */
    val buildShippedNotification: (OrderEvent, OrderState) => List[PetStoreCommand] = {
      case (e: ShipmentDispatched, _) =>
        List(
          PetStoreCommand.SendNotification(
            orderId = e.orderId,
            customerEmail = e.customerEmail,
            customerName = e.customerName,
            petName = e.petName,
            notificationType = "shipped",
            messageId = s"${e.messageId}-shipped",
          )
        )
      case _ => Nil
    }

    // ═══════════════════════════════════════════════════════════════════
    // State machine transitions - clean and readable with infix emitting
    // ═══════════════════════════════════════════════════════════════════

    // Created -> PaymentProcessing: emit ProcessPayment command
    // Payment must complete within 5 minutes or order is cancelled
    (Created via event[InitiatePayment] to PaymentProcessing emitting buildPaymentCommand) @@ Aspect.timeout(
      5.minutes,
      PaymentTimeout,
    )

    // PaymentProcessing -> Paid: emit RequestShipping + SendNotification
    PaymentProcessing via event[PaymentSucceeded] to Paid emitting buildPaidCommands

    // PaymentProcessing -> Cancelled: payment failed or timed out
    PaymentProcessing via event[PaymentFailed] to Cancelled
    PaymentProcessing via OrderEvent.PaymentTimeout to Cancelled

    // Paid -> ShippingRequested: shipping must be dispatched within 24 hours
    (Paid via event[OrderEvent.RequestShipping] to ShippingRequested) @@ Aspect.timeout(
      24.hours,
      OrderEvent.ShippingTimeout,
    )

    // ShippingRequested -> Shipped: emit shipped notification
    ShippingRequested via event[ShipmentDispatched] to Shipped emitting buildShippedNotification

    // ShippingRequested timeout: escalate to operations (stay in state, but notify)
    ShippingRequested via OrderEvent.ShippingTimeout to stay

    // Shipped -> Delivered: no commands
    Shipped via event[DeliveryConfirmed] to Delivered)

end OrderFSM
