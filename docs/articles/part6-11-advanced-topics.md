# 第6部11章：高度なトピック

## 本章の目的

本章では、会計システムの高度なトピックを学びます。企業が成長し、海外展開や子会社の設立を行うと、複数通貨での取引管理、連結財務諸表の作成が必要になります。また、経営管理のために部門別やプロジェクト別の損益管理も重要になります。

### 学習内容

1. 複数通貨会計（外貨建取引の処理）
2. 連結会計（グループ全体の財務諸表作成）
3. 管理会計（部門別損益、プロジェクト別損益）

## 11.1 複数通貨会計

### 複数通貨会計の概要

企業が海外取引を行う場合、外貨建ての取引（売上、仕入、借入など）が発生します。これらの取引を円貨に換算して会計処理する必要があります。

#### 為替換算の基本原則

| 換算レート | 用途 | 説明 |
|---|---|---|
| TTM（仲値） | 取引発生時 | 売りと買いの中間レート |
| TTS（売りレート） | 外貨の支払い | 銀行が外貨を売るレート |
| TTB（買いレート） | 外貨の受取り | 銀行が外貨を買うレート |
| 期末レート | 期末評価替え | 決算日のTTM |

#### D社の海外取引例

D社は米国と取引があり、月間500万ドル（約7億円）の輸出売上があります。

### 外貨建取引のドメインモデル

#### Currency（通貨）

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.domain.model

/**
 * 通貨
 */
sealed trait Currency {
  def code: String
  def symbol: String
  def decimalPlaces: Int
}

object Currency {
  case object JPY extends Currency {
    val code = "JPY"
    val symbol = "¥"
    val decimalPlaces = 0
  }

  case object USD extends Currency {
    val code = "USD"
    val symbol = "$"
    val decimalPlaces = 2
  }

  case object EUR extends Currency {
    val code = "EUR"
    val symbol = "€"
    val decimalPlaces = 2
  }

  case object GBP extends Currency {
    val code = "GBP"
    val symbol = "£"
    val decimalPlaces = 2
  }

  case object CNY extends Currency {
    val code = "CNY"
    val symbol = "¥"
    val decimalPlaces = 2
  }

  def fromCode(code: String): Option[Currency] = code match {
    case "JPY" => Some(JPY)
    case "USD" => Some(USD)
    case "EUR" => Some(EUR)
    case "GBP" => Some(GBP)
    case "CNY" => Some(CNY)
    case _ => None
  }
}

/**
 * 外貨金額
 */
final case class ForeignCurrencyAmount(
  amount: BigDecimal,
  currency: Currency
) {

  /**
   * 為替レートで円貨に換算
   */
  def convertToJPY(exchangeRate: ExchangeRate): Money = {
    require(exchangeRate.fromCurrency == currency, "通貨が一致しません")
    require(exchangeRate.toCurrency == Currency.JPY, "換算先は円貨である必要があります")

    Money((amount * exchangeRate.rate).setScale(0, BigDecimal.RoundingMode.HALF_UP).toLong)
  }

  override def toString: String = {
    f"${currency.symbol}${amount}%.${currency.decimalPlaces}f"
  }
}
```

#### ExchangeRate（為替レート）

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.domain.model

import java.time.{Instant, LocalDate}

/**
 * 為替レート
 */
final case class ExchangeRate(
  fromCurrency: Currency,
  toCurrency: Currency,
  rate: BigDecimal,
  rateType: ExchangeRateType,
  effectiveDate: LocalDate,
  createdAt: Instant
) {

  /**
   * 為替レートを適用して換算
   */
  def convert(amount: ForeignCurrencyAmount): Money = {
    require(amount.currency == fromCurrency, "通貨が一致しません")
    amount.convertToJPY(this)
  }
}

/**
 * 為替レートタイプ
 */
sealed trait ExchangeRateType
object ExchangeRateType {
  case object TTM extends ExchangeRateType  // 仲値（取引発生時）
  case object TTS extends ExchangeRateType  // 売りレート（外貨支払い時）
  case object TTB extends ExchangeRateType  // 買いレート（外貨受取り時）
  case object YearEnd extends ExchangeRateType  // 期末レート（評価替え）
}

/**
 * 為替レート取得サービス
 */
trait ExchangeRateService {

  /**
   * 指定日の為替レートを取得
   */
  def getExchangeRate(
    fromCurrency: Currency,
    toCurrency: Currency,
    date: LocalDate,
    rateType: ExchangeRateType
  ): Future[Either[String, ExchangeRate]]

  /**
   * 最新の為替レートを取得
   */
  def getLatestExchangeRate(
    fromCurrency: Currency,
    toCurrency: Currency,
    rateType: ExchangeRateType
  ): Future[Either[String, ExchangeRate]]
}
```

### 外貨建取引の仕訳生成

#### 外貨建売上の仕訳生成

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.usecase

import java.time.LocalDate
import scala.concurrent.{ExecutionContext, Future}

/**
 * 外貨建売上仕訳生成サービス
 */
class ForeignCurrencySalesJournalEntryGenerator(
  chartOfAccountsService: ChartOfAccountsService,
  exchangeRateService: ExchangeRateService,
  entryNumberGenerator: () => EntryNumber
)(implicit ec: ExecutionContext) {

  /**
   * 外貨建売上仕訳を生成
   *
   * 例: $50,000の売上（1ドル=140円で換算）
   * 借方: 外貨建売掛金 7,000,000円
   * 貸方: 売上高 7,000,000円
   */
  def generateForeignCurrencySalesEntry(
    foreignAmount: ForeignCurrencyAmount,
    customerId: String,
    orderDate: LocalDate,
    fiscalYear: FiscalYear,
    fiscalPeriod: FiscalPeriod
  ): Future[Either[ValidationError, JournalEntry]] = {

    for {
      // 取引発生時のTTM（仲値）レートを取得
      rateResult <- exchangeRateService.getExchangeRate(
        fromCurrency = foreignAmount.currency,
        toCurrency = Currency.JPY,
        date = orderDate,
        rateType = ExchangeRateType.TTM
      )

      result = rateResult.flatMap { exchangeRate =>
        // 外貨を円貨に換算
        val jpyAmount = foreignAmount.convertToJPY(exchangeRate)

        // 勘定科目を取得
        for {
          foreignAccountsReceivable <- chartOfAccountsService.findByCode(AccountCode("1135"))  // 外貨建売掛金
          salesRevenue <- chartOfAccountsService.findByCode(AccountCode("4120"))  // 売上高
        } yield {
          val lines = List(
            // 借方: 外貨建売掛金
            JournalEntryLine(
              lineNumber = LineNumber(1),
              accountCode = AccountCode("1135"),
              accountName = foreignAccountsReceivable.accountName,
              debitCredit = DebitCredit.Debit,
              amount = jpyAmount,
              foreignCurrencyAmount = Some(foreignAmount),
              exchangeRate = Some(exchangeRate.rate),
              auxiliaryAccount = Some(AuxiliaryAccount(customerId)),
              description = Some(s"外貨建売上 ${foreignAmount} (レート: ${exchangeRate.rate})")
            ),
            // 貸方: 売上高
            JournalEntryLine(
              lineNumber = LineNumber(2),
              accountCode = AccountCode("4120"),
              accountName = salesRevenue.accountName,
              debitCredit = DebitCredit.Credit,
              amount = jpyAmount,
              description = Some(s"外貨建売上 ${foreignAmount} (レート: ${exchangeRate.rate})")
            )
          )

          JournalEntry(
            id = JournalEntryId.generate(),
            entryNumber = entryNumberGenerator(),
            entryDate = orderDate,
            fiscalYear = fiscalYear,
            fiscalPeriod = fiscalPeriod,
            voucherType = VoucherType.Sales,
            lines = lines,
            description = Some(s"外貨建売上 顧客: ${customerId}"),
            status = JournalEntryStatus.Draft,
            createdAt = java.time.Instant.now(),
            updatedAt = java.time.Instant.now()
          )
        }
      }

    } yield result
  }
}
```

### 期末評価替えと為替差損益の計上

#### 期末評価替え処理

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.usecase

/**
 * 期末外貨建資産・負債の評価替えサービス
 */
class ForeignCurrencyRevaluationService(
  foreignAccountsReceivableRepository: ForeignAccountsReceivableRepository,
  exchangeRateService: ExchangeRateService,
  journalEntryGenerator: () => EntryNumber
)(implicit ec: ExecutionContext) {

  /**
   * 期末評価替えを実行
   *
   * 外貨建売掛金・買掛金を期末レートで評価替えし、
   * 為替差損益を計上する
   */
  def executeYearEndRevaluation(
    fiscalYear: FiscalYear,
    yearEndDate: LocalDate
  ): Future[List[JournalEntry]] = {

    for {
      // 期末時点の外貨建売掛金を取得
      foreignReceivables <- foreignAccountsReceivableRepository.findByYearEnd(fiscalYear, yearEndDate)

      // 各通貨の期末レートを取得
      usdYearEndRate <- exchangeRateService.getExchangeRate(
        Currency.USD,
        Currency.JPY,
        yearEndDate,
        ExchangeRateType.YearEnd
      )

      // 評価替え仕訳を生成
      entries = foreignReceivables.flatMap { receivable =>
        generateRevaluationEntry(receivable, usdYearEndRate.getOrElse(???), fiscalYear, yearEndDate)
      }

    } yield entries
  }

  /**
   * 評価替え仕訳を生成
   *
   * 例: $50,000の売掛金
   * 取引時レート: 1ドル=140円 → 帳簿価額7,000,000円
   * 期末レート:   1ドル=145円 → 期末評価額7,250,000円
   * 為替差益: 250,000円
   *
   * 借方: 外貨建売掛金 250,000円
   * 貸方: 為替差益 250,000円
   */
  private def generateRevaluationEntry(
    receivable: ForeignAccountsReceivable,
    yearEndRate: ExchangeRate,
    fiscalYear: FiscalYear,
    yearEndDate: LocalDate
  ): Option[JournalEntry] = {

    // 帳簿価額（取引時レートでの換算額）
    val bookValue = receivable.jpyAmount

    // 期末評価額（期末レートでの換算額）
    val yearEndValue = receivable.foreignAmount.convertToJPY(yearEndRate)

    // 為替差損益を計算
    val exchangeDifference = Money(yearEndValue.amount - bookValue.amount)

    // 為替差損益がゼロの場合は仕訳不要
    if (exchangeDifference.amount == 0) {
      None
    } else {
      // 為替差益の場合
      val isGain = exchangeDifference.amount > 0

      val lines = if (isGain) {
        List(
          // 借方: 外貨建売掛金
          JournalEntryLine(
            lineNumber = LineNumber(1),
            accountCode = AccountCode("1135"),
            accountName = AccountName("外貨建売掛金"),
            debitCredit = DebitCredit.Debit,
            amount = Money(exchangeDifference.amount.abs),
            description = Some(s"為替差益 ${receivable.foreignAmount}")
          ),
          // 貸方: 為替差益
          JournalEntryLine(
            lineNumber = LineNumber(2),
            accountCode = AccountCode("4310"),  // 為替差益（営業外収益）
            accountName = AccountName("為替差益"),
            debitCredit = DebitCredit.Credit,
            amount = Money(exchangeDifference.amount.abs),
            description = Some(s"期末評価替え ${receivable.foreignAmount}")
          )
        )
      } else {
        // 為替差損の場合
        List(
          // 借方: 為替差損
          JournalEntryLine(
            lineNumber = LineNumber(1),
            accountCode = AccountCode("5410"),  // 為替差損（営業外費用）
            accountName = AccountName("為替差損"),
            debitCredit = DebitCredit.Debit,
            amount = Money(exchangeDifference.amount.abs),
            description = Some(s"期末評価替え ${receivable.foreignAmount}")
          ),
          // 貸方: 外貨建売掛金
          JournalEntryLine(
            lineNumber = LineNumber(2),
            accountCode = AccountCode("1135"),
            accountName = AccountName("外貨建売掛金"),
            debitCredit = DebitCredit.Credit,
            amount = Money(exchangeDifference.amount.abs),
            description = Some(s"為替差損 ${receivable.foreignAmount}")
          )
        )
      }

      Some(JournalEntry(
        id = JournalEntryId.generate(),
        entryNumber = journalEntryGenerator(),
        entryDate = yearEndDate,
        fiscalYear = fiscalYear,
        fiscalPeriod = FiscalPeriod(fiscalYear.value, 12),  // 第12期（3月）
        voucherType = VoucherType.ClosingAdjustment,
        lines = lines,
        description = Some(s"期末外貨建資産評価替え"),
        status = JournalEntryStatus.Draft,
        createdAt = java.time.Instant.now(),
        updatedAt = java.time.Instant.now()
      ))
    }
  }
}

/**
 * 外貨建売掛金
 */
final case class ForeignAccountsReceivable(
  id: String,
  customerId: String,
  foreignAmount: ForeignCurrencyAmount,
  originalExchangeRate: BigDecimal,
  jpyAmount: Money,
  transactionDate: LocalDate
)
```

## 11.2 連結会計

### 連結会計の概要

連結会計は、親会社と子会社をグループ全体として捉え、連結財務諸表を作成する会計手法です。D社は2つの子会社（D社物流、D社IT）を持っています。

#### D社グループの構成

```
D社（親会社）
├── D社物流（子会社、持株比率100%）
└── D社IT（子会社、持株比率80%）
```

#### 連結財務諸表の作成手順

1. 個別財務諸表の合算
2. 内部取引の相殺消去
3. 非支配株主持分の計算（D社ITの20%分）
4. 連結財務諸表の作成

### 連結財務諸表のドメインモデル

#### Subsidiary（子会社）

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.domain.model

/**
 * 子会社
 */
final case class Subsidiary(
  id: SubsidiaryId,
  name: String,
  ownershipPercentage: BigDecimal,  // 持株比率（%）
  businessSegment: BusinessSegment,
  establishedDate: java.time.LocalDate,
  fiscalYearEnd: java.time.MonthDay  // 決算日（月日）
) {

  /**
   * 完全子会社か
   */
  def isWhollyOwned: Boolean = ownershipPercentage >= 100.0

  /**
   * 非支配株主持分比率
   */
  def nonControllingInterestPercentage: BigDecimal = {
    100.0 - ownershipPercentage
  }
}

final case class SubsidiaryId(value: String) extends AnyVal

/**
 * 事業セグメント
 */
sealed trait BusinessSegment
object BusinessSegment {
  case object Trading extends BusinessSegment      // 商社事業
  case object Logistics extends BusinessSegment    // 物流事業
  case object IT extends BusinessSegment           // IT事業
}
```

#### ConsolidatedFinancialStatements（連結財務諸表）

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.domain.model

/**
 * 連結財務諸表
 */
final case class ConsolidatedFinancialStatements(
  fiscalYear: FiscalYear,
  targetMonth: java.time.YearMonth,

  // 連結損益計算書
  consolidatedIncomeStatement: ConsolidatedIncomeStatement,

  // 連結貸借対照表
  consolidatedBalanceSheet: ConsolidatedBalanceSheet,

  // セグメント情報
  segmentInformation: List[SegmentInformation],

  calculatedAt: java.time.Instant
)

/**
 * 連結損益計算書
 */
final case class ConsolidatedIncomeStatement(
  // 親会社
  parentCompanyRevenue: Money,
  parentCompanyNetIncome: Money,

  // 子会社合計
  subsidiariesRevenue: Money,
  subsidiariesNetIncome: Money,

  // 内部取引消去
  internalTransactionElimination: Money,

  // 連結合計
  consolidatedRevenue: Money,
  consolidatedNetIncome: Money,

  // 非支配株主に帰属する当期純利益
  nonControllingInterestNetIncome: Money,

  // 親会社株主に帰属する当期純利益
  netIncomeAttributableToParent: Money
) {

  /**
   * 非支配株主持分控除前当期純利益
   */
  def netIncomeBeforeNonControllingInterest: Money = {
    Money(netIncomeAttributableToParent.amount + nonControllingInterestNetIncome.amount)
  }
}

/**
 * 連結貸借対照表
 */
final case class ConsolidatedBalanceSheet(
  // 資産
  consolidatedTotalAssets: Money,

  // 負債
  consolidatedTotalLiabilities: Money,

  // 純資産
  consolidatedTotalEquity: Money,

  // 親会社株主持分
  equityAttributableToParent: Money,

  // 非支配株主持分
  nonControllingInterest: Money
) {

  /**
   * 貸借が一致しているか
   */
  def isBalanced: Boolean = {
    consolidatedTotalAssets.amount ==
      (consolidatedTotalLiabilities.amount + consolidatedTotalEquity.amount)
  }
}

/**
 * セグメント情報
 */
final case class SegmentInformation(
  segment: BusinessSegment,
  revenue: Money,
  operatingIncome: Money,
  assets: Money
)
```

### 連結財務諸表の作成処理

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.usecase

/**
 * 連結財務諸表作成サービス
 */
class ConsolidatedFinancialStatementsService(
  parentCompanyStatementService: FinancialStatementQueryService,
  subsidiaryStatementService: SubsidiaryFinancialStatementQueryService,
  internalTransactionService: InternalTransactionService
)(implicit ec: ExecutionContext) {

  /**
   * 連結財務諸表を作成
   */
  def createConsolidatedStatements(
    fiscalYear: FiscalYear,
    targetMonth: java.time.YearMonth
  ): Future[ConsolidatedFinancialStatements] = {

    for {
      // 1. 親会社の個別財務諸表を取得
      parentStatements <- parentCompanyStatementService.getPreCalculatedStatements(fiscalYear, targetMonth)

      // 2. 子会社の個別財務諸表を取得
      subsidiaryStatements <- subsidiaryStatementService.getAllSubsidiaryStatements(fiscalYear, targetMonth)

      // 3. 内部取引を取得
      internalTransactions <- internalTransactionService.getInternalTransactions(fiscalYear, targetMonth)

      // 4. 連結処理を実行
      consolidated = performConsolidation(
        parentStatements.get,
        subsidiaryStatements,
        internalTransactions,
        fiscalYear,
        targetMonth
      )

    } yield consolidated
  }

  /**
   * 連結処理を実行
   */
  private def performConsolidation(
    parentStatements: FinancialStatementSet,
    subsidiaryStatements: List[SubsidiaryFinancialStatementSet],
    internalTransactions: List[InternalTransaction],
    fiscalYear: FiscalYear,
    targetMonth: java.time.YearMonth
  ): ConsolidatedFinancialStatements = {

    // Step 1: 個別財務諸表の合算
    val totalRevenue = parentStatements.incomeStatement.revenue.amount +
      subsidiaryStatements.map(_.incomeStatement.revenue.amount).sum

    val totalNetIncome = parentStatements.incomeStatement.netIncome.amount +
      subsidiaryStatements.map(_.incomeStatement.netIncome.amount).sum

    // Step 2: 内部取引の相殺消去
    // 例: 親会社がD社物流に物流費5,000万円を支払っている
    // 親会社側: 販管費 5,000万円（費用）
    // D社物流側: 売上高 5,000万円（収益）
    // → 連結上は相殺して消去
    val internalTransactionTotal = internalTransactions.map(_.amount.amount).sum

    val consolidatedRevenue = Money(totalRevenue - internalTransactionTotal)
    val consolidatedNetIncome = Money(totalNetIncome)  // 損益は相殺されるので影響なし

    // Step 3: 非支配株主持分の計算
    // D社ITは持株比率80%なので、20%は非支配株主持分
    val dCompanyIT = subsidiaryStatements.find(_.subsidiary.name == "D社IT")
    val nonControllingInterestNetIncome = dCompanyIT.map { statements =>
      val netIncome = statements.incomeStatement.netIncome.amount
      val nonControllingPercentage = statements.subsidiary.nonControllingInterestPercentage
      Money((netIncome * nonControllingPercentage / 100).toLong)
    }.getOrElse(Money(0))

    val netIncomeAttributableToParent = Money(
      consolidatedNetIncome.amount - nonControllingInterestNetIncome.amount
    )

    // 連結損益計算書を作成
    val consolidatedIncomeStatement = ConsolidatedIncomeStatement(
      parentCompanyRevenue = parentStatements.incomeStatement.revenue,
      parentCompanyNetIncome = parentStatements.incomeStatement.netIncome,
      subsidiariesRevenue = Money(subsidiaryStatements.map(_.incomeStatement.revenue.amount).sum),
      subsidiariesNetIncome = Money(subsidiaryStatements.map(_.incomeStatement.netIncome.amount).sum),
      internalTransactionElimination = Money(internalTransactionTotal),
      consolidatedRevenue = consolidatedRevenue,
      consolidatedNetIncome = consolidatedNetIncome,
      nonControllingInterestNetIncome = nonControllingInterestNetIncome,
      netIncomeAttributableToParent = netIncomeAttributableToParent
    )

    // セグメント情報を作成
    val segmentInformation = createSegmentInformation(parentStatements, subsidiaryStatements)

    ConsolidatedFinancialStatements(
      fiscalYear = fiscalYear,
      targetMonth = targetMonth,
      consolidatedIncomeStatement = consolidatedIncomeStatement,
      consolidatedBalanceSheet = createConsolidatedBalanceSheet(parentStatements, subsidiaryStatements),
      segmentInformation = segmentInformation,
      calculatedAt = java.time.Instant.now()
    )
  }

  private def createConsolidatedBalanceSheet(
    parentStatements: FinancialStatementSet,
    subsidiaryStatements: List[SubsidiaryFinancialStatementSet]
  ): ConsolidatedBalanceSheet = {
    // 実装は省略（貸借対照表の連結処理）
    ???
  }

  private def createSegmentInformation(
    parentStatements: FinancialStatementSet,
    subsidiaryStatements: List[SubsidiaryFinancialStatementSet]
  ): List[SegmentInformation] = {

    // 親会社: 商社事業
    val tradingSegment = SegmentInformation(
      segment = BusinessSegment.Trading,
      revenue = parentStatements.incomeStatement.revenue,
      operatingIncome = parentStatements.incomeStatement.operatingIncome,
      assets = parentStatements.balanceSheet.totalAssets
    )

    // 子会社セグメント
    val subsidiarySegments = subsidiaryStatements.map { statements =>
      SegmentInformation(
        segment = statements.subsidiary.businessSegment,
        revenue = statements.incomeStatement.revenue,
        operatingIncome = statements.incomeStatement.operatingIncome,
        assets = statements.balanceSheet.totalAssets
      )
    }

    tradingSegment :: subsidiarySegments
  }
}

/**
 * 内部取引
 */
final case class InternalTransaction(
  id: String,
  fromCompany: String,  // 取引元（親会社 or 子会社）
  toCompany: String,    // 取引先（親会社 or 子会社）
  amount: Money,
  description: String,
  transactionDate: java.time.LocalDate
)

/**
 * 子会社財務諸表セット
 */
final case class SubsidiaryFinancialStatementSet(
  subsidiary: Subsidiary,
  incomeStatement: IncomeStatement,
  balanceSheet: BalanceSheet
)
```

### D社グループの連結財務諸表例

```scala
// D社グループの連結財務諸表（2024年7月）

val dCompanyGroup = ConsolidatedFinancialStatements(
  fiscalYear = FiscalYear(2024),
  targetMonth = java.time.YearMonth.of(2024, 7),

  consolidatedIncomeStatement = ConsolidatedIncomeStatement(
    // 親会社（D社）
    parentCompanyRevenue = Money(1250000000),        // 売上高12.5億円
    parentCompanyNetIncome = Money(31500000),        // 当期純利益3,150万円

    // 子会社合計
    subsidiariesRevenue = Money(400000000),          // 売上高4億円
    subsidiariesNetIncome = Money(20000000),         // 当期純利益2,000万円

    // 内部取引消去
    internalTransactionElimination = Money(50000000),  // 5,000万円（親→D社物流の物流費）

    // 連結合計
    consolidatedRevenue = Money(1600000000),         // 連結売上高16億円（12.5億 + 4億 - 0.5億）
    consolidatedNetIncome = Money(51500000),         // 連結当期純利益5,150万円

    // 非支配株主に帰属する当期純利益（D社ITの20%分）
    nonControllingInterestNetIncome = Money(1000000),  // 100万円（500万円 × 20%）

    // 親会社株主に帰属する当期純利益
    netIncomeAttributableToParent = Money(50500000)  // 5,050万円
  ),

  consolidatedBalanceSheet = ConsolidatedBalanceSheet(
    consolidatedTotalAssets = Money(15000000000),     // 連結総資産150億円
    consolidatedTotalLiabilities = Money(9000000000), // 連結負債90億円
    consolidatedTotalEquity = Money(6000000000),      // 連結純資産60億円
    equityAttributableToParent = Money(5800000000),   // 親会社株主持分58億円
    nonControllingInterest = Money(200000000)         // 非支配株主持分2億円
  ),

  segmentInformation = List(
    SegmentInformation(
      segment = BusinessSegment.Trading,
      revenue = Money(1250000000),
      operatingIncome = Money(50000000),
      assets = Money(12000000000)
    ),
    SegmentInformation(
      segment = BusinessSegment.Logistics,
      revenue = Money(300000000),
      operatingIncome = Money(15000000),
      assets = Money(2000000000)
    ),
    SegmentInformation(
      segment = BusinessSegment.IT,
      revenue = Money(100000000),
      operatingIncome = Money(5000000),
      assets = Money(1000000000)
    )
  ),

  calculatedAt = java.time.Instant.now()
)
```

## 11.3 管理会計

### 管理会計の概要

管理会計は、経営者が意思決定を行うための会計情報を提供します。財務会計（制度会計）とは異なり、法令による制約がなく、企業が自由に設計できます。

### 部門別損益管理

#### Department（部門）

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.domain.model

/**
 * 部門
 */
final case class Department(
  id: DepartmentId,
  code: String,
  name: String,
  departmentType: DepartmentType,
  parentDepartmentId: Option[DepartmentId],
  manager: String,
  costCenter: Boolean  // コストセンター（収益を生まない部門）
)

final case class DepartmentId(value: String) extends AnyVal

/**
 * 部門タイプ
 */
sealed trait DepartmentType
object DepartmentType {
  case object Sales extends DepartmentType           // 営業部門
  case object Purchasing extends DepartmentType      // 購買部門
  case object Logistics extends DepartmentType       // 物流部門
  case object Administration extends DepartmentType  // 管理部門
  case object IT extends DepartmentType              // IT部門
}

/**
 * 部門別損益計算書
 */
final case class DepartmentProfitAndLoss(
  department: Department,
  fiscalYear: FiscalYear,
  targetMonth: java.time.YearMonth,

  // 収益
  revenue: Money,

  // 費用
  directCosts: Money,           // 直接費（その部門だけの費用）
  allocatedCosts: Money,        // 配賦費（共通費の配賦分）
  totalCosts: Money,

  // 利益
  departmentProfit: Money,      // 部門利益

  calculatedAt: java.time.Instant
) {

  /**
   * 部門利益率
   */
  def profitMargin: Double = {
    if (revenue.amount == 0) 0.0
    else (departmentProfit.amount.toDouble / revenue.amount.toDouble) * 100.0
  }
}
```

#### 部門別仕訳の記録

```scala
// 仕訳明細に部門コードを追加

final case class JournalEntryLine(
  lineNumber: LineNumber,
  accountCode: AccountCode,
  accountName: AccountName,
  debitCredit: DebitCredit,
  amount: Money,
  taxType: Option[TaxType],
  auxiliaryAccount: Option[AuxiliaryAccount],
  departmentId: Option[DepartmentId],  // 部門コード
  projectId: Option[ProjectId],        // プロジェクトコード
  description: Option[String]
)

// 営業部門の売上仕訳例
val salesDepartmentEntry = JournalEntry(
  lines = List(
    JournalEntryLine(
      accountCode = AccountCode("1130"),
      debitCredit = DebitCredit.Debit,
      amount = Money(1000000),
      departmentId = Some(DepartmentId("DEPT-SALES-01"))  // 営業1部
    ),
    JournalEntryLine(
      accountCode = AccountCode("4120"),
      debitCredit = DebitCredit.Credit,
      amount = Money(1000000),
      departmentId = Some(DepartmentId("DEPT-SALES-01"))
    )
  )
)
```

#### 部門別損益の集計

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.usecase

/**
 * 部門別損益集計サービス
 */
class DepartmentProfitAndLossService(
  generalLedgerRepository: GeneralLedgerRepository,
  departmentRepository: DepartmentRepository,
  costAllocationService: CostAllocationService
)(implicit ec: ExecutionContext) {

  /**
   * 部門別損益を集計
   */
  def calculateDepartmentProfitAndLoss(
    departmentId: DepartmentId,
    fiscalYear: FiscalYear,
    targetMonth: java.time.YearMonth
  ): Future[DepartmentProfitAndLoss] = {

    for {
      // 部門情報を取得
      department <- departmentRepository.findById(departmentId)

      // 部門別の収益を集計（売上高）
      revenue <- generalLedgerRepository.sumByDepartmentAndAccount(
        departmentId,
        AccountType.Revenue,
        fiscalYear,
        targetMonth
      )

      // 部門別の直接費を集計
      directCosts <- generalLedgerRepository.sumByDepartmentAndAccount(
        departmentId,
        AccountType.Expense,
        fiscalYear,
        targetMonth
      )

      // 共通費の配賦額を計算
      allocatedCosts <- costAllocationService.allocateCosts(
        departmentId,
        fiscalYear,
        targetMonth
      )

      totalCosts = Money(directCosts.amount + allocatedCosts.amount)
      departmentProfit = Money(revenue.amount - totalCosts.amount)

    } yield DepartmentProfitAndLoss(
      department = department,
      fiscalYear = fiscalYear,
      targetMonth = targetMonth,
      revenue = revenue,
      directCosts = directCosts,
      allocatedCosts = allocatedCosts,
      totalCosts = totalCosts,
      departmentProfit = departmentProfit,
      calculatedAt = java.time.Instant.now()
    )
  }

  /**
   * 全部門の損益を集計
   */
  def calculateAllDepartmentsProfitAndLoss(
    fiscalYear: FiscalYear,
    targetMonth: java.time.YearMonth
  ): Future[List[DepartmentProfitAndLoss]] = {

    for {
      departments <- departmentRepository.findAll()
      profitAndLossList <- Future.sequence(
        departments.map { dept =>
          calculateDepartmentProfitAndLoss(dept.id, fiscalYear, targetMonth)
        }
      )
    } yield profitAndLossList
  }
}

/**
 * コスト配賦サービス
 */
class CostAllocationService(
  generalLedgerRepository: GeneralLedgerRepository
)(implicit ec: ExecutionContext) {

  /**
   * 共通費を各部門に配賦
   *
   * 配賦基準:
   * - 本社費: 各部門の売上高比率で配賦
   * - IT費用: 各部門の人数比率で配賦
   */
  def allocateCosts(
    departmentId: DepartmentId,
    fiscalYear: FiscalYear,
    targetMonth: java.time.YearMonth
  ): Future[Money] = {

    // 実装は省略
    // 共通費の配賦ロジック
    Future.successful(Money(5000000))  // 例: 500万円
  }
}
```

### プロジェクト別損益管理

#### Project（プロジェクト）

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.domain.model

/**
 * プロジェクト
 */
final case class Project(
  id: ProjectId,
  code: String,
  name: String,
  customerId: String,
  projectManager: String,
  startDate: java.time.LocalDate,
  endDate: Option[java.time.LocalDate],
  budgetAmount: Money,
  status: ProjectStatus
)

final case class ProjectId(value: String) extends AnyVal

sealed trait ProjectStatus
object ProjectStatus {
  case object Planning extends ProjectStatus    // 計画中
  case object InProgress extends ProjectStatus  // 進行中
  case object Completed extends ProjectStatus   // 完了
  case object Cancelled extends ProjectStatus   // 中止
}

/**
 * プロジェクト別損益計算書
 */
final case class ProjectProfitAndLoss(
  project: Project,
  fiscalYear: FiscalYear,

  // 収益
  revenue: Money,

  // 費用
  laborCosts: Money,        // 人件費
  materialCosts: Money,     // 材料費
  outsourcingCosts: Money,  // 外注費
  otherCosts: Money,        // その他費用
  totalCosts: Money,

  // 利益
  projectProfit: Money,
  profitMargin: Double,

  // 予算対比
  budgetAmount: Money,
  budgetVariance: Money,
  budgetAchievementRate: Double,

  calculatedAt: java.time.Instant
)
```

### D社の部門別損益例

```scala
// D社の部門別損益（2024年7月）

val departmentProfitAndLossList = List(
  // 営業1部
  DepartmentProfitAndLoss(
    department = Department(
      id = DepartmentId("DEPT-SALES-01"),
      code = "SALES-01",
      name = "営業1部",
      departmentType = DepartmentType.Sales,
      parentDepartmentId = None,
      manager = "山田太郎",
      costCenter = false
    ),
    fiscalYear = FiscalYear(2024),
    targetMonth = java.time.YearMonth.of(2024, 7),
    revenue = Money(700000000),       // 売上高7億円
    directCosts = Money(600000000),   // 直接費6億円
    allocatedCosts = Money(20000000), // 配賦費2,000万円
    totalCosts = Money(620000000),
    departmentProfit = Money(80000000),  // 部門利益8,000万円
    calculatedAt = java.time.Instant.now()
  ),
  // 利益率: 11.4%

  // 営業2部
  DepartmentProfitAndLoss(
    department = Department(
      id = DepartmentId("DEPT-SALES-02"),
      code = "SALES-02",
      name = "営業2部",
      departmentType = DepartmentType.Sales,
      parentDepartmentId = None,
      manager = "佐藤花子",
      costCenter = false
    ),
    fiscalYear = FiscalYear(2024),
    targetMonth = java.time.YearMonth.of(2024, 7),
    revenue = Money(550000000),       // 売上高5.5億円
    directCosts = Money(450000000),   // 直接費4.5億円
    allocatedCosts = Money(15000000), // 配賦費1,500万円
    totalCosts = Money(465000000),
    departmentProfit = Money(85000000),  // 部門利益8,500万円
    calculatedAt = java.time.Instant.now()
  ),
  // 利益率: 15.5%

  // 管理部門（コストセンター）
  DepartmentProfitAndLoss(
    department = Department(
      id = DepartmentId("DEPT-ADMIN"),
      code = "ADMIN",
      name = "管理部",
      departmentType = DepartmentType.Administration,
      parentDepartmentId = None,
      manager = "鈴木一郎",
      costCenter = true  // コストセンター
    ),
    fiscalYear = FiscalYear(2024),
    targetMonth = java.time.YearMonth.of(2024, 7),
    revenue = Money(0),               // 収益なし
    directCosts = Money(50000000),    // 直接費5,000万円
    allocatedCosts = Money(0),        // 配賦費なし
    totalCosts = Money(50000000),
    departmentProfit = Money(-50000000),  // 部門損失-5,000万円
    calculatedAt = java.time.Instant.now()
  )
  // 管理部門は収益を生まないため、全社の費用として扱う
)
```

## まとめ

本章では、会計システムの高度なトピックを学びました。

### 実装した内容

1. **複数通貨会計**
   - Currency、ForeignCurrencyAmount、ExchangeRateの実装
   - 外貨建売上仕訳の自動生成
   - 期末評価替えと為替差損益の計上
   - TTM、TTS、TTBレートの使い分け

2. **連結会計**
   - Subsidiary、ConsolidatedFinancialStatementsの実装
   - 個別財務諸表の合算
   - 内部取引の相殺消去
   - 非支配株主持分の計算
   - セグメント情報の作成

3. **管理会計**
   - 部門別損益管理（Department、DepartmentProfitAndLoss）
   - 共通費の配賦ロジック
   - プロジェクト別損益管理（Project、ProjectProfitAndLoss）
   - 予算対比分析

### D社への適用効果

- **複数通貨会計**: 海外取引の自動処理、為替リスクの可視化
- **連結会計**: グループ全体の財務状況を把握、セグメント別分析
- **管理会計**: 部門別・プロジェクト別の収益性分析、意思決定の高度化

次章では、まとめと実践演習を行います。
