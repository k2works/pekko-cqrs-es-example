package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 在庫バージョンエラー */
enum InventoryVersionError(val message: String) {
  /** 正の数でない */
  case NotPositive extends InventoryVersionError("Inventory version must be a positive number")
}
