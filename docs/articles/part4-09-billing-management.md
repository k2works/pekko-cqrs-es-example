# 【第4部 第9章】請求管理の実装：月次締めと入金処理

## 本章の目的

請求管理は、受注管理システムの最終段階であり、売上を確定させる重要なプロセスです。本章では、月次締め処理による請求書の自動生成、入金記録と照合、入金不足・過入金の処理、入金催促について詳しく説明します。Invoice集約を中心に、確定した注文を請求書に変換し、入金を管理する仕組みを実装します。

## 9.1 月次締め処理

### 9.1.1 請求書生成の業務フロー

D社（卸売事業者）の請求サイクルは以下の通りです：

```
月次締め処理の流れ:
1. 締め日: 毎月末日（当月1日〜末日までの取引を集計）
2. 請求書発行: 翌月5営業日
3. 支払期限: 締め日の翌月末（取引先タイプにより異なる）
   - 大口: 60日後（締め日から2ヶ月後の末日）
   - 中口: 45日後（締め日から1.5ヶ月後）
   - 小口: 30日後（締め日から1ヶ月後の末日）
```

**例**:
- 締め日: 2024年1月31日
- 請求書発行: 2024年2月7日（5営業日）
- 支払期限（大口）: 2024年3月31日（60日後）
- 支払期限（小口）: 2024年2月29日（30日後）

### 9.1.2 請求書生成サービス

```scala
package com.example.order.usecase

import com.example.order.domain._
import com.example.order.adapter.repository._
import zio._
import java.time.{LocalDate, YearMonth}
import java.util.UUID

class InvoiceGenerationService(
  orderRepository: OrderRepository,
  invoiceRepository: InvoiceRepository,
  invoiceActor: ActorRef[InvoiceActor.Command]
) {

  // 単一顧客の月次請求書を生成
  def generateMonthlyInvoice(
    customerId: CustomerId,
    billingYearMonth: YearMonth
  ): Task[Either[InvoiceGenerationError, InvoiceId]] = {
    for {
      // 対象月の配送完了済み注文を取得
      orders <- orderRepository.findDeliveredOrdersByMonth(customerId, billingYearMonth)
      result <- if (orders.isEmpty) {
        ZIO.succeed(Left(InvoiceGenerationError.NoOrdersFound(customerId, billingYearMonth)))
      } else {
        generateInvoice(customerId, billingYearMonth, orders)
      }
    } yield result
  }

  // 全顧客の月次請求書を一括生成
  def generateAllMonthlyInvoices(
    billingYearMonth: YearMonth
  ): Task[List[InvoiceGenerationResult]] = {
    for {
      // 対象月に配送完了した注文がある全顧客を取得
      customersWithOrders <- orderRepository.findCustomersWithDeliveredOrders(billingYearMonth)
      results <- ZIO.foreach(customersWithOrders) { customerId =>
        generateMonthlyInvoice(customerId, billingYearMonth).map { result =>
          InvoiceGenerationResult(customerId, billingYearMonth, result)
        }
      }
    } yield results
  }

  private def generateInvoice(
    customerId: CustomerId,
    billingYearMonth: YearMonth,
    orders: List[Order]
  ): Task[Either[InvoiceGenerationError, InvoiceId]] = {

    // 請求金額を集計
    val totalAmount = Money.sum(orders.map(_.totalAmount))
    val orderIds = orders.map(_.id)

    // 請求書ID生成
    val invoiceId = InvoiceId(UUID.randomUUID().toString)

    // 請求書発行日（翌月5営業日）
    val issueDate = calculateIssueDate(billingYearMonth)

    // 支払期限（顧客タイプに応じて計算）
    val dueDate = calculateDueDate(billingYearMonth, customerId)

    // InvoiceActorに請求書生成コマンドを送信
    val replyPromise = Promise.make[InvoiceActor.GenerateInvoiceReply]

    for {
      _ <- ZIO.succeed {
        invoiceActor ! InvoiceActor.GenerateInvoice(
          invoiceId = invoiceId,
          customerId = customerId,
          billingYearMonth = billingYearMonth,
          orderIds = orderIds,
          totalAmount = totalAmount,
          issueDate = issueDate,
          dueDate = dueDate,
          replyTo = context.messageAdapter { reply =>
            replyPromise.succeed(reply)
          }
        )
      }
      reply <- replyPromise.await.timeout(10.seconds).some.orElseFail(
        InvoiceGenerationError.Timeout(customerId, billingYearMonth)
      )
      result <- reply match {
        case InvoiceActor.InvoiceGeneratedReply(id) =>
          ZIO.succeed(Right(id))
        case InvoiceActor.GenerateInvoiceFailed(error) =>
          ZIO.succeed(Left(InvoiceGenerationError.ActorError(error)))
      }
    } yield result
  }

  // 請求書発行日を計算（翌月5営業日）
  private def calculateIssueDate(billingYearMonth: YearMonth): LocalDate = {
    val firstDayOfNextMonth = billingYearMonth.plusMonths(1).atDay(1)
    addBusinessDays(firstDayOfNextMonth, 5)
  }

  // 支払期限を計算
  private def calculateDueDate(billingYearMonth: YearMonth, customerId: CustomerId): Task[LocalDate] = {
    for {
      customer <- orderRepository.findCustomer(customerId)
      lastDayOfBillingMonth = billingYearMonth.atEndOfMonth()
      dueDate = lastDayOfBillingMonth.plusDays(customer.customerType.paymentTermDays)
    } yield dueDate
  }

  // 営業日を加算（土日祝日を除外）
  private def addBusinessDays(date: LocalDate, businessDays: Int): LocalDate = {
    var current = date
    var count = 0

    while (count < businessDays) {
      current = current.plusDays(1)
      // 土日でなければカウント（簡易実装、実際には祝日も考慮）
      if (current.getDayOfWeek.getValue < 6) {
        count += 1
      }
    }

    current
  }
}

// 請求書生成エラー
sealed trait InvoiceGenerationError

object InvoiceGenerationError {
  final case class NoOrdersFound(
    customerId: CustomerId,
    billingYearMonth: YearMonth
  ) extends InvoiceGenerationError

  final case class Timeout(
    customerId: CustomerId,
    billingYearMonth: YearMonth
  ) extends InvoiceGenerationError

  final case class ActorError(
    error: InvoiceError
  ) extends InvoiceGenerationError
}

// 請求書生成結果
final case class InvoiceGenerationResult(
  customerId: CustomerId,
  billingYearMonth: YearMonth,
  result: Either[InvoiceGenerationError, InvoiceId]
) {
  def isSuccess: Boolean = result.isRight
  def isFailure: Boolean = result.isLeft
}
```

### 9.1.3 バッチ処理による自動生成

月次締め処理は、スケジューラーによって自動実行されます。

```scala
package com.example.order.adapter.scheduler

import com.example.order.usecase.InvoiceGenerationService
import zio._
import java.time.{LocalDate, YearMonth}

class MonthlyInvoiceGenerationJob(
  invoiceGenerationService: InvoiceGenerationService
) {

  // 月次請求書生成ジョブ
  def run(): Task[JobResult] = {
    val now = LocalDate.now()
    val previousMonth = YearMonth.from(now.minusMonths(1))

    for {
      _ <- ZIO.logInfo(s"Starting monthly invoice generation for ${previousMonth}")
      results <- invoiceGenerationService.generateAllMonthlyInvoices(previousMonth)
      _ <- ZIO.logInfo(s"Monthly invoice generation completed: ${results.size} invoices")
      _ <- logResults(results)
    } yield JobResult(
      billingYearMonth = previousMonth,
      totalCustomers = results.size,
      successCount = results.count(_.isSuccess),
      failureCount = results.count(_.isFailure),
      results = results
    )
  }

  private def logResults(results: List[InvoiceGenerationResult]): Task[Unit] = {
    ZIO.foreach_(results) { result =>
      result.result match {
        case Right(invoiceId) =>
          ZIO.logInfo(s"Invoice generated: ${invoiceId.value} for customer ${result.customerId.value}")
        case Left(error) =>
          ZIO.logError(s"Failed to generate invoice for customer ${result.customerId.value}: $error")
      }
    }
  }
}

// ジョブ結果
final case class JobResult(
  billingYearMonth: YearMonth,
  totalCustomers: Int,
  successCount: Int,
  failureCount: Int,
  results: List[InvoiceGenerationResult]
)

// スケジューラー設定
class InvoiceScheduler(
  invoiceGenerationJob: MonthlyInvoiceGenerationJob
) {

  // 毎月1日の午前3時に実行
  def schedule(): ZSchedule[Any, Any, Any] = {
    Schedule.cron("0 3 1 * *")  // Cron形式: 分 時 日 月 曜日
  }

  def start(): Task[Unit] = {
    invoiceGenerationJob.run()
      .repeat(schedule())
      .fork
      .unit
  }
}
```

## 9.2 入金処理

### 9.2.1 入金記録

入金が確認されたら、InvoiceActorに入金記録コマンドを送信します。

```scala
// Payment値オブジェクト（第4章で定義済み）
final case class Payment(
  paymentId: PaymentId,
  amount: Money,
  paymentDate: LocalDate,
  paymentMethod: PaymentMethod,
  reference: Option[String] = None  // 振込番号等
) {
  require(amount.isPositive, "入金額はゼロより大きい必要があります")
}

final case class PaymentId(value: String) extends AnyVal
object PaymentId {
  def generate(): PaymentId = PaymentId(UUID.randomUUID().toString)
}

// 入金方法
sealed trait PaymentMethod {
  def description: String
}

object PaymentMethod {
  case object BankTransfer extends PaymentMethod {
    val description = "銀行振込"
  }

  case object Cash extends PaymentMethod {
    val description = "現金"
  }

  case object Check extends PaymentMethod {
    val description = "小切手"
  }

  case object CreditCard extends PaymentMethod {
    val description = "クレジットカード"
  }

  def fromString(str: String): Option[PaymentMethod] = str match {
    case "BankTransfer" => Some(BankTransfer)
    case "Cash" => Some(Cash)
    case "Check" => Some(Check)
    case "CreditCard" => Some(CreditCard)
    case _ => None
  }
}
```

### 9.2.2 入金処理サービス

```scala
package com.example.order.usecase

import com.example.order.domain._
import com.example.order.adapter.repository._
import zio._
import java.time.LocalDate

class PaymentProcessingService(
  invoiceRepository: InvoiceRepository,
  invoiceActor: ActorRef[InvoiceActor.Command],
  creditLimitActor: ActorRef[CreditLimitActor.Command]
) {

  // 入金を記録
  def recordPayment(
    invoiceId: InvoiceId,
    payment: Payment
  ): Task[Either[PaymentError, Unit]] = {
    for {
      // 請求書を取得
      invoice <- invoiceRepository.findById(invoiceId)
        .someOrFail(PaymentError.InvoiceNotFound(invoiceId))

      // 入金額が妥当か検証
      validation <- validatePayment(invoice, payment)

      result <- validation match {
        case Right(_) =>
          // InvoiceActorに入金記録コマンドを送信
          recordPaymentToActor(invoiceId, payment).flatMap {
            case Right(_) =>
              // 入金完了後、与信枠を解放
              handleCreditRelease(invoice, payment).map(_ => Right(()))
            case Left(error) =>
              ZIO.succeed(Left(error))
          }
        case Left(error) =>
          ZIO.succeed(Left(error))
      }
    } yield result
  }

  private def validatePayment(
    invoice: Invoice,
    payment: Payment
  ): Task[Either[PaymentError, Unit]] = {
    ZIO.succeed {
      if (invoice.status == InvoiceStatus.FullyPaid) {
        Left(PaymentError.InvoiceAlreadyFullyPaid(invoice.id))
      } else if (payment.amount > invoice.balanceAmount) {
        Left(PaymentError.OverPayment(
          invoiceId = invoice.id,
          balanceAmount = invoice.balanceAmount,
          paymentAmount = payment.amount,
          excessAmount = payment.amount - invoice.balanceAmount
        ))
      } else {
        Right(())
      }
    }
  }

  private def recordPaymentToActor(
    invoiceId: InvoiceId,
    payment: Payment
  ): Task[Either[PaymentError, Unit]] = {
    val replyPromise = Promise.make[InvoiceActor.RecordPaymentReply]

    for {
      _ <- ZIO.succeed {
        invoiceActor ! InvoiceActor.RecordPayment(
          payment = payment,
          replyTo = context.messageAdapter { reply =>
            replyPromise.succeed(reply)
          }
        )
      }
      reply <- replyPromise.await.timeout(10.seconds).some.orElseFail(
        PaymentError.Timeout(invoiceId)
      )
      result <- reply match {
        case InvoiceActor.PaymentRecordedReply =>
          ZIO.succeed(Right(()))
        case InvoiceActor.RecordPaymentFailed(error) =>
          ZIO.succeed(Left(PaymentError.ActorError(error)))
      }
    } yield result
  }

  // 与信枠の解放（使用済みに変更）
  private def handleCreditRelease(
    invoice: Invoice,
    payment: Payment
  ): Task[Unit] = {
    for {
      // 請求書の最新状態を取得
      updatedInvoice <- invoiceRepository.findById(invoice.id)
        .someOrFail(PaymentError.InvoiceNotFound(invoice.id))

      // 全額入金の場合、与信枠を使用済みに変更
      _ <- if (updatedInvoice.status == InvoiceStatus.FullyPaid) {
        ZIO.foreach_(updatedInvoice.orderIds) { orderId =>
          releaseCreditForOrder(orderId)
        }
      } else {
        ZIO.unit
      }
    } yield ()
  }

  private def releaseCreditForOrder(orderId: OrderId): Task[Unit] = {
    val replyPromise = Promise.make[CreditLimitActor.UseCreditReply]

    for {
      _ <- ZIO.succeed {
        creditLimitActor ! CreditLimitActor.UseCredit(
          orderId = orderId,
          replyTo = context.messageAdapter { reply =>
            replyPromise.succeed(reply)
          }
        )
      }
      reply <- replyPromise.await.timeout(10.seconds).some.orElse(ZIO.unit)
      _ <- reply match {
        case CreditLimitActor.CreditUsedReply =>
          ZIO.logInfo(s"Credit used for order ${orderId.value}")
        case CreditLimitActor.UseCreditFailed(error) =>
          ZIO.logError(s"Failed to use credit for order ${orderId.value}: $error")
      }
    } yield ()
  }
}

// 入金エラー
sealed trait PaymentError

object PaymentError {
  final case class InvoiceNotFound(invoiceId: InvoiceId) extends PaymentError

  final case class InvoiceAlreadyFullyPaid(invoiceId: InvoiceId) extends PaymentError

  final case class OverPayment(
    invoiceId: InvoiceId,
    balanceAmount: Money,
    paymentAmount: Money,
    excessAmount: Money
  ) extends PaymentError

  final case class Timeout(invoiceId: InvoiceId) extends PaymentError

  final case class ActorError(error: InvoiceError) extends PaymentError
}
```

### 9.2.3 入金不足・過入金の処理

入金額が請求額と一致しない場合の処理を実装します。

```scala
// Invoice集約での入金記録（第5章で実装済みを拡張）
final case class Invoice(
  id: InvoiceId,
  customerId: CustomerId,
  billingYearMonth: YearMonth,
  orderIds: List[OrderId],
  totalAmount: Money,
  paidAmount: Money,
  payments: List[Payment],
  issueDate: LocalDate,
  dueDate: LocalDate,
  status: InvoiceStatus,
  version: Version
) {

  // 残高
  def balanceAmount: Money = totalAmount - paidAmount

  // 支払期限を過ぎているか
  def isOverdue: Boolean = {
    LocalDate.now().isAfter(dueDate) && balanceAmount.amount > 0
  }

  // 入金を記録
  def recordPayment(payment: Payment): Either[InvoiceError, Invoice] = {
    // 完済済みチェック
    if (status == InvoiceStatus.FullyPaid) {
      return Left(InvoiceError.InvoiceAlreadyFullyPaid(id))
    }

    // 過入金チェック
    if (payment.amount > balanceAmount) {
      return Left(InvoiceError.PaymentExceedsBalance(id, balanceAmount, payment.amount))
    }

    // 入金後の状態を計算
    val newPaidAmount = paidAmount + payment.amount
    val newPayments = payments :+ payment
    val newStatus = determineStatus(newPaidAmount)

    Right(copy(
      paidAmount = newPaidAmount,
      payments = newPayments,
      status = newStatus,
      version = version.increment
    ))
  }

  private def determineStatus(paidAmount: Money): InvoiceStatus = {
    if (paidAmount >= totalAmount) {
      InvoiceStatus.FullyPaid  // 完済
    } else if (paidAmount > Money.zero(totalAmount.currency)) {
      InvoiceStatus.PartiallyPaid  // 一部入金
    } else {
      InvoiceStatus.Issued  // 未入金
    }
  }
}

// 請求書ステータス
sealed trait InvoiceStatus

object InvoiceStatus {
  case object Issued extends InvoiceStatus       // 発行済み（未入金）
  case object PartiallyPaid extends InvoiceStatus // 一部入金
  case object FullyPaid extends InvoiceStatus    // 完済
  case object Overdue extends InvoiceStatus      // 延滞

  def fromString(str: String): Option[InvoiceStatus] = str match {
    case "Issued" => Some(Issued)
    case "PartiallyPaid" => Some(PartiallyPaid)
    case "FullyPaid" => Some(FullyPaid)
    case "Overdue" => Some(Overdue)
    case _ => None
  }
}
```

### 9.2.4 過入金の振替処理

過入金が発生した場合、次月の請求書に振替えます。

```scala
package com.example.order.usecase

import com.example.order.domain._
import zio._

class OverPaymentHandlingService(
  invoiceRepository: InvoiceRepository,
  customerRepository: CustomerRepository
) {

  // 過入金を処理
  def handleOverPayment(
    invoiceId: InvoiceId,
    excessAmount: Money
  ): Task[OverPaymentResult] = {
    for {
      invoice <- invoiceRepository.findById(invoiceId)
        .someOrFail(new RuntimeException(s"Invoice not found: ${invoiceId.value}"))

      // 次月の請求書を検索
      nextMonth = invoice.billingYearMonth.plusMonths(1)
      nextInvoice <- invoiceRepository.findByCustomerAndMonth(
        invoice.customerId,
        nextMonth
      )

      result <- nextInvoice match {
        case Some(next) =>
          // 次月の請求書が既にある場合、振替処理
          applyOverPaymentToNextInvoice(next, excessAmount)
        case None =>
          // 次月の請求書がまだない場合、前受金として記録
          recordAdvancePayment(invoice.customerId, excessAmount)
      }
    } yield result
  }

  private def applyOverPaymentToNextInvoice(
    nextInvoice: Invoice,
    excessAmount: Money
  ): Task[OverPaymentResult] = {
    // 次月請求書に自動入金として記録
    val autoPayment = Payment(
      paymentId = PaymentId.generate(),
      amount = excessAmount,
      paymentDate = LocalDate.now(),
      paymentMethod = PaymentMethod.BankTransfer,
      reference = Some(s"前月過入金振替")
    )

    for {
      _ <- ZIO.logInfo(s"Applying over-payment ${excessAmount} to next invoice ${nextInvoice.id.value}")
      // 入金記録サービスを使用して記録
      // （実際にはPaymentProcessingServiceを呼び出す）
    } yield OverPaymentResult.AppliedToNextInvoice(
      nextInvoiceId = nextInvoice.id,
      amount = excessAmount
    )
  }

  private def recordAdvancePayment(
    customerId: CustomerId,
    amount: Money
  ): Task[OverPaymentResult] = {
    // 前受金として記録
    val advancePayment = AdvancePayment(
      customerId = customerId,
      amount = amount,
      recordedAt = LocalDate.now()
    )

    for {
      _ <- ZIO.logInfo(s"Recording advance payment ${amount} for customer ${customerId.value}")
      // 前受金リポジトリに保存
      // （実際にはAdvancePaymentRepositoryを使用）
    } yield OverPaymentResult.RecordedAsAdvance(
      customerId = customerId,
      amount = amount
    )
  }
}

// 過入金結果
sealed trait OverPaymentResult

object OverPaymentResult {
  final case class AppliedToNextInvoice(
    nextInvoiceId: InvoiceId,
    amount: Money
  ) extends OverPaymentResult

  final case class RecordedAsAdvance(
    customerId: CustomerId,
    amount: Money
  ) extends OverPaymentResult
}

// 前受金
final case class AdvancePayment(
  customerId: CustomerId,
  amount: Money,
  recordedAt: LocalDate
)
```

## 9.3 入金催促

### 9.3.1 未入金アラート

支払期限に基づいてリマインダーと督促を自動送信します。

```scala
package com.example.order.usecase

import com.example.order.domain._
import com.example.order.adapter.notification._
import zio._
import java.time.LocalDate

class PaymentReminderService(
  invoiceRepository: InvoiceRepository,
  emailService: EmailService
) {

  // 入金リマインダーを送信
  def sendPaymentReminders(): Task[List[ReminderResult]] = {
    val today = LocalDate.now()

    for {
      // 支払期限が近い請求書を取得
      upcomingInvoices <- invoiceRepository.findUnpaidWithUpcomingDueDate(today)
      results <- ZIO.foreach(upcomingInvoices) { invoice =>
        sendReminder(invoice, today)
      }
    } yield results
  }

  private def sendReminder(
    invoice: Invoice,
    today: LocalDate
  ): Task[ReminderResult] = {
    val daysUntilDue = java.time.temporal.ChronoUnit.DAYS.between(today, invoice.dueDate).toInt

    val reminderType = determineReminderType(daysUntilDue, invoice.isOverdue)

    for {
      customer <- invoiceRepository.findCustomer(invoice.customerId)
      _ <- sendReminderEmail(invoice, customer, reminderType, daysUntilDue)
      _ <- recordReminderSent(invoice.id, reminderType)
    } yield ReminderResult(
      invoiceId = invoice.id,
      customerId = invoice.customerId,
      reminderType = reminderType,
      sentAt = LocalDate.now()
    )
  }

  private def determineReminderType(
    daysUntilDue: Int,
    isOverdue: Boolean
  ): ReminderType = {
    if (isOverdue) {
      if (daysUntilDue <= -30) {
        ReminderType.FinalNotice  // 30日以上延滞
      } else if (daysUntilDue <= -7) {
        ReminderType.SecondReminder  // 7日以上延滞
      } else {
        ReminderType.FirstReminder  // 7日未満の延滞
      }
    } else {
      if (daysUntilDue <= 3) {
        ReminderType.DueDateApproaching  // 期限3日前
      } else if (daysUntilDue <= 7) {
        ReminderType.DueDateReminder  // 期限7日前
      } else {
        ReminderType.DueDateNotice  // 期限14日前
      }
    }
  }

  private def sendReminderEmail(
    invoice: Invoice,
    customer: Customer,
    reminderType: ReminderType,
    daysUntilDue: Int
  ): Task[Unit] = {
    val subject = reminderType match {
      case ReminderType.DueDateNotice => s"【ご案内】お支払期限のお知らせ（請求書番号: ${invoice.id.value}）"
      case ReminderType.DueDateApproaching => s"【重要】お支払期限が近づいています（請求書番号: ${invoice.id.value}）"
      case ReminderType.FirstReminder => s"【未入金】お支払期限が過ぎています（請求書番号: ${invoice.id.value}）"
      case ReminderType.SecondReminder => s"【督促】お支払いのご確認をお願いします（請求書番号: ${invoice.id.value}）"
      case ReminderType.FinalNotice => s"【最終通知】お支払いについて至急ご連絡ください（請求書番号: ${invoice.id.value}）"
      case ReminderType.DueDateReminder => s"【リマインド】お支払期限のご確認（請求書番号: ${invoice.id.value}）"
    }

    val body = createReminderEmailBody(invoice, customer, reminderType, daysUntilDue)

    emailService.send(
      to = customer.email,
      cc = Some("accounting@example.com"),
      subject = subject,
      body = body
    )
  }

  private def createReminderEmailBody(
    invoice: Invoice,
    customer: Customer,
    reminderType: ReminderType,
    daysUntilDue: Int
  ): String = {
    val greeting = s"${customer.name} 様"

    val message = reminderType match {
      case ReminderType.DueDateNotice =>
        s"お支払期限（${invoice.dueDate}）が${math.abs(daysUntilDue)}日後に迫っております。"
      case ReminderType.DueDateApproaching =>
        s"お支払期限（${invoice.dueDate}）が${math.abs(daysUntilDue)}日後です。"
      case ReminderType.FirstReminder =>
        s"お支払期限（${invoice.dueDate}）が${math.abs(daysUntilDue)}日経過しております。"
      case ReminderType.SecondReminder =>
        s"お支払期限（${invoice.dueDate}）から${math.abs(daysUntilDue)}日が経過しておりますが、入金が確認できておりません。"
      case ReminderType.FinalNotice =>
        s"お支払期限（${invoice.dueDate}）から${math.abs(daysUntilDue)}日が経過しておりますが、未だ入金が確認できておりません。至急ご対応をお願いいたします。"
      case ReminderType.DueDateReminder =>
        s"お支払期限（${invoice.dueDate}）が${math.abs(daysUntilDue)}日後です。ご確認をお願いいたします。"
    }

    s"""
      |$greeting
      |
      |いつもお世話になっております。
      |
      |$message
      |
      |【請求書情報】
      |請求書番号: ${invoice.id.value}
      |請求対象月: ${invoice.billingYearMonth}
      |請求金額: ${invoice.totalAmount.formatted}
      |お支払済み金額: ${invoice.paidAmount.formatted}
      |残高: ${invoice.balanceAmount.formatted}
      |お支払期限: ${invoice.dueDate}
      |
      |既にお振込み済みの場合は、本メールは行き違いとなりますので、ご容赦ください。
      |
      |ご不明な点がございましたら、お気軽にお問い合わせください。
      |
      |今後ともよろしくお願いいたします。
      |
      |--
      |株式会社D社
      |経理部
      |Tel: 03-XXXX-XXXX
      |Email: accounting@example.com
      |""".stripMargin
  }

  private def recordReminderSent(
    invoiceId: InvoiceId,
    reminderType: ReminderType
  ): Task[Unit] = {
    // InvoiceActorにリマインダー送信イベントを記録
    val replyPromise = Promise.make[InvoiceActor.RemindPaymentReply]

    for {
      _ <- ZIO.succeed {
        invoiceActor ! InvoiceActor.RemindPayment(
          replyTo = context.messageAdapter { reply =>
            replyPromise.succeed(reply)
          }
        )
      }
      _ <- replyPromise.await.timeout(10.seconds).some.orElse(ZIO.unit)
    } yield ()
  }
}

// リマインダータイプ
sealed trait ReminderType

object ReminderType {
  case object DueDateNotice extends ReminderType        // 期限14日前の案内
  case object DueDateReminder extends ReminderType      // 期限7日前のリマインダー
  case object DueDateApproaching extends ReminderType   // 期限3日前の重要通知
  case object FirstReminder extends ReminderType        // 期限経過後の1回目督促
  case object SecondReminder extends ReminderType       // 期限経過7日後の2回目督促
  case object FinalNotice extends ReminderType          // 期限経過30日後の最終通知
}

// リマインダー結果
final case class ReminderResult(
  invoiceId: InvoiceId,
  customerId: CustomerId,
  reminderType: ReminderType,
  sentAt: LocalDate
)
```

### 9.3.2 スケジューラーによる自動実行

入金リマインダーを毎日自動実行します。

```scala
package com.example.order.adapter.scheduler

import com.example.order.usecase.PaymentReminderService
import zio._

class PaymentReminderJob(
  paymentReminderService: PaymentReminderService
) {

  // 入金リマインダージョブ
  def run(): Task[ReminderJobResult] = {
    for {
      _ <- ZIO.logInfo("Starting payment reminder job")
      results <- paymentReminderService.sendPaymentReminders()
      _ <- ZIO.logInfo(s"Payment reminder job completed: ${results.size} reminders sent")
      _ <- logResults(results)
    } yield ReminderJobResult(
      totalReminders = results.size,
      results = results
    )
  }

  private def logResults(results: List[ReminderResult]): Task[Unit] = {
    ZIO.foreach_(results) { result =>
      ZIO.logInfo(
        s"Reminder sent: ${result.reminderType} for invoice ${result.invoiceId.value}, customer ${result.customerId.value}"
      )
    }
  }
}

// ジョブ結果
final case class ReminderJobResult(
  totalReminders: Int,
  results: List[ReminderResult]
)

// スケジューラー設定
class PaymentReminderScheduler(
  paymentReminderJob: PaymentReminderJob
) {

  // 毎日午前9時に実行
  def schedule(): ZSchedule[Any, Any, Any] = {
    Schedule.cron("0 9 * * *")  // Cron形式: 分 時 日 月 曜日
  }

  def start(): Task[Unit] = {
    paymentReminderJob.run()
      .repeat(schedule())
      .fork
      .unit
  }
}
```

## 9.4 請求管理のモニタリング

### 9.4.1 請求ダッシュボード

請求状況を可視化するダッシュボードを提供します。

```scala
package com.example.order.usecase

import com.example.order.domain._
import zio._
import java.time.YearMonth

class BillingMonitoringService(
  invoiceRepository: InvoiceRepository
) {

  // 請求サマリーを取得
  def getBillingSummary(yearMonth: YearMonth): Task[BillingSummary] = {
    for {
      allInvoices <- invoiceRepository.findByMonth(yearMonth)
    } yield {
      val totalInvoices = allInvoices.size
      val totalBilledAmount = Money.sum(allInvoices.map(_.totalAmount))
      val totalPaidAmount = Money.sum(allInvoices.map(_.paidAmount))
      val totalUnpaidAmount = Money.sum(allInvoices.map(_.balanceAmount))

      val fullyPaidCount = allInvoices.count(_.status == InvoiceStatus.FullyPaid)
      val partiallyPaidCount = allInvoices.count(_.status == InvoiceStatus.PartiallyPaid)
      val unpaidCount = allInvoices.count(_.status == InvoiceStatus.Issued)
      val overdueCount = allInvoices.count(_.isOverdue)

      BillingSummary(
        yearMonth = yearMonth,
        totalInvoices = totalInvoices,
        totalBilledAmount = totalBilledAmount,
        totalPaidAmount = totalPaidAmount,
        totalUnpaidAmount = totalUnpaidAmount,
        fullyPaidCount = fullyPaidCount,
        partiallyPaidCount = partiallyPaidCount,
        unpaidCount = unpaidCount,
        overdueCount = overdueCount,
        collectionRate = calculateCollectionRate(totalBilledAmount, totalPaidAmount)
      )
    }
  }

  // 延滞請求書を取得
  def getOverdueInvoices(): Task[List[Invoice]] = {
    invoiceRepository.findOverdue()
  }

  // 顧客別の請求状況を取得
  def getCustomerBillingStatus(customerId: CustomerId): Task[CustomerBillingStatus] = {
    for {
      allInvoices <- invoiceRepository.findByCustomer(customerId)
      overdueInvoices = allInvoices.filter(_.isOverdue)
    } yield {
      CustomerBillingStatus(
        customerId = customerId,
        totalInvoices = allInvoices.size,
        totalBilledAmount = Money.sum(allInvoices.map(_.totalAmount)),
        totalPaidAmount = Money.sum(allInvoices.map(_.paidAmount)),
        totalUnpaidAmount = Money.sum(allInvoices.map(_.balanceAmount)),
        overdueInvoices = overdueInvoices.size,
        oldestOverdueDays = overdueInvoices.map { inv =>
          java.time.temporal.ChronoUnit.DAYS.between(inv.dueDate, LocalDate.now()).toInt
        }.maxOption.getOrElse(0)
      )
    }
  }

  private def calculateCollectionRate(billed: Money, paid: Money): BigDecimal = {
    if (billed.isZero) BigDecimal(0)
    else (paid.amount / billed.amount) * 100
  }
}

// 請求サマリー
final case class BillingSummary(
  yearMonth: YearMonth,
  totalInvoices: Int,
  totalBilledAmount: Money,
  totalPaidAmount: Money,
  totalUnpaidAmount: Money,
  fullyPaidCount: Int,
  partiallyPaidCount: Int,
  unpaidCount: Int,
  overdueCount: Int,
  collectionRate: BigDecimal  // 回収率（%）
)

// 顧客別請求状況
final case class CustomerBillingStatus(
  customerId: CustomerId,
  totalInvoices: Int,
  totalBilledAmount: Money,
  totalPaidAmount: Money,
  totalUnpaidAmount: Money,
  overdueInvoices: Int,
  oldestOverdueDays: Int  // 最も古い延滞日数
)
```

## 9.5 まとめ

本章では、請求管理の実装について詳しく説明しました。

**実装のポイント**:

1. **月次締め処理**: 配送完了した注文を集計し、請求書を自動生成
2. **スケジューラー**: 毎月1日午前3時に自動実行（バッチ処理）
3. **入金処理**: 入金記録と照合、与信枠の解放（使用済みに変更）
4. **入金不足・過入金**: 一部入金の繰越、過入金の次月振替
5. **入金催促**: 支払期限に基づく自動リマインダー（期限前・期限後）
6. **モニタリング**: 請求サマリー、延滞状況、回収率の可視化

**請求管理のフロー**:
```
1. 月次締め（毎月1日） → 請求書自動生成
2. 請求書発行（翌月5営業日） → 顧客へ送付
3. 支払期限前（7日前、3日前） → リマインダー送信
4. 入金確認 → 入金記録 → 与信枠解放
5. 支払期限後（延滞時） → 督促（1回目、2回目、最終通知）
```

**次章では**（outline.mdに従えば）:
- 第10章: 返品処理の実装（返品受付、在庫への戻し入れ、返品金額処理）

請求管理により、売上を確実に回収し、キャッシュフローを健全に保つことができます。自動化された月次締めと入金催促により、経理業務の効率化と回収率の向上を実現します。