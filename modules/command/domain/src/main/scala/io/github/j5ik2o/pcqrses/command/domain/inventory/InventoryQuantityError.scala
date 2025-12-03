package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 在庫数量エラー */
enum InventoryQuantityError(val message: String) {
  /** 負の数量 */
  case Negative extends InventoryQuantityError("Inventory quantity cannot be negative")
}
