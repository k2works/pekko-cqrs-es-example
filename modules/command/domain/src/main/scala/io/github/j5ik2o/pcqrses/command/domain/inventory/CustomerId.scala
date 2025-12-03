package io.github.j5ik2o.pcqrses.command.domain.inventory

import io.github.j5ik2o.pcqrses.command.domain.support.EntityId
import wvlet.airframe.ulid.{CrockfordBase32, ULID}

/** 取引先ID
  *
  * 取引先を一意に識別するID。
  * ULIDを使用して生成される。
  */
trait CustomerId extends EntityId {
  def entityTypeName: String
  def asString: String
}

object CustomerId {
  final val EntityTypeName: String = "Customer"

  def apply(value: ULID): CustomerId = CustomerIdImpl(value)

  def unapply(self: CustomerId): Option[String] = Some(self.asString)

  /** 文字列からCustomerIdを生成
    *
    * @param value ULID文字列
    * @return CustomerId
    * @throws IllegalArgumentException 無効な文字列の場合
    */
  def from(value: String): CustomerId = parseFromString(value) match {
    case Right(v) => v
    case Left(e)  => throw new IllegalArgumentException(e.message)
  }

  /** 新しいCustomerIdを生成
    *
    * @return CustomerId
    */
  def generate(): CustomerId = CustomerIdImpl(ULID.newULID)

  /** 文字列からCustomerIdをパース
    *
    * @param value ULID文字列
    * @return CustomerIdまたはエラー
    */
  def parseFromString(value: String): Either[CustomerIdError, CustomerId] =
    if (value.isEmpty) {
      Left(CustomerIdError.Empty)
    } else if (value.length != 26) {
      Left(CustomerIdError.InvalidLength)
    } else if (!CrockfordBase32.isValidBase32(value)) {
      Left(CustomerIdError.InvalidFormat)
    } else {
      Right(apply(ULID.fromString(value)))
    }

  private case class CustomerIdImpl(ulid: ULID) extends CustomerId {
    override def entityTypeName: String = EntityTypeName
    override def asString: String       = ulid.toString
  }
}
