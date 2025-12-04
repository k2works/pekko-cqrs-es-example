package io.github.j5ik2o.pcqrses.command.interfaceAdapter.contract.inventory

import io.github.j5ik2o.pcqrses.command.domain.inventory.*
import org.apache.pekko.actor.typed.ActorRef

object WarehouseZoneProtocol {
  sealed trait Command {
    def id: WarehouseZoneId
  }

  /** 倉庫ゾーン作成コマンド */
  final case class CreateWarehouseZone(
      id: WarehouseZoneId,
      warehouseId: WarehouseId,
      zoneCode: ZoneCode,
      name: ZoneName,
      zoneType: StorageCondition,
      capacity: ZoneCapacity,
      replyTo: ActorRef[CreateWarehouseZoneReply]
  ) extends Command

  /** 倉庫ゾーン情報更新コマンド */
  final case class UpdateWarehouseZone(
      id: WarehouseZoneId,
      newName: ZoneName,
      newCapacity: ZoneCapacity,
      replyTo: ActorRef[UpdateWarehouseZoneReply]
  ) extends Command

  /** 倉庫ゾーン無効化コマンド */
  final case class DeactivateWarehouseZone(
      id: WarehouseZoneId,
      replyTo: ActorRef[DeactivateWarehouseZoneReply]
  ) extends Command

  /** 倉庫ゾーン再有効化コマンド */
  final case class ReactivateWarehouseZone(
      id: WarehouseZoneId,
      replyTo: ActorRef[ReactivateWarehouseZoneReply]
  ) extends Command

  /** 倉庫ゾーン取得コマンド */
  final case class GetWarehouseZone(
      id: WarehouseZoneId,
      replyTo: ActorRef[GetWarehouseZoneReply]
  ) extends Command

  // --- 応答 ---

  sealed trait CreateWarehouseZoneReply
  final case class CreateWarehouseZoneSucceeded(id: WarehouseZoneId) extends CreateWarehouseZoneReply

  sealed trait UpdateWarehouseZoneReply
  final case class UpdateWarehouseZoneSucceeded(id: WarehouseZoneId) extends UpdateWarehouseZoneReply
  final case class UpdateWarehouseZoneFailed(id: WarehouseZoneId, reason: UpdateZoneError)
      extends UpdateWarehouseZoneReply

  sealed trait DeactivateWarehouseZoneReply
  final case class DeactivateWarehouseZoneSucceeded(id: WarehouseZoneId)
      extends DeactivateWarehouseZoneReply
  final case class DeactivateWarehouseZoneFailed(id: WarehouseZoneId, reason: DeactivateZoneError)
      extends DeactivateWarehouseZoneReply

  sealed trait ReactivateWarehouseZoneReply
  final case class ReactivateWarehouseZoneSucceeded(id: WarehouseZoneId)
      extends ReactivateWarehouseZoneReply
  final case class ReactivateWarehouseZoneFailed(id: WarehouseZoneId, reason: ReactivateZoneError)
      extends ReactivateWarehouseZoneReply

  sealed trait GetWarehouseZoneReply
  final case class GetWarehouseZoneSucceeded(value: WarehouseZone) extends GetWarehouseZoneReply
  final case class GetWarehouseZoneNotFoundFailed(id: WarehouseZoneId) extends GetWarehouseZoneReply

  final case class Stop(id: WarehouseZoneId) extends Command
}
