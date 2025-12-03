package io.github.j5ik2o.pcqrses.command.interfaceAdapter.contract.inventory

import io.github.j5ik2o.pcqrses.command.domain.inventory.*
import org.apache.pekko.actor.typed.ActorRef

object InventoryProtocol {
  sealed trait Command {
    def id: InventoryId
  }

  /** 在庫作成コマンド（初期在庫ゼロ） */
  final case class CreateInventory(
      id: InventoryId,
      productId: ProductId,
      warehouseZoneId: WarehouseZoneId,
      replyTo: ActorRef[CreateInventoryReply]
  ) extends Command

  /** 在庫入庫コマンド */
  final case class ReceiveInventory(
      id: InventoryId,
      quantity: InventoryQuantity,
      expectedVersion: InventoryVersion,
      replyTo: ActorRef[ReceiveInventoryReply]
  ) extends Command

  /** 在庫引当コマンド */
  final case class ReserveInventory(
      id: InventoryId,
      quantity: InventoryQuantity,
      expectedVersion: InventoryVersion,
      replyTo: ActorRef[ReserveInventoryReply]
  ) extends Command

  /** 在庫引当解放コマンド */
  final case class ReleaseInventory(
      id: InventoryId,
      quantity: InventoryQuantity,
      expectedVersion: InventoryVersion,
      replyTo: ActorRef[ReleaseInventoryReply]
  ) extends Command

  /** 在庫出庫コマンド */
  final case class IssueInventory(
      id: InventoryId,
      quantity: InventoryQuantity,
      expectedVersion: InventoryVersion,
      replyTo: ActorRef[IssueInventoryReply]
  ) extends Command

  /** 在庫調整コマンド */
  final case class AdjustInventory(
      id: InventoryId,
      newQuantity: InventoryQuantity,
      reason: String,
      expectedVersion: InventoryVersion,
      replyTo: ActorRef[AdjustInventoryReply]
  ) extends Command

  /** 在庫取得コマンド */
  final case class GetInventory(
      id: InventoryId,
      replyTo: ActorRef[GetInventoryReply]
  ) extends Command

  // --- 応答 ---

  sealed trait CreateInventoryReply
  final case class CreateInventorySucceeded(id: InventoryId) extends CreateInventoryReply

  sealed trait ReceiveInventoryReply
  final case class ReceiveInventorySucceeded(id: InventoryId, newVersion: InventoryVersion)
      extends ReceiveInventoryReply
  final case class ReceiveInventoryFailed(id: InventoryId, reason: ReceiveInventoryError)
      extends ReceiveInventoryReply

  sealed trait ReserveInventoryReply
  final case class ReserveInventorySucceeded(id: InventoryId, newVersion: InventoryVersion)
      extends ReserveInventoryReply
  final case class ReserveInventoryFailed(id: InventoryId, reason: ReserveInventoryError)
      extends ReserveInventoryReply

  sealed trait ReleaseInventoryReply
  final case class ReleaseInventorySucceeded(id: InventoryId, newVersion: InventoryVersion)
      extends ReleaseInventoryReply
  final case class ReleaseInventoryFailed(id: InventoryId, reason: ReleaseInventoryError)
      extends ReleaseInventoryReply

  sealed trait IssueInventoryReply
  final case class IssueInventorySucceeded(id: InventoryId, newVersion: InventoryVersion)
      extends IssueInventoryReply
  final case class IssueInventoryFailed(id: InventoryId, reason: IssueInventoryError)
      extends IssueInventoryReply

  sealed trait AdjustInventoryReply
  final case class AdjustInventorySucceeded(id: InventoryId, newVersion: InventoryVersion)
      extends AdjustInventoryReply
  final case class AdjustInventoryFailed(id: InventoryId, reason: AdjustInventoryError)
      extends AdjustInventoryReply

  sealed trait GetInventoryReply
  final case class GetInventorySucceeded(value: Inventory) extends GetInventoryReply
  final case class GetInventoryNotFoundFailed(id: InventoryId) extends GetInventoryReply

  final case class Stop(id: InventoryId) extends Command
}
