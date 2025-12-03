# 第7部第10章：パフォーマンス最適化

## 本章の目的

マスターデータ管理サービスは、在庫管理、受注管理、発注管理、会計といった全てのBounded Contextから頻繁にアクセスされるため、高いパフォーマンスが要求されます。本章では、キャッシング戦略、インデックス最適化、Materialized Viewの活用により、5,000商品・月間800件のマスター変更を高速に処理できるシステムを構築します。

## 10.1 キャッシング戦略

### 10.1.1 多層キャッシュアーキテクチャ

マスターデータは頻繁に参照されるため、3層のキャッシュアーキテクチャを採用します。

```
┌──────────────────────────────────┐
│     アプリケーション              │
└────────────┬─────────────────────┘
             │
             ▼
┌──────────────────────────────────┐
│ レベル1: インメモリキャッシュ     │
│         (Caffeine)                │
│  - 最大1,000エントリ              │
│  - TTL: 5分                       │
│  - ヒット率: 80%                  │
└────────────┬─────────────────────┘
             │ キャッシュミス
             ▼
┌──────────────────────────────────┐
│ レベル2: 分散キャッシュ           │
│         (Redis)                   │
│  - TTL: 1時間                     │
│  - ヒット率: 95%                  │
└────────────┬─────────────────────┘
             │ キャッシュミス
             ▼
┌──────────────────────────────────┐
│ レベル3: データベース             │
│         (PostgreSQL)              │
│  - マスターデータ                 │
│  - すべてのデータ                 │
└──────────────────────────────────┘
```

### 10.1.2 インメモリキャッシュ実装（Caffeine）

```scala
package com.example.masterdata.infrastructure.cache

import com.github.benmanes.caffeine.cache.{Cache, Caffeine, RemovalCause, RemovalListener}
import scala.concurrent.duration._
import scala.jdk.DurationConverters._
import java.util.concurrent.TimeUnit

/**
 * インメモリキャッシュサービス（Caffeine）
 */
class InMemoryCacheService {

  // 商品キャッシュ
  private val productCache: Cache[String, ProductView] = Caffeine.newBuilder()
    .maximumSize(1000)
    .expireAfterWrite(5.minutes.toJava)
    .recordStats() // 統計情報を記録
    .removalListener(new RemovalListener[String, ProductView] {
      override def onRemoval(key: String, value: ProductView, cause: RemovalCause): Unit = {
        println(s"商品キャッシュから削除: $key, 理由: $cause")
      }
    })
    .build()

  // 勘定科目キャッシュ
  private val accountSubjectCache: Cache[String, AccountSubjectView] = Caffeine.newBuilder()
    .maximumSize(500)
    .expireAfterWrite(10.minutes.toJava)
    .recordStats()
    .build()

  // コードマスターキャッシュ（変更頻度が低いため、TTLを長く）
  private val codeMasterCache: Cache[String, CodeMasterView] = Caffeine.newBuilder()
    .maximumSize(200)
    .expireAfterWrite(30.minutes.toJava)
    .recordStats()
    .build()

  /**
   * 商品取得（キャッシュ優先）
   */
  def getProduct(productId: String): Option[ProductView] = {
    Option(productCache.getIfPresent(productId))
  }

  /**
   * 商品をキャッシュに保存
   */
  def putProduct(productId: String, product: ProductView): Unit = {
    productCache.put(productId, product)
  }

  /**
   * 商品キャッシュを削除
   */
  def invalidateProduct(productId: String): Unit = {
    productCache.invalidate(productId)
  }

  /**
   * 勘定科目取得
   */
  def getAccountSubject(accountSubjectId: String): Option[AccountSubjectView] = {
    Option(accountSubjectCache.getIfPresent(accountSubjectId))
  }

  /**
   * 勘定科目をキャッシュに保存
   */
  def putAccountSubject(accountSubjectId: String, accountSubject: AccountSubjectView): Unit = {
    accountSubjectCache.put(accountSubjectId, accountSubject)
  }

  /**
   * 勘定科目キャッシュを削除
   */
  def invalidateAccountSubject(accountSubjectId: String): Unit = {
    accountSubjectCache.invalidate(accountSubjectId)
  }

  /**
   * キャッシュ統計情報を取得
   */
  def getProductCacheStats(): CacheStats = {
    val stats = productCache.stats()
    CacheStats(
      hitCount = stats.hitCount(),
      missCount = stats.missCount(),
      hitRate = stats.hitRate(),
      evictionCount = stats.evictionCount(),
      loadSuccessCount = stats.loadSuccessCount(),
      loadFailureCount = stats.loadFailureCount()
    )
  }

  def getAccountSubjectCacheStats(): CacheStats = {
    val stats = accountSubjectCache.stats()
    CacheStats(
      hitCount = stats.hitCount(),
      missCount = stats.missCount(),
      hitRate = stats.hitRate(),
      evictionCount = stats.evictionCount(),
      loadSuccessCount = stats.loadSuccessCount(),
      loadFailureCount = stats.loadFailureCount()
    )
  }
}

case class CacheStats(
  hitCount: Long,
  missCount: Long,
  hitRate: Double,
  evictionCount: Long,
  loadSuccessCount: Long,
  loadFailureCount: Long
)
```

### 10.1.3 分散キャッシュ実装（Redis）

```scala
package com.example.masterdata.infrastructure.cache

import redis.clients.jedis.{Jedis, JedisPool, JedisPoolConfig}
import io.circe.{Encoder, Decoder}
import io.circe.syntax._
import io.circe.parser._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Try, Success, Failure}

/**
 * 分散キャッシュサービス（Redis）
 */
class DistributedCacheService(jedisPool: JedisPool)(implicit ec: ExecutionContext) {

  private val productCacheTTL = 3600 // 1時間
  private val accountSubjectCacheTTL = 7200 // 2時間
  private val codeMasterCacheTTL = 86400 // 24時間

  /**
   * 商品取得
   */
  def getProduct(productId: String)(implicit decoder: Decoder[ProductView]): Future[Option[ProductView]] = {
    Future {
      withJedis { jedis =>
        Option(jedis.get(productKey(productId))).flatMap { json =>
          decode[ProductView](json).toOption
        }
      }
    }
  }

  /**
   * 商品保存
   */
  def putProduct(productId: String, product: ProductView)(implicit encoder: Encoder[ProductView]): Future[Unit] = {
    Future {
      withJedis { jedis =>
        val json = product.asJson.noSpaces
        jedis.setex(productKey(productId), productCacheTTL, json)
      }
    }
  }

  /**
   * 商品削除
   */
  def deleteProduct(productId: String): Future[Unit] = {
    Future {
      withJedis { jedis =>
        jedis.del(productKey(productId))
      }
    }
  }

  /**
   * 商品一括取得（パイプライン使用）
   */
  def getProducts(productIds: List[String])(implicit decoder: Decoder[ProductView]): Future[Map[String, ProductView]] = {
    Future {
      withJedis { jedis =>
        val pipeline = jedis.pipelined()
        val responses = productIds.map { productId =>
          productId -> pipeline.get(productKey(productId))
        }
        pipeline.sync()

        responses.flatMap { case (productId, response) =>
          Option(response.get()).flatMap { json =>
            decode[ProductView](json).toOption.map(productId -> _)
          }
        }.toMap
      }
    }
  }

  /**
   * 勘定科目取得
   */
  def getAccountSubject(accountSubjectId: String)(implicit decoder: Decoder[AccountSubjectView]): Future[Option[AccountSubjectView]] = {
    Future {
      withJedis { jedis =>
        Option(jedis.get(accountSubjectKey(accountSubjectId))).flatMap { json =>
          decode[AccountSubjectView](json).toOption
        }
      }
    }
  }

  /**
   * 勘定科目保存
   */
  def putAccountSubject(accountSubjectId: String, accountSubject: AccountSubjectView)(implicit encoder: Encoder[AccountSubjectView]): Future[Unit] = {
    Future {
      withJedis { jedis =>
        val json = accountSubject.asJson.noSpaces
        jedis.setex(accountSubjectKey(accountSubjectId), accountSubjectCacheTTL, json)
      }
    }
  }

  /**
   * 勘定科目削除
   */
  def deleteAccountSubject(accountSubjectId: String): Future[Unit] = {
    Future {
      withJedis { jedis =>
        jedis.del(accountSubjectKey(accountSubjectId))
      }
    }
  }

  /**
   * パターンマッチによる一括削除（商品カテゴリー変更時など）
   */
  def deleteByPattern(pattern: String): Future[Int] = {
    Future {
      withJedis { jedis =>
        val keys = jedis.keys(pattern)
        if (keys.isEmpty) {
          0
        } else {
          jedis.del(keys.toArray: _*).toInt
        }
      }
    }
  }

  /**
   * キャッシュ統計情報を取得
   */
  def getCacheInfo(): Future[Map[String, String]] = {
    Future {
      withJedis { jedis =>
        val info = jedis.info("stats")
        info.split("\n").flatMap { line =>
          line.split(":") match {
            case Array(key, value) => Some(key.trim -> value.trim)
            case _ => None
          }
        }.toMap
      }
    }
  }

  private def productKey(productId: String): String = s"product:$productId"
  private def accountSubjectKey(accountSubjectId: String): String = s"account_subject:$accountSubjectId"

  private def withJedis[A](f: Jedis => A): A = {
    val jedis = jedisPool.getResource
    try {
      f(jedis)
    } finally {
      jedis.close()
    }
  }
}

/**
 * Redisコネクションプール設定
 */
object RedisConnectionPool {

  def createPool(host: String, port: Int, maxTotal: Int = 128, maxIdle: Int = 64): JedisPool = {
    val config = new JedisPoolConfig()
    config.setMaxTotal(maxTotal)
    config.setMaxIdle(maxIdle)
    config.setMinIdle(16)
    config.setTestOnBorrow(true)
    config.setTestOnReturn(true)
    config.setTestWhileIdle(true)
    config.setMinEvictableIdleTimeMillis(60000)
    config.setTimeBetweenEvictionRunsMillis(30000)
    config.setNumTestsPerEvictionRun(-1)

    new JedisPool(config, host, port)
  }
}
```

### 10.1.4 統合キャッシュサービス

3層のキャッシュを統合して、透過的にアクセスできるサービスを実装します。

```scala
package com.example.masterdata.infrastructure.cache

import scala.concurrent.{ExecutionContext, Future}
import io.circe.{Encoder, Decoder}

/**
 * 統合キャッシュサービス（3層キャッシュ）
 */
class MultiLevelCacheService(
  inMemoryCache: InMemoryCacheService,
  distributedCache: DistributedCacheService,
  productRepository: ProductQueryRepository
)(implicit ec: ExecutionContext) {

  /**
   * 商品取得（3層キャッシュ）
   */
  def getProduct(productId: String)(
    implicit encoder: Encoder[ProductView],
    decoder: Decoder[ProductView]
  ): Future[Option[ProductView]] = {
    // レベル1: インメモリキャッシュ
    inMemoryCache.getProduct(productId) match {
      case Some(product) =>
        // キャッシュヒット
        Future.successful(Some(product))

      case None =>
        // レベル2: 分散キャッシュ
        distributedCache.getProduct(productId).flatMap {
          case Some(product) =>
            // Redisからヒット → レベル1に保存
            inMemoryCache.putProduct(productId, product)
            Future.successful(Some(product))

          case None =>
            // レベル3: データベース
            productRepository.findById(productId).map {
              case Some(product) =>
                // データベースからヒット → レベル1・2に保存
                inMemoryCache.putProduct(productId, product)
                distributedCache.putProduct(productId, product)
                Some(product)

              case None =>
                None
            }
        }
    }
  }

  /**
   * 商品保存（3層すべてに保存）
   */
  def putProduct(productId: String, product: ProductView)(
    implicit encoder: Encoder[ProductView]
  ): Future[Unit] = {
    inMemoryCache.putProduct(productId, product)
    distributedCache.putProduct(productId, product)
  }

  /**
   * 商品キャッシュ無効化（3層すべてから削除）
   */
  def invalidateProduct(productId: String): Future[Unit] = {
    inMemoryCache.invalidateProduct(productId)
    distributedCache.deleteProduct(productId)
  }

  /**
   * 勘定科目取得（3層キャッシュ）
   */
  def getAccountSubject(accountSubjectId: String)(
    implicit encoder: Encoder[AccountSubjectView],
    decoder: Decoder[AccountSubjectView]
  ): Future[Option[AccountSubjectView]] = {
    inMemoryCache.getAccountSubject(accountSubjectId) match {
      case Some(accountSubject) =>
        Future.successful(Some(accountSubject))

      case None =>
        distributedCache.getAccountSubject(accountSubjectId).flatMap {
          case Some(accountSubject) =>
            inMemoryCache.putAccountSubject(accountSubjectId, accountSubject)
            Future.successful(Some(accountSubject))

          case None =>
            accountSubjectRepository.findById(accountSubjectId).map {
              case Some(accountSubject) =>
                inMemoryCache.putAccountSubject(accountSubjectId, accountSubject)
                distributedCache.putAccountSubject(accountSubjectId, accountSubject)
                Some(accountSubject)

              case None =>
                None
            }
        }
    }
  }

  /**
   * 勘定科目キャッシュ無効化
   */
  def invalidateAccountSubject(accountSubjectId: String): Future[Unit] = {
    inMemoryCache.invalidateAccountSubject(accountSubjectId)
    distributedCache.deleteAccountSubject(accountSubjectId)
  }
}
```

### 10.1.5 イベント受信時のキャッシュ無効化

マスターデータ変更イベントを受信したら、即座にキャッシュを無効化します。

```scala
package com.example.masterdata.infrastructure.integration

import org.apache.pekko.actor.typed.{ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import com.example.masterdata.domain.event.ProductEvents._
import com.example.masterdata.infrastructure.cache.MultiLevelCacheService

/**
 * キャッシュ無効化ハンドラー
 */
object CacheInvalidationHandler {

  def apply(cacheService: MultiLevelCacheService): Behavior[MasterDataEvent] = {
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case event: ProductCreated =>
          context.log.info(s"商品作成イベント受信、キャッシュ無効化: ${event.productId.value}")
          cacheService.invalidateProduct(event.productId.value)
          Behaviors.same

        case event: ProductInfoUpdated =>
          context.log.info(s"商品情報更新イベント受信、キャッシュ無効化: ${event.productId.value}")
          cacheService.invalidateProduct(event.productId.value)
          Behaviors.same

        case event: ProductPriceChanged =>
          context.log.info(s"商品価格変更イベント受信、キャッシュ無効化: ${event.productId.value}")
          cacheService.invalidateProduct(event.productId.value)
          Behaviors.same

        case event: ProductSuspended =>
          context.log.info(s"商品停止イベント受信、キャッシュ無効化: ${event.productId.value}")
          cacheService.invalidateProduct(event.productId.value)
          Behaviors.same

        case event: ProductReactivated =>
          context.log.info(s"商品再開イベント受信、キャッシュ無効化: ${event.productId.value}")
          cacheService.invalidateProduct(event.productId.value)
          Behaviors.same

        case event: ProductObsoleted =>
          context.log.info(s"商品廃止イベント受信、キャッシュ無効化: ${event.productId.value}")
          cacheService.invalidateProduct(event.productId.value)
          Behaviors.same

        case _ =>
          Behaviors.same
      }
    }
  }
}
```

## 10.2 インデックス最適化

### 10.2.1 商品テーブルのインデックス

```sql
-- ============================================
-- 商品テーブルのインデックス最適化
-- ============================================

-- 商品コード検索用（ユニークインデックス）
-- 使用クエリ: SELECT * FROM products WHERE product_code = 'P-001'
CREATE UNIQUE INDEX idx_products_code
  ON master_data_management.products(product_code);

-- 商品名検索用（部分一致検索対応）
-- 使用クエリ: SELECT * FROM products WHERE product_name LIKE '%玄米%'
-- PostgreSQLの全文検索インデックス
CREATE INDEX idx_products_name_fulltext
  ON master_data_management.products
  USING gin(to_tsvector('japanese', product_name));

-- 商品名の通常インデックス（前方一致検索用）
CREATE INDEX idx_products_name_prefix
  ON master_data_management.products(product_name varchar_pattern_ops);

-- カテゴリー別検索用
-- 使用クエリ: SELECT * FROM products WHERE category_code = 'RICE' AND status = 'Active'
CREATE INDEX idx_products_category_status
  ON master_data_management.products(category_code, status);

-- 有効な商品の検索用（部分インデックス）
-- 使用クエリ: SELECT * FROM products WHERE status = 'Active' AND valid_from <= CURRENT_DATE AND (valid_to IS NULL OR valid_to >= CURRENT_DATE)
CREATE INDEX idx_products_valid_active
  ON master_data_management.products(valid_from, valid_to)
  WHERE status = 'Active';

-- ステータス別検索用
-- 使用クエリ: SELECT * FROM products WHERE status = 'Active'
CREATE INDEX idx_products_status
  ON master_data_management.products(status);

-- 複合検索用（カテゴリー + ステータス + 有効期間）
CREATE INDEX idx_products_category_status_valid
  ON master_data_management.products(category_code, status, valid_from, valid_to);

-- 主要仕入先別検索用
-- 使用クエリ: SELECT * FROM products WHERE primary_supplier_id = 'SUP-001'
CREATE INDEX idx_products_supplier
  ON master_data_management.products(primary_supplier_id)
  WHERE primary_supplier_id IS NOT NULL;

-- 更新日時順ソート用
-- 使用クエリ: SELECT * FROM products ORDER BY updated_at DESC
CREATE INDEX idx_products_updated_at
  ON master_data_management.products(updated_at DESC);
```

### 10.2.2 商品価格テーブルのインデックス

```sql
-- ============================================
-- 商品価格テーブルのインデックス最適化
-- ============================================

-- 商品別価格検索用
-- 使用クエリ: SELECT * FROM product_prices WHERE product_id = 'PROD-001'
CREATE INDEX idx_product_prices_product
  ON master_data_management.product_prices(product_id);

-- 商品+価格タイプ検索用
-- 使用クエリ: SELECT * FROM product_prices WHERE product_id = 'PROD-001' AND price_type = 'Special'
CREATE INDEX idx_product_prices_product_type
  ON master_data_management.product_prices(product_id, price_type);

-- 商品+顧客検索用（特別価格照会）
-- 使用クエリ: SELECT * FROM product_prices WHERE product_id = 'PROD-001' AND customer_id = 'CUST-001'
CREATE INDEX idx_product_prices_product_customer
  ON master_data_management.product_prices(product_id, customer_id)
  WHERE customer_id IS NOT NULL;

-- 有効期間検索用
-- 使用クエリ: SELECT * FROM product_prices WHERE product_id = 'PROD-001' AND valid_from <= '2024-01-01' AND (valid_to IS NULL OR valid_to >= '2024-01-01')
CREATE INDEX idx_product_prices_valid
  ON master_data_management.product_prices(product_id, valid_from, valid_to);

-- 複合検索用（商品+顧客+有効期間）
CREATE INDEX idx_product_prices_full
  ON master_data_management.product_prices(product_id, customer_id, valid_from, valid_to, price_type);
```

### 10.2.3 勘定科目テーブルのインデックス

```sql
-- ============================================
-- 勘定科目テーブルのインデックス最適化
-- ============================================

-- 勘定科目コード検索用（ユニークインデックス）
-- 使用クエリ: SELECT * FROM account_subjects WHERE account_subject_code = '1000'
CREATE UNIQUE INDEX idx_account_subjects_code
  ON master_data_management.account_subjects(account_subject_code);

-- 階層構造検索用（親子関係）
-- 使用クエリ: SELECT * FROM account_subjects WHERE parent_account_subject_id = 'AS-001'
CREATE INDEX idx_account_subjects_parent
  ON master_data_management.account_subjects(parent_account_subject_id)
  WHERE status = 'Active';

-- 勘定科目種別検索用
-- 使用クエリ: SELECT * FROM account_subjects WHERE account_type = 'Asset' AND status = 'Active'
CREATE INDEX idx_account_subjects_type_status
  ON master_data_management.account_subjects(account_type, status);

-- 階層レベル検索用
-- 使用クエリ: SELECT * FROM account_subjects WHERE account_type = 'Asset' AND level = 1
CREATE INDEX idx_account_subjects_type_level
  ON master_data_management.account_subjects(account_type, level);

-- 有効な勘定科目の検索用
CREATE INDEX idx_account_subjects_valid_active
  ON master_data_management.account_subjects(valid_from, valid_to)
  WHERE status = 'Active';
```

### 10.2.4 変更申請テーブルのインデックス

```sql
-- ============================================
-- 変更申請テーブルのインデックス最適化
-- ============================================

-- ステータス検索用
-- 使用クエリ: SELECT * FROM change_requests WHERE status = 'Pending'
CREATE INDEX idx_change_requests_status
  ON master_data_management.change_requests(status);

-- 集約別検索用
-- 使用クエリ: SELECT * FROM change_requests WHERE aggregate_type = 'Product' AND aggregate_id = 'PROD-001'
CREATE INDEX idx_change_requests_aggregate
  ON master_data_management.change_requests(aggregate_type, aggregate_id);

-- 申請者検索用
-- 使用クエリ: SELECT * FROM change_requests WHERE requester_user_id = 'user001'
CREATE INDEX idx_change_requests_requester
  ON master_data_management.change_requests(requester_user_id);

-- 承認者検索用
-- 使用クエリ: SELECT * FROM change_requests WHERE approver_user_id = 'mgr001' AND status = 'Pending'
CREATE INDEX idx_change_requests_approver_status
  ON master_data_management.change_requests(approver_user_id, status)
  WHERE approver_user_id IS NOT NULL;

-- 申請日時順ソート用
-- 使用クエリ: SELECT * FROM change_requests ORDER BY requested_at DESC
CREATE INDEX idx_change_requests_requested_at
  ON master_data_management.change_requests(requested_at DESC);

-- 複合検索用（ステータス + 申請日時）
CREATE INDEX idx_change_requests_status_requested
  ON master_data_management.change_requests(status, requested_at DESC);
```

### 10.2.5 インデックス効果の確認

インデックスが実際に使用されているか、EXPLAIN ANALYZEで確認します。

```sql
-- インデックス使用確認: 商品コード検索
EXPLAIN ANALYZE
SELECT * FROM master_data_management.products
WHERE product_code = 'P-001';

-- 期待される実行計画:
-- Index Scan using idx_products_code on products (cost=0.28..8.29 rows=1 width=...)

-- インデックス使用確認: 有効な商品検索
EXPLAIN ANALYZE
SELECT * FROM master_data_management.products
WHERE status = 'Active'
  AND valid_from <= CURRENT_DATE
  AND (valid_to IS NULL OR valid_to >= CURRENT_DATE);

-- 期待される実行計画:
-- Index Scan using idx_products_valid_active on products (cost=0.28..100.50 rows=50 width=...)

-- インデックス使用確認: 商品名全文検索
EXPLAIN ANALYZE
SELECT * FROM master_data_management.products
WHERE to_tsvector('japanese', product_name) @@ to_tsquery('japanese', '玄米');

-- 期待される実行計画:
-- Bitmap Heap Scan on products (cost=12.25..50.75 rows=10 width=...)
--   Recheck Cond: (to_tsvector('japanese'::regconfig, product_name) @@ '''玄米'''::tsquery)
--   -> Bitmap Index Scan on idx_products_name_fulltext (cost=0.00..12.24 rows=10 width=0)

-- インデックス統計情報の確認
SELECT
  schemaname,
  tablename,
  indexname,
  idx_scan,
  idx_tup_read,
  idx_tup_fetch
FROM pg_stat_user_indexes
WHERE schemaname = 'master_data_management'
ORDER BY idx_scan DESC;

-- 未使用インデックスの確認
SELECT
  schemaname,
  tablename,
  indexname,
  idx_scan
FROM pg_stat_user_indexes
WHERE schemaname = 'master_data_management'
  AND idx_scan = 0
  AND indexname NOT LIKE '%_pkey';
```

## 10.3 Materialized Viewの活用

### 10.3.1 有効商品と価格のMaterialized View

頻繁にアクセスされる「有効な商品と価格」を事前計算してMaterialized Viewに保存します。

```sql
-- ============================================
-- 有効商品と価格のMaterialized View
-- ============================================

CREATE MATERIALIZED VIEW master_data_management.mv_active_products_with_prices AS
SELECT
  p.product_id,
  p.product_code,
  p.product_name,
  p.category_code,
  p.unit_of_measure,
  p.standard_cost,
  p.list_price,
  p.primary_supplier_id,
  p.lead_time_days,
  p.minimum_order_quantity,
  p.status,
  p.valid_from,
  p.valid_to,
  p.version,
  p.created_at,
  p.updated_at,
  pp.price_id,
  pp.price_type,
  pp.unit_price as special_price,
  pp.customer_id,
  pp.valid_from as price_valid_from,
  pp.valid_to as price_valid_to
FROM master_data_management.products p
LEFT JOIN master_data_management.product_prices pp
  ON p.product_id = pp.product_id
  AND pp.valid_from <= CURRENT_DATE
  AND (pp.valid_to IS NULL OR pp.valid_to >= CURRENT_DATE)
WHERE p.status = 'Active'
  AND p.valid_from <= CURRENT_DATE
  AND (p.valid_to IS NULL OR p.valid_to >= CURRENT_DATE);

-- Materialized Viewのインデックス
CREATE UNIQUE INDEX idx_mv_products_id_price
  ON master_data_management.mv_active_products_with_prices(product_id, COALESCE(price_id, '00000000-0000-0000-0000-000000000000'));

CREATE INDEX idx_mv_products_code
  ON master_data_management.mv_active_products_with_prices(product_code);

CREATE INDEX idx_mv_products_category
  ON master_data_management.mv_active_products_with_prices(category_code);

CREATE INDEX idx_mv_products_name
  ON master_data_management.mv_active_products_with_prices
  USING gin(to_tsvector('japanese', product_name));

CREATE INDEX idx_mv_products_customer
  ON master_data_management.mv_active_products_with_prices(customer_id)
  WHERE customer_id IS NOT NULL;

-- Materialized Viewのリフレッシュ（並行実行可能）
REFRESH MATERIALIZED VIEW CONCURRENTLY master_data_management.mv_active_products_with_prices;
```

### 10.3.2 勘定科目階層のMaterialized View

勘定科目の階層構造を事前計算して、高速に取得できるようにします。

```sql
-- ============================================
-- 勘定科目階層のMaterialized View
-- ============================================

CREATE MATERIALIZED VIEW master_data_management.mv_account_subject_hierarchy AS
WITH RECURSIVE account_hierarchy AS (
  -- 最上位の勘定科目（レベル1）
  SELECT
    account_subject_id,
    account_subject_code,
    account_name,
    account_type,
    parent_account_subject_id,
    1 as level,
    ARRAY[account_subject_code] as path,
    account_subject_code as root_code,
    status,
    valid_from,
    valid_to
  FROM master_data_management.account_subjects
  WHERE parent_account_subject_id IS NULL

  UNION ALL

  -- 子孫の勘定科目
  SELECT
    as2.account_subject_id,
    as2.account_subject_code,
    as2.account_name,
    as2.account_type,
    as2.parent_account_subject_id,
    ah.level + 1 as level,
    ah.path || as2.account_subject_code as path,
    ah.root_code,
    as2.status,
    as2.valid_from,
    as2.valid_to
  FROM master_data_management.account_subjects as2
  INNER JOIN account_hierarchy ah
    ON as2.parent_account_subject_id = ah.account_subject_id
)
SELECT
  account_subject_id,
  account_subject_code,
  account_name,
  account_type,
  parent_account_subject_id,
  level,
  path,
  root_code,
  status,
  valid_from,
  valid_to
FROM account_hierarchy;

-- Materialized Viewのインデックス
CREATE UNIQUE INDEX idx_mv_account_hierarchy_id
  ON master_data_management.mv_account_subject_hierarchy(account_subject_id);

CREATE INDEX idx_mv_account_hierarchy_code
  ON master_data_management.mv_account_subject_hierarchy(account_subject_code);

CREATE INDEX idx_mv_account_hierarchy_parent
  ON master_data_management.mv_account_subject_hierarchy(parent_account_subject_id)
  WHERE parent_account_subject_id IS NOT NULL;

CREATE INDEX idx_mv_account_hierarchy_root
  ON master_data_management.mv_account_subject_hierarchy(root_code);

CREATE INDEX idx_mv_account_hierarchy_type_level
  ON master_data_management.mv_account_subject_hierarchy(account_type, level);

-- Materialized Viewのリフレッシュ
REFRESH MATERIALIZED VIEW CONCURRENTLY master_data_management.mv_account_subject_hierarchy;
```

### 10.3.3 自動リフレッシュの実装

Materialized Viewを定期的に自動リフレッシュする仕組みを実装します。

```scala
package com.example.masterdata.infrastructure.view

import org.apache.pekko.actor.typed.{ActorSystem, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import slick.jdbc.PostgresProfile.api._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

/**
 * Materialized View自動リフレッシュサービス
 */
object MaterializedViewRefreshService {

  sealed trait Command
  case object RefreshAllViews extends Command
  case object RefreshProductView extends Command
  case object RefreshAccountSubjectView extends Command
  private case object ScheduledRefresh extends Command

  def apply(db: Database)(implicit ec: ExecutionContext): Behavior[Command] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        // 5分ごとに自動リフレッシュ
        timers.startTimerWithFixedDelay(ScheduledRefresh, 5.minutes)

        Behaviors.receiveMessage {
          case RefreshAllViews =>
            context.log.info("全Materialized Viewをリフレッシュ開始")
            refreshAllViews(db, context)
            Behaviors.same

          case RefreshProductView =>
            context.log.info("商品Materialized Viewをリフレッシュ開始")
            refreshProductView(db, context)
            Behaviors.same

          case RefreshAccountSubjectView =>
            context.log.info("勘定科目Materialized Viewをリフレッシュ開始")
            refreshAccountSubjectView(db, context)
            Behaviors.same

          case ScheduledRefresh =>
            context.log.debug("定期リフレッシュ実行")
            refreshAllViews(db, context)
            Behaviors.same
        }
      }
    }
  }

  private def refreshAllViews(db: Database, context: ActorContext[Command])(implicit ec: ExecutionContext): Unit = {
    val startTime = System.currentTimeMillis()

    val refreshFuture = for {
      _ <- refreshProductView(db)
      _ <- refreshAccountSubjectView(db)
    } yield ()

    refreshFuture.onComplete {
      case Success(_) =>
        val duration = System.currentTimeMillis() - startTime
        context.log.info(s"全Materialized Viewのリフレッシュ完了（${duration}ms）")

      case Failure(ex) =>
        context.log.error(s"Materialized Viewのリフレッシュ失敗", ex)
    }
  }

  private def refreshProductView(db: Database, context: ActorContext[Command])(implicit ec: ExecutionContext): Unit = {
    val startTime = System.currentTimeMillis()

    refreshProductView(db).onComplete {
      case Success(_) =>
        val duration = System.currentTimeMillis() - startTime
        context.log.info(s"商品Materialized Viewのリフレッシュ完了（${duration}ms）")

      case Failure(ex) =>
        context.log.error(s"商品Materialized Viewのリフレッシュ失敗", ex)
    }
  }

  private def refreshAccountSubjectView(db: Database, context: ActorContext[Command])(implicit ec: ExecutionContext): Unit = {
    val startTime = System.currentTimeMillis()

    refreshAccountSubjectView(db).onComplete {
      case Success(_) =>
        val duration = System.currentTimeMillis() - startTime
        context.log.info(s"勘定科目Materialized Viewのリフレッシュ完了（${duration}ms）")

      case Failure(ex) =>
        context.log.error(s"勘定科目Materialized Viewのリフレッシュ失敗", ex)
    }
  }

  private def refreshProductView(db: Database)(implicit ec: ExecutionContext): Future[Unit] = {
    val sql = sqlu"""
      REFRESH MATERIALIZED VIEW CONCURRENTLY master_data_management.mv_active_products_with_prices
    """
    db.run(sql).map(_ => ())
  }

  private def refreshAccountSubjectView(db: Database)(implicit ec: ExecutionContext): Future[Unit] = {
    val sql = sqlu"""
      REFRESH MATERIALIZED VIEW CONCURRENTLY master_data_management.mv_account_subject_hierarchy
    """
    db.run(sql).map(_ => ())
  }
}
```

### 10.3.4 イベント受信時の即座リフレッシュ

重要なマスターデータ変更イベント受信時は、定期リフレッシュを待たずに即座にリフレッシュします。

```scala
package com.example.masterdata.infrastructure.integration

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import com.example.masterdata.domain.event.MasterDataEvent
import com.example.masterdata.infrastructure.view.MaterializedViewRefreshService

/**
 * イベント受信時のMaterialized Viewリフレッシュハンドラー
 */
object MaterializedViewRefreshHandler {

  def apply(
    refreshService: ActorRef[MaterializedViewRefreshService.Command]
  ): Behavior[MasterDataEvent] = {
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case event: ProductEvent =>
          context.log.debug(s"商品イベント受信、Materialized Viewリフレッシュ: ${event.getClass.getSimpleName}")
          // 即座にリフレッシュ
          refreshService ! MaterializedViewRefreshService.RefreshProductView
          Behaviors.same

        case event: AccountSubjectEvent =>
          context.log.debug(s"勘定科目イベント受信、Materialized Viewリフレッシュ: ${event.getClass.getSimpleName}")
          refreshService ! MaterializedViewRefreshService.RefreshAccountSubjectView
          Behaviors.same

        case _ =>
          Behaviors.same
      }
    }
  }
}
```

## 10.4 パフォーマンステスト

### 10.4.1 負荷テストシナリオ

JMeterまたはGatlingを使った負荷テストシナリオを定義します。

```scala
package com.example.masterdata.loadtest

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

/**
 * マスターデータAPI負荷テスト
 */
class MasterDataLoadTest extends Simulation {

  val httpProtocol = http
    .baseUrl("http://localhost:8080")
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")

  // シナリオ1: 商品一覧取得（ページング）
  val productListScenario = scenario("商品一覧取得")
    .exec(
      http("商品一覧ページ1")
        .post("/graphql")
        .body(StringBody("""
          {
            "query": "{ products(page: 1, pageSize: 20) { edges { node { id productCode productName listPrice } } totalCount } }"
          }
        """)).asJson
        .check(status.is(200))
        .check(jsonPath("$.data.products.totalCount").exists)
    )
    .pause(1.second)

  // シナリオ2: 商品詳細取得（キャッシュ効果確認）
  val productDetailScenario = scenario("商品詳細取得")
    .exec(
      http("商品詳細")
        .post("/graphql")
        .body(StringBody("""
          {
            "query": "{ productByCode(productCode: \"P-001\") { id productCode productName listPrice prices { priceType unitPrice } } }"
          }
        """)).asJson
        .check(status.is(200))
        .check(jsonPath("$.data.productByCode.id").exists)
    )
    .pause(500.milliseconds)

  // シナリオ3: 商品検索（全文検索）
  val productSearchScenario = scenario("商品全文検索")
    .exec(
      http("商品検索")
        .post("/graphql")
        .body(StringBody("""
          {
            "query": "{ searchProducts(query: \"玄米\", page: 1, pageSize: 10) { edges { node { productCode productName } } totalCount } }"
          }
        """)).asJson
        .check(status.is(200))
    )
    .pause(2.seconds)

  // シナリオ4: 勘定科目階層取得
  val accountSubjectTreeScenario = scenario("勘定科目階層取得")
    .exec(
      http("勘定科目ツリー")
        .post("/graphql")
        .body(StringBody("""
          {
            "query": "{ accountSubjectTree(accountType: ASSET) { accountCode accountName level childAccountSubjects { accountCode accountName } } }"
          }
        """)).asJson
        .check(status.is(200))
    )
    .pause(1.second)

  setUp(
    // 商品一覧: 10ユーザー、10分間
    productListScenario.inject(
      rampUsers(10).during(10.seconds),
      constantUsersPerSec(5).during(10.minutes)
    ),
    // 商品詳細: 50ユーザー、10分間（キャッシュ効果を確認）
    productDetailScenario.inject(
      rampUsers(50).during(10.seconds),
      constantUsersPerSec(20).during(10.minutes)
    ),
    // 商品検索: 5ユーザー、10分間
    productSearchScenario.inject(
      rampUsers(5).during(10.seconds),
      constantUsersPerSec(2).during(10.minutes)
    ),
    // 勘定科目階層: 3ユーザー、10分間
    accountSubjectTreeScenario.inject(
      rampUsers(3).during(10.seconds),
      constantUsersPerSec(1).during(10.minutes)
    )
  ).protocols(httpProtocol)
}
```

### 10.4.2 パフォーマンス目標

以下のパフォーマンス目標を設定します。

| 操作 | レスポンスタイム（P95） | スループット |
|------|------------------------|--------------|
| 商品一覧取得（20件） | < 100ms | 100 req/sec |
| 商品詳細取得（キャッシュヒット） | < 10ms | 500 req/sec |
| 商品詳細取得（キャッシュミス） | < 50ms | 200 req/sec |
| 商品検索（全文検索） | < 200ms | 50 req/sec |
| 勘定科目階層取得 | < 100ms | 100 req/sec |
| 商品作成 | < 500ms | 10 req/sec |
| 価格変更（承認不要） | < 300ms | 20 req/sec |

## 10.5 まとめ

本章では、マスターデータ管理サービスのパフォーマンス最適化を実装しました。

### 実装した内容

1. **キャッシング戦略**
   - 3層キャッシュアーキテクチャ（Caffeine + Redis + PostgreSQL）
   - イベント受信時の自動キャッシュ無効化
   - キャッシュ統計情報の収集

2. **インデックス最適化**
   - 商品テーブル: 9個のインデックス（コード、名前、カテゴリー、ステータス、有効期間）
   - 勘定科目テーブル: 5個のインデックス（コード、階層、種別、レベル）
   - 変更申請テーブル: 6個のインデックス（ステータス、集約、申請者、承認者）

3. **Materialized Viewの活用**
   - 有効商品と価格のMaterialized View
   - 勘定科目階層のMaterialized View
   - 自動リフレッシュ機構

4. **パフォーマンステスト**
   - Gatlingによる負荷テストシナリオ
   - パフォーマンス目標の設定

これらの最適化により、5,000商品・月間800件のマスター変更を高速に処理できるシステムが構築できました。

### 次章の予告

次の第11章では、運用とモニタリングを詳しく解説します。ビジネスメトリクス、データ品質メトリクス、同期状況モニタリングにより、プロダクション環境での安定運用を実現します。
