package io.github.j5ik2o.pcqrses.command.domain.inventory

/** WarehouseIdのエラー */
enum WarehouseIdError(val message: String) {
  /** 空文字列 */
  case Empty extends WarehouseIdError("WarehouseId cannot be empty")
  /** 無効な長さ */
  case InvalidLength extends WarehouseIdError("WarehouseId must be 26 characters long")
  /** 無効なフォーマット */
  case InvalidFormat extends WarehouseIdError("WarehouseId must be a valid ULID format")
}
