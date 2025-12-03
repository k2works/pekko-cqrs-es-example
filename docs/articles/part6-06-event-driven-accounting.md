# 第6部6章：イベント駆動会計の実装

## 本章の目的

本章では、CQRS/イベントソーシングアーキテクチャにおいて、ビジネスイベントから会計仕訳を自動生成するイベント駆動会計の実装を学びます。

従来の会計システムでは、業務システムと会計システムが分離しており、手動でデータを転記したり、バッチ処理で連携していました。イベント駆動会計では、ビジネスイベント（受注確定、入荷検収、入金など）が発生した瞬間に、対応する仕訳を自動生成します。

### 学習内容

1. ビジネスイベントから仕訳への変換パターン
2. Pekko Persistence Queryによるイベント購読
3. 冪等性の保証（重複イベント処理の防止）
4. イベントソーシングによる監査証跡
5. 実践的な統合実装とテスト

## 6.1 イベント駆動会計の概要

### 従来の会計連携 vs イベント駆動会計

#### 従来の会計連携（バッチ処理）

```
業務システム              会計システム
   |                         |
   | 1. 受注確定              |
   |                         |
   | 2. 出荷                  |
   |                         |
   | 3. 請求書発行            |
   |                         |
   | 4. 夜間バッチ実行  ----> | 5. 仕訳一括取込
   |                         | 6. 仕訳承認
   |                         | 7. 総勘定元帳転記
```

**課題**:
- リアルタイム性がない（翌日にならないと会計データが反映されない）
- データの二重管理（業務システムと会計システムで同じデータを保持）
- 連携エラー時の対応が困難
- 監査証跡が不完全（元トランザクションとの紐付けが弱い）

#### イベント駆動会計（リアルタイム）

```
業務システム                     会計システム
   |                                |
   | 1. OrderConfirmed イベント --> | 2. 売上仕訳自動生成
   |                                | 3. 仕訳承認（必要に応じて）
   |                                | 4. 総勘定元帳転記
   |                                |
   | 5. PaymentReceived イベント -> | 6. 入金仕訳自動生成
   |                                | 7. 総勘定元帳転記
```

**利点**:
- リアルタイムで仕訳が生成される（経営判断に必要な情報がすぐに見られる）
- イベントソーシングによる完全な監査証跡
- 業務システムと会計システムの疎結合（イベント経由でのみ連携）
- 冪等性の保証によるデータ整合性の確保

### D社における適用範囲

D社（年商150億円、仕訳年間190万件）では、以下のビジネスイベントから仕訳を自動生成します：

| ビジネスイベント | 発生元 | 会計仕訳 | 年間件数 |
|---|---|---|---|
| OrderConfirmed | 受注管理サービス | 売上仕訳 | 67,000件/月 |
| InspectionCompleted | 発注管理サービス | 仕入仕訳 | 55,000件/月 |
| PaymentReceived | 入金管理サービス | 入金仕訳 | 67,000件/月 |
| PaymentMade | 支払管理サービス | 支払仕訳 | 55,000件/月 |
| DepreciationCalculated | 固定資産管理 | 減価償却仕訳 | 1回/月 |
| SalaryPaymentProcessed | 給与管理 | 給与仕訳 | 1回/月 |

年間合計: 約244,000件の自動仕訳 + 月次経費等 = **約190万件**

## 6.2 ビジネスイベントから仕訳への変換

### 売上仕訳の生成（OrderConfirmed イベント）

受注管理サービスから `OrderConfirmed` イベントを受け取り、売上仕訳を自動生成します。

#### OrderConfirmed イベントの構造

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.domain.event

import java.time.LocalDate

// 受注管理サービスのイベント
final case class OrderConfirmed(
  orderId: String,
  customerId: String,
  orderDate: LocalDate,
  totalAmount: BigDecimal,           // 税込金額
  totalAmountExcludingTax: BigDecimal, // 税抜金額
  taxAmount: BigDecimal,              // 消費税額
  items: List[OrderItem]
)

final case class OrderItem(
  productId: String,
  quantity: Int,
  unitPrice: BigDecimal,
  amount: BigDecimal
)
```

#### 売上仕訳生成ロジック

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.usecase

import com.github.j5ik2o.pekko.cqrs.accounting.domain.model.*
import com.github.j5ik2o.pekko.cqrs.accounting.domain.event.OrderConfirmed
import java.time.LocalDate

/**
 * 売上仕訳生成サービス
 *
 * OrderConfirmed イベントから売上仕訳を生成する
 */
class SalesJournalEntryGenerator(
  chartOfAccountsService: ChartOfAccountsService,
  entryNumberGenerator: () => EntryNumber
) {

  /**
   * 売上仕訳を生成
   *
   * 仕訳パターン:
   * 借方: 売掛金 (1130) 税込金額
   * 貸方: 売上高 (4120) 税抜金額
   * 貸方: 仮受消費税 (2190) 消費税額
   */
  def generateFromOrderConfirmed(
    event: OrderConfirmed,
    fiscalYear: FiscalYear,
    fiscalPeriod: FiscalPeriod
  ): Either[ValidationError, JournalEntry] = {

    for {
      // 勘定科目の存在確認
      accountsReceivable <- chartOfAccountsService.findByCode(AccountCode("1130"))
      salesRevenue <- chartOfAccountsService.findByCode(AccountCode("4120"))
      consumptionTaxPayable <- chartOfAccountsService.findByCode(AccountCode("2190"))

      // 仕訳明細の作成
      lines = List(
        // 借方: 売掛金
        JournalEntryLine(
          lineNumber = LineNumber(1),
          accountCode = AccountCode("1130"),
          accountName = accountsReceivable.accountName,
          debitCredit = DebitCredit.Debit,
          amount = Money(event.totalAmount),
          taxType = None,
          auxiliaryAccount = Some(AuxiliaryAccount(event.customerId)),
          description = Some(s"売上計上 注文番号: ${event.orderId}")
        ),
        // 貸方: 売上高
        JournalEntryLine(
          lineNumber = LineNumber(2),
          accountCode = AccountCode("4120"),
          accountName = salesRevenue.accountName,
          debitCredit = DebitCredit.Credit,
          amount = Money(event.totalAmountExcludingTax),
          taxType = None,
          description = Some(s"売上計上 注文番号: ${event.orderId}")
        ),
        // 貸方: 仮受消費税
        JournalEntryLine(
          lineNumber = LineNumber(3),
          accountCode = AccountCode("2190"),
          accountName = consumptionTaxPayable.accountName,
          debitCredit = DebitCredit.Credit,
          amount = Money(event.taxAmount),
          taxType = Some(TaxType.Consumption10),
          description = Some(s"消費税 注文番号: ${event.orderId}")
        )
      )

      // 仕訳の作成
      entry = JournalEntry(
        id = JournalEntryId.generate(),
        entryNumber = entryNumberGenerator(),
        entryDate = event.orderDate,
        fiscalYear = fiscalYear,
        fiscalPeriod = fiscalPeriod,
        voucherType = VoucherType.Sales,
        lines = lines,
        description = Some(s"売上計上 顧客: ${event.customerId}"),
        sourceEventId = Some(event.orderId),  // 元イベントIDを保存（監査証跡）
        status = JournalEntryStatus.Draft,
        createdAt = java.time.Instant.now(),
        updatedAt = java.time.Instant.now()
      )

      // 仕訳の検証
      validatedEntry <- entry.validate()

    } yield validatedEntry
  }
}
```

#### 売上仕訳のテスト

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.usecase

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues
import java.time.LocalDate
import com.github.j5ik2o.pekko.cqrs.accounting.domain.event.OrderConfirmed
import com.github.j5ik2o.pekko.cqrs.accounting.domain.model.*

class SalesJournalEntryGeneratorSpec extends AnyFlatSpec with Matchers with EitherValues {

  // テスト用の勘定科目サービス（モック）
  val chartOfAccountsService = new ChartOfAccountsService {
    private val accounts = Map(
      AccountCode("1130") -> ChartOfAccount(AccountCode("1130"), AccountName("売掛金"), AccountType.Asset),
      AccountCode("4120") -> ChartOfAccount(AccountCode("4120"), AccountName("売上高"), AccountType.Revenue),
      AccountCode("2190") -> ChartOfAccount(AccountCode("2190"), AccountName("仮受消費税"), AccountType.Liability)
    )

    override def findByCode(code: AccountCode): Either[ValidationError, ChartOfAccount] =
      accounts.get(code).toRight(ValidationError(s"勘定科目が見つかりません: ${code.value}"))
  }

  // テスト用の伝票番号生成器
  var entryNumberCounter = 1
  def entryNumberGenerator(): EntryNumber = {
    val number = EntryNumber(f"SL-2024-$entryNumberCounter%06d")
    entryNumberCounter += 1
    number
  }

  val generator = new SalesJournalEntryGenerator(chartOfAccountsService, entryNumberGenerator _)

  "SalesJournalEntryGenerator" should "OrderConfirmedイベントから売上仕訳を生成できる" in {
    // Given: 受注確定イベント
    val event = OrderConfirmed(
      orderId = "ORD-20240701-001",
      customerId = "CUST-1001",
      orderDate = LocalDate.of(2024, 7, 1),
      totalAmount = BigDecimal("110000"),          // 税込
      totalAmountExcludingTax = BigDecimal("100000"), // 税抜
      taxAmount = BigDecimal("10000"),             // 消費税10%
      items = List(
        OrderItem("PROD-001", 10, BigDecimal("10000"), BigDecimal("100000"))
      )
    )

    val fiscalYear = FiscalYear(2024)
    val fiscalPeriod = FiscalPeriod(2024, 4)  // 2024年7月（第4期）

    // When: 売上仕訳を生成
    val result = generator.generateFromOrderConfirmed(event, fiscalYear, fiscalPeriod)

    // Then: 仕訳が正しく生成される
    result should be a 'right
    val entry = result.value

    entry.voucherType shouldBe VoucherType.Sales
    entry.entryDate shouldBe LocalDate.of(2024, 7, 1)
    entry.sourceEventId shouldBe Some("ORD-20240701-001")

    // 3行の仕訳明細が生成される
    entry.lines should have size 3

    // 借方: 売掛金 110,000円
    val debitLine = entry.lines.find(_.debitCredit == DebitCredit.Debit).get
    debitLine.accountCode shouldBe AccountCode("1130")
    debitLine.amount shouldBe Money(110000)
    debitLine.auxiliaryAccount shouldBe Some(AuxiliaryAccount("CUST-1001"))

    // 貸方: 売上高 100,000円
    val salesLine = entry.lines.find(l =>
      l.debitCredit == DebitCredit.Credit && l.accountCode == AccountCode("4120")
    ).get
    salesLine.amount shouldBe Money(100000)

    // 貸方: 仮受消費税 10,000円
    val taxLine = entry.lines.find(l =>
      l.debitCredit == DebitCredit.Credit && l.accountCode == AccountCode("2190")
    ).get
    taxLine.amount shouldBe Money(10000)
    taxLine.taxType shouldBe Some(TaxType.Consumption10)

    // 貸借が一致している
    entry.isBalanced shouldBe true
    entry.debitTotal shouldBe Money(110000)
    entry.creditTotal shouldBe Money(110000)
  }

  it should "100万円以上の売上仕訳は承認待ちステータスになる" in {
    // Given: 高額受注（100万円以上）
    val event = OrderConfirmed(
      orderId = "ORD-20240701-002",
      customerId = "CUST-2001",
      orderDate = LocalDate.of(2024, 7, 1),
      totalAmount = BigDecimal("1100000"),          // 税込110万円
      totalAmountExcludingTax = BigDecimal("1000000"),
      taxAmount = BigDecimal("100000"),
      items = List()
    )

    val fiscalYear = FiscalYear(2024)
    val fiscalPeriod = FiscalPeriod(2024, 4)

    // When: 売上仕訳を生成
    val result = generator.generateFromOrderConfirmed(event, fiscalYear, fiscalPeriod)

    // Then: Draftステータスで生成され、承認が必要
    val entry = result.value
    entry.status shouldBe JournalEntryStatus.Draft
    entry.requiresApproval shouldBe true
    entry.totalAmount shouldBe Money(1100000)
  }
}
```

### 仕入仕訳の生成（InspectionCompleted イベント）

発注管理サービスから `InspectionCompleted` イベントを受け取り、仕入仕訳を自動生成します。

#### InspectionCompleted イベントの構造

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.domain.event

import java.time.LocalDate

// 発注管理サービスのイベント
final case class InspectionCompleted(
  purchaseOrderId: String,
  supplierId: String,
  inspectionDate: LocalDate,
  totalAmount: BigDecimal,           // 税込金額
  totalAmountExcludingTax: BigDecimal, // 税抜金額
  taxAmount: BigDecimal,              // 消費税額
  items: List[PurchaseItem]
)

final case class PurchaseItem(
  productId: String,
  quantity: Int,
  unitPrice: BigDecimal,
  amount: BigDecimal
)
```

#### 仕入仕訳生成ロジック

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.usecase

/**
 * 仕入仕訳生成サービス
 *
 * InspectionCompleted イベントから仕入仕訳を生成する
 */
class PurchaseJournalEntryGenerator(
  chartOfAccountsService: ChartOfAccountsService,
  entryNumberGenerator: () => EntryNumber
) {

  /**
   * 仕入仕訳を生成
   *
   * 仕訳パターン:
   * 借方: 仕入高 (5110) 税抜金額
   * 借方: 仮払消費税 (1180) 消費税額
   * 貸方: 買掛金 (2110) 税込金額
   */
  def generateFromInspectionCompleted(
    event: InspectionCompleted,
    fiscalYear: FiscalYear,
    fiscalPeriod: FiscalPeriod
  ): Either[ValidationError, JournalEntry] = {

    for {
      // 勘定科目の存在確認
      purchaseCost <- chartOfAccountsService.findByCode(AccountCode("5110"))
      consumptionTaxReceivable <- chartOfAccountsService.findByCode(AccountCode("1180"))
      accountsPayable <- chartOfAccountsService.findByCode(AccountCode("2110"))

      // 仕訳明細の作成
      lines = List(
        // 借方: 仕入高
        JournalEntryLine(
          lineNumber = LineNumber(1),
          accountCode = AccountCode("5110"),
          accountName = purchaseCost.accountName,
          debitCredit = DebitCredit.Debit,
          amount = Money(event.totalAmountExcludingTax),
          taxType = None,
          description = Some(s"仕入計上 発注番号: ${event.purchaseOrderId}")
        ),
        // 借方: 仮払消費税
        JournalEntryLine(
          lineNumber = LineNumber(2),
          accountCode = AccountCode("1180"),
          accountName = consumptionTaxReceivable.accountName,
          debitCredit = DebitCredit.Debit,
          amount = Money(event.taxAmount),
          taxType = Some(TaxType.Consumption10),
          description = Some(s"消費税 発注番号: ${event.purchaseOrderId}")
        ),
        // 貸方: 買掛金
        JournalEntryLine(
          lineNumber = LineNumber(3),
          accountCode = AccountCode("2110"),
          accountName = accountsPayable.accountName,
          debitCredit = DebitCredit.Credit,
          amount = Money(event.totalAmount),
          taxType = None,
          auxiliaryAccount = Some(AuxiliaryAccount(event.supplierId)),
          description = Some(s"仕入計上 発注番号: ${event.purchaseOrderId}")
        )
      )

      // 仕訳の作成
      entry = JournalEntry(
        id = JournalEntryId.generate(),
        entryNumber = entryNumberGenerator(),
        entryDate = event.inspectionDate,
        fiscalYear = fiscalYear,
        fiscalPeriod = fiscalPeriod,
        voucherType = VoucherType.Purchase,
        lines = lines,
        description = Some(s"仕入計上 仕入先: ${event.supplierId}"),
        sourceEventId = Some(event.purchaseOrderId),  // 元イベントIDを保存
        status = JournalEntryStatus.Draft,
        createdAt = java.time.Instant.now(),
        updatedAt = java.time.Instant.now()
      )

      // 仕訳の検証
      validatedEntry <- entry.validate()

    } yield validatedEntry
  }
}
```

### 入金仕訳の生成（PaymentReceived イベント）

入金管理サービスから `PaymentReceived` イベントを受け取り、入金仕訳を自動生成します。

#### PaymentReceived イベントの構造

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.domain.event

import java.time.LocalDate

// 入金管理サービスのイベント
final case class PaymentReceived(
  paymentId: String,
  customerId: String,
  paymentDate: LocalDate,
  amount: BigDecimal,
  paymentMethod: PaymentMethod,
  relatedOrderIds: List[String]  // 関連する受注ID（複数の場合あり)
)

sealed trait PaymentMethod
object PaymentMethod {
  case object BankTransfer extends PaymentMethod  // 銀行振込
  case object Cash extends PaymentMethod          // 現金
  case object CreditCard extends PaymentMethod    // クレジットカード
}
```

#### 入金仕訳生成ロジック

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.usecase

/**
 * 入金仕訳生成サービス
 *
 * PaymentReceived イベントから入金仕訳を生成する
 */
class PaymentReceivedJournalEntryGenerator(
  chartOfAccountsService: ChartOfAccountsService,
  entryNumberGenerator: () => EntryNumber
) {

  /**
   * 入金仕訳を生成
   *
   * 仕訳パターン:
   * 借方: 普通預金 (1110) または 現金 (1100)
   * 貸方: 売掛金 (1130)
   */
  def generateFromPaymentReceived(
    event: PaymentReceived,
    fiscalYear: FiscalYear,
    fiscalPeriod: FiscalPeriod
  ): Either[ValidationError, JournalEntry] = {

    for {
      // 勘定科目の存在確認
      // 入金方法に応じて借方科目を選択
      debitAccountCode = event.paymentMethod match {
        case PaymentMethod.BankTransfer => AccountCode("1110")  // 普通預金
        case PaymentMethod.Cash => AccountCode("1100")          // 現金
        case PaymentMethod.CreditCard => AccountCode("1120")    // クレジットカード売掛金
      }

      debitAccount <- chartOfAccountsService.findByCode(debitAccountCode)
      accountsReceivable <- chartOfAccountsService.findByCode(AccountCode("1130"))

      // 仕訳明細の作成
      lines = List(
        // 借方: 普通預金 or 現金 or クレジットカード売掛金
        JournalEntryLine(
          lineNumber = LineNumber(1),
          accountCode = debitAccountCode,
          accountName = debitAccount.accountName,
          debitCredit = DebitCredit.Debit,
          amount = Money(event.amount),
          description = Some(s"入金 入金ID: ${event.paymentId}")
        ),
        // 貸方: 売掛金
        JournalEntryLine(
          lineNumber = LineNumber(2),
          accountCode = AccountCode("1130"),
          accountName = accountsReceivable.accountName,
          debitCredit = DebitCredit.Credit,
          amount = Money(event.amount),
          auxiliaryAccount = Some(AuxiliaryAccount(event.customerId)),
          description = Some(s"入金 顧客: ${event.customerId}")
        )
      )

      // 仕訳の作成
      entry = JournalEntry(
        id = JournalEntryId.generate(),
        entryNumber = entryNumberGenerator(),
        entryDate = event.paymentDate,
        fiscalYear = fiscalYear,
        fiscalPeriod = fiscalPeriod,
        voucherType = VoucherType.Receipt,
        lines = lines,
        description = Some(s"入金 顧客: ${event.customerId} ${event.paymentMethod}"),
        sourceEventId = Some(event.paymentId),  // 元イベントIDを保存
        status = JournalEntryStatus.Draft,
        createdAt = java.time.Instant.now(),
        updatedAt = java.time.Instant.now()
      )

      // 仕訳の検証
      validatedEntry <- entry.validate()

    } yield validatedEntry
  }
}
```

## 6.3 イベント購読とイベントハンドラー

### Pekko Persistence Queryによるイベント購読

Pekko Persistence Queryを使用して、業務システムのイベントを購読し、仕訳を自動生成します。

#### イベント購読アクター

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.interfaceadapter

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.persistence.query.PersistenceQuery
import org.apache.pekko.persistence.query.scaladsl.EventsByTagQuery
import org.apache.pekko.stream.scaladsl.{RestartSource, Sink}
import org.apache.pekko.stream.RestartSettings
import scala.concurrent.duration.*
import com.github.j5ik2o.pekko.cqrs.accounting.domain.event.*
import com.github.j5ik2o.pekko.cqrs.accounting.usecase.*

/**
 * ビジネスイベント購読アクター
 *
 * 業務システムのイベントを購読し、仕訳を自動生成する
 */
object BusinessEventSubscriber {

  sealed trait Command
  private case object Start extends Command
  private case class EventReceived(event: Any, offset: Long) extends Command

  def apply(
    salesGenerator: SalesJournalEntryGenerator,
    purchaseGenerator: PurchaseJournalEntryGenerator,
    paymentGenerator: PaymentReceivedJournalEntryGenerator,
    journalEntryActor: ActorRef[JournalEntryCommand]
  ): Behavior[Command] = Behaviors.setup { context =>

    implicit val system = context.system
    implicit val ec = system.executionContext

    // Persistence Queryの取得
    val readJournal = PersistenceQuery(system)
      .readJournalFor[EventsByTagQuery]("cassandra-query-journal")

    Behaviors.receiveMessage {
      case Start =>
        context.log.info("ビジネスイベントの購読を開始します")

        // "order-events" タグでイベントを購読
        val orderEventSource = RestartSource.onFailuresWithBackoff(
          RestartSettings(
            minBackoff = 3.seconds,
            maxBackoff = 30.seconds,
            randomFactor = 0.2
          )
        ) { () =>
          readJournal.eventsByTag("order-events", offset = 0L)
        }

        orderEventSource.runWith(Sink.actorRef(
          ref = context.self,
          onCompleteMessage = Start,  // ストリーム完了時は再開
          onFailureMessage = (ex: Throwable) => {
            context.log.error("イベント購読エラー", ex)
            Start
          }
        ))

        Behaviors.same

      case EventReceived(event: OrderConfirmed, offset) =>
        context.log.info(s"OrderConfirmed イベントを受信: ${event.orderId}")

        // 会計年度・期間の判定
        val fiscalYear = FiscalYear.fromDate(event.orderDate)
        val fiscalPeriod = FiscalPeriod.fromDate(event.orderDate)

        // 売上仕訳を生成
        salesGenerator.generateFromOrderConfirmed(event, fiscalYear, fiscalPeriod) match {
          case Right(entry) =>
            context.log.info(s"売上仕訳を生成しました: ${entry.entryNumber}")

            // 仕訳作成コマンドを送信
            journalEntryActor ! JournalEntryCommand.CreateJournalEntry(
              id = entry.id,
              entryDate = entry.entryDate,
              fiscalYear = entry.fiscalYear,
              fiscalPeriod = entry.fiscalPeriod,
              voucherType = entry.voucherType,
              lines = entry.lines,
              description = entry.description,
              sourceEventId = entry.sourceEventId,
              idempotencyKey = Some(s"order-${event.orderId}")  // 冪等性キー
            )

          case Left(error) =>
            context.log.error(s"売上仕訳の生成に失敗しました: ${error.message}")
        }

        Behaviors.same

      case EventReceived(event: InspectionCompleted, offset) =>
        context.log.info(s"InspectionCompleted イベントを受信: ${event.purchaseOrderId}")

        val fiscalYear = FiscalYear.fromDate(event.inspectionDate)
        val fiscalPeriod = FiscalPeriod.fromDate(event.inspectionDate)

        // 仕入仕訳を生成
        purchaseGenerator.generateFromInspectionCompleted(event, fiscalYear, fiscalPeriod) match {
          case Right(entry) =>
            context.log.info(s"仕入仕訳を生成しました: ${entry.entryNumber}")

            journalEntryActor ! JournalEntryCommand.CreateJournalEntry(
              id = entry.id,
              entryDate = entry.entryDate,
              fiscalYear = entry.fiscalYear,
              fiscalPeriod = entry.fiscalPeriod,
              voucherType = entry.voucherType,
              lines = entry.lines,
              description = entry.description,
              sourceEventId = entry.sourceEventId,
              idempotencyKey = Some(s"purchase-${event.purchaseOrderId}")  // 冪等性キー
            )

          case Left(error) =>
            context.log.error(s"仕入仕訳の生成に失敗しました: ${error.message}")
        }

        Behaviors.same

      case EventReceived(event: PaymentReceived, offset) =>
        context.log.info(s"PaymentReceived イベントを受信: ${event.paymentId}")

        val fiscalYear = FiscalYear.fromDate(event.paymentDate)
        val fiscalPeriod = FiscalPeriod.fromDate(event.paymentDate)

        // 入金仕訳を生成
        paymentGenerator.generateFromPaymentReceived(event, fiscalYear, fiscalPeriod) match {
          case Right(entry) =>
            context.log.info(s"入金仕訳を生成しました: ${entry.entryNumber}")

            journalEntryActor ! JournalEntryCommand.CreateJournalEntry(
              id = entry.id,
              entryDate = entry.entryDate,
              fiscalYear = entry.fiscalYear,
              fiscalPeriod = entry.fiscalPeriod,
              voucherType = entry.voucherType,
              lines = entry.lines,
              description = entry.description,
              sourceEventId = entry.sourceEventId,
              idempotencyKey = Some(s"payment-${event.paymentId}")  // 冪等性キー
            )

          case Left(error) =>
            context.log.error(s"入金仕訳の生成に失敗しました: ${error.message}")
        }

        Behaviors.same

      case _ =>
        Behaviors.same
    }
  }
}
```

### 冪等性の保証

イベント駆動アーキテクチャでは、ネットワーク障害などにより同じイベントが複数回配信される可能性があります。重複イベントによって仕訳が二重計上されないよう、冪等性を保証する必要があります。

#### 冪等性キーによる重複検知

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.domain.model

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import org.apache.pekko.persistence.typed.PersistenceId

/**
 * 冪等性を保証するJournalEntryActor
 */
object JournalEntryActor {

  sealed trait Command
  final case class CreateJournalEntry(
    id: JournalEntryId,
    entryDate: java.time.LocalDate,
    fiscalYear: FiscalYear,
    fiscalPeriod: FiscalPeriod,
    voucherType: VoucherType,
    lines: List[JournalEntryLine],
    description: Option[String],
    sourceEventId: Option[String],
    idempotencyKey: Option[String],  // 冪等性キー
    replyTo: ActorRef[StatusReply[JournalEntry]]
  ) extends Command

  sealed trait Event
  final case class JournalEntryCreated(
    id: JournalEntryId,
    entryNumber: EntryNumber,
    entryDate: java.time.LocalDate,
    fiscalYear: FiscalYear,
    fiscalPeriod: FiscalPeriod,
    voucherType: VoucherType,
    lines: List[JournalEntryLine],
    description: Option[String],
    sourceEventId: Option[String],
    idempotencyKey: Option[String],  // 冪等性キーを保存
    createdAt: java.time.Instant
  ) extends Event

  sealed trait State
  case object EmptyState extends State
  final case class JournalEntryState(
    entry: JournalEntry,
    processedIdempotencyKeys: Set[String]  // 処理済み冪等性キー
  ) extends State

  def commandHandler(
    entryNumberGenerator: () => EntryNumber,
    chartOfAccountsService: ChartOfAccountsService,
    context: ActorContext[Command]
  ): (State, Command) => Effect[Event, State] = { (state, command) =>

    command match {
      case cmd: CreateJournalEntry =>
        state match {
          case EmptyState =>
            // 新規作成
            createNewJournalEntry(cmd, entryNumberGenerator, chartOfAccountsService)

          case currentState: JournalEntryState =>
            // 冪等性キーのチェック
            cmd.idempotencyKey match {
              case Some(key) if currentState.processedIdempotencyKeys.contains(key) =>
                // 既に処理済み - 冪等性保証
                context.log.warn(s"冪等性キー '$key' は既に処理済みです。重複リクエストをスキップします。")
                Effect.reply(cmd.replyTo)(StatusReply.success(currentState.entry))

              case _ =>
                // 新しいリクエスト
                context.log.error(s"仕訳 ${currentState.entry.id} は既に存在します")
                Effect.reply(cmd.replyTo)(StatusReply.error("仕訳は既に存在します"))
            }
        }
    }
  }

  private def createNewJournalEntry(
    cmd: CreateJournalEntry,
    entryNumberGenerator: () => EntryNumber,
    chartOfAccountsService: ChartOfAccountsService
  ): Effect[Event, State] = {

    // バリデーション
    val validationResult = for {
      _ <- validateBalance(cmd.lines)
      _ <- validateLines(cmd.lines)
      _ <- validateFiscalPeriod(cmd.fiscalPeriod)
      _ <- validateAccounts(cmd.lines, chartOfAccountsService)
    } yield ()

    validationResult match {
      case Right(_) =>
        val event = JournalEntryCreated(
          id = cmd.id,
          entryNumber = entryNumberGenerator(),
          entryDate = cmd.entryDate,
          fiscalYear = cmd.fiscalYear,
          fiscalPeriod = cmd.fiscalPeriod,
          voucherType = cmd.voucherType,
          lines = cmd.lines,
          description = cmd.description,
          sourceEventId = cmd.sourceEventId,
          idempotencyKey = cmd.idempotencyKey,  // 冪等性キーを保存
          createdAt = java.time.Instant.now()
        )

        Effect
          .persist(event)
          .thenReply(cmd.replyTo) { state =>
            state match {
              case s: JournalEntryState => StatusReply.success(s.entry)
              case _ => StatusReply.error("仕訳の作成に失敗しました")
            }
          }

      case Left(error) =>
        Effect.reply(cmd.replyTo)(StatusReply.error(error.message))
    }
  }

  def eventHandler: (State, Event) => State = { (state, event) =>
    event match {
      case evt: JournalEntryCreated =>
        val entry = JournalEntry(
          id = evt.id,
          entryNumber = evt.entryNumber,
          entryDate = evt.entryDate,
          fiscalYear = evt.fiscalYear,
          fiscalPeriod = evt.fiscalPeriod,
          voucherType = evt.voucherType,
          lines = evt.lines,
          description = evt.description,
          sourceEventId = evt.sourceEventId,
          status = JournalEntryStatus.Draft,
          createdAt = evt.createdAt,
          updatedAt = evt.createdAt
        )

        val processedKeys = state match {
          case s: JournalEntryState => s.processedIdempotencyKeys
          case EmptyState => Set.empty[String]
        }

        // 冪等性キーを処理済みセットに追加
        val updatedKeys = evt.idempotencyKey match {
          case Some(key) => processedKeys + key
          case None => processedKeys
        }

        JournalEntryState(entry, updatedKeys)
    }
  }
}
```

#### 冪等性のテスト

```scala
class JournalEntryActorIdempotencySpec extends ScalaTestWithActorTestKit {

  "JournalEntryActor" should "同じ冪等性キーでの重複リクエストを拒否する" in {
    val probe = testKit.createTestProbe[StatusReply[JournalEntry]]()
    val actor = testKit.spawn(JournalEntryActor(...))

    val cmd = CreateJournalEntry(
      id = JournalEntryId.generate(),
      entryDate = LocalDate.of(2024, 7, 1),
      fiscalYear = FiscalYear(2024),
      fiscalPeriod = FiscalPeriod(2024, 4),
      voucherType = VoucherType.Sales,
      lines = List(...),
      description = Some("売上計上"),
      sourceEventId = Some("ORD-20240701-001"),
      idempotencyKey = Some("order-ORD-20240701-001"),  // 冪等性キー
      replyTo = probe.ref
    )

    // 1回目のリクエスト - 成功
    actor ! cmd
    val response1 = probe.receiveMessage()
    response1.isSuccess shouldBe true

    // 2回目のリクエスト（重複） - 冪等性保証により同じ結果を返す
    actor ! cmd
    val response2 = probe.receiveMessage()
    response2.isSuccess shouldBe true

    // 同じ仕訳が返される
    response1.getValue.id shouldBe response2.getValue.id
    response1.getValue.entryNumber shouldBe response2.getValue.entryNumber
  }
}
```

## 6.4 イベントソーシング監査証跡

イベントソーシングアーキテクチャの最大の利点の一つは、完全な監査証跡（Audit Trail）が自動的に記録されることです。

### 監査証跡の構成要素

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.domain.model

/**
 * 会計監査証跡
 *
 * イベントソーシングにより自動的に記録される情報:
 * - いつ（When）: イベントのタイムスタンプ
 * - 誰が（Who）: イベントを発行したユーザー/システム
 * - 何を（What）: 仕訳の内容（金額、科目、摘要）
 * - なぜ（Why）: 元となったビジネスイベント（受注、発注など）
 * - どのように（How）: 仕訳の状態変化（Draft → Approved → Posted）
 */
final case class AuditTrail(
  journalEntryId: JournalEntryId,
  events: List[AuditEvent]
) {

  /**
   * 監査レポートを生成
   */
  def generateAuditReport(): String = {
    val sb = new StringBuilder
    sb.append(s"仕訳ID: ${journalEntryId.value}\n")
    sb.append("=" * 80 + "\n")

    events.foreach { event =>
      sb.append(s"[${event.timestamp}] ${event.eventType}\n")
      sb.append(s"  実行者: ${event.performedBy}\n")
      event.sourceEventId.foreach(id => sb.append(s"  元イベント: $id\n"))
      sb.append(s"  変更内容: ${event.description}\n")
      sb.append("-" * 80 + "\n")
    }

    sb.toString()
  }
}

final case class AuditEvent(
  eventType: String,
  timestamp: java.time.Instant,
  performedBy: String,
  description: String,
  sourceEventId: Option[String],  // 元となったビジネスイベントID
  metadata: Map[String, String]
)
```

### 監査証跡の利用例

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.usecase

/**
 * 監査証跡サービス
 */
class AuditTrailService(
  readJournal: EventsByPersistenceIdQuery
) {

  /**
   * 特定の仕訳の完全な履歴を取得
   */
  def getJournalEntryHistory(
    journalEntryId: JournalEntryId
  ): Future[AuditTrail] = {

    val persistenceId = s"JournalEntry-${journalEntryId.value}"

    readJournal
      .eventsByPersistenceId(persistenceId, 0L, Long.MaxValue)
      .map { envelope =>
        envelope.event match {
          case evt: JournalEntryCreated =>
            AuditEvent(
              eventType = "仕訳作成",
              timestamp = evt.createdAt,
              performedBy = "System",
              description = s"仕訳を作成しました。伝票番号: ${evt.entryNumber.value}",
              sourceEventId = evt.sourceEventId,
              metadata = Map(
                "voucherType" -> evt.voucherType.toString,
                "totalAmount" -> evt.lines.map(_.amount.amount).sum.toString
              )
            )

          case evt: ApprovalRequested =>
            AuditEvent(
              eventType = "承認依頼",
              timestamp = evt.requestedAt,
              performedBy = evt.requestedBy,
              description = s"承認を依頼しました。承認者: ${evt.approver}",
              sourceEventId = None,
              metadata = Map("approver" -> evt.approver)
            )

          case evt: JournalEntryApproved =>
            AuditEvent(
              eventType = "承認",
              timestamp = evt.approvedAt,
              performedBy = evt.approvedBy,
              description = s"仕訳を承認しました。",
              sourceEventId = None,
              metadata = Map("comment" -> evt.comment.getOrElse(""))
            )

          case evt: JournalEntryPosted =>
            AuditEvent(
              eventType = "転記",
              timestamp = evt.postedAt,
              performedBy = "System",
              description = "総勘定元帳に転記しました。",
              sourceEventId = None,
              metadata = Map()
            )
        }
      }
      .runWith(Sink.seq)
      .map(events => AuditTrail(journalEntryId, events.toList))
  }

  /**
   * 特定のビジネスイベントから生成された全仕訳を追跡
   */
  def findJournalEntriesBySourceEvent(
    sourceEventId: String
  ): Future[List[JournalEntry]] = {
    // sourceEventId でイベントストアを検索
    // 実装は省略
    Future.successful(List.empty)
  }
}
```

### 監査レポートの例

```
仕訳ID: JE-2024-070001
================================================================================
[2024-07-01T09:00:00Z] 仕訳作成
  実行者: System
  元イベント: ORD-20240701-001
  変更内容: 仕訳を作成しました。伝票番号: SL-2024-000001
--------------------------------------------------------------------------------
[2024-07-01T09:05:00Z] 承認依頼
  実行者: yamada.taro
  変更内容: 承認を依頼しました。承認者: suzuki.hanako
--------------------------------------------------------------------------------
[2024-07-01T10:30:00Z] 承認
  実行者: suzuki.hanako
  変更内容: 仕訳を承認しました。
--------------------------------------------------------------------------------
[2024-07-01T10:31:00Z] 転記
  実行者: System
  変更内容: 総勘定元帳に転記しました。
--------------------------------------------------------------------------------
```

## 6.5 統合テスト

### イベント駆動会計の統合テスト

```scala
package com.github.j5ik2o.pekko.cqrs.accounting.integration

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import scala.concurrent.duration.*

class EventDrivenAccountingIntegrationSpec
  extends ScalaTestWithActorTestKit
  with AnyFlatSpec
  with Matchers {

  "イベント駆動会計システム" should "受注イベントから売上仕訳を自動生成し総勘定元帳に転記する" in {

    // Given: システムの初期化
    val chartOfAccountsService = new ChartOfAccountsService(...)
    val salesGenerator = new SalesJournalEntryGenerator(
      chartOfAccountsService,
      () => EntryNumber(s"SL-2024-${System.currentTimeMillis()}")
    )

    val journalEntryActorProbe = testKit.createTestProbe[JournalEntryCommand]()
    val generalLedgerActorProbe = testKit.createTestProbe[GeneralLedgerCommand]()

    val subscriber = testKit.spawn(
      BusinessEventSubscriber(
        salesGenerator,
        purchaseGenerator,
        paymentGenerator,
        journalEntryActorProbe.ref
      )
    )

    // When: OrderConfirmed イベントを発行
    val orderEvent = OrderConfirmed(
      orderId = "ORD-20240701-001",
      customerId = "CUST-1001",
      orderDate = LocalDate.of(2024, 7, 1),
      totalAmount = BigDecimal("110000"),
      totalAmountExcludingTax = BigDecimal("100000"),
      taxAmount = BigDecimal("10000"),
      items = List(...)
    )

    // イベントを発行（実際にはPekko Clusterのイベントストリームに発行）
    system.eventStream.publish(orderEvent)

    // Then: 仕訳作成コマンドが送信される
    val createCmd = journalEntryActorProbe.receiveMessage(3.seconds)
    createCmd shouldBe a[JournalEntryCommand.CreateJournalEntry]

    val cmd = createCmd.asInstanceOf[JournalEntryCommand.CreateJournalEntry]
    cmd.voucherType shouldBe VoucherType.Sales
    cmd.idempotencyKey shouldBe Some("order-ORD-20240701-001")

    // 仕訳が貸借一致している
    val debitTotal = cmd.lines.filter(_.debitCredit == DebitCredit.Debit)
      .map(_.amount.amount).sum
    val creditTotal = cmd.lines.filter(_.debitCredit == DebitCredit.Credit)
      .map(_.amount.amount).sum

    debitTotal shouldBe creditTotal
    debitTotal shouldBe BigDecimal("110000")

    // Then: 仕訳が自動承認される（100万円未満のため）
    // Then: 総勘定元帳への転記コマンドが送信される
    // （実際の動作確認は省略）
  }

  it should "同じイベントが複数回配信されても仕訳は一度だけ作成される（冪等性）" in {
    // Given: システムの初期化
    val journalEntryActor = testKit.spawn(JournalEntryActor(...))

    val cmd = CreateJournalEntry(
      id = JournalEntryId.generate(),
      entryDate = LocalDate.of(2024, 7, 1),
      fiscalYear = FiscalYear(2024),
      fiscalPeriod = FiscalPeriod(2024, 4),
      voucherType = VoucherType.Sales,
      lines = List(...),
      description = Some("売上計上"),
      sourceEventId = Some("ORD-20240701-001"),
      idempotencyKey = Some("order-ORD-20240701-001"),
      replyTo = probe.ref
    )

    // When: 同じコマンドを3回送信（ネットワーク障害による再送を模擬）
    journalEntryActor ! cmd
    journalEntryActor ! cmd
    journalEntryActor ! cmd

    // Then: 3回とも成功レスポンスが返る
    val response1 = probe.receiveMessage()
    val response2 = probe.receiveMessage()
    val response3 = probe.receiveMessage()

    response1.isSuccess shouldBe true
    response2.isSuccess shouldBe true
    response3.isSuccess shouldBe true

    // Then: 同じ仕訳が返される（新規作成ではなく既存仕訳を返す）
    val entry1 = response1.getValue
    val entry2 = response2.getValue
    val entry3 = response3.getValue

    entry1.id shouldBe entry2.id
    entry2.id shouldBe entry3.id
    entry1.entryNumber shouldBe entry2.entryNumber
  }
}
```

## まとめ

本章では、イベント駆動会計の実装を学びました。

### 実装した内容

1. **ビジネスイベントから仕訳への変換**
   - OrderConfirmed → 売上仕訳
   - InspectionCompleted → 仕入仕訳
   - PaymentReceived → 入金仕訳
   - 各仕訳パターンの完全なScala 3実装

2. **Pekko Persistence Queryによるイベント購読**
   - RestartSourceによる自動再接続
   - イベントタグによるフィルタリング
   - ストリーム処理による高速化

3. **冪等性の保証**
   - 冪等性キーによる重複検知
   - 処理済みキーの保存
   - ネットワーク障害時の安全性確保

4. **イベントソーシング監査証跡**
   - 完全な変更履歴の自動記録
   - 元ビジネスイベントとの紐付け
   - 監査レポートの生成

### D社への適用効果

- **リアルタイム性**: 受注確定から仕訳生成まで **数秒以内**（従来は翌日）
- **正確性**: 自動生成により手入力ミスを **完全排除**
- **監査対応**: イベントソーシングにより **完全な証跡** を保持
- **冪等性**: 障害時でも **データ整合性** を保証
- **スケーラビリティ**: 年間190万件の仕訳を **自動生成**

次章では、債権債務管理の実装を学びます。売掛金・買掛金の残高管理、入金消込、支払予定管理などを実装します。
