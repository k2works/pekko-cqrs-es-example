# 【第4部 第5章】複数集約の実装：イベントソーシングによる永続化

## 本章の目的

第4章で設計したドメインモデル（Order、CreditLimit、Invoice、Quotation）を、Apache Pekko Persistenceを使用したイベントソースドアクターとして実装します。イベントソーシングパターンにより、すべての状態変更をイベントとして記録し、集約の状態をイベントの再生によって復元できるようにします。

## 5.1 Order集約の実装

### 5.1.1 EventSourcedBehaviorの基本構造

Order集約をイベントソースドアクターとして実装します。Pekko Persistenceの`EventSourcedBehavior`を使用して、コマンドの受信、イベントの永続化、状態の更新を行います。

```scala
package com.example.order.adapter.actor

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect}
import com.example.order.domain.{Order, OrderId, OrderStatus}
import com.example.order.domain.command._
import com.example.order.domain.event._
import java.time.Instant

object OrderActor {

  // アクターの状態
  sealed trait State
  case object EmptyState extends State
  final case class ActiveState(order: Order) extends State

  // コマンド型の定義
  sealed trait Command

  final case class CreateOrder(
    orderId: OrderId,
    customerId: CustomerId,
    companyId: CompanyId,
    items: List[OrderItemData],
    requestedDeliveryDate: Option[LocalDate],
    replyTo: ActorRef[CreateOrderReply]
  ) extends Command

  final case class ReserveStock(
    reservations: List[StockReservation],
    replyTo: ActorRef[ReserveStockReply]
  ) extends Command

  final case class ApproveCredit(
    replyTo: ActorRef[ApproveCreditReply]
  ) extends Command

  final case class ConfirmOrder(
    replyTo: ActorRef[ConfirmOrderReply]
  ) extends Command

  final case class ShipOrder(
    shippedQuantities: Map[ProductId, Quantity],
    replyTo: ActorRef[ShipOrderReply]
  ) extends Command

  final case class CompleteDelivery(
    deliveryDate: LocalDate,
    replyTo: ActorRef[CompleteDeliveryReply]
  ) extends Command

  final case class CancelOrder(
    reason: String,
    replyTo: ActorRef[CancelOrderReply]
  ) extends Command

  final case class ReturnOrder(
    returnItems: List[ReturnItem],
    replyTo: ActorRef[ReturnOrderReply]
  ) extends Command

  final case class GetOrder(
    replyTo: ActorRef[GetOrderReply]
  ) extends Command

  // 返信型の定義
  sealed trait Reply
  sealed trait CreateOrderReply extends Reply
  final case class OrderCreatedReply(orderId: OrderId) extends CreateOrderReply
  final case class CreateOrderFailed(error: OrderError) extends CreateOrderReply

  sealed trait ReserveStockReply extends Reply
  case object StockReservedReply extends ReserveStockReply
  final case class ReserveStockFailed(error: OrderError) extends ReserveStockReply

  sealed trait ApproveCreditReply extends Reply
  case object CreditApprovedReply extends ApproveCreditReply
  final case class ApproveCreditFailed(error: OrderError) extends ApproveCreditReply

  sealed trait ConfirmOrderReply extends Reply
  case object OrderConfirmedReply extends ConfirmOrderReply
  final case class ConfirmOrderFailed(error: OrderError) extends ConfirmOrderReply

  sealed trait ShipOrderReply extends Reply
  case object OrderShippedReply extends ShipOrderReply
  final case class ShipOrderFailed(error: OrderError) extends ShipOrderReply

  sealed trait CompleteDeliveryReply extends Reply
  case object DeliveryCompletedReply extends CompleteDeliveryReply
  final case class CompleteDeliveryFailed(error: OrderError) extends CompleteDeliveryReply

  sealed trait CancelOrderReply extends Reply
  case object OrderCancelledReply extends CancelOrderReply
  final case class CancelOrderFailed(error: OrderError) extends CancelOrderReply

  sealed trait ReturnOrderReply extends Reply
  case object OrderReturnedReply extends ReturnOrderReply
  final case class ReturnOrderFailed(error: OrderError) extends ReturnOrderReply

  sealed trait GetOrderReply extends Reply
  final case class OrderFound(order: Order) extends GetOrderReply
  case object OrderNotFound extends GetOrderReply

  // イベント型の定義
  sealed trait Event {
    def occurredAt: Instant
  }

  final case class OrderCreated(
    orderId: OrderId,
    customerId: CustomerId,
    companyId: CompanyId,
    orderNumber: OrderNumber,
    orderDate: LocalDate,
    items: List[OrderItem],
    requestedDeliveryDate: Option[LocalDate],
    occurredAt: Instant = Instant.now()
  ) extends Event

  final case class StockReserved(
    reservations: List[StockReservation],
    occurredAt: Instant = Instant.now()
  ) extends Event

  final case class CreditApproved(
    occurredAt: Instant = Instant.now()
  ) extends Event

  final case class OrderConfirmed(
    occurredAt: Instant = Instant.now()
  ) extends Event

  final case class OrderShipped(
    shippedQuantities: Map[ProductId, Quantity],
    shippedAt: Instant,
    occurredAt: Instant = Instant.now()
  ) extends Event

  final case class DeliveryCompleted(
    deliveryDate: LocalDate,
    occurredAt: Instant = Instant.now()
  ) extends Event

  final case class OrderCancelled(
    reason: String,
    cancelledAt: Instant,
    occurredAt: Instant = Instant.now()
  ) extends Event

  final case class OrderReturned(
    returnItems: List[ReturnItem],
    returnedAt: Instant,
    occurredAt: Instant = Instant.now()
  ) extends Event

  // アクターの生成
  def apply(orderId: OrderId): Behavior[Command] = {
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId(s"Order-${orderId.value}"),
      emptyState = EmptyState,
      commandHandler = commandHandler,
      eventHandler = eventHandler
    ).withRetention(
      // スナップショット戦略: 100イベントごとにスナップショット作成
      org.apache.pekko.persistence.typed.scaladsl.RetentionCriteria
        .snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2)
    )
  }

  // コマンドハンドラ
  private def commandHandler: (State, Command) => ReplyEffect[Event, State] = {
    case (EmptyState, cmd: CreateOrder) => handleCreateOrder(cmd)
    case (EmptyState, cmd) => Effect.reply(extractReplyTo(cmd))(createNotFoundReply(cmd))
    case (state: ActiveState, cmd) => handleActiveStateCommand(state, cmd)
  }

  // イベントハンドラ
  private def eventHandler: (State, Event) => State = {
    case (EmptyState, evt: OrderCreated) =>
      ActiveState(orderFromEvent(evt))
    case (state: ActiveState, evt) =>
      ActiveState(applyEvent(state.order, evt))
    case (state, _) => state
  }

  // EmptyState時のコマンド処理
  private def handleCreateOrder(cmd: CreateOrder): ReplyEffect[Event, State] = {
    // 注文番号の生成（実際にはシーケンス生成サービスから取得）
    val orderNumber = OrderNumber(s"ORD-${LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)}-${cmd.orderId.value.take(8)}")

    // OrderItemの作成
    val items = cmd.items.map { itemData =>
      OrderItem(
        productId = itemData.productId,
        quantity = itemData.quantity,
        unitPrice = itemData.unitPrice,
        discountRate = itemData.discountRate,
        taxCategory = itemData.taxCategory,
        taxRate = itemData.taxRate
      )
    }

    // バリデーション
    if (items.isEmpty) {
      Effect.reply(cmd.replyTo)(CreateOrderFailed(OrderError.EmptyOrderItems))
    } else {
      val event = OrderCreated(
        orderId = cmd.orderId,
        customerId = cmd.customerId,
        companyId = cmd.companyId,
        orderNumber = orderNumber,
        orderDate = LocalDate.now(),
        items = items,
        requestedDeliveryDate = cmd.requestedDeliveryDate
      )

      Effect
        .persist(event)
        .thenReply(cmd.replyTo)(_ => OrderCreatedReply(cmd.orderId))
    }
  }

  // ActiveState時のコマンド処理
  private def handleActiveStateCommand(state: ActiveState, cmd: Command): ReplyEffect[Event, State] = {
    cmd match {
      case cmd: ReserveStock =>
        state.order.reserveStock(cmd.reservations) match {
          case Right(_) =>
            Effect
              .persist(StockReserved(cmd.reservations))
              .thenReply(cmd.replyTo)(_ => StockReservedReply)
          case Left(error) =>
            Effect.reply(cmd.replyTo)(ReserveStockFailed(error))
        }

      case cmd: ApproveCredit =>
        state.order.approveCredit() match {
          case Right(_) =>
            Effect
              .persist(CreditApproved())
              .thenReply(cmd.replyTo)(_ => CreditApprovedReply)
          case Left(error) =>
            Effect.reply(cmd.replyTo)(ApproveCreditFailed(error))
        }

      case cmd: ConfirmOrder =>
        state.order.confirm() match {
          case Right(_) =>
            Effect
              .persist(OrderConfirmed())
              .thenReply(cmd.replyTo)(_ => OrderConfirmedReply)
          case Left(error) =>
            Effect.reply(cmd.replyTo)(ConfirmOrderFailed(error))
        }

      case cmd: ShipOrder =>
        state.order.ship(cmd.shippedQuantities) match {
          case Right(_) =>
            Effect
              .persist(OrderShipped(cmd.shippedQuantities, Instant.now()))
              .thenReply(cmd.replyTo)(_ => OrderShippedReply)
          case Left(error) =>
            Effect.reply(cmd.replyTo)(ShipOrderFailed(error))
        }

      case cmd: CompleteDelivery =>
        state.order.deliver(cmd.deliveryDate) match {
          case Right(_) =>
            Effect
              .persist(DeliveryCompleted(cmd.deliveryDate))
              .thenReply(cmd.replyTo)(_ => DeliveryCompletedReply)
          case Left(error) =>
            Effect.reply(cmd.replyTo)(CompleteDeliveryFailed(error))
        }

      case cmd: CancelOrder =>
        state.order.cancel(cmd.reason) match {
          case Right(_) =>
            Effect
              .persist(OrderCancelled(cmd.reason, Instant.now()))
              .thenReply(cmd.replyTo)(_ => OrderCancelledReply)
          case Left(error) =>
            Effect.reply(cmd.replyTo)(CancelOrderFailed(error))
        }

      case cmd: ReturnOrder =>
        state.order.returnOrder(cmd.returnItems) match {
          case Right(_) =>
            Effect
              .persist(OrderReturned(cmd.returnItems, Instant.now()))
              .thenReply(cmd.replyTo)(_ => OrderReturnedReply)
          case Left(error) =>
            Effect.reply(cmd.replyTo)(ReturnOrderFailed(error))
        }

      case cmd: GetOrder =>
        Effect.reply(cmd.replyTo)(OrderFound(state.order))

      case cmd: CreateOrder =>
        Effect.reply(cmd.replyTo)(CreateOrderFailed(OrderError.OrderAlreadyExists))
    }
  }

  // イベントからOrderエンティティを構築
  private def orderFromEvent(evt: OrderCreated): Order = {
    Order(
      id = evt.orderId,
      customerId = evt.customerId,
      companyId = evt.companyId,
      orderNumber = evt.orderNumber,
      orderDate = evt.orderDate,
      quotationId = None,
      items = evt.items,
      shippingAddress = None,
      requestedDeliveryDate = evt.requestedDeliveryDate,
      status = OrderStatus.Created,
      sagaId = None,
      version = Version(1)
    )
  }

  // イベントを既存のOrderに適用
  private def applyEvent(order: Order, event: Event): Order = {
    event match {
      case _: StockReserved =>
        order.copy(status = OrderStatus.StockReserved, version = order.version.increment)

      case _: CreditApproved =>
        order.copy(status = OrderStatus.CreditApproved, version = order.version.increment)

      case _: OrderConfirmed =>
        order.copy(status = OrderStatus.Confirmed, version = order.version.increment)

      case evt: OrderShipped =>
        order.copy(status = OrderStatus.Shipped, version = order.version.increment)

      case _: DeliveryCompleted =>
        order.copy(status = OrderStatus.Delivered, version = order.version.increment)

      case _: OrderCancelled =>
        order.copy(status = OrderStatus.Cancelled, version = order.version.increment)

      case _: OrderReturned =>
        order.copy(status = OrderStatus.Returned, version = order.version.increment)

      case _ => order
    }
  }

  // ヘルパーメソッド
  private def extractReplyTo(cmd: Command): ActorRef[Reply] = {
    cmd match {
      case c: CreateOrder => c.replyTo
      case c: ReserveStock => c.replyTo
      case c: ApproveCredit => c.replyTo
      case c: ConfirmOrder => c.replyTo
      case c: ShipOrder => c.replyTo
      case c: CompleteDelivery => c.replyTo
      case c: CancelOrder => c.replyTo
      case c: ReturnOrder => c.replyTo
      case c: GetOrder => c.replyTo
    }
  }

  private def createNotFoundReply(cmd: Command): Reply = {
    cmd match {
      case _: CreateOrder => CreateOrderFailed(OrderError.OrderNotFound)
      case _: ReserveStock => ReserveStockFailed(OrderError.OrderNotFound)
      case _: ApproveCredit => ApproveCreditFailed(OrderError.OrderNotFound)
      case _: ConfirmOrder => ConfirmOrderFailed(OrderError.OrderNotFound)
      case _: ShipOrder => ShipOrderFailed(OrderError.OrderNotFound)
      case _: CompleteDelivery => CompleteDeliveryFailed(OrderError.OrderNotFound)
      case _: CancelOrder => CancelOrderFailed(OrderError.OrderNotFound)
      case _: ReturnOrder => ReturnOrderFailed(OrderError.OrderNotFound)
      case _: GetOrder => OrderNotFound
    }
  }
}
```

### 5.1.2 スナップショット戦略

イベント数が多くなると、集約の復元時にすべてのイベントを再生するコストが高くなります。そのため、定期的にスナップショット（ある時点での状態のスナップショット）を作成します。

```scala
// スナップショット戦略の設定
.withRetention(
  RetentionCriteria
    .snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2)
    .withDeleteEventsOnSnapshot // スナップショット作成時に古いイベントを削除
)
```

この設定により：
- 100イベントごとにスナップショットを作成
- 最新2つのスナップショットを保持
- スナップショット作成時に、それより古いイベントを削除

### 5.1.3 タグ付けとイベントクエリ

イベントにタグを付けることで、特定の種類のイベントをクエリできます。これは、読み取りモデルの更新や、イベント駆動の処理に使用されます。

```scala
object OrderActor {
  // ... 既存のコード ...

  def apply(orderId: OrderId): Behavior[Command] = {
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId(s"Order-${orderId.value}"),
      emptyState = EmptyState,
      commandHandler = commandHandler,
      eventHandler = eventHandler
    ).withTagger {
      case _: OrderCreated => Set("order-events", "order-created")
      case _: StockReserved => Set("order-events", "stock-reserved")
      case _: CreditApproved => Set("order-events", "credit-approved")
      case _: OrderConfirmed => Set("order-events", "order-confirmed")
      case _: OrderShipped => Set("order-events", "order-shipped")
      case _: DeliveryCompleted => Set("order-events", "delivery-completed")
      case _: OrderCancelled => Set("order-events", "order-cancelled")
      case _: OrderReturned => Set("order-events", "order-returned")
      case _ => Set("order-events")
    }.withRetention(
      RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2)
    )
  }
}
```

## 5.2 CreditLimit集約の実装

CreditLimit集約は、顧客の与信限度額を管理し、注文時の与信チェックと与信枠の引当・解放を行います。

```scala
package com.example.order.adapter.actor

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect}
import com.example.order.domain.{CreditLimit, CustomerId, OrderId, Money}
import java.time.Instant

object CreditLimitActor {

  // アクターの状態
  sealed trait State
  case object EmptyState extends State
  final case class ActiveState(creditLimit: CreditLimit) extends State

  // コマンド型
  sealed trait Command

  final case class SetCreditLimit(
    customerId: CustomerId,
    limitAmount: Money,
    replyTo: ActorRef[SetCreditLimitReply]
  ) extends Command

  final case class ReserveCredit(
    orderId: OrderId,
    amount: Money,
    replyTo: ActorRef[ReserveCreditReply]
  ) extends Command

  final case class ReleaseCredit(
    orderId: OrderId,
    replyTo: ActorRef[ReleaseCreditReply]
  ) extends Command

  final case class UseCredit(
    orderId: OrderId,
    replyTo: ActorRef[UseCreditReply]
  ) extends Command

  final case class RecoverCredit(
    amount: Money,
    replyTo: ActorRef[RecoverCreditReply]
  ) extends Command

  final case class AdjustCreditLimit(
    newLimitAmount: Money,
    replyTo: ActorRef[AdjustCreditLimitReply]
  ) extends Command

  final case class GetCreditLimit(
    replyTo: ActorRef[GetCreditLimitReply]
  ) extends Command

  // 返信型
  sealed trait Reply

  sealed trait SetCreditLimitReply extends Reply
  case object CreditLimitSetReply extends SetCreditLimitReply
  final case class SetCreditLimitFailed(error: CreditError) extends SetCreditLimitReply

  sealed trait ReserveCreditReply extends Reply
  case object CreditReservedReply extends ReserveCreditReply
  final case class ReserveCreditFailed(error: CreditError) extends ReserveCreditReply

  sealed trait ReleaseCreditReply extends Reply
  case object CreditReleasedReply extends ReleaseCreditReply
  final case class ReleaseCreditFailed(error: CreditError) extends ReleaseCreditReply

  sealed trait UseCreditReply extends Reply
  case object CreditUsedReply extends UseCreditReply
  final case class UseCreditFailed(error: CreditError) extends UseCreditReply

  sealed trait RecoverCreditReply extends Reply
  case object CreditRecoveredReply extends RecoverCreditReply
  final case class RecoverCreditFailed(error: CreditError) extends RecoverCreditReply

  sealed trait AdjustCreditLimitReply extends Reply
  case object CreditLimitAdjustedReply extends AdjustCreditLimitReply
  final case class AdjustCreditLimitFailed(error: CreditError) extends AdjustCreditLimitReply

  sealed trait GetCreditLimitReply extends Reply
  final case class CreditLimitFound(creditLimit: CreditLimit) extends GetCreditLimitReply
  case object CreditLimitNotFound extends GetCreditLimitReply

  // イベント型
  sealed trait Event {
    def occurredAt: Instant
  }

  final case class CreditLimitSet(
    customerId: CustomerId,
    limitAmount: Money,
    occurredAt: Instant = Instant.now()
  ) extends Event

  final case class CreditReserved(
    orderId: OrderId,
    amount: Money,
    occurredAt: Instant = Instant.now()
  ) extends Event

  final case class CreditReleased(
    orderId: OrderId,
    amount: Money,
    occurredAt: Instant = Instant.now()
  ) extends Event

  final case class CreditUsed(
    orderId: OrderId,
    amount: Money,
    occurredAt: Instant = Instant.now()
  ) extends Event

  final case class CreditRecovered(
    amount: Money,
    occurredAt: Instant = Instant.now()
  ) extends Event

  final case class CreditLimitAdjusted(
    oldLimitAmount: Money,
    newLimitAmount: Money,
    occurredAt: Instant = Instant.now()
  ) extends Event

  // アクターの生成
  def apply(customerId: CustomerId): Behavior[Command] = {
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId(s"CreditLimit-${customerId.value}"),
      emptyState = EmptyState,
      commandHandler = commandHandler,
      eventHandler = eventHandler
    ).withTagger {
      case _: CreditLimitSet => Set("credit-events", "credit-limit-set")
      case _: CreditReserved => Set("credit-events", "credit-reserved")
      case _: CreditReleased => Set("credit-events", "credit-released")
      case _: CreditUsed => Set("credit-events", "credit-used")
      case _: CreditRecovered => Set("credit-events", "credit-recovered")
      case _: CreditLimitAdjusted => Set("credit-events", "credit-limit-adjusted")
      case _ => Set("credit-events")
    }.withRetention(
      RetentionCriteria.snapshotEvery(numberOfEvents = 50, keepNSnapshots = 2)
    )
  }

  // コマンドハンドラ
  private def commandHandler: (State, Command) => ReplyEffect[Event, State] = {
    case (EmptyState, cmd: SetCreditLimit) => handleSetCreditLimit(cmd)
    case (EmptyState, cmd) => Effect.reply(extractReplyTo(cmd))(createNotFoundReply(cmd))
    case (state: ActiveState, cmd) => handleActiveStateCommand(state, cmd)
  }

  // イベントハンドラ
  private def eventHandler: (State, Event) => State = {
    case (EmptyState, evt: CreditLimitSet) =>
      ActiveState(creditLimitFromEvent(evt))
    case (state: ActiveState, evt) =>
      ActiveState(applyEvent(state.creditLimit, evt))
    case (state, _) => state
  }

  // EmptyState時のコマンド処理
  private def handleSetCreditLimit(cmd: SetCreditLimit): ReplyEffect[Event, State] = {
    if (cmd.limitAmount.amount <= 0) {
      Effect.reply(cmd.replyTo)(SetCreditLimitFailed(CreditError.InvalidLimitAmount))
    } else {
      val event = CreditLimitSet(cmd.customerId, cmd.limitAmount)
      Effect
        .persist(event)
        .thenReply(cmd.replyTo)(_ => CreditLimitSetReply)
    }
  }

  // ActiveState時のコマンド処理
  private def handleActiveStateCommand(state: ActiveState, cmd: Command): ReplyEffect[Event, State] = {
    cmd match {
      case cmd: ReserveCredit =>
        state.creditLimit.reserve(cmd.orderId, cmd.amount) match {
          case Right(_) =>
            Effect
              .persist(CreditReserved(cmd.orderId, cmd.amount))
              .thenReply(cmd.replyTo)(_ => CreditReservedReply)
          case Left(error) =>
            Effect.reply(cmd.replyTo)(ReserveCreditFailed(error))
        }

      case cmd: ReleaseCredit =>
        state.creditLimit.release(cmd.orderId) match {
          case Right(updated) =>
            val amount = state.creditLimit.reservations.getOrElse(cmd.orderId, Money.zero)
            Effect
              .persist(CreditReleased(cmd.orderId, amount))
              .thenReply(cmd.replyTo)(_ => CreditReleasedReply)
          case Left(error) =>
            Effect.reply(cmd.replyTo)(ReleaseCreditFailed(error))
        }

      case cmd: UseCredit =>
        state.creditLimit.use(cmd.orderId) match {
          case Right(updated) =>
            val amount = state.creditLimit.reservations.getOrElse(cmd.orderId, Money.zero)
            Effect
              .persist(CreditUsed(cmd.orderId, amount))
              .thenReply(cmd.replyTo)(_ => CreditUsedReply)
          case Left(error) =>
            Effect.reply(cmd.replyTo)(UseCreditFailed(error))
        }

      case cmd: RecoverCredit =>
        state.creditLimit.recover(cmd.amount) match {
          case Right(_) =>
            Effect
              .persist(CreditRecovered(cmd.amount))
              .thenReply(cmd.replyTo)(_ => CreditRecoveredReply)
          case Left(error) =>
            Effect.reply(cmd.replyTo)(RecoverCreditFailed(error))
        }

      case cmd: AdjustCreditLimit =>
        val oldLimitAmount = state.creditLimit.limitAmount
        if (cmd.newLimitAmount.amount <= 0) {
          Effect.reply(cmd.replyTo)(AdjustCreditLimitFailed(CreditError.InvalidLimitAmount))
        } else {
          Effect
            .persist(CreditLimitAdjusted(oldLimitAmount, cmd.newLimitAmount))
            .thenReply(cmd.replyTo)(_ => CreditLimitAdjustedReply)
        }

      case cmd: GetCreditLimit =>
        Effect.reply(cmd.replyTo)(CreditLimitFound(state.creditLimit))

      case cmd: SetCreditLimit =>
        Effect.reply(cmd.replyTo)(SetCreditLimitFailed(CreditError.CreditLimitAlreadyExists))
    }
  }

  // イベントからCreditLimitエンティティを構築
  private def creditLimitFromEvent(evt: CreditLimitSet): CreditLimit = {
    CreditLimit(
      customerId = evt.customerId,
      limitAmount = evt.limitAmount,
      usedAmount = Money.zero,
      reservations = Map.empty,
      version = Version(1)
    )
  }

  // イベントを既存のCreditLimitに適用
  private def applyEvent(creditLimit: CreditLimit, event: Event): CreditLimit = {
    event match {
      case evt: CreditReserved =>
        creditLimit.copy(
          reservations = creditLimit.reservations + (evt.orderId -> evt.amount),
          version = creditLimit.version.increment
        )

      case evt: CreditReleased =>
        creditLimit.copy(
          reservations = creditLimit.reservations - evt.orderId,
          version = creditLimit.version.increment
        )

      case evt: CreditUsed =>
        creditLimit.copy(
          reservations = creditLimit.reservations - evt.orderId,
          usedAmount = creditLimit.usedAmount + evt.amount,
          version = creditLimit.version.increment
        )

      case evt: CreditRecovered =>
        creditLimit.copy(
          usedAmount = creditLimit.usedAmount - evt.amount,
          version = creditLimit.version.increment
        )

      case evt: CreditLimitAdjusted =>
        creditLimit.copy(
          limitAmount = evt.newLimitAmount,
          version = creditLimit.version.increment
        )

      case _ => creditLimit
    }
  }

  // ヘルパーメソッド（省略）
  private def extractReplyTo(cmd: Command): ActorRef[Reply] = { /* ... */ }
  private def createNotFoundReply(cmd: Command): Reply = { /* ... */ }
}
```

### 5.2.1 与信チェックのビジネスルール

CreditLimit集約は以下のビジネスルールを実装します：

1. **利用可能額の計算**: `利用可能額 = 与信限度額 - 使用額 - 引当済み額`
2. **与信超過チェック**: 引当時に利用可能額を超えていないか確認
3. **二重引当の防止**: 同じOrderIdで複数回引当できないようにする
4. **引当→使用の流れ**: 引当した与信枠を、注文確定時に使用済みに変更

## 5.3 Invoice集約の実装

Invoice集約は、月次の請求書発行と入金記録を管理します。

```scala
package com.example.order.adapter.actor

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect}
import com.example.order.domain.{Invoice, InvoiceId, CustomerId, OrderId, Money, Payment, InvoiceStatus}
import java.time.{Instant, LocalDate, YearMonth}

object InvoiceActor {

  // アクターの状態
  sealed trait State
  case object EmptyState extends State
  final case class ActiveState(invoice: Invoice) extends State

  // コマンド型
  sealed trait Command

  final case class GenerateInvoice(
    invoiceId: InvoiceId,
    customerId: CustomerId,
    billingYearMonth: YearMonth,
    orderIds: List[OrderId],
    totalAmount: Money,
    issueDate: LocalDate,
    dueDate: LocalDate,
    replyTo: ActorRef[GenerateInvoiceReply]
  ) extends Command

  final case class RecordPayment(
    payment: Payment,
    replyTo: ActorRef[RecordPaymentReply]
  ) extends Command

  final case class RemindPayment(
    replyTo: ActorRef[RemindPaymentReply]
  ) extends Command

  final case class GetInvoice(
    replyTo: ActorRef[GetInvoiceReply]
  ) extends Command

  // 返信型
  sealed trait Reply

  sealed trait GenerateInvoiceReply extends Reply
  final case class InvoiceGeneratedReply(invoiceId: InvoiceId) extends GenerateInvoiceReply
  final case class GenerateInvoiceFailed(error: InvoiceError) extends GenerateInvoiceReply

  sealed trait RecordPaymentReply extends Reply
  case object PaymentRecordedReply extends RecordPaymentReply
  final case class RecordPaymentFailed(error: InvoiceError) extends RecordPaymentReply

  sealed trait RemindPaymentReply extends Reply
  case object PaymentRemindedReply extends RemindPaymentReply
  final case class RemindPaymentFailed(error: InvoiceError) extends RemindPaymentReply

  sealed trait GetInvoiceReply extends Reply
  final case class InvoiceFound(invoice: Invoice) extends GetInvoiceReply
  case object InvoiceNotFound extends GetInvoiceReply

  // イベント型
  sealed trait Event {
    def occurredAt: Instant
  }

  final case class InvoiceGenerated(
    invoiceId: InvoiceId,
    customerId: CustomerId,
    billingYearMonth: YearMonth,
    orderIds: List[OrderId],
    totalAmount: Money,
    issueDate: LocalDate,
    dueDate: LocalDate,
    occurredAt: Instant = Instant.now()
  ) extends Event

  final case class PaymentRecorded(
    payment: Payment,
    occurredAt: Instant = Instant.now()
  ) extends Event

  final case class PaymentReminded(
    occurredAt: Instant = Instant.now()
  ) extends Event

  // アクターの生成
  def apply(invoiceId: InvoiceId): Behavior[Command] = {
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId(s"Invoice-${invoiceId.value}"),
      emptyState = EmptyState,
      commandHandler = commandHandler,
      eventHandler = eventHandler
    ).withTagger {
      case _: InvoiceGenerated => Set("invoice-events", "invoice-generated")
      case _: PaymentRecorded => Set("invoice-events", "payment-recorded")
      case _: PaymentReminded => Set("invoice-events", "payment-reminded")
      case _ => Set("invoice-events")
    }.withRetention(
      RetentionCriteria.snapshotEvery(numberOfEvents = 50, keepNSnapshots = 2)
    )
  }

  // コマンドハンドラ
  private def commandHandler: (State, Command) => ReplyEffect[Event, State] = {
    case (EmptyState, cmd: GenerateInvoice) => handleGenerateInvoice(cmd)
    case (EmptyState, cmd) => Effect.reply(extractReplyTo(cmd))(createNotFoundReply(cmd))
    case (state: ActiveState, cmd) => handleActiveStateCommand(state, cmd)
  }

  // イベントハンドラ
  private def eventHandler: (State, Event) => State = {
    case (EmptyState, evt: InvoiceGenerated) =>
      ActiveState(invoiceFromEvent(evt))
    case (state: ActiveState, evt) =>
      ActiveState(applyEvent(state.invoice, evt))
    case (state, _) => state
  }

  // EmptyState時のコマンド処理
  private def handleGenerateInvoice(cmd: GenerateInvoice): ReplyEffect[Event, State] = {
    if (cmd.orderIds.isEmpty) {
      Effect.reply(cmd.replyTo)(GenerateInvoiceFailed(InvoiceError.EmptyOrderIds))
    } else if (cmd.totalAmount.amount <= 0) {
      Effect.reply(cmd.replyTo)(GenerateInvoiceFailed(InvoiceError.InvalidTotalAmount))
    } else {
      val event = InvoiceGenerated(
        invoiceId = cmd.invoiceId,
        customerId = cmd.customerId,
        billingYearMonth = cmd.billingYearMonth,
        orderIds = cmd.orderIds,
        totalAmount = cmd.totalAmount,
        issueDate = cmd.issueDate,
        dueDate = cmd.dueDate
      )

      Effect
        .persist(event)
        .thenReply(cmd.replyTo)(_ => InvoiceGeneratedReply(cmd.invoiceId))
    }
  }

  // ActiveState時のコマンド処理
  private def handleActiveStateCommand(state: ActiveState, cmd: Command): ReplyEffect[Event, State] = {
    cmd match {
      case cmd: RecordPayment =>
        state.invoice.recordPayment(cmd.payment) match {
          case Right(_) =>
            Effect
              .persist(PaymentRecorded(cmd.payment))
              .thenReply(cmd.replyTo)(_ => PaymentRecordedReply)
          case Left(error) =>
            Effect.reply(cmd.replyTo)(RecordPaymentFailed(error))
        }

      case cmd: RemindPayment =>
        if (state.invoice.isOverdue) {
          Effect
            .persist(PaymentReminded())
            .thenReply(cmd.replyTo)(_ => PaymentRemindedReply)
        } else {
          Effect.reply(cmd.replyTo)(RemindPaymentFailed(InvoiceError.InvoiceNotOverdue))
        }

      case cmd: GetInvoice =>
        Effect.reply(cmd.replyTo)(InvoiceFound(state.invoice))

      case cmd: GenerateInvoice =>
        Effect.reply(cmd.replyTo)(GenerateInvoiceFailed(InvoiceError.InvoiceAlreadyExists))
    }
  }

  // イベントからInvoiceエンティティを構築
  private def invoiceFromEvent(evt: InvoiceGenerated): Invoice = {
    Invoice(
      id = evt.invoiceId,
      customerId = evt.customerId,
      billingYearMonth = evt.billingYearMonth,
      orderIds = evt.orderIds,
      totalAmount = evt.totalAmount,
      paidAmount = Money.zero,
      payments = List.empty,
      issueDate = evt.issueDate,
      dueDate = evt.dueDate,
      status = InvoiceStatus.Issued,
      version = Version(1)
    )
  }

  // イベントを既存のInvoiceに適用
  private def applyEvent(invoice: Invoice, event: Event): Invoice = {
    event match {
      case evt: PaymentRecorded =>
        val newPaidAmount = invoice.paidAmount + evt.payment.amount
        val newPayments = invoice.payments :+ evt.payment
        val newStatus = if (newPaidAmount >= invoice.totalAmount) {
          InvoiceStatus.FullyPaid
        } else {
          InvoiceStatus.PartiallyPaid
        }

        invoice.copy(
          paidAmount = newPaidAmount,
          payments = newPayments,
          status = newStatus,
          version = invoice.version.increment
        )

      case _: PaymentReminded =>
        invoice.copy(version = invoice.version.increment)

      case _ => invoice
    }
  }

  // ヘルパーメソッド（省略）
  private def extractReplyTo(cmd: Command): ActorRef[Reply] = { /* ... */ }
  private def createNotFoundReply(cmd: Command): Reply = { /* ... */ }
}
```

### 5.3.1 月次締め処理のビジネスルール

Invoice集約は以下のビジネスルールを実装します：

1. **月次締め**: 対象月のすべての確定注文（Deliveredステータス）を集計
2. **請求書発行**: 集計した注文金額の合計を請求書として発行
3. **入金照合**: 入金記録時に、入金額が請求残高を超えていないか確認
4. **完済判定**: 入金累計額が請求金額と一致したらステータスを「FullyPaid」に変更
5. **未入金アラート**: 支払期日を過ぎても未入金の場合、催促イベントを発行

## 5.4 Quotation集約の実装（補足）

Quotation（見積）集約も同様のパターンで実装します。

```scala
package com.example.order.adapter.actor

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect}
import com.example.order.domain.{Quotation, QuotationId, QuotationStatus}
import java.time.{Instant, LocalDate}

object QuotationActor {

  // アクターの状態
  sealed trait State
  case object EmptyState extends State
  final case class ActiveState(quotation: Quotation) extends State

  // コマンド型
  sealed trait Command

  final case class CreateQuotation(
    quotationId: QuotationId,
    customerId: CustomerId,
    companyId: CompanyId,
    items: List[QuotationItemData],
    validUntil: LocalDate,
    replyTo: ActorRef[CreateQuotationReply]
  ) extends Command

  final case class ApproveQuotation(
    replyTo: ActorRef[ApproveQuotationReply]
  ) extends Command

  final case class RejectQuotation(
    reason: String,
    replyTo: ActorRef[RejectQuotationReply]
  ) extends Command

  final case class ConvertToOrder(
    orderId: OrderId,
    replyTo: ActorRef[ConvertToOrderReply]
  ) extends Command

  final case class GetQuotation(
    replyTo: ActorRef[GetQuotationReply]
  ) extends Command

  // 返信型、イベント型、ハンドラは同様のパターンで実装
  // ...

  def apply(quotationId: QuotationId): Behavior[Command] = {
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId(s"Quotation-${quotationId.value}"),
      emptyState = EmptyState,
      commandHandler = commandHandler,
      eventHandler = eventHandler
    ).withTagger {
      case _: QuotationCreated => Set("quotation-events", "quotation-created")
      case _: QuotationApproved => Set("quotation-events", "quotation-approved")
      case _: QuotationRejected => Set("quotation-events", "quotation-rejected")
      case _: QuotationConvertedToOrder => Set("quotation-events", "quotation-converted")
      case _ => Set("quotation-events")
    }.withRetention(
      RetentionCriteria.snapshotEvery(numberOfEvents = 50, keepNSnapshots = 2)
    )
  }
}
```

## 5.5 イベントのシリアライゼーション

イベントは永続化のためにProtocol Buffersでシリアライズされます。各イベント型に対応する`.proto`ファイルを定義します。

### 5.5.1 Protobuf定義例

```protobuf
syntax = "proto3";

package com.example.order.event;

import "google/protobuf/timestamp.proto";
import "common.proto";

// Order イベント
message OrderCreated {
  string order_id = 1;
  string customer_id = 2;
  string company_id = 3;
  string order_number = 4;
  string order_date = 5;
  repeated OrderItemProto items = 6;
  optional string requested_delivery_date = 7;
  google.protobuf.Timestamp occurred_at = 8;
}

message StockReserved {
  repeated StockReservationProto reservations = 1;
  google.protobuf.Timestamp occurred_at = 2;
}

message CreditApproved {
  google.protobuf.Timestamp occurred_at = 1;
}

message OrderConfirmed {
  google.protobuf.Timestamp occurred_at = 1;
}

// ... 他のイベント定義 ...

// OrderItem用のProtoメッセージ
message OrderItemProto {
  string product_id = 1;
  int32 quantity = 2;
  MoneyProto unit_price = 3;
  optional double discount_rate = 4;
  string tax_category = 5;
  double tax_rate = 6;
}

message MoneyProto {
  string amount = 1;  // BigDecimalを文字列で表現
  string currency = 2;
}

message StockReservationProto {
  string product_id = 1;
  string warehouse_id = 2;
  int32 quantity = 3;
}
```

### 5.5.2 シリアライザの実装

```scala
package com.example.order.adapter.serializer

import org.apache.pekko.serialization.SerializerWithStringManifest
import com.example.order.adapter.actor.OrderActor
import com.example.order.event.{OrderCreatedProto, StockReservedProto, CreditApprovedProto}
import com.google.protobuf.timestamp.Timestamp
import java.nio.charset.StandardCharsets

class OrderEventSerializer extends SerializerWithStringManifest {

  // シリアライザID（application.confで設定する一意のID）
  override def identifier: Int = 100001

  // マニフェスト（イベント型を識別する文字列）
  override def manifest(o: AnyRef): String = o match {
    case _: OrderActor.OrderCreated => "OrderCreated"
    case _: OrderActor.StockReserved => "StockReserved"
    case _: OrderActor.CreditApproved => "CreditApproved"
    case _: OrderActor.OrderConfirmed => "OrderConfirmed"
    case _: OrderActor.OrderShipped => "OrderShipped"
    case _: OrderActor.DeliveryCompleted => "DeliveryCompleted"
    case _: OrderActor.OrderCancelled => "OrderCancelled"
    case _: OrderActor.OrderReturned => "OrderReturned"
    case _ => throw new IllegalArgumentException(s"Unknown event type: ${o.getClass}")
  }

  // シリアライズ
  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case evt: OrderActor.OrderCreated =>
      val proto = OrderCreatedProto(
        orderId = evt.orderId.value,
        customerId = evt.customerId.value,
        companyId = evt.companyId.value,
        orderNumber = evt.orderNumber.value,
        orderDate = evt.orderDate.toString,
        items = evt.items.map(toOrderItemProto),
        requestedDeliveryDate = evt.requestedDeliveryDate.map(_.toString),
        occurredAt = Some(toTimestamp(evt.occurredAt))
      )
      proto.toByteArray

    case evt: OrderActor.StockReserved =>
      val proto = StockReservedProto(
        reservations = evt.reservations.map(toStockReservationProto),
        occurredAt = Some(toTimestamp(evt.occurredAt))
      )
      proto.toByteArray

    case evt: OrderActor.CreditApproved =>
      val proto = CreditApprovedProto(
        occurredAt = Some(toTimestamp(evt.occurredAt))
      )
      proto.toByteArray

    // ... 他のイベント型のシリアライズ ...

    case _ => throw new IllegalArgumentException(s"Cannot serialize: ${o.getClass}")
  }

  // デシリアライズ
  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    case "OrderCreated" =>
      val proto = OrderCreatedProto.parseFrom(bytes)
      OrderActor.OrderCreated(
        orderId = OrderId(proto.orderId),
        customerId = CustomerId(proto.customerId),
        companyId = CompanyId(proto.companyId),
        orderNumber = OrderNumber(proto.orderNumber),
        orderDate = LocalDate.parse(proto.orderDate),
        items = proto.items.map(fromOrderItemProto).toList,
        requestedDeliveryDate = proto.requestedDeliveryDate.map(LocalDate.parse),
        occurredAt = fromTimestamp(proto.occurredAt.get)
      )

    case "StockReserved" =>
      val proto = StockReservedProto.parseFrom(bytes)
      OrderActor.StockReserved(
        reservations = proto.reservations.map(fromStockReservationProto).toList,
        occurredAt = fromTimestamp(proto.occurredAt.get)
      )

    case "CreditApproved" =>
      val proto = CreditApprovedProto.parseFrom(bytes)
      OrderActor.CreditApproved(
        occurredAt = fromTimestamp(proto.occurredAt.get)
      )

    // ... 他のイベント型のデシリアライズ ...

    case _ => throw new IllegalArgumentException(s"Unknown manifest: $manifest")
  }

  // ヘルパーメソッド
  private def toTimestamp(instant: Instant): Timestamp = {
    Timestamp(seconds = instant.getEpochSecond, nanos = instant.getNano)
  }

  private def fromTimestamp(ts: Timestamp): Instant = {
    Instant.ofEpochSecond(ts.seconds, ts.nanos.toLong)
  }

  private def toOrderItemProto(item: OrderItem): OrderItemProto = {
    OrderItemProto(
      productId = item.productId.value,
      quantity = item.quantity.value,
      unitPrice = Some(toMoneyProto(item.unitPrice)),
      discountRate = item.discountRate.map(_.value),
      taxCategory = item.taxCategory.toString,
      taxRate = item.taxRate.value.toDouble
    )
  }

  private def fromOrderItemProto(proto: OrderItemProto): OrderItem = {
    OrderItem(
      productId = ProductId(proto.productId),
      quantity = Quantity(proto.quantity),
      unitPrice = fromMoneyProto(proto.unitPrice.get),
      discountRate = proto.discountRate.map(DiscountRate.apply),
      taxCategory = TaxCategory.valueOf(proto.taxCategory),
      taxRate = TaxRate(BigDecimal(proto.taxRate))
    )
  }

  private def toMoneyProto(money: Money): MoneyProto = {
    MoneyProto(
      amount = money.amount.toString,
      currency = money.currency.toString
    )
  }

  private def fromMoneyProto(proto: MoneyProto): Money = {
    Money(
      amount = BigDecimal(proto.amount),
      currency = Currency.valueOf(proto.currency)
    )
  }

  private def toStockReservationProto(reservation: StockReservation): StockReservationProto = {
    StockReservationProto(
      productId = reservation.productId.value,
      warehouseId = reservation.warehouseId.value,
      quantity = reservation.quantity.value
    )
  }

  private def fromStockReservationProto(proto: StockReservationProto): StockReservation = {
    StockReservation(
      productId = ProductId(proto.productId),
      warehouseId = WarehouseId(proto.warehouseId),
      quantity = Quantity(proto.quantity)
    )
  }
}
```

### 5.5.3 application.confでのシリアライザ登録

```hocon
pekko {
  actor {
    serializers {
      order-event-serializer = "com.example.order.adapter.serializer.OrderEventSerializer"
      credit-event-serializer = "com.example.order.adapter.serializer.CreditEventSerializer"
      invoice-event-serializer = "com.example.order.adapter.serializer.InvoiceEventSerializer"
      quotation-event-serializer = "com.example.order.adapter.serializer.QuotationEventSerializer"
    }

    serialization-bindings {
      "com.example.order.adapter.actor.OrderActor$Event" = order-event-serializer
      "com.example.order.adapter.actor.CreditLimitActor$Event" = credit-event-serializer
      "com.example.order.adapter.actor.InvoiceActor$Event" = invoice-event-serializer
      "com.example.order.adapter.actor.QuotationActor$Event" = quotation-event-serializer
    }
  }
}
```

## 5.6 アクターのテスト

EventSourcedBehaviorのテストには`EventSourcedBehaviorTestKit`を使用します。

```scala
package com.example.order.adapter.actor

import org.apache.pekko.actor.testkit.typed.scaladsl.{ScalaTestWithActorTestKit, EventSourcedBehaviorTestKit}
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.BeforeAndAfterEach
import com.example.order.domain._
import java.time.LocalDate

class OrderActorSpec
    extends ScalaTestWithActorTestKit(EventSourcedBehaviorTestKit.config)
    with AnyWordSpecLike
    with BeforeAndAfterEach {

  private val orderId = OrderId("order-001")
  private val customerId = CustomerId("customer-001")
  private val companyId = CompanyId("company-001")

  private var eventSourcedTestKit: EventSourcedBehaviorTestKit[
    OrderActor.Command,
    OrderActor.Event,
    OrderActor.State
  ] = _

  override def beforeEach(): Unit = {
    super.beforeEach()
    eventSourcedTestKit = EventSourcedBehaviorTestKit[
      OrderActor.Command,
      OrderActor.Event,
      OrderActor.State
    ](system, OrderActor(orderId))
  }

  override def afterEach(): Unit = {
    eventSourcedTestKit.clear()
    super.afterEach()
  }

  "OrderActor" should {
    "create a new order" in {
      val items = List(
        OrderItemData(
          productId = ProductId("product-001"),
          quantity = Quantity(10),
          unitPrice = Money(1000),
          discountRate = None,
          taxCategory = TaxCategory.Standard,
          taxRate = TaxRate(0.10)
        )
      )

      val result = eventSourcedTestKit.runCommand[OrderActor.CreateOrderReply] { replyTo =>
        OrderActor.CreateOrder(
          orderId = orderId,
          customerId = customerId,
          companyId = companyId,
          items = items,
          requestedDeliveryDate = Some(LocalDate.now().plusDays(7)),
          replyTo = replyTo
        )
      }

      result.reply shouldBe OrderActor.OrderCreatedReply(orderId)
      result.event shouldBe a[OrderActor.OrderCreated]
      result.state shouldBe a[OrderActor.ActiveState]

      val activeState = result.stateOfType[OrderActor.ActiveState]
      activeState.order.id shouldBe orderId
      activeState.order.status shouldBe OrderStatus.Created
      activeState.order.items should have size 1
    }

    "reserve stock for an existing order" in {
      // まず注文を作成
      val items = List(
        OrderItemData(
          productId = ProductId("product-001"),
          quantity = Quantity(10),
          unitPrice = Money(1000),
          discountRate = None,
          taxCategory = TaxCategory.Standard,
          taxRate = TaxRate(0.10)
        )
      )

      eventSourcedTestKit.runCommand[OrderActor.CreateOrderReply] { replyTo =>
        OrderActor.CreateOrder(orderId, customerId, companyId, items, None, replyTo)
      }

      // 在庫引当
      val reservations = List(
        StockReservation(
          productId = ProductId("product-001"),
          warehouseId = WarehouseId("warehouse-001"),
          quantity = Quantity(10)
        )
      )

      val result = eventSourcedTestKit.runCommand[OrderActor.ReserveStockReply] { replyTo =>
        OrderActor.ReserveStock(reservations, replyTo)
      }

      result.reply shouldBe OrderActor.StockReservedReply
      result.event shouldBe a[OrderActor.StockReserved]

      val activeState = result.stateOfType[OrderActor.ActiveState]
      activeState.order.status shouldBe OrderStatus.StockReserved
    }

    "reject stock reservation if order does not exist" in {
      val reservations = List(
        StockReservation(
          productId = ProductId("product-001"),
          warehouseId = WarehouseId("warehouse-001"),
          quantity = Quantity(10)
        )
      )

      val result = eventSourcedTestKit.runCommand[OrderActor.ReserveStockReply] { replyTo =>
        OrderActor.ReserveStock(reservations, replyTo)
      }

      result.reply shouldBe a[OrderActor.ReserveStockFailed]
      result.hasNoEvents shouldBe true
    }

    "complete full order lifecycle" in {
      // 1. 注文作成
      val items = List(
        OrderItemData(
          productId = ProductId("product-001"),
          quantity = Quantity(10),
          unitPrice = Money(1000),
          discountRate = Some(DiscountRate(0.05)),
          taxCategory = TaxCategory.Standard,
          taxRate = TaxRate(0.10)
        )
      )

      eventSourcedTestKit.runCommand[OrderActor.CreateOrderReply] { replyTo =>
        OrderActor.CreateOrder(orderId, customerId, companyId, items, None, replyTo)
      }

      // 2. 在庫引当
      val reservations = List(
        StockReservation(ProductId("product-001"), WarehouseId("warehouse-001"), Quantity(10))
      )
      eventSourcedTestKit.runCommand[OrderActor.ReserveStockReply] { replyTo =>
        OrderActor.ReserveStock(reservations, replyTo)
      }

      // 3. 与信承認
      eventSourcedTestKit.runCommand[OrderActor.ApproveCreditReply] { replyTo =>
        OrderActor.ApproveCredit(replyTo)
      }

      // 4. 注文確定
      eventSourcedTestKit.runCommand[OrderActor.ConfirmOrderReply] { replyTo =>
        OrderActor.ConfirmOrder(replyTo)
      }

      // 5. 出荷
      val shippedQuantities = Map(ProductId("product-001") -> Quantity(10))
      eventSourcedTestKit.runCommand[OrderActor.ShipOrderReply] { replyTo =>
        OrderActor.ShipOrder(shippedQuantities, replyTo)
      }

      // 6. 配送完了
      val result = eventSourcedTestKit.runCommand[OrderActor.CompleteDeliveryReply] { replyTo =>
        OrderActor.CompleteDelivery(LocalDate.now(), replyTo)
      }

      val activeState = result.stateOfType[OrderActor.ActiveState]
      activeState.order.status shouldBe OrderStatus.Delivered
    }
  }
}
```

## 5.7 まとめ

本章では、ドメインモデル（Order、CreditLimit、Invoice、Quotation）をApache Pekko Persistenceを使用したイベントソースドアクターとして実装しました。

**実装のポイント**:

1. **EventSourcedBehavior**: コマンドハンドラ、イベントハンドラ、状態管理を統合
2. **コマンドとイベントの分離**: コマンドは意図、イベントは結果を表現
3. **イベントタグ**: イベントにタグを付けることで、イベントクエリと読み取りモデル更新を実現
4. **スナップショット**: 定期的にスナップショットを作成し、復元時のパフォーマンスを向上
5. **Protobufシリアライゼーション**: イベントを効率的かつバージョン管理可能な形式で永続化
6. **テスト**: EventSourcedBehaviorTestKitを使用してコマンド、イベント、状態遷移をテスト

次章では、これらの集約を協調させる**Sagaパターン**を実装し、分散トランザクションを実現します。
