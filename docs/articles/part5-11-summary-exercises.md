# 第5部 第11章 まとめと実践演習

本章では、第5部で学んだ発注管理サービスの実装内容を総括し、実践的な演習問題を通じて理解を深めます。

## 11.1 学んだこと

### 11.1.1 発注管理の実装

第5部では、D社の調達・購買業務を支える発注管理サービスをCQRS/イベントソーシングで実装しました。

#### 承認ワークフロー

発注金額に応じた多段階承認を実装:

```scala
// 承認金額に応じた承認者ロールの決定
def requiredApproverRole: ApproverRole = {
  val amount = totalAmount.amount
  amount match {
    case a if a < 500000 => ApproverRole.Manager       // 50万円未満: 課長
    case a if a < 1000000 => ApproverRole.Director     // 100万円未満: 部長
    case _ => ApproverRole.Executive                   // 100万円以上: 役員
  }
}

// 承認状態の管理
sealed trait ApprovalStatus
case object PendingApproval extends ApprovalStatus
case object Approved extends ApprovalStatus
case object Rejected extends ApprovalStatus
```

**学んだこと:**
- 金額閾値による承認ルートの動的決定
- 承認履歴の永続化（イベントソーシング）
- 承認フロー制御のステートマシン設計

#### 発注書発行

承認後の発注書発行と仕入先通知:

```scala
case (state @ PurchaseOrderState(po), IssuePurchaseOrder(poId, issuedBy, replyTo))
    if po.status == PurchaseOrderStatus.Approved =>

  val event = PurchaseOrderIssued(
    purchaseOrderId = poId,
    issuedBy = issuedBy,
    issuedAt = Instant.now()
  )

  Effect
    .persist(event)
    .thenRun { _ =>
      // 仕入先に発注書を通知
      supplierNotificationService ! NotifySupplier(po.supplierId, po)
      replyTo ! StatusReply.Success(event)
    }
```

**学んだこと:**
- 承認完了後の業務フロー制御
- 外部システム（仕入先）との連携
- イベント駆動による通知処理

#### 入荷検収処理

入荷品の検収と不合格品の処理:

```scala
final case class InspectionResult(
  inspectedItem: InspectedItem,
  result: InspectionResultType,
  defectType: Option[DefectType],
  actionTaken: InspectionAction
)

sealed trait InspectionResultType
case object Accepted extends InspectionResultType
case object Rejected extends InspectionResultType
case object PartiallyAccepted extends InspectionResultType

// 検収完了処理
case (state @ ReceivingState(receiving), CompleteInspection(receivingId, completedBy, replyTo))
    if receiving.status == ReceivingStatus.InspectionInProgress =>

  val acceptedItems = receiving.inspectedItems.filter(_.inspectionResult == Accepted)
  val rejectedItems = receiving.inspectedItems.filter(_.inspectionResult == Rejected)

  // 在庫増加イベントを発行（合格品のみ）
  acceptedItems.foreach { item =>
    inventoryService ! IncreaseInventory(item.productId, item.acceptedQuantity)
  }
```

**学んだこと:**
- 検収プロセスのステート管理
- 合格品と不合格品の分離処理
- 在庫への自動反映

### 11.1.2 在庫との連携

#### イベント駆動の在庫更新

Pekko Persistence Queryを活用した在庫への非同期連携:

```scala
object InventoryIntegrationService {
  def apply(sharding: ClusterSharding)(implicit system: ActorSystem[_]): Behavior[Nothing] = {
    Behaviors.setup { context =>
      val readJournal = PersistenceQuery(system)
        .readJournalFor[EventsByTagQuery]("jdbc-read-journal")

      val source = RestartSource.onFailuresWithBackoff(
        RestartSettings(minBackoff = 3.seconds, maxBackoff = 30.seconds, randomFactor = 0.2)
      ) { () =>
        readJournal.eventsByTag("inspection-completed", offset = readJournal.currentOffset)
      }

      source.runWith(Sink.foreach { envelope =>
        envelope.event match {
          case event: ReceivingEvent.InspectionCompleted =>
            processInspectionCompleted(event, sharding)
        }
      })

      Behaviors.empty
    }
  }
}
```

**学んだこと:**
- Bounded Context間のイベント駆動統合
- 結果整合性の実現
- リトライとバックオフによる耐障害性

#### 発注点管理

在庫水準の監視と自動発注:

```scala
final case class ReorderPoint(
  productId: ProductId,
  reorderLevel: Quantity,           // 発注点
  economicOrderQuantity: Quantity,  // 経済的発注量（EOQ）
  leadTimeDays: Int,                // リードタイム（日数）
  safetyStock: Quantity             // 安全在庫
) {
  // 発注が必要か判定
  def shouldReorder(currentInventory: Quantity): Boolean = {
    currentInventory.value <= reorderLevel.value
  }

  // EOQ計算（ウィルソンの公式）
  def calculateEOQ(
    annualDemand: Double,
    orderCost: BigDecimal,
    holdingCostPerUnit: BigDecimal
  ): Quantity = {
    val eoq = Math.sqrt(
      (2 * annualDemand * orderCost.toDouble) / holdingCostPerUnit.toDouble
    )
    Quantity(BigDecimal(eoq).setScale(0, BigDecimal.RoundingMode.HALF_UP))
  }
}
```

**学んだこと:**
- 在庫管理の基本理論（発注点、EOQ）
- 安全在庫の計算
- 自動発注判定ロジック

#### 自動発注

定期的な在庫監視による自動発注:

```scala
class AutoReorderService(
  inventoryQueryService: InventoryQueryService,
  reorderPointRepository: ReorderPointRepository,
  purchaseOrderService: ActorRef[PurchaseOrderCommand]
)(implicit system: ActorSystem[_]) {

  // 1時間ごとに発注点チェック
  system.scheduler.scheduleAtFixedRate(
    initialDelay = 1.minute,
    interval = 1.hour
  ) { () =>
    checkReorderPoints()
  }

  private def checkReorderPoints(): Unit = {
    for {
      reorderPoints <- reorderPointRepository.findAll()
      _ <- Future.sequence(reorderPoints.map(checkSingleProduct))
    } yield ()
  }

  private def checkSingleProduct(reorderPoint: ReorderPoint): Future[Unit] = {
    for {
      currentInventory <- inventoryQueryService.getInventoryLevel(reorderPoint.productId)
      _ <- if (reorderPoint.shouldReorder(currentInventory)) {
        createPurchaseOrder(reorderPoint)
      } else {
        Future.successful(())
      }
    } yield ()
  }
}
```

**学んだこと:**
- スケジューラーによる定期実行
- 在庫監視の自動化
- 発注プロセスの完全自動化

### 11.1.3 3-way matching（三方突合）

発注・入荷・請求の整合性検証:

```scala
final case class ThreeWayMatchingService(
  purchaseOrderRepository: PurchaseOrderRepository,
  receivingRepository: ReceivingRepository,
  invoiceRepository: InvoiceRepository
) {

  def performThreeWayMatching(invoiceId: InvoiceId): Future[ThreeWayMatchingResult] = {
    for {
      invoice <- invoiceRepository.findById(invoiceId)
      purchaseOrder <- purchaseOrderRepository.findById(invoice.purchaseOrderId)
      receiving <- receivingRepository.findByPurchaseOrderId(invoice.purchaseOrderId)

      result = validateMatching(purchaseOrder, receiving, invoice)
    } yield result
  }

  private def validateMatching(
    po: PurchaseOrder,
    receiving: Receiving,
    invoice: SupplierInvoice
  ): ThreeWayMatchingResult = {

    val quantityMatches = receiving.receivedItems.forall { receivedItem =>
      invoice.items.exists { invoiceItem =>
        invoiceItem.productId == receivedItem.productId &&
        Math.abs(invoiceItem.quantity.value - receivedItem.receivedQuantity.value) <= 0
      }
    }

    val priceMatches = invoice.items.forall { invoiceItem =>
      po.items.exists { poItem =>
        poItem.productId == invoiceItem.productId &&
        Math.abs((poItem.unitPrice.amount - invoiceItem.unitPrice.amount).toDouble) <= PRICE_TOLERANCE
      }
    }

    val amountMatches = Math.abs((po.totalAmount.amount - invoice.totalAmount.amount).toDouble) <= AMOUNT_TOLERANCE

    if (quantityMatches && priceMatches && amountMatches) {
      ThreeWayMatchingResult.Matched(invoice.invoiceId)
    } else {
      val discrepancies = detectDiscrepancies(po, receiving, invoice)
      ThreeWayMatchingResult.Discrepancy(invoice.invoiceId, discrepancies)
    }
  }

  private val PRICE_TOLERANCE = 100.0    // ¥100の許容差
  private val AMOUNT_TOLERANCE = 100.0
}
```

**学んだこと:**
- 三方突合の実装パターン
- 許容差を含めた整合性チェック
- 差異検出とアラート

### 11.1.4 Sagaパターン

#### 発注承認Saga

複数アクターにまたがる発注承認プロセスの管理:

```scala
object PurchaseOrderApprovalSaga {
  sealed trait SagaState
  case object NotStarted extends SagaState
  case object ApprovalRequested extends SagaState
  case object Approved extends SagaState
  case object Issued extends SagaState
  case object SupplierNotified extends SagaState
  case object Completed extends SagaState
  case object Rejected extends SagaState
  case object Cancelled extends SagaState
  case object Failed extends SagaState

  def commandHandler: (SagaState, SagaCommand) => Effect[SagaEvent, SagaState] = {
    // 承認プロセスの開始
    case (NotStarted, StartApprovalProcess(sagaId, purchaseOrderId, _)) =>
      Effect
        .persist(ApprovalProcessStarted(sagaId, purchaseOrderId, Instant.now()))
        .thenRun { _ =>
          // PurchaseOrderActorに承認リクエスト
          purchaseOrderRef ! RequestApproval(purchaseOrderId, requiredRole, replyTo)
        }

    // 承認完了
    case (ApprovalRequested, ApprovalGranted(_, _, approver, _)) =>
      Effect
        .persist(PurchaseOrderApprovedEvent(sagaId, purchaseOrderId, approver, Instant.now()))
        .thenRun { _ =>
          // 発注書発行
          purchaseOrderRef ! IssuePurchaseOrder(purchaseOrderId, approver, replyTo)
        }

    // 補償トランザクション（キャンセル）
    case (Issued, CancelSaga(_, reason, _)) =>
      Effect
        .persist(SagaCancelled(sagaId, reason, Instant.now()))
        .thenRun { _ =>
          // 発注をキャンセル
          purchaseOrderRef ! CancelPurchaseOrder(purchaseOrderId, reason, replyTo)
        }
  }
}
```

**学んだこと:**
- オーケストレーション型Sagaの実装
- 補償トランザクションによるロールバック
- 長時間実行プロセスの状態管理

#### 入荷検収Saga

入荷から在庫反映までのプロセス管理:

```scala
object ReceivingInspectionSaga {
  sealed trait SagaState
  case object NotStarted extends SagaState
  case object ReceivingCreated extends SagaState
  case object GoodsReceived extends SagaState
  case object InspectionInProgress extends SagaState
  case object InspectionCompleted extends SagaState
  case object InventoryUpdated extends SagaState
  case object Completed extends SagaState
  case object Failed extends SagaState

  def commandHandler: (SagaState, SagaCommand) => Effect[SagaEvent, SagaState] = {
    // 検収完了後の在庫更新
    case (InspectionCompleted, UpdateInventory(sagaId, receivingId, acceptedItems, _)) =>
      Effect
        .persist(InventoryUpdateRequested(sagaId, receivingId, acceptedItems, Instant.now()))
        .thenRun { _ =>
          // 合格品を在庫に反映
          acceptedItems.foreach { item =>
            inventoryRef ! IncreaseInventory(
              productId = item.productId,
              quantity = item.acceptedQuantity,
              reason = InventoryChangeReason.ProcurementReceiving,
              reference = Some(receivingId.value),
              replyTo = replyTo
            )
          }
        }
  }
}
```

**学んだこと:**
- 検収プロセスのSaga化
- 在庫システムとの連携
- 合格品のみの在庫反映ロジック

#### 支払Saga

三方突合から支払実行までのプロセス管理:

```scala
object PaymentSaga {
  sealed trait SagaState
  case object NotStarted extends SagaState
  case object InvoiceReceived extends SagaState
  case object MatchingInProgress extends SagaState
  case object Matched extends SagaState
  case object PaymentApproved extends SagaState
  case object PaymentScheduled extends SagaState
  case object PaymentCompleted extends SagaState
  case object Mismatched extends SagaState
  case final case class PaymentFailed(
    invoiceId: InvoiceId,
    reason: String,
    retryCount: Int,
    lastAttempt: Instant
  ) extends SagaState

  private val MAX_RETRY_COUNT = 3

  def commandHandler: (SagaState, SagaCommand) => Effect[SagaEvent, SagaState] = {
    // 三方突合実行
    case (InvoiceReceived, PerformMatching(sagaId, invoiceId, _)) =>
      Effect
        .persist(MatchingStarted(sagaId, invoiceId, Instant.now()))
        .thenRun { _ =>
          threeWayMatchingService.performMatching(invoiceId).map {
            case ThreeWayMatchingResult.Matched(_) =>
              self ! MatchingCompleted(sagaId, invoiceId, replyTo)
            case ThreeWayMatchingResult.Discrepancy(_, discrepancies) =>
              self ! MatchingFailed(sagaId, invoiceId, discrepancies, replyTo)
          }
        }

    // リトライロジック
    case (state @ PaymentFailed(invoiceId, reason, retryCount, _), RetryPayment(sagaId))
        if retryCount < MAX_RETRY_COUNT =>
      Effect
        .persist(PaymentRetryScheduled(sagaId, invoiceId, retryCount + 1, Instant.now()))
        .thenRun { _ =>
          // 指数バックオフでリトライ
          val delay = Math.pow(2, retryCount).toInt.minutes
          system.scheduler.scheduleOnce(delay) {
            self ! ExecutePayment(sagaId, invoiceId, replyTo)
          }
        }

    // リトライ上限に達した場合（エスカレーション）
    case (PaymentFailed(invoiceId, reason, retryCount, _), RetryPayment(_))
        if retryCount >= MAX_RETRY_COUNT =>
      Effect
        .persist(EscalatedToManagerEvent(sagaId, invoiceId, reason, Instant.now()))
        .thenRun { _ =>
          notificationService ! NotifyManager(
            subject = "支払失敗（エスカレーション）",
            message = s"請求書 ${invoiceId.value} の支払が${MAX_RETRY_COUNT}回失敗しました: $reason"
          )
        }
  }
}
```

**学んだこと:**
- 複雑な業務フローのSaga実装
- リトライとエスカレーション戦略
- 指数バックオフによる再試行

### 11.1.5 パフォーマンス最適化

#### Redis キャッシュ

承認ルールのキャッシング:

```scala
class RedisApprovalRuleCache(jedisPool: JedisPool, cacheTTL: Duration = 1.hour) extends ApprovalRuleCache {

  override def getApprovalRules(tenantId: TenantId): Future[List[ApprovalRule]] = {
    Future {
      val jedis = jedisPool.getResource
      try {
        val cached = jedis.get(cacheKey(tenantId))
        if (cached != null) {
          decode[List[ApprovalRule]](cached).getOrElse(List.empty)
        } else {
          List.empty
        }
      } finally {
        jedis.close()
      }
    }
  }

  override def setApprovalRules(tenantId: TenantId, rules: List[ApprovalRule]): Future[Unit] = {
    Future {
      val jedis = jedisPool.getResource
      try {
        val json = rules.asJson.noSpaces
        jedis.setex(cacheKey(tenantId), cacheTTL.toSeconds.toInt, json)
      } finally {
        jedis.close()
      }
    }
  }
}
```

**学んだこと:**
- Redisによる分散キャッシュ
- TTLによる自動期限切れ
- キャッシュヒット率の測定

#### Pekko Streams

三方突合のバッチ処理:

```scala
class StreamingThreeWayMatchingProcessor(
  invoiceRepository: InvoiceRepository,
  matchingService: ThreeWayMatchingService
)(implicit system: ActorSystem[_], ec: ExecutionContext) {

  private val parallelism = 10
  private val throttle = 100 // 秒間100件まで

  def processMonthlyInvoices(yearMonth: YearMonth): Future[List[ThreeWayMatchingResult]] = {
    Source
      .future(invoiceRepository.findByMonth(yearMonth))
      .mapConcat(identity)
      .throttle(throttle, 1.second)
      .mapAsyncUnordered(parallelism) { invoice =>
        matchingService.performMatching(invoice.invoiceId)
      }
      .runWith(Sink.seq)
      .map(_.toList)
  }
}
```

**学んだこと:**
- Pekko Streamsによるバックプレッシャー制御
- スロットリングによる負荷調整
- 並列度の最適化

#### データベースインデックス

クエリ最適化のためのインデックス:

```sql
-- 発注書検索の最適化
CREATE INDEX idx_purchase_orders_status_created ON purchase_orders(status, created_at DESC);
CREATE INDEX idx_purchase_orders_supplier ON purchase_orders(supplier_id, created_at DESC);

-- 入荷検索の最適化
CREATE INDEX idx_receivings_po_status ON receivings(purchase_order_id, status);
CREATE INDEX idx_receivings_scheduled ON receivings(scheduled_date, status);

-- 請求書検索の最適化
CREATE INDEX idx_invoices_po_id ON supplier_invoices(purchase_order_id);
CREATE INDEX idx_invoices_due_date ON supplier_invoices(due_date, payment_status);

-- 三方突合の最適化
CREATE INDEX idx_matching_results_invoice ON three_way_matching_results(invoice_id, matched_at DESC);
CREATE INDEX idx_matching_results_status ON three_way_matching_results(matching_status, created_at DESC);
```

**学んだこと:**
- 複合インデックスの設計
- カバリングインデックスの活用
- クエリパターンに基づくインデックス設計

### 11.1.6 運用とモニタリング

#### ビジネスメトリクス

発注処理のKPI監視:

```scala
class PurchaseOrderProcessingMetrics {
  private val dailyOrderCount = new AtomicLong(0)
  private val monthlyOrderCount = new AtomicLong(0)
  private val monthlyTarget = 3000L  // 月次目標: 3,000件

  def recordOrder(): Unit = {
    dailyOrderCount.incrementAndGet()
    monthlyOrderCount.incrementAndGet()
  }

  def getTargetAchievementRate: Double = {
    (monthlyOrderCount.get().toDouble / monthlyTarget) * 100.0
  }

  def isOnTrack(currentDayOfMonth: Int, totalDaysInMonth: Int): Boolean = {
    val expectedOrders = (monthlyTarget * currentDayOfMonth) / totalDaysInMonth
    monthlyOrderCount.get() >= expectedOrders
  }
}
```

**学んだこと:**
- ビジネスKPIの計測
- 目標達成率の監視
- 進捗トラッキング

#### Saga監視

長時間実行Sagaの検出:

```scala
class SagaMonitoringService {
  private val activeSagas = new ConcurrentHashMap[SagaId, SagaMonitoringInfo]()

  def detectLongRunningSagas(thresholdDuration: Duration = 1.hour): List[SagaMonitoringInfo] = {
    val now = Instant.now()
    activeSagas.values().asScala
      .filter { info =>
        val duration = Duration.between(info.startedAt, now)
        duration.compareTo(thresholdDuration) > 0
      }
      .toList
  }

  def getSagaStatistics: SagaStatistics = {
    val all = activeSagas.values().asScala.toList
    SagaStatistics(
      activeCount = all.count(_.status == SagaStatus.Active),
      completedCount = all.count(_.status == SagaStatus.Completed),
      failedCount = all.count(_.status == SagaStatus.Failed),
      averageDuration = calculateAverageDuration(all)
    )
  }
}
```

**学んだこと:**
- Saga実行状況の可視化
- 長時間実行プロセスの検出
- 失敗率の監視

#### 仕入先評価

仕入先のパフォーマンス評価:

```scala
class SupplierEvaluationMetrics {

  def getSupplierEvaluation(supplierId: SupplierId): SupplierEvaluation = {
    val complianceRate = getDeliveryComplianceRate(supplierId)
    val avgDelay = getAverageDelayDays(supplierId)
    val passRate = getInspectionPassRate(supplierId)
    val defectRate = getDefectRate(supplierId)

    val overallScore = (complianceRate * 0.5 + passRate * 0.5)

    val rank = overallScore match {
      case s if s >= 95.0 => SupplierRank.Excellent
      case s if s >= 85.0 => SupplierRank.Good
      case s if s >= 70.0 => SupplierRank.Fair
      case _ => SupplierRank.Poor
    }

    SupplierEvaluation(supplierId, complianceRate, avgDelay, passRate, defectRate, overallScore, rank)
  }
}
```

**学んだこと:**
- 仕入先のスコアリング
- 多次元評価の統合
- ランク付けとレポーティング

### 11.1.7 高度なトピック

#### 需要予測

複数手法による需要予測:

```scala
class EnsembleForecastingService(
  exponentialSmoothingService: AdvancedDemandForecastingService,
  holtWintersService: AdvancedDemandForecastingService,
  seasonalAdjustmentService: AdvancedDemandForecastingService
) {

  def generateEnsembleForecast(
    productId: ProductId,
    forecastPeriodDays: Int
  ): Future[AdvancedDemandForecast] = {
    for {
      historicalData <- loadHistoricalData(productId)

      esForecast <- exponentialSmoothingService.forecast(historicalData, forecastPeriodDays)
      hwForecast <- holtWintersService.forecastWithHoltWinters(historicalData, forecastPeriodDays)
      saForecast <- seasonalAdjustmentService.forecastWithSeasonalAdjustment(historicalData, forecastPeriodDays)

      // 加重平均（Holt-Wintersに最も高いウェイトを付与）
      ensemblePredictions = esForecast.predictions.zip(hwForecast.predictions).zip(saForecast.predictions)
        .map { case ((es, hw), sa) =>
          DemandPrediction(
            date = es.date,
            predictedDemand = Quantity(
              (es.predictedDemand.value * 0.3) +
              (hw.predictedDemand.value * 0.4) +
              (sa.predictedDemand.value * 0.3)
            )
          )
        }

      confidenceInterval = ConfidenceInterval(lower = 0.90, upper = 1.10) // 90%信頼区間
    } yield AdvancedDemandForecast(
      productId = productId,
      predictions = ensemblePredictions,
      confidenceInterval = confidenceInterval,
      method = ForecastMethod.Ensemble
    )
  }
}
```

**学んだこと:**
- 複数予測手法の統合
- アンサンブル学習の応用
- 信頼区間の計算

#### 仕入先選定アルゴリズム

複数基準による最適仕入先選定:

```scala
class SupplierSelectionService {

  def selectOptimalSupplier(
    productId: ProductId,
    requiredQuantity: Quantity,
    candidates: List[SupplierId]
  ): Future[SupplierSelectionResult] = {
    for {
      evaluations <- Future.sequence(candidates.map(evaluateSupplier(_, productId)))

      // スコアリング: 価格40%、品質35%、納期25%
      scoredSuppliers = evaluations.map { eval =>
        val priceScore = eval.priceCompetitiveness * 0.40
        val qualityScore = eval.qualityScore * 0.35
        val deliveryScore = eval.deliveryReliability * 0.25

        SupplierwithScore(eval.supplierId, priceScore + qualityScore + deliveryScore, eval)
      }.sortBy(_.totalScore).reverse

      // 分散発注: 最大60%まで集中可
      selectedSuppliers = distributeOrders(scoredSuppliers, requiredQuantity, maxConcentration = 0.60)

    } yield SupplierSelectionResult(productId, selectedSuppliers)
  }

  private def distributeOrders(
    suppliers: List[SupplierWithScore],
    totalQuantity: Quantity,
    maxConcentration: Double
  ): List[SupplierOrder] = {
    val maxQuantityPerSupplier = Quantity(totalQuantity.value * maxConcentration)

    suppliers.foldLeft((List.empty[SupplierOrder], totalQuantity)) {
      case ((orders, remaining), supplier) if remaining.value > 0 =>
        val orderQuantity = Quantity(
          Math.min(remaining.value.toDouble, maxQuantityPerSupplier.value.toDouble)
        )
        (orders :+ SupplierOrder(supplier.supplierId, orderQuantity),
         Quantity(remaining.value - orderQuantity.value))
      case (acc, _) => acc
    }._1
  }
}
```

**学んだこと:**
- 多基準意思決定（MCDM）
- スコアリングモデルの設計
- リスク分散戦略

#### 複数通貨対応

グローバル調達の複数通貨サポート:

```scala
final case class MultiCurrencyMoney(amount: BigDecimal, currency: Currency) {

  def convertTo(targetCurrency: Currency, exchangeRate: ExchangeRate): MultiCurrencyMoney = {
    if (currency == targetCurrency) this
    else {
      val convertedAmount = amount * exchangeRate.rate
      MultiCurrencyMoney(convertedAmount, targetCurrency)
    }
  }

  def add(other: MultiCurrencyMoney, exchangeRateService: ExchangeRateService): Future[MultiCurrencyMoney] = {
    if (currency == other.currency) {
      Future.successful(MultiCurrencyMoney(amount + other.amount, currency))
    } else {
      for {
        rate <- exchangeRateService.getExchangeRate(other.currency, currency)
        converted = other.convertTo(currency, rate)
      } yield MultiCurrencyMoney(amount + converted.amount, currency)
    }
  }
}

class InternationalTaxService {

  def calculateImportDuties(
    cifValue: MultiCurrencyMoney,
    hsCode: HSCode,
    originCountry: Country
  ): Future[TaxCalculationResult] = {
    for {
      dutyRate <- getDutyRate(hsCode, originCountry)
      customsDuty = MultiCurrencyMoney(cifValue.amount * dutyRate, cifValue.currency)

      importConsumptionTaxRate = 0.10 // 10%
      taxableValue = cifValue.amount + customsDuty.amount
      importConsumptionTax = MultiCurrencyMoney(taxableValue * importConsumptionTaxRate, cifValue.currency)

      totalTax = customsDuty.amount + importConsumptionTax.amount
    } yield TaxCalculationResult(
      cifValue = cifValue,
      customsDuty = customsDuty,
      importConsumptionTax = importConsumptionTax,
      totalTax = MultiCurrencyMoney(totalTax, cifValue.currency)
    )
  }
}
```

**学んだこと:**
- 複数通貨の演算
- 為替レート管理
- 輸入税計算

## 11.2 実践演習

### 演習1: 発注承認ワークフローの拡張

#### 目的
現在の単一承認者による承認フローを、複数承認者による順次承認に拡張します。

#### 要件
1. **複数承認ルートの定義**
   - 金額に応じて複数の承認者を設定
   - 例: 100万円以上の場合、課長→部長→役員の順次承認

2. **承認履歴の管理**
   - 誰がいつ承認したかを記録
   - 承認コメントの保存

3. **承認ルートの動的変更**
   - 特定の商品カテゴリでは別ルートを使用
   - 緊急発注時のショートカットルート

#### 実装のヒント

```scala
// 複数承認者の定義
final case class ApprovalRoute(
  routeId: ApprovalRouteId,
  name: String,
  steps: List[ApprovalStep]
)

final case class ApprovalStep(
  stepNumber: Int,
  requiredRole: ApproverRole,
  isParallel: Boolean = false,  // 並列承認が可能か
  isMandatory: Boolean = true    // 必須承認か（スキップ可能か）
)

// 承認履歴
final case class ApprovalHistory(
  approvalId: ApprovalId,
  purchaseOrderId: PurchaseOrderId,
  approver: UserId,
  approverRole: ApproverRole,
  action: ApprovalAction,  // Approved, Rejected, Delegated
  comment: Option[String],
  approvedAt: Instant
)

// 承認状態の管理
final case class ApprovalProgress(
  purchaseOrderId: PurchaseOrderId,
  route: ApprovalRoute,
  currentStep: Int,
  completedSteps: List[ApprovalHistory],
  status: ApprovalProgressStatus
)

sealed trait ApprovalProgressStatus
case object InProgress extends ApprovalProgressStatus
case object Completed extends ApprovalProgressStatus
case object Rejected extends ApprovalProgressStatus
```

#### タスク
1. `ApprovalRoute` と `ApprovalStep` を実装
2. `ApprovalProgress` で現在の承認状況を管理
3. 承認者ごとの通知機能を実装
4. 承認履歴のクエリサービスを実装
5. 並列承認（複数の承認者が同時に承認可能）をサポート

#### テストケース
- 3段階承認フローのテスト
- 途中でリジェクトされた場合の処理
- 並列承認のテスト
- 承認者不在時の代理承認

### 演習2: 在庫最適化機能

#### 目的
発注点の自動調整と安全在庫の動的計算を実装し、在庫切れリスクを最小化しながら在庫保有コストを削減します。

#### 要件
1. **発注点の自動調整**
   - 過去の需要実績から最適な発注点を計算
   - 季節変動を考慮した調整

2. **安全在庫の計算**
   - リードタイムのばらつきを考慮
   - サービスレベル（欠品許容率）に基づく計算

3. **ABC分析に基づく発注戦略**
   - A品目: 厳格な在庫管理、短いリードタイム
   - B品目: 標準的な管理
   - C品目: 緩やかな管理、まとめ発注

#### 実装のヒント

```scala
// ABC分析
final case class ABCClassification(
  productId: ProductId,
  category: ABCCategory,
  annualUsageValue: Money,
  annualUsageQuantity: Quantity,
  percentageOfTotalValue: Double
)

sealed trait ABCCategory
case object CategoryA extends ABCCategory  // 上位20%（売上の80%）
case object CategoryB extends ABCCategory  // 次の30%（売上の15%）
case object CategoryC extends ABCCategory  // 残り50%（売上の5%）

// 安全在庫計算
class SafetyStockCalculator {

  def calculateSafetyStock(
    averageDailyDemand: Quantity,
    leadTimeDays: Int,
    demandStdDev: Double,
    leadTimeStdDev: Double,
    serviceLevel: Double = 0.95  // 95%サービスレベル
  ): Quantity = {
    // z値（標準正規分布）を取得
    val zScore = getZScore(serviceLevel)  // 95% → 1.65

    // 安全在庫 = z × √(リードタイム × 需要分散 + 平均需要² × リードタイム分散)
    val variance = (leadTimeDays * Math.pow(demandStdDev, 2)) +
                   (Math.pow(averageDailyDemand.value.toDouble, 2) * Math.pow(leadTimeStdDev, 2))

    val safetyStock = zScore * Math.sqrt(variance)

    Quantity(BigDecimal(safetyStock).setScale(0, BigDecimal.RoundingMode.HALF_UP))
  }

  private def getZScore(serviceLevel: Double): Double = {
    serviceLevel match {
      case sl if sl >= 0.999 => 3.09
      case sl if sl >= 0.99 => 2.33
      case sl if sl >= 0.95 => 1.65
      case sl if sl >= 0.90 => 1.28
      case _ => 1.00
    }
  }
}

// 動的発注点調整
class DynamicReorderPointCalculator(
  demandForecastService: AdvancedDemandForecastingService,
  safetyStockCalculator: SafetyStockCalculator
) {

  def calculateOptimalReorderPoint(
    productId: ProductId,
    leadTimeDays: Int,
    abcCategory: ABCCategory
  ): Future[ReorderPoint] = {
    for {
      // 過去90日の需要データを取得
      historicalData <- loadHistoricalDemand(productId, days = 90)

      // 需要予測
      forecast <- demandForecastService.forecast(historicalData, forecastPeriodDays = leadTimeDays)

      // 統計値計算
      averageDailyDemand = calculateAverageDemand(historicalData)
      demandStdDev = calculateStandardDeviation(historicalData)
      leadTimeStdDev = estimateLeadTimeVariation(productId)

      // サービスレベルをABC分類に応じて設定
      serviceLevel = abcCategory match {
        case CategoryA => 0.99  // A品目: 99%
        case CategoryB => 0.95  // B品目: 95%
        case CategoryC => 0.90  // C品目: 90%
      }

      // 安全在庫計算
      safetyStock = safetyStockCalculator.calculateSafetyStock(
        averageDailyDemand,
        leadTimeDays,
        demandStdDev,
        leadTimeStdDev,
        serviceLevel
      )

      // 発注点 = リードタイム需要 + 安全在庫
      leadTimeDemand = Quantity(averageDailyDemand.value * leadTimeDays)
      reorderLevel = Quantity(leadTimeDemand.value + safetyStock.value)

      // EOQ計算
      eoq = calculateEOQ(productId, averageDailyDemand)

    } yield ReorderPoint(
      productId = productId,
      reorderLevel = reorderLevel,
      economicOrderQuantity = eoq,
      leadTimeDays = leadTimeDays,
      safetyStock = safetyStock
    )
  }
}
```

#### タスク
1. ABC分析の実装
2. 安全在庫計算式の実装
3. 発注点の動的調整ロジック
4. 季節変動を考慮した調整
5. 在庫保有コストの計算とレポート

#### テストケース
- 需要が安定している商品の発注点計算
- 需要変動が大きい商品の安全在庫計算
- 季節商品の発注点調整
- ABC分類の自動更新

### 演習3: 仕入先評価システム

#### 目的
仕入先のパフォーマンスを多角的に評価し、発注先選定に活用できる評価システムを構築します。

#### 要件
1. **納期遵守率の計算**
   - 約束納期と実際の納期の比較
   - 遅延日数の記録

2. **品質スコアの算出**
   - 検収合格率
   - 不良品率
   - クレーム件数

3. **総合評価ダッシュボード**
   - 複数指標の可視化
   - ランキング表示
   - トレンド分析

#### 実装のヒント

```scala
// 仕入先評価指標
final case class SupplierPerformanceMetrics(
  supplierId: SupplierId,
  evaluationPeriod: Period,
  deliveryMetrics: DeliveryMetrics,
  qualityMetrics: QualityMetrics,
  priceMetrics: PriceMetrics,
  responseMetrics: ResponseMetrics,
  overallScore: Double,
  rank: SupplierRank,
  trend: PerformanceTrend
)

final case class DeliveryMetrics(
  totalDeliveries: Int,
  onTimeDeliveries: Int,
  lateDeliveries: Int,
  onTimeRate: Double,              // 納期遵守率
  averageDelayDays: Double,        // 平均遅延日数
  maximumDelayDays: Int,           // 最大遅延日数
  earlyDeliveryRate: Double        // 前倒し納品率
)

final case class QualityMetrics(
  totalInspections: Int,
  passedInspections: Int,
  failedInspections: Int,
  inspectionPassRate: Double,      // 検収合格率
  defectRate: Double,              // 不良率
  totalDefectQuantity: Quantity,
  complaintCount: Int,             // クレーム件数
  correctiveActionRate: Double     // 是正処置率
)

final case class PriceMetrics(
  priceCompetitiveness: Double,    // 価格競争力（市場比）
  priceStability: Double,          // 価格安定性
  paymentTerms: Int,               // 支払条件（日数）
  discountRate: Double             // 値引率
)

final case class ResponseMetrics(
  averageQuoteResponseTime: Duration,  // 見積回答時間
  averageInquiryResponseTime: Duration, // 問合せ回答時間
  communicationQuality: Double         // コミュニケーション品質スコア
)

// 総合評価サービス
class ComprehensiveSupplierEvaluationService(
  deliveryRepository: DeliveryRepository,
  inspectionRepository: InspectionRepository,
  priceRepository: PriceRepository
) {

  def evaluateSupplier(
    supplierId: SupplierId,
    period: Period = Period.ofMonths(3)
  ): Future[SupplierPerformanceMetrics] = {
    for {
      deliveryMetrics <- calculateDeliveryMetrics(supplierId, period)
      qualityMetrics <- calculateQualityMetrics(supplierId, period)
      priceMetrics <- calculatePriceMetrics(supplierId, period)
      responseMetrics <- calculateResponseMetrics(supplierId, period)

      // 重み付けスコア計算
      // 納期: 30%, 品質: 35%, 価格: 25%, 対応: 10%
      overallScore = (
        deliveryMetrics.onTimeRate * 0.30 +
        qualityMetrics.inspectionPassRate * 0.35 +
        priceMetrics.priceCompetitiveness * 0.25 +
        responseMetrics.communicationQuality * 0.10
      )

      // ランク決定
      rank = determineRank(overallScore)

      // トレンド分析
      previousPeriod = period.minus(period)
      previousMetrics <- evaluateSupplier(supplierId, previousPeriod)
      trend = analyzeTrend(overallScore, previousMetrics.overallScore)

    } yield SupplierPerformanceMetrics(
      supplierId,
      period,
      deliveryMetrics,
      qualityMetrics,
      priceMetrics,
      responseMetrics,
      overallScore,
      rank,
      trend
    )
  }

  private def calculateDeliveryMetrics(
    supplierId: SupplierId,
    period: Period
  ): Future[DeliveryMetrics] = {
    for {
      deliveries <- deliveryRepository.findBySupplierAndPeriod(supplierId, period)

      onTimeDeliveries = deliveries.count(_.isOnTime)
      lateDeliveries = deliveries.count(!_.isOnTime)
      onTimeRate = (onTimeDeliveries.toDouble / deliveries.size) * 100.0

      delays = deliveries.filter(!_.isOnTime).map(_.delayDays)
      averageDelay = if (delays.nonEmpty) delays.sum.toDouble / delays.size else 0.0
      maxDelay = if (delays.nonEmpty) delays.max else 0

      earlyDeliveries = deliveries.count(_.isEarlyDelivery)
      earlyRate = (earlyDeliveries.toDouble / deliveries.size) * 100.0

    } yield DeliveryMetrics(
      totalDeliveries = deliveries.size,
      onTimeDeliveries = onTimeDeliveries,
      lateDeliveries = lateDeliveries,
      onTimeRate = onTimeRate,
      averageDelayDays = averageDelay,
      maximumDelayDays = maxDelay,
      earlyDeliveryRate = earlyRate
    )
  }

  private def determineRank(score: Double): SupplierRank = score match {
    case s if s >= 95.0 => SupplierRank.Excellent
    case s if s >= 85.0 => SupplierRank.Good
    case s if s >= 70.0 => SupplierRank.Fair
    case s if s >= 50.0 => SupplierRank.Poor
    case _ => SupplierRank.Unacceptable
  }

  private def analyzeTrend(currentScore: Double, previousScore: Double): PerformanceTrend = {
    val change = currentScore - previousScore
    change match {
      case c if c > 5.0 => PerformanceTrend.SignificantImprovement
      case c if c > 2.0 => PerformanceTrend.Improving
      case c if c >= -2.0 => PerformanceTrend.Stable
      case c if c >= -5.0 => PerformanceTrend.Declining
      case _ => PerformanceTrend.SignificantDecline
    }
  }
}

// ダッシュボード用レポート生成
class SupplierEvaluationDashboardService(
  evaluationService: ComprehensiveSupplierEvaluationService
) {

  def generateDashboard(period: Period): Future[SupplierDashboard] = {
    for {
      allSuppliers <- supplierRepository.findAll()
      evaluations <- Future.sequence(
        allSuppliers.map(s => evaluationService.evaluateSupplier(s.id, period))
      )

      // ランキング
      topPerformers = evaluations.sortBy(_.overallScore).reverse.take(10)
      bottomPerformers = evaluations.sortBy(_.overallScore).take(10)

      // カテゴリ別分析
      excellentSuppliers = evaluations.filter(_.rank == SupplierRank.Excellent)
      improvingSuppliers = evaluations.filter(_.trend == PerformanceTrend.Improving)
      concernSuppliers = evaluations.filter(e =>
        e.rank == SupplierRank.Poor || e.trend == PerformanceTrend.Declining
      )

      // 統計情報
      averageScore = evaluations.map(_.overallScore).sum / evaluations.size
      averageOnTimeRate = evaluations.map(_.deliveryMetrics.onTimeRate).sum / evaluations.size
      averageQualityRate = evaluations.map(_.qualityMetrics.inspectionPassRate).sum / evaluations.size

    } yield SupplierDashboard(
      period = period,
      totalSuppliers = allSuppliers.size,
      topPerformers = topPerformers,
      bottomPerformers = bottomPerformers,
      excellentSuppliers = excellentSuppliers,
      improvingSuppliers = improvingSuppliers,
      concernSuppliers = concernSuppliers,
      statistics = DashboardStatistics(
        averageOverallScore = averageScore,
        averageOnTimeRate = averageOnTimeRate,
        averageQualityRate = averageQualityRate
      )
    )
  }
}
```

#### タスク
1. 各種メトリクスの計算ロジック実装
2. 重み付けスコアの計算
3. トレンド分析の実装
4. ダッシュボードレポート生成
5. アラート機能（閾値を下回った場合の通知）

#### テストケース
- 完璧な仕入先（100%合格率、100%納期遵守）
- 問題のある仕入先（低品質、遅延多発）
- 改善傾向の仕入先
- 悪化傾向の仕入先

### 演習4: グローバル調達機能

#### 目的
海外仕入先からの調達を可能にするため、複数通貨対応、為替ヘッジ、国際輸送のトラッキング機能を実装します。

#### 要件
1. **複数通貨での発注**
   - USD, EUR, GBP, CNY などの通貨サポート
   - 為替レートの自動取得

2. **為替ヘッジの実装**
   - 為替レート変動リスクの管理
   - ヘッジ契約の記録

3. **国際輸送のトラッキング**
   - 船積み情報の管理
   - 到着予定日の追跡
   - 輸入通関状況の管理

#### 実装のヒント

```scala
// 国際発注書
final case class InternationalPurchaseOrder(
  purchaseOrderId: PurchaseOrderId,
  orderNumber: OrderNumber,
  supplierId: SupplierId,
  supplierCountry: Country,
  items: List[PurchaseOrderItem],
  currency: Currency,
  totalAmount: MultiCurrencyMoney,
  incoterms: Incoterms,              // 貿易条件
  expectedShipmentDate: LocalDate,
  expectedArrivalDate: LocalDate,
  forexHedge: Option[ForexHedgeContract],
  customsInfo: Option[CustomsInformation]
)

// 貿易条件（インコタームズ）
sealed trait Incoterms
case object EXW extends Incoterms  // Ex Works
case object FOB extends Incoterms  // Free On Board
case object CIF extends Incoterms  // Cost, Insurance and Freight
case object DDP extends Incoterms  // Delivered Duty Paid

// 為替ヘッジ契約
final case class ForexHedgeContract(
  contractId: ForexHedgeContractId,
  fromCurrency: Currency,
  toCurrency: Currency,
  lockedRate: BigDecimal,
  amount: MultiCurrencyMoney,
  contractDate: LocalDate,
  settlementDate: LocalDate,
  hedgeType: HedgeType
)

sealed trait HedgeType
case object ForwardContract extends HedgeType    // 先渡契約
case object FutureContract extends HedgeType     // 先物契約
case object CurrencyOption extends HedgeType     // 通貨オプション

// 輸送トラッキング
final case class ShipmentTracking(
  shipmentId: ShipmentId,
  purchaseOrderId: PurchaseOrderId,
  shippingMethod: ShippingMethod,
  carrier: Carrier,
  trackingNumber: String,
  departurePort: Port,
  arrivalPort: Port,
  estimatedDepartureDate: LocalDate,
  actualDepartureDate: Option[LocalDate],
  estimatedArrivalDate: LocalDate,
  actualArrivalDate: Option[LocalDate],
  currentStatus: ShipmentStatus,
  trackingHistory: List[TrackingEvent]
)

sealed trait ShippingMethod
case object AirFreight extends ShippingMethod
case object SeaFreight extends ShippingMethod
case object Express extends ShippingMethod
case object LandTransport extends ShippingMethod

sealed trait ShipmentStatus
case object AwaitingShipment extends ShipmentStatus
case object InTransit extends ShipmentStatus
case object CustomsClearing extends ShipmentStatus
case object Delivered extends ShipmentStatus
case object Delayed extends ShipmentStatus

final case class TrackingEvent(
  eventTime: Instant,
  location: Location,
  status: ShipmentStatus,
  description: String
)

// 通関情報
final case class CustomsInformation(
  customsId: CustomsId,
  shipmentId: ShipmentId,
  hsCode: HSCode,                    // HSコード（関税分類）
  customsValue: MultiCurrencyMoney,  // 課税価格
  dutyRate: BigDecimal,              // 関税率
  calculatedDuty: MultiCurrencyMoney,
  importConsumptionTax: MultiCurrencyMoney,
  totalTax: MultiCurrencyMoney,
  customsStatus: CustomsStatus,
  declarationDate: Option[LocalDate],
  clearanceDate: Option[LocalDate]
)

sealed trait CustomsStatus
case object NotDeclared extends CustomsStatus
case object Declared extends CustomsStatus
case object UnderInspection extends CustomsStatus
case object Cleared extends CustomsStatus
case object OnHold extends CustomsStatus

// 国際発注管理サービス
class InternationalProcurementService(
  exchangeRateService: ExchangeRateService,
  forexHedgingService: ForexHedgingService,
  shipmentTrackingService: ShipmentTrackingService,
  customsService: InternationalTaxService
)(implicit system: ActorSystem[_], ec: ExecutionContext) {

  def createInternationalPurchaseOrder(
    supplierId: SupplierId,
    items: List[PurchaseOrderItem],
    supplierCurrency: Currency,
    incoterms: Incoterms,
    hedgeStrategy: HedgeStrategy
  ): Future[InternationalPurchaseOrder] = {
    for {
      // 為替レート取得
      exchangeRate <- exchangeRateService.getLatestRate(supplierCurrency, Currency.JPY)

      // 発注金額計算
      totalInSupplierCurrency = calculateTotalAmount(items, supplierCurrency)
      totalInJPY = totalInSupplierCurrency.convertTo(Currency.JPY, exchangeRate)

      // 為替ヘッジの検討
      hedgeContract <- if (shouldHedge(totalInJPY, hedgeStrategy)) {
        forexHedgingService.createHedgeContract(
          fromCurrency = supplierCurrency,
          toCurrency = Currency.JPY,
          amount = totalInSupplierCurrency,
          hedgeType = hedgeStrategy.preferredHedgeType
        ).map(Some(_))
      } else {
        Future.successful(None)
      }

      // 発注書作成
      po = InternationalPurchaseOrder(
        purchaseOrderId = PurchaseOrderId.generate(),
        orderNumber = generateOrderNumber(),
        supplierId = supplierId,
        supplierCountry = getSupplierCountry(supplierId),
        items = items,
        currency = supplierCurrency,
        totalAmount = totalInSupplierCurrency,
        incoterms = incoterms,
        expectedShipmentDate = calculateShipmentDate(),
        expectedArrivalDate = calculateArrivalDate(incoterms),
        forexHedge = hedgeContract,
        customsInfo = None  // 後で設定
      )

    } yield po
  }

  def trackShipment(shipmentId: ShipmentId): Future[ShipmentTracking] = {
    shipmentTrackingService.getTrackingInfo(shipmentId)
  }

  def processCustomsClearance(shipmentId: ShipmentId): Future[CustomsInformation] = {
    for {
      shipment <- shipmentTrackingService.getTrackingInfo(shipmentId)
      po <- getPurchaseOrder(shipment.purchaseOrderId)

      // CIF価額計算（Cost + Insurance + Freight）
      cifValue = calculateCIFValue(po, shipment)

      // 関税・税金計算
      taxCalculation <- customsService.calculateImportDuties(
        cifValue = cifValue,
        hsCode = getHSCode(po.items.head.productId),
        originCountry = po.supplierCountry
      )

      // 通関情報作成
      customsInfo = CustomsInformation(
        customsId = CustomsId.generate(),
        shipmentId = shipmentId,
        hsCode = getHSCode(po.items.head.productId),
        customsValue = cifValue,
        dutyRate = taxCalculation.dutyRate,
        calculatedDuty = taxCalculation.customsDuty,
        importConsumptionTax = taxCalculation.importConsumptionTax,
        totalTax = taxCalculation.totalTax,
        customsStatus = CustomsStatus.Declared,
        declarationDate = Some(LocalDate.now()),
        clearanceDate = None
      )

    } yield customsInfo
  }

  private def shouldHedge(amount: MultiCurrencyMoney, strategy: HedgeStrategy): Boolean = {
    // ヘッジ戦略に基づいて判定
    strategy match {
      case HedgeStrategy.Always => true
      case HedgeStrategy.AboveThreshold(threshold) => amount.amount >= threshold
      case HedgeStrategy.HighVolatility(volatilityThreshold) =>
        // 為替ボラティリティが閾値を超えている場合
        checkVolatility(amount.currency) > volatilityThreshold
      case HedgeStrategy.Never => false
    }
  }
}

// 為替ヘッジ戦略
sealed trait HedgeStrategy {
  def preferredHedgeType: HedgeType
}
object HedgeStrategy {
  case object Always extends HedgeStrategy {
    override def preferredHedgeType: HedgeType = ForwardContract
  }
  final case class AboveThreshold(threshold: BigDecimal) extends HedgeStrategy {
    override def preferredHedgeType: HedgeType = ForwardContract
  }
  final case class HighVolatility(volatilityThreshold: Double) extends HedgeStrategy {
    override def preferredHedgeType: HedgeType = CurrencyOption
  }
  case object Never extends HedgeStrategy {
    override def preferredHedgeType: HedgeType = ForwardContract  // Not used
  }
}
```

#### タスク
1. 複数通貨での発注書作成
2. 為替レートの取得と管理
3. 為替ヘッジ契約の記録
4. 輸送トラッキングシステムの構築
5. 通関処理の自動化
6. CIF価額と関税の計算

#### テストケース
- USD建て発注書の作成
- 為替ヘッジ契約の記録
- 船舶輸送のトラッキング
- 通関処理のシミュレーション
- 複数通貨の合計計算

## 11.3 次のステップ

### 11.3.1 より複雑なビジネスルールの追加

発注管理サービスをさらに実践的にするための拡張:

#### ロット管理

```scala
final case class Lot(
  lotNumber: LotNumber,
  productId: ProductId,
  quantity: Quantity,
  manufacturingDate: LocalDate,
  expirationDate: Option[LocalDate],
  status: LotStatus
)

sealed trait LotStatus
case object Active extends LotStatus
case object Expired extends LotStatus
case object Recalled extends LotStatus

// ロット追跡
class LotTraceabilityService {
  // 特定のロットがどの発注から来たかを追跡
  def traceLot(lotNumber: LotNumber): Future[LotTraceability]

  // 問題発生時のロット特定
  def findAffectedLots(supplierId: SupplierId, dateRange: DateRange): Future[List[Lot]]
}
```

#### 有効期限管理

```scala
class ExpirationManagementService {
  // 有効期限切れ間近の在庫を検出
  def findExpiringProducts(daysBeforeExpiration: Int): Future[List[ExpiringProduct]]

  // FEFO (First Expired, First Out) による引当
  def allocateByExpiration(productId: ProductId, requiredQuantity: Quantity): Future[List[Lot]]
}
```

#### 返品・返金処理

```scala
sealed trait ReturnReason
case object DefectiveProduct extends ReturnReason
case object WrongProduct extends ReturnReason
case object Overshipped extends ReturnReason
case object DamagedInTransit extends ReturnReason

final case class PurchaseReturn(
  returnId: ReturnId,
  purchaseOrderId: PurchaseOrderId,
  receivingId: ReceivingId,
  returnItems: List[ReturnItem],
  reason: ReturnReason,
  status: ReturnStatus,
  refundAmount: Option[Money]
)

class PurchaseReturnSaga {
  // 返品プロセスのオーケストレーション
  // 1. 返品承認
  // 2. 仕入先への返送
  // 3. 返金処理
  // 4. 在庫からの減算
}
```

### 11.3.2 他のBounded Contextとの統合

#### 生産計画コンテキスト

```scala
// 生産計画からの資材所要量計画（MRP）連携
class MaterialRequirementsPlanningIntegration {

  // 生産計画イベントを購読
  def subscribeToProductionPlan(): Source[ProductionPlanEvent, NotUsed]

  // 必要資材の自動発注
  def createPurchaseOrdersFromMRP(
    mrpRequirements: List[MaterialRequirement]
  ): Future[List[PurchaseOrder]]
}

final case class MaterialRequirement(
  productId: ProductId,
  requiredQuantity: Quantity,
  requiredDate: LocalDate,
  productionOrderId: ProductionOrderId
)
```

#### 品質管理コンテキスト

```scala
// 品質管理システムとの連携
class QualityManagementIntegration {

  // 不良品発生時の仕入先へのフィードバック
  def reportQualityIssue(
    supplierId: SupplierId,
    productId: ProductId,
    defectType: DefectType,
    affectedLots: List<LotNumber>
  ): Future[QualityIssueReport]

  // 是正処置要求（CAPA: Corrective And Preventive Action）
  def requestCorrectiveAction(
    supplierId: SupplierId,
    issueReport: QualityIssueReport
  ): Future[CAPARequest]
}
```

#### 会計管理コンテキスト

```scala
// 会計システムとの連携
class AccountingIntegration {

  // 支払完了時の仕訳起票
  def createJournalEntry(payment: Payment): Future[JournalEntry]

  // 買掛金の管理
  def updateAccountsPayable(
    supplierId: SupplierId,
    amount: Money,
    dueDate: LocalDate
  ): Future[AccountsPayableEntry]

  // 月次締め処理
  def performMonthEndClosing(yearMonth: YearMonth): Future[ClosingReport]
}

final case class JournalEntry(
  entryId: JournalEntryId,
  transactionDate: LocalDate,
  debitAccount: Account,
  creditAccount: Account,
  amount: Money,
  description: String
)

// 仕訳例: 商品購入時
// 借方: 商品 100,000円
// 貸方: 買掛金 100,000円
```

### 11.3.3 サプライチェーン全体の最適化

#### エンドツーエンドの可視化

```scala
class SupplyChainVisibilityService {

  // 発注から納品までの全プロセスを可視化
  def getEndToEndVisibility(purchaseOrderId: PurchaseOrderId): Future[SupplyChainVisibility]

  // リアルタイムステータス追跡
  def trackOrderStatus(orderNumber: OrderNumber): Future[OrderStatusTimeline]
}

final case class SupplyChainVisibility(
  purchaseOrder: PurchaseOrder,
  approvalHistory: List[ApprovalHistory],
  supplierStatus: SupplierOrderStatus,
  shipmentTracking: Option[ShipmentTracking],
  customsStatus: Option[CustomsStatus],
  receivingStatus: Option[ReceivingStatus],
  inspectionResults: Option[InspectionResults],
  inventoryImpact: Option[InventoryUpdate],
  paymentStatus: Option[PaymentStatus],
  estimatedCompletionDate: LocalDate,
  actualCompletionDate: Option[LocalDate]
)
```

#### ボトルネックの特定

```scala
class SupplyChainBottleneckAnalyzer {

  // プロセスのボトルネック分析
  def analyzeBottlenecks(period: Period): Future[BottleneckAnalysis]

  // 長時間かかっているプロセスの特定
  def findLongRunningProcesses(threshold: Duration): Future[List[ProcessDelay]]
}

final case class BottleneckAnalysis(
  period: Period,
  approvalBottleneck: Option[ProcessBottleneck],
  supplierResponseBottleneck: Option[ProcessBottleneck],
  shippingBottleneck: Option[ProcessBottleneck],
  customsBottleneck: Option[ProcessBottleneck],
  inspectionBottleneck: Option[ProcessBottleneck],
  recommendations: List[ImprovementRecommendation]
)

final case class ProcessBottleneck(
  processName: String,
  averageDuration: Duration,
  maxDuration: Duration,
  affectedOrders: Int,
  impactOnLeadTime: Duration
)
```

#### リードタイムの短縮

```scala
class LeadTimeOptimizationService {

  // リードタイム分析
  def analyzeLeadTime(supplierId: SupplierId): Future[LeadTimeAnalysis]

  // 最適化提案
  def generateOptimizationProposals(
    analysis: LeadTimeAnalysis
  ): Future[List[OptimizationProposal]]
}

final case class LeadTimeAnalysis(
  supplierId: SupplierId,
  averageLeadTime: Duration,
  leadTimeBreakdown: LeadTimeBreakdown,
  variability: LeadTimeVariability,
  comparisonWithBenchmark: BenchmarkComparison
)

final case class LeadTimeBreakdown(
  orderProcessingTime: Duration,
  supplierProductionTime: Duration,
  shippingTime: Duration,
  customsClearanceTime: Duration,
  receivingInspectionTime: Duration
)

sealed trait OptimizationProposal
final case class ChangeSupplierProposal(
  currentSupplierId: SupplierId,
  proposedSupplierId: SupplierId,
  expectedLeadTimeReduction: Duration,
  costImpact: Money
) extends OptimizationProposal

final case class ChangeShippingMethodProposal(
  currentMethod: ShippingMethod,
  proposedMethod: ShippingMethod,
  expectedLeadTimeReduction: Duration,
  additionalCost: Money
) extends OptimizationProposal

final case class PreApprovalProposal(
  productCategory: ProductCategory,
  approvalAmountThreshold: Money,
  expectedLeadTimeReduction: Duration
) extends OptimizationProposal
```

### 11.3.4 機械学習の活用

#### 需要予測の精度向上

```scala
// 機械学習モデルの統合
class MLDemandForecastingService {

  // 複数のMLモデルを活用した予測
  def forecastWithML(
    productId: ProductId,
    forecastHorizon: Int
  ): Future[MLForecastResult]

  // モデルの精度評価と自動選択
  def selectBestModel(
    productId: ProductId,
    historicalData: List[TimeSeriesDataPoint]
  ): Future[ForecastModel]
}

sealed trait ForecastModel
case object LSTM extends ForecastModel           // Long Short-Term Memory
case object Prophet extends ForecastModel        // Facebook Prophet
case object XGBoost extends ForecastModel        // Gradient Boosting
case object AutoARIMA extends ForecastModel      // Auto ARIMA
```

#### 異常検知

```scala
class AnomalyDetectionService {

  // 発注パターンの異常検知
  def detectOrderingAnomalies(): Future[List[OrderingAnomaly]]

  // 仕入先の行動変化検知
  def detectSupplierBehaviorChanges(
    supplierId: SupplierId
  ): Future[Option[BehaviorChangeAlert]]

  // 価格異常の検出
  def detectPriceAnomalies(
    productId: ProductId,
    proposedPrice: Money
  ): Future[PriceAnomalyAssessment]
}
```

## まとめ

第5部では、D社の調達・購買業務を支える発注管理サービスをCQRS/イベントソーシングで実装しました。

**実装した主要機能:**
1. 発注承認ワークフロー（金額に応じた多段階承認）
2. 入荷検収処理（合格/不合格判定、在庫反映）
3. 三方突合（発注・入荷・請求の整合性検証）
4. Sagaパターン（長時間実行プロセスの管理）
5. 在庫との連携（イベント駆動、発注点管理、自動発注）
6. パフォーマンス最適化（Redis、Pekko Streams、インデックス）
7. 運用監視（ビジネスメトリクス、Saga監視、仕入先評価）
8. 高度な機能（需要予測、仕入先選定、複数通貨）

**学んだアーキテクチャパターン:**
- イベントソーシング: 全ての状態変更をイベントとして記録
- CQRS: 書き込みと読み取りモデルの分離
- Sagaパターン: 分散トランザクションの管理
- Bounded Context統合: イベント駆動による疎結合
- ドメイン駆動設計: 集約、エンティティ、値オブジェクト

**実践演習:**
4つの演習問題を通じて、学んだ内容を実際に手を動かして深めることができます。

**次のステップ:**
さらなる機能拡張として、ロット管理、返品処理、他システム連携、サプライチェーン最適化などに取り組むことで、より実践的な発注管理システムを構築できます。

本章で完結した発注管理サービスは、実際のビジネス要件に対応できる堅牢で拡張性の高いアーキテクチャとなっています。この実装パターンを参考に、皆さんのプロジェクトでもCQRS/イベントソーシングを活用してください。

---

**おめでとうございます！第5部「発注管理サービス」を完了しました。**

次は第6部に進み、さらに高度なトピックを学んでいきましょう。
