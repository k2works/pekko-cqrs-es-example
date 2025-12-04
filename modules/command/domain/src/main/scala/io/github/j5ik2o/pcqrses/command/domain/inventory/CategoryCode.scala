package io.github.j5ik2o.pcqrses.command.domain.inventory

/** 商品カテゴリコード
  *
  * 商品のカテゴリを識別するコード。
  * D社では食品類と日用品を扱うため、カテゴリコードでこれらを分類する。
  *
  * 例：
  * - FOOD: 食品類（60%）
  * - DAILY: 日用品（40%）
  * - FOOD-PROCESSED: 加工食品
  * - FOOD-BEVERAGE: 飲料
  * - FOOD-FROZEN: 冷凍食品
  * - DAILY-DETERGENT: 洗剤
  * - DAILY-TOILETRY: トイレタリー
  *
  * 制約：
  * - 1文字以上50文字以下
  * - 英数字とハイフン、アンダースコアのみ許可
  */
trait CategoryCode {
  def value: String
  def asString: String = value
}

object CategoryCode {
  /** カテゴリコードの最大長 */
  final val MaxLength: Int = 50

  /** カテゴリコードを生成
    *
    * @param value カテゴリコード文字列
    * @return CategoryCode
    * @throws IllegalArgumentException 無効なカテゴリコードの場合
    */
  def apply(value: String): CategoryCode = parseFromString(value) match {
    case Right(v) => v
    case Left(e)  => throw new IllegalArgumentException(e.message)
  }

  def unapply(self: CategoryCode): Option[String] = Some(self.value)

  def from(value: String): CategoryCode = apply(value)

  /** 文字列からカテゴリコードをパース
    *
    * @param value カテゴリコード文字列
    * @return CategoryCodeまたはエラー
    */
  def parseFromString(value: String): Either[CategoryCodeError, CategoryCode] = {
    if (value.isEmpty) {
      Left(CategoryCodeError.Empty)
    } else if (value.length > MaxLength) {
      Left(CategoryCodeError.TooLong)
    } else if (!value.matches("^[a-zA-Z0-9_-]+$")) {
      Left(CategoryCodeError.InvalidFormat)
    } else {
      Right(CategoryCodeImpl(value))
    }
  }

  private case class CategoryCodeImpl(value: String) extends CategoryCode
}
