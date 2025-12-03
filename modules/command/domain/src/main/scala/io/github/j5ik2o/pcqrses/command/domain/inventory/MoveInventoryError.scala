package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 在庫移動エラー */
enum MoveInventoryError(val message: String) {
  /** 在庫不足 */
  case InsufficientStock extends MoveInventoryError("Insufficient inventory stock for move")
  /** バージョン不一致（競合発生） */
  case VersionMismatch extends MoveInventoryError("Inventory version mismatch - concurrent modification detected")
  /** 保管条件不一致 */
  case StorageConditionMismatch
      extends MoveInventoryError("Storage condition mismatch between product and target warehouse zone")
}
