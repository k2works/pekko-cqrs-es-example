package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 区画コード
  *
  * 倉庫区画を識別するための業務コード。
  * D社では各倉庫に3つの区画（常温、冷蔵、冷凍）を設ける。
  *
  * 例：
  * - TOKYO-RT: 東京倉庫常温区画
  * - TOKYO-RF: 東京倉庫冷蔵区画
  * - TOKYO-FZ: 東京倉庫冷凍区画
  * - OSAKA-RT: 大阪倉庫常温区画
  *
  * 制約：
  * - 1文字以上50文字以下
  * - 英数字とハイフン、アンダースコアのみ許可
  */
trait ZoneCode {
  def value: String
  def asString: String = value
}

object ZoneCode {
  /** 区画コードの最大長 */
  final val MaxLength: Int = 50

  /** 区画コードを生成
    *
    * @param value 区画コード文字列
    * @return ZoneCode
    * @throws IllegalArgumentException 無効な区画コードの場合
    */
  def apply(value: String): ZoneCode = parseFromString(value) match {
    case Right(v) => v
    case Left(e)  => throw new IllegalArgumentException(e.message)
  }

  def unapply(self: ZoneCode): Option[String] = Some(self.value)

  /** 文字列から区画コードを生成
    *
    * @param value 区画コード文字列
    * @return ZoneCode
    * @throws IllegalArgumentException 無効な区画コードの場合
    */
  def from(value: String): ZoneCode = apply(value)

  /** 文字列から区画コードをパース
    *
    * @param value 区画コード文字列
    * @return ZoneCodeまたはエラー
    */
  def parseFromString(value: String): Either[ZoneCodeError, ZoneCode] = {
    if (value.isEmpty) {
      Left(ZoneCodeError.Empty)
    } else if (value.length > MaxLength) {
      Left(ZoneCodeError.TooLong)
    } else if (!value.matches("^[a-zA-Z0-9_-]+$")) {
      Left(ZoneCodeError.InvalidFormat)
    } else {
      Right(ZoneCodeImpl(value))
    }
  }

  private case class ZoneCodeImpl(value: String) extends ZoneCode
}
