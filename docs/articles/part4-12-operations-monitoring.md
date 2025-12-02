# 【第4部 第12章】運用とモニタリング：ビジネスメトリクスとアラート

## 本章の目的

本章では、受注管理システムの運用とモニタリングについて詳しく説明します。ビジネスメトリクスの収集、Sagaの監視、与信管理の監視、アラート通知を実装することで、システムの健全性を維持し、ビジネス上の問題を早期に発見します。Prometheus、Grafana、OpenTelemetryなどの標準的なツールを使用した監視基盤を構築します。

## 12.1 ビジネスメトリクス

### 12.1.1 注文処理レートの監視

月間50,000件（1日約1,600件）の注文を安定して処理できているか監視します。

```scala
package com.example.order.monitoring

import com.example.order.domain._
import zio._
import zio.metrics._
import io.prometheus.client._
import java.time.{LocalDate, YearMonth}

class OrderMetricsCollector(
  orderRepository: OrderRepository
) {

  // Prometheusカウンター: 注文作成数
  private val orderCreatedCounter = Counter.build()
    .name("orders_created_total")
    .help("Total number of orders created")
    .labelNames("status", "customer_type")
    .register()

  // Prometheusゲージ: 処理中の注文数
  private val orderInProgressGauge = Gauge.build()
    .name("orders_in_progress")
    .help("Number of orders currently in progress")
    .labelNames("status")
    .register()

  // Prometheusヒストグラム: 注文処理時間
  private val orderProcessingDuration = Histogram.build()
    .name("order_processing_duration_seconds")
    .help("Order processing duration in seconds")
    .buckets(0.1, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0)
    .register()

  // 注文作成時にメトリクスを記録
  def recordOrderCreated(
    order: Order,
    customerType: CustomerType
  ): Task[Unit] = {
    ZIO.attempt {
      orderCreatedCounter
        .labels(order.status.toString, customerType.toString)
        .inc()
    }
  }

  // 注文処理時間を記録
  def recordOrderProcessingTime(durationSeconds: Double): Task[Unit] = {
    ZIO.attempt {
      orderProcessingDuration.observe(durationSeconds)
    }
  }

  // 処理中の注文数を更新
  def updateInProgressOrders(): Task[Unit] = {
    for {
      // 各ステータスの注文数を集計
      created <- orderRepository.countByStatus(OrderStatus.Created)
      stockReserved <- orderRepository.countByStatus(OrderStatus.StockReserved)
      creditApproved <- orderRepository.countByStatus(OrderStatus.CreditApproved)
      confirmed <- orderRepository.countByStatus(OrderStatus.Confirmed)
      shipped <- orderRepository.countByStatus(OrderStatus.Shipped)

      _ <- ZIO.attempt {
        orderInProgressGauge.labels("Created").set(created.toDouble)
        orderInProgressGauge.labels("StockReserved").set(stockReserved.toDouble)
        orderInProgressGauge.labels("CreditApproved").set(creditApproved.toDouble)
        orderInProgressGauge.labels("Confirmed").set(confirmed.toDouble)
        orderInProgressGauge.labels("Shipped").set(shipped.toDouble)
      }
    } yield ()
  }

  // 日次注文処理レートを取得
  def getDailyOrderRate(date: LocalDate): Task[OrderRateMetrics] = {
    for {
      orders <- orderRepository.findByDate(date)
    } yield {
      val totalOrders = orders.size
      val successfulOrders = orders.count { order =>
        order.status == OrderStatus.Delivered || order.status == OrderStatus.Shipped
      }
      val failedOrders = orders.count(_.status == OrderStatus.Cancelled)

      OrderRateMetrics(
        date = date,
        totalOrders = totalOrders,
        successfulOrders = successfulOrders,
        failedOrders = failedOrders,
        successRate = if (totalOrders > 0) {
          BigDecimal(successfulOrders) / BigDecimal(totalOrders) * 100
        } else BigDecimal(0)
      )
    }
  }

  // 月次注文処理レートを取得
  def getMonthlyOrderRate(yearMonth: YearMonth): Task[MonthlyOrderMetrics] = {
    for {
      orders <- orderRepository.findByMonth(yearMonth)
    } yield {
      val totalOrders = orders.size
      val totalAmount = Money.sum(orders.map(_.totalAmount))
      val averageAmount = if (totalOrders > 0) {
        totalAmount / totalOrders
      } else Money.zero()

      val byStatus = orders.groupBy(_.status).view.mapValues(_.size).toMap

      MonthlyOrderMetrics(
        yearMonth = yearMonth,
        totalOrders = totalOrders,
        totalAmount = totalAmount,
        averageAmount = averageAmount,
        ordersByStatus = byStatus,
        peakDailyOrders = calculatePeakDailyOrders(orders)
      )
    }
  }

  private def calculatePeakDailyOrders(orders: List[Order]): Int = {
    orders
      .groupBy(_.orderDate)
      .values
      .map(_.size)
      .maxOption
      .getOrElse(0)
  }
}

// 日次注文レートメトリクス
final case class OrderRateMetrics(
  date: LocalDate,
  totalOrders: Int,
  successfulOrders: Int,
  failedOrders: Int,
  successRate: BigDecimal
)

// 月次注文メトリクス
final case class MonthlyOrderMetrics(
  yearMonth: YearMonth,
  totalOrders: Int,
  totalAmount: Money,
  averageAmount: Money,
  ordersByStatus: Map[OrderStatus, Int],
  peakDailyOrders: Int  // ピーク時の1日注文数
)
```

### 12.1.2 Saga完了率の監視

Sagaの成功率を監視し、目標の95%以上を維持します。

```scala
package com.example.order.monitoring

import com.example.order.domain.saga._
import zio._
import io.prometheus.client._
import scala.concurrent.duration._

class SagaMetricsCollector(
  sagaRepository: SagaRepository
) {

  // Sagaカウンター
  private val sagaStartedCounter = Counter.build()
    .name("sagas_started_total")
    .help("Total number of sagas started")
    .register()

  private val sagaCompletedCounter = Counter.build()
    .name("sagas_completed_total")
    .help("Total number of sagas completed")
    .labelNames("result")  // "success" or "failure"
    .register()

  // Saga完了時間ヒストグラム
  private val sagaDuration = Histogram.build()
    .name("saga_duration_seconds")
    .help("Saga completion duration in seconds")
    .buckets(1.0, 2.0, 5.0, 10.0, 30.0, 60.0)
    .register()

  // ステップ別失敗カウンター
  private val sagaStepFailedCounter = Counter.build()
    .name("saga_step_failed_total")
    .help("Total number of saga step failures")
    .labelNames("step")
    .register()

  // Saga開始を記録
  def recordSagaStarted(): Task[Unit] = {
    ZIO.attempt {
      sagaStartedCounter.inc()
    }
  }

  // Saga完了を記録
  def recordSagaCompleted(
    result: SagaResult,
    durationSeconds: Double
  ): Task[Unit] = {
    ZIO.attempt {
      result match {
        case SagaResult.Success(_, _) =>
          sagaCompletedCounter.labels("success").inc()
        case SagaResult.Failure(_, _, _, _) =>
          sagaCompletedCounter.labels("failure").inc()
      }

      sagaDuration.observe(durationSeconds)
    }
  }

  // ステップ失敗を記録
  def recordStepFailed(step: SagaStep): Task[Unit] = {
    ZIO.attempt {
      sagaStepFailedCounter.labels(step.toString).inc()
    }
  }

  // Saga統計を取得
  def getSagaStatistics(
    from: java.time.Instant,
    to: java.time.Instant
  ): Task[SagaStatistics] = {
    for {
      allSagas <- sagaRepository.findByTimeRange(from, to)
    } yield {
      val totalSagas = allSagas.size
      val completedSagas = allSagas.count(_.status == SagaStatus.Completed)
      val failedSagas = allSagas.count(_.status == SagaStatus.Failed)
      val compensatedSagas = allSagas.count(_.status == SagaStatus.Compensated)

      val completionRate = if (totalSagas > 0) {
        BigDecimal(completedSagas) / BigDecimal(totalSagas) * 100
      } else BigDecimal(0)

      val averageDuration = if (completedSagas > 0) {
        val durations = allSagas
          .filter(_.status == SagaStatus.Completed)
          .flatMap { saga =>
            saga.completedAt.map { completed =>
              java.time.Duration.between(saga.startedAt, completed).toMillis
            }
          }
        durations.sum / durations.size
      } else 0L

      // ステップ別の失敗理由集計
      val failuresByStep = allSagas
        .filter(_.status == SagaStatus.Failed)
        .flatMap(_.currentStep)
        .groupBy(identity)
        .view
        .mapValues(_.size)
        .toMap

      SagaStatistics(
        totalSagas = totalSagas,
        completedSagas = completedSagas,
        failedSagas = failedSagas,
        compensatedSagas = compensatedSagas,
        completionRate = completionRate,
        averageDurationMs = averageDuration,
        failuresByStep = failuresByStep
      )
    }
  }
}

// Saga統計
final case class SagaStatistics(
  totalSagas: Int,
  completedSagas: Int,
  failedSagas: Int,
  compensatedSagas: Int,
  completionRate: BigDecimal,  // %
  averageDurationMs: Long,
  failuresByStep: Map[SagaStep, Int]
)
```

### 12.1.3 与信チェックメトリクス

与信チェックの承認率と処理時間を監視します（目標: 100ms以内）。

```scala
package com.example.order.monitoring

import com.example.order.domain._
import zio._
import io.prometheus.client._

class CreditCheckMetricsCollector {

  // 与信チェックカウンター
  private val creditCheckCounter = Counter.build()
    .name("credit_checks_total")
    .help("Total number of credit checks")
    .labelNames("result")  // "approved" or "rejected"
    .register()

  // 与信チェック処理時間ヒストグラム
  private val creditCheckDuration = Histogram.build()
    .name("credit_check_duration_seconds")
    .help("Credit check duration in seconds")
    .buckets(0.01, 0.05, 0.1, 0.2, 0.5, 1.0)
    .register()

  // 与信超過アラートカウンター
  private val creditExceededCounter = Counter.build()
    .name("credit_exceeded_total")
    .help("Total number of credit exceeded events")
    .labelNames("severity")  // "warning" or "critical"
    .register()

  // 与信チェック結果を記録
  def recordCreditCheck(
    result: Either[CreditError, CreditApproval],
    durationSeconds: Double
  ): Task[Unit] = {
    ZIO.attempt {
      result match {
        case Right(_) =>
          creditCheckCounter.labels("approved").inc()
        case Left(_) =>
          creditCheckCounter.labels("rejected").inc()
      }

      creditCheckDuration.observe(durationSeconds)
    }
  }

  // 与信超過を記録
  def recordCreditExceeded(severity: AlertSeverity): Task[Unit] = {
    ZIO.attempt {
      severity match {
        case AlertSeverity.Warning =>
          creditExceededCounter.labels("warning").inc()
        case AlertSeverity.Critical =>
          creditExceededCounter.labels("critical").inc()
      }
    }
  }

  // 与信チェック統計を取得
  def getCreditCheckStatistics(
    from: java.time.Instant,
    to: java.time.Instant
  ): Task[CreditCheckStatistics] = {
    // 実際にはログやデータベースから集計
    ZIO.succeed(CreditCheckStatistics(
      totalChecks = 10000,
      approvedChecks = 9500,
      rejectedChecks = 500,
      approvalRate = BigDecimal("95.0"),
      averageDurationMs = 45,
      p95DurationMs = 80,
      p99DurationMs = 150
    ))
  }
}

// 与信チェック統計
final case class CreditCheckStatistics(
  totalChecks: Int,
  approvedChecks: Int,
  rejectedChecks: Int,
  approvalRate: BigDecimal,  // %
  averageDurationMs: Long,
  p95DurationMs: Long,  // 95パーセンタイル
  p99DurationMs: Long   // 99パーセンタイル
)
```

### 12.1.4 請求・入金状況メトリクス

月次の請求金額、入金率、未入金金額を監視します。

```scala
package com.example.order.monitoring

import com.example.order.domain._
import zio._
import io.prometheus.client._
import java.time.YearMonth

class BillingMetricsCollector(
  invoiceRepository: InvoiceRepository
) {

  // 請求金額ゲージ
  private val billingAmountGauge = Gauge.build()
    .name("billing_amount")
    .help("Current billing amount")
    .labelNames("year_month", "status")
    .register()

  // 入金率ゲージ
  private val collectionRateGauge = Gauge.build()
    .name("collection_rate")
    .help("Payment collection rate")
    .labelNames("year_month")
    .register()

  // 未入金金額ゲージ
  private val unpaidAmountGauge = Gauge.build()
    .name("unpaid_amount")
    .help("Total unpaid amount")
    .register()

  // 請求・入金メトリクスを更新
  def updateBillingMetrics(yearMonth: YearMonth): Task[Unit] = {
    for {
      invoices <- invoiceRepository.findByMonth(yearMonth)

      totalBilled = Money.sum(invoices.map(_.totalAmount))
      totalPaid = Money.sum(invoices.map(_.paidAmount))
      totalUnpaid = Money.sum(invoices.map(_.balanceAmount))

      collectionRate = if (totalBilled.amount > 0) {
        (totalPaid.amount / totalBilled.amount) * 100
      } else BigDecimal(0)

      _ <- ZIO.attempt {
        val ymStr = yearMonth.toString

        billingAmountGauge
          .labels(ymStr, "total")
          .set(totalBilled.amount.toDouble)

        billingAmountGauge
          .labels(ymStr, "paid")
          .set(totalPaid.amount.toDouble)

        billingAmountGauge
          .labels(ymStr, "unpaid")
          .set(totalUnpaid.amount.toDouble)

        collectionRateGauge
          .labels(ymStr)
          .set(collectionRate.toDouble)

        unpaidAmountGauge.set(totalUnpaid.amount.toDouble)
      }
    } yield ()
  }

  // 請求・入金統計を取得
  def getBillingStatistics(yearMonth: YearMonth): Task[BillingStatistics] = {
    for {
      invoices <- invoiceRepository.findByMonth(yearMonth)
      overdueInvoices <- invoiceRepository.findOverdue()
    } yield {
      val totalInvoices = invoices.size
      val totalBilled = Money.sum(invoices.map(_.totalAmount))
      val totalPaid = Money.sum(invoices.map(_.paidAmount))
      val totalUnpaid = Money.sum(invoices.map(_.balanceAmount))

      val fullyPaid = invoices.count(_.status == InvoiceStatus.FullyPaid)
      val partiallyPaid = invoices.count(_.status == InvoiceStatus.PartiallyPaid)
      val unpaid = invoices.count(_.status == InvoiceStatus.Issued)

      val collectionRate = if (totalBilled.amount > 0) {
        (totalPaid.amount / totalBilled.amount) * 100
      } else BigDecimal(0)

      BillingStatistics(
        yearMonth = yearMonth,
        totalInvoices = totalInvoices,
        totalBilled = totalBilled,
        totalPaid = totalPaid,
        totalUnpaid = totalUnpaid,
        fullyPaidCount = fullyPaid,
        partiallyPaidCount = partiallyPaid,
        unpaidCount = unpaid,
        collectionRate = collectionRate,
        overdueInvoices = overdueInvoices.size,
        oldestOverdueDays = calculateOldestOverdueDays(overdueInvoices)
      )
    }
  }

  private def calculateOldestOverdueDays(invoices: List[Invoice]): Int = {
    invoices
      .map { invoice =>
        java.time.temporal.ChronoUnit.DAYS
          .between(invoice.dueDate, java.time.LocalDate.now())
          .toInt
      }
      .maxOption
      .getOrElse(0)
  }
}

// 請求・入金統計
final case class BillingStatistics(
  yearMonth: YearMonth,
  totalInvoices: Int,
  totalBilled: Money,
  totalPaid: Money,
  totalUnpaid: Money,
  fullyPaidCount: Int,
  partiallyPaidCount: Int,
  unpaidCount: Int,
  collectionRate: BigDecimal,  // %
  overdueInvoices: Int,
  oldestOverdueDays: Int
)
```

## 12.2 Sagaの監視

### 12.2.1 Sagaステータスダッシュボード

進行中、失敗、補償処理中のSagaを可視化します。

```scala
package com.example.order.monitoring

import com.example.order.domain.saga._
import zio._

class SagaDashboardService(
  sagaRepository: SagaRepository
) {

  // 進行中のSaga一覧を取得
  def getInProgressSagas(): Task[List[SagaSummary]] = {
    for {
      sagas <- sagaRepository.findByStatus(SagaStatus.InProgress)
    } yield {
      sagas.map(toSummary)
    }
  }

  // 失敗したSaga一覧を取得
  def getFailedSagas(): Task[List[SagaSummary]] = {
    for {
      sagas <- sagaRepository.findByStatus(SagaStatus.Failed)
    } yield {
      sagas.map(toSummary)
    }
  }

  // 補償処理中のSaga一覧を取得
  def getCompensatingSagas(): Task[List[SagaSummary]] = {
    for {
      sagas <- sagaRepository.findByStatus(SagaStatus.Compensating)
    } yield {
      sagas.map(toSummary)
    }
  }

  // 長時間実行中のSagaを検出
  def getLongRunningSagas(
    thresholdMinutes: Int = 30
  ): Task[List[SagaSummary]] = {
    for {
      now <- Clock.instant
      sagas <- sagaRepository.findByStatus(SagaStatus.InProgress)
      longRunning = sagas.filter { saga =>
        val durationMinutes = java.time.Duration.between(saga.startedAt, now).toMinutes
        durationMinutes > thresholdMinutes
      }
    } yield {
      longRunning.map(toSummary)
    }
  }

  // Sagaの詳細を取得
  def getSagaDetails(sagaId: SagaId): Task[Option[SagaDetails]] = {
    for {
      saga <- sagaRepository.findById(sagaId)
    } yield {
      saga.map { s =>
        val duration = s.completedAt.map { completed =>
          java.time.Duration.between(s.startedAt, completed).toMillis
        }

        SagaDetails(
          sagaId = s.id,
          orderId = s.orderId,
          customerId = s.customerId,
          status = s.status,
          currentStep = s.currentStep,
          completedSteps = s.completedSteps,
          failureReason = s.failureReason,
          startedAt = s.startedAt,
          completedAt = s.completedAt,
          durationMs = duration
        )
      }
    }
  }

  private def toSummary(saga: OrderSaga): SagaSummary = {
    val duration = saga.completedAt.map { completed =>
      java.time.Duration.between(saga.startedAt, completed).toMillis
    }.getOrElse {
      java.time.Duration.between(saga.startedAt, java.time.Instant.now()).toMillis
    }

    SagaSummary(
      sagaId = saga.id,
      orderId = saga.orderId,
      status = saga.status,
      currentStep = saga.currentStep,
      completedStepsCount = saga.completedSteps.size,
      failureReason = saga.failureReason,
      durationMs = duration
    )
  }
}

// Sagaサマリー
final case class SagaSummary(
  sagaId: SagaId,
  orderId: OrderId,
  status: SagaStatus,
  currentStep: Option[SagaStep],
  completedStepsCount: Int,
  failureReason: Option[String],
  durationMs: Long
)

// Saga詳細
final case class SagaDetails(
  sagaId: SagaId,
  orderId: OrderId,
  customerId: CustomerId,
  status: SagaStatus,
  currentStep: Option[SagaStep],
  completedSteps: List[SagaStep],
  failureReason: Option[String],
  startedAt: java.time.Instant,
  completedAt: Option[java.time.Instant],
  durationMs: Option[Long]
)
```

### 12.2.2 Saga失敗アラート

失敗率が閾値を超えた場合、アラートを発行します。

```scala
package com.example.order.monitoring

import com.example.order.domain.saga._
import zio._
import scala.concurrent.duration._

class SagaAlertService(
  sagaMetricsCollector: SagaMetricsCollector,
  alertNotifier: AlertNotifier
) {

  // Saga失敗率を監視
  def monitorSagaFailureRate(
    checkInterval: Duration = 5.minutes,
    warningThreshold: BigDecimal = BigDecimal("5.0"),   // 5%
    criticalThreshold: BigDecimal = BigDecimal("10.0")  // 10%
  ): Task[Unit] = {
    (for {
      now <- Clock.instant
      from = now.minusSeconds(checkInterval.toSeconds)

      statistics <- sagaMetricsCollector.getSagaStatistics(from, now)

      failureRate = if (statistics.totalSagas > 0) {
        BigDecimal(statistics.failedSagas) / BigDecimal(statistics.totalSagas) * 100
      } else BigDecimal(0)

      _ <- if (failureRate >= criticalThreshold) {
        alertNotifier.sendAlert(Alert(
          severity = AlertSeverity.Critical,
          title = "Saga失敗率が重大レベル",
          message = s"Saga失敗率が${failureRate}%に達しています（閾値: ${criticalThreshold}%）。" +
                    s"総Saga数: ${statistics.totalSagas}, 失敗数: ${statistics.failedSagas}",
          metadata = Map(
            "failureRate" -> failureRate.toString,
            "totalSagas" -> statistics.totalSagas.toString,
            "failedSagas" -> statistics.failedSagas.toString,
            "failuresByStep" -> statistics.failuresByStep.toString
          )
        ))
      } else if (failureRate >= warningThreshold) {
        alertNotifier.sendAlert(Alert(
          severity = AlertSeverity.Warning,
          title = "Saga失敗率が警告レベル",
          message = s"Saga失敗率が${failureRate}%に達しています（閾値: ${warningThreshold}%）。",
          metadata = Map(
            "failureRate" -> failureRate.toString,
            "totalSagas" -> statistics.totalSagas.toString,
            "failedSagas" -> statistics.failedSagas.toString
          )
        ))
      } else {
        ZIO.unit
      }
    } yield ())
      .repeat(Schedule.fixed(checkInterval))
      .fork
      .unit
  }

  // タイムアウト頻発を監視
  def monitorTimeouts(
    checkInterval: Duration = 5.minutes,
    threshold: Int = 10  // 10回以上のタイムアウトで警告
  ): Task[Unit] = {
    (for {
      // タイムアウトカウントを取得（実際にはメトリクスから）
      timeoutCount <- ZIO.succeed(5)  // 仮の値

      _ <- if (timeoutCount >= threshold) {
        alertNotifier.sendAlert(Alert(
          severity = AlertSeverity.Warning,
          title = "Sagaタイムアウトが頻発",
          message = s"過去${checkInterval.toMinutes}分間に${timeoutCount}回のタイムアウトが発生しました。",
          metadata = Map(
            "timeoutCount" -> timeoutCount.toString,
            "interval" -> checkInterval.toString
          )
        ))
      } else {
        ZIO.unit
      }
    } yield ())
      .repeat(Schedule.fixed(checkInterval))
      .fork
      .unit
  }
}

// アラート
final case class Alert(
  severity: AlertSeverity,
  title: String,
  message: String,
  metadata: Map[String, String] = Map.empty,
  timestamp: java.time.Instant = java.time.Instant.now()
)

// アラート通知インターフェース
trait AlertNotifier {
  def sendAlert(alert: Alert): Task[Unit]
}

// Slack通知実装
class SlackAlertNotifier(webhookUrl: String) extends AlertNotifier {
  def sendAlert(alert: Alert): Task[Unit] = {
    val color = alert.severity match {
      case AlertSeverity.Warning => "warning"
      case AlertSeverity.Critical => "danger"
    }

    val payload = s"""
      |{
      |  "attachments": [{
      |    "color": "$color",
      |    "title": "${alert.title}",
      |    "text": "${alert.message}",
      |    "fields": [
      |      ${alert.metadata.map { case (k, v) => s"""{"title": "$k", "value": "$v", "short": true}""" }.mkString(",\n")}
      |    ],
      |    "footer": "Order Management System",
      |    "ts": ${alert.timestamp.getEpochSecond}
      |  }]
      |}
      |""".stripMargin

    // HTTPクライアントでSlackに送信
    ZIO.logInfo(s"Sending alert to Slack: ${alert.title}")
  }
}
```

## 12.3 与信管理の監視

### 12.3.1 与信使用率の監視

取引先ごとの与信使用率を監視し、リスクを検知します。

```scala
package com.example.order.monitoring

import com.example.order.domain._
import zio._

class CreditMonitoringDashboard(
  creditLimitRepository: CreditLimitRepository,
  creditCheckService: CreditCheckService
) {

  // 与信使用率が高い顧客を検出
  def getHighUtilizationCustomers(
    threshold: BigDecimal = BigDecimal("0.80")  // 80%
  ): Task[List[CustomerCreditSummary]] = {
    for {
      allCreditLimits <- creditLimitRepository.findAll()
      summaries <- ZIO.foreach(allCreditLimits) { creditLimit =>
        for {
          utilizationRate <- creditCheckService.calculateUtilizationRate(creditLimit.customerId)
        } yield CustomerCreditSummary(
          customerId = creditLimit.customerId,
          limitAmount = creditLimit.limitAmount,
          usedAmount = creditLimit.usedAmount,
          reservedAmount = creditLimit.reservedAmount,
          availableAmount = creditLimit.availableAmount,
          utilizationRate = utilizationRate
        )
      }
      highUtilization = summaries.filter(_.utilizationRate >= threshold)
    } yield highUtilization.sortBy(_.utilizationRate).reverse
  }

  // 与信超過リスクのある顧客を取得
  def getCreditRiskCustomers(): Task[List[CreditRiskSummary]] = {
    for {
      highUtilization <- getHighUtilizationCustomers(BigDecimal("0.90"))
      riskSummaries <- ZIO.foreach(highUtilization) { summary =>
        for {
          alert <- creditCheckService.checkCreditAlert(summary.customerId)
        } yield CreditRiskSummary(
          customerSummary = summary,
          alert = alert,
          riskLevel = determineRiskLevel(summary.utilizationRate)
        )
      }
    } yield riskSummaries.sortBy(_.riskLevel.ordinal).reverse
  }

  private def determineRiskLevel(utilizationRate: BigDecimal): RiskLevel = {
    if (utilizationRate >= BigDecimal("0.95")) RiskLevel.Critical
    else if (utilizationRate >= BigDecimal("0.90")) RiskLevel.High
    else if (utilizationRate >= BigDecimal("0.80")) RiskLevel.Medium
    else RiskLevel.Low
  }
}

// 顧客与信サマリー
final case class CustomerCreditSummary(
  customerId: CustomerId,
  limitAmount: Money,
  usedAmount: Money,
  reservedAmount: Money,
  availableAmount: Money,
  utilizationRate: BigDecimal
)

// 与信リスクサマリー
final case class CreditRiskSummary(
  customerSummary: CustomerCreditSummary,
  alert: Option[CreditAlert],
  riskLevel: RiskLevel
)

// リスクレベル
sealed trait RiskLevel {
  def ordinal: Int
}
object RiskLevel {
  case object Low extends RiskLevel { val ordinal = 1 }
  case object Medium extends RiskLevel { val ordinal = 2 }
  case object High extends RiskLevel { val ordinal = 3 }
  case object Critical extends RiskLevel { val ordinal = 4 }
}
```

### 12.3.2 与信超過アラート

使用率90%超で警告、95%超で重大アラートを発行します。

```scala
package com.example.order.monitoring

import com.example.order.domain._
import zio._
import scala.concurrent.duration._

class CreditAlertService(
  creditMonitoringDashboard: CreditMonitoringDashboard,
  alertNotifier: AlertNotifier
) {

  // 与信超過を監視
  def monitorCreditUtilization(
    checkInterval: Duration = 10.minutes
  ): Task[Unit] = {
    (for {
      riskCustomers <- creditMonitoringDashboard.getCreditRiskCustomers()

      _ <- ZIO.foreach_(riskCustomers) { risk =>
        risk.riskLevel match {
          case RiskLevel.Critical =>
            alertNotifier.sendAlert(Alert(
              severity = AlertSeverity.Critical,
              title = s"与信超過リスク（重大）: ${risk.customerSummary.customerId.value}",
              message = s"与信使用率が${formatPercentage(risk.customerSummary.utilizationRate)}に達しています。" +
                        s"利用可能額: ${risk.customerSummary.availableAmount.formatted}",
              metadata = Map(
                "customerId" -> risk.customerSummary.customerId.value,
                "utilizationRate" -> formatPercentage(risk.customerSummary.utilizationRate),
                "limitAmount" -> risk.customerSummary.limitAmount.amount.toString,
                "usedAmount" -> risk.customerSummary.usedAmount.amount.toString,
                "availableAmount" -> risk.customerSummary.availableAmount.amount.toString
              )
            ))

          case RiskLevel.High =>
            alertNotifier.sendAlert(Alert(
              severity = AlertSeverity.Warning,
              title = s"与信超過リスク（警告）: ${risk.customerSummary.customerId.value}",
              message = s"与信使用率が${formatPercentage(risk.customerSummary.utilizationRate)}に達しています。",
              metadata = Map(
                "customerId" -> risk.customerSummary.customerId.value,
                "utilizationRate" -> formatPercentage(risk.customerSummary.utilizationRate),
                "availableAmount" -> risk.customerSummary.availableAmount.amount.toString
              )
            ))

          case _ =>
            ZIO.unit
        }
      }
    } yield ())
      .repeat(Schedule.fixed(checkInterval))
      .fork
      .unit
  }

  private def formatPercentage(rate: BigDecimal): String = {
    s"${(rate * 100).setScale(1, BigDecimal.RoundingMode.HALF_UP)}%"
  }
}
```

## 12.4 統合モニタリングダッシュボード

### 12.4.1 Grafanaダッシュボード設定

```yaml
# grafana-dashboard.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: order-management-dashboard
data:
  dashboard.json: |
    {
      "dashboard": {
        "title": "受注管理システム - 総合ダッシュボード",
        "panels": [
          {
            "title": "注文処理レート",
            "targets": [
              {
                "expr": "rate(orders_created_total[5m])",
                "legendFormat": "注文/秒"
              }
            ]
          },
          {
            "title": "Saga完了率",
            "targets": [
              {
                "expr": "rate(sagas_completed_total{result=\"success\"}[5m]) / rate(sagas_started_total[5m]) * 100",
                "legendFormat": "成功率(%)"
              }
            ]
          },
          {
            "title": "与信チェック処理時間",
            "targets": [
              {
                "expr": "histogram_quantile(0.95, rate(credit_check_duration_seconds_bucket[5m]))",
                "legendFormat": "95パーセンタイル"
              },
              {
                "expr": "histogram_quantile(0.99, rate(credit_check_duration_seconds_bucket[5m]))",
                "legendFormat": "99パーセンタイル"
              }
            ]
          },
          {
            "title": "請求・入金状況",
            "targets": [
              {
                "expr": "collection_rate",
                "legendFormat": "入金率(%)"
              }
            ]
          }
        ]
      }
    }
```

## 12.5 まとめ

本章では、受注管理システムの運用とモニタリングについて詳しく説明しました。

**実装のポイント**:

1. **ビジネスメトリクス**: Prometheusによる注文処理レート、Saga完了率、与信チェック、請求・入金の監視
2. **Sagaの監視**: 進行中・失敗・補償処理中のSagaダッシュボード、失敗率アラート
3. **与信管理の監視**: 使用率80%以上の顧客検出、90%超で警告、95%超で重大アラート
4. **アラート通知**: Slackへのリアルタイム通知、重要度に応じた色分け
5. **統合ダッシュボード**: Grafanaによる可視化、リアルタイムメトリクス

**監視目標**:
```
- Saga完了率: 95%以上
- 与信チェック処理時間: 95パーセンタイル < 100ms
- 注文処理レスポンス: 95パーセンタイル < 500ms
- 入金率: 90%以上（30日以内）
```

**次章では**（outline.mdに従えば）:
- 第13章: 高度なトピック（イベント駆動による他システム連携、分散トレーシング、まとめと実践演習）

運用とモニタリングにより、システムの健全性を継続的に維持し、ビジネス上の問題を早期に発見・対処できます。Prometheus、Grafana、Slackなどの標準ツールを活用することで、効率的な運用が実現できます。
