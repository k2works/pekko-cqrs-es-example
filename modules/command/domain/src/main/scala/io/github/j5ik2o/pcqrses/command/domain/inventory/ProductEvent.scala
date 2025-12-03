package io.github.j5ik2o.pcqrses.command.domain.inventory

import io.github.j5ik2o.pcqrses.command.domain.basic.DateTime
import io.github.j5ik2o.pcqrses.command.domain.support.{DomainEvent, DomainEventId}

/** 商品に関するドメインイベント */
enum ProductEvent extends DomainEvent {
  override type EntityIdType = ProductId

  /** 商品作成イベント
    *
    * @param id イベントID
    * @param entityId 商品ID
    * @param productCode 商品コード
    * @param name 商品名
    * @param categoryCode カテゴリコード
    * @param storageCondition 保管条件
    * @param occurredAt 発生日時
    */
  case Created_V1(
      id: DomainEventId,
      entityId: ProductId,
      productCode: ProductCode,
      name: ProductName,
      categoryCode: CategoryCode,
      storageCondition: StorageCondition,
      occurredAt: DateTime
  )

  /** 商品情報更新イベント
    *
    * @param id イベントID
    * @param entityId 商品ID
    * @param oldName 旧商品名
    * @param newName 新商品名
    * @param oldCategoryCode 旧カテゴリコード
    * @param newCategoryCode 新カテゴリコード
    * @param oldStorageCondition 旧保管条件
    * @param newStorageCondition 新保管条件
    * @param occurredAt 発生日時
    */
  case Updated_V1(
      id: DomainEventId,
      entityId: ProductId,
      oldName: ProductName,
      newName: ProductName,
      oldCategoryCode: CategoryCode,
      newCategoryCode: CategoryCode,
      oldStorageCondition: StorageCondition,
      newStorageCondition: StorageCondition,
      occurredAt: DateTime
  )

  /** 商品廃止イベント
    *
    * @param id イベントID
    * @param entityId 商品ID
    * @param occurredAt 発生日時
    */
  case Obsoleted_V1(
      id: DomainEventId,
      entityId: ProductId,
      occurredAt: DateTime
  )
}
