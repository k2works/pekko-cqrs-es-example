package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 商品名
  *
  * 商品の名称を表す値オブジェクト。
  *
  * 制約：
  * - 1文字以上200文字以下
  * - 空白のみの文字列は不可
  */
trait ProductName {
  def value: String
  def asString: String = value
}

object ProductName {
  /** 商品名の最大長 */
  final val MaxLength: Int = 200

  /** 商品名を生成
    *
    * @param value 商品名文字列
    * @return ProductName
    * @throws IllegalArgumentException 無効な商品名の場合
    */
  def apply(value: String): ProductName = parseFromString(value) match {
    case Right(v) => v
    case Left(e)  => throw new IllegalArgumentException(e.message)
  }

  def unapply(self: ProductName): Option[String] = Some(self.value)

  def from(value: String): ProductName = apply(value)

  /** 文字列から商品名をパース
    *
    * @param value 商品名文字列
    * @return ProductNameまたはエラー
    */
  def parseFromString(value: String): Either[ProductNameError, ProductName] = {
    val trimmed = value.trim
    if (trimmed.isEmpty) {
      Left(ProductNameError.Empty)
    } else if (trimmed.length > MaxLength) {
      Left(ProductNameError.TooLong)
    } else {
      Right(ProductNameImpl(trimmed))
    }
  }

  private case class ProductNameImpl(value: String) extends ProductName
}
