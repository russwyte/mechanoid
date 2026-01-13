package mechanoid.examples.petstore

import zio.*
import zio.json.*
import mechanoid.core.{MState, MEvent, ActionFailedError}
import mechanoid.machine.*

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

/** Order lifecycle events with rich metadata.
  *
  * Events carry meaningful data that can be used for:
  *   - Audit trails and logging
  *   - Command processing with real transaction details
  *   - Debugging and visualization
  *
  * The suite-style DSL uses `event[T]` to match on TYPE (shape), ignoring the parameter values. This allows the Machine
  * definition to be declarative while runtime events carry actual data.
  *
  * Example:
  * {{{
  * // DSL matches ANY PaymentSucceeded by type
  * PaymentProcessing via event[PaymentSucceeded] to Paid
  *
  * // At runtime, send with actual transaction data
  * fsm.send(PaymentSucceeded("TXN-12345"))
  * }}}
  */
enum OrderEvent extends MEvent derives JsonCodec:
  /** Initiate payment with amount and payment method */
  case InitiatePayment(amount: BigDecimal, method: String)

  /** Payment completed successfully with transaction ID */
  case PaymentSucceeded(transactionId: String)

  /** Payment failed with error reason */
  case PaymentFailed(reason: String)

  /** Request shipping to an address */
  case RequestShipping(address: String)

  /** Shipment dispatched with tracking info */
  case ShipmentDispatched(trackingId: String, carrier: String, eta: String)

  /** Delivery confirmed at a timestamp */
  case DeliveryConfirmed(timestamp: String)
end OrderEvent

object OrderFSM:
  import OrderState.*, OrderEvent.*

  val machine: Machine[OrderState, OrderEvent, Nothing] =
    build[OrderState, OrderEvent](
      Created via event[InitiatePayment] to PaymentProcessing,
      PaymentProcessing via event[PaymentSucceeded] to Paid,
      PaymentProcessing via event[PaymentFailed] to Cancelled,
      Paid via event[RequestShipping] to ShippingRequested,
      ShippingRequested via event[ShipmentDispatched] to Shipped,
      Shipped via event[DeliveryConfirmed] to Delivered,
    )

  /** Create an order FSM definition with entry actions.
    *
    * @param onPaymentProcessing
    *   Action to run when entering PaymentProcessing state
    * @param onPaid
    *   Action to run when entering Paid state
    * @param onShipped
    *   Action to run when entering Shipped state
    */
  def definition(
      onPaymentProcessing: ZIO[Any, Throwable, Unit],
      onPaid: ZIO[Any, Throwable, Unit],
      onShipped: ZIO[Any, Throwable, Unit],
  ): Machine[OrderState, OrderEvent, Nothing] =
    // Entry actions trigger side effects when entering specific states
    machine
      .withEntry(PaymentProcessing)(onPaymentProcessing.mapError(ActionFailedError(_)))
      .withEntry(Paid)(onPaid.mapError(ActionFailedError(_)))
      .withEntry(Shipped)(onShipped.mapError(ActionFailedError(_)))
  end definition

end OrderFSM
