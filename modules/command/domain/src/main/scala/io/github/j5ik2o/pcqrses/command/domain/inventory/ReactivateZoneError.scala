package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 区画再有効化エラー */
enum ReactivateZoneError(val message: String) {
  /** 既に有効 */
  case AlreadyActive extends ReactivateZoneError("Zone is already active")
}
