package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 区画更新エラー */
enum UpdateZoneError(val message: String) {
  /** 変更なし */
  case NoChanges extends UpdateZoneError("No changes detected in zone information")
  /** 既に無効化済み */
  case AlreadyDeactivated extends UpdateZoneError("Zone has already been deactivated")
}
