package io.github.j5ik2o.pcqrses.command.domain.inventory

import io.github.j5ik2o.pcqrses.command.domain.basic.DateTime
import io.github.j5ik2o.pcqrses.command.domain.support.{DomainEvent, DomainEventId}

/** 倉庫に関するドメインイベント */
enum WarehouseEvent extends DomainEvent {
  override type EntityIdType = WarehouseId

  /** 倉庫作成イベント
    *
    * @param id イベントID
    * @param entityId 倉庫ID
    * @param warehouseCode 倉庫コード
    * @param name 倉庫名
    * @param location 所在地
    * @param occurredAt 発生日時
    */
  case Created_V1(
      id: DomainEventId,
      entityId: WarehouseId,
      warehouseCode: WarehouseCode,
      name: WarehouseName,
      location: WarehouseLocation,
      occurredAt: DateTime
  )

  /** 倉庫情報更新イベント
    *
    * @param id イベントID
    * @param entityId 倉庫ID
    * @param oldName 旧倉庫名
    * @param newName 新倉庫名
    * @param oldLocation 旧所在地
    * @param newLocation 新所在地
    * @param occurredAt 発生日時
    */
  case Updated_V1(
      id: DomainEventId,
      entityId: WarehouseId,
      oldName: WarehouseName,
      newName: WarehouseName,
      oldLocation: WarehouseLocation,
      newLocation: WarehouseLocation,
      occurredAt: DateTime
  )

  /** 倉庫無効化イベント
    *
    * @param id イベントID
    * @param entityId 倉庫ID
    * @param occurredAt 発生日時
    */
  case Deactivated_V1(
      id: DomainEventId,
      entityId: WarehouseId,
      occurredAt: DateTime
  )

  /** 倉庫再有効化イベント
    *
    * @param id イベントID
    * @param entityId 倉庫ID
    * @param occurredAt 発生日時
    */
  case Reactivated_V1(
      id: DomainEventId,
      entityId: WarehouseId,
      occurredAt: DateTime
  )
}
