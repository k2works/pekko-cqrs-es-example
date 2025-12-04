package io.github.j5ik2o.pcqrses.command.useCase.inventory

import io.github.j5ik2o.pcqrses.command.domain.inventory.{
  CategoryCode,
  ProductCode,
  ProductId,
  ProductName,
  StorageCondition
}
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.contract.inventory.ProductProtocol
import io.github.j5ik2o.pcqrses.infrastructure.effect.PekkoInterop
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorRef, Scheduler}
import org.apache.pekko.util.Timeout
import org.slf4j.LoggerFactory
import zio.{IO, Task, ZIO}

import scala.concurrent.ExecutionContext

/**
  * ProductUseCaseの実装クラス
  *
  * Pekkoアクターを使用して商品管理のビジネスロジックを実行する
  */
private[inventory] final class ProductUseCaseImpl(
    productAggregateRef: ActorRef[ProductProtocol.Command]
)(implicit
    timeout: Timeout,
    scheduler: Scheduler,
    ec: ExecutionContext
) extends ProductUseCase {

  private val logger = LoggerFactory.getLogger(this.getClass)

  override def createProduct(
      productCode: ProductCode,
      name: ProductName,
      categoryCode: CategoryCode,
      storageCondition: StorageCondition
  ): IO[ProductUseCaseError, ProductId] =
    for {
      _ <- ZIO.succeed(
        logger.info(s"Creating Product with productCode: ${productCode.value}")
      )
      productId <- ZIO.succeed(ProductId.generate())
      reply <- askActor[ProductProtocol.CreateProductReply] { replyTo =>
        ProductProtocol.CreateProduct(
          id = productId,
          productCode = productCode,
          name = name,
          categoryCode = categoryCode,
          storageCondition = storageCondition,
          replyTo = replyTo
        )
      }.mapError(e =>
        ProductUseCaseError.UnexpectedError(
          s"Failed to communicate with actor: ${e.getMessage}",
          Some(e)
        )
      )
      result <- reply match {
        case ProductProtocol.CreateProductSucceeded(id) =>
          ZIO.succeed(logger.info(s"Product creation succeeded for ID: ${id.asString}")) *>
            ZIO.succeed(id)
      }
    } yield result

  override def updateProduct(
      productId: ProductId,
      newName: ProductName,
      newCategoryCode: CategoryCode,
      newStorageCondition: StorageCondition
  ): IO[ProductUseCaseError, ProductId] =
    for {
      _ <- ZIO.succeed(
        logger.info(s"Updating Product with ID: ${productId.asString}")
      )
      reply <- askActor[ProductProtocol.UpdateProductReply] { replyTo =>
        ProductProtocol.UpdateProduct(
          id = productId,
          newName = newName,
          newCategoryCode = newCategoryCode,
          newStorageCondition = newStorageCondition,
          replyTo = replyTo
        )
      }.mapError(e =>
        ProductUseCaseError.UnexpectedError(
          s"Failed to communicate with actor: ${e.getMessage}",
          Some(e)
        )
      )
      result <- reply match {
        case ProductProtocol.UpdateProductSucceeded(id) =>
          ZIO.succeed(logger.info(s"Product update succeeded for ID: ${id.asString}")) *>
            ZIO.succeed(id)
        case ProductProtocol.UpdateProductFailed(id, reason) =>
          ZIO.fail(ProductUseCaseError.UpdateFailed(s"Update failed for ID ${id.asString}: $reason"))
      }
    } yield result

  override def obsoleteProduct(
      productId: ProductId
  ): IO[ProductUseCaseError, ProductId] =
    for {
      _ <- ZIO.succeed(
        logger.info(s"Obsoleting Product with ID: ${productId.asString}")
      )
      reply <- askActor[ProductProtocol.ObsoleteProductReply] { replyTo =>
        ProductProtocol.ObsoleteProduct(
          id = productId,
          replyTo = replyTo
        )
      }.mapError(e =>
        ProductUseCaseError.UnexpectedError(
          s"Failed to communicate with actor: ${e.getMessage}",
          Some(e)
        )
      )
      result <- reply match {
        case ProductProtocol.ObsoleteProductSucceeded(id) =>
          ZIO.succeed(logger.info(s"Product obsolete succeeded for ID: ${id.asString}")) *>
            ZIO.succeed(id)
        case ProductProtocol.ObsoleteProductFailed(id, reason) =>
          ZIO.fail(
            ProductUseCaseError.ObsoleteFailed(s"Obsolete failed for ID ${id.asString}: $reason")
          )
      }
    } yield result

  private def askActor[R](
      createMessage: ActorRef[R] => ProductProtocol.Command
  ): Task[R] =
    PekkoInterop.fromFuture {
      productAggregateRef.ask(createMessage)
    }
}
