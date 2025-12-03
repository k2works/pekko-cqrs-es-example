package io.github.j5ik2o.pcqrses.command.domain.inventory

/** CustomerNameのエラー */
enum CustomerNameError(val message: String) {
  /** 空文字列 */
  case Empty extends CustomerNameError("CustomerName cannot be empty")
  /** 長すぎる */
  case TooLong extends CustomerNameError(s"CustomerName cannot exceed ${CustomerName.MaxLength} characters")
}
