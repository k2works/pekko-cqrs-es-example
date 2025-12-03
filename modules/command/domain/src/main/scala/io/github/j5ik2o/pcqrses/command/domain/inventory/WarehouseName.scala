package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 倉庫名
  *
  * 倉庫の名称を表す値オブジェクト。
  *
  * 制約：
  * - 1文字以上200文字以下
  * - 空白のみの文字列は不可
  */
trait WarehouseName {
  def value: String
}

object WarehouseName {
  /** 倉庫名の最大長 */
  final val MaxLength: Int = 200

  /** 倉庫名を生成
    *
    * @param value 倉庫名文字列
    * @return WarehouseName
    * @throws IllegalArgumentException 無効な倉庫名の場合
    */
  def apply(value: String): WarehouseName = parseFromString(value) match {
    case Right(v) => v
    case Left(e)  => throw new IllegalArgumentException(e.message)
  }

  def unapply(self: WarehouseName): Option[String] = Some(self.value)

  /** 文字列から倉庫名をパース
    *
    * @param value 倉庫名文字列
    * @return WarehouseNameまたはエラー
    */
  def parseFromString(value: String): Either[WarehouseNameError, WarehouseName] = {
    val trimmed = value.trim
    if (trimmed.isEmpty) {
      Left(WarehouseNameError.Empty)
    } else if (trimmed.length > MaxLength) {
      Left(WarehouseNameError.TooLong)
    } else {
      Right(WarehouseNameImpl(trimmed))
    }
  }

  private case class WarehouseNameImpl(value: String) extends WarehouseName
}
