package io.github.j5ik2o.pcqrses.command.domain.inventory

import io.github.j5ik2o.pcqrses.command.domain.support.EntityId
import wvlet.airframe.ulid.{CrockfordBase32, ULID}

/** 倉庫区画ID
  *
  * 倉庫区画を一意に識別するID。
  * ULIDを使用して生成される。
  */
trait WarehouseZoneId extends EntityId {
  def entityTypeName: String
  def asString: String
}

object WarehouseZoneId {
  final val EntityTypeName: String = "WarehouseZone"

  def apply(value: ULID): WarehouseZoneId = WarehouseZoneIdImpl(value)

  def unapply(self: WarehouseZoneId): Option[String] = Some(self.asString)

  /** 文字列からWarehouseZoneIdを生成
    *
    * @param value ULID文字列
    * @return WarehouseZoneId
    * @throws IllegalArgumentException 無効な文字列の場合
    */
  def from(value: String): WarehouseZoneId = parseFromString(value) match {
    case Right(v) => v
    case Left(e)  => throw new IllegalArgumentException(e.message)
  }

  /** 新しいWarehouseZoneIdを生成
    *
    * @return WarehouseZoneId
    */
  def generate(): WarehouseZoneId = WarehouseZoneIdImpl(ULID.newULID)

  /** 文字列からWarehouseZoneIdをパース
    *
    * @param value ULID文字列
    * @return WarehouseZoneIdまたはエラー
    */
  def parseFromString(value: String): Either[WarehouseZoneIdError, WarehouseZoneId] =
    if (value.isEmpty) {
      Left(WarehouseZoneIdError.Empty)
    } else if (value.length != 26) {
      Left(WarehouseZoneIdError.InvalidLength)
    } else if (!CrockfordBase32.isValidBase32(value)) {
      Left(WarehouseZoneIdError.InvalidFormat)
    } else {
      Right(apply(ULID.fromString(value)))
    }

  private case class WarehouseZoneIdImpl(ulid: ULID) extends WarehouseZoneId {
    override def entityTypeName: String = EntityTypeName
    override def asString: String       = ulid.toString
  }
}
