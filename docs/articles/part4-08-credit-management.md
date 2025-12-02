# 【第4部 第8章】与信管理の実装：動的調整とリスク管理

## 本章の目的

与信管理は、卸売事業において重要なリスク管理機能です。適切な与信限度額の設定と管理により、売上機会を最大化しつつ、貸し倒れリスクを最小化します。本章では、取引先タイプ別の与信限度額設定、取引実績に基づく動的調整、与信チェックプロセス、与信枠の引当と解放について詳しく説明します。

## 8.1 与信限度額の設定

### 8.1.1 取引先タイプ別の与信限度額

D社（卸売事業者）では、取引先を3つのタイプに分類し、それぞれに異なる与信限度額を設定しています。

```scala
package com.example.order.domain

// 取引先タイプ
sealed trait CustomerType {
  def description: String
  def defaultCreditLimit: Money
  def paymentTermDays: Int  // 支払期限日数
}

object CustomerType {
  // 大口取引先（年間取引額1億円以上）
  case object Large extends CustomerType {
    val description = "大口"
    val defaultCreditLimit = Money.jpy(30000000)  // 3,000万円
    val paymentTermDays = 60  // 60日後払い
  }

  // 中口取引先（年間取引額1,000万円〜1億円）
  case object Medium extends CustomerType {
    val description = "中口"
    val defaultCreditLimit = Money.jpy(5000000)  // 500万円
    val paymentTermDays = 45  // 45日後払い
  }

  // 小口取引先（年間取引額1,000万円未満）
  case object Small extends CustomerType {
    val description = "小口"
    val defaultCreditLimit = Money.jpy(1000000)  // 100万円
    val paymentTermDays = 30  // 30日後払い
  }

  def fromString(str: String): Option[CustomerType] = str match {
    case "Large" => Some(Large)
    case "Medium" => Some(Medium)
    case "Small" => Some(Small)
    case _ => None
  }
}
```

### 8.1.2 与信限度額の動的調整

与信限度額は、取引実績に基づいて動的に調整されます。

```scala
// 与信調整ポリシー
final case class CreditAdjustmentPolicy(
  // 増額条件
  increaseThresholds: List[CreditIncreaseThreshold],
  // 減額条件
  decreaseConditions: List[CreditDecreaseCondition],
  // 調整間隔
  adjustmentIntervalMonths: Int = 6
)

// 増額閾値
final case class CreditIncreaseThreshold(
  // 連続X ヶ月の良好な取引実績
  consecutiveGoodMonths: Int,
  // 最低取引額（月平均）
  minimumAverageMonthlyAmount: Money,
  // 増額率
  increaseRate: BigDecimal
) {
  require(increaseRate > 0 && increaseRate <= 1, "増額率は0より大きく1以下でなければなりません")
}

// 減額条件
sealed trait CreditDecreaseCondition {
  def shouldDecrease(history: CreditHistory): Boolean
  def decreaseRate: BigDecimal
}

object CreditDecreaseCondition {
  // 延滞発生時
  final case class PaymentDelay(
    delayDays: Int,
    decreaseRate: BigDecimal = BigDecimal("0.20")  // 20%減額
  ) extends CreditDecreaseCondition {
    def shouldDecrease(history: CreditHistory): Boolean = {
      history.hasPaymentDelay(delayDays)
    }
  }

  // 取引額減少時
  final case class DecreasingTransactions(
    consecutiveDecreaseMonths: Int,
    decreaseRate: BigDecimal = BigDecimal("0.10")  // 10%減額
  ) extends CreditDecreaseCondition {
    def shouldDecrease(history: CreditHistory): Boolean = {
      history.hasDecreasingTransactions(consecutiveDecreaseMonths)
    }
  }

  // 与信使用率が常に低い場合
  final case class LowUtilization(
    averageUtilizationRate: BigDecimal,  // 例: 0.3（30%未満）
    consecutiveMonths: Int,
    decreaseRate: BigDecimal = BigDecimal("0.15")  // 15%減額
  ) extends CreditDecreaseCondition {
    def shouldDecrease(history: CreditHistory): Boolean = {
      history.hasLowUtilization(averageUtilizationRate, consecutiveMonths)
    }
  }
}

// 与信履歴
final case class CreditHistory(
  customerId: CustomerId,
  monthlyRecords: List[MonthlyCreditRecord]
) {

  def hasPaymentDelay(delayDays: Int): Boolean = {
    monthlyRecords.exists(_.maxPaymentDelayDays >= delayDays)
  }

  def hasDecreasingTransactions(consecutiveMonths: Int): Boolean = {
    val recentRecords = monthlyRecords.takeRight(consecutiveMonths)
    if (recentRecords.size < consecutiveMonths) return false

    recentRecords.sliding(2).forall { case List(prev, current) =>
      current.totalTransactionAmount < prev.totalTransactionAmount
    }
  }

  def hasLowUtilization(threshold: BigDecimal, consecutiveMonths: Int): Boolean = {
    val recentRecords = monthlyRecords.takeRight(consecutiveMonths)
    if (recentRecords.size < consecutiveMonths) return false

    recentRecords.forall(_.averageUtilizationRate < threshold)
  }

  def consecutiveGoodMonths: Int = {
    monthlyRecords.reverse.takeWhile(_.isGoodRecord).size
  }

  def averageMonthlyTransactionAmount: Money = {
    if (monthlyRecords.isEmpty) return Money.zero()

    val total = Money.sum(monthlyRecords.map(_.totalTransactionAmount))
    total / monthlyRecords.size
  }
}

// 月次与信記録
final case class MonthlyCreditRecord(
  yearMonth: YearMonth,
  totalTransactionAmount: Money,  // 取引総額
  averageUtilizationRate: BigDecimal,  // 与信使用率（平均）
  maxPaymentDelayDays: Int,  // 最大延滞日数
  onTimePaymentCount: Int,  // 期限内支払い回数
  totalPaymentCount: Int  // 総支払い回数
) {

  def onTimePaymentRate: BigDecimal = {
    if (totalPaymentCount == 0) BigDecimal(1)
    else BigDecimal(onTimePaymentCount) / BigDecimal(totalPaymentCount)
  }

  def isGoodRecord: Boolean = {
    maxPaymentDelayDays == 0 && onTimePaymentRate >= BigDecimal("0.95")
  }
}
```

### 8.1.3 与信限度額調整サービス

```scala
package com.example.order.usecase

import com.example.order.domain._
import com.example.order.domain.saga._
import zio._
import java.time.YearMonth

class CreditAdjustmentService(
  creditLimitRepository: CreditLimitRepository,
  creditHistoryRepository: CreditHistoryRepository,
  policy: CreditAdjustmentPolicy
) {

  // 与信限度額の自動調整
  def adjustCreditLimit(customerId: CustomerId): Task[CreditAdjustmentResult] = {
    for {
      creditLimit <- creditLimitRepository.findByCustomer(customerId)
      history <- creditHistoryRepository.findByCustomer(customerId)
      result <- evaluateAndAdjust(creditLimit, history)
    } yield result
  }

  // 全顧客の与信限度額を定期的に調整
  def adjustAllCreditLimits(): Task[List[CreditAdjustmentResult]] = {
    for {
      allCustomers <- creditLimitRepository.findAll()
      results <- ZIO.foreach(allCustomers) { creditLimit =>
        adjustCreditLimit(creditLimit.customerId)
      }
    } yield results
  }

  private def evaluateAndAdjust(
    creditLimit: CreditLimit,
    history: CreditHistory
  ): Task[CreditAdjustmentResult] = {

    // 減額条件のチェック
    val decreaseCondition = policy.decreaseConditions.find(_.shouldDecrease(history))

    decreaseCondition match {
      case Some(condition) =>
        // 減額が必要
        val newLimitAmount = (creditLimit.limitAmount * (BigDecimal(1) - condition.decreaseRate))
          .roundToDefaultScale()
        val reason = s"与信減額: ${condition.getClass.getSimpleName}"

        for {
          _ <- creditLimitRepository.adjustLimit(creditLimit.customerId, newLimitAmount, reason)
        } yield CreditAdjustmentResult.Decreased(
          customerId = creditLimit.customerId,
          oldLimit = creditLimit.limitAmount,
          newLimit = newLimitAmount,
          reason = reason
        )

      case None =>
        // 増額条件のチェック
        val increaseThreshold = policy.increaseThresholds.find { threshold =>
          history.consecutiveGoodMonths >= threshold.consecutiveGoodMonths &&
          history.averageMonthlyTransactionAmount >= threshold.minimumAverageMonthlyAmount
        }

        increaseThreshold match {
          case Some(threshold) =>
            // 増額が可能
            val newLimitAmount = (creditLimit.limitAmount * (BigDecimal(1) + threshold.increaseRate))
              .roundToDefaultScale()
            val reason = s"与信増額: ${threshold.consecutiveGoodMonths}ヶ月連続の良好な取引実績"

            for {
              _ <- creditLimitRepository.adjustLimit(creditLimit.customerId, newLimitAmount, reason)
            } yield CreditAdjustmentResult.Increased(
              customerId = creditLimit.customerId,
              oldLimit = creditLimit.limitAmount,
              newLimit = newLimitAmount,
              reason = reason
            )

          case None =>
            // 調整不要
            ZIO.succeed(CreditAdjustmentResult.NoChange(
              customerId = creditLimit.customerId,
              currentLimit = creditLimit.limitAmount
            ))
        }
    }
  }
}

// 調整結果
sealed trait CreditAdjustmentResult {
  def customerId: CustomerId
}

object CreditAdjustmentResult {
  final case class Increased(
    customerId: CustomerId,
    oldLimit: Money,
    newLimit: Money,
    reason: String
  ) extends CreditAdjustmentResult

  final case class Decreased(
    customerId: CustomerId,
    oldLimit: Money,
    newLimit: Money,
    reason: String
  ) extends CreditAdjustmentResult

  final case class NoChange(
    customerId: CustomerId,
    currentLimit: Money
  ) extends CreditAdjustmentResult
}
```

## 8.2 与信チェックプロセス

### 8.2.1 与信チェックサービス

注文時に与信枠の可用性をチェックします。

```scala
package com.example.order.usecase

import com.example.order.domain._
import zio._
import java.time.LocalDate

class CreditCheckService(
  creditLimitRepository: CreditLimitRepository,
  orderRepository: OrderRepository
) {

  // 与信チェック
  def checkCredit(
    customerId: CustomerId,
    orderAmount: Money
  ): Task[Either[CreditError, CreditApproval]] = {
    for {
      creditLimit <- creditLimitRepository.findByCustomer(customerId)
      currentUsage <- calculateCurrentUsage(customerId)
      result <- performCheck(creditLimit, currentUsage, orderAmount)
    } yield result
  }

  private def performCheck(
    creditLimit: CreditLimit,
    currentUsage: Money,
    orderAmount: Money
  ): Task[Either[CreditError, CreditApproval]] = {

    val reservedAmount = creditLimit.reservedAmount
    val availableAmount = creditLimit.limitAmount - currentUsage - reservedAmount

    if (availableAmount >= orderAmount) {
      ZIO.succeed(Right(CreditApproval(
        customerId = creditLimit.customerId,
        approvedAmount = orderAmount,
        availableAmount = availableAmount,
        approvedAt = LocalDate.now()
      )))
    } else {
      ZIO.succeed(Left(CreditError.InsufficientCredit(
        customerId = creditLimit.customerId,
        available = availableAmount,
        required = orderAmount,
        shortage = orderAmount - availableAmount
      )))
    }
  }

  // 現在の与信使用額を計算
  private def calculateCurrentUsage(customerId: CustomerId): Task[Money] = {
    for {
      // 確定済み・出荷済みだが未入金の注文を取得
      unpaidOrders <- orderRepository.findUnpaidOrders(customerId)
    } yield {
      Money.sum(unpaidOrders.map(_.totalAmount))
    }
  }

  // 与信使用率を計算
  def calculateUtilizationRate(customerId: CustomerId): Task[BigDecimal] = {
    for {
      creditLimit <- creditLimitRepository.findByCustomer(customerId)
      currentUsage <- calculateCurrentUsage(customerId)
    } yield {
      if (creditLimit.limitAmount.isZero) {
        BigDecimal(0)
      } else {
        (currentUsage.amount + creditLimit.reservedAmount.amount) / creditLimit.limitAmount.amount
      }
    }
  }

  // 与信超過アラートをチェック
  def checkCreditAlert(
    customerId: CustomerId,
    warningThreshold: BigDecimal = BigDecimal("0.80")  // 80%で警告
  ): Task[Option[CreditAlert]] = {
    for {
      utilizationRate <- calculateUtilizationRate(customerId)
      creditLimit <- creditLimitRepository.findByCustomer(customerId)
    } yield {
      if (utilizationRate >= warningThreshold) {
        Some(CreditAlert(
          customerId = customerId,
          utilizationRate = utilizationRate,
          currentLimit = creditLimit.limitAmount,
          usedAmount = creditLimit.usedAmount,
          reservedAmount = creditLimit.reservedAmount,
          severity = if (utilizationRate >= BigDecimal("0.95")) AlertSeverity.Critical else AlertSeverity.Warning
        ))
      } else {
        None
      }
    }
  }
}

// 与信承認
final case class CreditApproval(
  customerId: CustomerId,
  approvedAmount: Money,
  availableAmount: Money,
  approvedAt: LocalDate
)

// 与信エラー
sealed trait CreditError

object CreditError {
  final case class InsufficientCredit(
    customerId: CustomerId,
    available: Money,
    required: Money,
    shortage: Money
  ) extends CreditError

  final case class CreditAlreadyReserved(
    customerId: CustomerId,
    orderId: OrderId
  ) extends CreditError

  final case class CreditExceeded(
    customerId: CustomerId,
    availableAmount: Money,
    requestedAmount: Money
  ) extends CreditError

  final case class CreditLimitNotFound(
    customerId: CustomerId
  ) extends CreditError

  final case class InvalidLimitAmount(
    amount: Money
  ) extends CreditError

  case object CreditLimitAlreadyExists extends CreditError
}

// 与信アラート
final case class CreditAlert(
  customerId: CustomerId,
  utilizationRate: BigDecimal,
  currentLimit: Money,
  usedAmount: Money,
  reservedAmount: Money,
  severity: AlertSeverity
)

sealed trait AlertSeverity
object AlertSeverity {
  case object Warning extends AlertSeverity  // 警告（80%以上）
  case object Critical extends AlertSeverity  // 重大（95%以上）
}
```

### 8.2.2 与信チェックの統合

Sagaオーケストレーターに与信チェックを統合します。

```scala
// OrderSagaOrchestratorでの与信チェック統合
private def handleStockReservedNotification(
  state: ActiveState,
  orderId: OrderId,
  reservations: List[StockReservation]
): ReplyEffect[Event, State] = {

  Effect
    .persist(StepCompleted(SagaStep.StockReserved))
    .thenRun { _ =>
      // ステップ3: 与信チェック
      val replyAdapter = context.messageAdapter[CreditCheckService.CheckCreditReply] {
        case CreditCheckService.CreditApproved(approval) =>
          CreditApprovedNotification(orderId, approval)
        case CreditCheckService.CreditCheckFailed(error) =>
          CreditCheckFailed(orderId, error.toString)
      }

      // 与信チェックサービスを呼び出し
      creditCheckService.checkCredit(
        customerId = state.saga.customerId,
        orderAmount = calculateOrderAmount(orderId)  // 注文金額を計算
      ).map {
        case Right(approval) =>
          replyAdapter ! CreditCheckService.CreditApproved(approval)
        case Left(error) =>
          replyAdapter ! CreditCheckService.CreditCheckFailed(error)
      }

      timers.startSingleTimer(
        StepTimeout(SagaStep.CreditApproved),
        creditCheckTimeout
      )
    }
}
```

## 8.3 与信枠の引当と解放

### 8.3.1 与信枠の引当

注文時に与信枠を引き当て、二重引当を防止します。

```scala
// CreditLimitアクターでの引当処理（第5章で実装済み）
private def handleReserveCredit(
  state: ActiveState,
  cmd: ReserveCredit
): ReplyEffect[Event, State] = {

  // 重複チェック: 既に同じOrderIdで引当済みか確認
  if (state.creditLimit.reservations.contains(cmd.orderId)) {
    context.log.warn(s"Credit already reserved for order ${cmd.orderId.value}")
    return Effect.reply(cmd.replyTo)(CreditReservedReply)  // べき等性を保つ
  }

  state.creditLimit.reserve(cmd.orderId, cmd.amount) match {
    case Right(_) =>
      Effect
        .persist(CreditReserved(cmd.orderId, cmd.amount))
        .thenReply(cmd.replyTo)(_ => CreditReservedReply)

    case Left(error) =>
      Effect.reply(cmd.replyTo)(ReserveCreditFailed(error))
  }
}
```

### 8.3.2 与信枠の解放

注文キャンセル時や入金確認時に与信枠を解放します。

```scala
// 注文キャンセル時の与信枠解放
private def handleCancelOrder(
  state: ActiveState,
  cmd: CancelOrder
): ReplyEffect[Event, State] = {

  state.order.cancel(cmd.reason) match {
    case Right(_) =>
      Effect
        .persist(OrderCancelled(cmd.reason, Instant.now()))
        .thenRun { _ =>
          // 与信枠を解放
          val replyAdapter = context.messageAdapter[CreditLimitActor.ReleaseCreditReply] {
            case CreditLimitActor.CreditReleasedReply =>
              context.log.info(s"Credit released for cancelled order ${state.order.id.value}")
              // 成功時の処理
            case CreditLimitActor.ReleaseCreditFailed(error) =>
              context.log.error(s"Failed to release credit for order ${state.order.id.value}: $error")
              // 失敗時の処理（リトライ、アラート等）
          }

          creditLimitActor ! CreditLimitActor.ReleaseCredit(
            orderId = state.order.id,
            replyTo = replyAdapter
          )
        }
        .thenReply(cmd.replyTo)(_ => OrderCancelledReply)

    case Left(error) =>
      Effect.reply(cmd.replyTo)(CancelOrderFailed(error))
  }
}

// 入金確認時の与信枠解放（使用済みに変更）
private def handleRecordPayment(
  state: ActiveState,
  cmd: RecordPayment
): ReplyEffect[Event, State] = {

  state.invoice.recordPayment(cmd.payment) match {
    case Right(_) =>
      Effect
        .persist(PaymentRecorded(cmd.payment))
        .thenRun { updatedState =>
          // 全額入金の場合、与信枠を解放（使用済みに変更）
          if (updatedState.invoice.status == InvoiceStatus.FullyPaid) {
            state.invoice.orderIds.foreach { orderId =>
              val replyAdapter = context.messageAdapter[CreditLimitActor.UseCreditReply] {
                case CreditLimitActor.CreditUsedReply =>
                  context.log.info(s"Credit used for paid order ${orderId.value}")
                case CreditLimitActor.UseCreditFailed(error) =>
                  context.log.error(s"Failed to use credit for order ${orderId.value}: $error")
              }

              creditLimitActor ! CreditLimitActor.UseCredit(
                orderId = orderId,
                replyTo = replyAdapter
              )
            }
          }
        }
        .thenReply(cmd.replyTo)(_ => PaymentRecordedReply)

    case Left(error) =>
      Effect.reply(cmd.replyTo)(RecordPaymentFailed(error))
  }
}
```

### 8.3.3 与信回復（返品時）

返品時には、使用済みの与信を回復します。

```scala
// 返品時の与信回復
private def handleReturnOrder(
  state: ActiveState,
  cmd: ReturnOrder
): ReplyEffect[Event, State] = {

  state.order.returnOrder(cmd.returnItems) match {
    case Right(_) =>
      // 返品金額を計算
      val returnAmount = Money.sum(cmd.returnItems.map(_.totalAmount))

      Effect
        .persist(OrderReturned(cmd.returnItems, Instant.now()))
        .thenRun { _ =>
          // 与信を回復
          val replyAdapter = context.messageAdapter[CreditLimitActor.RecoverCreditReply] {
            case CreditLimitActor.CreditRecoveredReply =>
              context.log.info(s"Credit recovered for returned order ${state.order.id.value}, amount: $returnAmount")
            case CreditLimitActor.RecoverCreditFailed(error) =>
              context.log.error(s"Failed to recover credit: $error")
          }

          creditLimitActor ! CreditLimitActor.RecoverCredit(
            amount = returnAmount,
            replyTo = replyAdapter
          )
        }
        .thenReply(cmd.replyTo)(_ => OrderReturnedReply)

    case Left(error) =>
      Effect.reply(cmd.replyTo)(ReturnOrderFailed(error))
  }
}
```

## 8.4 与信管理のモニタリング

### 8.4.1 与信ダッシュボード

与信の使用状況をモニタリングするためのダッシュボードを提供します。

```scala
package com.example.order.usecase

import com.example.order.domain._
import zio._

class CreditMonitoringService(
  creditLimitRepository: CreditLimitRepository,
  creditHistoryRepository: CreditHistoryRepository,
  creditCheckService: CreditCheckService
) {

  // 与信サマリーを取得
  def getCreditSummary(customerId: CustomerId): Task[CreditSummary] = {
    for {
      creditLimit <- creditLimitRepository.findByCustomer(customerId)
      utilizationRate <- creditCheckService.calculateUtilizationRate(customerId)
      alert <- creditCheckService.checkCreditAlert(customerId)
    } yield CreditSummary(
      customerId = customerId,
      limitAmount = creditLimit.limitAmount,
      usedAmount = creditLimit.usedAmount,
      reservedAmount = creditLimit.reservedAmount,
      availableAmount = creditLimit.availableAmount,
      utilizationRate = utilizationRate,
      alert = alert
    )
  }

  // 全顧客の与信サマリーを取得
  def getAllCreditSummaries(): Task[List[CreditSummary]] = {
    for {
      allCreditLimits <- creditLimitRepository.findAll()
      summaries <- ZIO.foreach(allCreditLimits) { creditLimit =>
        getCreditSummary(creditLimit.customerId)
      }
    } yield summaries
  }

  // 与信超過リスクのある顧客を抽出
  def getHighRiskCustomers(
    threshold: BigDecimal = BigDecimal("0.90")  // 90%以上
  ): Task[List[CreditSummary]] = {
    for {
      allSummaries <- getAllCreditSummaries()
    } yield allSummaries.filter(_.utilizationRate >= threshold)
  }

  // 与信履歴を取得
  def getCreditHistory(
    customerId: CustomerId,
    months: Int = 12
  ): Task[CreditHistory] = {
    creditHistoryRepository.findByCustomer(customerId, months)
  }
}

// 与信サマリー
final case class CreditSummary(
  customerId: CustomerId,
  limitAmount: Money,
  usedAmount: Money,
  reservedAmount: Money,
  availableAmount: Money,
  utilizationRate: BigDecimal,
  alert: Option[CreditAlert]
) {

  def isHealthy: Boolean = utilizationRate < BigDecimal("0.80")
  def isWarning: Boolean = utilizationRate >= BigDecimal("0.80") && utilizationRate < BigDecimal("0.95")
  def isCritical: Boolean = utilizationRate >= BigDecimal("0.95")
}
```

### 8.4.2 与信アラート通知

与信使用率が高い顧客に対してアラートを通知します。

```scala
package com.example.order.adapter.notification

import com.example.order.domain._
import zio._

trait CreditAlertNotifier {
  def notify(alert: CreditAlert): Task[Unit]
}

class EmailCreditAlertNotifier(
  emailService: EmailService
) extends CreditAlertNotifier {

  def notify(alert: CreditAlert): Task[Unit] = {
    val subject = alert.severity match {
      case AlertSeverity.Warning => s"【警告】与信使用率が高くなっています (${formatPercentage(alert.utilizationRate)})"
      case AlertSeverity.Critical => s"【重大】与信限度額の上限に近づいています (${formatPercentage(alert.utilizationRate)})"
    }

    val body = s"""
      |顧客ID: ${alert.customerId.value}
      |
      |与信限度額: ${alert.currentLimit.formatted}
      |使用額: ${alert.usedAmount.formatted}
      |引当済み額: ${alert.reservedAmount.formatted}
      |利用可能額: ${(alert.currentLimit - alert.usedAmount - alert.reservedAmount).formatted}
      |使用率: ${formatPercentage(alert.utilizationRate)}
      |
      |適切な対応をお願いします。
      |""".stripMargin

    emailService.send(
      to = "credit-team@example.com",
      subject = subject,
      body = body
    )
  }

  private def formatPercentage(rate: BigDecimal): String = {
    s"${(rate * 100).setScale(1, BigDecimal.RoundingMode.HALF_UP)}%"
  }
}
```

## 8.5 まとめ

本章では、与信管理の高度な実装について詳しく説明しました。

**実装のポイント**:

1. **取引先タイプ別の与信限度額**: 大口・中口・小口に分類し、それぞれ異なる限度額を設定
2. **動的調整**: 取引実績に基づいて与信限度額を自動的に増減
3. **与信チェックプロセス**: 注文時に与信枠の可用性を確認し、不足時はエラー
4. **与信枠の引当と解放**: 注文時に引当、キャンセル時に解放、入金時に使用済みに変更
5. **与信回復**: 返品時に使用済み与信を回復
6. **モニタリング**: 与信使用率の監視とアラート通知

**次章では**:
- 第9章: 請求管理の実装（月次締め処理、請求書発行、入金照合）

与信管理により、売上機会を最大化しつつ貸し倒れリスクを最小化し、健全な取引関係を維持できます。動的調整機能により、顧客の成長に合わせて柔軟に与信限度額を調整し、ビジネスチャンスを逃しません。
