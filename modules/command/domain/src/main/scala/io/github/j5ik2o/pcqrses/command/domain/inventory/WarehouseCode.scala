package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 倉庫コード
  *
  * 倉庫を識別するための業務コード。
  * D社では3拠点の倉庫を管理する。
  *
  * 例：
  * - TOKYO: 東京倉庫
  * - OSAKA: 大阪倉庫
  * - FUKUOKA: 福岡倉庫
  *
  * 制約：
  * - 1文字以上50文字以下
  * - 英数字とハイフン、アンダースコアのみ許可
  */
trait WarehouseCode {
  def value: String
}

object WarehouseCode {
  /** 倉庫コードの最大長 */
  final val MaxLength: Int = 50

  /** 倉庫コードを生成
    *
    * @param value 倉庫コード文字列
    * @return WarehouseCode
    * @throws IllegalArgumentException 無効な倉庫コードの場合
    */
  def apply(value: String): WarehouseCode = parseFromString(value) match {
    case Right(v) => v
    case Left(e)  => throw new IllegalArgumentException(e.message)
  }

  def unapply(self: WarehouseCode): Option[String] = Some(self.value)

  /** 文字列から倉庫コードをパース
    *
    * @param value 倉庫コード文字列
    * @return WarehouseCodeまたはエラー
    */
  def parseFromString(value: String): Either[WarehouseCodeError, WarehouseCode] = {
    if (value.isEmpty) {
      Left(WarehouseCodeError.Empty)
    } else if (value.length > MaxLength) {
      Left(WarehouseCodeError.TooLong)
    } else if (!value.matches("^[a-zA-Z0-9_-]+$")) {
      Left(WarehouseCodeError.InvalidFormat)
    } else {
      Right(WarehouseCodeImpl(value))
    }
  }

  private case class WarehouseCodeImpl(value: String) extends WarehouseCode
}
