package io.github.j5ik2o.pcqrses.command.domain.inventory

/** ZoneCapacityのエラー */
enum ZoneCapacityError(val message: String) {
  /** 容量が0以下 */
  case NotPositive extends ZoneCapacityError("ZoneCapacity must be greater than 0")
}
