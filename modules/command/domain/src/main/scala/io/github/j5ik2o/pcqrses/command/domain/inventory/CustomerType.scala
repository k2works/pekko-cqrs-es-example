package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 取引先タイプ
  *
  * 取引先の規模・種別を表す値オブジェクト。
  * D社では取引先を以下の3つのタイプに分類する：
  * - 大口取引先（Large）: 約30社
  * - 中口取引先（Medium）: 約150社
  * - 小口取引先（Small）: 約250社
  */
enum CustomerType {
  /** 大口取引先（約30社） */
  case Large
  /** 中口取引先（約150社） */
  case Medium
  /** 小口取引先（約250社） */
  case Small

  /** 取引先タイプの日本語名を取得
    *
    * @return 取引先タイプの日本語名
    */
  def displayName: String = this match {
    case Large  => "大口"
    case Medium => "中口"
    case Small  => "小口"
  }

  /** 取引先タイプコードを取得
    *
    * @return 取引先タイプコード
    */
  def code: String = this match {
    case Large  => "L"
    case Medium => "M"
    case Small  => "S"
  }
}

object CustomerType {

  /** 取引先タイプコードから取引先タイプを取得
    *
    * @param code 取引先タイプコード（"L", "M", "S"）
    * @return 取引先タイプ
    */
  def fromCode(code: String): Either[String, CustomerType] = code match {
    case "L" => Right(Large)
    case "M" => Right(Medium)
    case "S" => Right(Small)
    case _   => Left(s"Invalid customer type code: $code")
  }

  /** 日本語名から取引先タイプを取得
    *
    * @param displayName 取引先タイプの日本語名（"大口", "中口", "小口"）
    * @return 取引先タイプ
    */
  def fromDisplayName(displayName: String): Either[String, CustomerType] = displayName match {
    case "大口" => Right(Large)
    case "中口" => Right(Medium)
    case "小口" => Right(Small)
    case _     => Left(s"Invalid customer type display name: $displayName")
  }
}
