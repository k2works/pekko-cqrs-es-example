# 第7部第7章：マスターデータの変更承認ワークフロー

## 本章の目的

マスターデータの重要な変更には、承認フローが必要です。本章では、Sagaパターンを使った承認ワークフローの実装を詳しく解説します。商品価格の変更や勘定科目の追加など、業務に大きな影響を与える変更を適切に管理する仕組みを構築します。

## 7.1 承認が必要な変更

### 7.1.1 重要変更の定義

D社では、以下の変更に対して承認フローを適用します。

#### 商品マスターの重要変更

```scala
package com.example.masterdata.domain.approval

/**
 * 承認が必要な変更種別
 */
sealed trait ApprovalRequiredChange

object ProductApprovalRules {

  /**
   * 商品価格変更が承認を必要とするかを判定
   */
  def requiresApprovalForPriceChange(
    oldPrice: Money,
    newPrice: Money,
    threshold: Money = Money(BigDecimal(10000))
  ): Boolean = {
    val change = (newPrice.amount - oldPrice.amount).abs
    change >= threshold.amount
  }

  /**
   * 商品情報変更が承認を必要とするかを判定
   */
  def requiresApprovalForInfoChange(
    oldCategoryCode: CategoryCode,
    newCategoryCode: CategoryCode
  ): Boolean = {
    // カテゴリー変更は常に承認が必要
    oldCategoryCode != newCategoryCode
  }

  /**
   * 商品ステータス変更が承認を必要とするかを判定
   */
  def requiresApprovalForStatusChange(
    newStatus: ProductStatus
  ): Boolean = {
    // 廃止への変更は承認が必要
    newStatus == ProductStatus.Obsolete
  }
}
```

#### 勘定科目マスターの重要変更

```scala
object AccountSubjectApprovalRules {

  /**
   * 勘定科目の新規作成は常に承認が必要
   */
  def requiresApprovalForCreation(): Boolean = true

  /**
   * 勘定科目名変更が承認を必要とするかを判定
   */
  def requiresApprovalForNameChange(
    oldName: AccountName,
    newName: AccountName
  ): Boolean = {
    // 名称変更は常に承認が必要
    true
  }

  /**
   * 勘定科目の停止・廃止は常に承認が必要
   */
  def requiresApprovalForStatusChange(
    newStatus: AccountSubjectStatus
  ): Boolean = {
    newStatus == AccountSubjectStatus.Suspended ||
    newStatus == AccountSubjectStatus.Obsolete
  }
}
```

### 7.1.2 変更申請データモデル

承認ワークフローで使用する変更申請のデータモデルを定義します。

```scala
package com.example.masterdata.domain.approval

import java.time.Instant
import java.util.UUID

/**
 * 変更申請ID
 */
final case class ChangeRequestId(value: String) extends AnyVal

object ChangeRequestId {
  def generate(): ChangeRequestId = ChangeRequestId(UUID.randomUUID().toString)
}

/**
 * 変更申請ステータス
 */
sealed trait ChangeRequestStatus
object ChangeRequestStatus {
  case object Pending extends ChangeRequestStatus     // 承認待ち
  case object Approved extends ChangeRequestStatus    // 承認済み
  case object Rejected extends ChangeRequestStatus    // 却下
  case object Applied extends ChangeRequestStatus     // 適用済み
  case object Cancelled extends ChangeRequestStatus   // 取り消し
}

/**
 * 申請者情報
 */
final case class Requester(
  userId: String,
  userName: String,
  email: String
)

/**
 * 承認者情報
 */
final case class Approver(
  userId: String,
  userName: String,
  email: String
)

/**
 * 変更申請
 */
sealed trait ChangeRequest {
  def id: ChangeRequestId
  def requester: Requester
  def status: ChangeRequestStatus
  def requestedAt: Instant
  def approver: Option[Approver]
  def approvedAt: Option[Instant]
  def rejectionReason: Option[String]
}

/**
 * 商品価格変更申請
 */
final case class PriceChangeRequest(
  id: ChangeRequestId,
  productId: ProductId,
  productCode: ProductCode,
  productName: ProductName,
  oldPrice: Money,
  newPrice: Money,
  effectiveFrom: LocalDate,
  reason: String,
  requester: Requester,
  status: ChangeRequestStatus,
  requestedAt: Instant,
  approver: Option[Approver],
  approvedAt: Option[Instant],
  rejectionReason: Option[String]
) extends ChangeRequest

/**
 * 勘定科目作成申請
 */
final case class AccountSubjectCreationRequest(
  id: ChangeRequestId,
  accountCode: AccountCode,
  accountName: AccountName,
  accountType: AccountType,
  parentAccountSubjectId: Option[AccountSubjectId],
  reason: String,
  requester: Requester,
  status: ChangeRequestStatus,
  requestedAt: Instant,
  approver: Option[Approver],
  approvedAt: Option[Instant],
  rejectionReason: Option[String]
) extends ChangeRequest
```

### 7.1.3 変更申請テーブル

PostgreSQLに変更申請データを保存するテーブルを定義します。

```sql
-- 変更申請テーブル
CREATE TABLE master_data_management.change_requests (
  request_id VARCHAR(50) PRIMARY KEY,
  request_type VARCHAR(50) NOT NULL,  -- 'PriceChange', 'AccountSubjectCreation'など
  aggregate_type VARCHAR(50) NOT NULL, -- 'Product', 'AccountSubject'など
  aggregate_id VARCHAR(50) NOT NULL,
  request_data JSONB NOT NULL,         -- 変更内容をJSONで保存
  reason TEXT NOT NULL,

  -- 申請者情報
  requester_user_id VARCHAR(50) NOT NULL,
  requester_user_name VARCHAR(200) NOT NULL,
  requester_email VARCHAR(200) NOT NULL,

  -- ステータス
  status VARCHAR(20) NOT NULL,
  requested_at TIMESTAMP NOT NULL,

  -- 承認者情報
  approver_user_id VARCHAR(50),
  approver_user_name VARCHAR(200),
  approver_email VARCHAR(200),
  approved_at TIMESTAMP,

  -- 却下理由
  rejection_reason TEXT,

  -- 適用情報
  applied_at TIMESTAMP,

  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_change_requests_status ON master_data_management.change_requests(status);
CREATE INDEX idx_change_requests_aggregate ON master_data_management.change_requests(aggregate_type, aggregate_id);
CREATE INDEX idx_change_requests_requester ON master_data_management.change_requests(requester_user_id);
CREATE INDEX idx_change_requests_approver ON master_data_management.change_requests(approver_user_id);
CREATE INDEX idx_change_requests_requested_at ON master_data_management.change_requests(requested_at DESC);
```

## 7.2 承認フロー

### 7.2.1 承認フローの概要

```
┌─────────┐       ┌──────────────┐       ┌─────────┐
│ 申請者  │       │マスターデータ│       │ 承認者  │
└────┬────┘       └──────┬───────┘       └────┬────┘
     │                   │                    │
     │ 1. 変更申請       │                    │
     │──────────────────>│                    │
     │                   │                    │
     │                   │ 2. 申請データ保存  │
     │                   │─────┐              │
     │                   │     │              │
     │                   │<────┘              │
     │                   │                    │
     │                   │ 3. 承認依頼通知    │
     │                   │───────────────────>│
     │                   │                    │
     │                   │        4a. 承認    │
     │                   │<───────────────────│
     │                   │                    │
     │                   │ 5a. 変更適用       │
     │                   │─────┐              │
     │                   │     │              │
     │                   │<────┘              │
     │                   │                    │
     │ 6a. 承認通知      │                    │
     │<──────────────────│                    │
     │                   │                    │
     │                   │    4b. 却下（理由）│
     │                   │<───────────────────│
     │                   │                    │
     │ 6b. 却下通知      │                    │
     │<──────────────────│                    │
     │                   │                    │
```

### 7.2.2 承認フローActor

承認フロー全体を管理するActorを実装します。

```scala
package com.example.masterdata.application.approval

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.pattern.StatusReply
import com.example.masterdata.domain.approval._
import scala.concurrent.duration._

/**
 * 承認フロー管理Actor
 */
object ApprovalFlowActor {

  // コマンド
  sealed trait Command extends CborSerializable

  final case class SubmitChangeRequest(
    request: ChangeRequest,
    replyTo: ActorRef[StatusReply[ChangeRequestId]]
  ) extends Command

  final case class ApproveChangeRequest(
    requestId: ChangeRequestId,
    approver: Approver,
    replyTo: ActorRef[StatusReply[ChangeRequest]]
  ) extends Command

  final case class RejectChangeRequest(
    requestId: ChangeRequestId,
    approver: Approver,
    reason: String,
    replyTo: ActorRef[StatusReply[ChangeRequest]]
  ) extends Command

  final case class CancelChangeRequest(
    requestId: ChangeRequestId,
    requester: Requester,
    replyTo: ActorRef[StatusReply[ChangeRequest]]
  ) extends Command

  private case class ChangeRequestSaved(requestId: ChangeRequestId) extends Command
  private case class NotificationSent(requestId: ChangeRequestId) extends Command
  private case class ChangeApplied(requestId: ChangeRequestId) extends Command

  def apply(
    repository: ChangeRequestRepository,
    notificationService: NotificationService,
    productActor: ActorRef[ProductCommand],
    accountSubjectActor: ActorRef[AccountSubjectCommand]
  ): Behavior[Command] = {
    Behaviors.setup { context =>
      active(repository, notificationService, productActor, accountSubjectActor, context)
    }
  }

  private def active(
    repository: ChangeRequestRepository,
    notificationService: NotificationService,
    productActor: ActorRef[ProductCommand],
    accountSubjectActor: ActorRef[AccountSubjectCommand],
    context: ActorContext[Command]
  ): Behavior[Command] = {
    Behaviors.receiveMessage {

      case SubmitChangeRequest(request, replyTo) =>
        context.log.info(s"変更申請受付: ${request.id.value}")

        // 1. 申請データをDBに保存
        context.pipeToSelf(repository.save(request)) {
          case Success(_) => ChangeRequestSaved(request.id)
          case Failure(ex) =>
            context.log.error(s"申請保存失敗: ${request.id.value}", ex)
            ChangeRequestSaved(request.id) // エラーハンドリング
        }

        // 2. 承認者に通知
        context.pipeToSelf(notificationService.notifyApprover(request)) {
          case Success(_) => NotificationSent(request.id)
          case Failure(ex) =>
            context.log.error(s"承認者通知失敗: ${request.id.value}", ex)
            NotificationSent(request.id)
        }

        replyTo ! StatusReply.Success(request.id)
        Behaviors.same

      case ApproveChangeRequest(requestId, approver, replyTo) =>
        context.log.info(s"変更申請承認: ${requestId.value}")

        // 1. 申請ステータスを「承認済み」に更新
        context.pipeToSelf(repository.approve(requestId, approver)) {
          case Success(request) =>
            // 2. 変更を適用
            applyChange(request, productActor, accountSubjectActor, context)

            // 3. 申請者に承認通知
            notificationService.notifyRequester(request, approved = true)

            replyTo ! StatusReply.Success(request)
            ChangeApplied(requestId)

          case Failure(ex) =>
            context.log.error(s"承認処理失敗: ${requestId.value}", ex)
            replyTo ! StatusReply.Error(s"承認処理に失敗しました: ${ex.getMessage}")
            ChangeApplied(requestId)
        }
        Behaviors.same

      case RejectChangeRequest(requestId, approver, reason, replyTo) =>
        context.log.info(s"変更申請却下: ${requestId.value}")

        // 1. 申請ステータスを「却下」に更新
        context.pipeToSelf(repository.reject(requestId, approver, reason)) {
          case Success(request) =>
            // 2. 申請者に却下通知
            notificationService.notifyRequester(request, approved = false)

            replyTo ! StatusReply.Success(request)
            ChangeApplied(requestId) // 処理完了

          case Failure(ex) =>
            context.log.error(s"却下処理失敗: ${requestId.value}", ex)
            replyTo ! StatusReply.Error(s"却下処理に失敗しました: ${ex.getMessage}")
            ChangeApplied(requestId)
        }
        Behaviors.same

      case CancelChangeRequest(requestId, requester, replyTo) =>
        context.log.info(s"変更申請取り消し: ${requestId.value}")

        context.pipeToSelf(repository.cancel(requestId, requester)) {
          case Success(request) =>
            replyTo ! StatusReply.Success(request)
            ChangeApplied(requestId)
          case Failure(ex) =>
            context.log.error(s"取り消し処理失敗: ${requestId.value}", ex)
            replyTo ! StatusReply.Error(s"取り消し処理に失敗しました: ${ex.getMessage}")
            ChangeApplied(requestId)
        }
        Behaviors.same

      case ChangeRequestSaved(requestId) =>
        context.log.debug(s"変更申請保存完了: ${requestId.value}")
        Behaviors.same

      case NotificationSent(requestId) =>
        context.log.debug(s"通知送信完了: ${requestId.value}")
        Behaviors.same

      case ChangeApplied(requestId) =>
        context.log.debug(s"変更適用完了: ${requestId.value}")
        Behaviors.same
    }
  }

  /**
   * 承認された変更を実際に適用
   */
  private def applyChange(
    request: ChangeRequest,
    productActor: ActorRef[ProductCommand],
    accountSubjectActor: ActorRef[AccountSubjectCommand],
    context: ActorContext[Command]
  ): Unit = {
    request match {
      case priceChange: PriceChangeRequest =>
        // 商品価格変更を適用
        val probe = context.system.ignoreRef[StatusReply[ProductEvent]]
        productActor ! ChangeProductPrice(
          productId = priceChange.productId,
          newPrice = priceChange.newPrice,
          effectiveFrom = priceChange.effectiveFrom,
          replyTo = probe
        )

      case accountCreation: AccountSubjectCreationRequest =>
        // 勘定科目作成を適用
        val probe = context.system.ignoreRef[StatusReply[AccountSubjectEvent]]
        accountSubjectActor ! CreateAccountSubject(
          accountCode = accountCreation.accountCode,
          accountName = accountCreation.accountName,
          accountType = accountCreation.accountType,
          parentAccountSubjectId = accountCreation.parentAccountSubjectId,
          replyTo = probe
        )

      case _ =>
        context.log.warn(s"未対応の変更申請タイプ: ${request.getClass.getSimpleName}")
    }
  }
}
```

## 7.3 承認Saga

### 7.3.1 Sagaパターンの概要

Sagaパターンは、長期間実行されるビジネストランザクションを管理するパターンです。承認ワークフローは以下のステップで構成されます。

```scala
// 価格変更承認Saga
PriceChangeApprovalSaga:
  1. RequestPriceChange（価格変更申請）
  2. NotifyApprover（承認者に通知）
  3. WaitForApproval（承認待ち）
  4a. ApproveAndApplyChange（承認して変更適用）
  4b. RejectChange（却下）
```

### 7.3.2 価格変更承認Saga実装

```scala
package com.example.masterdata.application.saga

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import com.example.masterdata.domain.approval._
import java.time.Instant
import scala.concurrent.duration._

/**
 * 価格変更承認Saga
 */
object PriceChangeApprovalSaga {

  // コマンド
  sealed trait Command extends CborSerializable

  final case class StartApprovalProcess(
    priceChangeRequest: PriceChangeRequest
  ) extends Command

  final case class ApproverNotified(
    notifiedAt: Instant
  ) extends Command

  final case class ApprovalReceived(
    approver: Approver,
    approvedAt: Instant
  ) extends Command

  final case class RejectionReceived(
    approver: Approver,
    reason: String,
    rejectedAt: Instant
  ) extends Command

  final case class ChangeApplied(
    appliedAt: Instant
  ) extends Command

  private case object ApprovalTimeout extends Command

  // イベント
  sealed trait Event extends CborSerializable

  final case class ApprovalProcessStarted(
    requestId: ChangeRequestId,
    priceChangeRequest: PriceChangeRequest,
    startedAt: Instant
  ) extends Event

  final case class ApproverWasNotified(
    requestId: ChangeRequestId,
    notifiedAt: Instant
  ) extends Event

  final case class ApprovalWasReceived(
    requestId: ChangeRequestId,
    approver: Approver,
    approvedAt: Instant
  ) extends Event

  final case class RejectionWasReceived(
    requestId: ChangeRequestId,
    approver: Approver,
    reason: String,
    rejectedAt: Instant
  ) extends Event

  final case class ChangeWasApplied(
    requestId: ChangeRequestId,
    appliedAt: Instant
  ) extends Event

  final case class ApprovalWasTimedOut(
    requestId: ChangeRequestId,
    timedOutAt: Instant
  ) extends Event

  // 状態
  sealed trait State extends CborSerializable
  case object EmptyState extends State

  final case class PendingApproval(
    requestId: ChangeRequestId,
    priceChangeRequest: PriceChangeRequest,
    startedAt: Instant,
    notifiedAt: Option[Instant]
  ) extends State

  final case class Approved(
    requestId: ChangeRequestId,
    priceChangeRequest: PriceChangeRequest,
    approver: Approver,
    approvedAt: Instant
  ) extends State

  final case class Rejected(
    requestId: ChangeRequestId,
    priceChangeRequest: PriceChangeRequest,
    approver: Approver,
    reason: String,
    rejectedAt: Instant
  ) extends State

  final case class Applied(
    requestId: ChangeRequestId,
    appliedAt: Instant
  ) extends State

  final case class TimedOut(
    requestId: ChangeRequestId,
    timedOutAt: Instant
  ) extends State

  def apply(
    requestId: ChangeRequestId,
    notificationService: NotificationService,
    productActor: ActorRef[ProductCommand]
  ): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        EventSourcedBehavior[Command, Event, State](
          persistenceId = PersistenceId.ofUniqueId(s"PriceChangeApprovalSaga-${requestId.value}"),
          emptyState = EmptyState,
          commandHandler = commandHandler(notificationService, productActor, timers, context),
          eventHandler = eventHandler
        )
      }
    }
  }

  private def commandHandler(
    notificationService: NotificationService,
    productActor: ActorRef[ProductCommand],
    timers: TimerScheduler[Command],
    context: ActorContext[Command]
  ): (State, Command) => Effect[Event, State] = {

    case (EmptyState, StartApprovalProcess(request)) =>
      context.log.info(s"価格変更承認プロセス開始: ${request.id.value}")

      // 承認タイムアウトタイマー設定（72時間）
      timers.startSingleTimer(ApprovalTimeout, 72.hours)

      val event = ApprovalProcessStarted(
        requestId = request.id,
        priceChangeRequest = request,
        startedAt = Instant.now()
      )

      Effect
        .persist(event)
        .thenRun { _ =>
          // 承認者に通知
          notificationService.notifyApprover(request).foreach { _ =>
            context.self ! ApproverNotified(Instant.now())
          }
        }

    case (pending: PendingApproval, ApproverNotified(notifiedAt)) =>
      context.log.info(s"承認者通知完了: ${pending.requestId.value}")

      val event = ApproverWasNotified(
        requestId = pending.requestId,
        notifiedAt = notifiedAt
      )

      Effect.persist(event)

    case (pending: PendingApproval, ApprovalReceived(approver, approvedAt)) =>
      context.log.info(s"承認受信: ${pending.requestId.value}")

      // タイマーキャンセル
      timers.cancel(ApprovalTimeout)

      val event = ApprovalWasReceived(
        requestId = pending.requestId,
        approver = approver,
        approvedAt = approvedAt
      )

      Effect
        .persist(event)
        .thenRun { _ =>
          // 価格変更を適用
          val probe = context.system.ignoreRef[StatusReply[ProductEvent]]
          productActor ! ChangeProductPrice(
            productId = pending.priceChangeRequest.productId,
            newPrice = pending.priceChangeRequest.newPrice,
            effectiveFrom = pending.priceChangeRequest.effectiveFrom,
            replyTo = probe
          )
          context.self ! ChangeApplied(Instant.now())
        }

    case (pending: PendingApproval, RejectionReceived(approver, reason, rejectedAt)) =>
      context.log.info(s"却下受信: ${pending.requestId.value}")

      // タイマーキャンセル
      timers.cancel(ApprovalTimeout)

      val event = RejectionWasReceived(
        requestId = pending.requestId,
        approver = approver,
        reason = reason,
        rejectedAt = rejectedAt
      )

      Effect.persist(event)

    case (approved: Approved, ChangeApplied(appliedAt)) =>
      context.log.info(s"変更適用完了: ${approved.requestId.value}")

      val event = ChangeWasApplied(
        requestId = approved.requestId,
        appliedAt = appliedAt
      )

      Effect
        .persist(event)
        .thenRun { _ =>
          // 申請者に完了通知
          notificationService.notifyRequester(
            approved.priceChangeRequest,
            approved = true
          )
        }

    case (pending: PendingApproval, ApprovalTimeout) =>
      context.log.warn(s"承認タイムアウト: ${pending.requestId.value}")

      val event = ApprovalWasTimedOut(
        requestId = pending.requestId,
        timedOutAt = Instant.now()
      )

      Effect
        .persist(event)
        .thenRun { _ =>
          // システム管理者に通知
          notificationService.notifyTimeout(pending.priceChangeRequest)
        }

    case (state, cmd) =>
      context.log.warn(s"無効なコマンド: state=${state.getClass.getSimpleName}, cmd=${cmd.getClass.getSimpleName}")
      Effect.none
  }

  private def eventHandler: (State, Event) => State = {
    case (EmptyState, ApprovalProcessStarted(requestId, request, startedAt)) =>
      PendingApproval(
        requestId = requestId,
        priceChangeRequest = request,
        startedAt = startedAt,
        notifiedAt = None
      )

    case (pending: PendingApproval, ApproverWasNotified(_, notifiedAt)) =>
      pending.copy(notifiedAt = Some(notifiedAt))

    case (pending: PendingApproval, ApprovalWasReceived(requestId, approver, approvedAt)) =>
      Approved(
        requestId = requestId,
        priceChangeRequest = pending.priceChangeRequest,
        approver = approver,
        approvedAt = approvedAt
      )

    case (pending: PendingApproval, RejectionWasReceived(requestId, approver, reason, rejectedAt)) =>
      Rejected(
        requestId = requestId,
        priceChangeRequest = pending.priceChangeRequest,
        approver = approver,
        reason = reason,
        rejectedAt = rejectedAt
      )

    case (approved: Approved, ChangeWasApplied(requestId, appliedAt)) =>
      Applied(
        requestId = requestId,
        appliedAt = appliedAt
      )

    case (pending: PendingApproval, ApprovalWasTimedOut(requestId, timedOutAt)) =>
      TimedOut(
        requestId = requestId,
        timedOutAt = timedOutAt
      )

    case (state, _) => state
  }
}
```

### 7.3.3 勘定科目作成承認Saga実装

```scala
package com.example.masterdata.application.saga

/**
 * 勘定科目作成承認Saga
 */
object AccountSubjectCreationApprovalSaga {

  // 価格変更承認Sagaと同様の構造
  sealed trait Command extends CborSerializable

  final case class StartApprovalProcess(
    creationRequest: AccountSubjectCreationRequest
  ) extends Command

  // 以下、PriceChangeApprovalSagaと同様のコマンド・イベント・状態定義

  sealed trait Event extends CborSerializable
  sealed trait State extends CborSerializable

  def apply(
    requestId: ChangeRequestId,
    notificationService: NotificationService,
    accountSubjectActor: ActorRef[AccountSubjectCommand]
  ): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        EventSourcedBehavior[Command, Event, State](
          persistenceId = PersistenceId.ofUniqueId(s"AccountSubjectCreationApprovalSaga-${requestId.value}"),
          emptyState = EmptyState,
          commandHandler = commandHandler(notificationService, accountSubjectActor, timers, context),
          eventHandler = eventHandler
        )
      }
    }
  }

  // commandHandlerとeventHandlerの実装は価格変更承認Sagaと類似
}
```

## 7.4 通知サービス

### 7.4.1 通知サービスインターフェース

```scala
package com.example.masterdata.application.notification

import scala.concurrent.Future
import com.example.masterdata.domain.approval._

/**
 * 通知サービス
 */
trait NotificationService {

  /**
   * 承認者に承認依頼を通知
   */
  def notifyApprover(request: ChangeRequest): Future[Unit]

  /**
   * 申請者に承認・却下結果を通知
   */
  def notifyRequester(request: ChangeRequest, approved: Boolean): Future[Unit]

  /**
   * システム管理者に承認タイムアウトを通知
   */
  def notifyTimeout(request: ChangeRequest): Future[Unit]
}
```

### 7.4.2 メール通知サービス実装

```scala
package com.example.masterdata.infrastructure.notification

import scala.concurrent.{ExecutionContext, Future}
import com.example.masterdata.domain.approval._
import com.example.masterdata.application.notification.NotificationService

/**
 * メール通知サービス実装
 */
class EmailNotificationService(
  emailClient: EmailClient
)(implicit ec: ExecutionContext) extends NotificationService {

  override def notifyApprover(request: ChangeRequest): Future[Unit] = {
    val subject = request match {
      case _: PriceChangeRequest => "【承認依頼】商品価格変更申請"
      case _: AccountSubjectCreationRequest => "【承認依頼】勘定科目作成申請"
      case _ => "【承認依頼】マスターデータ変更申請"
    }

    val body = buildApprovalRequestEmail(request)

    // 承認者にメール送信（実際には承認者リストから取得）
    val approverEmail = "approver@example.com" // 実際にはDBから取得

    emailClient.send(
      to = approverEmail,
      subject = subject,
      body = body
    )
  }

  override def notifyRequester(request: ChangeRequest, approved: Boolean): Future[Unit] = {
    val subject = if (approved) {
      "【承認完了】マスターデータ変更申請が承認されました"
    } else {
      "【却下】マスターデータ変更申請が却下されました"
    }

    val body = if (approved) {
      buildApprovalNotificationEmail(request)
    } else {
      buildRejectionNotificationEmail(request)
    }

    emailClient.send(
      to = request.requester.email,
      subject = subject,
      body = body
    )
  }

  override def notifyTimeout(request: ChangeRequest): Future[Unit] = {
    val subject = "【警告】承認タイムアウト"
    val body = buildTimeoutNotificationEmail(request)

    emailClient.send(
      to = "admin@example.com",
      subject = subject,
      body = body
    )
  }

  private def buildApprovalRequestEmail(request: ChangeRequest): String = {
    request match {
      case pc: PriceChangeRequest =>
        s"""
        |承認依頼が届いています。
        |
        |申請ID: ${pc.id.value}
        |申請種別: 商品価格変更
        |商品コード: ${pc.productCode.value}
        |商品名: ${pc.productName.value}
        |
        |旧価格: ${pc.oldPrice.amount}円
        |新価格: ${pc.newPrice.amount}円
        |有効開始日: ${pc.effectiveFrom}
        |
        |変更理由:
        |${pc.reason}
        |
        |申請者: ${pc.requester.userName} (${pc.requester.email})
        |申請日時: ${pc.requestedAt}
        |
        |承認画面: https://masterdata.example.com/approval/${pc.id.value}
        """.stripMargin

      case ac: AccountSubjectCreationRequest =>
        s"""
        |承認依頼が届いています。
        |
        |申請ID: ${ac.id.value}
        |申請種別: 勘定科目作成
        |勘定科目コード: ${ac.accountCode.value}
        |勘定科目名: ${ac.accountName.value}
        |勘定科目種別: ${ac.accountType}
        |
        |作成理由:
        |${ac.reason}
        |
        |申請者: ${ac.requester.userName} (${ac.requester.email})
        |申請日時: ${ac.requestedAt}
        |
        |承認画面: https://masterdata.example.com/approval/${ac.id.value}
        """.stripMargin

      case _ => ""
    }
  }

  private def buildApprovalNotificationEmail(request: ChangeRequest): String = {
    s"""
    |変更申請が承認されました。
    |
    |申請ID: ${request.id.value}
    |承認者: ${request.approver.map(_.userName).getOrElse("N/A")}
    |承認日時: ${request.approvedAt.getOrElse("N/A")}
    |
    |変更は自動的に適用されます。
    """.stripMargin
  }

  private def buildRejectionNotificationEmail(request: ChangeRequest): String = {
    s"""
    |変更申請が却下されました。
    |
    |申請ID: ${request.id.value}
    |承認者: ${request.approver.map(_.userName).getOrElse("N/A")}
    |却下理由:
    |${request.rejectionReason.getOrElse("N/A")}
    |
    |再申請が必要な場合は、内容を修正して再度申請してください。
    """.stripMargin
  }

  private def buildTimeoutNotificationEmail(request: ChangeRequest): String = {
    s"""
    |【警告】承認タイムアウトが発生しました。
    |
    |申請ID: ${request.id.value}
    |申請種別: ${request.getClass.getSimpleName}
    |申請者: ${request.requester.userName}
    |申請日時: ${request.requestedAt}
    |
    |72時間以内に承認されませんでした。
    |対応が必要です。
    |
    |承認画面: https://masterdata.example.com/approval/${request.id.value}
    """.stripMargin
  }
}

/**
 * メールクライアント
 */
trait EmailClient {
  def send(to: String, subject: String, body: String): Future[Unit]
}
```

## 7.5 承認ワークフローのテスト

### 7.5.1 承認Sagaのテスト

```scala
package com.example.masterdata.application.saga

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration._
import java.time.Instant

class PriceChangeApprovalSagaSpec extends AnyWordSpec with Matchers {

  val testKit = ActorTestKit()

  "PriceChangeApprovalSaga" should {

    "承認プロセスを正常に完了する" in {
      // Given: 価格変更申請
      val requestId = ChangeRequestId.generate()
      val priceChangeRequest = PriceChangeRequest(
        id = requestId,
        productId = ProductId.generate(),
        productCode = ProductCode("P-001"),
        productName = ProductName("テスト商品"),
        oldPrice = Money(BigDecimal(1000)),
        newPrice = Money(BigDecimal(1500)),
        effectiveFrom = LocalDate.now().plusDays(7),
        reason = "市場価格上昇のため",
        requester = Requester("user1", "田中太郎", "tanaka@example.com"),
        status = ChangeRequestStatus.Pending,
        requestedAt = Instant.now(),
        approver = None,
        approvedAt = None,
        rejectionReason = None
      )

      val mockNotificationService = new MockNotificationService()
      val mockProductActor = testKit.createTestProbe[ProductCommand]()

      val saga = testKit.spawn(
        PriceChangeApprovalSaga(requestId, mockNotificationService, mockProductActor.ref)
      )

      // When: 承認プロセス開始
      saga ! StartApprovalProcess(priceChangeRequest)

      // Then: 承認者に通知される
      eventually(timeout(3.seconds)) {
        mockNotificationService.approverNotified shouldBe true
      }

      // When: 承認を受信
      val approver = Approver("approver1", "佐藤花子", "sato@example.com")
      saga ! ApprovalReceived(approver, Instant.now())

      // Then: 商品価格変更コマンドが発行される
      val priceChangeCmd = mockProductActor.receiveMessage(3.seconds)
      priceChangeCmd shouldBe a[ChangeProductPrice]

      // When: 変更適用完了
      saga ! ChangeApplied(Instant.now())

      // Then: 申請者に完了通知される
      eventually(timeout(3.seconds)) {
        mockNotificationService.requesterNotified shouldBe true
      }
    }

    "却下を正常に処理する" in {
      // Given: 価格変更申請
      val requestId = ChangeRequestId.generate()
      val priceChangeRequest = createPriceChangeRequest(requestId)

      val mockNotificationService = new MockNotificationService()
      val mockProductActor = testKit.createTestProbe[ProductCommand]()

      val saga = testKit.spawn(
        PriceChangeApprovalSaga(requestId, mockNotificationService, mockProductActor.ref)
      )

      // When: 承認プロセス開始
      saga ! StartApprovalProcess(priceChangeRequest)

      // When: 却下を受信
      val approver = Approver("approver1", "佐藤花子", "sato@example.com")
      saga ! RejectionReceived(approver, "価格上昇の根拠が不十分", Instant.now())

      // Then: 商品価格変更コマンドは発行されない
      mockProductActor.expectNoMessage(3.seconds)

      // Then: 申請者に却下通知される
      eventually(timeout(3.seconds)) {
        mockNotificationService.requesterNotified shouldBe true
      }
    }

    "承認タイムアウトを処理する" in {
      // タイムアウトのテストはタイマーの短縮版で実施
      // 実装省略
    }
  }

  override def afterAll(): Unit = {
    testKit.shutdownTestKit()
  }

  private def createPriceChangeRequest(requestId: ChangeRequestId): PriceChangeRequest = {
    PriceChangeRequest(
      id = requestId,
      productId = ProductId.generate(),
      productCode = ProductCode("P-001"),
      productName = ProductName("テスト商品"),
      oldPrice = Money(BigDecimal(1000)),
      newPrice = Money(BigDecimal(1500)),
      effectiveFrom = LocalDate.now().plusDays(7),
      reason = "市場価格上昇のため",
      requester = Requester("user1", "田中太郎", "tanaka@example.com"),
      status = ChangeRequestStatus.Pending,
      requestedAt = Instant.now(),
      approver = None,
      approvedAt = None,
      rejectionReason = None
    )
  }
}

class MockNotificationService extends NotificationService {
  var approverNotified = false
  var requesterNotified = false
  var timeoutNotified = false

  override def notifyApprover(request: ChangeRequest): Future[Unit] = {
    approverNotified = true
    Future.successful(())
  }

  override def notifyRequester(request: ChangeRequest, approved: Boolean): Future[Unit] = {
    requesterNotified = true
    Future.successful(())
  }

  override def notifyTimeout(request: ChangeRequest): Future[Unit] = {
    timeoutNotified = true
    Future.successful(())
  }
}
```

## 7.6 まとめ

本章では、マスターデータの変更承認ワークフローをSagaパターンで実装しました。

### 実装した内容

1. **承認が必要な変更の定義**
   - 商品価格変更（一定金額以上）
   - 勘定科目の追加・変更
   - 承認ルールの実装

2. **変更申請データモデル**
   - PriceChangeRequest、AccountSubjectCreationRequest
   - 変更申請テーブル設計
   - 申請ステータス管理

3. **承認フロー**
   - ApprovalFlowActorによる承認フロー管理
   - 申請受付、承認、却下、取り消し
   - 変更の自動適用

4. **承認Saga**
   - PriceChangeApprovalSagaの実装
   - イベントソーシングによる状態管理
   - 承認タイムアウト処理

5. **通知サービス**
   - メール通知サービス実装
   - 承認依頼、結果通知、タイムアウト警告

6. **承認ワークフローのテスト**
   - Sagaの単体テスト
   - 承認・却下・タイムアウトのシナリオテスト

### 次章の予告

次の第8章では、本シリーズの総まとめと実践演習を行います。これまでに学んだCQRS/イベントソーシングの知識を統合し、実際のプロジェクトで活用するためのベストプラクティスを解説します。
