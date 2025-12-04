package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 区画名
  *
  * 倉庫区画の名称を表す値オブジェクト。
  *
  * 制約：
  * - 1文字以上200文字以下
  * - 空白のみの文字列は不可
  */
trait ZoneName {
  def value: String
  def asString: String = value
}

object ZoneName {
  /** 区画名の最大長 */
  final val MaxLength: Int = 200

  /** 区画名を生成
    *
    * @param value 区画名文字列
    * @return ZoneName
    * @throws IllegalArgumentException 無効な区画名の場合
    */
  def apply(value: String): ZoneName = parseFromString(value) match {
    case Right(v) => v
    case Left(e)  => throw new IllegalArgumentException(e.message)
  }

  def unapply(self: ZoneName): Option[String] = Some(self.value)

  /** 文字列から区画名を生成
    *
    * @param value 区画名文字列
    * @return ZoneName
    * @throws IllegalArgumentException 無効な区画名の場合
    */
  def from(value: String): ZoneName = apply(value)

  /** 文字列から区画名をパース
    *
    * @param value 区画名文字列
    * @return ZoneNameまたはエラー
    */
  def parseFromString(value: String): Either[ZoneNameError, ZoneName] = {
    val trimmed = value.trim
    if (trimmed.isEmpty) {
      Left(ZoneNameError.Empty)
    } else if (trimmed.length > MaxLength) {
      Left(ZoneNameError.TooLong)
    } else {
      Right(ZoneNameImpl(trimmed))
    }
  }

  private case class ZoneNameImpl(value: String) extends ZoneName
}
