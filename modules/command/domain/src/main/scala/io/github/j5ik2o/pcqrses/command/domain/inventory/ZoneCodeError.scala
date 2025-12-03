package io.github.j5ik2o.pcqrses.command.domain.inventory

/** ZoneCodeのエラー */
enum ZoneCodeError(val message: String) {
  /** 空文字列 */
  case Empty extends ZoneCodeError("ZoneCode cannot be empty")
  /** 長すぎる */
  case TooLong extends ZoneCodeError(s"ZoneCode cannot exceed ${ZoneCode.MaxLength} characters")
  /** 無効なフォーマット */
  case InvalidFormat
      extends ZoneCodeError("ZoneCode must contain only alphanumeric characters, hyphens, and underscores")
}
