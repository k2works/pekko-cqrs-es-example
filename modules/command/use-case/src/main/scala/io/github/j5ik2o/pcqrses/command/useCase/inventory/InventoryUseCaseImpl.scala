package io.github.j5ik2o.pcqrses.command.useCase.inventory

import io.github.j5ik2o.pcqrses.command.domain.inventory.{
  InventoryId,
  InventoryQuantity,
  InventoryVersion,
  ProductId,
  WarehouseZoneId
}
import io.github.j5ik2o.pcqrses.command.interfaceAdapter.contract.inventory.InventoryProtocol
import io.github.j5ik2o.pcqrses.infrastructure.effect.PekkoInterop
import org.apache.pekko.actor.typed.scaladsl.AskPattern.*
import org.apache.pekko.actor.typed.{ActorRef, Scheduler}
import org.apache.pekko.util.Timeout
import org.slf4j.LoggerFactory
import zio.{IO, Task, ZIO}

import scala.concurrent.ExecutionContext

/**
  * InventoryUseCaseの実装クラス
  *
  * Pekkoアクターを使用して在庫管理のビジネスロジックを実行する
  * 楽観的ロック制御により並行アクセス時の整合性を保証
  */
private[inventory] final class InventoryUseCaseImpl(
    inventoryAggregateRef: ActorRef[InventoryProtocol.Command]
)(implicit
    timeout: Timeout,
    scheduler: Scheduler,
    ec: ExecutionContext
) extends InventoryUseCase {

  private val logger = LoggerFactory.getLogger(this.getClass)

  override def createInventory(
      productId: ProductId,
      warehouseZoneId: WarehouseZoneId
  ): IO[InventoryUseCaseError, InventoryId] =
    for {
      _ <- ZIO.succeed(
        logger.info(s"Creating Inventory for product: ${productId.asString}, zone: ${warehouseZoneId.asString}")
      )
      inventoryId <- ZIO.succeed(InventoryId.generate())
      reply <- askActor[InventoryProtocol.CreateInventoryReply] { replyTo =>
        InventoryProtocol.CreateInventory(
          id = inventoryId,
          productId = productId,
          warehouseZoneId = warehouseZoneId,
          replyTo = replyTo
        )
      }.mapError(e =>
        InventoryUseCaseError.UnexpectedError(
          s"Failed to communicate with actor: ${e.getMessage}",
          Some(e)
        )
      )
      result <- reply match {
        case InventoryProtocol.CreateInventorySucceeded(id) =>
          ZIO.succeed(logger.info(s"Inventory creation succeeded for ID: ${id.asString}")) *>
            ZIO.succeed(id)
      }
    } yield result

  override def receiveInventory(
      inventoryId: InventoryId,
      quantity: InventoryQuantity,
      expectedVersion: InventoryVersion
  ): IO[InventoryUseCaseError, InventoryVersion] =
    for {
      _ <- ZIO.succeed(
        logger.info(s"Receiving inventory ${inventoryId.asString}, quantity: ${quantity.amount}, version: ${expectedVersion.value}")
      )
      reply <- askActor[InventoryProtocol.ReceiveInventoryReply] { replyTo =>
        InventoryProtocol.ReceiveInventory(
          id = inventoryId,
          quantity = quantity,
          expectedVersion = expectedVersion,
          replyTo = replyTo
        )
      }.mapError(e =>
        InventoryUseCaseError.UnexpectedError(
          s"Failed to communicate with actor: ${e.getMessage}",
          Some(e)
        )
      )
      result <- reply match {
        case InventoryProtocol.ReceiveInventorySucceeded(id, newVersion) =>
          ZIO.succeed(logger.info(s"Inventory receive succeeded for ID: ${id.asString}, new version: ${newVersion.value}")) *>
            ZIO.succeed(newVersion)
        case InventoryProtocol.ReceiveInventoryFailed(id, reason) =>
          reason match {
            case io.github.j5ik2o.pcqrses.command.domain.inventory.ReceiveInventoryError.VersionMismatch =>
              ZIO.fail(InventoryUseCaseError.VersionMismatch(s"Version mismatch for ID ${id.asString}"))
            case _ =>
              ZIO.fail(InventoryUseCaseError.ReceiveFailed(s"Receive failed for ID ${id.asString}: $reason"))
          }
      }
    } yield result

  override def reserveInventory(
      inventoryId: InventoryId,
      quantity: InventoryQuantity,
      expectedVersion: InventoryVersion
  ): IO[InventoryUseCaseError, InventoryVersion] =
    for {
      _ <- ZIO.succeed(
        logger.info(s"Reserving inventory ${inventoryId.asString}, quantity: ${quantity.amount}, version: ${expectedVersion.value}")
      )
      reply <- askActor[InventoryProtocol.ReserveInventoryReply] { replyTo =>
        InventoryProtocol.ReserveInventory(
          id = inventoryId,
          quantity = quantity,
          expectedVersion = expectedVersion,
          replyTo = replyTo
        )
      }.mapError(e =>
        InventoryUseCaseError.UnexpectedError(
          s"Failed to communicate with actor: ${e.getMessage}",
          Some(e)
        )
      )
      result <- reply match {
        case InventoryProtocol.ReserveInventorySucceeded(id, newVersion) =>
          ZIO.succeed(logger.info(s"Inventory reserve succeeded for ID: ${id.asString}, new version: ${newVersion.value}")) *>
            ZIO.succeed(newVersion)
        case InventoryProtocol.ReserveInventoryFailed(id, reason) =>
          reason match {
            case io.github.j5ik2o.pcqrses.command.domain.inventory.ReserveInventoryError.VersionMismatch =>
              ZIO.fail(InventoryUseCaseError.VersionMismatch(s"Version mismatch for ID ${id.asString}"))
            case io.github.j5ik2o.pcqrses.command.domain.inventory.ReserveInventoryError.InsufficientStock =>
              ZIO.fail(InventoryUseCaseError.ReserveFailed(s"Insufficient stock for ID ${id.asString}"))
          }
      }
    } yield result

  override def releaseInventory(
      inventoryId: InventoryId,
      quantity: InventoryQuantity,
      expectedVersion: InventoryVersion
  ): IO[InventoryUseCaseError, InventoryVersion] =
    for {
      _ <- ZIO.succeed(
        logger.info(s"Releasing inventory ${inventoryId.asString}, quantity: ${quantity.amount}, version: ${expectedVersion.value}")
      )
      reply <- askActor[InventoryProtocol.ReleaseInventoryReply] { replyTo =>
        InventoryProtocol.ReleaseInventory(
          id = inventoryId,
          quantity = quantity,
          expectedVersion = expectedVersion,
          replyTo = replyTo
        )
      }.mapError(e =>
        InventoryUseCaseError.UnexpectedError(
          s"Failed to communicate with actor: ${e.getMessage}",
          Some(e)
        )
      )
      result <- reply match {
        case InventoryProtocol.ReleaseInventorySucceeded(id, newVersion) =>
          ZIO.succeed(logger.info(s"Inventory release succeeded for ID: ${id.asString}, new version: ${newVersion.value}")) *>
            ZIO.succeed(newVersion)
        case InventoryProtocol.ReleaseInventoryFailed(id, reason) =>
          reason match {
            case io.github.j5ik2o.pcqrses.command.domain.inventory.ReleaseInventoryError.VersionMismatch =>
              ZIO.fail(InventoryUseCaseError.VersionMismatch(s"Version mismatch for ID ${id.asString}"))
            case io.github.j5ik2o.pcqrses.command.domain.inventory.ReleaseInventoryError.InsufficientReserved =>
              ZIO.fail(InventoryUseCaseError.ReleaseFailed(s"Insufficient reserved for ID ${id.asString}"))
          }
      }
    } yield result

  override def issueInventory(
      inventoryId: InventoryId,
      quantity: InventoryQuantity,
      expectedVersion: InventoryVersion
  ): IO[InventoryUseCaseError, InventoryVersion] =
    for {
      _ <- ZIO.succeed(
        logger.info(s"Issuing inventory ${inventoryId.asString}, quantity: ${quantity.amount}, version: ${expectedVersion.value}")
      )
      reply <- askActor[InventoryProtocol.IssueInventoryReply] { replyTo =>
        InventoryProtocol.IssueInventory(
          id = inventoryId,
          quantity = quantity,
          expectedVersion = expectedVersion,
          replyTo = replyTo
        )
      }.mapError(e =>
        InventoryUseCaseError.UnexpectedError(
          s"Failed to communicate with actor: ${e.getMessage}",
          Some(e)
        )
      )
      result <- reply match {
        case InventoryProtocol.IssueInventorySucceeded(id, newVersion) =>
          ZIO.succeed(logger.info(s"Inventory issue succeeded for ID: ${id.asString}, new version: ${newVersion.value}")) *>
            ZIO.succeed(newVersion)
        case InventoryProtocol.IssueInventoryFailed(id, reason) =>
          reason match {
            case io.github.j5ik2o.pcqrses.command.domain.inventory.IssueInventoryError.VersionMismatch =>
              ZIO.fail(InventoryUseCaseError.VersionMismatch(s"Version mismatch for ID ${id.asString}"))
            case io.github.j5ik2o.pcqrses.command.domain.inventory.IssueInventoryError.InsufficientReserved =>
              ZIO.fail(InventoryUseCaseError.IssueFailed(s"Insufficient reserved for ID ${id.asString}"))
          }
      }
    } yield result

  override def adjustInventory(
      inventoryId: InventoryId,
      newQuantity: InventoryQuantity,
      reason: String,
      expectedVersion: InventoryVersion
  ): IO[InventoryUseCaseError, InventoryVersion] =
    for {
      _ <- ZIO.succeed(
        logger.info(s"Adjusting inventory ${inventoryId.asString}, new quantity: ${newQuantity.amount}, reason: $reason, version: ${expectedVersion.value}")
      )
      reply <- askActor[InventoryProtocol.AdjustInventoryReply] { replyTo =>
        InventoryProtocol.AdjustInventory(
          id = inventoryId,
          newQuantity = newQuantity,
          reason = reason,
          expectedVersion = expectedVersion,
          replyTo = replyTo
        )
      }.mapError(e =>
        InventoryUseCaseError.UnexpectedError(
          s"Failed to communicate with actor: ${e.getMessage}",
          Some(e)
        )
      )
      result <- reply match {
        case InventoryProtocol.AdjustInventorySucceeded(id, newVersion) =>
          ZIO.succeed(logger.info(s"Inventory adjust succeeded for ID: ${id.asString}, new version: ${newVersion.value}")) *>
            ZIO.succeed(newVersion)
        case InventoryProtocol.AdjustInventoryFailed(id, reason) =>
          reason match {
            case io.github.j5ik2o.pcqrses.command.domain.inventory.AdjustInventoryError.VersionMismatch =>
              ZIO.fail(InventoryUseCaseError.VersionMismatch(s"Version mismatch for ID ${id.asString}"))
            case io.github.j5ik2o.pcqrses.command.domain.inventory.AdjustInventoryError.ResultingNegativeQuantity =>
              ZIO.fail(InventoryUseCaseError.AdjustFailed(s"Adjustment would result in negative quantity for ID ${id.asString}"))
          }
      }
    } yield result

  private def askActor[R](
      createMessage: ActorRef[R] => InventoryProtocol.Command
  ): Task[R] =
    PekkoInterop.fromFuture {
      inventoryAggregateRef.ask(createMessage)
    }
}
