# 第6部9章：パフォーマンス最適化

## 本章の目的

本章では、会計システムのパフォーマンス最適化を学びます。D社では、月間67,000件の仕訳を処理し、試算表を5秒以内、財務諸表を10秒以内で作成する必要があります。

このような高いパフォーマンス要件を満たすには、適切なアーキテクチャパターンとデータベース最適化が不可欠です。

### 学習内容

1. Pekko Streamsによる仕訳の一括生成
2. Materialized Viewによる財務諸表の高速化
3. PostgreSQLパーティショニングによる総勘定元帳の最適化
4. キャッシングとインデックス戦略
5. パフォーマンス測定とチューニング

## 9.1 仕訳生成の高速化

### 課題：大量の仕訳を効率的に生成

D社では、日次で約2,200件のビジネスイベント（受注、発注、入金など）が発生し、それぞれから仕訳を生成する必要があります。

#### 従来の処理方式の問題点

```scala
// ❌ 非効率な処理（1件ずつ同期処理）
def generateJournalEntriesSequentially(events: List[BusinessEvent]): List[JournalEntry] = {
  events.map { event =>
    // 1件ずつDBアクセスして仕訳を生成
    val entry = generateJournalEntry(event)
    saveToDatabase(entry)  // 同期的にDB保存
    entry
  }
}

// 問題点:
// - 2,200件のイベント × 平均100msの処理時間 = 220秒（約3.7分）
// - DBへの接続が2,200回発生
// - メモリ効率が悪い（全てをメモリに保持）
```

### 解決策：Pekko Streamsによるストリーム処理

Pekko Streamsを使用することで、バックプレッシャー制御、並列処理、バッチ処理を実現します。

#### JournalEntryGenerationStream の実装

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.usecase

import org.apache.pekko.stream.scaladsl.{Flow, Sink, Source}
import org.apache.pekko.stream.{Materializer, OverflowStrategy}
import org.apache.pekko.NotUsed
import scala.concurrent.{ExecutionContext, Future}
import com.github.j5ik2o.pekko.cqrs.accounting.domain.event.*
import com.github.j5ik2o.pekko.cqrs.accounting.domain.model.*

/**
 * Pekko Streamsを使用した仕訳生成パイプライン
 */
class JournalEntryGenerationStream(
  salesGenerator: SalesJournalEntryGenerator,
  purchaseGenerator: PurchaseJournalEntryGenerator,
  paymentGenerator: PaymentReceivedJournalEntryGenerator,
  journalEntryRepository: JournalEntryRepository
)(implicit
  materializer: Materializer,
  ec: ExecutionContext
) {

  /**
   * ビジネスイベントから仕訳を生成するストリーム
   */
  def createJournalEntryPipeline(): Flow[BusinessEvent, JournalEntry, NotUsed] = {
    Flow[BusinessEvent]
      // Step 1: イベントタイプに応じて仕訳を生成（CPU集約的な処理）
      .mapAsyncUnordered(parallelism = 8) { event =>
        Future {
          generateJournalEntry(event)
        }
      }
      // Step 2: 生成に成功した仕訳のみを通過させる
      .collect {
        case Right(entry) => entry
      }
      // Step 3: バッチでグルーピング（100件ずつ）
      .grouped(100)
      // Step 4: バッチをDBに保存（I/O集約的な処理）
      .mapAsyncUnordered(parallelism = 4) { batch =>
        journalEntryRepository.saveBatch(batch).map(_ => batch)
      }
      // Step 5: バッチをフラット化して個別の仕訳に戻す
      .mapConcat(identity)
  }

  /**
   * イベントタイプに応じて仕訳を生成
   */
  private def generateJournalEntry(
    event: BusinessEvent
  ): Either[ValidationError, JournalEntry] = {

    val fiscalYear = FiscalYear.fromDate(event.eventDate)
    val fiscalPeriod = FiscalPeriod.fromDate(event.eventDate)

    event match {
      case e: OrderConfirmed =>
        salesGenerator.generateFromOrderConfirmed(e, fiscalYear, fiscalPeriod)

      case e: InspectionCompleted =>
        purchaseGenerator.generateFromInspectionCompleted(e, fiscalYear, fiscalPeriod)

      case e: PaymentReceived =>
        paymentGenerator.generateFromPaymentReceived(e, fiscalYear, fiscalPeriod)

      case _ =>
        Left(ValidationError(s"未対応のイベントタイプ: ${event.getClass.getSimpleName}"))
    }
  }

  /**
   * 日次バッチ処理を実行
   */
  def processDailyBatch(
    targetDate: java.time.LocalDate
  ): Future[BatchProcessingResult] = {

    val startTime = System.currentTimeMillis()

    // イベントストアから該当日のイベントを取得
    val eventSource: Source[BusinessEvent, NotUsed] = Source
      .future(fetchBusinessEvents(targetDate))
      .mapConcat(identity)

    // パイプラインを実行
    val resultFuture = eventSource
      .via(createJournalEntryPipeline())
      .runWith(Sink.seq)

    resultFuture.map { entries =>
      val endTime = System.currentTimeMillis()
      val duration = endTime - startTime

      BatchProcessingResult(
        targetDate = targetDate,
        totalEvents = entries.size,
        successfulEntries = entries.size,
        failedEntries = 0,
        processingTimeMs = duration,
        throughput = if (duration > 0) (entries.size.toDouble / (duration / 1000.0)) else 0.0
      )
    }
  }

  /**
   * リアルタイムストリーム処理
   *
   * イベントストリームを購読し、リアルタイムに仕訳を生成
   */
  def startRealtimeProcessing(
    eventStream: Source[BusinessEvent, NotUsed]
  ): Future[Unit] = {

    eventStream
      .buffer(1000, OverflowStrategy.backpressure)  // バックプレッシャー制御
      .via(createJournalEntryPipeline())
      .runWith(Sink.ignore)
      .map(_ => ())
  }

  private def fetchBusinessEvents(
    targetDate: java.time.LocalDate
  ): Future[List[BusinessEvent]] = {
    // イベントストアから該当日のイベントを取得
    // 実装は省略
    Future.successful(List.empty)
  }
}

/**
 * バッチ処理結果
 */
final case class BatchProcessingResult(
  targetDate: java.time.LocalDate,
  totalEvents: Int,
  successfulEntries: Int,
  failedEntries: Int,
  processingTimeMs: Long,
  throughput: Double  // 件/秒
) {

  def report(): String = {
    s"""
    |========== 仕訳生成バッチ処理結果 ==========
    |処理日:       ${targetDate}
    |処理件数:     ${totalEvents}件
    |成功:         ${successfulEntries}件
    |失敗:         ${failedEntries}件
    |処理時間:     ${processingTimeMs}ms (${processingTimeMs / 1000.0}秒)
    |スループット: ${f"$throughput%.2f"}件/秒
    |==========================================
    """.stripMargin
  }
}
```

#### バッチ処理のパフォーマンス比較

```scala
// D社の日次仕訳生成（2,200件のイベント）

// ❌ 従来方式（同期処理）
// 処理時間: 220秒（約3.7分）
// スループット: 10件/秒

// ✅ Pekko Streams方式（並列処理 + バッチ保存）
// 処理時間: 22秒
// スループット: 100件/秒
// 改善率: 10倍高速化

val result = BatchProcessingResult(
  targetDate = LocalDate.of(2024, 7, 1),
  totalEvents = 2200,
  successfulEntries = 2200,
  failedEntries = 0,
  processingTimeMs = 22000,
  throughput = 100.0
)

println(result.report())

/*
========== 仕訳生成バッチ処理結果 ==========
処理日:       2024-07-01
処理件数:     2200件
成功:         2200件
失敗:         0件
処理時間:     22000ms (22.0秒)
スループット: 100.00件/秒
==========================================
*/
```

### Pekko Streamsの最適化テクニック

#### 1. 並列度の調整

```scala
// CPU集約的な処理（仕訳生成）
.mapAsyncUnordered(parallelism = 8) { event =>
  // CPUコア数に応じて調整
  // 8コアのサーバーなら parallelism = 8 が最適
}

// I/O集約的な処理（DB保存）
.mapAsyncUnordered(parallelism = 4) { batch =>
  // DBコネクションプールのサイズに応じて調整
  // コネクションプール = 10 なら parallelism = 4-8 が最適
}
```

#### 2. バッチサイズの最適化

```scala
// バッチサイズのチューニング
.grouped(batchSize)  // バッチサイズを調整

// バッチサイズが小さすぎる場合:
// - DB接続回数が増える
// - ネットワークオーバーヘッドが大きい

// バッチサイズが大きすぎる場合:
// - メモリ消費が増える
// - レスポンスタイムが悪化

// D社の最適値: 100件/バッチ
// - 2,200件のイベント → 22回のDB接続
// - メモリ消費: 約10MB/バッチ
```

#### 3. バックプレッシャー制御

```scala
eventStream
  .buffer(1000, OverflowStrategy.backpressure)
  // バッファが満杯になったら upstream の処理を遅くする
  // これにより、メモリ溢れを防ぐ
```

## 9.2 財務諸表作成の最適化

### 課題：試算表を5秒以内、財務諸表を10秒以内で作成

財務諸表の作成は、総勘定元帳の全データを集計する必要があり、データ量が多いと時間がかかります。

#### 従来の処理方式の問題点

```sql
-- ❌ 非効率な試算表作成クエリ
SELECT
  account_code,
  account_name,
  SUM(CASE WHEN debit_credit = 'DEBIT' THEN amount ELSE 0 END) as debit_total,
  SUM(CASE WHEN debit_credit = 'CREDIT' THEN amount ELSE 0 END) as credit_total
FROM general_ledger
WHERE fiscal_year = 2024
  AND fiscal_period <= 4
GROUP BY account_code, account_name
ORDER BY account_code;

-- 問題点:
-- - general_ledger テーブルの全行をスキャン（数百万行）
-- - 毎回集計計算が発生
-- - 処理時間: 30秒以上
```

### 解決策1：Materialized View（物理化ビュー）

Materialized Viewを使用して、集計結果を事前計算して保存します。

#### 勘定科目別残高のMaterialized View

```sql
-- 月次勘定科目別残高のMaterialized View
CREATE MATERIALIZED VIEW mv_monthly_account_balance AS
SELECT
  fiscal_year,
  fiscal_period,
  account_code,
  account_name,
  account_type,
  SUM(CASE WHEN debit_credit = 'DEBIT' THEN amount ELSE 0 END) as debit_total,
  SUM(CASE WHEN debit_credit = 'CREDIT' THEN amount ELSE 0 END) as credit_total,
  SUM(CASE WHEN debit_credit = 'DEBIT' THEN amount ELSE -amount END) as balance,
  COUNT(*) as entry_count,
  MAX(posted_at) as last_posted_at
FROM general_ledger
WHERE status = 'POSTED'
GROUP BY
  fiscal_year,
  fiscal_period,
  account_code,
  account_name,
  account_type;

-- インデックスの作成
CREATE UNIQUE INDEX idx_mv_monthly_balance_pk
  ON mv_monthly_account_balance(fiscal_year, fiscal_period, account_code);

CREATE INDEX idx_mv_monthly_balance_account_type
  ON mv_monthly_account_balance(fiscal_year, fiscal_period, account_type);

-- Materialized Viewのリフレッシュ
-- 仕訳が転記されるたびに更新（CONCURRENTLY オプションで読み取り可能）
REFRESH MATERIALIZED VIEW CONCURRENTLY mv_monthly_account_balance;
```

#### Materialized Viewを使用した試算表作成

```sql
-- ✅ 高速な試算表作成クエリ（Materialized Viewを使用）
SELECT
  account_code,
  account_name,
  account_type,
  SUM(debit_total) as debit_total,
  SUM(credit_total) as credit_total,
  SUM(balance) as balance
FROM mv_monthly_account_balance
WHERE fiscal_year = 2024
  AND fiscal_period <= 4
GROUP BY account_code, account_name, account_type
ORDER BY account_code;

-- パフォーマンス:
-- - 処理時間: 0.5秒以下（60倍高速化）
-- - 理由: 集計済みのデータを読むだけ
```

#### Materialized View管理サービス

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.infrastructure

import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.PostgresProfile.api._

/**
 * Materialized View管理サービス
 */
class MaterializedViewManager(database: Database)(implicit ec: ExecutionContext) {

  /**
   * 月次残高ビューをリフレッシュ
   */
  def refreshMonthlyBalance(): Future[Unit] = {
    val sql = sql"""
      REFRESH MATERIALIZED VIEW CONCURRENTLY mv_monthly_account_balance
    """.as[Int]

    database.run(sql).map(_ => ())
  }

  /**
   * 仕訳転記後に自動的にビューをリフレッシュ
   */
  def refreshAfterPosting(
    fiscalYear: FiscalYear,
    fiscalPeriod: FiscalPeriod
  ): Future[Unit] = {
    // 特定の会計期間のみをリフレッシュ（部分更新）
    // PostgreSQL 9.4以降では増分更新が可能

    for {
      _ <- refreshMonthlyBalance()
      _ <- refreshTrialBalanceCache(fiscalYear, fiscalPeriod)
    } yield ()
  }

  /**
   * 試算表キャッシュをリフレッシュ
   */
  private def refreshTrialBalanceCache(
    fiscalYear: FiscalYear,
    fiscalPeriod: FiscalPeriod
  ): Future[Unit] = {
    // Redis や Memcached にキャッシュ
    // 実装は省略
    Future.successful(())
  }
}
```

### 解決策2：財務諸表の事前計算

決算処理時に財務諸表を事前計算し、JSONBとして保存します。

#### 財務諸表の事前計算と保存

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.usecase

/**
 * 財務諸表の事前計算サービス
 */
class FinancialStatementPreCalculationService(
  generalLedgerQueryService: GeneralLedgerQueryService,
  financialStatementRepository: FinancialStatementRepository
)(implicit ec: ExecutionContext) {

  /**
   * 月次決算時に財務諸表を事前計算して保存
   */
  def preCalculateMonthlyStatements(
    fiscalYear: FiscalYear,
    fiscalPeriod: FiscalPeriod,
    targetMonth: YearMonth
  ): Future[Unit] = {

    for {
      // 試算表を作成
      trialBalance <- generateTrialBalance(fiscalYear, fiscalPeriod, targetMonth)

      // 損益計算書を作成
      incomeStatement <- generateIncomeStatement(fiscalYear, fiscalPeriod, targetMonth)

      // 貸借対照表を作成
      balanceSheet <- generateBalanceSheet(fiscalYear, fiscalPeriod, targetMonth)

      // キャッシュフロー計算書を作成
      cashFlowStatement <- generateCashFlowStatement(fiscalYear, fiscalPeriod, targetMonth)

      // 全てをDBに保存（JSONBとして）
      _ <- financialStatementRepository.save(
        FinancialStatementSet(
          fiscalYear = fiscalYear,
          fiscalPeriod = fiscalPeriod,
          targetMonth = targetMonth,
          trialBalance = trialBalance,
          incomeStatement = incomeStatement,
          balanceSheet = balanceSheet,
          cashFlowStatement = Some(cashFlowStatement),
          calculatedAt = java.time.Instant.now()
        )
      )

    } yield ()
  }

  /**
   * 事前計算済みの財務諸表を取得
   */
  def getPreCalculatedStatements(
    fiscalYear: FiscalYear,
    targetMonth: YearMonth
  ): Future[Option[FinancialStatementSet]] = {
    financialStatementRepository.findByMonth(fiscalYear, targetMonth)
  }
}

/**
 * 財務諸表セット
 */
final case class FinancialStatementSet(
  fiscalYear: FiscalYear,
  fiscalPeriod: FiscalPeriod,
  targetMonth: YearMonth,
  trialBalance: TrialBalance,
  incomeStatement: IncomeStatement,
  balanceSheet: BalanceSheet,
  cashFlowStatement: Option[CashFlowStatement],
  calculatedAt: java.time.Instant
)
```

#### 財務諸表テーブルのスキーマ

```sql
-- 事前計算済み財務諸表テーブル
CREATE TABLE financial_statements (
  id BIGSERIAL PRIMARY KEY,
  fiscal_year INTEGER NOT NULL,
  fiscal_period INTEGER NOT NULL,
  target_month DATE NOT NULL,

  -- 各財務諸表をJSONBとして保存
  trial_balance JSONB NOT NULL,
  income_statement JSONB NOT NULL,
  balance_sheet JSONB NOT NULL,
  cash_flow_statement JSONB,

  calculated_at TIMESTAMP NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

  UNIQUE(fiscal_year, target_month)
);

-- インデックス
CREATE INDEX idx_financial_statements_fiscal_year_month
  ON financial_statements(fiscal_year, target_month);

-- JSONB内の特定のフィールドにGINインデックスを作成（高速検索用）
CREATE INDEX idx_financial_statements_income_gin
  ON financial_statements USING gin(income_statement);
```

#### パフォーマンス比較

```scala
// D社の財務諸表作成パフォーマンス

// ❌ 従来方式（毎回集計計算）
// 試算表:     30秒
// 損益計算書: 25秒
// 貸借対照表: 20秒
// 合計:       75秒

// ✅ 事前計算方式（月次決算時に計算済み）
// 試算表:     0.1秒（JSONBから取得）
// 損益計算書: 0.1秒（JSONBから取得）
// 貸借対照表: 0.1秒（JSONBから取得）
// 合計:       0.3秒（250倍高速化）

// 月次決算時の事前計算時間: 5秒
// - Materialized Viewから集計するため高速
// - 月1回の処理なので許容範囲内
```

## 9.3 総勘定元帳の最適化

### 課題：数百万行の総勘定元帳データの効率的な管理

D社では、年間約190万件の仕訳（約570万行の仕訳明細）が発生します。このような大量データを効率的に管理するには、テーブルパーティショニングが有効です。

### 解決策：会計年度別パーティショニング

#### パーティショニング戦略

```sql
-- 総勘定元帳のパーティションテーブル（会計年度別）

-- 親テーブル（パーティション定義）
CREATE TABLE general_ledger (
  id BIGSERIAL,
  fiscal_year INTEGER NOT NULL,
  fiscal_period INTEGER NOT NULL,
  account_code VARCHAR(10) NOT NULL,
  account_name VARCHAR(100) NOT NULL,
  account_type VARCHAR(20) NOT NULL,
  debit_credit VARCHAR(10) NOT NULL,
  amount BIGINT NOT NULL,
  journal_entry_id VARCHAR(100) NOT NULL,
  entry_date DATE NOT NULL,
  posted_at TIMESTAMP NOT NULL,
  description TEXT,
  status VARCHAR(20) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) PARTITION BY RANGE (fiscal_year);

-- 会計年度別の子パーティション
CREATE TABLE general_ledger_2023 PARTITION OF general_ledger
  FOR VALUES FROM (2023) TO (2024);

CREATE TABLE general_ledger_2024 PARTITION OF general_ledger
  FOR VALUES FROM (2024) TO (2025);

CREATE TABLE general_ledger_2025 PARTITION OF general_ledger
  FOR VALUES FROM (2025) TO (2026);

-- 各パーティションにインデックスを作成
-- パーティション: general_ledger_2024
CREATE INDEX idx_gl_2024_account_period
  ON general_ledger_2024(account_code, fiscal_period);

CREATE INDEX idx_gl_2024_entry_date
  ON general_ledger_2024(entry_date);

CREATE INDEX idx_gl_2024_journal_entry
  ON general_ledger_2024(journal_entry_id);

CREATE INDEX idx_gl_2024_posted_at
  ON general_ledger_2024(posted_at);

-- パーティション: general_ledger_2025
CREATE INDEX idx_gl_2025_account_period
  ON general_ledger_2025(account_code, fiscal_period);

CREATE INDEX idx_gl_2025_entry_date
  ON general_ledger_2025(entry_date);

CREATE INDEX idx_gl_2025_journal_entry
  ON general_ledger_2025(journal_entry_id);

CREATE INDEX idx_gl_2025_posted_at
  ON general_ledger_2025(posted_at);
```

#### パーティショニングのメリット

```sql
-- ✅ クエリがパーティションを自動選択（Partition Pruning）

-- 2024年度のデータのみを検索
SELECT account_code, SUM(amount)
FROM general_ledger
WHERE fiscal_year = 2024
  AND account_code = '1110'
GROUP BY account_code;

-- 実行計画:
-- Partition Pruning により general_ledger_2024 のみをスキャン
-- 他の年度のパーティションは無視される
-- スキャン行数: 約57万行（2024年度のみ）
-- 処理時間: 0.5秒

-- ❌ パーティショニングなしの場合
-- 全年度のデータをスキャン
-- スキャン行数: 約300万行（全年度）
-- 処理時間: 3秒
```

#### パーティション管理の自動化

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.infrastructure

/**
 * パーティション管理サービス
 */
class PartitionManager(database: Database)(implicit ec: ExecutionContext) {

  /**
   * 新しい会計年度のパーティションを作成
   */
  def createPartitionForFiscalYear(fiscalYear: FiscalYear): Future[Unit] = {
    val nextYear = fiscalYear.value + 1
    val sql = sqlu"""
      CREATE TABLE IF NOT EXISTS general_ledger_#${fiscalYear.value}
      PARTITION OF general_ledger
      FOR VALUES FROM (#${fiscalYear.value}) TO (#${nextYear});

      CREATE INDEX IF NOT EXISTS idx_gl_#${fiscalYear.value}_account_period
        ON general_ledger_#${fiscalYear.value}(account_code, fiscal_period);

      CREATE INDEX IF NOT EXISTS idx_gl_#${fiscalYear.value}_entry_date
        ON general_ledger_#${fiscalYear.value}(entry_date);

      CREATE INDEX IF NOT EXISTS idx_gl_#${fiscalYear.value}_journal_entry
        ON general_ledger_#${fiscalYear.value}(journal_entry_id);
    """

    database.run(sql).map(_ => ())
  }

  /**
   * 古いパーティションをアーカイブ（別テーブルに移動）
   */
  def archiveOldPartition(fiscalYear: FiscalYear): Future[Unit] = {
    // 7年以上前のデータはアーカイブテーブルに移動
    // 会計法により、帳簿は7年間の保存が義務付けられている

    val sql = sqlu"""
      -- パーティションをデタッチ
      ALTER TABLE general_ledger
        DETACH PARTITION general_ledger_#${fiscalYear.value};

      -- アーカイブテーブルに名前変更
      ALTER TABLE general_ledger_#${fiscalYear.value}
        RENAME TO general_ledger_archive_#${fiscalYear.value};
    """

    database.run(sql).map(_ => ())
  }

  /**
   * パーティション統計を取得
   */
  def getPartitionStatistics(): Future[List[PartitionStats]] = {
    val sql = sql"""
      SELECT
        schemaname,
        tablename,
        pg_size_pretty(pg_total_relation_size(schemaname||'.'||tablename)) AS size,
        n_tup_ins AS insert_count,
        n_tup_upd AS update_count,
        n_tup_del AS delete_count
      FROM pg_stat_user_tables
      WHERE tablename LIKE 'general_ledger_%'
      ORDER BY tablename;
    """.as[(String, String, String, Long, Long, Long)]

    database.run(sql).map { results =>
      results.map { case (schema, table, size, inserts, updates, deletes) =>
        PartitionStats(schema, table, size, inserts, updates, deletes)
      }.toList
    }
  }
}

final case class PartitionStats(
  schema: String,
  tableName: String,
  size: String,
  insertCount: Long,
  updateCount: Long,
  deleteCount: Long
)
```

### インデックス戦略

#### 複合インデックスの設計

```sql
-- 勘定科目別の期間検索用（頻繁に使用）
CREATE INDEX idx_gl_account_period_date
  ON general_ledger(account_code, fiscal_period, entry_date);

-- 仕訳IDからの逆引き用
CREATE INDEX idx_gl_journal_entry
  ON general_ledger(journal_entry_id);

-- 転記日時での検索用（監査証跡）
CREATE INDEX idx_gl_posted_at
  ON general_ledger(posted_at)
  WHERE status = 'POSTED';  -- 部分インデックス

-- 未転記データの検索用
CREATE INDEX idx_gl_unposted
  ON general_ledger(created_at)
  WHERE status = 'DRAFT';  -- 部分インデックス
```

#### インデックスの効果測定

```sql
-- インデックスの使用状況を確認
SELECT
  schemaname,
  tablename,
  indexname,
  idx_scan,  -- インデックススキャン回数
  idx_tup_read,  -- インデックスから読み取った行数
  idx_tup_fetch  -- インデックスから取得した行数
FROM pg_stat_user_indexes
WHERE schemaname = 'public'
  AND tablename LIKE 'general_ledger%'
ORDER BY idx_scan DESC;

-- 使用されていないインデックスを削除
-- idx_scan = 0 のインデックスは不要
```

## 9.4 キャッシング戦略

### Redis によるクエリ結果のキャッシング

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.infrastructure

import redis.clients.jedis.JedisPool
import io.circe.syntax.*
import io.circe.parser.decode
import scala.concurrent.{ExecutionContext, Future}

/**
 * 財務諸表キャッシュサービス
 */
class FinancialStatementCacheService(
  jedisPool: JedisPool
)(implicit ec: ExecutionContext) {

  /**
   * 試算表をキャッシュに保存
   */
  def cacheTrialBalance(
    fiscalYear: FiscalYear,
    targetMonth: YearMonth,
    trialBalance: TrialBalance
  ): Future[Unit] = Future {
    val jedis = jedisPool.getResource
    try {
      val key = s"trial_balance:${fiscalYear.value}:${targetMonth}"
      val json = trialBalance.asJson.noSpaces

      // 1時間のTTL（Time To Live）
      jedis.setex(key, 3600, json)

    } finally {
      jedis.close()
    }
  }

  /**
   * キャッシュから試算表を取得
   */
  def getCachedTrialBalance(
    fiscalYear: FiscalYear,
    targetMonth: YearMonth
  ): Future[Option[TrialBalance]] = Future {
    val jedis = jedisPool.getResource
    try {
      val key = s"trial_balance:${fiscalYear.value}:${targetMonth}"
      Option(jedis.get(key)).flatMap { json =>
        decode[TrialBalance](json).toOption
      }
    } finally {
      jedis.close()
    }
  }

  /**
   * キャッシュを無効化
   */
  def invalidateCache(
    fiscalYear: FiscalYear,
    targetMonth: YearMonth
  ): Future[Unit] = Future {
    val jedis = jedisPool.getResource
    try {
      // 該当月の全キャッシュを削除
      val pattern = s"*:${fiscalYear.value}:${targetMonth}*"
      val keys = jedis.keys(pattern)
      if (keys.size() > 0) {
        jedis.del(keys.toArray(Array.empty[String]): _*)
      }
    } finally {
      jedis.close()
    }
  }
}
```

### キャッシュを使用した財務諸表取得

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.usecase

/**
 * キャッシュを使用した財務諸表クエリサービス
 */
class CachedFinancialStatementQueryService(
  cacheService: FinancialStatementCacheService,
  statementService: FinancialStatementPreCalculationService
)(implicit ec: ExecutionContext) {

  /**
   * 試算表を取得（キャッシュファーストで）
   */
  def getTrialBalance(
    fiscalYear: FiscalYear,
    targetMonth: YearMonth
  ): Future[TrialBalance] = {

    cacheService.getCachedTrialBalance(fiscalYear, targetMonth).flatMap {
      case Some(cached) =>
        // キャッシュヒット
        Future.successful(cached)

      case None =>
        // キャッシュミス - DBから取得してキャッシュに保存
        statementService.getPreCalculatedStatements(fiscalYear, targetMonth).flatMap {
          case Some(statements) =>
            cacheService.cacheTrialBalance(fiscalYear, targetMonth, statements.trialBalance)
              .map(_ => statements.trialBalance)

          case None =>
            Future.failed(new NoSuchElementException(s"試算表が見つかりません: ${fiscalYear.value} - ${targetMonth}"))
        }
    }
  }
}
```

## 9.5 パフォーマンス測定とモニタリング

### パフォーマンスメトリクスの収集

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.infrastructure

import io.prometheus.client.{Counter, Histogram}

/**
 * パフォーマンスメトリクス
 */
object PerformanceMetrics {

  // 仕訳生成のスループット
  val journalEntryGenerationCounter: Counter = Counter.build()
    .name("journal_entry_generation_total")
    .help("Total number of journal entries generated")
    .labelNames("event_type")
    .register()

  // 仕訳生成の処理時間
  val journalEntryGenerationDuration: Histogram = Histogram.build()
    .name("journal_entry_generation_duration_seconds")
    .help("Journal entry generation duration in seconds")
    .labelNames("event_type")
    .register()

  // 試算表作成の処理時間
  val trialBalanceGenerationDuration: Histogram = Histogram.build()
    .name("trial_balance_generation_duration_seconds")
    .help("Trial balance generation duration in seconds")
    .register()

  // 財務諸表作成の処理時間
  val financialStatementGenerationDuration: Histogram = Histogram.build()
    .name("financial_statement_generation_duration_seconds")
    .help("Financial statement generation duration in seconds")
    .labelNames("statement_type")
    .register()

  // キャッシュヒット率
  val cacheHitCounter: Counter = Counter.build()
    .name("cache_hit_total")
    .help("Total number of cache hits")
    .labelNames("cache_type")
    .register()

  val cacheMissCounter: Counter = Counter.build()
    .name("cache_miss_total")
    .help("Total number of cache misses")
    .labelNames("cache_type")
    .register()
}
```

### パフォーマンス測定の実装

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.usecase

/**
 * パフォーマンス測定を含む仕訳生成サービス
 */
class MonitoredJournalEntryGenerationService(
  generator: SalesJournalEntryGenerator
) {

  def generateFromOrderConfirmed(
    event: OrderConfirmed,
    fiscalYear: FiscalYear,
    fiscalPeriod: FiscalPeriod
  ): Either[ValidationError, JournalEntry] = {

    // 処理時間を測定
    val timer = PerformanceMetrics.journalEntryGenerationDuration
      .labels("order_confirmed")
      .startTimer()

    try {
      val result = generator.generateFromOrderConfirmed(event, fiscalYear, fiscalPeriod)

      // 成功した場合はカウンターをインクリメント
      result.foreach { _ =>
        PerformanceMetrics.journalEntryGenerationCounter
          .labels("order_confirmed")
          .inc()
      }

      result

    } finally {
      // 処理時間を記録
      timer.observeDuration()
    }
  }
}
```

### D社のパフォーマンス目標と実績

```scala
// D社のパフォーマンス要件と最適化後の実績

val performanceResults = Map(
  "仕訳生成（日次2,200件）" -> PerformanceResult(
    requirement = "60秒以内",
    beforeOptimization = "220秒",
    afterOptimization = "22秒",
    improvement = "10倍高速化"
  ),

  "試算表作成" -> PerformanceResult(
    requirement = "5秒以内",
    beforeOptimization = "30秒",
    afterOptimization = "0.5秒",
    improvement = "60倍高速化"
  ),

  "損益計算書作成" -> PerformanceResult(
    requirement = "10秒以内",
    beforeOptimization = "25秒",
    afterOptimization = "0.1秒",
    improvement = "250倍高速化"
  ),

  "貸借対照表作成" -> PerformanceResult(
    requirement = "10秒以内",
    beforeOptimization = "20秒",
    afterOptimization = "0.1秒",
    improvement = "200倍高速化"
  ),

  "月次決算処理" -> PerformanceResult(
    requirement = "30分以内",
    beforeOptimization = "180分",
    afterOptimization = "25分",
    improvement = "7倍高速化"
  )
)

final case class PerformanceResult(
  requirement: String,
  beforeOptimization: String,
  afterOptimization: String,
  improvement: String
)

// レポート出力
performanceResults.foreach { case (operation, result) =>
  println(s"""
  |$operation:
  |  要件:         ${result.requirement}
  |  最適化前:     ${result.beforeOptimization}
  |  最適化後:     ${result.afterOptimization}
  |  改善率:       ${result.improvement}
  """.stripMargin)
}

/*
仕訳生成（日次2,200件）:
  要件:         60秒以内
  最適化前:     220秒
  最適化後:     22秒
  改善率:       10倍高速化

試算表作成:
  要件:         5秒以内
  最適化前:     30秒
  最適化後:     0.5秒
  改善率:       60倍高速化

損益計算書作成:
  要件:         10秒以内
  最適化前:     25秒
  最適化後:     0.1秒
  改善率:       250倍高速化

貸借対照表作成:
  要件:         10秒以内
  最適化前:     20秒
  最適化後:     0.1秒
  改善率:       200倍高速化

月次決算処理:
  要件:         30分以内
  最適化前:     180分
  最適化後:     25分
  改善率:       7倍高速化
*/
```

## まとめ

本章では、会計システムのパフォーマンス最適化を学びました。

### 実装した最適化手法

1. **Pekko Streamsによる仕訳生成の高速化**
   - 並列処理（parallelism = 8）
   - バッチ保存（100件/バッチ）
   - バックプレッシャー制御
   - スループット: 100件/秒（10倍高速化）

2. **Materialized Viewによる財務諸表の高速化**
   - 月次勘定科目別残高の事前集計
   - 試算表作成: 30秒 → 0.5秒（60倍高速化）
   - CONCURRENTLY オプションで読み取り可能

3. **財務諸表の事前計算とキャッシング**
   - 月次決算時にJSONBとして保存
   - 損益計算書: 25秒 → 0.1秒（250倍高速化）
   - Redisによるキャッシング（TTL: 1時間）

4. **PostgreSQLパーティショニング**
   - 会計年度別のパーティション
   - Partition Pruningによる検索高速化
   - スキャン行数: 300万行 → 57万行（約5倍削減）

5. **インデックス戦略**
   - 複合インデックス（account_code, fiscal_period, entry_date）
   - 部分インデックス（WHERE status = 'POSTED'）
   - 使用されていないインデックスの削除

### D社への適用効果

- **仕訳生成**: 220秒 → 22秒（10倍高速化）
- **試算表作成**: 30秒 → 0.5秒（60倍高速化）
- **財務諸表作成**: 25秒 → 0.1秒（250倍高速化）
- **月次決算**: 180分 → 25分（7倍高速化）
- **全ての要件を達成**: ✅

次章では、運用とモニタリング（ビジネスメトリクス、会計監査対応、内部統制）を学びます。