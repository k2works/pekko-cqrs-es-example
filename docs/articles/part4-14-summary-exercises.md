# 【第4部 第14章】まとめと実践演習：受注管理システムの総括

## 本章の目的

本章では、Part 4で構築した受注管理システム全体を振り返り、学んだ知識を整理します。また、実践的な演習課題を通じて、これまでの学習内容を応用し、さらなる機能拡張を経験します。

## 14.1 学んだこと

Part 4を通じて、月間50,000件（1日1,600件）の注文を処理する、エンタープライズグレードの受注管理システムを構築しました。ここでは、重要な概念と実装パターンを振り返ります。

### 14.1.1 Sagaパターンの実装

**Orchestrationによる分散トランザクション**:

複数の集約（Order、Inventory、CreditLimit、Shipping）にまたがる注文プロセスを、中央のオーケストレーターが管理します。

```scala
package com.example.order.adapter.saga

import com.example.order.domain._
import org.apache.pekko.actor.typed._
import org.apache.pekko.actor.typed.scaladsl._
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl._
import java.time.Instant

// Sagaの状態
sealed trait OrderSagaState
object OrderSagaState {
  case object Idle extends OrderSagaState
  final case class InProgress(
    orderId: OrderId,
    currentStep: SagaStep,
    completedSteps: List[SagaStep]
  ) extends OrderSagaState
  final case class Compensating(
    orderId: OrderId,
    failedStep: SagaStep,
    reason: String,
    compensatedSteps: List[SagaStep]
  ) extends OrderSagaState
  final case class Completed(orderId: OrderId) extends OrderSagaState
  final case class Failed(orderId: OrderId, reason: String) extends OrderSagaState
}

// Sagaのステップ
sealed trait SagaStep
object SagaStep {
  case object OrderCreated extends SagaStep          // Step 1: 注文作成
  case object StockReserved extends SagaStep         // Step 2: 在庫引当
  case object CreditChecked extends SagaStep         // Step 3: 与信チェック
  case object OrderConfirmed extends SagaStep        // Step 4: 注文確定
  case object ShippingInstructed extends SagaStep    // Step 5: 出荷指示
}

// Sagaオーケストレーター
object OrderSagaOrchestrator {

  sealed trait Command
  final case class StartSaga(orderId: OrderId) extends Command
  final case class StockReservationSucceeded(orderId: OrderId) extends Command
  final case class StockReservationFailed(orderId: OrderId, reason: String) extends Command
  final case class CreditCheckSucceeded(orderId: OrderId) extends Command
  final case class CreditCheckFailed(orderId: OrderId, reason: String) extends Command
  final case class OrderConfirmationSucceeded(orderId: OrderId) extends Command
  final case class OrderConfirmationFailed(orderId: OrderId, reason: String) extends Command
  final case class ShippingInstructionSucceeded(orderId: OrderId) extends Command
  final case class ShippingInstructionFailed(orderId: OrderId, reason: String) extends Command
  final case class CompensationCompleted(orderId: OrderId) extends Command
  final case class CompensationFailed(orderId: OrderId, reason: String) extends Command

  sealed trait Event
  final case class SagaStarted(orderId: OrderId, occurredAt: Instant = Instant.now()) extends Event
  final case class StepCompleted(step: SagaStep, occurredAt: Instant = Instant.now()) extends Event
  final case class StepFailed(step: SagaStep, reason: String, occurredAt: Instant = Instant.now()) extends Event
  final case class CompensationStarted(failedStep: SagaStep, occurredAt: Instant = Instant.now()) extends Event
  final case class StepCompensated(step: SagaStep, occurredAt: Instant = Instant.now()) extends Event
  final case class SagaCompleted(orderId: OrderId, occurredAt: Instant = Instant.now()) extends Event
  final case class SagaFailed(orderId: OrderId, reason: String, occurredAt: Instant = Instant.now()) extends Event

  def apply(sagaId: SagaId): Behavior[Command] = {
    Behaviors.setup { context =>
      EventSourcedBehavior[Command, Event, OrderSagaState](
        persistenceId = PersistenceId.ofUniqueId(sagaId.value),
        emptyState = OrderSagaState.Idle,
        commandHandler = commandHandler(context),
        eventHandler = eventHandler
      )
    }
  }

  private def commandHandler(
    context: ActorContext[Command]
  ): (OrderSagaState, Command) => Effect[Event, OrderSagaState] = {
    case (OrderSagaState.Idle, StartSaga(orderId)) =>
      Effect.persist(SagaStarted(orderId))
        .thenRun { _ =>
          // Step 1: 在庫引当を開始
          context.log.info(s"Starting stock reservation for order ${orderId.value}")
          // ここで在庫引当アクターにコマンドを送信
        }

    case (state: OrderSagaState.InProgress, StockReservationSucceeded(orderId)) =>
      Effect.persist(StepCompleted(SagaStep.StockReserved))
        .thenRun { _ =>
          // Step 2: 与信チェックを開始
          context.log.info(s"Starting credit check for order ${orderId.value}")
          // ここで与信チェックアクターにコマンドを送信
        }

    case (state: OrderSagaState.InProgress, StockReservationFailed(orderId, reason)) =>
      Effect.persist(StepFailed(SagaStep.StockReserved, reason))
        .thenRun { _ =>
          // 補償処理を開始（ただし在庫引当前なので何もしない）
          context.log.warn(s"Stock reservation failed for order ${orderId.value}: $reason")
        }

    case (state: OrderSagaState.InProgress, CreditCheckSucceeded(orderId)) =>
      Effect.persist(StepCompleted(SagaStep.CreditChecked))
        .thenRun { _ =>
          // Step 3: 注文確定を開始
          context.log.info(s"Confirming order ${orderId.value}")
          // ここで注文確定アクターにコマンドを送信
        }

    case (state: OrderSagaState.InProgress, CreditCheckFailed(orderId, reason)) =>
      Effect.persist(StepFailed(SagaStep.CreditChecked, reason))
        .thenRun { _ =>
          // 補償処理を開始（在庫引当を解除）
          context.log.warn(s"Credit check failed for order ${orderId.value}: $reason")
          // C1: 在庫解放
        }

    case (state: OrderSagaState.InProgress, OrderConfirmationSucceeded(orderId)) =>
      Effect.persist(StepCompleted(SagaStep.OrderConfirmed))
        .thenRun { _ =>
          // Step 4: 出荷指示を開始
          context.log.info(s"Sending shipping instruction for order ${orderId.value}")
          // ここで出荷指示アクターにコマンドを送信
        }

    case (state: OrderSagaState.InProgress, ShippingInstructionSucceeded(orderId)) =>
      Effect.persist(StepCompleted(SagaStep.ShippingInstructed))
        .thenPersist(SagaCompleted(orderId))
        .thenRun { _ =>
          context.log.info(s"Saga completed successfully for order ${orderId.value}")
        }

    case (state: OrderSagaState.Compensating, CompensationCompleted(orderId)) =>
      Effect.persist(SagaFailed(orderId, "Compensation completed"))

    case _ =>
      Effect.none
  }

  private def eventHandler: (OrderSagaState, Event) => OrderSagaState = {
    case (OrderSagaState.Idle, SagaStarted(orderId, _)) =>
      OrderSagaState.InProgress(
        orderId = orderId,
        currentStep = SagaStep.OrderCreated,
        completedSteps = List.empty
      )

    case (state: OrderSagaState.InProgress, StepCompleted(step, _)) =>
      state.copy(
        currentStep = step,
        completedSteps = state.completedSteps :+ step
      )

    case (state: OrderSagaState.InProgress, StepFailed(step, reason, _)) =>
      OrderSagaState.Compensating(
        orderId = state.orderId,
        failedStep = step,
        reason = reason,
        compensatedSteps = List.empty
      )

    case (state: OrderSagaState.Compensating, StepCompensated(step, _)) =>
      state.copy(compensatedSteps = state.compensatedSteps :+ step)

    case (_, SagaCompleted(orderId, _)) =>
      OrderSagaState.Completed(orderId)

    case (_, SagaFailed(orderId, reason, _)) =>
      OrderSagaState.Failed(orderId, reason)

    case (state, _) =>
      state
  }
}
```

**補償トランザクション**:

失敗したステップに応じて、完了済みステップを逆順に補償します。

```scala
package com.example.order.adapter.saga

import com.example.order.domain._
import zio._

// 補償トランザクションの定義
sealed trait CompensationAction {
  def execute(): Task[Unit]
  def stepName: String
}

object CompensationAction {

  // C1: 注文キャンセル
  final case class CancelOrder(
    orderId: OrderId,
    orderActor: ActorRef[OrderActor.Command]
  ) extends CompensationAction {
    def stepName: String = "Cancel Order"

    def execute(): Task[Unit] = {
      ZIO.attemptBlocking {
        orderActor ! OrderActor.CancelOrder(
          reason = "Saga compensation",
          replyTo = ???
        )
      }
    }
  }

  // C2: 在庫解放
  final case class ReleaseStock(
    orderId: OrderId,
    items: List[OrderItem],
    inventoryActor: ActorRef[InventoryActor.Command]
  ) extends CompensationAction {
    def stepName: String = "Release Stock"

    def execute(): Task[Unit] = {
      ZIO.attemptBlocking {
        items.foreach { item =>
          inventoryActor ! InventoryActor.ReleaseReservation(
            productId = item.productId,
            quantity = item.quantity,
            reservationId = ???,
            replyTo = ???
          )
        }
      }
    }
  }

  // C3: 与信解放
  final case class ReleaseCredit(
    customerId: CustomerId,
    amount: Money,
    creditActor: ActorRef[CreditActor.Command]
  ) extends CompensationAction {
    def stepName: String = "Release Credit"

    def execute(): Task[Unit] = {
      ZIO.attemptBlocking {
        creditActor ! CreditActor.ReleaseCredit(
          customerId = customerId,
          amount = amount,
          replyTo = ???
        )
      }
    }
  }
}

// 補償処理の実行
class CompensationExecutor {

  def executeCompensations(
    compensations: List[CompensationAction]
  ): Task[Unit] = {
    // 逆順に補償処理を実行
    ZIO.foreach(compensations.reverse) { compensation =>
      for {
        _ <- ZIO.logInfo(s"Executing compensation: ${compensation.stepName}")
        _ <- compensation.execute().retry(
          Schedule.exponentialBackoff(1.second, 2.0) && Schedule.recurs(3)
        )
        _ <- ZIO.logInfo(s"Compensation completed: ${compensation.stepName}")
      } yield ()
    }
  }
}
```

**タイムアウト処理**:

各ステップに適切なタイムアウトを設定し、タイムアウト時は補償処理を開始します。

```scala
package com.example.order.adapter.saga

import scala.concurrent.duration._
import org.apache.pekko.actor.typed._
import org.apache.pekko.actor.typed.scaladsl._

// タイムアウト設定
object SagaTimeoutConfig {
  val stockReservationTimeout: FiniteDuration = 30.seconds
  val creditCheckTimeout: FiniteDuration = 10.seconds
  val orderConfirmationTimeout: FiniteDuration = 10.seconds
  val shippingInstructionTimeout: FiniteDuration = 60.seconds
}

// タイムアウト処理を含むSagaステップ
class SagaStepWithTimeout(context: ActorContext[OrderSagaOrchestrator.Command]) {

  def executeStockReservation(orderId: OrderId): Unit = {
    context.scheduleOnce(
      SagaTimeoutConfig.stockReservationTimeout,
      context.self,
      OrderSagaOrchestrator.StockReservationFailed(orderId, "Timeout")
    )
    // 在庫引当処理を実行
  }

  def executeCreditCheck(orderId: OrderId): Unit = {
    context.scheduleOnce(
      SagaTimeoutConfig.creditCheckTimeout,
      context.self,
      OrderSagaOrchestrator.CreditCheckFailed(orderId, "Timeout")
    )
    // 与信チェック処理を実行
  }

  def executeOrderConfirmation(orderId: OrderId): Unit = {
    context.scheduleOnce(
      SagaTimeoutConfig.orderConfirmationTimeout,
      context.self,
      OrderSagaOrchestrator.OrderConfirmationFailed(orderId, "Timeout")
    )
    // 注文確定処理を実行
  }

  def executeShippingInstruction(orderId: OrderId): Unit = {
    context.scheduleOnce(
      SagaTimeoutConfig.shippingInstructionTimeout,
      context.self,
      OrderSagaOrchestrator.ShippingInstructionFailed(orderId, "Timeout")
    )
    // 出荷指示処理を実行
  }
}

// リトライ戦略（指数バックオフ）
class SagaRetryStrategy {

  def retryWithExponentialBackoff[A](
    attempt: Int,
    maxAttempts: Int,
    initialDelay: FiniteDuration
  )(operation: => Task[A]): Task[A] = {
    operation.catchAll { error =>
      if (attempt < maxAttempts) {
        val delay = initialDelay * math.pow(2, attempt).toLong
        ZIO.logWarn(s"Attempt $attempt failed, retrying in ${delay.toSeconds}s: $error") *>
          ZIO.sleep(delay) *>
          retryWithExponentialBackoff(attempt + 1, maxAttempts, initialDelay)(operation)
      } else {
        ZIO.fail(error)
      }
    }
  }
}
```

### 14.1.2 金額情報の管理

**BigDecimalによる正確な計算**:

浮動小数点数（Float、Double）は、10進数の小数を正確に表現できないため、金額計算には使用できません。

```scala
package com.example.order.domain

import scala.math.BigDecimal.RoundingMode

// 浮動小数点の問題例
object FloatingPointProblem {
  val result1 = 0.1 + 0.2  // 0.30000000000000004（正確に0.3にならない）
  val result2 = 0.1f + 0.2f  // 0.3（表示上は正しいが内部的には誤差がある）
}

// BigDecimalによる正確な計算
object BigDecimalSolution {
  val result = BigDecimal("0.1") + BigDecimal("0.2")  // 0.3（正確）
}

// Money値オブジェクトの実装
final case class Money(
  amount: BigDecimal,
  currency: Currency = Currency.JPY
) {
  require(amount.scale <= 2, "金額の小数点以下は2桁まで")

  // 加算
  def +(other: Money): Money = {
    require(currency == other.currency, s"通貨が一致しません: $currency != ${other.currency}")
    Money(amount + other.amount, currency)
  }

  // 減算
  def -(other: Money): Money = {
    require(currency == other.currency, s"通貨が一致しません: $currency != ${other.currency}")
    Money(amount - other.amount, currency)
  }

  // 乗算
  def *(multiplier: BigDecimal): Money = {
    Money(amount * multiplier, currency)
  }

  // 除算
  def /(divisor: BigDecimal): Money = {
    require(divisor != 0, "0で除算できません")
    Money(amount / divisor, currency)
  }

  // 丸め処理（四捨五入）
  def round(scale: Int = 0, mode: RoundingMode = RoundingMode.HALF_UP): Money = {
    Money(amount.setScale(scale, mode), currency)
  }

  // 比較
  def >(other: Money): Boolean = {
    require(currency == other.currency, s"通貨が一致しません: $currency != ${other.currency}")
    amount > other.amount
  }

  def <(other: Money): Boolean = {
    require(currency == other.currency, s"通貨が一致しません: $currency != ${other.currency}")
    amount < other.amount
  }

  def >=(other: Money): Boolean = {
    require(currency == other.currency, s"通貨が一致しません: $currency != ${other.currency}")
    amount >= other.amount
  }

  def <=(other: Money): Boolean = {
    require(currency == other.currency, s"通貨が一致しません: $currency != ${other.currency}")
    amount <= other.amount
  }
}

object Money {
  val zero: Money = Money(BigDecimal(0))

  def apply(amount: String): Money = Money(BigDecimal(amount))
  def apply(amount: Int): Money = Money(BigDecimal(amount))
  def apply(amount: Long): Money = Money(BigDecimal(amount))
}
```

**税金計算**:

日本の消費税（標準10%、軽減8%）を正確に計算します。

```scala
package com.example.order.domain

// 税率の管理
sealed trait TaxRate {
  def rate: BigDecimal
  def description: String
}

object TaxRate {
  case object Standard extends TaxRate {
    val rate = BigDecimal("0.10")  // 10%
    val description = "標準税率"
  }

  case object Reduced extends TaxRate {
    val rate = BigDecimal("0.08")  // 8%
    val description = "軽減税率"
  }

  case object TaxFree extends TaxRate {
    val rate = BigDecimal("0.00")  // 0%
    val description = "非課税"
  }
}

// 税金計算サービス
class TaxCalculationService {

  // 外税方式: 税込金額 = 本体価格 + 税額
  def calculateTaxExcluded(
    baseAmount: Money,
    taxRate: TaxRate
  ): (Money, Money) = {
    val taxAmount = (baseAmount * taxRate.rate).round(0)  // 1円未満は四捨五入
    val totalAmount = baseAmount + taxAmount
    (taxAmount, totalAmount)
  }

  // 内税方式: 本体価格 = 税込金額 / (1 + 税率)
  def calculateTaxIncluded(
    totalAmount: Money,
    taxRate: TaxRate
  ): (Money, Money) = {
    val divisor = BigDecimal("1") + taxRate.rate
    val baseAmount = (totalAmount / divisor).round(0)
    val taxAmount = totalAmount - baseAmount
    (baseAmount, taxAmount)
  }

  // 複数税率の混在（注文明細ごとに異なる税率）
  def calculateMixedTaxRates(
    items: List[OrderItem]
  ): (Money, Money, Money) = {
    val results = items.map { item =>
      val itemTotal = Money(item.unitPrice.amount * item.quantity.value)
      val (taxAmount, totalAmount) = calculateTaxExcluded(itemTotal, item.taxRate)
      (itemTotal, taxAmount, totalAmount)
    }

    val subtotal = results.map(_._1).foldLeft(Money.zero)(_ + _)
    val totalTax = results.map(_._2).foldLeft(Money.zero)(_ + _)
    val total = results.map(_._3).foldLeft(Money.zero)(_ + _)

    (subtotal, totalTax, total)
  }
}
```

**割引処理**:

率引き、額引き、クーポンの3種類の割引を適用します。

```scala
package com.example.order.domain

// 割引タイプ
sealed trait DiscountType
object DiscountType {
  case object Percentage extends DiscountType  // 率引き（10% OFFなど）
  case object Amount extends DiscountType      // 額引き（1,000円引きなど）
  case object Coupon extends DiscountType      // クーポン
}

// 割引
final case class Discount(
  discountType: DiscountType,
  value: BigDecimal,
  description: String
) {
  def apply(baseAmount: Money): Money = discountType match {
    case DiscountType.Percentage =>
      // 率引き: 10% OFF = 0.10
      val discountAmount = (baseAmount * value).round(0)
      discountAmount

    case DiscountType.Amount =>
      // 額引き: 1,000円引き
      Money(value)

    case DiscountType.Coupon =>
      // クーポン: 固定金額
      Money(value)
  }
}

// 割引計算サービス
class DiscountCalculationService {

  def calculateWithDiscounts(
    subtotal: Money,
    discounts: List[Discount],
    taxRate: TaxRate
  ): (Money, Money, Money) = {
    // 1. 割引額の計算
    val totalDiscount = discounts.map(_.apply(subtotal)).foldLeft(Money.zero)(_ + _)

    // 2. 割引後金額の計算
    val discountedAmount = subtotal - totalDiscount
    require(discountedAmount.amount >= 0, "割引後金額が負になりました")

    // 3. 税金の計算（割引後金額に対して税金を計算）
    val taxAmount = (discountedAmount * taxRate.rate).round(0)

    // 4. 合計金額の計算
    val totalAmount = discountedAmount + taxAmount

    (discountedAmount, taxAmount, totalAmount)
  }

  // 按分処理（複数明細への割引配分）
  def allocateDiscount(
    items: List[OrderItem],
    totalDiscount: Money
  ): List[(OrderItem, Money)] = {
    val subtotal = items.map(i => Money(i.unitPrice.amount * i.quantity.value))
      .foldLeft(Money.zero)(_ + _)

    // 各明細への按分額を計算
    val allocations = items.zipWithIndex.map { case (item, index) =>
      val itemTotal = Money(item.unitPrice.amount * item.quantity.value)
      val ratio = itemTotal.amount / subtotal.amount
      val allocation = (totalDiscount * ratio).round(0)
      (item, allocation)
    }

    // 端数調整（最後の明細で調整）
    val allocatedTotal = allocations.map(_._2).foldLeft(Money.zero)(_ + _)
    val remainder = totalDiscount - allocatedTotal

    if (remainder.amount != 0 && allocations.nonEmpty) {
      val (lastItem, lastAllocation) = allocations.last
      allocations.init :+ (lastItem, lastAllocation + remainder)
    } else {
      allocations
    }
  }
}
```

### 14.1.3 与信管理

**与信限度額チェック**:

顧客の与信限度額と使用額を管理し、注文時に与信枠をチェックします。

```scala
package com.example.order.domain

// 与信限度額集約
final case class CreditLimit(
  customerId: CustomerId,
  limitAmount: Money,           // 与信限度額
  usedAmount: Money,             // 使用済み額
  reservedAmount: Money,         // 引当済み額（確定前の注文）
  version: Version
) {
  // 利用可能額を計算
  def availableAmount: Money = {
    limitAmount - usedAmount - reservedAmount
  }

  // 与信チェック
  def canReserve(amount: Money): Boolean = {
    availableAmount >= amount
  }

  // 与信枠の引当
  def reserve(amount: Money): Either[CreditError, CreditLimit] = {
    if (!canReserve(amount)) {
      return Left(CreditError.InsufficientCredit(
        customerId = customerId,
        availableAmount = availableAmount,
        requestedAmount = amount
      ))
    }
    Right(copy(
      reservedAmount = reservedAmount + amount,
      version = version.increment
    ))
  }

  // 与信枠の使用（注文確定時）
  def use(amount: Money): Either[CreditError, CreditLimit] = {
    if (amount > reservedAmount) {
      return Left(CreditError.ReservationNotFound(
        customerId = customerId,
        requestedAmount = amount,
        reservedAmount = reservedAmount
      ))
    }
    Right(copy(
      reservedAmount = reservedAmount - amount,
      usedAmount = usedAmount + amount,
      version = version.increment
    ))
  }

  // 与信枠の解放（キャンセル時）
  def release(amount: Money): Either[CreditError, CreditLimit] = {
    if (amount > reservedAmount) {
      return Left(CreditError.ReleaseExceedsReserved(
        customerId = customerId,
        releaseAmount = amount,
        reservedAmount = reservedAmount
      ))
    }
    Right(copy(
      reservedAmount = reservedAmount - amount,
      version = version.increment
    ))
  }

  // 与信枠の回復（入金確認時）
  def recover(amount: Money): Either[CreditError, CreditLimit] = {
    if (amount > usedAmount) {
      return Left(CreditError.RecoveryExceedsUsedAmount(
        customerId = customerId,
        usedAmount = usedAmount,
        recoveryAmount = amount
      ))
    }
    Right(copy(
      usedAmount = usedAmount - amount,
      version = version.increment
    ))
  }

  // 与信使用率
  def usageRate: BigDecimal = {
    if (limitAmount.amount == 0) BigDecimal(0)
    else (usedAmount + reservedAmount).amount / limitAmount.amount
  }
}

sealed trait CreditError {
  def message: String
}

object CreditError {
  final case class InsufficientCredit(
    customerId: CustomerId,
    availableAmount: Money,
    requestedAmount: Money
  ) extends CreditError {
    def message: String =
      s"与信限度額不足: 利用可能額=${availableAmount.amount}, 要求額=${requestedAmount.amount}"
  }

  final case class ReservationNotFound(
    customerId: CustomerId,
    requestedAmount: Money,
    reservedAmount: Money
  ) extends CreditError {
    def message: String =
      s"引当額不足: 引当済み額=${reservedAmount.amount}, 要求額=${requestedAmount.amount}"
  }

  final case class ReleaseExceedsReserved(
    customerId: CustomerId,
    releaseAmount: Money,
    reservedAmount: Money
  ) extends CreditError {
    def message: String =
      s"解放額が引当額を超過: 解放額=${releaseAmount.amount}, 引当済み額=${reservedAmount.amount}"
  }

  final case class RecoveryExceedsUsedAmount(
    customerId: CustomerId,
    usedAmount: Money,
    recoveryAmount: Money
  ) extends CreditError {
    def message: String =
      s"回復額が使用額を超過: 回復額=${recoveryAmount.amount}, 使用済み額=${usedAmount.amount}"
  }
}
```

**動的な与信調整**:

取引実績に基づいて与信限度額を自動的に調整します。

```scala
package com.example.order.usecase

import com.example.order.domain._
import zio._
import java.time.YearMonth

// 与信評価サービス
class CreditEvaluationService(
  creditLimitRepository: CreditLimitRepository,
  orderRepository: OrderRepository,
  invoiceRepository: InvoiceRepository
) {

  final case class CreditEvaluation(
    customerId: CustomerId,
    currentLimit: Money,
    recommendedLimit: Money,
    adjustmentReason: String,
    adjustmentRate: BigDecimal
  )

  def evaluateCredit(
    customerId: CustomerId,
    evaluationMonth: YearMonth
  ): Task[CreditEvaluation] = {
    for {
      creditLimit <- creditLimitRepository.findByCustomer(customerId)
      tradingHistory <- getTradingHistory(customerId, evaluationMonth)
      evaluation <- calculateAdjustment(creditLimit, tradingHistory)
    } yield evaluation
  }

  private def getTradingHistory(
    customerId: CustomerId,
    evaluationMonth: YearMonth
  ): Task[TradingHistory] = {
    for {
      // 過去6ヶ月の取引実績を取得
      orders <- ZIO.foreach(0 until 6) { monthsAgo =>
        val targetMonth = evaluationMonth.minusMonths(monthsAgo)
        orderRepository.findByMonthAndCustomer(customerId, targetMonth)
      }

      // 延滞情報を取得
      invoices <- ZIO.foreach(0 until 6) { monthsAgo =>
        val targetMonth = evaluationMonth.minusMonths(monthsAgo)
        invoiceRepository.findByMonthAndCustomer(customerId, targetMonth)
      }

      overdueCount = invoices.flatten.count(_.isOverdue)
      totalOrders = orders.flatten.size
      totalRevenue = orders.flatten.map(_.totalAmount).foldLeft(Money.zero)(_ + _)
      averageRevenue = if (totalOrders > 0) totalRevenue / BigDecimal(totalOrders) else Money.zero

    } yield TradingHistory(
      monthlyOrders = orders.map(_.size),
      totalRevenue = totalRevenue,
      averageRevenue = averageRevenue,
      overdueCount = overdueCount
    )
  }

  private def calculateAdjustment(
    creditLimit: CreditLimit,
    history: TradingHistory
  ): Task[CreditEvaluation] = ZIO.succeed {
    var adjustmentRate = BigDecimal("1.0")  // 100% = 変更なし
    var reasons = List.empty[String]

    // ルール1: 連続6ヶ月の良好な取引 → +20%増額
    if (history.monthlyOrders.forall(_ > 0) && history.overdueCount == 0) {
      adjustmentRate += BigDecimal("0.20")
      reasons = reasons :+ "連続6ヶ月の良好な取引実績（+20%）"
    }

    // ルール2: 延滞1回以上 → -20%減額
    if (history.overdueCount > 0) {
      adjustmentRate -= BigDecimal("0.20") * history.overdueCount
      reasons = reasons :+ s"延滞${history.overdueCount}回（-${20 * history.overdueCount}%）"
    }

    // ルール3: 使用率が常に30%未満 → -15%減額
    if (creditLimit.usageRate < BigDecimal("0.30")) {
      adjustmentRate -= BigDecimal("0.15")
      reasons = reasons :+ "低使用率（-15%）"
    }

    // ルール4: 取引額が平均の2倍以上に増加 → +30%増額
    val recentAverage = history.monthlyOrders.take(2).sum / 2.0
    val previousAverage = history.monthlyOrders.drop(2).sum / 4.0
    if (recentAverage >= previousAverage * 2) {
      adjustmentRate += BigDecimal("0.30")
      reasons = reasons :+ "取引額大幅増加（+30%）"
    }

    // 調整率の範囲を制限（-50% 〜 +50%）
    adjustmentRate = adjustmentRate.max(BigDecimal("0.50")).min(BigDecimal("1.50"))

    val recommendedLimit = (creditLimit.limitAmount * adjustmentRate).round(0)

    CreditEvaluation(
      customerId = creditLimit.customerId,
      currentLimit = creditLimit.limitAmount,
      recommendedLimit = recommendedLimit,
      adjustmentReason = reasons.mkString(", "),
      adjustmentRate = adjustmentRate
    )
  }

  final case class TradingHistory(
    monthlyOrders: List[Int],
    totalRevenue: Money,
    averageRevenue: Money,
    overdueCount: Int
  )
}
```

### 14.1.4 請求管理

**月次締め処理**:

毎月末に、対象月の配送完了済み注文を集計し、顧客別に請求書を自動生成します。

```scala
package com.example.order.usecase

import com.example.order.domain._
import zio._
import java.time.{YearMonth, LocalDate}

// 請求書生成サービス
class InvoiceGenerationService(
  orderRepository: OrderRepository,
  invoiceRepository: InvoiceRepository,
  invoiceActor: ActorRef[InvoiceActor.Command]
) {

  def generateMonthlyInvoices(
    billingYearMonth: YearMonth
  ): Task[List[Invoice]] = {
    for {
      // 対象月の全顧客を取得
      customers <- orderRepository.findCustomersWithDeliveredOrders(billingYearMonth)

      // 顧客ごとに請求書を生成
      invoices <- ZIO.foreach(customers) { customerId =>
        generateMonthlyInvoice(customerId, billingYearMonth)
      }
    } yield invoices.flatten
  }

  def generateMonthlyInvoice(
    customerId: CustomerId,
    billingYearMonth: YearMonth
  ): Task[Option[Invoice]] = {
    for {
      // 対象月の配送完了済み注文を取得
      orders <- orderRepository.findDeliveredOrdersByMonth(customerId, billingYearMonth)

      result <- if (orders.isEmpty) {
        ZIO.logInfo(s"No orders found for customer ${customerId.value} in $billingYearMonth") *>
          ZIO.succeed(None)
      } else {
        for {
          invoice <- createInvoice(customerId, billingYearMonth, orders)
          _ <- invoiceRepository.save(invoice)
          _ <- ZIO.logInfo(s"Generated invoice ${invoice.id.value} for customer ${customerId.value}")
        } yield Some(invoice)
      }
    } yield result
  }

  private def createInvoice(
    customerId: CustomerId,
    billingYearMonth: YearMonth,
    orders: List[Order]
  ): Task[Invoice] = ZIO.succeed {
    // 金額を集計
    val subtotalAmount = orders.map(_.subtotalAmount).foldLeft(Money.zero)(_ + _)
    val taxAmount = orders.map(_.taxAmount).foldLeft(Money.zero)(_ + _)
    val totalAmount = orders.map(_.totalAmount).foldLeft(Money.zero)(_ + _)

    // 請求書発行日（月末の5営業日後）
    val closingDate = billingYearMonth.atEndOfMonth()
    val issueDate = addBusinessDays(closingDate, 5)

    // 支払期限（発行日の30日後）
    val dueDate = issueDate.plusDays(30)

    Invoice(
      id = InvoiceId.generate(),
      invoiceNumber = generateInvoiceNumber(billingYearMonth, customerId),
      customerId = customerId,
      billingYearMonth = billingYearMonth,
      orderIds = orders.map(_.id),
      subtotalAmount = subtotalAmount,
      taxAmount = taxAmount,
      totalAmount = totalAmount,
      issueDate = issueDate,
      dueDate = dueDate,
      status = InvoiceStatus.Issued,
      version = Version.initial
    )
  }

  private def generateInvoiceNumber(
    billingYearMonth: YearMonth,
    customerId: CustomerId
  ): InvoiceNumber = {
    val yearMonth = billingYearMonth.toString.replace("-", "")
    val customerCode = customerId.value.take(6)
    InvoiceNumber(s"INV-$yearMonth-$customerCode")
  }

  private def addBusinessDays(date: LocalDate, days: Int): LocalDate = {
    var current = date
    var remaining = days
    while (remaining > 0) {
      current = current.plusDays(1)
      // 土日を除外（実際は祝日も考慮が必要）
      if (current.getDayOfWeek.getValue <= 5) {
        remaining -= 1
      }
    }
    current
  }
}
```

**入金処理**:

入金記録を照合し、一部入金や過入金の処理を行います。

```scala
package com.example.order.usecase

import com.example.order.domain._
import zio._
import java.time.LocalDate

// 入金処理サービス
class PaymentProcessingService(
  invoiceRepository: InvoiceRepository,
  paymentRepository: PaymentRepository,
  creditLimitRepository: CreditLimitRepository
) {

  def processPayment(
    invoiceId: InvoiceId,
    paymentAmount: Money,
    paymentDate: LocalDate,
    paymentMethod: PaymentMethod
  ): Task[Either[PaymentError, Payment]] = {
    for {
      invoice <- invoiceRepository.findById(invoiceId)
        .someOrFail(PaymentError.InvoiceNotFound(invoiceId))

      result <- processPaymentInternal(invoice, paymentAmount, paymentDate, paymentMethod)
    } yield result
  }

  private def processPaymentInternal(
    invoice: Invoice,
    paymentAmount: Money,
    paymentDate: LocalDate,
    paymentMethod: PaymentMethod
  ): Task[Either[PaymentError, Payment]] = {
    val remainingAmount = invoice.totalAmount - invoice.paidAmount

    paymentAmount.amount.compare(remainingAmount.amount) match {
      // 完全入金
      case 0 =>
        for {
          payment <- createPayment(invoice, paymentAmount, paymentDate, paymentMethod, PaymentStatus.FullyPaid)
          _ <- paymentRepository.save(payment)
          _ <- invoiceRepository.save(invoice.copy(
            paidAmount = invoice.paidAmount + paymentAmount,
            status = InvoiceStatus.Paid
          ))
          _ <- recoverCredit(invoice.customerId, paymentAmount)
          _ <- ZIO.logInfo(s"Full payment processed for invoice ${invoice.id.value}")
        } yield Right(payment)

      // 一部入金
      case -1 =>
        for {
          payment <- createPayment(invoice, paymentAmount, paymentDate, paymentMethod, PaymentStatus.PartiallyPaid)
          _ <- paymentRepository.save(payment)
          _ <- invoiceRepository.save(invoice.copy(
            paidAmount = invoice.paidAmount + paymentAmount,
            status = InvoiceStatus.PartiallyPaid
          ))
          _ <- recoverCredit(invoice.customerId, paymentAmount)
          _ <- ZIO.logInfo(s"Partial payment processed for invoice ${invoice.id.value}: ${paymentAmount.amount}/${invoice.totalAmount.amount}")
        } yield Right(payment)

      // 過入金
      case 1 =>
        val overpaymentAmount = paymentAmount - remainingAmount
        for {
          payment <- createPayment(invoice, paymentAmount, paymentDate, paymentMethod, PaymentStatus.Overpaid)
          _ <- paymentRepository.save(payment)
          _ <- invoiceRepository.save(invoice.copy(
            paidAmount = invoice.paidAmount + remainingAmount,
            status = InvoiceStatus.Paid
          ))
          _ <- recoverCredit(invoice.customerId, remainingAmount)
          _ <- handleOverpayment(invoice.customerId, overpaymentAmount)
          _ <- ZIO.logInfo(s"Overpayment processed for invoice ${invoice.id.value}: overpayment=${overpaymentAmount.amount}")
        } yield Right(payment)
    }
  }

  private def createPayment(
    invoice: Invoice,
    paymentAmount: Money,
    paymentDate: LocalDate,
    paymentMethod: PaymentMethod,
    status: PaymentStatus
  ): Task[Payment] = ZIO.succeed {
    Payment(
      id = PaymentId.generate(),
      invoiceId = invoice.id,
      customerId = invoice.customerId,
      paymentAmount = paymentAmount,
      paymentDate = paymentDate,
      paymentMethod = paymentMethod,
      status = status
    )
  }

  private def recoverCredit(
    customerId: CustomerId,
    amount: Money
  ): Task[Unit] = {
    for {
      creditLimit <- creditLimitRepository.findByCustomer(customerId)
      updated <- ZIO.fromEither(creditLimit.recover(amount))
      _ <- creditLimitRepository.save(updated)
    } yield ()
  }

  private def handleOverpayment(
    customerId: CustomerId,
    overpaymentAmount: Money
  ): Task[Unit] = {
    // オプション1: 次月の請求に振替
    // オプション2: 前受金として処理
    ZIO.logInfo(s"Overpayment of ${overpaymentAmount.amount} for customer ${customerId.value} will be transferred to next month")
    // 実際の処理はビジネス要件に応じて実装
  }
}

sealed trait PaymentError {
  def message: String
}

object PaymentError {
  final case class InvoiceNotFound(invoiceId: InvoiceId) extends PaymentError {
    def message: String = s"請求書が見つかりません: ${invoiceId.value}"
  }
}

sealed trait PaymentStatus
object PaymentStatus {
  case object FullyPaid extends PaymentStatus      // 完全入金
  case object PartiallyPaid extends PaymentStatus  // 一部入金
  case object Overpaid extends PaymentStatus       // 過入金
}

sealed trait PaymentMethod
object PaymentMethod {
  case object BankTransfer extends PaymentMethod   // 銀行振込
  case object CreditCard extends PaymentMethod     // クレジットカード
  case object Cash extends PaymentMethod           // 現金
}
```

**入金催促**:

支払期限に応じて、自動的にリマインダーと督促を送信します。

```scala
package com.example.order.usecase

import com.example.order.domain._
import zio._
import java.time.LocalDate

// 入金催促サービス
class PaymentReminderService(
  invoiceRepository: InvoiceRepository,
  emailService: EmailService,
  slackService: SlackNotificationService
) {

  def sendReminders(today: LocalDate): Task[Unit] = {
    for {
      // 未払い請求書を取得
      unpaidInvoices <- invoiceRepository.findUnpaid()

      // リマインダータイプごとに分類して送信
      _ <- ZIO.foreach(unpaidInvoices) { invoice =>
        getReminderType(invoice, today) match {
          case Some(reminderType) =>
            sendReminder(invoice, reminderType)
          case None =>
            ZIO.unit
        }
      }
    } yield ()
  }

  private def getReminderType(
    invoice: Invoice,
    today: LocalDate
  ): Option[ReminderType] = {
    val daysUntilDue = java.time.temporal.ChronoUnit.DAYS.between(today, invoice.dueDate).toInt

    daysUntilDue match {
      case 7 => Some(ReminderType.DueDateNotice)        // 支払期限7日前
      case 3 => Some(ReminderType.DueDateReminder)      // 支払期限3日前
      case 1 => Some(ReminderType.DueDateApproaching)   // 支払期限1日前
      case 0 => Some(ReminderType.FirstReminder)        // 支払期限当日
      case -7 => Some(ReminderType.SecondReminder)      // 支払期限7日後
      case -14 => Some(ReminderType.FinalNotice)        // 支払期限14日後
      case _ => None
    }
  }

  private def sendReminder(
    invoice: Invoice,
    reminderType: ReminderType
  ): Task[Unit] = {
    for {
      customer <- getCustomerInfo(invoice.customerId)

      // メール送信
      _ <- emailService.send(
        to = customer.email,
        subject = reminderType.emailSubject,
        body = createEmailBody(invoice, customer, reminderType)
      )

      // Slack通知（重要度が高い場合）
      _ <- if (reminderType.severity >= Severity.High) {
        slackService.sendAlert(
          channel = "#billing-alerts",
          message = createSlackMessage(invoice, customer, reminderType),
          severity = reminderType.severity
        )
      } else {
        ZIO.unit
      }

      _ <- ZIO.logInfo(s"Sent ${reminderType.name} for invoice ${invoice.id.value} to customer ${customer.name}")
    } yield ()
  }

  private def createEmailBody(
    invoice: Invoice,
    customer: Customer,
    reminderType: ReminderType
  ): String = {
    val remainingAmount = invoice.totalAmount - invoice.paidAmount

    s"""
       |${customer.name} 様
       |
       |${reminderType.greeting}
       |
       |請求書番号: ${invoice.invoiceNumber.value}
       |請求金額: ${invoice.totalAmount.amount}円
       |既入金額: ${invoice.paidAmount.amount}円
       |残額: ${remainingAmount.amount}円
       |支払期限: ${invoice.dueDate}
       |
       |${reminderType.message}
       |
       |よろしくお願いいたします。
       |""".stripMargin
  }

  private def createSlackMessage(
    invoice: Invoice,
    customer: Customer,
    reminderType: ReminderType
  ): String = {
    val remainingAmount = invoice.totalAmount - invoice.paidAmount
    s"[${reminderType.name}] ${customer.name}: 請求書${invoice.invoiceNumber.value} 残額${remainingAmount.amount}円"
  }

  private def getCustomerInfo(customerId: CustomerId): Task[Customer] = {
    // 実装省略
    ???
  }
}

sealed trait ReminderType {
  def name: String
  def emailSubject: String
  def greeting: String
  def message: String
  def severity: Severity
}

object ReminderType {
  case object DueDateNotice extends ReminderType {
    val name = "支払期限通知"
    val emailSubject = "【お支払いのお願い】支払期限7日前のご案内"
    val greeting = "お支払期限が近づいております。"
    val message = "支払期限まで残り7日となりました。お早めのお支払いをお願いいたします。"
    val severity = Severity.Low
  }

  case object DueDateReminder extends ReminderType {
    val name = "支払期限リマインダー"
    val emailSubject = "【重要】支払期限3日前のご案内"
    val greeting = "お支払期限が迫っております。"
    val message = "支払期限まで残り3日となりました。至急お支払いをお願いいたします。"
    val severity = Severity.Medium
  }

  case object DueDateApproaching extends ReminderType {
    val name = "支払期限直前"
    val emailSubject = "【至急】支払期限1日前のご案内"
    val greeting = "お支払期限が明日となりました。"
    val message = "支払期限まで残り1日となりました。本日中のお支払いをお願いいたします。"
    val severity = Severity.High
  }

  case object FirstReminder extends ReminderType {
    val name = "第1回督促"
    val emailSubject = "【至急】お支払いのお願い（支払期限当日）"
    val greeting = "本日が支払期限となっております。"
    val message = "本日が支払期限です。まだお支払いが確認できておりません。至急お支払いをお願いいたします。"
    val severity = Severity.High
  }

  case object SecondReminder extends ReminderType {
    val name = "第2回督促"
    val emailSubject = "【重要】お支払いのお願い（支払期限超過）"
    val greeting = "お支払期限を過ぎております。"
    val message = "支払期限から7日が経過しましたが、まだお支払いが確認できておりません。至急お支払いをお願いいたします。"
    val severity = Severity.High
  }

  case object FinalNotice extends ReminderType {
    val name = "最終通知"
    val emailSubject = "【最終通知】お支払いのお願い"
    val greeting = "最終通知です。"
    val message = "支払期限から14日が経過しました。お支払いが確認できない場合、取引を停止させていただく可能性がございます。至急お支払いをお願いいたします。"
    val severity = Severity.Critical
  }
}
```

## 14.2 実践演習

ここでは、Part 4で学んだ知識を応用する4つの実践的な演習課題を提供します。これらの演習を通じて、受注管理システムをさらに拡張し、実践的なスキルを身につけます。

### 演習1: 割引クーポン機能の追加

**目標**: Coupon集約を実装し、注文時にクーポンを適用できるようにする

**実装すべき機能**:

1. **Coupon集約の実装**

```scala
package com.example.order.domain

import java.time.LocalDate

// クーポン集約
final case class Coupon(
  id: CouponId,
  code: CouponCode,
  discountType: DiscountType,
  discountValue: BigDecimal,
  validFrom: LocalDate,
  validUntil: LocalDate,
  minimumPurchaseAmount: Option[Money],
  maxUsageCount: Option[Int],
  currentUsageCount: Int,
  applicableCustomerTypes: List[CustomerType],
  applicableProducts: Option[List[ProductId]],
  status: CouponStatus,
  version: Version
) {
  // クーポンが有効かチェック
  def isValid(today: LocalDate): Boolean = {
    status == CouponStatus.Active &&
    !today.isBefore(validFrom) &&
    !today.isAfter(validUntil) &&
    maxUsageCount.forall(_ > currentUsageCount)
  }

  // 最低購入金額を満たすかチェック
  def meetsMinimumPurchase(orderAmount: Money): Boolean = {
    minimumPurchaseAmount.forall(_ <= orderAmount)
  }

  // 顧客タイプが適用対象かチェック
  def isApplicableToCustomer(customerType: CustomerType): Boolean = {
    applicableCustomerTypes.isEmpty || applicableCustomerTypes.contains(customerType)
  }

  // 商品が適用対象かチェック
  def isApplicableToProduct(productId: ProductId): Boolean = {
    applicableProducts.isEmpty || applicableProducts.exists(_.contains(productId))
  }

  // クーポンを使用
  def use(): Either[CouponError, Coupon] = {
    if (status != CouponStatus.Active) {
      return Left(CouponError.CouponNotActive(id))
    }
    if (!maxUsageCount.forall(_ > currentUsageCount)) {
      return Left(CouponError.UsageLimitExceeded(id, maxUsageCount.get))
    }
    Right(copy(
      currentUsageCount = currentUsageCount + 1,
      version = version.increment
    ))
  }

  // 割引額を計算
  def calculateDiscount(orderAmount: Money): Money = {
    discountType match {
      case DiscountType.Percentage =>
        (orderAmount * discountValue).round(0)
      case DiscountType.Amount =>
        Money(discountValue).min(orderAmount)
      case _ =>
        Money.zero
    }
  }
}

final case class CouponCode(value: String) extends AnyVal
final case class CouponId(value: String) extends AnyVal

sealed trait CouponStatus
object CouponStatus {
  case object Active extends CouponStatus
  case object Expired extends CouponStatus
  case object Suspended extends CouponStatus
}

sealed trait CouponError {
  def message: String
}

object CouponError {
  final case class CouponNotActive(id: CouponId) extends CouponError {
    def message: String = s"クーポンが有効ではありません: ${id.value}"
  }

  final case class UsageLimitExceeded(id: CouponId, limit: Int) extends CouponError {
    def message: String = s"クーポンの使用回数上限に達しました: ${id.value}, 上限=${limit}"
  }
}
```

2. **CouponActorの実装**

```scala
package com.example.order.adapter.actor

import com.example.order.domain._
import org.apache.pekko.actor.typed._
import org.apache.pekko.actor.typed.scaladsl._
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl._
import java.time.{LocalDate, Instant}

object CouponActor {

  sealed trait Command
  final case class CreateCoupon(
    code: CouponCode,
    discountType: DiscountType,
    discountValue: BigDecimal,
    validFrom: LocalDate,
    validUntil: LocalDate,
    minimumPurchaseAmount: Option[Money],
    maxUsageCount: Option[Int],
    replyTo: ActorRef[CreateCouponResult]
  ) extends Command

  final case class UseCoupon(
    orderAmount: Money,
    customerType: CustomerType,
    productIds: List[ProductId],
    replyTo: ActorRef[UseCouponResult]
  ) extends Command

  final case class ExpireCoupon(
    replyTo: ActorRef[ExpireCouponResult]
  ) extends Command

  sealed trait CreateCouponResult
  final case class CouponCreated(coupon: Coupon) extends CreateCouponResult
  final case class CreateCouponFailed(error: String) extends CreateCouponResult

  sealed trait UseCouponResult
  final case class CouponUsed(discount: Money) extends UseCouponResult
  final case class UseCouponFailed(error: CouponError) extends UseCouponResult

  sealed trait ExpireCouponResult
  case object CouponExpired extends ExpireCouponResult

  sealed trait Event
  final case class CouponCreatedEvent(
    code: CouponCode,
    discountType: DiscountType,
    discountValue: BigDecimal,
    validFrom: LocalDate,
    validUntil: LocalDate,
    minimumPurchaseAmount: Option[Money],
    maxUsageCount: Option[Int],
    occurredAt: Instant = Instant.now()
  ) extends Event

  final case class CouponUsedEvent(
    discount: Money,
    occurredAt: Instant = Instant.now()
  ) extends Event

  final case class CouponExpiredEvent(
    occurredAt: Instant = Instant.now()
  ) extends Event

  sealed trait State
  case object EmptyState extends State
  final case class ActiveState(coupon: Coupon) extends State

  def apply(couponId: CouponId): Behavior[Command] = {
    Behaviors.setup { context =>
      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId.ofUniqueId(couponId.value),
        emptyState = EmptyState,
        commandHandler = commandHandler(context),
        eventHandler = eventHandler
      )
    }
  }

  private def commandHandler(
    context: ActorContext[Command]
  ): (State, Command) => Effect[Event, State] = {
    case (EmptyState, cmd: CreateCoupon) =>
      Effect.persist(CouponCreatedEvent(
        code = cmd.code,
        discountType = cmd.discountType,
        discountValue = cmd.discountValue,
        validFrom = cmd.validFrom,
        validUntil = cmd.validUntil,
        minimumPurchaseAmount = cmd.minimumPurchaseAmount,
        maxUsageCount = cmd.maxUsageCount
      )).thenRun { state: State =>
        state match {
          case ActiveState(coupon) =>
            cmd.replyTo ! CouponCreated(coupon)
          case _ =>
            cmd.replyTo ! CreateCouponFailed("Failed to create coupon")
        }
      }

    case (ActiveState(coupon), cmd: UseCoupon) =>
      val today = LocalDate.now()

      // バリデーション
      if (!coupon.isValid(today)) {
        cmd.replyTo ! UseCouponFailed(CouponError.CouponNotActive(coupon.id))
        Effect.none
      } else if (!coupon.meetsMinimumPurchase(cmd.orderAmount)) {
        cmd.replyTo ! UseCouponFailed(CouponError.CouponNotActive(coupon.id))
        Effect.none
      } else if (!coupon.isApplicableToCustomer(cmd.customerType)) {
        cmd.replyTo ! UseCouponFailed(CouponError.CouponNotActive(coupon.id))
        Effect.none
      } else {
        val discount = coupon.calculateDiscount(cmd.orderAmount)

        Effect.persist(CouponUsedEvent(discount))
          .thenRun { _ =>
            cmd.replyTo ! CouponUsed(discount)
          }
      }

    case (ActiveState(_), cmd: ExpireCoupon) =>
      Effect.persist(CouponExpiredEvent())
        .thenRun { _ =>
          cmd.replyTo ! CouponExpired
        }

    case _ =>
      Effect.none
  }

  private def eventHandler: (State, Event) => State = {
    case (EmptyState, evt: CouponCreatedEvent) =>
      ActiveState(Coupon(
        id = CouponId.generate(),
        code = evt.code,
        discountType = evt.discountType,
        discountValue = evt.discountValue,
        validFrom = evt.validFrom,
        validUntil = evt.validUntil,
        minimumPurchaseAmount = evt.minimumPurchaseAmount,
        maxUsageCount = evt.maxUsageCount,
        currentUsageCount = 0,
        applicableCustomerTypes = List.empty,
        applicableProducts = None,
        status = CouponStatus.Active,
        version = Version.initial
      ))

    case (ActiveState(coupon), _: CouponUsedEvent) =>
      ActiveState(coupon.copy(
        currentUsageCount = coupon.currentUsageCount + 1
      ))

    case (ActiveState(coupon), _: CouponExpiredEvent) =>
      ActiveState(coupon.copy(status = CouponStatus.Expired))

    case (state, _) =>
      state
  }
}
```

**実装のポイント**:
- クーポンの有効期限、使用回数、最低購入金額のバリデーション
- 顧客タイプや商品による適用条件の制御
- 率引き・額引きの両方に対応した割引計算
- イベントソーシングによる使用履歴の記録

### 演習2: 配送管理との統合

**目標**: 配送ステータスの追跡と配送完了の自動通知を実装する

**実装すべき機能**:

1. **DeliveryTracking集約の実装**

```scala
package com.example.shipping.domain

import java.time.{LocalDate, Instant}

// 配送追跡集約
final case class DeliveryTracking(
  trackingId: TrackingId,
  orderId: OrderId,
  shippingInstructionId: ShippingInstructionId,
  customerId: CustomerId,
  shippingAddress: Address,
  status: DeliveryStatus,
  estimatedDeliveryDate: LocalDate,
  actualDeliveryDate: Option[LocalDate],
  carrier: Carrier,
  trackingNumber: TrackingNumber,
  trackingHistory: List[TrackingEvent],
  version: Version
) {
  // 配送ステータスを更新
  def updateStatus(
    newStatus: DeliveryStatus,
    location: String,
    note: Option[String]
  ): DeliveryTracking = {
    val event = TrackingEvent(
      status = newStatus,
      location = location,
      note = note,
      occurredAt = Instant.now()
    )
    copy(
      status = newStatus,
      trackingHistory = trackingHistory :+ event,
      version = version.increment
    )
  }

  // 配送完了
  def complete(actualDate: LocalDate): DeliveryTracking = {
    copy(
      status = DeliveryStatus.Delivered,
      actualDeliveryDate = Some(actualDate),
      version = version.increment
    )
  }

  // 配送が遅延しているかチェック
  def isDelayed(today: LocalDate): Boolean = {
    status != DeliveryStatus.Delivered && today.isAfter(estimatedDeliveryDate)
  }
}

final case class TrackingId(value: String) extends AnyVal
final case class ShippingInstructionId(value: String) extends AnyVal
final case class TrackingNumber(value: String) extends AnyVal

sealed trait DeliveryStatus
object DeliveryStatus {
  case object Preparing extends DeliveryStatus       // 準備中
  case object PickedUp extends DeliveryStatus        // 集荷完了
  case object InTransit extends DeliveryStatus       // 配送中
  case object OutForDelivery extends DeliveryStatus  // 配達中
  case object Delivered extends DeliveryStatus       // 配達完了
  case object Failed extends DeliveryStatus          // 配達失敗
}

sealed trait Carrier
object Carrier {
  case object YamatoTransport extends Carrier    // ヤマト運輸
  case object SagawaExpress extends Carrier      // 佐川急便
  case object JapanPost extends Carrier          // 日本郵便
}

final case class TrackingEvent(
  status: DeliveryStatus,
  location: String,
  note: Option[String],
  occurredAt: Instant
)
```

2. **イベント統合の実装**

```scala
package com.example.integration

import com.example.order.adapter.actor.OrderActor
import com.example.shipping.domain._
import org.apache.pekko.persistence.query._
import org.apache.pekko.stream.scaladsl._
import zio._

// OrderShippedイベント → 配送管理へ配送指示
class OrderShippedProjection(
  eventsByTagQuery: EventsByTagQuery,
  deliveryTrackingService: DeliveryTrackingService
) {

  def start(): Task[Unit] = {
    ZIO.attemptBlocking {
      eventsByTagQuery
        .eventsByTag("order-shipped", NoOffset)
        .mapAsync(parallelism = 4) { envelope =>
          envelope.event match {
            case OrderActor.OrderShipped(orderId, shippedItems, shippedAt, _) =>
              createShippingInstruction(orderId, shippedItems).runToFuture
            case _ =>
              Future.successful(())
          }
        }
        .runForeach { _ => () }
    }
  }

  private def createShippingInstruction(
    orderId: OrderId,
    shippedItems: List[ShippedItem]
  ): Task[Unit] = {
    for {
      order <- getOrderDetails(orderId)
      tracking <- deliveryTrackingService.createTracking(
        orderId = orderId,
        customerId = order.customerId,
        shippingAddress = order.shippingAddress,
        items = shippedItems,
        estimatedDeliveryDate = calculateEstimatedDeliveryDate(order.shippingAddress)
      )
      _ <- ZIO.logInfo(s"Created delivery tracking ${tracking.trackingId.value} for order ${orderId.value}")
    } yield ()
  }

  private def calculateEstimatedDeliveryDate(address: Address): LocalDate = {
    // 配送先の地域に応じて配達予定日を計算
    LocalDate.now().plusDays(2)
  }

  private def getOrderDetails(orderId: OrderId): Task[OrderDetails] = {
    // 実装省略
    ???
  }
}

// DeliveryStatusUpdatedイベント → 注文管理へステータス通知
class DeliveryStatusProjection(
  eventsByTagQuery: EventsByTagQuery,
  orderRepository: OrderRepository
) {

  def start(): Task[Unit] = {
    ZIO.attemptBlocking {
      eventsByTagQuery
        .eventsByTag("delivery-status-updated", NoOffset)
        .mapAsync(parallelism = 4) { envelope =>
          envelope.event match {
            case DeliveryTrackingActor.StatusUpdated(orderId, status, location, _) =>
              updateOrderDeliveryStatus(orderId, status, location).runToFuture
            case _ =>
              Future.successful(())
          }
        }
        .runForeach { _ => () }
    }
  }

  private def updateOrderDeliveryStatus(
    orderId: OrderId,
    status: DeliveryStatus,
    location: String
  ): Task[Unit] = {
    for {
      _ <- ZIO.logInfo(s"Delivery status updated for order ${orderId.value}: $status at $location")
      // 必要に応じて注文のステータスを更新
    } yield ()
  }
}

// DeliveryCompletedイベント → 注文をDeliveredステータスに更新
class DeliveryCompletedProjection(
  eventsByTagQuery: EventsByTagQuery,
  orderActor: ActorRef[OrderActor.Command]
) {

  def start(): Task[Unit] = {
    ZIO.attemptBlocking {
      eventsByTagQuery
        .eventsByTag("delivery-completed", NoOffset)
        .mapAsync(parallelism = 4) { envelope =>
          envelope.event match {
            case DeliveryTrackingActor.DeliveryCompleted(orderId, actualDate, _) =>
              completeOrder(orderId, actualDate).runToFuture
            case _ =>
              Future.successful(())
          }
        }
        .runForeach { _ => () }
    }
  }

  private def completeOrder(
    orderId: OrderId,
    actualDate: LocalDate
  ): Task[Unit] = {
    for {
      _ <- ZIO.attemptBlocking {
        orderActor ! OrderActor.CompleteDelivery(
          deliveredAt = actualDate.atStartOfDay(),
          replyTo = ???
        )
      }
      _ <- ZIO.logInfo(s"Order ${orderId.value} marked as delivered")
    } yield ()
  }
}
```

**実装のポイント**:
- OrderShippedイベントをリスンして配送追跡を開始
- 配送ステータスの変更を注文管理システムに通知
- 配送完了イベントで注文をDeliveredステータスに自動更新
- イベント駆動による疎結合な統合

### 演習3: 売上分析機能

**目標**: 売上データを集計し、ダッシュボードで可視化する

**実装すべき機能**:

1. **SalesAnalytics読み取りモデル**

```scala
package com.example.order.adapter.dao

import com.example.order.domain._
import java.time.LocalDate

// 顧客別売上レポート
final case class CustomerSalesReport(
  customerId: CustomerId,
  customerName: String,
  customerType: CustomerType,
  totalOrders: Int,
  totalRevenue: Money,
  totalQuantity: Int,
  averageOrderValue: Money,
  firstOrderDate: LocalDate,
  lastOrderDate: LocalDate
)

// 商品別売上レポート
final case class ProductSalesReport(
  productId: ProductId,
  productName: String,
  categoryId: CategoryId,
  categoryName: String,
  totalQuantitySold: Int,
  totalRevenue: Money,
  averageUnitPrice: Money,
  salesTrend: List[MonthlySales]
)

// 月次売上
final case class MonthlySales(
  yearMonth: String,
  quantity: Int,
  revenue: Money
)

// 売上トレンドレポート
final case class SalesTrendReport(
  yearMonth: String,
  totalOrders: Int,
  totalRevenue: Money,
  averageOrderValue: Money,
  customerBreakdown: List[CustomerTypeSales],
  topProducts: List[ProductSales]
)

final case class CustomerTypeSales(
  customerType: CustomerType,
  orderCount: Int,
  revenue: Money
)

final case class ProductSales(
  productId: ProductId,
  productName: String,
  quantity: Int,
  revenue: Money
)
```

2. **GraphQL APIの実装**

```scala
package com.example.order.adapter.graphql

import com.example.order.adapter.dao._
import com.example.order.domain._
import sangria.schema._
import java.time.LocalDate

object SalesAnalyticsSchema {

  // Customer Sales Report Type
  val CustomerSalesReportType: ObjectType[Unit, CustomerSalesReport] =
    ObjectType(
      "CustomerSalesReport",
      fields[Unit, CustomerSalesReport](
        Field("customerId", StringType, resolve = _.value.customerId.value),
        Field("customerName", StringType, resolve = _.value.customerName),
        Field("customerType", StringType, resolve = _.value.customerType.toString),
        Field("totalOrders", IntType, resolve = _.value.totalOrders),
        Field("totalRevenue", FloatType, resolve = _.value.totalRevenue.amount.toDouble),
        Field("totalQuantity", IntType, resolve = _.value.totalQuantity),
        Field("averageOrderValue", FloatType, resolve = _.value.averageOrderValue.amount.toDouble),
        Field("firstOrderDate", StringType, resolve = _.value.firstOrderDate.toString),
        Field("lastOrderDate", StringType, resolve = _.value.lastOrderDate.toString)
      )
    )

  // Product Sales Report Type
  val ProductSalesReportType: ObjectType[Unit, ProductSalesReport] =
    ObjectType(
      "ProductSalesReport",
      fields[Unit, ProductSalesReport](
        Field("productId", StringType, resolve = _.value.productId.value),
        Field("productName", StringType, resolve = _.value.productName),
        Field("categoryId", StringType, resolve = _.value.categoryId.value),
        Field("categoryName", StringType, resolve = _.value.categoryName),
        Field("totalQuantitySold", IntType, resolve = _.value.totalQuantitySold),
        Field("totalRevenue", FloatType, resolve = _.value.totalRevenue.amount.toDouble),
        Field("averageUnitPrice", FloatType, resolve = _.value.averageUnitPrice.amount.toDouble),
        Field("salesTrend", ListType(MonthlySalesType), resolve = _.value.salesTrend)
      )
    )

  val MonthlySalesType: ObjectType[Unit, MonthlySales] =
    ObjectType(
      "MonthlySales",
      fields[Unit, MonthlySales](
        Field("yearMonth", StringType, resolve = _.value.yearMonth),
        Field("quantity", IntType, resolve = _.value.quantity),
        Field("revenue", FloatType, resolve = _.value.revenue.amount.toDouble)
      )
    )

  val SalesTrendReportType: ObjectType[Unit, SalesTrendReport] =
    ObjectType(
      "SalesTrendReport",
      fields[Unit, SalesTrendReport](
        Field("yearMonth", StringType, resolve = _.value.yearMonth),
        Field("totalOrders", IntType, resolve = _.value.totalOrders),
        Field("totalRevenue", FloatType, resolve = _.value.totalRevenue.amount.toDouble),
        Field("averageOrderValue", FloatType, resolve = _.value.averageOrderValue.amount.toDouble),
        Field("customerBreakdown", ListType(CustomerTypeSalesType), resolve = _.value.customerBreakdown),
        Field("topProducts", ListType(ProductSalesItemType), resolve = _.value.topProducts)
      )
    )

  val CustomerTypeSalesType: ObjectType[Unit, CustomerTypeSales] =
    ObjectType(
      "CustomerTypeSales",
      fields[Unit, CustomerTypeSales](
        Field("customerType", StringType, resolve = _.value.customerType.toString),
        Field("orderCount", IntType, resolve = _.value.orderCount),
        Field("revenue", FloatType, resolve = _.value.revenue.amount.toDouble)
      )
    )

  val ProductSalesItemType: ObjectType[Unit, ProductSales] =
    ObjectType(
      "ProductSales",
      fields[Unit, ProductSales](
        Field("productId", StringType, resolve = _.value.productId.value),
        Field("productName", StringType, resolve = _.value.productName),
        Field("quantity", IntType, resolve = _.value.quantity),
        Field("revenue", FloatType, resolve = _.value.revenue.amount.toDouble)
      )
    )

  // Query Type
  val QueryType: ObjectType[SalesAnalyticsContext, Unit] = ObjectType(
    "Query",
    fields[SalesAnalyticsContext, Unit](
      Field(
        "customerSales",
        ListType(CustomerSalesReportType),
        arguments = Argument("from", StringType) :: Argument("to", StringType) :: Nil,
        resolve = ctx => {
          val from = LocalDate.parse(ctx.arg[String]("from"))
          val to = LocalDate.parse(ctx.arg[String]("to"))
          ctx.ctx.salesAnalyticsService.getCustomerSales(from, to)
        }
      ),
      Field(
        "productSales",
        ListType(ProductSalesReportType),
        arguments = Argument("from", StringType) :: Argument("to", StringType) :: Nil,
        resolve = ctx => {
          val from = LocalDate.parse(ctx.arg[String]("from"))
          val to = LocalDate.parse(ctx.arg[String]("to"))
          ctx.ctx.salesAnalyticsService.getProductSales(from, to)
        }
      ),
      Field(
        "salesTrend",
        SalesTrendReportType,
        arguments = Argument("yearMonth", StringType) :: Nil,
        resolve = ctx => {
          val yearMonth = ctx.arg[String]("yearMonth")
          ctx.ctx.salesAnalyticsService.getSalesTrend(yearMonth)
        }
      )
    )
  )

  val schema: Schema[SalesAnalyticsContext, Unit] = Schema(QueryType)
}

case class SalesAnalyticsContext(salesAnalyticsService: SalesAnalyticsService)
```

3. **売上分析サービスの実装**

```scala
package com.example.order.usecase

import com.example.order.adapter.dao._
import com.example.order.domain._
import zio._
import java.time.LocalDate

class SalesAnalyticsService(
  orderRepository: OrderRepository
) {

  def getCustomerSales(
    from: LocalDate,
    to: LocalDate
  ): Task[List[CustomerSalesReport]] = {
    for {
      orders <- orderRepository.findByDateRange(from, to)

      // 顧客ごとにグループ化
      customerGroups = orders.groupBy(_.customerId)

      // 顧客別レポートを作成
      reports = customerGroups.map { case (customerId, customerOrders) =>
        val totalRevenue = customerOrders.map(_.totalAmount).foldLeft(Money.zero)(_ + _)
        val totalQuantity = customerOrders.flatMap(_.items).map(_.quantity.value).sum
        val averageOrderValue = if (customerOrders.nonEmpty) {
          totalRevenue / BigDecimal(customerOrders.size)
        } else {
          Money.zero
        }

        CustomerSalesReport(
          customerId = customerId,
          customerName = customerOrders.head.customerName,
          customerType = customerOrders.head.customerType,
          totalOrders = customerOrders.size,
          totalRevenue = totalRevenue,
          totalQuantity = totalQuantity,
          averageOrderValue = averageOrderValue,
          firstOrderDate = customerOrders.map(_.orderedAt.toLocalDate).min,
          lastOrderDate = customerOrders.map(_.orderedAt.toLocalDate).max
        )
      }.toList

      // 売上順にソート
      sortedReports = reports.sortBy(_.totalRevenue.amount)(Ordering[BigDecimal].reverse)

    } yield sortedReports
  }

  def getProductSales(
    from: LocalDate,
    to: LocalDate
  ): Task[List[ProductSalesReport]] = {
    for {
      orders <- orderRepository.findByDateRange(from, to)

      // 全ての注文明細を取得
      allItems = orders.flatMap(_.items)

      // 商品ごとにグループ化
      productGroups = allItems.groupBy(_.productId)

      // 商品別レポートを作成
      reports = productGroups.map { case (productId, items) =>
        val totalQuantity = items.map(_.quantity.value).sum
        val totalRevenue = items.map(i => Money(i.unitPrice.amount * i.quantity.value)).foldLeft(Money.zero)(_ + _)
        val averageUnitPrice = if (items.nonEmpty) {
          totalRevenue / BigDecimal(totalQuantity)
        } else {
          Money.zero
        }

        // 月次トレンドを計算
        val monthlyTrend = calculateMonthlyTrend(productId, from, to)

        ProductSalesReport(
          productId = productId,
          productName = items.head.productName,
          categoryId = items.head.categoryId,
          categoryName = items.head.categoryName,
          totalQuantitySold = totalQuantity,
          totalRevenue = totalRevenue,
          averageUnitPrice = averageUnitPrice,
          salesTrend = monthlyTrend
        )
      }.toList

      // 売上順にソート
      sortedReports = reports.sortBy(_.totalRevenue.amount)(Ordering[BigDecimal].reverse)

    } yield sortedReports
  }

  def getSalesTrend(yearMonth: String): Task[SalesTrendReport] = {
    for {
      ym <- ZIO.attempt(java.time.YearMonth.parse(yearMonth))
      from = ym.atDay(1)
      to = ym.atEndOfMonth()

      orders <- orderRepository.findByDateRange(from, to)

      totalRevenue = orders.map(_.totalAmount).foldLeft(Money.zero)(_ + _)
      averageOrderValue = if (orders.nonEmpty) {
        totalRevenue / BigDecimal(orders.size)
      } else {
        Money.zero
      }

      // 顧客タイプ別内訳
      customerBreakdown = orders.groupBy(_.customerType).map { case (customerType, typeOrders) =>
        CustomerTypeSales(
          customerType = customerType,
          orderCount = typeOrders.size,
          revenue = typeOrders.map(_.totalAmount).foldLeft(Money.zero)(_ + _)
        )
      }.toList

      // トップ10商品
      topProducts = calculateTopProducts(orders, 10)

    } yield SalesTrendReport(
      yearMonth = yearMonth,
      totalOrders = orders.size,
      totalRevenue = totalRevenue,
      averageOrderValue = averageOrderValue,
      customerBreakdown = customerBreakdown,
      topProducts = topProducts
    )
  }

  private def calculateMonthlyTrend(
    productId: ProductId,
    from: LocalDate,
    to: LocalDate
  ): List[MonthlySales] = {
    // 実装省略（月ごとに集計）
    List.empty
  }

  private def calculateTopProducts(
    orders: List[Order],
    limit: Int
  ): List[ProductSales] = {
    val allItems = orders.flatMap(_.items)
    val productGroups = allItems.groupBy(_.productId)

    productGroups.map { case (productId, items) =>
      ProductSales(
        productId = productId,
        productName = items.head.productName,
        quantity = items.map(_.quantity.value).sum,
        revenue = items.map(i => Money(i.unitPrice.amount * i.quantity.value)).foldLeft(Money.zero)(_ + _)
      )
    }.toList
      .sortBy(_.revenue.amount)(Ordering[BigDecimal].reverse)
      .take(limit)
  }
}
```

**実装のポイント**:
- 顧客別・商品別の売上集計
- 時系列での売上トレンド分析
- GraphQL APIによる柔軟なクエリ対応
- 読み取りモデルの効率的な設計

### 演習4: 与信自動調整機能

**目標**: 取引実績に基づいて与信限度額を自動調整する

**実装すべき機能**:

1. **与信評価サービス** (前述の14.1.3を参照)

2. **自動調整ジョブの実装**

```scala
package com.example.order.adapter.job

import com.example.order.domain._
import com.example.order.usecase.CreditEvaluationService
import zio._
import java.time.YearMonth

// 与信自動調整ジョブ
class AutoCreditAdjustmentJob(
  creditLimitRepository: CreditLimitRepository,
  creditEvaluationService: CreditEvaluationService,
  creditActor: ActorRef[CreditActor.Command]
) {

  def run(): Task[Unit] = {
    val evaluationMonth = YearMonth.now().minusMonths(1)  // 前月を評価

    for {
      _ <- ZIO.logInfo(s"Starting auto credit adjustment for $evaluationMonth")

      // 全顧客の与信限度額を取得
      allCreditLimits <- creditLimitRepository.findAll()

      // 各顧客の与信を評価・調整
      results <- ZIO.foreach(allCreditLimits) { creditLimit =>
        evaluateAndAdjust(creditLimit.customerId, evaluationMonth)
      }

      // 結果のサマリーを出力
      successful = results.count(_.isSuccess)
      failed = results.count(!_.isSuccess)
      _ <- ZIO.logInfo(s"Auto credit adjustment completed: successful=$successful, failed=$failed")

    } yield ()
  }

  private def evaluateAndAdjust(
    customerId: CustomerId,
    evaluationMonth: YearMonth
  ): Task[AdjustmentResult] = {
    (for {
      // 与信を評価
      evaluation <- creditEvaluationService.evaluateCredit(customerId, evaluationMonth)

      // 調整が必要かチェック
      result <- if (evaluation.currentLimit != evaluation.recommendedLimit) {
        for {
          // 与信限度額を調整
          _ <- ZIO.attemptBlocking {
            creditActor ! CreditActor.AdjustLimit(
              customerId = customerId,
              newLimit = evaluation.recommendedLimit,
              reason = evaluation.adjustmentReason,
              replyTo = ???
            )
          }

          _ <- ZIO.logInfo(
            s"Adjusted credit limit for customer ${customerId.value}: " +
            s"${evaluation.currentLimit.amount} → ${evaluation.recommendedLimit.amount} " +
            s"(${evaluation.adjustmentReason})"
          )
        } yield AdjustmentResult.Success(customerId, evaluation)
      } else {
        ZIO.logInfo(s"No adjustment needed for customer ${customerId.value}") *>
          ZIO.succeed(AdjustmentResult.NoChange(customerId))
      }
    } yield result).catchAll { error =>
      ZIO.logError(s"Failed to adjust credit for customer ${customerId.value}: $error") *>
        ZIO.succeed(AdjustmentResult.Failed(customerId, error.getMessage))
    }
  }

  sealed trait AdjustmentResult {
    def customerId: CustomerId
    def isSuccess: Boolean
  }

  object AdjustmentResult {
    final case class Success(
      customerId: CustomerId,
      evaluation: CreditEvaluationService.CreditEvaluation
    ) extends AdjustmentResult {
      def isSuccess: Boolean = true
    }

    final case class NoChange(customerId: CustomerId) extends AdjustmentResult {
      def isSuccess: Boolean = true
    }

    final case class Failed(customerId: CustomerId, reason: String) extends AdjustmentResult {
      def isSuccess: Boolean = false
    }
  }
}
```

3. **ジョブのスケジューリング**

```scala
package com.example.order.adapter.job

import zio._
import java.time.LocalDate

// 月次ジョブスケジューラー
class MonthlyJobScheduler(
  autoCreditAdjustmentJob: AutoCreditAdjustmentJob
) {

  def start(): Task[Unit] = {
    // 毎月1日の午前2時に実行
    scheduleMonthly(
      dayOfMonth = 1,
      hour = 2,
      minute = 0
    )(autoCreditAdjustmentJob.run())
  }

  private def scheduleMonthly(
    dayOfMonth: Int,
    hour: Int,
    minute: Int
  )(job: Task[Unit]): Task[Unit] = {
    def nextExecutionTime: LocalDate = {
      val today = LocalDate.now()
      val thisMonth = today.withDayOfMonth(dayOfMonth)
      if (today.isBefore(thisMonth)) thisMonth else thisMonth.plusMonths(1)
    }

    def calculateDelay: Duration = {
      val next = nextExecutionTime.atTime(hour, minute)
      val now = java.time.LocalDateTime.now()
      val duration = java.time.Duration.between(now, next)
      Duration.fromMillis(duration.toMillis)
    }

    def runAndReschedule: Task[Unit] = {
      for {
        _ <- ZIO.logInfo("Running monthly auto credit adjustment job")
        _ <- job
        _ <- ZIO.sleep(calculateDelay)
        _ <- runAndReschedule
      } yield ()
    }

    for {
      delay <- ZIO.succeed(calculateDelay)
      _ <- ZIO.logInfo(s"Next auto credit adjustment scheduled in ${delay.toHours} hours")
      _ <- ZIO.sleep(delay)
      _ <- runAndReschedule
    } yield ()
  }
}
```

**実装のポイント**:
- 月次での自動評価・調整
- 複数の評価基準の組み合わせ
- 調整結果のログ記録と通知
- スケジューリングによる自動実行

## 14.3 次のステップ

Part 4を完了した皆さんは、エンタープライズグレードの受注管理システムを構築するための重要なスキルを習得しました。ここでは、さらにシステムを発展させるための次のステップを提案します。

### 14.3.1 より複雑なビジネスルールの追加

**階層割引**:

数量や金額に応じた段階的な割引を実装します。

```scala
// 数量割引: 100個以上で5% OFF、500個以上で10% OFF
final case class VolumeDiscount(
  thresholds: List[VolumeThreshold]
) {
  def apply(quantity: Int, unitPrice: Money): Money = {
    val applicableThreshold = thresholds
      .filter(_.minimumQuantity <= quantity)
      .sortBy(_.discountRate)(Ordering[BigDecimal].reverse)
      .headOption

    applicableThreshold match {
      case Some(threshold) =>
        val totalAmount = Money(unitPrice.amount * quantity)
        (totalAmount * threshold.discountRate).round(0)
      case None =>
        Money.zero
    }
  }
}

final case class VolumeThreshold(
  minimumQuantity: Int,
  discountRate: BigDecimal
)

// 使用例
val volumeDiscount = VolumeDiscount(List(
  VolumeThreshold(minimumQuantity = 100, discountRate = BigDecimal("0.05")),  // 5% OFF
  VolumeThreshold(minimumQuantity = 500, discountRate = BigDecimal("0.10"))   // 10% OFF
))
```

**ロイヤリティプログラム**:

ポイント制度と会員ランクを導入します。

```scala
// ポイント集約
final case class LoyaltyPoints(
  customerId: CustomerId,
  totalPoints: Int,
  usedPoints: Int,
  memberRank: MemberRank,
  version: Version
) {
  def availablePoints: Int = totalPoints - usedPoints

  // ポイント付与（購入金額の1%）
  def earn(purchaseAmount: Money): LoyaltyPoints = {
    val earnedPoints = (purchaseAmount.amount / 100).toInt
    copy(
      totalPoints = totalPoints + earnedPoints,
      version = version.increment
    )
  }

  // ポイント使用
  def use(points: Int): Either[PointsError, LoyaltyPoints] = {
    if (points > availablePoints) {
      Left(PointsError.InsufficientPoints(availablePoints, points))
    } else {
      Right(copy(
        usedPoints = usedPoints + points,
        version = version.increment
      ))
    }
  }

  // 会員ランク判定
  def updateRank(): LoyaltyPoints = {
    val newRank = MemberRank.fromPoints(totalPoints)
    copy(memberRank = newRank, version = version.increment)
  }
}

sealed trait MemberRank {
  def discountRate: BigDecimal
}

object MemberRank {
  case object Regular extends MemberRank {
    val discountRate = BigDecimal("0.00")  // 0% OFF
  }

  case object Silver extends MemberRank {
    val discountRate = BigDecimal("0.03")  // 3% OFF
  }

  case object Gold extends MemberRank {
    val discountRate = BigDecimal("0.05")  // 5% OFF
  }

  case object Platinum extends MemberRank {
    val discountRate = BigDecimal("0.10")  // 10% OFF
  }

  def fromPoints(points: Int): MemberRank = points match {
    case p if p >= 100000 => Platinum
    case p if p >= 50000 => Gold
    case p if p >= 10000 => Silver
    case _ => Regular
  }
}
```

**季節限定キャンペーン**:

期間限定の割引キャンペーンを管理します。

```scala
// キャンペーン集約
final case class Campaign(
  id: CampaignId,
  name: String,
  discountType: DiscountType,
  discountValue: BigDecimal,
  validFrom: LocalDate,
  validUntil: LocalDate,
  targetProducts: Option[List[ProductId]],
  targetCustomerTypes: Option[List[CustomerType]],
  status: CampaignStatus,
  version: Version
) {
  def isActive(today: LocalDate): Boolean = {
    status == CampaignStatus.Active &&
    !today.isBefore(validFrom) &&
    !today.isAfter(validUntil)
  }

  def isApplicableTo(
    productId: ProductId,
    customerType: CustomerType
  ): Boolean = {
    val productMatch = targetProducts.forall(_.contains(productId))
    val customerMatch = targetCustomerTypes.forall(_.contains(customerType))
    productMatch && customerMatch
  }

  // キャンペーン終了時の自動無効化
  def expire(): Campaign = {
    copy(status = CampaignStatus.Expired, version = version.increment)
  }
}

sealed trait CampaignStatus
object CampaignStatus {
  case object Active extends CampaignStatus
  case object Expired extends CampaignStatus
  case object Suspended extends CampaignStatus
}

// キャンペーン自動無効化ジョブ
class CampaignExpirationJob(
  campaignRepository: CampaignRepository
) {
  def run(): Task[Unit] = {
    val today = LocalDate.now()

    for {
      activeCampaigns <- campaignRepository.findActive()
      expiredCampaigns = activeCampaigns.filter(c => today.isAfter(c.validUntil))

      _ <- ZIO.foreach(expiredCampaigns) { campaign =>
        campaignRepository.save(campaign.expire())
      }

      _ <- ZIO.logInfo(s"Expired ${expiredCampaigns.size} campaigns")
    } yield ()
  }
}
```

### 14.3.2 他のBounded Contextとの統合

**会計管理コンテキスト**:

受注管理システムから会計システムへ、自動仕訳を連携します。

```scala
// 総勘定元帳への自動仕訳
class AccountingIntegrationProjection(
  eventsByTagQuery: EventsByTagQuery,
  accountingClient: AccountingManagementClient
) {

  def start(): Task[Unit] = {
    ZIO.attemptBlocking {
      eventsByTagQuery
        .eventsByTag("invoice-generated", NoOffset)
        .mapAsync(parallelism = 4) { envelope =>
          envelope.event match {
            case InvoiceActor.InvoiceGenerated(invoice, _) =>
              createJournalEntry(invoice).runToFuture
            case _ =>
              Future.successful(())
          }
        }
        .runForeach { _ => () }
    }
  }

  private def createJournalEntry(invoice: Invoice): Task[Unit] = {
    val entry = JournalEntry(
      date = invoice.issueDate,
      description = s"売上計上 - 請求書${invoice.invoiceNumber.value}",
      debits = List(
        DebitEntry(
          account = AccountCode.AccountsReceivable,  // 売掛金
          amount = invoice.totalAmount
        )
      ),
      credits = List(
        CreditEntry(
          account = AccountCode.Sales,  // 売上高
          amount = invoice.subtotalAmount
        ),
        CreditEntry(
          account = AccountCode.SalesTax,  // 仮受消費税
          amount = invoice.taxAmount
        )
      )
    )

    accountingClient.createJournalEntry(entry)
  }
}
```

**配送管理コンテキスト**:

配送ルートの最適化と配送コストの計算を行います。

```scala
// 配送ルート最適化
class DeliveryRouteOptimizer {

  def optimizeRoutes(
    deliveries: List[DeliveryTask],
    availableVehicles: List[Vehicle]
  ): Task[List[DeliveryRoute]] = {
    for {
      // 配送先を地域ごとにクラスタリング
      clusters <- clusterByRegion(deliveries)

      // 各クラスタに対してルートを最適化
      routes <- ZIO.foreach(clusters) { cluster =>
        optimizeRoute(cluster, availableVehicles)
      }
    } yield routes
  }

  private def clusterByRegion(
    deliveries: List[DeliveryTask]
  ): Task[List[DeliveryCluster]] = {
    // K-means法などで地域をクラスタリング
    ???
  }

  private def optimizeRoute(
    cluster: DeliveryCluster,
    vehicles: List[Vehicle]
  ): Task[DeliveryRoute] = {
    // 巡回セールスマン問題（TSP）を解く
    ???
  }
}

// 配送コスト計算
class DeliveryCostCalculator {

  def calculateCost(route: DeliveryRoute): Money = {
    val baseCost = Money(1000)  // 基本料金
    val distanceCost = Money(route.totalDistance * 50)  // 距離料金（50円/km）
    val weightCost = Money(route.totalWeight * 10)  // 重量料金（10円/kg）

    baseCost + distanceCost + weightCost
  }
}
```

**マーケティングコンテキスト**:

顧客セグメンテーションとレコメンデーションエンジンを実装します。

```scala
// 顧客セグメンテーション
class CustomerSegmentationService(
  orderRepository: OrderRepository
) {

  def segmentCustomers(): Task[List[CustomerSegment]] = {
    for {
      // RFM分析（Recency, Frequency, Monetary）
      customers <- orderRepository.getAllCustomersWithOrders()

      segments = customers.map { case (customer, orders) =>
        val recency = calculateRecency(orders)
        val frequency = orders.size
        val monetary = orders.map(_.totalAmount).foldLeft(Money.zero)(_ + _)

        CustomerSegment(
          customerId = customer.id,
          recencyScore = scoreRecency(recency),
          frequencyScore = scoreFrequency(frequency),
          monetaryScore = scoreMonetary(monetary),
          segment = determineSegment(recency, frequency, monetary)
        )
      }
    } yield segments
  }

  private def determineSegment(
    recency: Int,
    frequency: Int,
    monetary: Money
  ): SegmentType = {
    (recency, frequency, monetary.amount) match {
      case (r, f, m) if r <= 30 && f >= 10 && m >= 1000000 => SegmentType.Champions
      case (r, f, m) if r <= 60 && f >= 5 && m >= 500000 => SegmentType.LoyalCustomers
      case (r, f, m) if r <= 90 => SegmentType.PotentialLoyalists
      case (r, f, m) if r > 180 => SegmentType.AtRisk
      case _ => SegmentType.Regular
    }
  }

  private def calculateRecency(orders: List[Order]): Int = {
    val lastOrderDate = orders.map(_.orderedAt.toLocalDate).max
    java.time.temporal.ChronoUnit.DAYS.between(lastOrderDate, LocalDate.now()).toInt
  }

  private def scoreRecency(days: Int): Int = days match {
    case d if d <= 30 => 5
    case d if d <= 60 => 4
    case d if d <= 90 => 3
    case d if d <= 180 => 2
    case _ => 1
  }

  private def scoreFrequency(count: Int): Int = count match {
    case c if c >= 10 => 5
    case c if c >= 5 => 4
    case c if c >= 3 => 3
    case c if c >= 1 => 2
    case _ => 1
  }

  private def scoreMonetary(amount: Money): Int = amount.amount match {
    case a if a >= 1000000 => 5
    case a if a >= 500000 => 4
    case a if a >= 100000 => 3
    case a if a >= 50000 => 2
    case _ => 1
  }
}

sealed trait SegmentType
object SegmentType {
  case object Champions extends SegmentType          // 優良顧客
  case object LoyalCustomers extends SegmentType    // ロイヤル顧客
  case object PotentialLoyalists extends SegmentType // 潜在ロイヤル顧客
  case object AtRisk extends SegmentType            // 離反リスク顧客
  case object Regular extends SegmentType           // 一般顧客
}

// レコメンデーションエンジン
class ProductRecommendationEngine(
  orderRepository: OrderRepository
) {

  def recommend(
    customerId: CustomerId,
    limit: Int = 5
  ): Task[List[ProductRecommendation]] = {
    for {
      // 顧客の購入履歴を取得
      customerOrders <- orderRepository.findByCustomer(customerId)
      purchasedProducts = customerOrders.flatMap(_.items.map(_.productId)).distinct

      // 類似顧客を見つける
      similarCustomers <- findSimilarCustomers(customerId, purchasedProducts)

      // 類似顧客が購入した商品を集計
      recommendations <- calculateRecommendations(
        purchasedProducts,
        similarCustomers,
        limit
      )
    } yield recommendations
  }

  private def findSimilarCustomers(
    customerId: CustomerId,
    purchasedProducts: List[ProductId]
  ): Task[List[CustomerId]] = {
    // 協調フィルタリング
    ???
  }

  private def calculateRecommendations(
    excludeProducts: List[ProductId],
    similarCustomers: List[CustomerId],
    limit: Int
  ): Task[List[ProductRecommendation]] = {
    // スコアリングとランキング
    ???
  }
}
```

### 14.3.3 グローバル展開

**複数通貨対応**:

```scala
// 通貨変換レート管理
final case class ExchangeRate(
  fromCurrency: Currency,
  toCurrency: Currency,
  rate: BigDecimal,
  validFrom: Instant,
  validUntil: Option[Instant]
)

class CurrencyConversionService(
  exchangeRateRepository: ExchangeRateRepository
) {

  def convert(
    amount: Money,
    toCurrency: Currency
  ): Task[Money] = {
    if (amount.currency == toCurrency) {
      ZIO.succeed(amount)
    } else {
      for {
        rate <- exchangeRateRepository.findLatestRate(amount.currency, toCurrency)
          .someOrFail(new Exception(s"Exchange rate not found: ${amount.currency} -> $toCurrency"))

        convertedAmount = (amount * rate.rate).round(2)
      } yield Money(convertedAmount.amount, toCurrency)
    }
  }
}
```

**国際税務対応**:

```scala
// 国別税率管理
sealed trait Country
object Country {
  case object Japan extends Country
  case object UnitedStates extends Country
  case object UnitedKingdom extends Country
  case object Germany extends Country
}

final case class CountryTaxRate(
  country: Country,
  taxType: TaxType,
  rate: BigDecimal,
  description: String
)

sealed trait TaxType
object TaxType {
  case object ConsumptionTax extends TaxType  // 消費税（日本）
  case object VAT extends TaxType             // 付加価値税（EU）
  case object GST extends TaxType             // 物品・サービス税（シンガポール等）
  case object SalesTax extends TaxType        // 売上税（米国）
}
```

**多言語対応**:

```scala
// 多言語メッセージ
sealed trait Language
object Language {
  case object Japanese extends Language
  case object English extends Language
  case object Chinese extends Language
}

class I18nMessageService {

  private val messages = Map(
    ("order.created", Language.Japanese) -> "注文が作成されました",
    ("order.created", Language.English) -> "Order created",
    ("order.created", Language.Chinese) -> "订单已创建",

    ("order.confirmed", Language.Japanese) -> "注文が確定しました",
    ("order.confirmed", Language.English) -> "Order confirmed",
    ("order.confirmed", Language.Chinese) -> "订单已确认"
  )

  def getMessage(key: String, language: Language): String = {
    messages.getOrElse((key, language), key)
  }
}
```

## 14.4 Part 4のまとめ

Part 4では、月間50,000件（1日1,600件）の注文を処理する、エンタープライズグレードの受注管理システムを構築しました。

### 技術的な成果

- **CQRS/Event Sourcingによる柔軟なアーキテクチャ**: コマンド側とクエリ側を分離し、それぞれ最適化
- **Sagaパターンによる分散トランザクション**: 5ステップの注文プロセスを確実に実行
- **正確な金額計算と税金処理**: BigDecimalによる誤差のない計算
- **パフォーマンス最適化**: Redisキャッシングと並列処理により10倍のスループット向上
- **包括的な監視とアラート**: Prometheus/Grafana/Slackによるリアルタイム監視

### ビジネス的な成果

- **年商150億円の受注処理を支援**: 安定した大規模トランザクション処理
- **与信管理による貸し倒れリスク最小化**: 動的な与信調整と使用率監視
- **自動化された請求・催促による業務効率化**: 月次締めと入金催促の自動化
- **リアルタイムなビジネスメトリクス**: 売上分析とダッシュボード

### 学んだ重要な概念

1. **ドメイン駆動設計（DDD）**: 集約、値オブジェクト、ドメインイベント
2. **イベントソーシング**: イベントによる状態管理と履歴の完全な記録
3. **CQRS**: コマンドとクエリの責任分離による最適化
4. **Sagaパターン**: 分散トランザクションの調整と補償
5. **金額計算**: BigDecimalによる正確な計算と税金処理
6. **与信管理**: 動的な限度額調整とリスク管理
7. **請求管理**: 月次締めと入金処理の自動化
8. **パフォーマンス最適化**: キャッシング、並列処理、イベントバッチング
9. **運用監視**: メトリクス収集、アラート、ダッシュボード
10. **システム統合**: イベント駆動による疎結合な連携

### 実践演習を通じた学び

4つの実践演習を通じて、以下のスキルを身につけました：

1. **割引クーポン機能**: 複雑なビジネスルールの実装
2. **配送管理との統合**: イベント駆動による他システム連携
3. **売上分析機能**: 集計とGraphQL APIの実装
4. **与信自動調整機能**: 自動化ジョブとスケジューリング

## 14.5 次のステップ

Part 5では、**発注管理機能**を追加し、調達から販売までの一連のサプライチェーンを完成させます。

**Part 5で学ぶ内容**:
- Supplier集約と購入発注管理
- 3-way matching（発注・入荷・請求の突合）
- 仕入先への支払管理
- 在庫管理との連携（入荷による在庫増加）
- 発注Sagaパターン

在庫管理（Part 3）、受注管理（Part 4）、発注管理（Part 5）の3つのコンテキストが連携し、エンドツーエンドのビジネスプロセスを実現します。

---

おめでとうございます！Part 4を完了しました。実践演習に取り組み、学んだ知識を定着させましょう。そして、Part 5でさらなる挑戦を続けてください！
