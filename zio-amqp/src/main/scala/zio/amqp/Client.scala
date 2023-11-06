package zio.amqp

import com.rabbitmq.client.AMQP.Queue.{ DeclareOk, PurgeOk }
import com.rabbitmq.client.{ Channel => RChannel, _ }

import zio.ZIO.attemptBlocking
import zio._
import zio.amqp.model._
import zio.stream.ZStream

import java.net.URI
import scala.jdk.CollectionConverters._
// import scala.collection.immutable.SortedMap
import java.util.concurrent.TimeoutException
import model.Confirmation

class ConfirmsPublisher(private[amqp] val channel: Channel, val queue: Queue[(Long, Confirmation)]) {

  /**
   * Declare a queue
   * @param queue
   *   Name of the queue. If left empty, a random queue name is used
   * @param durable
   *   True if we are declaring a durable queue (the queue will survive a server restart)
   * @param exclusive
   *   Exclusive to this connection
   * @param autoDelete
   *   True if we are declaring an autodelete queue (server will delete it when no longer in use)
   * @param arguments
   * @return
   *   The name of the created queue
   */
  def queueDeclare(
    queue: QueueName,
    durable: Boolean = false,
    exclusive: Boolean = false,
    autoDelete: Boolean = false,
    arguments: Map[String, AnyRef] = Map.empty
  ): ZIO[Any, Throwable, String] = channel.queueDeclare(queue, durable, exclusive, autoDelete, arguments)

  /**
   * Check if a queue exists
   * @param queue
   *   Name of the queue.
   * @return
   *   a declaration-confirm method to indicate the queue exists
   */
  def queueDeclarePassive(
    queue: QueueName
  ): ZIO[Any, Throwable, DeclareOk] = channel.queueDeclarePassive(queue)

  /**
   * Delete a queue
   *
   * @param queue
   *   Name of the queue
   * @param ifUnused
   *   True if the queue should be deleted only if not in use
   * @param ifEmpty
   *   True if the queue should be deleted only if empty
   */
  def queueDelete(
    queue: QueueName,
    ifUnused: Boolean = false,
    ifEmpty: Boolean = false
  ): ZIO[Any, Throwable, Unit] = channel.queueDelete(queue, ifUnused, ifEmpty)

  def exchangeDeclare(
    exchange: ExchangeName,
    `type`: ExchangeType,
    durable: Boolean = false,
    autoDelete: Boolean = false,
    internal: Boolean = false,
    arguments: Map[String, AnyRef] = Map.empty
  ): ZIO[Any, Throwable, Unit] = channel.exchangeDeclare(exchange, `type`, durable, autoDelete, internal, arguments)

  def exchangeDelete(
    exchange: ExchangeName,
    ifUnused: Boolean = false
  ): ZIO[Any, Throwable, Unit] = channel.exchangeDelete(exchange, ifUnused)

  def queueBind(
    queue: QueueName,
    exchange: ExchangeName,
    routingKey: RoutingKey,
    arguments: Map[String, AnyRef] = Map.empty
  ): ZIO[Any, Throwable, Unit] = channel.queueBind(queue, exchange, routingKey, arguments)

  /**
   * Publishes a message and semantically blocks waitings for a confirmation from the broker. This should be used for
   * single or low volume messages given it blocks.
   *
   * @param exchange
   *   Name of the exchange
   * @param body
   *   Message body
   * @param routingKey
   *   Routing key
   * @param mandatory
   *   Mandatory flag
   * @param immediate
   *   Immediate flag
   * @param props
   *   Message properties
   * @return
   *   Confirmation of the publish
   */
  def blockingPublish(
    exchange: ExchangeName,
    body: Array[Byte],
    routingKey: RoutingKey = RoutingKey(""),
    mandatory: Boolean = false,
    immediate: Boolean = false,
    props: AMQP.BasicProperties = new AMQP.BasicProperties()
  ): ZIO[Any, Throwable, Confirmation] =
    ZIO.logSpan("blockingPublish") {
      channel.withChannel { channel =>
        ZIO.attemptBlocking(channel.confirmSelect()) *>
          ZIO.attemptBlocking(
            channel.basicPublish(
              ExchangeName.unwrap(exchange),
              RoutingKey.unwrap(routingKey),
              mandatory,
              immediate,
              props,
              body
            )
          ) *> ZIO
            .attemptBlocking(channel.waitForConfirms)
            .map {
              case true  => model.Confirmation.Ack
              case false => model.Confirmation.Nack
            }
            .catchSome {
              case _: IllegalStateException => ZIO.succeed(model.Confirmation.NotEnabled)
              case _: TimeoutException      => ZIO.succeed(model.Confirmation.Timeout)
            }
            .tap(c => ZIO.log(s"Published message with confirmation $c"))
      }
    }

  def asyncPublish(
    exchange: ExchangeName,
    body: Array[Byte],
    routingKey: RoutingKey = RoutingKey(""),
    mandatory: Boolean = false,
    immediate: Boolean = false,
    props: AMQP.BasicProperties = new AMQP.BasicProperties()
  ): ZIO[Any, Throwable, Unit] =
    ZIO.logSpan("blockingPublish") {
      channel.withChannel { channel =>
        for {
          // _ <- ZIO.attemptBlocking(channel.getNextPublishSeqNo)
          _ <- ZIO
                 .attemptBlocking(
                   channel.basicPublish(
                     ExchangeName.unwrap(exchange),
                     RoutingKey.unwrap(routingKey),
                     mandatory,
                     immediate,
                     props,
                     body
                   )
                 )
          //  .tap(c => ZIO.log(s"Published message with confirmation $c"))
        } yield ()
      }
    }

  /**
   * Purges the contents of the given queue.
   *
   * @param queue
   *   the name of the queue
   * @return
   *   purge-confirm if the purge was executed successfully
   */
  def purgeQueue(queue: QueueName): ZIO[Any, Throwable, PurgeOk] = channel.purgeQueue(queue)
}

/**
 * Thread-safe access to a RabbitMQ Channel
 */
class Channel private[amqp] (channel: RChannel, access: Semaphore, pool: ZPool[Throwable, RChannel]) {

  /**
   * Declare a queue
   * @param queue
   *   Name of the queue. If left empty, a random queue name is used
   * @param durable
   *   True if we are declaring a durable queue (the queue will survive a server restart)
   * @param exclusive
   *   Exclusive to this connection
   * @param autoDelete
   *   True if we are declaring an autodelete queue (server will delete it when no longer in use)
   * @param arguments
   * @return
   *   The name of the created queue
   */
  def queueDeclare(
    queue: QueueName,
    durable: Boolean = false,
    exclusive: Boolean = false,
    autoDelete: Boolean = false,
    arguments: Map[String, AnyRef] = Map.empty
  ): ZIO[Any, Throwable, String] = withChannelBlocking(
    _.queueDeclare(
      QueueName.unwrap(queue),
      durable,
      exclusive,
      autoDelete,
      arguments.asJava
    )
  ).map(_.getQueue)

  /**
   * Check if a queue exists
   * @param queue
   *   Name of the queue.
   * @return
   *   a declaration-confirm method to indicate the queue exists
   */
  def queueDeclarePassive(
    queue: QueueName
  ): ZIO[Any, Throwable, DeclareOk] = withChannelBlocking(
    _.queueDeclarePassive(
      QueueName.unwrap(queue)
    )
  )

  /**
   * Delete a queue
   *
   * @param queue
   *   Name of the queue
   * @param ifUnused
   *   True if the queue should be deleted only if not in use
   * @param ifEmpty
   *   True if the queue should be deleted only if empty
   */
  def queueDelete(
    queue: QueueName,
    ifUnused: Boolean = false,
    ifEmpty: Boolean = false
  ): ZIO[Any, Throwable, Unit] = withChannelBlocking(
    _.queueDelete(
      QueueName.unwrap(queue),
      ifUnused,
      ifEmpty
    )
  ).unit

  def exchangeDeclare(
    exchange: ExchangeName,
    `type`: ExchangeType,
    durable: Boolean = false,
    autoDelete: Boolean = false,
    internal: Boolean = false,
    arguments: Map[String, AnyRef] = Map.empty
  ): ZIO[Any, Throwable, Unit] = withChannelBlocking(
    _.exchangeDeclare(
      ExchangeName.unwrap(exchange),
      `type`,
      durable,
      autoDelete,
      internal,
      arguments.asJava
    )
  ).unit

  def exchangeDelete(
    exchange: ExchangeName,
    ifUnused: Boolean = false
  ): ZIO[Any, Throwable, Unit] = withChannelBlocking(
    _.exchangeDelete(
      ExchangeName.unwrap(exchange),
      ifUnused
    )
  ).unit

  def queueBind(
    queue: QueueName,
    exchange: ExchangeName,
    routingKey: RoutingKey,
    arguments: Map[String, AnyRef] = Map.empty
  ): ZIO[Any, Throwable, Unit] = withChannelBlocking(
    _.queueBind(
      QueueName.unwrap(queue),
      ExchangeName.unwrap(exchange),
      RoutingKey.unwrap(routingKey),
      arguments.asJava
    )
  ).unit

  def basicQos(
    count: Int,
    global: Boolean = false
  ): ZIO[Any, Throwable, Unit] =
    withChannelBlocking(_.basicQos(count, global)).unit

  /**
   * Consume a stream of messages from a queue
   *
   * When the stream is completed, the AMQP consumption is cancelled
   *
   * @param queue
   * @param consumerTag
   * @param autoAck
   * @return
   */
  def consume(
    queue: QueueName,
    consumerTag: ConsumerTag,
    autoAck: Boolean = false
  ): ZStream[Any, Throwable, Delivery] =
    ZStream
      .asyncZIO[Any, Throwable, Delivery] { offer =>
        withChannel { c =>
          attemptBlocking {
            c.basicConsume(
              QueueName.unwrap(queue),
              autoAck,
              ConsumerTag.unwrap(consumerTag),
              new DeliverCallback                {
                override def handle(consumerTag: String, message: Delivery): Unit =
                  offer(ZIO.succeed(Chunk.single(message)))
              },
              new CancelCallback                 {
                override def handle(consumerTag: String): Unit = offer(ZIO.fail(None))
              },
              new ConsumerShutdownSignalCallback {
                override def handleShutdownSignal(consumerTag: String, sig: ShutdownSignalException): Unit =
                  offer(ZIO.fail(Some(sig)))
              }
            )
          }
        }
      }
      .ensuring {
        withChannel(c =>
          attemptBlocking(
            c.basicCancel(ConsumerTag.unwrap(consumerTag))
          )
        ).ignore
      }
      .retry(Schedule.stop)

  def ack(deliveryTag: DeliveryTag, multiple: Boolean = false): ZIO[Any, Throwable, Unit] =
    withChannel(c =>
      attemptBlocking(
        c.basicAck(deliveryTag, multiple)
      )
    )

  def ackMany(deliveryTags: Seq[DeliveryTag]): ZIO[Any, Throwable, Unit] =
    ack(deliveryTags.max[Long], multiple = true)

  def nack(
    deliveryTag: DeliveryTag,
    requeue: Boolean = false,
    multiple: Boolean = false
  ): ZIO[Any, Throwable, Unit] =
    withChannel(c =>
      attemptBlocking(
        c.basicNack(deliveryTag, multiple, requeue)
      )
    )

  def nackMany(deliveryTags: Seq[DeliveryTag], requeue: Boolean = false): ZIO[Any, Throwable, Unit] =
    nack(deliveryTags.max[Long], requeue, multiple = true)

  def publish(
    exchange: ExchangeName,
    body: Array[Byte],
    routingKey: RoutingKey = RoutingKey(""),
    mandatory: Boolean = false,
    immediate: Boolean = false,
    props: AMQP.BasicProperties = new AMQP.BasicProperties()
  ): ZIO[Any, Throwable, Unit] =
    ZIO.attempt(
      channel.basicPublish(
        ExchangeName.unwrap(exchange),
        RoutingKey.unwrap(routingKey),
        mandatory,
        immediate,
        props,
        body
      )
    )

  def publishPool(
    exchange: ExchangeName,
    body: Array[Byte],
    routingKey: RoutingKey = RoutingKey(""),
    mandatory: Boolean = false,
    immediate: Boolean = false,
    props: AMQP.BasicProperties = new AMQP.BasicProperties()
  ): ZIO[Any, Throwable, Unit] =
    ZIO.scoped {
      pool.get.flatMap { c =>
        ZIO.attempt(
          c.basicPublish(
            ExchangeName.unwrap(exchange),
            RoutingKey.unwrap(routingKey),
            mandatory,
            immediate,
            props,
            body
          )
        )
      }.orDie
    }

  /**
   * Returns the number of messages in a queue ready to be delivered to consumers. This method assumes the queue exists.
   * If it doesn't, the channels will be closed with an exception.
   *
   * @param queue
   *   the name of the queue
   * @return
   *   the number of messages in ready state
   */
  def messageCount(queue: QueueName): ZIO[Any, Throwable, Long] = withChannelBlocking { c =>
    c.messageCount(QueueName.unwrap(queue))
  }

  /**
   * Returns the number of consumers on a queue. This method assumes the queue exists. If it doesn't, the channel will
   * be closed with an exception.
   *
   * @param queue
   *   the name of the queue
   * @return
   *   the number of consumers
   */
  def consumerCount(queue: QueueName): ZIO[Any, Throwable, Long] = withChannelBlocking { c =>
    c.consumerCount(QueueName.unwrap(queue))
  }

  /**
   * Purges the contents of the given queue.
   *
   * @param queue
   *   the name of the queue
   * @return
   *   purge-confirm if the purge was executed successfully
   */
  def purgeQueue(queue: QueueName): ZIO[Any, Throwable, PurgeOk] = withChannelBlocking { c =>
    c.queuePurge(QueueName.unwrap(queue))
  }

  private[amqp] def withChannel[T](f: RChannel => Task[T]) =
    access.withPermit(f(channel))

  private[amqp] def withChannelBlocking[R, T](f: RChannel => T) =
    access.withPermit(attemptBlocking(f(channel)))
}

object Amqp {

  /**
   * Creates a Connection
   *
   * @param factory
   *   Connection factory
   * @return
   *   Connection as a managed resource
   */
  def connect(factory: ConnectionFactory): ZIO[Scope, Throwable, Connection] =
    ZIO.acquireRelease(attemptBlocking(factory.newConnection()))(c => ZIO.attempt(c.close()).orDie)

  def connect(uri: URI): ZIO[Scope, Throwable, Connection]               = {
    val factory = new ConnectionFactory()
    factory.setUri(uri)
    connect(factory)
  }
  def connect(amqpConfig: AMQPConfig): ZIO[Scope, Throwable, Connection] = {
    val factory = new ConnectionFactory()
    factory.setUri(amqpConfig.toUri)
    connect(factory)
  }

  /**
   * Creates a Channel that is safe for concurrent access
   *
   * @param connection
   * @return
   */

  def createChannel(connection: Connection): ZIO[Scope, Throwable, Channel] =
    (for {
      pool    <- ZPool.make(ZIO.attempt(connection.createChannel()), 10)
      channel <- ZIO.attempt(connection.createChannel())
      permit  <- Semaphore.make(1)
    } yield new Channel(channel, permit, pool))
      .withFinalizer(_.withChannel(c => attemptBlocking(c.close())).orDie)

  def createChannelPublisherConfirm(connection: Connection): ZIO[Scope, Throwable, ConfirmsPublisher] =
    (for {
      pool    <- ZPool.make(ZIO.attempt(connection.createChannel()), 10)
      channel <- ZIO.attempt(connection.createChannel())
      _       <- ZIO.attemptBlocking(channel.confirmSelect())
      queue   <- Queue.bounded[(Long, Confirmation)](2000)
      _       <- ZIO
                   .async[Any, Nothing, Boolean](callback =>
                     channel.addConfirmListener(
                       new ConfirmListener {
                         override def handleAck(deliveryTag: Long, multiple: Boolean): Unit =
                           callback(
                             queue.offer((deliveryTag, model.Confirmation.Ack))
                           )

                         override def handleNack(deliveryTag: Long, multiple: Boolean): Unit =
                           callback(
                             ZIO.when(multiple)(ZIO.log(s"Received multiple acks: $multiple")) *>
                               queue.offer((deliveryTag, model.Confirmation.Nack))
                           )
                       }
                     )
                   )
                   .forkDaemon
      permit  <- Semaphore.make(1)
    } yield new ConfirmsPublisher(new Channel(channel, permit, pool), queue))
      .withFinalizer(_.channel.withChannel(c => attemptBlocking(c.close())).orDie)
}
