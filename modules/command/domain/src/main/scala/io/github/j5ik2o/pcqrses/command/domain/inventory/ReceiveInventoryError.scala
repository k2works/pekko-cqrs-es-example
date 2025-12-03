package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 在庫入庫エラー */
enum ReceiveInventoryError(val message: String) {
  /** バージョン不一致（競合発生） */
  case VersionMismatch extends ReceiveInventoryError("Inventory version mismatch - concurrent modification detected")
}
