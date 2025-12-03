package io.github.j5ik2o.pcqrses.command.domain.inventory

import io.github.j5ik2o.pcqrses.command.domain.basic.DateTime
import io.github.j5ik2o.pcqrses.command.domain.support.{DomainEventId, Entity}

/** 倉庫区画集約
  *
  * D社の倉庫区画を管理する集約。
  * 各倉庫に3つの区画（常温、冷蔵、冷凍）を設け、合計9区画を管理する。
  *
  * ビジネスルール：
  * - 区画コードは一意である必要がある
  * - 区画タイプ（保管条件）は常温、冷蔵、冷凍のいずれかである必要がある
  * - 容量は0より大きい値である必要がある
  * - 無効化された区画は更新できない
  */
trait WarehouseZone extends Entity {
  override type IdType = WarehouseZoneId

  /** 区画ID */
  def id: WarehouseZoneId

  /** 所属倉庫ID */
  def warehouseId: WarehouseId

  /** 区画コード */
  def zoneCode: ZoneCode

  /** 区画名 */
  def name: ZoneName

  /** 区画タイプ（保管条件） */
  def zoneType: StorageCondition

  /** 容量 */
  def capacity: ZoneCapacity

  /** 作成日時 */
  def createdAt: DateTime

  /** 更新日時 */
  def updatedAt: DateTime

  /** 区画情報を更新
    *
    * @param newName 新しい区画名
    * @param newCapacity 新しい容量
    * @return 更新後の区画とイベント、またはエラー
    */
  def update(
      newName: ZoneName,
      newCapacity: ZoneCapacity
  ): Either[UpdateZoneError, (WarehouseZone, WarehouseZoneEvent)]

  /** 区画を無効化
    *
    * @return 無効化後の区画とイベント、またはエラー
    */
  def deactivate: Either[DeactivateZoneError, (WarehouseZone, WarehouseZoneEvent)]

  /** 区画を再有効化
    *
    * @return 再有効化後の区画とイベント、またはエラー
    */
  def reactivate: Either[ReactivateZoneError, (WarehouseZone, WarehouseZoneEvent)]
}

object WarehouseZone {

  /** 倉庫区画を作成
    *
    * @param id 区画ID
    * @param warehouseId 所属倉庫ID
    * @param zoneCode 区画コード
    * @param name 区画名
    * @param zoneType 区画タイプ（保管条件）
    * @param capacity 容量
    * @param createdAt 作成日時（省略時は現在時刻）
    * @param updatedAt 更新日時（省略時は現在時刻）
    * @return 作成された区画とCreatedイベント
    */
  def apply(
      id: WarehouseZoneId,
      warehouseId: WarehouseId,
      zoneCode: ZoneCode,
      name: ZoneName,
      zoneType: StorageCondition,
      capacity: ZoneCapacity,
      createdAt: DateTime = DateTime.now(),
      updatedAt: DateTime = DateTime.now()
  ): (WarehouseZone, WarehouseZoneEvent) = {
    val zone = WarehouseZoneImpl(
      id = id,
      warehouseId = warehouseId,
      zoneCode = zoneCode,
      name = name,
      zoneType = zoneType,
      capacity = capacity,
      active = true,
      createdAt = createdAt,
      updatedAt = updatedAt
    )
    val event = WarehouseZoneEvent.Created_V1(
      id = DomainEventId.generate(),
      entityId = id,
      warehouseId = warehouseId,
      zoneCode = zoneCode,
      name = name,
      zoneType = zoneType,
      capacity = capacity,
      occurredAt = DateTime.now()
    )
    (zone, event)
  }

  def unapply(
      self: WarehouseZone
  ): Option[(WarehouseZoneId, WarehouseId, ZoneCode, ZoneName, StorageCondition, ZoneCapacity, DateTime, DateTime)] =
    Some((self.id, self.warehouseId, self.zoneCode, self.name, self.zoneType, self.capacity, self.createdAt, self.updatedAt))

  private final case class WarehouseZoneImpl(
      id: WarehouseZoneId,
      warehouseId: WarehouseId,
      zoneCode: ZoneCode,
      name: ZoneName,
      zoneType: StorageCondition,
      capacity: ZoneCapacity,
      active: Boolean,
      createdAt: DateTime,
      updatedAt: DateTime
  ) extends WarehouseZone {

    override def update(
        newName: ZoneName,
        newCapacity: ZoneCapacity
    ): Either[UpdateZoneError, (WarehouseZone, WarehouseZoneEvent)] = {
      if (!active) {
        Left(UpdateZoneError.AlreadyDeactivated)
      } else if (name == newName && capacity == newCapacity) {
        Left(UpdateZoneError.NoChanges)
      } else {
        val updated = this.copy(
          name = newName,
          capacity = newCapacity,
          updatedAt = DateTime.now()
        )
        val event = WarehouseZoneEvent.Updated_V1(
          id = DomainEventId.generate(),
          entityId = id,
          oldName = name,
          newName = newName,
          oldCapacity = capacity,
          newCapacity = newCapacity,
          occurredAt = DateTime.now()
        )
        Right((updated, event))
      }
    }

    override def deactivate: Either[DeactivateZoneError, (WarehouseZone, WarehouseZoneEvent)] = {
      if (!active) {
        Left(DeactivateZoneError.AlreadyDeactivated)
      } else {
        val updated = this.copy(active = false, updatedAt = DateTime.now())
        val event = WarehouseZoneEvent.Deactivated_V1(
          id = DomainEventId.generate(),
          entityId = id,
          occurredAt = DateTime.now()
        )
        Right((updated, event))
      }
    }

    override def reactivate: Either[ReactivateZoneError, (WarehouseZone, WarehouseZoneEvent)] = {
      if (active) {
        Left(ReactivateZoneError.AlreadyActive)
      } else {
        val updated = this.copy(active = true, updatedAt = DateTime.now())
        val event = WarehouseZoneEvent.Reactivated_V1(
          id = DomainEventId.generate(),
          entityId = id,
          occurredAt = DateTime.now()
        )
        Right((updated, event))
      }
    }
  }
}
