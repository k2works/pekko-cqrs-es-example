package io.github.j5ik2o.pcqrses.command.domain.inventory

import io.github.j5ik2o.pcqrses.command.domain.support.EntityId
import wvlet.airframe.ulid.ULID

/** 在庫ID */
trait InventoryId extends EntityId {
  def entityTypeName: String
  def asString: String
}

object InventoryId {
  final val EntityTypeName: String = "Inventory"

  /** 新しい在庫IDを生成
    *
    * @return 生成された在庫ID
    */
  def generate(): InventoryId = {
    InventoryIdImpl(ULID.newULID)
  }

  /** ULID文字列から在庫IDをパース
    *
    * @param value ULID文字列
    * @return パース結果
    */
  def parseFromString(value: String): Either[InventoryIdError, InventoryId] = {
    try {
      val ulid = ULID.fromString(value)
      Right(InventoryIdImpl(ulid))
    } catch {
      case _: IllegalArgumentException => Left(InventoryIdError.InvalidFormat)
    }
  }

  /** ULIDから在庫IDを生成
    *
    * @param ulid ULID
    * @return 在庫ID
    */
  def fromUlid(ulid: ULID): InventoryId = InventoryIdImpl(ulid)

  def unapply(self: InventoryId): Option[String] = Some(self.asString)

  private final case class InventoryIdImpl(ulid: ULID) extends InventoryId {
    override def entityTypeName: String = EntityTypeName
    override def asString: String       = ulid.toString
  }
}
