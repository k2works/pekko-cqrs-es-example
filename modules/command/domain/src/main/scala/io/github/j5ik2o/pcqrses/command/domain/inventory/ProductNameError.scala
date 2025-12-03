package io.github.j5ik2o.pcqrses.command.domain.inventory

/** ProductNameのエラー */
enum ProductNameError(val message: String) {
  /** 空文字列 */
  case Empty extends ProductNameError("ProductName cannot be empty")
  /** 長すぎる */
  case TooLong extends ProductNameError(s"ProductName cannot exceed ${ProductName.MaxLength} characters")
}
