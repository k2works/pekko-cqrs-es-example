package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 在庫バージョン
  *
  * 在庫引当の競合制御に使用するバージョン番号。
  * 楽観的ロックのためにインクリメントされる。
  */
trait InventoryVersion {
  /** バージョン番号 */
  def value: Long

  /** 次のバージョンを取得
    *
    * @return インクリメントされたバージョン
    */
  def next: InventoryVersion = {
    InventoryVersion.InventoryVersionImpl(value + 1)
  }

  /** 指定されたバージョンと一致するかチェック
    *
    * @param other 比較するバージョン
    * @return true: 一致、false: 不一致
    */
  def matches(other: InventoryVersion): Boolean = {
    value == other.value
  }
}

object InventoryVersion {

  /** 初期バージョン（1） */
  val Initial: InventoryVersion = InventoryVersionImpl(1L)

  /** Longからバージョンを生成
    *
    * @param value バージョン番号
    * @return バージョン
    */
  def parseFromLong(value: Long): Either[InventoryVersionError, InventoryVersion] = {
    if (value <= 0) {
      Left(InventoryVersionError.NotPositive)
    } else {
      Right(InventoryVersionImpl(value))
    }
  }

  def unapply(self: InventoryVersion): Option[Long] = Some(self.value)

  private[inventory] final case class InventoryVersionImpl(value: Long) extends InventoryVersion
}
