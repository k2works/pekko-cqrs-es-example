package io.github.j5ik2o.pcqrses.command.domain.inventory

/** CustomerIdのエラー */
enum CustomerIdError(val message: String) {
  /** 空文字列 */
  case Empty extends CustomerIdError("CustomerId cannot be empty")
  /** 無効な長さ */
  case InvalidLength extends CustomerIdError("CustomerId must be 26 characters long")
  /** 無効なフォーマット */
  case InvalidFormat extends CustomerIdError("CustomerId must be a valid ULID format")
}
