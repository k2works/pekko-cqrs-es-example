package io.github.j5ik2o.pcqrses.command.domain.inventory

import io.github.j5ik2o.pcqrses.command.domain.basic.DateTime
import io.github.j5ik2o.pcqrses.command.domain.support.{DomainEvent, DomainEventId}

/** 在庫に関するドメインイベント */
enum InventoryEvent extends DomainEvent {
  override type EntityIdType = InventoryId

  /** 在庫入庫イベント
    *
    * @param id イベントID
    * @param entityId 在庫ID
    * @param productId 商品ID
    * @param warehouseZoneId 倉庫ゾーンID
    * @param quantity 入庫数量
    * @param version 更新後のバージョン
    * @param occurredAt 発生日時
    */
  case Received_V1(
      id: DomainEventId,
      entityId: InventoryId,
      productId: ProductId,
      warehouseZoneId: WarehouseZoneId,
      quantity: InventoryQuantity,
      version: InventoryVersion,
      occurredAt: DateTime
  )

  /** 在庫出庫イベント
    *
    * @param id イベントID
    * @param entityId 在庫ID
    * @param productId 商品ID
    * @param warehouseZoneId 倉庫ゾーンID
    * @param quantity 出庫数量
    * @param version 更新後のバージョン
    * @param occurredAt 発生日時
    */
  case Issued_V1(
      id: DomainEventId,
      entityId: InventoryId,
      productId: ProductId,
      warehouseZoneId: WarehouseZoneId,
      quantity: InventoryQuantity,
      version: InventoryVersion,
      occurredAt: DateTime
  )

  /** 在庫引当イベント
    *
    * @param id イベントID
    * @param entityId 在庫ID
    * @param productId 商品ID
    * @param warehouseZoneId 倉庫ゾーンID
    * @param quantity 引当数量
    * @param version 更新後のバージョン
    * @param occurredAt 発生日時
    */
  case Reserved_V1(
      id: DomainEventId,
      entityId: InventoryId,
      productId: ProductId,
      warehouseZoneId: WarehouseZoneId,
      quantity: InventoryQuantity,
      version: InventoryVersion,
      occurredAt: DateTime
  )

  /** 在庫引当解放イベント
    *
    * @param id イベントID
    * @param entityId 在庫ID
    * @param productId 商品ID
    * @param warehouseZoneId 倉庫ゾーンID
    * @param quantity 解放数量
    * @param version 更新後のバージョン
    * @param occurredAt 発生日時
    */
  case Released_V1(
      id: DomainEventId,
      entityId: InventoryId,
      productId: ProductId,
      warehouseZoneId: WarehouseZoneId,
      quantity: InventoryQuantity,
      version: InventoryVersion,
      occurredAt: DateTime
  )

  /** 在庫調整イベント
    *
    * @param id イベントID
    * @param entityId 在庫ID
    * @param productId 商品ID
    * @param warehouseZoneId 倉庫ゾーンID
    * @param oldQuantity 調整前の在庫数量
    * @param newQuantity 調整後の在庫数量
    * @param reason 調整理由
    * @param version 更新後のバージョン
    * @param occurredAt 発生日時
    */
  case Adjusted_V1(
      id: DomainEventId,
      entityId: InventoryId,
      productId: ProductId,
      warehouseZoneId: WarehouseZoneId,
      oldQuantity: InventoryQuantity,
      newQuantity: InventoryQuantity,
      reason: String,
      version: InventoryVersion,
      occurredAt: DateTime
  )

  /** 在庫移動イベント
    *
    * @param id イベントID
    * @param entityId 在庫ID（移動元）
    * @param productId 商品ID
    * @param fromWarehouseZoneId 移動元倉庫ゾーンID
    * @param toWarehouseZoneId 移動先倉庫ゾーンID
    * @param quantity 移動数量
    * @param version 更新後のバージョン
    * @param occurredAt 発生日時
    */
  case Moved_V1(
      id: DomainEventId,
      entityId: InventoryId,
      productId: ProductId,
      fromWarehouseZoneId: WarehouseZoneId,
      toWarehouseZoneId: WarehouseZoneId,
      quantity: InventoryQuantity,
      version: InventoryVersion,
      occurredAt: DateTime
  )
}
