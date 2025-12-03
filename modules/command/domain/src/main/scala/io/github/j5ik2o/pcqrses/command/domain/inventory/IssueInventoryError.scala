package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 在庫出庫エラー */
enum IssueInventoryError(val message: String) {
  /** 引当数量不足 */
  case InsufficientReserved extends IssueInventoryError("Insufficient reserved inventory for issue")
  /** バージョン不一致（競合発生） */
  case VersionMismatch extends IssueInventoryError("Inventory version mismatch - concurrent modification detected")
}
