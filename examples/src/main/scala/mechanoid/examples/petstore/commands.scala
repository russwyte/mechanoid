package mechanoid.examples.petstore

import zio.json.*
import mechanoid.core.sensitive

// ============================================
// Commands
// ============================================

enum PetStoreCommand derives JsonCodec:
  case ProcessPayment(
      orderId: Int,
      @sensitive customerId: String,
      @sensitive customerName: String,
      petName: String,
      amount: BigDecimal,
      @sensitive paymentMethod: String,
  )
  case RequestShipping(
      orderId: Int,
      petName: String,
      @sensitive customerName: String,
      @sensitive customerAddress: String,
      correlationId: String,
  )
  case SendNotification(
      orderId: Int,
      @sensitive customerEmail: String,
      @sensitive customerName: String,
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
