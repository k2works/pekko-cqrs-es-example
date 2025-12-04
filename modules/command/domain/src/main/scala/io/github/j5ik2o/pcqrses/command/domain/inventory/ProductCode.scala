package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 商品コード
  *
  * 商品を識別するための業務コード。
  * D社では約8,000品目を扱うため、商品コードは業務上重要な識別子となる。
  *
  * 制約：
  * - 1文字以上50文字以下
  * - 英数字とハイフン、アンダースコアのみ許可
  */
trait ProductCode {
  def value: String
  def asString: String = value
}

object ProductCode {
  /** 商品コードの最大長 */
  final val MaxLength: Int = 50

  /** 商品コードを生成
    *
    * @param value 商品コード文字列
    * @return ProductCode
    * @throws IllegalArgumentException 無効な商品コードの場合
    */
  def apply(value: String): ProductCode = parseFromString(value) match {
    case Right(v) => v
    case Left(e)  => throw new IllegalArgumentException(e.message)
  }

  def unapply(self: ProductCode): Option[String] = Some(self.value)

  def from(value: String): ProductCode = apply(value)

  /** 文字列から商品コードをパース
    *
    * @param value 商品コード文字列
    * @return ProductCodeまたはエラー
    */
  def parseFromString(value: String): Either[ProductCodeError, ProductCode] = {
    if (value.isEmpty) {
      Left(ProductCodeError.Empty)
    } else if (value.length > MaxLength) {
      Left(ProductCodeError.TooLong)
    } else if (!value.matches("^[a-zA-Z0-9_-]+$")) {
      Left(ProductCodeError.InvalidFormat)
    } else {
      Right(ProductCodeImpl(value))
    }
  }

  private case class ProductCodeImpl(value: String) extends ProductCode
}
