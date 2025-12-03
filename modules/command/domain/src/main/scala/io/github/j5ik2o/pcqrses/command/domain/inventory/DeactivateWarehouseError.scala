package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 倉庫無効化エラー */
enum DeactivateWarehouseError(val message: String) {
  /** 既に無効化済み */
  case AlreadyDeactivated extends DeactivateWarehouseError("Warehouse has already been deactivated")
}
