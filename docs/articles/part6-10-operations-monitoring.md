# 第6部10章：運用とモニタリング

## 本章の目的

本章では、会計システムの運用とモニタリングの実装を学びます。安定した会計システムの運用には、適切なメトリクス収集、アラート設定、監査証跡の管理、内部統制の実装が不可欠です。

### 学習内容

1. ビジネスメトリクスの収集と可視化
2. アラートとSLO（Service Level Objective）の設定
3. 会計監査対応（監査証跡の管理）
4. 内部統制の実装（職務分掌、アクセス制御）
5. ダッシュボードの構築

## 10.1 ビジネスメトリクス

### ビジネスメトリクスの概要

会計システムでは、以下のメトリクスを継続的に監視します：

| カテゴリ | メトリクス | 目標値 |
|---|---|---|
| 処理量 | 月間仕訳件数 | 67,000件/月 |
| 品質 | 仕訳承認率 | 95%以上 |
| 品質 | 仕訳エラー率 | 1%以下 |
| パフォーマンス | 月次決算処理時間 | 30分以内 |
| パフォーマンス | 試算表作成時間 | 5秒以内 |
| パフォーマンス | 財務諸表作成時間 | 10秒以内 |
| 財務 | 売掛金残高 | エージング別に監視 |
| 財務 | 延滞債権額 | 売上高の3%以内 |

### 仕訳処理メトリクスの実装

#### JournalEntryMetricsCollector

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.infrastructure

import io.prometheus.client.{Counter, Gauge, Histogram}
import scala.concurrent.{ExecutionContext, Future}
import java.time.YearMonth

/**
 * 仕訳処理メトリクス収集サービス
 */
class JournalEntryMetricsCollector(
  journalEntryRepository: JournalEntryRepository
)(implicit ec: ExecutionContext) {

  // 仕訳作成件数（ステータス別）
  private val journalEntryCreatedCounter = Counter.build()
    .name("journal_entry_created_total")
    .help("Total number of journal entries created")
    .labelNames("status", "voucher_type")
    .register()

  // 仕訳承認件数
  private val journalEntryApprovedCounter = Counter.build()
    .name("journal_entry_approved_total")
    .help("Total number of journal entries approved")
    .register()

  // 仕訳却下件数
  private val journalEntryRejectedCounter = Counter.build()
    .name("journal_entry_rejected_total")
    .help("Total number of journal entries rejected")
    .register()

  // 仕訳エラー件数
  private val journalEntryErrorCounter = Counter.build()
    .name("journal_entry_error_total")
    .help("Total number of journal entry errors")
    .labelNames("error_type")
    .register()

  // 月間仕訳件数（Gauge）
  private val monthlyJournalEntryGauge = Gauge.build()
    .name("monthly_journal_entry_count")
    .help("Number of journal entries in the current month")
    .labelNames("fiscal_year", "fiscal_period")
    .register()

  // 仕訳承認待ち件数（Gauge）
  private val pendingApprovalGauge = Gauge.build()
    .name("journal_entry_pending_approval_count")
    .help("Number of journal entries pending approval")
    .register()

  // 仕訳承認率（Gauge）
  private val approvalRateGauge = Gauge.build()
    .name("journal_entry_approval_rate")
    .help("Journal entry approval rate (percentage)")
    .register()

  /**
   * 仕訳作成時にメトリクスを記録
   */
  def recordJournalEntryCreated(
    status: JournalEntryStatus,
    voucherType: VoucherType
  ): Unit = {
    journalEntryCreatedCounter
      .labels(status.toString, voucherType.toString)
      .inc()
  }

  /**
   * 仕訳承認時にメトリクスを記録
   */
  def recordJournalEntryApproved(): Unit = {
    journalEntryApprovedCounter.inc()
  }

  /**
   * 仕訳却下時にメトリクスを記録
   */
  def recordJournalEntryRejected(): Unit = {
    journalEntryRejectedCounter.inc()
  }

  /**
   * 仕訳エラー時にメトリクスを記録
   */
  def recordJournalEntryError(errorType: String): Unit = {
    journalEntryErrorCounter.labels(errorType).inc()
  }

  /**
   * 月間メトリクスを更新（定期実行）
   */
  def updateMonthlyMetrics(
    fiscalYear: FiscalYear,
    targetMonth: YearMonth
  ): Future[Unit] = {

    for {
      // 月間仕訳件数を取得
      monthlyCount <- journalEntryRepository.countByMonth(fiscalYear, targetMonth)

      // 承認待ち件数を取得
      pendingCount <- journalEntryRepository.countByStatus(JournalEntryStatus.PendingApproval)

      // 承認率を計算
      approvedCount <- journalEntryRepository.countByStatusAndMonth(
        JournalEntryStatus.Approved,
        fiscalYear,
        targetMonth
      )

      rejectedCount <- journalEntryRepository.countByStatusAndMonth(
        JournalEntryStatus.Rejected,
        fiscalYear,
        targetMonth
      )

      totalProcessed = approvedCount + rejectedCount
      approvalRate = if (totalProcessed > 0) {
        (approvedCount.toDouble / totalProcessed.toDouble) * 100.0
      } else 0.0

    } yield {
      // Gaugeを更新
      val fiscalPeriod = FiscalPeriod.fromYearMonth(targetMonth)
      monthlyJournalEntryGauge
        .labels(fiscalYear.value.toString, fiscalPeriod.period.toString)
        .set(monthlyCount.toDouble)

      pendingApprovalGauge.set(pendingCount.toDouble)
      approvalRateGauge.set(approvalRate)
    }
  }
}
```

### 決算処理メトリクスの実装

#### ClosingProcessMetricsCollector

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.infrastructure

/**
 * 決算処理メトリクス収集サービス
 */
class ClosingProcessMetricsCollector {

  // 月次決算処理時間
  private val monthlyClosingDuration = Histogram.build()
    .name("monthly_closing_duration_seconds")
    .help("Monthly closing process duration in seconds")
    .buckets(60, 300, 600, 1200, 1800)  // 1分, 5分, 10分, 20分, 30分
    .register()

  // 試算表作成時間
  private val trialBalanceGenerationDuration = Histogram.build()
    .name("trial_balance_generation_duration_seconds")
    .help("Trial balance generation duration in seconds")
    .buckets(1, 3, 5, 10, 30)  // 1秒, 3秒, 5秒, 10秒, 30秒
    .register()

  // 財務諸表作成時間
  private val financialStatementGenerationDuration = Histogram.build()
    .name("financial_statement_generation_duration_seconds")
    .help("Financial statement generation duration in seconds")
    .labelNames("statement_type")
    .buckets(1, 5, 10, 30, 60)  // 1秒, 5秒, 10秒, 30秒, 60秒
    .register()

  // 決算処理成功/失敗カウンター
  private val closingProcessCounter = Counter.build()
    .name("closing_process_total")
    .help("Total number of closing processes")
    .labelNames("closing_type", "status")  // monthly/annual, success/failure
    .register()

  /**
   * 月次決算処理時間を記録
   */
  def recordMonthlyClosingDuration(durationSeconds: Double): Unit = {
    monthlyClosingDuration.observe(durationSeconds)
  }

  /**
   * 試算表作成時間を記録
   */
  def recordTrialBalanceGeneration(durationSeconds: Double): Unit = {
    trialBalanceGenerationDuration.observe(durationSeconds)
  }

  /**
   * 財務諸表作成時間を記録
   */
  def recordFinancialStatementGeneration(
    statementType: String,
    durationSeconds: Double
  ): Unit = {
    financialStatementGenerationDuration
      .labels(statementType)
      .observe(durationSeconds)
  }

  /**
   * 決算処理の成功/失敗を記録
   */
  def recordClosingProcess(
    closingType: String,  // "monthly" or "annual"
    success: Boolean
  ): Unit = {
    val status = if (success) "success" else "failure"
    closingProcessCounter.labels(closingType, status).inc()
  }
}
```

### 債権債務メトリクスの実装

#### AccountsReceivableMetricsCollector

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.infrastructure

/**
 * 債権債務メトリクス収集サービス
 */
class AccountsReceivableMetricsCollector(
  accountsReceivableRepository: AccountsReceivableRepository
)(implicit ec: ExecutionContext) {

  // 売掛金残高（エージング別）
  private val accountsReceivableBalanceGauge = Gauge.build()
    .name("accounts_receivable_balance")
    .help("Accounts receivable balance by aging")
    .labelNames("aging_category")  // current, 30days, 60days, 90days, over90days
    .register()

  // 延滞債権額
  private val overdueReceivableGauge = Gauge.build()
    .name("overdue_receivable_amount")
    .help("Overdue receivable amount")
    .register()

  // 延滞債権比率（売上高比）
  private val overdueReceivableRatioGauge = Gauge.build()
    .name("overdue_receivable_ratio")
    .help("Overdue receivable ratio to revenue")
    .register()

  // 買掛金残高
  private val accountsPayableBalanceGauge = Gauge.build()
    .name("accounts_payable_balance")
    .help("Accounts payable balance")
    .register()

  /**
   * 債権債務メトリクスを更新（定期実行）
   */
  def updateAccountsReceivableMetrics(): Future[Unit] = {

    for {
      // エージング分析を実行
      agingAnalysis <- accountsReceivableRepository.performAgingAnalysis()

      // 売上高を取得（延滞比率の計算用）
      monthlyRevenue <- getMonthlyRevenue()

    } yield {
      // エージング別残高を設定
      accountsReceivableBalanceGauge.labels("current").set(agingAnalysis.current.amount.toDouble)
      accountsReceivableBalanceGauge.labels("30days").set(agingAnalysis.days30.amount.toDouble)
      accountsReceivableBalanceGauge.labels("60days").set(agingAnalysis.days60.amount.toDouble)
      accountsReceivableBalanceGauge.labels("90days").set(agingAnalysis.days90.amount.toDouble)
      accountsReceivableBalanceGauge.labels("over90days").set(agingAnalysis.over90days.amount.toDouble)

      // 延滞債権額（30日超）
      val overdueAmount = agingAnalysis.days30.amount +
        agingAnalysis.days60.amount +
        agingAnalysis.days90.amount +
        agingAnalysis.over90days.amount

      overdueReceivableGauge.set(overdueAmount.toDouble)

      // 延滞債権比率
      val overdueRatio = if (monthlyRevenue.amount > 0) {
        (overdueAmount.toDouble / monthlyRevenue.amount.toDouble) * 100.0
      } else 0.0

      overdueReceivableRatioGauge.set(overdueRatio)
    }
  }

  private def getMonthlyRevenue(): Future[Money] = {
    // 月次売上高を取得（実装は省略）
    Future.successful(Money(1250000000))
  }
}

/**
 * エージング分析結果
 */
final case class AgingAnalysis(
  current: Money,      // 30日以内
  days30: Money,       // 31-60日
  days60: Money,       // 61-90日
  days90: Money,       // 91-120日
  over90days: Money    // 120日超
)
```

### メトリクス収集の定期実行

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.infrastructure

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import scala.concurrent.duration.*

/**
 * メトリクス収集スケジューラー
 */
object MetricsCollectionScheduler {

  sealed trait Command
  private case object CollectMetrics extends Command

  def apply(
    journalEntryMetrics: JournalEntryMetricsCollector,
    accountsReceivableMetrics: AccountsReceivableMetricsCollector
  ): Behavior[Command] = {

    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>

        // 5分ごとにメトリクスを収集
        timers.startTimerWithFixedDelay(CollectMetrics, 5.minutes)

        Behaviors.receiveMessage {
          case CollectMetrics =>
            context.log.info("メトリクスを収集します")

            val fiscalYear = FiscalYear.current()
            val targetMonth = java.time.YearMonth.now()

            // 月間メトリクスを更新
            context.pipeToSelf(
              journalEntryMetrics.updateMonthlyMetrics(fiscalYear, targetMonth)
            ) {
              case scala.util.Success(_) =>
                context.log.info("仕訳メトリクスを更新しました")
                CollectMetrics
              case scala.util.Failure(ex) =>
                context.log.error("仕訳メトリクスの更新に失敗しました", ex)
                CollectMetrics
            }

            // 債権債務メトリクスを更新
            context.pipeToSelf(
              accountsReceivableMetrics.updateAccountsReceivableMetrics()
            ) {
              case scala.util.Success(_) =>
                context.log.info("債権債務メトリクスを更新しました")
                CollectMetrics
              case scala.util.Failure(ex) =>
                context.log.error("債権債務メトリクスの更新に失敗しました", ex)
                CollectMetrics
            }

            Behaviors.same
        }
      }
    }
  }
}
```

### アラート設定（Prometheus Alertmanager）

```yaml
# prometheus_alerts.yml
# 会計システムのアラートルール

groups:
  - name: accounting_system_alerts
    interval: 1m
    rules:

      # 仕訳承認率が90%を下回った場合
      - alert: LowJournalEntryApprovalRate
        expr: journal_entry_approval_rate < 90
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "仕訳承認率が低下しています"
          description: "仕訳承認率が {{ $value }}% に低下しました（目標: 95%以上）"

      # 仕訳エラー率が1%を超えた場合
      - alert: HighJournalEntryErrorRate
        expr: |
          (rate(journal_entry_error_total[5m]) /
           rate(journal_entry_created_total[5m])) * 100 > 1
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "仕訳エラー率が高すぎます"
          description: "仕訳エラー率が {{ $value }}% に上昇しました（目標: 1%以下）"

      # 試算表作成時間が5秒を超えた場合
      - alert: SlowTrialBalanceGeneration
        expr: |
          histogram_quantile(0.95,
            rate(trial_balance_generation_duration_seconds_bucket[5m])
          ) > 5
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "試算表作成が遅延しています"
          description: "試算表作成時間の95パーセンタイルが {{ $value }}秒です（目標: 5秒以内）"

      # 月次決算処理時間が30分を超えた場合
      - alert: SlowMonthlyClosing
        expr: |
          histogram_quantile(0.95,
            rate(monthly_closing_duration_seconds_bucket[1h])
          ) > 1800
        for: 10m
        labels:
          severity: critical
        annotations:
          summary: "月次決算処理が遅延しています"
          description: "月次決算処理時間が {{ $value }}秒です（目標: 1800秒以内）"

      # 延滞債権比率が3%を超えた場合
      - alert: HighOverdueReceivableRatio
        expr: overdue_receivable_ratio > 3
        for: 1h
        labels:
          severity: warning
        annotations:
          summary: "延滞債権比率が高すぎます"
          description: "延滞債権比率が {{ $value }}% に上昇しました（目標: 3%以内）"

      # 承認待ち仕訳が100件を超えた場合
      - alert: HighPendingApprovalCount
        expr: journal_entry_pending_approval_count > 100
        for: 30m
        labels:
          severity: warning
        annotations:
          summary: "承認待ち仕訳が滞留しています"
          description: "承認待ち仕訳が {{ $value }}件に達しています"

      # 決算処理が失敗した場合
      - alert: ClosingProcessFailed
        expr: |
          increase(closing_process_total{status="failure"}[5m]) > 0
        labels:
          severity: critical
        annotations:
          summary: "決算処理が失敗しました"
          description: "{{ $labels.closing_type }}決算処理が失敗しました。至急確認してください。"
```

## 10.2 会計監査対応

### 監査証跡の管理

会計システムでは、法令（会社法、金融商品取引法など）により、全ての取引記録を一定期間保存する義務があります。イベントソーシングアーキテクチャは、完全な監査証跡を自動的に提供します。

#### 監査証跡の要件

| 要件 | 説明 | 実装方法 |
|---|---|---|
| 完全性 | 全ての変更を記録 | イベントソーシング |
| 不変性 | 過去の記録は変更不可 | Append-onlyのイベントストア |
| 追跡可能性 | 誰が、いつ、何をしたか | イベントにユーザーIDとタイムスタンプを記録 |
| 元データとの紐付け | 仕訳と元トランザクション | sourceEventIdフィールド |
| 長期保存 | 7年間の保存義務 | アーカイブ機能 |

#### AuditLogService の実装

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.usecase

import org.apache.pekko.persistence.query.{EventEnvelope, PersistenceQuery}
import org.apache.pekko.persistence.query.scaladsl.{EventsByPersistenceIdQuery, EventsByTagQuery}
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import scala.concurrent.{ExecutionContext, Future}
import java.time.Instant

/**
 * 監査ログサービス
 */
class AuditLogService(
  readJournal: EventsByPersistenceIdQuery with EventsByTagQuery
)(implicit
  materializer: Materializer,
  ec: ExecutionContext
) {

  /**
   * 特定の仕訳の完全な監査証跡を取得
   */
  def getJournalEntryAuditTrail(
    journalEntryId: JournalEntryId
  ): Future[AuditTrail] = {

    val persistenceId = s"JournalEntry-${journalEntryId.value}"

    readJournal
      .eventsByPersistenceId(persistenceId, 0L, Long.MaxValue)
      .map { envelope =>
        eventToAuditLog(envelope)
      }
      .runWith(Sink.seq)
      .map { logs =>
        AuditTrail(
          entityId = journalEntryId.value,
          entityType = "JournalEntry",
          logs = logs.toList,
          generatedAt = Instant.now()
        )
      }
  }

  /**
   * 特定期間の全ての監査ログを取得
   */
  def getAuditLogsByPeriod(
    from: Instant,
    to: Instant
  ): Future[List[AuditLog]] = {

    // "accounting-events" タグで全ての会計イベントを取得
    readJournal
      .eventsByTag("accounting-events", offset = 0L)
      .filter { envelope =>
        val timestamp = envelope.timestamp
        timestamp >= from.toEpochMilli && timestamp <= to.toEpochMilli
      }
      .map { envelope =>
        eventToAuditLog(envelope)
      }
      .runWith(Sink.seq)
      .map(_.toList)
  }

  /**
   * 特定ユーザーの操作履歴を取得
   */
  def getUserActivityLog(
    userId: String,
    from: Instant,
    to: Instant
  ): Future[List[AuditLog]] = {

    getAuditLogsByPeriod(from, to).map { logs =>
      logs.filter(_.performedBy == userId)
    }
  }

  /**
   * 元トランザクションから生成された全仕訳を追跡
   */
  def traceJournalEntriesBySourceEvent(
    sourceEventId: String
  ): Future[List[JournalEntry]] = {

    // sourceEventId で検索
    // 実装は省略
    Future.successful(List.empty)
  }

  /**
   * イベントを監査ログに変換
   */
  private def eventToAuditLog(envelope: EventEnvelope): AuditLog = {
    envelope.event match {
      case evt: JournalEntryCreated =>
        AuditLog(
          eventId = envelope.sequenceNr,
          eventType = "JournalEntryCreated",
          entityId = evt.id.value,
          entityType = "JournalEntry",
          performedBy = evt.createdBy.getOrElse("System"),
          performedAt = evt.createdAt,
          description = s"仕訳を作成しました: ${evt.entryNumber.value}",
          metadata = Map(
            "voucherType" -> evt.voucherType.toString,
            "amount" -> evt.lines.map(_.amount.amount).sum.toString,
            "sourceEventId" -> evt.sourceEventId.getOrElse("")
          )
        )

      case evt: JournalEntryApproved =>
        AuditLog(
          eventId = envelope.sequenceNr,
          eventType = "JournalEntryApproved",
          entityId = evt.journalEntryId.value,
          entityType = "JournalEntry",
          performedBy = evt.approvedBy,
          performedAt = evt.approvedAt,
          description = s"仕訳を承認しました",
          metadata = Map(
            "comment" -> evt.comment.getOrElse("")
          )
        )

      case evt: JournalEntryRejected =>
        AuditLog(
          eventId = envelope.sequenceNr,
          eventType = "JournalEntryRejected",
          entityId = evt.journalEntryId.value,
          entityType = "JournalEntry",
          performedBy = evt.rejectedBy,
          performedAt = evt.rejectedAt,
          description = s"仕訳を却下しました: ${evt.reason}",
          metadata = Map(
            "reason" -> evt.reason
          )
        )

      case evt: JournalEntryPosted =>
        AuditLog(
          eventId = envelope.sequenceNr,
          eventType = "JournalEntryPosted",
          entityId = evt.journalEntryId.value,
          entityType = "JournalEntry",
          performedBy = "System",
          performedAt = evt.postedAt,
          description = "仕訳を総勘定元帳に転記しました",
          metadata = Map()
        )

      case _ =>
        AuditLog(
          eventId = envelope.sequenceNr,
          eventType = envelope.event.getClass.getSimpleName,
          entityId = "",
          entityType = "",
          performedBy = "System",
          performedAt = Instant.ofEpochMilli(envelope.timestamp),
          description = "その他のイベント",
          metadata = Map()
        )
    }
  }
}

/**
 * 監査証跡
 */
final case class AuditTrail(
  entityId: String,
  entityType: String,
  logs: List[AuditLog],
  generatedAt: Instant
) {

  /**
   * 監査レポートを生成
   */
  def generateReport(): String = {
    val sb = new StringBuilder
    sb.append(s"========== 監査証跡レポート ==========\n")
    sb.append(s"エンティティID: $entityId\n")
    sb.append(s"エンティティタイプ: $entityType\n")
    sb.append(s"作成日時: $generatedAt\n")
    sb.append("=" * 50 + "\n\n")

    logs.foreach { log =>
      sb.append(s"[${log.performedAt}] ${log.eventType}\n")
      sb.append(s"  実行者: ${log.performedBy}\n")
      sb.append(s"  内容: ${log.description}\n")
      if (log.metadata.nonEmpty) {
        sb.append(s"  メタデータ:\n")
        log.metadata.foreach { case (key, value) =>
          sb.append(s"    $key: $value\n")
        }
      }
      sb.append("-" * 50 + "\n")
    }

    sb.toString()
  }
}

/**
 * 監査ログ
 */
final case class AuditLog(
  eventId: Long,
  eventType: String,
  entityId: String,
  entityType: String,
  performedBy: String,
  performedAt: Instant,
  description: String,
  metadata: Map[String, String]
)
```

### 内部統制の実装

#### 職務分掌（Separation of Duties）

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.domain.model

/**
 * 職務分掌ポリシー
 *
 * 仕訳の入力者と承認者を分離する
 */
object SeparationOfDutiesPolicy {

  /**
   * 同一ユーザーが仕訳の作成と承認を行うことを防ぐ
   */
  def validateApproval(
    entry: JournalEntry,
    approverId: String
  ): Either[ValidationError, Unit] = {

    entry.createdBy match {
      case Some(creatorId) if creatorId == approverId =>
        Left(ValidationError(
          "職務分掌違反: 仕訳の作成者と承認者は異なるユーザーである必要があります"
        ))

      case _ =>
        Right(())
    }
  }

  /**
   * 決算後の仕訳修正を制限
   */
  def validateModificationAfterClosing(
    entry: JournalEntry,
    closingStatus: ClosingStatus
  ): Either[ValidationError, Unit] = {

    if (closingStatus == ClosingStatus.Locked) {
      Left(ValidationError(
        "決算後修正エラー: 決算がロックされているため、仕訳を修正できません"
      ))
    } else {
      Right(())
    }
  }
}
```

#### アクセス制御（RBAC: Role-Based Access Control）

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.infrastructure

/**
 * ロールベースアクセス制御
 */
object AccessControl {

  // ロール定義
  sealed trait Role
  object Role {
    case object Accountant extends Role         // 経理担当者（仕訳入力）
    case object AccountingManager extends Role  // 経理部長（仕訳承認）
    case object CFO extends Role                // CFO（決算承認）
    case object Auditor extends Role            // 監査役（読み取り専用）
  }

  // パーミッション定義
  sealed trait Permission
  object Permission {
    case object CreateJournalEntry extends Permission    // 仕訳作成
    case object ApproveJournalEntry extends Permission   // 仕訳承認
    case object PostJournalEntry extends Permission      // 仕訳転記
    case object ExecuteClosing extends Permission        // 決算実行
    case object ApproveClosing extends Permission        // 決算承認
    case object ViewFinancialStatements extends Permission  // 財務諸表閲覧
    case object ViewAuditTrail extends Permission        // 監査証跡閲覧
  }

  // ロールごとのパーミッション
  private val rolePermissions: Map[Role, Set[Permission]] = Map(
    Role.Accountant -> Set(
      Permission.CreateJournalEntry,
      Permission.ViewFinancialStatements
    ),
    Role.AccountingManager -> Set(
      Permission.CreateJournalEntry,
      Permission.ApproveJournalEntry,
      Permission.PostJournalEntry,
      Permission.ExecuteClosing,
      Permission.ViewFinancialStatements,
      Permission.ViewAuditTrail
    ),
    Role.CFO -> Set(
      Permission.ApproveJournalEntry,
      Permission.ApproveClosing,
      Permission.ViewFinancialStatements,
      Permission.ViewAuditTrail
    ),
    Role.Auditor -> Set(
      Permission.ViewFinancialStatements,
      Permission.ViewAuditTrail
    )
  )

  /**
   * ユーザーが特定のパーミッションを持つか確認
   */
  def hasPermission(user: User, permission: Permission): Boolean = {
    user.roles.exists { role =>
      rolePermissions.get(role).exists(_.contains(permission))
    }
  }

  /**
   * パーミッションチェック（Either返却）
   */
  def checkPermission(
    user: User,
    permission: Permission
  ): Either[AuthorizationError, Unit] = {

    if (hasPermission(user, permission)) {
      Right(())
    } else {
      Left(AuthorizationError(
        s"ユーザー ${user.id} は ${permission} の権限を持っていません"
      ))
    }
  }
}

/**
 * ユーザー
 */
final case class User(
  id: String,
  name: String,
  email: String,
  roles: Set[AccessControl.Role]
)

/**
 * 認可エラー
 */
final case class AuthorizationError(message: String)
```

#### アクセス制御の適用例

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.usecase

/**
 * アクセス制御を適用した仕訳承認サービス
 */
class SecuredJournalEntryApprovalService(
  journalEntryActor: ActorRef[JournalEntryCommand]
) {

  /**
   * 仕訳を承認（アクセス制御付き）
   */
  def approveJournalEntry(
    journalEntryId: JournalEntryId,
    user: User,
    comment: Option[String]
  )(implicit timeout: Timeout, ec: ExecutionContext): Future[Either[String, JournalEntry]] = {

    // パーミッションチェック
    AccessControl.checkPermission(user, Permission.ApproveJournalEntry) match {
      case Left(error) =>
        Future.successful(Left(error.message))

      case Right(_) =>
        // 職務分掌チェック（承認者が作成者でないことを確認）
        // 実際には、仕訳を取得して createdBy を確認する
        val cmd = JournalEntryCommand.ApproveJournalEntry(
          id = journalEntryId,
          approvedBy = user.id,
          comment = comment,
          replyTo = ???
        )

        // コマンドを送信
        journalEntryActor.ask(cmd).map {
          case StatusReply.Success(entry) => Right(entry)
          case StatusReply.Error(message) => Left(message)
        }
    }
  }
}
```

### 監査レポートの生成

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.usecase

/**
 * 監査レポート生成サービス
 */
class AuditReportGenerationService(
  auditLogService: AuditLogService,
  journalEntryRepository: JournalEntryRepository
)(implicit ec: ExecutionContext) {

  /**
   * 月次監査レポートを生成
   */
  def generateMonthlyAuditReport(
    fiscalYear: FiscalYear,
    targetMonth: YearMonth
  ): Future[MonthlyAuditReport] = {

    val from = targetMonth.atDay(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC)
    val to = targetMonth.atEndOfMonth().atTime(23, 59, 59).toInstant(java.time.ZoneOffset.UTC)

    for {
      // 該当月の全ての監査ログを取得
      auditLogs <- auditLogService.getAuditLogsByPeriod(from, to)

      // 仕訳統計を取得
      totalEntries <- journalEntryRepository.countByMonth(fiscalYear, targetMonth)
      approvedEntries <- journalEntryRepository.countByStatusAndMonth(
        JournalEntryStatus.Approved,
        fiscalYear,
        targetMonth
      )
      rejectedEntries <- journalEntryRepository.countByStatusAndMonth(
        JournalEntryStatus.Rejected,
        fiscalYear,
        targetMonth
      )

      // ユーザー別の操作統計
      userActivityStats = auditLogs.groupBy(_.performedBy).map { case (userId, logs) =>
        UserActivityStats(
          userId = userId,
          totalOperations = logs.size,
          journalEntryCreations = logs.count(_.eventType == "JournalEntryCreated"),
          journalEntryApprovals = logs.count(_.eventType == "JournalEntryApproved"),
          journalEntryRejections = logs.count(_.eventType == "JournalEntryRejected")
        )
      }.toList

    } yield MonthlyAuditReport(
      fiscalYear = fiscalYear,
      targetMonth = targetMonth,
      totalJournalEntries = totalEntries,
      approvedJournalEntries = approvedEntries,
      rejectedJournalEntries = rejectedEntries,
      approvalRate = if (approvedEntries + rejectedEntries > 0) {
        (approvedEntries.toDouble / (approvedEntries + rejectedEntries).toDouble) * 100.0
      } else 0.0,
      totalAuditLogs = auditLogs.size,
      userActivityStats = userActivityStats,
      generatedAt = Instant.now()
    )
  }
}

/**
 * 月次監査レポート
 */
final case class MonthlyAuditReport(
  fiscalYear: FiscalYear,
  targetMonth: YearMonth,
  totalJournalEntries: Long,
  approvedJournalEntries: Long,
  rejectedJournalEntries: Long,
  approvalRate: Double,
  totalAuditLogs: Int,
  userActivityStats: List[UserActivityStats],
  generatedAt: Instant
) {

  def report(): String = {
    s"""
    |========== 月次監査レポート ==========
    |会計年度: ${fiscalYear.value}
    |対象月: ${targetMonth}
    |作成日時: ${generatedAt}
    |
    |【仕訳統計】
    |  総仕訳件数:   ${totalJournalEntries}件
    |  承認件数:     ${approvedJournalEntries}件
    |  却下件数:     ${rejectedJournalEntries}件
    |  承認率:       ${f"$approvalRate%.2f"}%
    |
    |【監査ログ】
    |  総ログ件数:   ${totalAuditLogs}件
    |
    |【ユーザー別操作統計】
    |${userActivityStats.sortBy(-_.totalOperations).map { stats =>
      s"""  ${stats.userId}:
      |    総操作回数: ${stats.totalOperations}回
      |    仕訳作成:   ${stats.journalEntryCreations}回
      |    仕訳承認:   ${stats.journalEntryApprovals}回
      |    仕訳却下:   ${stats.journalEntryRejections}回
      """.stripMargin
    }.mkString("\n")}
    |=====================================
    """.stripMargin
  }
}

/**
 * ユーザー別操作統計
 */
final case class UserActivityStats(
  userId: String,
  totalOperations: Int,
  journalEntryCreations: Int,
  journalEntryApprovals: Int,
  journalEntryRejections: Int
)
```

## 10.3 ダッシュボードとレポート

### Grafanaダッシュボードの設定例

```json
{
  "dashboard": {
    "title": "会計システム ダッシュボード",
    "panels": [
      {
        "title": "月間仕訳件数",
        "targets": [
          {
            "expr": "monthly_journal_entry_count"
          }
        ],
        "type": "graph"
      },
      {
        "title": "仕訳承認率",
        "targets": [
          {
            "expr": "journal_entry_approval_rate"
          }
        ],
        "type": "gauge",
        "thresholds": [
          { "value": 90, "color": "red" },
          { "value": 95, "color": "yellow" },
          { "value": 100, "color": "green" }
        ]
      },
      {
        "title": "承認待ち仕訳件数",
        "targets": [
          {
            "expr": "journal_entry_pending_approval_count"
          }
        ],
        "type": "singlestat"
      },
      {
        "title": "試算表作成時間（95パーセンタイル）",
        "targets": [
          {
            "expr": "histogram_quantile(0.95, rate(trial_balance_generation_duration_seconds_bucket[5m]))"
          }
        ],
        "type": "graph"
      },
      {
        "title": "売掛金残高（エージング別）",
        "targets": [
          {
            "expr": "accounts_receivable_balance",
            "legendFormat": "{{ aging_category }}"
          }
        ],
        "type": "graph"
      },
      {
        "title": "延滞債権比率",
        "targets": [
          {
            "expr": "overdue_receivable_ratio"
          }
        ],
        "type": "gauge",
        "thresholds": [
          { "value": 3, "color": "green" },
          { "value": 5, "color": "yellow" },
          { "value": 10, "color": "red" }
        ]
      }
    ]
  }
}
```

## まとめ

本章では、会計システムの運用とモニタリングの実装を学びました。

### 実装した内容

1. **ビジネスメトリクスの収集**
   - 仕訳処理メトリクス（件数、承認率、エラー率）
   - 決算処理メトリクス（処理時間）
   - 債権債務メトリクス（売掛金残高、延滞債権額）
   - Prometheusメトリクスの定義

2. **アラート設定**
   - 仕訳承認率低下のアラート（90%未満）
   - 仕訳エラー率上昇のアラート（1%超）
   - 処理時間超過のアラート（試算表5秒超、月次決算30分超）
   - 延滞債権比率上昇のアラート（3%超）

3. **監査証跡の管理**
   - AuditLogService: 完全な監査証跡の取得
   - 元トランザクションとの紐付け（sourceEventId）
   - ユーザー別操作履歴の追跡
   - 監査レポートの自動生成

4. **内部統制の実装**
   - 職務分掌（Separation of Duties）: 作成者と承認者の分離
   - RBAC（Role-Based Access Control）: ロールベースのアクセス制御
   - 決算後の仕訳修正制限
   - パーミッションチェックの適用

5. **ダッシュボードとレポート**
   - Grafanaダッシュボードの設計
   - 月次監査レポートの生成
   - ユーザー別操作統計

### D社への適用効果

- **可視化**: 経営者がリアルタイムに財務状況を把握
- **早期警戒**: アラートにより問題を早期発見
- **監査対応**: イベントソーシングによる完全な監査証跡
- **内部統制**: 職務分掌とアクセス制御により不正を防止
- **コンプライアンス**: 会社法、金融商品取引法に準拠

次章では、高度なトピック（複数通貨会計、連結会計、管理会計）を学びます。
