# Part5 第6章: Sagaパターンによる発注プロセスの実装

## 本章の目的

発注管理システムでは、複数の集約をまたがる複雑なビジネスプロセスが存在します。本章では、Sagaパターンを使ってこれらの分散トランザクションを実装し、エラー発生時の補償処理を含む堅牢なシステムを構築します。

## 本章で学ぶこと

- Sagaパターンの概念と実装戦略
- Pekko型付きアクターによるSagaの実装
- 正常フローと補償フローの設計
- イベント駆動によるSagaステップの実行
- エラーハンドリングとリトライ戦略
- Sagaの状態管理と監視

---

## 6.1 Sagaパターンの概要

### 6.1.1 Sagaパターンとは

Sagaパターンは、マイクロサービスやイベントソーシングアーキテクチャにおいて、複数のサービス（集約）にまたがる長期実行トランザクションを管理するためのパターンです。

#### Sagaの特徴

1. **分散トランザクション**: 複数の集約をまたがる処理を順次実行
2. **補償トランザクション**: エラー発生時に既に実行された処理を元に戻す
3. **結果整合性**: 最終的には整合性が取れる状態を目指す
4. **イベント駆動**: 各ステップの完了をイベントで通知

#### Sagaの実装方式

1. **オーケストレーション**: 中央のSagaコーディネーターが各ステップを制御
2. **コレオグラフィ**: 各サービスがイベントを監視して自律的に次のステップを実行

本章では、**オーケストレーション方式**を採用します。これにより、ビジネスプロセス全体を一箇所で管理でき、可視性と保守性が向上します。

### 6.1.2 発注管理における3つのSaga

発注管理システムでは、以下の3つのSagaを実装します：

1. **発注承認Saga**: 発注作成 → 承認申請 → 承認 → 発注書発行 → サプライヤー通知
2. **入荷検収Saga**: 入荷記録 → 検収開始 → 検収完了 → 在庫更新 → 発注ステータス更新
3. **支払Saga**: 請求書受領 → 3-way matching → 支払承認 → 支払予定設定 → 支払実行

---

## 6.2 発注承認Saga

### 6.2.1 Sagaの定義

発注承認Sagaは、発注が作成されてから発注書が発行され、サプライヤーに通知されるまでのプロセスを管理します。

```scala
package com.example.procurement.saga.approval

import com.example.shared.domain.*
import com.example.procurement.domain.purchaseorder.*
import java.time.Instant

// Sagaの状態
sealed trait PurchaseOrderApprovalSagaState

object PurchaseOrderApprovalSagaState {
  case object NotStarted extends PurchaseOrderApprovalSagaState

  final case class ApprovalRequested(
    purchaseOrderId: PurchaseOrderId,
    requestedAt: Instant
  ) extends PurchaseOrderApprovalSagaState

  final case class Approved(
    purchaseOrderId: PurchaseOrderId,
    approverId: UserId,
    approvedAt: Instant
  ) extends PurchaseOrderApprovalSagaState

  final case class Issued(
    purchaseOrderId: PurchaseOrderId,
    issuedAt: Instant
  ) extends PurchaseOrderApprovalSagaState

  final case class SupplierNotified(
    purchaseOrderId: PurchaseOrderId,
    notifiedAt: Instant
  ) extends PurchaseOrderApprovalSagaState

  final case class Completed(
    purchaseOrderId: PurchaseOrderId,
    completedAt: Instant
  ) extends PurchaseOrderApprovalSagaState

  // 補償状態
  final case class Rejected(
    purchaseOrderId: PurchaseOrderId,
    reason: String,
    rejectedAt: Instant
  ) extends PurchaseOrderApprovalSagaState

  final case class Cancelled(
    purchaseOrderId: PurchaseOrderId,
    reason: String,
    cancelledAt: Instant
  ) extends PurchaseOrderApprovalSagaState

  final case class Failed(
    purchaseOrderId: PurchaseOrderId,
    reason: String,
    failedAt: Instant
  ) extends PurchaseOrderApprovalSagaState
}

// Sagaのコマンド
sealed trait PurchaseOrderApprovalSagaCommand

object PurchaseOrderApprovalSagaCommand {
  final case class StartSaga(
    purchaseOrderId: PurchaseOrderId
  ) extends PurchaseOrderApprovalSagaCommand

  final case class ApprovalGranted(
    purchaseOrderId: PurchaseOrderId,
    approverId: UserId
  ) extends PurchaseOrderApprovalSagaCommand

  final case class ApprovalRejected(
    purchaseOrderId: PurchaseOrderId,
    reason: String
  ) extends PurchaseOrderApprovalSagaCommand

  final case class IssuingSucceeded(
    purchaseOrderId: PurchaseOrderId
  ) extends PurchaseOrderApprovalSagaCommand

  final case class IssuingFailed(
    purchaseOrderId: PurchaseOrderId,
    reason: String
  ) extends PurchaseOrderApprovalSagaCommand

  final case class SupplierNotificationSucceeded(
    purchaseOrderId: PurchaseOrderId
  ) extends PurchaseOrderApprovalSagaCommand

  final case class SupplierNotificationFailed(
    purchaseOrderId: PurchaseOrderId,
    reason: String
  ) extends PurchaseOrderApprovalSagaCommand
}

// Sagaのイベント
sealed trait PurchaseOrderApprovalSagaEvent {
  def sagaId: SagaId
  def occurredAt: Instant
}

object PurchaseOrderApprovalSagaEvent {
  final case class SagaStarted(
    sagaId: SagaId,
    purchaseOrderId: PurchaseOrderId,
    occurredAt: Instant
  ) extends PurchaseOrderApprovalSagaEvent

  final case class ApprovalRequestSent(
    sagaId: SagaId,
    purchaseOrderId: PurchaseOrderId,
    occurredAt: Instant
  ) extends PurchaseOrderApprovalSagaEvent

  final case class ApprovalReceived(
    sagaId: SagaId,
    purchaseOrderId: PurchaseOrderId,
    approverId: UserId,
    occurredAt: Instant
  ) extends PurchaseOrderApprovalSagaEvent

  final case class PurchaseOrderIssueSent(
    sagaId: SagaId,
    purchaseOrderId: PurchaseOrderId,
    occurredAt: Instant
  ) extends PurchaseOrderApprovalSagaEvent

  final case class SupplierNotificationSent(
    sagaId: SagaId,
    purchaseOrderId: PurchaseOrderId,
    occurredAt: Instant
  ) extends PurchaseOrderApprovalSagaEvent

  final case class SagaCompleted(
    sagaId: SagaId,
    purchaseOrderId: PurchaseOrderId,
    occurredAt: Instant
  ) extends PurchaseOrderApprovalSagaEvent

  // 補償イベント
  final case class ApprovalRejectedEvent(
    sagaId: SagaId,
    purchaseOrderId: PurchaseOrderId,
    reason: String,
    occurredAt: Instant
  ) extends PurchaseOrderApprovalSagaEvent

  final case class CompensationStarted(
    sagaId: SagaId,
    purchaseOrderId: PurchaseOrderId,
    reason: String,
    occurredAt: Instant
  ) extends PurchaseOrderApprovalSagaEvent

  final case class SagaCancelled(
    sagaId: SagaId,
    purchaseOrderId: PurchaseOrderId,
    reason: String,
    occurredAt: Instant
  ) extends PurchaseOrderApprovalSagaEvent

  final case class SagaFailed(
    sagaId: SagaId,
    purchaseOrderId: PurchaseOrderId,
    reason: String,
    occurredAt: Instant
  ) extends PurchaseOrderApprovalSagaEvent
}

// SagaId値オブジェクト
final case class SagaId(value: String) extends AnyVal
```

### 6.2.2 発注承認Sagaアクターの実装

```scala
package com.example.procurement.saga.approval

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import com.example.shared.domain.*
import com.example.procurement.domain.purchaseorder.*
import java.time.Instant
import scala.concurrent.duration.*

object PurchaseOrderApprovalSagaActor {

  import PurchaseOrderApprovalSagaState.*
  import PurchaseOrderApprovalSagaCommand.*
  import PurchaseOrderApprovalSagaEvent.*

  // 依存関係
  final case class Dependencies(
    sharding: ClusterSharding,
    supplierNotificationService: ActorRef[SupplierNotificationCommand]
  )

  def apply(
    sagaId: SagaId,
    dependencies: Dependencies
  ): Behavior[PurchaseOrderApprovalSagaCommand] = {
    EventSourcedBehavior[
      PurchaseOrderApprovalSagaCommand,
      PurchaseOrderApprovalSagaEvent,
      PurchaseOrderApprovalSagaState
    ](
      persistenceId = PersistenceId.ofUniqueId(s"PurchaseOrderApprovalSaga-${sagaId.value}"),
      emptyState = NotStarted,
      commandHandler = commandHandler(sagaId, dependencies),
      eventHandler = eventHandler
    ).withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2))
  }

  // コマンドハンドラ
  private def commandHandler(
    sagaId: SagaId,
    dependencies: Dependencies
  )(state: PurchaseOrderApprovalSagaState, command: PurchaseOrderApprovalSagaCommand): Effect[PurchaseOrderApprovalSagaEvent, PurchaseOrderApprovalSagaState] = {
    (state, command) match {
      // Sagaの開始
      case (NotStarted, StartSaga(purchaseOrderId)) =>
        Effect
          .persist(SagaStarted(sagaId, purchaseOrderId, Instant.now()))
          .thenRun { _ =>
            // 発注承認申請コマンドを送信
            val purchaseOrderRef = dependencies.sharding.entityRefFor(
              PurchaseOrderActor.TypeKey,
              purchaseOrderId.value
            )

            purchaseOrderRef.ask[StatusReply[PurchaseOrderEvent.ApprovalRequested]] { replyTo =>
              PurchaseOrderCommand.RequestApproval(replyTo)
            }
          }
          .thenPersist(ApprovalRequestSent(sagaId, purchaseOrderId, Instant.now()))

      // 承認が得られた場合
      case (ApprovalRequested(purchaseOrderId, _), ApprovalGranted(_, approverId)) =>
        Effect
          .persist(ApprovalReceived(sagaId, purchaseOrderId, approverId, Instant.now()))
          .thenRun { _ =>
            // 発注書発行コマンドを送信
            val purchaseOrderRef = dependencies.sharding.entityRefFor(
              PurchaseOrderActor.TypeKey,
              purchaseOrderId.value
            )

            purchaseOrderRef.ask[StatusReply[PurchaseOrderEvent.PurchaseOrderIssued]] { replyTo =>
              PurchaseOrderCommand.IssuePurchaseOrder(
                issuedBy = approverId,
                replyTo = replyTo
              )
            }
          }
          .thenPersist(PurchaseOrderIssueSent(sagaId, purchaseOrderId, Instant.now()))

      // 発注書発行が成功した場合
      case (Approved(purchaseOrderId, _, _), IssuingSucceeded(_)) =>
        Effect
          .persist(PurchaseOrderIssueSent(sagaId, purchaseOrderId, Instant.now()))
          .thenRun { _ =>
            // サプライヤー通知コマンドを送信
            dependencies.supplierNotificationService ! SupplierNotificationCommand.NotifyPurchaseOrder(
              purchaseOrderId = purchaseOrderId,
              replyTo = ActorRef.noSender // 実際にはSagaアクター自身
            )
          }
          .thenPersist(SupplierNotificationSent(sagaId, purchaseOrderId, Instant.now()))

      // サプライヤー通知が成功した場合
      case (Issued(purchaseOrderId, _), SupplierNotificationSucceeded(_)) =>
        Effect
          .persist(SupplierNotificationSent(sagaId, purchaseOrderId, Instant.now()))
          .thenPersist(SagaCompleted(sagaId, purchaseOrderId, Instant.now()))

      // 承認が却下された場合（補償フロー）
      case (ApprovalRequested(purchaseOrderId, _), ApprovalRejected(_, reason)) =>
        Effect
          .persist(ApprovalRejectedEvent(sagaId, purchaseOrderId, reason, Instant.now()))
          .thenRun { _ =>
            // 発注をキャンセル（補償トランザクション）
            val purchaseOrderRef = dependencies.sharding.entityRefFor(
              PurchaseOrderActor.TypeKey,
              purchaseOrderId.value
            )

            purchaseOrderRef.ask[StatusReply[PurchaseOrderEvent.PurchaseOrderCancelled]] { replyTo =>
              PurchaseOrderCommand.CancelPurchaseOrder(
                cancelledBy = UserId("SYSTEM"),
                reason = s"Approval rejected: $reason",
                replyTo = replyTo
              )
            }
          }
          .thenPersist(SagaCancelled(sagaId, purchaseOrderId, reason, Instant.now()))

      // 発注書発行が失敗した場合（補償フロー）
      case (Approved(purchaseOrderId, _, _), IssuingFailed(_, reason)) =>
        Effect
          .persist(CompensationStarted(sagaId, purchaseOrderId, reason, Instant.now()))
          .thenRun { _ =>
            // 承認を取り消して発注をキャンセル（補償トランザクション）
            val purchaseOrderRef = dependencies.sharding.entityRefFor(
              PurchaseOrderActor.TypeKey,
              purchaseOrderId.value
            )

            purchaseOrderRef.ask[StatusReply[PurchaseOrderEvent.PurchaseOrderCancelled]] { replyTo =>
              PurchaseOrderCommand.CancelPurchaseOrder(
                cancelledBy = UserId("SYSTEM"),
                reason = s"Issuing failed: $reason",
                replyTo = replyTo
              )
            }
          }
          .thenPersist(SagaCancelled(sagaId, purchaseOrderId, reason, Instant.now()))

      // サプライヤー通知が失敗した場合（リトライ可能なので失敗として記録）
      case (Issued(purchaseOrderId, _), SupplierNotificationFailed(_, reason)) =>
        Effect.persist(SagaFailed(sagaId, purchaseOrderId, reason, Instant.now()))

      case _ =>
        Effect.unhandled
    }
  }

  // イベントハンドラ
  private val eventHandler: (PurchaseOrderApprovalSagaState, PurchaseOrderApprovalSagaEvent) => PurchaseOrderApprovalSagaState = {
    (state, event) =>
      event match {
        case SagaStarted(_, purchaseOrderId, _) =>
          NotStarted

        case ApprovalRequestSent(_, purchaseOrderId, requestedAt) =>
          ApprovalRequested(purchaseOrderId, requestedAt)

        case ApprovalReceived(_, purchaseOrderId, approverId, approvedAt) =>
          Approved(purchaseOrderId, approverId, approvedAt)

        case PurchaseOrderIssueSent(_, purchaseOrderId, issuedAt) =>
          Issued(purchaseOrderId, issuedAt)

        case SupplierNotificationSent(_, purchaseOrderId, notifiedAt) =>
          SupplierNotified(purchaseOrderId, notifiedAt)

        case SagaCompleted(_, purchaseOrderId, completedAt) =>
          Completed(purchaseOrderId, completedAt)

        case ApprovalRejectedEvent(_, purchaseOrderId, reason, rejectedAt) =>
          Rejected(purchaseOrderId, reason, rejectedAt)

        case SagaCancelled(_, purchaseOrderId, reason, cancelledAt) =>
          Cancelled(purchaseOrderId, reason, cancelledAt)

        case SagaFailed(_, purchaseOrderId, reason, failedAt) =>
          Failed(purchaseOrderId, reason, failedAt)

        case _ =>
          state
      }
  }
}

// サプライヤー通知サービスのコマンド（簡易版）
sealed trait SupplierNotificationCommand

object SupplierNotificationCommand {
  final case class NotifyPurchaseOrder(
    purchaseOrderId: PurchaseOrderId,
    replyTo: ActorRef[SupplierNotificationResult]
  ) extends SupplierNotificationCommand
}

sealed trait SupplierNotificationResult

object SupplierNotificationResult {
  final case class NotificationSucceeded(purchaseOrderId: PurchaseOrderId) extends SupplierNotificationResult
  final case class NotificationFailed(purchaseOrderId: PurchaseOrderId, reason: String) extends SupplierNotificationResult
}
```

---

## 6.3 入荷検収Saga

### 6.3.1 Sagaの定義

入荷検収Sagaは、商品が入荷してから検収が完了し、在庫が更新され、発注ステータスが更新されるまでのプロセスを管理します。

```scala
package com.example.procurement.saga.receiving

import com.example.shared.domain.*
import com.example.procurement.domain.receiving.*
import com.example.procurement.domain.purchaseorder.*
import java.time.Instant

// Sagaの状態
sealed trait ReceivingInspectionSagaState

object ReceivingInspectionSagaState {
  case object NotStarted extends ReceivingInspectionSagaState

  final case class InspectionStarted(
    receivingId: ReceivingId,
    purchaseOrderId: PurchaseOrderId,
    inspectorId: UserId,
    startedAt: Instant
  ) extends ReceivingInspectionSagaState

  final case class InspectionCompleted(
    receivingId: ReceivingId,
    purchaseOrderId: PurchaseOrderId,
    completedAt: Instant,
    acceptedItems: List[InspectedItemData]
  ) extends ReceivingInspectionSagaState

  final case class InventoryUpdated(
    receivingId: ReceivingId,
    purchaseOrderId: PurchaseOrderId,
    updatedAt: Instant
  ) extends ReceivingInspectionSagaState

  final case class PurchaseOrderUpdated(
    receivingId: ReceivingId,
    purchaseOrderId: PurchaseOrderId,
    updatedAt: Instant
  ) extends ReceivingInspectionSagaState

  final case class Completed(
    receivingId: ReceivingId,
    purchaseOrderId: PurchaseOrderId,
    completedAt: Instant
  ) extends ReceivingInspectionSagaState

  // 補償状態
  final case class InspectionFailed(
    receivingId: ReceivingId,
    purchaseOrderId: PurchaseOrderId,
    reason: String,
    failedAt: Instant
  ) extends ReceivingInspectionSagaState

  final case class InventoryRolledBack(
    receivingId: ReceivingId,
    purchaseOrderId: PurchaseOrderId,
    reason: String,
    rolledBackAt: Instant
  ) extends ReceivingInspectionSagaState
}

// Sagaのコマンド
sealed trait ReceivingInspectionSagaCommand

object ReceivingInspectionSagaCommand {
  final case class StartSaga(
    receivingId: ReceivingId,
    purchaseOrderId: PurchaseOrderId,
    inspectorId: UserId
  ) extends ReceivingInspectionSagaCommand

  final case class InspectionCompletedSuccessfully(
    receivingId: ReceivingId,
    acceptedItems: List[InspectedItemData]
  ) extends ReceivingInspectionSagaCommand

  final case class InspectionFailedWithDiscrepancy(
    receivingId: ReceivingId,
    reason: String
  ) extends ReceivingInspectionSagaCommand

  final case class InventoryUpdateSucceeded(
    receivingId: ReceivingId
  ) extends ReceivingInspectionSagaCommand

  final case class InventoryUpdateFailed(
    receivingId: ReceivingId,
    reason: String
  ) extends ReceivingInspectionSagaCommand

  final case class PurchaseOrderUpdateSucceeded(
    receivingId: ReceivingId
  ) extends ReceivingInspectionSagaCommand
}

// Sagaのイベント
sealed trait ReceivingInspectionSagaEvent {
  def sagaId: SagaId
  def occurredAt: Instant
}

object ReceivingInspectionSagaEvent {
  final case class SagaStarted(
    sagaId: SagaId,
    receivingId: ReceivingId,
    purchaseOrderId: PurchaseOrderId,
    occurredAt: Instant
  ) extends ReceivingInspectionSagaEvent

  final case class InspectionStartCommandSent(
    sagaId: SagaId,
    receivingId: ReceivingId,
    inspectorId: UserId,
    occurredAt: Instant
  ) extends ReceivingInspectionSagaEvent

  final case class InspectionCompletedEvent(
    sagaId: SagaId,
    receivingId: ReceivingId,
    acceptedItems: List[InspectedItemData],
    occurredAt: Instant
  ) extends ReceivingInspectionSagaEvent

  final case class InventoryUpdateCommandSent(
    sagaId: SagaId,
    receivingId: ReceivingId,
    occurredAt: Instant
  ) extends ReceivingInspectionSagaEvent

  final case class InventoryUpdatedEvent(
    sagaId: SagaId,
    receivingId: ReceivingId,
    occurredAt: Instant
  ) extends ReceivingInspectionSagaEvent

  final case class PurchaseOrderUpdateCommandSent(
    sagaId: SagaId,
    receivingId: ReceivingId,
    purchaseOrderId: PurchaseOrderId,
    occurredAt: Instant
  ) extends ReceivingInspectionSagaEvent

  final case class SagaCompleted(
    sagaId: SagaId,
    receivingId: ReceivingId,
    occurredAt: Instant
  ) extends ReceivingInspectionSagaEvent

  // 補償イベント
  final case class InspectionFailedEvent(
    sagaId: SagaId,
    receivingId: ReceivingId,
    reason: String,
    occurredAt: Instant
  ) extends ReceivingInspectionSagaEvent

  final case class DiscrepancyRecorded(
    sagaId: SagaId,
    receivingId: ReceivingId,
    reason: String,
    occurredAt: Instant
  ) extends ReceivingInspectionSagaEvent

  final case class SupplierNotificationSent(
    sagaId: SagaId,
    receivingId: ReceivingId,
    occurredAt: Instant
  ) extends ReceivingInspectionSagaEvent

  final case class InventoryRollbackCommandSent(
    sagaId: SagaId,
    receivingId: ReceivingId,
    reason: String,
    occurredAt: Instant
  ) extends ReceivingInspectionSagaEvent
}
```

### 6.3.2 入荷検収Sagaアクターの実装

```scala
package com.example.procurement.saga.receiving

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import com.example.shared.domain.*
import com.example.procurement.domain.receiving.*
import com.example.procurement.domain.purchaseorder.*
import java.time.Instant

object ReceivingInspectionSagaActor {

  import ReceivingInspectionSagaState.*
  import ReceivingInspectionSagaCommand.*
  import ReceivingInspectionSagaEvent.*

  final case class Dependencies(
    sharding: ClusterSharding,
    inventoryService: ActorRef[InventoryCommand]
  )

  def apply(
    sagaId: SagaId,
    dependencies: Dependencies
  ): Behavior[ReceivingInspectionSagaCommand] = {
    EventSourcedBehavior[
      ReceivingInspectionSagaCommand,
      ReceivingInspectionSagaEvent,
      ReceivingInspectionSagaState
    ](
      persistenceId = PersistenceId.ofUniqueId(s"ReceivingInspectionSaga-${sagaId.value}"),
      emptyState = NotStarted,
      commandHandler = commandHandler(sagaId, dependencies),
      eventHandler = eventHandler
    ).withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2))
  }

  private def commandHandler(
    sagaId: SagaId,
    dependencies: Dependencies
  )(state: ReceivingInspectionSagaState, command: ReceivingInspectionSagaCommand): Effect[ReceivingInspectionSagaEvent, ReceivingInspectionSagaState] = {
    (state, command) match {
      // Sagaの開始
      case (NotStarted, StartSaga(receivingId, purchaseOrderId, inspectorId)) =>
        Effect
          .persist(SagaStarted(sagaId, receivingId, purchaseOrderId, Instant.now()))
          .thenRun { _ =>
            // 検収開始コマンドを送信
            val receivingRef = dependencies.sharding.entityRefFor(
              ReceivingActor.TypeKey,
              receivingId.value
            )

            receivingRef.ask[StatusReply[ReceivingEvent.InspectionStarted]] { replyTo =>
              ReceivingCommand.StartInspection(
                inspectorId = inspectorId,
                replyTo = replyTo
              )
            }
          }
          .thenPersist(InspectionStartCommandSent(sagaId, receivingId, inspectorId, Instant.now()))

      // 検収完了
      case (InspectionStarted(receivingId, purchaseOrderId, _, _), InspectionCompletedSuccessfully(_, acceptedItems)) =>
        Effect
          .persist(InspectionCompletedEvent(sagaId, receivingId, acceptedItems, Instant.now()))
          .thenRun { _ =>
            // 在庫増加コマンドを送信（第3部の在庫管理サービスと連携）
            acceptedItems.foreach { item =>
              dependencies.inventoryService ! InventoryCommand.IncreaseInventory(
                productId = item.productId,
                quantity = item.acceptedQuantity,
                reason = s"入荷検収完了: ${receivingId.value}",
                reference = Some(receivingId.value)
              )
            }
          }
          .thenPersist(InventoryUpdateCommandSent(sagaId, receivingId, Instant.now()))

      // 在庫更新成功
      case (InspectionCompleted(receivingId, purchaseOrderId, _, acceptedItems), InventoryUpdateSucceeded(_)) =>
        Effect
          .persist(InventoryUpdatedEvent(sagaId, receivingId, Instant.now()))
          .thenRun { _ =>
            // 発注ステータス更新コマンドを送信
            val purchaseOrderRef = dependencies.sharding.entityRefFor(
              PurchaseOrderActor.TypeKey,
              purchaseOrderId.value
            )

            val receivedItems = acceptedItems.map { item =>
              ReceivedItemData(
                productId = item.productId,
                receivedQuantity = item.acceptedQuantity
              )
            }

            purchaseOrderRef.ask[StatusReply[PurchaseOrderEvent.ReceiptRecorded]] { replyTo =>
              PurchaseOrderCommand.RecordReceipt(
                receivingId = receivingId,
                receivedItems = receivedItems,
                replyTo = replyTo
              )
            }
          }
          .thenPersist(PurchaseOrderUpdateCommandSent(sagaId, receivingId, purchaseOrderId, Instant.now()))

      // 発注ステータス更新成功
      case (InventoryUpdated(receivingId, purchaseOrderId, _), PurchaseOrderUpdateSucceeded(_)) =>
        Effect.persist(SagaCompleted(sagaId, receivingId, Instant.now()))

      // 検収失敗（補償フロー）
      case (InspectionStarted(receivingId, purchaseOrderId, _, _), InspectionFailedWithDiscrepancy(_, reason)) =>
        Effect
          .persist(InspectionFailedEvent(sagaId, receivingId, reason, Instant.now()))
          .thenRun { _ =>
            // 差異を記録
            val receivingRef = dependencies.sharding.entityRefFor(
              ReceivingActor.TypeKey,
              receivingId.value
            )

            receivingRef ! ReceivingCommand.RecordDiscrepancy(
              productId = ProductId("UNKNOWN"), // 実際には差異のあった商品ID
              discrepancyType = DiscrepancyReason.ShortShipment,
              description = reason,
              replyTo = ActorRef.noSender
            )
          }
          .thenPersist(DiscrepancyRecorded(sagaId, receivingId, reason, Instant.now()))

      // 在庫更新失敗（補償フロー）
      case (InspectionCompleted(receivingId, purchaseOrderId, _, _), InventoryUpdateFailed(_, reason)) =>
        Effect
          .persist(InventoryRollbackCommandSent(sagaId, receivingId, reason, Instant.now()))
          .thenRun { _ =>
            // 検収をロールバック（実際には検収ステータスを戻す）
            val receivingRef = dependencies.sharding.entityRefFor(
              ReceivingActor.TypeKey,
              receivingId.value
            )

            // ここでは簡易的に差異として記録
            receivingRef ! ReceivingCommand.RecordDiscrepancy(
              productId = ProductId("UNKNOWN"),
              discrepancyType = DiscrepancyReason.Defective,
              description = s"在庫更新失敗: $reason",
              replyTo = ActorRef.noSender
            )
          }

      case _ =>
        Effect.unhandled
    }
  }

  private val eventHandler: (ReceivingInspectionSagaState, ReceivingInspectionSagaEvent) => ReceivingInspectionSagaState = {
    (state, event) =>
      event match {
        case SagaStarted(_, receivingId, purchaseOrderId, _) =>
          NotStarted

        case InspectionStartCommandSent(_, receivingId, inspectorId, startedAt) =>
          InspectionStarted(receivingId, purchaseOrderId = PurchaseOrderId(""), inspectorId, startedAt) // 実際にはpurchaseOrderIdを保持

        case InspectionCompletedEvent(_, receivingId, acceptedItems, completedAt) =>
          InspectionCompleted(receivingId, PurchaseOrderId(""), completedAt, acceptedItems)

        case InventoryUpdatedEvent(_, receivingId, updatedAt) =>
          InventoryUpdated(receivingId, PurchaseOrderId(""), updatedAt)

        case PurchaseOrderUpdateCommandSent(_, receivingId, purchaseOrderId, updatedAt) =>
          PurchaseOrderUpdated(receivingId, purchaseOrderId, updatedAt)

        case SagaCompleted(_, receivingId, completedAt) =>
          Completed(receivingId, PurchaseOrderId(""), completedAt)

        case InspectionFailedEvent(_, receivingId, reason, failedAt) =>
          InspectionFailed(receivingId, PurchaseOrderId(""), reason, failedAt)

        case InventoryRollbackCommandSent(_, receivingId, reason, rolledBackAt) =>
          InventoryRolledBack(receivingId, PurchaseOrderId(""), reason, rolledBackAt)

        case _ =>
          state
      }
  }
}

// 在庫管理サービスのコマンド（簡易版）
sealed trait InventoryCommand

object InventoryCommand {
  final case class IncreaseInventory(
    productId: ProductId,
    quantity: Quantity,
    reason: String,
    reference: Option[String]
  ) extends InventoryCommand
}
```

---

## 6.4 支払Saga

### 6.4.1 Sagaの定義

支払Sagaは、請求書を受領してから3-way matchingを実行し、支払を完了するまでのプロセスを管理します。

```scala
package com.example.procurement.saga.payment

import com.example.shared.domain.*
import com.example.procurement.domain.payment.*
import java.time.{Instant, LocalDate}

// Sagaの状態
sealed trait PaymentSagaState

object PaymentSagaState {
  case object NotStarted extends PaymentSagaState

  final case class InvoiceReceived(
    invoiceId: SupplierInvoiceId,
    purchaseOrderId: PurchaseOrderId,
    receivedAt: Instant
  ) extends PaymentSagaState

  final case class MatchingPerformed(
    invoiceId: SupplierInvoiceId,
    matchingSucceeded: Boolean,
    performedAt: Instant
  ) extends PaymentSagaState

  final case class PaymentScheduled(
    invoiceId: SupplierInvoiceId,
    scheduledDate: LocalDate,
    scheduledAt: Instant
  ) extends PaymentSagaState

  final case class PaymentExecuted(
    invoiceId: SupplierInvoiceId,
    paymentId: SupplierPaymentId,
    executedAt: Instant
  ) extends PaymentSagaState

  final case class Completed(
    invoiceId: SupplierInvoiceId,
    completedAt: Instant
  ) extends PaymentSagaState

  // 補償状態
  final case class MatchingFailed(
    invoiceId: SupplierInvoiceId,
    reason: String,
    failedAt: Instant
  ) extends PaymentSagaState

  final case class PaymentFailed(
    invoiceId: SupplierInvoiceId,
    reason: String,
    retryCount: Int,
    failedAt: Instant
  ) extends PaymentSagaState

  final case class EscalatedToManager(
    invoiceId: SupplierInvoiceId,
    reason: String,
    escalatedAt: Instant
  ) extends PaymentSagaState
}

// Sagaのコマンド
sealed trait PaymentSagaCommand

object PaymentSagaCommand {
  final case class StartSaga(
    invoiceId: SupplierInvoiceId,
    purchaseOrderId: PurchaseOrderId,
    receivingId: Option[ReceivingId]
  ) extends PaymentSagaCommand

  final case class MatchingSucceeded(
    invoiceId: SupplierInvoiceId
  ) extends PaymentSagaCommand

  final case class MatchingFailed(
    invoiceId: SupplierInvoiceId,
    reason: String
  ) extends PaymentSagaCommand

  final case class PaymentDateReached(
    invoiceId: SupplierInvoiceId
  ) extends PaymentSagaCommand

  final case class PaymentSucceeded(
    invoiceId: SupplierInvoiceId,
    paymentId: SupplierPaymentId
  ) extends PaymentSagaCommand

  final case class PaymentFailedCommand(
    invoiceId: SupplierInvoiceId,
    reason: String
  ) extends PaymentSagaCommand

  final case class RetryPayment(
    invoiceId: SupplierInvoiceId
  ) extends PaymentSagaCommand
}

// Sagaのイベント
sealed trait PaymentSagaEvent {
  def sagaId: SagaId
  def occurredAt: Instant
}

object PaymentSagaEvent {
  final case class SagaStarted(
    sagaId: SagaId,
    invoiceId: SupplierInvoiceId,
    purchaseOrderId: PurchaseOrderId,
    occurredAt: Instant
  ) extends PaymentSagaEvent

  final case class ThreeWayMatchingCommandSent(
    sagaId: SagaId,
    invoiceId: SupplierInvoiceId,
    occurredAt: Instant
  ) extends PaymentSagaEvent

  final case class MatchingSucceededEvent(
    sagaId: SagaId,
    invoiceId: SupplierInvoiceId,
    occurredAt: Instant
  ) extends PaymentSagaEvent

  final case class PaymentSchedulingCommandSent(
    sagaId: SagaId,
    invoiceId: SupplierInvoiceId,
    scheduledDate: LocalDate,
    occurredAt: Instant
  ) extends PaymentSagaEvent

  final case class PaymentExecutionCommandSent(
    sagaId: SagaId,
    invoiceId: SupplierInvoiceId,
    occurredAt: Instant
  ) extends PaymentSagaEvent

  final case class PaymentCompletedEvent(
    sagaId: SagaId,
    invoiceId: SupplierInvoiceId,
    paymentId: SupplierPaymentId,
    occurredAt: Instant
  ) extends PaymentSagaEvent

  final case class SagaCompleted(
    sagaId: SagaId,
    invoiceId: SupplierInvoiceId,
    occurredAt: Instant
  ) extends PaymentSagaEvent

  // 補償イベント
  final case class MatchingFailedEvent(
    sagaId: SagaId,
    invoiceId: SupplierInvoiceId,
    reason: String,
    occurredAt: Instant
  ) extends PaymentSagaEvent

  final case class AccountingTeamNotified(
    sagaId: SagaId,
    invoiceId: SupplierInvoiceId,
    reason: String,
    occurredAt: Instant
  ) extends PaymentSagaEvent

  final case class PaymentFailedEvent(
    sagaId: SagaId,
    invoiceId: SupplierInvoiceId,
    reason: String,
    retryCount: Int,
    occurredAt: Instant
  ) extends PaymentSagaEvent

  final case class PaymentRetried(
    sagaId: SagaId,
    invoiceId: SupplierInvoiceId,
    retryCount: Int,
    occurredAt: Instant
  ) extends PaymentSagaEvent

  final case class EscalatedToManagerEvent(
    sagaId: SagaId,
    invoiceId: SupplierInvoiceId,
    reason: String,
    occurredAt: Instant
  ) extends PaymentSagaEvent
}
```

### 6.4.2 支払Sagaアクターの実装

```scala
package com.example.procurement.saga.payment

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import com.example.shared.domain.*
import com.example.procurement.domain.payment.*
import com.example.procurement.domain.purchaseorder.*
import com.example.procurement.domain.receiving.*
import java.time.{Instant, LocalDate}

object PaymentSagaActor {

  import PaymentSagaState.*
  import PaymentSagaCommand.*
  import PaymentSagaEvent.*

  private val MAX_RETRY_COUNT = 3

  final case class Dependencies(
    sharding: ClusterSharding,
    notificationService: ActorRef[NotificationCommand]
  )

  def apply(
    sagaId: SagaId,
    dependencies: Dependencies
  ): Behavior[PaymentSagaCommand] = {
    EventSourcedBehavior[
      PaymentSagaCommand,
      PaymentSagaEvent,
      PaymentSagaState
    ](
      persistenceId = PersistenceId.ofUniqueId(s"PaymentSaga-${sagaId.value}"),
      emptyState = NotStarted,
      commandHandler = commandHandler(sagaId, dependencies),
      eventHandler = eventHandler
    ).withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2))
  }

  private def commandHandler(
    sagaId: SagaId,
    dependencies: Dependencies
  )(state: PaymentSagaState, command: PaymentSagaCommand): Effect[PaymentSagaEvent, PaymentSagaState] = {
    (state, command) match {
      // Sagaの開始
      case (NotStarted, StartSaga(invoiceId, purchaseOrderId, receivingId)) =>
        Effect
          .persist(SagaStarted(sagaId, invoiceId, purchaseOrderId, Instant.now()))
          .thenRun { _ =>
            // 3-way matchingコマンドを送信
            val invoiceRef = dependencies.sharding.entityRefFor(
              SupplierPaymentActor.TypeKey,
              invoiceId.value
            )

            // 発注と入荷の情報を取得して3-way matchingを実行
            // （実際にはPurchaseOrderとReceivingの情報を取得する必要がある）
            invoiceRef.ask[StatusReply[MatchingEvent]] { replyTo =>
              SupplierPaymentCommand.PerformThreeWayMatching(
                purchaseOrderAmount = Money(1000000), // 実際には発注情報から取得
                receivingAmount = Money(1000000),     // 実際には入荷情報から取得
                replyTo = replyTo
              )
            }
          }
          .thenPersist(ThreeWayMatchingCommandSent(sagaId, invoiceId, Instant.now()))

      // マッチング成功
      case (InvoiceReceived(invoiceId, purchaseOrderId, _), MatchingSucceeded(_)) =>
        Effect
          .persist(MatchingSucceededEvent(sagaId, invoiceId, Instant.now()))
          .thenRun { _ =>
            // 支払予定日を計算（例: 請求日から30日後）
            val scheduledDate = LocalDate.now().plusDays(30)

            // 支払予定設定コマンドを送信
            val invoiceRef = dependencies.sharding.entityRefFor(
              SupplierPaymentActor.TypeKey,
              invoiceId.value
            )

            invoiceRef.ask[StatusReply[SupplierPaymentEvent.PaymentScheduled]] { replyTo =>
              SupplierPaymentCommand.SchedulePayment(
                scheduledDate = scheduledDate,
                replyTo = replyTo
              )
            }
          }
          .thenPersist(PaymentSchedulingCommandSent(sagaId, invoiceId, LocalDate.now().plusDays(30), Instant.now()))

      // 支払予定日到達
      case (MatchingPerformed(invoiceId, true, _), PaymentDateReached(_)) =>
        Effect
          .persist(PaymentExecutionCommandSent(sagaId, invoiceId, Instant.now()))
          .thenRun { _ =>
            // 支払実行コマンドを送信
            val invoiceRef = dependencies.sharding.entityRefFor(
              SupplierPaymentActor.TypeKey,
              invoiceId.value
            )

            invoiceRef.ask[StatusReply[SupplierPaymentEvent.PaymentCompleted]] { replyTo =>
              SupplierPaymentCommand.CompletePayment(
                paymentDate = LocalDate.now(),
                paymentMethod = PaymentMethod.BankTransfer,
                paymentAmount = Money(1000000), // 実際には請求金額
                replyTo = replyTo
              )
            }
          }

      // 支払完了
      case (PaymentScheduled(invoiceId, _, _), PaymentSucceeded(_, paymentId)) =>
        Effect
          .persist(PaymentCompletedEvent(sagaId, invoiceId, paymentId, Instant.now()))
          .thenPersist(SagaCompleted(sagaId, invoiceId, Instant.now()))

      // マッチング失敗（補償フロー）
      case (InvoiceReceived(invoiceId, _, _), MatchingFailed(_, reason)) =>
        Effect
          .persist(MatchingFailedEvent(sagaId, invoiceId, reason, Instant.now()))
          .thenRun { _ =>
            // 経理チームに通知
            dependencies.notificationService ! NotificationCommand.NotifyAccountingTeam(
              subject = "3-way matching失敗",
              message = s"請求書 ${invoiceId.value} のマッチングが失敗しました: $reason",
              invoiceId = invoiceId
            )
          }
          .thenPersist(AccountingTeamNotified(sagaId, invoiceId, reason, Instant.now()))

      // 支払失敗（リトライ）
      case (PaymentScheduled(invoiceId, _, _), PaymentFailedCommand(_, reason)) =>
        Effect
          .persist(PaymentFailedEvent(sagaId, invoiceId, reason, retryCount = 1, Instant.now()))

      case (PaymentFailed(invoiceId, reason, retryCount, _), RetryPayment(_)) if retryCount < MAX_RETRY_COUNT =>
        Effect
          .persist(PaymentRetried(sagaId, invoiceId, retryCount + 1, Instant.now()))
          .thenRun { _ =>
            // 支払を再試行
            val invoiceRef = dependencies.sharding.entityRefFor(
              SupplierPaymentActor.TypeKey,
              invoiceId.value
            )

            invoiceRef.ask[StatusReply[SupplierPaymentEvent.PaymentCompleted]] { replyTo =>
              SupplierPaymentCommand.CompletePayment(
                paymentDate = LocalDate.now(),
                paymentMethod = PaymentMethod.BankTransfer,
                paymentAmount = Money(1000000),
                replyTo = replyTo
              )
            }
          }

      // リトライ上限に達した場合（エスカレーション）
      case (PaymentFailed(invoiceId, reason, retryCount, _), RetryPayment(_)) if retryCount >= MAX_RETRY_COUNT =>
        Effect
          .persist(EscalatedToManagerEvent(sagaId, invoiceId, reason, Instant.now()))
          .thenRun { _ =>
            // マネージャーに通知
            dependencies.notificationService ! NotificationCommand.NotifyManager(
              subject = "支払失敗（エスカレーション）",
              message = s"請求書 ${invoiceId.value} の支払が${MAX_RETRY_COUNT}回失敗しました: $reason",
              invoiceId = invoiceId
            )
          }

      case _ =>
        Effect.unhandled
    }
  }

  private val eventHandler: (PaymentSagaState, PaymentSagaEvent) => PaymentSagaState = {
    (state, event) =>
      event match {
        case SagaStarted(_, invoiceId, purchaseOrderId, receivedAt) =>
          InvoiceReceived(invoiceId, purchaseOrderId, receivedAt)

        case MatchingSucceededEvent(_, invoiceId, performedAt) =>
          MatchingPerformed(invoiceId, matchingSucceeded = true, performedAt)

        case PaymentSchedulingCommandSent(_, invoiceId, scheduledDate, scheduledAt) =>
          PaymentScheduled(invoiceId, scheduledDate, scheduledAt)

        case PaymentCompletedEvent(_, invoiceId, paymentId, executedAt) =>
          PaymentExecuted(invoiceId, paymentId, executedAt)

        case SagaCompleted(_, invoiceId, completedAt) =>
          Completed(invoiceId, completedAt)

        case MatchingFailedEvent(_, invoiceId, reason, failedAt) =>
          MatchingFailed(invoiceId, reason, failedAt)

        case PaymentFailedEvent(_, invoiceId, reason, retryCount, failedAt) =>
          PaymentFailed(invoiceId, reason, retryCount, failedAt)

        case PaymentRetried(_, invoiceId, retryCount, _) =>
          state match {
            case pf: PaymentFailed => pf.copy(retryCount = retryCount)
            case _ => state
          }

        case EscalatedToManagerEvent(_, invoiceId, reason, escalatedAt) =>
          EscalatedToManager(invoiceId, reason, escalatedAt)

        case _ =>
          state
      }
  }
}

// 通知サービスのコマンド（簡易版）
sealed trait NotificationCommand

object NotificationCommand {
  final case class NotifyAccountingTeam(
    subject: String,
    message: String,
    invoiceId: SupplierInvoiceId
  ) extends NotificationCommand

  final case class NotifyManager(
    subject: String,
    message: String,
    invoiceId: SupplierInvoiceId
  ) extends NotificationCommand
}
```

---

## 6.5 Sagaの監視と管理

### 6.5.1 Saga状態の永続化

各Sagaの状態は、Pekko Persistenceによって永続化されます。これにより、システムが再起動してもSagaの実行を継続できます。

```scala
package com.example.procurement.saga.monitoring

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.persistence.query.PersistenceQuery
import org.apache.pekko.persistence.query.scaladsl.EventsByTagQuery
import org.apache.pekko.stream.scaladsl.{RestartSource, Sink}
import scala.concurrent.duration.*

// Saga監視サービス
object SagaMonitor {

  final case class SagaStatistics(
    activeSagas: Int,
    completedSagas: Int,
    failedSagas: Int,
    averageCompletionTime: Long
  )

  def monitorSagas()(implicit system: ActorSystem[_]): Unit = {
    implicit val ec = system.executionContext

    val readJournal = PersistenceQuery(system)
      .readJournalFor[EventsByTagQuery]("jdbc-read-journal")

    // "saga"タグでイベントをストリーム
    val source = RestartSource.onFailuresWithBackoff(
      RestartSettings(
        minBackoff = 3.seconds,
        maxBackoff = 30.seconds,
        randomFactor = 0.2
      )
    ) { () =>
      readJournal.eventsByTag("saga", offset = readJournal.currentOffset)
    }

    source.runWith(Sink.foreach { envelope =>
      envelope.event match {
        case event: com.example.procurement.saga.approval.PurchaseOrderApprovalSagaEvent.SagaStarted =>
          println(s"[SAGA MONITOR] 発注承認Saga開始: ${event.sagaId.value}")

        case event: com.example.procurement.saga.approval.PurchaseOrderApprovalSagaEvent.SagaCompleted =>
          println(s"[SAGA MONITOR] 発注承認Saga完了: ${event.sagaId.value}")

        case event: com.example.procurement.saga.approval.PurchaseOrderApprovalSagaEvent.SagaFailed =>
          println(s"[SAGA MONITOR] 発注承認Saga失敗: ${event.sagaId.value} - ${event.reason}")

        case event: com.example.procurement.saga.receiving.ReceivingInspectionSagaEvent.SagaStarted =>
          println(s"[SAGA MONITOR] 入荷検収Saga開始: ${event.sagaId.value}")

        case event: com.example.procurement.saga.receiving.ReceivingInspectionSagaEvent.SagaCompleted =>
          println(s"[SAGA MONITOR] 入荷検収Saga完了: ${event.sagaId.value}")

        case event: com.example.procurement.saga.payment.PaymentSagaEvent.SagaStarted =>
          println(s"[SAGA MONITOR] 支払Saga開始: ${event.sagaId.value}")

        case event: com.example.procurement.saga.payment.PaymentSagaEvent.SagaCompleted =>
          println(s"[SAGA MONITOR] 支払Saga完了: ${event.sagaId.value}")

        case _ => // 他のイベントは無視
      }
    })
  }
}
```

### 6.5.2 Sagaのクエリモデル

Sagaの状態をクエリできるように、Read Modelを構築します。

```scala
package com.example.procurement.saga.query

import com.example.shared.domain.*
import java.time.Instant

// Saga実行履歴のRead Model
final case class SagaExecutionRecord(
  sagaId: SagaId,
  sagaType: SagaType,
  entityId: String,
  status: SagaExecutionStatus,
  startedAt: Instant,
  completedAt: Option[Instant],
  failedAt: Option[Instant],
  failureReason: Option[String],
  currentStep: String,
  retryCount: Int
)

sealed trait SagaType

object SagaType {
  case object PurchaseOrderApproval extends SagaType
  case object ReceivingInspection extends SagaType
  case object Payment extends SagaType
}

sealed trait SagaExecutionStatus

object SagaExecutionStatus {
  case object Running extends SagaExecutionStatus
  case object Completed extends SagaExecutionStatus
  case object Failed extends SagaExecutionStatus
  case object Compensating extends SagaExecutionStatus
  case object Cancelled extends SagaExecutionStatus
}

// Sagaクエリサービス
trait SagaQueryService {
  // 実行中のSaga一覧を取得
  def getActiveSagas(): List[SagaExecutionRecord]

  // 特定のSagaの詳細を取得
  def getSagaById(sagaId: SagaId): Option[SagaExecutionRecord]

  // 失敗したSaga一覧を取得
  def getFailedSagas(): List[SagaExecutionRecord]

  // 特定エンティティに関連するSaga一覧を取得
  def getSagasByEntityId(entityId: String): List[SagaExecutionRecord]
}
```

---

## 6.6 Sagaのテスト

### 6.6.1 発注承認Sagaのテスト

```scala
package com.example.procurement.saga.approval

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, ScalaTestWithActorTestKit}
import org.apache.pekko.cluster.sharding.typed.scaladsl.ClusterSharding
import org.scalatest.wordspec.AnyWordSpecLike
import com.example.shared.domain.*
import com.example.procurement.domain.purchaseorder.*
import java.time.Instant

class PurchaseOrderApprovalSagaActorSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike {

  "PurchaseOrderApprovalSagaActor" should {

    "complete the approval saga successfully" in {
      val sagaId = SagaId("SAGA-001")
      val purchaseOrderId = PurchaseOrderId("PO-001")

      // モックの依存関係を作成
      val shardingProbe = createTestProbe[ClusterSharding]()
      val notificationProbe = createTestProbe[SupplierNotificationCommand]()

      val dependencies = PurchaseOrderApprovalSagaActor.Dependencies(
        sharding = shardingProbe.ref.unsafeUpcast[ClusterSharding],
        supplierNotificationService = notificationProbe.ref
      )

      val saga = spawn(PurchaseOrderApprovalSagaActor(sagaId, dependencies))

      // Sagaを開始
      saga ! PurchaseOrderApprovalSagaCommand.StartSaga(purchaseOrderId)

      // 承認が得られたことを通知
      saga ! PurchaseOrderApprovalSagaCommand.ApprovalGranted(
        purchaseOrderId = purchaseOrderId,
        approverId = UserId("USER-001")
      )

      // 発注書発行が成功したことを通知
      saga ! PurchaseOrderApprovalSagaCommand.IssuingSucceeded(purchaseOrderId)

      // サプライヤー通知が成功したことを通知
      saga ! PurchaseOrderApprovalSagaCommand.SupplierNotificationSucceeded(purchaseOrderId)

      // Sagaが完了することを確認
      // （実際にはイベントの永続化を確認する必要がある）
    }

    "handle approval rejection with compensation" in {
      val sagaId = SagaId("SAGA-002")
      val purchaseOrderId = PurchaseOrderId("PO-002")

      val shardingProbe = createTestProbe[ClusterSharding]()
      val notificationProbe = createTestProbe[SupplierNotificationCommand]()

      val dependencies = PurchaseOrderApprovalSagaActor.Dependencies(
        sharding = shardingProbe.ref.unsafeUpcast[ClusterSharding],
        supplierNotificationService = notificationProbe.ref
      )

      val saga = spawn(PurchaseOrderApprovalSagaActor(sagaId, dependencies))

      // Sagaを開始
      saga ! PurchaseOrderApprovalSagaCommand.StartSaga(purchaseOrderId)

      // 承認が却下されたことを通知
      saga ! PurchaseOrderApprovalSagaCommand.ApprovalRejected(
        purchaseOrderId = purchaseOrderId,
        reason = "予算超過"
      )

      // 補償トランザクション（発注キャンセル）が実行されることを確認
      // （実際には発注キャンセルコマンドが送信されることを確認）
    }
  }
}
```

---

## まとめ

本章では、Sagaパターンを使って発注管理システムの複雑なビジネスプロセスを実装しました。

### 実装した内容

1. **発注承認Saga**
   - 正常フロー: 発注作成 → 承認申請 → 承認 → 発注書発行 → サプライヤー通知
   - 補償フロー: 承認却下時の発注キャンセル、発行失敗時のロールバック

2. **入荷検収Saga**
   - 正常フロー: 入荷記録 → 検収開始 → 検収完了 → 在庫更新 → 発注ステータス更新
   - 補償フロー: 検収失敗時の差異記録、在庫更新失敗時のロールバック

3. **支払Saga**
   - 正常フロー: 請求書受領 → 3-way matching → 支払承認 → 支払予定設定 → 支払実行
   - 補償フロー: マッチング失敗時の通知、支払失敗時のリトライとエスカレーション

4. **Saga監視と管理**
   - Pekko Persistenceによる状態の永続化
   - イベントストリーミングによる監視
   - Read Modelによるクエリ機能

### Sagaパターンの利点

- **分散トランザクションの管理**: 複数の集約をまたがる処理を安全に実行
- **補償トランザクション**: エラー発生時に既存の処理を元に戻す
- **可視性**: ビジネスプロセス全体を一箇所で管理
- **耐障害性**: システム再起動後もSagaの実行を継続

### 次章の予告

次章では、在庫管理サービス（第3部）との連携を実装し、入荷による在庫増加、発注残の管理、自動発注機能を構築します。イベント駆動アーキテクチャによるシステム間連携の実装を学びます。
