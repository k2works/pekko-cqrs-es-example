# 第7部第6章：イベント駆動マスターデータ同期

## 本章の目的

第5章で実装した商品集約と勘定科目集約のドメインイベントを、他のBounded Contextに伝播させる仕組みを実装します。本章では、イベント駆動アーキテクチャによるマスターデータ同期の設計と実装について詳しく解説します。

## 6.1 マスターデータ変更イベントの発行

### 6.1.1 ドメインイベントの設計

マスターデータの変更を他のBounded Contextに通知するため、以下のドメインイベントを設計します。

```scala
package com.example.masterdata.domain.event

import java.time.{Instant, LocalDate}
import java.util.UUID

// マスターデータイベントの基底トレイト
sealed trait MasterDataEvent extends DomainEvent {
  def eventId: UUID
  def occurredAt: Instant
  def aggregateId: String
}

// 商品マスターイベント
object ProductEvents {

  final case class ProductCreated(
    eventId: UUID,
    productId: ProductId,
    productCode: ProductCode,
    productName: ProductName,
    categoryCode: CategoryCode,
    standardCost: Money,
    listPrice: Money,
    occurredAt: Instant
  ) extends MasterDataEvent {
    override def aggregateId: String = productId.value
  }

  final case class ProductInfoUpdated(
    eventId: UUID,
    productId: ProductId,
    productCode: ProductCode,
    productName: ProductName,
    categoryCode: CategoryCode,
    occurredAt: Instant
  ) extends MasterDataEvent {
    override def aggregateId: String = productId.value
  }

  final case class ProductPriceChanged(
    eventId: UUID,
    productId: ProductId,
    productCode: ProductCode,
    oldPrice: Money,
    newPrice: Money,
    validFrom: LocalDate,
    occurredAt: Instant
  ) extends MasterDataEvent {
    override def aggregateId: String = productId.value
  }

  final case class ProductStatusChanged(
    eventId: UUID,
    productId: ProductId,
    productCode: ProductCode,
    oldStatus: ProductStatus,
    newStatus: ProductStatus,
    occurredAt: Instant
  ) extends MasterDataEvent {
    override def aggregateId: String = productId.value
  }
}

// 勘定科目マスターイベント
object AccountSubjectEvents {

  final case class AccountSubjectCreated(
    eventId: UUID,
    accountSubjectId: AccountSubjectId,
    accountCode: AccountCode,
    accountName: AccountName,
    accountType: AccountType,
    occurredAt: Instant
  ) extends MasterDataEvent {
    override def aggregateId: String = accountSubjectId.value
  }

  final case class AccountSubjectNameUpdated(
    eventId: UUID,
    accountSubjectId: AccountSubjectId,
    accountCode: AccountCode,
    oldName: AccountName,
    newName: AccountName,
    occurredAt: Instant
  ) extends MasterDataEvent {
    override def aggregateId: String = accountSubjectId.value
  }

  final case class AccountSubjectStatusChanged(
    eventId: UUID,
    accountSubjectId: AccountSubjectId,
    accountCode: AccountCode,
    oldStatus: AccountSubjectStatus,
    newStatus: AccountSubjectStatus,
    occurredAt: Instant
  ) extends MasterDataEvent {
    override def aggregateId: String = accountSubjectId.value
  }
}
```

### 6.1.2 イベント発行の実装

Pekko Persistenceの`EventSourcedBehavior`で永続化されたイベントを、Pekko Clusterの`EventBus`を使って他のサービスに配信します。

```scala
package com.example.masterdata.infrastructure.event

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.cluster.pubsub.DistributedPubSub
import org.apache.pekko.cluster.pubsub.DistributedPubSubMediator.Publish
import org.apache.pekko.persistence.query.PersistenceQuery
import org.apache.pekko.persistence.query.journal.dynamodb.scaladsl.DynamoDBReadJournal
import org.apache.pekko.stream.scaladsl.{RestartSource, Sink}
import com.example.masterdata.domain.event.MasterDataEvent
import scala.concurrent.duration._

/**
 * マスターデータイベント発行者
 *
 * DynamoDBから読み取ったイベントをPub/Subで配信
 */
class MasterDataEventPublisher(system: ActorSystem[_]) {

  private val readJournal = PersistenceQuery(system)
    .readJournalFor[DynamoDBReadJournal](DynamoDBReadJournal.Identifier)

  private val mediator = DistributedPubSub(system).mediator

  def startPublishing(): Unit = {
    // 商品イベントの購読と配信
    publishProductEvents()

    // 勘定科目イベントの購読と配信
    publishAccountSubjectEvents()
  }

  private def publishProductEvents(): Unit = {
    val source = RestartSource.onFailuresWithBackoff(
      minBackoff = 3.seconds,
      maxBackoff = 30.seconds,
      randomFactor = 0.2
    ) { () =>
      readJournal
        .eventsByTag("product-event", readJournal.NoOffset)
        .map { envelope =>
          envelope.event match {
            case event: MasterDataEvent =>
              // Pub/Subトピックに発行
              mediator ! Publish("master-data.product", event)
              envelope.offset
            case _ =>
              envelope.offset
          }
        }
    }

    source.runWith(Sink.ignore)(system)
  }

  private def publishAccountSubjectEvents(): Unit = {
    val source = RestartSource.onFailuresWithBackoff(
      minBackoff = 3.seconds,
      maxBackoff = 30.seconds,
      randomFactor = 0.2
    ) { () =>
      readJournal
        .eventsByTag("account-subject-event", readJournal.NoOffset)
        .map { envelope =>
          envelope.event match {
            case event: MasterDataEvent =>
              // Pub/Subトピックに発行
              mediator ! Publish("master-data.account-subject", event)
              envelope.offset
            case _ =>
              envelope.offset
          }
        }
    }

    source.runWith(Sink.ignore)(system)
  }
}
```

### 6.1.3 イベントタギング

イベントをタグ付けすることで、特定のイベント種別だけを購読できるようにします。

```scala
// ProductActorのEventSourcedBehaviorにタグを追加
object ProductActor {

  def apply(productId: ProductId, productCodeValidator: ProductCode => Boolean): Behavior[ProductCommand] = {
    Behaviors.setup { context =>
      EventSourcedBehavior[ProductCommand, ProductEvent, State](
        persistenceId = PersistenceId.ofUniqueId(s"Product-${productId.value}"),
        emptyState = EmptyState,
        commandHandler = commandHandler(productCodeValidator, context),
        eventHandler = eventHandler
      )
        .withTagger {
          case _: ProductCreated => Set("product-event", "product-created")
          case _: ProductInfoUpdated => Set("product-event", "product-info-updated")
          case _: ProductPriceChanged => Set("product-event", "product-price-changed")
          case _: ProductSuspended => Set("product-event", "product-suspended")
          case _: ProductReactivated => Set("product-event", "product-reactivated")
          case _: ProductObsoleted => Set("product-event", "product-obsoleted")
          case _ => Set("product-event")
        }
        .withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100, keepNSnapshots = 2))
    }
  }
}
```

## 6.2 他のBounded Contextでのイベント購読

### 6.2.1 在庫管理サービスでの商品情報同期

在庫管理サービスは、商品マスターの変更イベントを購読して、自サービスの商品参照データを更新します。

```scala
package com.example.inventory.infrastructure.integration

import org.apache.pekko.actor.typed.{ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.pubsub.DistributedPubSub
import org.apache.pekko.cluster.pubsub.DistributedPubSubMediator.{Subscribe, SubscribeAck}
import com.example.masterdata.domain.event.ProductEvents._
import com.example.inventory.infrastructure.repository.ProductReferenceRepository
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/**
 * 在庫管理サービスの商品参照データ更新
 */
object ProductReadModelUpdater {

  sealed trait Command
  private case class HandleProductCreated(event: ProductCreated) extends Command
  private case class HandleProductInfoUpdated(event: ProductInfoUpdated) extends Command
  private case class HandleProductPriceChanged(event: ProductPriceChanged) extends Command
  private case class HandleProductStatusChanged(event: ProductStatusChanged) extends Command

  def apply(repository: ProductReferenceRepository)(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.setup { context =>
      val mediator = DistributedPubSub(context.system).mediator

      // Pub/Subトピックを購読
      mediator ! Subscribe("master-data.product", context.self.toClassic)

      Behaviors.receiveMessage {
        case HandleProductCreated(event) =>
          context.log.info(s"商品作成イベント受信: ${event.productCode.value}")

          val future = repository.insert(
            productId = event.productId.value,
            productCode = event.productCode.value,
            productName = event.productName.value,
            currentPrice = event.listPrice.amount,
            isActive = true
          )

          context.pipeToSelf(future) {
            case Success(_) =>
              context.log.info(s"商品参照データ追加完了: ${event.productCode.value}")
              HandleProductCreated(event)
            case Failure(ex) =>
              context.log.error(s"商品参照データ追加失敗: ${event.productCode.value}", ex)
              HandleProductCreated(event)
          }
          Behaviors.same

        case HandleProductInfoUpdated(event) =>
          context.log.info(s"商品情報更新イベント受信: ${event.productCode.value}")

          val future = repository.updateProductInfo(
            productId = event.productId.value,
            productName = event.productName.value
          )

          context.pipeToSelf(future) {
            case Success(_) =>
              context.log.info(s"商品参照データ更新完了: ${event.productCode.value}")
              HandleProductInfoUpdated(event)
            case Failure(ex) =>
              context.log.error(s"商品参照データ更新失敗: ${event.productCode.value}", ex)
              HandleProductInfoUpdated(event)
          }
          Behaviors.same

        case HandleProductPriceChanged(event) =>
          context.log.info(s"商品価格変更イベント受信: ${event.productCode.value}")

          val future = repository.updatePrice(
            productId = event.productId.value,
            newPrice = event.newPrice.amount
          )

          context.pipeToSelf(future) {
            case Success(_) =>
              context.log.info(s"商品価格更新完了: ${event.productCode.value}")
              HandleProductPriceChanged(event)
            case Failure(ex) =>
              context.log.error(s"商品価格更新失敗: ${event.productCode.value}", ex)
              HandleProductPriceChanged(event)
          }
          Behaviors.same

        case HandleProductStatusChanged(event) =>
          context.log.info(s"商品ステータス変更イベント受信: ${event.productCode.value}")

          val isActive = event.newStatus match {
            case ProductStatus.Active => true
            case _ => false
          }

          val future = repository.updateStatus(
            productId = event.productId.value,
            isActive = isActive
          )

          context.pipeToSelf(future) {
            case Success(_) =>
              context.log.info(s"商品ステータス更新完了: ${event.productCode.value}")
              HandleProductStatusChanged(event)
            case Failure(ex) =>
              context.log.error(s"商品ステータス更新失敗: ${event.productCode.value}", ex)
              HandleProductStatusChanged(event)
          }
          Behaviors.same
      }
    }
  }
}
```

### 6.2.2 会計サービスでの勘定科目同期

会計サービスは、勘定科目マスターの変更イベントを購読して、自サービスの勘定科目参照データを更新します。

```scala
package com.example.accounting.infrastructure.integration

import org.apache.pekko.actor.typed.{ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.cluster.pubsub.DistributedPubSub
import org.apache.pekko.cluster.pubsub.DistributedPubSubMediator.Subscribe
import com.example.masterdata.domain.event.AccountSubjectEvents._
import com.example.accounting.infrastructure.repository.AccountSubjectReferenceRepository
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/**
 * 会計サービスの勘定科目参照データ更新
 */
object AccountSubjectReadModelUpdater {

  sealed trait Command
  private case class HandleAccountSubjectCreated(event: AccountSubjectCreated) extends Command
  private case class HandleAccountSubjectNameUpdated(event: AccountSubjectNameUpdated) extends Command
  private case class HandleAccountSubjectStatusChanged(event: AccountSubjectStatusChanged) extends Command

  def apply(repository: AccountSubjectReferenceRepository)(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.setup { context =>
      val mediator = DistributedPubSub(context.system).mediator

      // Pub/Subトピックを購読
      mediator ! Subscribe("master-data.account-subject", context.self.toClassic)

      Behaviors.receiveMessage {
        case HandleAccountSubjectCreated(event) =>
          context.log.info(s"勘定科目作成イベント受信: ${event.accountCode.value}")

          val future = repository.insert(
            accountSubjectId = event.accountSubjectId.value,
            accountCode = event.accountCode.value,
            accountName = event.accountName.value,
            accountType = event.accountType.toString,
            isActive = true
          )

          context.pipeToSelf(future) {
            case Success(_) =>
              context.log.info(s"勘定科目参照データ追加完了: ${event.accountCode.value}")
              HandleAccountSubjectCreated(event)
            case Failure(ex) =>
              context.log.error(s"勘定科目参照データ追加失敗: ${event.accountCode.value}", ex)
              HandleAccountSubjectCreated(event)
          }
          Behaviors.same

        case HandleAccountSubjectNameUpdated(event) =>
          context.log.info(s"勘定科目名変更イベント受信: ${event.accountCode.value}")

          val future = repository.updateAccountName(
            accountSubjectId = event.accountSubjectId.value,
            accountName = event.newName.value
          )

          context.pipeToSelf(future) {
            case Success(_) =>
              context.log.info(s"勘定科目名更新完了: ${event.accountCode.value}")
              HandleAccountSubjectNameUpdated(event)
            case Failure(ex) =>
              context.log.error(s"勘定科目名更新失敗: ${event.accountCode.value}", ex)
              HandleAccountSubjectNameUpdated(event)
          }
          Behaviors.same

        case HandleAccountSubjectStatusChanged(event) =>
          context.log.info(s"勘定科目ステータス変更イベント受信: ${event.accountCode.value}")

          val isActive = event.newStatus match {
            case AccountSubjectStatus.Active => true
            case _ => false
          }

          val future = repository.updateStatus(
            accountSubjectId = event.accountSubjectId.value,
            isActive = isActive
          )

          context.pipeToSelf(future) {
            case Success(_) =>
              context.log.info(s"勘定科目ステータス更新完了: ${event.accountCode.value}")
              HandleAccountSubjectStatusChanged(event)
            case Failure(ex) =>
              context.log.error(s"勘定科目ステータス更新失敗: ${event.accountCode.value}", ex)
              HandleAccountSubjectStatusChanged(event)
          }
          Behaviors.same
      }
    }
  }
}
```

## 6.3 参照データの最適化（CQRS）

### 6.3.1 Materialized Viewの設計

各Bounded Contextは、自分が必要とする参照データのみを保持する非正規化テーブル（Materialized View）を持ちます。

#### 在庫管理サービスの商品参照テーブル

```sql
-- 在庫管理サービス用の商品参照テーブル
CREATE TABLE inventory_management.product_reference (
  product_id UUID PRIMARY KEY,
  product_code VARCHAR(50) NOT NULL,
  product_name VARCHAR(200) NOT NULL,
  current_price DECIMAL(15,2) NOT NULL,
  is_active BOOLEAN NOT NULL,
  last_updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  CONSTRAINT uq_product_reference_code UNIQUE (product_code)
);

CREATE INDEX idx_product_reference_code ON inventory_management.product_reference(product_code);
CREATE INDEX idx_product_reference_active ON inventory_management.product_reference(is_active);
CREATE INDEX idx_product_reference_updated ON inventory_management.product_reference(last_updated_at DESC);
```

#### 受注管理サービスの商品参照テーブル

```sql
-- 受注管理サービス用の商品参照テーブル（価格情報を重視）
CREATE TABLE order_management.product_reference (
  product_id UUID PRIMARY KEY,
  product_code VARCHAR(50) NOT NULL,
  product_name VARCHAR(200) NOT NULL,
  standard_cost DECIMAL(15,2) NOT NULL,
  current_price DECIMAL(15,2) NOT NULL,
  is_orderable BOOLEAN NOT NULL,
  last_updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  CONSTRAINT uq_order_product_reference_code UNIQUE (product_code)
);

CREATE INDEX idx_order_product_reference_code ON order_management.product_reference(product_code);
CREATE INDEX idx_order_product_reference_orderable ON order_management.product_reference(is_orderable);
```

#### 会計サービスの勘定科目参照テーブル

```sql
-- 会計サービス用の勘定科目参照テーブル
CREATE TABLE accounting.account_subject_reference (
  account_subject_id UUID PRIMARY KEY,
  account_code VARCHAR(20) NOT NULL,
  account_name VARCHAR(200) NOT NULL,
  account_type VARCHAR(20) NOT NULL,
  is_active BOOLEAN NOT NULL,
  last_updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  CONSTRAINT uq_account_subject_reference_code UNIQUE (account_code)
);

CREATE INDEX idx_account_subject_reference_code ON accounting.account_subject_reference(account_code);
CREATE INDEX idx_account_subject_reference_type ON accounting.account_subject_reference(account_type);
CREATE INDEX idx_account_subject_reference_active ON accounting.account_subject_reference(is_active);
```

### 6.3.2 Slick DAOの実装

在庫管理サービスの商品参照データDAO実装例：

```scala
package com.example.inventory.infrastructure.repository

import slick.jdbc.PostgresProfile.api._
import java.util.UUID
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class ProductReferenceRepository(db: Database)(implicit ec: ExecutionContext) {

  // テーブル定義
  class ProductReferenceTable(tag: Tag) extends Table[(UUID, String, String, BigDecimal, Boolean, Instant)](tag, Some("inventory_management"), "product_reference") {
    def productId = column[UUID]("product_id", O.PrimaryKey)
    def productCode = column[String]("product_code")
    def productName = column[String]("product_name")
    def currentPrice = column[BigDecimal]("current_price")
    def isActive = column[Boolean]("is_active")
    def lastUpdatedAt = column[Instant]("last_updated_at")

    def * = (productId, productCode, productName, currentPrice, isActive, lastUpdatedAt)
  }

  private val productReferenceTable = TableQuery[ProductReferenceTable]

  def insert(
    productId: String,
    productCode: String,
    productName: String,
    currentPrice: BigDecimal,
    isActive: Boolean
  ): Future[Int] = {
    val action = productReferenceTable += (
      UUID.fromString(productId),
      productCode,
      productName,
      currentPrice,
      isActive,
      Instant.now()
    )
    db.run(action)
  }

  def updateProductInfo(productId: String, productName: String): Future[Int] = {
    val action = productReferenceTable
      .filter(_.productId === UUID.fromString(productId))
      .map(p => (p.productName, p.lastUpdatedAt))
      .update((productName, Instant.now()))
    db.run(action)
  }

  def updatePrice(productId: String, newPrice: BigDecimal): Future[Int] = {
    val action = productReferenceTable
      .filter(_.productId === UUID.fromString(productId))
      .map(p => (p.currentPrice, p.lastUpdatedAt))
      .update((newPrice, Instant.now()))
    db.run(action)
  }

  def updateStatus(productId: String, isActive: Boolean): Future[Int] = {
    val action = productReferenceTable
      .filter(_.productId === UUID.fromString(productId))
      .map(p => (p.isActive, p.lastUpdatedAt))
      .update((isActive, Instant.now()))
    db.run(action)
  }

  def findByProductCode(productCode: String): Future[Option[(UUID, String, String, BigDecimal, Boolean, Instant)]] = {
    val action = productReferenceTable
      .filter(_.productCode === productCode)
      .result
      .headOption
    db.run(action)
  }
}
```

## 6.4 マスターデータ同期の整合性

### 6.4.1 結果整合性（Eventual Consistency）

マスターデータの変更は、イベント駆動により各Bounded Contextに非同期で伝播されます。これは**結果整合性**と呼ばれるパターンです。

#### 整合性の特性

- **即時反映されない**: マスター変更は即座に全システムに反映されない
- **遅延時間**: イベント処理により数秒〜数分で反映される
- **ビジネス的許容**: D社のビジネス要件では、この遅延は許容可能

#### 結果整合性のメリット

1. **高可用性**: マスターデータサービスと参照側サービスが疎結合
2. **スケーラビリティ**: 各サービスが独立してスケールアウト可能
3. **障害隔離**: マスターデータサービスの障害が他サービスに影響しない

### 6.4.2 整合性保証の仕組み

#### イベント順序保証

Pekko Clusterの`DistributedPubSub`では、同一の集約（同じaggregate ID）から発行されたイベントの順序が保証されます。

```scala
// イベント発行時に集約IDをパーティションキーとして使用
mediator ! Publish(
  topic = "master-data.product",
  msg = event,
  sendOneMessageToEachGroup = true
)
```

#### 冪等性（Idempotency）

同じイベントを複数回処理しても、結果が同じになるよう実装します。

```scala
// 冪等性を保証するためのUPSERT操作
def upsertProductReference(event: ProductCreated): Future[Int] = {
  val action = sqlu"""
    INSERT INTO inventory_management.product_reference
      (product_id, product_code, product_name, current_price, is_active, last_updated_at)
    VALUES
      (${event.productId.value}::uuid, ${event.productCode.value}, ${event.productName.value},
       ${event.listPrice.amount}, true, ${event.occurredAt})
    ON CONFLICT (product_id)
    DO UPDATE SET
      product_name = EXCLUDED.product_name,
      current_price = EXCLUDED.current_price,
      last_updated_at = EXCLUDED.last_updated_at
    WHERE product_reference.last_updated_at < EXCLUDED.last_updated_at
  """
  db.run(action)
}
```

#### リトライ機構

イベント処理が失敗した場合、自動的にリトライします。

```scala
// リトライ付きイベント処理
import org.apache.pekko.pattern.retry
import scala.concurrent.duration._

def processEventWithRetry(event: MasterDataEvent): Future[Unit] = {
  retry(
    () => processEvent(event),
    attempts = 5,
    delay = 1.second,
    exponentialBackoff = 2.0
  )
}
```

### 6.4.3 整合性モニタリング

マスターデータの同期状態を監視するために、以下のメトリクスを収集します。

```scala
package com.example.masterdata.infrastructure.monitoring

import org.apache.pekko.actor.typed.{ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import scala.concurrent.duration._
import java.time.Instant

/**
 * マスターデータ同期状態モニター
 */
object MasterDataSyncMonitor {

  sealed trait Command
  case object CheckSyncStatus extends Command

  case class SyncStatus(
    topic: String,
    lastEventTime: Instant,
    lastProcessedTime: Instant,
    lagSeconds: Long,
    failedEvents: Int
  )

  def apply(): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        timers.startTimerWithFixedDelay(CheckSyncStatus, 30.seconds)

        Behaviors.receiveMessage {
          case CheckSyncStatus =>
            // 各トピックの同期状態をチェック
            checkProductSync(context)
            checkAccountSubjectSync(context)
            Behaviors.same
        }
      }
    }
  }

  private def checkProductSync(context: ActorContext): Unit = {
    // 最新イベント時刻と最終処理時刻を比較
    val lag = calculateLag("master-data.product")

    if (lag > 300) { // 5分以上の遅延
      context.log.warn(s"商品マスター同期に遅延: ${lag}秒")
    }
  }

  private def checkAccountSubjectSync(context: ActorContext): Unit = {
    val lag = calculateLag("master-data.account-subject")

    if (lag > 300) {
      context.log.warn(s"勘定科目マスター同期に遅延: ${lag}秒")
    }
  }

  private def calculateLag(topic: String): Long = {
    // 実際にはデータベースから最新イベント時刻と処理時刻を取得
    // ここでは簡略化
    0L
  }
}
```

## 6.5 イベント駆動アーキテクチャのベストプラクティス

### 6.5.1 イベントスキーマの進化

イベントスキーマは時間とともに進化します。後方互換性を保つために以下のルールに従います。

```scala
// バージョン1
final case class ProductCreatedV1(
  eventId: UUID,
  productId: ProductId,
  productCode: ProductCode,
  productName: ProductName,
  listPrice: Money,
  occurredAt: Instant
) extends MasterDataEvent

// バージョン2（categoryCode追加）
final case class ProductCreatedV2(
  eventId: UUID,
  productId: ProductId,
  productCode: ProductCode,
  productName: ProductName,
  categoryCode: Option[CategoryCode], // Optionで後方互換性
  listPrice: Money,
  occurredAt: Instant
) extends MasterDataEvent
```

### 6.5.2 イベント駆動テスト

イベント駆動の統合テストを実装します。

```scala
package com.example.masterdata.integration

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration._

class MasterDataEventIntegrationSpec extends AnyWordSpec with Matchers {

  val testKit = ActorTestKit()

  "商品マスターイベント連携" should {
    "商品作成イベントが在庫管理サービスに伝播される" in {
      // Given: 商品を作成
      val productId = ProductId.generate()
      val productCode = ProductCode("P-TEST-001")

      val productActor = testKit.spawn(ProductActor(productId, _ => true))
      val probe = testKit.createTestProbe[StatusReply[ProductEvent]]()

      productActor ! CreateProduct(
        productCode = productCode,
        productName = ProductName("テスト商品"),
        categoryCode = CategoryCode("TEST"),
        standardCost = Money(BigDecimal(1000)),
        listPrice = Money(BigDecimal(1500)),
        replyTo = probe.ref
      )

      // When: イベントが発行される
      val response = probe.receiveMessage(3.seconds)
      response.isSuccess shouldBe true

      // Then: 在庫管理サービスの参照テーブルが更新される（実際のDBチェック）
      eventually(timeout(10.seconds)) {
        val product = productReferenceRepository.findByProductCode(productCode.value).futureValue
        product shouldBe defined
        product.get._3 shouldBe "テスト商品"
      }
    }
  }

  override def afterAll(): Unit = {
    testKit.shutdownTestKit()
  }
}
```

## 6.6 まとめ

本章では、イベント駆動アーキテクチャによるマスターデータ同期の実装を詳しく解説しました。

### 実装した内容

1. **マスターデータ変更イベントの発行**
   - ドメインイベントの設計
   - Pekko Persistenceからのイベント発行
   - イベントタギングによる分類

2. **他のBounded Contextでのイベント購読**
   - 在庫管理サービスの商品参照データ更新
   - 会計サービスの勘定科目参照データ更新
   - Pub/Subパターンの実装

3. **参照データの最適化（CQRS）**
   - Materialized Viewの設計
   - 各サービスに最適化された参照テーブル
   - Slick DAOの実装

4. **マスターデータ同期の整合性**
   - 結果整合性（Eventual Consistency）
   - イベント順序保証、冪等性、リトライ機構
   - 整合性モニタリング

### 次章の予告

次の第7章では、マスターデータの変更承認ワークフローを実装します。価格変更や勘定科目の追加など、重要な変更には承認フローが必要です。Sagaパターンを使った承認ワークフローの実装を詳しく解説します。
