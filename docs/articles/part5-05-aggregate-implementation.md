# Part5 第5章: 複数集約の実装

## 本章の目的

第4章で設計したドメインモデルを、Pekko Persistenceを使ったイベントソーシングアクターとして実装します。各集約（PurchaseOrder、Receiving、SupplierPayment）に対して、コマンドとイベントを定義し、アクターベースのコマンドハンドラとイベントハンドラを実装します。

## 本章で学ぶこと

- Pekko型付きアクターによる集約の実装
- EventSourcedBehaviorを使ったイベントソーシング
- コマンドハンドラとイベントハンドラの実装
- 集約間のイベント駆動連携
- ビジネスルールの実装とテスト

---

## 5.1 PurchaseOrder集約の実装

### 5.1.1 コマンドとイベントの定義

まず、PurchaseOrder集約に対するコマンドとイベントを定義します。

```scala
package com.example.procurement.domain.purchaseorder

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.pattern.StatusReply
import com.example.shared.domain.*
import java.time.LocalDate

// コマンド
sealed trait PurchaseOrderCommand

object PurchaseOrderCommand {
  // 発注作成
  final case class CreatePurchaseOrder(
    tenantId: TenantId,
    supplierId: SupplierId,
    orderDate: LocalDate,
    deliveryDate: LocalDate,
    items: List[PurchaseOrderItemData],
    replyTo: ActorRef[StatusReply[PurchaseOrderCreated]]
  ) extends PurchaseOrderCommand

  // 承認申請
  final case class RequestApproval(
    replyTo: ActorRef[StatusReply[ApprovalRequested]]
  ) extends PurchaseOrderCommand

  // 承認
  final case class ApprovePurchaseOrder(
    approverId: UserId,
    approverRole: ApproverRole,
    comment: Option[String],
    replyTo: ActorRef[StatusReply[PurchaseOrderApproved]]
  ) extends PurchaseOrderCommand

  // 却下
  final case class RejectPurchaseOrder(
    approverId: UserId,
    reason: String,
    replyTo: ActorRef[StatusReply[PurchaseOrderRejected]]
  ) extends PurchaseOrderCommand

  // 発注書発行
  final case class IssuePurchaseOrder(
    issuedBy: UserId,
    replyTo: ActorRef[StatusReply[PurchaseOrderIssued]]
  ) extends PurchaseOrderCommand

  // 発注キャンセル
  final case class CancelPurchaseOrder(
    cancelledBy: UserId,
    reason: String,
    replyTo: ActorRef[StatusReply[PurchaseOrderCancelled]]
  ) extends PurchaseOrderCommand

  // 入荷記録（他の集約から呼ばれる）
  final case class RecordReceipt(
    receivingId: ReceivingId,
    receivedItems: List[ReceivedItemData],
    replyTo: ActorRef[StatusReply[ReceiptRecorded]]
  ) extends PurchaseOrderCommand
}

// イベント
sealed trait PurchaseOrderEvent {
  def purchaseOrderId: PurchaseOrderId
  def occurredAt: java.time.Instant
}

object PurchaseOrderEvent {
  final case class PurchaseOrderCreated(
    purchaseOrderId: PurchaseOrderId,
    tenantId: TenantId,
    supplierId: SupplierId,
    orderNumber: OrderNumber,
    orderDate: LocalDate,
    deliveryDate: LocalDate,
    items: List[PurchaseOrderItemData],
    subtotalAmount: Money,
    taxAmount: Money,
    totalAmount: Money,
    approvalRequired: Boolean,
    occurredAt: java.time.Instant
  ) extends PurchaseOrderEvent

  final case class ApprovalRequested(
    purchaseOrderId: PurchaseOrderId,
    requestedAt: java.time.Instant,
    requiredApproverRole: ApproverRole,
    totalAmount: Money,
    occurredAt: java.time.Instant
  ) extends PurchaseOrderEvent

  final case class PurchaseOrderApproved(
    purchaseOrderId: PurchaseOrderId,
    approverId: UserId,
    approverRole: ApproverRole,
    approvedAt: java.time.Instant,
    comment: Option[String],
    occurredAt: java.time.Instant
  ) extends PurchaseOrderEvent

  final case class PurchaseOrderRejected(
    purchaseOrderId: PurchaseOrderId,
    approverId: UserId,
    rejectedAt: java.time.Instant,
    reason: String,
    occurredAt: java.time.Instant
  ) extends PurchaseOrderEvent

  final case class PurchaseOrderIssued(
    purchaseOrderId: PurchaseOrderId,
    issuedBy: UserId,
    issuedAt: java.time.Instant,
    occurredAt: java.time.Instant
  ) extends PurchaseOrderEvent

  final case class PurchaseOrderCancelled(
    purchaseOrderId: PurchaseOrderId,
    cancelledBy: UserId,
    cancelledAt: java.time.Instant,
    reason: String,
    occurredAt: java.time.Instant
  ) extends PurchaseOrderEvent

  final case class ReceiptRecorded(
    purchaseOrderId: PurchaseOrderId,
    receivingId: ReceivingId,
    receivedItems: List[ReceivedItemData],
    remainingQuantities: Map[ProductId, Quantity],
    isFullyReceived: Boolean,
    occurredAt: java.time.Instant
  ) extends PurchaseOrderEvent
}

// データ転送オブジェクト
final case class PurchaseOrderItemData(
  productId: ProductId,
  productName: ProductName,
  quantity: Quantity,
  unitPrice: Money,
  taxRate: TaxRate
)

final case class ReceivedItemData(
  productId: ProductId,
  receivedQuantity: Quantity
)
```

### 5.1.2 PurchaseOrderアクターの実装

次に、EventSourcedBehaviorを使ってPurchaseOrderアクターを実装します。

```scala
package com.example.procurement.domain.purchaseorder

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}
import org.apache.pekko.pattern.StatusReply
import com.example.shared.domain.*
import java.time.{Instant, LocalDate}

object PurchaseOrderActor {

  // アクター状態
  sealed trait State

  case object EmptyState extends State

  final case class PurchaseOrderState(
    purchaseOrder: PurchaseOrder
  ) extends State

  // アクタービヘイビアの作成
  def apply(
    purchaseOrderId: PurchaseOrderId,
    orderNumberGenerator: () => OrderNumber
  ): Behavior[PurchaseOrderCommand] = {
    EventSourcedBehavior[PurchaseOrderCommand, PurchaseOrderEvent, State](
      persistenceId = PersistenceId.ofUniqueId(s"PurchaseOrder-${purchaseOrderId.value}"),
      emptyState = EmptyState,
      commandHandler = commandHandler(orderNumberGenerator),
      eventHandler = eventHandler
    ).withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2))
  }

  // コマンドハンドラ
  private def commandHandler(
    orderNumberGenerator: () => OrderNumber
  ): (State, PurchaseOrderCommand) => ReplyEffect[PurchaseOrderEvent, State] = { (state, command) =>
    state match {
      case EmptyState =>
        command match {
          case cmd: PurchaseOrderCommand.CreatePurchaseOrder =>
            handleCreatePurchaseOrder(cmd, orderNumberGenerator)
          case _ =>
            Effect.unhandled.thenNoReply()
        }

      case PurchaseOrderState(purchaseOrder) =>
        command match {
          case cmd: PurchaseOrderCommand.RequestApproval =>
            handleRequestApproval(purchaseOrder, cmd)
          case cmd: PurchaseOrderCommand.ApprovePurchaseOrder =>
            handleApprovePurchaseOrder(purchaseOrder, cmd)
          case cmd: PurchaseOrderCommand.RejectPurchaseOrder =>
            handleRejectPurchaseOrder(purchaseOrder, cmd)
          case cmd: PurchaseOrderCommand.IssuePurchaseOrder =>
            handleIssuePurchaseOrder(purchaseOrder, cmd)
          case cmd: PurchaseOrderCommand.CancelPurchaseOrder =>
            handleCancelPurchaseOrder(purchaseOrder, cmd)
          case cmd: PurchaseOrderCommand.RecordReceipt =>
            handleRecordReceipt(purchaseOrder, cmd)
          case _: PurchaseOrderCommand.CreatePurchaseOrder =>
            Effect.reply(command.asInstanceOf[PurchaseOrderCommand.CreatePurchaseOrder].replyTo)(
              StatusReply.error("Purchase order already exists")
            )
        }
    }
  }

  // 発注作成ハンドラ
  private def handleCreatePurchaseOrder(
    cmd: PurchaseOrderCommand.CreatePurchaseOrder,
    orderNumberGenerator: () => OrderNumber
  ): ReplyEffect[PurchaseOrderEvent, State] = {
    // 発注番号を生成
    val orderNumber = orderNumberGenerator()

    // 発注明細を作成
    val itemsResult = cmd.items.map { itemData =>
      PurchaseOrderItem.create(
        productId = itemData.productId,
        productName = itemData.productName,
        quantity = itemData.quantity,
        unitPrice = itemData.unitPrice,
        taxRate = itemData.taxRate
      )
    }

    // バリデーションエラーをチェック
    val errors = itemsResult.collect { case Left(error) => error }
    if (errors.nonEmpty) {
      return Effect.reply(cmd.replyTo)(
        StatusReply.error(s"Invalid purchase order items: ${errors.mkString(", ")}")
      )
    }

    val items = itemsResult.collect { case Right(item) => item }

    // 金額を計算
    val subtotal = items.map(_.subtotal).reduce(_ + _)
    val tax = items.map(_.taxAmount).reduce(_ + _)
    val total = subtotal + tax

    // 承認が必要かどうかを判定
    val approvalRequired = total.amount >= 500000 // 50万円以上は承認が必要

    // イベントを作成
    val event = PurchaseOrderEvent.PurchaseOrderCreated(
      purchaseOrderId = PurchaseOrderId(s"PO-${orderNumber.value}"),
      tenantId = cmd.tenantId,
      supplierId = cmd.supplierId,
      orderNumber = orderNumber,
      orderDate = cmd.orderDate,
      deliveryDate = cmd.deliveryDate,
      items = cmd.items,
      subtotalAmount = subtotal,
      taxAmount = tax,
      totalAmount = total,
      approvalRequired = approvalRequired,
      occurredAt = Instant.now()
    )

    Effect.persist(event).thenReply(cmd.replyTo)(_ => StatusReply.success(event))
  }

  // 承認申請ハンドラ
  private def handleRequestApproval(
    purchaseOrder: PurchaseOrder,
    cmd: PurchaseOrderCommand.RequestApproval
  ): ReplyEffect[PurchaseOrderEvent, State] = {
    purchaseOrder.requestApproval() match {
      case Right(updatedPO) =>
        val event = PurchaseOrderEvent.ApprovalRequested(
          purchaseOrderId = purchaseOrder.id,
          requestedAt = Instant.now(),
          requiredApproverRole = purchaseOrder.requiredApproverRole,
          totalAmount = purchaseOrder.totalAmount,
          occurredAt = Instant.now()
        )
        Effect.persist(event).thenReply(cmd.replyTo)(_ => StatusReply.success(event))

      case Left(error) =>
        Effect.reply(cmd.replyTo)(StatusReply.error(error.message))
    }
  }

  // 承認ハンドラ
  private def handleApprovePurchaseOrder(
    purchaseOrder: PurchaseOrder,
    cmd: PurchaseOrderCommand.ApprovePurchaseOrder
  ): ReplyEffect[PurchaseOrderEvent, State] = {
    purchaseOrder.approve(cmd.approverId, cmd.approverRole, cmd.comment) match {
      case Right(updatedPO) =>
        val event = PurchaseOrderEvent.PurchaseOrderApproved(
          purchaseOrderId = purchaseOrder.id,
          approverId = cmd.approverId,
          approverRole = cmd.approverRole,
          approvedAt = Instant.now(),
          comment = cmd.comment,
          occurredAt = Instant.now()
        )
        Effect.persist(event).thenReply(cmd.replyTo)(_ => StatusReply.success(event))

      case Left(error) =>
        Effect.reply(cmd.replyTo)(StatusReply.error(error.message))
    }
  }

  // 却下ハンドラ
  private def handleRejectPurchaseOrder(
    purchaseOrder: PurchaseOrder,
    cmd: PurchaseOrderCommand.RejectPurchaseOrder
  ): ReplyEffect[PurchaseOrderEvent, State] = {
    purchaseOrder.reject(cmd.approverId, cmd.reason) match {
      case Right(updatedPO) =>
        val event = PurchaseOrderEvent.PurchaseOrderRejected(
          purchaseOrderId = purchaseOrder.id,
          approverId = cmd.approverId,
          rejectedAt = Instant.now(),
          reason = cmd.reason,
          occurredAt = Instant.now()
        )
        Effect.persist(event).thenReply(cmd.replyTo)(_ => StatusReply.success(event))

      case Left(error) =>
        Effect.reply(cmd.replyTo)(StatusReply.error(error.message))
    }
  }

  // 発注書発行ハンドラ
  private def handleIssuePurchaseOrder(
    purchaseOrder: PurchaseOrder,
    cmd: PurchaseOrderCommand.IssuePurchaseOrder
  ): ReplyEffect[PurchaseOrderEvent, State] = {
    purchaseOrder.issue(cmd.issuedBy) match {
      case Right(updatedPO) =>
        val event = PurchaseOrderEvent.PurchaseOrderIssued(
          purchaseOrderId = purchaseOrder.id,
          issuedBy = cmd.issuedBy,
          issuedAt = Instant.now(),
          occurredAt = Instant.now()
        )
        Effect.persist(event).thenReply(cmd.replyTo)(_ => StatusReply.success(event))

      case Left(error) =>
        Effect.reply(cmd.replyTo)(StatusReply.error(error.message))
    }
  }

  // 発注キャンセルハンドラ
  private def handleCancelPurchaseOrder(
    purchaseOrder: PurchaseOrder,
    cmd: PurchaseOrderCommand.CancelPurchaseOrder
  ): ReplyEffect[PurchaseOrderEvent, State] = {
    purchaseOrder.cancel(cmd.cancelledBy, cmd.reason) match {
      case Right(updatedPO) =>
        val event = PurchaseOrderEvent.PurchaseOrderCancelled(
          purchaseOrderId = purchaseOrder.id,
          cancelledBy = cmd.cancelledBy,
          cancelledAt = Instant.now(),
          reason = cmd.reason,
          occurredAt = Instant.now()
        )
        Effect.persist(event).thenReply(cmd.replyTo)(_ => StatusReply.success(event))

      case Left(error) =>
        Effect.reply(cmd.replyTo)(StatusReply.error(error.message))
    }
  }

  // 入荷記録ハンドラ
  private def handleRecordReceipt(
    purchaseOrder: PurchaseOrder,
    cmd: PurchaseOrderCommand.RecordReceipt
  ): ReplyEffect[PurchaseOrderEvent, State] = {
    purchaseOrder.recordReceipt(cmd.receivingId, cmd.receivedItems) match {
      case Right((updatedPO, remainingQty, isFullyReceived)) =>
        val event = PurchaseOrderEvent.ReceiptRecorded(
          purchaseOrderId = purchaseOrder.id,
          receivingId = cmd.receivingId,
          receivedItems = cmd.receivedItems,
          remainingQuantities = remainingQty,
          isFullyReceived = isFullyReceived,
          occurredAt = Instant.now()
        )
        Effect.persist(event).thenReply(cmd.replyTo)(_ => StatusReply.success(event))

      case Left(error) =>
        Effect.reply(cmd.replyTo)(StatusReply.error(error.message))
    }
  }

  // イベントハンドラ
  private val eventHandler: (State, PurchaseOrderEvent) => State = { (state, event) =>
    event match {
      case e: PurchaseOrderEvent.PurchaseOrderCreated =>
        val items = e.items.map { itemData =>
          PurchaseOrderItem.create(
            productId = itemData.productId,
            productName = itemData.productName,
            quantity = itemData.quantity,
            unitPrice = itemData.unitPrice,
            taxRate = itemData.taxRate
          ).getOrElse(throw new IllegalStateException("Invalid item data in event"))
        }

        val purchaseOrder = PurchaseOrder(
          id = e.purchaseOrderId,
          tenantId = e.tenantId,
          supplierId = e.supplierId,
          orderNumber = e.orderNumber,
          orderDate = e.orderDate,
          deliveryDate = e.deliveryDate,
          items = items,
          status = PurchaseOrderStatus.Draft,
          approvalInfo = None,
          issuedInfo = None,
          receivedItems = Map.empty,
          version = Version.initial
        )

        PurchaseOrderState(purchaseOrder)

      case e: PurchaseOrderEvent.ApprovalRequested =>
        state match {
          case PurchaseOrderState(po) =>
            val updatedPO = po.copy(
              status = PurchaseOrderStatus.PendingApproval,
              version = po.version.increment
            )
            PurchaseOrderState(updatedPO)
          case _ => state
        }

      case e: PurchaseOrderEvent.PurchaseOrderApproved =>
        state match {
          case PurchaseOrderState(po) =>
            val approvalInfo = ApprovalInfo(
              approverId = e.approverId,
              approverRole = e.approverRole,
              approvedAt = e.approvedAt.atZone(java.time.ZoneId.systemDefault()).toLocalDateTime(),
              comment = e.comment
            )
            val updatedPO = po.copy(
              status = PurchaseOrderStatus.Approved,
              approvalInfo = Some(approvalInfo),
              version = po.version.increment
            )
            PurchaseOrderState(updatedPO)
          case _ => state
        }

      case e: PurchaseOrderEvent.PurchaseOrderRejected =>
        state match {
          case PurchaseOrderState(po) =>
            val updatedPO = po.copy(
              status = PurchaseOrderStatus.Cancelled,
              version = po.version.increment
            )
            PurchaseOrderState(updatedPO)
          case _ => state
        }

      case e: PurchaseOrderEvent.PurchaseOrderIssued =>
        state match {
          case PurchaseOrderState(po) =>
            val issuedInfo = IssuedInfo(
              issuedBy = e.issuedBy,
              issuedAt = e.issuedAt.atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
            )
            val updatedPO = po.copy(
              status = PurchaseOrderStatus.Issued,
              issuedInfo = Some(issuedInfo),
              version = po.version.increment
            )
            PurchaseOrderState(updatedPO)
          case _ => state
        }

      case e: PurchaseOrderEvent.PurchaseOrderCancelled =>
        state match {
          case PurchaseOrderState(po) =>
            val updatedPO = po.copy(
              status = PurchaseOrderStatus.Cancelled,
              version = po.version.increment
            )
            PurchaseOrderState(updatedPO)
          case _ => state
        }

      case e: PurchaseOrderEvent.ReceiptRecorded =>
        state match {
          case PurchaseOrderState(po) =>
            val updatedReceivedItems = e.receivedItems.foldLeft(po.receivedItems) { (acc, item) =>
              val currentQty = acc.getOrElse(item.productId, Quantity(0))
              acc + (item.productId -> (currentQty + item.receivedQuantity))
            }

            val newStatus = if (e.isFullyReceived) {
              PurchaseOrderStatus.Completed
            } else {
              PurchaseOrderStatus.PartiallyReceived
            }

            val updatedPO = po.copy(
              receivedItems = updatedReceivedItems,
              status = newStatus,
              version = po.version.increment
            )
            PurchaseOrderState(updatedPO)
          case _ => state
        }
    }
  }
}
```

### 5.1.3 ビジネスルールの実装

PurchaseOrderエンティティにビジネスロジックを実装します（第4章で定義したものを拡張）。

```scala
package com.example.procurement.domain.purchaseorder

import com.example.shared.domain.*
import java.time.{LocalDate, LocalDateTime}

// PurchaseOrder エンティティ（拡張版）
final case class PurchaseOrder(
  id: PurchaseOrderId,
  tenantId: TenantId,
  supplierId: SupplierId,
  orderNumber: OrderNumber,
  orderDate: LocalDate,
  deliveryDate: LocalDate,
  items: List[PurchaseOrderItem],
  status: PurchaseOrderStatus,
  approvalInfo: Option[ApprovalInfo],
  issuedInfo: Option[IssuedInfo],
  receivedItems: Map[ProductId, Quantity],  // 商品IDごとの入荷済み数量
  version: Version
) {
  // 小計金額
  def subtotalAmount: Money = items.map(_.subtotal).reduce(_ + _)

  // 税額
  def taxAmount: Money = items.map(_.taxAmount).reduce(_ + _)

  // 合計金額
  def totalAmount: Money = subtotalAmount + taxAmount

  // 必要な承認者のロール
  def requiredApproverRole: ApproverRole = {
    val amount = totalAmount.amount
    amount match {
      case a if a < 500000 => ApproverRole.Manager       // 50万円未満: 課長
      case a if a < 1000000 => ApproverRole.Director     // 100万円未満: 部長
      case _ => ApproverRole.Executive                   // 100万円以上: 役員
    }
  }

  // 承認申請
  def requestApproval(): Either[PurchaseOrderError, PurchaseOrder] = {
    status match {
      case PurchaseOrderStatus.Draft =>
        Right(copy(
          status = PurchaseOrderStatus.PendingApproval,
          version = version.increment
        ))
      case _ =>
        Left(PurchaseOrderError.InvalidStatusTransition(status, PurchaseOrderStatus.PendingApproval))
    }
  }

  // 承認
  def approve(
    approverId: UserId,
    approverRole: ApproverRole,
    comment: Option[String]
  ): Either[PurchaseOrderError, PurchaseOrder] = {
    status match {
      case PurchaseOrderStatus.PendingApproval =>
        // 承認者のロールが要求されるロール以上であることを確認
        if (!approverRole.canApprove(requiredApproverRole)) {
          return Left(PurchaseOrderError.InsufficientApprovalAuthority(approverRole, requiredApproverRole))
        }

        val info = ApprovalInfo(
          approverId = approverId,
          approverRole = approverRole,
          approvedAt = LocalDateTime.now(),
          comment = comment
        )

        Right(copy(
          status = PurchaseOrderStatus.Approved,
          approvalInfo = Some(info),
          version = version.increment
        ))
      case _ =>
        Left(PurchaseOrderError.InvalidStatusTransition(status, PurchaseOrderStatus.Approved))
    }
  }

  // 却下
  def reject(
    approverId: UserId,
    reason: String
  ): Either[PurchaseOrderError, PurchaseOrder] = {
    status match {
      case PurchaseOrderStatus.PendingApproval =>
        Right(copy(
          status = PurchaseOrderStatus.Cancelled,
          version = version.increment
        ))
      case _ =>
        Left(PurchaseOrderError.InvalidStatusTransition(status, PurchaseOrderStatus.Cancelled))
    }
  }

  // 発注書発行
  def issue(issuedBy: UserId): Either[PurchaseOrderError, PurchaseOrder] = {
    status match {
      case PurchaseOrderStatus.Approved =>
        val info = IssuedInfo(
          issuedBy = issuedBy,
          issuedAt = LocalDateTime.now()
        )

        Right(copy(
          status = PurchaseOrderStatus.Issued,
          issuedInfo = Some(info),
          version = version.increment
        ))
      case PurchaseOrderStatus.Draft =>
        // 承認不要な少額発注の場合は直接発行可能
        if (totalAmount.amount < 500000) {
          val info = IssuedInfo(
            issuedBy = issuedBy,
            issuedAt = LocalDateTime.now()
          )

          Right(copy(
            status = PurchaseOrderStatus.Issued,
            issuedInfo = Some(info),
            version = version.increment
          ))
        } else {
          Left(PurchaseOrderError.ApprovalRequired(totalAmount))
        }
      case _ =>
        Left(PurchaseOrderError.InvalidStatusTransition(status, PurchaseOrderStatus.Issued))
    }
  }

  // 発注キャンセル
  def cancel(cancelledBy: UserId, reason: String): Either[PurchaseOrderError, PurchaseOrder] = {
    status match {
      case PurchaseOrderStatus.Draft | PurchaseOrderStatus.PendingApproval | PurchaseOrderStatus.Approved =>
        Right(copy(
          status = PurchaseOrderStatus.Cancelled,
          version = version.increment
        ))
      case PurchaseOrderStatus.Issued | PurchaseOrderStatus.PartiallyReceived =>
        // 発注済みまたは一部入荷済みの場合はキャンセル不可
        Left(PurchaseOrderError.CannotCancelIssuedOrder(status))
      case _ =>
        Left(PurchaseOrderError.InvalidStatusTransition(status, PurchaseOrderStatus.Cancelled))
    }
  }

  // 入荷記録
  def recordReceipt(
    receivingId: ReceivingId,
    receivedItemsData: List[ReceivedItemData]
  ): Either[PurchaseOrderError, (PurchaseOrder, Map[ProductId, Quantity], Boolean)] = {
    status match {
      case PurchaseOrderStatus.Issued | PurchaseOrderStatus.PartiallyReceived =>
        // 各商品の入荷数量を更新
        val updatedReceivedItems = receivedItemsData.foldLeft(receivedItems) { (acc, item) =>
          val currentQty = acc.getOrElse(item.productId, Quantity(0))
          acc + (item.productId -> (currentQty + item.receivedQuantity))
        }

        // 発注残数量を計算
        val remainingQuantities = items.map { item =>
          val orderedQty = item.quantity
          val receivedQty = updatedReceivedItems.getOrElse(item.productId, Quantity(0))
          val remaining = orderedQty - receivedQty
          item.productId -> remaining
        }.toMap

        // 全て入荷済みかどうかをチェック
        val isFullyReceived = remainingQuantities.values.forall(_.value <= 0)

        val newStatus = if (isFullyReceived) {
          PurchaseOrderStatus.Completed
        } else {
          PurchaseOrderStatus.PartiallyReceived
        }

        val updatedPO = copy(
          receivedItems = updatedReceivedItems,
          status = newStatus,
          version = version.increment
        )

        Right((updatedPO, remainingQuantities, isFullyReceived))

      case _ =>
        Left(PurchaseOrderError.InvalidStatusForReceipt(status))
    }
  }
}

// 承認者ロール
sealed trait ApproverRole {
  def level: Int
  def canApprove(requiredRole: ApproverRole): Boolean = this.level >= requiredRole.level
}

object ApproverRole {
  case object Manager extends ApproverRole {
    val level = 1
  }

  case object Director extends ApproverRole {
    val level = 2
  }

  case object Executive extends ApproverRole {
    val level = 3
  }
}

// エラー型
sealed trait PurchaseOrderError {
  def message: String
}

object PurchaseOrderError {
  final case class InvalidStatusTransition(from: PurchaseOrderStatus, to: PurchaseOrderStatus) extends PurchaseOrderError {
    def message: String = s"Cannot transition from $from to $to"
  }

  final case class InsufficientApprovalAuthority(actual: ApproverRole, required: ApproverRole) extends PurchaseOrderError {
    def message: String = s"Insufficient approval authority: $actual cannot approve orders requiring $required"
  }

  final case class ApprovalRequired(amount: Money) extends PurchaseOrderError {
    def message: String = s"Approval required for orders of ${amount.amount} or more"
  }

  final case class CannotCancelIssuedOrder(status: PurchaseOrderStatus) extends PurchaseOrderError {
    def message: String = s"Cannot cancel order in status $status"
  }

  final case class InvalidStatusForReceipt(status: PurchaseOrderStatus) extends PurchaseOrderError {
    def message: String = s"Cannot record receipt for order in status $status"
  }
}
```

---

## 5.2 Receiving集約の実装

### 5.2.1 コマンドとイベントの定義

```scala
package com.example.procurement.domain.receiving

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.pattern.StatusReply
import com.example.shared.domain.*
import java.time.LocalDate

// コマンド
sealed trait ReceivingCommand

object ReceivingCommand {
  // 入荷記録作成
  final case class CreateReceiving(
    tenantId: TenantId,
    purchaseOrderId: PurchaseOrderId,
    warehouseId: WarehouseId,
    receivingDate: LocalDate,
    items: List[ReceivingItemData],
    replyTo: ActorRef[StatusReply[ReceivingCreated]]
  ) extends ReceivingCommand

  // 入荷記録
  final case class RecordGoodsReceipt(
    receivedBy: UserId,
    replyTo: ActorRef[StatusReply[GoodsReceived]]
  ) extends ReceivingCommand

  // 検収開始
  final case class StartInspection(
    inspectorId: UserId,
    replyTo: ActorRef[StatusReply[InspectionStarted]]
  ) extends ReceivingCommand

  // 検収完了
  final case class CompleteInspection(
    inspectedItems: List[InspectedItemData],
    replyTo: ActorRef[StatusReply[InspectionCompleted]]
  ) extends ReceivingCommand

  // 差異記録
  final case class RecordDiscrepancy(
    productId: ProductId,
    discrepancyType: DiscrepancyReason,
    description: String,
    replyTo: ActorRef[StatusReply[DiscrepancyRecorded]]
  ) extends ReceivingCommand
}

// イベント
sealed trait ReceivingEvent {
  def receivingId: ReceivingId
  def occurredAt: java.time.Instant
}

object ReceivingEvent {
  final case class ReceivingCreated(
    receivingId: ReceivingId,
    tenantId: TenantId,
    purchaseOrderId: PurchaseOrderId,
    warehouseId: WarehouseId,
    receivingNumber: ReceivingNumber,
    receivingDate: LocalDate,
    items: List[ReceivingItemData],
    occurredAt: java.time.Instant
  ) extends ReceivingEvent

  final case class GoodsReceived(
    receivingId: ReceivingId,
    receivedBy: UserId,
    receivedAt: java.time.Instant,
    occurredAt: java.time.Instant
  ) extends ReceivingEvent

  final case class InspectionStarted(
    receivingId: ReceivingId,
    inspectorId: UserId,
    startedAt: java.time.Instant,
    occurredAt: java.time.Instant
  ) extends ReceivingEvent

  final case class InspectionCompleted(
    receivingId: ReceivingId,
    inspectedItems: List[InspectedItemData],
    completedAt: java.time.Instant,
    hasDiscrepancy: Boolean,
    occurredAt: java.time.Instant
  ) extends ReceivingEvent

  final case class DiscrepancyRecorded(
    receivingId: ReceivingId,
    productId: ProductId,
    discrepancyType: DiscrepancyReason,
    description: String,
    recordedAt: java.time.Instant,
    occurredAt: java.time.Instant
  ) extends ReceivingEvent
}

// データ転送オブジェクト
final case class ReceivingItemData(
  productId: ProductId,
  productName: ProductName,
  orderedQuantity: Quantity,
  lotNumber: Option[String],
  expiryDate: Option[LocalDate]
)

final case class InspectedItemData(
  productId: ProductId,
  receivedQuantity: Quantity,
  acceptedQuantity: Quantity,
  rejectedQuantity: Quantity,
  inspectionResult: InspectionResult,
  reason: Option[String]
)
```

### 5.2.2 Receivingアクターの実装

```scala
package com.example.procurement.domain.receiving

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}
import org.apache.pekko.pattern.StatusReply
import com.example.shared.domain.*
import java.time.Instant

object ReceivingActor {

  // アクター状態
  sealed trait State

  case object EmptyState extends State

  final case class ReceivingState(
    receiving: Receiving
  ) extends State

  // アクタービヘイビアの作成
  def apply(
    receivingId: ReceivingId,
    receivingNumberGenerator: () => ReceivingNumber
  ): Behavior[ReceivingCommand] = {
    EventSourcedBehavior[ReceivingCommand, ReceivingEvent, State](
      persistenceId = PersistenceId.ofUniqueId(s"Receiving-${receivingId.value}"),
      emptyState = EmptyState,
      commandHandler = commandHandler(receivingNumberGenerator),
      eventHandler = eventHandler
    ).withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2))
  }

  // コマンドハンドラ
  private def commandHandler(
    receivingNumberGenerator: () => ReceivingNumber
  ): (State, ReceivingCommand) => ReplyEffect[ReceivingEvent, State] = { (state, command) =>
    state match {
      case EmptyState =>
        command match {
          case cmd: ReceivingCommand.CreateReceiving =>
            handleCreateReceiving(cmd, receivingNumberGenerator)
          case _ =>
            Effect.unhandled.thenNoReply()
        }

      case ReceivingState(receiving) =>
        command match {
          case cmd: ReceivingCommand.RecordGoodsReceipt =>
            handleRecordGoodsReceipt(receiving, cmd)
          case cmd: ReceivingCommand.StartInspection =>
            handleStartInspection(receiving, cmd)
          case cmd: ReceivingCommand.CompleteInspection =>
            handleCompleteInspection(receiving, cmd)
          case cmd: ReceivingCommand.RecordDiscrepancy =>
            handleRecordDiscrepancy(receiving, cmd)
          case _: ReceivingCommand.CreateReceiving =>
            Effect.reply(command.asInstanceOf[ReceivingCommand.CreateReceiving].replyTo)(
              StatusReply.error("Receiving already exists")
            )
        }
    }
  }

  // 入荷記録作成ハンドラ
  private def handleCreateReceiving(
    cmd: ReceivingCommand.CreateReceiving,
    receivingNumberGenerator: () => ReceivingNumber
  ): ReplyEffect[ReceivingEvent, State] = {
    val receivingNumber = receivingNumberGenerator()

    val event = ReceivingEvent.ReceivingCreated(
      receivingId = ReceivingId(s"RCV-${receivingNumber.value}"),
      tenantId = cmd.tenantId,
      purchaseOrderId = cmd.purchaseOrderId,
      warehouseId = cmd.warehouseId,
      receivingNumber = receivingNumber,
      receivingDate = cmd.receivingDate,
      items = cmd.items,
      occurredAt = Instant.now()
    )

    Effect.persist(event).thenReply(cmd.replyTo)(_ => StatusReply.success(event))
  }

  // 入荷記録ハンドラ
  private def handleRecordGoodsReceipt(
    receiving: Receiving,
    cmd: ReceivingCommand.RecordGoodsReceipt
  ): ReplyEffect[ReceivingEvent, State] = {
    receiving.recordGoodsReceipt(cmd.receivedBy) match {
      case Right(updatedReceiving) =>
        val event = ReceivingEvent.GoodsReceived(
          receivingId = receiving.id,
          receivedBy = cmd.receivedBy,
          receivedAt = Instant.now(),
          occurredAt = Instant.now()
        )
        Effect.persist(event).thenReply(cmd.replyTo)(_ => StatusReply.success(event))

      case Left(error) =>
        Effect.reply(cmd.replyTo)(StatusReply.error(error.message))
    }
  }

  // 検収開始ハンドラ
  private def handleStartInspection(
    receiving: Receiving,
    cmd: ReceivingCommand.StartInspection
  ): ReplyEffect[ReceivingEvent, State] = {
    receiving.startInspection(cmd.inspectorId) match {
      case Right(updatedReceiving) =>
        val event = ReceivingEvent.InspectionStarted(
          receivingId = receiving.id,
          inspectorId = cmd.inspectorId,
          startedAt = Instant.now(),
          occurredAt = Instant.now()
        )
        Effect.persist(event).thenReply(cmd.replyTo)(_ => StatusReply.success(event))

      case Left(error) =>
        Effect.reply(cmd.replyTo)(StatusReply.error(error.message))
    }
  }

  // 検収完了ハンドラ
  private def handleCompleteInspection(
    receiving: Receiving,
    cmd: ReceivingCommand.CompleteInspection
  ): ReplyEffect[ReceivingEvent, State] = {
    val inspectedItems = cmd.inspectedItems.map { item =>
      (item.productId, item.acceptedQuantity, item.inspectionResult, item.reason)
    }

    receiving.completeInspection(inspectedItems) match {
      case Right(updatedReceiving) =>
        val hasDiscrepancy = cmd.inspectedItems.exists(_.inspectionResult != InspectionResult.Accepted)

        val event = ReceivingEvent.InspectionCompleted(
          receivingId = receiving.id,
          inspectedItems = cmd.inspectedItems,
          completedAt = Instant.now(),
          hasDiscrepancy = hasDiscrepancy,
          occurredAt = Instant.now()
        )
        Effect.persist(event).thenReply(cmd.replyTo)(_ => StatusReply.success(event))

      case Left(error) =>
        Effect.reply(cmd.replyTo)(StatusReply.error(error.message))
    }
  }

  // 差異記録ハンドラ
  private def handleRecordDiscrepancy(
    receiving: Receiving,
    cmd: ReceivingCommand.RecordDiscrepancy
  ): ReplyEffect[ReceivingEvent, State] = {
    val event = ReceivingEvent.DiscrepancyRecorded(
      receivingId = receiving.id,
      productId = cmd.productId,
      discrepancyType = cmd.discrepancyType,
      description = cmd.description,
      recordedAt = Instant.now(),
      occurredAt = Instant.now()
    )

    Effect.persist(event).thenReply(cmd.replyTo)(_ => StatusReply.success(event))
  }

  // イベントハンドラ
  private val eventHandler: (State, ReceivingEvent) => State = { (state, event) =>
    event match {
      case e: ReceivingEvent.ReceivingCreated =>
        val items = e.items.map { itemData =>
          ReceivingItem(
            productId = itemData.productId,
            productName = itemData.productName,
            orderedQuantity = itemData.orderedQuantity,
            receivedQuantity = Quantity(0),
            acceptedQuantity = Quantity(0),
            rejectedQuantity = Quantity(0),
            lotInfo = itemData.lotNumber.map { lotNum =>
              LotInfo(LotNumber(lotNum), itemData.expiryDate)
            },
            inspectionResult = None,
            discrepancyReason = None
          )
        }

        val receiving = Receiving(
          id = e.receivingId,
          tenantId = e.tenantId,
          purchaseOrderId = e.purchaseOrderId,
          warehouseId = e.warehouseId,
          receivingNumber = e.receivingNumber,
          receivingDate = e.receivingDate,
          inspectionDate = None,
          items = items,
          status = ReceivingStatus.Created,
          inspectorId = None,
          version = Version.initial
        )

        ReceivingState(receiving)

      case e: ReceivingEvent.GoodsReceived =>
        state match {
          case ReceivingState(receiving) =>
            val updatedReceiving = receiving.copy(
              status = ReceivingStatus.Received,
              version = receiving.version.increment
            )
            ReceivingState(updatedReceiving)
          case _ => state
        }

      case e: ReceivingEvent.InspectionStarted =>
        state match {
          case ReceivingState(receiving) =>
            val updatedReceiving = receiving.copy(
              status = ReceivingStatus.Inspecting,
              inspectorId = Some(e.inspectorId),
              version = receiving.version.increment
            )
            ReceivingState(updatedReceiving)
          case _ => state
        }

      case e: ReceivingEvent.InspectionCompleted =>
        state match {
          case ReceivingState(receiving) =>
            val updatedItems = receiving.items.map { item =>
              e.inspectedItems.find(_.productId == item.productId) match {
                case Some(inspected) =>
                  item.copy(
                    receivedQuantity = inspected.receivedQuantity,
                    acceptedQuantity = inspected.acceptedQuantity,
                    rejectedQuantity = inspected.rejectedQuantity,
                    inspectionResult = Some(inspected.inspectionResult),
                    discrepancyReason = inspected.reason
                  )
                case None => item
              }
            }

            val newStatus = if (e.hasDiscrepancy) {
              ReceivingStatus.DiscrepancyDetected
            } else {
              ReceivingStatus.Completed
            }

            val updatedReceiving = receiving.copy(
              items = updatedItems,
              status = newStatus,
              inspectionDate = Some(e.completedAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate()),
              version = receiving.version.increment
            )
            ReceivingState(updatedReceiving)
          case _ => state
        }

      case e: ReceivingEvent.DiscrepancyRecorded =>
        state match {
          case ReceivingState(receiving) =>
            val updatedReceiving = receiving.copy(
              status = ReceivingStatus.DiscrepancyDetected,
              version = receiving.version.increment
            )
            ReceivingState(updatedReceiving)
          case _ => state
        }
    }
  }
}
```

---

## 5.3 SupplierPayment集約の実装

### 5.3.1 コマンドとイベントの定義

```scala
package com.example.procurement.domain.payment

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.pattern.StatusReply
import com.example.shared.domain.*
import java.time.LocalDate

// コマンド
sealed trait SupplierPaymentCommand

object SupplierPaymentCommand {
  // 請求書受領
  final case class ReceiveInvoice(
    tenantId: TenantId,
    supplierId: SupplierId,
    purchaseOrderId: PurchaseOrderId,
    receivingId: Option[ReceivingId],
    invoiceNumber: InvoiceNumber,
    invoiceDate: LocalDate,
    items: List[SupplierInvoiceItemData],
    paymentDueDate: LocalDate,
    replyTo: ActorRef[StatusReply[InvoiceReceived]]
  ) extends SupplierPaymentCommand

  // 3-way matching実行
  final case class PerformThreeWayMatching(
    purchaseOrderAmount: Money,
    receivingAmount: Money,
    replyTo: ActorRef[StatusReply[MatchingEvent]]
  ) extends SupplierPaymentCommand

  // 支払承認
  final case class ApprovePayment(
    approvedBy: UserId,
    replyTo: ActorRef[StatusReply[PaymentApproved]]
  ) extends SupplierPaymentCommand

  // 支払予定設定
  final case class SchedulePayment(
    scheduledDate: LocalDate,
    replyTo: ActorRef[StatusReply[PaymentScheduled]]
  ) extends SupplierPaymentCommand

  // 支払完了
  final case class CompletePayment(
    paymentDate: LocalDate,
    paymentMethod: PaymentMethod,
    paymentAmount: Money,
    replyTo: ActorRef[StatusReply[PaymentCompleted]]
  ) extends SupplierPaymentCommand
}

// イベント
sealed trait SupplierPaymentEvent {
  def invoiceId: SupplierInvoiceId
  def occurredAt: java.time.Instant
}

object SupplierPaymentEvent {
  final case class InvoiceReceived(
    invoiceId: SupplierInvoiceId,
    tenantId: TenantId,
    supplierId: SupplierId,
    purchaseOrderId: PurchaseOrderId,
    receivingId: Option[ReceivingId],
    invoiceNumber: InvoiceNumber,
    invoiceDate: LocalDate,
    items: List[SupplierInvoiceItemData],
    totalAmount: Money,
    paymentDueDate: LocalDate,
    occurredAt: java.time.Instant
  ) extends SupplierPaymentEvent

  final case class ThreeWayMatchingSucceeded(
    invoiceId: SupplierInvoiceId,
    matchingResult: ThreeWayMatchingResultData,
    occurredAt: java.time.Instant
  ) extends SupplierPaymentEvent with MatchingEvent

  final case class ThreeWayMatchingFailed(
    invoiceId: SupplierInvoiceId,
    matchingResult: ThreeWayMatchingResultData,
    occurredAt: java.time.Instant
  ) extends SupplierPaymentEvent with MatchingEvent

  final case class PaymentApproved(
    invoiceId: SupplierInvoiceId,
    approvedBy: UserId,
    approvedAt: java.time.Instant,
    occurredAt: java.time.Instant
  ) extends SupplierPaymentEvent

  final case class PaymentScheduled(
    invoiceId: SupplierInvoiceId,
    scheduledDate: LocalDate,
    occurredAt: java.time.Instant
  ) extends SupplierPaymentEvent

  final case class PaymentCompleted(
    invoiceId: SupplierInvoiceId,
    paymentId: SupplierPaymentId,
    paymentDate: LocalDate,
    paymentMethod: PaymentMethod,
    paymentAmount: Money,
    occurredAt: java.time.Instant
  ) extends SupplierPaymentEvent
}

// マッチングイベントの共通型
sealed trait MatchingEvent extends SupplierPaymentEvent

// データ転送オブジェクト
final case class SupplierInvoiceItemData(
  productId: ProductId,
  productName: ProductName,
  quantity: Quantity,
  unitPrice: Money,
  taxRate: TaxRate
)

final case class ThreeWayMatchingResultData(
  purchaseOrderAmount: Money,
  receivingAmount: Money,
  invoiceAmount: Money,
  quantityMatches: Boolean,
  amountMatches: Boolean,
  unitPriceMatches: Boolean,
  discrepancies: List[String]
)
```

### 5.3.2 SupplierPaymentアクターの実装

```scala
package com.example.procurement.domain.payment

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, ReplyEffect, RetentionCriteria}
import org.apache.pekko.pattern.StatusReply
import com.example.shared.domain.*
import java.time.Instant
import java.util.UUID

object SupplierPaymentActor {

  // アクター状態
  sealed trait State

  case object EmptyState extends State

  final case class InvoiceState(
    invoice: SupplierInvoice
  ) extends State

  // アクタービヘイビアの作成
  def apply(
    invoiceId: SupplierInvoiceId
  ): Behavior[SupplierPaymentCommand] = {
    EventSourcedBehavior[SupplierPaymentCommand, SupplierPaymentEvent, State](
      persistenceId = PersistenceId.ofUniqueId(s"SupplierPayment-${invoiceId.value}"),
      emptyState = EmptyState,
      commandHandler = commandHandler,
      eventHandler = eventHandler
    ).withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2))
  }

  // コマンドハンドラ
  private val commandHandler: (State, SupplierPaymentCommand) => ReplyEffect[SupplierPaymentEvent, State] = {
    (state, command) =>
      state match {
        case EmptyState =>
          command match {
            case cmd: SupplierPaymentCommand.ReceiveInvoice =>
              handleReceiveInvoice(cmd)
            case _ =>
              Effect.unhandled.thenNoReply()
          }

        case InvoiceState(invoice) =>
          command match {
            case cmd: SupplierPaymentCommand.PerformThreeWayMatching =>
              handlePerformThreeWayMatching(invoice, cmd)
            case cmd: SupplierPaymentCommand.ApprovePayment =>
              handleApprovePayment(invoice, cmd)
            case cmd: SupplierPaymentCommand.SchedulePayment =>
              handleSchedulePayment(invoice, cmd)
            case cmd: SupplierPaymentCommand.CompletePayment =>
              handleCompletePayment(invoice, cmd)
            case _: SupplierPaymentCommand.ReceiveInvoice =>
              Effect.reply(command.asInstanceOf[SupplierPaymentCommand.ReceiveInvoice].replyTo)(
                StatusReply.error("Invoice already exists")
              )
          }
      }
  }

  // 請求書受領ハンドラ
  private def handleReceiveInvoice(
    cmd: SupplierPaymentCommand.ReceiveInvoice
  ): ReplyEffect[SupplierPaymentEvent, State] = {
    // 金額を計算
    val items = cmd.items
    val subtotal = items.map { item =>
      Money(item.quantity.value * item.unitPrice.amount)
    }.reduce(_ + _)

    val tax = items.map { item =>
      val itemSubtotal = Money(item.quantity.value * item.unitPrice.amount)
      Money(itemSubtotal.amount * item.taxRate.rate)
    }.reduce(_ + _)

    val total = subtotal + tax

    val event = SupplierPaymentEvent.InvoiceReceived(
      invoiceId = SupplierInvoiceId(s"INV-${UUID.randomUUID().toString}"),
      tenantId = cmd.tenantId,
      supplierId = cmd.supplierId,
      purchaseOrderId = cmd.purchaseOrderId,
      receivingId = cmd.receivingId,
      invoiceNumber = cmd.invoiceNumber,
      invoiceDate = cmd.invoiceDate,
      items = cmd.items,
      totalAmount = total,
      paymentDueDate = cmd.paymentDueDate,
      occurredAt = Instant.now()
    )

    Effect.persist(event).thenReply(cmd.replyTo)(_ => StatusReply.success(event))
  }

  // 3-way matching実行ハンドラ
  private def handlePerformThreeWayMatching(
    invoice: SupplierInvoice,
    cmd: SupplierPaymentCommand.PerformThreeWayMatching
  ): ReplyEffect[SupplierPaymentEvent, State] = {
    // 金額の突合
    val invoiceAmount = invoice.totalAmount
    val tolerance = Money(100) // 100円の許容差

    val amountMatches = (invoiceAmount - cmd.receivingAmount).abs <= tolerance &&
      (invoiceAmount - cmd.purchaseOrderAmount).abs <= tolerance

    // 数量の突合（簡易版）
    val quantityMatches = true // 実際には各明細を照合

    // 単価の突合（簡易版）
    val unitPriceMatches = true // 実際には各明細を照合

    val discrepancies = scala.collection.mutable.ListBuffer[String]()

    if (!amountMatches) {
      discrepancies += s"Amount mismatch: PO=${cmd.purchaseOrderAmount.amount}, RCV=${cmd.receivingAmount.amount}, INV=${invoiceAmount.amount}"
    }

    val matchingResult = ThreeWayMatchingResultData(
      purchaseOrderAmount = cmd.purchaseOrderAmount,
      receivingAmount = cmd.receivingAmount,
      invoiceAmount = invoiceAmount,
      quantityMatches = quantityMatches,
      amountMatches = amountMatches,
      unitPriceMatches = unitPriceMatches,
      discrepancies = discrepancies.toList
    )

    val isFullMatch = quantityMatches && amountMatches && unitPriceMatches

    val event: MatchingEvent = if (isFullMatch) {
      SupplierPaymentEvent.ThreeWayMatchingSucceeded(
        invoiceId = invoice.id,
        matchingResult = matchingResult,
        occurredAt = Instant.now()
      )
    } else {
      SupplierPaymentEvent.ThreeWayMatchingFailed(
        invoiceId = invoice.id,
        matchingResult = matchingResult,
        occurredAt = Instant.now()
      )
    }

    Effect.persist(event).thenReply(cmd.replyTo)(_ => StatusReply.success(event))
  }

  // 支払承認ハンドラ
  private def handleApprovePayment(
    invoice: SupplierInvoice,
    cmd: SupplierPaymentCommand.ApprovePayment
  ): ReplyEffect[SupplierPaymentEvent, State] = {
    invoice.status match {
      case InvoiceStatus.Matched =>
        val event = SupplierPaymentEvent.PaymentApproved(
          invoiceId = invoice.id,
          approvedBy = cmd.approvedBy,
          approvedAt = Instant.now(),
          occurredAt = Instant.now()
        )
        Effect.persist(event).thenReply(cmd.replyTo)(_ => StatusReply.success(event))

      case _ =>
        Effect.reply(cmd.replyTo)(
          StatusReply.error(s"Cannot approve payment in status ${invoice.status}")
        )
    }
  }

  // 支払予定設定ハンドラ
  private def handleSchedulePayment(
    invoice: SupplierInvoice,
    cmd: SupplierPaymentCommand.SchedulePayment
  ): ReplyEffect[SupplierPaymentEvent, State] = {
    invoice.status match {
      case InvoiceStatus.Approved =>
        val event = SupplierPaymentEvent.PaymentScheduled(
          invoiceId = invoice.id,
          scheduledDate = cmd.scheduledDate,
          occurredAt = Instant.now()
        )
        Effect.persist(event).thenReply(cmd.replyTo)(_ => StatusReply.success(event))

      case _ =>
        Effect.reply(cmd.replyTo)(
          StatusReply.error(s"Cannot schedule payment in status ${invoice.status}")
        )
    }
  }

  // 支払完了ハンドラ
  private def handleCompletePayment(
    invoice: SupplierInvoice,
    cmd: SupplierPaymentCommand.CompletePayment
  ): ReplyEffect[SupplierPaymentEvent, State] = {
    invoice.paymentScheduledDate match {
      case Some(_) =>
        val paymentId = SupplierPaymentId(s"PAY-${UUID.randomUUID().toString}")

        val event = SupplierPaymentEvent.PaymentCompleted(
          invoiceId = invoice.id,
          paymentId = paymentId,
          paymentDate = cmd.paymentDate,
          paymentMethod = cmd.paymentMethod,
          paymentAmount = cmd.paymentAmount,
          occurredAt = Instant.now()
        )
        Effect.persist(event).thenReply(cmd.replyTo)(_ => StatusReply.success(event))

      case None =>
        Effect.reply(cmd.replyTo)(
          StatusReply.error("Payment has not been scheduled")
        )
    }
  }

  // イベントハンドラ
  private val eventHandler: (State, SupplierPaymentEvent) => State = { (state, event) =>
    event match {
      case e: SupplierPaymentEvent.InvoiceReceived =>
        val items = e.items.map { itemData =>
          SupplierInvoiceItem(
            productId = itemData.productId,
            productName = itemData.productName,
            quantity = itemData.quantity,
            unitPrice = itemData.unitPrice,
            taxRate = itemData.taxRate
          )
        }

        val invoice = SupplierInvoice(
          id = e.invoiceId,
          tenantId = e.tenantId,
          supplierId = e.supplierId,
          purchaseOrderId = e.purchaseOrderId,
          receivingId = e.receivingId,
          invoiceNumber = e.invoiceNumber,
          invoiceDate = e.invoiceDate,
          items = items,
          status = InvoiceStatus.Received,
          matchingResult = None,
          paymentDueDate = e.paymentDueDate,
          paymentScheduledDate = None,
          version = Version.initial
        )

        InvoiceState(invoice)

      case e: SupplierPaymentEvent.ThreeWayMatchingSucceeded =>
        state match {
          case InvoiceState(invoice) =>
            val result = ThreeWayMatchingResult(
              purchaseOrderAmount = e.matchingResult.purchaseOrderAmount,
              receivingAmount = e.matchingResult.receivingAmount,
              invoiceAmount = e.matchingResult.invoiceAmount,
              quantityMatches = e.matchingResult.quantityMatches,
              amountMatches = e.matchingResult.amountMatches,
              unitPriceMatches = e.matchingResult.unitPriceMatches,
              discrepancies = e.matchingResult.discrepancies
            )

            val updatedInvoice = invoice.copy(
              status = InvoiceStatus.Matched,
              matchingResult = Some(result),
              version = invoice.version.increment
            )
            InvoiceState(updatedInvoice)
          case _ => state
        }

      case e: SupplierPaymentEvent.ThreeWayMatchingFailed =>
        state match {
          case InvoiceState(invoice) =>
            val result = ThreeWayMatchingResult(
              purchaseOrderAmount = e.matchingResult.purchaseOrderAmount,
              receivingAmount = e.matchingResult.receivingAmount,
              invoiceAmount = e.matchingResult.invoiceAmount,
              quantityMatches = e.matchingResult.quantityMatches,
              amountMatches = e.matchingResult.amountMatches,
              unitPriceMatches = e.matchingResult.unitPriceMatches,
              discrepancies = e.matchingResult.discrepancies
            )

            val updatedInvoice = invoice.copy(
              status = InvoiceStatus.PartiallyMatched,
              matchingResult = Some(result),
              version = invoice.version.increment
            )
            InvoiceState(updatedInvoice)
          case _ => state
        }

      case e: SupplierPaymentEvent.PaymentApproved =>
        state match {
          case InvoiceState(invoice) =>
            val updatedInvoice = invoice.copy(
              status = InvoiceStatus.Approved,
              version = invoice.version.increment
            )
            InvoiceState(updatedInvoice)
          case _ => state
        }

      case e: SupplierPaymentEvent.PaymentScheduled =>
        state match {
          case InvoiceState(invoice) =>
            val updatedInvoice = invoice.copy(
              paymentScheduledDate = Some(e.scheduledDate),
              version = invoice.version.increment
            )
            InvoiceState(updatedInvoice)
          case _ => state
        }

      case e: SupplierPaymentEvent.PaymentCompleted =>
        state match {
          case InvoiceState(invoice) =>
            val updatedInvoice = invoice.copy(
              status = InvoiceStatus.Paid,
              version = invoice.version.increment
            )
            InvoiceState(updatedInvoice)
          case _ => state
        }
    }
  }
}
```

### 5.3.3 3-way Matchingビジネスロジックの実装

```scala
package com.example.procurement.domain.payment

import com.example.shared.domain.*

// 3-way Matching結果
final case class ThreeWayMatchingResult(
  purchaseOrderAmount: Money,
  receivingAmount: Money,
  invoiceAmount: Money,
  quantityMatches: Boolean,
  amountMatches: Boolean,
  unitPriceMatches: Boolean,
  discrepancies: List[String]
) {
  def isFullMatch: Boolean = quantityMatches && amountMatches && unitPriceMatches

  def hasDiscrepancy: Boolean = !isFullMatch
}

object ThreeWayMatchingResult {
  // 許容差（100円）
  private val TOLERANCE = Money(100)

  // 3-way matchingを実行
  def perform(
    purchaseOrder: com.example.procurement.domain.purchaseorder.PurchaseOrder,
    receiving: com.example.procurement.domain.receiving.Receiving,
    invoice: SupplierInvoice
  ): ThreeWayMatchingResult = {
    val poAmount = purchaseOrder.totalAmount
    val rcvAmount = receiving.totalAmount
    val invAmount = invoice.totalAmount

    // 数量の突合
    val quantityMatches = checkQuantityMatches(purchaseOrder, receiving, invoice)

    // 金額の突合
    val amountMatches = checkAmountMatches(rcvAmount, invAmount)

    // 単価の突合
    val unitPriceMatches = checkUnitPriceMatches(purchaseOrder, invoice)

    // 差異の詳細
    val discrepancies = collectDiscrepancies(
      purchaseOrder,
      receiving,
      invoice,
      quantityMatches,
      amountMatches,
      unitPriceMatches
    )

    ThreeWayMatchingResult(
      purchaseOrderAmount = poAmount,
      receivingAmount = rcvAmount,
      invoiceAmount = invAmount,
      quantityMatches = quantityMatches,
      amountMatches = amountMatches,
      unitPriceMatches = unitPriceMatches,
      discrepancies = discrepancies
    )
  }

  // 数量の突合チェック
  private def checkQuantityMatches(
    purchaseOrder: com.example.procurement.domain.purchaseorder.PurchaseOrder,
    receiving: com.example.procurement.domain.receiving.Receiving,
    invoice: SupplierInvoice
  ): Boolean = {
    // 各商品の数量を比較
    invoice.items.forall { invItem =>
      val poItem = purchaseOrder.items.find(_.productId == invItem.productId)
      val rcvItem = receiving.items.find(_.productId == invItem.productId)

      (poItem, rcvItem) match {
        case (Some(po), Some(rcv)) =>
          // 入荷数量と請求数量が一致することを確認
          rcv.acceptedQuantity == invItem.quantity
        case _ =>
          false // 商品が見つからない場合は不一致
      }
    }
  }

  // 金額の突合チェック
  private def checkAmountMatches(
    receivingAmount: Money,
    invoiceAmount: Money
  ): Boolean = {
    val diff = (receivingAmount - invoiceAmount).abs
    diff <= TOLERANCE
  }

  // 単価の突合チェック
  private def checkUnitPriceMatches(
    purchaseOrder: com.example.procurement.domain.purchaseorder.PurchaseOrder,
    invoice: SupplierInvoice
  ): Boolean = {
    invoice.items.forall { invItem =>
      purchaseOrder.items.find(_.productId == invItem.productId) match {
        case Some(poItem) =>
          poItem.unitPrice == invItem.unitPrice
        case None =>
          false
      }
    }
  }

  // 差異の詳細を収集
  private def collectDiscrepancies(
    purchaseOrder: com.example.procurement.domain.purchaseorder.PurchaseOrder,
    receiving: com.example.procurement.domain.receiving.Receiving,
    invoice: SupplierInvoice,
    quantityMatches: Boolean,
    amountMatches: Boolean,
    unitPriceMatches: Boolean
  ): List[String] = {
    val discrepancies = scala.collection.mutable.ListBuffer[String]()

    if (!quantityMatches) {
      invoice.items.foreach { invItem =>
        val rcvItem = receiving.items.find(_.productId == invItem.productId)
        rcvItem match {
          case Some(rcv) if rcv.acceptedQuantity != invItem.quantity =>
            discrepancies += s"Quantity mismatch for ${invItem.productName.value}: " +
              s"Received=${rcv.acceptedQuantity.value}, Invoiced=${invItem.quantity.value}"
          case None =>
            discrepancies += s"Product ${invItem.productName.value} not found in receiving"
          case _ => // 一致している
        }
      }
    }

    if (!amountMatches) {
      discrepancies += s"Amount mismatch: " +
        s"Receiving=${receiving.totalAmount.amount}, " +
        s"Invoice=${invoice.totalAmount.amount}"
    }

    if (!unitPriceMatches) {
      invoice.items.foreach { invItem =>
        purchaseOrder.items.find(_.productId == invItem.productId) match {
          case Some(poItem) if poItem.unitPrice != invItem.unitPrice =>
            discrepancies += s"Unit price mismatch for ${invItem.productName.value}: " +
              s"PO=${poItem.unitPrice.amount}, Invoice=${invItem.unitPrice.amount}"
          case None =>
            discrepancies += s"Product ${invItem.productName.value} not found in purchase order"
          case _ => // 一致している
        }
      }
    }

    discrepancies.toList
  }
}
```

---

## 5.4 集約間の連携

### 5.4.1 イベント駆動連携の実装

各集約は独立していますが、ドメインイベントを通じて連携します。

```scala
package com.example.procurement.application

import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import org.apache.pekko.persistence.query.PersistenceQuery
import org.apache.pekko.persistence.query.scaladsl.EventsByTagQuery
import org.apache.pekko.stream.scaladsl.{RestartSource, Sink}
import org.apache.pekko.pattern.StatusReply
import com.example.shared.domain.*
import com.example.procurement.domain.purchaseorder.*
import com.example.procurement.domain.receiving.*
import com.example.procurement.domain.payment.*
import scala.concurrent.duration.*

// イベントプロセッサ: Receivingイベントを監視してPurchaseOrderを更新
object ReceivingEventProcessor {

  def apply(
    sharding: ClusterSharding
  )(implicit system: ActorSystem[_]): Behavior[Nothing] = {
    Behaviors.setup { context =>
      implicit val ec = context.executionContext

      // PersistenceQueryプラグインを取得
      val readJournal = PersistenceQuery(system)
        .readJournalFor[EventsByTagQuery]("jdbc-read-journal")

      // "receiving-completed"タグでイベントをストリーム
      val source = RestartSource.onFailuresWithBackoff(
        RestartSettings(
          minBackoff = 3.seconds,
          maxBackoff = 30.seconds,
          randomFactor = 0.2
        )
      ) { () =>
        readJournal.eventsByTag("receiving-completed", offset = readJournal.currentOffset)
      }

      // イベントを処理
      source.runWith(Sink.foreach { envelope =>
        envelope.event match {
          case event: ReceivingEvent.InspectionCompleted =>
            // PurchaseOrderアクターに入荷記録を送信
            val purchaseOrderRef = sharding.entityRefFor(
              PurchaseOrderActor.TypeKey,
              event.receivingId.value // 実際にはPurchaseOrderIdが必要
            )

            // 入荷データを構築
            val receivedItems = event.inspectedItems
              .filter(_.inspectionResult == InspectionResult.Accepted)
              .map { item =>
                ReceivedItemData(
                  productId = item.productId,
                  receivedQuantity = item.acceptedQuantity
                )
              }

            // コマンドを送信
            purchaseOrderRef.ask { replyTo: ActorRef[StatusReply[PurchaseOrderEvent.ReceiptRecorded]] =>
              PurchaseOrderCommand.RecordReceipt(
                receivingId = ReceivingId(event.receivingId.value),
                receivedItems = receivedItems,
                replyTo = replyTo
              )
            }

          case _ => // 他のイベントは無視
        }
      })

      Behaviors.empty
    }
  }
}

// イベントプロセッサ: InspectionCompletedイベントを監視して在庫を更新
object InventoryUpdateProcessor {

  def apply(
    inventoryService: ActorRef[InventoryCommand]
  )(implicit system: ActorSystem[_]): Behavior[Nothing] = {
    Behaviors.setup { context =>
      implicit val ec = context.executionContext

      val readJournal = PersistenceQuery(system)
        .readJournalFor[EventsByTagQuery]("jdbc-read-journal")

      val source = RestartSource.onFailuresWithBackoff(
        RestartSettings(
          minBackoff = 3.seconds,
          maxBackoff = 30.seconds,
          randomFactor = 0.2
        )
      ) { () =>
        readJournal.eventsByTag("receiving-completed", offset = readJournal.currentOffset)
      }

      source.runWith(Sink.foreach { envelope =>
        envelope.event match {
          case event: ReceivingEvent.InspectionCompleted =>
            // 合格した商品の在庫を増やす
            event.inspectedItems
              .filter(_.inspectionResult == InspectionResult.Accepted)
              .foreach { item =>
                // 在庫増加コマンドを送信（第3部の在庫管理サービスと連携）
                // inventoryService ! IncreaseInventory(...)
              }

          case _ => // 他のイベントは無視
        }
      })

      Behaviors.empty
    }
  }
}
```

### 5.4.2 Cluster Shardingの設定

各集約アクターをCluster Shardingで管理します。

```scala
package com.example.procurement.application

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityTypeKey}
import com.example.procurement.domain.purchaseorder.*
import com.example.procurement.domain.receiving.*
import com.example.procurement.domain.payment.*

object ProcurementSharding {

  // PurchaseOrderアクターのEntityTypeKey
  val PurchaseOrderTypeKey = EntityTypeKey[PurchaseOrderCommand]("PurchaseOrder")

  // ReceivingアクターのEntityTypeKey
  val ReceivingTypeKey = EntityTypeKey[ReceivingCommand]("Receiving")

  // SupplierPaymentアクターのEntityTypeKey
  val SupplierPaymentTypeKey = EntityTypeKey[SupplierPaymentCommand]("SupplierPayment")

  // Cluster Shardingの初期化
  def init(system: ActorSystem[_]): ClusterSharding = {
    val sharding = ClusterSharding(system)

    // PurchaseOrderアクターの登録
    sharding.init(Entity(PurchaseOrderTypeKey) { entityContext =>
      val purchaseOrderId = PurchaseOrderId(entityContext.entityId)
      PurchaseOrderActor(purchaseOrderId, () => OrderNumber.generate())
    })

    // Receivingアクターの登録
    sharding.init(Entity(ReceivingTypeKey) { entityContext =>
      val receivingId = ReceivingId(entityContext.entityId)
      ReceivingActor(receivingId, () => ReceivingNumber.generate())
    })

    // SupplierPaymentアクターの登録
    sharding.init(Entity(SupplierPaymentTypeKey) { entityContext =>
      val invoiceId = SupplierInvoiceId(entityContext.entityId)
      SupplierPaymentActor(invoiceId)
    })

    sharding
  }
}
```

---

## 5.5 テストの実装

### 5.5.1 PurchaseOrderアクターのテスト

```scala
package com.example.procurement.domain.purchaseorder

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.apache.pekko.pattern.StatusReply
import org.scalatest.wordspec.AnyWordSpecLike
import com.example.shared.domain.*
import java.time.LocalDate

class PurchaseOrderActorSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  "PurchaseOrderActor" should {

    "create a purchase order" in {
      val purchaseOrderId = PurchaseOrderId("PO-001")
      val actor = spawn(PurchaseOrderActor(purchaseOrderId, () => OrderNumber("PO-2025-001")))
      val probe = createTestProbe[StatusReply[PurchaseOrderEvent.PurchaseOrderCreated]]()

      val items = List(
        PurchaseOrderItemData(
          productId = ProductId("PROD-001"),
          productName = ProductName("商品A"),
          quantity = Quantity(10),
          unitPrice = Money(1000),
          taxRate = TaxRate(0.10)
        )
      )

      actor ! PurchaseOrderCommand.CreatePurchaseOrder(
        tenantId = TenantId("TENANT-001"),
        supplierId = SupplierId("SUP-001"),
        orderDate = LocalDate.now(),
        deliveryDate = LocalDate.now().plusDays(7),
        items = items,
        replyTo = probe.ref
      )

      val reply = probe.receiveMessage()
      assert(reply.isSuccess)

      val event = reply.getValue
      assert(event.orderNumber == OrderNumber("PO-2025-001"))
      assert(event.items.length == 1)
      assert(event.totalAmount == Money(11000)) // 10,000 + 1,000 (税)
    }

    "require approval for orders over 500,000 yen" in {
      val purchaseOrderId = PurchaseOrderId("PO-002")
      val actor = spawn(PurchaseOrderActor(purchaseOrderId, () => OrderNumber("PO-2025-002")))
      val createProbe = createTestProbe[StatusReply[PurchaseOrderEvent.PurchaseOrderCreated]]()

      val items = List(
        PurchaseOrderItemData(
          productId = ProductId("PROD-001"),
          productName = ProductName("高額商品"),
          quantity = Quantity(10),
          unitPrice = Money(60000), // 60万円
          taxRate = TaxRate(0.10)
        )
      )

      actor ! PurchaseOrderCommand.CreatePurchaseOrder(
        tenantId = TenantId("TENANT-001"),
        supplierId = SupplierId("SUP-001"),
        orderDate = LocalDate.now(),
        deliveryDate = LocalDate.now().plusDays(7),
        items = items,
        replyTo = createProbe.ref
      )

      val createReply = createProbe.receiveMessage()
      assert(createReply.isSuccess)
      assert(createReply.getValue.approvalRequired)

      // 承認申請
      val approvalProbe = createTestProbe[StatusReply[PurchaseOrderEvent.ApprovalRequested]]()
      actor ! PurchaseOrderCommand.RequestApproval(replyTo = approvalProbe.ref)

      val approvalReply = approvalProbe.receiveMessage()
      assert(approvalReply.isSuccess)
      assert(approvalReply.getValue.requiredApproverRole == ApproverRole.Director) // 部長承認が必要
    }

    "approve a purchase order" in {
      val purchaseOrderId = PurchaseOrderId("PO-003")
      val actor = spawn(PurchaseOrderActor(purchaseOrderId, () => OrderNumber("PO-2025-003")))

      // 発注作成
      val createProbe = createTestProbe[StatusReply[PurchaseOrderEvent.PurchaseOrderCreated]]()
      val items = List(
        PurchaseOrderItemData(
          productId = ProductId("PROD-001"),
          productName = ProductName("商品A"),
          quantity = Quantity(100),
          unitPrice = Money(10000),
          taxRate = TaxRate(0.10)
        )
      )

      actor ! PurchaseOrderCommand.CreatePurchaseOrder(
        tenantId = TenantId("TENANT-001"),
        supplierId = SupplierId("SUP-001"),
        orderDate = LocalDate.now(),
        deliveryDate = LocalDate.now().plusDays(7),
        items = items,
        replyTo = createProbe.ref
      )

      createProbe.receiveMessage()

      // 承認申請
      val requestProbe = createTestProbe[StatusReply[PurchaseOrderEvent.ApprovalRequested]]()
      actor ! PurchaseOrderCommand.RequestApproval(replyTo = requestProbe.ref)
      requestProbe.receiveMessage()

      // 承認
      val approveProbe = createTestProbe[StatusReply[PurchaseOrderEvent.PurchaseOrderApproved]]()
      actor ! PurchaseOrderCommand.ApprovePurchaseOrder(
        approverId = UserId("USER-001"),
        approverRole = ApproverRole.Executive, // 役員が承認
        comment = Some("承認します"),
        replyTo = approveProbe.ref
      )

      val approveReply = approveProbe.receiveMessage()
      assert(approveReply.isSuccess)
      assert(approveReply.getValue.approverRole == ApproverRole.Executive)
    }
  }
}
```

---

## まとめ

本章では、第4章で設計したドメインモデルをPekko Persistenceアクターとして実装しました。

### 実装した内容

1. **PurchaseOrder集約**
   - 発注作成、承認ワークフロー、発注書発行、入荷記録
   - 金額に応じた承認者ロールの判定
   - イベントソーシングによる状態管理

2. **Receiving集約**
   - 入荷記録作成、検収プロセス、差異検出
   - 検査結果に基づくステータス遷移
   - ロット管理と有効期限追跡

3. **SupplierPayment集約**
   - 請求書受領、3-way matching、支払処理
   - 発注・入荷・請求の突合ロジック
   - 差異検出と承認フロー

4. **集約間連携**
   - イベント駆動アーキテクチャによる疎結合
   - Pekko Persistence Queryによるイベントストリーム処理
   - Cluster Shardingによるスケーラブルな配置

### 次章の予告

次章では、Sagaパターンを使った複雑な発注プロセスの実装を行います。発注承認Saga、入荷検収Saga、支払Sagaを実装し、エラー処理と補償トランザクションを含む堅牢なビジネスプロセスを構築します。
