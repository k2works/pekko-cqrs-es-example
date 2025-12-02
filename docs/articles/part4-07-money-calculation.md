# 【第4部 第7章】金額計算と税金処理：正確な数値演算の実装

## 本章の目的

受注管理システムにおいて、金額計算と税金処理は極めて重要です。わずかな計算誤差が会計上の不整合を引き起こし、顧客との信頼関係を損なう可能性があります。本章では、BigDecimalを使用した正確な10進数演算、Money値オブジェクトの実装、税金計算、割引計算について詳しく説明します。

## 7.1 BigDecimalによる正確な金額計算

### 7.1.1 浮動小数点の問題

プログラミング言語の多くは、浮動小数点数（Float、Double）を使用して小数を表現します。しかし、浮動小数点数は2進数で表現されるため、10進数の小数を正確に表現できない場合があります。

```scala
// 問題のある例: Doubleを使用
val price: Double = 0.1
val quantity: Double = 0.2
val total: Double = price + quantity

println(total)  // 0.30000000000000004 （期待値: 0.3）

// 実際の金額計算での問題
val unitPrice: Double = 299.99  // 商品単価
val qty: Double = 3
val subtotal: Double = unitPrice * qty
println(subtotal)  // 899.9700000000001 （期待値: 899.97）
```

**なぜこの問題が発生するのか**:
- 浮動小数点数は2進数で表現される（例: 0.1 = 0.0001100110011...（無限小数））
- 有限のビット数で無限小数を表現しようとするため、丸め誤差が発生
- 演算を重ねると誤差が蓄積される

### 7.1.2 BigDecimalによる解決

Scalaの`BigDecimal`は、10進数の任意精度演算をサポートします。内部的には整数と指数で表現するため、10進数の小数を正確に表現できます。

```scala
// 正しい例: BigDecimalを使用
val price: BigDecimal = BigDecimal("0.1")
val quantity: BigDecimal = BigDecimal("0.2")
val total: BigDecimal = price + quantity

println(total)  // 0.3 （正確）

// 実際の金額計算
val unitPrice: BigDecimal = BigDecimal("299.99")
val qty: BigDecimal = BigDecimal(3)
val subtotal: BigDecimal = unitPrice * qty
println(subtotal)  // 899.97 （正確）
```

**BigDecimal使用時の注意点**:

1. **文字列コンストラクタを使用**
   ```scala
   // 推奨
   val amount = BigDecimal("123.45")

   // 非推奨（Doubleからの変換で誤差が入る）
   val amount = BigDecimal(123.45)  // 内部的にDouble経由で変換される
   ```

2. **精度（scale）の管理**
   ```scala
   val price = BigDecimal("100.123")
   println(price.scale)  // 3（小数点以下3桁）

   // 小数点以下2桁に丸める
   val rounded = price.setScale(2, BigDecimal.RoundingMode.HALF_UP)
   println(rounded)  // 100.12
   ```

3. **丸めモードの選択**
   ```scala
   val value = BigDecimal("10.5")

   // 四捨五入（HALF_UP）
   println(value.setScale(0, BigDecimal.RoundingMode.HALF_UP))  // 11

   // 切り捨て（DOWN）
   println(value.setScale(0, BigDecimal.RoundingMode.DOWN))     // 10

   // 切り上げ（UP）
   println(value.setScale(0, BigDecimal.RoundingMode.UP))       // 11

   // 偶数丸め（HALF_EVEN、銀行家の丸め）
   println(BigDecimal("10.5").setScale(0, BigDecimal.RoundingMode.HALF_EVEN))  // 10
   println(BigDecimal("11.5").setScale(0, BigDecimal.RoundingMode.HALF_EVEN))  // 12
   ```

### 7.1.3 Money値オブジェクトの実装

第4章で定義したMoney値オブジェクトを拡張し、より堅牢な実装にします。

```scala
package com.example.order.domain

import java.util.Currency as JavaCurrency
import scala.math.BigDecimal.RoundingMode

// 通貨
sealed trait Currency {
  def code: String
  def symbol: String
  def defaultScale: Int
}

object Currency {
  case object JPY extends Currency {
    val code = "JPY"
    val symbol = "¥"
    val defaultScale = 0  // 円は小数点なし
  }

  case object USD extends Currency {
    val code = "USD"
    val symbol = "$"
    val defaultScale = 2  // ドルは小数点以下2桁
  }

  case object EUR extends Currency {
    val code = "EUR"
    val symbol = "€"
    val defaultScale = 2  // ユーロは小数点以下2桁
  }

  def fromCode(code: String): Option[Currency] = code match {
    case "JPY" => Some(JPY)
    case "USD" => Some(USD)
    case "EUR" => Some(EUR)
    case _ => None
  }
}

// Money値オブジェクト
final case class Money(amount: BigDecimal, currency: Currency = Currency.JPY) {

  // バリデーション
  require(
    amount.scale <= 2,
    s"金額の小数点以下は2桁までです: ${amount.scale}"
  )

  // ゼロ値
  def isZero: Boolean = amount == 0

  // 正の値か
  def isPositive: Boolean = amount > 0

  // 負の値か
  def isNegative: Boolean = amount < 0

  // 加算
  def +(other: Money): Money = {
    requireSameCurrency(other)
    Money(amount + other.amount, currency)
  }

  // 減算
  def -(other: Money): Money = {
    requireSameCurrency(other)
    Money(amount - other.amount, currency)
  }

  // 乗算（数量による）
  def *(multiplier: BigDecimal): Money = {
    Money(amount * multiplier, currency)
  }

  def *(multiplier: Int): Money = {
    Money(amount * multiplier, currency)
  }

  // 除算
  def /(divisor: BigDecimal): Money = {
    require(divisor != 0, "0で除算できません")
    Money(amount / divisor, currency)
  }

  def /(divisor: Int): Money = {
    require(divisor != 0, "0で除算できません")
    Money(amount / divisor, currency)
  }

  // 丸め
  def round(scale: Int = 0, mode: RoundingMode = RoundingMode.HALF_UP): Money = {
    Money(amount.setScale(scale, mode), currency)
  }

  // 通貨のデフォルトスケールで丸め
  def roundToDefaultScale(mode: RoundingMode = RoundingMode.HALF_UP): Money = {
    round(currency.defaultScale, mode)
  }

  // 比較演算
  def >(other: Money): Boolean = {
    requireSameCurrency(other)
    amount > other.amount
  }

  def <(other: Money): Boolean = {
    requireSameCurrency(other)
    amount < other.amount
  }

  def >=(other: Money): Boolean = {
    requireSameCurrency(other)
    amount >= other.amount
  }

  def <=(other: Money): Boolean = {
    requireSameCurrency(other)
    amount <= other.amount
  }

  // 絶対値
  def abs: Money = Money(amount.abs, currency)

  // 符号反転
  def unary_- : Money = Money(-amount, currency)

  // 文字列表現
  override def toString: String = s"${currency.symbol}${amount.toString}"

  // フォーマット（カンマ区切り）
  def formatted: String = {
    val formatter = java.text.NumberFormat.getCurrencyInstance(java.util.Locale.JAPAN)
    formatter.setCurrency(JavaCurrency.getInstance(currency.code))
    formatter.format(amount.toDouble)
  }

  private def requireSameCurrency(other: Money): Unit = {
    require(
      currency == other.currency,
      s"通貨が一致しません: ${currency.code} != ${other.currency.code}"
    )
  }
}

object Money {
  // ゼロ値
  def zero(currency: Currency = Currency.JPY): Money = Money(BigDecimal(0), currency)

  // ファクトリメソッド
  def jpy(amount: BigDecimal): Money = Money(amount, Currency.JPY)
  def usd(amount: BigDecimal): Money = Money(amount, Currency.USD)
  def eur(amount: BigDecimal): Money = Money(amount, Currency.EUR)

  // 整数からの生成
  def jpy(amount: Int): Money = Money(BigDecimal(amount), Currency.JPY)
  def usd(amount: Int): Money = Money(BigDecimal(amount), Currency.USD)
  def eur(amount: Int): Money = Money(BigDecimal(amount), Currency.EUR)

  // 文字列からのパース
  def parse(str: String, currency: Currency = Currency.JPY): Option[Money] = {
    try {
      Some(Money(BigDecimal(str), currency))
    } catch {
      case _: NumberFormatException => None
    }
  }

  // 合計
  def sum(amounts: List[Money]): Money = {
    amounts match {
      case Nil => zero()
      case head :: tail =>
        tail.foldLeft(head)(_ + _)
    }
  }
}
```

### 7.1.4 Money値オブジェクトの使用例

```scala
// 基本的な演算
val price1 = Money.jpy(1000)
val price2 = Money.jpy(2000)
val total = price1 + price2
println(total)  // ¥3000

// 数量による乗算
val unitPrice = Money.jpy(299.99)
val quantity = 3
val subtotal = unitPrice * quantity
println(subtotal.roundToDefaultScale())  // ¥900（四捨五入）

// 割引計算
val originalPrice = Money.jpy(10000)
val discountRate = BigDecimal("0.15")  // 15% OFF
val discountAmount = (originalPrice * discountRate).roundToDefaultScale()
val discountedPrice = originalPrice - discountAmount
println(discountedPrice)  // ¥8500

// リストの合計
val items = List(
  Money.jpy(1000),
  Money.jpy(2000),
  Money.jpy(3000)
)
val sum = Money.sum(items)
println(sum)  // ¥6000

// 異なる通貨の演算（エラー）
try {
  val jpy = Money.jpy(1000)
  val usd = Money.usd(10)
  val invalid = jpy + usd  // 例外が発生
} catch {
  case e: IllegalArgumentException =>
    println(e.getMessage)  // "通貨が一致しません: JPY != USD"
}
```

## 7.2 税金計算

### 7.2.1 税率の管理

日本の消費税は複数税率制度を採用しており、標準税率（10%）と軽減税率（8%）があります。

```scala
package com.example.order.domain

// 税区分
sealed trait TaxCategory {
  def description: String
}

object TaxCategory {
  case object Standard extends TaxCategory {
    val description = "標準税率"
  }

  case object Reduced extends TaxCategory {
    val description = "軽減税率"
  }

  case object TaxFree extends TaxCategory {
    val description = "非課税"
  }

  def fromString(str: String): Option[TaxCategory] = str match {
    case "Standard" => Some(Standard)
    case "Reduced" => Some(Reduced)
    case "TaxFree" => Some(TaxFree)
    case _ => None
  }
}

// 税率
final case class TaxRate(value: BigDecimal, category: TaxCategory) {
  require(
    value >= 0 && value <= 1,
    s"税率は0から1の間でなければなりません: $value"
  )

  def percentage: BigDecimal = value * 100

  override def toString: String = s"${percentage}% (${category.description})"
}

object TaxRate {
  // 日本の消費税率
  val Standard: TaxRate = TaxRate(BigDecimal("0.10"), TaxCategory.Standard)  // 10%
  val Reduced: TaxRate = TaxRate(BigDecimal("0.08"), TaxCategory.Reduced)    // 8%
  val TaxFree: TaxRate = TaxRate(BigDecimal("0.00"), TaxCategory.TaxFree)    // 0%

  // デフォルト税率
  def default: TaxRate = Standard

  // 税区分から税率を取得
  def fromCategory(category: TaxCategory): TaxRate = category match {
    case TaxCategory.Standard => Standard
    case TaxCategory.Reduced => Reduced
    case TaxCategory.TaxFree => TaxFree
  }
}
```

### 7.2.2 税込金額の計算

税金計算には、外税方式と内税方式の2つがあります。

**外税方式（Tax-Exclusive）**:
- 表示価格は税抜価格
- 税込金額 = 税抜金額 + (税抜金額 × 税率)

**内税方式（Tax-Inclusive）**:
- 表示価格は税込価格
- 税抜金額 = 税込金額 / (1 + 税率)

```scala
// 税金計算サービス
object TaxCalculator {

  // 外税: 税込金額を計算
  def calculateTaxIncludedAmount(
    taxExclusiveAmount: Money,
    taxRate: TaxRate
  ): (Money, Money) = {
    // 税額 = 税抜金額 × 税率（端数は四捨五入）
    val taxAmount = (taxExclusiveAmount * taxRate.value).round(0, RoundingMode.HALF_UP)

    // 税込金額 = 税抜金額 + 税額
    val taxIncludedAmount = taxExclusiveAmount + taxAmount

    (taxIncludedAmount, taxAmount)
  }

  // 内税: 税抜金額を計算
  def calculateTaxExcludedAmount(
    taxInclusiveAmount: Money,
    taxRate: TaxRate
  ): (Money, Money) = {
    // 税抜金額 = 税込金額 / (1 + 税率)
    val divisor = BigDecimal(1) + taxRate.value
    val taxExclusiveAmount = (taxInclusiveAmount / divisor).round(0, RoundingMode.HALF_UP)

    // 税額 = 税込金額 - 税抜金額
    val taxAmount = taxInclusiveAmount - taxExclusiveAmount

    (taxExclusiveAmount, taxAmount)
  }

  // 税額のみを計算
  def calculateTaxAmount(
    taxExclusiveAmount: Money,
    taxRate: TaxRate
  ): Money = {
    (taxExclusiveAmount * taxRate.value).round(0, RoundingMode.HALF_UP)
  }
}
```

### 7.2.3 税金計算の具体例

```scala
// 外税方式の例
val taxExclusivePrice = Money.jpy(1000)  // 税抜価格
val taxRate = TaxRate.Standard  // 10%

val (taxIncludedPrice, taxAmount) = TaxCalculator.calculateTaxIncludedAmount(
  taxExclusivePrice,
  taxRate
)

println(s"税抜価格: $taxExclusivePrice")  // ¥1000
println(s"税額: $taxAmount")              // ¥100
println(s"税込価格: $taxIncludedPrice")  // ¥1100

// 内税方式の例
val taxInclusivePrice2 = Money.jpy(1100)  // 税込価格
val (taxExclusivePrice2, taxAmount2) = TaxCalculator.calculateTaxExcludedAmount(
  taxInclusivePrice2,
  TaxRate.Standard
)

println(s"税込価格: $taxInclusivePrice2")     // ¥1100
println(s"税額: $taxAmount2")                 // ¥100
println(s"税抜価格: $taxExclusivePrice2")     // ¥1000

// 軽減税率の例（食料品など）
val reducedTaxPrice = Money.jpy(1000)
val (reducedTaxIncluded, reducedTax) = TaxCalculator.calculateTaxIncludedAmount(
  reducedTaxPrice,
  TaxRate.Reduced  // 8%
)

println(s"税抜価格: $reducedTaxPrice")         // ¥1000
println(s"税額: $reducedTax")                  // ¥80
println(s"税込価格: $reducedTaxIncluded")      // ¥1080
```

### 7.2.4 注文明細での税金計算

OrderItemに税金計算ロジックを組み込みます。

```scala
package com.example.order.domain

final case class OrderItem(
  productId: ProductId,
  productName: String,
  quantity: Quantity,
  unitPrice: Money,  // 税抜単価
  discountRate: Option[DiscountRate],
  taxCategory: TaxCategory,
  taxRate: TaxRate
) {

  // 小計（税抜）= 単価 × 数量
  val subtotalTaxExclusive: Money = unitPrice * quantity.value

  // 割引額 = 小計 × 割引率
  val discountAmount: Money = discountRate match {
    case Some(rate) => (subtotalTaxExclusive * rate.value).roundToDefaultScale()
    case None => Money.zero(unitPrice.currency)
  }

  // 割引後金額（課税対象額）
  val taxableAmount: Money = subtotalTaxExclusive - discountAmount

  // 税額 = 課税対象額 × 税率（端数四捨五入）
  val taxAmount: Money = TaxCalculator.calculateTaxAmount(taxableAmount, taxRate)

  // 合計（税込）= 課税対象額 + 税額
  val totalAmount: Money = taxableAmount + taxAmount

  // 検証: 金額がマイナスでないこと
  require(subtotalTaxExclusive.isPositive || subtotalTaxExclusive.isZero, "小計はゼロ以上でなければなりません")
  require(totalAmount.isPositive || totalAmount.isZero, "合計金額はゼロ以上でなければなりません")
}
```

### 7.2.5 注文全体の税金計算

```scala
final case class Order(
  id: OrderId,
  customerId: CustomerId,
  items: List[OrderItem],
  // ... 他のフィールド ...
) {

  // 小計（税抜）: 全明細の課税対象額の合計
  def subtotalAmount: Money = {
    Money.sum(items.map(_.taxableAmount))
  }

  // 割引額の合計
  def discountAmount: Money = {
    Money.sum(items.map(_.discountAmount))
  }

  // 税額の合計
  def taxAmount: Money = {
    Money.sum(items.map(_.taxAmount))
  }

  // 合計金額（税込）
  def totalAmount: Money = {
    Money.sum(items.map(_.totalAmount))
  }

  // 税区分別の集計
  def taxAmountByCategory: Map[TaxCategory, Money] = {
    items
      .groupBy(_.taxCategory)
      .view
      .mapValues(items => Money.sum(items.map(_.taxAmount)))
      .toMap
  }

  // バランスチェック: 税金計算が正しいか検証
  def isBalanced: Boolean = {
    val calculatedTotal = subtotalAmount + taxAmount
    totalAmount == calculatedTotal
  }
}
```

## 7.3 割引計算

### 7.3.1 割引タイプ

割引には複数のタイプがあります。

```scala
package com.example.order.domain

// 割引タイプ
sealed trait DiscountType

object DiscountType {
  // 率引き（パーセンテージ）
  final case class RateDiscount(rate: BigDecimal) extends DiscountType {
    require(rate >= 0 && rate <= 1, s"割引率は0から1の間でなければなりません: $rate")

    def calculate(baseAmount: Money): Money = {
      (baseAmount * rate).roundToDefaultScale()
    }
  }

  // 額引き（固定金額）
  final case class AmountDiscount(amount: Money) extends DiscountType {
    require(amount.isPositive || amount.isZero, "割引額はゼロ以上でなければなりません")

    def calculate(baseAmount: Money): Money = {
      val discount = if (amount > baseAmount) baseAmount else amount
      discount.roundToDefaultScale()
    }
  }

  // クーポン（クーポンコード付き固定金額割引）
  final case class CouponDiscount(
    couponCode: String,
    amount: Money,
    minimumPurchaseAmount: Option[Money] = None
  ) extends DiscountType {
    require(amount.isPositive || amount.isZero, "割引額はゼロ以上でなければなりません")

    def canApply(baseAmount: Money): Boolean = {
      minimumPurchaseAmount match {
        case Some(minimum) => baseAmount >= minimum
        case None => true
      }
    }

    def calculate(baseAmount: Money): Money = {
      if (canApply(baseAmount)) {
        val discount = if (amount > baseAmount) baseAmount else amount
        discount.roundToDefaultScale()
      } else {
        Money.zero(baseAmount.currency)
      }
    }
  }
}
```

### 7.3.2 割引の適用順序

複数の割引が適用される場合、適用順序が重要です。一般的な順序は以下の通りです：

1. 商品単価 × 数量 = 小計
2. 小計 - 商品割引 = 商品割引後金額
3. 商品割引後金額の合計 - 注文割引 = 注文割引後金額
4. 注文割引後金額 × 税率 = 税額
5. 注文割引後金額 + 税額 = 合計金額

```scala
// 割引計算サービス
object DiscountCalculator {

  // 商品明細レベルの割引計算
  def applyItemDiscount(
    item: OrderItem,
    discount: DiscountType
  ): OrderItem = {
    val discountAmount = discount match {
      case d: DiscountType.RateDiscount => d.calculate(item.subtotalTaxExclusive)
      case d: DiscountType.AmountDiscount => d.calculate(item.subtotalTaxExclusive)
      case d: DiscountType.CouponDiscount => d.calculate(item.subtotalTaxExclusive)
    }

    val discountRate = if (item.subtotalTaxExclusive.isZero) {
      DiscountRate(BigDecimal(0))
    } else {
      DiscountRate(discountAmount.amount / item.subtotalTaxExclusive.amount)
    }

    item.copy(discountRate = Some(discountRate))
  }

  // 注文レベルの割引計算
  def applyOrderDiscount(
    order: Order,
    discount: DiscountType
  ): Order = {
    val subtotal = order.subtotalAmount

    val totalDiscountAmount = discount match {
      case d: DiscountType.RateDiscount => d.calculate(subtotal)
      case d: DiscountType.AmountDiscount => d.calculate(subtotal)
      case d: DiscountType.CouponDiscount => d.calculate(subtotal)
    }

    if (totalDiscountAmount.isZero) {
      return order
    }

    // 各明細に按分して割引を適用
    val updatedItems = distributeDiscount(order.items, totalDiscountAmount)
    order.copy(items = updatedItems)
  }

  // 割引を各明細に按分
  private def distributeDiscount(
    items: List[OrderItem],
    totalDiscount: Money
  ): List[OrderItem] = {
    val totalAmount = Money.sum(items.map(_.subtotalTaxExclusive))

    if (totalAmount.isZero) {
      return items
    }

    // 各明細の割引額を計算（比例配分）
    val itemsWithDiscount = items.map { item =>
      val ratio = item.subtotalTaxExclusive.amount / totalAmount.amount
      val itemDiscount = (totalDiscount * ratio).roundToDefaultScale()
      val itemDiscountRate = DiscountRate(itemDiscount.amount / item.subtotalTaxExclusive.amount)

      item.copy(discountRate = Some(itemDiscountRate))
    }

    // 端数調整: 割引額の合計が一致するように最も金額が大きい明細で調整
    val calculatedTotalDiscount = Money.sum(itemsWithDiscount.map(_.discountAmount))
    val difference = totalDiscount - calculatedTotalDiscount

    if (difference.isZero) {
      itemsWithDiscount
    } else {
      // 最も金額が大きい明細に差額を追加
      val maxIndex = itemsWithDiscount.zipWithIndex.maxBy(_._1.subtotalTaxExclusive.amount)._2
      val maxItem = itemsWithDiscount(maxIndex)
      val adjustedDiscount = maxItem.discountAmount + difference
      val adjustedRate = DiscountRate(adjustedDiscount.amount / maxItem.subtotalTaxExclusive.amount)

      itemsWithDiscount.updated(maxIndex, maxItem.copy(discountRate = Some(adjustedRate)))
    }
  }
}
```

### 7.3.3 割引計算の具体例

```scala
// 商品明細レベルの割引
val item = OrderItem(
  productId = ProductId("product-001"),
  productName = "商品A",
  quantity = Quantity(10),
  unitPrice = Money.jpy(1000),
  discountRate = None,
  taxCategory = TaxCategory.Standard,
  taxRate = TaxRate.Standard
)

// 15%割引を適用
val discount = DiscountType.RateDiscount(BigDecimal("0.15"))
val discountedItem = DiscountCalculator.applyItemDiscount(item, discount)

println(s"小計: ${discountedItem.subtotalTaxExclusive}")  // ¥10000
println(s"割引額: ${discountedItem.discountAmount}")       // ¥1500
println(s"課税対象額: ${discountedItem.taxableAmount}")    // ¥8500
println(s"税額: ${discountedItem.taxAmount}")             // ¥850
println(s"合計: ${discountedItem.totalAmount}")           // ¥9350

// クーポン割引の適用
val couponDiscount = DiscountType.CouponDiscount(
  couponCode = "SUMMER2024",
  amount = Money.jpy(2000),
  minimumPurchaseAmount = Some(Money.jpy(5000))
)

val order = Order(
  id = OrderId("order-001"),
  customerId = CustomerId("customer-001"),
  items = List(item, item.copy(productId = ProductId("product-002"))),
  // ...
)

val discountedOrder = DiscountCalculator.applyOrderDiscount(order, couponDiscount)
println(s"割引前合計: ${order.totalAmount}")           // ¥22000
println(s"割引後合計: ${discountedOrder.totalAmount}") // ¥20000
```

## 7.4 金額計算のベストプラクティス

### 7.4.1 一貫性のある丸め処理

金額計算では、丸め処理のタイミングと方法を一貫させることが重要です。

**推奨される丸め戦略**:
1. **中間計算では精度を保つ**: 小数点以下2桁以上の精度を保持
2. **最終結果のみ丸める**: 税額、合計金額など、最終的な金額のみ丸める
3. **丸めモードを明示**: 四捨五入（HALF_UP）を基本とし、特殊なケースでは他のモードを使用

```scala
// 悪い例: 中間計算で丸めてしまう
val unitPrice = Money.jpy(BigDecimal("100.50"))
val quantity = 3
val subtotal = (unitPrice * quantity).round(0)  // 中間で丸める
val taxAmount = (subtotal * BigDecimal("0.10")).round(0)
val total = subtotal + taxAmount
// 精度が失われる可能性がある

// 良い例: 最終結果のみ丸める
val unitPrice2 = Money.jpy(BigDecimal("100.50"))
val quantity2 = 3
val subtotal2 = unitPrice2 * quantity2  // 精度を保つ
val taxAmount2 = (subtotal2 * BigDecimal("0.10")).round(0)  // 最終結果を丸める
val total2 = subtotal2 + taxAmount2
```

### 7.4.2 金額のバリデーション

金額計算の結果が妥当かどうかをバリデーションします。

```scala
object MoneyValidator {

  // 金額が妥当な範囲内か
  def isWithinRange(amount: Money, min: Money, max: Money): Boolean = {
    amount >= min && amount <= max
  }

  // 税金計算が正しいか検証
  def validateTaxCalculation(
    taxExclusiveAmount: Money,
    taxAmount: Money,
    taxRate: TaxRate,
    tolerance: Money = Money.jpy(1)  // 許容誤差1円
  ): Boolean = {
    val expectedTaxAmount = TaxCalculator.calculateTaxAmount(taxExclusiveAmount, taxRate)
    val difference = (taxAmount - expectedTaxAmount).abs
    difference <= tolerance
  }

  // 注文の金額バランスをチェック
  def validateOrderBalance(order: Order): Either[ValidationError, Unit] = {
    if (!order.isBalanced) {
      Left(ValidationError("注文の金額バランスが不正です"))
    } else {
      Right(())
    }
  }
}
```

### 7.4.3 通貨の一貫性

同一注文内では通貨を統一します。

```scala
// 注文作成時に通貨を検証
def validateCurrency(items: List[OrderItem]): Either[ValidationError, Unit] = {
  val currencies = items.map(_.unitPrice.currency).distinct

  if (currencies.size > 1) {
    Left(ValidationError(s"複数の通貨が混在しています: ${currencies.map(_.code).mkString(", ")}"))
  } else {
    Right(())
  }
}
```

## 7.5 まとめ

本章では、受注管理システムにおける金額計算と税金処理について詳しく説明しました。

**実装のポイント**:

1. **BigDecimalの使用**: 浮動小数点数の誤差を避け、正確な10進数演算を実現
2. **Money値オブジェクト**: 金額と通貨を一体化し、型安全な演算を提供
3. **税金計算**: 外税・内税の両方に対応し、複数税率（標準10%、軽減8%）をサポート
4. **割引計算**: 率引き、額引き、クーポンなど複数の割引タイプに対応
5. **丸め処理**: 一貫した丸めモードを使用し、最終結果のみ丸める
6. **バリデーション**: 金額のバランスチェックと妥当性検証

**次章では**:
- 第8章: 与信管理の高度な実装（動的調整、与信超過アラート、取引実績に基づく自動増額）

金額計算の正確性は、受注管理システムの信頼性の根幹です。Money値オブジェクトとBigDecimalにより、会計上の不整合を防ぎ、顧客との信頼関係を維持できます。
