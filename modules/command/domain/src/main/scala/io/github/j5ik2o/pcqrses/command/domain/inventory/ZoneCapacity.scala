package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 区画容量
  *
  * 倉庫区画の容量を表す値オブジェクト。
  * 容量は平米（㎡）で管理する。
  *
  * 制約：
  * - 容量は0より大きい値である必要がある
  */
trait ZoneCapacity {
  def squareMeters: BigDecimal
}

object ZoneCapacity {

  /** 区画容量を生成
    *
    * @param squareMeters 容量（平米）
    * @return ZoneCapacity
    * @throws IllegalArgumentException 無効な容量の場合
    */
  def apply(squareMeters: BigDecimal): ZoneCapacity = parseFromBigDecimal(squareMeters) match {
    case Right(v) => v
    case Left(e)  => throw new IllegalArgumentException(e.message)
  }

  def unapply(self: ZoneCapacity): Option[BigDecimal] = Some(self.squareMeters)

  /** BigDecimalから区画容量をパース
    *
    * @param squareMeters 容量（平米）
    * @return ZoneCapacityまたはエラー
    */
  def parseFromBigDecimal(squareMeters: BigDecimal): Either[ZoneCapacityError, ZoneCapacity] = {
    if (squareMeters <= 0) {
      Left(ZoneCapacityError.NotPositive)
    } else {
      Right(ZoneCapacityImpl(squareMeters))
    }
  }

  private case class ZoneCapacityImpl(squareMeters: BigDecimal) extends ZoneCapacity
}
