package io.github.j5ik2o.pcqrses.command.domain.inventory

import io.github.j5ik2o.pcqrses.command.domain.basic.DateTime
import io.github.j5ik2o.pcqrses.command.domain.support.{DomainEventId, Entity}

/** 倉庫集約
  *
  * D社の倉庫を管理する集約。
  * 3拠点（東京、大阪、福岡）の倉庫情報を管理する。
  *
  * ビジネスルール：
  * - 倉庫コードは一意である必要がある
  * - 無効化された倉庫は更新できない
  * - 無効化された倉庫は再有効化できる
  */
trait Warehouse extends Entity {
  override type IdType = WarehouseId

  /** 倉庫ID */
  def id: WarehouseId

  /** 倉庫コード */
  def warehouseCode: WarehouseCode

  /** 倉庫名 */
  def name: WarehouseName

  /** 所在地 */
  def location: WarehouseLocation

  /** 作成日時 */
  def createdAt: DateTime

  /** 更新日時 */
  def updatedAt: DateTime

  /** 倉庫情報を更新
    *
    * @param newName 新しい倉庫名
    * @param newLocation 新しい所在地
    * @return 更新後の倉庫とイベント、またはエラー
    */
  def update(
      newName: WarehouseName,
      newLocation: WarehouseLocation
  ): Either[UpdateWarehouseError, (Warehouse, WarehouseEvent)]

  /** 倉庫を無効化
    *
    * @return 無効化後の倉庫とイベント、またはエラー
    */
  def deactivate: Either[DeactivateWarehouseError, (Warehouse, WarehouseEvent)]

  /** 倉庫を再有効化
    *
    * @return 再有効化後の倉庫とイベント、またはエラー
    */
  def reactivate: Either[ReactivateWarehouseError, (Warehouse, WarehouseEvent)]
}

object Warehouse {

  /** 倉庫を作成
    *
    * @param id 倉庫ID
    * @param warehouseCode 倉庫コード
    * @param name 倉庫名
    * @param location 所在地
    * @param createdAt 作成日時（省略時は現在時刻）
    * @param updatedAt 更新日時（省略時は現在時刻）
    * @return 作成された倉庫とCreatedイベント
    */
  def apply(
      id: WarehouseId,
      warehouseCode: WarehouseCode,
      name: WarehouseName,
      location: WarehouseLocation,
      createdAt: DateTime = DateTime.now(),
      updatedAt: DateTime = DateTime.now()
  ): (Warehouse, WarehouseEvent) = {
    val warehouse = WarehouseImpl(
      id = id,
      warehouseCode = warehouseCode,
      name = name,
      location = location,
      active = true,
      createdAt = createdAt,
      updatedAt = updatedAt
    )
    val event = WarehouseEvent.Created_V1(
      id = DomainEventId.generate(),
      entityId = id,
      warehouseCode = warehouseCode,
      name = name,
      location = location,
      occurredAt = DateTime.now()
    )
    (warehouse, event)
  }

  def unapply(
      self: Warehouse
  ): Option[(WarehouseId, WarehouseCode, WarehouseName, WarehouseLocation, DateTime, DateTime)] =
    Some((self.id, self.warehouseCode, self.name, self.location, self.createdAt, self.updatedAt))

  private final case class WarehouseImpl(
      id: WarehouseId,
      warehouseCode: WarehouseCode,
      name: WarehouseName,
      location: WarehouseLocation,
      active: Boolean,
      createdAt: DateTime,
      updatedAt: DateTime
  ) extends Warehouse {

    override def update(
        newName: WarehouseName,
        newLocation: WarehouseLocation
    ): Either[UpdateWarehouseError, (Warehouse, WarehouseEvent)] = {
      if (!active) {
        Left(UpdateWarehouseError.AlreadyDeactivated)
      } else if (name == newName && location == newLocation) {
        Left(UpdateWarehouseError.NoChanges)
      } else {
        val updated = this.copy(
          name = newName,
          location = newLocation,
          updatedAt = DateTime.now()
        )
        val event = WarehouseEvent.Updated_V1(
          id = DomainEventId.generate(),
          entityId = id,
          oldName = name,
          newName = newName,
          oldLocation = location,
          newLocation = newLocation,
          occurredAt = DateTime.now()
        )
        Right((updated, event))
      }
    }

    override def deactivate: Either[DeactivateWarehouseError, (Warehouse, WarehouseEvent)] = {
      if (!active) {
        Left(DeactivateWarehouseError.AlreadyDeactivated)
      } else {
        val updated = this.copy(active = false, updatedAt = DateTime.now())
        val event = WarehouseEvent.Deactivated_V1(
          id = DomainEventId.generate(),
          entityId = id,
          occurredAt = DateTime.now()
        )
        Right((updated, event))
      }
    }

    override def reactivate: Either[ReactivateWarehouseError, (Warehouse, WarehouseEvent)] = {
      if (active) {
        Left(ReactivateWarehouseError.AlreadyActive)
      } else {
        val updated = this.copy(active = true, updatedAt = DateTime.now())
        val event = WarehouseEvent.Reactivated_V1(
          id = DomainEventId.generate(),
          entityId = id,
          occurredAt = DateTime.now()
        )
        Right((updated, event))
      }
    }
  }
}
