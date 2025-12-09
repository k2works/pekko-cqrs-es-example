package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 商品廃止エラー */
enum ObsoleteProductError(val message: String) {

  /** 商品が見つからない */
  case NotFound extends ObsoleteProductError("Product not found")

  /** 既に廃止済み */
  case AlreadyObsoleted extends ObsoleteProductError("Product has already been obsoleted")
}
