package io.github.j5ik2o.pcqrses.command.domain.inventory

/** ProductCodeのエラー */
enum ProductCodeError(val message: String) {
  /** 空文字列 */
  case Empty extends ProductCodeError("ProductCode cannot be empty")
  /** 長すぎる */
  case TooLong extends ProductCodeError(s"ProductCode cannot exceed ${ProductCode.MaxLength} characters")
  /** 無効なフォーマット */
  case InvalidFormat
      extends ProductCodeError("ProductCode must contain only alphanumeric characters, hyphens, and underscores")
}
