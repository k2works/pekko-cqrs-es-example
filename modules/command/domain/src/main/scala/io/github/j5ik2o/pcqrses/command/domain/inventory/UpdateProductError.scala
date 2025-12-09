package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 商品更新エラー */
enum UpdateProductError(val message: String) {

  /** 商品が見つからない */
  case NotFound extends UpdateProductError("Product not found")

  /** 変更なし */
  case NoChanges extends UpdateProductError("No changes detected in product information")

  /** 既に廃止済み */
  case AlreadyObsoleted extends UpdateProductError("Product has already been obsoleted")
}
