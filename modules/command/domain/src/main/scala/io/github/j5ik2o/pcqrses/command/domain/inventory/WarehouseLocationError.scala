package io.github.j5ik2o.pcqrses.command.domain.inventory

/** WarehouseLocationのエラー */
enum WarehouseLocationError(val message: String) {
  /** 空文字列 */
  case Empty extends WarehouseLocationError("WarehouseLocation cannot be empty")
  /** 長すぎる */
  case TooLong extends WarehouseLocationError(s"WarehouseLocation cannot exceed ${WarehouseLocation.MaxLength} characters")
}
