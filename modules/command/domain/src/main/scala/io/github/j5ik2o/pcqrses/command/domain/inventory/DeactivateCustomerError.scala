package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 取引先無効化エラー */
enum DeactivateCustomerError(val message: String) {
  /** 既に無効化済み */
  case AlreadyDeactivated extends DeactivateCustomerError("Customer has already been deactivated")
}
