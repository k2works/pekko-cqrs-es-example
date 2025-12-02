# 【第5部 第4章】ドメインモデルの設計

## 本章の目的

本章では、発注管理システムのドメインモデルを設計します。ドメイン駆動設計（DDD）の原則に従い、4つの集約（Supplier、PurchaseOrder、Receiving、SupplierPayment）を詳細に実装します。

各集約は以下の要素で構成されます：
- **エンティティ**: 一意の識別子を持つドメインオブジェクト
- **値オブジェクト**: 不変で、等価性が値で判断されるオブジェクト
- **ビジネスルール**: ドメイン固有の制約と振る舞い

## 4.1 Supplier集約

Supplier集約は、仕入先の情報と評価を管理します。

### 4.1.1 Supplier エンティティ

```scala
package com.example.procurement.domain

import java.time.LocalDate

// 仕入先集約ルート
final case class Supplier(
  id: SupplierId,
  tenantId: TenantId,
  supplierCode: SupplierCode,
  supplierName: SupplierName,
  supplierType: SupplierType,
  contactInfo: ContactInfo,
  paymentTerms: PaymentTerms,
  leadTime: LeadTime,
  evaluation: SupplierEvaluation,
  isActive: Boolean,
  version: Version
) {
  // 仕入先情報を更新
  def updateInfo(
    contactInfo: ContactInfo,
    leadTime: LeadTime
  ): Supplier = {
    copy(
      contactInfo = contactInfo,
      leadTime = leadTime,
      version = version.increment
    )
  }

  // 仕入先評価を更新
  def updateEvaluation(
    evaluation: SupplierEvaluation
  ): Supplier = {
    copy(
      evaluation = evaluation,
      version = version.increment
    )
  }

  // 仕入先を無効化
  def deactivate(): Supplier = {
    copy(
      isActive = false,
      version = version.increment
    )
  }

  // 支払予定日を計算
  def calculatePaymentDate(invoiceDate: LocalDate): LocalDate = {
    paymentTerms.calculatePaymentDate(invoiceDate)
  }

  // 入荷予定日を計算
  def calculateExpectedDeliveryDate(orderDate: LocalDate): LocalDate = {
    leadTime.calculateExpectedDeliveryDate(orderDate)
  }
}

// 仕入先ID
final case class SupplierId(value: String) extends AnyVal {
  override def toString: String = value
}

object SupplierId {
  def generate(): SupplierId = {
    SupplierId(java.util.UUID.randomUUID().toString)
  }
}

// 仕入先コード
final case class SupplierCode(value: String) extends AnyVal {
  require(value.nonEmpty, "仕入先コードは必須です")
  require(value.length <= 50, "仕入先コードは50文字以内である必要があります")
}

// 仕入先名
final case class SupplierName(value: String) extends AnyVal {
  require(value.nonEmpty, "仕入先名は必須です")
  require(value.length <= 200, "仕入先名は200文字以内である必要があります")
}
```

### 4.1.2 仕入先タイプ

```scala
package com.example.procurement.domain

// 仕入先タイプ
sealed trait SupplierType {
  def description: String
  def defaultPaymentTermDays: Int
  def defaultLeadTimeDays: Int
}

object SupplierType {
  // 大手メーカー
  case object MajorManufacturer extends SupplierType {
    val description = "大手メーカー"
    val defaultPaymentTermDays = 60
    val defaultLeadTimeDays = 10
  }

  // 中堅メーカー
  case object MidSizeManufacturer extends SupplierType {
    val description = "中堅メーカー"
    val defaultPaymentTermDays = 45
    val defaultLeadTimeDays = 14
  }

  // 小規模事業者
  case object SmallBusiness extends SupplierType {
    val description = "小規模事業者"
    val defaultPaymentTermDays = 30
    val defaultLeadTimeDays = 21
  }

  def fromString(s: String): Option[SupplierType] = s match {
    case "MajorManufacturer" => Some(MajorManufacturer)
    case "MidSizeManufacturer" => Some(MidSizeManufacturer)
    case "SmallBusiness" => Some(SmallBusiness)
    case _ => None
  }
}
```

### 4.1.3 支払条件

```scala
package com.example.procurement.domain

import java.time.LocalDate

// 支払条件
final case class PaymentTerms(
  closingDay: Int,        // 締日（1-31、月末=31）
  paymentDay: Int,        // 支払日（1-31、月末=31）
  paymentTermDays: Int    // 支払サイト（日数）
) {
  require(closingDay >= 1 && closingDay <= 31, "締日は1-31の範囲である必要があります")
  require(paymentDay >= 1 && paymentDay <= 31, "支払日は1-31の範囲である必要があります")
  require(paymentTermDays >= 0, "支払サイトは0日以上である必要があります")

  // 支払予定日を計算
  def calculatePaymentDate(invoiceDate: LocalDate): LocalDate = {
    // 締日を計算
    val closingDate = if (invoiceDate.getDayOfMonth <= closingDay) {
      invoiceDate.withDayOfMonth(Math.min(closingDay, invoiceDate.lengthOfMonth()))
    } else {
      val nextMonth = invoiceDate.plusMonths(1)
      nextMonth.withDayOfMonth(Math.min(closingDay, nextMonth.lengthOfMonth()))
    }

    // 支払日を計算
    val paymentMonth = closingDate.plusDays(paymentTermDays)
    paymentMonth.withDayOfMonth(Math.min(paymentDay, paymentMonth.lengthOfMonth()))
  }
}

object PaymentTerms {
  // 月末締め翌月末払い（30日サイト）
  val monthEndNext30Days: PaymentTerms = PaymentTerms(31, 31, 30)

  // 月末締め翌々月15日払い（45日サイト）
  val monthEndNext45Days: PaymentTerms = PaymentTerms(31, 15, 45)

  // 月末締め翌々月末払い（60日サイト）
  val monthEndNext60Days: PaymentTerms = PaymentTerms(31, 31, 60)
}
```

### 4.1.4 リードタイム

```scala
package com.example.procurement.domain

import java.time.LocalDate

// リードタイム
final case class LeadTime(days: Int) extends AnyVal {
  require(days > 0, "リードタイムは1日以上である必要があります")

  // 入荷予定日を計算
  def calculateExpectedDeliveryDate(orderDate: LocalDate): LocalDate = {
    orderDate.plusDays(days)
  }

  // 営業日ベースの入荷予定日を計算
  def calculateExpectedDeliveryDateBusinessDays(orderDate: LocalDate): LocalDate = {
    var current = orderDate
    var remaining = days

    while (remaining > 0) {
      current = current.plusDays(1)
      // 土日を除外（実際は祝日も考慮が必要）
      if (current.getDayOfWeek.getValue <= 5) {
        remaining -= 1
      }
    }
    current
  }
}
```

### 4.1.5 仕入先評価

```scala
package com.example.procurement.domain

import java.time.{LocalDate, Period}

// 仕入先評価
final case class SupplierEvaluation(
  qualityScore: BigDecimal,              // 品質スコア（0-100）
  deliveryComplianceRate: BigDecimal,    // 納期遵守率（0-100%）
  totalTransactionAmount: Money,         // 累計取引額
  lastEvaluationDate: Option[LocalDate] = None
) {
  require(qualityScore >= 0 && qualityScore <= 100, "品質スコアは0-100の範囲である必要があります")
  require(deliveryComplianceRate >= 0 && deliveryComplianceRate <= 100, "納期遵守率は0-100の範囲である必要があります")

  // 総合評価スコアを計算（品質50%、納期遵守率50%）
  def overallScore: BigDecimal = {
    (qualityScore * BigDecimal("0.5")) + (deliveryComplianceRate * BigDecimal("0.5"))
  }

  // 評価ランクを取得
  def evaluationRank: EvaluationRank = {
    overallScore match {
      case score if score >= 95 => EvaluationRank.Excellent
      case score if score >= 85 => EvaluationRank.Good
      case score if score >= 70 => EvaluationRank.Fair
      case _ => EvaluationRank.Poor
    }
  }

  // 評価を更新
  def update(
    qualityScore: BigDecimal,
    deliveryComplianceRate: BigDecimal,
    additionalTransactionAmount: Money
  ): SupplierEvaluation = {
    copy(
      qualityScore = qualityScore,
      deliveryComplianceRate = deliveryComplianceRate,
      totalTransactionAmount = totalTransactionAmount + additionalTransactionAmount,
      lastEvaluationDate = Some(LocalDate.now())
    )
  }
}

// 評価ランク
sealed trait EvaluationRank {
  def description: String
}

object EvaluationRank {
  case object Excellent extends EvaluationRank {
    val description = "優良"
  }

  case object Good extends EvaluationRank {
    val description = "良好"
  }

  case object Fair extends EvaluationRank {
    val description = "普通"
  }

  case object Poor extends EvaluationRank {
    val description = "要改善"
  }
}
```

### 4.1.6 連絡先情報

```scala
package com.example.procurement.domain

// 連絡先情報
final case class ContactInfo(
  postalCode: Option[String],
  address: Option[String],
  phoneNumber: Option[String],
  email: Option[String],
  contactPerson: Option[String]
) {
  // メールアドレスの検証
  def validateEmail: Either[String, ContactInfo] = {
    email match {
      case Some(e) if !e.matches("^[\\w-\\.]+@([\\w-]+\\.)+[\\w-]{2,4}$") =>
        Left(s"無効なメールアドレス: $e")
      case _ =>
        Right(this)
    }
  }

  // 電話番号の検証
  def validatePhoneNumber: Either[String, ContactInfo] = {
    phoneNumber match {
      case Some(p) if !p.matches("^[0-9\\-]+$") =>
        Left(s"無効な電話番号: $p")
      case _ =>
        Right(this)
    }
  }
}
```

## 4.2 PurchaseOrder集約

PurchaseOrder集約は、発注の作成から完了までのライフサイクルを管理します。

### 4.2.1 PurchaseOrder エンティティ

```scala
package com.example.procurement.domain

import java.time.{LocalDate, Instant}

// 発注集約ルート
final case class PurchaseOrder(
  id: PurchaseOrderId,
  tenantId: TenantId,
  supplierId: SupplierId,
  orderNumber: OrderNumber,
  orderDate: LocalDate,
  deliveryDate: LocalDate,
  items: List[PurchaseOrderItem],
  status: PurchaseOrderStatus,
  approvalInfo: Option[ApprovalInfo],
  issuedInfo: Option[IssuedInfo],
  version: Version
) {
  // 小計を計算
  def subtotalAmount: Money = {
    items.map(_.subtotalAmount).foldLeft(Money.zero)(_ + _)
  }

  // 税額を計算
  def taxAmount: Money = {
    items.map(_.taxAmount).foldLeft(Money.zero)(_ + _)
  }

  // 合計金額を計算
  def totalAmount: Money = {
    subtotalAmount + taxAmount
  }

  // 承認申請
  def requestApproval(): Either[PurchaseOrderError, PurchaseOrder] = {
    status match {
      case PurchaseOrderStatus.Draft =>
        Right(copy(
          status = PurchaseOrderStatus.PendingApproval,
          version = version.increment
        ))
      case _ =>
        Left(PurchaseOrderError.InvalidStatusTransition(status, PurchaseOrderStatus.PendingApproval))
    }
  }

  // 承認
  def approve(approverId: UserId): Either[PurchaseOrderError, PurchaseOrder] = {
    status match {
      case PurchaseOrderStatus.PendingApproval =>
        Right(copy(
          status = PurchaseOrderStatus.Approved,
          approvalInfo = Some(ApprovalInfo(
            approverId = approverId,
            approvedAt = Instant.now()
          )),
          version = version.increment
        ))
      case _ =>
        Left(PurchaseOrderError.InvalidStatusTransition(status, PurchaseOrderStatus.Approved))
    }
  }

  // 却下
  def reject(rejecterId: UserId, reason: String): Either[PurchaseOrderError, PurchaseOrder] = {
    status match {
      case PurchaseOrderStatus.PendingApproval =>
        Right(copy(
          status = PurchaseOrderStatus.Rejected,
          approvalInfo = Some(ApprovalInfo(
            approverId = rejecterId,
            approvedAt = Instant.now(),
            rejectionReason = Some(reason)
          )),
          version = version.increment
        ))
      case _ =>
        Left(PurchaseOrderError.InvalidStatusTransition(status, PurchaseOrderStatus.Rejected))
    }
  }

  // 発注書発行
  def issue(issuedBy: UserId): Either[PurchaseOrderError, PurchaseOrder] = {
    status match {
      case PurchaseOrderStatus.Approved =>
        Right(copy(
          status = PurchaseOrderStatus.Issued,
          issuedInfo = Some(IssuedInfo(
            issuedBy = issuedBy,
            issuedAt = Instant.now()
          )),
          version = version.increment
        ))
      case _ =>
        Left(PurchaseOrderError.InvalidStatusTransition(status, PurchaseOrderStatus.Issued))
    }
  }

  // キャンセル
  def cancel(reason: String): Either[PurchaseOrderError, PurchaseOrder] = {
    status match {
      case PurchaseOrderStatus.Draft | PurchaseOrderStatus.PendingApproval | PurchaseOrderStatus.Approved =>
        Right(copy(
          status = PurchaseOrderStatus.Cancelled,
          version = version.increment
        ))
      case _ =>
        Left(PurchaseOrderError.CannotCancelOrder(status))
    }
  }

  // 一部入荷
  def recordPartialReceipt(receivedItems: List[(PurchaseOrderItemId, Quantity)]): Either[PurchaseOrderError, PurchaseOrder] = {
    status match {
      case PurchaseOrderStatus.Issued | PurchaseOrderStatus.PartiallyReceived =>
        // 入荷数量を更新
        val updatedItems = items.map { item =>
          receivedItems.find(_._1 == item.id) match {
            case Some((_, receivedQty)) =>
              item.recordReceipt(receivedQty)
            case None =>
              item
          }
        }

        // 全て入荷済みか確認
        val allReceived = updatedItems.forall(_.isFullyReceived)
        val newStatus = if (allReceived) {
          PurchaseOrderStatus.Completed
        } else {
          PurchaseOrderStatus.PartiallyReceived
        }

        Right(copy(
          items = updatedItems,
          status = newStatus,
          version = version.increment
        ))

      case _ =>
        Left(PurchaseOrderError.CannotRecordReceipt(status))
    }
  }

  // 承認が必要か判定
  def requiresApproval: Boolean = {
    val amount = totalAmount.amount
    amount >= BigDecimal("100000")  // 10万円以上は承認必要
  }

  // 必要な承認者のロールを取得
  def requiredApproverRole: ApproverRole = {
    val amount = totalAmount.amount
    amount match {
      case a if a < 500000 => ApproverRole.Manager       // 50万円未満: 課長
      case a if a < 1000000 => ApproverRole.Director     // 100万円未満: 部長
      case _ => ApproverRole.Executive                   // 100万円以上: 役員
    }
  }
}

// 発注ID
final case class PurchaseOrderId(value: String) extends AnyVal {
  override def toString: String = value
}

object PurchaseOrderId {
  def generate(): PurchaseOrderId = {
    PurchaseOrderId(java.util.UUID.randomUUID().toString)
  }
}

// 発注番号
final case class OrderNumber(value: String) extends AnyVal {
  require(value.nonEmpty, "発注番号は必須です")
}

object OrderNumber {
  def generate(tenantId: TenantId, yearMonth: String, sequence: Int): OrderNumber = {
    OrderNumber(f"PO-$yearMonth-$sequence%06d")
  }
}
```

### 4.2.2 発注ステータス

```scala
package com.example.procurement.domain

// 発注ステータス
sealed trait PurchaseOrderStatus {
  def description: String
  def canTransitionTo(next: PurchaseOrderStatus): Boolean
}

object PurchaseOrderStatus {
  // 下書き
  case object Draft extends PurchaseOrderStatus {
    val description = "下書き"
    def canTransitionTo(next: PurchaseOrderStatus): Boolean = next match {
      case PendingApproval | Cancelled => true
      case _ => false
    }
  }

  // 承認待ち
  case object PendingApproval extends PurchaseOrderStatus {
    val description = "承認待ち"
    def canTransitionTo(next: PurchaseOrderStatus): Boolean = next match {
      case Approved | Rejected | Cancelled => true
      case _ => false
    }
  }

  // 承認済み
  case object Approved extends PurchaseOrderStatus {
    val description = "承認済み"
    def canTransitionTo(next: PurchaseOrderStatus): Boolean = next match {
      case Issued | Cancelled => true
      case _ => false
    }
  }

  // 却下
  case object Rejected extends PurchaseOrderStatus {
    val description = "却下"
    def canTransitionTo(next: PurchaseOrderStatus): Boolean = false
  }

  // 発注済み
  case object Issued extends PurchaseOrderStatus {
    val description = "発注済み"
    def canTransitionTo(next: PurchaseOrderStatus): Boolean = next match {
      case PartiallyReceived | Completed => true
      case _ => false
    }
  }

  // 一部入荷
  case object PartiallyReceived extends PurchaseOrderStatus {
    val description = "一部入荷"
    def canTransitionTo(next: PurchaseOrderStatus): Boolean = next match {
      case Completed => true
      case _ => false
    }
  }

  // 完了
  case object Completed extends PurchaseOrderStatus {
    val description = "完了"
    def canTransitionTo(next: PurchaseOrderStatus): Boolean = false
  }

  // キャンセル
  case object Cancelled extends PurchaseOrderStatus {
    val description = "キャンセル"
    def canTransitionTo(next: PurchaseOrderStatus): Boolean = false
  }

  def fromString(s: String): Option[PurchaseOrderStatus] = s match {
    case "Draft" => Some(Draft)
    case "PendingApproval" => Some(PendingApproval)
    case "Approved" => Some(Approved)
    case "Rejected" => Some(Rejected)
    case "Issued" => Some(Issued)
    case "PartiallyReceived" => Some(PartiallyReceived)
    case "Completed" => Some(Completed)
    case "Cancelled" => Some(Cancelled)
    case _ => None
  }
}
```

### 4.2.3 発注明細

```scala
package com.example.procurement.domain

// 発注明細
final case class PurchaseOrderItem(
  id: PurchaseOrderItemId,
  productId: ProductId,
  productName: String,
  productCode: String,
  orderedQuantity: Quantity,
  receivedQuantity: Quantity,
  acceptedQuantity: Quantity,
  unitPrice: Money,
  taxRate: TaxRate,
  expectedReceivingDate: Option[LocalDate]
) {
  // 小計金額を計算
  def subtotalAmount: Money = {
    Money(unitPrice.amount * orderedQuantity.value)
  }

  // 税額を計算
  def taxAmount: Money = {
    (subtotalAmount * taxRate.rate).round(0)
  }

  // 合計金額を計算
  def totalAmount: Money = {
    subtotalAmount + taxAmount
  }

  // 未入荷数量
  def pendingQuantity: Quantity = {
    Quantity(orderedQuantity.value - receivedQuantity.value)
  }

  // 入荷記録
  def recordReceipt(quantity: Quantity): PurchaseOrderItem = {
    copy(receivedQuantity = Quantity(receivedQuantity.value + quantity.value))
  }

  // 検収記録
  def recordAcceptance(quantity: Quantity): PurchaseOrderItem = {
    copy(acceptedQuantity = Quantity(acceptedQuantity.value + quantity.value))
  }

  // 完全に入荷済みか
  def isFullyReceived: Boolean = {
    receivedQuantity.value >= orderedQuantity.value
  }

  // 完全に検収済みか
  def isFullyAccepted: Boolean = {
    acceptedQuantity.value >= orderedQuantity.value
  }
}

// 発注明細ID
final case class PurchaseOrderItemId(value: String) extends AnyVal {
  override def toString: String = value
}

object PurchaseOrderItemId {
  def generate(): PurchaseOrderItemId = {
    PurchaseOrderItemId(java.util.UUID.randomUUID().toString)
  }
}
```

### 4.2.4 承認情報

```scala
package com.example.procurement.domain

import java.time.Instant

// 承認情報
final case class ApprovalInfo(
  approverId: UserId,
  approvedAt: Instant,
  rejectionReason: Option[String] = None
)

// 承認者ロール
sealed trait ApproverRole {
  def description: String
  def requiredAmount: Money
}

object ApproverRole {
  // 課長
  case object Manager extends ApproverRole {
    val description = "課長"
    val requiredAmount = Money("100000")  // 10万円以上
  }

  // 部長
  case object Director extends ApproverRole {
    val description = "部長"
    val requiredAmount = Money("500000")  // 50万円以上
  }

  // 役員
  case object Executive extends ApproverRole {
    val description = "役員"
    val requiredAmount = Money("1000000")  // 100万円以上
  }
}

// 発注書発行情報
final case class IssuedInfo(
  issuedBy: UserId,
  issuedAt: Instant
)
```

### 4.2.5 発注エラー

```scala
package com.example.procurement.domain

// 発注エラー
sealed trait PurchaseOrderError {
  def message: String
}

object PurchaseOrderError {
  final case class InvalidStatusTransition(
    from: PurchaseOrderStatus,
    to: PurchaseOrderStatus
  ) extends PurchaseOrderError {
    def message: String = s"無効なステータス遷移: ${from.description} -> ${to.description}"
  }

  final case class CannotCancelOrder(
    status: PurchaseOrderStatus
  ) extends PurchaseOrderError {
    def message: String = s"発注をキャンセルできません: 現在のステータス=${status.description}"
  }

  final case class CannotRecordReceipt(
    status: PurchaseOrderStatus
  ) extends PurchaseOrderError {
    def message: String = s"入荷を記録できません: 現在のステータス=${status.description}"
  }

  final case class ItemNotFound(
    itemId: PurchaseOrderItemId
  ) extends PurchaseOrderError {
    def message: String = s"発注明細が見つかりません: ${itemId.value}"
  }
}
```

## 4.3 Receiving集約

Receiving集約は、入荷・検収のプロセスを管理します。

### 4.3.1 Receiving エンティティ

```scala
package com.example.procurement.domain

import java.time.{LocalDate, Instant}

// 入荷集約ルート
final case class Receiving(
  id: ReceivingId,
  tenantId: TenantId,
  purchaseOrderId: PurchaseOrderId,
  warehouseId: WarehouseId,
  receivingNumber: ReceivingNumber,
  receivingDate: LocalDate,
  inspectionDate: Option[LocalDate],
  items: List[ReceivingItem],
  status: ReceivingStatus,
  inspectorId: Option[UserId],
  version: Version
) {
  // 小計を計算
  def subtotalAmount: Money = {
    items.map(_.subtotalAmount).foldLeft(Money.zero)(_ + _)
  }

  // 税額を計算
  def taxAmount: Money = {
    items.map(_.taxAmount).foldLeft(Money.zero)(_ + _)
  }

  // 合計金額を計算
  def totalAmount: Money = {
    subtotalAmount + taxAmount
  }

  // 商品を入荷
  def receiveGoods(
    receivedItems: List[(ProductId, Quantity, Option[LotInfo])]
  ): Either[ReceivingError, Receiving] = {
    status match {
      case ReceivingStatus.Created =>
        val updatedItems = items.map { item =>
          receivedItems.find(_._1 == item.productId) match {
            case Some((_, qty, lotInfo)) =>
              item.recordReceipt(qty, lotInfo)
            case None =>
              item
          }
        }

        Right(copy(
          items = updatedItems,
          status = ReceivingStatus.Received,
          version = version.increment
        ))

      case _ =>
        Left(ReceivingError.InvalidStatusTransition(status, ReceivingStatus.Received))
    }
  }

  // 検収を開始
  def startInspection(inspectorId: UserId): Either[ReceivingError, Receiving] = {
    status match {
      case ReceivingStatus.Received =>
        Right(copy(
          status = ReceivingStatus.Inspecting,
          inspectorId = Some(inspectorId),
          version = version.increment
        ))

      case _ =>
        Left(ReceivingError.InvalidStatusTransition(status, ReceivingStatus.Inspecting))
    }
  }

  // 検収を完了
  def completeInspection(
    inspectedItems: List[(ProductId, Quantity, InspectionResult, Option[String])]
  ): Either[ReceivingError, Receiving] = {
    status match {
      case ReceivingStatus.Inspecting =>
        val updatedItems = items.map { item =>
          inspectedItems.find(_._1 == item.productId) match {
            case Some((_, acceptedQty, result, reason)) =>
              item.recordInspection(acceptedQty, result, reason)
            case None =>
              item
          }
        }

        // 差異があるか確認
        val hasDiscrepancy = updatedItems.exists(_.hasDiscrepancy)
        val newStatus = if (hasDiscrepancy) {
          ReceivingStatus.DiscrepancyDetected
        } else {
          ReceivingStatus.Completed
        }

        Right(copy(
          items = updatedItems,
          status = newStatus,
          inspectionDate = Some(LocalDate.now()),
          version = version.increment
        ))

      case _ =>
        Left(ReceivingError.InvalidStatusTransition(status, ReceivingStatus.Completed))
    }
  }

  // 差異を記録
  def recordDiscrepancy(
    productId: ProductId,
    discrepancyReason: DiscrepancyReason,
    note: String
  ): Either[ReceivingError, Receiving] = {
    items.find(_.productId == productId) match {
      case Some(_) =>
        Right(copy(
          status = ReceivingStatus.DiscrepancyDetected,
          version = version.increment
        ))

      case None =>
        Left(ReceivingError.ItemNotFound(productId))
    }
  }
}

// 入荷ID
final case class ReceivingId(value: String) extends AnyVal {
  override def toString: String = value
}

object ReceivingId {
  def generate(): ReceivingId = {
    ReceivingId(java.util.UUID.randomUUID().toString)
  }
}

// 入荷番号
final case class ReceivingNumber(value: String) extends AnyVal {
  require(value.nonEmpty, "入荷番号は必須です")
}

object ReceivingNumber {
  def generate(tenantId: TenantId, yearMonth: String, sequence: Int): ReceivingNumber = {
    ReceivingNumber(f"RCV-$yearMonth-$sequence%06d")
  }
}
```

### 4.3.2 入荷ステータス

```scala
package com.example.procurement.domain

// 入荷ステータス
sealed trait ReceivingStatus {
  def description: String
}

object ReceivingStatus {
  // 作成済み
  case object Created extends ReceivingStatus {
    val description = "作成済み"
  }

  // 入荷済み
  case object Received extends ReceivingStatus {
    val description = "入荷済み"
  }

  // 検収中
  case object Inspecting extends ReceivingStatus {
    val description = "検収中"
  }

  // 検収完了
  case object Completed extends ReceivingStatus {
    val description = "検収完了"
  }

  // 差異あり
  case object DiscrepancyDetected extends ReceivingStatus {
    val description = "差異あり"
  }

  def fromString(s: String): Option[ReceivingStatus] = s match {
    case "Created" => Some(Created)
    case "Received" => Some(Received)
    case "Inspecting" => Some(Inspecting)
    case "Completed" => Some(Completed)
    case "DiscrepancyDetected" => Some(DiscrepancyDetected)
    case _ => None
  }
}
```

### 4.3.3 入荷明細

```scala
package com.example.procurement.domain

// 入荷明細
final case class ReceivingItem(
  id: ReceivingItemId,
  purchaseOrderItemId: PurchaseOrderItemId,
  productId: ProductId,
  orderedQuantity: Quantity,
  receivedQuantity: Quantity,
  acceptedQuantity: Quantity,
  rejectedQuantity: Quantity,
  unitPrice: Money,
  taxRate: TaxRate,
  lotInfo: Option[LotInfo],
  inspectionResult: Option[InspectionResult],
  discrepancyReason: Option[DiscrepancyReason],
  warehouseZoneId: Option[WarehouseZoneId]
) {
  // 小計金額を計算（検収数量ベース）
  def subtotalAmount: Money = {
    Money(unitPrice.amount * acceptedQuantity.value)
  }

  // 税額を計算
  def taxAmount: Money = {
    (subtotalAmount * taxRate.rate).round(0)
  }

  // 差異数量
  def discrepancyQuantity: Quantity = {
    Quantity((orderedQuantity.value - receivedQuantity.value).abs)
  }

  // 差異があるか
  def hasDiscrepancy: Boolean = {
    orderedQuantity.value != receivedQuantity.value ||
    acceptedQuantity.value != receivedQuantity.value
  }

  // 差異率
  def discrepancyRate: BigDecimal = {
    if (orderedQuantity.value == 0) BigDecimal(0)
    else discrepancyQuantity.value.toDouble / orderedQuantity.value.toDouble
  }

  // 入荷を記録
  def recordReceipt(quantity: Quantity, lotInfo: Option[LotInfo]): ReceivingItem = {
    copy(
      receivedQuantity = quantity,
      lotInfo = lotInfo
    )
  }

  // 検収を記録
  def recordInspection(
    acceptedQty: Quantity,
    result: InspectionResult,
    discrepancyReason: Option[String]
  ): ReceivingItem = {
    val rejectedQty = Quantity(receivedQuantity.value - acceptedQty.value)

    copy(
      acceptedQuantity = acceptedQty,
      rejectedQuantity = rejectedQty,
      inspectionResult = Some(result),
      discrepancyReason = discrepancyReason.flatMap(DiscrepancyReason.fromString)
    )
  }
}

// 入荷明細ID
final case class ReceivingItemId(value: String) extends AnyVal {
  override def toString: String = value
}

object ReceivingItemId {
  def generate(): ReceivingItemId = {
    ReceivingItemId(java.util.UUID.randomUUID().toString)
  }
}
```

### 4.3.4 ロット情報

```scala
package com.example.procurement.domain

import java.time.LocalDate

// ロット情報
final case class LotInfo(
  lotNumber: LotNumber,
  manufacturingDate: Option[LocalDate],
  expiryDate: Option[LocalDate]
) {
  // 賞味期限が切れているか
  def isExpired(today: LocalDate = LocalDate.now()): Boolean = {
    expiryDate.exists(_.isBefore(today))
  }

  // 賞味期限までの日数
  def daysUntilExpiry(today: LocalDate = LocalDate.now()): Option[Long] = {
    expiryDate.map { expiry =>
      java.time.temporal.ChronoUnit.DAYS.between(today, expiry)
    }
  }
}

// ロット番号
final case class LotNumber(value: String) extends AnyVal {
  require(value.nonEmpty, "ロット番号は必須です")
}
```

### 4.3.5 検収結果

```scala
package com.example.procurement.domain

// 検収結果
sealed trait InspectionResult {
  def description: String
}

object InspectionResult {
  // 合格
  case object Accepted extends InspectionResult {
    val description = "合格"
  }

  // 不合格
  case object Rejected extends InspectionResult {
    val description = "不合格"
  }

  // 一部合格
  case object PartiallyAccepted extends InspectionResult {
    val description = "一部合格"
  }

  def fromString(s: String): Option[InspectionResult] = s match {
    case "Accepted" => Some(Accepted)
    case "Rejected" => Some(Rejected)
    case "PartiallyAccepted" => Some(PartiallyAccepted)
    case _ => None
  }
}

// 差異理由
sealed trait DiscrepancyReason {
  def description: String
}

object DiscrepancyReason {
  // 数量不足
  case object ShortShipment extends DiscrepancyReason {
    val description = "数量不足"
  }

  // 数量過多
  case object OverShipment extends DiscrepancyReason {
    val description = "数量過多"
  }

  // 破損
  case object Damaged extends DiscrepancyReason {
    val description = "破損"
  }

  // 不良品
  case object Defective extends DiscrepancyReason {
    val description = "不良品"
  }

  // 誤品
  case object WrongItem extends DiscrepancyReason {
    val description = "誤品"
  }

  // 期限切れ
  case object Expired extends DiscrepancyReason {
    val description = "期限切れ"
  }

  def fromString(s: String): Option[DiscrepancyReason] = s match {
    case "ShortShipment" => Some(ShortShipment)
    case "OverShipment" => Some(OverShipment)
    case "Damaged" => Some(Damaged)
    case "Defective" => Some(Defective)
    case "WrongItem" => Some(WrongItem)
    case "Expired" => Some(Expired)
    case _ => None
  }
}
```

### 4.3.6 入荷エラー

```scala
package com.example.procurement.domain

// 入荷エラー
sealed trait ReceivingError {
  def message: String
}

object ReceivingError {
  final case class InvalidStatusTransition(
    from: ReceivingStatus,
    to: ReceivingStatus
  ) extends ReceivingError {
    def message: String = s"無効なステータス遷移: ${from.description} -> ${to.description}"
  }

  final case class ItemNotFound(
    productId: ProductId
  ) extends ReceivingError {
    def message: String = s"商品が見つかりません: ${productId.value}"
  }

  final case class QuantityMismatch(
    productId: ProductId,
    ordered: Quantity,
    received: Quantity
  ) extends ReceivingError {
    def message: String = s"数量不一致: 商品=${productId.value}, 発注=${ordered.value}, 入荷=${received.value}"
  }
}
```

## 4.4 SupplierPayment集約

SupplierPayment集約は、仕入先への支払と3-way matchingを管理します。

### 4.4.1 SupplierInvoice エンティティ

```scala
package com.example.procurement.domain

import java.time.{LocalDate, Instant}

// 仕入先請求書集約ルート
final case class SupplierInvoice(
  id: SupplierInvoiceId,
  tenantId: TenantId,
  supplierId: SupplierId,
  purchaseOrderId: PurchaseOrderId,
  receivingId: Option[ReceivingId],
  invoiceNumber: InvoiceNumber,
  invoiceDate: LocalDate,
  items: List[SupplierInvoiceItem],
  status: InvoiceStatus,
  matchingResult: Option[ThreeWayMatchingResult],
  paymentDueDate: LocalDate,
  paymentScheduledDate: Option[LocalDate],
  version: Version
) {
  // 小計を計算
  def subtotalAmount: Money = {
    items.map(_.subtotalAmount).foldLeft(Money.zero)(_ + _)
  }

  // 税額を計算
  def taxAmount: Money = {
    items.map(_.taxAmount).foldLeft(Money.zero)(_ + _)
  }

  // 合計金額を計算
  def totalAmount: Money = {
    subtotalAmount + taxAmount
  }

  // 3-way matchingを実行
  def performThreeWayMatching(
    purchaseOrder: PurchaseOrder,
    receiving: Receiving
  ): Either[PaymentError, SupplierInvoice] = {
    status match {
      case InvoiceStatus.Received =>
        val result = ThreeWayMatchingResult.perform(purchaseOrder, receiving, this)

        val newStatus = if (result.isFullMatch) {
          InvoiceStatus.Matched
        } else {
          InvoiceStatus.PartiallyMatched
        }

        Right(copy(
          status = newStatus,
          matchingResult = Some(result),
          version = version.increment
        ))

      case _ =>
        Left(PaymentError.InvalidStatusForMatching(status))
    }
  }

  // 支払予定を設定
  def schedulePayment(paymentDate: LocalDate): Either[PaymentError, SupplierInvoice] = {
    status match {
      case InvoiceStatus.Matched | InvoiceStatus.PartiallyMatched =>
        Right(copy(
          paymentScheduledDate = Some(paymentDate),
          version = version.increment
        ))

      case _ =>
        Left(PaymentError.CannotSchedulePayment(status))
    }
  }

  // 承認
  def approve(approverId: UserId): Either[PaymentError, SupplierInvoice] = {
    status match {
      case InvoiceStatus.Matched | InvoiceStatus.PartiallyMatched =>
        Right(copy(
          status = InvoiceStatus.Approved,
          version = version.increment
        ))

      case _ =>
        Left(PaymentError.CannotApproveInvoice(status))
    }
  }

  // 支払完了を記録
  def recordPayment(paymentId: SupplierPaymentId): Either[PaymentError, SupplierInvoice] = {
    status match {
      case InvoiceStatus.Approved =>
        Right(copy(
          status = InvoiceStatus.Paid,
          version = version.increment
        ))

      case _ =>
        Left(PaymentError.CannotRecordPayment(status))
    }
  }
}

// 仕入先請求書ID
final case class SupplierInvoiceId(value: String) extends AnyVal {
  override def toString: String = value
}

object SupplierInvoiceId {
  def generate(): SupplierInvoiceId = {
    SupplierInvoiceId(java.util.UUID.randomUUID().toString)
  }
}

// 請求書番号
final case class InvoiceNumber(value: String) extends AnyVal {
  require(value.nonEmpty, "請求書番号は必須です")
}
```

### 4.4.2 請求書ステータス

```scala
package com.example.procurement.domain

// 請求書ステータス
sealed trait InvoiceStatus {
  def description: String
}

object InvoiceStatus {
  // 受領済み
  case object Received extends InvoiceStatus {
    val description = "受領済み"
  }

  // 照合完了（完全一致）
  case object Matched extends InvoiceStatus {
    val description = "照合完了"
  }

  // 照合完了（一部不一致）
  case object PartiallyMatched extends InvoiceStatus {
    val description = "一部不一致"
  }

  // 承認済み
  case object Approved extends InvoiceStatus {
    val description = "承認済み"
  }

  // 支払済み
  case object Paid extends InvoiceStatus {
    val description = "支払済み"
  }

  // 却下
  case object Rejected extends InvoiceStatus {
    val description = "却下"
  }

  def fromString(s: String): Option[InvoiceStatus] = s match {
    case "Received" => Some(Received)
    case "Matched" => Some(Matched)
    case "PartiallyMatched" => Some(PartiallyMatched)
    case "Approved" => Some(Approved)
    case "Paid" => Some(Paid)
    case "Rejected" => Some(Rejected)
    case _ => None
  }
}
```

### 4.4.3 請求明細

```scala
package com.example.procurement.domain

// 仕入先請求明細
final case class SupplierInvoiceItem(
  id: SupplierInvoiceItemId,
  purchaseOrderItemId: Option[PurchaseOrderItemId],
  productId: ProductId,
  productName: String,
  productCode: String,
  quantity: Quantity,
  unitPrice: Money,
  taxRate: TaxRate
) {
  // 小計金額を計算
  def subtotalAmount: Money = {
    Money(unitPrice.amount * quantity.value)
  }

  // 税額を計算
  def taxAmount: Money = {
    (subtotalAmount * taxRate.rate).round(0)
  }

  // 合計金額を計算
  def totalAmount: Money = {
    subtotalAmount + taxAmount
  }
}

// 仕入先請求明細ID
final case class SupplierInvoiceItemId(value: String) extends AnyVal {
  override def toString: String = value
}

object SupplierInvoiceItemId {
  def generate(): SupplierInvoiceItemId = {
    SupplierInvoiceItemId(java.util.UUID.randomUUID().toString)
  }
}
```

### 4.4.4 3-way Matching結果

```scala
package com.example.procurement.domain

// 3-way matching結果
final case class ThreeWayMatchingResult(
  purchaseOrderAmount: Money,
  receivingAmount: Money,
  invoiceAmount: Money,
  quantityMatches: Boolean,
  amountMatches: Boolean,
  unitPriceMatches: Boolean,
  discrepancies: List[MatchingDiscrepancy]
) {
  // 完全一致か
  def isFullMatch: Boolean = {
    quantityMatches && amountMatches && unitPriceMatches && discrepancies.isEmpty
  }

  // 許容範囲内の差異か
  def isWithinTolerance(tolerance: Money = Money("100")): Boolean = {
    val diff = (invoiceAmount - receivingAmount).amount.abs
    diff <= tolerance.amount
  }
}

object ThreeWayMatchingResult {
  // 3-way matchingを実行
  def perform(
    purchaseOrder: PurchaseOrder,
    receiving: Receiving,
    invoice: SupplierInvoice
  ): ThreeWayMatchingResult = {
    val poAmount = purchaseOrder.totalAmount
    val rcvAmount = receiving.totalAmount
    val invAmount = invoice.totalAmount

    // 数量の突合
    val quantityMatches = checkQuantityMatches(purchaseOrder, receiving, invoice)

    // 金額の突合
    val amountMatches = checkAmountMatches(rcvAmount, invAmount)

    // 単価の突合
    val unitPriceMatches = checkUnitPriceMatches(purchaseOrder, invoice)

    // 差異の詳細
    val discrepancies = collectDiscrepancies(
      purchaseOrder,
      receiving,
      invoice,
      quantityMatches,
      amountMatches,
      unitPriceMatches
    )

    ThreeWayMatchingResult(
      purchaseOrderAmount = poAmount,
      receivingAmount = rcvAmount,
      invoiceAmount = invAmount,
      quantityMatches = quantityMatches,
      amountMatches = amountMatches,
      unitPriceMatches = unitPriceMatches,
      discrepancies = discrepancies
    )
  }

  private def checkQuantityMatches(
    purchaseOrder: PurchaseOrder,
    receiving: Receiving,
    invoice: SupplierInvoice
  ): Boolean = {
    // 各商品の数量を突合
    invoice.items.forall { invItem =>
      val poItem = purchaseOrder.items.find(_.productId == invItem.productId)
      val rcvItem = receiving.items.find(_.productId == invItem.productId)

      (poItem, rcvItem) match {
        case (Some(po), Some(rcv)) =>
          rcv.acceptedQuantity == invItem.quantity
        case _ =>
          false
      }
    }
  }

  private def checkAmountMatches(
    receivingAmount: Money,
    invoiceAmount: Money
  ): Boolean = {
    val tolerance = Money("100")  // 100円の誤差を許容
    val diff = (invoiceAmount - receivingAmount).amount.abs
    diff <= tolerance.amount
  }

  private def checkUnitPriceMatches(
    purchaseOrder: PurchaseOrder,
    invoice: SupplierInvoice
  ): Boolean = {
    invoice.items.forall { invItem =>
      purchaseOrder.items.find(_.productId == invItem.productId) match {
        case Some(poItem) =>
          poItem.unitPrice == invItem.unitPrice
        case None =>
          false
      }
    }
  }

  private def collectDiscrepancies(
    purchaseOrder: PurchaseOrder,
    receiving: Receiving,
    invoice: SupplierInvoice,
    quantityMatches: Boolean,
    amountMatches: Boolean,
    unitPriceMatches: Boolean
  ): List[MatchingDiscrepancy] = {
    val discrepancies = scala.collection.mutable.ListBuffer.empty[MatchingDiscrepancy]

    if (!quantityMatches) {
      discrepancies += MatchingDiscrepancy.QuantityMismatch(
        "数量が一致しません"
      )
    }

    if (!amountMatches) {
      discrepancies += MatchingDiscrepancy.AmountMismatch(
        expected = receiving.totalAmount,
        actual = invoice.totalAmount
      )
    }

    if (!unitPriceMatches) {
      discrepancies += MatchingDiscrepancy.UnitPriceMismatch(
        "単価が一致しません"
      )
    }

    discrepancies.toList
  }
}

// 突合差異
sealed trait MatchingDiscrepancy {
  def description: String
}

object MatchingDiscrepancy {
  final case class QuantityMismatch(
    description: String
  ) extends MatchingDiscrepancy

  final case class AmountMismatch(
    expected: Money,
    actual: Money
  ) extends MatchingDiscrepancy {
    def description: String = s"金額不一致: 期待値=${expected.amount}, 実際=${actual.amount}"
  }

  final case class UnitPriceMismatch(
    description: String
  ) extends MatchingDiscrepancy
}
```

### 4.4.5 SupplierPayment エンティティ

```scala
package com.example.procurement.domain

import java.time.LocalDate

// 仕入先支払
final case class SupplierPayment(
  id: SupplierPaymentId,
  tenantId: TenantId,
  supplierId: SupplierId,
  supplierInvoiceId: SupplierInvoiceId,
  paymentNumber: PaymentNumber,
  paymentDate: LocalDate,
  paymentAmount: Money,
  paymentMethod: PaymentMethod,
  bankTransferInfo: Option[BankTransferInfo],
  promissoryNoteInfo: Option[PromissoryNoteInfo],
  approvedBy: Option[UserId],
  version: Version
)

// 支払ID
final case class SupplierPaymentId(value: String) extends AnyVal {
  override def toString: String = value
}

object SupplierPaymentId {
  def generate(): SupplierPaymentId = {
    SupplierPaymentId(java.util.UUID.randomUUID().toString)
  }
}

// 支払番号
final case class PaymentNumber(value: String) extends AnyVal {
  require(value.nonEmpty, "支払番号は必須です")
}

// 支払方法
sealed trait PaymentMethod {
  def description: String
}

object PaymentMethod {
  // 銀行振込
  case object BankTransfer extends PaymentMethod {
    val description = "銀行振込"
  }

  // 約束手形
  case object PromissoryNote extends PaymentMethod {
    val description = "約束手形"
  }

  // 現金
  case object Cash extends PaymentMethod {
    val description = "現金"
  }

  def fromString(s: String): Option[PaymentMethod] = s match {
    case "BankTransfer" => Some(BankTransfer)
    case "PromissoryNote" => Some(PromissoryNote)
    case "Cash" => Some(Cash)
    case _ => None
  }
}

// 銀行振込情報
final case class BankTransferInfo(
  bankName: String,
  branchName: String,
  accountType: AccountType,
  accountNumber: String
)

// 口座種別
sealed trait AccountType {
  def description: String
}

object AccountType {
  case object Ordinary extends AccountType {
    val description = "普通"
  }

  case object Current extends AccountType {
    val description = "当座"
  }

  case object Savings extends AccountType {
    val description = "貯蓄"
  }
}

// 約束手形情報
final case class PromissoryNoteInfo(
  noteNumber: String,
  noteDueDate: LocalDate
)
```

### 4.4.6 支払エラー

```scala
package com.example.procurement.domain

// 支払エラー
sealed trait PaymentError {
  def message: String
}

object PaymentError {
  final case class InvalidStatusForMatching(
    status: InvoiceStatus
  ) extends PaymentError {
    def message: String = s"突合を実行できません: 現在のステータス=${status.description}"
  }

  final case class CannotSchedulePayment(
    status: InvoiceStatus
  ) extends PaymentError {
    def message: String = s"支払予定を設定できません: 現在のステータス=${status.description}"
  }

  final case class CannotApproveInvoice(
    status: InvoiceStatus
  ) extends PaymentError {
    def message: String = s"請求書を承認できません: 現在のステータス=${status.description}"
  }

  final case class CannotRecordPayment(
    status: InvoiceStatus
  ) extends PaymentError {
    def message: String = s"支払を記録できません: 現在のステータス=${status.description}"
  }

  final case class ThreeWayMatchingFailed(
    discrepancies: List[MatchingDiscrepancy]
  ) extends PaymentError {
    def message: String = s"3-way matching失敗: ${discrepancies.map(_.description).mkString(", ")}"
  }
}
```

## 4.5 まとめ

本章では、発注管理システムの4つの集約を詳細に設計しました。

**Supplier集約**:
- 仕入先情報と評価の管理
- 支払条件、リードタイムの計算
- 仕入先評価（品質スコア、納期遵守率）

**PurchaseOrder集約**:
- 発注のライフサイクル管理（下書き → 承認 → 発行 → 完了）
- 承認ワークフロー（金額に応じた承認者）
- 入荷記録と発注完了判定

**Receiving集約**:
- 入荷・検収のプロセス管理
- 差異検出（数量、品質）
- ロット情報の追跡

**SupplierPayment集約**:
- 3-way matching（発注・入荷・請求の突合）
- 支払予定の管理
- 複数の支払方法（銀行振込、手形、現金）

次章では、これらの集約を組み合わせた複数集約の実装を行います。PurchaseOrderActor、ReceivingActor、SupplierPaymentActorの詳細な実装を見ていきます。
