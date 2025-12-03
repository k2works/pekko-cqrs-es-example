package io.github.j5ik2o.pcqrses.command.domain.inventory

import io.github.j5ik2o.pcqrses.command.domain.basic.DateTime
import io.github.j5ik2o.pcqrses.command.domain.support.{DomainEventId, Entity}

/** 商品集約
  *
  * D社の商品マスタを管理する集約。
  * 約8,000品目の商品情報（商品コード、商品名、カテゴリ、保管条件）を管理する。
  *
  * ビジネスルール：
  * - 商品コードは一意である必要がある
  * - 保管条件は常温、冷蔵、冷凍のいずれかである必要がある
  * - 廃止された商品は更新できない
  */
trait Product extends Entity {
  override type IdType = ProductId

  /** 商品ID */
  def id: ProductId

  /** 商品コード */
  def productCode: ProductCode

  /** 商品名 */
  def name: ProductName

  /** カテゴリコード */
  def categoryCode: CategoryCode

  /** 保管条件 */
  def storageCondition: StorageCondition

  /** 作成日時 */
  def createdAt: DateTime

  /** 更新日時 */
  def updatedAt: DateTime

  /** 商品情報を更新
    *
    * @param newName 新しい商品名
    * @param newCategoryCode 新しいカテゴリコード
    * @param newStorageCondition 新しい保管条件
    * @return 更新後の商品とイベント、またはエラー
    */
  def update(
      newName: ProductName,
      newCategoryCode: CategoryCode,
      newStorageCondition: StorageCondition
  ): Either[UpdateProductError, (Product, ProductEvent)]

  /** 商品を廃止
    *
    * @return 廃止後の商品とイベント、またはエラー
    */
  def obsolete: Either[ObsoleteProductError, (Product, ProductEvent)]
}

object Product {

  /** 商品を作成
    *
    * @param id 商品ID
    * @param productCode 商品コード
    * @param name 商品名
    * @param categoryCode カテゴリコード
    * @param storageCondition 保管条件
    * @param createdAt 作成日時（省略時は現在時刻）
    * @param updatedAt 更新日時（省略時は現在時刻）
    * @return 作成された商品とCreatedイベント
    */
  def apply(
      id: ProductId,
      productCode: ProductCode,
      name: ProductName,
      categoryCode: CategoryCode,
      storageCondition: StorageCondition,
      createdAt: DateTime = DateTime.now(),
      updatedAt: DateTime = DateTime.now()
  ): (Product, ProductEvent) = {
    val product = ProductImpl(
      id = id,
      productCode = productCode,
      name = name,
      categoryCode = categoryCode,
      storageCondition = storageCondition,
      obsoleted = false,
      createdAt = createdAt,
      updatedAt = updatedAt
    )
    val event = ProductEvent.Created_V1(
      id = DomainEventId.generate(),
      entityId = id,
      productCode = productCode,
      name = name,
      categoryCode = categoryCode,
      storageCondition = storageCondition,
      occurredAt = DateTime.now()
    )
    (product, event)
  }

  def unapply(
      self: Product
  ): Option[(ProductId, ProductCode, ProductName, CategoryCode, StorageCondition, DateTime, DateTime)] =
    Some((self.id, self.productCode, self.name, self.categoryCode, self.storageCondition, self.createdAt, self.updatedAt))

  private final case class ProductImpl(
      id: ProductId,
      productCode: ProductCode,
      name: ProductName,
      categoryCode: CategoryCode,
      storageCondition: StorageCondition,
      obsoleted: Boolean,
      createdAt: DateTime,
      updatedAt: DateTime
  ) extends Product {

    override def update(
        newName: ProductName,
        newCategoryCode: CategoryCode,
        newStorageCondition: StorageCondition
    ): Either[UpdateProductError, (Product, ProductEvent)] = {
      if (obsoleted) {
        Left(UpdateProductError.AlreadyObsoleted)
      } else if (name == newName && categoryCode == newCategoryCode && storageCondition == newStorageCondition) {
        Left(UpdateProductError.NoChanges)
      } else {
        val updated = this.copy(
          name = newName,
          categoryCode = newCategoryCode,
          storageCondition = newStorageCondition,
          updatedAt = DateTime.now()
        )
        val event = ProductEvent.Updated_V1(
          id = DomainEventId.generate(),
          entityId = id,
          oldName = name,
          newName = newName,
          oldCategoryCode = categoryCode,
          newCategoryCode = newCategoryCode,
          oldStorageCondition = storageCondition,
          newStorageCondition = newStorageCondition,
          occurredAt = DateTime.now()
        )
        Right((updated, event))
      }
    }

    override def obsolete: Either[ObsoleteProductError, (Product, ProductEvent)] = {
      if (obsoleted) {
        Left(ObsoleteProductError.AlreadyObsoleted)
      } else {
        val updated = this.copy(obsoleted = true, updatedAt = DateTime.now())
        val event = ProductEvent.Obsoleted_V1(
          id = DomainEventId.generate(),
          entityId = id,
          occurredAt = DateTime.now()
        )
        Right((updated, event))
      }
    }
  }
}
