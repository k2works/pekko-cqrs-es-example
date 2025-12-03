package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 取引先更新エラー */
enum UpdateCustomerError(val message: String) {
  /** 変更なし */
  case NoChanges extends UpdateCustomerError("No changes detected in customer information")
  /** 既に無効化済み */
  case AlreadyDeactivated extends UpdateCustomerError("Customer has already been deactivated")
}
