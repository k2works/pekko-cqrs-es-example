package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 倉庫所在地
  *
  * 倉庫の所在地を表す値オブジェクト。
  *
  * 制約：
  * - 1文字以上500文字以下
  * - 空白のみの文字列は不可
  */
trait WarehouseLocation {
  def value: String
}

object WarehouseLocation {
  /** 所在地の最大長 */
  final val MaxLength: Int = 500

  /** 所在地を生成
    *
    * @param value 所在地文字列
    * @return WarehouseLocation
    * @throws IllegalArgumentException 無効な所在地の場合
    */
  def apply(value: String): WarehouseLocation = parseFromString(value) match {
    case Right(v) => v
    case Left(e)  => throw new IllegalArgumentException(e.message)
  }

  def unapply(self: WarehouseLocation): Option[String] = Some(self.value)

  /** 文字列から所在地をパース
    *
    * @param value 所在地文字列
    * @return WarehouseLocationまたはエラー
    */
  def parseFromString(value: String): Either[WarehouseLocationError, WarehouseLocation] = {
    val trimmed = value.trim
    if (trimmed.isEmpty) {
      Left(WarehouseLocationError.Empty)
    } else if (trimmed.length > MaxLength) {
      Left(WarehouseLocationError.TooLong)
    } else {
      Right(WarehouseLocationImpl(trimmed))
    }
  }

  private case class WarehouseLocationImpl(value: String) extends WarehouseLocation
}
