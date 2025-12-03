package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 在庫調整エラー */
enum AdjustInventoryError(val message: String) {
  /** バージョン不一致（競合発生） */
  case VersionMismatch extends AdjustInventoryError("Inventory version mismatch - concurrent modification detected")
  /** 調整後数量が負 */
  case ResultingNegativeQuantity extends AdjustInventoryError("Adjustment would result in negative inventory")
}
