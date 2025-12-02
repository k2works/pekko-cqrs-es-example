# 【第5部 第1章】イントロダクション：発注管理サービスの要件定義

## 本章の目的

第5部では、第3部で構築した在庫管理システム、第4部で追加した受注管理システムに、**発注管理機能**を追加します。これにより、調達から販売までの一連のサプライチェーンが完成し、エンドツーエンドのビジネスプロセスを実現します。

本章では、発注管理サービスのビジネス要件と技術的課題を明確にし、システム設計の基礎を固めます。

## 1.1 第3部・第4部の振り返り

まず、これまでに構築してきたシステムを振り返り、今回追加する発注管理機能との関係を整理します。

### 1.1.1 在庫管理システム（第3部）

第3部では、卸売事業者D社の在庫管理システムを構築しました。

**構築した集約**:
- **Product集約**: 商品情報の管理
- **Warehouse集約**: 倉庫情報の管理
- **WarehouseZone集約**: 保管区画の管理
- **Inventory集約**: 在庫残高の管理
- **Customer集約**: 取引先情報の管理

**主要機能**:
- 在庫受払処理: 1日約2,000件の入出庫トランザクション
- 在庫引当機能: 受注時の在庫確保
- ロット管理: 賞味期限、製造日の追跡
- 保管条件管理: 常温、冷蔵、冷凍の区分
- 在庫照会: GraphQLによる柔軟なクエリAPI

**在庫フロー**:
```
入荷（発注管理から連携）→ 在庫計上 → 在庫引当（受注管理へ連携）→ 出荷 → 在庫減少
```

### 1.1.2 受注管理システム（第4部）

第4部では、月間50,000件の注文を処理する受注管理システムを構築しました。

**構築した集約**:
- **Quotation集約**: 見積もり管理
- **Order集約**: 注文管理
- **CreditLimit集約**: 与信限度額管理
- **Invoice集約**: 請求書管理

**主要機能**:
- 受注フロー: 見積もり → 注文 → 与信チェック → 在庫引当 → 出荷 → 請求 → 入金
- Sagaパターン: 分散トランザクションの調整
- 金額計算: BigDecimalによる正確な税金計算
- 与信管理: 動的な与信限度額調整
- 請求管理: 月次締めと入金催促

**受注フロー**:
```
見積もり → 注文作成 → 与信チェック → 在庫引当 → 注文確定 → 出荷 → 配送 → 請求 → 入金
```

### 1.1.3 今回追加する発注管理機能

第5部では、在庫を補充するための発注管理機能を追加します。これにより、「仕入れ → 在庫 → 販売」の完全なサプライチェーンが実現します。

**追加する集約**:
- **Supplier集約**: 仕入先情報の管理
- **PurchaseOrder集約**: 発注情報の管理
- **Receiving集約**: 入荷・検収の管理
- **SupplierInvoice集約**: 仕入先請求書の管理
- **SupplierPayment集約**: 仕入先への支払管理

**主要機能**:
- 発注処理: 発注計画 → 承認 → 発注書発行
- 入荷管理: 入荷予定 → 入荷検収 → 在庫計上
- 3-way matching: 発注・入荷・請求の突合
- 支払管理: 月末締め翌月末払い
- 自動発注: 在庫レベルに基づく自動発注

**発注フロー**:
```
発注計画 → 発注承認 → 発注書発行 → 入荷 → 検収 → 在庫計上 → 請求書照合 → 支払
```

### 1.1.4 3つのコンテキストの連携

発注管理、在庫管理、受注管理の3つのコンテキストは、イベント駆動で連携します。

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  発注管理       │     │  在庫管理       │     │  受注管理       │
│  Procurement    │────▶│  Inventory      │────▶│  Sales          │
└─────────────────┘     └─────────────────┘     └─────────────────┘
       │                        │                        │
       │ ReceivingCompleted     │ StockReserved         │ OrderConfirmed
       │ イベント               │ イベント              │ イベント
       ▼                        ▼                        ▼
    在庫増加                 在庫引当                 在庫減少
```

**イベント連携の例**:

1. **発注管理 → 在庫管理**
   - `ReceivingCompleted`イベント: 入荷検収完了 → 在庫増加
   - 入荷した商品が自動的に在庫に計上される

2. **受注管理 → 在庫管理**
   - `StockReservationRequested`コマンド: 注文確定 → 在庫引当
   - 注文に対して在庫を確保する

3. **在庫管理 → 発注管理**
   - `StockLevelBelowReorderPoint`イベント: 在庫不足 → 自動発注
   - 在庫が発注点を下回ったら自動的に発注を作成

## 1.2 卸売事業者D社の発注業務

D社の発注業務の現状と要件を詳しく見ていきます。

### 1.2.1 事業規模

**仕入先の規模**:
- 仕入先数: 約200社
- 大手メーカー: 30社（15%）
- 中堅メーカー: 100社（50%）
- 小規模事業者: 70社（35%）

**取引規模**:
- 月間発注件数: 約3,000件
- 月間仕入金額: 約10億円（年間120億円）
- 1件あたり平均発注金額: 333,000円
- 1日あたり平均発注件数: 約100件（営業日ベース）

**発注パターン**:
```scala
// 発注規模の分布
val orderDistribution = Map(
  "小口発注（10万円未満）" -> 1200,      // 40% - 小規模・スポット発注
  "通常発注（10万〜50万円）" -> 1350,    // 45% - 定期発注
  "大口発注（50万円以上）" -> 450        // 15% - まとめ発注
)

// 仕入先タイプ別の発注頻度
val supplierOrderFrequency = Map(
  "大手メーカー" -> "週1〜2回",          // 定期発注が中心
  "中堅メーカー" -> "週1回または隔週",   // 中量発注
  "小規模事業者" -> "月1〜2回"           // スポット発注
)
```

### 1.2.2 発注フロー

D社の発注業務は、以下の9ステップで構成されます。

**1. 発注計画**

在庫レベル、需要予測、リードタイムを考慮して発注計画を立案します。

```scala
// 発注計画の判断材料
case class PurchaseOrderPlanning(
  currentStock: Quantity,          // 現在庫
  reservedStock: Quantity,         // 引当済み在庫
  reorderPoint: Quantity,          // 発注点
  economicOrderQuantity: Quantity, // 経済的発注量（EOQ）
  leadTimeDays: Int,               // リードタイム
  forecastDemand: Quantity,        // 需要予測
  averageDailyDemand: Quantity     // 平均日次需要
) {
  // 利用可能在庫
  def availableStock: Quantity = currentStock - reservedStock

  // 発注が必要か判定
  def shouldOrder: Boolean = {
    // 利用可能在庫が発注点を下回った場合
    availableStock.value <= reorderPoint.value
  }

  // 推奨発注数量を計算
  def recommendedOrderQuantity: Quantity = {
    if (!shouldOrder) {
      Quantity(0)
    } else {
      // リードタイム中の需要 + 安全在庫 - 利用可能在庫
      val leadTimeDemand = Quantity(averageDailyDemand.value * leadTimeDays)
      val safetyStock = Quantity((averageDailyDemand.value * leadTimeDays * 0.5).toInt)
      val requiredQuantity = leadTimeDemand + safetyStock - availableStock

      // 経済的発注量の倍数に丸める
      val multiplier = Math.ceil(requiredQuantity.value.toDouble / economicOrderQuantity.value).toInt
      Quantity(economicOrderQuantity.value * multiplier)
    }
  }
}
```

**2. 発注承認**

金額に応じた承認ワークフローを実施します。

```scala
// 承認ルール
sealed trait ApprovalRule {
  def requiredApprovers: List[ApproverRole]
  def description: String
}

object ApprovalRule {
  // 10万円未満: 自動承認
  case object AutoApproval extends ApprovalRule {
    val requiredApprovers = List.empty[ApproverRole]
    val description = "10万円未満は自動承認"
  }

  // 10万円以上50万円未満: 課長承認
  case object ManagerApproval extends ApprovalRule {
    val requiredApprovers = List(ApproverRole.Manager)
    val description = "10万円以上50万円未満は課長承認"
  }

  // 50万円以上100万円未満: 部長承認
  case object DirectorApproval extends ApprovalRule {
    val requiredApprovers = List(ApproverRole.Manager, ApproverRole.Director)
    val description = "50万円以上100万円未満は部長承認"
  }

  // 100万円以上: 役員承認
  case object ExecutiveApproval extends ApprovalRule {
    val requiredApprovers = List(ApproverRole.Manager, ApproverRole.Director, ApproverRole.Executive)
    val description = "100万円以上は役員承認"
  }

  def fromAmount(amount: Money): ApprovalRule = {
    amount.amount match {
      case a if a < 100000 => AutoApproval
      case a if a < 500000 => ManagerApproval
      case a if a < 1000000 => DirectorApproval
      case _ => ExecutiveApproval
    }
  }
}

sealed trait ApproverRole
object ApproverRole {
  case object Manager extends ApproverRole    // 課長
  case object Director extends ApproverRole   // 部長
  case object Executive extends ApproverRole  // 役員
}
```

**3. 発注書発行**

承認完了後、仕入先へ発注書を送付します。

**4. 入荷予定管理**

発注から入荷までの期間を管理します。

```scala
// 入荷予定の計算
case class ExpectedReceivingDate(
  orderDate: LocalDate,      // 発注日
  leadTimeDays: Int,         // リードタイム
  isBusinessDaysOnly: Boolean // 営業日のみカウントするか
) {
  def calculate: LocalDate = {
    if (isBusinessDaysOnly) {
      addBusinessDays(orderDate, leadTimeDays)
    } else {
      orderDate.plusDays(leadTimeDays)
    }
  }

  private def addBusinessDays(start: LocalDate, days: Int): LocalDate = {
    var current = start
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

**5. 入荷検収**

入荷した商品の数量・品質をチェックします。

```scala
// 検収結果
sealed trait InspectionResult
object InspectionResult {
  case object Accepted extends InspectionResult           // 合格
  case object Rejected extends InspectionResult           // 不合格
  case object PartiallyAccepted extends InspectionResult  // 一部合格
}

// 差異理由
sealed trait DiscrepancyReason
object DiscrepancyReason {
  case object ShortShipment extends DiscrepancyReason   // 数量不足
  case object OverShipment extends DiscrepancyReason    // 数量過多
  case object Damaged extends DiscrepancyReason         // 破損
  case object Defective extends DiscrepancyReason       // 不良品
  case object WrongItem extends DiscrepancyReason       // 誤品
  case object Expired extends DiscrepancyReason         // 期限切れ
}
```

**6. 在庫計上**

検収完了後、在庫へ反映します。

**7. 請求書受領**

仕入先から請求書を受領します。

**8. 3-way matching**

発注、入荷、請求の3つの情報を突合します。

```scala
// 3-way matchingの検証
case class ThreeWayMatching(
  purchaseOrder: PurchaseOrder,        // 発注情報
  receiving: Receiving,                // 入荷情報
  supplierInvoice: SupplierInvoice     // 請求書情報
) {
  // 数量の突合
  def quantityMatches: Boolean = {
    purchaseOrder.items.forall { poItem =>
      val receivedItem = receiving.items.find(_.productId == poItem.productId)
      val invoiceItem = supplierInvoice.items.find(_.productId == poItem.productId)

      (receivedItem, invoiceItem) match {
        case (Some(ri), Some(ii)) =>
          ri.acceptedQuantity == ii.quantity
        case _ =>
          false
      }
    }
  }

  // 金額の突合
  def amountMatches: Boolean = {
    val tolerance = Money(100)  // 100円の誤差を許容
    val diff = (supplierInvoice.totalAmount - receiving.totalAmount).amount.abs
    diff <= tolerance.amount
  }

  // 単価の突合
  def unitPriceMatches: Boolean = {
    purchaseOrder.items.forall { poItem =>
      val invoiceItem = supplierInvoice.items.find(_.productId == poItem.productId)
      invoiceItem.exists(_.unitPrice == poItem.unitPrice)
    }
  }

  // すべての項目が一致するか
  def isFullMatch: Boolean = {
    quantityMatches && amountMatches && unitPriceMatches
  }

  // 差異の詳細
  def discrepancies: List[MatchingDiscrepancy] = {
    val discrepancies = scala.collection.mutable.ListBuffer.empty[MatchingDiscrepancy]

    if (!quantityMatches) {
      discrepancies += MatchingDiscrepancy.QuantityMismatch
    }
    if (!amountMatches) {
      discrepancies += MatchingDiscrepancy.AmountMismatch
    }
    if (!unitPriceMatches) {
      discrepancies += MatchingDiscrepancy.UnitPriceMismatch
    }

    discrepancies.toList
  }
}

sealed trait MatchingDiscrepancy
object MatchingDiscrepancy {
  case object QuantityMismatch extends MatchingDiscrepancy    // 数量不一致
  case object AmountMismatch extends MatchingDiscrepancy      // 金額不一致
  case object UnitPriceMismatch extends MatchingDiscrepancy   // 単価不一致
}
```

**9. 支払処理**

月末締め翌月末払いで仕入先へ支払います。

```scala
// 支払スケジュール
case class PaymentSchedule(
  closingDay: Int,           // 締日（月の何日か）
  paymentDay: Int,           // 支払日（月の何日か）
  paymentTermDays: Int       // 支払サイト（締日から何日後か）
) {
  def calculatePaymentDate(invoiceDate: LocalDate): LocalDate = {
    // 締日を計算
    val closingDate = if (invoiceDate.getDayOfMonth <= closingDay) {
      invoiceDate.withDayOfMonth(closingDay)
    } else {
      invoiceDate.plusMonths(1).withDayOfMonth(closingDay)
    }

    // 支払日を計算
    closingDate.plusDays(paymentTermDays)
  }
}

// 仕入先タイプ別の支払条件
object PaymentScheduleBySupplierType {
  val majorManufacturer = PaymentSchedule(
    closingDay = 31,         // 月末締め
    paymentDay = 31,         // 月末払い
    paymentTermDays = 60     // 60日後
  )

  val midSizeManufacturer = PaymentSchedule(
    closingDay = 31,
    paymentDay = 31,
    paymentTermDays = 45     // 45日後
  )

  val smallBusiness = PaymentSchedule(
    closingDay = 31,
    paymentDay = 31,
    paymentTermDays = 30     // 30日後
  )
}
```

### 1.2.3 仕入先タイプ

D社は、200社の仕入先を3つのタイプに分類して管理しています。

**大手メーカー（30社、15%）**:
- 月間取引額: 5,000万円以上
- 支払条件: 月末締め翌々月末払い（60日サイト）
- 品質: 安定した品質、返品率1%未満
- リードタイム: 7〜14日
- 納期遵守率: 95%以上
- 特徴: 定期発注が中心、大量仕入によるコスト削減

```scala
case class MajorManufacturer(
  supplierId: SupplierId,
  name: String,
  monthlyTransactionAmount: Money,  // 平均5,000万円以上
  paymentTermDays: Int = 60,
  qualityScore: BigDecimal,         // 90点以上
  leadTimeDays: Int,                // 7〜14日
  deliveryComplianceRate: BigDecimal // 95%以上
)
```

**中堅メーカー（100社、50%）**:
- 月間取引額: 500万円〜5,000万円
- 支払条件: 月末締め翌月15日払い（45日サイト）
- 品質: 良好、返品率3%未満
- リードタイム: 10〜21日
- 納期遵守率: 90%以上
- 特徴: 週1回または隔週の定期発注

```scala
case class MidSizeManufacturer(
  supplierId: SupplierId,
  name: String,
  monthlyTransactionAmount: Money,  // 500万〜5,000万円
  paymentTermDays: Int = 45,
  qualityScore: BigDecimal,         // 80点以上
  leadTimeDays: Int,                // 10〜21日
  deliveryComplianceRate: BigDecimal // 90%以上
)
```

**小規模事業者（70社、35%）**:
- 月間取引額: 500万円未満
- 支払条件: 月末締め翌月末払い（30日サイト）
- 品質: 一般的、返品率5%未満
- リードタイム: 14〜30日
- 納期遵守率: 85%以上
- 特徴: スポット発注が中心、柔軟な対応

```scala
case class SmallBusiness(
  supplierId: SupplierId,
  name: String,
  monthlyTransactionAmount: Money,  // 500万円未満
  paymentTermDays: Int = 30,
  qualityScore: BigDecimal,         // 70点以上
  leadTimeDays: Int,                // 14〜30日
  deliveryComplianceRate: BigDecimal // 85%以上
)
```

**仕入先タイプの判定**:

```scala
sealed trait SupplierType {
  def paymentTermDays: Int
  def description: String
}

object SupplierType {
  case object MajorManufacturer extends SupplierType {
    val paymentTermDays = 60
    val description = "大手メーカー"
  }

  case object MidSizeManufacturer extends SupplierType {
    val paymentTermDays = 45
    val description = "中堅メーカー"
  }

  case object SmallBusiness extends SupplierType {
    val paymentTermDays = 30
    val description = "小規模事業者"
  }

  def fromMonthlyTransactionAmount(amount: Money): SupplierType = {
    amount.amount match {
      case a if a >= 50000000 => MajorManufacturer   // 5,000万円以上
      case a if a >= 5000000 => MidSizeManufacturer  // 500万円以上
      case _ => SmallBusiness
    }
  }
}
```

### 1.2.4 発注業務の課題

現在、D社の発注業務には以下の課題があります。

**1. 手作業による非効率**:
- 発注計画の立案に時間がかかる（1件あたり平均10分）
- 月間3,000件 × 10分 = 500時間の工数

**2. 承認フローの遅延**:
- 承認待ちによる発注遅延（平均2日）
- 在庫切れリスクの増加

**3. 入荷差異の対応**:
- 数量差異、破損品への対応に時間がかかる
- 月間約150件（5%）の差異が発生

**4. 3-way matchingの手作業**:
- 発注・入荷・請求の突合に時間がかかる（1件あたり15分）
- 月間3,000件 × 15分 = 750時間の工数

**5. 支払ミス**:
- 手作業による支払金額の誤り（月間約10件）
- 仕入先との信頼関係に影響

これらの課題を解決するため、発注管理システムを構築します。

## 1.3 技術的課題

発注管理システムを構築するにあたり、以下の技術的課題を解決する必要があります。

### 1.3.1 分散トランザクション（発注Saga）

発注業務は、複数の集約（PurchaseOrder、Receiving、Inventory、SupplierPayment）にまたがるため、Sagaパターンによる分散トランザクション管理が必要です。

**発注Sagaのステップ**:

```
1. 発注承認（PurchaseOrderSaga）
2. 発注確定（PurchaseOrderSaga）
3. 入荷検収（ReceivingSaga）
4. 在庫計上（InventorySaga）
5. 3-way matching（PaymentSaga）
6. 支払処理（PaymentSaga）
```

**補償トランザクション**:

各ステップで失敗が発生した場合、以下の補償処理を実行します。

```scala
// 補償処理の定義
sealed trait CompensationAction {
  def step: String
  def action: String
}

object CompensationAction {
  // C1: 発注キャンセル
  case object CancelPurchaseOrder extends CompensationAction {
    val step = "発注確定"
    val action = "発注をキャンセル"
  }

  // C2: 入荷取り消し
  case object CancelReceiving extends CompensationAction {
    val step = "入荷検収"
    val action = "入荷記録を取り消し"
  }

  // C3: 在庫計上取り消し
  case object ReverseInventoryPosting extends CompensationAction {
    val step = "在庫計上"
    val action = "在庫増加を取り消し"
  }

  // C4: 請求書照合取り消し
  case object CancelInvoiceMatching extends CompensationAction {
    val step = "3-way matching"
    val action = "照合結果を取り消し"
  }
}
```

**Sagaの実装パターン**:

```scala
package com.example.procurement.adapter.saga

import com.example.procurement.domain._
import org.apache.pekko.actor.typed._
import org.apache.pekko.actor.typed.scaladsl._
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl._
import java.time.Instant

// 発注Sagaのオーケストレーター
object PurchaseOrderSagaOrchestrator {

  sealed trait Command
  final case class StartSaga(purchaseOrderId: PurchaseOrderId) extends Command
  final case class ApprovalCompleted(purchaseOrderId: PurchaseOrderId) extends Command
  final case class ApprovalFailed(purchaseOrderId: PurchaseOrderId, reason: String) extends Command
  final case class OrderIssued(purchaseOrderId: PurchaseOrderId) extends Command
  final case class ReceivingCompleted(purchaseOrderId: PurchaseOrderId) extends Command
  final case class InventoryPosted(purchaseOrderId: PurchaseOrderId) extends Command
  final case class MatchingCompleted(purchaseOrderId: PurchaseOrderId) extends Command
  final case class PaymentProcessed(purchaseOrderId: PurchaseOrderId) extends Command

  sealed trait Event
  final case class SagaStarted(purchaseOrderId: PurchaseOrderId, occurredAt: Instant = Instant.now()) extends Event
  final case class StepCompleted(step: SagaStep, occurredAt: Instant = Instant.now()) extends Event
  final case class StepFailed(step: SagaStep, reason: String, occurredAt: Instant = Instant.now()) extends Event
  final case class SagaCompleted(purchaseOrderId: PurchaseOrderId, occurredAt: Instant = Instant.now()) extends Event
  final case class SagaFailed(purchaseOrderId: PurchaseOrderId, reason: String, occurredAt: Instant = Instant.now()) extends Event

  sealed trait SagaStep
  object SagaStep {
    case object ApprovalRequested extends SagaStep
    case object OrderIssued extends SagaStep
    case object ReceivingCompleted extends SagaStep
    case object InventoryPosted extends SagaStep
    case object MatchingCompleted extends SagaStep
    case object PaymentProcessed extends SagaStep
  }

  sealed trait State
  case object Idle extends State
  final case class InProgress(
    purchaseOrderId: PurchaseOrderId,
    currentStep: SagaStep,
    completedSteps: List[SagaStep]
  ) extends State
  final case class Completed(purchaseOrderId: PurchaseOrderId) extends State
  final case class Failed(purchaseOrderId: PurchaseOrderId, reason: String) extends State

  def apply(sagaId: SagaId): Behavior[Command] = {
    Behaviors.setup { context =>
      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId.ofUniqueId(sagaId.value),
        emptyState = Idle,
        commandHandler = commandHandler(context),
        eventHandler = eventHandler
      )
    }
  }

  private def commandHandler(
    context: ActorContext[Command]
  ): (State, Command) => Effect[Event, State] = {
    case (Idle, StartSaga(purchaseOrderId)) =>
      Effect.persist(SagaStarted(purchaseOrderId))
        .thenRun { _ =>
          context.log.info(s"Starting purchase order saga for ${purchaseOrderId.value}")
        }

    case (state: InProgress, ApprovalCompleted(purchaseOrderId)) =>
      Effect.persist(StepCompleted(SagaStep.ApprovalRequested))
        .thenRun { _ =>
          context.log.info(s"Approval completed for ${purchaseOrderId.value}")
        }

    case (state: InProgress, ApprovalFailed(purchaseOrderId, reason)) =>
      Effect.persist(StepFailed(SagaStep.ApprovalRequested, reason))
        .thenPersist(SagaFailed(purchaseOrderId, reason))
        .thenRun { _ =>
          context.log.warn(s"Approval failed for ${purchaseOrderId.value}: $reason")
        }

    case _ =>
      Effect.none
  }

  private def eventHandler: (State, Event) => State = {
    case (Idle, SagaStarted(purchaseOrderId, _)) =>
      InProgress(
        purchaseOrderId = purchaseOrderId,
        currentStep = SagaStep.ApprovalRequested,
        completedSteps = List.empty
      )

    case (state: InProgress, StepCompleted(step, _)) =>
      state.copy(
        currentStep = step,
        completedSteps = state.completedSteps :+ step
      )

    case (_, SagaCompleted(purchaseOrderId, _)) =>
      Completed(purchaseOrderId)

    case (_, SagaFailed(purchaseOrderId, reason, _)) =>
      Failed(purchaseOrderId, reason)

    case (state, _) =>
      state
  }
}
```

### 1.3.2 在庫管理との連携

入荷検収完了時に、自動的に在庫を増加させる必要があります。

**イベント駆動連携**:

```scala
package com.example.procurement.adapter.integration

import com.example.procurement.domain._
import com.example.inventory.adapter.actor.InventoryActor
import org.apache.pekko.persistence.query._
import org.apache.pekko.stream.scaladsl._
import zio._

// 入荷完了イベント → 在庫増加
class ReceivingCompletedProjection(
  eventsByTagQuery: EventsByTagQuery,
  inventoryActor: ActorRef[InventoryActor.Command]
) {

  def start(): Task[Unit] = {
    ZIO.attemptBlocking {
      eventsByTagQuery
        .eventsByTag("receiving-completed", NoOffset)
        .mapAsync(parallelism = 4) { envelope =>
          envelope.event match {
            case ReceivingActor.InspectionCompleted(receivingId, items, _) =>
              processInventoryIncrease(items).runToFuture
            case _ =>
              Future.successful(())
          }
        }
        .runForeach { _ => () }
    }
  }

  private def processInventoryIncrease(
    items: List[ReceivingItem]
  ): Task[Unit] = {
    ZIO.foreach(items) { item =>
      ZIO.attemptBlocking {
        inventoryActor ! InventoryActor.IncreaseStock(
          productId = item.productId,
          quantity = item.acceptedQuantity,
          warehouseId = item.warehouseId,
          zoneId = item.zoneId,
          lotNumber = item.lotNumber,
          replyTo = ???
        )
      }
    }
  }
}
```

**発注残の管理**:

```scala
// 発注残（未入荷数量）の追跡
case class PurchaseOrderItem(
  purchaseOrderItemId: PurchaseOrderItemId,
  purchaseOrderId: PurchaseOrderId,
  productId: ProductId,
  orderedQuantity: Quantity,      // 発注数量
  receivedQuantity: Quantity,     // 入荷済み数量
  acceptedQuantity: Quantity,     // 検収済み数量
  unitPrice: Money
) {
  // 未入荷数量
  def pendingQuantity: Quantity = {
    Quantity(orderedQuantity.value - receivedQuantity.value)
  }

  // 入荷完了したか
  def isFullyReceived: Boolean = {
    receivedQuantity.value >= orderedQuantity.value
  }

  // 検収完了したか
  def isFullyAccepted: Boolean = {
    acceptedQuantity.value >= orderedQuantity.value
  }
}
```

**在庫レベルによる自動発注**:

```scala
package com.example.procurement.usecase

import com.example.inventory.domain._
import com.example.procurement.domain._
import zio._

// 自動発注判定サービス
class AutomaticPurchaseOrderService(
  inventoryRepository: InventoryRepository,
  productRepository: ProductRepository,
  purchaseOrderActor: ActorRef[PurchaseOrderActor.Command]
) {

  def checkAndCreateOrders(): Task[Unit] = {
    for {
      // 全商品の在庫レベルをチェック
      products <- productRepository.findAll()

      // 発注が必要な商品を抽出
      needsOrdering <- ZIO.filter(products) { product =>
        checkReorderPoint(product)
      }

      // 発注を作成
      _ <- ZIO.foreach(needsOrdering) { product =>
        createPurchaseOrder(product)
      }

      _ <- ZIO.logInfo(s"Created ${needsOrdering.size} automatic purchase orders")
    } yield ()
  }

  private def checkReorderPoint(product: Product): Task[Boolean] = {
    for {
      inventory <- inventoryRepository.findByProduct(product.id)

      // 利用可能在庫が発注点を下回っているか
      result = inventory.availableQuantity.value <= product.reorderPoint.value
    } yield result
  }

  private def createPurchaseOrder(product: Product): Task[Unit] = {
    for {
      // 推奨発注数量を計算
      orderQuantity <- calculateOrderQuantity(product)

      // 発注を作成
      _ <- ZIO.attemptBlocking {
        purchaseOrderActor ! PurchaseOrderActor.CreatePurchaseOrder(
          supplierId = product.preferredSupplierId,
          items = List(
            PurchaseOrderItemInput(
              productId = product.id,
              quantity = orderQuantity,
              unitPrice = product.purchasePrice
            )
          ),
          replyTo = ???
        )
      }

      _ <- ZIO.logInfo(s"Created automatic purchase order for product ${product.id.value}")
    } yield ()
  }

  private def calculateOrderQuantity(product: Product): Task[Quantity] = {
    for {
      inventory <- inventoryRepository.findByProduct(product.id)

      // EOQ（経済的発注量）または最小発注量を使用
      orderQuantity = product.economicOrderQuantity.getOrElse(
        product.minimumOrderQuantity
      )
    } yield orderQuantity
  }
}
```

### 1.3.3 3-way matching

発注、入荷、請求の3つの情報を突合し、差異を検出します。

**突合処理のフロー**:

```scala
package com.example.procurement.usecase

import com.example.procurement.domain._
import zio._

// 3-way matching サービス
class ThreeWayMatchingService(
  purchaseOrderRepository: PurchaseOrderRepository,
  receivingRepository: ReceivingRepository,
  supplierInvoiceRepository: SupplierInvoiceRepository
) {

  def performMatching(
    supplierInvoiceId: SupplierInvoiceId
  ): Task[Either[MatchingError, MatchingResult]] = {
    for {
      // 請求書を取得
      invoice <- supplierInvoiceRepository.findById(supplierInvoiceId)
        .someOrFail(MatchingError.InvoiceNotFound(supplierInvoiceId))

      // 関連する発注を取得
      purchaseOrder <- purchaseOrderRepository.findById(invoice.purchaseOrderId)
        .someOrFail(MatchingError.PurchaseOrderNotFound(invoice.purchaseOrderId))

      // 関連する入荷を取得
      receiving <- receivingRepository.findByPurchaseOrderId(invoice.purchaseOrderId)
        .someOrFail(MatchingError.ReceivingNotFound(invoice.purchaseOrderId))

      // 突合を実行
      matching = ThreeWayMatching(purchaseOrder, receiving, invoice)

      result <- if (matching.isFullMatch) {
        // 完全一致
        ZIO.succeed(Right(MatchingResult.FullMatch(
          purchaseOrderId = purchaseOrder.id,
          receivingId = receiving.id,
          supplierInvoiceId = invoice.id
        )))
      } else {
        // 差異あり
        ZIO.succeed(Right(MatchingResult.PartialMatch(
          purchaseOrderId = purchaseOrder.id,
          receivingId = receiving.id,
          supplierInvoiceId = invoice.id,
          discrepancies = matching.discrepancies
        )))
      }
    } yield result
  }
}

sealed trait MatchingError {
  def message: String
}

object MatchingError {
  final case class InvoiceNotFound(id: SupplierInvoiceId) extends MatchingError {
    def message: String = s"請求書が見つかりません: ${id.value}"
  }

  final case class PurchaseOrderNotFound(id: PurchaseOrderId) extends MatchingError {
    def message: String = s"発注が見つかりません: ${id.value}"
  }

  final case class ReceivingNotFound(id: PurchaseOrderId) extends MatchingError {
    def message: String = s"入荷記録が見つかりません（発注ID: ${id.value}）"
  }
}

sealed trait MatchingResult
object MatchingResult {
  final case class FullMatch(
    purchaseOrderId: PurchaseOrderId,
    receivingId: ReceivingId,
    supplierInvoiceId: SupplierInvoiceId
  ) extends MatchingResult

  final case class PartialMatch(
    purchaseOrderId: PurchaseOrderId,
    receivingId: ReceivingId,
    supplierInvoiceId: SupplierInvoiceId,
    discrepancies: List[MatchingDiscrepancy]
  ) extends MatchingResult
}
```

**差異の処理**:

```scala
// 差異処理サービス
class DiscrepancyResolutionService(
  threeWayMatchingService: ThreeWayMatchingService,
  alertService: AlertService
) {

  def resolveDiscrepancy(
    matchingResult: MatchingResult.PartialMatch,
    resolution: DiscrepancyResolution
  ): Task[Unit] = {
    resolution match {
      case DiscrepancyResolution.AcceptInvoice =>
        // 請求書をそのまま承認
        acceptInvoiceAsIs(matchingResult)

      case DiscrepancyResolution.RequestCorrection(reason) =>
        // 仕入先に訂正を依頼
        requestInvoiceCorrection(matchingResult, reason)

      case DiscrepancyResolution.PartialPayment(amount) =>
        // 一部金額のみ支払
        processPartialPayment(matchingResult, amount)

      case DiscrepancyResolution.Escalate(to) =>
        // 上位者へエスカレーション
        escalateToManager(matchingResult, to)
    }
  }

  private def acceptInvoiceAsIs(
    matchingResult: MatchingResult.PartialMatch
  ): Task[Unit] = {
    for {
      _ <- ZIO.logWarn(s"Accepting invoice ${matchingResult.supplierInvoiceId.value} with discrepancies")
      _ <- alertService.sendAlert(
        AlertLevel.Warning,
        s"Invoice accepted with discrepancies: ${matchingResult.discrepancies.mkString(", ")}"
      )
    } yield ()
  }

  private def requestInvoiceCorrection(
    matchingResult: MatchingResult.PartialMatch,
    reason: String
  ): Task[Unit] = {
    for {
      _ <- ZIO.logInfo(s"Requesting invoice correction: $reason")
      // 仕入先へメール送信などの処理
    } yield ()
  }

  private def processPartialPayment(
    matchingResult: MatchingResult.PartialMatch,
    amount: Money
  ): Task[Unit] = {
    for {
      _ <- ZIO.logInfo(s"Processing partial payment: ${amount.amount}")
      // 一部支払処理
    } yield ()
  }

  private def escalateToManager(
    matchingResult: MatchingResult.PartialMatch,
    to: String
  ): Task[Unit] = {
    for {
      _ <- ZIO.logInfo(s"Escalating to $to")
      _ <- alertService.sendAlert(
        AlertLevel.High,
        s"Discrepancy escalated to $to: ${matchingResult.discrepancies.mkString(", ")}"
      )
    } yield ()
  }
}

sealed trait DiscrepancyResolution
object DiscrepancyResolution {
  case object AcceptInvoice extends DiscrepancyResolution
  final case class RequestCorrection(reason: String) extends DiscrepancyResolution
  final case class PartialPayment(amount: Money) extends DiscrepancyResolution
  final case class Escalate(to: String) extends DiscrepancyResolution
}
```

### 1.3.4 パフォーマンス要件

発注管理システムには、以下のパフォーマンス要件があります。

**レスポンスタイム目標**:

```scala
object PerformanceRequirements {
  // 発注承認: 300ms以内
  val approvalResponseTime: FiniteDuration = 300.milliseconds

  // 入荷検収: 500ms以内
  val inspectionResponseTime: FiniteDuration = 500.milliseconds

  // 3-way matching: 200ms以内
  val matchingResponseTime: FiniteDuration = 200.milliseconds

  // 自動発注判定: 1,000ms以内（バッチ処理）
  val autoOrderResponseTime: FiniteDuration = 1000.milliseconds

  // スループット目標
  val ordersPerSecond: Int = 10           // 1日3,000件 ÷ 8時間 ÷ 3,600秒 ≈ 0.1件/秒（ピーク時10倍）
  val receivingsPerSecond: Int = 10       // 同上
  val matchingsPerSecond: Int = 10        // 同上
}
```

**パフォーマンス最適化戦略**:

1. **発注情報のキャッシング**
   - Redis によるステータスキャッシュ
   - TTL: 60秒

2. **承認フローの並列化**
   - 複数の承認を並列処理

3. **3-way matchingの最適化**
   - 事前計算とマテリアライズドビュー

4. **バッチ処理**
   - 自動発注判定は夜間バッチで実行

## 1.4 まとめ

本章では、発注管理サービスの要件を定義しました。

**ビジネス要件**:
- 月間3,000件、年間120億円の仕入取引を管理
- 200社の仕入先を3つのタイプに分類
- 9ステップの発注フローを自動化
- 3-way matchingによる突合精度の向上

**技術的課題**:
- Sagaパターンによる分散トランザクション管理
- 在庫管理との疎結合な連携
- 3-way matchingの自動化
- パフォーマンス要件の達成

次章では、発注管理システムのデータモデルを設計します。Read Model（PostgreSQL）とWrite Model（DynamoDB/イベントストア）のスキーマを詳しく見ていきます。
