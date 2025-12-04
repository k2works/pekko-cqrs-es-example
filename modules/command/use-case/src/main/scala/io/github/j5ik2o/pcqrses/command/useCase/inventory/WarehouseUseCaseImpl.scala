package io.github.j5ik2o.pcqrses.command.useCase.inventory

import io.github.j5ik2o.pcqrses.command.domain.inventory.{
  WarehouseCode,
  WarehouseId,
  WarehouseLocation,
  WarehouseName
}
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.contract.inventory.WarehouseProtocol
import io.github.j5ik2o.pcqrses.infrastructure.effect.PekkoInterop
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorRef, Scheduler}
import org.apache.pekko.util.Timeout
import org.slf4j.LoggerFactory
import zio.{IO, Task, ZIO}

import scala.concurrent.ExecutionContext

/**
  * WarehouseUseCaseの実装クラス
  *
  * Pekkoアクターを使用して倉庫管理のビジネスロジックを実行する
  */
private[inventory] final class WarehouseUseCaseImpl(
    warehouseAggregateRef: ActorRef[WarehouseProtocol.Command]
)(implicit
    timeout: Timeout,
    scheduler: Scheduler,
    ec: ExecutionContext
) extends WarehouseUseCase {

  private val logger = LoggerFactory.getLogger(this.getClass)

  override def createWarehouse(
      warehouseCode: WarehouseCode,
      name: WarehouseName,
      location: WarehouseLocation
  ): IO[WarehouseUseCaseError, WarehouseId] =
    for {
      _ <- ZIO.succeed(
        logger.info(s"Creating Warehouse with warehouseCode: ${warehouseCode.value}")
      )
      warehouseId <- ZIO.succeed(WarehouseId.generate())
      reply <- askActor[WarehouseProtocol.CreateWarehouseReply] { replyTo =>
        WarehouseProtocol.CreateWarehouse(
          id = warehouseId,
          warehouseCode = warehouseCode,
          name = name,
          location = location,
          replyTo = replyTo
        )
      }.mapError(e =>
        WarehouseUseCaseError.UnexpectedError(
          s"Failed to communicate with actor: ${e.getMessage}",
          Some(e)
        )
      )
      result <- reply match {
        case WarehouseProtocol.CreateWarehouseSucceeded(id) =>
          ZIO.succeed(logger.info(s"Warehouse creation succeeded for ID: ${id.asString}")) *>
            ZIO.succeed(id)
      }
    } yield result

  override def updateWarehouse(
      warehouseId: WarehouseId,
      newName: WarehouseName,
      newLocation: WarehouseLocation
  ): IO[WarehouseUseCaseError, WarehouseId] =
    for {
      _ <- ZIO.succeed(
        logger.info(s"Updating Warehouse with ID: ${warehouseId.asString}")
      )
      reply <- askActor[WarehouseProtocol.UpdateWarehouseReply] { replyTo =>
        WarehouseProtocol.UpdateWarehouse(
          id = warehouseId,
          newName = newName,
          newLocation = newLocation,
          replyTo = replyTo
        )
      }.mapError(e =>
        WarehouseUseCaseError.UnexpectedError(
          s"Failed to communicate with actor: ${e.getMessage}",
          Some(e)
        )
      )
      result <- reply match {
        case WarehouseProtocol.UpdateWarehouseSucceeded(id) =>
          ZIO.succeed(logger.info(s"Warehouse update succeeded for ID: ${id.asString}")) *>
            ZIO.succeed(id)
        case WarehouseProtocol.UpdateWarehouseFailed(id, reason) =>
          ZIO.fail(WarehouseUseCaseError.UpdateFailed(s"Update failed for ID ${id.asString}: $reason"))
      }
    } yield result

  override def deactivateWarehouse(
      warehouseId: WarehouseId
  ): IO[WarehouseUseCaseError, WarehouseId] =
    for {
      _ <- ZIO.succeed(
        logger.info(s"Deactivating Warehouse with ID: ${warehouseId.asString}")
      )
      reply <- askActor[WarehouseProtocol.DeactivateWarehouseReply] { replyTo =>
        WarehouseProtocol.DeactivateWarehouse(
          id = warehouseId,
          replyTo = replyTo
        )
      }.mapError(e =>
        WarehouseUseCaseError.UnexpectedError(
          s"Failed to communicate with actor: ${e.getMessage}",
          Some(e)
        )
      )
      result <- reply match {
        case WarehouseProtocol.DeactivateWarehouseSucceeded(id) =>
          ZIO.succeed(logger.info(s"Warehouse deactivate succeeded for ID: ${id.asString}")) *>
            ZIO.succeed(id)
        case WarehouseProtocol.DeactivateWarehouseFailed(id, reason) =>
          ZIO.fail(
            WarehouseUseCaseError.DeactivateFailed(s"Deactivate failed for ID ${id.asString}: $reason")
          )
      }
    } yield result

  override def reactivateWarehouse(
      warehouseId: WarehouseId
  ): IO[WarehouseUseCaseError, WarehouseId] =
    for {
      _ <- ZIO.succeed(
        logger.info(s"Reactivating Warehouse with ID: ${warehouseId.asString}")
      )
      reply <- askActor[WarehouseProtocol.ReactivateWarehouseReply] { replyTo =>
        WarehouseProtocol.ReactivateWarehouse(
          id = warehouseId,
          replyTo = replyTo
        )
      }.mapError(e =>
        WarehouseUseCaseError.UnexpectedError(
          s"Failed to communicate with actor: ${e.getMessage}",
          Some(e)
        )
      )
      result <- reply match {
        case WarehouseProtocol.ReactivateWarehouseSucceeded(id) =>
          ZIO.succeed(logger.info(s"Warehouse reactivate succeeded for ID: ${id.asString}")) *>
            ZIO.succeed(id)
        case WarehouseProtocol.ReactivateWarehouseFailed(id, reason) =>
          ZIO.fail(
            WarehouseUseCaseError.ReactivateFailed(s"Reactivate failed for ID ${id.asString}: $reason")
          )
      }
    } yield result

  private def askActor[R](
      createMessage: ActorRef[R] => WarehouseProtocol.Command
  ): Task[R] =
    PekkoInterop.fromFuture {
      warehouseAggregateRef.ask(createMessage)
    }
}
