# 第6部 第4章 ドメインモデルの設計

本章では、会計システムのドメインモデルをDDD（ドメイン駆動設計）に基づいて設計します。

## 4.1 JournalEntry集約（仕訳）

仕訳は会計システムの中核となる集約です。全てのビジネストランザクションは仕訳として記録されます。

### 4.1.1 JournalEntry エンティティ

```scala
// 仕訳エンティティ
final case class JournalEntry(
  id: JournalEntryId,
  entryNumber: EntryNumber,              // 仕訳番号（連番）
  transactionDate: LocalDate,            // 取引日
  entryDate: LocalDate,                  // 仕訳日
  fiscalPeriod: FiscalPeriod,           // 会計期間
  voucherType: VoucherType,              // 伝票種類
  voucherNumber: Option[VoucherNumber],  // 伝票番号
  lines: List[JournalEntryLine],         // 仕訳明細
  description: String,                   // 摘要
  referenceInfo: Option[ReferenceInfo],  // 参照情報
  status: JournalEntryStatus,            // ステータス
  approvalInfo: Option[ApprovalInfo],    // 承認情報
  postingInfo: Option[PostingInfo],      // 転記情報
  reversalInfo: Option[ReversalInfo],    // 取消情報
  version: Version
) {

  // 借方合計を計算
  def debitTotal: Money = Money(
    lines.filter(_.debitCredit == DebitCredit.Debit).map(_.amount.amount).sum
  )

  // 貸方合計を計算
  def creditTotal: Money = Money(
    lines.filter(_.debitCredit == DebitCredit.Credit).map(_.amount.amount).sum
  )

  // 貸借一致チェック
  def isBalanced: Boolean = debitTotal.amount == creditTotal.amount

  // 合計金額
  def totalAmount: Money = debitTotal

  // 仕訳の検証
  def validate(): Either[ValidationError, JournalEntry] = {
    for {
      _ <- validateBalance()
      _ <- validateLines()
      _ <- validateFiscalPeriod()
      _ <- validateStatus()
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

  private def validateFiscalPeriod(): Either[ValidationError, Unit] = {
    if (fiscalPeriod.isClosed) {
      Left(ValidationError(s"会計期間が締め後のため、仕訳を登録できません: ${fiscalPeriod}"))
    } else Right(())
  }

  private def validateStatus(): Either[ValidationError, Unit] = {
    status match {
      case JournalEntryStatus.Cancelled =>
        Left(ValidationError("取消済みの仕訳は変更できません"))
      case JournalEntryStatus.Posted if reversalInfo.isEmpty =>
        Left(ValidationError("転記済みの仕訳は取消のみ可能です"))
      case _ => Right(())
    }
  }

  // 承認が必要か判定
  def requiresApproval: Boolean = {
    totalAmount.amount >= Money(1000000).amount // 100万円以上は承認必要
  }

  // 承認可能か判定
  def canBeApproved: Boolean = {
    status == JournalEntryStatus.PendingApproval && isBalanced
  }

  // 転記可能か判定
  def canBePosted: Boolean = {
    (status == JournalEntryStatus.Approved || !requiresApproval) && isBalanced
  }

  // 取消可能か判定
  def canBeCancelled: Boolean = {
    status == JournalEntryStatus.Posted && reversalInfo.isEmpty
  }
}
```

### 4.1.2 JournalEntryLine 値オブジェクト

```scala
// 仕訳明細
final case class JournalEntryLine(
  id: JournalEntryLineId,
  lineNumber: Int,                       // 行番号
  accountCode: AccountCode,              // 勘定科目コード
  debitCredit: DebitCredit,              // 貸借区分
  amount: Money,                         // 金額
  taxInfo: Option[TaxInfo],              // 税情報
  auxiliaryAccount: Option[AuxiliaryAccount], // 補助科目
  description: Option[String]            // 摘要
) {
  require(amount.amount > 0, "金額は正の値でなければなりません")
  require(lineNumber > 0, "行番号は1以上でなければなりません")

  // 税込金額を計算
  def amountIncludingTax: Money = taxInfo match {
    case Some(info) => Money(amount.amount + info.taxAmount.amount)
    case None => amount
  }
}

// 貸借区分
sealed trait DebitCredit
object DebitCredit {
  case object Debit extends DebitCredit   // 借方
  case object Credit extends DebitCredit  // 貸方
}

// 税情報
final case class TaxInfo(
  taxCategory: TaxCategory,   // 税区分
  taxRate: TaxRate,           // 税率
  taxAmount: Money            // 消費税額
)

// 税区分
sealed trait TaxCategory
object TaxCategory {
  case object Standard10 extends TaxCategory    // 標準課税10%
  case object Reduced8 extends TaxCategory      // 軽減税率8%
  case object NonTaxable extends TaxCategory    // 非課税
  case object TaxExempt extends TaxCategory     // 免税
  case object OutOfScope extends TaxCategory    // 対象外
}

// 税率
final case class TaxRate(rate: Double) {
  require(rate >= 0 && rate <= 1.0, "税率は0から1の範囲でなければなりません")

  def calculateTax(amount: Money): Money = {
    Money(BigDecimal(amount.amount.toDouble * rate).setScale(0, BigDecimal.RoundingMode.DOWN))
  }
}

// 補助科目
final case class AuxiliaryAccount(
  accountType: AuxiliaryAccountType,
  accountCode: String,
  accountName: String
)

sealed trait AuxiliaryAccountType
object AuxiliaryAccountType {
  case object Customer extends AuxiliaryAccountType   // 取引先（顧客）
  case object Supplier extends AuxiliaryAccountType   // 取引先（仕入先）
  case object Department extends AuxiliaryAccountType // 部門
  case object Project extends AuxiliaryAccountType    // プロジェクト
  case object Employee extends AuxiliaryAccountType   // 従業員
}
```

### 4.1.3 JournalEntryStatus

```scala
// 仕訳ステータス
sealed trait JournalEntryStatus

object JournalEntryStatus {
  case object Draft extends JournalEntryStatus           // 下書き
  case object PendingApproval extends JournalEntryStatus // 承認待ち
  case object Approved extends JournalEntryStatus        // 承認済み
  case object Posted extends JournalEntryStatus          // 転記済み
  case object Cancelled extends JournalEntryStatus       // 取消済み

  // ステータス遷移の検証
  def canTransition(from: JournalEntryStatus, to: JournalEntryStatus): Boolean = {
    (from, to) match {
      case (Draft, PendingApproval) => true
      case (Draft, Approved) => true  // 承認不要の場合
      case (PendingApproval, Approved) => true
      case (PendingApproval, Draft) => true  // 差し戻し
      case (Approved, Posted) => true
      case (Posted, Cancelled) => true
      case _ => false
    }
  }
}
```

### 4.1.4 関連する値オブジェクト

```scala
// 伝票種類
sealed trait VoucherType
object VoucherType {
  case object Sales extends VoucherType      // 売上伝票
  case object Purchase extends VoucherType   // 仕入伝票
  case object Receipt extends VoucherType    // 入金伝票
  case object Payment extends VoucherType    // 出金伝票
  case object Transfer extends VoucherType   // 振替伝票
}

// 参照情報
final case class ReferenceInfo(
  referenceType: String,  // Order, PurchaseOrder, Payment, etc.
  referenceId: String,
  sourceEventId: Option[String]
)

// 承認情報
final case class ApprovalInfo(
  approver: UserId,
  approvedAt: Instant,
  comment: Option[String]
)

// 転記情報
final case class PostingInfo(
  postedBy: UserId,
  postedAt: Instant,
  ledgerEntryIds: List[LedgerEntryId]
)

// 取消情報
final case class ReversalInfo(
  reversalEntryId: JournalEntryId,  // 取消仕訳ID
  reason: String,
  cancelledBy: UserId,
  cancelledAt: Instant
)

// 会計期間
final case class FiscalPeriod(
  fiscalYear: Int,
  fiscalMonth: Int
) {
  require(fiscalYear > 0, "会計年度は正の値でなければなりません")
  require(fiscalMonth >= 1 && fiscalMonth <= 12, "会計月度は1から12の範囲でなければなりません")

  def fiscalQuarter: Int = (fiscalMonth - 1) / 3 + 1

  def isClosed: Boolean = {
    // 締め後かどうかの判定ロジック
    // 実装例: 現在の会計期間より過去で、かつ締め処理完了している場合
    false // 実装は省略
  }

  def startDate: LocalDate = {
    // 会計年度の開始日（4月1日）
    LocalDate.of(fiscalYear, 4, 1).plusMonths(fiscalMonth - 1)
  }

  def endDate: LocalDate = {
    startDate.plusMonths(1).minusDays(1)
  }
}
```

## 4.2 GeneralLedger集約（総勘定元帳）

総勘定元帳は、勘定科目ごとの取引履歴と残高を管理する集約です。

### 4.2.1 GeneralLedger エンティティ

```scala
// 総勘定元帳エンティティ
final case class GeneralLedger(
  id: GeneralLedgerId,
  accountCode: AccountCode,
  fiscalPeriod: FiscalPeriod,
  entries: List[LedgerEntry],
  openingBalance: Money,
  currentBalance: Money,
  version: Version
) {

  // 元帳エントリを追加
  def addEntry(entry: LedgerEntry): GeneralLedger = {
    val newBalance = calculateNewBalance(entry)
    val updatedEntries = entries :+ entry.copy(balance = newBalance)
    copy(
      entries = updatedEntries,
      currentBalance = newBalance,
      version = version.increment
    )
  }

  // 新しい残高を計算
  private def calculateNewBalance(entry: LedgerEntry): Money = {
    entry.debitCredit match {
      case DebitCredit.Debit =>
        Money(currentBalance.amount + entry.amount.amount)
      case DebitCredit.Credit =>
        Money(currentBalance.amount - entry.amount.amount)
    }
  }

  // 期末残高を計算
  def closingBalance: Money = currentBalance

  // 借方合計を計算
  def debitTotal: Money = Money(
    entries.filter(_.debitCredit == DebitCredit.Debit).map(_.amount.amount).sum
  )

  // 貸方合計を計算
  def creditTotal: Money = Money(
    entries.filter(_.debitCredit == DebitCredit.Credit).map(_.amount.amount).sum
  )

  // 残高の整合性チェック
  def validateBalance(): Either[ValidationError, Unit] = {
    val expectedBalance = Money(
      openingBalance.amount + debitTotal.amount - creditTotal.amount
    )
    if (expectedBalance.amount == currentBalance.amount) Right(())
    else Left(ValidationError(
      s"残高の整合性エラー: 期待値=${expectedBalance.amount}, 実際値=${currentBalance.amount}"
    ))
  }
}

// 元帳エントリ
final case class LedgerEntry(
  id: LedgerEntryId,
  entryDate: LocalDate,
  journalEntryId: JournalEntryId,
  journalEntryLineId: JournalEntryLineId,
  debitCredit: DebitCredit,
  amount: Money,
  balance: Money,                    // 累計残高
  auxiliaryAccount: Option[AuxiliaryAccount],
  description: String,
  postedBy: UserId,
  postedAt: Instant
) {
  require(amount.amount > 0, "金額は正の値でなければなりません")
}
```

### 4.2.2 勘定科目関連

```scala
// 勘定科目コード
final case class AccountCode(value: String) {
  require(value.matches("^[0-9]{4}$"), "勘定科目コードは4桁の数字でなければなりません")
}

// 勘定科目
final case class AccountSubject(
  code: AccountCode,
  name: String,
  category: AccountCategory,
  subcategory: String,
  balanceSide: DebitCredit,
  level: Int,
  parentCode: Option[AccountCode],
  displayOrder: Int,
  showInPL: Boolean,              // 損益計算書に表示
  showInBS: Boolean,              // 貸借対照表に表示
  useAuxiliaryAccount: Boolean,
  auxiliaryType: Option[AuxiliaryAccountType],
  defaultTaxCategory: Option[TaxCategory],
  isActive: Boolean
) {
  require(level >= 1 && level <= 4, "レベルは1から4の範囲でなければなりません")
}

// 勘定区分
sealed trait AccountCategory
object AccountCategory {
  case object Asset extends AccountCategory        // 資産
  case object Liability extends AccountCategory    // 負債
  case object Equity extends AccountCategory       // 純資産
  case object Revenue extends AccountCategory      // 収益
  case object Expense extends AccountCategory      // 費用
}
```

## 4.3 FinancialStatement集約（財務諸表）

財務諸表は、損益計算書、貸借対照表、キャッシュフロー計算書を管理する集約です。

### 4.3.1 IncomeStatement エンティティ（損益計算書）

```scala
// 損益計算書エンティティ
final case class IncomeStatement(
  id: IncomeStatementId,
  fiscalPeriod: FiscalPeriod,
  periodType: PeriodType,
  asOfDate: LocalDate,

  // 売上高
  revenue: Money,

  // 売上原価
  costOfGoodsSold: Money,

  // 売上総利益
  grossProfit: Money,

  // 販売費及び一般管理費
  operatingExpenses: Money,

  // 営業利益
  operatingIncome: Money,

  // 営業外収益
  nonOperatingIncome: Money,

  // 営業外費用
  nonOperatingExpenses: Money,

  // 経常利益
  ordinaryIncome: Money,

  // 特別利益
  extraordinaryIncome: Money,

  // 特別損失
  extraordinaryLoss: Money,

  // 税引前当期純利益
  incomeBeforeTax: Money,

  // 法人税等
  incomeTax: Money,

  // 当期純利益
  netIncome: Money,

  version: Version
) {

  // 検証
  def validate(): Either[ValidationError, Unit] = {
    for {
      _ <- validateGrossProfit()
      _ <- validateOperatingIncome()
      _ <- validateOrdinaryIncome()
      _ <- validateIncomeBeforeTax()
      _ <- validateNetIncome()
    } yield ()
  }

  private def validateGrossProfit(): Either[ValidationError, Unit] = {
    val expected = Money(revenue.amount - costOfGoodsSold.amount)
    if (expected.amount == grossProfit.amount) Right(())
    else Left(ValidationError(
      s"売上総利益の計算エラー: 期待値=${expected.amount}, 実際値=${grossProfit.amount}"
    ))
  }

  private def validateOperatingIncome(): Either[ValidationError, Unit] = {
    val expected = Money(grossProfit.amount - operatingExpenses.amount)
    if (expected.amount == operatingIncome.amount) Right(())
    else Left(ValidationError(
      s"営業利益の計算エラー: 期待値=${expected.amount}, 実際値=${operatingIncome.amount}"
    ))
  }

  private def validateOrdinaryIncome(): Either[ValidationError, Unit] = {
    val expected = Money(
      operatingIncome.amount + nonOperatingIncome.amount - nonOperatingExpenses.amount
    )
    if (expected.amount == ordinaryIncome.amount) Right(())
    else Left(ValidationError(
      s"経常利益の計算エラー: 期待値=${expected.amount}, 実際値=${ordinaryIncome.amount}"
    ))
  }

  private def validateIncomeBeforeTax(): Either[ValidationError, Unit] = {
    val expected = Money(
      ordinaryIncome.amount + extraordinaryIncome.amount - extraordinaryLoss.amount
    )
    if (expected.amount == incomeBeforeTax.amount) Right(())
    else Left(ValidationError(
      s"税引前当期純利益の計算エラー: 期待値=${expected.amount}, 実際値=${incomeBeforeTax.amount}"
    ))
  }

  private def validateNetIncome(): Either[ValidationError, Unit] = {
    val expected = Money(incomeBeforeTax.amount - incomeTax.amount)
    if (expected.amount == netIncome.amount) Right(())
    else Left(ValidationError(
      s"当期純利益の計算エラー: 期待値=${expected.amount}, 実際値=${netIncome.amount}"
    ))
  }

  // 売上総利益率
  def grossProfitMargin: Double = {
    if (revenue.amount == 0) 0.0
    else (grossProfit.amount.toDouble / revenue.amount.toDouble) * 100.0
  }

  // 営業利益率
  def operatingProfitMargin: Double = {
    if (revenue.amount == 0) 0.0
    else (operatingIncome.amount.toDouble / revenue.amount.toDouble) * 100.0
  }

  // 当期純利益率
  def netProfitMargin: Double = {
    if (revenue.amount == 0) 0.0
    else (netIncome.amount.toDouble / revenue.amount.toDouble) * 100.0
  }
}
```

### 4.3.2 BalanceSheet エンティティ（貸借対照表）

```scala
// 貸借対照表エンティティ
final case class BalanceSheet(
  id: BalanceSheetId,
  fiscalPeriod: FiscalPeriod,
  periodType: PeriodType,
  asOfDate: LocalDate,

  // 資産の部
  currentAssets: AssetSection,
  fixedAssets: AssetSection,
  totalAssets: Money,

  // 負債の部
  currentLiabilities: LiabilitySection,
  fixedLiabilities: LiabilitySection,
  totalLiabilities: Money,

  // 純資産の部
  equity: EquitySection,
  totalEquity: Money,

  // 負債・純資産合計
  totalLiabilitiesAndEquity: Money,

  version: Version
) {

  // 貸借対照表の整合性チェック
  def isBalanced: Boolean = {
    totalAssets.amount == totalLiabilitiesAndEquity.amount
  }

  // 検証
  def validate(): Either[ValidationError, Unit] = {
    for {
      _ <- validateAssets()
      _ <- validateLiabilities()
      _ <- validateEquity()
      _ <- validateBalance()
    } yield ()
  }

  private def validateAssets(): Either[ValidationError, Unit] = {
    val expected = Money(currentAssets.total.amount + fixedAssets.total.amount)
    if (expected.amount == totalAssets.amount) Right(())
    else Left(ValidationError(
      s"資産合計の計算エラー: 期待値=${expected.amount}, 実際値=${totalAssets.amount}"
    ))
  }

  private def validateLiabilities(): Either[ValidationError, Unit] = {
    val expected = Money(currentLiabilities.total.amount + fixedLiabilities.total.amount)
    if (expected.amount == totalLiabilities.amount) Right(())
    else Left(ValidationError(
      s"負債合計の計算エラー: 期待値=${expected.amount}, 実際値=${totalLiabilities.amount}"
    ))
  }

  private def validateEquity(): Either[ValidationError, Unit] = {
    if (equity.total.amount == totalEquity.amount) Right(())
    else Left(ValidationError(
      s"純資産合計の計算エラー: 期待値=${equity.total.amount}, 実際値=${totalEquity.amount}"
    ))
  }

  private def validateBalance(): Either[ValidationError, Unit] = {
    val expected = Money(totalLiabilities.amount + totalEquity.amount)
    if (isBalanced) Right(())
    else Left(ValidationError(
      s"貸借対照表が一致しません: 資産=${totalAssets.amount}, 負債・純資産=${expected.amount}"
    ))
  }

  // 自己資本比率
  def equityRatio: Double = {
    if (totalAssets.amount == 0) 0.0
    else (totalEquity.amount.toDouble / totalAssets.amount.toDouble) * 100.0
  }

  // 流動比率
  def currentRatio: Double = {
    if (currentLiabilities.total.amount == 0) 0.0
    else (currentAssets.total.amount.toDouble / currentLiabilities.total.amount.toDouble) * 100.0
  }

  // 負債比率
  def debtRatio: Double = {
    if (totalEquity.amount == 0) 0.0
    else (totalLiabilities.amount.toDouble / totalEquity.amount.toDouble) * 100.0
  }
}

// 資産セクション
final case class AssetSection(
  items: List[AssetItem],
  total: Money
)

final case class AssetItem(
  accountCode: AccountCode,
  accountName: String,
  amount: Money
)

// 負債セクション
final case class LiabilitySection(
  items: List[LiabilityItem],
  total: Money
)

final case class LiabilityItem(
  accountCode: AccountCode,
  accountName: String,
  amount: Money
)

// 純資産セクション
final case class EquitySection(
  capital: Money,              // 資本金
  capitalSurplus: Money,       // 資本剰余金
  retainedEarnings: Money,     // 利益剰余金
  total: Money
)
```

### 4.3.3 CashFlowStatement エンティティ（キャッシュフロー計算書）

```scala
// キャッシュフロー計算書エンティティ
final case class CashFlowStatement(
  id: CashFlowStatementId,
  fiscalPeriod: FiscalPeriod,
  periodType: PeriodType,
  period: Period,

  // 営業活動によるキャッシュフロー
  operatingActivities: OperatingCashFlow,

  // 投資活動によるキャッシュフロー
  investingActivities: InvestingCashFlow,

  // 財務活動によるキャッシュフロー
  financingActivities: FinancingCashFlow,

  // 現金及び現金同等物の増減額
  netCashIncrease: Money,

  // 現金及び現金同等物の期首残高
  cashAtBeginning: Money,

  // 現金及び現金同等物の期末残高
  cashAtEnd: Money,

  version: Version
) {

  // 検証
  def validate(): Either[ValidationError, Unit] = {
    for {
      _ <- validateNetCashIncrease()
      _ <- validateCashAtEnd()
    } yield ()
  }

  private def validateNetCashIncrease(): Either[ValidationError, Unit] = {
    val expected = Money(
      operatingActivities.netCash.amount +
      investingActivities.netCash.amount +
      financingActivities.netCash.amount
    )
    if (expected.amount == netCashIncrease.amount) Right(())
    else Left(ValidationError(
      s"現金増減額の計算エラー: 期待値=${expected.amount}, 実際値=${netCashIncrease.amount}"
    ))
  }

  private def validateCashAtEnd(): Either[ValidationError, Unit] = {
    val expected = Money(cashAtBeginning.amount + netCashIncrease.amount)
    if (expected.amount == cashAtEnd.amount) Right(())
    else Left(ValidationError(
      s"期末現金残高の計算エラー: 期待値=${expected.amount}, 実際値=${cashAtEnd.amount}"
    ))
  }
}

// 営業活動によるキャッシュフロー
final case class OperatingCashFlow(
  incomeBeforeTax: Money,
  depreciation: Money,
  accountsReceivableDecrease: Money,
  inventoryDecrease: Money,
  accountsPayableIncrease: Money,
  subtotal: Money,
  incomeTaxPaid: Money,
  netCash: Money
)

// 投資活動によるキャッシュフロー
final case class InvestingCashFlow(
  fixedAssetPurchase: Money,
  fixedAssetSale: Money,
  investmentPurchase: Money,
  investmentSale: Money,
  netCash: Money
)

// 財務活動によるキャッシュフロー
final case class FinancingCashFlow(
  shortTermBorrowingIncrease: Money,
  longTermBorrowingIncrease: Money,
  borrowingRepayment: Money,
  dividendPaid: Money,
  netCash: Money
)
```

### 4.3.4 TrialBalance エンティティ（試算表）

```scala
// 試算表エンティティ
final case class TrialBalance(
  id: TrialBalanceId,
  fiscalPeriod: FiscalPeriod,
  balances: List[AccountBalance],
  totalDebit: Money,
  totalCredit: Money,
  status: TrialBalanceStatus,
  generatedBy: UserId,
  generatedAt: Instant,
  version: Version
) {

  // 貸借一致チェック
  def isBalanced: Boolean = {
    totalDebit.amount == totalCredit.amount
  }

  // 検証
  def validate(): Either[ValidationError, Unit] = {
    for {
      _ <- validateTotals()
      _ <- validateBalance()
    } yield ()
  }

  private def validateTotals(): Either[ValidationError, Unit] = {
    val expectedDebit = Money(balances.map(_.debitTotal.amount).sum)
    val expectedCredit = Money(balances.map(_.creditTotal.amount).sum)

    if (expectedDebit.amount == totalDebit.amount &&
        expectedCredit.amount == totalCredit.amount) Right(())
    else Left(ValidationError(
      s"試算表合計の計算エラー: 借方期待=${expectedDebit.amount}, 実際=${totalDebit.amount}, " +
      s"貸方期待=${expectedCredit.amount}, 実際=${totalCredit.amount}"
    ))
  }

  private def validateBalance(): Either[ValidationError, Unit] = {
    if (isBalanced) Right(())
    else Left(ValidationError(
      s"試算表の貸借が一致しません: 借方=${totalDebit.amount}, 貸方=${totalCredit.amount}"
    ))
  }
}

// 勘定科目別残高
final case class AccountBalance(
  accountCode: AccountCode,
  accountName: String,
  openingBalance: Money,
  debitTotal: Money,
  creditTotal: Money,
  closingBalance: Money
)

// 試算表ステータス
sealed trait TrialBalanceStatus
object TrialBalanceStatus {
  case object Draft extends TrialBalanceStatus      // 下書き
  case object Confirmed extends TrialBalanceStatus  // 確定
  case object Closed extends TrialBalanceStatus     // 締め済み
}
```

### 4.3.5 期間タイプ

```scala
// 期間タイプ
sealed trait PeriodType
object PeriodType {
  case object Monthly extends PeriodType    // 月次
  case object Quarterly extends PeriodType  // 四半期
  case object Annual extends PeriodType     // 年次
}
```

## 4.4 AccountsReceivable集約（売掛金管理）

売掛金の残高管理とエージング分析を行う集約です。

### 4.4.1 AccountsReceivable エンティティ

```scala
// 売掛金エンティティ
final case class AccountsReceivable(
  id: AccountsReceivableId,
  customerId: CustomerId,
  invoiceId: InvoiceId,
  orderId: OrderId,
  invoiceDate: LocalDate,
  dueDate: LocalDate,
  invoiceAmount: Money,
  paidAmount: Money,
  balance: Money,
  status: ReceivableStatus,
  aging: AgingCategory,
  paymentHistory: List[PaymentRecord],
  version: Version
) {

  // 入金を記録
  def recordPayment(payment: PaymentRecord): AccountsReceivable = {
    val newPaidAmount = Money(paidAmount.amount + payment.amount.amount)
    val newBalance = Money(invoiceAmount.amount - newPaidAmount.amount)
    val newStatus = if (newBalance.amount == 0) ReceivableStatus.Paid
                    else if (newPaidAmount.amount > 0) ReceivableStatus.PartiallyPaid
                    else status

    copy(
      paidAmount = newPaidAmount,
      balance = newBalance,
      status = newStatus,
      paymentHistory = paymentHistory :+ payment,
      version = version.increment
    )
  }

  // 期日超過日数を計算
  def overdueDays: Int = {
    val today = LocalDate.now()
    if (today.isAfter(dueDate) && balance.amount > 0) {
      java.time.temporal.ChronoUnit.DAYS.between(dueDate, today).toInt
    } else 0
  }

  // エージング区分を更新
  def updateAging(): AccountsReceivable = {
    val newAging = AgingCategory.fromOverdueDays(overdueDays)
    copy(aging = newAging)
  }

  // 期日超過しているか
  def isOverdue: Boolean = overdueDays > 0
}

// 売掛金ステータス
sealed trait ReceivableStatus
object ReceivableStatus {
  case object Unpaid extends ReceivableStatus         // 未入金
  case object PartiallyPaid extends ReceivableStatus  // 一部入金
  case object Paid extends ReceivableStatus           // 入金済み
  case object WrittenOff extends ReceivableStatus     // 貸倒償却
}

// エージング区分
sealed trait AgingCategory
object AgingCategory {
  case object Current extends AgingCategory      // 30日以内
  case object Days30To60 extends AgingCategory   // 31〜60日
  case object Days61To90 extends AgingCategory   // 61〜90日
  case object Over90Days extends AgingCategory   // 90日超

  def fromOverdueDays(days: Int): AgingCategory = days match {
    case d if d <= 30 => Current
    case d if d <= 60 => Days30To60
    case d if d <= 90 => Days61To90
    case _ => Over90Days
  }

  def description: String = this match {
    case Current => "30日以内"
    case Days30To60 => "31〜60日"
    case Days61To90 => "61〜90日"
    case Over90Days => "90日超"
  }
}

// 入金記録
final case class PaymentRecord(
  paymentId: PaymentId,
  paymentDate: LocalDate,
  amount: Money,
  paymentMethod: PaymentMethod,
  note: Option[String]
)

// 支払方法
sealed trait PaymentMethod
object PaymentMethod {
  case object BankTransfer extends PaymentMethod  // 銀行振込
  case object Cash extends PaymentMethod          // 現金
  case object Check extends PaymentMethod         // 小切手
  case object CreditCard extends PaymentMethod    // クレジットカード
  case object Other extends PaymentMethod         // その他
}
```

## 4.5 AccountsPayable集約（買掛金管理）

買掛金の残高管理と支払管理を行う集約です。

### 4.5.1 AccountsPayable エンティティ

```scala
// 買掛金エンティティ
final case class AccountsPayable(
  id: AccountsPayableId,
  supplierId: SupplierId,
  invoiceId: InvoiceId,
  purchaseOrderId: PurchaseOrderId,
  invoiceDate: LocalDate,
  dueDate: LocalDate,
  invoiceAmount: Money,
  paidAmount: Money,
  balance: Money,
  status: PayableStatus,
  paymentHistory: List[PaymentRecord],
  version: Version
) {

  // 支払を記録
  def recordPayment(payment: PaymentRecord): AccountsPayable = {
    val newPaidAmount = Money(paidAmount.amount + payment.amount.amount)
    val newBalance = Money(invoiceAmount.amount - newPaidAmount.amount)
    val newStatus = if (newBalance.amount == 0) PayableStatus.Paid
                    else if (newPaidAmount.amount > 0) PayableStatus.PartiallyPaid
                    else status

    copy(
      paidAmount = newPaidAmount,
      balance = newBalance,
      status = newStatus,
      paymentHistory = paymentHistory :+ payment,
      version = version.increment
    )
  }

  // 期日超過日数を計算
  def overdueDays: Int = {
    val today = LocalDate.now()
    if (today.isAfter(dueDate) && balance.amount > 0) {
      java.time.temporal.ChronoUnit.DAYS.between(dueDate, today).toInt
    } else 0
  }

  // 期日超過しているか
  def isOverdue: Boolean = overdueDays > 0
}

// 買掛金ステータス
sealed trait PayableStatus
object PayableStatus {
  case object Unpaid extends PayableStatus         // 未払い
  case object PartiallyPaid extends PayableStatus  // 一部支払済み
  case object Paid extends PayableStatus           // 支払済み
}
```

## まとめ

本章では、会計システムの5つの主要な集約を設計しました。

**設計した集約**:
1. **JournalEntry集約**: 仕訳の作成・承認・転記・取消
2. **GeneralLedger集約**: 勘定科目別の取引履歴と残高管理
3. **FinancialStatement集約**: 損益計算書・貸借対照表・キャッシュフロー計算書・試算表
4. **AccountsReceivable集約**: 売掛金管理とエージング分析
5. **AccountsPayable集約**: 買掛金管理と支払管理

**主要なドメインルール**:
- 仕訳の貸借一致（借方合計 = 貸方合計）
- 財務諸表の整合性（損益計算の段階利益計算、貸借対照表の資産 = 負債 + 純資産）
- 会計期間の締め後は仕訳登録不可
- 金額に応じた承認ワークフロー（100万円以上）
- エージング区分による売掛金管理

**値オブジェクト**:
- Money、AccountCode、TaxRate、FiscalPeriod
- DebitCredit、VoucherType、AccountCategory
- AgingCategory、PaymentMethod

次章では、これらの集約を実装し、Pekko Persistenceを使用したイベントソーシングを行います。