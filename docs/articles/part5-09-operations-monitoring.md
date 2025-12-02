# Part5 第9章: 運用とモニタリング

## 本章の目的

発注管理システムを本番環境で安定的に運用するために必要な監視機能を実装します。ビジネスメトリクスの収集、Sagaの監視、仕入先評価など、実運用に欠かせない可観測性を構築します。

## 本章で学ぶこと

- ビジネスKPIのリアルタイム測定
- Sagaの状態監視とアラート
- 仕入先評価システムの構築
- メトリクス収集とダッシュボード
- SLO/SLAに基づく監視戦略
- 異常検知とアラート通知

---

## 9.1 ビジネスメトリクス

### 9.1.1 発注処理レートの監視

月間3,000件の発注を安定的に処理できることを監視します。

```scala
package com.example.procurement.monitoring.metrics

import com.example.shared.domain.*
import java.time.{LocalDate, YearMonth, Instant}
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.*

// 発注処理メトリクス
class PurchaseOrderProcessingMetrics {
  private val dailyCount = new java.util.concurrent.ConcurrentHashMap[LocalDate, AtomicLong]()
  private val monthlyCount = new java.util.concurrent.ConcurrentHashMap[YearMonth, AtomicLong]()
  private val totalProcessed = new AtomicLong(0)
  private val totalFailed = new AtomicLong(0)

  // 発注処理を記録
  def recordProcessed(date: LocalDate): Unit = {
    dailyCount.computeIfAbsent(date, _ => new AtomicLong(0)).incrementAndGet()
    val month = YearMonth.from(date)
    monthlyCount.computeIfAbsent(month, _ => new AtomicLong(0)).incrementAndGet()
    totalProcessed.incrementAndGet()
  }

  // 発注失敗を記録
  def recordFailed(date: LocalDate): Unit = {
    totalFailed.incrementAndGet()
  }

  // 日次処理件数を取得
  def getDailyCount(date: LocalDate): Long = {
    Option(dailyCount.get(date)).map(_.get()).getOrElse(0L)
  }

  // 月次処理件数を取得
  def getMonthlyCount(month: YearMonth): Long = {
    Option(monthlyCount.get(month)).map(_.get()).getOrElse(0L)
  }

  // 今月の処理件数を取得
  def getCurrentMonthCount: Long = {
    getMonthlyCount(YearMonth.now())
  }

  // 月間目標達成率を計算
  def getMonthlyTargetAchievement(targetCount: Long = 3000): Double = {
    val current = getCurrentMonthCount
    (current.toDouble / targetCount.toDouble) * 100.0
  }

  // ピーク時の処理能力をチェック
  def getPeakDailyCount(month: YearMonth): Long = {
    val startDate = month.atDay(1)
    val endDate = month.atEndOfMonth()

    var maxCount = 0L
    var currentDate = startDate
    while (!currentDate.isAfter(endDate)) {
      val count = getDailyCount(currentDate)
      if (count > maxCount) maxCount = count
      currentDate = currentDate.plusDays(1)
    }
    maxCount
  }

  // 成功率を計算
  def getSuccessRate: Double = {
    val total = totalProcessed.get() + totalFailed.get()
    if (total == 0) 100.0
    else (totalProcessed.get().toDouble / total.toDouble) * 100.0
  }

  // 統計情報を取得
  def getStatistics: PurchaseOrderStatistics = {
    val currentMonth = YearMonth.now()
    PurchaseOrderStatistics(
      currentMonthCount = getCurrentMonthCount,
      monthlyTarget = 3000,
      achievementRate = getMonthlyTargetAchievement(),
      peakDailyCount = getPeakDailyCount(currentMonth),
      successRate = getSuccessRate,
      totalProcessed = totalProcessed.get(),
      totalFailed = totalFailed.get()
    )
  }
}

final case class PurchaseOrderStatistics(
  currentMonthCount: Long,
  monthlyTarget: Long,
  achievementRate: Double,
  peakDailyCount: Long,
  successRate: Double,
  totalProcessed: Long,
  totalFailed: Long
)
```

### 9.1.2 承認処理時間の監視

承認プロセスの効率性を測定します。

```scala
package com.example.procurement.monitoring.metrics

import com.example.shared.domain.*
import java.time.{Duration, Instant}
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

// 承認処理メトリクス
class ApprovalProcessingMetrics {
  // 承認待ち発注（発注ID → 申請時刻）
  private val pendingApprovals = new ConcurrentHashMap[PurchaseOrderId, Instant]()

  // 承認時間の履歴
  private val approvalDurations = new java.util.concurrent.ConcurrentLinkedQueue[Duration]()
  private val maxHistorySize = 1000

  // 承認申請を記録
  def recordApprovalRequest(purchaseOrderId: PurchaseOrderId, requestedAt: Instant): Unit = {
    pendingApprovals.put(purchaseOrderId, requestedAt)
  }

  // 承認完了を記録
  def recordApprovalCompleted(purchaseOrderId: PurchaseOrderId, completedAt: Instant): Unit = {
    Option(pendingApprovals.remove(purchaseOrderId)).foreach { requestedAt =>
      val duration = Duration.between(requestedAt, completedAt)

      // 履歴に追加（最大サイズを超えたら古いものを削除）
      approvalDurations.add(duration)
      if (approvalDurations.size() > maxHistorySize) {
        approvalDurations.poll()
      }
    }
  }

  // 承認却下を記録
  def recordApprovalRejected(purchaseOrderId: PurchaseOrderId): Unit = {
    pendingApprovals.remove(purchaseOrderId)
  }

  // 承認待ち件数を取得
  def getPendingCount: Int = {
    pendingApprovals.size()
  }

  // 平均承認時間を取得
  def getAverageApprovalTime: Duration = {
    val durations = approvalDurations.asScala.toList
    if (durations.isEmpty) Duration.ZERO
    else {
      val totalSeconds = durations.map(_.getSeconds).sum
      Duration.ofSeconds(totalSeconds / durations.length)
    }
  }

  // 最大承認時間を取得
  def getMaxApprovalTime: Duration = {
    val durations = approvalDurations.asScala.toList
    if (durations.isEmpty) Duration.ZERO
    else durations.maxBy(_.getSeconds)
  }

  // 最小承認時間を取得
  def getMinApprovalTime: Duration = {
    val durations = approvalDurations.asScala.toList
    if (durations.isEmpty) Duration.ZERO
    else durations.minBy(_.getSeconds)
  }

  // 長時間承認待ちの発注を取得（24時間以上）
  def getOverdueApprovals(thresholdHours: Int = 24): List[OverdueApproval] = {
    val now = Instant.now()
    val threshold = Duration.ofHours(thresholdHours.toLong)

    pendingApprovals.asScala.toList
      .map { case (orderId, requestedAt) =>
        val waitingTime = Duration.between(requestedAt, now)
        OverdueApproval(orderId, requestedAt, waitingTime)
      }
      .filter(_.waitingTime.compareTo(threshold) > 0)
      .sortBy(_.waitingTime.getSeconds)(Ordering[Long].reverse)
  }

  // 統計情報を取得
  def getStatistics: ApprovalStatistics = {
    ApprovalStatistics(
      pendingCount = getPendingCount,
      averageApprovalTime = getAverageApprovalTime,
      maxApprovalTime = getMaxApprovalTime,
      minApprovalTime = getMinApprovalTime,
      overdueCount = getOverdueApprovals().length
    )
  }
}

final case class OverdueApproval(
  purchaseOrderId: PurchaseOrderId,
  requestedAt: Instant,
  waitingTime: Duration
)

final case class ApprovalStatistics(
  pendingCount: Int,
  averageApprovalTime: Duration,
  maxApprovalTime: Duration,
  minApprovalTime: Duration,
  overdueCount: Int
)
```

### 9.1.3 入荷検収状況の監視

検収プロセスの品質を測定します。

```scala
package com.example.procurement.monitoring.metrics

import com.example.shared.domain.*
import com.example.procurement.domain.receiving.*
import java.util.concurrent.atomic.AtomicLong

// 入荷検収メトリクス
class ReceivingInspectionMetrics {
  private val totalReceivings = new AtomicLong(0)
  private val completedInspections = new AtomicLong(0)
  private val discrepancyCount = new AtomicLong(0)
  private val totalDiscrepancyAmount = new AtomicLong(0) // 差異金額（円）

  // 入荷記録を記録
  def recordReceiving(): Unit = {
    totalReceivings.incrementAndGet()
  }

  // 検収完了を記録
  def recordInspectionCompleted(hasDiscrepancy: Boolean, discrepancyAmount: Money): Unit = {
    completedInspections.incrementAndGet()

    if (hasDiscrepancy) {
      discrepancyCount.incrementAndGet()
      totalDiscrepancyAmount.addAndGet(discrepancyAmount.amount.toLong)
    }
  }

  // 検収完了率を計算
  def getCompletionRate: Double = {
    val total = totalReceivings.get()
    if (total == 0) 0.0
    else (completedInspections.get().toDouble / total.toDouble) * 100.0
  }

  // 差異発生率を計算
  def getDiscrepancyRate: Double = {
    val completed = completedInspections.get()
    if (completed == 0) 0.0
    else (discrepancyCount.get().toDouble / completed.toDouble) * 100.0
  }

  // 平均差異金額を計算
  def getAverageDiscrepancyAmount: Money = {
    val count = discrepancyCount.get()
    if (count == 0) Money(0)
    else Money(totalDiscrepancyAmount.get() / count)
  }

  // 統計情報を取得
  def getStatistics: ReceivingStatistics = {
    ReceivingStatistics(
      totalReceivings = totalReceivings.get(),
      completedInspections = completedInspections.get(),
      completionRate = getCompletionRate,
      discrepancyCount = discrepancyCount.get(),
      discrepancyRate = getDiscrepancyRate,
      totalDiscrepancyAmount = Money(totalDiscrepancyAmount.get()),
      averageDiscrepancyAmount = getAverageDiscrepancyAmount
    )
  }
}

final case class ReceivingStatistics(
  totalReceivings: Long,
  completedInspections: Long,
  completionRate: Double,
  discrepancyCount: Long,
  discrepancyRate: Double,
  totalDiscrepancyAmount: Money,
  averageDiscrepancyAmount: Money
)
```

### 9.1.4 支払状況の監視

支払プロセスの健全性を測定します。

```scala
package com.example.procurement.monitoring.metrics

import com.example.shared.domain.*
import java.time.{LocalDate, YearMonth}
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

// 支払メトリクス
class PaymentMetrics {
  private val monthlyPaymentAmount = new ConcurrentHashMap[YearMonth, AtomicLong]()
  private val delayedPayments = new AtomicLong(0)
  private val totalMatchingAttempts = new AtomicLong(0)
  private val successfulMatchings = new AtomicLong(0)

  // 支払予定を記録
  def recordScheduledPayment(month: YearMonth, amount: Money): Unit = {
    monthlyPaymentAmount
      .computeIfAbsent(month, _ => new AtomicLong(0))
      .addAndGet(amount.amount.toLong)
  }

  // 支払遅延を記録
  def recordDelayedPayment(): Unit = {
    delayedPayments.incrementAndGet()
  }

  // 3-way matchingを記録
  def recordThreeWayMatching(successful: Boolean): Unit = {
    totalMatchingAttempts.incrementAndGet()
    if (successful) {
      successfulMatchings.incrementAndGet()
    }
  }

  // 月次支払予定金額を取得
  def getMonthlyPaymentAmount(month: YearMonth): Money = {
    Option(monthlyPaymentAmount.get(month))
      .map(amount => Money(amount.get()))
      .getOrElse(Money(0))
  }

  // 今月の支払予定金額を取得
  def getCurrentMonthPaymentAmount: Money = {
    getMonthlyPaymentAmount(YearMonth.now())
  }

  // 支払遅延件数を取得
  def getDelayedPaymentCount: Long = {
    delayedPayments.get()
  }

  // 3-way matching成功率を計算
  def getMatchingSuccessRate: Double = {
    val total = totalMatchingAttempts.get()
    if (total == 0) 0.0
    else (successfulMatchings.get().toDouble / total.toDouble) * 100.0
  }

  // 統計情報を取得
  def getStatistics: PaymentStatistics = {
    PaymentStatistics(
      currentMonthPaymentAmount = getCurrentMonthPaymentAmount,
      delayedPaymentCount = getDelayedPaymentCount,
      matchingSuccessRate = getMatchingSuccessRate,
      totalMatchingAttempts = totalMatchingAttempts.get(),
      successfulMatchings = successfulMatchings.get()
    )
  }
}

final case class PaymentStatistics(
  currentMonthPaymentAmount: Money,
  delayedPaymentCount: Long,
  matchingSuccessRate: Double,
  totalMatchingAttempts: Long,
  successfulMatchings: Long
)
```

---

## 9.2 Sagaの監視

### 9.2.1 Saga状態の追跡

各Sagaの状態をリアルタイムで追跡します。

```scala
package com.example.procurement.monitoring.saga

import com.example.shared.domain.*
import com.example.procurement.saga.approval.*
import java.time.{Duration, Instant}
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

// Saga実行状態
final case class SagaExecution(
  sagaId: SagaId,
  sagaType: SagaType,
  entityId: String,
  status: SagaExecutionStatus,
  startedAt: Instant,
  completedAt: Option[Instant],
  failedAt: Option[Instant],
  currentStep: String,
  retryCount: Int,
  errorMessage: Option[String]
) {
  // 実行時間を計算
  def duration: Duration = {
    val endTime = completedAt.orElse(failedAt).getOrElse(Instant.now())
    Duration.between(startedAt, endTime)
  }

  // 実行中かどうか
  def isRunning: Boolean = status == SagaExecutionStatus.Running

  // 完了しているかどうか
  def isCompleted: Boolean = status == SagaExecutionStatus.Completed

  // 失敗しているかどうか
  def isFailed: Boolean = status == SagaExecutionStatus.Failed
}

sealed trait SagaType
object SagaType {
  case object PurchaseOrderApproval extends SagaType
  case object ReceivingInspection extends SagaType
  case object Payment extends SagaType
}

sealed trait SagaExecutionStatus
object SagaExecutionStatus {
  case object Running extends SagaExecutionStatus
  case object Completed extends SagaExecutionStatus
  case object Failed extends SagaExecutionStatus
  case object Compensating extends SagaExecutionStatus
  case object Cancelled extends SagaExecutionStatus
}

// Saga監視サービス
class SagaMonitoringService {
  private val activeSagas = new ConcurrentHashMap[SagaId, SagaExecution]()
  private val completedSagas = new java.util.concurrent.ConcurrentLinkedQueue[SagaExecution]()
  private val failedSagas = new java.util.concurrent.ConcurrentLinkedQueue[SagaExecution]()
  private val maxHistorySize = 10000

  // Saga開始を記録
  def recordSagaStarted(
    sagaId: SagaId,
    sagaType: SagaType,
    entityId: String,
    startedAt: Instant
  ): Unit = {
    val execution = SagaExecution(
      sagaId = sagaId,
      sagaType = sagaType,
      entityId = entityId,
      status = SagaExecutionStatus.Running,
      startedAt = startedAt,
      completedAt = None,
      failedAt = None,
      currentStep = "Started",
      retryCount = 0,
      errorMessage = None
    )
    activeSagas.put(sagaId, execution)
  }

  // Sagaステップ更新を記録
  def recordSagaStepUpdate(sagaId: SagaId, stepName: String): Unit = {
    Option(activeSagas.get(sagaId)).foreach { execution =>
      val updated = execution.copy(currentStep = stepName)
      activeSagas.put(sagaId, updated)
    }
  }

  // Saga完了を記録
  def recordSagaCompleted(sagaId: SagaId, completedAt: Instant): Unit = {
    Option(activeSagas.remove(sagaId)).foreach { execution =>
      val completed = execution.copy(
        status = SagaExecutionStatus.Completed,
        completedAt = Some(completedAt)
      )

      completedSagas.add(completed)
      if (completedSagas.size() > maxHistorySize) {
        completedSagas.poll()
      }
    }
  }

  // Saga失敗を記録
  def recordSagaFailed(sagaId: SagaId, failedAt: Instant, errorMessage: String): Unit = {
    Option(activeSagas.remove(sagaId)).foreach { execution =>
      val failed = execution.copy(
        status = SagaExecutionStatus.Failed,
        failedAt = Some(failedAt),
        errorMessage = Some(errorMessage)
      )

      failedSagas.add(failed)
      if (failedSagas.size() > maxHistorySize) {
        failedSagas.poll()
      }
    }
  }

  // 進行中のSaga一覧を取得
  def getActiveSagas: List[SagaExecution] = {
    activeSagas.values().asScala.toList
  }

  // 失敗したSaga一覧を取得
  def getFailedSagas(limit: Int = 100): List[SagaExecution] = {
    failedSagas.asScala.toList.take(limit)
  }

  // Sagaタイプ別の統計を取得
  def getStatisticsByType(sagaType: SagaType): SagaTypeStatistics = {
    val active = activeSagas.values().asScala.filter(_.sagaType == sagaType).toList
    val completed = completedSagas.asScala.filter(_.sagaType == sagaType).toList
    val failed = failedSagas.asScala.filter(_.sagaType == sagaType).toList

    val avgCompletionTime = if (completed.isEmpty) Duration.ZERO
    else {
      val totalSeconds = completed.map(_.duration.getSeconds).sum
      Duration.ofSeconds(totalSeconds / completed.length)
    }

    SagaTypeStatistics(
      sagaType = sagaType,
      activeCount = active.length,
      completedCount = completed.length,
      failedCount = failed.length,
      averageCompletionTime = avgCompletionTime,
      successRate = calculateSuccessRate(completed.length, failed.length)
    )
  }

  // 全Sagaの統計を取得
  def getOverallStatistics: SagaOverallStatistics = {
    val approvalStats = getStatisticsByType(SagaType.PurchaseOrderApproval)
    val receivingStats = getStatisticsByType(SagaType.ReceivingInspection)
    val paymentStats = getStatisticsByType(SagaType.Payment)

    SagaOverallStatistics(
      approvalSaga = approvalStats,
      receivingSaga = receivingStats,
      paymentSaga = paymentStats,
      totalActive = activeSagas.size(),
      totalCompleted = completedSagas.size(),
      totalFailed = failedSagas.size()
    )
  }

  // 長時間実行中のSagaを検出（1時間以上）
  def getLongRunningSagas(thresholdMinutes: Int = 60): List[SagaExecution] = {
    val now = Instant.now()
    val threshold = Duration.ofMinutes(thresholdMinutes.toLong)

    activeSagas.values().asScala.toList
      .filter { execution =>
        val runningTime = Duration.between(execution.startedAt, now)
        runningTime.compareTo(threshold) > 0
      }
      .sortBy(_.startedAt.getEpochSecond)
  }

  private def calculateSuccessRate(completed: Int, failed: Int): Double = {
    val total = completed + failed
    if (total == 0) 100.0
    else (completed.toDouble / total.toDouble) * 100.0
  }
}

final case class SagaTypeStatistics(
  sagaType: SagaType,
  activeCount: Int,
  completedCount: Int,
  failedCount: Int,
  averageCompletionTime: Duration,
  successRate: Double
)

final case class SagaOverallStatistics(
  approvalSaga: SagaTypeStatistics,
  receivingSaga: SagaTypeStatistics,
  paymentSaga: SagaTypeStatistics,
  totalActive: Int,
  totalCompleted: Int,
  totalFailed: Int
)
```

### 9.2.2 Sagaアラートシステム

異常を検知してアラートを発報します。

```scala
package com.example.procurement.monitoring.saga

import com.example.shared.domain.*
import java.time.{Duration, Instant}
import scala.collection.mutable

// Sagaアラート
sealed trait SagaAlert {
  def severity: AlertSeverity
  def message: String
  def sagaId: SagaId
  def occurredAt: Instant
}

object SagaAlert {
  final case class LongRunningSaga(
    sagaId: SagaId,
    sagaType: SagaType,
    runningTime: Duration,
    occurredAt: Instant
  ) extends SagaAlert {
    val severity: AlertSeverity = AlertSeverity.Warning
    val message: String = s"Saga ${sagaId.value} has been running for ${runningTime.toMinutes} minutes"
  }

  final case class SagaFailed(
    sagaId: SagaId,
    sagaType: SagaType,
    errorMessage: String,
    occurredAt: Instant
  ) extends SagaAlert {
    val severity: AlertSeverity = AlertSeverity.Error
    val message: String = s"Saga ${sagaId.value} failed: $errorMessage"
  }

  final case class HighFailureRate(
    sagaType: SagaType,
    failureRate: Double,
    occurredAt: Instant
  ) extends SagaAlert {
    val sagaId: SagaId = SagaId("N/A")
    val severity: AlertSeverity = AlertSeverity.Critical
    val message: String = s"High failure rate for $sagaType: ${failureRate}%"
  }
}

sealed trait AlertSeverity
object AlertSeverity {
  case object Info extends AlertSeverity
  case object Warning extends AlertSeverity
  case object Error extends AlertSeverity
  case object Critical extends AlertSeverity
}

// Sagaアラートマネージャー
class SagaAlertManager(
  monitoringService: SagaMonitoringService,
  notificationService: AlertNotificationService
) {
  private val alertHistory = mutable.ListBuffer[SagaAlert]()
  private val maxHistorySize = 1000

  // アラートチェックを実行
  def checkAlerts(): List[SagaAlert] = {
    val alerts = mutable.ListBuffer[SagaAlert]()

    // 長時間実行中のSagaをチェック
    val longRunningSagas = monitoringService.getLongRunningSagas(thresholdMinutes = 60)
    longRunningSagas.foreach { saga =>
      val alert = SagaAlert.LongRunningSaga(
        sagaId = saga.sagaId,
        sagaType = saga.sagaType,
        runningTime = saga.duration,
        occurredAt = Instant.now()
      )
      alerts += alert
      recordAlert(alert)
    }

    // 失敗率をチェック
    val stats = monitoringService.getOverallStatistics
    checkFailureRate(stats.approvalSaga, alerts)
    checkFailureRate(stats.receivingSaga, alerts)
    checkFailureRate(stats.paymentSaga, alerts)

    alerts.toList
  }

  private def checkFailureRate(stats: SagaTypeStatistics, alerts: mutable.ListBuffer[SagaAlert]): Unit = {
    val failureRate = 100.0 - stats.successRate
    if (failureRate > 10.0) { // 失敗率が10%を超えたらアラート
      val alert = SagaAlert.HighFailureRate(
        sagaType = stats.sagaType,
        failureRate = failureRate,
        occurredAt = Instant.now()
      )
      alerts += alert
      recordAlert(alert)
    }
  }

  private def recordAlert(alert: SagaAlert): Unit = {
    alertHistory += alert
    if (alertHistory.size > maxHistorySize) {
      alertHistory.remove(0)
    }

    // 通知サービスに送信
    notificationService.sendAlert(alert)
  }

  // アラート履歴を取得
  def getAlertHistory(limit: Int = 100): List[SagaAlert] = {
    alertHistory.takeRight(limit).toList
  }
}

// アラート通知サービス
trait AlertNotificationService {
  def sendAlert(alert: SagaAlert): Unit
}

class ConsoleAlertNotificationService extends AlertNotificationService {
  override def sendAlert(alert: SagaAlert): Unit = {
    println(s"[${alert.severity}] ${alert.message} at ${alert.occurredAt}")
  }
}
```

---

## 9.3 仕入先評価

### 9.3.1 納期遵守率の測定

仕入先ごとの納期遵守状況を評価します。

```scala
package com.example.procurement.monitoring.supplier

import com.example.shared.domain.*
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

// 納期遵守記録
final case class DeliveryComplianceRecord(
  supplierId: SupplierId,
  purchaseOrderId: PurchaseOrderId,
  expectedDeliveryDate: LocalDate,
  actualDeliveryDate: LocalDate,
  isOnTime: Boolean,
  delayDays: Int
)

// 仕入先評価メトリクス
class SupplierEvaluationMetrics {
  // 仕入先ごとの納期記録
  private val deliveryRecords = new ConcurrentHashMap[SupplierId, java.util.List[DeliveryComplianceRecord]]()

  // 仕入先ごとの検収結果
  private val inspectionRecords = new ConcurrentHashMap[SupplierId, InspectionResults]()

  // 納期遵守記録を追加
  def recordDelivery(record: DeliveryComplianceRecord): Unit = {
    val records = deliveryRecords.computeIfAbsent(
      record.supplierId,
      _ => new java.util.concurrent.CopyOnWriteArrayList[DeliveryComplianceRecord]()
    )
    records.add(record)
  }

  // 検収結果を記録
  def recordInspection(
    supplierId: SupplierId,
    totalQuantity: Quantity,
    acceptedQuantity: Quantity,
    rejectedQuantity: Quantity
  ): Unit = {
    val results = inspectionRecords.computeIfAbsent(
      supplierId,
      _ => InspectionResults(0, 0, 0)
    )

    val updated = InspectionResults(
      totalQuantity = results.totalQuantity + totalQuantity.value,
      acceptedQuantity = results.acceptedQuantity + acceptedQuantity.value,
      rejectedQuantity = results.rejectedQuantity + rejectedQuantity.value
    )

    inspectionRecords.put(supplierId, updated)
  }

  // 仕入先の納期遵守率を計算
  def getDeliveryComplianceRate(supplierId: SupplierId): Double = {
    Option(deliveryRecords.get(supplierId)) match {
      case Some(records) if !records.isEmpty =>
        val recordList = records.asScala.toList
        val onTimeCount = recordList.count(_.isOnTime)
        (onTimeCount.toDouble / recordList.length.toDouble) * 100.0
      case _ => 0.0
    }
  }

  // 仕入先の平均遅延日数を計算
  def getAverageDelayDays(supplierId: SupplierId): Double = {
    Option(deliveryRecords.get(supplierId)) match {
      case Some(records) if !records.isEmpty =>
        val recordList = records.asScala.toList
        val totalDelay = recordList.filter(!_.isOnTime).map(_.delayDays).sum
        val delayCount = recordList.count(!_.isOnTime)
        if (delayCount == 0) 0.0 else totalDelay.toDouble / delayCount.toDouble
      case _ => 0.0
    }
  }

  // 仕入先の検収合格率を計算
  def getInspectionPassRate(supplierId: SupplierId): Double = {
    Option(inspectionRecords.get(supplierId)) match {
      case Some(results) if results.totalQuantity > 0 =>
        (results.acceptedQuantity.toDouble / results.totalQuantity.toDouble) * 100.0
      case _ => 0.0
    }
  }

  // 仕入先の不良品率を計算
  def getDefectRate(supplierId: SupplierId): Double = {
    Option(inspectionRecords.get(supplierId)) match {
      case Some(results) if results.totalQuantity > 0 =>
        (results.rejectedQuantity.toDouble / results.totalQuantity.toDouble) * 100.0
      case _ => 0.0
    }
  }

  // 仕入先の総合評価を取得
  def getSupplierEvaluation(supplierId: SupplierId): SupplierEvaluation = {
    val complianceRate = getDeliveryComplianceRate(supplierId)
    val avgDelay = getAverageDelayDays(supplierId)
    val passRate = getInspectionPassRate(supplierId)
    val defectRate = getDefectRate(supplierId)

    // 総合スコアを計算（0-100）
    val deliveryScore = complianceRate
    val qualityScore = passRate
    val overallScore = (deliveryScore * 0.5 + qualityScore * 0.5)

    // ランク付け
    val rank = overallScore match {
      case s if s >= 95.0 => SupplierRank.Excellent
      case s if s >= 85.0 => SupplierRank.Good
      case s if s >= 70.0 => SupplierRank.Fair
      case _ => SupplierRank.Poor
    }

    SupplierEvaluation(
      supplierId = supplierId,
      deliveryComplianceRate = complianceRate,
      averageDelayDays = avgDelay,
      inspectionPassRate = passRate,
      defectRate = defectRate,
      overallScore = overallScore,
      rank = rank
    )
  }

  // 全仕入先の評価一覧を取得
  def getAllSupplierEvaluations: List[SupplierEvaluation] = {
    val supplierIds = (deliveryRecords.keySet().asScala ++ inspectionRecords.keySet().asScala).toSet
    supplierIds.map(getSupplierEvaluation).toList.sortBy(_.overallScore)(Ordering[Double].reverse)
  }

  // 低評価の仕入先を取得
  def getLowPerformingSuppliers(threshold: Double = 70.0): List[SupplierEvaluation] = {
    getAllSupplierEvaluations.filter(_.overallScore < threshold)
  }
}

final case class InspectionResults(
  totalQuantity: Int,
  acceptedQuantity: Int,
  rejectedQuantity: Int
)

final case class SupplierEvaluation(
  supplierId: SupplierId,
  deliveryComplianceRate: Double,
  averageDelayDays: Double,
  inspectionPassRate: Double,
  defectRate: Double,
  overallScore: Double,
  rank: SupplierRank
)

sealed trait SupplierRank
object SupplierRank {
  case object Excellent extends SupplierRank // 95%以上
  case object Good extends SupplierRank      // 85%以上
  case object Fair extends SupplierRank      // 70%以上
  case object Poor extends SupplierRank      // 70%未満
}
```

### 9.3.2 仕入先評価レポート生成

定期的に仕入先評価レポートを生成します。

```scala
package com.example.procurement.monitoring.supplier

import com.example.shared.domain.*
import java.time.{LocalDate, YearMonth}

// 仕入先評価レポート
final case class SupplierEvaluationReport(
  reportDate: LocalDate,
  period: YearMonth,
  evaluations: List[SupplierEvaluation],
  summary: SupplierEvaluationSummary
)

final case class SupplierEvaluationSummary(
  totalSuppliers: Int,
  excellentCount: Int,
  goodCount: Int,
  fairCount: Int,
  poorCount: Int,
  averageDeliveryComplianceRate: Double,
  averageInspectionPassRate: Double,
  improvementNeeded: List[SupplierId]
)

// 仕入先評価レポート生成サービス
class SupplierEvaluationReportService(
  metrics: SupplierEvaluationMetrics
) {
  // 月次レポートを生成
  def generateMonthlyReport(period: YearMonth): SupplierEvaluationReport = {
    val evaluations = metrics.getAllSupplierEvaluations

    val excellentCount = evaluations.count(_.rank == SupplierRank.Excellent)
    val goodCount = evaluations.count(_.rank == SupplierRank.Good)
    val fairCount = evaluations.count(_.rank == SupplierRank.Fair)
    val poorCount = evaluations.count(_.rank == SupplierRank.Poor)

    val avgDeliveryCompliance = if (evaluations.isEmpty) 0.0
    else evaluations.map(_.deliveryComplianceRate).sum / evaluations.length

    val avgInspectionPass = if (evaluations.isEmpty) 0.0
    else evaluations.map(_.inspectionPassRate).sum / evaluations.length

    val improvementNeeded = metrics.getLowPerformingSuppliers().map(_.supplierId)

    val summary = SupplierEvaluationSummary(
      totalSuppliers = evaluations.length,
      excellentCount = excellentCount,
      goodCount = goodCount,
      fairCount = fairCount,
      poorCount = poorCount,
      averageDeliveryComplianceRate = avgDeliveryCompliance,
      averageInspectionPassRate = avgInspectionPass,
      improvementNeeded = improvementNeeded
    )

    SupplierEvaluationReport(
      reportDate = LocalDate.now(),
      period = period,
      evaluations = evaluations,
      summary = summary
    )
  }

  // レポートをMarkdown形式で出力
  def generateMarkdownReport(report: SupplierEvaluationReport): String = {
    val sb = new StringBuilder

    sb.append(s"# 仕入先評価レポート\n\n")
    sb.append(s"**レポート日**: ${report.reportDate}\n")
    sb.append(s"**対象期間**: ${report.period}\n\n")

    sb.append("## サマリー\n\n")
    sb.append(s"- 総仕入先数: ${report.summary.totalSuppliers}\n")
    sb.append(s"- Excellentランク: ${report.summary.excellentCount}\n")
    sb.append(s"- Goodランク: ${report.summary.goodCount}\n")
    sb.append(s"- Fairランク: ${report.summary.fairCount}\n")
    sb.append(s"- Poorランク: ${report.summary.poorCount}\n")
    sb.append(s"- 平均納期遵守率: ${report.summary.averageDeliveryComplianceRate}%\n")
    sb.append(s"- 平均検収合格率: ${report.summary.averageInspectionPassRate}%\n\n")

    sb.append("## 仕入先詳細\n\n")
    sb.append("| 仕入先ID | 納期遵守率 | 平均遅延日数 | 検収合格率 | 不良品率 | 総合スコア | ランク |\n")
    sb.append("|---------|-----------|------------|-----------|---------|-----------|-------|\n")

    report.evaluations.foreach { eval =>
      sb.append(s"| ${eval.supplierId.value} | ")
      sb.append(f"${eval.deliveryComplianceRate}%.1f%% | ")
      sb.append(f"${eval.averageDelayDays}%.1f日 | ")
      sb.append(f"${eval.inspectionPassRate}%.1f%% | ")
      sb.append(f"${eval.defectRate}%.1f%% | ")
      sb.append(f"${eval.overallScore}%.1f | ")
      sb.append(s"${eval.rank} |\n")
    }

    if (report.summary.improvementNeeded.nonEmpty) {
      sb.append("\n## 改善が必要な仕入先\n\n")
      report.summary.improvementNeeded.foreach { supplierId =>
        sb.append(s"- ${supplierId.value}\n")
      }
    }

    sb.toString()
  }
}
```

---

## 9.4 統合監視ダッシュボード

### 9.4.1 ダッシュボードデータ集約

全てのメトリクスを集約してダッシュボードに表示します。

```scala
package com.example.procurement.monitoring.dashboard

import com.example.procurement.monitoring.metrics.*
import com.example.procurement.monitoring.saga.*
import com.example.procurement.monitoring.supplier.*
import java.time.Instant

// ダッシュボードデータ
final case class ProcurementDashboard(
  timestamp: Instant,
  purchaseOrderMetrics: PurchaseOrderStatistics,
  approvalMetrics: ApprovalStatistics,
  receivingMetrics: ReceivingStatistics,
  paymentMetrics: PaymentStatistics,
  sagaMetrics: SagaOverallStatistics,
  topSuppliers: List[SupplierEvaluation],
  recentAlerts: List[SagaAlert]
)

// ダッシュボードサービス
class ProcurementDashboardService(
  poMetrics: PurchaseOrderProcessingMetrics,
  approvalMetrics: ApprovalProcessingMetrics,
  receivingMetrics: ReceivingInspectionMetrics,
  paymentMetrics: PaymentMetrics,
  sagaMonitoring: SagaMonitoringService,
  supplierMetrics: SupplierEvaluationMetrics,
  alertManager: SagaAlertManager
) {
  // ダッシュボードデータを取得
  def getDashboardData: ProcurementDashboard = {
    ProcurementDashboard(
      timestamp = Instant.now(),
      purchaseOrderMetrics = poMetrics.getStatistics,
      approvalMetrics = approvalMetrics.getStatistics,
      receivingMetrics = receivingMetrics.getStatistics,
      paymentMetrics = paymentMetrics.getStatistics,
      sagaMetrics = sagaMonitoring.getOverallStatistics,
      topSuppliers = supplierMetrics.getAllSupplierEvaluations.take(10),
      recentAlerts = alertManager.getAlertHistory(limit = 20)
    )
  }

  // JSON形式で出力
  def getDashboardJson: String = {
    import io.circe.generic.auto.*
    import io.circe.syntax.*

    val dashboard = getDashboardData
    dashboard.asJson.spaces2
  }
}
```

---

## まとめ

本章では、発注管理システムの運用に必要な監視機能を実装しました。

### 実装した内容

1. **ビジネスメトリクス**
   - 発注処理レート: 月間目標3,000件の達成状況監視
   - 承認処理時間: 平均承認時間、承認待ち件数、長時間承認待ちの検出
   - 入荷検収状況: 検収完了率、差異発生率、差異金額の追跡
   - 支払状況: 月次支払予定金額、支払遅延件数、3-way matching成功率

2. **Sagaの監視**
   - Saga実行状態の追跡（実行中/完了/失敗）
   - Sagaタイプ別の統計情報
   - 長時間実行中のSaga検出
   - アラートシステム（長時間実行、失敗、高失敗率）

3. **仕入先評価**
   - 納期遵守率の測定
   - 平均遅延日数の計算
   - 検収合格率と不良品率の追跡
   - 総合評価とランク付け（Excellent/Good/Fair/Poor）
   - 月次評価レポートの生成

4. **統合ダッシュボード**
   - 全メトリクスの集約
   - リアルタイムデータの可視化
   - JSON形式でのデータ出力

### 監視のベストプラクティス

- **ビジネスKPIの追跡**: 技術的メトリクスだけでなく、ビジネス価値を測定
- **プロアクティブなアラート**: 問題が深刻化する前に検知
- **仕入先パフォーマンス**: サプライチェーン全体の品質を向上
- **Sagaの可観測性**: 分散トランザクションの健全性を監視

### 次章の予告

次章では、需要予測に基づく自動発注、複数仕入先の最適化、グローバル調達など、発注管理の高度なトピックを実装します。
