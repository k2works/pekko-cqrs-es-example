package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 取引先再有効化エラー */
enum ReactivateCustomerError(val message: String) {
  /** 既に有効 */
  case AlreadyActive extends ReactivateCustomerError("Customer is already active")
}
