package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 保管条件
  *
  * 商品の保管条件を表す値オブジェクト。
  * D社では食品・日用品を扱うため、以下の3つの保管条件を管理する：
  * - 常温（Room Temperature）: 日用品、常温保存可能な食品
  * - 冷蔵（Refrigerated）: 要冷蔵食品（5℃前後）
  * - 冷凍（Frozen）: 要冷凍食品（-18℃以下）
  */
enum StorageCondition {
  /** 常温保存 */
  case RoomTemperature
  /** 要冷蔵（5℃前後） */
  case Refrigerated
  /** 要冷凍（-18℃以下） */
  case Frozen

  /** 保管条件の日本語名を取得
    *
    * @return 保管条件の日本語名
    */
  def displayName: String = this match {
    case RoomTemperature => "常温"
    case Refrigerated    => "冷蔵"
    case Frozen          => "冷凍"
  }

  /** 保管条件コードを取得
    *
    * @return 保管条件コード
    */
  def code: String = this match {
    case RoomTemperature => "RT"
    case Refrigerated    => "RF"
    case Frozen          => "FZ"
  }
}

object StorageCondition {

  /** 保管条件コードから保管条件を取得
    *
    * @param code 保管条件コード（"RT", "RF", "FZ"）
    * @return 保管条件
    */
  def fromCode(code: String): Either[String, StorageCondition] = code match {
    case "RT" => Right(RoomTemperature)
    case "RF" => Right(Refrigerated)
    case "FZ" => Right(Frozen)
    case _    => Left(s"Invalid storage condition code: $code")
  }

  /** 日本語名から保管条件を取得
    *
    * @param displayName 保管条件の日本語名（"常温", "冷蔵", "冷凍"）
    * @return 保管条件
    */
  def fromDisplayName(displayName: String): Either[String, StorageCondition] = displayName match {
    case "常温" => Right(RoomTemperature)
    case "冷蔵" => Right(Refrigerated)
    case "冷凍" => Right(Frozen)
    case _    => Left(s"Invalid storage condition display name: $displayName")
  }
}
