package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 取引先名
  *
  * 取引先の名称を表す値オブジェクト。
  *
  * 制約：
  * - 1文字以上200文字以下
  * - 空白のみの文字列は不可
  */
trait CustomerName {
  def value: String
  def asString: String = value
}

object CustomerName {
  /** 取引先名の最大長 */
  final val MaxLength: Int = 200

  /** 取引先名を生成
    *
    * @param value 取引先名文字列
    * @return CustomerName
    * @throws IllegalArgumentException 無効な取引先名の場合
    */
  def apply(value: String): CustomerName = parseFromString(value) match {
    case Right(v) => v
    case Left(e)  => throw new IllegalArgumentException(e.message)
  }

  def unapply(self: CustomerName): Option[String] = Some(self.value)

  /** 文字列から取引先名を生成
    *
    * @param value 取引先名文字列
    * @return CustomerName
    * @throws IllegalArgumentException 無効な取引先名の場合
    */
  def from(value: String): CustomerName = apply(value)

  /** 文字列から取引先名をパース
    *
    * @param value 取引先名文字列
    * @return CustomerNameまたはエラー
    */
  def parseFromString(value: String): Either[CustomerNameError, CustomerName] = {
    val trimmed = value.trim
    if (trimmed.isEmpty) {
      Left(CustomerNameError.Empty)
    } else if (trimmed.length > MaxLength) {
      Left(CustomerNameError.TooLong)
    } else {
      Right(CustomerNameImpl(trimmed))
    }
  }

  private case class CustomerNameImpl(value: String) extends CustomerName
}
