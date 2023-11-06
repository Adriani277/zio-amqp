package zio.amqp.model

sealed trait Confirmation extends Product with Serializable
object Confirmation {
  case object Ack        extends Confirmation
  case object Nack       extends Confirmation
  case object Timeout    extends Confirmation
  case object NotEnabled extends Confirmation
}
