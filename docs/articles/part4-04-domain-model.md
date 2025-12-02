# 【第4部 第4章】ドメインモデルの設計

## 本章の目的

本章では、受注管理システムの中核となるドメインモデルを設計します。ドメイン駆動設計（DDD）の原則に従い、ビジネスロジックを集約（Aggregate）として表現します。

設計する集約：
1. **Order集約**: 注文のライフサイクルと金額計算
2. **Quotation集約**: 見積もりの作成と承認
3. **CreditLimit集約**: 与信限度額の管理
4. **Invoice集約**: 請求書の発行と入金管理

各集約は、不変条件（Invariant）を保証し、ビジネスルールを明確に表現します。

## 4.1 Order集約

Order集約は、受注管理システムの中心的な集約です。注文のライフサイクル全体（作成→在庫引当→与信チェック→確定→出荷→配送完了）を管理します。

### Order エンティティ

```scala
package com.example.order.domain.model.order

import com.example.shared.domain.model._
import java.time.LocalDate
import java.util.UUID

/**
 * Order集約のルートエンティティ
 *
 * 注文は以下のライフサイクルを持つ：
 * Created → StockReserved → CreditApproved → Confirmed → Shipped → Delivered
 *    ↓           ↓               ↓              ↓
 * Cancelled   Cancelled       Cancelled      Returned
 *
 * @param id 注文ID
 * @param customerId 取引先ID
 * @param companyId 自社ID
 * @param orderNumber 注文番号（ビジネスキー）
 * @param orderDate 注文日
 * @param quotationId 見積もりID（見積もりからの変換の場合）
 * @param items 注文明細
 * @param shippingAddress 配送先住所
 * @param requestedDeliveryDate 希望配送日
 * @param status 注文ステータス
 * @param sagaId Saga識別子（分散トランザクション管理用）
 * @param version バージョン（楽観的ロック用）
 */
final case class Order(
  id: OrderId,
  customerId: CustomerId,
  companyId: CompanyId,
  orderNumber: OrderNumber,
  orderDate: LocalDate,
  quotationId: Option[QuotationId],
  items: List[OrderItem],
  shippingAddress: Option[Address],
  requestedDeliveryDate: Option[LocalDate],
  status: OrderStatus,
  sagaId: Option[SagaId],
  version: Version
) {
  // 不変条件のチェック
  require(items.nonEmpty, "注文明細は1件以上必要です")
  require(totalAmount.amount > 0, "注文金額は0より大きい必要があります")
  require(isBalanced, "税金計算が正しくありません")

  /**
   * 小計金額（税抜）
   * 全明細の小計の合計
   */
  def subtotalAmount: Money =
    items.map(_.subtotal).foldLeft(Money.zero)(_ + _)

  /**
   * 割引額
   * 全明細の割引額の合計
   */
  def discountAmount: Money =
    items.map(_.discountAmount).foldLeft(Money.zero)(_ + _)

  /**
   * 税額
   * 全明細の税額の合計
   */
  def taxAmount: Money =
    items.map(_.taxAmount).foldLeft(Money.zero)(_ + _)

  /**
   * 合計金額（税込）
   * 全明細の合計金額の合計
   */
  def totalAmount: Money =
    items.map(_.totalAmount).foldLeft(Money.zero)(_ + _)

  /**
   * 金額計算の整合性チェック
   * subtotal - discount + tax = total が成り立つか
   */
  private def isBalanced: Boolean = {
    val calculated = subtotalAmount - discountAmount + taxAmount
    calculated == totalAmount
  }

  /**
   * 在庫引当を記録
   *
   * @param reservations 商品ごとの引当情報
   * @return 更新されたOrder
   */
  def reserveStock(reservations: List[StockReservation]): Either[OrderError, Order] = {
    if (status != OrderStatus.Created) {
      return Left(InvalidOrderStatus(status, OrderStatus.Created))
    }

    // 全明細に対して引当情報が存在するかチェック
    val allItemsReserved = items.forall { item =>
      reservations.exists(_.productId == item.productId)
    }

    if (!allItemsReserved) {
      return Left(IncompleteStockReservation(items.map(_.productId)))
    }

    // 引当情報を明細に反映
    val updatedItems = items.map { item =>
      val reservation = reservations.find(_.productId == item.productId).get
      item.copy(
        warehouseId = Some(reservation.warehouseId),
        reservedQuantity = Some(reservation.quantity)
      )
    }

    Right(copy(
      items = updatedItems,
      status = OrderStatus.StockReserved,
      version = version.increment
    ))
  }

  /**
   * 与信チェック完了を記録
   *
   * @return 更新されたOrder
   */
  def approvCredit(): Either[OrderError, Order] = {
    if (status != OrderStatus.StockReserved) {
      return Left(InvalidOrderStatus(status, OrderStatus.StockReserved))
    }

    Right(copy(
      status = OrderStatus.CreditApproved,
      version = version.increment
    ))
  }

  /**
   * 注文を確定
   *
   * @return 更新されたOrder
   */
  def confirm(): Either[OrderError, Order] = {
    if (status != OrderStatus.CreditApproved) {
      return Left(InvalidOrderStatus(status, OrderStatus.CreditApproved))
    }

    Right(copy(
      status = OrderStatus.Confirmed,
      version = version.increment
    ))
  }

  /**
   * 出荷完了を記録
   *
   * @param shippedQuantities 実際に出荷された数量
   * @return 更新されたOrder
   */
  def ship(shippedQuantities: Map[ProductId, Quantity]): Either[OrderError, Order] = {
    if (status != OrderStatus.Confirmed) {
      return Left(InvalidOrderStatus(status, OrderStatus.Confirmed))
    }

    // 全明細が出荷されたかチェック
    val allItemsShipped = items.forall { item =>
      shippedQuantities.get(item.productId).exists(_ >= item.quantity)
    }

    if (!allItemsShipped) {
      return Left(IncompleteShipment(items.map(i => (i.productId, i.quantity))))
    }

    // 出荷数量を明細に反映
    val updatedItems = items.map { item =>
      val shippedQty = shippedQuantities(item.productId)
      item.copy(shippedQuantity = Some(shippedQty))
    }

    Right(copy(
      items = updatedItems,
      status = OrderStatus.Shipped,
      version = version.increment
    ))
  }

  /**
   * 配送完了を記録
   *
   * @param deliveryDate 配送完了日
   * @return 更新されたOrder
   */
  def deliver(deliveryDate: LocalDate): Either[OrderError, Order] = {
    if (status != OrderStatus.Shipped) {
      return Left(InvalidOrderStatus(status, OrderStatus.Shipped))
    }

    Right(copy(
      status = OrderStatus.Delivered,
      version = version.increment
    ))
  }

  /**
   * 注文をキャンセル
   *
   * @param reason キャンセル理由
   * @return 更新されたOrder
   */
  def cancel(reason: String): Either[OrderError, Order] = {
    // 配送完了後はキャンセル不可
    if (status == OrderStatus.Delivered) {
      return Left(CannotCancelDeliveredOrder(id))
    }

    Right(copy(
      status = OrderStatus.Cancelled,
      version = version.increment
    ))
  }

  /**
   * 返品処理
   *
   * @param returnItems 返品明細
   * @return 更新されたOrder
   */
  def returnOrder(returnItems: List[ReturnItem]): Either[OrderError, Order] = {
    // 配送完了した注文のみ返品可能
    if (status != OrderStatus.Delivered) {
      return Left(CannotReturnNonDeliveredOrder(id, status))
    }

    // 返品明細が注文明細に含まれているかチェック
    val validReturnItems = returnItems.forall { returnItem =>
      items.exists { orderItem =>
        orderItem.productId == returnItem.productId &&
        orderItem.quantity >= returnItem.quantity
      }
    }

    if (!validReturnItems) {
      return Left(InvalidReturnItems(returnItems.map(_.productId)))
    }

    Right(copy(
      status = OrderStatus.Returned,
      version = version.increment
    ))
  }
}

object Order {
  /**
   * 新規注文を作成
   *
   * @param customerId 取引先ID
   * @param companyId 自社ID
   * @param orderDate 注文日
   * @param items 注文明細
   * @param quotationId 見積もりID（オプション）
   * @param shippingAddress 配送先住所（オプション）
   * @param requestedDeliveryDate 希望配送日（オプション）
   * @param sagaId Saga識別子
   * @return 新規Order
   */
  def create(
    customerId: CustomerId,
    companyId: CompanyId,
    orderDate: LocalDate,
    items: List[OrderItem],
    quotationId: Option[QuotationId] = None,
    shippingAddress: Option[Address] = None,
    requestedDeliveryDate: Option[LocalDate] = None,
    sagaId: Option[SagaId] = None
  ): Either[OrderError, Order] = {
    if (items.isEmpty) {
      return Left(EmptyOrderItems())
    }

    val orderId = OrderId(UUID.randomUUID())
    val orderNumber = OrderNumber.generate(orderDate)

    Right(Order(
      id = orderId,
      customerId = customerId,
      companyId = companyId,
      orderNumber = orderNumber,
      orderDate = orderDate,
      quotationId = quotationId,
      items = items,
      shippingAddress = shippingAddress,
      requestedDeliveryDate = requestedDeliveryDate,
      status = OrderStatus.Created,
      sagaId = sagaId,
      version = Version.initial
    ))
  }
}
```

### OrderItem 値オブジェクト

注文明細を表す値オブジェクト。商品、数量、価格、税金の計算を含みます。

```scala
/**
 * 注文明細
 *
 * 金額計算ロジック：
 * 1. subtotal = quantity × unitPrice
 * 2. discountAmount = subtotal × discountRate（割引率が設定されている場合）
 * 3. taxAmount = (subtotal - discountAmount) × taxRate（円未満四捨五入）
 * 4. totalAmount = subtotal - discountAmount + taxAmount
 *
 * @param productId 商品ID
 * @param productCode 商品コード（非正規化）
 * @param productName 商品名（非正規化）
 * @param quantity 数量
 * @param unitOfMeasure 単位
 * @param unitPrice 単価（税抜）
 * @param discountRate 割引率（オプション）
 * @param taxCategory 税区分
 * @param taxRate 税率
 * @param warehouseId 引当倉庫ID（引当後に設定）
 * @param reservedQuantity 引当数量（引当後に設定）
 * @param shippedQuantity 出荷数量（出荷後に設定）
 */
final case class OrderItem(
  productId: ProductId,
  productCode: ProductCode,
  productName: ProductName,
  quantity: Quantity,
  unitOfMeasure: UnitOfMeasure,
  unitPrice: Money,
  discountRate: Option[DiscountRate],
  taxCategory: TaxCategory,
  taxRate: TaxRate,
  warehouseId: Option[WarehouseId] = None,
  reservedQuantity: Option[Quantity] = None,
  shippedQuantity: Option[Quantity] = None
) {
  require(quantity.value > 0, "数量は0より大きい必要があります")
  require(unitPrice.amount >= 0, "単価は0以上である必要があります")

  /**
   * 小計 = 数量 × 単価
   */
  val subtotal: Money =
    unitPrice * quantity.value

  /**
   * 割引額 = 小計 × 割引率
   */
  val discountAmount: Money =
    discountRate.map(rate => subtotal * rate.value).getOrElse(Money.zero)

  /**
   * 課税対象額 = 小計 - 割引額
   */
  val taxableAmount: Money =
    subtotal - discountAmount

  /**
   * 税額 = 課税対象額 × 税率（円未満四捨五入）
   */
  val taxAmount: Money =
    (taxableAmount * taxRate.value).round(0)

  /**
   * 合計金額 = 課税対象額 + 税額
   */
  val totalAmount: Money =
    taxableAmount + taxAmount
}

object OrderItem {
  /**
   * 商品情報から注文明細を作成
   *
   * @param product 商品
   * @param quantity 数量
   * @param discountRate 割引率（オプション）
   * @return 注文明細
   */
  def fromProduct(
    product: Product,
    quantity: Quantity,
    discountRate: Option[DiscountRate] = None
  ): OrderItem = {
    OrderItem(
      productId = product.id,
      productCode = product.code,
      productName = product.name,
      quantity = quantity,
      unitOfMeasure = product.unitOfMeasure,
      unitPrice = product.standardPrice,
      discountRate = discountRate,
      taxCategory = product.taxCategory,
      taxRate = product.taxRate
    )
  }
}
```

### Money 値オブジェクト

金額を表す値オブジェクト。`BigDecimal`を使用して正確な10進数演算を実現します。

```scala
package com.example.shared.domain.model

import scala.math.BigDecimal.RoundingMode

/**
 * 金額を表す値オブジェクト
 *
 * 浮動小数点演算の問題を避けるため、BigDecimalを使用。
 * 全ての金額計算は円単位で行い、小数点以下は四捨五入。
 *
 * @param amount 金額（円単位）
 * @param currency 通貨
 */
final case class Money(amount: BigDecimal, currency: Currency = Currency.JPY) {
  require(amount.scale <= 2, "金額は小数点以下2桁までです")

  /**
   * 金額の加算
   */
  def +(other: Money): Money = {
    require(currency == other.currency, s"通貨が一致しません: $currency != ${other.currency}")
    Money(amount + other.amount, currency)
  }

  /**
   * 金額の減算
   */
  def -(other: Money): Money = {
    require(currency == other.currency, s"通貨が一致しません: $currency != ${other.currency}")
    Money(amount - other.amount, currency)
  }

  /**
   * 金額の乗算
   */
  def *(multiplier: BigDecimal): Money = {
    Money(amount * multiplier, currency)
  }

  /**
   * 金額の除算
   */
  def /(divisor: BigDecimal): Money = {
    require(divisor != 0, "0で除算できません")
    Money(amount / divisor, currency)
  }

  /**
   * 丸め処理
   *
   * @param scale 小数点以下の桁数
   * @param mode 丸めモード（デフォルト: 四捨五入）
   * @return 丸められた金額
   */
  def round(scale: Int = 0, mode: RoundingMode = RoundingMode.HALF_UP): Money = {
    Money(amount.setScale(scale, mode), currency)
  }

  /**
   * 比較演算子
   */
  def <(other: Money): Boolean = {
    require(currency == other.currency, s"通貨が一致しません: $currency != ${other.currency}")
    amount < other.amount
  }

  def <=(other: Money): Boolean = {
    require(currency == other.currency, s"通貨が一致しません: $currency != ${other.currency}")
    amount <= other.amount
  }

  def >(other: Money): Boolean = {
    require(currency == other.currency, s"通貨が一致しません: $currency != ${other.currency}")
    amount > other.amount
  }

  def >=(other: Money): Boolean = {
    require(currency == other.currency, s"通貨が一致しません: $currency != ${other.currency}")
    amount >= other.amount
  }

  /**
   * 表示用フォーマット
   */
  override def toString: String = s"¥${amount.setScale(0, RoundingMode.HALF_UP).toString}"
}

object Money {
  /**
   * ゼロ円
   */
  val zero: Money = Money(BigDecimal(0))

  /**
   * 文字列から金額を生成
   */
  def apply(amount: String): Money = Money(BigDecimal(amount))

  /**
   * Int/Longから金額を生成
   */
  def apply(amount: Int): Money = Money(BigDecimal(amount))
  def apply(amount: Long): Money = Money(BigDecimal(amount))
}

/**
 * 通貨
 */
sealed trait Currency
object Currency {
  case object JPY extends Currency  // 日本円
  case object USD extends Currency  // 米ドル
  case object EUR extends Currency  // ユーロ
}
```

### OrderStatus（注文ステータス）

```scala
/**
 * 注文ステータス
 *
 * 状態遷移：
 * Created → StockReserved → CreditApproved → Confirmed → Shipped → Delivered
 *    ↓           ↓               ↓              ↓
 * Cancelled   Cancelled       Cancelled      Returned
 */
sealed trait OrderStatus

object OrderStatus {
  /** 作成済み（初期状態） */
  case object Created extends OrderStatus

  /** 在庫引当済み */
  case object StockReserved extends OrderStatus

  /** 与信承認済み */
  case object CreditApproved extends OrderStatus

  /** 確定済み */
  case object Confirmed extends OrderStatus

  /** 出荷済み */
  case object Shipped extends OrderStatus

  /** 配送完了 */
  case object Delivered extends OrderStatus

  /** キャンセル */
  case object Cancelled extends OrderStatus

  /** 返品 */
  case object Returned extends OrderStatus

  /**
   * 有効な状態遷移かチェック
   */
  def canTransition(from: OrderStatus, to: OrderStatus): Boolean = (from, to) match {
    case (Created, StockReserved | Cancelled) => true
    case (StockReserved, CreditApproved | Cancelled) => true
    case (CreditApproved, Confirmed | Cancelled) => true
    case (Confirmed, Shipped | Cancelled) => true
    case (Shipped, Delivered) => true
    case (Delivered, Returned) => true
    case _ => false
  }
}
```

## 4.2 Quotation集約

Quotation集約は、見積もりの作成、承認、注文への変換を管理します。

### Quotation エンティティ

```scala
package com.example.order.domain.model.quotation

import com.example.shared.domain.model._
import java.time.LocalDate
import java.util.UUID

/**
 * Quotation集約のルートエンティティ
 *
 * 見積もりのライフサイクル：
 * Draft → Submitted → Approved → ConvertedToOrder
 *   ↓        ↓           ↓
 * Cancelled  Expired   Cancelled
 *
 * @param id 見積もりID
 * @param customerId 取引先ID
 * @param companyId 自社ID
 * @param quotationNumber 見積もり番号
 * @param quotationDate 見積もり日
 * @param validUntil 有効期限
 * @param items 見積もり明細
 * @param status ステータス
 * @param notes 備考
 * @param version バージョン
 */
final case class Quotation(
  id: QuotationId,
  customerId: CustomerId,
  companyId: CompanyId,
  quotationNumber: QuotationNumber,
  quotationDate: LocalDate,
  validUntil: LocalDate,
  items: List[QuotationItem],
  status: QuotationStatus,
  notes: Option[String],
  version: Version
) {
  require(items.nonEmpty, "見積もり明細は1件以上必要です")
  require(validUntil.isAfter(quotationDate), "有効期限は見積もり日より後である必要があります")

  /**
   * 合計金額（各明細の合計金額の合計）
   */
  def totalAmount: Money =
    items.map(_.totalAmount).foldLeft(Money.zero)(_ + _)

  /**
   * 見積もりを取引先に提出
   */
  def submit(): Either[QuotationError, Quotation] = {
    if (status != QuotationStatus.Draft) {
      return Left(InvalidQuotationStatus(status, QuotationStatus.Draft))
    }

    Right(copy(
      status = QuotationStatus.Submitted,
      version = version.increment
    ))
  }

  /**
   * 見積もりを承認（取引先による承認）
   */
  def approve(): Either[QuotationError, Quotation] = {
    if (status != QuotationStatus.Submitted) {
      return Left(InvalidQuotationStatus(status, QuotationStatus.Submitted))
    }

    // 有効期限切れチェック
    if (LocalDate.now().isAfter(validUntil)) {
      return Left(QuotationExpired(id, validUntil))
    }

    Right(copy(
      status = QuotationStatus.Approved,
      version = version.increment
    ))
  }

  /**
   * 見積もりを注文に変換
   *
   * @return 変換用の注文明細リスト
   */
  def convertToOrder(): Either[QuotationError, List[OrderItem]] = {
    if (status != QuotationStatus.Approved) {
      return Left(InvalidQuotationStatus(status, QuotationStatus.Approved))
    }

    // 有効期限切れチェック
    if (LocalDate.now().isAfter(validUntil)) {
      return Left(QuotationExpired(id, validUntil))
    }

    val orderItems = items.map { item =>
      OrderItem(
        productId = item.productId,
        productCode = item.productCode,
        productName = item.productName,
        quantity = item.quantity,
        unitOfMeasure = item.unitOfMeasure,
        unitPrice = item.unitPrice,
        discountRate = item.discountRate,
        taxCategory = item.taxCategory,
        taxRate = item.taxRate
      )
    }

    Right(orderItems)
  }

  /**
   * 変換済みとしてマーク
   */
  def markAsConverted(): Either[QuotationError, Quotation] = {
    if (status != QuotationStatus.Approved) {
      return Left(InvalidQuotationStatus(status, QuotationStatus.Approved))
    }

    Right(copy(
      status = QuotationStatus.ConvertedToOrder,
      version = version.increment
    ))
  }

  /**
   * 有効期限切れとしてマーク
   */
  def expire(): Either[QuotationError, Quotation] = {
    if (status == QuotationStatus.ConvertedToOrder || status == QuotationStatus.Cancelled) {
      return Left(CannotExpireQuotation(id, status))
    }

    Right(copy(
      status = QuotationStatus.Expired,
      version = version.increment
    ))
  }

  /**
   * キャンセル
   */
  def cancel(): Either[QuotationError, Quotation] = {
    if (status == QuotationStatus.ConvertedToOrder) {
      return Left(CannotCancelConvertedQuotation(id))
    }

    Right(copy(
      status = QuotationStatus.Cancelled,
      version = version.increment
    ))
  }
}

object Quotation {
  /**
   * 新規見積もりを作成
   */
  def create(
    customerId: CustomerId,
    companyId: CompanyId,
    quotationDate: LocalDate,
    validUntil: LocalDate,
    items: List[QuotationItem],
    notes: Option[String] = None
  ): Either[QuotationError, Quotation] = {
    if (items.isEmpty) {
      return Left(EmptyQuotationItems())
    }

    if (!validUntil.isAfter(quotationDate)) {
      return Left(InvalidValidUntilDate(quotationDate, validUntil))
    }

    val quotationId = QuotationId(UUID.randomUUID())
    val quotationNumber = QuotationNumber.generate(quotationDate)

    Right(Quotation(
      id = quotationId,
      customerId = customerId,
      companyId = companyId,
      quotationNumber = quotationNumber,
      quotationDate = quotationDate,
      validUntil = validUntil,
      items = items,
      status = QuotationStatus.Draft,
      notes = notes,
      version = Version.initial
    ))
  }
}
```

### QuotationItem 値オブジェクト

```scala
/**
 * 見積もり明細
 *
 * OrderItemと同様の金額計算ロジックを持つ
 */
final case class QuotationItem(
  productId: ProductId,
  productCode: ProductCode,
  productName: ProductName,
  quantity: Quantity,
  unitOfMeasure: UnitOfMeasure,
  unitPrice: Money,
  discountRate: Option[DiscountRate],
  taxCategory: TaxCategory,
  taxRate: TaxRate,
  notes: Option[String] = None
) {
  require(quantity.value > 0, "数量は0より大きい必要があります")
  require(unitPrice.amount >= 0, "単価は0以上である必要があります")

  val subtotal: Money = unitPrice * quantity.value
  val discountAmount: Money = discountRate.map(rate => subtotal * rate.value).getOrElse(Money.zero)
  val taxableAmount: Money = subtotal - discountAmount
  val taxAmount: Money = (taxableAmount * taxRate.value).round(0)
  val totalAmount: Money = taxableAmount + taxAmount
}
```

### QuotationStatus（見積もりステータス）

```scala
sealed trait QuotationStatus

object QuotationStatus {
  /** 下書き */
  case object Draft extends QuotationStatus

  /** 提出済み（取引先確認待ち） */
  case object Submitted extends QuotationStatus

  /** 承認済み */
  case object Approved extends QuotationStatus

  /** 有効期限切れ */
  case object Expired extends QuotationStatus

  /** 注文に変換済み */
  case object ConvertedToOrder extends QuotationStatus

  /** キャンセル */
  case object Cancelled extends QuotationStatus
}
```

## 4.3 CreditLimit集約

CreditLimit集約は、取引先の与信限度額と使用状況を管理します。

### CreditLimit エンティティ

```scala
package com.example.order.domain.model.credit

import com.example.shared.domain.model._

/**
 * CreditLimit集約のルートエンティティ
 *
 * 与信限度額の管理：
 * - 限度額の設定
 * - 与信枠の引当（Reserve）
 * - 与信枠の解放（Release）
 * - 与信使用（Use）
 * - 与信回収（Recover）
 *
 * @param customerId 取引先ID（集約ID）
 * @param limitAmount 与信限度額
 * @param usedAmount 現在使用額
 * @param reservations 引当中の金額（注文ID → 金額）
 * @param version バージョン（楽観的ロック用）
 */
final case class CreditLimit(
  customerId: CustomerId,
  limitAmount: Money,
  usedAmount: Money,
  reservations: Map[OrderId, Money],
  version: Version
) {
  require(limitAmount.amount >= 0, "与信限度額は0以上である必要があります")
  require(usedAmount.amount >= 0, "使用額は0以上である必要があります")
  require(usedAmount <= limitAmount, s"使用額が限度額を超えています: $usedAmount > $limitAmount")

  /**
   * 引当中の合計金額
   */
  def reservedAmount: Money =
    reservations.values.foldLeft(Money.zero)(_ + _)

  /**
   * 利用可能額 = 限度額 - 使用額 - 引当中の金額
   */
  def availableAmount: Money =
    limitAmount - usedAmount - reservedAmount

  /**
   * 与信使用率（0.0〜1.0）
   */
  def usageRatio: BigDecimal = {
    if (limitAmount.amount == 0) BigDecimal(0)
    else (usedAmount.amount + reservedAmount.amount) / limitAmount.amount
  }

  /**
   * 与信枠を引き当てる（注文作成時）
   *
   * @param orderId 注文ID
   * @param amount 引当金額
   * @return 更新されたCreditLimit
   */
  def reserve(orderId: OrderId, amount: Money): Either[CreditError, CreditLimit] = {
    // 既に引当済みの場合はエラー
    if (reservations.contains(orderId)) {
      return Left(CreditAlreadyReserved(customerId, orderId))
    }

    // 利用可能額チェック
    if (amount > availableAmount) {
      return Left(CreditExceeded(customerId, availableAmount, amount))
    }

    Right(copy(
      reservations = reservations + (orderId -> amount),
      version = version.increment
    ))
  }

  /**
   * 与信枠の引当を解放（注文キャンセル時）
   *
   * @param orderId 注文ID
   * @return 更新されたCreditLimit
   */
  def release(orderId: OrderId): Either[CreditError, CreditLimit] = {
    // 引当が存在しない場合はエラー
    if (!reservations.contains(orderId)) {
      return Left(CreditReservationNotFound(customerId, orderId))
    }

    Right(copy(
      reservations = reservations - orderId,
      version = version.increment
    ))
  }

  /**
   * 与信を使用（注文確定時）
   *
   * 引当から使用へ移行
   *
   * @param orderId 注文ID
   * @return 更新されたCreditLimit
   */
  def use(orderId: OrderId): Either[CreditError, CreditLimit] = {
    // 引当が存在しない場合はエラー
    reservations.get(orderId) match {
      case None =>
        Left(CreditReservationNotFound(customerId, orderId))

      case Some(amount) =>
        val newUsedAmount = usedAmount + amount
        val newReservations = reservations - orderId

        Right(copy(
          usedAmount = newUsedAmount,
          reservations = newReservations,
          version = version.increment
        ))
    }
  }

  /**
   * 与信を回収（入金時）
   *
   * @param amount 回収金額
   * @return 更新されたCreditLimit
   */
  def recover(amount: Money): Either[CreditError, CreditLimit] = {
    // 使用額を超える回収はエラー
    if (amount > usedAmount) {
      return Left(CreditRecoveryExceedsUsedAmount(customerId, usedAmount, amount))
    }

    Right(copy(
      usedAmount = usedAmount - amount,
      version = version.increment
    ))
  }

  /**
   * 与信限度額を調整
   *
   * @param newLimitAmount 新しい限度額
   * @return 更新されたCreditLimit
   */
  def adjustLimit(newLimitAmount: Money): Either[CreditError, CreditLimit] = {
    require(newLimitAmount.amount >= 0, "与信限度額は0以上である必要があります")

    // 新しい限度額が使用額より小さい場合はエラー
    if (newLimitAmount < usedAmount) {
      return Left(NewLimitBelowUsedAmount(customerId, usedAmount, newLimitAmount))
    }

    Right(copy(
      limitAmount = newLimitAmount,
      version = version.increment
    ))
  }
}

object CreditLimit {
  /**
   * 新規与信限度額を設定
   *
   * @param customerId 取引先ID
   * @param limitAmount 与信限度額
   * @return 新規CreditLimit
   */
  def create(customerId: CustomerId, limitAmount: Money): CreditLimit = {
    require(limitAmount.amount >= 0, "与信限度額は0以上である必要があります")

    CreditLimit(
      customerId = customerId,
      limitAmount = limitAmount,
      usedAmount = Money.zero,
      reservations = Map.empty,
      version = Version.initial
    )
  }
}
```

## 4.4 Invoice集約

Invoice集約は、月次締めで発行される請求書と入金管理を行います。

### Invoice エンティティ

```scala
package com.example.order.domain.model.invoice

import com.example.shared.domain.model._
import java.time.{LocalDate, YearMonth}
import java.util.UUID

/**
 * Invoice集約のルートエンティティ
 *
 * 請求書のライフサイクル：
 * Unpaid → PartiallyPaid → FullyPaid
 *   ↓
 * Overdue（支払期限超過）
 *
 * @param id 請求書ID
 * @param customerId 取引先ID
 * @param companyId 自社ID
 * @param invoiceNumber 請求書番号
 * @param billingYearMonth 請求年月
 * @param closingDate 締日
 * @param orderIds 請求対象の注文ID一覧
 * @param totalAmount 請求金額（税込）
 * @param paidAmount 入金済み金額
 * @param payments 入金履歴
 * @param issueDate 請求書発行日
 * @param dueDate 支払期限
 * @param status 支払ステータス
 * @param version バージョン
 */
final case class Invoice(
  id: InvoiceId,
  customerId: CustomerId,
  companyId: CompanyId,
  invoiceNumber: InvoiceNumber,
  billingYearMonth: YearMonth,
  closingDate: LocalDate,
  orderIds: List[OrderId],
  totalAmount: Money,
  paidAmount: Money,
  payments: List[Payment],
  issueDate: LocalDate,
  dueDate: LocalDate,
  status: InvoiceStatus,
  version: Version
) {
  require(orderIds.nonEmpty, "請求対象の注文は1件以上必要です")
  require(totalAmount.amount > 0, "請求金額は0より大きい必要があります")
  require(paidAmount.amount >= 0, "入金額は0以上である必要があります")
  require(paidAmount <= totalAmount, "入金額が請求金額を超えています")
  require(dueDate.isAfter(issueDate) || dueDate.isEqual(issueDate), "支払期限は発行日以降である必要があります")

  /**
   * 未入金残高
   */
  def balanceAmount: Money =
    totalAmount - paidAmount

  /**
   * 支払期限を超過しているか
   */
  def isOverdue: Boolean =
    LocalDate.now().isAfter(dueDate) && balanceAmount.amount > 0

  /**
   * 入金を記録
   *
   * @param payment 入金情報
   * @return 更新されたInvoice
   */
  def recordPayment(payment: Payment): Either[InvoiceError, Invoice] = {
    // 全額入金済みの場合はエラー
    if (status == InvoiceStatus.FullyPaid) {
      return Left(InvoiceAlreadyFullyPaid(id))
    }

    // 入金額が残高を超える場合はエラー
    if (payment.amount > balanceAmount) {
      return Left(PaymentExceedsBalance(id, balanceAmount, payment.amount))
    }

    val newPaidAmount = paidAmount + payment.amount
    val newPayments = payments :+ payment

    val newStatus = if (newPaidAmount == totalAmount) {
      InvoiceStatus.FullyPaid
    } else {
      InvoiceStatus.PartiallyPaid
    }

    Right(copy(
      paidAmount = newPaidAmount,
      payments = newPayments,
      status = newStatus,
      version = version.increment
    ))
  }

  /**
   * 入金催促を記録
   *
   * @return 更新されたInvoice
   */
  def remind(): Either[InvoiceError, Invoice] = {
    // 全額入金済みの場合は催促不要
    if (status == InvoiceStatus.FullyPaid) {
      return Left(CannotRemindFullyPaidInvoice(id))
    }

    // ステータスを更新（催促済みマーク）
    // ※実際には催促履歴を別途記録することも検討
    Right(this)
  }

  /**
   * 支払期限超過としてマーク
   *
   * @return 更新されたInvoice
   */
  def markAsOverdue(): Either[InvoiceError, Invoice] = {
    if (!isOverdue) {
      return Left(InvoiceNotOverdue(id, dueDate))
    }

    if (status == InvoiceStatus.FullyPaid) {
      return Left(CannotMarkFullyPaidAsOverdue(id))
    }

    Right(copy(
      status = InvoiceStatus.Overdue,
      version = version.increment
    ))
  }
}

object Invoice {
  /**
   * 月次締めで請求書を発行
   *
   * @param customerId 取引先ID
   * @param companyId 自社ID
   * @param billingYearMonth 請求年月
   * @param orders 請求対象の注文一覧
   * @param issueDate 請求書発行日
   * @param dueDate 支払期限
   * @return 新規Invoice
   */
  def generate(
    customerId: CustomerId,
    companyId: CompanyId,
    billingYearMonth: YearMonth,
    orders: List[Order],
    issueDate: LocalDate,
    dueDate: LocalDate
  ): Either[InvoiceError, Invoice] = {
    if (orders.isEmpty) {
      return Left(NoOrdersForInvoice(customerId, billingYearMonth))
    }

    // 全ての注文が配送完了していることを確認
    val allDelivered = orders.forall(_.status == OrderStatus.Delivered)
    if (!allDelivered) {
      return Left(OrdersNotDelivered(orders.filterNot(_.status == OrderStatus.Delivered).map(_.id)))
    }

    val invoiceId = InvoiceId(UUID.randomUUID())
    val invoiceNumber = InvoiceNumber.generate(billingYearMonth)
    val closingDate = billingYearMonth.atEndOfMonth()
    val totalAmount = orders.map(_.totalAmount).foldLeft(Money.zero)(_ + _)

    Right(Invoice(
      id = invoiceId,
      customerId = customerId,
      companyId = companyId,
      invoiceNumber = invoiceNumber,
      billingYearMonth = billingYearMonth,
      closingDate = closingDate,
      orderIds = orders.map(_.id),
      totalAmount = totalAmount,
      paidAmount = Money.zero,
      payments = List.empty,
      issueDate = issueDate,
      dueDate = dueDate,
      status = InvoiceStatus.Unpaid,
      version = Version.initial
    ))
  }
}
```

### Payment 値オブジェクト

```scala
/**
 * 入金情報
 *
 * @param paymentId 入金ID
 * @param paymentDate 入金日
 * @param amount 入金額
 * @param paymentMethod 入金方法
 * @param bankName 銀行名（銀行振込の場合）
 * @param transferReference 振込参照番号（銀行振込の場合）
 * @param notes 備考
 */
final case class Payment(
  paymentId: PaymentId,
  paymentDate: LocalDate,
  amount: Money,
  paymentMethod: PaymentMethod,
  bankName: Option[String] = None,
  transferReference: Option[String] = None,
  notes: Option[String] = None
) {
  require(amount.amount > 0, "入金額は0より大きい必要があります")
}

/**
 * 入金方法
 */
sealed trait PaymentMethod

object PaymentMethod {
  /** 銀行振込 */
  case object BankTransfer extends PaymentMethod

  /** 手形 */
  case object Bill extends PaymentMethod

  /** 現金 */
  case object Cash extends PaymentMethod

  /** クレジットカード */
  case object CreditCard extends PaymentMethod
}
```

### InvoiceStatus（請求ステータス）

```scala
sealed trait InvoiceStatus

object InvoiceStatus {
  /** 未入金 */
  case object Unpaid extends InvoiceStatus

  /** 一部入金 */
  case object PartiallyPaid extends InvoiceStatus

  /** 全額入金済み */
  case object FullyPaid extends InvoiceStatus

  /** 支払期限超過 */
  case object Overdue extends InvoiceStatus
}
```

## 本章のまとめ

本章では、受注管理システムの4つの集約を設計しました：

### Order集約
- **責務**: 注文のライフサイクル管理（作成→在庫引当→与信チェック→確定→出荷→配送完了）
- **主要メソッド**: `reserveStock()`, `approveCredit()`, `confirm()`, `ship()`, `deliver()`, `cancel()`, `returnOrder()`
- **不変条件**: 明細が1件以上、金額計算の整合性、ステータス遷移の妥当性

### Quotation集約
- **責務**: 見積もりの作成、承認、注文への変換
- **主要メソッド**: `submit()`, `approve()`, `convertToOrder()`, `expire()`, `cancel()`
- **不変条件**: 明細が1件以上、有効期限 > 見積もり日

### CreditLimit集約
- **責務**: 与信限度額と使用状況の管理
- **主要メソッド**: `reserve()`, `release()`, `use()`, `recover()`, `adjustLimit()`
- **不変条件**: 使用額 ≦ 限度額、引当中の金額を含めた利用可能額の計算

### Invoice集約
- **責務**: 請求書の発行と入金管理
- **主要メソッド**: `recordPayment()`, `remind()`, `markAsOverdue()`
- **不変条件**: 入金額 ≦ 請求金額、配送完了済み注文のみ請求対象

### 値オブジェクト

**Money**: BigDecimalによる正確な金額計算、通貨の管理、四則演算と比較演算

**OrderItem/QuotationItem**: 金額計算ロジック（小計 → 割引 → 税額 → 合計）

**Payment**: 入金情報（入金日、金額、入金方法）

次章では、これらの集約を実際に実装し、コマンド処理とイベント発行のロジックを記述します。
