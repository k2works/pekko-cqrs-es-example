package io.github.j5ik2o.pcqrses.command.domain.inventory

/** WarehouseCodeのエラー */
enum WarehouseCodeError(val message: String) {
  /** 空文字列 */
  case Empty extends WarehouseCodeError("WarehouseCode cannot be empty")
  /** 長すぎる */
  case TooLong extends WarehouseCodeError(s"WarehouseCode cannot exceed ${WarehouseCode.MaxLength} characters")
  /** 無効なフォーマット */
  case InvalidFormat
      extends WarehouseCodeError("WarehouseCode must contain only alphanumeric characters, hyphens, and underscores")
}
