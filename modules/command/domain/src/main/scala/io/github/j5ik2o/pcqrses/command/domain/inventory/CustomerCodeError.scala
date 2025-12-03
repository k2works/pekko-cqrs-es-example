package io.github.j5ik2o.pcqrses.command.domain.inventory

/** CustomerCodeのエラー */
enum CustomerCodeError(val message: String) {
  /** 空文字列 */
  case Empty extends CustomerCodeError("CustomerCode cannot be empty")
  /** 長すぎる */
  case TooLong extends CustomerCodeError(s"CustomerCode cannot exceed ${CustomerCode.MaxLength} characters")
  /** 無効なフォーマット */
  case InvalidFormat
      extends CustomerCodeError("CustomerCode must contain only alphanumeric characters, hyphens, and underscores")
}
