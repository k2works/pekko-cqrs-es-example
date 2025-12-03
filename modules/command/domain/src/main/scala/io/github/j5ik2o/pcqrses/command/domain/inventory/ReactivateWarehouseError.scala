package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 倉庫再有効化エラー */
enum ReactivateWarehouseError(val message: String) {
  /** 既に有効 */
  case AlreadyActive extends ReactivateWarehouseError("Warehouse is already active")
}
