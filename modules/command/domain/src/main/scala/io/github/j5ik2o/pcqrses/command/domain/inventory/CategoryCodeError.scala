package io.github.j5ik2o.pcqrses.command.domain.inventory

/** CategoryCodeのエラー */
enum CategoryCodeError(val message: String) {
  /** 空文字列 */
  case Empty extends CategoryCodeError("CategoryCode cannot be empty")
  /** 長すぎる */
  case TooLong extends CategoryCodeError(s"CategoryCode cannot exceed ${CategoryCode.MaxLength} characters")
  /** 無効なフォーマット */
  case InvalidFormat
      extends CategoryCodeError("CategoryCode must contain only alphanumeric characters, hyphens, and underscores")
}
