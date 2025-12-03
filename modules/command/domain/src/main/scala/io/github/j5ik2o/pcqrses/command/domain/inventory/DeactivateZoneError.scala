package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 区画無効化エラー */
enum DeactivateZoneError(val message: String) {
  /** 既に無効化済み */
  case AlreadyDeactivated extends DeactivateZoneError("Zone has already been deactivated")
}
