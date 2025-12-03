package io.github.j5ik2o.pcqrses.command.domain.inventory

import io.github.j5ik2o.pcqrses.command.domain.basic.DateTime
import io.github.j5ik2o.pcqrses.command.domain.support.{DomainEvent, DomainEventId}

/** 倉庫区画に関するドメインイベント */
enum WarehouseZoneEvent extends DomainEvent {
  override type EntityIdType = WarehouseZoneId

  /** 区画作成イベント
    *
    * @param id イベントID
    * @param entityId 区画ID
    * @param warehouseId 倉庫ID
    * @param zoneCode 区画コード
    * @param name 区画名
    * @param zoneType 区画タイプ（保管条件）
    * @param capacity 容量
    * @param occurredAt 発生日時
    */
  case Created_V1(
      id: DomainEventId,
      entityId: WarehouseZoneId,
      warehouseId: WarehouseId,
      zoneCode: ZoneCode,
      name: ZoneName,
      zoneType: StorageCondition,
      capacity: ZoneCapacity,
      occurredAt: DateTime
  )

  /** 区画情報更新イベント
    *
    * @param id イベントID
    * @param entityId 区画ID
    * @param oldName 旧区画名
    * @param newName 新区画名
    * @param oldCapacity 旧容量
    * @param newCapacity 新容量
    * @param occurredAt 発生日時
    */
  case Updated_V1(
      id: DomainEventId,
      entityId: WarehouseZoneId,
      oldName: ZoneName,
      newName: ZoneName,
      oldCapacity: ZoneCapacity,
      newCapacity: ZoneCapacity,
      occurredAt: DateTime
  )

  /** 区画無効化イベント
    *
    * @param id イベントID
    * @param entityId 区画ID
    * @param occurredAt 発生日時
    */
  case Deactivated_V1(
      id: DomainEventId,
      entityId: WarehouseZoneId,
      occurredAt: DateTime
  )

  /** 区画再有効化イベント
    *
    * @param id イベントID
    * @param entityId 区画ID
    * @param occurredAt 発生日時
    */
  case Reactivated_V1(
      id: DomainEventId,
      entityId: WarehouseZoneId,
      occurredAt: DateTime
  )
}
