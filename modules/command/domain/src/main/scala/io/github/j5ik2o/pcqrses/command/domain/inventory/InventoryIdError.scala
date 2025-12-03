package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 在庫IDエラー */
enum InventoryIdError(val message: String) {
  /** 不正なフォーマット */
  case InvalidFormat extends InventoryIdError("Invalid ULID format for inventory ID")
}
