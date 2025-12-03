package io.github.j5ik2o.pcqrses.command.domain.inventory

/** ZoneNameのエラー */
enum ZoneNameError(val message: String) {
  /** 空文字列 */
  case Empty extends ZoneNameError("ZoneName cannot be empty")
  /** 長すぎる */
  case TooLong extends ZoneNameError(s"ZoneName cannot exceed ${ZoneName.MaxLength} characters")
}
