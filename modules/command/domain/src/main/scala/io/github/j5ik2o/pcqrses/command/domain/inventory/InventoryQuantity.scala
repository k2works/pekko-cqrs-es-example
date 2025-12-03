package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 在庫数量
  *
  * 在庫の数量を表す値オブジェクト。
  * 負の数は許可されない。
  */
trait InventoryQuantity {
  /** 数量 */
  def amount: BigDecimal

  /** 加算
    *
    * @param other 加算する数量
    * @return 加算結果
    */
  def add(other: InventoryQuantity): InventoryQuantity = {
    InventoryQuantity.InventoryQuantityImpl(amount + other.amount)
  }

  /** 減算
    *
    * @param other 減算する数量
    * @return 減算結果（負の数になる場合はエラー）
    */
  def subtract(other: InventoryQuantity): Either[InventoryQuantityError, InventoryQuantity] = {
    val newAmount = amount - other.amount
    if (newAmount < 0) {
      Left(InventoryQuantityError.Negative)
    } else {
      Right(InventoryQuantity.InventoryQuantityImpl(newAmount))
    }
  }

  /** 指定数量以上かチェック
    *
    * @param other 比較する数量
    * @return true: 指定数量以上、false: 指定数量未満
    */
  def isGreaterThanOrEqual(other: InventoryQuantity): Boolean = {
    amount >= other.amount
  }

  /** ゼロかどうかチェック
    *
    * @return true: ゼロ、false: ゼロでない
    */
  def isZero: Boolean = amount == 0
}

object InventoryQuantity {

  /** ゼロ数量 */
  val Zero: InventoryQuantity = InventoryQuantityImpl(BigDecimal(0))

  /** BigDecimalから在庫数量をパース
    *
    * @param amount 数量
    * @return パース結果
    */
  def parseFromBigDecimal(amount: BigDecimal): Either[InventoryQuantityError, InventoryQuantity] = {
    if (amount < 0) {
      Left(InventoryQuantityError.Negative)
    } else {
      Right(InventoryQuantityImpl(amount))
    }
  }

  /** Intから在庫数量をパース
    *
    * @param amount 数量
    * @return パース結果
    */
  def parseFromInt(amount: Int): Either[InventoryQuantityError, InventoryQuantity] = {
    parseFromBigDecimal(BigDecimal(amount))
  }

  def unapply(self: InventoryQuantity): Option[BigDecimal] = Some(self.amount)

  private[inventory] final case class InventoryQuantityImpl(amount: BigDecimal) extends InventoryQuantity
}
