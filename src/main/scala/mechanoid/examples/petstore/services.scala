package mechanoid.examples.petstore

import zio.*

// ============================================
// Service Traits (Configurable)
// ============================================

/** Payment processor service trait */
trait PaymentProcessor:
  def processPayment(
      customerName: String,
      amount: BigDecimal,
      method: String,
  ): IO[PaymentError, PaymentSuccess]

object PaymentProcessor:
  /** Configuration for payment processor */
  case class Config(
      successRate: Double = 0.85,
      minDelayMs: Long = 125,
      maxDelayMs: Long = 375,
  )

  private val paymentErrors = List(
    PaymentError.CardDeclined("Generic decline"),
    PaymentError.InsufficientFunds,
    PaymentError.NetworkTimeout,
    PaymentError.FraudCheckFailed,
  )

  /** Create a configurable payment processor */
  def make(config: Config): PaymentProcessor =
    new PaymentProcessor:
      def processPayment(customerName: String, amount: BigDecimal, method: String): IO[PaymentError, PaymentSuccess] =
        for
          delay   <- Random.nextIntBounded((config.maxDelayMs - config.minDelayMs).toInt).map(_ + config.minDelayMs)
          _       <- ZIO.sleep(Duration.fromMillis(delay))
          success <- Random.nextDouble.map(_ < config.successRate)
          result  <-
            if success then Random.nextIntBounded(999999).map(n => PaymentSuccess(s"TXN-$n"))
            else Random.nextIntBounded(paymentErrors.length).flatMap(i => ZIO.fail(paymentErrors(i)))
        yield result

  /** Test layer with 100% success and minimal delay */
  def test(config: Config = Config(successRate = 1.0, minDelayMs = 1, maxDelayMs = 5)): ULayer[PaymentProcessor] =
    ZLayer.succeed(make(config))
end PaymentProcessor

/** Shipper service trait */
trait Shipper:
  def requestShipment(
      petName: String,
      customerName: String,
      address: String,
  ): IO[ShippingError, ShipmentAccepted]

object Shipper:
  /** Configuration for shipper */
  case class Config(
      successRate: Double = 0.95,
      minDelayMs: Long = 75,
      maxDelayMs: Long = 200,
  )

  private val carriers = List("PetExpress", "AnimalCare Logistics", "FurryFriends Delivery")

  private val shippingErrors = List(
    ShippingError.AddressInvalid("Could not validate address"),
    ShippingError.ServiceUnavailable,
    ShippingError.DestinationUnreachable,
  )

  /** Create a configurable shipper */
  def make(config: Config): Shipper =
    new Shipper:
      def requestShipment(petName: String, customerName: String, address: String): IO[ShippingError, ShipmentAccepted] =
        for
          delay   <- Random.nextIntBounded((config.maxDelayMs - config.minDelayMs).toInt).map(_ + config.minDelayMs)
          _       <- ZIO.sleep(Duration.fromMillis(delay))
          success <- Random.nextDouble.map(_ < config.successRate)
          result  <-
            if success then
              for
                trackNum   <- Random.nextIntBounded(999999)
                carrierIdx <- Random.nextIntBounded(carriers.length)
                days       <- Random.nextIntBounded(5).map(_ + 2)
              yield ShipmentAccepted(s"TRACK-$trackNum", carriers(carrierIdx), s"$days business days")
            else Random.nextIntBounded(shippingErrors.length).flatMap(i => ZIO.fail(shippingErrors(i)))
        yield result

  /** Test layer with 100% success and minimal delay */
  def test(config: Config = Config(successRate = 1.0, minDelayMs = 1, maxDelayMs = 5)): ULayer[Shipper] =
    ZLayer.succeed(make(config))
end Shipper

/** Notification service trait */
trait NotificationService:
  def sendNotification(
      email: String,
      notifType: String,
  ): IO[NotificationError, Unit]

object NotificationService:
  /** Configuration for notification service */
  case class Config(
      successRate: Double = 0.98,
      minDelayMs: Long = 25,
      maxDelayMs: Long = 75,
  )

  private val notificationErrors = List(
    NotificationError.InvalidEmail,
    NotificationError.Bounced("Mailbox full"),
    NotificationError.RateLimited,
  )

  /** Create a configurable notification service */
  def make(config: Config): NotificationService =
    new NotificationService:
      def sendNotification(email: String, notifType: String): IO[NotificationError, Unit] =
        for
          delay   <- Random.nextIntBounded((config.maxDelayMs - config.minDelayMs).toInt).map(_ + config.minDelayMs)
          _       <- ZIO.sleep(Duration.fromMillis(delay))
          success <- Random.nextDouble.map(_ < config.successRate)
          _       <-
            if success then ZIO.unit
            else Random.nextIntBounded(notificationErrors.length).flatMap(i => ZIO.fail(notificationErrors(i)))
        yield ()

  /** Test layer with 100% success and minimal delay */
  def test(config: Config = Config(successRate = 1.0, minDelayMs = 1, maxDelayMs = 5)): ULayer[NotificationService] =
    ZLayer.succeed(make(config))
end NotificationService
