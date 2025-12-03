package io.github.j5ik2o.pcqrses.command.domain.inventory

import io.github.j5ik2o.pcqrses.command.domain.support.EntityId
import wvlet.airframe.ulid.{CrockfordBase32, ULID}

/** 商品ID
  *
  * 商品を一意に識別するID。
  * ULIDを使用して生成される。
  */
trait ProductId extends EntityId {
  def entityTypeName: String
  def asString: String
}

object ProductId {
  final val EntityTypeName: String = "Product"

  def apply(value: ULID): ProductId = ProductIdImpl(value)

  def unapply(self: ProductId): Option[String] = Some(self.asString)

  /** 文字列からProductIdを生成
    *
    * @param value ULID文字列
    * @return ProductId
    * @throws IllegalArgumentException 無効な文字列の場合
    */
  def from(value: String): ProductId = parseFromString(value) match {
    case Right(v) => v
    case Left(e)  => throw new IllegalArgumentException(e.message)
  }

  /** 新しいProductIdを生成
    *
    * @return ProductId
    */
  def generate(): ProductId = ProductIdImpl(ULID.newULID)

  /** 文字列からProductIdをパース
    *
    * @param value ULID文字列
    * @return ProductIdまたはエラー
    */
  def parseFromString(value: String): Either[ProductIdError, ProductId] =
    if (value.isEmpty) {
      Left(ProductIdError.Empty)
    } else if (value.length != 26) {
      Left(ProductIdError.InvalidLength)
    } else if (!CrockfordBase32.isValidBase32(value)) {
      Left(ProductIdError.InvalidFormat)
    } else {
      Right(apply(ULID.fromString(value)))
    }

  private case class ProductIdImpl(ulid: ULID) extends ProductId {
    override def entityTypeName: String = EntityTypeName
    override def asString: String       = ulid.toString
  }
}
