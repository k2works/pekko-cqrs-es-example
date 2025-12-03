package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 在庫引当エラー */
enum ReserveInventoryError(val message: String) {
  /** 在庫不足 */
  case InsufficientStock
      extends ReserveInventoryError("Insufficient inventory stock for reservation")
  /** バージョン不一致（競合発生） */
  case VersionMismatch extends ReserveInventoryError("Inventory version mismatch - concurrent modification detected")
}
