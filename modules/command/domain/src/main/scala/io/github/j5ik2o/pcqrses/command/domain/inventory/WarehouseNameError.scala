package io.github.j5ik2o.pcqrses.command.domain.inventory

/** WarehouseNameのエラー */
enum WarehouseNameError(val message: String) {
  /** 空文字列 */
  case Empty extends WarehouseNameError("WarehouseName cannot be empty")
  /** 長すぎる */
  case TooLong extends WarehouseNameError(s"WarehouseName cannot exceed ${WarehouseName.MaxLength} characters")
}
