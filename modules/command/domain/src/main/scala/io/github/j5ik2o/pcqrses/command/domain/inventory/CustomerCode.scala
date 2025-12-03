package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 取引先コード
  *
  * 取引先を識別するための業務コード。
  * D社では約430社の取引先を管理する。
  *
  * 例：
  * - SUPER-001: スーパーマーケットチェーン
  * - CONV-001: コンビニエンスストア
  * - REST-001: 飲食店・レストラン
  * - RETAIL-001: その他小売店
  *
  * 制約：
  * - 1文字以上50文字以下
  * - 英数字とハイフン、アンダースコアのみ許可
  */
trait CustomerCode {
  def value: String
}

object CustomerCode {
  /** 取引先コードの最大長 */
  final val MaxLength: Int = 50

  /** 取引先コードを生成
    *
    * @param value 取引先コード文字列
    * @return CustomerCode
    * @throws IllegalArgumentException 無効な取引先コードの場合
    */
  def apply(value: String): CustomerCode = parseFromString(value) match {
    case Right(v) => v
    case Left(e)  => throw new IllegalArgumentException(e.message)
  }

  def unapply(self: CustomerCode): Option[String] = Some(self.value)

  /** 文字列から取引先コードをパース
    *
    * @param value 取引先コード文字列
    * @return CustomerCodeまたはエラー
    */
  def parseFromString(value: String): Either[CustomerCodeError, CustomerCode] = {
    if (value.isEmpty) {
      Left(CustomerCodeError.Empty)
    } else if (value.length > MaxLength) {
      Left(CustomerCodeError.TooLong)
    } else if (!value.matches("^[a-zA-Z0-9_-]+$")) {
      Left(CustomerCodeError.InvalidFormat)
    } else {
      Right(CustomerCodeImpl(value))
    }
  }

  private case class CustomerCodeImpl(value: String) extends CustomerCode
}
