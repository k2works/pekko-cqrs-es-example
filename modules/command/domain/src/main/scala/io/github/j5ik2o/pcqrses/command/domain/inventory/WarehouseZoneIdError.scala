package io.github.j5ik2o.pcqrses.command.domain.inventory

/** WarehouseZoneIdのエラー */
enum WarehouseZoneIdError(val message: String) {
  /** 空文字列 */
  case Empty extends WarehouseZoneIdError("WarehouseZoneId cannot be empty")
  /** 無効な長さ */
  case InvalidLength extends WarehouseZoneIdError("WarehouseZoneId must be 26 characters long")
  /** 無効なフォーマット */
  case InvalidFormat extends WarehouseZoneIdError("WarehouseZoneId must be a valid ULID format")
}
