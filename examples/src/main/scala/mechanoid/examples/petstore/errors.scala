package mechanoid.examples.petstore

// ============================================
// Typed Error ADTs
// ============================================

/** Marker trait for errors that can be retried */
trait Retryable

sealed trait PaymentError
object PaymentError:
  case class CardDeclined(reason: String) extends PaymentError
  case object InsufficientFunds           extends PaymentError
  case object NetworkTimeout              extends PaymentError with Retryable
  case object FraudCheckFailed            extends PaymentError

case class PaymentSuccess(transactionId: String)

sealed trait ShippingError
object ShippingError:
  case class AddressInvalid(details: String) extends ShippingError
  case object ServiceUnavailable             extends ShippingError with Retryable
  case object DestinationUnreachable         extends ShippingError

case class ShipmentAccepted(trackingId: String, carrier: String, estimatedDelivery: String)

sealed trait NotificationError
object NotificationError:
  case object InvalidEmail           extends NotificationError
  case class Bounced(reason: String) extends NotificationError
  case object RateLimited            extends NotificationError with Retryable
