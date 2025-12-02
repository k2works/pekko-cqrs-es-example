# 【第4部 第6章】Sagaパターン:分散トランザクションの実装

## 本章の目的

複数の集約にまたがる長期実行トランザクション（Long Running Transaction）を、Sagaパターンで実装します。受注プロセスは、Order、Inventory、CreditLimit、Shippingという複数の集約を協調させる必要があります。Sagaパターンにより、分散トランザクションの一貫性を保ちながら、部分的な失敗に対する補償（Compensating Transaction）を実現します。

## 6.1 Sagaパターンの基礎

### 6.1.1 Long Running Transactionの課題

従来のACIDトランザクションは、単一のデータベース内で完結する場合には有効ですが、マイクロサービスやCQRS/Event Sourcingアーキテクチャでは以下の課題があります：

**1. 複数の集約にまたがる処理**

受注プロセスでは、以下の集約を順次操作する必要があります：
- Order集約：注文の作成と確定
- Inventory集約：在庫の引当と確保
- CreditLimit集約：与信枠のチェックと引当
- Shipping集約（第3部で実装済み）：出荷指示の発行

これらの集約は独立したイベントストリームを持ち、それぞれが一貫性境界を形成しています。

**2. 分散トランザクションの一貫性保証**

複数の集約操作を1つのACIDトランザクションで包むことはできません。各集約は独立してイベントを永続化するため、以下のような部分的な失敗が発生する可能性があります：
- 注文は作成されたが、在庫引当に失敗した
- 在庫は引当されたが、与信チェックに失敗した
- 与信はOKだが、出荷指示の発行に失敗した

**3. 部分的な失敗への対応**

部分的な失敗が発生した場合、すでに完了した操作を取り消す必要があります（補償トランザクション）。例えば、与信チェックに失敗した場合、在庫引当を解除しなければなりません。

### 6.1.2 Sagaの設計原則

Sagaパターンは、Long Running Transactionを複数のローカルトランザクションに分割し、各ローカルトランザクションに対応する補償トランザクションを定義することで、全体の一貫性を保ちます。

**Choreography vs Orchestration**

Sagaには2つの実装パターンがあります：

1. **Choreography（振り付け）**
   - 各集約が独立してイベントを発行し、他の集約がそのイベントに反応
   - イベント駆動で疎結合
   - サービス数が増えると、イベントの流れが複雑になる

2. **Orchestration（指揮）**
   - 中央のオーケストレーターがSagaの進行を管理
   - 各ステップの実行と補償を明示的に制御
   - ビジネスフローが可視化され、理解しやすい

**本章ではOrchestrationパターンを採用します。** 受注プロセスは明確なステップがあり、中央で管理した方が理解しやすいためです。

**補償トランザクション（Compensating Transaction）**

各ローカルトランザクションTiに対して、その効果を取り消す補償トランザクションCiを定義します：

```
正常フロー: T1 → T2 → T3 → T4
失敗時（T3で失敗）: T1 → T2 → [T3失敗] → C2 → C1
```

補償トランザクションは、厳密な意味での「ロールバック」ではなく、ビジネス的に等価な逆操作です。例えば、在庫引当（T2）の補償は在庫解放（C2）です。

**べき等性の保証**

Sagaのステップや補償は、ネットワーク障害により複数回実行される可能性があります。そのため、各操作はべき等（同じ操作を複数回実行しても結果が同じ）でなければなりません。

実装方法：
- 一意なリクエストIDを使用
- 重複チェック（既に処理済みか確認）
- 条件付き更新（現在の状態が期待通りか確認してから更新）

## 6.2 注文Sagaの実装

### 6.2.1 Sagaのステップ定義

受注プロセスのSagaは以下のステップで構成されます：

```
┌─────────────────────────────────────────────────────────────┐
│                      Order Saga                              │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ステップ1: Order.CreateOrder                                │
│    ↓                                                          │
│  ステップ2: Inventory.ReserveStock                           │
│    ↓                                                          │
│  ステップ3: CreditLimit.CheckAndReserveCredit                │
│    ↓                                                          │
│  ステップ4: Order.ConfirmOrder                               │
│    ↓                                                          │
│  ステップ5: Shipping.IssueShippingInstruction                │
│    ↓                                                          │
│  完了: SagaCompleted                                          │
│                                                               │
└─────────────────────────────────────────────────────────────┘
```

**正常フロー**

1. **OrderCreated**: 注文を作成（ステータス: Created）
2. **StockReserved**: 在庫を引当（Inventory集約）
3. **CreditApproved**: 与信チェックと与信枠の引当（CreditLimit集約）
4. **OrderConfirmed**: 注文を確定（ステータス: Confirmed）
5. **ShippingInstructionIssued**: 出荷指示を発行（Shipping集約）
6. **SagaCompleted**: Saga完了

**失敗時の補償フロー**

- **StockReservationFailed（ステップ2で失敗）**:
  - C1: Order.CancelOrder（注文をキャンセル）

- **CreditCheckFailed（ステップ3で失敗）**:
  - C2: Inventory.ReleaseStock（在庫引当を解除）
  - C1: Order.CancelOrder（注文をキャンセル）

- **ShippingFailed（ステップ5で失敗）**:
  - C3: CreditLimit.ReleaseCredit（与信枠を解放）
  - C2: Inventory.ReleaseStock（在庫引当を解除）
  - C1: Order.CancelOrder（注文をキャンセル）

### 6.2.2 Sagaのドメインモデル

```scala
package com.example.order.domain.saga

import com.example.order.domain._
import java.time.Instant
import java.util.UUID

// Saga ID
final case class SagaId(value: String) extends AnyVal
object SagaId {
  def generate(): SagaId = SagaId(UUID.randomUUID().toString)
}

// Saga状態
sealed trait SagaStatus
object SagaStatus {
  case object Started extends SagaStatus
  case object InProgress extends SagaStatus
  case object Completed extends SagaStatus
  case object Failed extends SagaStatus
  case object Compensating extends SagaStatus
  case object Compensated extends SagaStatus
}

// Sagaステップ
sealed trait SagaStep
object SagaStep {
  case object OrderCreated extends SagaStep
  case object StockReserved extends SagaStep
  case object CreditApproved extends SagaStep
  case object OrderConfirmed extends SagaStep
  case object ShippingInstructionIssued extends SagaStep
}

// Saga結果
sealed trait SagaResult
object SagaResult {
  final case class Success(sagaId: SagaId, orderId: OrderId) extends SagaResult
  final case class Failure(sagaId: SagaId, orderId: OrderId, reason: String, failedStep: SagaStep) extends SagaResult
}

// Sagaエンティティ
final case class OrderSaga(
  id: SagaId,
  orderId: OrderId,
  customerId: CustomerId,
  status: SagaStatus,
  currentStep: Option[SagaStep],
  completedSteps: List[SagaStep],
  failureReason: Option[String],
  startedAt: Instant,
  completedAt: Option[Instant]
) {

  def start(): OrderSaga = {
    require(status == SagaStatus.Started, "Sagaは既に開始されています")
    copy(status = SagaStatus.InProgress, currentStep = Some(SagaStep.OrderCreated))
  }

  def completeStep(step: SagaStep): OrderSaga = {
    require(currentStep.contains(step), s"現在のステップ${currentStep}と一致しません: $step")

    val nextStep = getNextStep(step)
    val newCompletedSteps = completedSteps :+ step

    nextStep match {
      case Some(next) =>
        copy(
          currentStep = Some(next),
          completedSteps = newCompletedSteps
        )
      case None =>
        // 全ステップ完了
        copy(
          status = SagaStatus.Completed,
          currentStep = None,
          completedSteps = newCompletedSteps,
          completedAt = Some(Instant.now())
        )
    }
  }

  def fail(step: SagaStep, reason: String): OrderSaga = {
    copy(
      status = SagaStatus.Failed,
      currentStep = Some(step),
      failureReason = Some(reason)
    )
  }

  def startCompensation(): OrderSaga = {
    require(status == SagaStatus.Failed, "Sagaは失敗状態ではありません")
    copy(status = SagaStatus.Compensating)
  }

  def completeCompensation(): OrderSaga = {
    require(status == SagaStatus.Compensating, "Saga補償中ではありません")
    copy(
      status = SagaStatus.Compensated,
      currentStep = None,
      completedAt = Some(Instant.now())
    )
  }

  private def getNextStep(currentStep: SagaStep): Option[SagaStep] = {
    currentStep match {
      case SagaStep.OrderCreated => Some(SagaStep.StockReserved)
      case SagaStep.StockReserved => Some(SagaStep.CreditApproved)
      case SagaStep.CreditApproved => Some(SagaStep.OrderConfirmed)
      case SagaStep.OrderConfirmed => Some(SagaStep.ShippingInstructionIssued)
      case SagaStep.ShippingInstructionIssued => None
    }
  }
}
```

## 6.3 Saga Orchestratorの実装

### 6.3.1 OrderSagaOrchestratorアクター

```scala
package com.example.order.adapter.actor

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect}
import com.example.order.domain.saga._
import com.example.order.domain._
import scala.concurrent.duration._
import java.time.Instant

object OrderSagaOrchestrator {

  // コマンド型
  sealed trait Command

  final case class StartSaga(
    orderId: OrderId,
    customerId: CustomerId,
    companyId: CompanyId,
    items: List[OrderItemData],
    replyTo: ActorRef[StartSagaReply]
  ) extends Command

  // ステップ完了通知
  final case class OrderCreatedNotification(orderId: OrderId) extends Command
  final case class StockReservedNotification(orderId: OrderId, reservations: List[StockReservation]) extends Command
  final case class CreditApprovedNotification(orderId: OrderId) extends Command
  final case class OrderConfirmedNotification(orderId: OrderId) extends Command
  final case class ShippingInstructionIssuedNotification(orderId: OrderId) extends Command

  // ステップ失敗通知
  final case class OrderCreationFailed(orderId: OrderId, reason: String) extends Command
  final case class StockReservationFailed(orderId: OrderId, reason: String) extends Command
  final case class CreditCheckFailed(orderId: OrderId, reason: String) extends Command
  final case class OrderConfirmationFailed(orderId: OrderId, reason: String) extends Command
  final case class ShippingInstructionFailed(orderId: OrderId, reason: String) extends Command

  // タイムアウト
  private case class StepTimeout(step: SagaStep) extends Command

  final case class GetSagaStatus(replyTo: ActorRef[GetSagaStatusReply]) extends Command

  // 返信型
  sealed trait Reply

  sealed trait StartSagaReply extends Reply
  final case class SagaStartedReply(sagaId: SagaId) extends StartSagaReply
  final case class StartSagaFailed(reason: String) extends StartSagaReply

  sealed trait GetSagaStatusReply extends Reply
  final case class SagaStatusReply(saga: OrderSaga) extends GetSagaStatusReply
  case object SagaNotFound extends GetSagaStatusReply

  // イベント型
  sealed trait Event {
    def occurredAt: Instant
  }

  final case class SagaStarted(
    sagaId: SagaId,
    orderId: OrderId,
    customerId: CustomerId,
    occurredAt: Instant = Instant.now()
  ) extends Event

  final case class StepCompleted(
    step: SagaStep,
    occurredAt: Instant = Instant.now()
  ) extends Event

  final case class StepFailed(
    step: SagaStep,
    reason: String,
    occurredAt: Instant = Instant.now()
  ) extends Event

  final case class CompensationStarted(
    failedStep: SagaStep,
    occurredAt: Instant = Instant.now()
  ) extends Event

  final case class CompensationCompleted(
    occurredAt: Instant = Instant.now()
  ) extends Event

  final case class SagaCompleted(
    occurredAt: Instant = Instant.now()
  ) extends Event

  // 状態
  sealed trait State
  case object EmptyState extends State
  final case class ActiveState(saga: OrderSaga) extends State

  // アクター生成
  def apply(
    sagaId: SagaId,
    orderActor: ActorRef[OrderActor.Command],
    inventoryActor: ActorRef[InventoryActor.Command],
    creditLimitActor: ActorRef[CreditLimitActor.Command],
    shippingActor: ActorRef[ShippingActor.Command]
  ): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        new OrderSagaOrchestrator(
          context,
          timers,
          sagaId,
          orderActor,
          inventoryActor,
          creditLimitActor,
          shippingActor
        ).behavior()
      }
    }
  }
}

class OrderSagaOrchestrator(
  context: ActorContext[OrderSagaOrchestrator.Command],
  timers: TimerScheduler[OrderSagaOrchestrator.Command],
  sagaId: SagaId,
  orderActor: ActorRef[OrderActor.Command],
  inventoryActor: ActorRef[InventoryActor.Command],
  creditLimitActor: ActorRef[CreditLimitActor.Command],
  shippingActor: ActorRef[ShippingActor.Command]
) {
  import OrderSagaOrchestrator._

  // タイムアウト設定
  private val stockReservationTimeout = 30.seconds
  private val creditCheckTimeout = 10.seconds
  private val orderConfirmationTimeout = 10.seconds
  private val shippingInstructionTimeout = 60.seconds

  def behavior(): EventSourcedBehavior[Command, Event, State] = {
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId(s"OrderSaga-${sagaId.value}"),
      emptyState = EmptyState,
      commandHandler = commandHandler,
      eventHandler = eventHandler
    ).withTagger(_ => Set("saga-events", "order-saga"))
      .withRetention(
        org.apache.pekko.persistence.typed.scaladsl.RetentionCriteria
          .snapshotEvery(numberOfEvents = 10, keepNSnapshots = 2)
      )
  }

  // コマンドハンドラ
  private def commandHandler: (State, Command) => ReplyEffect[Event, State] = {
    case (EmptyState, cmd: StartSaga) => handleStartSaga(cmd)
    case (EmptyState, cmd) => Effect.noReply // Saga未開始
    case (state: ActiveState, cmd) => handleActiveStateCommand(state, cmd)
  }

  // イベントハンドラ
  private def eventHandler: (State, Event) => State = {
    case (EmptyState, evt: SagaStarted) =>
      ActiveState(
        OrderSaga(
          id = evt.sagaId,
          orderId = evt.orderId,
          customerId = evt.customerId,
          status = SagaStatus.Started,
          currentStep = None,
          completedSteps = List.empty,
          failureReason = None,
          startedAt = evt.occurredAt,
          completedAt = None
        )
      )

    case (state: ActiveState, evt: StepCompleted) =>
      ActiveState(state.saga.completeStep(evt.step))

    case (state: ActiveState, evt: StepFailed) =>
      ActiveState(state.saga.fail(evt.step, evt.reason))

    case (state: ActiveState, _: CompensationStarted) =>
      ActiveState(state.saga.startCompensation())

    case (state: ActiveState, _: CompensationCompleted) =>
      ActiveState(state.saga.completeCompensation())

    case (state: ActiveState, _: SagaCompleted) =>
      state // 状態は変わらない（既にcompletedStepsで完了を追跡）

    case (state, _) => state
  }

  // Saga開始処理
  private def handleStartSaga(cmd: StartSaga): ReplyEffect[Event, State] = {
    val event = SagaStarted(sagaId, cmd.orderId, cmd.customerId)

    Effect
      .persist(event)
      .thenRun { _ =>
        // ステップ1: 注文作成
        val replyAdapter = context.messageAdapter[OrderActor.CreateOrderReply] {
          case OrderActor.OrderCreatedReply(orderId) =>
            OrderCreatedNotification(orderId)
          case OrderActor.CreateOrderFailed(error) =>
            OrderCreationFailed(cmd.orderId, error.toString)
        }

        orderActor ! OrderActor.CreateOrder(
          orderId = cmd.orderId,
          customerId = cmd.customerId,
          companyId = cmd.companyId,
          items = cmd.items,
          requestedDeliveryDate = None,
          replyTo = replyAdapter
        )

        // タイムアウトをセット
        timers.startSingleTimer(
          StepTimeout(SagaStep.OrderCreated),
          orderConfirmationTimeout
        )
      }
      .thenReply(cmd.replyTo)(_ => SagaStartedReply(sagaId))
  }

  // ActiveState時のコマンド処理
  private def handleActiveStateCommand(state: ActiveState, cmd: Command): ReplyEffect[Event, State] = {
    cmd match {
      // --- ステップ完了通知 ---
      case OrderCreatedNotification(orderId) =>
        timers.cancel(StepTimeout(SagaStep.OrderCreated))
        Effect
          .persist(StepCompleted(SagaStep.OrderCreated))
          .thenRun { _ =>
            // ステップ2: 在庫引当
            val replyAdapter = context.messageAdapter[InventoryActor.ReserveStockReply] {
              case InventoryActor.StockReservedReply(reservations) =>
                StockReservedNotification(orderId, reservations)
              case InventoryActor.ReserveStockFailed(error) =>
                StockReservationFailed(orderId, error.toString)
            }

            // 在庫引当リクエストを送信
            // （実際には注文明細から必要な在庫を計算）
            inventoryActor ! InventoryActor.ReserveStockForOrder(
              orderId = orderId,
              replyTo = replyAdapter
            )

            timers.startSingleTimer(
              StepTimeout(SagaStep.StockReserved),
              stockReservationTimeout
            )
          }

      case StockReservedNotification(orderId, reservations) =>
        timers.cancel(StepTimeout(SagaStep.StockReserved))
        Effect
          .persist(StepCompleted(SagaStep.StockReserved))
          .thenRun { _ =>
            // ステップ3: 与信チェック
            val replyAdapter = context.messageAdapter[CreditLimitActor.ReserveCreditReply] {
              case CreditLimitActor.CreditReservedReply =>
                CreditApprovedNotification(orderId)
              case CreditLimitActor.ReserveCreditFailed(error) =>
                CreditCheckFailed(orderId, error.toString)
            }

            // 与信枠引当リクエストを送信
            // （実際には注文合計金額を計算）
            creditLimitActor ! CreditLimitActor.ReserveCredit(
              orderId = orderId,
              amount = Money(100000), // 仮の金額
              replyTo = replyAdapter
            )

            timers.startSingleTimer(
              StepTimeout(SagaStep.CreditApproved),
              creditCheckTimeout
            )
          }

      case CreditApprovedNotification(orderId) =>
        timers.cancel(StepTimeout(SagaStep.CreditApproved))
        Effect
          .persist(StepCompleted(SagaStep.CreditApproved))
          .thenRun { _ =>
            // ステップ4: 注文確定
            val replyAdapter = context.messageAdapter[OrderActor.ConfirmOrderReply] {
              case OrderActor.OrderConfirmedReply =>
                OrderConfirmedNotification(orderId)
              case OrderActor.ConfirmOrderFailed(error) =>
                OrderConfirmationFailed(orderId, error.toString)
            }

            orderActor ! OrderActor.ConfirmOrder(replyTo = replyAdapter)

            timers.startSingleTimer(
              StepTimeout(SagaStep.OrderConfirmed),
              orderConfirmationTimeout
            )
          }

      case OrderConfirmedNotification(orderId) =>
        timers.cancel(StepTimeout(SagaStep.OrderConfirmed))
        Effect
          .persist(StepCompleted(SagaStep.OrderConfirmed))
          .thenRun { _ =>
            // ステップ5: 出荷指示
            val replyAdapter = context.messageAdapter[ShippingActor.IssueShippingInstructionReply] {
              case ShippingActor.ShippingInstructionIssuedReply =>
                ShippingInstructionIssuedNotification(orderId)
              case ShippingActor.IssueShippingInstructionFailed(error) =>
                ShippingInstructionFailed(orderId, error.toString)
            }

            shippingActor ! ShippingActor.IssueShippingInstruction(
              orderId = orderId,
              replyTo = replyAdapter
            )

            timers.startSingleTimer(
              StepTimeout(SagaStep.ShippingInstructionIssued),
              shippingInstructionTimeout
            )
          }

      case ShippingInstructionIssuedNotification(orderId) =>
        timers.cancel(StepTimeout(SagaStep.ShippingInstructionIssued))
        Effect
          .persist(StepCompleted(SagaStep.ShippingInstructionIssued))
          .thenRun { _ =>
            // Saga完了
            context.log.info(s"Saga ${sagaId.value} completed successfully for order ${orderId.value}")
          }
          .thenPersist(SagaCompleted())

      // --- ステップ失敗通知 ---
      case StockReservationFailed(orderId, reason) =>
        timers.cancel(StepTimeout(SagaStep.StockReserved))
        handleStepFailure(state, SagaStep.StockReserved, orderId, reason)

      case CreditCheckFailed(orderId, reason) =>
        timers.cancel(StepTimeout(SagaStep.CreditApproved))
        handleStepFailure(state, SagaStep.CreditApproved, orderId, reason)

      case OrderConfirmationFailed(orderId, reason) =>
        timers.cancel(StepTimeout(SagaStep.OrderConfirmed))
        handleStepFailure(state, SagaStep.OrderConfirmed, orderId, reason)

      case ShippingInstructionFailed(orderId, reason) =>
        timers.cancel(StepTimeout(SagaStep.ShippingInstructionIssued))
        handleStepFailure(state, SagaStep.ShippingInstructionIssued, orderId, reason)

      // --- タイムアウト ---
      case StepTimeout(step) =>
        context.log.warn(s"Saga ${sagaId.value} step $step timed out")
        handleStepFailure(state, step, state.saga.orderId, s"Step $step timed out")

      // --- クエリ ---
      case GetSagaStatus(replyTo) =>
        Effect.reply(replyTo)(SagaStatusReply(state.saga))

      case _ =>
        Effect.noReply
    }
  }

  // ステップ失敗時の処理
  private def handleStepFailure(
    state: ActiveState,
    failedStep: SagaStep,
    orderId: OrderId,
    reason: String
  ): ReplyEffect[Event, State] = {
    context.log.error(s"Saga ${sagaId.value} failed at step $failedStep: $reason")

    Effect
      .persist(StepFailed(failedStep, reason))
      .thenPersist(CompensationStarted(failedStep))
      .thenRun { _ =>
        // 補償処理を開始
        startCompensation(state.saga.completedSteps, orderId)
      }
  }

  // 補償処理の開始
  private def startCompensation(completedSteps: List[SagaStep], orderId: OrderId): Unit = {
    context.log.info(s"Starting compensation for saga ${sagaId.value}, completed steps: $completedSteps")

    // 完了したステップを逆順に補償
    completedSteps.reverse.foreach {
      case SagaStep.OrderCreated =>
        // C1: 注文キャンセル
        val replyAdapter = context.messageAdapter[OrderActor.CancelOrderReply] {
          case OrderActor.OrderCancelledReply =>
            context.log.info(s"Order ${orderId.value} cancelled as part of compensation")
            context.self ! CompensationCompletedNotification
          case OrderActor.CancelOrderFailed(error) =>
            context.log.error(s"Failed to cancel order ${orderId.value}: $error")
            // 補償失敗時の処理（リトライ、アラート等）
            context.self ! CompensationCompletedNotification
        }
        orderActor ! OrderActor.CancelOrder(reason = "Saga compensation", replyTo = replyAdapter)

      case SagaStep.StockReserved =>
        // C2: 在庫引当解除
        val replyAdapter = context.messageAdapter[InventoryActor.ReleaseStockReply] {
          case InventoryActor.StockReleasedReply =>
            context.log.info(s"Stock released for order ${orderId.value}")
            context.self ! CompensationCompletedNotification
          case InventoryActor.ReleaseStockFailed(error) =>
            context.log.error(s"Failed to release stock for order ${orderId.value}: $error")
            context.self ! CompensationCompletedNotification
        }
        inventoryActor ! InventoryActor.ReleaseStockForOrder(orderId, replyAdapter)

      case SagaStep.CreditApproved =>
        // C3: 与信枠解放
        val replyAdapter = context.messageAdapter[CreditLimitActor.ReleaseCreditReply] {
          case CreditLimitActor.CreditReleasedReply =>
            context.log.info(s"Credit released for order ${orderId.value}")
            context.self ! CompensationCompletedNotification
          case CreditLimitActor.ReleaseCreditFailed(error) =>
            context.log.error(s"Failed to release credit for order ${orderId.value}: $error")
            context.self ! CompensationCompletedNotification
        }
        creditLimitActor ! CreditLimitActor.ReleaseCredit(orderId, replyAdapter)

      case _ =>
        // その他のステップは補償不要または未実装
        context.self ! CompensationCompletedNotification
    }
  }

  // 補償完了通知
  private case object CompensationCompletedNotification extends Command
}
```

## 6.4 タイムアウト処理

### 6.4.1 各ステップのタイムアウト設定

Sagaの各ステップには、適切なタイムアウトを設定します。タイムアウトが発生した場合、そのステップは失敗したとみなし、補償処理を開始します。

```scala
// タイムアウト設定
private val stockReservationTimeout = 30.seconds   // 在庫引当: 30秒
private val creditCheckTimeout = 10.seconds        // 与信チェック: 10秒
private val orderConfirmationTimeout = 10.seconds  // 注文確定: 10秒
private val shippingInstructionTimeout = 60.seconds // 出荷指示: 60秒
```

**タイムアウト値の決定基準**:
- **在庫引当（30秒）**: 複数の倉庫に問い合わせる可能性があるため、やや長めに設定
- **与信チェック（10秒）**: 単一の集約への問い合わせなので短め
- **注文確定（10秒）**: 単純な状態遷移なので短め
- **出荷指示（60秒）**: 外部の出荷管理システムと連携する可能性があるため、最も長く設定

### 6.4.2 タイムアウト時の処理

タイムアウトが発生した場合、以下の処理を実行します：

1. **StepTimeoutイベントの発行**: タイムアウトが発生したことを記録
2. **補償処理の開始**: 完了済みのステップを逆順に補償
3. **リトライ戦略**: 一時的な障害の場合、指数バックオフでリトライ

**リトライ戦略の実装例**:

```scala
final case class RetryPolicy(
  maxRetries: Int = 3,
  initialDelay: FiniteDuration = 1.second,
  maxDelay: FiniteDuration = 30.seconds,
  backoffFactor: Double = 2.0
) {
  def nextDelay(retryCount: Int): FiniteDuration = {
    val delay = initialDelay * math.pow(backoffFactor, retryCount.toDouble)
    FiniteDuration(math.min(delay.toMillis, maxDelay.toMillis), MILLISECONDS)
  }
}

// Sagaステップにリトライカウントを追加
final case class OrderSaga(
  // ... 既存のフィールド ...
  retryCount: Map[SagaStep, Int] = Map.empty,
  retryPolicy: RetryPolicy = RetryPolicy()
) {

  def shouldRetry(step: SagaStep): Boolean = {
    val count = retryCount.getOrElse(step, 0)
    count < retryPolicy.maxRetries
  }

  def incrementRetry(step: SagaStep): OrderSaga = {
    val count = retryCount.getOrElse(step, 0)
    copy(retryCount = retryCount + (step -> (count + 1)))
  }

  def getRetryDelay(step: SagaStep): FiniteDuration = {
    val count = retryCount.getOrElse(step, 0)
    retryPolicy.nextDelay(count)
  }
}

// Orchestratorでリトライを実装
private def handleStepFailure(
  state: ActiveState,
  failedStep: SagaStep,
  orderId: OrderId,
  reason: String
): ReplyEffect[Event, State] = {

  if (state.saga.shouldRetry(failedStep)) {
    // リトライ
    val retryDelay = state.saga.getRetryDelay(failedStep)
    context.log.warn(
      s"Saga ${sagaId.value} step $failedStep failed, retrying in $retryDelay: $reason"
    )

    Effect
      .persist(StepRetryScheduled(failedStep, retryDelay))
      .thenRun { _ =>
        timers.startSingleTimer(
          RetryStep(failedStep),
          retryDelay
        )
      }
  } else {
    // リトライ上限に達したので補償開始
    context.log.error(
      s"Saga ${sagaId.value} failed at step $failedStep after max retries: $reason"
    )

    Effect
      .persist(StepFailed(failedStep, reason))
      .thenPersist(CompensationStarted(failedStep))
      .thenRun { _ =>
        startCompensation(state.saga.completedSteps, orderId)
      }
  }
}
```

## 6.5 べき等性の保証

### 6.5.1 リクエストIDによる重複検出

Sagaの各ステップは、ネットワーク障害やリトライにより複数回実行される可能性があります。そのため、べき等性を保証する必要があります。

```scala
// コマンドにリクエストIDを追加
final case class ReserveStock(
  orderId: OrderId,
  reservations: List[StockReservation],
  requestId: RequestId,  // 追加
  replyTo: ActorRef[ReserveStockReply]
) extends Command

// アクター側で重複チェック
final case class InventoryState(
  inventory: Inventory,
  processedRequests: Set[RequestId] = Set.empty  // 処理済みリクエストIDを記録
)

private def handleReserveStock(state: ActiveState, cmd: ReserveStock): ReplyEffect[Event, State] = {
  if (state.processedRequests.contains(cmd.requestId)) {
    // 重複リクエスト: イベントを永続化せずに成功を返す
    context.log.info(s"Duplicate request ${cmd.requestId.value}, returning cached result")
    Effect.reply(cmd.replyTo)(StockReservedReply)
  } else {
    // 通常処理
    state.inventory.reserve(cmd.orderId, cmd.reservations) match {
      case Right(_) =>
        Effect
          .persist(StockReserved(cmd.orderId, cmd.reservations, cmd.requestId))
          .thenReply(cmd.replyTo)(_ => StockReservedReply)
      case Left(error) =>
        Effect.reply(cmd.replyTo)(ReserveStockFailed(error))
    }
  }
}

// イベントハンドラでprocessedRequestsを更新
private def eventHandler: (State, Event) => State = {
  case (state: ActiveState, evt: StockReserved) =>
    ActiveState(
      inventory = applyEvent(state.inventory, evt),
      processedRequests = state.processedRequests + evt.requestId
    )
  // ...
}
```

### 6.5.2 条件付き更新による競合回避

楽観的ロック（バージョンフィールド）を使用して、条件付き更新を実現します。

```scala
final case class Order(
  // ... 既存のフィールド ...
  version: Version
) {
  def reserveStock(
    reservations: List[StockReservation],
    expectedVersion: Version  // 期待するバージョン
  ): Either[OrderError, Order] = {
    if (version != expectedVersion) {
      return Left(OrderError.VersionMismatch(expectedVersion, version))
    }
    // ... 通常の処理 ...
  }
}
```

## 6.6 Sagaのモニタリングとデバッグ

### 6.6.1 Sagaイベントのクエリ

Pekko Persistenceのイベントクエリを使用して、Sagaの進行状況をモニタリングできます。

```scala
import org.apache.pekko.persistence.query.PersistenceQuery
import org.apache.pekko.persistence.query.scaladsl.EventsByTagQuery

val queries = PersistenceQuery(system).readJournalFor[EventsByTagQuery](
  "pekko.persistence.dynamodb.query"
)

// "saga-events"タグが付いたイベントをストリーミング
val sagaEvents: Source[EventEnvelope, NotUsed] =
  queries.eventsByTag("saga-events", Offset.noOffset)

sagaEvents.runForeach { envelope =>
  println(s"Saga event: ${envelope.event}")
}
```

### 6.6.2 Sagaダッシュボード

進行中のSagaや失敗したSagaをダッシュボードで可視化します。

```scala
final case class SagaStatistics(
  totalStarted: Long,
  totalCompleted: Long,
  totalFailed: Long,
  totalCompensated: Long,
  averageDuration: FiniteDuration,
  failuresByStep: Map[SagaStep, Long]
)

object SagaMonitor {
  def getStatistics(): Future[SagaStatistics] = {
    // イベントストアからSaga統計を集計
    ???
  }

  def getActiveSagas(): Future[List[OrderSaga]] = {
    // 進行中のSagaを取得
    ???
  }

  def getFailedSagas(since: Instant): Future[List[OrderSaga]] = {
    // 指定時刻以降に失敗したSagaを取得
    ???
  }
}
```

## 6.7 まとめ

本章では、Sagaパターンを使用して複数の集約にまたがる受注プロセスを実装しました。

**実装のポイント**:

1. **Orchestrationパターン**: 中央のOrchestratorがSagaの進行を管理し、ビジネスフローを可視化
2. **補償トランザクション**: 各ステップに対応する補償処理を定義し、部分的な失敗に対応
3. **タイムアウト処理**: 各ステップに適切なタイムアウトを設定し、障害を検出
4. **リトライ戦略**: 一時的な障害に対して指数バックオフでリトライ
5. **べき等性の保証**: リクエストIDと条件付き更新により、重複実行を防止
6. **イベントソーシング**: Saga自体もEventSourcedBehaviorとして実装し、全ての状態遷移を記録

**次章以降では**:
- 第7章: 金額計算と税金処理（BigDecimal、Money値オブジェクト、消費税計算）
- 第8章: 与信管理の高度な実装（自動調整、与信超過アラート）
- 第9章: パフォーマンス最適化（キャッシング、読み取りモデルの設計）

Sagaパターンにより、分散環境でも一貫性を保ちながら、柔軟でスケーラブルな受注プロセスを実現できます。
