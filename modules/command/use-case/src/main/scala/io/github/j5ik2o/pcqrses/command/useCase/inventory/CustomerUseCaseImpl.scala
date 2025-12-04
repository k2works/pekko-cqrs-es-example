package io.github.j5ik2o.pcqrses.command.useCase.inventory

import io.github.j5ik2o.pcqrses.command.domain.inventory.{
  CustomerCode,
  CustomerId,
  CustomerName,
  CustomerType
}
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.contract.inventory.CustomerProtocol
import io.github.j5ik2o.pcqrses.infrastructure.effect.PekkoInterop
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorRef, Scheduler}
import org.apache.pekko.util.Timeout
import org.slf4j.LoggerFactory
import zio.{IO, Task, ZIO}

import scala.concurrent.ExecutionContext

/**
  * CustomerUseCaseの実装クラス
  *
  * Pekkoアクターを使用して取引先管理のビジネスロジックを実行する
  */
private[inventory] final class CustomerUseCaseImpl(
    customerAggregateRef: ActorRef[CustomerProtocol.Command]
)(implicit
    timeout: Timeout,
    scheduler: Scheduler,
    ec: ExecutionContext
) extends CustomerUseCase {

  private val logger = LoggerFactory.getLogger(this.getClass)

  override def createCustomer(
      customerCode: CustomerCode,
      name: CustomerName,
      customerType: CustomerType
  ): IO[CustomerUseCaseError, CustomerId] =
    for {
      _ <- ZIO.succeed(
        logger.info(s"Creating Customer with customerCode: ${customerCode.value}")
      )
      customerId <- ZIO.succeed(CustomerId.generate())
      reply <- askActor[CustomerProtocol.CreateCustomerReply] { replyTo =>
        CustomerProtocol.CreateCustomer(
          id = customerId,
          customerCode = customerCode,
          name = name,
          customerType = customerType,
          replyTo = replyTo
        )
      }.mapError(e =>
        CustomerUseCaseError.UnexpectedError(
          s"Failed to communicate with actor: ${e.getMessage}",
          Some(e)
        )
      )
      result <- reply match {
        case CustomerProtocol.CreateCustomerSucceeded(id) =>
          ZIO.succeed(logger.info(s"Customer creation succeeded for ID: ${id.asString}")) *>
            ZIO.succeed(id)
      }
    } yield result

  override def updateCustomer(
      customerId: CustomerId,
      newName: CustomerName,
      newCustomerType: CustomerType
  ): IO[CustomerUseCaseError, CustomerId] =
    for {
      _ <- ZIO.succeed(
        logger.info(s"Updating Customer with ID: ${customerId.asString}")
      )
      reply <- askActor[CustomerProtocol.UpdateCustomerReply] { replyTo =>
        CustomerProtocol.UpdateCustomer(
          id = customerId,
          newName = newName,
          newCustomerType = newCustomerType,
          replyTo = replyTo
        )
      }.mapError(e =>
        CustomerUseCaseError.UnexpectedError(
          s"Failed to communicate with actor: ${e.getMessage}",
          Some(e)
        )
      )
      result <- reply match {
        case CustomerProtocol.UpdateCustomerSucceeded(id) =>
          ZIO.succeed(logger.info(s"Customer update succeeded for ID: ${id.asString}")) *>
            ZIO.succeed(id)
        case CustomerProtocol.UpdateCustomerFailed(id, reason) =>
          ZIO.fail(CustomerUseCaseError.UpdateFailed(s"Update failed for ID ${id.asString}: $reason"))
      }
    } yield result

  override def deactivateCustomer(
      customerId: CustomerId
  ): IO[CustomerUseCaseError, CustomerId] =
    for {
      _ <- ZIO.succeed(
        logger.info(s"Deactivating Customer with ID: ${customerId.asString}")
      )
      reply <- askActor[CustomerProtocol.DeactivateCustomerReply] { replyTo =>
        CustomerProtocol.DeactivateCustomer(
          id = customerId,
          replyTo = replyTo
        )
      }.mapError(e =>
        CustomerUseCaseError.UnexpectedError(
          s"Failed to communicate with actor: ${e.getMessage}",
          Some(e)
        )
      )
      result <- reply match {
        case CustomerProtocol.DeactivateCustomerSucceeded(id) =>
          ZIO.succeed(logger.info(s"Customer deactivate succeeded for ID: ${id.asString}")) *>
            ZIO.succeed(id)
        case CustomerProtocol.DeactivateCustomerFailed(id, reason) =>
          ZIO.fail(
            CustomerUseCaseError.DeactivateFailed(s"Deactivate failed for ID ${id.asString}: $reason")
          )
      }
    } yield result

  override def reactivateCustomer(
      customerId: CustomerId
  ): IO[CustomerUseCaseError, CustomerId] =
    for {
      _ <- ZIO.succeed(
        logger.info(s"Reactivating Customer with ID: ${customerId.asString}")
      )
      reply <- askActor[CustomerProtocol.ReactivateCustomerReply] { replyTo =>
        CustomerProtocol.ReactivateCustomer(
          id = customerId,
          replyTo = replyTo
        )
      }.mapError(e =>
        CustomerUseCaseError.UnexpectedError(
          s"Failed to communicate with actor: ${e.getMessage}",
          Some(e)
        )
      )
      result <- reply match {
        case CustomerProtocol.ReactivateCustomerSucceeded(id) =>
          ZIO.succeed(logger.info(s"Customer reactivate succeeded for ID: ${id.asString}")) *>
            ZIO.succeed(id)
        case CustomerProtocol.ReactivateCustomerFailed(id, reason) =>
          ZIO.fail(
            CustomerUseCaseError.ReactivateFailed(s"Reactivate failed for ID ${id.asString}: $reason")
          )
      }
    } yield result

  private def askActor[R](
      createMessage: ActorRef[R] => CustomerProtocol.Command
  ): Task[R] =
    PekkoInterop.fromFuture {
      customerAggregateRef.ask(createMessage)
    }
}
