# Part5 第7章: 在庫管理との連携

## 本章の目的

発注管理システムと在庫管理システム（第3部）を連携させ、入荷による在庫増加、発注残の管理、自動発注機能を実装します。Bounded Context間のイベント駆動アーキテクチャによる疎結合な連携を学びます。

## 本章で学ぶこと

- Bounded Context間のイベント駆動連携
- 入荷検収完了による在庫の自動更新
- 発注残数量の追跡と管理
- 発注点に基づく自動発注の実装
- 需要予測とリードタイムを考慮した発注判定
- クロスコンテキストの整合性管理

---

## 7.1 入荷による在庫増加

### 7.1.1 イベント駆動連携の設計

発注管理サービス（Procurement Context）と在庫管理サービス（Inventory Context）は、別々のBounded Contextとして独立しています。この2つのコンテキストをイベント駆動で連携させます。

```
┌─────────────────────────────┐        ┌─────────────────────────────┐
│  Procurement Context        │        │  Inventory Context          │
│                             │        │                             │
│  ┌─────────────────┐        │        │  ┌─────────────────┐        │
│  │ Receiving       │        │        │  │ Inventory       │        │
│  │ Aggregate       │        │        │  │ Aggregate       │        │
│  └────────┬────────┘        │        │  └────────▲────────┘        │
│           │                 │        │           │                 │
│           │ InspectionCompleted     │           │ IncreaseInventory│
│           │ Event           │        │           │ Command         │
│           │                 │        │           │                 │
│           ▼                 │        │           │                 │
│  ┌─────────────────┐        │        │  ┌─────────────────┐        │
│  │ Event Store     │        │        │  │ Event Processor │        │
│  └─────────────────┘        │        │  └─────────────────┘        │
│                             │        │                             │
└─────────────────────────────┘        └─────────────────────────────┘
         │                                        ▲
         │        Pekko Persistence Query         │
         └────────────────────────────────────────┘
```

### 7.1.2 在庫増加イベントプロセッサの実装

検収完了イベントを監視し、在庫を増加させるイベントプロセッサを実装します。

```scala
package com.example.procurement.integration.inventory

import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.persistence.query.PersistenceQuery
import org.apache.pekko.persistence.query.scaladsl.EventsByTagQuery
import org.apache.pekko.stream.scaladsl.{RestartSource, Sink}
import org.apache.pekko.pattern.StatusReply
import com.example.shared.domain.*
import com.example.procurement.domain.receiving.*
import com.example.inventory.domain.inventory.* // 第3部の在庫管理ドメイン
import scala.concurrent.duration.*

object InventoryIntegrationService {

  // 在庫更新結果
  sealed trait InventoryUpdateResult

  object InventoryUpdateResult {
    final case class Success(productId: ProductId, quantity: Quantity) extends InventoryUpdateResult
    final case class Failure(productId: ProductId, reason: String) extends InventoryUpdateResult
  }

  def apply(
    sharding: ClusterSharding
  )(implicit system: ActorSystem[_]): Behavior[Nothing] = {
    Behaviors.setup { context =>
      implicit val ec = context.executionContext

      context.log.info("Starting Inventory Integration Service")

      // PersistenceQueryプラグインを取得
      val readJournal = PersistenceQuery(system)
        .readJournalFor[EventsByTagQuery]("jdbc-read-journal")

      // "inspection-completed"タグでイベントをストリーム
      val source = RestartSource.onFailuresWithBackoff(
        RestartSettings(
          minBackoff = 3.seconds,
          maxBackoff = 30.seconds,
          randomFactor = 0.2
        )
      ) { () =>
        readJournal.eventsByTag("inspection-completed", offset = readJournal.currentOffset)
      }

      // イベントを処理
      source.runWith(Sink.foreach { envelope =>
        envelope.event match {
          case event: ReceivingEvent.InspectionCompleted =>
            context.log.info(
              s"Processing InspectionCompleted event: receivingId=${event.receivingId.value}"
            )

            // 合格した商品の在庫を増やす
            event.inspectedItems
              .filter(_.inspectionResult == InspectionResult.Accepted)
              .foreach { item =>
                context.log.info(
                  s"Increasing inventory: productId=${item.productId.value}, " +
                  s"quantity=${item.acceptedQuantity.value}"
                )

                // Inventory集約のEntityRefを取得
                val inventoryRef = sharding.entityRefFor(
                  InventoryActor.TypeKey,
                  s"${event.tenantId.value}-${event.warehouseId.value}-${item.productId.value}"
                )

                // 在庫増加コマンドを送信
                inventoryRef.ask[StatusReply[InventoryEvent.InventoryIncreased]] { replyTo =>
                  InventoryCommand.IncreaseInventory(
                    productId = item.productId,
                    warehouseId = event.warehouseId,
                    quantity = item.acceptedQuantity,
                    reason = InventoryChangeReason.ProcurementReceiving,
                    reference = Some(event.receivingId.value),
                    occurredAt = event.occurredAt,
                    replyTo = replyTo
                  )
                }(3.seconds, system.scheduler).onComplete {
                  case scala.util.Success(StatusReply.Success(result)) =>
                    context.log.info(
                      s"Inventory increased successfully: productId=${item.productId.value}, " +
                      s"newQuantity=${result.newQuantity.value}"
                    )

                  case scala.util.Success(StatusReply.Error(error)) =>
                    context.log.error(
                      s"Failed to increase inventory: productId=${item.productId.value}, " +
                      s"error=$error"
                    )

                  case scala.util.Failure(exception) =>
                    context.log.error(
                      s"Exception while increasing inventory: productId=${item.productId.value}",
                      exception
                    )
                }
              }

          case _ => // 他のイベントは無視
        }
      })

      Behaviors.empty
    }
  }
}

// 在庫変更理由（第3部の在庫管理ドメインと統合）
sealed trait InventoryChangeReason {
  def description: String
}

object InventoryChangeReason {
  case object ProcurementReceiving extends InventoryChangeReason {
    val description = "発注入荷"
  }

  case object SalesShipment extends InventoryChangeReason {
    val description = "受注出荷"
  }

  case object StockAdjustment extends InventoryChangeReason {
    val description = "棚卸調整"
  }

  case object Transfer extends InventoryChangeReason {
    val description = "倉庫間移動"
  }
}
```

### 7.1.3 在庫管理コマンドの定義（第3部との統合）

在庫管理サービス（第3部）のInventory集約に対するコマンドを定義します。

```scala
package com.example.inventory.domain.inventory

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.pattern.StatusReply
import com.example.shared.domain.*
import java.time.Instant

// 在庫管理コマンド
sealed trait InventoryCommand

object InventoryCommand {
  // 在庫増加（入荷）
  final case class IncreaseInventory(
    productId: ProductId,
    warehouseId: WarehouseId,
    quantity: Quantity,
    reason: InventoryChangeReason,
    reference: Option[String],
    occurredAt: Instant,
    replyTo: ActorRef[StatusReply[InventoryEvent.InventoryIncreased]]
  ) extends InventoryCommand

  // 在庫減少（出荷）
  final case class DecreaseInventory(
    productId: ProductId,
    warehouseId: WarehouseId,
    quantity: Quantity,
    reason: InventoryChangeReason,
    reference: Option[String],
    occurredAt: Instant,
    replyTo: ActorRef[StatusReply[InventoryEvent.InventoryDecreased]]
  ) extends InventoryCommand

  // 在庫引当
  final case class ReserveInventory(
    productId: ProductId,
    warehouseId: WarehouseId,
    quantity: Quantity,
    orderId: String,
    replyTo: ActorRef[StatusReply[InventoryEvent.InventoryReserved]]
  ) extends InventoryCommand

  // 在庫引当解除
  final case class ReleaseInventory(
    productId: ProductId,
    warehouseId: WarehouseId,
    quantity: Quantity,
    orderId: String,
    replyTo: ActorRef[StatusReply[InventoryEvent.InventoryReleased]]
  ) extends InventoryCommand

  // 在庫調整
  final case class AdjustInventory(
    productId: ProductId,
    warehouseId: WarehouseId,
    newQuantity: Quantity,
    reason: String,
    replyTo: ActorRef[StatusReply[InventoryEvent.InventoryAdjusted]]
  ) extends InventoryCommand
}

// 在庫イベント
sealed trait InventoryEvent {
  def inventoryId: InventoryId
  def occurredAt: Instant
}

object InventoryEvent {
  final case class InventoryIncreased(
    inventoryId: InventoryId,
    productId: ProductId,
    warehouseId: WarehouseId,
    quantity: Quantity,
    newQuantity: Quantity,
    reason: InventoryChangeReason,
    reference: Option[String],
    occurredAt: Instant
  ) extends InventoryEvent

  final case class InventoryDecreased(
    inventoryId: InventoryId,
    productId: ProductId,
    warehouseId: WarehouseId,
    quantity: Quantity,
    newQuantity: Quantity,
    reason: InventoryChangeReason,
    reference: Option[String],
    occurredAt: Instant
  ) extends InventoryEvent

  final case class InventoryReserved(
    inventoryId: InventoryId,
    productId: ProductId,
    warehouseId: WarehouseId,
    quantity: Quantity,
    orderId: String,
    occurredAt: Instant
  ) extends InventoryEvent

  final case class InventoryReleased(
    inventoryId: InventoryId,
    productId: ProductId,
    warehouseId: WarehouseId,
    quantity: Quantity,
    orderId: String,
    occurredAt: Instant
  ) extends InventoryEvent

  final case class InventoryAdjusted(
    inventoryId: InventoryId,
    productId: ProductId,
    warehouseId: WarehouseId,
    oldQuantity: Quantity,
    newQuantity: Quantity,
    reason: String,
    occurredAt: Instant
  ) extends InventoryEvent
}

// 在庫ID
final case class InventoryId(value: String) extends AnyVal
```

### 7.1.4 べき等性の保証

同じ検収完了イベントが複数回処理されても問題ないように、べき等性を保証する必要があります。

```scala
package com.example.procurement.integration.inventory

import com.example.shared.domain.*
import java.time.Instant

// イベント処理履歴
final case class ProcessedEvent(
  eventId: String,
  eventType: String,
  processedAt: Instant,
  status: ProcessedEventStatus
)

sealed trait ProcessedEventStatus

object ProcessedEventStatus {
  case object Success extends ProcessedEventStatus
  case object Failed extends ProcessedEventStatus
  case object Skipped extends ProcessedEventStatus
}

// イベント処理履歴リポジトリ
trait ProcessedEventRepository {
  // イベントが既に処理済みかチェック
  def isProcessed(eventId: String): Boolean

  // イベント処理を記録
  def markAsProcessed(event: ProcessedEvent): Unit

  // 処理済みイベント一覧を取得
  def getProcessedEvents(limit: Int, offset: Int): List[ProcessedEvent]
}

// べき等性を保証するイベントプロセッサ
object IdempotentInventoryIntegrationService {

  def processInspectionCompletedEvent(
    event: com.example.procurement.domain.receiving.ReceivingEvent.InspectionCompleted,
    repository: ProcessedEventRepository,
    inventoryService: org.apache.pekko.actor.typed.ActorRef[com.example.inventory.domain.inventory.InventoryCommand]
  ): Unit = {
    val eventId = s"inspection-completed-${event.receivingId.value}-${event.occurredAt.toEpochMilli}"

    // 既に処理済みかチェック
    if (repository.isProcessed(eventId)) {
      println(s"Event already processed: $eventId")
      return
    }

    try {
      // 在庫増加処理を実行
      event.inspectedItems
        .filter(_.inspectionResult == com.example.procurement.domain.receiving.InspectionResult.Accepted)
        .foreach { item =>
          // 在庫増加コマンドを送信
          // （実際の処理はここに実装）
        }

      // 処理成功を記録
      repository.markAsProcessed(
        ProcessedEvent(
          eventId = eventId,
          eventType = "InspectionCompleted",
          processedAt = Instant.now(),
          status = ProcessedEventStatus.Success
        )
      )
    } catch {
      case ex: Exception =>
        // 処理失敗を記録
        repository.markAsProcessed(
          ProcessedEvent(
            eventId = eventId,
            eventType = "InspectionCompleted",
            processedAt = Instant.now(),
            status = ProcessedEventStatus.Failed
          )
        )
        throw ex
    }
  }
}
```

---

## 7.2 発注残の管理

### 7.2.1 発注残の計算

発注残（Outstanding Quantity）は、発注数量から入荷数量を差し引いた数量です。

```scala
package com.example.procurement.domain.purchaseorder

import com.example.shared.domain.*

// 発注残情報
final case class OutstandingInfo(
  items: List[OutstandingItem]
) {
  // 全て入荷済みかどうか
  def isFullyReceived: Boolean = items.forall(_.outstandingQuantity.value <= 0)

  // 一部入荷済みかどうか
  def isPartiallyReceived: Boolean = items.exists(_.receivedQuantity.value > 0) && !isFullyReceived

  // 未入荷かどうか
  def isNotReceived: Boolean = items.forall(_.receivedQuantity.value == 0)

  // 発注残合計金額を計算
  def totalOutstandingAmount: Money = {
    items.map { item =>
      Money(item.outstandingQuantity.value * item.unitPrice.amount)
    }.reduce(_ + _)
  }
}

// 商品ごとの発注残情報
final case class OutstandingItem(
  productId: ProductId,
  productName: ProductName,
  orderedQuantity: Quantity,
  receivedQuantity: Quantity,
  outstandingQuantity: Quantity,
  unitPrice: Money
)

object OutstandingInfo {
  // 発注情報から発注残情報を計算
  def calculate(purchaseOrder: PurchaseOrder): OutstandingInfo = {
    val outstandingItems = purchaseOrder.items.map { item =>
      val receivedQty = purchaseOrder.receivedItems.getOrElse(item.productId, Quantity(0))
      val outstandingQty = Quantity(item.quantity.value - receivedQty.value)

      OutstandingItem(
        productId = item.productId,
        productName = item.productName,
        orderedQuantity = item.quantity,
        receivedQuantity = receivedQty,
        outstandingQuantity = outstandingQty,
        unitPrice = item.unitPrice
      )
    }

    OutstandingInfo(outstandingItems)
  }
}
```

### 7.2.2 発注ステータスの自動更新

入荷が完了したら、発注ステータスを自動的に更新します。

```scala
package com.example.procurement.domain.purchaseorder

import com.example.shared.domain.*

// PurchaseOrderエンティティの拡張（発注残管理）
final case class PurchaseOrder(
  id: PurchaseOrderId,
  tenantId: TenantId,
  supplierId: SupplierId,
  orderNumber: OrderNumber,
  orderDate: java.time.LocalDate,
  deliveryDate: java.time.LocalDate,
  items: List[PurchaseOrderItem],
  status: PurchaseOrderStatus,
  approvalInfo: Option[ApprovalInfo],
  issuedInfo: Option[IssuedInfo],
  receivedItems: Map[ProductId, Quantity],
  version: Version
) {
  // 発注残情報を取得
  def outstandingInfo: OutstandingInfo = OutstandingInfo.calculate(this)

  // 入荷記録時に発注ステータスを自動更新
  def recordReceiptWithStatusUpdate(
    receivingId: ReceivingId,
    receivedItemsData: List[ReceivedItemData]
  ): Either[PurchaseOrderError, (PurchaseOrder, Map[ProductId, Quantity], Boolean)] = {
    status match {
      case PurchaseOrderStatus.Issued | PurchaseOrderStatus.PartiallyReceived =>
        // 各商品の入荷数量を更新
        val updatedReceivedItems = receivedItemsData.foldLeft(receivedItems) { (acc, item) =>
          val currentQty = acc.getOrElse(item.productId, Quantity(0))
          acc + (item.productId -> (currentQty + item.receivedQuantity))
        }

        // 発注残数量を計算
        val remainingQuantities = items.map { item =>
          val orderedQty = item.quantity
          val receivedQty = updatedReceivedItems.getOrElse(item.productId, Quantity(0))
          val remaining = Quantity(orderedQty.value - receivedQty.value)
          item.productId -> remaining
        }.toMap

        // 全て入荷済みかどうかをチェック
        val isFullyReceived = remainingQuantities.values.forall(_.value <= 0)

        // ステータスを決定
        val newStatus = if (isFullyReceived) {
          PurchaseOrderStatus.Completed
        } else {
          PurchaseOrderStatus.PartiallyReceived
        }

        val updatedPO = copy(
          receivedItems = updatedReceivedItems,
          status = newStatus,
          version = version.increment
        )

        Right((updatedPO, remainingQuantities, isFullyReceived))

      case _ =>
        Left(PurchaseOrderError.InvalidStatusForReceipt(status))
    }
  }

  // 発注残レポートを生成
  def generateOutstandingReport(): OutstandingReport = {
    val outstanding = outstandingInfo

    OutstandingReport(
      purchaseOrderId = id,
      orderNumber = orderNumber,
      supplierId = supplierId,
      orderDate = orderDate,
      deliveryDate = deliveryDate,
      status = status,
      items = outstanding.items,
      isFullyReceived = outstanding.isFullyReceived,
      isPartiallyReceived = outstanding.isPartiallyReceived,
      totalOutstandingAmount = outstanding.totalOutstandingAmount
    )
  }
}

// 発注残レポート
final case class OutstandingReport(
  purchaseOrderId: PurchaseOrderId,
  orderNumber: OrderNumber,
  supplierId: SupplierId,
  orderDate: java.time.LocalDate,
  deliveryDate: java.time.LocalDate,
  status: PurchaseOrderStatus,
  items: List[OutstandingItem],
  isFullyReceived: Boolean,
  isPartiallyReceived: Boolean,
  totalOutstandingAmount: Money
)
```

### 7.2.3 発注残一覧の取得（Read Model）

発注残一覧を効率的に取得するために、Read Modelを構築します。

```scala
package com.example.procurement.query

import com.example.shared.domain.*
import java.time.LocalDate

// 発注残一覧のクエリ
final case class OutstandingPurchaseOrderQuery(
  tenantId: Option[TenantId],
  supplierId: Option[SupplierId],
  fromDate: Option[LocalDate],
  toDate: Option[LocalDate],
  minAmount: Option[Money],
  limit: Int = 100,
  offset: Int = 0
)

// 発注残一覧の結果
final case class OutstandingPurchaseOrderResult(
  purchaseOrderId: PurchaseOrderId,
  orderNumber: OrderNumber,
  supplierId: SupplierId,
  supplierName: String,
  orderDate: LocalDate,
  deliveryDate: LocalDate,
  status: PurchaseOrderStatus,
  totalAmount: Money,
  receivedAmount: Money,
  outstandingAmount: Money,
  outstandingRate: Double, // 発注残率（%）
  isOverdue: Boolean       // 納期遅延フラグ
)

// 発注残クエリサービス
trait OutstandingPurchaseOrderQueryService {
  // 発注残一覧を取得
  def findOutstanding(query: OutstandingPurchaseOrderQuery): List[OutstandingPurchaseOrderResult]

  // 発注残合計金額を取得
  def getTotalOutstandingAmount(tenantId: TenantId, supplierId: Option[SupplierId]): Money

  // 納期遅延の発注一覧を取得
  def findOverdue(tenantId: TenantId): List[OutstandingPurchaseOrderResult]
}
```

---

## 7.3 自動発注機能

### 7.3.1 発注点管理

発注点（Reorder Point）を管理するドメインモデルを実装します。

```scala
package com.example.procurement.domain.reorder

import com.example.shared.domain.*
import java.time.LocalDate

// 発注点情報
final case class ReorderPoint(
  id: ReorderPointId,
  tenantId: TenantId,
  productId: ProductId,
  warehouseId: WarehouseId,
  minimumLevel: Quantity,          // 最小在庫レベル（発注点）
  reorderQuantity: Quantity,       // 発注数量
  supplierId: SupplierId,          // 優先仕入先
  leadTimeDays: Int,               // リードタイム（日数）
  safetyStockDays: Int,            // 安全在庫日数
  isActive: Boolean,               // 有効フラグ
  lastReorderedAt: Option[LocalDate], // 最終発注日
  version: Version
) {
  // 発注点を計算（動的計算版）
  def calculateReorderPoint(
    averageDailyDemand: Quantity
  ): Quantity = {
    // 発注点 = リードタイム需要 + 安全在庫
    val leadTimeDemand = Quantity(averageDailyDemand.value * leadTimeDays)
    val safetyStock = Quantity(averageDailyDemand.value * safetyStockDays)
    leadTimeDemand + safetyStock
  }

  // 経済的発注量（EOQ）を計算
  def calculateEOQ(
    annualDemand: Quantity,
    orderingCost: Money,
    holdingCostRate: Double,
    unitCost: Money
  ): Quantity = {
    // EOQ = sqrt((2 * D * S) / H)
    // D: 年間需要量、S: 発注コスト、H: 在庫維持コスト
    val holdingCost = unitCost.amount * holdingCostRate
    val eoq = Math.sqrt((2.0 * annualDemand.value * orderingCost.amount) / holdingCost)
    Quantity(Math.max(eoq.toInt, reorderQuantity.value))
  }

  // 発注すべきかどうかを判定
  def shouldReorder(
    currentInventory: Quantity,
    reservedQuantity: Quantity,
    incomingQuantity: Quantity // 発注済み未入荷数量
  ): Boolean = {
    // 利用可能在庫 = 現在庫 - 引当済み + 入荷予定
    val availableInventory = Quantity(
      currentInventory.value - reservedQuantity.value + incomingQuantity.value
    )

    // 利用可能在庫が発注点を下回ったら発注
    availableInventory.value <= minimumLevel.value && isActive
  }

  // 推奨発注量を計算
  def calculateRecommendedOrderQuantity(
    currentInventory: Quantity,
    reservedQuantity: Quantity,
    incomingQuantity: Quantity,
    averageDailyDemand: Quantity
  ): Quantity = {
    val availableInventory = Quantity(
      currentInventory.value - reservedQuantity.value + incomingQuantity.value
    )

    // 不足量を計算
    val shortage = Quantity(Math.max(0, minimumLevel.value - availableInventory.value))

    // 推奨発注量 = 基本発注数量 + 不足量
    reorderQuantity + shortage
  }
}

// 発注点ID
final case class ReorderPointId(value: String) extends AnyVal

object ReorderPointId {
  def generate(tenantId: TenantId, productId: ProductId, warehouseId: WarehouseId): ReorderPointId = {
    ReorderPointId(s"ROP-${tenantId.value}-${warehouseId.value}-${productId.value}")
  }
}
```

### 7.3.2 自動発注判定サービス

在庫レベルを監視し、発注点を下回った場合に自動的に発注を作成するサービスを実装します。

```scala
package com.example.procurement.application.reorder

import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.apache.pekko.pattern.StatusReply
import com.example.shared.domain.*
import com.example.procurement.domain.reorder.*
import com.example.procurement.domain.purchaseorder.*
import com.example.inventory.domain.inventory.*
import java.time.{Instant, LocalDate}
import scala.concurrent.duration.*
import scala.concurrent.Future

// 自動発注判定結果
sealed trait ReorderDecision

object ReorderDecision {
  final case class ShouldReorder(
    reorderPoint: ReorderPoint,
    currentInventory: Quantity,
    availableInventory: Quantity,
    recommendedQuantity: Quantity
  ) extends ReorderDecision

  final case class NoReorderNeeded(
    reorderPoint: ReorderPoint,
    currentInventory: Quantity,
    availableInventory: Quantity
  ) extends ReorderDecision

  final case class ReorderSkipped(
    reorderPoint: ReorderPoint,
    reason: String
  ) extends ReorderDecision
}

// 自動発注サービス
object AutoReorderService {

  // コマンド
  sealed trait Command
  final case class CheckReorderPoints(tenantId: TenantId) extends Command
  private case object ScheduledCheck extends Command

  def apply(
    sharding: ClusterSharding,
    reorderPointRepository: ReorderPointRepository,
    inventoryQueryService: InventoryQueryService,
    purchaseOrderService: ActorRef[PurchaseOrderServiceCommand]
  )(implicit system: ActorSystem[_]): Behavior[Command] = {
    Behaviors.setup { context =>
      implicit val ec = context.executionContext

      // 定期的な発注点チェック（1時間ごと）
      context.system.scheduler.scheduleAtFixedRate(
        initialDelay = 1.minute,
        interval = 1.hour
      ) { () =>
        context.self ! ScheduledCheck
      }

      Behaviors.receiveMessage {
        case CheckReorderPoints(tenantId) =>
          context.log.info(s"Checking reorder points for tenant: ${tenantId.value}")
          checkAndReorder(tenantId, context, sharding, reorderPointRepository, inventoryQueryService, purchaseOrderService)
          Behaviors.same

        case ScheduledCheck =>
          // 全テナントの発注点をチェック（実際にはテナント一覧を取得）
          context.log.info("Scheduled reorder point check")
          // checkAndReorder(...)
          Behaviors.same
      }
    }
  }

  private def checkAndReorder(
    tenantId: TenantId,
    context: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
    sharding: ClusterSharding,
    reorderPointRepository: ReorderPointRepository,
    inventoryQueryService: InventoryQueryService,
    purchaseOrderService: ActorRef[PurchaseOrderServiceCommand]
  )(implicit ec: scala.concurrent.ExecutionContext, system: ActorSystem[_]): Unit = {

    // 有効な発注点一覧を取得
    val reorderPoints = reorderPointRepository.findActiveByTenant(tenantId)

    context.log.info(s"Found ${reorderPoints.length} active reorder points")

    reorderPoints.foreach { reorderPoint =>
      // 現在の在庫状況を取得
      inventoryQueryService.getInventoryLevel(
        tenantId = tenantId,
        warehouseId = reorderPoint.warehouseId,
        productId = reorderPoint.productId
      ).foreach { inventoryLevel =>

        // 発注済み未入荷数量を取得
        val outstandingQuantity = getOutstandingQuantity(
          tenantId = tenantId,
          productId = reorderPoint.productId,
          supplierId = reorderPoint.supplierId
        )

        // 発注判定
        val decision = evaluateReorderDecision(
          reorderPoint = reorderPoint,
          inventoryLevel = inventoryLevel,
          outstandingQuantity = outstandingQuantity
        )

        decision match {
          case ReorderDecision.ShouldReorder(rp, current, available, recommendedQty) =>
            context.log.info(
              s"Creating automatic purchase order: product=${rp.productId.value}, " +
              s"quantity=${recommendedQty.value}, supplier=${rp.supplierId.value}"
            )

            // 自動発注を作成
            createAutomaticPurchaseOrder(
              tenantId = tenantId,
              reorderPoint = rp,
              quantity = recommendedQty,
              sharding = sharding,
              purchaseOrderService = purchaseOrderService
            )

          case ReorderDecision.NoReorderNeeded(rp, current, available) =>
            context.log.debug(
              s"No reorder needed: product=${rp.productId.value}, " +
              s"available=${available.value}, minimum=${rp.minimumLevel.value}"
            )

          case ReorderDecision.ReorderSkipped(rp, reason) =>
            context.log.warn(
              s"Reorder skipped: product=${rp.productId.value}, reason=$reason"
            )
        }
      }
    }
  }

  // 発注判定ロジック
  private def evaluateReorderDecision(
    reorderPoint: ReorderPoint,
    inventoryLevel: InventoryLevel,
    outstandingQuantity: Quantity
  ): ReorderDecision = {

    val currentInventory = inventoryLevel.quantityOnHand
    val reservedQuantity = inventoryLevel.quantityReserved
    val availableInventory = Quantity(
      currentInventory.value - reservedQuantity.value + outstandingQuantity.value
    )

    // 発注が必要かどうか
    if (reorderPoint.shouldReorder(currentInventory, reservedQuantity, outstandingQuantity)) {
      // 平均日次需要を計算（簡易版：過去30日の平均）
      val averageDailyDemand = inventoryLevel.averageDailyDemand.getOrElse(Quantity(1))

      // 推奨発注量を計算
      val recommendedQuantity = reorderPoint.calculateRecommendedOrderQuantity(
        currentInventory = currentInventory,
        reservedQuantity = reservedQuantity,
        incomingQuantity = outstandingQuantity,
        averageDailyDemand = averageDailyDemand
      )

      ReorderDecision.ShouldReorder(
        reorderPoint = reorderPoint,
        currentInventory = currentInventory,
        availableInventory = availableInventory,
        recommendedQuantity = recommendedQuantity
      )
    } else {
      ReorderDecision.NoReorderNeeded(
        reorderPoint = reorderPoint,
        currentInventory = currentInventory,
        availableInventory = availableInventory
      )
    }
  }

  // 発注済み未入荷数量を取得
  private def getOutstandingQuantity(
    tenantId: TenantId,
    productId: ProductId,
    supplierId: SupplierId
  ): Quantity = {
    // 実際にはデータベースから取得
    // SELECT SUM(ordered_quantity - received_quantity)
    // FROM purchase_order_items poi
    // JOIN purchase_orders po ON poi.purchase_order_id = po.id
    // WHERE po.tenant_id = ? AND poi.product_id = ? AND po.supplier_id = ?
    //   AND po.status IN ('ISSUED', 'PARTIALLY_RECEIVED')
    Quantity(0) // 仮実装
  }

  // 自動発注を作成
  private def createAutomaticPurchaseOrder(
    tenantId: TenantId,
    reorderPoint: ReorderPoint,
    quantity: Quantity,
    sharding: ClusterSharding,
    purchaseOrderService: ActorRef[PurchaseOrderServiceCommand]
  )(implicit ec: scala.concurrent.ExecutionContext, system: ActorSystem[_]): Unit = {

    // 商品情報を取得（単価など）
    // 実際には商品マスタから取得
    val unitPrice = Money(10000) // 仮

    val items = List(
      PurchaseOrderItemData(
        productId = reorderPoint.productId,
        productName = ProductName("商品名"), // 実際には商品マスタから取得
        quantity = quantity,
        unitPrice = unitPrice,
        taxRate = TaxRate(0.10)
      )
    )

    // 納期を計算（リードタイム考慮）
    val deliveryDate = LocalDate.now().plusDays(reorderPoint.leadTimeDays.toLong)

    // 発注作成コマンドを送信
    purchaseOrderService ! PurchaseOrderServiceCommand.CreateAutomaticPurchaseOrder(
      tenantId = tenantId,
      supplierId = reorderPoint.supplierId,
      orderDate = LocalDate.now(),
      deliveryDate = deliveryDate,
      items = items,
      reorderPointId = Some(reorderPoint.id),
      reason = s"自動発注: 在庫レベル低下（発注点: ${reorderPoint.minimumLevel.value}）"
    )
  }
}

// 在庫レベル情報
final case class InventoryLevel(
  productId: ProductId,
  warehouseId: WarehouseId,
  quantityOnHand: Quantity,
  quantityReserved: Quantity,
  quantityAvailable: Quantity,
  averageDailyDemand: Option[Quantity]
)

// 在庫クエリサービス
trait InventoryQueryService {
  def getInventoryLevel(
    tenantId: TenantId,
    warehouseId: WarehouseId,
    productId: ProductId
  ): Future[InventoryLevel]
}

// 発注点リポジトリ
trait ReorderPointRepository {
  def findActiveByTenant(tenantId: TenantId): List[ReorderPoint]
  def findById(id: ReorderPointId): Option[ReorderPoint]
  def save(reorderPoint: ReorderPoint): Unit
  def delete(id: ReorderPointId): Unit
}

// 発注サービスコマンド
sealed trait PurchaseOrderServiceCommand

object PurchaseOrderServiceCommand {
  final case class CreateAutomaticPurchaseOrder(
    tenantId: TenantId,
    supplierId: SupplierId,
    orderDate: LocalDate,
    deliveryDate: LocalDate,
    items: List[PurchaseOrderItemData],
    reorderPointId: Option[ReorderPointId],
    reason: String
  ) extends PurchaseOrderServiceCommand
}
```

### 7.3.3 需要予測の実装

過去の出荷データから需要を予測し、発注量を最適化します。

```scala
package com.example.procurement.application.reorder

import com.example.shared.domain.*
import java.time.LocalDate

// 需要予測結果
final case class DemandForecast(
  productId: ProductId,
  forecastPeriodDays: Int,
  forecastedDemand: Quantity,
  averageDailyDemand: Quantity,
  trend: DemandTrend,
  seasonalFactor: Double,
  confidence: Double // 信頼度（0.0〜1.0）
)

// 需要トレンド
sealed trait DemandTrend

object DemandTrend {
  case object Increasing extends DemandTrend   // 増加傾向
  case object Stable extends DemandTrend       // 安定
  case object Decreasing extends DemandTrend   // 減少傾向
}

// 需要予測サービス
object DemandForecastService {

  // 移動平均法による需要予測
  def forecastWithMovingAverage(
    historicalData: List[DailyDemand],
    forecastPeriodDays: Int,
    windowSize: Int = 30
  ): DemandForecast = {
    require(historicalData.length >= windowSize, "Insufficient historical data")

    // 最新N日分のデータで移動平均を計算
    val recentData = historicalData.takeRight(windowSize)
    val totalDemand = recentData.map(_.quantity.value).sum
    val averageDailyDemand = Quantity(totalDemand / windowSize)

    // 予測需要量を計算
    val forecastedDemand = Quantity(averageDailyDemand.value * forecastPeriodDays)

    // トレンドを判定
    val trend = detectTrend(historicalData, windowSize)

    DemandForecast(
      productId = recentData.head.productId,
      forecastPeriodDays = forecastPeriodDays,
      forecastedDemand = forecastedDemand,
      averageDailyDemand = averageDailyDemand,
      trend = trend,
      seasonalFactor = 1.0, // 簡易版では考慮しない
      confidence = 0.7
    )
  }

  // 線形回帰による需要予測
  def forecastWithLinearRegression(
    historicalData: List[DailyDemand],
    forecastPeriodDays: Int
  ): DemandForecast = {
    require(historicalData.nonEmpty, "Historical data is required")

    // 線形回帰の係数を計算
    val n = historicalData.length
    val xValues = (1 to n).map(_.toDouble).toList
    val yValues = historicalData.map(_.quantity.value.toDouble)

    val sumX = xValues.sum
    val sumY = yValues.sum
    val sumXY = xValues.zip(yValues).map { case (x, y) => x * y }.sum
    val sumX2 = xValues.map(x => x * x).sum

    // 傾き（slope）と切片（intercept）を計算
    val slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX)
    val intercept = (sumY - slope * sumX) / n

    // 予測値を計算
    val nextDay = n + 1
    val forecastStartDay = nextDay
    val forecastEndDay = nextDay + forecastPeriodDays - 1

    val forecastValues = (forecastStartDay to forecastEndDay).map { day =>
      Math.max(0, slope * day + intercept)
    }

    val totalForecastedDemand = forecastValues.sum
    val averageDailyDemand = Quantity(totalForecastedDemand.toInt / forecastPeriodDays)

    // トレンドを判定
    val trend = if (slope > 0.1) DemandTrend.Increasing
                else if (slope < -0.1) DemandTrend.Decreasing
                else DemandTrend.Stable

    DemandForecast(
      productId = historicalData.head.productId,
      forecastPeriodDays = forecastPeriodDays,
      forecastedDemand = Quantity(totalForecastedDemand.toInt),
      averageDailyDemand = averageDailyDemand,
      trend = trend,
      seasonalFactor = 1.0,
      confidence = 0.75
    )
  }

  // トレンドを検出
  private def detectTrend(
    historicalData: List[DailyDemand],
    windowSize: Int
  ): DemandTrend = {
    if (historicalData.length < windowSize * 2) {
      return DemandTrend.Stable
    }

    // 前半と後半の平均を比較
    val firstHalf = historicalData.take(windowSize)
    val secondHalf = historicalData.takeRight(windowSize)

    val firstAvg = firstHalf.map(_.quantity.value).sum / windowSize
    val secondAvg = secondHalf.map(_.quantity.value).sum / windowSize

    val changeRate = (secondAvg - firstAvg).toDouble / firstAvg

    if (changeRate > 0.15) DemandTrend.Increasing
    else if (changeRate < -0.15) DemandTrend.Decreasing
    else DemandTrend.Stable
  }

  // 季節性を考慮した需要予測
  def forecastWithSeasonality(
    historicalData: List[DailyDemand],
    forecastPeriodDays: Int,
    seasonalFactors: Map[Int, Double] // 月ごとの季節係数
  ): DemandForecast = {
    // 基本予測を取得
    val baseForecast = forecastWithMovingAverage(historicalData, forecastPeriodDays)

    // 現在の月を取得
    val currentMonth = LocalDate.now().getMonthValue

    // 季節係数を適用
    val seasonalFactor = seasonalFactors.getOrElse(currentMonth, 1.0)
    val adjustedForecast = Quantity((baseForecast.forecastedDemand.value * seasonalFactor).toInt)
    val adjustedDailyDemand = Quantity((baseForecast.averageDailyDemand.value * seasonalFactor).toInt)

    baseForecast.copy(
      forecastedDemand = adjustedForecast,
      averageDailyDemand = adjustedDailyDemand,
      seasonalFactor = seasonalFactor,
      confidence = baseForecast.confidence * 0.9 // 季節性を考慮すると信頼度が若干低下
    )
  }
}

// 日次需要データ
final case class DailyDemand(
  productId: ProductId,
  date: LocalDate,
  quantity: Quantity
)
```

---

## 7.4 クロスコンテキストの整合性管理

### 7.4.1 結果整合性の保証

発注管理と在庫管理は別々のBounded Contextであり、Strong Consistencyではなく、Eventual Consistency（結果整合性）で連携します。

```scala
package com.example.procurement.integration

import com.example.shared.domain.*
import java.time.Instant

// クロスコンテキスト整合性チェッカー
object CrossContextConsistencyChecker {

  // 不整合検出結果
  sealed trait InconsistencyResult

  object InconsistencyResult {
    final case class Consistent(
      receivingId: ReceivingId,
      inventoryId: InventoryId
    ) extends InconsistencyResult

    final case class Inconsistent(
      receivingId: ReceivingId,
      expectedInventoryChange: Quantity,
      actualInventoryChange: Option[Quantity],
      reason: String
    ) extends InconsistencyResult
  }

  // 検収完了と在庫増加の整合性をチェック
  def checkReceivingInventoryConsistency(
    receivingEvent: com.example.procurement.domain.receiving.ReceivingEvent.InspectionCompleted,
    inventoryEvents: List[com.example.inventory.domain.inventory.InventoryEvent.InventoryIncreased]
  ): List[InconsistencyResult] = {

    receivingEvent.inspectedItems
      .filter(_.inspectionResult == com.example.procurement.domain.receiving.InspectionResult.Accepted)
      .map { inspectedItem =>
        // 対応する在庫増加イベントを検索
        inventoryEvents.find { invEvent =>
          invEvent.productId == inspectedItem.productId &&
          invEvent.reference.contains(receivingEvent.receivingId.value)
        } match {
          case Some(invEvent) =>
            // 数量が一致するかチェック
            if (invEvent.quantity == inspectedItem.acceptedQuantity) {
              InconsistencyResult.Consistent(
                receivingId = receivingEvent.receivingId,
                inventoryId = invEvent.inventoryId
              )
            } else {
              InconsistencyResult.Inconsistent(
                receivingId = receivingEvent.receivingId,
                expectedInventoryChange = inspectedItem.acceptedQuantity,
                actualInventoryChange = Some(invEvent.quantity),
                reason = s"Quantity mismatch: expected=${inspectedItem.acceptedQuantity.value}, actual=${invEvent.quantity.value}"
              )
            }

          case None =>
            InconsistencyResult.Inconsistent(
              receivingId = receivingEvent.receivingId,
              expectedInventoryChange = inspectedItem.acceptedQuantity,
              actualInventoryChange = None,
              reason = s"Inventory increase event not found for product ${inspectedItem.productId.value}"
            )
        }
      }
  }
}
```

---

## まとめ

本章では、発注管理システムと在庫管理システムの連携を実装しました。

### 実装した内容

1. **入荷による在庫増加**
   - イベント駆動による疎結合な連携
   - 検収完了イベントを監視して在庫を自動更新
   - べき等性の保証（同じイベントを複数回処理しても安全）

2. **発注残の管理**
   - 発注数量と入荷数量から発注残を計算
   - 発注ステータスの自動更新
   - 発注残一覧のRead Model構築

3. **自動発注機能**
   - 発注点（Reorder Point）の管理
   - 在庫レベルの監視と自動発注判定
   - EOQ（経済的発注量）の計算
   - 需要予測（移動平均法、線形回帰）
   - 季節性を考慮した発注量調整

4. **クロスコンテキスト整合性**
   - 結果整合性（Eventual Consistency）の保証
   - 不整合検出とアラート

### Bounded Context間連携のポイント

- **イベント駆動**: Pekko Persistence Queryによるイベントストリーミング
- **疎結合**: 各コンテキストは独立して動作
- **べき等性**: 同じイベントを複数回処理しても副作用なし
- **結果整合性**: Strong Consistencyではなく、最終的に整合性が取れる設計

### 次章の予告

次章では、発注管理システムのパフォーマンス最適化を実装します。承認ルールのキャッシング、3-way matchingのバッチ処理、在庫レベル監視の最適化など、実運用に必要な性能改善を学びます。
