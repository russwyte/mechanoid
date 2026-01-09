package mechanoid.examples.petstore

import zio.json.*

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
