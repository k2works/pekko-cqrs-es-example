package io.github.j5ik2o.pcqrses.command.domain.inventory

/** ProductIdのエラー */
enum ProductIdError(val message: String) {
  /** 空文字列 */
  case Empty extends ProductIdError("ProductId cannot be empty")
  /** 無効な長さ */
  case InvalidLength extends ProductIdError("ProductId must be 26 characters long")
  /** 無効なフォーマット */
  case InvalidFormat extends ProductIdError("ProductId must be a valid ULID format")
}
