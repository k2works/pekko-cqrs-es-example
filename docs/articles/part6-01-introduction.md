# 第6部 第1章 イントロダクション：会計サービスの要件定義

## 1.1 第3部〜第5部の振り返り

第6部では、これまでに構築してきた在庫管理、受注管理、発注管理の各システムで発生した全てのビジネストランザクションを会計仕訳に変換し、財務諸表を作成する会計システムを構築します。

### 1.1.1 在庫管理システム（第3部）

第3部では、D社の在庫管理システムをCQRS/イベントソーシングで実装しました。

**実装した機能**:
- 在庫受払処理（1日約2,000件）
- 在庫引当の競合制御（楽観的ロック）
- 複数拠点・区画管理（3拠点・9区画）
- 保管条件管理（常温・冷蔵・冷凍）

**会計への影響**:

```scala
// 在庫受払イベントから会計仕訳を生成する必要がある

// 入庫時（仕入時）
case InventoryEvent.InventoryIncreased(productId, quantity, unitCost, reason) =>
  // 借方: 商品（資産）  貸方: 買掛金（負債）
  // または
  // 借方: 商品（資産）  貸方: 現金（資産）

// 出庫時（売上時）
case InventoryEvent.InventoryDecreased(productId, quantity, unitCost, reason) =>
  // 借方: 売上原価（費用）  貸方: 商品（資産）
```

**会計仕訳の例**:

| 取引 | 借方 | 貸方 |
|------|------|------|
| 商品仕入（買掛） | 商品 1,000,000円 | 買掛金 1,000,000円 |
| 商品仕入（現金） | 商品 500,000円 | 現金 500,000円 |
| 商品販売（売上原価） | 売上原価 800,000円 | 商品 800,000円 |

**年間取引規模**:
- 入庫: 約36,000件/年（月間3,000件）
- 出庫: 約600,000件/年（月間50,000件）
- 合計仕訳: 約636,000件/年

### 1.1.2 受注管理システム（第4部）

第4部では、在庫管理システムに受注管理機能を追加し、Sagaパターンによる分散トランザクションを実装しました。

**実装した機能**:
- 見積もり作成から注文への変換
- 受注処理（月間約50,000件）
- 与信管理（取引先別与信限度額）
- 請求書発行と入金管理
- OrderSaga（注文→与信チェック→在庫引当→確定→出荷）

**会計への影響**:

```scala
// 受注確定イベント
case OrderEvent.OrderConfirmed(orderId, customerId, totalAmount, items) =>
  // 借方: 売掛金（資産）  貸方: 売上高（収益）
  // 消費税の処理も必要
  // 借方: 売掛金（資産）  貸方: 仮受消費税（負債）

// 入金イベント
case PaymentEvent.PaymentReceived(customerId, amount, receivedDate) =>
  // 借方: 現金（資産）  貸方: 売掛金（資産）
```

**会計仕訳の例**:

| 取引 | 借方 | 貸方 |
|------|------|------|
| 売上計上（税抜） | 売掛金 10,000,000円 | 売上高 10,000,000円 |
| 消費税計上（10%） | 売掛金 1,000,000円 | 仮受消費税 1,000,000円 |
| 入金処理 | 現金 11,000,000円 | 売掛金 11,000,000円 |

**年間取引規模**:
- 売上計上: 約600,000件/年（月間50,000件）
- 入金処理: 約600,000件/年
- 合計仕訳: 約1,200,000件/年（消費税含む）

### 1.1.3 発注管理システム（第5部）

第5部では、仕入先管理から発注、入荷検品、支払管理までの調達プロセス全体をイベントソーシングで実装しました。

**実装した機能**:
- 発注処理（月間約3,000件）
- 承認ワークフロー（金額に応じた多段階承認）
- 入荷検品と差異管理
- 3-way matching（発注・入荷・請求の突合）
- 支払処理

**会計への影響**:

```scala
// 発注確定イベント
case PurchaseOrderEvent.PurchaseOrderIssued(poId, supplierId, totalAmount, items) =>
  // この時点では仕訳なし（発注残として管理）

// 入荷完了イベント（検収合格）
case ReceivingEvent.InspectionCompleted(receivingId, acceptedItems) =>
  // 借方: 商品（資産）  貸方: 買掛金（負債）
  // 消費税の処理
  // 借方: 仮払消費税（資産）  貸方: 買掛金（負債）

// 支払イベント
case PaymentEvent.PaymentCompleted(supplierId, amount, paidDate) =>
  // 借方: 買掛金（負債）  貸方: 現金（資産）
```

**会計仕訳の例**:

| 取引 | 借方 | 貸方 |
|------|------|------|
| 仕入計上（税抜） | 商品 8,000,000円 | 買掛金 8,000,000円 |
| 消費税計上（10%） | 仮払消費税 800,000円 | 買掛金 800,000円 |
| 支払処理 | 買掛金 8,800,000円 | 現金 8,800,000円 |

**年間取引規模**:
- 仕入計上: 約36,000件/年（月間3,000件）
- 支払処理: 約36,000件/年
- 合計仕訳: 約72,000件/年（消費税含む）

### 1.1.4 今回追加する機能

第6部では、これらのビジネスイベントから会計仕訳を自動生成し、財務諸表を作成する機能を実装します。

**新規に実装する集約**:

1. **ChartOfAccounts集約**: 勘定科目体系の管理
2. **JournalEntry集約**: 仕訳の生成と管理
3. **GeneralLedger集約**: 総勘定元帳の管理
4. **FinancialStatement集約**: 財務諸表の作成
5. **AccountsReceivable集約**: 売掛金管理
6. **AccountsPayable集約**: 買掛金管理
7. **Closing集約**: 月次・年次決算処理

**イベント駆動会計の全体像**:

```
┌─────────────────┐
│ 在庫管理        │
│ (第3部)         │
│ - 在庫受払      │
└────────┬────────┘
         │ InventoryEvent
         ▼
┌─────────────────┐       ┌──────────────────┐
│ 受注管理        │       │ 会計システム      │
│ (第4部)         │──────▶│ (第6部)          │
│ - 売上・入金    │       │ - 仕訳生成        │
└────────┬────────┘       │ - 総勘定元帳      │
         │ OrderEvent    │ - 財務諸表        │
         │ PaymentEvent  │ - 決算処理        │
         ▼               └──────────────────┘
┌─────────────────┐
│ 発注管理        │
│ (第5部)         │
│ - 仕入・支払    │
└─────────────────┘
         │ PurchaseOrderEvent
         │ ReceivingEvent
         │ PaymentEvent
         ▼
```

**年間仕訳件数の見積もり**:

| 発生源 | 仕訳種類 | 年間件数 |
|--------|----------|----------|
| 在庫管理 | 商品仕入、売上原価 | 636,000 |
| 受注管理 | 売上計上、入金処理 | 1,200,000 |
| 発注管理 | 仕入計上、支払処理 | 72,000 |
| 決算整理 | 減価償却、棚卸など | 120 |
| **合計** | | **約1,908,120件/年** |

月間平均: **約159,010件/月**
日次平均: **約5,300件/日**

## 1.2 卸売事業者D社の会計業務

### 1.2.1 事業規模

D社の会計年度における事業規模は以下の通りです（第3部〜第5部の実装内容に基づく）。

**損益情報**:

```scala
// D社の年間損益（2024年度）
final case class AnnualProfitAndLoss(
  fiscalYear: FiscalYear,
  revenue: Money,              // 売上高
  costOfGoodsSold: Money,      // 売上原価
  grossProfit: Money,          // 売上総利益（粗利）
  operatingExpenses: Money,    // 販売費及び一般管理費
  operatingIncome: Money,      // 営業利益
  ordinaryIncome: Money,       // 経常利益
  netIncome: Money             // 当期純利益
)

object DCompanyFinancials {
  val fiscalYear2024 = AnnualProfitAndLoss(
    fiscalYear = FiscalYear(2024),  // 2024年4月1日〜2025年3月31日
    revenue = Money(15_000_000_000),              // 売上高: 150億円
    costOfGoodsSold = Money(12_000_000_000),      // 売上原価: 120億円
    grossProfit = Money(3_000_000_000),           // 粗利: 30億円（粗利率20%）
    operatingExpenses = Money(2_400_000_000),     // 販管費: 24億円
    operatingIncome = Money(600_000_000),         // 営業利益: 6億円（営業利益率4%）
    ordinaryIncome = Money(550_000_000),          // 経常利益: 5.5億円
    netIncome = Money(350_000_000)                // 当期純利益: 3.5億円
  )
}
```

**貸借対照表（簡易版）**:

| 資産の部 | 金額（億円） | 負債・純資産の部 | 金額（億円） |
|----------|--------------|------------------|--------------|
| **流動資産** | **80** | **流動負債** | **60** |
| 現金及び預金 | 20 | 買掛金 | 40 |
| 売掛金 | 35 | 短期借入金 | 15 |
| 商品 | 25 | 未払金 | 5 |
| **固定資産** | **40** | **固定負債** | **20** |
| 建物 | 25 | 長期借入金 | 20 |
| 備品 | 10 | **純資産** | **40** |
| ソフトウェア | 5 | 資本金 | 10 |
|  |  | 利益剰余金 | 30 |
| **資産合計** | **120** | **負債・純資産合計** | **120** |

**主要な会計指標**:

```scala
final case class FinancialRatios(
  // 収益性指標
  grossProfitMargin: Double,      // 売上総利益率
  operatingProfitMargin: Double,  // 営業利益率
  netProfitMargin: Double,        // 当期純利益率
  roa: Double,                    // 総資産利益率（ROA）
  roe: Double,                    // 自己資本利益率（ROE）

  // 安全性指標
  currentRatio: Double,           // 流動比率
  equityRatio: Double,            // 自己資本比率
  debtToEquityRatio: Double,      // 負債比率

  // 効率性指標
  inventoryTurnover: Double,      // 棚卸資産回転率
  accountsReceivableTurnover: Double, // 売掛金回転率
  totalAssetTurnover: Double      // 総資産回転率
)

object DCompanyRatios {
  val fiscalYear2024 = FinancialRatios(
    // 収益性
    grossProfitMargin = 20.0,        // 粗利率: 30億 ÷ 150億 = 20%
    operatingProfitMargin = 4.0,     // 営業利益率: 6億 ÷ 150億 = 4%
    netProfitMargin = 2.3,           // 純利益率: 3.5億 ÷ 150億 = 2.3%
    roa = 2.9,                       // ROA: 3.5億 ÷ 120億 = 2.9%
    roe = 8.8,                       // ROE: 3.5億 ÷ 40億 = 8.8%

    // 安全性
    currentRatio = 133.3,            // 流動比率: 80億 ÷ 60億 = 133%
    equityRatio = 33.3,              // 自己資本比率: 40億 ÷ 120億 = 33%
    debtToEquityRatio = 200.0,       // 負債比率: 80億 ÷ 40億 = 200%

    // 効率性
    inventoryTurnover = 6.0,         // 棚卸回転率: 120億 ÷ 25億 ÷ 0.8 = 6回転
    accountsReceivableTurnover = 5.1, // 売掛金回転率: 150億 ÷ 35億 ÷ 0.85 = 5.1回転
    totalAssetTurnover = 1.25        // 総資産回転率: 150億 ÷ 120億 = 1.25回転
  )
}
```

### 1.2.2 会計処理フロー

D社の会計処理は、以下の8つのステップで構成されます。

#### ステップ1: 日次仕訳生成

ビジネスイベントから会計仕訳を自動生成します。

```scala
// イベントから仕訳への変換
trait JournalEntryGenerator {
  def generateFromOrderConfirmed(event: OrderEvent.OrderConfirmed): JournalEntry
  def generateFromPaymentReceived(event: PaymentEvent.PaymentReceived): JournalEntry
  def generateFromInventoryIncreased(event: InventoryEvent.InventoryIncreased): JournalEntry
  def generateFromReceivingCompleted(event: ReceivingEvent.InspectionCompleted): JournalEntry
}

// 売上計上の仕訳生成例
class SalesJournalEntryGenerator extends JournalEntryGenerator {

  override def generateFromOrderConfirmed(event: OrderEvent.OrderConfirmed): JournalEntry = {
    val taxExcludedAmount = event.totalAmount.excludingTax
    val taxAmount = event.totalAmount.taxAmount

    JournalEntry(
      entryId = JournalEntryId.generate(),
      transactionDate = event.orderDate,
      entryDate = LocalDate.now(),
      description = s"売上計上 注文番号: ${event.orderNumber.value}",
      lines = List(
        // 借方: 売掛金
        JournalEntryLine(
          lineNumber = 1,
          debitOrCredit = Debit,
          accountCode = AccountCode("1130"), // 売掛金
          amount = event.totalAmount.includingTax,
          description = s"顧客: ${event.customerId.value}"
        ),
        // 貸方: 売上高
        JournalEntryLine(
          lineNumber = 2,
          debitOrCredit = Credit,
          accountCode = AccountCode("4100"), // 売上高
          amount = taxExcludedAmount,
          description = "売上計上"
        ),
        // 貸方: 仮受消費税
        JournalEntryLine(
          lineNumber = 3,
          debitOrCredit = Credit,
          accountCode = AccountCode("2170"), // 仮受消費税
          amount = taxAmount,
          description = "消費税（10%）"
        )
      )
    )
  }
}
```

**日次仕訳生成の流れ**:

```
ビジネスイベント発生
    ↓
イベント購読（Pekko Persistence Query）
    ↓
仕訳変換ロジック適用
    ↓
JournalEntryActorに送信
    ↓
仕訳の永続化（DynamoDB）
    ↓
Read Model更新（PostgreSQL）
```

#### ステップ2: 仕訳承認

経理部門による仕訳内容の確認と承認を行います。

```scala
sealed trait JournalEntryStatus
case object Draft extends JournalEntryStatus      // 下書き（自動生成直後）
case object PendingApproval extends JournalEntryStatus  // 承認待ち
case object Approved extends JournalEntryStatus   // 承認済み
case object Rejected extends JournalEntryStatus   // 却下
case object Posted extends JournalEntryStatus     // 転記済み

// 仕訳承認コマンド
final case class ApproveJournalEntry(
  entryId: JournalEntryId,
  approver: UserId,
  approvedAt: Instant,
  comment: Option[String],
  replyTo: ActorRef[StatusReply[JournalEntryApproved]]
) extends JournalEntryCommand
```

**承認ルール**:
- 金額が100万円未満: 自動承認
- 金額が100万円以上500万円未満: 経理担当者による承認
- 金額が500万円以上: 経理部長による承認

#### ステップ3: 総勘定元帳への転記

承認済み仕訳を勘定科目別に総勘定元帳に転記します。

```scala
// 総勘定元帳への転記
class GeneralLedgerService {

  def postJournalEntry(entry: JournalEntry): Future[Unit] = {
    // 各仕訳明細を該当する勘定科目の元帳に転記
    Future.sequence(
      entry.lines.map { line =>
        generalLedgerActor ! PostToLedger(
          accountCode = line.accountCode,
          entryDate = entry.transactionDate,
          debitOrCredit = line.debitOrCredit,
          amount = line.amount,
          description = line.description,
          journalEntryId = entry.entryId
        )
      }
    ).map(_ => ())
  }
}
```

#### ステップ4: 試算表作成（月次）

月次で勘定科目別の残高を集計し、試算表を作成します。

```scala
final case class TrialBalance(
  fiscalYear: FiscalYear,
  month: Month,
  balances: List[AccountBalance]
)

final case class AccountBalance(
  accountCode: AccountCode,
  accountName: String,
  debitTotal: Money,     // 借方合計
  creditTotal: Money,    // 貸方合計
  balance: Money         // 残高
)

class TrialBalanceGenerator {

  def generate(year: FiscalYear, month: Month): Future[TrialBalance] = {
    for {
      // 全勘定科目を取得
      accounts <- chartOfAccountsService.getAllAccounts()

      // 各勘定科目の残高を取得
      balances <- Future.sequence(
        accounts.map(account =>
          generalLedgerService.getBalance(account.code, year, month)
        )
      )

      // 借方合計と貸方合計が一致することを検証
      _ = validateBalance(balances)

    } yield TrialBalance(year, month, balances)
  }

  private def validateBalance(balances: List[AccountBalance]): Unit = {
    val debitTotal = balances.map(_.debitTotal.amount).sum
    val creditTotal = balances.map(_.creditTotal.amount).sum

    require(
      debitTotal == creditTotal,
      s"試算表の貸借が一致しません: 借方=$debitTotal, 貸方=$creditTotal"
    )
  }
}
```

#### ステップ5: 月次決算（月末締め処理）

月末に以下の処理を実行します。

```scala
class MonthEndClosingService {

  def performMonthEndClosing(year: FiscalYear, month: Month): Future[ClosingResult] = {
    for {
      // 1. 試算表作成
      trialBalance <- trialBalanceGenerator.generate(year, month)

      // 2. 売掛金・買掛金の残高確認
      arBalance <- accountsReceivableService.getMonthEndBalance(year, month)
      apBalance <- accountsPayableService.getMonthEndBalance(year, month)

      // 3. 棚卸資産の確認
      inventoryValue <- inventoryValuationService.calculate(year, month)

      // 4. 決算整理仕訳（必要に応じて）
      adjustments <- generateMonthEndAdjustments(year, month)

      // 5. 月次損益計算
      pnl <- profitAndLossService.calculate(year, month)

      // 6. 締め処理
      _ <- closingService.closeMonth(year, month)

    } yield ClosingResult(
      year, month, trialBalance, arBalance, apBalance, inventoryValue, pnl
    )
  }
}
```

#### ステップ6: 財務諸表作成

損益計算書と貸借対照表を作成します。

```scala
// 損益計算書
final case class ProfitAndLossStatement(
  fiscalYear: FiscalYear,
  period: Period,
  revenue: Money,                  // 売上高
  costOfGoodsSold: Money,          // 売上原価
  grossProfit: Money,              // 売上総利益
  sellingExpenses: Money,          // 販売費
  generalExpenses: Money,          // 一般管理費
  operatingIncome: Money,          // 営業利益
  nonOperatingIncome: Money,       // 営業外収益
  nonOperatingExpenses: Money,     // 営業外費用
  ordinaryIncome: Money,           // 経常利益
  extraordinaryIncome: Money,      // 特別利益
  extraordinaryLoss: Money,        // 特別損失
  incomeBeforeTax: Money,          // 税引前当期純利益
  corporateTax: Money,             // 法人税等
  netIncome: Money                 // 当期純利益
)

// 貸借対照表
final case class BalanceSheet(
  fiscalYear: FiscalYear,
  asOfDate: LocalDate,
  // 資産の部
  currentAssets: AssetSection,     // 流動資産
  fixedAssets: AssetSection,       // 固定資産
  totalAssets: Money,              // 資産合計
  // 負債の部
  currentLiabilities: LiabilitySection,  // 流動負債
  fixedLiabilities: LiabilitySection,    // 固定負債
  totalLiabilities: Money,         // 負債合計
  // 純資産の部
  equity: EquitySection,           // 純資産
  totalEquity: Money,              // 純資産合計
  totalLiabilitiesAndEquity: Money // 負債・純資産合計
)
```

#### ステップ7: 年次決算

会計年度末（3月31日）に年次決算処理を実行します。

```scala
class YearEndClosingService {

  def performYearEndClosing(fiscalYear: FiscalYear): Future[YearEndClosingResult] = {
    for {
      // 1. 減価償却の計算と仕訳
      depreciation <- depreciationService.calculateAnnualDepreciation(fiscalYear)
      _ <- journalEntryService.recordDepreciation(depreciation)

      // 2. 棚卸資産の評価
      inventoryValuation <- inventoryValuationService.performYearEndValuation(fiscalYear)
      _ <- journalEntryService.recordInventoryValuation(inventoryValuation)

      // 3. 繰延税金資産・負債の計算
      deferredTax <- deferredTaxService.calculate(fiscalYear)
      _ <- journalEntryService.recordDeferredTax(deferredTax)

      // 4. 引当金の計上
      provisions <- provisionService.calculateYearEndProvisions(fiscalYear)
      _ <- journalEntryService.recordProvisions(provisions)

      // 5. 損益勘定への振替（損益の確定）
      _ <- transferToProfitAndLoss(fiscalYear)

      // 6. 次年度繰越処理
      _ <- carryForwardToNextYear(fiscalYear)

      // 7. 財務諸表の確定
      financialStatements <- finalizeFinancialStatements(fiscalYear)

    } yield YearEndClosingResult(fiscalYear, financialStatements)
  }

  // 損益勘定への振替
  private def transferToProfitAndLoss(fiscalYear: FiscalYear): Future[Unit] = {
    for {
      // 全ての収益・費用を損益勘定に振替
      revenues <- getAllRevenueAccounts(fiscalYear)
      expenses <- getAllExpenseAccounts(fiscalYear)

      // 収益を損益勘定に振替（借方: 各収益勘定、貸方: 損益）
      _ <- Future.sequence(revenues.map(closeRevenueAccount))

      // 費用を損益勘定に振替（借方: 損益、貸方: 各費用勘定）
      _ <- Future.sequence(expenses.map(closeExpenseAccount))

      // 損益を繰越利益剰余金に振替
      netIncome <- calculateNetIncome(fiscalYear)
      _ <- transferNetIncomeToRetainedEarnings(netIncome)

    } yield ()
  }
}
```

#### ステップ8: 税務申告

税務申告のためのデータを作成します。

```scala
// 消費税申告データ
final case class ConsumptionTaxReturn(
  fiscalYear: FiscalYear,
  period: Period,
  taxableSales: Money,             // 課税売上高
  salesTaxReceived: Money,         // 仮受消費税
  taxablePurchases: Money,         // 課税仕入高
  purchaseTaxPaid: Money,          // 仮払消費税
  taxPayable: Money                // 納付税額
)

// 法人税申告データ
final case class CorporateTaxReturn(
  fiscalYear: FiscalYear,
  accountingIncome: Money,         // 会計上の利益
  taxAdjustments: List[TaxAdjustment], // 税務調整
  taxableIncome: Money,            // 課税所得
  corporateTaxRate: Double,        // 法人税率
  corporateTax: Money,             // 法人税額
  localCorporateTax: Money,        // 地方法人税
  enterpriseTax: Money,            // 事業税
  residentTax: Money,              // 住民税
  totalTax: Money                  // 税額合計
)

class TaxReturnService {

  // 消費税申告データ作成
  def generateConsumptionTaxReturn(
    fiscalYear: FiscalYear,
    period: Period
  ): Future[ConsumptionTaxReturn] = {
    for {
      // 仮受消費税の集計
      salesTaxReceived <- generalLedgerService.getAccountTotal(
        AccountCode("2170"), // 仮受消費税
        fiscalYear,
        period
      )

      // 仮払消費税の集計
      purchaseTaxPaid <- generalLedgerService.getAccountTotal(
        AccountCode("1330"), // 仮払消費税
        fiscalYear,
        period
      )

      // 納付税額 = 仮受消費税 - 仮払消費税
      taxPayable = Money(salesTaxReceived.amount - purchaseTaxPaid.amount)

    } yield ConsumptionTaxReturn(
      fiscalYear = fiscalYear,
      period = period,
      taxableSales = Money(salesTaxReceived.amount / 0.10), // 税抜売上
      salesTaxReceived = salesTaxReceived,
      taxablePurchases = Money(purchaseTaxPaid.amount / 0.10), // 税抜仕入
      purchaseTaxPaid = purchaseTaxPaid,
      taxPayable = taxPayable
    )
  }
}
```

### 1.2.3 会計期間

D社の会計期間は以下の通りです。

```scala
// 会計年度
final case class FiscalYear(year: Int) {
  // 開始日: 4月1日
  def startDate: LocalDate = LocalDate.of(year, 4, 1)

  // 終了日: 翌年3月31日
  def endDate: LocalDate = LocalDate.of(year + 1, 3, 31)

  // 期間
  def period: Period = Period.between(startDate, endDate)
}

// 四半期
sealed trait Quarter
case object Q1 extends Quarter  // 4-6月
case object Q2 extends Quarter  // 7-9月
case object Q3 extends Quarter  // 10-12月
case object Q4 extends Quarter  // 1-3月

object Quarter {
  def fromMonth(month: Int): Quarter = month match {
    case m if m >= 4 && m <= 6 => Q1
    case m if m >= 7 && m <= 9 => Q2
    case m if m >= 10 && m <= 12 => Q3
    case m if m >= 1 && m <= 3 => Q4
    case _ => throw new IllegalArgumentException(s"Invalid month: $month")
  }

  def period(fiscalYear: FiscalYear, quarter: Quarter): Period = {
    val (startMonth, endMonth) = quarter match {
      case Q1 => (4, 6)
      case Q2 => (7, 9)
      case Q3 => (10, 12)
      case Q4 => (1, 3)
    }

    val year = if (quarter == Q4) fiscalYear.year + 1 else fiscalYear.year
    val start = LocalDate.of(
      if (startMonth >= 4) fiscalYear.year else fiscalYear.year + 1,
      startMonth,
      1
    )
    val end = start.plusMonths(3).minusDays(1)

    Period.between(start, end)
  }
}
```

**決算スケジュール**:

| 期間 | 締め日 | 処理内容 |
|------|--------|----------|
| 月次 | 毎月末日 | 試算表作成、月次損益計算 |
| Q1 | 6月30日 | 四半期決算、財務諸表作成 |
| Q2 | 9月30日 | 四半期決算、財務諸表作成 |
| Q3 | 12月31日 | 四半期決算、財務諸表作成 |
| Q4（年次） | 3月31日 | 年次決算、税務申告 |

## 1.3 技術的課題

### 1.3.1 イベント駆動会計

ビジネスイベントから会計仕訳を自動生成するアーキテクチャを実装します。

**課題1: イベントから仕訳への変換**

各Bounded Contextで発生したビジネスイベントを会計仕訳に変換する必要があります。

```scala
// イベント駆動仕訳生成の全体フロー
object EventDrivenAccounting {

  // ビジネスイベントから仕訳生成
  trait EventToJournalEntryMapper[E] {
    def toJournalEntry(event: E): List[JournalEntry]
  }

  // 在庫管理イベント
  implicit val inventoryEventMapper: EventToJournalEntryMapper[InventoryEvent] = {
    case event: InventoryEvent.InventoryIncreased =>
      // 借方: 商品、貸方: 買掛金
      List(createPurchaseJournalEntry(event))

    case event: InventoryEvent.InventoryDecreased =>
      // 借方: 売上原価、貸方: 商品
      List(createCostOfGoodsSoldEntry(event))

    case _ => List.empty
  }

  // 受注管理イベント
  implicit val orderEventMapper: EventToJournalEntryMapper[OrderEvent] = {
    case event: OrderEvent.OrderConfirmed =>
      // 借方: 売掛金、貸方: 売上高 + 仮受消費税
      List(createSalesJournalEntry(event))

    case event: OrderEvent.PaymentReceived =>
      // 借方: 現金、貸方: 売掛金
      List(createPaymentReceivedEntry(event))

    case _ => List.empty
  }

  // 発注管理イベント
  implicit val purchaseOrderEventMapper: EventToJournalEntryMapper[PurchaseOrderEvent] = {
    case event: PurchaseOrderEvent.ReceivingCompleted =>
      // 借方: 商品 + 仮払消費税、貸方: 買掛金
      List(createPurchaseEntry(event))

    case event: PurchaseOrderEvent.PaymentCompleted =>
      // 借方: 買掛金、貸方: 現金
      List(createPaymentMadeEntry(event))

    case _ => List.empty
  }
}
```

**課題2: イベントソーシングによる監査証跡**

全ての仕訳をイベントとして記録し、監査証跡を確保します。

```scala
// 仕訳イベント
sealed trait JournalEntryEvent

final case class JournalEntryCreated(
  entryId: JournalEntryId,
  transactionDate: LocalDate,
  entryDate: LocalDate,
  description: String,
  lines: List[JournalEntryLine>,
  sourceEventId: Option[EventId],  // 元となったビジネスイベントのID
  createdBy: UserId,
  createdAt: Instant
) extends JournalEntryEvent

final case class JournalEntryApproved(
  entryId: JournalEntryId,
  approver: UserId,
  approvedAt: Instant,
  comment: Option[String]
) extends JournalEntryEvent

final case class JournalEntryPosted(
  entryId: JournalEntryId,
  postedBy: UserId,
  postedAt: Instant
) extends JournalEntryEvent

final case class JournalEntryReversed(
  entryId: JournalEntryId,
  reversalEntryId: JournalEntryId,
  reason: String,
  reversedBy: UserId,
  reversedAt: Instant
) extends JournalEntryEvent
```

**課題3: 複式簿記の整合性保証**

仕訳の借方合計と貸方合計が必ず一致することを保証します。

```scala
final case class JournalEntry(
  entryId: JournalEntryId,
  transactionDate: LocalDate,
  entryDate: LocalDate,
  description: String,
  lines: List[JournalEntryLine]
) {
  require(isBalanced, "仕訳の貸借が一致していません")

  // 借方合計
  def debitTotal: Money = Money(
    lines.filter(_.debitOrCredit == Debit).map(_.amount.amount).sum
  )

  // 貸方合計
  def creditTotal: Money = Money(
    lines.filter(_.debitOrCredit == Credit).map(_.amount.amount).sum
  )

  // 貸借一致チェック
  def isBalanced: Boolean = debitTotal.amount == creditTotal.amount

  // 検証
  def validate(): Either[ValidationError, JournalEntry] = {
    for {
      _ <- validateBalance()
      _ <- validateLines()
      _ <- validateAccounts()
    } yield this
  }

  private def validateBalance(): Either[ValidationError, Unit] = {
    if (isBalanced) Right(())
    else Left(ValidationError(
      s"仕訳の貸借が一致しません: 借方=${debitTotal.amount}, 貸方=${creditTotal.amount}"
    ))
  }

  private def validateLines(): Either[ValidationError, Unit] = {
    if (lines.size >= 2) Right(())
    else Left(ValidationError("仕訳明細は2行以上必要です"))
  }

  private def validateAccounts(): Either[ValidationError, Unit] = {
    // 勘定科目の存在チェックなど
    Right(())
  }
}
```

### 1.3.2 期末決算処理

年度末の決算処理を自動化します。

**課題1: 減価償却計算**

固定資産の減価償却を定率法または定額法で計算します。

```scala
// 減価償却方法
sealed trait DepreciationMethod
case object StraightLine extends DepreciationMethod  // 定額法
case object DecliningBalance extends DepreciationMethod  // 定率法

// 固定資産
final case class FixedAsset(
  assetId: FixedAssetId,
  assetName: String,
  acquisitionCost: Money,        // 取得原価
  acquisitionDate: LocalDate,    // 取得日
  usefulLife: Int,               // 耐用年数（年）
  residualValue: Money,          // 残存価額
  depreciationMethod: DepreciationMethod,
  accumulatedDepreciation: Money // 減価償却累計額
)

class DepreciationCalculator {

  // 定額法: (取得原価 - 残存価額) ÷ 耐用年数
  def calculateStraightLine(asset: FixedAsset): Money = {
    val depreciableAmount = Money(asset.acquisitionCost.amount - asset.residualValue.amount)
    Money(depreciableAmount.amount / asset.usefulLife)
  }

  // 定率法: (取得原価 - 減価償却累計額) × 償却率
  def calculateDecliningBalance(asset: FixedAsset, rate: Double): Money = {
    val bookValue = Money(asset.acquisitionCost.amount - asset.accumulatedDepreciation.amount)
    Money(bookValue.amount * rate)
  }

  // 年間減価償却費の計算
  def calculateAnnualDepreciation(asset: FixedAsset, fiscalYear: FiscalYear): Money = {
    asset.depreciationMethod match {
      case StraightLine =>
        calculateStraightLine(asset)

      case DecliningBalance =>
        // 定率法の償却率（耐用年数に応じて設定）
        val rate = getDepreciationRate(asset.usefulLife)
        calculateDecliningBalance(asset, rate)
    }
  }

  private def getDepreciationRate(usefulLife: Int): Double = usefulLife match {
    case 2 => 1.000
    case 3 => 0.667
    case 4 => 0.500
    case 5 => 0.400
    case 10 => 0.200
    case 15 => 0.133
    case 20 => 0.100
    case _ => 1.0 / usefulLife
  }
}
```

**課題2: 棚卸資産の評価**

期末在庫の評価を行います（移動平均法、先入先出法など）。

```scala
// 棚卸評価方法
sealed trait InventoryValuationMethod
case object MovingAverage extends InventoryValuationMethod  // 移動平均法
case object FIFO extends InventoryValuationMethod           // 先入先出法
case object LastPurchasePrice extends InventoryValuationMethod  // 最終仕入原価法

class InventoryValuationService {

  // 期末棚卸資産の評価
  def performYearEndValuation(
    fiscalYear: FiscalYear,
    method: InventoryValuationMethod = MovingAverage
  ): Future[InventoryValuation] = {
    for {
      // 全商品の在庫数量を取得
      inventoryLevels <- inventoryQueryService.getAllInventoryLevels(fiscalYear.endDate)

      // 商品ごとの評価額を計算
      valuations <- Future.sequence(
        inventoryLevels.map(level =>
          calculateProductValuation(level, method, fiscalYear)
        )
      )

      totalValuation = Money(valuations.map(_.amount.amount).sum)

    } yield InventoryValuation(fiscalYear, method, valuations, totalValuation)
  }

  private def calculateProductValuation(
    level: InventoryLevel,
    method: InventoryValuationMethod,
    fiscalYear: FiscalYear
  ): Future[ProductValuation] = method match {
    case MovingAverage =>
      calculateMovingAverageValuation(level, fiscalYear)

    case FIFO =>
      calculateFIFOValuation(level, fiscalYear)

    case LastPurchasePrice =>
      calculateLastPurchasePriceValuation(level, fiscalYear)
  }
}
```

**課題3: 繰延税金の計算**

一時差異に基づく繰延税金資産・負債を計算します。

```scala
final case class DeferredTaxCalculation(
  fiscalYear: FiscalYear,
  temporaryDifferences: List[TemporaryDifference],
  taxRate: Double,
  deferredTaxAsset: Money,
  deferredTaxLiability: Money,
  netDeferredTax: Money
)

final case class TemporaryDifference(
  description: String,
  amount: Money,
  differenceType: DifferenceType
)

sealed trait DifferenceType
case object Deductible extends DifferenceType    // 将来減算一時差異
case object Taxable extends DifferenceType       // 将来加算一時差異

class DeferredTaxService {

  def calculate(fiscalYear: FiscalYear): Future[DeferredTaxCalculation] = {
    for {
      // 一時差異の識別
      temporaryDifferences <- identifyTemporaryDifferences(fiscalYear)

      // 税率（実効税率）
      taxRate = 0.30  // 30%

      // 繰延税金資産（将来減算一時差異 × 税率）
      deferredTaxAsset = Money(
        temporaryDifferences
          .filter(_.differenceType == Deductible)
          .map(_.amount.amount)
          .sum * taxRate
      )

      // 繰延税金負債（将来加算一時差異 × 税率）
      deferredTaxLiability = Money(
        temporaryDifferences
          .filter(_.differenceType == Taxable)
          .map(_.amount.amount)
          .sum * taxRate
      )

      netDeferredTax = Money(deferredTaxAsset.amount - deferredTaxLiability.amount)

    } yield DeferredTaxCalculation(
      fiscalYear,
      temporaryDifferences,
      taxRate,
      deferredTaxAsset,
      deferredTaxLiability,
      netDeferredTax
    )
  }
}
```

### 1.3.3 財務諸表の作成

リアルタイムで財務諸表を作成できる仕組みを構築します。

**課題1: リアルタイム損益計算**

```scala
class ProfitAndLossCalculator {

  def calculate(
    fiscalYear: FiscalYear,
    asOfDate: LocalDate = LocalDate.now()
  ): Future[ProfitAndLossStatement] = {
    for {
      // 売上高の集計
      revenue <- generalLedgerService.getAccountTotal(
        AccountCode("4100"),  // 売上高
        fiscalYear,
        Period.between(fiscalYear.startDate, asOfDate)
      )

      // 売上原価の集計
      costOfGoodsSold <- generalLedgerService.getAccountTotal(
        AccountCode("5100"),  // 売上原価
        fiscalYear,
        Period.between(fiscalYear.startDate, asOfDate)
      )

      // 売上総利益
      grossProfit = Money(revenue.amount - costOfGoodsSold.amount)

      // 販売費及び一般管理費の集計
      sellingExpenses <- getSellingExpenses(fiscalYear, asOfDate)
      generalExpenses <- getGeneralExpenses(fiscalYear, asOfDate)

      // 営業利益
      operatingIncome = Money(
        grossProfit.amount - sellingExpenses.amount - generalExpenses.amount
      )

      // 営業外損益
      nonOperatingIncome <- getNonOperatingIncome(fiscalYear, asOfDate)
      nonOperatingExpenses <- getNonOperatingExpenses(fiscalYear, asOfDate)

      // 経常利益
      ordinaryIncome = Money(
        operatingIncome.amount + nonOperatingIncome.amount - nonOperatingExpenses.amount
      )

      // 特別損益
      extraordinaryIncome <- getExtraordinaryIncome(fiscalYear, asOfDate)
      extraordinaryLoss <- getExtraordinaryLoss(fiscalYear, asOfDate)

      // 税引前当期純利益
      incomeBeforeTax = Money(
        ordinaryIncome.amount + extraordinaryIncome.amount - extraordinaryLoss.amount
      )

      // 法人税等
      corporateTax <- getCorporateTax(fiscalYear, asOfDate)

      // 当期純利益
      netIncome = Money(incomeBeforeTax.amount - corporateTax.amount)

    } yield ProfitAndLossStatement(
      fiscalYear = fiscalYear,
      period = Period.between(fiscalYear.startDate, asOfDate),
      revenue = revenue,
      costOfGoodsSold = costOfGoodsSold,
      grossProfit = grossProfit,
      sellingExpenses = sellingExpenses,
      generalExpenses = generalExpenses,
      operatingIncome = operatingIncome,
      nonOperatingIncome = nonOperatingIncome,
      nonOperatingExpenses = nonOperatingExpenses,
      ordinaryIncome = ordinaryIncome,
      extraordinaryIncome = extraordinaryIncome,
      extraordinaryLoss = extraordinaryLoss,
      incomeBeforeTax = incomeBeforeTax,
      corporateTax = corporateTax,
      netIncome = netIncome
    )
  }
}
```

**課題2: 貸借対照表の整合性**

資産・負債・純資産の整合性を保証します。

```scala
class BalanceSheetCalculator {

  def calculate(
    fiscalYear: FiscalYear,
    asOfDate: LocalDate
  ): Future[BalanceSheet] = {
    for {
      // 資産の部
      currentAssets <- calculateCurrentAssets(fiscalYear, asOfDate)
      fixedAssets <- calculateFixedAssets(fiscalYear, asOfDate)
      totalAssets = Money(currentAssets.total.amount + fixedAssets.total.amount)

      // 負債の部
      currentLiabilities <- calculateCurrentLiabilities(fiscalYear, asOfDate)
      fixedLiabilities <- calculateFixedLiabilities(fiscalYear, asOfDate)
      totalLiabilities = Money(
        currentLiabilities.total.amount + fixedLiabilities.total.amount
      )

      // 純資産の部
      equity <- calculateEquity(fiscalYear, asOfDate)

      // 貸借一致チェック
      _ = validateBalance(totalAssets, totalLiabilities, equity.total)

    } yield BalanceSheet(
      fiscalYear = fiscalYear,
      asOfDate = asOfDate,
      currentAssets = currentAssets,
      fixedAssets = fixedAssets,
      totalAssets = totalAssets,
      currentLiabilities = currentLiabilities,
      fixedLiabilities = fixedLiabilities,
      totalLiabilities = totalLiabilities,
      equity = equity,
      totalEquity = equity.total,
      totalLiabilitiesAndEquity = Money(totalLiabilities.amount + equity.total.amount)
    )
  }

  private def validateBalance(
    totalAssets: Money,
    totalLiabilities: Money,
    totalEquity: Money
  ): Unit = {
    val liabilitiesAndEquity = Money(totalLiabilities.amount + totalEquity.amount)

    require(
      totalAssets.amount == liabilitiesAndEquity.amount,
      s"貸借対照表が一致しません: 資産=${totalAssets.amount}, " +
      s"負債・純資産=${liabilitiesAndEquity.amount}"
    )
  }
}
```

**課題3: キャッシュフロー計算書（間接法）**

```scala
final case class CashFlowStatement(
  fiscalYear: FiscalYear,
  period: Period,
  operatingActivities: OperatingCashFlow,
  investingActivities: InvestingCashFlow,
  financingActivities: FinancingCashFlow,
  netCashIncrease: Money,
  cashAtBeginning: Money,
  cashAtEnd: Money
)

class CashFlowCalculator {

  // 間接法によるキャッシュフロー計算
  def calculateIndirectMethod(
    fiscalYear: FiscalYear,
    period: Period
  ): Future[CashFlowStatement] = {
    for {
      // 営業活動によるキャッシュフロー
      operatingCF <- calculateOperatingCashFlow(fiscalYear, period)

      // 投資活動によるキャッシュフロー
      investingCF <- calculateInvestingCashFlow(fiscalYear, period)

      // 財務活動によるキャッシュフロー
      financingCF <- calculateFinancingCashFlow(fiscalYear, period)

      // 現金及び現金同等物の増減
      netIncrease = Money(
        operatingCF.netCash.amount +
        investingCF.netCash.amount +
        financingCF.netCash.amount
      )

      // 期首現金残高
      cashAtBeginning <- getCashBalance(fiscalYear.startDate)

      // 期末現金残高
      cashAtEnd = Money(cashAtBeginning.amount + netIncrease.amount)

    } yield CashFlowStatement(
      fiscalYear,
      period,
      operatingCF,
      investingCF,
      financingCF,
      netIncrease,
      cashAtBeginning,
      cashAtEnd
    )
  }

  // 営業活動によるキャッシュフロー（間接法）
  private def calculateOperatingCashFlow(
    fiscalYear: FiscalYear,
    period: Period
  ): Future[OperatingCashFlow] = {
    for {
      // 税引前当期純利益
      incomeBeforeTax <- getIncomeBeforeTax(fiscalYear, period)

      // 非資金項目の調整
      depreciation <- getDepreciation(fiscalYear, period)  // 減価償却費（加算）

      // 運転資本の増減
      arIncrease <- getAccountsReceivableIncrease(fiscalYear, period)  // 売掛金増加（減算）
      inventoryIncrease <- getInventoryIncrease(fiscalYear, period)   // 棚卸資産増加（減算）
      apIncrease <- getAccountsPayableIncrease(fiscalYear, period)    // 買掛金増加（加算）

      // 小計
      subtotal = Money(
        incomeBeforeTax.amount +
        depreciation.amount -
        arIncrease.amount -
        inventoryIncrease.amount +
        apIncrease.amount
      )

      // 法人税等の支払
      taxPaid <- getTaxPaid(fiscalYear, period)

      // 営業活動によるキャッシュフロー
      netCash = Money(subtotal.amount - taxPaid.amount)

    } yield OperatingCashFlow(
      incomeBeforeTax,
      depreciation,
      arIncrease,
      inventoryIncrease,
      apIncrease,
      subtotal,
      taxPaid,
      netCash
    )
  }
}
```

### 1.3.4 パフォーマンス要件

会計処理のパフォーマンス要件を満たすための最適化を行います。

**パフォーマンス目標**:

| 処理 | 目標時間 | 備考 |
|------|----------|------|
| 仕訳生成 | 100ms以内 | イベント発生から仕訳永続化まで |
| 試算表作成 | 5秒以内 | 全勘定科目（500科目）の集計 |
| 財務諸表作成 | 10秒以内 | 損益計算書・貸借対照表 |
| 月次決算処理 | 30分以内 | 試算表、財務諸表、チェック |

**最適化戦略**:

```scala
// 1. Materialized Viewの活用
class AccountSummaryView {
  // 勘定科目別の月次サマリをMaterialized Viewで事前計算
  def updateMonthlySummary(
    accountCode: AccountCode,
    fiscalYear: FiscalYear,
    month: Month
  ): Future[Unit]

  def getMonthlySummary(
    accountCode: AccountCode,
    fiscalYear: FiscalYear,
    month: Month
  ): Future[AccountMonthlySummary]
}

// 2. キャッシング戦略
class FinancialStatementCache {
  // 財務諸表のキャッシング（日次更新）
  def getOrCalculate(
    fiscalYear: FiscalYear,
    asOfDate: LocalDate
  ): Future[FinancialStatements]

  // キャッシュ無効化（仕訳追加時）
  def invalidate(fiscalYear: FiscalYear, date: LocalDate): Future[Unit]
}

// 3. バッチ処理
class MonthEndBatchProcessor {
  // 月末締め処理をバッチ実行
  def processMonthEnd(fiscalYear: FiscalYear, month: Month): Future[BatchResult]
}
```

## まとめ

本章では、第6部で構築する会計サービスの全体像と要件を定義しました。

**学んだこと**:
- 在庫・受注・発注システムで発生したトランザクションを会計仕訳に変換
- D社の事業規模と会計処理フロー（年商150億円、月間15万件の仕訳）
- 8つのステップからなる会計処理フロー（日次仕訳→承認→転記→試算表→月次決算→財務諸表→年次決算→税務申告）
- イベント駆動会計の技術的課題（複式簿記の整合性、決算処理、財務諸表作成）

**次章以降の内容**:
- 第2章: Read Modelスキーマの設計（勘定科目、仕訳、元帳）
- 第3章: 勘定科目体系の設計（ChartOfAccounts集約）
- 第4章: 仕訳生成の実装（JournalEntry集約、イベント駆動）
- 第5章: 総勘定元帳の実装（GeneralLedger集約）
- 第6章: 財務諸表の作成（FinancialStatement集約）
- 第7章: 債権債務管理（AccountsReceivable、AccountsPayable集約）
- 第8章: 月次決算処理（Sagaパターン）
- 第9章: 年次決算処理（減価償却、棚卸、税金）
- 第10章: パフォーマンス最適化（Materialized View、キャッシング）
- 第11章: 運用とモニタリング（会計メトリクス、監査ログ）
- 第12章: まとめと実践演習

次章では、会計システムのRead Modelスキーマを設計し、PostgreSQLのテーブル構造を定義します。
