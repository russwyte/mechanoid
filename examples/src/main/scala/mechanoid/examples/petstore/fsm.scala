package mechanoid.examples.petstore

import zio.*
import mechanoid.Finite
import mechanoid.machine.*

// ============================================
// Order FSM - States and Events
// ============================================

/** Order lifecycle states - simple status indicators */
enum OrderState derives Finite:
  case Created
  case PaymentProcessing
  case Paid
  case ShippingRequested
  case Shipped
  case Delivered
  case Cancelled

/** Order lifecycle events with rich metadata.
  *
  * Events carry all context needed for effects. The suite-style DSL uses `event[T]` to match on TYPE (shape), ignoring
  * the parameter values. This allows the Machine definition to be declarative while runtime events carry actual data.
  *
  * Timeout events are simple case objects - they're fired automatically by the FSM runtime when a state's timeout
  * expires. Define handlers for them like any other event.
  *
  * Example:
  * {{{
  * // DSL matches ANY PaymentSucceeded by type
  * (PaymentProcessing via event[PaymentSucceeded] to Paid)
  *   .onEntry { (e, _) => ZIO.logInfo(s"Payment succeeded: ${e.transactionId}") }
  *
  * // Timeout handling - simple case object
  * PaymentProcessing via PaymentTimeout to Cancelled
  *
  * // At runtime, send with actual transaction data
  * fsm.send(PaymentSucceeded(orderId, txnId, customerId, customerName, ...))
  * }}}
  */
enum OrderEvent derives Finite:
  /** Initiate payment - carries full order context */
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

  /** Payment completed - carries context for shipping/notification */
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

  /** Shipment dispatched - carries tracking info */
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

/** Order FSM with timeouts and per-transition effects.
  *
  * Effects are declared as part of the transition specification using `.onEntry` (sync) or `.producing` (async). Entry
  * effects run synchronously when the transition occurs. Producing effects fork asynchronously and send their produced
  * events back to the FSM.
  *
  * Timeouts are configured using the `@@ Aspect.timeout(duration, event)` syntax on transitions. When the FSM enters
  * the target state, a timer starts. If no other event fires before the timeout, the specified event is automatically
  * sent to the FSM.
  *
  * For crash-resilient side effects (like payment processing), consider:
  *   - Using `.producing` effects with timeout fallback
  *   - Implementing your own transactional outbox pattern
  *   - Using event replay for recovery
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

  val machine: Machine[OrderState, OrderEvent] = Machine(assemblyAll[OrderState, OrderEvent]:
    // ═══════════════════════════════════════════════════════════════════
    // State machine transitions with effects
    // ═══════════════════════════════════════════════════════════════════

    // Created -> PaymentProcessing: log payment initiation
    // Payment must complete within 5 minutes or order is cancelled
    (Created via event[InitiatePayment] to PaymentProcessing)
      .onEntry { (e, _) =>
        e match
          case ip: InitiatePayment =>
            ZIO.logInfo(s"Processing payment for order ${ip.orderId}: ${ip.amount} via ${ip.paymentMethod}")
          case _ => ZIO.unit
      } @@ Aspect.timeout(5.minutes, PaymentTimeout)

    // PaymentProcessing -> Paid: log success and notify
    (PaymentProcessing via event[PaymentSucceeded] to Paid)
      .onEntry { (e, _) =>
        e match
          case ps: PaymentSucceeded =>
            ZIO.logInfo(s"Payment succeeded for order ${ps.orderId}, txn: ${ps.transactionId}")
          case _ => ZIO.unit
      }

    // PaymentProcessing -> Cancelled: payment failed or timed out
    (PaymentProcessing via event[PaymentFailed] to Cancelled)
      .onEntry { (e, _) =>
        e match
          case pf: PaymentFailed =>
            ZIO.logWarning(s"Payment failed for order ${pf.orderId}: ${pf.reason}")
          case _ => ZIO.unit
      }

    (PaymentProcessing via OrderEvent.PaymentTimeout to Cancelled)
      .onEntry { (_, _) =>
        ZIO.logWarning("Payment timed out, order cancelled")
      }

    // Paid -> ShippingRequested: shipping must be dispatched within 24 hours
    (Paid via event[OrderEvent.RequestShipping] to ShippingRequested)
      .onEntry { (e, _) =>
        e match
          case rs: OrderEvent.RequestShipping =>
            ZIO.logInfo(s"Shipping requested for order ${rs.orderId} to ${rs.address}")
          case _ => ZIO.unit
      } @@ Aspect.timeout(24.hours, OrderEvent.ShippingTimeout)

    // ShippingRequested -> Shipped: log shipment
    (ShippingRequested via event[ShipmentDispatched] to Shipped)
      .onEntry { (e, _) =>
        e match
          case sd: ShipmentDispatched =>
            ZIO.logInfo(s"Order ${sd.orderId} shipped via ${sd.carrier}, tracking: ${sd.trackingId}")
          case _ => ZIO.unit
      }

    // ShippingRequested timeout: escalate to operations (stay in state, but notify)
    (ShippingRequested via OrderEvent.ShippingTimeout to stay)
      .onEntry { (_, _) =>
        ZIO.logWarning("Shipping timeout - escalating to operations")
      }

    // Shipped -> Delivered: log delivery
    (Shipped via event[DeliveryConfirmed] to Delivered)
      .onEntry { (e, _) =>
        e match
          case dc: DeliveryConfirmed =>
            ZIO.logInfo(s"Order ${dc.orderId} delivered at ${dc.timestamp}")
          case _ => ZIO.unit
      })

end OrderFSM
