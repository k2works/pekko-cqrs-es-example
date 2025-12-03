package io.github.j5ik2o.pcqrses.command.domain.inventory

import io.github.j5ik2o.pcqrses.command.domain.basic.DateTime
import io.github.j5ik2o.pcqrses.command.domain.support.{DomainEventId, Entity}

/** 在庫集約
  *
  * 商品の在庫を管理する集約。
  * 在庫引当の競合制御を楽観的ロックで実現する。
  *
  * ビジネスルール：
  * - 在庫数量はマイナスになってはならない
  * - 引当数量は利用可能在庫数量を超えてはならない
  * - 出庫は引当数量の範囲内で行われる
  * - 在庫移動は保管条件が一致する倉庫ゾーン間でのみ可能
  * - 楽観的ロックにより競合を検出し、バージョン不一致時はエラーを返す
  */
trait Inventory extends Entity {
  override type IdType = InventoryId

  /** 在庫ID */
  def id: InventoryId

  /** 商品ID */
  def productId: ProductId

  /** 倉庫ゾーンID */
  def warehouseZoneId: WarehouseZoneId

  /** 利用可能在庫数量 */
  def availableQuantity: InventoryQuantity

  /** 引当済み在庫数量 */
  def reservedQuantity: InventoryQuantity

  /** バージョン（楽観的ロック用） */
  def version: InventoryVersion

  /** 作成日時 */
  def createdAt: DateTime

  /** 更新日時 */
  def updatedAt: DateTime

  /** 在庫を入庫
    *
    * @param quantity 入庫数量
    * @param expectedVersion 期待するバージョン（楽観的ロック）
    * @return 更新後の在庫とイベント、またはエラー
    */
  def receive(
      quantity: InventoryQuantity,
      expectedVersion: InventoryVersion
  ): Either[ReceiveInventoryError, (Inventory, InventoryEvent)]

  /** 在庫を引当
    *
    * @param quantity 引当数量
    * @param expectedVersion 期待するバージョン（楽観的ロック）
    * @return 更新後の在庫とイベント、またはエラー
    */
  def reserve(
      quantity: InventoryQuantity,
      expectedVersion: InventoryVersion
  ): Either[ReserveInventoryError, (Inventory, InventoryEvent)]

  /** 在庫引当を解放
    *
    * @param quantity 解放数量
    * @param expectedVersion 期待するバージョン（楽観的ロック）
    * @return 更新後の在庫とイベント、またはエラー
    */
  def release(
      quantity: InventoryQuantity,
      expectedVersion: InventoryVersion
  ): Either[ReleaseInventoryError, (Inventory, InventoryEvent)]

  /** 在庫を出庫
    *
    * @param quantity 出庫数量
    * @param expectedVersion 期待するバージョン（楽観的ロック）
    * @return 更新後の在庫とイベント、またはエラー
    */
  def issue(
      quantity: InventoryQuantity,
      expectedVersion: InventoryVersion
  ): Either[IssueInventoryError, (Inventory, InventoryEvent)]

  /** 在庫を調整
    *
    * @param newQuantity 調整後の在庫数量
    * @param reason 調整理由
    * @param expectedVersion 期待するバージョン（楽観的ロック）
    * @return 更新後の在庫とイベント、またはエラー
    */
  def adjust(
      newQuantity: InventoryQuantity,
      reason: String,
      expectedVersion: InventoryVersion
  ): Either[AdjustInventoryError, (Inventory, InventoryEvent)]
}

object Inventory {

  /** 在庫を作成（初期在庫ゼロ）
    *
    * @param id 在庫ID
    * @param productId 商品ID
    * @param warehouseZoneId 倉庫ゾーンID
    * @param createdAt 作成日時（省略時は現在時刻）
    * @param updatedAt 更新日時（省略時は現在時刻）
    * @return 作成された在庫とReceivedイベント
    */
  def create(
      id: InventoryId,
      productId: ProductId,
      warehouseZoneId: WarehouseZoneId,
      createdAt: DateTime = DateTime.now(),
      updatedAt: DateTime = DateTime.now()
  ): Inventory = {
    InventoryImpl(
      id = id,
      productId = productId,
      warehouseZoneId = warehouseZoneId,
      availableQuantity = InventoryQuantity.Zero,
      reservedQuantity = InventoryQuantity.Zero,
      version = InventoryVersion.Initial,
      createdAt = createdAt,
      updatedAt = updatedAt
    )
  }

  def unapply(
      self: Inventory
  ): Option[(
      InventoryId,
      ProductId,
      WarehouseZoneId,
      InventoryQuantity,
      InventoryQuantity,
      InventoryVersion,
      DateTime,
      DateTime
  )] =
    Some(
      (
        self.id,
        self.productId,
        self.warehouseZoneId,
        self.availableQuantity,
        self.reservedQuantity,
        self.version,
        self.createdAt,
        self.updatedAt
      )
    )

  private final case class InventoryImpl(
      id: InventoryId,
      productId: ProductId,
      warehouseZoneId: WarehouseZoneId,
      availableQuantity: InventoryQuantity,
      reservedQuantity: InventoryQuantity,
      version: InventoryVersion,
      createdAt: DateTime,
      updatedAt: DateTime
  ) extends Inventory {

    override def receive(
        quantity: InventoryQuantity,
        expectedVersion: InventoryVersion
    ): Either[ReceiveInventoryError, (Inventory, InventoryEvent)] = {
      if (!version.matches(expectedVersion)) {
        Left(ReceiveInventoryError.VersionMismatch)
      } else {
        val newAvailable      = availableQuantity.add(quantity)
        val newVersion        = version.next
        val now               = DateTime.now()
        val updated           = this.copy(availableQuantity = newAvailable, version = newVersion, updatedAt = now)
        val event = InventoryEvent.Received_V1(
          id = DomainEventId.generate(),
          entityId = id,
          productId = productId,
          warehouseZoneId = warehouseZoneId,
          quantity = quantity,
          version = newVersion,
          occurredAt = now
        )
        Right((updated, event))
      }
    }

    override def reserve(
        quantity: InventoryQuantity,
        expectedVersion: InventoryVersion
    ): Either[ReserveInventoryError, (Inventory, InventoryEvent)] = {
      if (!version.matches(expectedVersion)) {
        Left(ReserveInventoryError.VersionMismatch)
      } else if (!availableQuantity.isGreaterThanOrEqual(quantity)) {
        Left(ReserveInventoryError.InsufficientStock)
      } else {
        availableQuantity.subtract(quantity) match {
          case Left(_) => Left(ReserveInventoryError.InsufficientStock)
          case Right(newAvailable) =>
            val newReserved = reservedQuantity.add(quantity)
            val newVersion  = version.next
            val now         = DateTime.now()
            val updated = this.copy(
              availableQuantity = newAvailable,
              reservedQuantity = newReserved,
              version = newVersion,
              updatedAt = now
            )
            val event = InventoryEvent.Reserved_V1(
              id = DomainEventId.generate(),
              entityId = id,
              productId = productId,
              warehouseZoneId = warehouseZoneId,
              quantity = quantity,
              version = newVersion,
              occurredAt = now
            )
            Right((updated, event))
        }
      }
    }

    override def release(
        quantity: InventoryQuantity,
        expectedVersion: InventoryVersion
    ): Either[ReleaseInventoryError, (Inventory, InventoryEvent)] = {
      if (!version.matches(expectedVersion)) {
        Left(ReleaseInventoryError.VersionMismatch)
      } else if (!reservedQuantity.isGreaterThanOrEqual(quantity)) {
        Left(ReleaseInventoryError.InsufficientReserved)
      } else {
        reservedQuantity.subtract(quantity) match {
          case Left(_) => Left(ReleaseInventoryError.InsufficientReserved)
          case Right(newReserved) =>
            val newAvailable = availableQuantity.add(quantity)
            val newVersion   = version.next
            val now          = DateTime.now()
            val updated = this.copy(
              availableQuantity = newAvailable,
              reservedQuantity = newReserved,
              version = newVersion,
              updatedAt = now
            )
            val event = InventoryEvent.Released_V1(
              id = DomainEventId.generate(),
              entityId = id,
              productId = productId,
              warehouseZoneId = warehouseZoneId,
              quantity = quantity,
              version = newVersion,
              occurredAt = now
            )
            Right((updated, event))
        }
      }
    }

    override def issue(
        quantity: InventoryQuantity,
        expectedVersion: InventoryVersion
    ): Either[IssueInventoryError, (Inventory, InventoryEvent)] = {
      if (!version.matches(expectedVersion)) {
        Left(IssueInventoryError.VersionMismatch)
      } else if (!reservedQuantity.isGreaterThanOrEqual(quantity)) {
        Left(IssueInventoryError.InsufficientReserved)
      } else {
        reservedQuantity.subtract(quantity) match {
          case Left(_) => Left(IssueInventoryError.InsufficientReserved)
          case Right(newReserved) =>
            val newVersion = version.next
            val now        = DateTime.now()
            val updated    = this.copy(reservedQuantity = newReserved, version = newVersion, updatedAt = now)
            val event = InventoryEvent.Issued_V1(
              id = DomainEventId.generate(),
              entityId = id,
              productId = productId,
              warehouseZoneId = warehouseZoneId,
              quantity = quantity,
              version = newVersion,
              occurredAt = now
            )
            Right((updated, event))
        }
      }
    }

    override def adjust(
        newQuantity: InventoryQuantity,
        reason: String,
        expectedVersion: InventoryVersion
    ): Either[AdjustInventoryError, (Inventory, InventoryEvent)] = {
      if (!version.matches(expectedVersion)) {
        Left(AdjustInventoryError.VersionMismatch)
      } else {
        val newVersion = version.next
        val now        = DateTime.now()
        val updated    = this.copy(availableQuantity = newQuantity, version = newVersion, updatedAt = now)
        val event = InventoryEvent.Adjusted_V1(
          id = DomainEventId.generate(),
          entityId = id,
          productId = productId,
          warehouseZoneId = warehouseZoneId,
          oldQuantity = availableQuantity,
          newQuantity = newQuantity,
          reason = reason,
          version = newVersion,
          occurredAt = now
        )
        Right((updated, event))
      }
    }
  }
}
