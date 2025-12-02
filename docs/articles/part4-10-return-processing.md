# 【第4部 第10章】返品処理の実装：在庫戻しと与信回復

## 本章の目的

返品処理は、顧客満足度を高めるとともに、在庫と売上の正確性を保つために重要なプロセスです。本章では、返品受付、返品理由の管理、在庫への戻し入れ、返品金額の計算と与信枠の回復について詳しく説明します。返品は注文の逆フローであり、Order集約、Inventory集約、CreditLimit集約が協調して処理します。

## 10.1 返品受付

### 10.1.1 返品理由の分類

返品には様々な理由があり、それぞれ異なる処理が必要です。

```scala
package com.example.order.domain

// 返品理由
sealed trait ReturnReason {
  def code: String
  def description: String
  def isDefective: Boolean  // 不良品か
  def requiresInspection: Boolean  // 検品が必要か
}

object ReturnReason {
  // 不良品（商品の欠陥）
  case object Defective extends ReturnReason {
    val code = "DEFECTIVE"
    val description = "不良品"
    val isDefective = true
    val requiresInspection = true
  }

  // 誤配送（注文と異なる商品が配送された）
  case object WrongItem extends ReturnReason {
    val code = "WRONG_ITEM"
    val description = "誤配送"
    val isDefective = false
    val requiresInspection = true
  }

  // 破損（配送中の破損）
  case object Damaged extends ReturnReason {
    val code = "DAMAGED"
    val description = "破損"
    val isDefective = true
    val requiresInspection = true
  }

  // 顧客都合（注文のキャンセル、誤発注等）
  case object CustomerRequest extends ReturnReason {
    val code = "CUSTOMER_REQUEST"
    val description = "顧客都合"
    val isDefective = false
    val requiresInspection = false
  }

  // 期限切れ
  case object Expired extends ReturnReason {
    val code = "EXPIRED"
    val description = "期限切れ"
    val isDefective = true
    val requiresInspection = false
  }

  def fromCode(code: String): Option[ReturnReason] = code match {
    case "DEFECTIVE" => Some(Defective)
    case "WRONG_ITEM" => Some(WrongItem)
    case "DAMAGED" => Some(Damaged)
    case "CUSTOMER_REQUEST" => Some(CustomerRequest)
    case "EXPIRED" => Some(Expired)
    case _ => None
  }
}

// 返品明細
final case class ReturnItem(
  orderItemId: OrderItemId,  // 元の注文明細ID
  productId: ProductId,
  productName: String,
  quantity: Quantity,  // 返品数量
  unitPrice: Money,  // 単価（元の注文時の単価）
  returnReason: ReturnReason,
  detailedReason: Option[String] = None,  // 詳細理由（自由記述）
  inspectionRequired: Boolean
) {
  require(quantity.value > 0, "返品数量は0より大きい必要があります")

  // 返品金額（税抜）= 単価 × 数量
  def subtotalAmount: Money = unitPrice * quantity.value

  // 税金（元の注文時の税率で計算）
  def taxAmount(taxRate: TaxRate): Money = {
    (subtotalAmount * taxRate.value).round(0, RoundingMode.HALF_UP)
  }

  // 返品合計金額（税込）
  def totalAmount(taxRate: TaxRate): Money = {
    subtotalAmount + taxAmount(taxRate)
  }
}

final case class OrderItemId(value: String) extends AnyVal
```

### 10.1.2 返品ポリシー

返品を受け付ける条件を定義します。

```scala
package com.example.order.domain

import java.time.{LocalDate, Period}

// 返品ポリシー
final case class ReturnPolicy(
  allowedPeriodDays: Int = 30,  // 返品可能期間（配送日から30日以内）
  requiresOriginalPackaging: Boolean = false,  // 元の梱包が必要か
  defectiveReturnPeriodDays: Int = 90,  // 不良品の返品可能期間（90日）
  minimumReturnQuantity: Int = 1,
  maximumReturnQuantity: Option[Int] = None
) {

  // 返品可能か判定
  def canReturn(
    deliveryDate: LocalDate,
    returnReason: ReturnReason,
    today: LocalDate = LocalDate.now()
  ): Either[ReturnPolicyViolation, Unit] = {

    val daysSinceDelivery = Period.between(deliveryDate, today).getDays

    // 返品期間チェック
    val allowedDays = if (returnReason.isDefective) defectiveReturnPeriodDays else allowedPeriodDays

    if (daysSinceDelivery > allowedDays) {
      return Left(ReturnPolicyViolation.ReturnPeriodExpired(
        deliveryDate = deliveryDate,
        allowedDays = allowedDays,
        actualDays = daysSinceDelivery
      ))
    }

    Right(())
  }

  // 返品数量が妥当か判定
  def validateReturnQuantity(
    returnQuantity: Int,
    originalQuantity: Int
  ): Either[ReturnPolicyViolation, Unit] = {

    if (returnQuantity < minimumReturnQuantity) {
      return Left(ReturnPolicyViolation.QuantityTooSmall(returnQuantity, minimumReturnQuantity))
    }

    if (returnQuantity > originalQuantity) {
      return Left(ReturnPolicyViolation.QuantityExceedsOriginal(returnQuantity, originalQuantity))
    }

    maximumReturnQuantity match {
      case Some(max) if returnQuantity > max =>
        Left(ReturnPolicyViolation.QuantityTooLarge(returnQuantity, max))
      case _ =>
        Right(())
    }
  }
}

// 返品ポリシー違反
sealed trait ReturnPolicyViolation

object ReturnPolicyViolation {
  final case class ReturnPeriodExpired(
    deliveryDate: LocalDate,
    allowedDays: Int,
    actualDays: Int
  ) extends ReturnPolicyViolation

  final case class QuantityTooSmall(
    actualQuantity: Int,
    minimumQuantity: Int
  ) extends ReturnPolicyViolation

  final case class QuantityTooLarge(
    actualQuantity: Int,
    maximumQuantity: Int
  ) extends ReturnPolicyViolation

  final case class QuantityExceedsOriginal(
    returnQuantity: Int,
    originalQuantity: Int
  ) extends ReturnPolicyViolation
}
```

### 10.1.3 Order集約での返品処理

第4章で定義したOrder集約のreturnOrder()メソッドを詳細化します。

```scala
package com.example.order.domain

import java.time.{Instant, LocalDate}

final case class Order(
  id: OrderId,
  customerId: CustomerId,
  companyId: CompanyId,
  orderNumber: OrderNumber,
  orderDate: LocalDate,
  items: List[OrderItem],
  deliveryDate: Option[LocalDate],  // 配送日
  status: OrderStatus,
  returnedItems: List[ReturnItem] = List.empty,  // 返品済み明細
  version: Version
) {

  // 返品を受け付ける
  def returnOrder(
    returnItems: List[ReturnItem],
    returnPolicy: ReturnPolicy = ReturnPolicy()
  ): Either[OrderError, Order] = {

    // ステータスチェック: 配送完了済みのみ返品可能
    if (status != OrderStatus.Delivered) {
      return Left(OrderError.InvalidOrderStatus(status, OrderStatus.Delivered))
    }

    // 配送日が記録されているか確認
    val deliveredDate = deliveryDate.getOrElse {
      return Left(OrderError.DeliveryDateNotRecorded(id))
    }

    // 返品期間チェック
    returnPolicy.canReturn(deliveredDate, returnItems.head.returnReason) match {
      case Left(violation) =>
        return Left(OrderError.ReturnPolicyViolation(violation))
      case Right(_) =>
    }

    // 返品明細の検証
    for (returnItem <- returnItems) {
      validateReturnItem(returnItem, returnPolicy) match {
        case Left(error) => return Left(error)
        case Right(_) =>
      }
    }

    // 返品を適用
    Right(copy(
      status = OrderStatus.Returned,
      returnedItems = returnedItems ++ returnItems,
      version = version.increment
    ))
  }

  private def validateReturnItem(
    returnItem: ReturnItem,
    returnPolicy: ReturnPolicy
  ): Either[OrderError, Unit] = {

    // 元の注文明細を検索
    val originalItem = items.find(item =>
      item.productId == returnItem.productId
    ).getOrElse {
      return Left(OrderError.ProductNotInOrder(returnItem.productId, id))
    }

    // 既に返品済みの数量を計算
    val alreadyReturnedQuantity = returnedItems
      .filter(_.productId == returnItem.productId)
      .map(_.quantity.value)
      .sum

    // 返品可能な残数量
    val remainingQuantity = originalItem.quantity.value - alreadyReturnedQuantity

    // 返品数量が残数量を超えていないか確認
    if (returnItem.quantity.value > remainingQuantity) {
      return Left(OrderError.ReturnQuantityExceedsRemaining(
        productId = returnItem.productId,
        requestedQuantity = returnItem.quantity.value,
        remainingQuantity = remainingQuantity
      ))
    }

    // 返品数量がポリシーに準拠しているか確認
    returnPolicy.validateReturnQuantity(
      returnQuantity = returnItem.quantity.value,
      originalQuantity = originalItem.quantity.value
    ) match {
      case Left(violation) =>
        Left(OrderError.ReturnPolicyViolation(violation))
      case Right(_) =>
        Right(())
    }
  }

  // 返品済み金額の合計
  def totalReturnedAmount: Money = {
    // 各返品明細の税率は元の注文明細から取得する必要がある
    Money.sum(returnedItems.map { returnItem =>
      val originalItem = items.find(_.productId == returnItem.productId).get
      returnItem.totalAmount(originalItem.taxRate)
    })
  }

  // 返品控除後の正味金額
  def netAmount: Money = {
    totalAmount - totalReturnedAmount
  }
}

// 注文ステータスに返品を追加
sealed trait OrderStatus
object OrderStatus {
  case object Created extends OrderStatus
  case object StockReserved extends OrderStatus
  case object CreditApproved extends OrderStatus
  case object Confirmed extends OrderStatus
  case object Shipped extends OrderStatus
  case object Delivered extends OrderStatus
  case object Cancelled extends OrderStatus
  case object Returned extends OrderStatus  // 返品済み
  case object PartiallyReturned extends OrderStatus  // 一部返品
}

// 注文エラーに返品関連を追加
sealed trait OrderError
object OrderError {
  // ... 既存のエラー ...

  final case class DeliveryDateNotRecorded(orderId: OrderId) extends OrderError

  final case class ReturnPolicyViolation(violation: ReturnPolicyViolation) extends OrderError

  final case class ProductNotInOrder(productId: ProductId, orderId: OrderId) extends OrderError

  final case class ReturnQuantityExceedsRemaining(
    productId: ProductId,
    requestedQuantity: Int,
    remainingQuantity: Int
  ) extends OrderError
}
```

### 10.1.4 返品受付サービス

```scala
package com.example.order.usecase

import com.example.order.domain._
import com.example.order.adapter.actor.OrderActor
import zio._
import java.time.Instant

class ReturnProcessingService(
  orderRepository: OrderRepository,
  orderActor: ActorRef[OrderActor.Command],
  inventoryActor: ActorRef[InventoryActor.Command],
  creditLimitActor: ActorRef[CreditLimitActor.Command],
  returnPolicy: ReturnPolicy = ReturnPolicy()
) {

  // 返品を受け付ける
  def acceptReturn(
    orderId: OrderId,
    returnItems: List[ReturnItem]
  ): Task[Either[ReturnError, ReturnId]] = {
    for {
      // 注文を取得
      order <- orderRepository.findById(orderId)
        .someOrFail(ReturnError.OrderNotFound(orderId))

      // 返品IDを生成
      returnId = ReturnId.generate()

      // OrderActorに返品コマンドを送信
      result <- sendReturnCommand(orderId, returnItems, returnId)

      // 成功した場合、後続処理を開始
      _ <- result match {
        case Right(_) =>
          // 在庫への戻し入れと与信回復を並行実行
          handleReturnSideEffects(order, returnItems)
        case Left(_) =>
          ZIO.unit
      }
    } yield result
  }

  private def sendReturnCommand(
    orderId: OrderId,
    returnItems: List[ReturnItem],
    returnId: ReturnId
  ): Task[Either[ReturnError, ReturnId]] = {
    val replyPromise = Promise.make[OrderActor.ReturnOrderReply]

    for {
      _ <- ZIO.succeed {
        orderActor ! OrderActor.ReturnOrder(
          returnItems = returnItems,
          replyTo = context.messageAdapter { reply =>
            replyPromise.succeed(reply)
          }
        )
      }
      reply <- replyPromise.await.timeout(10.seconds).some.orElseFail(
        ReturnError.Timeout(orderId)
      )
      result <- reply match {
        case OrderActor.OrderReturnedReply =>
          ZIO.succeed(Right(returnId))
        case OrderActor.ReturnOrderFailed(error) =>
          ZIO.succeed(Left(ReturnError.OrderError(error)))
      }
    } yield result
  }

  private def handleReturnSideEffects(
    order: Order,
    returnItems: List[ReturnItem]
  ): Task[Unit] = {
    ZIO.collectAllPar(
      List(
        // 在庫への戻し入れ
        returnToInventory(order, returnItems),
        // 与信の回復
        recoverCredit(order, returnItems)
      )
    ).unit
  }

  private def returnToInventory(
    order: Order,
    returnItems: List[ReturnItem]
  ): Task[Unit] = {
    ZIO.foreach_(returnItems) { returnItem =>
      val replyPromise = Promise.make[InventoryActor.ReturnToInventoryReply]

      for {
        _ <- ZIO.succeed {
          inventoryActor ! InventoryActor.ReturnToInventory(
            orderId = order.id,
            productId = returnItem.productId,
            quantity = returnItem.quantity,
            returnReason = returnItem.returnReason,
            replyTo = context.messageAdapter { reply =>
              replyPromise.succeed(reply)
            }
          )
        }
        reply <- replyPromise.await.timeout(10.seconds).some.orElse(ZIO.unit)
        _ <- reply match {
          case InventoryActor.ReturnedToInventoryReply =>
            ZIO.logInfo(s"Returned ${returnItem.quantity.value} of ${returnItem.productId.value} to inventory")
          case InventoryActor.ReturnToInventoryFailed(error) =>
            ZIO.logError(s"Failed to return to inventory: $error")
        }
      } yield ()
    }
  }

  private def recoverCredit(
    order: Order,
    returnItems: List[ReturnItem]
  ): Task[Unit] = {
    // 返品金額を計算
    val returnAmount = Money.sum(returnItems.map { returnItem =>
      val originalItem = order.items.find(_.productId == returnItem.productId).get
      returnItem.totalAmount(originalItem.taxRate)
    })

    val replyPromise = Promise.make[CreditLimitActor.RecoverCreditReply]

    for {
      _ <- ZIO.succeed {
        creditLimitActor ! CreditLimitActor.RecoverCredit(
          amount = returnAmount,
          replyTo = context.messageAdapter { reply =>
            replyPromise.succeed(reply)
          }
        )
      }
      reply <- replyPromise.await.timeout(10.seconds).some.orElse(ZIO.unit)
      _ <- reply match {
        case CreditLimitActor.CreditRecoveredReply =>
          ZIO.logInfo(s"Recovered credit ${returnAmount} for customer ${order.customerId.value}")
        case CreditLimitActor.RecoverCreditFailed(error) =>
          ZIO.logError(s"Failed to recover credit: $error")
      }
    } yield ()
  }
}

// 返品ID
final case class ReturnId(value: String) extends AnyVal
object ReturnId {
  def generate(): ReturnId = ReturnId(java.util.UUID.randomUUID().toString)
}

// 返品エラー
sealed trait ReturnError

object ReturnError {
  final case class OrderNotFound(orderId: OrderId) extends ReturnError
  final case class Timeout(orderId: OrderId) extends ReturnError
  final case class OrderError(error: com.example.order.domain.OrderError) extends ReturnError
}
```

## 10.2 在庫への戻し入れ

### 10.2.1 返品在庫の検証

返品された商品は、品質チェックを経て在庫に戻します。

```scala
package com.example.inventory.domain

// 返品在庫の状態
sealed trait ReturnedInventoryStatus

object ReturnedInventoryStatus {
  case object AwaitingInspection extends ReturnedInventoryStatus  // 検品待ち
  case object Inspecting extends ReturnedInventoryStatus          // 検品中
  case object Approved extends ReturnedInventoryStatus            // 承認済み（通常在庫に戻す）
  case object Rejected extends ReturnedInventoryStatus            // 却下（不良品在庫）
}

// 検品結果
final case class InspectionResult(
  returnId: ReturnId,
  productId: ProductId,
  inspectedQuantity: Quantity,
  approvedQuantity: Quantity,  // 承認数量（通常在庫に戻す）
  rejectedQuantity: Quantity,  // 却下数量（不良品在庫）
  inspectionDate: LocalDate,
  inspector: String,
  notes: Option[String] = None
) {
  require(
    approvedQuantity.value + rejectedQuantity.value == inspectedQuantity.value,
    "承認数量と却下数量の合計が検品数量と一致しません"
  )
}

// 返品在庫エンティティ
final case class ReturnedInventory(
  returnId: ReturnId,
  orderId: OrderId,
  productId: ProductId,
  warehouseId: WarehouseId,
  quantity: Quantity,
  returnReason: ReturnReason,
  status: ReturnedInventoryStatus,
  receivedDate: LocalDate,
  inspectionResult: Option[InspectionResult] = None
) {

  // 検品を開始
  def startInspection(): Either[InventoryError, ReturnedInventory] = {
    if (status != ReturnedInventoryStatus.AwaitingInspection) {
      return Left(InventoryError.InvalidReturnedInventoryStatus(status))
    }

    Right(copy(status = ReturnedInventoryStatus.Inspecting))
  }

  // 検品結果を記録
  def recordInspectionResult(
    result: InspectionResult
  ): Either[InventoryError, ReturnedInventory] = {
    if (status != ReturnedInventoryStatus.Inspecting) {
      return Left(InventoryError.InvalidReturnedInventoryStatus(status))
    }

    if (result.inspectedQuantity != quantity) {
      return Left(InventoryError.InspectionQuantityMismatch(
        expected = quantity,
        actual = result.inspectedQuantity
      ))
    }

    val newStatus = if (result.rejectedQuantity.value == 0) {
      ReturnedInventoryStatus.Approved
    } else {
      ReturnedInventoryStatus.Rejected
    }

    Right(copy(
      status = newStatus,
      inspectionResult = Some(result)
    ))
  }
}
```

### 10.2.2 在庫への反映

検品結果に基づいて在庫を更新します。

```scala
package com.example.inventory.adapter.actor

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect}
import com.example.inventory.domain._
import java.time.Instant

object InventoryActor {
  // ... 既存のコマンド ...

  // 返品在庫の受け入れ
  final case class ReturnToInventory(
    orderId: OrderId,
    productId: ProductId,
    quantity: Quantity,
    returnReason: ReturnReason,
    replyTo: ActorRef[ReturnToInventoryReply]
  ) extends Command

  // 検品開始
  final case class StartInspection(
    returnId: ReturnId,
    replyTo: ActorRef[StartInspectionReply]
  ) extends Command

  // 検品結果の記録
  final case class RecordInspectionResult(
    returnId: ReturnId,
    result: InspectionResult,
    replyTo: ActorRef[RecordInspectionResultReply]
  ) extends Command

  // 返信型
  sealed trait ReturnToInventoryReply extends Reply
  case object ReturnedToInventoryReply extends ReturnToInventoryReply
  final case class ReturnToInventoryFailed(error: InventoryError) extends ReturnToInventoryReply

  sealed trait StartInspectionReply extends Reply
  case object InspectionStartedReply extends StartInspectionReply
  final case class StartInspectionFailed(error: InventoryError) extends StartInspectionReply

  sealed trait RecordInspectionResultReply extends Reply
  case object InspectionResultRecordedReply extends RecordInspectionResultReply
  final case class RecordInspectionResultFailed(error: InventoryError) extends RecordInspectionResultReply

  // イベント型
  // ... 既存のイベント ...

  final case class ReturnReceived(
    returnId: ReturnId,
    orderId: OrderId,
    productId: ProductId,
    quantity: Quantity,
    returnReason: ReturnReason,
    occurredAt: Instant = Instant.now()
  ) extends Event

  final case class InspectionStarted(
    returnId: ReturnId,
    occurredAt: Instant = Instant.now()
  ) extends Event

  final case class InspectionResultRecorded(
    returnId: ReturnId,
    result: InspectionResult,
    occurredAt: Instant = Instant.now()
  ) extends Event

  final case class InventoryRestored(
    productId: ProductId,
    warehouseId: WarehouseId,
    quantity: Quantity,  // 通常在庫に戻した数量
    occurredAt: Instant = Instant.now()
  ) extends Event

  final case class DefectiveInventoryAdded(
    productId: ProductId,
    warehouseId: WarehouseId,
    quantity: Quantity,  // 不良品在庫に追加した数量
    occurredAt: Instant = Instant.now()
  ) extends Event

  // 状態に返品在庫を追加
  final case class ActiveState(
    inventory: Inventory,
    returnedInventories: Map[ReturnId, ReturnedInventory] = Map.empty
  ) extends State

  // コマンドハンドラ（返品関連）
  private def handleReturnToInventory(
    state: ActiveState,
    cmd: ReturnToInventory
  ): ReplyEffect[Event, State] = {

    val returnId = ReturnId.generate()

    val event = ReturnReceived(
      returnId = returnId,
      orderId = cmd.orderId,
      productId = cmd.productId,
      quantity = cmd.quantity,
      returnReason = cmd.returnReason
    )

    Effect
      .persist(event)
      .thenReply(cmd.replyTo)(_ => ReturnedToInventoryReply)
  }

  private def handleStartInspection(
    state: ActiveState,
    cmd: StartInspection
  ): ReplyEffect[Event, State] = {

    state.returnedInventories.get(cmd.returnId) match {
      case Some(returnedInventory) =>
        returnedInventory.startInspection() match {
          case Right(_) =>
            Effect
              .persist(InspectionStarted(cmd.returnId))
              .thenReply(cmd.replyTo)(_ => InspectionStartedReply)
          case Left(error) =>
            Effect.reply(cmd.replyTo)(StartInspectionFailed(error))
        }
      case None =>
        Effect.reply(cmd.replyTo)(StartInspectionFailed(
          InventoryError.ReturnedInventoryNotFound(cmd.returnId)
        ))
    }
  }

  private def handleRecordInspectionResult(
    state: ActiveState,
    cmd: RecordInspectionResult
  ): ReplyEffect[Event, State] = {

    state.returnedInventories.get(cmd.returnId) match {
      case Some(returnedInventory) =>
        returnedInventory.recordInspectionResult(cmd.result) match {
          case Right(_) =>
            // 検品結果を記録し、在庫を更新
            val events = List(
              InspectionResultRecorded(cmd.returnId, cmd.result)
            ) ++ createInventoryUpdateEvents(returnedInventory, cmd.result)

            Effect
              .persist(events: _*)
              .thenReply(cmd.replyTo)(_ => InspectionResultRecordedReply)

          case Left(error) =>
            Effect.reply(cmd.replyTo)(RecordInspectionResultFailed(error))
        }
      case None =>
        Effect.reply(cmd.replyTo)(RecordInspectionResultFailed(
          InventoryError.ReturnedInventoryNotFound(cmd.returnId)
        ))
    }
  }

  private def createInventoryUpdateEvents(
    returnedInventory: ReturnedInventory,
    result: InspectionResult
  ): List[Event] = {
    var events: List[Event] = List.empty

    // 承認数量を通常在庫に戻す
    if (result.approvedQuantity.value > 0) {
      events = events :+ InventoryRestored(
        productId = returnedInventory.productId,
        warehouseId = returnedInventory.warehouseId,
        quantity = result.approvedQuantity
      )
    }

    // 却下数量を不良品在庫に追加
    if (result.rejectedQuantity.value > 0) {
      events = events :+ DefectiveInventoryAdded(
        productId = returnedInventory.productId,
        warehouseId = returnedInventory.warehouseId,
        quantity = result.rejectedQuantity
      )
    }

    events
  }

  // イベントハンドラ（返品関連）
  private def eventHandler: (State, Event) => State = {
    // ... 既存のイベントハンドラ ...

    case (state: ActiveState, evt: ReturnReceived) =>
      val returnedInventory = ReturnedInventory(
        returnId = evt.returnId,
        orderId = evt.orderId,
        productId = evt.productId,
        warehouseId = state.inventory.warehouseId,  // 仮
        quantity = evt.quantity,
        returnReason = evt.returnReason,
        status = ReturnedInventoryStatus.AwaitingInspection,
        receivedDate = LocalDate.now()
      )
      state.copy(returnedInventories = state.returnedInventories + (evt.returnId -> returnedInventory))

    case (state: ActiveState, evt: InspectionStarted) =>
      val updated = state.returnedInventories.get(evt.returnId).map { returned =>
        returned.copy(status = ReturnedInventoryStatus.Inspecting)
      }
      updated match {
        case Some(r) => state.copy(returnedInventories = state.returnedInventories + (evt.returnId -> r))
        case None => state
      }

    case (state: ActiveState, evt: InspectionResultRecorded) =>
      val updated = state.returnedInventories.get(evt.returnId).map { returned =>
        returned.copy(
          status = if (evt.result.rejectedQuantity.value == 0) {
            ReturnedInventoryStatus.Approved
          } else {
            ReturnedInventoryStatus.Rejected
          },
          inspectionResult = Some(evt.result)
        )
      }
      updated match {
        case Some(r) => state.copy(returnedInventories = state.returnedInventories + (evt.returnId -> r))
        case None => state
      }

    case (state: ActiveState, evt: InventoryRestored) =>
      // 通常在庫に数量を追加
      val updatedInventory = state.inventory.copy(
        availableQuantity = state.inventory.availableQuantity + evt.quantity.value
      )
      state.copy(inventory = updatedInventory)

    case (state: ActiveState, evt: DefectiveInventoryAdded) =>
      // 不良品在庫に数量を追加（簡易実装）
      // 実際には不良品在庫を別途管理する必要がある
      state

    case (state, _) => state
  }
}
```

## 10.3 返品金額の処理

### 10.3.1 返品金額の計算

返品金額は、元の注文明細の単価と税率を使用して計算します。

```scala
package com.example.order.usecase

import com.example.order.domain._
import zio._

class ReturnAmountCalculationService {

  // 返品金額を計算
  def calculateReturnAmount(
    order: Order,
    returnItems: List[ReturnItem]
  ): Task[ReturnAmountBreakdown] = {
    ZIO.attempt {
      var subtotalAmount = Money.zero()
      var totalTaxAmount = Money.zero()
      var itemBreakdowns: List[ReturnItemBreakdown] = List.empty

      for (returnItem <- returnItems) {
        // 元の注文明細を取得
        val originalItem = order.items.find(_.productId == returnItem.productId).getOrElse {
          throw new IllegalArgumentException(s"Product ${returnItem.productId.value} not found in order")
        }

        // 返品明細の金額計算
        val itemSubtotal = returnItem.subtotalAmount
        val itemTax = returnItem.taxAmount(originalItem.taxRate)
        val itemTotal = returnItem.totalAmount(originalItem.taxRate)

        subtotalAmount = subtotalAmount + itemSubtotal
        totalTaxAmount = totalTaxAmount + itemTax

        itemBreakdowns = itemBreakdowns :+ ReturnItemBreakdown(
          productId = returnItem.productId,
          productName = returnItem.productName,
          quantity = returnItem.quantity,
          unitPrice = returnItem.unitPrice,
          subtotal = itemSubtotal,
          taxRate = originalItem.taxRate,
          taxAmount = itemTax,
          total = itemTotal
        )
      }

      val totalAmount = subtotalAmount + totalTaxAmount

      ReturnAmountBreakdown(
        subtotalAmount = subtotalAmount,
        totalTaxAmount = totalTaxAmount,
        totalAmount = totalAmount,
        items = itemBreakdowns
      )
    }
  }
}

// 返品金額明細
final case class ReturnAmountBreakdown(
  subtotalAmount: Money,  // 小計（税抜）
  totalTaxAmount: Money,  // 税額合計
  totalAmount: Money,     // 合計（税込）
  items: List[ReturnItemBreakdown]
)

final case class ReturnItemBreakdown(
  productId: ProductId,
  productName: String,
  quantity: Quantity,
  unitPrice: Money,
  subtotal: Money,
  taxRate: TaxRate,
  taxAmount: Money,
  total: Money
)
```

### 10.3.2 請求金額からの控除

返品金額を請求書から控除します。

```scala
package com.example.order.usecase

import com.example.order.domain._
import zio._

class ReturnRefundService(
  invoiceRepository: InvoiceRepository,
  invoiceActor: ActorRef[InvoiceActor.Command]
) {

  // 返品の返金処理
  def processReturnRefund(
    orderId: OrderId,
    returnAmount: Money
  ): Task[Either[ReturnRefundError, Unit]] = {
    for {
      // 注文に関連する請求書を検索
      invoices <- invoiceRepository.findByOrderId(orderId)

      result <- invoices match {
        case Nil =>
          // 請求書未発行の場合、何もしない（注文金額から直接控除）
          ZIO.succeed(Right(()))

        case invoice :: _ =>
          // 請求書が発行済みの場合、返金処理
          if (invoice.status == InvoiceStatus.Issued || invoice.status == InvoiceStatus.PartiallyPaid) {
            // 未入金または一部入金の場合、請求額を減額
            adjustInvoiceAmount(invoice, returnAmount)
          } else if (invoice.status == InvoiceStatus.FullyPaid) {
            // 完済済みの場合、返金処理
            issueRefund(invoice, returnAmount)
          } else {
            ZIO.succeed(Left(ReturnRefundError.InvalidInvoiceStatus(invoice.id, invoice.status)))
          }
      }
    } yield result
  }

  private def adjustInvoiceAmount(
    invoice: Invoice,
    returnAmount: Money
  ): Task[Either[ReturnRefundError, Unit]] = {
    // 請求額から返品金額を控除
    val newTotalAmount = invoice.totalAmount - returnAmount

    if (newTotalAmount.isNegative) {
      return ZIO.succeed(Left(ReturnRefundError.RefundExceedsInvoiceAmount(
        invoiceId = invoice.id,
        invoiceAmount = invoice.totalAmount,
        refundAmount = returnAmount
      )))
    }

    // InvoiceActorに調整コマンドを送信
    // （実際の実装では、InvoiceActorにAdjustAmountコマンドを追加する必要がある）
    ZIO.succeed(Right(()))
  }

  private def issueRefund(
    invoice: Invoice,
    returnAmount: Money
  ): Task[Either[ReturnRefundError, Unit]] = {
    // 完済済みの場合、返金として記録
    // 実際には、返金処理を別途実装する必要がある
    for {
      _ <- ZIO.logInfo(s"Issuing refund of ${returnAmount} for invoice ${invoice.id.value}")
      // 返金記録の作成
      // 返金方法（銀行振込、次回請求からの控除等）を選択
    } yield Right(())
  }
}

// 返金エラー
sealed trait ReturnRefundError

object ReturnRefundError {
  final case class InvalidInvoiceStatus(
    invoiceId: InvoiceId,
    status: InvoiceStatus
  ) extends ReturnRefundError

  final case class RefundExceedsInvoiceAmount(
    invoiceId: InvoiceId,
    invoiceAmount: Money,
    refundAmount: Money
  ) extends ReturnRefundError
}
```

### 10.3.3 与信枠の回復

返品により、使用済みの与信枠を回復します（第8章で実装済み）。

```scala
// CreditLimitActor での与信回復処理（第8章で実装済み）
private def handleRecoverCredit(
  state: ActiveState,
  cmd: RecoverCredit
): ReplyEffect[Event, State] = {

  state.creditLimit.recover(cmd.amount) match {
    case Right(_) =>
      Effect
        .persist(CreditRecovered(cmd.amount))
        .thenReply(cmd.replyTo)(_ => CreditRecoveredReply)
    case Left(error) =>
      Effect.reply(cmd.replyTo)(RecoverCreditFailed(error))
  }
}

// CreditLimit集約での回復処理（第8章で実装済み）
final case class CreditLimit(
  customerId: CustomerId,
  limitAmount: Money,
  usedAmount: Money,
  reservations: Map[OrderId, Money],
  version: Version
) {

  // 与信を回復（返品時）
  def recover(amount: Money): Either[CreditError, CreditLimit] = {
    if (amount.isNegative) {
      return Left(CreditError.InvalidAmount(amount))
    }

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
}
```

## 10.4 返品処理のモニタリング

### 10.4.1 返品統計

```scala
package com.example.order.usecase

import com.example.order.domain._
import zio._
import java.time.YearMonth

class ReturnStatisticsService(
  orderRepository: OrderRepository,
  returnRepository: ReturnRepository
) {

  // 返品統計を取得
  def getReturnStatistics(yearMonth: YearMonth): Task[ReturnStatistics] = {
    for {
      allReturns <- returnRepository.findByMonth(yearMonth)
      allOrders <- orderRepository.findByMonth(yearMonth)
    } yield {
      val totalReturns = allReturns.size
      val totalReturnAmount = Money.sum(allReturns.map(_.totalAmount))
      val totalOrderAmount = Money.sum(allOrders.map(_.totalAmount))

      val returnRate = if (totalOrders == 0) {
        BigDecimal(0)
      } else {
        BigDecimal(totalReturns) / BigDecimal(allOrders.size) * 100
      }

      val returnAmountRate = if (totalOrderAmount.isZero) {
        BigDecimal(0)
      } else {
        (totalReturnAmount.amount / totalOrderAmount.amount) * 100
      }

      // 返品理由別の集計
      val returnsByReason = allReturns
        .flatMap(_.returnItems)
        .groupBy(_.returnReason)
        .view
        .mapValues(items => items.size)
        .toMap

      ReturnStatistics(
        yearMonth = yearMonth,
        totalReturns = totalReturns,
        totalReturnAmount = totalReturnAmount,
        totalOrders = allOrders.size,
        totalOrderAmount = totalOrderAmount,
        returnRate = returnRate,
        returnAmountRate = returnAmountRate,
        returnsByReason = returnsByReason
      )
    }
  }
}

// 返品統計
final case class ReturnStatistics(
  yearMonth: YearMonth,
  totalReturns: Int,
  totalReturnAmount: Money,
  totalOrders: Int,
  totalOrderAmount: Money,
  returnRate: BigDecimal,        // 返品率（件数ベース）%
  returnAmountRate: BigDecimal,  // 返品率（金額ベース）%
  returnsByReason: Map[ReturnReason, Int]  // 返品理由別の件数
)
```

## 10.5 まとめ

本章では、返品処理の実装について詳しく説明しました。

**実装のポイント**:

1. **返品受付**: 返品理由の分類、返品ポリシーによる検証、Order集約での返品処理
2. **在庫への戻し入れ**: 検品プロセス、通常在庫と不良品在庫の分離管理
3. **返品金額の処理**: 元の注文明細に基づく正確な金額計算、請求書からの控除
4. **与信枠の回復**: 使用済み与信の回復により、顧客の与信枠を正常化
5. **返品統計**: 返品率、返品理由別の分析によるビジネス改善

**返品処理のフロー**:
```
1. 返品受付 → 返品ポリシーチェック → Order集約で返品記録
2. 在庫受入 → 検品 → 通常在庫/不良品在庫に分離
3. 返品金額計算 → 請求書調整 → 与信回復
4. モニタリング → 返品率分析 → 品質改善
```

**次章では**（outline.mdに従えば）:
- 第11章: パフォーマンス最適化（キャッシング、並列化、Saga最適化）
- 第12章: 運用とモニタリング（ビジネスメトリクス、Saga監視）

返品処理により、顧客満足度を維持しながら、在庫と売上の正確性を保つことができます。適切な返品ポリシーと検品プロセスにより、不良品の流通を防ぎ、ビジネスの品質を向上させます。
