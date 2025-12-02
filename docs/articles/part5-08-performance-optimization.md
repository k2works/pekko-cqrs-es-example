# Part5 第8章: パフォーマンス最適化

## 本章の目的

発注管理システムを本番環境で運用するために必要なパフォーマンス最適化を実装します。承認ルールのキャッシング、大量請求書の効率的な処理、在庫レベル監視の最適化など、実践的な性能改善手法を学びます。

## 本章で学ぶこと

- Redisを使った承認ルールのキャッシング戦略
- Pekko Streamsによる大量データのストリーム処理
- 3-way matchingのバッチ最適化
- イベント駆動アーキテクチャの性能チューニング
- 非同期バッチ処理による負荷分散
- パフォーマンスメトリクスの計測と監視

---

## 8.1 発注承認の高速化

### 8.1.1 承認ルールキャッシングの設計

発注承認では、金額に応じて承認者を決定する必要があります。この承認ルールをデータベースから毎回取得するのは非効率なため、Redisにキャッシュします。

```scala
package com.example.procurement.application.approval

import com.example.shared.domain.*
import com.example.procurement.domain.purchaseorder.*
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

// 承認ルール
final case class ApprovalRule(
  tenantId: TenantId,
  minAmount: Money,
  maxAmount: Option[Money],
  requiredRole: ApproverRole,
  priority: Int // 優先順位（小さいほど優先）
) {
  // 金額が範囲内かチェック
  def matches(amount: Money): Boolean = {
    amount.amount >= minAmount.amount &&
    maxAmount.forall(max => amount.amount < max.amount)
  }
}

object ApprovalRule {
  // デフォルトの承認ルール
  def defaults: List[ApprovalRule] = List(
    ApprovalRule(
      tenantId = TenantId("default"),
      minAmount = Money(0),
      maxAmount = Some(Money(500000)),
      requiredRole = ApproverRole.Manager,
      priority = 1
    ),
    ApprovalRule(
      tenantId = TenantId("default"),
      minAmount = Money(500000),
      maxAmount = Some(Money(1000000)),
      requiredRole = ApproverRole.Director,
      priority = 2
    ),
    ApprovalRule(
      tenantId = TenantId("default"),
      minAmount = Money(1000000),
      maxAmount = None,
      requiredRole = ApproverRole.Executive,
      priority = 3
    )
  )
}

// 承認ルールキャッシュ
trait ApprovalRuleCache {
  // 承認ルールを取得（キャッシュから）
  def getApprovalRules(tenantId: TenantId): Future[List[ApprovalRule]]

  // 承認ルールをキャッシュに保存
  def setApprovalRules(tenantId: TenantId, rules: List[ApprovalRule]): Future[Unit]

  // キャッシュをクリア
  def clearCache(tenantId: TenantId): Future[Unit]

  // 金額に適用する承認ルールを取得
  def findApplicableRule(tenantId: TenantId, amount: Money): Future[Option[ApprovalRule]]
}
```

### 8.1.2 Redisによるキャッシュ実装

Redisを使って承認ルールをキャッシュする実装です。

```scala
package com.example.procurement.infrastructure.cache

import com.example.shared.domain.*
import com.example.procurement.application.approval.*
import redis.clients.jedis.{Jedis, JedisPool}
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.{Try, Success, Failure}
import io.circe.generic.auto.*
import io.circe.parser.*
import io.circe.syntax.*

class RedisApprovalRuleCache(
  jedisPool: JedisPool,
  cacheTTL: Duration = 1.hour
)(implicit ec: ExecutionContext) extends ApprovalRuleCache {

  private def cacheKey(tenantId: TenantId): String = {
    s"approval-rules:${tenantId.value}"
  }

  override def getApprovalRules(tenantId: TenantId): Future[List[ApprovalRule]] = {
    Future {
      val jedis = jedisPool.getResource
      try {
        val key = cacheKey(tenantId)
        val cached = jedis.get(key)

        if (cached != null) {
          // キャッシュヒット
          decode[List[ApprovalRule]](cached) match {
            case Right(rules) =>
              println(s"[CACHE HIT] Approval rules for tenant ${tenantId.value}")
              rules
            case Left(error) =>
              println(s"[CACHE ERROR] Failed to decode rules: $error")
              List.empty
          }
        } else {
          // キャッシュミス
          println(s"[CACHE MISS] Approval rules for tenant ${tenantId.value}")
          List.empty
        }
      } finally {
        jedis.close()
      }
    }
  }

  override def setApprovalRules(tenantId: TenantId, rules: List[ApprovalRule]): Future[Unit] = {
    Future {
      val jedis = jedisPool.getResource
      try {
        val key = cacheKey(tenantId)
        val json = rules.asJson.noSpaces
        val ttlSeconds = cacheTTL.toSeconds.toInt

        jedis.setex(key, ttlSeconds, json)
        println(s"[CACHE SET] Approval rules for tenant ${tenantId.value}, TTL=${ttlSeconds}s")
      } finally {
        jedis.close()
      }
    }
  }

  override def clearCache(tenantId: TenantId): Future[Unit] = {
    Future {
      val jedis = jedisPool.getResource
      try {
        val key = cacheKey(tenantId)
        jedis.del(key)
        println(s"[CACHE CLEAR] Approval rules for tenant ${tenantId.value}")
      } finally {
        jedis.close()
      }
    }
  }

  override def findApplicableRule(tenantId: TenantId, amount: Money): Future[Option[ApprovalRule]] = {
    getApprovalRules(tenantId).map { rules =>
      rules
        .filter(_.matches(amount))
        .sortBy(_.priority)
        .headOption
    }
  }
}

// Redisプールの初期化
object RedisPoolFactory {
  def createPool(host: String = "localhost", port: Int = 6379): JedisPool = {
    val config = new redis.clients.jedis.JedisPoolConfig()
    config.setMaxTotal(128)
    config.setMaxIdle(128)
    config.setMinIdle(16)
    config.setTestOnBorrow(true)
    config.setTestOnReturn(true)
    config.setTestWhileIdle(true)

    new JedisPool(config, host, port)
  }
}
```

### 8.1.3 キャッシュを統合した承認サービス

キャッシュを使った承認サービスの実装です。キャッシュミス時はデータベースから取得し、キャッシュに保存します。

```scala
package com.example.procurement.application.approval

import com.example.shared.domain.*
import com.example.procurement.domain.purchaseorder.*
import scala.concurrent.{ExecutionContext, Future}

// 承認ルールリポジトリ
trait ApprovalRuleRepository {
  def findByTenant(tenantId: TenantId): Future[List[ApprovalRule]]
  def save(rule: ApprovalRule): Future[Unit]
  def deleteByTenant(tenantId: TenantId): Future[Unit]
}

// キャッシュを統合した承認サービス
class CachedApprovalService(
  cache: ApprovalRuleCache,
  repository: ApprovalRuleRepository
)(implicit ec: ExecutionContext) {

  // 承認ルールを取得（キャッシュ優先）
  def getApprovalRules(tenantId: TenantId): Future[List[ApprovalRule]] = {
    cache.getApprovalRules(tenantId).flatMap { cachedRules =>
      if (cachedRules.nonEmpty) {
        // キャッシュヒット
        Future.successful(cachedRules)
      } else {
        // キャッシュミス：データベースから取得してキャッシュに保存
        repository.findByTenant(tenantId).flatMap { dbRules =>
          if (dbRules.nonEmpty) {
            cache.setApprovalRules(tenantId, dbRules).map(_ => dbRules)
          } else {
            // デフォルトルールを使用
            val defaultRules = ApprovalRule.defaults
            cache.setApprovalRules(tenantId, defaultRules).map(_ => defaultRules)
          }
        }
      }
    }
  }

  // 金額に対する必要な承認者ロールを取得
  def getRequiredApproverRole(tenantId: TenantId, amount: Money): Future[ApproverRole] = {
    cache.findApplicableRule(tenantId, amount).flatMap {
      case Some(rule) =>
        Future.successful(rule.requiredRole)

      case None =>
        // キャッシュミス：データベースから取得
        getApprovalRules(tenantId).map { rules =>
          rules
            .filter(_.matches(amount))
            .sortBy(_.priority)
            .headOption
            .map(_.requiredRole)
            .getOrElse(ApproverRole.Manager) // デフォルト
        }
    }
  }

  // 承認ルールを更新（データベースとキャッシュの両方）
  def updateApprovalRules(tenantId: TenantId, rules: List[ApprovalRule]): Future[Unit] = {
    for {
      // まずデータベースを更新
      _ <- repository.deleteByTenant(tenantId)
      _ <- Future.traverse(rules)(repository.save)
      // 次にキャッシュをクリア
      _ <- cache.clearCache(tenantId)
      // 新しいルールをキャッシュに保存
      _ <- cache.setApprovalRules(tenantId, rules)
    } yield ()
  }

  // 組織構造変更時のキャッシュ更新
  def refreshCacheForOrganizationChange(tenantId: TenantId): Future[Unit] = {
    for {
      _ <- cache.clearCache(tenantId)
      rules <- repository.findByTenant(tenantId)
      _ <- cache.setApprovalRules(tenantId, rules)
    } yield ()
  }
}
```

### 8.1.4 パフォーマンス測定

キャッシュの効果を測定するメトリクス収集機能を実装します。

```scala
package com.example.procurement.monitoring

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.*

// キャッシュメトリクス
class CacheMetrics {
  private val hits = new AtomicLong(0)
  private val misses = new AtomicLong(0)
  private val errors = new AtomicLong(0)
  private val totalResponseTime = new AtomicLong(0) // ナノ秒
  private val requests = new AtomicLong(0)

  def recordHit(responseTimeNanos: Long): Unit = {
    hits.incrementAndGet()
    totalResponseTime.addAndGet(responseTimeNanos)
    requests.incrementAndGet()
  }

  def recordMiss(responseTimeNanos: Long): Unit = {
    misses.incrementAndGet()
    totalResponseTime.addAndGet(responseTimeNanos)
    requests.incrementAndGet()
  }

  def recordError(): Unit = {
    errors.incrementAndGet()
  }

  def getHitRate: Double = {
    val total = hits.get() + misses.get()
    if (total == 0) 0.0 else hits.get().toDouble / total.toDouble
  }

  def getAverageResponseTime: Duration = {
    val total = requests.get()
    if (total == 0) Duration.Zero
    else (totalResponseTime.get() / total).nanos
  }

  def getStatistics: CacheStatistics = {
    CacheStatistics(
      hits = hits.get(),
      misses = misses.get(),
      errors = errors.get(),
      hitRate = getHitRate,
      averageResponseTime = getAverageResponseTime
    )
  }

  def reset(): Unit = {
    hits.set(0)
    misses.set(0)
    errors.set(0)
    totalResponseTime.set(0)
    requests.set(0)
  }
}

final case class CacheStatistics(
  hits: Long,
  misses: Long,
  errors: Long,
  hitRate: Double,
  averageResponseTime: Duration
)
```

---

## 8.2 3-way matchingの最適化

### 8.2.1 バッチ処理の設計

月末には大量の請求書が届くため、個別に処理するのではなく、バッチで一括処理します。

```scala
package com.example.procurement.application.payment

import com.example.shared.domain.*
import com.example.procurement.domain.payment.*
import com.example.procurement.domain.purchaseorder.*
import com.example.procurement.domain.receiving.*
import scala.concurrent.{ExecutionContext, Future}
import java.time.{LocalDate, Instant}

// バッチ処理結果
final case class ThreeWayMatchingBatchResult(
  totalProcessed: Int,
  successCount: Int,
  failureCount: Int,
  partialMatchCount: Int,
  results: List[ThreeWayMatchingResult],
  processingTime: scala.concurrent.duration.Duration
)

// 3-way matchingバッチプロセッサ
class ThreeWayMatchingBatchProcessor(
  invoiceRepository: SupplierInvoiceRepository,
  purchaseOrderRepository: PurchaseOrderRepository,
  receivingRepository: ReceivingRepository
)(implicit ec: ExecutionContext) {

  // 月末バッチ処理
  def processMonthEndBatch(
    tenantId: TenantId,
    targetMonth: java.time.YearMonth
  ): Future[ThreeWayMatchingBatchResult] = {
    val startTime = System.nanoTime()

    for {
      // 対象月の未処理請求書を取得
      invoices <- invoiceRepository.findPendingByMonth(tenantId, targetMonth)

      // 並列処理
      results <- Future.traverse(invoices) { invoice =>
        processInvoice(invoice)
      }

      endTime = System.nanoTime()
      processingTime = (endTime - startTime).nanos

    } yield {
      val successCount = results.count(_.isFullMatch)
      val failureCount = results.count(!_.isFullMatch && _.discrepancies.nonEmpty)
      val partialMatchCount = results.count(r => !r.isFullMatch && r.discrepancies.isEmpty)

      ThreeWayMatchingBatchResult(
        totalProcessed = results.length,
        successCount = successCount,
        failureCount = failureCount,
        partialMatchCount = partialMatchCount,
        results = results,
        processingTime = processingTime
      )
    }
  }

  // 個別請求書の処理
  private def processInvoice(invoice: SupplierInvoice): Future[ThreeWayMatchingResult] = {
    for {
      // 発注情報を取得
      purchaseOrder <- purchaseOrderRepository.findById(invoice.purchaseOrderId)

      // 入荷情報を取得
      receiving <- invoice.receivingId match {
        case Some(rid) => receivingRepository.findById(rid)
        case None => Future.successful(None)
      }

    } yield {
      (purchaseOrder, receiving) match {
        case (Some(po), Some(rcv)) =>
          // 3-way matchingを実行
          ThreeWayMatchingResult.perform(po, rcv, invoice)

        case _ =>
          // 必要な情報が見つからない
          ThreeWayMatchingResult(
            purchaseOrderAmount = Money(0),
            receivingAmount = Money(0),
            invoiceAmount = invoice.totalAmount,
            quantityMatches = false,
            amountMatches = false,
            unitPriceMatches = false,
            discrepancies = List("Required documents not found")
          )
      }
    }
  }
}

// 請求書リポジトリ
trait SupplierInvoiceRepository {
  def findPendingByMonth(tenantId: TenantId, month: java.time.YearMonth): Future[List[SupplierInvoice]]
  def findById(id: SupplierInvoiceId): Future[Option[SupplierInvoice]]
  def save(invoice: SupplierInvoice): Future[Unit]
}

// 発注リポジトリ
trait PurchaseOrderRepository {
  def findById(id: PurchaseOrderId): Future[Option[PurchaseOrder]]
}

// 入荷リポジトリ
trait ReceivingRepository {
  def findById(id: ReceivingId): Future[Option[Receiving]]
}
```

### 8.2.2 Pekko Streamsによるストリーム処理

大量の請求書を効率的に処理するために、Pekko Streamsを使ったストリーム処理を実装します。

```scala
package com.example.procurement.application.payment

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.scaladsl.{Source, Sink, Flow}
import org.apache.pekko.stream.{Materializer, Supervision}
import org.apache.pekko.NotUsed
import com.example.shared.domain.*
import com.example.procurement.domain.payment.*
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*

// Pekko Streamsを使った3-way matchingプロセッサ
class StreamingThreeWayMatchingProcessor(
  invoiceRepository: SupplierInvoiceRepository,
  purchaseOrderRepository: PurchaseOrderRepository,
  receivingRepository: ReceivingRepository
)(implicit system: ActorSystem, ec: ExecutionContext) {

  private implicit val mat: Materializer = Materializer(system)

  // ストリーム処理設定
  private val parallelism = 8  // 並列度
  private val bufferSize = 100 // バッファサイズ

  // エラーハンドリング戦略
  private val decider: Supervision.Decider = {
    case _: Exception =>
      println(s"Error in stream, resuming...")
      Supervision.Resume
  }

  // 請求書ストリーム処理
  def processInvoicesStream(
    tenantId: TenantId,
    targetMonth: java.time.YearMonth
  ): Future[ThreeWayMatchingBatchResult] = {
    val startTime = System.nanoTime()

    // 請求書ソース
    val invoiceSource: Source[SupplierInvoice, NotUsed] = Source
      .future(invoiceRepository.findPendingByMonth(tenantId, targetMonth))
      .mapConcat(identity)

    // 3-way matchingフロー
    val matchingFlow: Flow[SupplierInvoice, ThreeWayMatchingResult, NotUsed] = Flow[SupplierInvoice]
      .mapAsyncUnordered(parallelism) { invoice =>
        performMatching(invoice)
      }
      .withAttributes(org.apache.pekko.stream.ActorAttributes.supervisionStrategy(decider))

    // 結果収集シンク
    val resultSink: Sink[ThreeWayMatchingResult, Future[List[ThreeWayMatchingResult]]] =
      Sink.seq[ThreeWayMatchingResult].mapMaterializedValue(_.map(_.toList))

    // ストリームを実行
    invoiceSource
      .via(matchingFlow)
      .runWith(resultSink)
      .map { results =>
        val endTime = System.nanoTime()
        val processingTime = (endTime - startTime).nanos

        ThreeWayMatchingBatchResult(
          totalProcessed = results.length,
          successCount = results.count(_.isFullMatch),
          failureCount = results.count(!_.isFullMatch && _.discrepancies.nonEmpty),
          partialMatchCount = results.count(r => !r.isFullMatch && r.discrepancies.isEmpty),
          results = results,
          processingTime = processingTime
        )
      }
  }

  // バックプレッシャー対応の請求書処理
  def processInvoicesWithBackpressure(
    tenantId: TenantId,
    targetMonth: java.time.YearMonth,
    maxConcurrency: Int = 10
  ): Future[ThreeWayMatchingBatchResult] = {
    val startTime = System.nanoTime()

    val invoiceSource = Source
      .future(invoiceRepository.findPendingByMonth(tenantId, targetMonth))
      .mapConcat(identity)

    // スロットリング（1秒あたり100件まで）
    val throttledFlow = Flow[SupplierInvoice]
      .throttle(100, 1.second)

    // バックプレッシャーを考慮した並列処理
    val matchingFlow = Flow[SupplierInvoice]
      .mapAsync(maxConcurrency) { invoice =>
        performMatching(invoice)
      }

    invoiceSource
      .via(throttledFlow)
      .via(matchingFlow)
      .runWith(Sink.seq[ThreeWayMatchingResult])
      .map { results =>
        val endTime = System.nanoTime()
        val processingTime = (endTime - startTime).nanos

        ThreeWayMatchingBatchResult(
          totalProcessed = results.length,
          successCount = results.count(_.isFullMatch),
          failureCount = results.count(!_.isFullMatch && _.discrepancies.nonEmpty),
          partialMatchCount = results.count(r => !r.isFullMatch && r.discrepancies.isEmpty),
          results = results.toList,
          processingTime = processingTime
        )
      }
  }

  // 進捗レポート付き処理
  def processWithProgress(
    tenantId: TenantId,
    targetMonth: java.time.YearMonth,
    progressCallback: (Int, Int) => Unit // (processed, total) => Unit
  ): Future[ThreeWayMatchingBatchResult] = {
    val startTime = System.nanoTime()

    invoiceRepository.findPendingByMonth(tenantId, targetMonth).flatMap { invoices =>
      val total = invoices.length
      var processed = 0

      val source = Source(invoices)

      val progressFlow = Flow[SupplierInvoice].map { invoice =>
        processed += 1
        progressCallback(processed, total)
        invoice
      }

      val matchingFlow = Flow[SupplierInvoice]
        .mapAsyncUnordered(parallelism) { invoice =>
          performMatching(invoice)
        }

      source
        .via(progressFlow)
        .via(matchingFlow)
        .runWith(Sink.seq[ThreeWayMatchingResult])
        .map { results =>
          val endTime = System.nanoTime()
          val processingTime = (endTime - startTime).nanos

          ThreeWayMatchingBatchResult(
            totalProcessed = results.length,
            successCount = results.count(_.isFullMatch),
            failureCount = results.count(!_.isFullMatch && _.discrepancies.nonEmpty),
            partialMatchCount = results.count(r => !r.isFullMatch && r.discrepancies.isEmpty),
            results = results.toList,
            processingTime = processingTime
          )
        }
    }
  }

  // 3-way matchingを実行
  private def performMatching(invoice: SupplierInvoice): Future[ThreeWayMatchingResult] = {
    for {
      purchaseOrder <- purchaseOrderRepository.findById(invoice.purchaseOrderId)
      receiving <- invoice.receivingId match {
        case Some(rid) => receivingRepository.findById(rid)
        case None => Future.successful(None)
      }
    } yield {
      (purchaseOrder, receiving) match {
        case (Some(po), Some(rcv)) =>
          ThreeWayMatchingResult.perform(po, rcv, invoice)
        case _ =>
          ThreeWayMatchingResult(
            purchaseOrderAmount = Money(0),
            receivingAmount = Money(0),
            invoiceAmount = invoice.totalAmount,
            quantityMatches = false,
            amountMatches = false,
            unitPriceMatches = false,
            discrepancies = List("Required documents not found")
          )
      }
    }
  }
}
```

### 8.2.3 バッチスケジューリング

月末に自動的にバッチ処理を実行するスケジューラーを実装します。

```scala
package com.example.procurement.application.payment

import org.apache.pekko.actor.typed.{ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import scala.concurrent.duration.*
import java.time.{LocalDate, YearMonth}

// バッチスケジューラー
object ThreeWayMatchingBatchScheduler {

  sealed trait Command
  private case object ScheduledRun extends Command
  final case class RunBatch(yearMonth: YearMonth) extends Command

  def apply(
    processor: StreamingThreeWayMatchingProcessor,
    tenantId: com.example.shared.domain.TenantId
  )(implicit system: ActorSystem[_]): Behavior[Command] = {
    Behaviors.setup { context =>
      implicit val ec = context.executionContext

      // 毎月1日の午前2時に前月分を処理（簡易版：起動後1時間ごと）
      context.system.scheduler.scheduleAtFixedRate(
        initialDelay = 1.minute,
        interval = 1.hour
      ) { () =>
        context.self ! ScheduledRun
      }

      Behaviors.receiveMessage {
        case ScheduledRun =>
          // 今日が月初かチェック
          val today = LocalDate.now()
          if (today.getDayOfMonth == 1) {
            val lastMonth = YearMonth.from(today.minusMonths(1))
            context.log.info(s"Starting scheduled 3-way matching batch for $lastMonth")
            context.self ! RunBatch(lastMonth)
          }
          Behaviors.same

        case RunBatch(yearMonth) =>
          context.log.info(s"Running 3-way matching batch for $yearMonth")

          processor.processWithProgress(
            tenantId = tenantId,
            targetMonth = yearMonth,
            progressCallback = { (processed, total) =>
              if (processed % 100 == 0 || processed == total) {
                context.log.info(s"Progress: $processed/$total invoices processed")
              }
            }
          ).onComplete {
            case scala.util.Success(result) =>
              context.log.info(
                s"Batch completed: total=${result.totalProcessed}, " +
                s"success=${result.successCount}, failure=${result.failureCount}, " +
                s"time=${result.processingTime.toSeconds}s"
              )

            case scala.util.Failure(exception) =>
              context.log.error(s"Batch failed", exception)
          }

          Behaviors.same
      }
    }
  }
}
```

---

## 8.3 在庫レベル監視の最適化

### 8.3.1 イベント駆動の在庫レベル更新

在庫変動イベントをリアルタイムで処理し、Read Modelを更新します。

```scala
package com.example.procurement.application.reorder

import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.persistence.query.PersistenceQuery
import org.apache.pekko.persistence.query.scaladsl.EventsByTagQuery
import org.apache.pekko.stream.scaladsl.{RestartSource, Sink}
import com.example.shared.domain.*
import com.example.inventory.domain.inventory.*
import scala.concurrent.duration.*

// 在庫レベルRead Model
final case class InventoryLevelReadModel(
  productId: ProductId,
  warehouseId: WarehouseId,
  quantityOnHand: Quantity,
  quantityReserved: Quantity,
  quantityAvailable: Quantity,
  lastUpdatedAt: java.time.Instant
)

// 在庫レベルRead Modelリポジトリ
trait InventoryLevelReadModelRepository {
  def upsert(model: InventoryLevelReadModel): Unit
  def findByProductAndWarehouse(productId: ProductId, warehouseId: WarehouseId): Option[InventoryLevelReadModel]
  def findBelowMinimumLevel(tenantId: TenantId): List[InventoryLevelReadModel]
}

// 在庫レベル更新プロセッサ
object InventoryLevelUpdateProcessor {

  def apply(
    repository: InventoryLevelReadModelRepository
  )(implicit system: ActorSystem[_]): Behavior[Nothing] = {
    Behaviors.setup { context =>
      implicit val ec = context.executionContext

      context.log.info("Starting Inventory Level Update Processor")

      val readJournal = PersistenceQuery(system)
        .readJournalFor[EventsByTagQuery]("jdbc-read-journal")

      // "inventory-changed"タグでイベントをストリーム
      val source = RestartSource.onFailuresWithBackoff(
        RestartSettings(
          minBackoff = 3.seconds,
          maxBackoff = 30.seconds,
          randomFactor = 0.2
        )
      ) { () =>
        readJournal.eventsByTag("inventory-changed", offset = readJournal.currentOffset)
      }

      // イベントを処理
      source.runWith(Sink.foreach { envelope =>
        envelope.event match {
          case event: InventoryEvent.InventoryIncreased =>
            updateInventoryLevel(event.productId, event.warehouseId, repository, context)

          case event: InventoryEvent.InventoryDecreased =>
            updateInventoryLevel(event.productId, event.warehouseId, repository, context)

          case event: InventoryEvent.InventoryReserved =>
            updateInventoryLevel(event.productId, event.warehouseId, repository, context)

          case event: InventoryEvent.InventoryReleased =>
            updateInventoryLevel(event.productId, event.warehouseId, repository, context)

          case _ => // 他のイベントは無視
        }
      })

      Behaviors.empty
    }
  }

  private def updateInventoryLevel(
    productId: ProductId,
    warehouseId: WarehouseId,
    repository: InventoryLevelReadModelRepository,
    context: org.apache.pekko.actor.typed.scaladsl.ActorContext[Nothing]
  ): Unit = {
    // 実際には在庫集約から最新の状態を取得
    // ここでは簡易的に既存のRead Modelを更新
    repository.findByProductAndWarehouse(productId, warehouseId) match {
      case Some(existing) =>
        // 更新（実際には在庫集約から最新値を取得）
        val updated = existing.copy(
          lastUpdatedAt = java.time.Instant.now()
        )
        repository.upsert(updated)
        context.log.debug(s"Updated inventory level: product=${productId.value}, warehouse=${warehouseId.value}")

      case None =>
        // 新規作成
        val newModel = InventoryLevelReadModel(
          productId = productId,
          warehouseId = warehouseId,
          quantityOnHand = Quantity(0),
          quantityReserved = Quantity(0),
          quantityAvailable = Quantity(0),
          lastUpdatedAt = java.time.Instant.now()
        )
        repository.upsert(newModel)
        context.log.info(s"Created inventory level: product=${productId.value}, warehouse=${warehouseId.value}")
    }
  }
}
```

### 8.3.2 非同期バッチによる発注点判定

発注点判定を非同期バッチで実行し、システム負荷を分散します。

```scala
package com.example.procurement.application.reorder

import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import com.example.shared.domain.*
import com.example.procurement.domain.reorder.*
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

// バッチ発注点チェッカー
object BatchReorderPointChecker {

  sealed trait Command
  private case object ScheduledCheck extends Command
  final case class CheckSpecificProduct(productId: ProductId, warehouseId: WarehouseId) extends Command

  def apply(
    reorderPointRepository: ReorderPointRepository,
    inventoryLevelRepository: InventoryLevelReadModelRepository,
    autoReorderService: ActorRef[AutoReorderService.Command]
  )(implicit system: ActorSystem[_]): Behavior[Command] = {
    Behaviors.setup { context =>
      implicit val ec = context.executionContext

      // 30分ごとに全発注点をチェック
      context.system.scheduler.scheduleAtFixedRate(
        initialDelay = 5.minutes,
        interval = 30.minutes
      ) { () =>
        context.self ! ScheduledCheck
      }

      Behaviors.receiveMessage {
        case ScheduledCheck =>
          context.log.info("Starting scheduled reorder point check")
          performBatchCheck(context, reorderPointRepository, inventoryLevelRepository, autoReorderService)
          Behaviors.same

        case CheckSpecificProduct(productId, warehouseId) =>
          context.log.info(s"Checking specific product: ${productId.value} at warehouse ${warehouseId.value}")
          checkSingleProduct(productId, warehouseId, reorderPointRepository, inventoryLevelRepository, context)
          Behaviors.same
      }
    }
  }

  private def performBatchCheck(
    context: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
    reorderPointRepository: ReorderPointRepository,
    inventoryLevelRepository: InventoryLevelReadModelRepository,
    autoReorderService: ActorRef[AutoReorderService.Command]
  )(implicit ec: ExecutionContext): Unit = {

    Future {
      // 全テナントの発注点を取得（実際にはテナントごとに並列処理）
      val tenantId = TenantId("tenant-001") // 簡易版
      val reorderPoints = reorderPointRepository.findActiveByTenant(tenantId)

      context.log.info(s"Found ${reorderPoints.length} active reorder points")

      // バッチサイズで分割処理（一度に100件ずつ）
      val batchSize = 100
      reorderPoints.grouped(batchSize).foreach { batch =>
        batch.foreach { reorderPoint =>
          checkSingleProduct(
            reorderPoint.productId,
            reorderPoint.warehouseId,
            reorderPointRepository,
            inventoryLevelRepository,
            context
          )
        }

        // バッチ間で少し待機（負荷分散）
        Thread.sleep(100)
      }

      context.log.info(s"Completed batch reorder point check")
    }
  }

  private def checkSingleProduct(
    productId: ProductId,
    warehouseId: WarehouseId,
    reorderPointRepository: ReorderPointRepository,
    inventoryLevelRepository: InventoryLevelReadModelRepository,
    context: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command]
  ): Unit = {

    // 在庫レベルを取得
    inventoryLevelRepository.findByProductAndWarehouse(productId, warehouseId) match {
      case Some(inventoryLevel) =>
        // 発注点を取得
        val reorderPointId = ReorderPointId.generate(
          TenantId("tenant-001"), // 簡易版
          productId,
          warehouseId
        )

        reorderPointRepository.findById(reorderPointId) match {
          case Some(reorderPoint) =>
            // 発注判定
            val shouldReorder = reorderPoint.shouldReorder(
              currentInventory = inventoryLevel.quantityOnHand,
              reservedQuantity = inventoryLevel.quantityReserved,
              incomingQuantity = Quantity(0) // 実際には発注残数量を取得
            )

            if (shouldReorder) {
              context.log.info(
                s"Reorder needed: product=${productId.value}, " +
                s"current=${inventoryLevel.quantityOnHand.value}, " +
                s"minimum=${reorderPoint.minimumLevel.value}"
              )
              // 自動発注サービスに通知（実装は省略）
            }

          case None =>
            context.log.debug(s"No reorder point configured for product=${productId.value}")
        }

      case None =>
        context.log.warn(s"Inventory level not found: product=${productId.value}, warehouse=${warehouseId.value}")
    }
  }
}
```

### 8.3.3 インデックス最適化

在庫レベルクエリのパフォーマンスを向上させるためのインデックス設計です。

```sql
-- 在庫レベルRead Modelテーブル
CREATE TABLE inventory_levels (
  product_id VARCHAR(255) NOT NULL,
  warehouse_id VARCHAR(255) NOT NULL,
  quantity_on_hand INTEGER NOT NULL,
  quantity_reserved INTEGER NOT NULL,
  quantity_available INTEGER NOT NULL,
  last_updated_at TIMESTAMP NOT NULL,
  PRIMARY KEY (product_id, warehouse_id)
);

-- パフォーマンス最適化のためのインデックス
CREATE INDEX idx_inventory_levels_updated ON inventory_levels(last_updated_at DESC);
CREATE INDEX idx_inventory_levels_available ON inventory_levels(quantity_available) WHERE quantity_available < 100;
CREATE INDEX idx_inventory_levels_warehouse ON inventory_levels(warehouse_id);

-- 発注点テーブル
CREATE TABLE reorder_points (
  id VARCHAR(255) PRIMARY KEY,
  tenant_id VARCHAR(255) NOT NULL,
  product_id VARCHAR(255) NOT NULL,
  warehouse_id VARCHAR(255) NOT NULL,
  minimum_level INTEGER NOT NULL,
  reorder_quantity INTEGER NOT NULL,
  supplier_id VARCHAR(255) NOT NULL,
  lead_time_days INTEGER NOT NULL,
  safety_stock_days INTEGER NOT NULL,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  last_reordered_at DATE,
  version INTEGER NOT NULL
);

-- 発注点検索用インデックス
CREATE INDEX idx_reorder_points_tenant ON reorder_points(tenant_id) WHERE is_active = TRUE;
CREATE INDEX idx_reorder_points_product_warehouse ON reorder_points(product_id, warehouse_id);
CREATE INDEX idx_reorder_points_supplier ON reorder_points(supplier_id);

-- 複合インデックス（在庫レベルと発注点のJOIN最適化）
CREATE INDEX idx_inventory_product_warehouse ON inventory_levels(product_id, warehouse_id, quantity_available);
```

---

## 8.4 パフォーマンスモニタリング

### 8.4.1 メトリクス収集

システムのパフォーマンスメトリクスを収集し、ボトルネックを特定します。

```scala
package com.example.procurement.monitoring

import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.concurrent.duration.*

// パフォーマンスメトリクス
class PerformanceMetrics {
  private val commandProcessingTime = new AtomicLong(0) // 累積処理時間（ナノ秒）
  private val commandCount = new AtomicLong(0)
  private val eventCount = new AtomicLong(0)
  private val errorCount = new AtomicLong(0)

  // 最近の処理時間（移動平均用）
  private val recentProcessingTimes = new AtomicReference[List[Long]](List.empty)
  private val maxRecentSamples = 100

  def recordCommandProcessing(processingTimeNanos: Long): Unit = {
    commandProcessingTime.addAndGet(processingTimeNanos)
    commandCount.incrementAndGet()

    // 最近の処理時間を記録
    val current = recentProcessingTimes.get()
    val updated = (processingTimeNanos :: current).take(maxRecentSamples)
    recentProcessingTimes.set(updated)
  }

  def recordEvent(): Unit = {
    eventCount.incrementAndGet()
  }

  def recordError(): Unit = {
    errorCount.incrementAndGet()
  }

  def getAverageCommandProcessingTime: Duration = {
    val count = commandCount.get()
    if (count == 0) Duration.Zero
    else (commandProcessingTime.get() / count).nanos
  }

  def getRecentAverageProcessingTime: Duration = {
    val recent = recentProcessingTimes.get()
    if (recent.isEmpty) Duration.Zero
    else (recent.sum / recent.length).nanos
  }

  def getCommandThroughput(duration: Duration): Double = {
    commandCount.get().toDouble / duration.toSeconds
  }

  def getStatistics: PerformanceStatistics = {
    PerformanceStatistics(
      commandCount = commandCount.get(),
      eventCount = eventCount.get(),
      errorCount = errorCount.get(),
      averageProcessingTime = getAverageCommandProcessingTime,
      recentAverageProcessingTime = getRecentAverageProcessingTime
    )
  }
}

final case class PerformanceStatistics(
  commandCount: Long,
  eventCount: Long,
  errorCount: Long,
  averageProcessingTime: Duration,
  recentAverageProcessingTime: Duration
)
```

---

## まとめ

本章では、発注管理システムの実運用に必要なパフォーマンス最適化を実装しました。

### 実装した内容

1. **発注承認の高速化**
   - Redisによる承認ルールのキャッシング
   - キャッシュミス時のフォールバック処理
   - 組織構造変更時のキャッシュ更新
   - キャッシュヒット率とレスポンスタイムの測定

2. **3-way matchingの最適化**
   - 月末バッチ処理の実装
   - Pekko Streamsによるストリーム処理
   - バックプレッシャー対応
   - スロットリングによる負荷制御
   - 進捗レポート機能
   - 自動スケジューリング

3. **在庫レベル監視の最適化**
   - イベント駆動によるRead Modelのリアルタイム更新
   - 非同期バッチによる発注点判定
   - バッチサイズと実行間隔の最適化
   - データベースインデックスの最適化

4. **パフォーマンスモニタリング**
   - コマンド処理時間の測定
   - スループットの計測
   - 移動平均による最近の性能把握

### パフォーマンス改善のポイント

- **キャッシング**: 頻繁にアクセスされる不変データをRedisにキャッシュ
- **ストリーム処理**: 大量データをメモリ効率的に処理
- **非同期処理**: 長時間実行タスクをバックグラウンドで実行
- **バッチ処理**: 同種の処理をまとめて実行し、オーバーヘッドを削減
- **インデックス最適化**: クエリパフォーマンスを向上

### 次章の予告

次章では、発注管理システムの運用とモニタリングを実装します。ビジネスメトリクスの収集、Sagaの監視、仕入先評価など、実運用に必要な監視機能を構築します。
