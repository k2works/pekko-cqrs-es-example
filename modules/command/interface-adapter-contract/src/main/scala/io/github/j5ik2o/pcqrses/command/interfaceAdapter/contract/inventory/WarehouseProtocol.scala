package io.github.j5ik2o.pcqrses.command.interfaceAdapter.contract.inventory

import io.github.j5ik2o.pcqrses.command.domain.inventory.*
import org.apache.pekko.actor.typed.ActorRef

object WarehouseProtocol {
  sealed trait Command {
    def id: WarehouseId
  }

  /** 倉庫作成コマンド */
  final case class CreateWarehouse(
      id: WarehouseId,
      warehouseCode: WarehouseCode,
      name: WarehouseName,
      location: WarehouseLocation,
      replyTo: ActorRef[CreateWarehouseReply]
  ) extends Command

  /** 倉庫情報更新コマンド */
  final case class UpdateWarehouse(
      id: WarehouseId,
      newName: WarehouseName,
      newLocation: WarehouseLocation,
      replyTo: ActorRef[UpdateWarehouseReply]
  ) extends Command

  /** 倉庫無効化コマンド */
  final case class DeactivateWarehouse(
      id: WarehouseId,
      replyTo: ActorRef[DeactivateWarehouseReply]
  ) extends Command

  /** 倉庫再有効化コマンド */
  final case class ReactivateWarehouse(
      id: WarehouseId,
      replyTo: ActorRef[ReactivateWarehouseReply]
  ) extends Command

  /** 倉庫取得コマンド */
  final case class GetWarehouse(
      id: WarehouseId,
      replyTo: ActorRef[GetWarehouseReply]
  ) extends Command

  // --- 応答 ---

  sealed trait CreateWarehouseReply
  final case class CreateWarehouseSucceeded(id: WarehouseId) extends CreateWarehouseReply

  sealed trait UpdateWarehouseReply
  final case class UpdateWarehouseSucceeded(id: WarehouseId) extends UpdateWarehouseReply
  final case class UpdateWarehouseFailed(id: WarehouseId, reason: UpdateWarehouseError)
      extends UpdateWarehouseReply

  sealed trait DeactivateWarehouseReply
  final case class DeactivateWarehouseSucceeded(id: WarehouseId) extends DeactivateWarehouseReply
  final case class DeactivateWarehouseFailed(id: WarehouseId, reason: DeactivateWarehouseError)
      extends DeactivateWarehouseReply

  sealed trait ReactivateWarehouseReply
  final case class ReactivateWarehouseSucceeded(id: WarehouseId) extends ReactivateWarehouseReply
  final case class ReactivateWarehouseFailed(id: WarehouseId, reason: ReactivateWarehouseError)
      extends ReactivateWarehouseReply

  sealed trait GetWarehouseReply
  final case class GetWarehouseSucceeded(value: Warehouse) extends GetWarehouseReply
  final case class GetWarehouseNotFoundFailed(id: WarehouseId) extends GetWarehouseReply

  final case class Stop(id: WarehouseId) extends Command
}
