# 第7部第11章：運用とモニタリング

## 本章の目的

マスターデータ管理サービスは全てのBounded Contextの基盤となるため、安定した運用が不可欠です。本章では、Prometheusメトリクス、Grafanaダッシュボード、アラート設定により、ビジネスメトリクス、データ品質、同期状況を可視化し、プロアクティブな運用を実現します。

## 11.1 ビジネスメトリクス

### 11.1.1 マスターデータ統計メトリクス

Prometheusメトリクスを使用して、マスターデータの統計情報を収集します。

```scala
package com.example.masterdata.infrastructure.monitoring

import io.prometheus.client.{Counter, Gauge, Histogram, Summary}
import io.prometheus.client.hotspot.DefaultExports

/**
 * マスターデータビジネスメトリクス
 */
object MasterDataBusinessMetrics {

  // JVMメトリクスを自動登録
  DefaultExports.initialize()

  // ==========================================
  // マスターデータ統計
  // ==========================================

  /**
   * 商品マスター総数
   */
  val productTotalGauge: Gauge = Gauge.build()
    .name("masterdata_product_total")
    .help("商品マスター総数")
    .labelNames("status")
    .register()

  /**
   * 勘定科目マスター総数
   */
  val accountSubjectTotalGauge: Gauge = Gauge.build()
    .name("masterdata_account_subject_total")
    .help("勘定科目マスター総数")
    .labelNames("account_type", "status")
    .register()

  /**
   * コードマスター総数
   */
  val codeMasterTotalGauge: Gauge = Gauge.build()
    .name("masterdata_code_master_total")
    .help("コードマスター総数")
    .labelNames("code_type", "status")
    .register()

  /**
   * 部門マスター総数
   */
  val departmentTotalGauge: Gauge = Gauge.build()
    .name("masterdata_department_total")
    .help("部門マスター総数")
    .labelNames("status")
    .register()

  /**
   * 従業員マスター総数
   */
  val employeeTotalGauge: Gauge = Gauge.build()
    .name("masterdata_employee_total")
    .help("従業員マスター総数")
    .labelNames("department_id", "status")
    .register()

  // ==========================================
  // 変更頻度メトリクス
  // ==========================================

  /**
   * マスターデータ変更件数
   */
  val masterDataChangeCounter: Counter = Counter.build()
    .name("masterdata_change_total")
    .help("マスターデータ変更件数")
    .labelNames("aggregate_type", "change_type", "status")
    .register()

  /**
   * 月間変更件数（過去30日）
   */
  val monthlyChangeGauge: Gauge = Gauge.build()
    .name("masterdata_monthly_change_count")
    .help("月間マスターデータ変更件数（過去30日）")
    .labelNames("aggregate_type", "change_type")
    .register()

  /**
   * 日別変更件数
   */
  val dailyChangeGauge: Gauge = Gauge.build()
    .name("masterdata_daily_change_count")
    .help("本日のマスターデータ変更件数")
    .labelNames("aggregate_type", "change_type")
    .register()

  // ==========================================
  // 承認状況メトリクス
  // ==========================================

  /**
   * 承認待ち件数
   */
  val pendingApprovalGauge: Gauge = Gauge.build()
    .name("masterdata_pending_approval_total")
    .help("承認待ち変更申請件数")
    .labelNames("request_type")
    .register()

  /**
   * 承認処理時間（ヒストグラム）
   */
  val approvalDurationHistogram: Histogram = Histogram.build()
    .name("masterdata_approval_duration_seconds")
    .help("承認処理時間（秒）")
    .labelNames("request_type", "result")
    .buckets(60, 300, 900, 1800, 3600, 7200, 14400, 28800, 86400) // 1分、5分、15分、30分、1時間、2時間、4時間、8時間、24時間
    .register()

  /**
   * 承認率
   */
  val approvalRateGauge: Gauge = Gauge.build()
    .name("masterdata_approval_rate")
    .help("承認率（承認/（承認+却下））")
    .labelNames("request_type")
    .register()

  /**
   * 却下件数
   */
  val rejectionCounter: Counter = Counter.build()
    .name("masterdata_rejection_total")
    .help("却下件数")
    .labelNames("request_type", "rejection_reason")
    .register()

  /**
   * 承認タイムアウト件数
   */
  val approvalTimeoutCounter: Counter = Counter.build()
    .name("masterdata_approval_timeout_total")
    .help("承認タイムアウト件数")
    .labelNames("request_type")
    .register()

  // ==========================================
  // ユーザー活動メトリクス
  // ==========================================

  /**
   * ユーザー別変更件数
   */
  val userChangeCounter: Counter = Counter.build()
    .name("masterdata_user_change_total")
    .help("ユーザー別マスターデータ変更件数")
    .labelNames("user_id", "user_name", "aggregate_type")
    .register()

  /**
   * アクティブユーザー数（過去24時間）
   */
  val activeUserGauge: Gauge = Gauge.build()
    .name("masterdata_active_user_count")
    .help("アクティブユーザー数（過去24時間）")
    .register()
}
```

### 11.1.2 メトリクス収集サービス

定期的にデータベースからメトリクスを収集して、Prometheusゲージを更新します。

```scala
package com.example.masterdata.infrastructure.monitoring

import org.apache.pekko.actor.typed.{ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import java.time.{Instant, LocalDate}

/**
 * ビジネスメトリクス収集サービス
 */
object BusinessMetricsCollectionService {

  sealed trait Command
  case object CollectMetrics extends Command
  private case object ScheduledCollection extends Command

  def apply(
    productRepository: ProductQueryRepository,
    accountSubjectRepository: AccountSubjectQueryRepository,
    codeMasterRepository: CodeMasterQueryRepository,
    departmentRepository: DepartmentQueryRepository,
    employeeRepository: EmployeeQueryRepository,
    changeRequestRepository: ChangeRequestQueryRepository,
    auditLogRepository: AuditLogRepository
  )(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        // 1分ごとにメトリクス収集
        timers.startTimerWithFixedDelay(ScheduledCollection, 1.minute)

        Behaviors.receiveMessage {
          case CollectMetrics | ScheduledCollection =>
            context.log.debug("ビジネスメトリクス収集開始")

            val startTime = System.currentTimeMillis()

            val collectionFuture = for {
              _ <- collectMasterDataStatistics(
                productRepository,
                accountSubjectRepository,
                codeMasterRepository,
                departmentRepository,
                employeeRepository
              )
              _ <- collectChangeFrequencyMetrics(auditLogRepository)
              _ <- collectApprovalMetrics(changeRequestRepository)
              _ <- collectUserActivityMetrics(auditLogRepository)
            } yield ()

            collectionFuture.onComplete {
              case Success(_) =>
                val duration = System.currentTimeMillis() - startTime
                context.log.debug(s"ビジネスメトリクス収集完了（${duration}ms）")

              case Failure(ex) =>
                context.log.error(s"ビジネスメトリクス収集失敗", ex)
            }

            Behaviors.same
        }
      }
    }
  }

  /**
   * マスターデータ統計収集
   */
  private def collectMasterDataStatistics(
    productRepository: ProductQueryRepository,
    accountSubjectRepository: AccountSubjectQueryRepository,
    codeMasterRepository: CodeMasterQueryRepository,
    departmentRepository: DepartmentQueryRepository,
    employeeRepository: EmployeeQueryRepository
  )(implicit ec: ExecutionContext): Future[Unit] = {
    for {
      // 商品マスター総数
      productTotal <- productRepository.countByStatus()
      _ = productTotal.foreach { case (status, count) =>
        MasterDataBusinessMetrics.productTotalGauge.labels(status).set(count.toDouble)
      }

      // 勘定科目マスター総数
      accountSubjectTotal <- accountSubjectRepository.countByTypeAndStatus()
      _ = accountSubjectTotal.foreach { case ((accountType, status), count) =>
        MasterDataBusinessMetrics.accountSubjectTotalGauge.labels(accountType, status).set(count.toDouble)
      }

      // コードマスター総数
      codeMasterTotal <- codeMasterRepository.countByTypeAndStatus()
      _ = codeMasterTotal.foreach { case ((codeType, status), count) =>
        MasterDataBusinessMetrics.codeMasterTotalGauge.labels(codeType, status).set(count.toDouble)
      }

      // 部門マスター総数
      departmentTotal <- departmentRepository.countByStatus()
      _ = departmentTotal.foreach { case (status, count) =>
        MasterDataBusinessMetrics.departmentTotalGauge.labels(status).set(count.toDouble)
      }

      // 従業員マスター総数
      employeeTotal <- employeeRepository.countByDepartmentAndStatus()
      _ = employeeTotal.foreach { case ((departmentId, status), count) =>
        MasterDataBusinessMetrics.employeeTotalGauge.labels(departmentId, status).set(count.toDouble)
      }
    } yield ()
  }

  /**
   * 変更頻度メトリクス収集
   */
  private def collectChangeFrequencyMetrics(
    auditLogRepository: AuditLogRepository
  )(implicit ec: ExecutionContext): Future[Unit] = {
    val today = LocalDate.now()
    val thirtyDaysAgo = today.minusDays(30)

    for {
      // 月間変更件数（過去30日）
      monthlyChanges <- auditLogRepository.countChangesByPeriod(thirtyDaysAgo, today)
      _ = monthlyChanges.foreach { case ((aggregateType, changeType), count) =>
        MasterDataBusinessMetrics.monthlyChangeGauge.labels(aggregateType, changeType).set(count.toDouble)
      }

      // 日別変更件数（本日）
      dailyChanges <- auditLogRepository.countChangesByPeriod(today, today)
      _ = dailyChanges.foreach { case ((aggregateType, changeType), count) =>
        MasterDataBusinessMetrics.dailyChangeGauge.labels(aggregateType, changeType).set(count.toDouble)
      }
    } yield ()
  }

  /**
   * 承認メトリクス収集
   */
  private def collectApprovalMetrics(
    changeRequestRepository: ChangeRequestQueryRepository
  )(implicit ec: ExecutionContext): Future[Unit] = {
    for {
      // 承認待ち件数
      pendingApprovals <- changeRequestRepository.countByStatusAndType(ChangeRequestStatus.Pending)
      _ = pendingApprovals.foreach { case (requestType, count) =>
        MasterDataBusinessMetrics.pendingApprovalGauge.labels(requestType).set(count.toDouble)
      }

      // 承認率
      approvalStats <- changeRequestRepository.getApprovalStats()
      _ = approvalStats.foreach { case (requestType, stats) =>
        val approvalRate = if (stats.total > 0) {
          stats.approved.toDouble / (stats.approved + stats.rejected)
        } else {
          0.0
        }
        MasterDataBusinessMetrics.approvalRateGauge.labels(requestType).set(approvalRate)
      }
    } yield ()
  }

  /**
   * ユーザー活動メトリクス収集
   */
  private def collectUserActivityMetrics(
    auditLogRepository: AuditLogRepository
  )(implicit ec: ExecutionContext): Future[Unit] = {
    val yesterday = Instant.now().minus(24, java.time.temporal.ChronoUnit.HOURS)

    for {
      // アクティブユーザー数（過去24時間）
      activeUsers <- auditLogRepository.countActiveUsersSince(yesterday)
      _ = MasterDataBusinessMetrics.activeUserGauge.set(activeUsers.toDouble)
    } yield ()
  }
}
```

## 11.2 データ品質メトリクス

### 11.2.1 データ品質メトリクス定義

```scala
package com.example.masterdata.infrastructure.monitoring

/**
 * データ品質メトリクス
 */
object DataQualityMetrics {

  // ==========================================
  // データ完全性メトリクス
  // ==========================================

  /**
   * 必須項目充足率
   */
  val requiredFieldCompletionRate: Gauge = Gauge.build()
    .name("masterdata_required_field_completion_rate")
    .help("必須項目充足率（0-1）")
    .labelNames("aggregate_type", "field_name")
    .register()

  /**
   * 有効期間妥当性率
   */
  val validPeriodValidityRate: Gauge = Gauge.build()
    .name("masterdata_valid_period_validity_rate")
    .help("有効期間妥当性率（valid_from <= valid_to）")
    .labelNames("aggregate_type")
    .register()

  /**
   * 参照整合性エラー件数
   */
  val referentialIntegrityErrorCounter: Counter = Counter.build()
    .name("masterdata_referential_integrity_error_total")
    .help("参照整合性エラー件数")
    .labelNames("aggregate_type", "field_name", "error_type")
    .register()

  /**
   * データ整合性スコア
   */
  val dataIntegrityScoreGauge: Gauge = Gauge.build()
    .name("masterdata_data_integrity_score")
    .help("データ整合性スコア（0-100）")
    .labelNames("aggregate_type")
    .register()

  // ==========================================
  // 重複データメトリクス
  // ==========================================

  /**
   * 重複候補検出件数
   */
  val duplicateCandidateCounter: Counter = Counter.build()
    .name("masterdata_duplicate_candidate_total")
    .help("重複候補検出件数")
    .labelNames("aggregate_type", "similarity_threshold")
    .register()

  /**
   * 重複候補件数（現在）
   */
  val duplicateCandidateGauge: Gauge = Gauge.build()
    .name("masterdata_duplicate_candidate_count")
    .help("重複候補件数（現在）")
    .labelNames("aggregate_type")
    .register()

  /**
   * マージ実施件数
   */
  val mergeCounter: Counter = Counter.build()
    .name("masterdata_merge_total")
    .help("マスターデータマージ実施件数")
    .labelNames("aggregate_type", "merge_type")
    .register()

  // ==========================================
  // データ品質チェック結果
  // ==========================================

  /**
   * データ品質チェック実行件数
   */
  val qualityCheckCounter: Counter = Counter.build()
    .name("masterdata_quality_check_total")
    .help("データ品質チェック実行件数")
    .labelNames("check_type", "result")
    .register()

  /**
   * データ品質チェック処理時間
   */
  val qualityCheckDurationHistogram: Histogram = Histogram.build()
    .name("masterdata_quality_check_duration_seconds")
    .help("データ品質チェック処理時間（秒）")
    .labelNames("check_type")
    .buckets(0.1, 0.5, 1, 2, 5, 10, 30, 60)
    .register()
}
```

### 11.2.2 データ品質チェックサービス

```scala
package com.example.masterdata.infrastructure.monitoring

import org.apache.pekko.actor.typed.{ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/**
 * データ品質チェックサービス
 */
object DataQualityCheckService {

  sealed trait Command
  case object RunQualityChecks extends Command
  private case object ScheduledCheck extends Command

  def apply(
    productRepository: ProductQueryRepository,
    accountSubjectRepository: AccountSubjectQueryRepository,
    duplicateCheckService: DuplicateCheckService
  )(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        // 1時間ごとにデータ品質チェック
        timers.startTimerWithFixedDelay(ScheduledCheck, 1.hour)

        Behaviors.receiveMessage {
          case RunQualityChecks | ScheduledCheck =>
            context.log.info("データ品質チェック開始")

            val startTime = System.currentTimeMillis()

            val checkFuture = for {
              _ <- checkDataCompleteness(productRepository, accountSubjectRepository)
              _ <- checkValidPeriodValidity(productRepository, accountSubjectRepository)
              _ <- checkReferentialIntegrity(productRepository, accountSubjectRepository)
              _ <- checkDuplicates(productRepository, duplicateCheckService)
            } yield ()

            checkFuture.onComplete {
              case Success(_) =>
                val duration = System.currentTimeMillis() - startTime
                context.log.info(s"データ品質チェック完了（${duration}ms）")

              case Failure(ex) =>
                context.log.error(s"データ品質チェック失敗", ex)
            }

            Behaviors.same
        }
      }
    }
  }

  /**
   * データ完全性チェック
   */
  private def checkDataCompleteness(
    productRepository: ProductQueryRepository,
    accountSubjectRepository: AccountSubjectQueryRepository
  )(implicit ec: ExecutionContext): Future[Unit] = {
    val startTime = System.currentTimeMillis()

    val checkFuture = for {
      // 商品マスターの必須項目充足率
      productCompleteness <- productRepository.checkRequiredFields()
      _ = productCompleteness.foreach { case (fieldName, completionRate) =>
        DataQualityMetrics.requiredFieldCompletionRate
          .labels("Product", fieldName)
          .set(completionRate)
      }

      // 勘定科目マスターの必須項目充足率
      accountSubjectCompleteness <- accountSubjectRepository.checkRequiredFields()
      _ = accountSubjectCompleteness.foreach { case (fieldName, completionRate) =>
        DataQualityMetrics.requiredFieldCompletionRate
          .labels("AccountSubject", fieldName)
          .set(completionRate)
      }
    } yield ()

    checkFuture.andThen {
      case Success(_) =>
        val duration = (System.currentTimeMillis() - startTime) / 1000.0
        DataQualityMetrics.qualityCheckDurationHistogram
          .labels("completeness")
          .observe(duration)
        DataQualityMetrics.qualityCheckCounter
          .labels("completeness", "success")
          .inc()

      case Failure(_) =>
        DataQualityMetrics.qualityCheckCounter
          .labels("completeness", "failure")
          .inc()
    }
  }

  /**
   * 有効期間妥当性チェック
   */
  private def checkValidPeriodValidity(
    productRepository: ProductQueryRepository,
    accountSubjectRepository: AccountSubjectQueryRepository
  )(implicit ec: ExecutionContext): Future[Unit] = {
    val startTime = System.currentTimeMillis()

    val checkFuture = for {
      // 商品マスターの有効期間妥当性
      productValidityRate <- productRepository.checkValidPeriodValidity()
      _ = DataQualityMetrics.validPeriodValidityRate
        .labels("Product")
        .set(productValidityRate)

      // 勘定科目マスターの有効期間妥当性
      accountSubjectValidityRate <- accountSubjectRepository.checkValidPeriodValidity()
      _ = DataQualityMetrics.validPeriodValidityRate
        .labels("AccountSubject")
        .set(accountSubjectValidityRate)
    } yield ()

    checkFuture.andThen {
      case Success(_) =>
        val duration = (System.currentTimeMillis() - startTime) / 1000.0
        DataQualityMetrics.qualityCheckDurationHistogram
          .labels("valid_period")
          .observe(duration)
        DataQualityMetrics.qualityCheckCounter
          .labels("valid_period", "success")
          .inc()

      case Failure(_) =>
        DataQualityMetrics.qualityCheckCounter
          .labels("valid_period", "failure")
          .inc()
    }
  }

  /**
   * 参照整合性チェック
   */
  private def checkReferentialIntegrity(
    productRepository: ProductQueryRepository,
    accountSubjectRepository: AccountSubjectQueryRepository
  )(implicit ec: ExecutionContext): Future[Unit] = {
    val startTime = System.currentTimeMillis()

    val checkFuture = for {
      // 商品の主要仕入先が存在するかチェック
      orphanedSuppliers <- productRepository.findOrphanedSupplierReferences()
      _ = if (orphanedSuppliers.nonEmpty) {
        orphanedSuppliers.foreach { productId =>
          DataQualityMetrics.referentialIntegrityErrorCounter
            .labels("Product", "primary_supplier_id", "orphaned_reference")
            .inc()
        }
      }

      // 勘定科目の親が存在するかチェック
      orphanedParents <- accountSubjectRepository.findOrphanedParentReferences()
      _ = if (orphanedParents.nonEmpty) {
        orphanedParents.foreach { accountSubjectId =>
          DataQualityMetrics.referentialIntegrityErrorCounter
            .labels("AccountSubject", "parent_account_subject_id", "orphaned_reference")
            .inc()
        }
      }

      // データ整合性スコア計算（エラー件数に基づく）
      productTotal <- productRepository.count()
      productErrorRate = if (productTotal > 0) orphanedSuppliers.size.toDouble / productTotal else 0.0
      productIntegrityScore = (1.0 - productErrorRate) * 100
      _ = DataQualityMetrics.dataIntegrityScoreGauge
        .labels("Product")
        .set(productIntegrityScore)

      accountSubjectTotal <- accountSubjectRepository.count()
      accountSubjectErrorRate = if (accountSubjectTotal > 0) orphanedParents.size.toDouble / accountSubjectTotal else 0.0
      accountSubjectIntegrityScore = (1.0 - accountSubjectErrorRate) * 100
      _ = DataQualityMetrics.dataIntegrityScoreGauge
        .labels("AccountSubject")
        .set(accountSubjectIntegrityScore)
    } yield ()

    checkFuture.andThen {
      case Success(_) =>
        val duration = (System.currentTimeMillis() - startTime) / 1000.0
        DataQualityMetrics.qualityCheckDurationHistogram
          .labels("referential_integrity")
          .observe(duration)
        DataQualityMetrics.qualityCheckCounter
          .labels("referential_integrity", "success")
          .inc()

      case Failure(_) =>
        DataQualityMetrics.qualityCheckCounter
          .labels("referential_integrity", "failure")
          .inc()
    }
  }

  /**
   * 重複チェック
   */
  private def checkDuplicates(
    productRepository: ProductQueryRepository,
    duplicateCheckService: DuplicateCheckService
  )(implicit ec: ExecutionContext): Future[Unit] = {
    val startTime = System.currentTimeMillis()

    val checkFuture = for {
      // 商品名の類似性チェック
      duplicateCandidates <- duplicateCheckService.findDuplicateProductNames(similarityThreshold = 0.9)
      _ = DataQualityMetrics.duplicateCandidateGauge
        .labels("Product")
        .set(duplicateCandidates.size.toDouble)
    } yield ()

    checkFuture.andThen {
      case Success(_) =>
        val duration = (System.currentTimeMillis() - startTime) / 1000.0
        DataQualityMetrics.qualityCheckDurationHistogram
          .labels("duplicate")
          .observe(duration)
        DataQualityMetrics.qualityCheckCounter
          .labels("duplicate", "success")
          .inc()

      case Failure(_) =>
        DataQualityMetrics.qualityCheckCounter
          .labels("duplicate", "failure")
          .inc()
    }
  }
}
```

## 11.3 同期状況モニタリング

### 11.3.1 同期メトリクス定義

```scala
package com.example.masterdata.infrastructure.monitoring

/**
 * 同期状況メトリクス
 */
object SyncMetrics {

  // ==========================================
  // イベント処理状況
  // ==========================================

  /**
   * イベント処理遅延時間（ゲージ）
   */
  val eventProcessingLagGauge: Gauge = Gauge.build()
    .name("masterdata_event_processing_lag_seconds")
    .help("イベント処理遅延時間（秒）")
    .labelNames("topic", "bounded_context", "aggregate_type")
    .register()

  /**
   * 未処理イベント件数
   */
  val unprocessedEventGauge: Gauge = Gauge.build()
    .name("masterdata_unprocessed_event_total")
    .help("未処理イベント件数")
    .labelNames("topic", "bounded_context")
    .register()

  /**
   * イベント処理成功件数
   */
  val eventProcessingSuccessCounter: Counter = Counter.build()
    .name("masterdata_event_processing_success_total")
    .help("イベント処理成功件数")
    .labelNames("topic", "bounded_context", "event_type")
    .register()

  /**
   * イベント処理失敗件数
   */
  val eventProcessingFailureCounter: Counter = Counter.build()
    .name("masterdata_event_processing_failure_total")
    .help("イベント処理失敗件数")
    .labelNames("topic", "bounded_context", "event_type", "error_type")
    .register()

  /**
   * イベント処理エラー率
   */
  val eventProcessingErrorRate: Gauge = Gauge.build()
    .name("masterdata_event_processing_error_rate")
    .help("イベント処理エラー率")
    .labelNames("topic", "bounded_context")
    .register()

  /**
   * イベント処理時間
   */
  val eventProcessingDurationHistogram: Histogram = Histogram.build()
    .name("masterdata_event_processing_duration_seconds")
    .help("イベント処理時間（秒）")
    .labelNames("topic", "bounded_context", "event_type")
    .buckets(0.001, 0.005, 0.01, 0.05, 0.1, 0.5, 1, 2, 5)
    .register()

  // ==========================================
  // 参照データ同期状態
  // ==========================================

  /**
   * 参照データ最終更新時刻（Unixタイムスタンプ）
   */
  val referenceDataLastUpdateGauge: Gauge = Gauge.build()
    .name("masterdata_reference_data_last_update_timestamp")
    .help("参照データ最終更新時刻（Unixタイムスタンプ）")
    .labelNames("bounded_context", "aggregate_type")
    .register()

  /**
   * 同期遅延時間（マスター変更から参照データ反映まで）
   */
  val syncDelayHistogram: Histogram = Histogram.build()
    .name("masterdata_sync_delay_seconds")
    .help("同期遅延時間（秒）：マスター変更から参照データ反映まで")
    .labelNames("bounded_context", "aggregate_type")
    .buckets(0.1, 0.5, 1, 2, 5, 10, 30, 60, 300, 600)
    .register()

  /**
   * 参照データ件数
   */
  val referenceDataCountGauge: Gauge = Gauge.build()
    .name("masterdata_reference_data_count")
    .help("参照データ件数")
    .labelNames("bounded_context", "aggregate_type")
    .register()

  /**
   * 同期エラー件数
   */
  val syncErrorCounter: Counter = Counter.build()
    .name("masterdata_sync_error_total")
    .help("同期エラー件数")
    .labelNames("bounded_context", "aggregate_type", "error_type")
    .register()
}
```

### 11.3.2 同期監視サービス

```scala
package com.example.masterdata.infrastructure.monitoring

import org.apache.pekko.actor.typed.{ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import java.time.Instant

/**
 * 同期監視サービス
 */
object SyncMonitoringService {

  sealed trait Command
  case object MonitorSync extends Command
  private case object ScheduledMonitoring extends Command

  case class SyncStatus(
    topic: String,
    boundedContext: String,
    aggregateType: String,
    lastEventTime: Instant,
    lastProcessedTime: Instant,
    lagSeconds: Long,
    unprocessedCount: Int,
    errorCount: Int,
    totalCount: Int
  )

  def apply(
    inventoryProductRefRepository: ProductReferenceRepository,
    orderProductRefRepository: ProductReferenceRepository,
    accountingAccountSubjectRefRepository: AccountSubjectReferenceRepository
  )(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        // 30秒ごとに同期状況を監視
        timers.startTimerWithFixedDelay(ScheduledMonitoring, 30.seconds)

        Behaviors.receiveMessage {
          case MonitorSync | ScheduledMonitoring =>
            context.log.debug("同期状況監視開始")

            val monitoringFuture = for {
              _ <- monitorInventorySync(inventoryProductRefRepository)
              _ <- monitorOrderSync(orderProductRefRepository)
              _ <- monitorAccountingSync(accountingAccountSubjectRefRepository)
            } yield ()

            monitoringFuture.onComplete {
              case Success(_) =>
                context.log.debug("同期状況監視完了")

              case Failure(ex) =>
                context.log.error("同期状況監視失敗", ex)
            }

            Behaviors.same
        }
      }
    }
  }

  /**
   * 在庫管理サービスの商品参照データ同期監視
   */
  private def monitorInventorySync(
    repository: ProductReferenceRepository
  )(implicit ec: ExecutionContext): Future[Unit] = {
    for {
      syncStatus <- repository.getSyncStatus()
      _ = {
        val now = Instant.now()
        val lagSeconds = now.getEpochSecond - syncStatus.lastProcessedTime.getEpochSecond

        // 遅延時間
        SyncMetrics.eventProcessingLagGauge
          .labels("master-data.product", "inventory-management", "Product")
          .set(lagSeconds.toDouble)

        // 最終更新時刻
        SyncMetrics.referenceDataLastUpdateGauge
          .labels("inventory-management", "Product")
          .set(syncStatus.lastProcessedTime.getEpochSecond.toDouble)

        // 参照データ件数
        SyncMetrics.referenceDataCountGauge
          .labels("inventory-management", "Product")
          .set(syncStatus.totalCount.toDouble)

        // 遅延警告（5分以上）
        if (lagSeconds > 300) {
          context.log.warn(s"在庫管理サービスの商品参照データ同期に遅延: ${lagSeconds}秒")
        }
      }
    } yield ()
  }

  /**
   * 受注管理サービスの商品参照データ同期監視
   */
  private def monitorOrderSync(
    repository: ProductReferenceRepository
  )(implicit ec: ExecutionContext): Future[Unit] = {
    for {
      syncStatus <- repository.getSyncStatus()
      _ = {
        val now = Instant.now()
        val lagSeconds = now.getEpochSecond - syncStatus.lastProcessedTime.getEpochSecond

        SyncMetrics.eventProcessingLagGauge
          .labels("master-data.product", "order-management", "Product")
          .set(lagSeconds.toDouble)

        SyncMetrics.referenceDataLastUpdateGauge
          .labels("order-management", "Product")
          .set(syncStatus.lastProcessedTime.getEpochSecond.toDouble)

        SyncMetrics.referenceDataCountGauge
          .labels("order-management", "Product")
          .set(syncStatus.totalCount.toDouble)

        if (lagSeconds > 300) {
          context.log.warn(s"受注管理サービスの商品参照データ同期に遅延: ${lagSeconds}秒")
        }
      }
    } yield ()
  }

  /**
   * 会計サービスの勘定科目参照データ同期監視
   */
  private def monitorAccountingSync(
    repository: AccountSubjectReferenceRepository
  )(implicit ec: ExecutionContext): Future[Unit] = {
    for {
      syncStatus <- repository.getSyncStatus()
      _ = {
        val now = Instant.now()
        val lagSeconds = now.getEpochSecond - syncStatus.lastProcessedTime.getEpochSecond

        SyncMetrics.eventProcessingLagGauge
          .labels("master-data.account-subject", "accounting", "AccountSubject")
          .set(lagSeconds.toDouble)

        SyncMetrics.referenceDataLastUpdateGauge
          .labels("accounting", "AccountSubject")
          .set(syncStatus.lastProcessedTime.getEpochSecond.toDouble)

        SyncMetrics.referenceDataCountGauge
          .labels("accounting", "AccountSubject")
          .set(syncStatus.totalCount.toDouble)

        if (lagSeconds > 300) {
          context.log.warn(s"会計サービスの勘定科目参照データ同期に遅延: ${lagSeconds}秒")
        }
      }
    } yield ()
  }
}
```

## 11.4 Grafanaダッシュボード

### 11.4.1 ビジネスメトリクスダッシュボード

```json
{
  "dashboard": {
    "title": "マスターデータ管理 - ビジネスメトリクス",
    "panels": [
      {
        "id": 1,
        "title": "商品マスター総数",
        "type": "graph",
        "targets": [
          {
            "expr": "sum(masterdata_product_total) by (status)",
            "legendFormat": "{{status}}"
          }
        ]
      },
      {
        "id": 2,
        "title": "月間変更件数（過去30日）",
        "type": "graph",
        "targets": [
          {
            "expr": "sum(masterdata_monthly_change_count) by (aggregate_type, change_type)",
            "legendFormat": "{{aggregate_type}} - {{change_type}}"
          }
        ]
      },
      {
        "id": 3,
        "title": "承認待ち件数",
        "type": "stat",
        "targets": [
          {
            "expr": "sum(masterdata_pending_approval_total)",
            "legendFormat": "承認待ち"
          }
        ]
      },
      {
        "id": 4,
        "title": "承認率",
        "type": "gauge",
        "targets": [
          {
            "expr": "avg(masterdata_approval_rate) by (request_type)",
            "legendFormat": "{{request_type}}"
          }
        ]
      },
      {
        "id": 5,
        "title": "承認処理時間（P95）",
        "type": "graph",
        "targets": [
          {
            "expr": "histogram_quantile(0.95, sum(rate(masterdata_approval_duration_seconds_bucket[5m])) by (le, request_type))",
            "legendFormat": "{{request_type}}"
          }
        ]
      },
      {
        "id": 6,
        "title": "日別変更件数",
        "type": "bargauge",
        "targets": [
          {
            "expr": "sum(masterdata_daily_change_count) by (aggregate_type)",
            "legendFormat": "{{aggregate_type}}"
          }
        ]
      }
    ]
  }
}
```

### 11.4.2 データ品質ダッシュボード

```json
{
  "dashboard": {
    "title": "マスターデータ管理 - データ品質",
    "panels": [
      {
        "id": 1,
        "title": "データ整合性スコア",
        "type": "gauge",
        "targets": [
          {
            "expr": "masterdata_data_integrity_score",
            "legendFormat": "{{aggregate_type}}"
          }
        ],
        "fieldConfig": {
          "defaults": {
            "thresholds": {
              "mode": "absolute",
              "steps": [
                {"value": 0, "color": "red"},
                {"value": 80, "color": "yellow"},
                {"value": 95, "color": "green"}
              ]
            },
            "min": 0,
            "max": 100
          }
        }
      },
      {
        "id": 2,
        "title": "必須項目充足率",
        "type": "heatmap",
        "targets": [
          {
            "expr": "masterdata_required_field_completion_rate",
            "legendFormat": "{{aggregate_type}} - {{field_name}}"
          }
        ]
      },
      {
        "id": 3,
        "title": "参照整合性エラー件数",
        "type": "table",
        "targets": [
          {
            "expr": "sum(increase(masterdata_referential_integrity_error_total[24h])) by (aggregate_type, field_name, error_type)",
            "format": "table"
          }
        ]
      },
      {
        "id": 4,
        "title": "重複候補件数",
        "type": "stat",
        "targets": [
          {
            "expr": "masterdata_duplicate_candidate_count",
            "legendFormat": "{{aggregate_type}}"
          }
        ]
      },
      {
        "id": 5,
        "title": "データ品質チェック処理時間",
        "type": "graph",
        "targets": [
          {
            "expr": "histogram_quantile(0.95, sum(rate(masterdata_quality_check_duration_seconds_bucket[5m])) by (le, check_type))",
            "legendFormat": "{{check_type}} (P95)"
          }
        ]
      }
    ]
  }
}
```

### 11.4.3 同期状況ダッシュボード

```json
{
  "dashboard": {
    "title": "マスターデータ管理 - 同期状況",
    "panels": [
      {
        "id": 1,
        "title": "イベント処理遅延時間",
        "type": "graph",
        "targets": [
          {
            "expr": "masterdata_event_processing_lag_seconds",
            "legendFormat": "{{bounded_context}} - {{aggregate_type}}"
          }
        ],
        "alert": {
          "conditions": [
            {
              "evaluator": {
                "params": [300],
                "type": "gt"
              },
              "query": {
                "params": ["A", "5m", "now"]
              }
            }
          ],
          "name": "イベント処理遅延アラート",
          "message": "イベント処理が5分以上遅延しています"
        }
      },
      {
        "id": 2,
        "title": "未処理イベント件数",
        "type": "stat",
        "targets": [
          {
            "expr": "sum(masterdata_unprocessed_event_total) by (bounded_context)",
            "legendFormat": "{{bounded_context}}"
          }
        ]
      },
      {
        "id": 3,
        "title": "イベント処理成功率",
        "type": "graph",
        "targets": [
          {
            "expr": "sum(rate(masterdata_event_processing_success_total[5m])) by (bounded_context) / (sum(rate(masterdata_event_processing_success_total[5m])) by (bounded_context) + sum(rate(masterdata_event_processing_failure_total[5m])) by (bounded_context))",
            "legendFormat": "{{bounded_context}}"
          }
        ]
      },
      {
        "id": 4,
        "title": "参照データ最終更新時刻",
        "type": "table",
        "targets": [
          {
            "expr": "masterdata_reference_data_last_update_timestamp",
            "format": "table"
          }
        ]
      },
      {
        "id": 5,
        "title": "同期遅延時間（P95）",
        "type": "graph",
        "targets": [
          {
            "expr": "histogram_quantile(0.95, sum(rate(masterdata_sync_delay_seconds_bucket[5m])) by (le, bounded_context, aggregate_type))",
            "legendFormat": "{{bounded_context}} - {{aggregate_type}}"
          }
        ]
      },
      {
        "id": 6,
        "title": "参照データ件数",
        "type": "bargauge",
        "targets": [
          {
            "expr": "masterdata_reference_data_count",
            "legendFormat": "{{bounded_context}} - {{aggregate_type}}"
          }
        ]
      }
    ]
  }
}
```

## 11.5 アラート設定

### 11.5.1 Prometheus Alertmanagerルール

```yaml
# prometheus-alerts.yml
groups:
  - name: masterdata_alerts
    interval: 30s
    rules:
      # イベント処理遅延アラート
      - alert: EventProcessingLagHigh
        expr: masterdata_event_processing_lag_seconds > 300
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "イベント処理遅延が高い"
          description: "{{ $labels.bounded_context }}の{{ $labels.aggregate_type }}イベント処理が5分以上遅延しています（現在: {{ $value }}秒）"

      # イベント処理遅延クリティカルアラート
      - alert: EventProcessingLagCritical
        expr: masterdata_event_processing_lag_seconds > 1800
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "イベント処理遅延がクリティカル"
          description: "{{ $labels.bounded_context }}の{{ $labels.aggregate_type }}イベント処理が30分以上遅延しています（現在: {{ $value }}秒）"

      # データ整合性スコア低下アラート
      - alert: DataIntegrityScoreLow
        expr: masterdata_data_integrity_score < 95
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "データ整合性スコアが低下"
          description: "{{ $labels.aggregate_type }}のデータ整合性スコアが95未満です（現在: {{ $value }}）"

      # 承認待ち件数多数アラート
      - alert: PendingApprovalHigh
        expr: sum(masterdata_pending_approval_total) > 50
        for: 1h
        labels:
          severity: warning
        annotations:
          summary: "承認待ち件数が多い"
          description: "承認待ちの変更申請が50件を超えています（現在: {{ $value }}件）"

      # 承認タイムアウトアラート
      - alert: ApprovalTimeout
        expr: increase(masterdata_approval_timeout_total[1h]) > 5
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "承認タイムアウトが発生"
          description: "過去1時間に{{ $value }}件の承認タイムアウトが発生しました"

      # イベント処理エラー率高アラート
      - alert: EventProcessingErrorRateHigh
        expr: masterdata_event_processing_error_rate > 0.1
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "イベント処理エラー率が高い"
          description: "{{ $labels.bounded_context }}のイベント処理エラー率が10%を超えています（現在: {{ $value }}）"

      # 参照整合性エラーアラート
      - alert: ReferentialIntegrityErrors
        expr: increase(masterdata_referential_integrity_error_total[1h]) > 10
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "参照整合性エラーが多発"
          description: "{{ $labels.aggregate_type }}で過去1時間に{{ $value }}件の参照整合性エラーが発生しました"
```

### 11.5.2 アラート通知設定

```yaml
# alertmanager.yml
global:
  resolve_timeout: 5m
  slack_api_url: 'https://hooks.slack.com/services/YOUR/SLACK/WEBHOOK'

route:
  group_by: ['alertname', 'cluster', 'service']
  group_wait: 10s
  group_interval: 10s
  repeat_interval: 12h
  receiver: 'slack-notifications'
  routes:
    - match:
        severity: critical
      receiver: 'slack-critical'
      continue: true
    - match:
        severity: warning
      receiver: 'slack-warnings'

receivers:
  - name: 'slack-notifications'
    slack_configs:
      - channel: '#masterdata-alerts'
        title: 'マスターデータアラート'
        text: '{{ range .Alerts }}{{ .Annotations.description }}{{ end }}'

  - name: 'slack-critical'
    slack_configs:
      - channel: '#masterdata-critical'
        title: 'クリティカルアラート'
        text: '{{ range .Alerts }}{{ .Annotations.description }}{{ end }}'
        color: 'danger'

  - name: 'slack-warnings'
    slack_configs:
      - channel: '#masterdata-warnings'
        title: '警告アラート'
        text: '{{ range .Alerts }}{{ .Annotations.description }}{{ end }}'
        color: 'warning'
```

## 11.6 まとめ

本章では、マスターデータ管理サービスの運用とモニタリングを詳細に実装しました。

### 実装した内容

1. **ビジネスメトリクス**
   - マスターデータ統計（商品5,000 SKU、勘定科目150科目）
   - 変更頻度（月間800件）
   - 承認状況（承認待ち件数、承認率、平均承認時間）

2. **データ品質メトリクス**
   - データ完全性（必須項目充足率、有効期間妥当性、参照整合性）
   - 重複データ（重複候補検出、マージ実施件数）
   - データ整合性スコア（0-100）

3. **同期状況モニタリング**
   - イベント処理状況（遅延時間、未処理件数、エラー率）
   - 参照データの同期状態（最終更新時刻、同期遅延時間）

4. **Grafanaダッシュボード**
   - ビジネスメトリクスダッシュボード
   - データ品質ダッシュボード
   - 同期状況ダッシュボード

5. **アラート設定**
   - Prometheus Alertmanagerルール
   - Slack通知設定

これらの監視機構により、プロダクション環境での安定運用とプロアクティブな問題検知が実現できます。

### 次章の予告

次の第12章では、本シリーズの総まとめと実践演習を行います。これまでに学んだ知識を統合し、実際のプロジェクトで活用するためのベストプラクティスと演習課題を提供します。
