# 第6部12章：まとめと実践演習

## 本章の目的

本章では、第6部で学んだ会計サービスの内容を総括し、実践的な演習問題に取り組みます。これまでに学んだ技術を組み合わせて、実務で必要となる機能を実装します。

## 12.1 学んだこと

### イベント駆動会計

第6部では、ビジネスイベントから仕訳を自動生成するイベント駆動会計を実装しました。

#### 主要な実装内容

**1. ビジネスイベントから仕訳への自動生成**
- `OrderConfirmed` → 売上仕訳（借方:売掛金、貸方:売上高+仮受消費税）
- `InspectionCompleted` → 仕入仕訳（借方:仕入高+仮払消費税、貸方:買掛金）
- `PaymentReceived` → 入金仕訳（借方:普通預金、貸方:売掛金）

**2. イベントソーシングによる監査証跡**
- 全ての仕訳変更を永続化（JournalEntryCreated、JournalEntryApproved、JournalEntryPosted）
- 元トランザクションとの紐付け（sourceEventId）
- 完全な変更履歴の追跡

**3. 冪等性の保証**
- 冪等性キーによる重複検知
- ネットワーク障害時の安全性確保

### 財務諸表作成

**1. 損益計算書（Income Statement）**
```scala
final case class IncomeStatement(
  revenue: Money,              // 売上高
  costOfGoodsSold: Money,     // 売上原価
  grossProfit: Money,         // 売上総利益
  operatingExpenses: Money,   // 販売費及び一般管理費
  operatingIncome: Money,     // 営業利益
  ordinaryIncome: Money,      // 経常利益
  netIncome: Money            // 当期純利益
)
```

**2. 貸借対照表（Balance Sheet）**
```scala
final case class BalanceSheet(
  currentAssets: Money,       // 流動資産
  fixedAssets: Money,         // 固定資産
  totalAssets: Money,         // 総資産
  currentLiabilities: Money,  // 流動負債
  fixedLiabilities: Money,    // 固定負債
  totalLiabilities: Money,    // 負債合計
  totalEquity: Money          // 純資産
)
```

**3. キャッシュフロー計算書（Cash Flow Statement）**
- 営業活動によるキャッシュフロー（間接法）
- 投資活動によるキャッシュフロー
- 財務活動によるキャッシュフロー

### 決算処理

**1. 月次決算処理（MonthlyClosingSaga）**
- 7つのステップ: 全仕訳転記確認 → 試算表作成 → 検証 → 損益計算書作成 → 貸借対照表作成 → 承認 → ロック
- Sagaパターンによる長期トランザクション管理
- 処理時間: 30分以内（目標達成）

**2. 年次決算処理（AnnualClosingSaga）**
- 減価償却費の自動計算（定額法・定率法）
- 棚卸資産の評価
- 決算整理仕訳の自動計上
- 次年度への繰越

**3. 減価償却の実装**
```scala
def calculateAnnualDepreciation(asset: DepreciableAsset): Money = {
  asset.depreciationMethod match {
    case StraightLine =>
      (asset.acquisitionCost - asset.residualValue) / asset.usefulLife
    case DecliningBalance(rate) =>
      (asset.acquisitionCost - asset.accumulatedDepreciation) * rate
  }
}
```

### パフォーマンス最適化

**1. Pekko Streamsによる仕訳生成の高速化**
- 並列処理（parallelism = 8）
- バッチ保存（100件/バッチ）
- スループット: 100件/秒（10倍高速化）

**2. Materialized Viewによる財務諸表の高速化**
- 試算表作成: 30秒 → 0.5秒（60倍高速化）
- 損益計算書: 25秒 → 0.1秒（250倍高速化）

**3. PostgreSQLパーティショニング**
- 会計年度別のパーティション
- Partition Pruningによる検索高速化

### 運用とモニタリング

**1. ビジネスメトリクスの収集**
- 仕訳処理レート、承認率、エラー率
- 決算処理時間
- 債権債務状況

**2. 会計監査対応**
- イベントソーシングによる完全な監査証跡
- 職務分掌（作成者と承認者の分離）
- RBACによるアクセス制御

### 高度なトピック

**1. 複数通貨会計**
- 外貨建取引の処理（TTM、TTS、TTBレート）
- 期末評価替えと為替差損益の計上

**2. 連結会計**
- 個別財務諸表の合算
- 内部取引の相殺消去
- 非支配株主持分の計算

**3. 管理会計**
- 部門別損益管理
- プロジェクト別損益管理

### D社での成果

| 項目 | 従来 | 最適化後 | 改善率 |
|---|---|---|---|
| 仕訳生成（日次2,200件） | 220秒 | 22秒 | 10倍 |
| 試算表作成 | 30秒 | 0.5秒 | 60倍 |
| 損益計算書作成 | 25秒 | 0.1秒 | 250倍 |
| 月次決算処理 | 180分 | 25分 | 7倍 |

## 12.2 実践演習

### 演習1: 消費税申告データの作成

消費税申告に必要なデータを集計するサービスを実装します。

#### 要件

- 課税売上高の集計
- 課税仕入高の集計
- 仮受消費税・仮払消費税の計算
- 納付税額の算出

#### 実装

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.usecase

import scala.concurrent.{ExecutionContext, Future}
import java.time.YearMonth

/**
 * 消費税申告データ作成サービス
 */
class ConsumptionTaxReturnService(
  generalLedgerRepository: GeneralLedgerRepository
)(implicit ec: ExecutionContext) {

  /**
   * 消費税申告データを作成
   *
   * 日本の消費税:
   * - 標準税率: 10%（軽減税率: 8%）
   * - 納付税額 = 仮受消費税 - 仮払消費税
   */
  def createConsumptionTaxReturn(
    fiscalYear: FiscalYear,
    fromMonth: YearMonth,
    toMonth: YearMonth
  ): Future[ConsumptionTaxReturn] = {

    for {
      // 1. 課税売上高を集計（売上高勘定の合計）
      taxableSales <- generalLedgerRepository.sumByAccountRange(
        AccountCode("4000"),  // 売上高の範囲開始
        AccountCode("4999"),  // 売上高の範囲終了
        fiscalYear,
        fromMonth,
        toMonth
      )

      // 2. 仮受消費税を集計（仮受消費税勘定）
      consumptionTaxPayable <- generalLedgerRepository.sumByAccount(
        AccountCode("2190"),  // 仮受消費税
        fiscalYear,
        fromMonth,
        toMonth
      )

      // 3. 課税仕入高を集計（仕入高・経費勘定の合計）
      taxablePurchases <- generalLedgerRepository.sumByAccountRange(
        AccountCode("5000"),  // 仕入高・経費の範囲開始
        AccountCode("5999"),  // 仕入高・経費の範囲終了
        fiscalYear,
        fromMonth,
        toMonth
      )

      // 4. 仮払消費税を集計（仮払消費税勘定）
      consumptionTaxReceivable <- generalLedgerRepository.sumByAccount(
        AccountCode("1180"),  // 仮払消費税
        fiscalYear,
        fromMonth,
        toMonth
      )

      // 5. 納付税額を計算
      taxPayable = Money(consumptionTaxPayable.amount - consumptionTaxReceivable.amount)

    } yield ConsumptionTaxReturn(
      fiscalYear = fiscalYear,
      fromMonth = fromMonth,
      toMonth = toMonth,
      taxableSales = taxableSales,
      consumptionTaxPayable = consumptionTaxPayable,
      taxablePurchases = taxablePurchases,
      consumptionTaxReceivable = consumptionTaxReceivable,
      taxPayable = taxPayable,
      calculatedAt = java.time.Instant.now()
    )
  }
}

/**
 * 消費税申告データ
 */
final case class ConsumptionTaxReturn(
  fiscalYear: FiscalYear,
  fromMonth: YearMonth,
  toMonth: YearMonth,
  taxableSales: Money,              // 課税売上高
  consumptionTaxPayable: Money,     // 仮受消費税
  taxablePurchases: Money,          // 課税仕入高
  consumptionTaxReceivable: Money,  // 仮払消費税
  taxPayable: Money,                // 納付税額
  calculatedAt: java.time.Instant
) {

  /**
   * 申告書を生成
   */
  def generateReport(): String = {
    s"""
    |========== 消費税申告書 ==========
    |会計年度: ${fiscalYear.value}
    |対象期間: ${fromMonth} 〜 ${toMonth}
    |
    |【課税売上高】
    |  課税売上高（税抜）:   ${formatMoney(taxableSales)}
    |  仮受消費税:           ${formatMoney(consumptionTaxPayable)}
    |
    |【課税仕入高】
    |  課税仕入高（税抜）:   ${formatMoney(taxablePurchases)}
    |  仮払消費税:           ${formatMoney(consumptionTaxReceivable)}
    |
    |【納付税額】
    |  仮受消費税:           ${formatMoney(consumptionTaxPayable)}
    |  △ 仮払消費税:        ${formatMoney(consumptionTaxReceivable)}
    |  ────────────────────
    |  納付税額:             ${formatMoney(taxPayable)}
    |
    |作成日時: ${calculatedAt}
    |================================
    """.stripMargin
  }

  private def formatMoney(money: Money): String = {
    f"¥${money.amount}%,d"
  }
}
```

#### テスト

```scala
class ConsumptionTaxReturnServiceSpec extends AnyFlatSpec with Matchers {

  "ConsumptionTaxReturnService" should "消費税申告データを正しく計算できる" in {
    // Given: モックデータ
    val generalLedgerRepository = new GeneralLedgerRepository {
      override def sumByAccountRange(...): Future[Money] = {
        // 課税売上高: 12億円（税抜）
        Future.successful(Money(1200000000))
      }

      override def sumByAccount(accountCode: AccountCode, ...): Future[Money] = {
        accountCode match {
          case AccountCode("2190") => Future.successful(Money(120000000))  // 仮受消費税
          case AccountCode("1180") => Future.successful(Money(100000000))  // 仮払消費税
        }
      }
    }

    val service = new ConsumptionTaxReturnService(generalLedgerRepository)

    // When: 消費税申告データを作成
    val result = Await.result(
      service.createConsumptionTaxReturn(
        FiscalYear(2024),
        YearMonth.of(2024, 4),
        YearMonth.of(2025, 3)
      ),
      5.seconds
    )

    // Then: 納付税額が正しく計算される
    result.taxPayable shouldBe Money(20000000)  // 1.2億円 - 1億円 = 2,000万円

    println(result.generateReport())
  }
}
```

### 演習2: キャッシュフロー計算書の作成

間接法によるキャッシュフロー計算書を作成します。

#### 要件

- 営業活動によるキャッシュフロー（間接法）
- 投資活動によるキャッシュフロー
- 財務活動によるキャッシュフロー

#### 実装

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.usecase

/**
 * キャッシュフロー計算書作成サービス
 */
class CashFlowStatementService(
  incomeStatementService: FinancialStatementQueryService,
  balanceSheetService: FinancialStatementQueryService,
  generalLedgerRepository: GeneralLedgerRepository
)(implicit ec: ExecutionContext) {

  /**
   * キャッシュフロー計算書を作成（間接法）
   */
  def createCashFlowStatement(
    fiscalYear: FiscalYear,
    targetMonth: YearMonth
  ): Future[CashFlowStatement] = {

    for {
      // 損益計算書を取得
      incomeStatement <- incomeStatementService.getIncomeStatement(fiscalYear, targetMonth)

      // 貸借対照表を取得（当月と前月）
      currentBalanceSheet <- balanceSheetService.getBalanceSheet(fiscalYear, targetMonth)
      previousBalanceSheet <- balanceSheetService.getBalanceSheet(fiscalYear, targetMonth.minusMonths(1))

      // 営業活動によるキャッシュフローを計算
      operatingCashFlow = calculateOperatingCashFlow(
        incomeStatement,
        currentBalanceSheet,
        previousBalanceSheet
      )

      // 投資活動によるキャッシュフローを計算
      investingCashFlow <- calculateInvestingCashFlow(fiscalYear, targetMonth)

      // 財務活動によるキャッシュフローを計算
      financingCashFlow <- calculateFinancingCashFlow(fiscalYear, targetMonth)

      // 現金及び現金同等物の増減額
      cashIncrease = Money(
        operatingCashFlow.amount +
        investingCashFlow.amount +
        financingCashFlow.amount
      )

      // 現金及び現金同等物の期首残高
      cashBeginning = previousBalanceSheet.cash

      // 現金及び現金同等物の期末残高
      cashEnding = Money(cashBeginning.amount + cashIncrease.amount)

    } yield CashFlowStatement(
      fiscalYear = fiscalYear,
      targetMonth = targetMonth,
      operatingActivities = OperatingActivities(
        netIncome = incomeStatement.netIncome,
        depreciation = Money(40000000),  // 減価償却費（実際はDBから取得）
        accountsReceivableDecrease = Money(0),
        inventoryDecrease = Money(0),
        accountsPayableIncrease = Money(0),
        operatingCashFlow = operatingCashFlow
      ),
      investingActivities = InvestingActivities(
        purchaseOfFixedAssets = Money(-50000000),  // 固定資産の取得（実際はDBから取得）
        investingCashFlow = investingCashFlow
      ),
      financingActivities = FinancingActivities(
        proceedsFromBorrowing = Money(100000000),  // 借入による収入（実際はDBから取得）
        repaymentOfBorrowing = Money(-80000000),   // 借入金の返済（実際はDBから取得）
        dividendsPaid = Money(-20000000),          // 配当金の支払い（実際はDBから取得）
        financingCashFlow = financingCashFlow
      ),
      cashIncrease = cashIncrease,
      cashBeginning = cashBeginning,
      cashEnding = cashEnding,
      calculatedAt = java.time.Instant.now()
    )
  }

  /**
   * 営業活動によるキャッシュフローを計算（間接法）
   */
  private def calculateOperatingCashFlow(
    incomeStatement: IncomeStatement,
    currentBS: BalanceSheet,
    previousBS: BalanceSheet
  ): Money = {

    // 営業活動によるキャッシュフロー（間接法）
    // = 当期純利益
    // + 減価償却費（非資金費用）
    // + 売上債権の減少（または - 増加）
    // + 棚卸資産の減少（または - 増加）
    // + 仕入債務の増加（または - 減少）

    val netIncome = incomeStatement.netIncome.amount
    val depreciation = 40000000L  // 減価償却費（実際はDBから取得）

    val accountsReceivableChange = previousBS.accountsReceivable.amount - currentBS.accountsReceivable.amount
    val inventoryChange = previousBS.inventory.amount - currentBS.inventory.amount
    val accountsPayableChange = currentBS.accountsPayable.amount - previousBS.accountsPayable.amount

    Money(
      netIncome +
      depreciation +
      accountsReceivableChange +
      inventoryChange +
      accountsPayableChange
    )
  }

  /**
   * 投資活動によるキャッシュフローを計算
   */
  private def calculateInvestingCashFlow(
    fiscalYear: FiscalYear,
    targetMonth: YearMonth
  ): Future[Money] = {

    // 固定資産の取得・売却などを集計
    // 実装は省略
    Future.successful(Money(-50000000))  // 例: 5,000万円の固定資産取得
  }

  /**
   * 財務活動によるキャッシュフローを計算
   */
  private def calculateFinancingCashFlow(
    fiscalYear: FiscalYear,
    targetMonth: YearMonth
  ): Future[Money] = {

    // 借入・返済・配当などを集計
    // 実装は省略
    Future.successful(Money(0))  // 例: 収支ゼロ
  }
}

/**
 * キャッシュフロー計算書
 */
final case class CashFlowStatement(
  fiscalYear: FiscalYear,
  targetMonth: YearMonth,
  operatingActivities: OperatingActivities,
  investingActivities: InvestingActivities,
  financingActivities: FinancingActivities,
  cashIncrease: Money,
  cashBeginning: Money,
  cashEnding: Money,
  calculatedAt: java.time.Instant
) {

  def report(): String = {
    s"""
    |========== キャッシュフロー計算書 ==========
    |会計年度: ${fiscalYear.value}
    |対象月: ${targetMonth}
    |
    |【営業活動によるキャッシュフロー】
    |  当期純利益:           ${formatMoney(operatingActivities.netIncome)}
    |  減価償却費:           ${formatMoney(operatingActivities.depreciation)}
    |  営業活動CF:           ${formatMoney(operatingActivities.operatingCashFlow)}
    |
    |【投資活動によるキャッシュフロー】
    |  固定資産の取得:       ${formatMoney(investingActivities.purchaseOfFixedAssets)}
    |  投資活動CF:           ${formatMoney(investingActivities.investingCashFlow)}
    |
    |【財務活動によるキャッシュフロー】
    |  借入による収入:       ${formatMoney(financingActivities.proceedsFromBorrowing)}
    |  借入金の返済:         ${formatMoney(financingActivities.repaymentOfBorrowing)}
    |  配当金の支払い:       ${formatMoney(financingActivities.dividendsPaid)}
    |  財務活動CF:           ${formatMoney(financingActivities.financingCashFlow)}
    |
    |【現金及び現金同等物の増減】
    |  現金増減額:           ${formatMoney(cashIncrease)}
    |  現金期首残高:         ${formatMoney(cashBeginning)}
    |  現金期末残高:         ${formatMoney(cashEnding)}
    |==========================================
    """.stripMargin
  }

  private def formatMoney(money: Money): String = {
    f"¥${money.amount}%,d"
  }
}

final case class OperatingActivities(
  netIncome: Money,
  depreciation: Money,
  accountsReceivableDecrease: Money,
  inventoryDecrease: Money,
  accountsPayableIncrease: Money,
  operatingCashFlow: Money
)

final case class InvestingActivities(
  purchaseOfFixedAssets: Money,
  investingCashFlow: Money
)

final case class FinancingActivities(
  proceedsFromBorrowing: Money,
  repaymentOfBorrowing: Money,
  dividendsPaid: Money,
  financingCashFlow: Money
)
```

### 演習3: 経営ダッシュボードの実装

GraphQL APIで経営ダッシュボードに必要なデータを提供します。

#### GraphQLスキーマ

```graphql
# schema.graphql

type Query {
  # 経営ダッシュボード
  managementDashboard(fiscalYear: Int!, targetMonth: String!): ManagementDashboard!
}

type ManagementDashboard {
  # 基本情報
  fiscalYear: Int!
  targetMonth: String!

  # 財務サマリー
  financialSummary: FinancialSummary!

  # 経営指標
  indicators: ManagementIndicators!

  # 売上推移（直近12ヶ月）
  revenueTrend: [MonthlyRevenue!]!

  # 部門別損益
  departmentProfitAndLoss: [DepartmentPL!]!

  # 債権債務状況
  receivablesAndPayables: ReceivablesAndPayables!

  # アラート
  alerts: [Alert!]!
}

type FinancialSummary {
  revenue: Long!              # 売上高
  grossProfit: Long!          # 売上総利益
  operatingIncome: Long!      # 営業利益
  netIncome: Long!            # 当期純利益
  totalAssets: Long!          # 総資産
  totalLiabilities: Long!     # 負債合計
  totalEquity: Long!          # 純資産
}

type ManagementIndicators {
  grossProfitMargin: Float!        # 粗利率
  operatingProfitMargin: Float!    # 営業利益率
  netProfitMargin: Float!          # 純利益率
  roa: Float!                      # ROA
  roe: Float!                      # ROE
  currentRatio: Float!             # 流動比率
  equityRatio: Float!              # 自己資本比率
}

type MonthlyRevenue {
  month: String!
  revenue: Long!
  profit: Long!
}

type DepartmentPL {
  departmentId: String!
  departmentName: String!
  revenue: Long!
  profit: Long!
  profitMargin: Float!
}

type ReceivablesAndPayables {
  accountsReceivable: Long!
  accountsPayable: Long!
  overdueReceivables: Long!
  agingAnalysis: AgingAnalysis!
}

type AgingAnalysis {
  current: Long!     # 30日以内
  days30: Long!      # 31-60日
  days60: Long!      # 61-90日
  days90: Long!      # 91-120日
  over90days: Long!  # 120日超
}

type Alert {
  severity: AlertSeverity!
  title: String!
  message: String!
  timestamp: String!
}

enum AlertSeverity {
  INFO
  WARNING
  CRITICAL
}
```

#### Resolver実装

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.interfaceadapter.graphql

import sangria.schema.*
import scala.concurrent.{ExecutionContext, Future}

/**
 * 経営ダッシュボードResolver
 */
class ManagementDashboardResolver(
  financialStatementService: FinancialStatementQueryService,
  financialAnalysisService: FinancialAnalysisService,
  departmentPLService: DepartmentProfitAndLossService,
  accountsReceivableService: AccountsReceivableMetricsCollector
)(implicit ec: ExecutionContext) {

  /**
   * 経営ダッシュボードデータを取得
   */
  def getManagementDashboard(
    fiscalYear: Int,
    targetMonth: String
  ): Future[ManagementDashboard] = {

    val fy = FiscalYear(fiscalYear)
    val month = YearMonth.parse(targetMonth)

    for {
      // 財務諸表を取得
      statements <- financialStatementService.getPreCalculatedStatements(fy, month)

      // 経営指標を計算
      indicators <- financialAnalysisService.calculateAllIndicators(fy, month)

      // 売上推移を取得（直近12ヶ月）
      revenueTrend <- getRevenueTrend(fy, month)

      // 部門別損益を取得
      departmentPL <- departmentPLService.calculateAllDepartmentsProfitAndLoss(fy, month)

      // 債権債務状況を取得
      receivablesAndPayables <- getReceivablesAndPayables()

      // アラートを取得
      alerts <- getAlerts(fy, month, indicators)

    } yield ManagementDashboard(
      fiscalYear = fiscalYear,
      targetMonth = targetMonth,
      financialSummary = FinancialSummary(
        revenue = statements.get.incomeStatement.revenue.amount,
        grossProfit = statements.get.incomeStatement.grossProfit.amount,
        operatingIncome = statements.get.incomeStatement.operatingIncome.amount,
        netIncome = statements.get.incomeStatement.netIncome.amount,
        totalAssets = statements.get.balanceSheet.totalAssets.amount,
        totalLiabilities = statements.get.balanceSheet.totalLiabilities.amount,
        totalEquity = statements.get.balanceSheet.totalEquity.amount
      ),
      indicators = ManagementIndicators(
        grossProfitMargin = indicators.profitability.grossProfitMargin,
        operatingProfitMargin = indicators.profitability.operatingProfitMargin,
        netProfitMargin = indicators.profitability.netProfitMargin,
        roa = indicators.profitability.returnOnAssets,
        roe = indicators.profitability.returnOnEquity,
        currentRatio = indicators.safety.currentRatio,
        equityRatio = indicators.safety.equityRatio
      ),
      revenueTrend = revenueTrend,
      departmentProfitAndLoss = departmentPL.map { dept =>
        DepartmentPL(
          departmentId = dept.department.id.value,
          departmentName = dept.department.name,
          revenue = dept.revenue.amount,
          profit = dept.departmentProfit.amount,
          profitMargin = dept.profitMargin
        )
      },
      receivablesAndPayables = receivablesAndPayables,
      alerts = alerts
    )
  }

  private def getRevenueTrend(
    fiscalYear: FiscalYear,
    targetMonth: YearMonth
  ): Future[List[MonthlyRevenue]] = {
    // 直近12ヶ月の売上推移を取得
    // 実装は省略
    Future.successful(List.empty)
  }

  private def getReceivablesAndPayables(): Future[ReceivablesAndPayables] = {
    // 債権債務状況を取得
    // 実装は省略
    Future.successful(ReceivablesAndPayables(
      accountsReceivable = 1500000000L,
      accountsPayable = 1200000000L,
      overdueReceivables = 50000000L,
      agingAnalysis = AgingAnalysis(
        current = 1200000000L,
        days30 = 200000000L,
        days60 = 80000000L,
        days90 = 20000000L,
        over90days = 0L
      )
    ))
  }

  private def getAlerts(
    fiscalYear: FiscalYear,
    targetMonth: YearMonth,
    indicators: FinancialIndicators
  ): Future[List[Alert]] = {

    val alerts = scala.collection.mutable.ListBuffer[Alert]()

    // 粗利率が20%未満の場合は警告
    if (indicators.profitability.grossProfitMargin < 20.0) {
      alerts += Alert(
        severity = AlertSeverity.WARNING,
        title = "粗利率低下",
        message = s"粗利率が${indicators.profitability.grossProfitMargin}%に低下しています（目標: 20%以上）",
        timestamp = java.time.Instant.now().toString
      )
    }

    // 自己資本比率が30%未満の場合は警告
    if (indicators.safety.equityRatio < 30.0) {
      alerts += Alert(
        severity = AlertSeverity.CRITICAL,
        title = "自己資本比率低下",
        message = s"自己資本比率が${indicators.safety.equityRatio}%に低下しています（目標: 30%以上）",
        timestamp = java.time.Instant.now().toString
      )
    }

    Future.successful(alerts.toList)
  }
}

// GraphQLのcase class定義
final case class ManagementDashboard(
  fiscalYear: Int,
  targetMonth: String,
  financialSummary: FinancialSummary,
  indicators: ManagementIndicators,
  revenueTrend: List[MonthlyRevenue],
  departmentProfitAndLoss: List[DepartmentPL],
  receivablesAndPayables: ReceivablesAndPayables,
  alerts: List[Alert]
)

final case class FinancialSummary(
  revenue: Long,
  grossProfit: Long,
  operatingIncome: Long,
  netIncome: Long,
  totalAssets: Long,
  totalLiabilities: Long,
  totalEquity: Long
)

final case class ManagementIndicators(
  grossProfitMargin: Double,
  operatingProfitMargin: Double,
  netProfitMargin: Double,
  roa: Double,
  roe: Double,
  currentRatio: Double,
  equityRatio: Double
)

final case class MonthlyRevenue(
  month: String,
  revenue: Long,
  profit: Long
)

final case class DepartmentPL(
  departmentId: String,
  departmentName: String,
  revenue: Long,
  profit: Long,
  profitMargin: Double
)

final case class ReceivablesAndPayables(
  accountsReceivable: Long,
  accountsPayable: Long,
  overdueReceivables: Long,
  agingAnalysis: AgingAnalysis
)

final case class AgingAnalysis(
  current: Long,
  days30: Long,
  days60: Long,
  days90: Long,
  over90days: Long
)

final case class Alert(
  severity: AlertSeverity,
  title: String,
  message: String,
  timestamp: String
)

sealed trait AlertSeverity
object AlertSeverity {
  case object INFO extends AlertSeverity
  case object WARNING extends AlertSeverity
  case object CRITICAL extends AlertSeverity
}
```

### 演習4: 予実管理機能の拡張

第8章で実装した予実管理機能を拡張し、より詳細な分析を行います。

#### 要件

- 予算の登録・更新
- 予算vs実績の差異分析（部門別）
- 着地予想の算出
- 予算達成率のトレンド分析

#### 実装

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.usecase

/**
 * 拡張予実管理サービス
 */
class EnhancedBudgetManagementService(
  budgetRepository: BudgetRepository,
  actualPerformanceService: ActualPerformanceService,
  forecastService: ForecastService
)(implicit ec: ExecutionContext) {

  /**
   * 部門別予実分析を実行
   */
  def analyzeDepartmentBudgetVsActual(
    fiscalYear: FiscalYear,
    targetMonth: YearMonth
  ): Future[List[DepartmentBudgetAnalysis]] = {

    for {
      // 全部門の予算を取得
      budgets <- budgetRepository.findAllByMonth(fiscalYear, targetMonth)

      // 各部門の実績を取得
      analyses <- Future.sequence(budgets.map { budget =>
        for {
          actual <- actualPerformanceService.getActualByDepartment(
            budget.departmentId.get,
            fiscalYear,
            targetMonth
          )
        } yield DepartmentBudgetAnalysis(
          departmentId = budget.departmentId.get,
          departmentName = budget.departmentName.getOrElse(""),
          budget = budget,
          actual = actual,
          variance = Money(actual.revenue.amount - budget.revenue.amount),
          achievementRate = if (budget.revenue.amount > 0) {
            (actual.revenue.amount.toDouble / budget.revenue.amount.toDouble) * 100.0
          } else 0.0,
          status = determineStatus(actual.revenue, budget.revenue)
        )
      })

    } yield analyses
  }

  /**
   * 予算達成状況を判定
   */
  private def determineStatus(actual: Money, budget: Money): BudgetStatus = {
    val achievementRate = if (budget.amount > 0) {
      (actual.amount.toDouble / budget.amount.toDouble) * 100.0
    } else 0.0

    if (achievementRate >= 100.0) BudgetStatus.Achieved
    else if (achievementRate >= 90.0) BudgetStatus.OnTrack
    else if (achievementRate >= 70.0) BudgetStatus.AtRisk
    else BudgetStatus.BehindSchedule
  }

  /**
   * 年度末着地予想を詳細分析
   */
  def analyzeYearEndForecast(
    fiscalYear: FiscalYear,
    currentMonth: YearMonth
  ): Future[DetailedYearEndForecast] = {

    for {
      // 年度予算を取得
      annualBudget <- budgetRepository.findAnnualBudget(fiscalYear)

      // 現時点までの実績を取得
      actualPerformances <- actualPerformanceService.getActualsByYear(fiscalYear, currentMonth)

      // 基本的な着地予想を算出
      basicForecast = forecastService.forecastYearEnd(
        fiscalYear,
        currentMonth,
        actualPerformances,
        annualBudget
      )

      // トレンド分析
      trend = analyzeTrend(actualPerformances)

      // リスク分析
      risks = analyzeRisks(basicForecast, annualBudget, trend)

    } yield DetailedYearEndForecast(
      fiscalYear = fiscalYear,
      asOfMonth = currentMonth,
      basicForecast = basicForecast,
      trend = trend,
      risks = risks,
      recommendations = generateRecommendations(basicForecast, annualBudget, risks)
    )
  }

  private def analyzeTrend(
    actualPerformances: List[ActualPerformance]
  ): PerformanceTrend = {
    // 実績のトレンド分析（上昇・横ばい・下降）
    // 実装は省略
    PerformanceTrend.Stable
  }

  private def analyzeRisks(
    forecast: YearEndForecast,
    budget: Budget,
    trend: PerformanceTrend
  ): List[Risk] = {
    // リスク分析
    // 実装は省略
    List.empty
  }

  private def generateRecommendations(
    forecast: YearEndForecast,
    budget: Budget,
    risks: List[Risk]
  ): List[String] = {
    // 推奨アクションを生成
    val recommendations = scala.collection.mutable.ListBuffer[String]()

    if (forecast.revenueAchievementRate < 90.0) {
      recommendations += "売上拡大施策の強化が必要です"
      recommendations += "営業部門へのリソース追加を検討してください"
    }

    if (forecast.netIncomeAchievementRate < 80.0) {
      recommendations += "コスト削減施策を至急実施してください"
      recommendations += "不採算事業の見直しを検討してください"
    }

    recommendations.toList
  }
}

final case class DepartmentBudgetAnalysis(
  departmentId: DepartmentId,
  departmentName: String,
  budget: Budget,
  actual: ActualPerformance,
  variance: Money,
  achievementRate: Double,
  status: BudgetStatus
)

sealed trait BudgetStatus
object BudgetStatus {
  case object Achieved extends BudgetStatus         // 達成
  case object OnTrack extends BudgetStatus          // 順調
  case object AtRisk extends BudgetStatus           // 要注意
  case object BehindSchedule extends BudgetStatus   // 遅延
}

final case class DetailedYearEndForecast(
  fiscalYear: FiscalYear,
  asOfMonth: YearMonth,
  basicForecast: YearEndForecast,
  trend: PerformanceTrend,
  risks: List[Risk],
  recommendations: List[String]
)

sealed trait PerformanceTrend
object PerformanceTrend {
  case object Improving extends PerformanceTrend    // 改善傾向
  case object Stable extends PerformanceTrend       // 安定
  case object Declining extends PerformanceTrend    // 悪化傾向
}

final case class Risk(
  category: RiskCategory,
  severity: RiskSeverity,
  description: String,
  impact: Money
)

sealed trait RiskCategory
object RiskCategory {
  case object Revenue extends RiskCategory          // 売上リスク
  case object Cost extends RiskCategory             // コストリスク
  case object Market extends RiskCategory           // 市場リスク
}

sealed trait RiskSeverity
object RiskSeverity {
  case object High extends RiskSeverity
  case object Medium extends RiskSeverity
  case object Low extends RiskSeverity
}
```

## 12.3 次のステップ

### より高度な会計処理

第6部で学んだ基礎をもとに、さらに高度な会計処理に挑戦できます。

**1. リース会計**
- ファイナンスリースとオペレーティングリースの区分
- リース資産・リース債務の計上
- リース料の利息部分と元本部分の按分

**2. 税効果会計**
- 一時差異の認識
- 繰延税金資産・繰延税金負債の計上
- 実効税率の適用

**3. 退職給付会計**
- 退職給付債務の計算
- 年金資産の時価評価
- 数理計算上の差異の処理

### 他のBounded Contextとの統合

**1. 人事給与システム（給料仕訳）**
```scala
// 給料支払いイベント → 給料仕訳
SalaryPaidEvent → JournalEntry(
  借方: 給料手当、法定福利費
  貸方: 預り金、普通預金
)
```

**2. 固定資産管理システム（減価償却）**
```scala
// 固定資産取得イベント → 資産計上仕訳
FixedAssetAcquiredEvent → JournalEntry(
  借方: 建物・機械装置
  貸方: 未払金・普通預金
)
```

**3. 予算管理システム（予実管理）**
- 予算の承認ワークフロー
- 予算vs実績の自動比較
- 予算超過アラート

### BI・データ分析

**1. 多次元分析（OLAP）**
- 時系列分析（年次・月次・日次）
- 部門別分析
- 商品別分析
- 顧客別分析

**2. 経営ダッシュボード**
- リアルタイムKPI表示
- ドリルダウン分析
- What-if分析

**3. 予測分析**
- 売上予測（機械学習）
- 資金繰り予測
- 与信リスク予測

## まとめ

第6部では、CQRS/イベントソーシングアーキテクチャを用いた会計サービスの実装を学びました。

### 達成した成果

- **イベント駆動会計**: 年間190万件の仕訳を自動生成
- **高速化**: 月次決算25分、試算表0.5秒、財務諸表0.1秒
- **完全な監査証跡**: イベントソーシングによる全変更の記録
- **内部統制**: 職務分掌とRBACによる不正防止
- **高度な機能**: 複数通貨会計、連結会計、管理会計

### D社への貢献

- 経理業務の効率化（月次決算時間を7倍短縮）
- リアルタイムな経営情報の提供
- コンプライアンス対応の強化
- 意思決定の高度化

おめでとうございます！これで第6部「会計サービスのケーススタディ」を完了しました。
