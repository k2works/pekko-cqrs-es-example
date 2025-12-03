package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 倉庫更新エラー */
enum UpdateWarehouseError(val message: String) {
  /** 変更なし */
  case NoChanges extends UpdateWarehouseError("No changes detected in warehouse information")
  /** 既に無効化済み */
  case AlreadyDeactivated extends UpdateWarehouseError("Warehouse has already been deactivated")
}
