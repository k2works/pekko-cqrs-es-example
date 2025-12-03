package io.github.j5ik2o.pcqrses.command.domain.inventory

import io.github.j5ik2o.pcqrses.command.domain.support.EntityId
import wvlet.airframe.ulid.{CrockfordBase32, ULID}

/** 倉庫ID
  *
  * 倉庫を一意に識別するID。
  * ULIDを使用して生成される。
  */
trait WarehouseId extends EntityId {
  def entityTypeName: String
  def asString: String
}

object WarehouseId {
  final val EntityTypeName: String = "Warehouse"

  def apply(value: ULID): WarehouseId = WarehouseIdImpl(value)

  def unapply(self: WarehouseId): Option[String] = Some(self.asString)

  /** 文字列からWarehouseIdを生成
    *
    * @param value ULID文字列
    * @return WarehouseId
    * @throws IllegalArgumentException 無効な文字列の場合
    */
  def from(value: String): WarehouseId = parseFromString(value) match {
    case Right(v) => v
    case Left(e)  => throw new IllegalArgumentException(e.message)
  }

  /** 新しいWarehouseIdを生成
    *
    * @return WarehouseId
    */
  def generate(): WarehouseId = WarehouseIdImpl(ULID.newULID)

  /** 文字列からWarehouseIdをパース
    *
    * @param value ULID文字列
    * @return WarehouseIdまたはエラー
    */
  def parseFromString(value: String): Either[WarehouseIdError, WarehouseId] =
    if (value.isEmpty) {
      Left(WarehouseIdError.Empty)
    } else if (value.length != 26) {
      Left(WarehouseIdError.InvalidLength)
    } else if (!CrockfordBase32.isValidBase32(value)) {
      Left(WarehouseIdError.InvalidFormat)
    } else {
      Right(apply(ULID.fromString(value)))
    }

  private case class WarehouseIdImpl(ulid: ULID) extends WarehouseId {
    override def entityTypeName: String = EntityTypeName
    override def asString: String       = ulid.toString
  }
}
