package mechanoid.core

import scala.annotation.StaticAnnotation

/** Marks fields containing sensitive data (PII) that should be redacted in visualizations.
  *
  * When applied to a constructor parameter, that field's value will be shown as `{redacted}` instead of the actual value
  * when using `Redactor.redact()`.
  *
  * Usage:
  * {{{
  * case class ProcessPayment(
  *   orderId: Int,
  *   @sensitive customerName: String,  // Will show as "customerName={redacted}"
  *   @sensitive email: String,         // Will show as "email={redacted}"
  *   amount: BigDecimal,               // Will show as "amount=250.0"
  * )
  * }}}
  */
final class sensitive extends StaticAnnotation
