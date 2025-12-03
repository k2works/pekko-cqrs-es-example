package io.github.j5ik2o.pcqrses.command.domain.inventory

import io.github.j5ik2o.pcqrses.command.domain.basic.DateTime
import io.github.j5ik2o.pcqrses.command.domain.support.{DomainEventId, Entity}

/** 取引先集約
  *
  * D社の取引先を管理する集約。
  * 約430社の取引先情報を管理する。
  *
  * ビジネスルール：
  * - 取引先コードは一意である必要がある
  * - 取引先タイプは大口、中口、小口のいずれかである必要がある
  * - 無効化された取引先は更新できない
  */
trait Customer extends Entity {
  override type IdType = CustomerId

  /** 取引先ID */
  def id: CustomerId

  /** 取引先コード */
  def customerCode: CustomerCode

  /** 取引先名 */
  def name: CustomerName

  /** 取引先タイプ */
  def customerType: CustomerType

  /** 作成日時 */
  def createdAt: DateTime

  /** 更新日時 */
  def updatedAt: DateTime

  /** 取引先情報を更新
    *
    * @param newName 新しい取引先名
    * @param newCustomerType 新しい取引先タイプ
    * @return 更新後の取引先とイベント、またはエラー
    */
  def update(
      newName: CustomerName,
      newCustomerType: CustomerType
  ): Either[UpdateCustomerError, (Customer, CustomerEvent)]

  /** 取引先を無効化
    *
    * @return 無効化後の取引先とイベント、またはエラー
    */
  def deactivate: Either[DeactivateCustomerError, (Customer, CustomerEvent)]

  /** 取引先を再有効化
    *
    * @return 再有効化後の取引先とイベント、またはエラー
    */
  def reactivate: Either[ReactivateCustomerError, (Customer, CustomerEvent)]
}

object Customer {

  /** 取引先を作成
    *
    * @param id 取引先ID
    * @param customerCode 取引先コード
    * @param name 取引先名
    * @param customerType 取引先タイプ
    * @param createdAt 作成日時（省略時は現在時刻）
    * @param updatedAt 更新日時（省略時は現在時刻）
    * @return 作成された取引先とCreatedイベント
    */
  def apply(
      id: CustomerId,
      customerCode: CustomerCode,
      name: CustomerName,
      customerType: CustomerType,
      createdAt: DateTime = DateTime.now(),
      updatedAt: DateTime = DateTime.now()
  ): (Customer, CustomerEvent) = {
    val customer = CustomerImpl(
      id = id,
      customerCode = customerCode,
      name = name,
      customerType = customerType,
      active = true,
      createdAt = createdAt,
      updatedAt = updatedAt
    )
    val event = CustomerEvent.Created_V1(
      id = DomainEventId.generate(),
      entityId = id,
      customerCode = customerCode,
      name = name,
      customerType = customerType,
      occurredAt = DateTime.now()
    )
    (customer, event)
  }

  def unapply(
      self: Customer
  ): Option[(CustomerId, CustomerCode, CustomerName, CustomerType, DateTime, DateTime)] =
    Some((self.id, self.customerCode, self.name, self.customerType, self.createdAt, self.updatedAt))

  private final case class CustomerImpl(
      id: CustomerId,
      customerCode: CustomerCode,
      name: CustomerName,
      customerType: CustomerType,
      active: Boolean,
      createdAt: DateTime,
      updatedAt: DateTime
  ) extends Customer {

    override def update(
        newName: CustomerName,
        newCustomerType: CustomerType
    ): Either[UpdateCustomerError, (Customer, CustomerEvent)] = {
      if (!active) {
        Left(UpdateCustomerError.AlreadyDeactivated)
      } else if (name == newName && customerType == newCustomerType) {
        Left(UpdateCustomerError.NoChanges)
      } else {
        val updated = this.copy(
          name = newName,
          customerType = newCustomerType,
          updatedAt = DateTime.now()
        )
        val event = CustomerEvent.Updated_V1(
          id = DomainEventId.generate(),
          entityId = id,
          oldName = name,
          newName = newName,
          oldCustomerType = customerType,
          newCustomerType = newCustomerType,
          occurredAt = DateTime.now()
        )
        Right((updated, event))
      }
    }

    override def deactivate: Either[DeactivateCustomerError, (Customer, CustomerEvent)] = {
      if (!active) {
        Left(DeactivateCustomerError.AlreadyDeactivated)
      } else {
        val updated = this.copy(active = false, updatedAt = DateTime.now())
        val event = CustomerEvent.Deactivated_V1(
          id = DomainEventId.generate(),
          entityId = id,
          occurredAt = DateTime.now()
        )
        Right((updated, event))
      }
    }

    override def reactivate: Either[ReactivateCustomerError, (Customer, CustomerEvent)] = {
      if (active) {
        Left(ReactivateCustomerError.AlreadyActive)
      } else {
        val updated = this.copy(active = true, updatedAt = DateTime.now())
        val event = CustomerEvent.Reactivated_V1(
          id = DomainEventId.generate(),
          entityId = id,
          occurredAt = DateTime.now()
        )
        Right((updated, event))
      }
    }
  }
}
