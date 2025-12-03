package io.github.j5ik2o.pcqrses.command.domain.inventory

import io.github.j5ik2o.pcqrses.command.domain.basic.DateTime
import io.github.j5ik2o.pcqrses.command.domain.support.{DomainEvent, DomainEventId}

/** 取引先に関するドメインイベント */
enum CustomerEvent extends DomainEvent {
  override type EntityIdType = CustomerId

  /** 取引先作成イベント
    *
    * @param id イベントID
    * @param entityId 取引先ID
    * @param customerCode 取引先コード
    * @param name 取引先名
    * @param customerType 取引先タイプ
    * @param occurredAt 発生日時
    */
  case Created_V1(
      id: DomainEventId,
      entityId: CustomerId,
      customerCode: CustomerCode,
      name: CustomerName,
      customerType: CustomerType,
      occurredAt: DateTime
  )

  /** 取引先情報更新イベント
    *
    * @param id イベントID
    * @param entityId 取引先ID
    * @param oldName 旧取引先名
    * @param newName 新取引先名
    * @param oldCustomerType 旧取引先タイプ
    * @param newCustomerType 新取引先タイプ
    * @param occurredAt 発生日時
    */
  case Updated_V1(
      id: DomainEventId,
      entityId: CustomerId,
      oldName: CustomerName,
      newName: CustomerName,
      oldCustomerType: CustomerType,
      newCustomerType: CustomerType,
      occurredAt: DateTime
  )

  /** 取引先無効化イベント
    *
    * @param id イベントID
    * @param entityId 取引先ID
    * @param occurredAt 発生日時
    */
  case Deactivated_V1(
      id: DomainEventId,
      entityId: CustomerId,
      occurredAt: DateTime
  )

  /** 取引先再有効化イベント
    *
    * @param id イベントID
    * @param entityId 取引先ID
    * @param occurredAt 発生日時
    */
  case Reactivated_V1(
      id: DomainEventId,
      entityId: CustomerId,
      occurredAt: DateTime
  )
}
