package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 在庫引当解放エラー */
enum ReleaseInventoryError(val message: String) {
  /** 引当数量不足 */
  case InsufficientReserved
      extends ReleaseInventoryError("Insufficient reserved inventory for release")
  /** バージョン不一致（競合発生） */
  case VersionMismatch extends ReleaseInventoryError("Inventory version mismatch - concurrent modification detected")
}
