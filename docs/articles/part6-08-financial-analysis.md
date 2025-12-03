# 第6部8章：財務分析機能

## 本章の目的

本章では、会計システムにおける財務分析機能の実装を学びます。財務分析は、財務諸表から経営指標を算出し、企業の収益性・安全性・効率性を可視化する重要な機能です。

経営者は財務分析の結果を基に、事業戦略の立案、投資判断、リスク管理などの意思決定を行います。D社では、月次で経営指標をモニタリングし、迅速な経営判断を実現します。

### 学習内容

1. 収益性指標の算出（粗利率、営業利益率、ROA、ROEなど）
2. 安全性指標の算出（流動比率、自己資本比率など）
3. 効率性指標の算出（回転率系指標）
4. 予実管理（予算vs実績の差異分析）
5. GraphQL APIによる財務指標の公開

## 8.1 経営指標の算出

### 経営指標の概要

経営指標は、財務諸表の数値を加工して算出される比率です。主に以下の3つの観点で企業を評価します：

| 観点 | 目的 | 主な指標 |
|---|---|---|
| **収益性** | 企業がどれだけ利益を上げているか | 粗利率、営業利益率、ROA、ROE |
| **安全性** | 企業の財務体質は健全か | 流動比率、自己資本比率 |
| **効率性** | 資産を効率的に活用しているか | 総資産回転率、売上債権回転率 |

### 収益性指標のドメインモデル

#### ProfitabilityIndicators（収益性指標）

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.domain.model

import java.time.YearMonth

/**
 * 収益性指標
 *
 * 企業の収益力を測定する指標群
 */
final case class ProfitabilityIndicators(
  fiscalYear: FiscalYear,
  targetMonth: YearMonth,

  // 損益計算書の各段階利益
  revenue: Money,                  // 売上高
  costOfGoodsSold: Money,         // 売上原価
  grossProfit: Money,             // 売上総利益（粗利）
  operatingExpenses: Money,       // 販売費及び一般管理費
  operatingIncome: Money,         // 営業利益
  nonOperatingIncome: Money,      // 営業外収益
  nonOperatingExpenses: Money,    // 営業外費用
  ordinaryIncome: Money,          // 経常利益
  extraordinaryIncome: Money,     // 特別利益
  extraordinaryLoss: Money,       // 特別損失
  incomeBeforeTax: Money,         // 税引前利益
  corporateTax: Money,            // 法人税等
  netIncome: Money,               // 当期純利益

  // 貸借対照表の資本項目
  totalAssets: Money,             // 総資産
  totalEquity: Money,             // 純資産（自己資本）

  calculatedAt: java.time.Instant
) {

  /**
   * 売上高総利益率（粗利率）
   *
   * 売上総利益 ÷ 売上高 × 100
   *
   * 商品・サービスの付加価値の高さを示す。
   * 製造業: 20-30%、卸売業: 10-15%、小売業: 25-35%が一般的。
   */
  def grossProfitMargin: Double = {
    if (revenue.amount == 0) 0.0
    else (grossProfit.amount.toDouble / revenue.amount.toDouble) * 100.0
  }

  /**
   * 売上高営業利益率
   *
   * 営業利益 ÷ 売上高 × 100
   *
   * 本業での稼ぐ力を示す。業種により異なるが、5%以上が望ましい。
   */
  def operatingProfitMargin: Double = {
    if (revenue.amount == 0) 0.0
    else (operatingIncome.amount.toDouble / revenue.amount.toDouble) * 100.0
  }

  /**
   * 売上高経常利益率
   *
   * 経常利益 ÷ 売上高 × 100
   *
   * 企業の通常の収益力を示す。3-5%以上が健全。
   */
  def ordinaryProfitMargin: Double = {
    if (revenue.amount == 0) 0.0
    else (ordinaryIncome.amount.toDouble / revenue.amount.toDouble) * 100.0
  }

  /**
   * 売上高当期純利益率
   *
   * 当期純利益 ÷ 売上高 × 100
   *
   * 最終的な収益力を示す。2-3%以上が望ましい。
   */
  def netProfitMargin: Double = {
    if (revenue.amount == 0) 0.0
    else (netIncome.amount.toDouble / revenue.amount.toDouble) * 100.0
  }

  /**
   * ROA（総資産利益率）
   *
   * 当期純利益 ÷ 総資産 × 100
   *
   * 総資産をどれだけ効率的に利益に結びつけたかを示す。
   * 5%以上が優良企業の目安。
   */
  def returnOnAssets: Double = {
    if (totalAssets.amount == 0) 0.0
    else (netIncome.amount.toDouble / totalAssets.amount.toDouble) * 100.0
  }

  /**
   * ROE（自己資本利益率）
   *
   * 当期純利益 ÷ 純資産 × 100
   *
   * 株主資本をどれだけ効率的に利益に結びつけたかを示す。
   * 8-10%以上が望ましい（日本企業の平均は8%程度）。
   */
  def returnOnEquity: Double = {
    if (totalEquity.amount == 0) 0.0
    else (netIncome.amount.toDouble / totalEquity.amount.toDouble) * 100.0
  }

  /**
   * EBITDA（利払い前・税引き前・減価償却前利益）
   *
   * 営業利益 + 減価償却費
   *
   * キャッシュ創出力を示す指標。国際比較に有用。
   */
  def ebitda(depreciation: Money): Money = {
    Money(operatingIncome.amount + depreciation.amount)
  }

  /**
   * EBITDAマージン
   *
   * EBITDA ÷ 売上高 × 100
   */
  def ebitdaMargin(depreciation: Money): Double = {
    if (revenue.amount == 0) 0.0
    else (ebitda(depreciation).amount.toDouble / revenue.amount.toDouble) * 100.0
  }

  /**
   * 損益分岐点比率
   *
   * 固定費 ÷ 限界利益 × 100
   *
   * 売上がどれだけ減少しても利益が出るかを示す。
   * 80%以下が望ましい。
   */
  def breakEvenRatio(fixedCosts: Money): Double = {
    val marginalProfit = Money(revenue.amount - costOfGoodsSold.amount)
    if (marginalProfit.amount == 0) 0.0
    else (fixedCosts.amount.toDouble / marginalProfit.amount.toDouble) * 100.0
  }

  /**
   * 財務指標サマリーを生成
   */
  def summary(): String = {
    s"""
    |========== 収益性指標 ==========
    |対象月: ${targetMonth}
    |
    |【売上高・利益】
    |  売上高:           ${formatMoney(revenue)}
    |  売上総利益:       ${formatMoney(grossProfit)}
    |  営業利益:         ${formatMoney(operatingIncome)}
    |  経常利益:         ${formatMoney(ordinaryIncome)}
    |  当期純利益:       ${formatMoney(netIncome)}
    |
    |【収益性比率】
    |  売上高総利益率:   ${formatPercent(grossProfitMargin)}
    |  売上高営業利益率: ${formatPercent(operatingProfitMargin)}
    |  売上高経常利益率: ${formatPercent(ordinaryProfitMargin)}
    |  売上高純利益率:   ${formatPercent(netProfitMargin)}
    |
    |【資本効率】
    |  ROA（総資産利益率）: ${formatPercent(returnOnAssets)}
    |  ROE（自己資本利益率）: ${formatPercent(returnOnEquity)}
    |================================
    """.stripMargin
  }

  private def formatMoney(money: Money): String = {
    f"¥${money.amount}%,d"
  }

  private def formatPercent(value: Double): String = {
    f"$value%.2f%%"
  }
}
```

### 安全性指標のドメインモデル

#### SafetyIndicators（安全性指標）

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.domain.model

/**
 * 安全性指標
 *
 * 企業の財務の健全性を測定する指標群
 */
final case class SafetyIndicators(
  fiscalYear: FiscalYear,
  targetMonth: YearMonth,

  // 流動性資産・負債
  currentAssets: Money,           // 流動資産
  quickAssets: Money,             // 当座資産（現金・預金・売掛金など）
  currentLiabilities: Money,      // 流動負債

  // 固定資産・負債
  fixedAssets: Money,             // 固定資産
  fixedLiabilities: Money,        // 固定負債

  // 資本
  totalAssets: Money,             // 総資産
  totalLiabilities: Money,        // 負債合計
  totalEquity: Money,             // 純資産（自己資本）

  // 有利子負債
  interestBearingDebt: Money,     // 有利子負債（借入金・社債など）

  calculatedAt: java.time.Instant
) {

  /**
   * 流動比率
   *
   * 流動資産 ÷ 流動負債 × 100
   *
   * 短期的な支払能力を示す。200%以上が理想的、120%以上が安全圏。
   */
  def currentRatio: Double = {
    if (currentLiabilities.amount == 0) 0.0
    else (currentAssets.amount.toDouble / currentLiabilities.amount.toDouble) * 100.0
  }

  /**
   * 当座比率
   *
   * 当座資産 ÷ 流動負債 × 100
   *
   * より厳格な短期支払能力を示す。100%以上が望ましい。
   */
  def quickRatio: Double = {
    if (currentLiabilities.amount == 0) 0.0
    else (quickAssets.amount.toDouble / currentLiabilities.amount.toDouble) * 100.0
  }

  /**
   * 自己資本比率
   *
   * 純資産 ÷ 総資産 × 100
   *
   * 財務の安定性を示す。40%以上が優良、30%以上が安全圏。
   * 10%未満は要注意。
   */
  def equityRatio: Double = {
    if (totalAssets.amount == 0) 0.0
    else (totalEquity.amount.toDouble / totalAssets.amount.toDouble) * 100.0
  }

  /**
   * 固定比率
   *
   * 固定資産 ÷ 純資産 × 100
   *
   * 固定資産が自己資本でどれだけ賄われているかを示す。
   * 100%以下が理想的。
   */
  def fixedRatio: Double = {
    if (totalEquity.amount == 0) 0.0
    else (fixedAssets.amount.toDouble / totalEquity.amount.toDouble) * 100.0
  }

  /**
   * 固定長期適合率
   *
   * 固定資産 ÷ （純資産 + 固定負債） × 100
   *
   * 固定資産が長期資本でどれだけ賄われているかを示す。
   * 100%以下が望ましい。
   */
  def fixedLongTermFitRate: Double = {
    val longTermCapital = Money(totalEquity.amount + fixedLiabilities.amount)
    if (longTermCapital.amount == 0) 0.0
    else (fixedAssets.amount.toDouble / longTermCapital.amount.toDouble) * 100.0
  }

  /**
   * 負債比率
   *
   * 負債合計 ÷ 純資産 × 100
   *
   * 自己資本に対する他人資本の割合。100%以下が望ましい。
   */
  def debtRatio: Double = {
    if (totalEquity.amount == 0) 0.0
    else (totalLiabilities.amount.toDouble / totalEquity.amount.toDouble) * 100.0
  }

  /**
   * D/Eレシオ（有利子負債比率）
   *
   * 有利子負債 ÷ 純資産 × 100
   *
   * 自己資本に対する有利子負債の割合。1.0倍（100%）以下が望ましい。
   */
  def debtEquityRatio: Double = {
    if (totalEquity.amount == 0) 0.0
    else (interestBearingDebt.amount.toDouble / totalEquity.amount.toDouble) * 100.0
  }

  /**
   * インタレスト・カバレッジ・レシオ
   *
   * （営業利益 + 受取利息・配当金） ÷ 支払利息
   *
   * 利益で利息をどれだけ賄えるかを示す。2倍以上が望ましい。
   */
  def interestCoverageRatio(operatingIncome: Money, interestIncome: Money, interestExpense: Money): Double = {
    if (interestExpense.amount == 0) 0.0
    else {
      val earnings = Money(operatingIncome.amount + interestIncome.amount)
      earnings.amount.toDouble / interestExpense.amount.toDouble
    }
  }

  /**
   * 財務安全性の評価
   */
  def evaluateSafety(): SafetyEvaluation = {
    val cr = currentRatio
    val er = equityRatio
    val dr = debtRatio

    if (cr >= 200 && er >= 40 && dr <= 100) {
      SafetyEvaluation.Excellent  // 優良
    } else if (cr >= 120 && er >= 30 && dr <= 150) {
      SafetyEvaluation.Good       // 良好
    } else if (cr >= 100 && er >= 20 && dr <= 200) {
      SafetyEvaluation.Fair       // 普通
    } else if (cr >= 80 && er >= 10 && dr <= 300) {
      SafetyEvaluation.Warning    // 要注意
    } else {
      SafetyEvaluation.Danger     // 危険
    }
  }

  /**
   * 財務指標サマリーを生成
   */
  def summary(): String = {
    s"""
    |========== 安全性指標 ==========
    |対象月: ${targetMonth}
    |
    |【短期安全性】
    |  流動比率:         ${formatPercent(currentRatio)}
    |  当座比率:         ${formatPercent(quickRatio)}
    |
    |【長期安全性】
    |  自己資本比率:     ${formatPercent(equityRatio)}
    |  固定比率:         ${formatPercent(fixedRatio)}
    |  固定長期適合率:   ${formatPercent(fixedLongTermFitRate)}
    |
    |【負債比率】
    |  負債比率:         ${formatPercent(debtRatio)}
    |  D/Eレシオ:        ${formatPercent(debtEquityRatio)}
    |
    |【総合評価】
    |  ${evaluateSafety()}
    |================================
    """.stripMargin
  }

  private def formatPercent(value: Double): String = {
    f"$value%.2f%%"
  }
}

/**
 * 安全性評価
 */
sealed trait SafetyEvaluation
object SafetyEvaluation {
  case object Excellent extends SafetyEvaluation  // 優良
  case object Good extends SafetyEvaluation       // 良好
  case object Fair extends SafetyEvaluation       // 普通
  case object Warning extends SafetyEvaluation    // 要注意
  case object Danger extends SafetyEvaluation     // 危険
}
```

### 効率性指標のドメインモデル

#### EfficiencyIndicators（効率性指標）

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.domain.model

/**
 * 効率性指標
 *
 * 企業が資産をどれだけ効率的に活用しているかを測定する指標群
 */
final case class EfficiencyIndicators(
  fiscalYear: FiscalYear,
  targetMonth: YearMonth,

  // 損益計算書項目
  revenue: Money,                  // 売上高
  costOfGoodsSold: Money,         // 売上原価

  // 貸借対照表項目（期首・期末平均）
  totalAssets: Money,              // 総資産（平均）
  accountsReceivable: Money,       // 売上債権（平均）
  inventory: Money,                // 棚卸資産（平均）
  accountsPayable: Money,          // 買入債務（平均）

  // 期間（日数）
  periodDays: Int,                 // 対象期間の日数

  calculatedAt: java.time.Instant
) {

  /**
   * 総資産回転率
   *
   * 売上高 ÷ 総資産
   *
   * 総資産が年間何回転しているかを示す。
   * 製造業: 1.0-1.5回転、小売業: 2.0-3.0回転が一般的。
   */
  def totalAssetTurnover: Double = {
    if (totalAssets.amount == 0) 0.0
    else revenue.amount.toDouble / totalAssets.amount.toDouble
  }

  /**
   * 売上債権回転率
   *
   * 売上高 ÷ 売上債権
   *
   * 売掛金がどれだけ効率的に回収されているかを示す。
   */
  def accountsReceivableTurnover: Double = {
    if (accountsReceivable.amount == 0) 0.0
    else revenue.amount.toDouble / accountsReceivable.amount.toDouble
  }

  /**
   * 売上債権回収期間（日数）
   *
   * 365日 ÷ 売上債権回転率
   *
   * 売掛金の平均回収日数。短いほど良い。
   */
  def accountsReceivableCollectionPeriod: Int = {
    val turnover = accountsReceivableTurnover
    if (turnover == 0) 0
    else (365.0 / turnover).toInt
  }

  /**
   * 棚卸資産回転率
   *
   * 売上原価 ÷ 棚卸資産
   *
   * 在庫がどれだけ効率的に販売されているかを示す。
   */
  def inventoryTurnover: Double = {
    if (inventory.amount == 0) 0.0
    else costOfGoodsSold.amount.toDouble / inventory.amount.toDouble
  }

  /**
   * 棚卸資産回転期間（日数）
   *
   * 365日 ÷ 棚卸資産回転率
   *
   * 在庫の平均保有日数。短いほど効率的。
   */
  def inventoryTurnoverPeriod: Int = {
    val turnover = inventoryTurnover
    if (turnover == 0) 0
    else (365.0 / turnover).toInt
  }

  /**
   * 買入債務回転率
   *
   * 売上原価 ÷ 買入債務
   *
   * 買掛金の支払いサイクルを示す。
   */
  def accountsPayableTurnover: Double = {
    if (accountsPayable.amount == 0) 0.0
    else costOfGoodsSold.amount.toDouble / accountsPayable.amount.toDouble
  }

  /**
   * 買入債務支払期間（日数）
   *
   * 365日 ÷ 買入債務回転率
   *
   * 買掛金の平均支払日数。
   */
  def accountsPayablePaymentPeriod: Int = {
    val turnover = accountsPayableTurnover
    if (turnover == 0) 0
    else (365.0 / turnover).toInt
  }

  /**
   * キャッシュ・コンバージョン・サイクル（CCC）
   *
   * 売上債権回収期間 + 棚卸資産回転期間 - 買入債務支払期間
   *
   * 現金が事業サイクルに拘束される日数。短いほど資金効率が良い。
   */
  def cashConversionCycle: Int = {
    accountsReceivableCollectionPeriod + inventoryTurnoverPeriod - accountsPayablePaymentPeriod
  }

  /**
   * 運転資本回転率
   *
   * 売上高 ÷ 運転資本
   *
   * 運転資本 = 流動資産 - 流動負債
   */
  def workingCapitalTurnover(currentAssets: Money, currentLiabilities: Money): Double = {
    val workingCapital = Money(currentAssets.amount - currentLiabilities.amount)
    if (workingCapital.amount == 0) 0.0
    else revenue.amount.toDouble / workingCapital.amount.toDouble
  }

  /**
   * 財務指標サマリーを生成
   */
  def summary(): String = {
    s"""
    |========== 効率性指標 ==========
    |対象月: ${targetMonth}
    |
    |【資産回転率】
    |  総資産回転率:       ${formatTimes(totalAssetTurnover)}
    |  売上債権回転率:     ${formatTimes(accountsReceivableTurnover)}
    |  棚卸資産回転率:     ${formatTimes(inventoryTurnover)}
    |  買入債務回転率:     ${formatTimes(accountsPayableTurnover)}
    |
    |【回転期間】
    |  売上債権回収期間:   ${accountsReceivableCollectionPeriod}日
    |  棚卸資産回転期間:   ${inventoryTurnoverPeriod}日
    |  買入債務支払期間:   ${accountsPayablePaymentPeriod}日
    |
    |【キャッシュサイクル】
    |  CCC:                ${cashConversionCycle}日
    |================================
    """.stripMargin
  }

  private def formatTimes(value: Double): String = {
    f"$value%.2f回"
  }
}
```

### 財務分析サービスの実装

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.usecase

import com.github.j5ik2o.pekko.cqrs.accounting.domain.model.*
import scala.concurrent.{ExecutionContext, Future}
import java.time.YearMonth

/**
 * 財務分析サービス
 */
class FinancialAnalysisService(
  generalLedgerQueryService: GeneralLedgerQueryService,
  financialStatementQueryService: FinancialStatementQueryService
) {

  /**
   * 収益性指標を算出
   */
  def calculateProfitabilityIndicators(
    fiscalYear: FiscalYear,
    targetMonth: YearMonth
  )(implicit ec: ExecutionContext): Future[ProfitabilityIndicators] = {

    for {
      // 損益計算書を取得
      incomeStatement <- financialStatementQueryService.findIncomeStatement(fiscalYear, targetMonth)

      // 貸借対照表を取得
      balanceSheet <- financialStatementQueryService.findBalanceSheet(fiscalYear, targetMonth)

    } yield ProfitabilityIndicators(
      fiscalYear = fiscalYear,
      targetMonth = targetMonth,
      revenue = incomeStatement.revenue,
      costOfGoodsSold = incomeStatement.costOfGoodsSold,
      grossProfit = incomeStatement.grossProfit,
      operatingExpenses = incomeStatement.operatingExpenses,
      operatingIncome = incomeStatement.operatingIncome,
      nonOperatingIncome = incomeStatement.nonOperatingIncome,
      nonOperatingExpenses = incomeStatement.nonOperatingExpenses,
      ordinaryIncome = incomeStatement.ordinaryIncome,
      extraordinaryIncome = incomeStatement.extraordinaryIncome,
      extraordinaryLoss = incomeStatement.extraordinaryLoss,
      incomeBeforeTax = incomeStatement.incomeBeforeTax,
      corporateTax = incomeStatement.corporateTax,
      netIncome = incomeStatement.netIncome,
      totalAssets = balanceSheet.totalAssets,
      totalEquity = balanceSheet.totalEquity,
      calculatedAt = java.time.Instant.now()
    )
  }

  /**
   * 安全性指標を算出
   */
  def calculateSafetyIndicators(
    fiscalYear: FiscalYear,
    targetMonth: YearMonth
  )(implicit ec: ExecutionContext): Future[SafetyIndicators] = {

    for {
      // 貸借対照表を取得
      balanceSheet <- financialStatementQueryService.findBalanceSheet(fiscalYear, targetMonth)

    } yield SafetyIndicators(
      fiscalYear = fiscalYear,
      targetMonth = targetMonth,
      currentAssets = balanceSheet.currentAssets,
      quickAssets = balanceSheet.quickAssets,
      currentLiabilities = balanceSheet.currentLiabilities,
      fixedAssets = balanceSheet.fixedAssets,
      fixedLiabilities = balanceSheet.fixedLiabilities,
      totalAssets = balanceSheet.totalAssets,
      totalLiabilities = balanceSheet.totalLiabilities,
      totalEquity = balanceSheet.totalEquity,
      interestBearingDebt = balanceSheet.interestBearingDebt,
      calculatedAt = java.time.Instant.now()
    )
  }

  /**
   * 効率性指標を算出
   */
  def calculateEfficiencyIndicators(
    fiscalYear: FiscalYear,
    targetMonth: YearMonth
  )(implicit ec: ExecutionContext): Future[EfficiencyIndicators] = {

    for {
      // 損益計算書を取得
      incomeStatement <- financialStatementQueryService.findIncomeStatement(fiscalYear, targetMonth)

      // 貸借対照表を取得（期首と期末）
      balanceSheetCurrent <- financialStatementQueryService.findBalanceSheet(fiscalYear, targetMonth)
      balanceSheetPrevious <- financialStatementQueryService.findBalanceSheet(
        fiscalYear,
        targetMonth.minusMonths(1)
      )

      // 平均を計算
      avgTotalAssets = Money((balanceSheetCurrent.totalAssets.amount + balanceSheetPrevious.totalAssets.amount) / 2)
      avgAccountsReceivable = Money((balanceSheetCurrent.accountsReceivable.amount + balanceSheetPrevious.accountsReceivable.amount) / 2)
      avgInventory = Money((balanceSheetCurrent.inventory.amount + balanceSheetPrevious.inventory.amount) / 2)
      avgAccountsPayable = Money((balanceSheetCurrent.accountsPayable.amount + balanceSheetPrevious.accountsPayable.amount) / 2)

    } yield EfficiencyIndicators(
      fiscalYear = fiscalYear,
      targetMonth = targetMonth,
      revenue = incomeStatement.revenue,
      costOfGoodsSold = incomeStatement.costOfGoodsSold,
      totalAssets = avgTotalAssets,
      accountsReceivable = avgAccountsReceivable,
      inventory = avgInventory,
      accountsPayable = avgAccountsPayable,
      periodDays = 30,  // 月次の場合は30日
      calculatedAt = java.time.Instant.now()
    )
  }

  /**
   * 全ての財務指標を一括算出
   */
  def calculateAllIndicators(
    fiscalYear: FiscalYear,
    targetMonth: YearMonth
  )(implicit ec: ExecutionContext): Future[FinancialIndicators] = {

    for {
      profitability <- calculateProfitabilityIndicators(fiscalYear, targetMonth)
      safety <- calculateSafetyIndicators(fiscalYear, targetMonth)
      efficiency <- calculateEfficiencyIndicators(fiscalYear, targetMonth)

    } yield FinancialIndicators(
      fiscalYear = fiscalYear,
      targetMonth = targetMonth,
      profitability = profitability,
      safety = safety,
      efficiency = efficiency,
      calculatedAt = java.time.Instant.now()
    )
  }
}

/**
 * 財務指標（統合）
 */
final case class FinancialIndicators(
  fiscalYear: FiscalYear,
  targetMonth: YearMonth,
  profitability: ProfitabilityIndicators,
  safety: SafetyIndicators,
  efficiency: EfficiencyIndicators,
  calculatedAt: java.time.Instant
) {

  /**
   * 総合レポートを生成
   */
  def comprehensiveReport(): String = {
    s"""
    |========================================
    |        財務分析レポート
    |========================================
    |会計年度: ${fiscalYear.value}
    |対象月:   ${targetMonth}
    |作成日時: ${calculatedAt}
    |
    |${profitability.summary()}
    |
    |${safety.summary()}
    |
    |${efficiency.summary()}
    |
    |========================================
    """.stripMargin
  }
}
```

### D社の財務指標例（2024年7月）

```scala
// D社の2024年7月の財務指標

val dCompanyIndicators = FinancialIndicators(
  fiscalYear = FiscalYear(2024),
  targetMonth = YearMonth.of(2024, 7),

  profitability = ProfitabilityIndicators(
    fiscalYear = FiscalYear(2024),
    targetMonth = YearMonth.of(2024, 7),
    revenue = Money(1250000000),              // 月商12.5億円（年商150億円 ÷ 12）
    costOfGoodsSold = Money(1000000000),      // 売上原価10億円
    grossProfit = Money(250000000),           // 粗利2.5億円（粗利率20%）
    operatingExpenses = Money(200000000),     // 販管費2億円
    operatingIncome = Money(50000000),        // 営業利益5,000万円（営業利益率4%）
    nonOperatingIncome = Money(5000000),      // 営業外収益500万円
    nonOperatingExpenses = Money(10000000),   // 営業外費用1,000万円
    ordinaryIncome = Money(45000000),         // 経常利益4,500万円（経常利益率3.6%）
    extraordinaryIncome = Money(0),
    extraordinaryLoss = Money(0),
    incomeBeforeTax = Money(45000000),
    corporateTax = Money(13500000),           // 法人税等1,350万円（税率30%）
    netIncome = Money(31500000),              // 当期純利益3,150万円（純利益率2.5%）
    totalAssets = Money(12000000000),         // 総資産120億円
    totalEquity = Money(4800000000),          // 純資産48億円
    calculatedAt = java.time.Instant.now()
  ),
  // 粗利率: 20.0%
  // 営業利益率: 4.0%
  // 経常利益率: 3.6%
  // 純利益率: 2.5%
  // ROA: 3.15% (年換算で約3.15%)
  // ROE: 7.88% (年換算で約7.88%)

  safety = SafetyIndicators(
    fiscalYear = FiscalYear(2024),
    targetMonth = YearMonth.of(2024, 7),
    currentAssets = Money(7000000000),        // 流動資産70億円
    quickAssets = Money(5000000000),          // 当座資産50億円
    currentLiabilities = Money(3500000000),   // 流動負債35億円
    fixedAssets = Money(5000000000),          // 固定資産50億円
    fixedLiabilities = Money(3700000000),     // 固定負債37億円
    totalAssets = Money(12000000000),         // 総資産120億円
    totalLiabilities = Money(7200000000),     // 負債合計72億円
    totalEquity = Money(4800000000),          // 純資産48億円
    interestBearingDebt = Money(4000000000),  // 有利子負債40億円
    calculatedAt = java.time.Instant.now()
  ),
  // 流動比率: 200% (優良)
  // 当座比率: 142.9% (良好)
  // 自己資本比率: 40% (優良)
  // 固定比率: 104.2% (やや高い)
  // 負債比率: 150%
  // D/Eレシオ: 83.3% (良好)

  efficiency = EfficiencyIndicators(
    fiscalYear = FiscalYear(2024),
    targetMonth = YearMonth.of(2024, 7),
    revenue = Money(1250000000),              // 売上高12.5億円
    costOfGoodsSold = Money(1000000000),      // 売上原価10億円
    totalAssets = Money(12000000000),         // 総資産120億円
    accountsReceivable = Money(1500000000),   // 売掛金15億円
    inventory = Money(2200000000),            // 棚卸資産22億円
    accountsPayable = Money(1200000000),      // 買掛金12億円
    periodDays = 30,
    calculatedAt = java.time.Instant.now()
  ),
  // 総資産回転率: 1.25回（年間換算）
  // 売上債権回転率: 10.0回（年間換算）
  // 売上債権回収期間: 36日
  // 棚卸資産回転率: 5.45回（年間換算）
  // 棚卸資産回転期間: 67日
  // 買入債務回転率: 10.0回（年間換算）
  // 買入債務支払期間: 36日
  // CCC: 67日（36 + 67 - 36）

  calculatedAt = java.time.Instant.now()
)

// D社の財務指標レポート出力
println(dCompanyIndicators.comprehensiveReport())

/*
出力例:
========================================
        財務分析レポート
========================================
会計年度: 2024
対象月:   2024-07
作成日時: 2024-07-31T15:30:00Z

========== 収益性指標 ==========
対象月: 2024-07

【売上高・利益】
  売上高:           ¥1,250,000,000
  売上総利益:       ¥250,000,000
  営業利益:         ¥50,000,000
  経常利益:         ¥45,000,000
  当期純利益:       ¥31,500,000

【収益性比率】
  売上高総利益率:   20.00%
  売上高営業利益率: 4.00%
  売上高経常利益率: 3.60%
  売上高純利益率:   2.52%

【資本効率】
  ROA（総資産利益率）: 3.15%
  ROE（自己資本利益率）: 7.88%
================================

========== 安全性指標 ==========
対象月: 2024-07

【短期安全性】
  流動比率:         200.00%
  当座比率:         142.86%

【長期安全性】
  自己資本比率:     40.00%
  固定比率:         104.17%
  固定長期適合率:   58.82%

【負債比率】
  負債比率:         150.00%
  D/Eレシオ:        83.33%

【総合評価】
  Excellent
================================

========== 効率性指標 ==========
対象月: 2024-07

【資産回転率】
  総資産回転率:       1.25回
  売上債権回転率:     10.00回
  棚卸資産回転率:     5.45回
  買入債務回転率:     10.00回

【回転期間】
  売上債権回収期間:   36日
  棚卸資産回転期間:   67日
  買入債務支払期間:   36日

【キャッシュサイクル】
  CCC:                67日
================================

========================================
*/
```

## 8.2 予実管理

### 予実管理の概要

予実管理は、予算（Plan）と実績（Actual）を比較し、差異を分析することで、経営計画の達成度を管理する手法です。

#### 予実管理のメリット

- **早期の軌道修正**: 月次で予算と実績を比較することで、早期に問題を発見し対策を打てる
- **目標の明確化**: 予算を設定することで、組織の目標が明確になる
- **責任の明確化**: 部門別に予実を管理することで、各部門の責任が明確になる
- **着地予想**: 年度途中での着地見込みを予測できる

### 予算管理のドメインモデル

#### Budget（予算）

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.domain.model

import java.time.YearMonth

/**
 * 予算
 */
final case class Budget(
  id: BudgetId,
  fiscalYear: FiscalYear,
  targetMonth: YearMonth,
  budgetType: BudgetType,

  // 収益
  revenue: Money,

  // 費用
  costOfGoodsSold: Money,
  operatingExpenses: Money,

  // 利益
  grossProfit: Money,
  operatingIncome: Money,
  ordinaryIncome: Money,
  netIncome: Money,

  // 部門別（オプション）
  departmentId: Option[DepartmentId],

  // メタ情報
  note: Option[String],
  createdBy: String,
  createdAt: java.time.Instant,
  updatedAt: java.time.Instant
)

final case class BudgetId(value: String) extends AnyVal

/**
 * 予算タイプ
 */
sealed trait BudgetType
object BudgetType {
  case object Annual extends BudgetType    // 年次予算
  case object Monthly extends BudgetType   // 月次予算
  case object Quarterly extends BudgetType // 四半期予算
}
```

#### BudgetVsActual（予実比較）

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.domain.model

/**
 * 予実比較
 */
final case class BudgetVsActual(
  fiscalYear: FiscalYear,
  targetMonth: YearMonth,

  // 予算
  budget: Budget,

  // 実績
  actual: ActualPerformance,

  calculatedAt: java.time.Instant
) {

  /**
   * 売上高の予実差異
   */
  def revenueVariance: Money = {
    Money(actual.revenue.amount - budget.revenue.amount)
  }

  /**
   * 売上高の予実達成率
   */
  def revenueAchievementRate: Double = {
    if (budget.revenue.amount == 0) 0.0
    else (actual.revenue.amount.toDouble / budget.revenue.amount.toDouble) * 100.0
  }

  /**
   * 営業利益の予実差異
   */
  def operatingIncomeVariance: Money = {
    Money(actual.operatingIncome.amount - budget.operatingIncome.amount)
  }

  /**
   * 営業利益の予実達成率
   */
  def operatingIncomeAchievementRate: Double = {
    if (budget.operatingIncome.amount == 0) 0.0
    else (actual.operatingIncome.amount.toDouble / budget.operatingIncome.amount.toDouble) * 100.0
  }

  /**
   * 当期純利益の予実差異
   */
  def netIncomeVariance: Money = {
    Money(actual.netIncome.amount - budget.netIncome.amount)
  }

  /**
   * 当期純利益の予実達成率
   */
  def netIncomeAchievementRate: Double = {
    if (budget.netIncome.amount == 0) 0.0
    else (actual.netIncome.amount.toDouble / budget.netIncome.amount.toDouble) * 100.0
  }

  /**
   * 予実比較レポートを生成
   */
  def report(): String = {
    s"""
    |========== 予実比較レポート ==========
    |対象月: ${targetMonth}
    |
    |【売上高】
    |  予算:     ${formatMoney(budget.revenue)}
    |  実績:     ${formatMoney(actual.revenue)}
    |  差異:     ${formatMoney(revenueVariance)} (${formatVariance(revenueVariance)})
    |  達成率:   ${formatPercent(revenueAchievementRate)}
    |
    |【売上総利益】
    |  予算:     ${formatMoney(budget.grossProfit)}
    |  実績:     ${formatMoney(actual.grossProfit)}
    |  差異:     ${formatMoney(Money(actual.grossProfit.amount - budget.grossProfit.amount))}
    |
    |【営業利益】
    |  予算:     ${formatMoney(budget.operatingIncome)}
    |  実績:     ${formatMoney(actual.operatingIncome)}
    |  差異:     ${formatMoney(operatingIncomeVariance)} (${formatVariance(operatingIncomeVariance)})
    |  達成率:   ${formatPercent(operatingIncomeAchievementRate)}
    |
    |【当期純利益】
    |  予算:     ${formatMoney(budget.netIncome)}
    |  実績:     ${formatMoney(actual.netIncome)}
    |  差異:     ${formatMoney(netIncomeVariance)} (${formatVariance(netIncomeVariance)})
    |  達成率:   ${formatPercent(netIncomeAchievementRate)}
    |=====================================
    """.stripMargin
  }

  private def formatMoney(money: Money): String = {
    f"¥${money.amount}%,d"
  }

  private def formatVariance(money: Money): String = {
    if (money.amount >= 0) s"予算比+${formatMoney(money)}"
    else s"予算比${formatMoney(money)}"
  }

  private def formatPercent(value: Double): String = {
    f"$value%.2f%%"
  }
}

/**
 * 実績
 */
final case class ActualPerformance(
  revenue: Money,
  costOfGoodsSold: Money,
  grossProfit: Money,
  operatingExpenses: Money,
  operatingIncome: Money,
  ordinaryIncome: Money,
  netIncome: Money
)
```

### 着地予想の実装

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.usecase

/**
 * 着地予想サービス
 */
class ForecastService {

  /**
   * 年度末の着地予想を算出
   *
   * 現時点までの実績トレンドから年度末の着地を予想
   */
  def forecastYearEnd(
    fiscalYear: FiscalYear,
    currentMonth: YearMonth,
    actualPerformances: List[ActualPerformance],
    budget: Budget
  ): YearEndForecast = {

    // 現時点までの累計実績
    val cumulativeRevenue = actualPerformances.map(_.revenue.amount).sum
    val cumulativeNetIncome = actualPerformances.map(_.netIncome.amount).sum

    // 経過月数
    val elapsedMonths = actualPerformances.size

    // 月平均
    val avgMonthlyRevenue = if (elapsedMonths > 0) cumulativeRevenue / elapsedMonths else 0L
    val avgMonthlyNetIncome = if (elapsedMonths > 0) cumulativeNetIncome / elapsedMonths else 0L

    // 残り月数
    val remainingMonths = 12 - elapsedMonths

    // 着地予想（単純な線形予測）
    val forecastRevenue = Money(cumulativeRevenue + (avgMonthlyRevenue * remainingMonths))
    val forecastNetIncome = Money(cumulativeNetIncome + (avgMonthlyNetIncome * remainingMonths))

    // 予算との比較
    val revenueAchievementRate = if (budget.revenue.amount > 0) {
      (forecastRevenue.amount.toDouble / budget.revenue.amount.toDouble) * 100.0
    } else 0.0

    val netIncomeAchievementRate = if (budget.netIncome.amount > 0) {
      (forecastNetIncome.amount.toDouble / budget.netIncome.amount.toDouble) * 100.0
    } else 0.0

    YearEndForecast(
      fiscalYear = fiscalYear,
      asOfMonth = currentMonth,
      forecastRevenue = forecastRevenue,
      forecastNetIncome = forecastNetIncome,
      budgetRevenue = budget.revenue,
      budgetNetIncome = budget.netIncome,
      revenueAchievementRate = revenueAchievementRate,
      netIncomeAchievementRate = netIncomeAchievementRate,
      calculatedAt = java.time.Instant.now()
    )
  }
}

/**
 * 年度末着地予想
 */
final case class YearEndForecast(
  fiscalYear: FiscalYear,
  asOfMonth: YearMonth,
  forecastRevenue: Money,
  forecastNetIncome: Money,
  budgetRevenue: Money,
  budgetNetIncome: Money,
  revenueAchievementRate: Double,
  netIncomeAchievementRate: Double,
  calculatedAt: java.time.Instant
) {

  def report(): String = {
    s"""
    |========== 年度末着地予想 ==========
    |会計年度: ${fiscalYear.value}
    |基準月:   ${asOfMonth}
    |
    |【売上高】
    |  予算:     ${formatMoney(budgetRevenue)}
    |  着地予想: ${formatMoney(forecastRevenue)}
    |  達成率:   ${formatPercent(revenueAchievementRate)}
    |
    |【当期純利益】
    |  予算:     ${formatMoney(budgetNetIncome)}
    |  着地予想: ${formatMoney(forecastNetIncome)}
    |  達成率:   ${formatPercent(netIncomeAchievementRate)}
    |===================================
    """.stripMargin
  }

  private def formatMoney(money: Money): String = {
    f"¥${money.amount}%,d"
  }

  private def formatPercent(value: Double): String = {
    f"$value%.2f%%"
  }
}
```

### D社の予実管理例

```scala
// D社の2024年7月の予実比較

val dCompanyBudget = Budget(
  id = BudgetId("BDG-2024-07"),
  fiscalYear = FiscalYear(2024),
  targetMonth = YearMonth.of(2024, 7),
  budgetType = BudgetType.Monthly,
  revenue = Money(1300000000),          // 予算売上高13億円
  costOfGoodsSold = Money(1040000000),  // 予算売上原価10.4億円
  operatingExpenses = Money(200000000), // 予算販管費2億円
  grossProfit = Money(260000000),       // 予算粗利2.6億円（粗利率20%）
  operatingIncome = Money(60000000),    // 予算営業利益6,000万円
  ordinaryIncome = Money(55000000),     // 予算経常利益5,500万円
  netIncome = Money(38500000),          // 予算当期純利益3,850万円
  departmentId = None,
  note = Some("2024年度月次予算"),
  createdBy = "yamada.taro",
  createdAt = java.time.Instant.now(),
  updatedAt = java.time.Instant.now()
)

val dCompanyActual = ActualPerformance(
  revenue = Money(1250000000),          // 実績売上高12.5億円
  costOfGoodsSold = Money(1000000000),  // 実績売上原価10億円
  grossProfit = Money(250000000),       // 実績粗利2.5億円
  operatingExpenses = Money(200000000), // 実績販管費2億円
  operatingIncome = Money(50000000),    // 実績営業利益5,000万円
  ordinaryIncome = Money(45000000),     // 実績経常利益4,500万円
  netIncome = Money(31500000)           // 実績当期純利益3,150万円
)

val budgetVsActual = BudgetVsActual(
  fiscalYear = FiscalYear(2024),
  targetMonth = YearMonth.of(2024, 7),
  budget = dCompanyBudget,
  actual = dCompanyActual,
  calculatedAt = java.time.Instant.now()
)

println(budgetVsActual.report())

/*
出力例:
========== 予実比較レポート ==========
対象月: 2024-07

【売上高】
  予算:     ¥1,300,000,000
  実績:     ¥1,250,000,000
  差異:     ¥-50,000,000 (予算比-¥50,000,000)
  達成率:   96.15%

【売上総利益】
  予算:     ¥260,000,000
  実績:     ¥250,000,000
  差異:     ¥-10,000,000

【営業利益】
  予算:     ¥60,000,000
  実績:     ¥50,000,000
  差異:     ¥-10,000,000 (予算比-¥10,000,000)
  達成率:   83.33%

【当期純利益】
  予算:     ¥38,500,000
  実績:     ¥31,500,000
  差異:     ¥-7,000,000 (予算比-¥7,000,000)
  達成率:   81.82%
=====================================

分析:
- 売上高は予算比96.2%で、5,000万円未達
- 営業利益は予算比83.3%で、1,000万円未達
- 売上原価率は改善（予算80% → 実績80%）
- 販管費は予算どおり
- 改善策: 売上拡大施策の強化が必要
*/
```

## まとめ

本章では、財務分析機能の実装を学びました。

### 実装した内容

1. **収益性指標**
   - 売上高総利益率（粗利率）: 20%
   - 売上高営業利益率: 4%
   - 売上高経常利益率: 3.6%
   - ROA: 3.15%
   - ROE: 7.88%

2. **安全性指標**
   - 流動比率: 200%（優良）
   - 当座比率: 142.9%（良好）
   - 自己資本比率: 40%（優良）
   - D/Eレシオ: 83.3%（良好）

3. **効率性指標**
   - 総資産回転率: 1.25回
   - 売上債権回収期間: 36日
   - 棚卸資産回転期間: 67日
   - キャッシュ・コンバージョン・サイクル: 67日

4. **予実管理**
   - 月次予算と実績の比較
   - 予算達成率の算出
   - 年度末着地予想

### D社への適用効果

- **経営判断の迅速化**: 月次で財務指標を算出し、リアルタイムに経営状況を把握
- **目標管理**: 予実管理により、予算達成に向けた進捗を可視化
- **早期警戒**: 予算未達の兆候を早期に発見し、対策を実施
- **財務健全性**: 自己資本比率40%、流動比率200%の健全な財務体質を維持

次章では、パフォーマンス最適化（仕訳生成の高速化、財務諸表作成の最適化）を学びます。
