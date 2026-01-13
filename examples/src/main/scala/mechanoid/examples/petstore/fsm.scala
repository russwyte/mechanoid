package mechanoid.examples.petstore

import zio.*
import zio.json.*
import mechanoid.*

// ============================================
// Order FSM - States and Events
// ============================================

/** Order lifecycle states - simple status indicators */
enum OrderState extends MState derives JsonCodec:
  case Created
  case PaymentProcessing
  case Paid
  case ShippingRequested
  case Shipped
  case Delivered
  case Cancelled

/** Order lifecycle events - rich events carry contextual data for audit trail
  *
  * With ordinal-based matching, any PaymentSucceeded("x") matches PaymentSucceeded("y"). This allows the event log to
  * capture meaningful data while FSM transitions work correctly.
  */
enum OrderEvent extends MEvent derives JsonCodec:
  case InitiatePayment(amount: BigDecimal, method: String)
  case PaymentSucceeded(transactionId: String)
  case PaymentFailed(reason: String)
  case RequestShipping(address: String)
  case ShipmentDispatched(trackingId: String, carrier: String, eta: String)
  case DeliveryConfirmed(timestamp: String)

// ============================================
// Order FSM Definition Factory
// ============================================

/** Factory for creating Order FSM definitions with customizable entry actions.
  *
  * This allows tests to inject custom actions to observe state transitions while using the same FSM structure as the
  * production app.
  */
object OrderFSM:
  /** Create an order FSM definition with entry actions.
    *
    * @param onPaymentProcessing
    *   Action to run when entering PaymentProcessing state
    * @param onPaid
    *   Action to run when entering Paid state
    * @param onShipped
    *   Action to run when entering Shipped state
    *
    * Note: This method is inline to preserve the expression trees of the action parameters, allowing the ExpressionName
    * macro to extract the actual function names (e.g., "enqueuePaymentCommand") rather than just the parameter names.
    */
  import mechanoid.dsl.TypedDSL

  inline def definition(
      inline onPaymentProcessing: ZIO[Any, Throwable, Unit],
      inline onPaid: ZIO[Any, Throwable, Unit],
      inline onShipped: ZIO[Any, Throwable, Unit],
  ): FSMDefinition[OrderState, OrderEvent, Nothing] =
    import OrderState.*, OrderEvent.*

    TypedDSL[OrderState, OrderEvent]
      // Created -> PaymentProcessing on InitiatePayment
      .when[Created.type]
      .on[InitiatePayment]
      .goto(PaymentProcessing)
      // PaymentProcessing -> Paid on PaymentSucceeded
      .when[PaymentProcessing.type]
      .on[PaymentSucceeded]
      .goto(Paid)
      // PaymentProcessing -> Cancelled on PaymentFailed
      .when[PaymentProcessing.type]
      .on[PaymentFailed]
      .goto(Cancelled)
      // Paid -> ShippingRequested on RequestShipping
      .when[Paid.type]
      .on[RequestShipping]
      .goto(ShippingRequested)
      // ShippingRequested -> Shipped on ShipmentDispatched
      .when[ShippingRequested.type]
      .on[ShipmentDispatched]
      .goto(Shipped)
      // Shipped -> Delivered on DeliveryConfirmed
      .when[Shipped.type]
      .on[DeliveryConfirmed]
      .goto(Delivered)
      // Entry actions
      .onState[PaymentProcessing.type]
      .onEntry(onPaymentProcessing)
      .done
      .onState[Paid.type]
      .onEntry(onPaid)
      .done
      .onState[Shipped.type]
      .onEntry(onShipped)
      .done
      .build
  end definition
end OrderFSM
