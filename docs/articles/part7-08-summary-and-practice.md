# 第7部第8章：まとめと実践演習

## 本章の目的

本章は第7部の総まとめとして、これまでに学んだマスターデータ管理の知識を統合し、実践で活用するための総合的な内容を提供します。データ品質管理、GraphQL API実装、パフォーマンス最適化、運用モニタリング、そして実践演習を通じて、プロダクションレディなマスターデータ管理サービスの構築方法を習得します。

## 8.1 データ品質管理

### 8.1.1 バリデーション

データ品質の基本は、適切なバリデーションです。必須項目チェック、形式チェック、ビジネスルールチェックを階層的に実装します。

#### 必須項目チェック

```scala
package com.example.masterdata.domain.validation

/**
 * バリデーション結果
 */
sealed trait ValidationResult
case object Valid extends ValidationResult
final case class Invalid(errors: List[ValidationError]) extends ValidationResult

final case class ValidationError(
  field: String,
  message: String,
  errorCode: String
)

/**
 * 商品バリデーター
 */
object ProductValidator {

  /**
   * 必須項目チェック
   */
  def validateRequired(
    productCode: Option[ProductCode],
    productName: Option[ProductName],
    standardCost: Option[Money],
    listPrice: Option[Money]
  ): ValidationResult = {
    val errors = scala.collection.mutable.ListBuffer[ValidationError]()

    if (productCode.isEmpty) {
      errors += ValidationError("productCode", "商品コードは必須です", "REQUIRED_FIELD")
    }

    if (productName.isEmpty) {
      errors += ValidationError("productName", "商品名は必須です", "REQUIRED_FIELD")
    }

    if (standardCost.isEmpty) {
      errors += ValidationError("standardCost", "標準原価は必須です", "REQUIRED_FIELD")
    }

    if (listPrice.isEmpty) {
      errors += ValidationError("listPrice", "定価は必須です", "REQUIRED_FIELD")
    }

    if (errors.isEmpty) Valid else Invalid(errors.toList)
  }

  /**
   * 形式チェック
   */
  def validateFormat(
    productCode: ProductCode,
    standardCost: Money,
    listPrice: Money,
    validFrom: LocalDate,
    validTo: Option[LocalDate]
  ): ValidationResult = {
    val errors = scala.collection.mutable.ListBuffer[ValidationError]()

    // 商品コード形式チェック（英数字とハイフン）
    if (!productCode.value.matches("^[A-Z0-9\\-]+$")) {
      errors += ValidationError(
        "productCode",
        "商品コードは英数字とハイフンのみ使用できます",
        "INVALID_FORMAT"
      )
    }

    // 金額の範囲チェック（0以上）
    if (standardCost.amount < 0) {
      errors += ValidationError(
        "standardCost",
        "標準原価は0以上である必要があります",
        "INVALID_RANGE"
      )
    }

    if (listPrice.amount < 0) {
      errors += ValidationError(
        "listPrice",
        "定価は0以上である必要があります",
        "INVALID_RANGE"
      )
    }

    // 日付の妥当性チェック
    validTo.foreach { toDate =>
      if (toDate.isBefore(validFrom)) {
        errors += ValidationError(
          "validTo",
          "有効終了日は有効開始日以降である必要があります",
          "INVALID_DATE_RANGE"
        )
      }
    }

    if (errors.isEmpty) Valid else Invalid(errors.toList)
  }

  /**
   * ビジネスルールチェック
   */
  def validateBusinessRules(
    product: Product,
    existingPrices: List[ProductPrice]
  ): ValidationResult = {
    val errors = scala.collection.mutable.ListBuffer[ValidationError]()

    // 定価は標準原価以上であること
    if (product.listPrice.amount < product.standardCost.amount) {
      errors += ValidationError(
        "listPrice",
        "定価は標準原価以上である必要があります",
        "PRICE_BELOW_COST"
      )
    }

    // 特別価格の有効期間重複チェック
    product.prices.foreach { newPrice =>
      existingPrices
        .filter(_.priceType == newPrice.priceType)
        .foreach { existingPrice =>
          if (hasOverlap(newPrice.validPeriod, existingPrice.validPeriod)) {
            errors += ValidationError(
              "prices",
              s"価格タイプ ${newPrice.priceType} の有効期間が重複しています",
              "OVERLAPPING_PRICE_PERIOD"
            )
          }
        }
    }

    if (errors.isEmpty) Valid else Invalid(errors.toList)
  }

  private def hasOverlap(period1: ValidPeriod, period2: ValidPeriod): Boolean = {
    val start1 = period1.validFrom
    val end1 = period1.validTo.getOrElse(LocalDate.MAX)
    val start2 = period2.validFrom
    val end2 = period2.validTo.getOrElse(LocalDate.MAX)

    !(end1.isBefore(start2) || end2.isBefore(start1))
  }
}
```

#### 勘定科目バリデーター

```scala
object AccountSubjectValidator {

  /**
   * 勘定科目の階層構造整合性チェック
   */
  def validateHierarchy(
    accountSubject: AccountSubject,
    parentAccountSubject: Option[AccountSubject]
  ): ValidationResult = {
    val errors = scala.collection.mutable.ListBuffer[ValidationError]()

    parentAccountSubject.foreach { parent =>
      // 親勘定科目は同一種別であること
      if (parent.accountType != accountSubject.accountType) {
        errors += ValidationError(
          "parentAccountSubjectId",
          s"親勘定科目の種別（${parent.accountType}）と子勘定科目の種別（${accountSubject.accountType}）は一致する必要があります",
          "INCONSISTENT_ACCOUNT_TYPE"
        )
      }

      // 親勘定科目が停止・廃止状態でないこと
      if (parent.status != AccountSubjectStatus.Active) {
        errors += ValidationError(
          "parentAccountSubjectId",
          s"親勘定科目が${parent.status}状態のため、子勘定科目を作成できません",
          "PARENT_NOT_ACTIVE"
        )
      }
    }

    if (errors.isEmpty) Valid else Invalid(errors.toList)
  }

  /**
   * 使用中の勘定科目は廃止不可チェック
   */
  def validateObsolete(
    accountSubjectId: AccountSubjectId,
    journalEntryExists: Boolean
  ): ValidationResult = {
    if (journalEntryExists) {
      Invalid(List(
        ValidationError(
          "accountSubjectId",
          "仕訳が存在する勘定科目は廃止できません",
          "ACCOUNT_IN_USE"
        )
      ))
    } else {
      Valid
    }
  }
}
```

### 8.1.2 重複チェック

#### ユニークキー制約

データベースレベルでのユニーク制約と、アプリケーションレベルでの重複チェックを組み合わせます。

```scala
package com.example.masterdata.domain.service

import scala.concurrent.{ExecutionContext, Future}

/**
 * 重複チェックサービス
 */
class DuplicateCheckService(
  productRepository: ProductRepository,
  accountSubjectRepository: AccountSubjectRepository
)(implicit ec: ExecutionContext) {

  /**
   * 商品コードの重複チェック
   */
  def checkProductCodeDuplicate(productCode: ProductCode): Future[Boolean] = {
    productRepository.existsByProductCode(productCode)
  }

  /**
   * 勘定科目コードの重複チェック
   */
  def checkAccountCodeDuplicate(accountCode: AccountCode): Future[Boolean] = {
    accountSubjectRepository.existsByAccountCode(accountCode)
  }
}
```

#### 類似データ検出

レーベンシュタイン距離を使った商品名の類似性チェックを実装します。

```scala
package com.example.masterdata.domain.service

/**
 * 類似データ検出サービス
 */
class SimilarityDetectionService(
  productRepository: ProductRepository
)(implicit ec: ExecutionContext) {

  /**
   * レーベンシュタイン距離の計算
   */
  def levenshteinDistance(s1: String, s2: String): Int = {
    val m = s1.length
    val n = s2.length
    val d = Array.ofDim[Int](m + 1, n + 1)

    for (i <- 0 to m) d(i)(0) = i
    for (j <- 0 to n) d(0)(j) = j

    for (j <- 1 to n; i <- 1 to m) {
      val cost = if (s1(i - 1) == s2(j - 1)) 0 else 1
      d(i)(j) = (d(i - 1)(j) + 1) // 削除
        .min(d(i)(j - 1) + 1) // 挿入
        .min(d(i - 1)(j - 1) + cost) // 置換
    }

    d(m)(n)
  }

  /**
   * 類似商品名の検出
   */
  def findSimilarProductNames(
    productName: String,
    threshold: Double = 0.8
  ): Future[List[(Product, Double)]] = {
    productRepository.findAll().map { products =>
      products
        .map { product =>
          val distance = levenshteinDistance(productName, product.productName.value)
          val maxLen = productName.length.max(product.productName.value.length)
          val similarity = 1.0 - (distance.toDouble / maxLen)
          (product, similarity)
        }
        .filter(_._2 >= threshold)
        .sortBy(-_._2)
    }
  }

  /**
   * マージ候補の提案
   */
  def suggestMergeCandidates(
    productId: ProductId,
    similarityThreshold: Double = 0.9
  ): Future[List[Product]] = {
    productRepository.findById(productId).flatMap {
      case Some(product) =>
        findSimilarProductNames(product.productName.value, similarityThreshold).map {
          _.filter(_._1.id != productId).map(_._1)
        }
      case None =>
        Future.successful(List.empty)
    }
  }
}
```

### 8.1.3 データクレンジング

#### 名称の正規化

```scala
package com.example.masterdata.domain.service

/**
 * データクレンジングサービス
 */
object DataCleansingService {

  /**
   * 全角・半角の統一（数字・英字は半角、カナは全角）
   */
  def normalizeCharacterWidth(text: String): String = {
    // 英数字を半角に
    val halfWidthAlphanumeric = text
      .replaceAll("[０-９]", m => (m.charAt(0) - 0xFF10 + '0').toChar.toString)
      .replaceAll("[Ａ-Ｚ]", m => (m.charAt(0) - 0xFF21 + 'A').toChar.toString)
      .replaceAll("[ａ-ｚ]", m => (m.charAt(0) - 0xFF41 + 'a').toChar.toString)

    // カタカナを全角に
    halfWidthAlphanumeric.map {
      case c if (c >= 0xFF66 && c <= 0xFF9F) =>
        // 半角カナから全角カナへの変換テーブル（簡略版）
        (c - 0xFF66 + 0x30A1).toChar
      case c => c
    }.mkString
  }

  /**
   * カタカナ表記の統一
   */
  def normalizeKatakana(text: String): String = {
    text
      .replaceAll("ヴ", "ブ")
      .replaceAll("ァ", "ア")
      .replaceAll("ィ", "イ")
      .replaceAll("ゥ", "ウ")
      .replaceAll("ェ", "エ")
      .replaceAll("ォ", "オ")
  }

  /**
   * 商品名のクレンジング
   */
  def cleanseProductName(productName: String): String = {
    val normalized = normalizeCharacterWidth(productName)
    val katakana = normalizeKatakana(normalized)
    katakana.trim
  }

  /**
   * 郵便番号の正規化（ハイフン除去）
   */
  def normalizePostalCode(postalCode: String): String = {
    postalCode.replaceAll("-", "").trim
  }

  /**
   * 電話番号の正規化（ハイフン除去）
   */
  def normalizePhoneNumber(phoneNumber: String): String = {
    phoneNumber.replaceAll("[\\-\\s()]", "").trim
  }
}
```

## 8.2 GraphQL APIの実装

### 8.2.1 商品マスターAPI

Sangria GraphQLを使用して、柔軟なクエリAPIを提供します。

#### GraphQLスキーマ定義

```scala
package com.example.masterdata.query.graphql

import sangria.schema._
import sangria.macros.derive._
import java.time.LocalDate
import java.util.UUID

/**
 * 商品GraphQLスキーマ
 */
object ProductSchema {

  // 商品ステータス
  val ProductStatusType = EnumType(
    "ProductStatus",
    values = List(
      EnumValue("ACTIVE", value = "Active"),
      EnumValue("SUSPENDED", value = "Suspended"),
      EnumValue("OBSOLETE", value = "Obsolete")
    )
  )

  // 価格タイプ
  val PriceTypeType = EnumType(
    "PriceType",
    values = List(
      EnumValue("STANDARD", value = "Standard"),
      EnumValue("SPECIAL", value = "Special"),
      EnumValue("DISCOUNT", value = "Discount")
    )
  )

  // 商品価格型
  val ProductPriceType = ObjectType(
    "ProductPrice",
    fields[Unit, ProductPriceView](
      Field("priceType", PriceTypeType, resolve = _.value.priceType),
      Field("unitPrice", BigDecimalType, resolve = _.value.unitPrice),
      Field("validFrom", StringType, resolve = _.value.validFrom.toString),
      Field("validTo", OptionType(StringType), resolve = _.value.validTo.map(_.toString))
    )
  )

  // 商品型
  val ProductType = ObjectType(
    "Product",
    fields[ProductContext, ProductView](
      Field("id", IDType, resolve = _.value.id),
      Field("productCode", StringType, resolve = _.value.productCode),
      Field("productName", StringType, resolve = _.value.productName),
      Field("categoryCode", StringType, resolve = _.value.categoryCode),
      Field("standardCost", BigDecimalType, resolve = _.value.standardCost),
      Field("listPrice", BigDecimalType, resolve = _.value.listPrice),
      Field("status", ProductStatusType, resolve = _.value.status),
      Field("validFrom", StringType, resolve = _.value.validFrom.toString),
      Field("validTo", OptionType(StringType), resolve = _.value.validTo.map(_.toString)),
      Field(
        "prices",
        ListType(ProductPriceType),
        resolve = ctx => ctx.ctx.productPriceLoader.loadMany(ctx.value.id)
      )
    )
  )

  // ページ情報型
  val PageInfoType = ObjectType(
    "PageInfo",
    fields[Unit, PageInfo](
      Field("hasNextPage", BooleanType, resolve = _.value.hasNextPage),
      Field("hasPreviousPage", BooleanType, resolve = _.value.hasPreviousPage),
      Field("startCursor", OptionType(StringType), resolve = _.value.startCursor),
      Field("endCursor", OptionType(StringType), resolve = _.value.endCursor)
    )
  )

  // 商品エッジ型
  val ProductEdgeType = ObjectType(
    "ProductEdge",
    fields[ProductContext, ProductEdge](
      Field("node", ProductType, resolve = _.value.node),
      Field("cursor", StringType, resolve = _.value.cursor)
    )
  )

  // 商品コネクション型
  val ProductConnectionType = ObjectType(
    "ProductConnection",
    fields[ProductContext, ProductConnection](
      Field("edges", ListType(ProductEdgeType), resolve = _.value.edges),
      Field("pageInfo", PageInfoType, resolve = _.value.pageInfo),
      Field("totalCount", IntType, resolve = _.value.totalCount)
    )
  )

  // 商品フィルター入力
  val ProductFilterInputType = InputObjectType[ProductFilter](
    "ProductFilter",
    List(
      InputField("productCode", OptionInputType(StringType)),
      InputField("productName", OptionInputType(StringType)),
      InputField("categoryCode", OptionInputType(StringType)),
      InputField("status", OptionInputType(ProductStatusType)),
      InputField("validAt", OptionInputType(StringType))
    )
  )

  // クエリ定義
  val QueryType = ObjectType(
    "Query",
    fields[ProductContext, Unit](
      Field(
        "products",
        ProductConnectionType,
        arguments = List(
          Argument("filter", OptionInputType(ProductFilterInputType)),
          Argument("page", OptionInputType(IntType), defaultValue = 1),
          Argument("pageSize", OptionInputType(IntType), defaultValue = 20)
        ),
        resolve = ctx => {
          val filter = ctx.argOpt[ProductFilter]("filter")
          val page = ctx.arg[Int]("page")
          val pageSize = ctx.arg[Int]("pageSize")
          ctx.ctx.productRepository.findWithPagination(filter, page, pageSize)
        }
      ),
      Field(
        "product",
        OptionType(ProductType),
        arguments = List(Argument("id", IDType)),
        resolve = ctx => {
          val id = ctx.arg[String]("id")
          ctx.ctx.productRepository.findById(UUID.fromString(id))
        }
      ),
      Field(
        "productByCode",
        OptionType(ProductType),
        arguments = List(Argument("productCode", StringType)),
        resolve = ctx => {
          val productCode = ctx.arg[String]("productCode")
          ctx.ctx.productRepository.findByProductCode(productCode)
        }
      ),
      Field(
        "activeProductsAt",
        ListType(ProductType),
        arguments = List(Argument("date", StringType)),
        resolve = ctx => {
          val date = LocalDate.parse(ctx.arg[String]("date"))
          ctx.ctx.productRepository.findActiveAt(date)
        }
      ),
      Field(
        "productPrice",
        OptionType(ProductPriceType),
        arguments = List(
          Argument("productId", IDType),
          Argument("date", StringType)
        ),
        resolve = ctx => {
          val productId = UUID.fromString(ctx.arg[String]("productId"))
          val date = LocalDate.parse(ctx.arg[String]("date"))
          ctx.ctx.productPriceRepository.findPriceAt(productId, date)
        }
      )
    )
  )

  // ミューテーション定義
  val MutationType = ObjectType(
    "Mutation",
    fields[ProductContext, Unit](
      Field(
        "createProduct",
        ProductType,
        arguments = List(
          Argument("productCode", StringType),
          Argument("productName", StringType),
          Argument("categoryCode", StringType),
          Argument("standardCost", BigDecimalType),
          Argument("listPrice", BigDecimalType)
        ),
        resolve = ctx => {
          val input = CreateProductInput(
            productCode = ctx.arg[String]("productCode"),
            productName = ctx.arg[String]("productName"),
            categoryCode = ctx.arg[String]("categoryCode"),
            standardCost = ctx.arg[BigDecimal]("standardCost"),
            listPrice = ctx.arg[BigDecimal]("listPrice")
          )
          ctx.ctx.productCommandService.createProduct(input)
        }
      )
    )
  )

  // スキーマ
  val schema = Schema(QueryType, Some(MutationType))
}

// ビューモデル
case class ProductView(
  id: String,
  productCode: String,
  productName: String,
  categoryCode: String,
  standardCost: BigDecimal,
  listPrice: BigDecimal,
  status: String,
  validFrom: LocalDate,
  validTo: Option[LocalDate]
)

case class ProductPriceView(
  priceType: String,
  unitPrice: BigDecimal,
  validFrom: LocalDate,
  validTo: Option[LocalDate]
)

case class ProductEdge(node: ProductView, cursor: String)

case class PageInfo(
  hasNextPage: Boolean,
  hasPreviousPage: Boolean,
  startCursor: Option[String],
  endCursor: Option[String]
)

case class ProductConnection(
  edges: List[ProductEdge],
  pageInfo: PageInfo,
  totalCount: Int
)

case class ProductFilter(
  productCode: Option[String],
  productName: Option[String],
  categoryCode: Option[String],
  status: Option[String],
  validAt: Option[LocalDate]
)

case class CreateProductInput(
  productCode: String,
  productName: String,
  categoryCode: String,
  standardCost: BigDecimal,
  listPrice: BigDecimal
)

// コンテキスト
case class ProductContext(
  productRepository: ProductQueryRepository,
  productPriceRepository: ProductPriceQueryRepository,
  productPriceLoader: DataLoader[String, List[ProductPriceView]],
  productCommandService: ProductCommandService
)
```

### 8.2.2 クエリ最適化

#### DataLoaderパターン

N+1クエリ問題を解決するため、DataLoaderパターンを実装します。

```scala
package com.example.masterdata.query.graphql

import scala.concurrent.{ExecutionContext, Future}

/**
 * DataLoader実装
 */
class DataLoader[K, V](
  batchLoadFn: List[K] => Future[Map[K, V]]
)(implicit ec: ExecutionContext) {

  private val cache = scala.collection.mutable.Map[K, Future[V]]()
  private val queue = scala.collection.mutable.ListBuffer[K]()

  def load(key: K): Future[V] = {
    cache.getOrElseUpdate(key, {
      queue += key
      dispatch().flatMap(_.apply(key))
    })
  }

  def loadMany(keys: List[K]): Future[List[V]] = {
    Future.sequence(keys.map(load))
  }

  private def dispatch(): Future[Map[K, V]] = {
    if (queue.isEmpty) {
      Future.successful(Map.empty)
    } else {
      val keys = queue.toList
      queue.clear()
      batchLoadFn(keys)
    }
  }
}

/**
 * 商品価格DataLoader
 */
class ProductPriceDataLoader(
  productPriceRepository: ProductPriceQueryRepository
)(implicit ec: ExecutionContext) {

  def createLoader(): DataLoader[String, List[ProductPriceView]] = {
    new DataLoader[String, List[ProductPriceView]](productIds => {
      productPriceRepository.findByProductIds(productIds).map { prices =>
        prices.groupBy(_.productId)
      }
    })
  }
}
```

## 8.3 パフォーマンス最適化

### 8.3.1 キャッシング戦略

#### 多層キャッシュアーキテクチャ

```scala
package com.example.masterdata.infrastructure.cache

import com.github.benmanes.caffeine.cache.{Cache, Caffeine}
import redis.clients.jedis.JedisPool
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Success, Failure}

/**
 * 多層キャッシュサービス
 */
class MultiLevelCacheService(
  jedisPool: JedisPool,
  productRepository: ProductRepository
)(implicit ec: ExecutionContext) {

  // レベル1: インメモリキャッシュ（Caffeine）
  private val inMemoryCache: Cache[String, ProductView] = Caffeine.newBuilder()
    .maximumSize(1000)
    .expireAfterWrite(5.minutes.toJava)
    .build()

  // レベル2: 分散キャッシュ（Redis）
  private val redisTTL = 3600 // 1時間

  /**
   * 商品取得（3層キャッシュ）
   */
  def getProduct(productId: String): Future[Option[ProductView]] = {
    // レベル1: インメモリキャッシュ
    Option(inMemoryCache.getIfPresent(productId)) match {
      case Some(product) =>
        Future.successful(Some(product))

      case None =>
        // レベル2: Redis
        getFromRedis(productId).flatMap {
          case Some(product) =>
            // レベル1キャッシュに保存
            inMemoryCache.put(productId, product)
            Future.successful(Some(product))

          case None =>
            // レベル3: データベース
            productRepository.findById(productId).map {
              case Some(product) =>
                // レベル1・2キャッシュに保存
                inMemoryCache.put(productId, product)
                putToRedis(productId, product)
                Some(product)

              case None =>
                None
            }
        }
    }
  }

  /**
   * キャッシュ無効化（イベント受信時）
   */
  def invalidateProduct(productId: String): Future[Unit] = {
    // レベル1: インメモリキャッシュ無効化
    inMemoryCache.invalidate(productId)

    // レベル2: Redis無効化
    Future {
      val jedis = jedisPool.getResource
      try {
        jedis.del(s"product:$productId")
      } finally {
        jedis.close()
      }
    }
  }

  private def getFromRedis(productId: String): Future[Option[ProductView]] = {
    Future {
      val jedis = jedisPool.getResource
      try {
        Option(jedis.get(s"product:$productId")).map { json =>
          // JSONデシリアライズ（実装省略）
          deserializeProduct(json)
        }
      } finally {
        jedis.close()
      }
    }
  }

  private def putToRedis(productId: String, product: ProductView): Unit = {
    val jedis = jedisPool.getResource
    try {
      val json = serializeProduct(product) // JSONシリアライズ（実装省略）
      jedis.setex(s"product:$productId", redisTTL, json)
    } finally {
      jedis.close()
    }
  }

  private def serializeProduct(product: ProductView): String = {
    // JSON シリアライズ実装（省略）
    ???
  }

  private def deserializeProduct(json: String): ProductView = {
    // JSON デシリアライズ実装（省略）
    ???
  }
}
```

### 8.3.2 インデックス最適化

PostgreSQLのインデックス戦略を最適化します。

```sql
-- 商品コード検索用（ユニークインデックス）
CREATE UNIQUE INDEX idx_products_code
  ON master_data_management.products(product_code);

-- 商品名検索用（部分一致検索対応）
CREATE INDEX idx_products_name
  ON master_data_management.products
  USING gin(to_tsvector('japanese', product_name));

-- 有効な商品の検索用（部分インデックス）
CREATE INDEX idx_products_valid
  ON master_data_management.products(valid_from, valid_to)
  WHERE status = 'Active';

-- カテゴリー別検索用
CREATE INDEX idx_products_category
  ON master_data_management.products(category_code)
  WHERE status = 'Active';

-- 勘定科目コード検索用
CREATE UNIQUE INDEX idx_account_subjects_code
  ON master_data_management.account_subjects(account_subject_code);

-- 階層構造検索用
CREATE INDEX idx_account_subjects_parent
  ON master_data_management.account_subjects(parent_account_subject_id)
  WHERE status = 'Active';

-- 勘定科目種別検索用
CREATE INDEX idx_account_subjects_type
  ON master_data_management.account_subjects(account_type, status);
```

### 8.3.3 Materialized Viewの活用

頻繁にアクセスされる複雑なクエリは、Materialized Viewで最適化します。

```sql
-- 有効な商品と価格のMaterialized View
CREATE MATERIALIZED VIEW master_data_management.mv_active_products_with_prices AS
SELECT
  p.product_id,
  p.product_code,
  p.product_name,
  p.category_code,
  p.standard_cost,
  p.list_price,
  p.status,
  p.valid_from,
  p.valid_to,
  pp.price_type,
  pp.unit_price as special_price,
  pp.customer_id,
  pp.valid_from as price_valid_from,
  pp.valid_to as price_valid_to
FROM master_data_management.products p
LEFT JOIN master_data_management.product_prices pp
  ON p.product_id = pp.product_id
WHERE p.status = 'Active'
  AND CURRENT_DATE >= p.valid_from
  AND (p.valid_to IS NULL OR CURRENT_DATE <= p.valid_to);

-- インデックス作成
CREATE UNIQUE INDEX idx_mv_products_id
  ON master_data_management.mv_active_products_with_prices(product_id, price_type);

CREATE INDEX idx_mv_products_code
  ON master_data_management.mv_active_products_with_prices(product_code);

CREATE INDEX idx_mv_products_category
  ON master_data_management.mv_active_products_with_prices(category_code);

CREATE INDEX idx_mv_products_customer
  ON master_data_management.mv_active_products_with_prices(customer_id)
  WHERE customer_id IS NOT NULL;

-- 定期的にリフレッシュ（イベント受信時、または定期バッチ）
REFRESH MATERIALIZED VIEW CONCURRENTLY master_data_management.mv_active_products_with_prices;
```

Materialized Viewを自動更新するサービス：

```scala
package com.example.masterdata.infrastructure.view

import scala.concurrent.{ExecutionContext, Future}
import slick.jdbc.PostgresProfile.api._

/**
 * Materialized View 更新サービス
 */
class MaterializedViewRefreshService(db: Database)(implicit ec: ExecutionContext) {

  /**
   * 商品Materialized Viewを更新
   */
  def refreshProductView(): Future[Unit] = {
    val sql = sql"""
      REFRESH MATERIALIZED VIEW CONCURRENTLY master_data_management.mv_active_products_with_prices
    """.as[Int]

    db.run(sql).map(_ => ())
  }

  /**
   * イベント受信時の部分更新（該当商品のみ）
   */
  def refreshProductViewForProduct(productId: String): Future[Unit] = {
    // Materialized Viewは部分更新不可のため、全体をリフレッシュ
    // または、通常テーブルとして実装し、トリガーで更新
    refreshProductView()
  }
}
```

## 8.4 運用とモニタリング

### 8.4.1 ビジネスメトリクス

Prometheus形式でビジネスメトリクスを収集します。

```scala
package com.example.masterdata.infrastructure.monitoring

import io.prometheus.client.{Counter, Gauge, Histogram}

/**
 * マスターデータメトリクス
 */
object MasterDataMetrics {

  // 商品マスター総数
  val productTotalGauge: Gauge = Gauge.build()
    .name("masterdata_product_total")
    .help("商品マスター総数")
    .register()

  // 勘定科目マスター総数
  val accountSubjectTotalGauge: Gauge = Gauge.build()
    .name("masterdata_account_subject_total")
    .help("勘定科目マスター総数")
    .register()

  // マスター変更件数
  val masterChangeCounter: Counter = Counter.build()
    .name("masterdata_change_total")
    .help("マスター変更件数")
    .labelNames("aggregate_type", "change_type")
    .register()

  // 承認待ち件数
  val pendingApprovalGauge: Gauge = Gauge.build()
    .name("masterdata_pending_approval_total")
    .help("承認待ち件数")
    .labelNames("request_type")
    .register()

  // 承認処理時間
  val approvalDurationHistogram: Histogram = Histogram.build()
    .name("masterdata_approval_duration_seconds")
    .help("承認処理時間（秒）")
    .labelNames("request_type")
    .buckets(60, 300, 900, 1800, 3600, 7200) // 1分、5分、15分、30分、1時間、2時間
    .register()

  // 承認率
  val approvalRateGauge: Gauge = Gauge.build()
    .name("masterdata_approval_rate")
    .help("承認率（承認/（承認+却下））")
    .labelNames("request_type")
    .register()
}

/**
 * メトリクス収集サービス
 */
class MetricsCollectionService(
  productRepository: ProductRepository,
  accountSubjectRepository: AccountSubjectRepository,
  changeRequestRepository: ChangeRequestRepository
)(implicit ec: ExecutionContext) {

  /**
   * 定期的にメトリクスを収集
   */
  def collectMetrics(): Future[Unit] = {
    for {
      productTotal <- productRepository.count()
      accountSubjectTotal <- accountSubjectRepository.count()
      pendingApprovals <- changeRequestRepository.countByStatus(ChangeRequestStatus.Pending)
      approvalRate <- calculateApprovalRate()
    } yield {
      MasterDataMetrics.productTotalGauge.set(productTotal.toDouble)
      MasterDataMetrics.accountSubjectTotalGauge.set(accountSubjectTotal.toDouble)
      MasterDataMetrics.pendingApprovalGauge.labels("all").set(pendingApprovals.toDouble)
      MasterDataMetrics.approvalRateGauge.labels("all").set(approvalRate)
    }
  }

  private def calculateApprovalRate(): Future[Double] = {
    for {
      approved <- changeRequestRepository.countByStatus(ChangeRequestStatus.Approved)
      rejected <- changeRequestRepository.countByStatus(ChangeRequestStatus.Rejected)
    } yield {
      val total = approved + rejected
      if (total > 0) approved.toDouble / total else 0.0
    }
  }
}
```

### 8.4.2 データ品質メトリクス

```scala
/**
 * データ品質メトリクス
 */
object DataQualityMetrics {

  // 必須項目充足率
  val requiredFieldCompletionRate: Gauge = Gauge.build()
    .name("masterdata_required_field_completion_rate")
    .help("必須項目充足率")
    .labelNames("aggregate_type", "field_name")
    .register()

  // 参照整合性エラー件数
  val referentialIntegrityErrorCounter: Counter = Counter.build()
    .name("masterdata_referential_integrity_error_total")
    .help("参照整合性エラー件数")
    .labelNames("aggregate_type")
    .register()

  // 重複候補検出件数
  val duplicateCandidateCounter: Counter = Counter.build()
    .name("masterdata_duplicate_candidate_total")
    .help("重複候補検出件数")
    .labelNames("aggregate_type")
    .register()
}
```

### 8.4.3 同期状況モニタリング

```scala
/**
 * 同期状況メトリクス
 */
object SyncMetrics {

  // イベント処理遅延時間
  val eventProcessingLagGauge: Gauge = Gauge.build()
    .name("masterdata_event_processing_lag_seconds")
    .help("イベント処理遅延時間（秒）")
    .labelNames("topic", "bounded_context")
    .register()

  // 未処理イベント件数
  val unprocessedEventGauge: Gauge = Gauge.build()
    .name("masterdata_unprocessed_event_total")
    .help("未処理イベント件数")
    .labelNames("topic", "bounded_context")
    .register()

  // イベント処理エラー率
  val eventProcessingErrorRate: Gauge = Gauge.build()
    .name("masterdata_event_processing_error_rate")
    .help("イベント処理エラー率")
    .labelNames("topic", "bounded_context")
    .register()
}
```

## 8.5 まとめと実践演習

### 8.5.1 学んだこと

第7部を通じて、以下のトピックを学習しました。

#### マスターデータのイベントソーシング

- **全変更履歴の追跡**: すべてのマスターデータ変更をイベントとして記録
- **時点復元機能**: 過去の任意の時点のマスターデータ状態を復元可能
- **監査証跡の確保**: コンプライアンス要件に対応した完全な監査ログ

#### イベント駆動マスターデータ同期

- **Single Source of Truth**: マスターデータは共用データ管理サービスが一元管理
- **結果整合性による疎結合**: 各Bounded Contextは非同期にマスター変更を受信
- **Materialized Viewによる参照最適化**: 各サービスに最適化された参照データを保持

#### データガバナンス

- **変更承認ワークフロー**: 重要な変更にはSagaパターンによる承認フローを適用
- **データ品質管理**: バリデーション、重複チェック、データクレンジング
- **アクセス制御**: GraphQL APIによる柔軟なアクセス制御

#### GraphQL API

- **柔軟なクエリ**: クライアントが必要なデータのみを取得
- **DataLoaderによるN+1問題解決**: バッチ処理でデータベースアクセスを効率化
- **ページングとフィルタリング**: Relay仕様準拠のカーソルベースページング

### 8.5.2 実践演習

#### 演習1: 商品価格変更ワークフロー

**目的**: 承認フロー付きの商品価格変更を実装し、他サービスへの伝播を確認

**手順**:
1. 商品価格変更申請を作成
2. 承認者による承認
3. 承認後の価格反映
4. 在庫管理サービスでの新価格適用確認

**実装例**:
```scala
// 1. 価格変更申請
val request = PriceChangeRequest(
  id = ChangeRequestId.generate(),
  productId = ProductId("PROD-0001"),
  productCode = ProductCode("P-001"),
  productName = ProductName("有機玄米 5kg"),
  oldPrice = Money(BigDecimal(3500)),
  newPrice = Money(BigDecimal(3800)),
  effectiveFrom = LocalDate.now().plusDays(7),
  reason = "原材料費の上昇により価格改定",
  requester = Requester("user001", "田中太郎", "tanaka@example.com"),
  status = ChangeRequestStatus.Pending,
  requestedAt = Instant.now(),
  approver = None,
  approvedAt = None,
  rejectionReason = None
)

approvalFlowActor ! SubmitChangeRequest(request, replyTo)

// 2. 承認
val approver = Approver("mgr001", "佐藤花子", "sato@example.com")
approvalFlowActor ! ApproveChangeRequest(request.id, approver, replyTo)

// 3. 在庫管理サービスで価格確認
inventoryProductRepository.findByProductCode("P-001").map { product =>
  assert(product.currentPrice == BigDecimal(3800))
}
```

#### 演習2: 勘定科目の階層構造管理

**目的**: 勘定科目の親子関係を管理し、階層構造の整合性を検証

**手順**:
1. 親勘定科目を作成
2. 子勘定科目を作成（親との種別一致を確認）
3. 親勘定科目の種別変更を試みる（エラーになることを確認）
4. 会計サービスでの勘定科目階層の参照

**実装例**:
```scala
// 1. 親勘定科目作成
val parentAccountSubject = AccountSubject(
  id = AccountSubjectId.generate(),
  accountCode = AccountCode("1000"),
  accountName = AccountName("流動資産"),
  accountType = AccountType.Asset,
  parentAccountSubjectId = None,
  status = AccountSubjectStatus.Active,
  validPeriod = ValidPeriod(LocalDate.of(2020, 1, 1), None),
  version = Version(1)
)

// 2. 子勘定科目作成（同一種別）
val childAccountSubject = AccountSubject(
  id = AccountSubjectId.generate(),
  accountCode = AccountCode("1010"),
  accountName = AccountName("現金"),
  accountType = AccountType.Asset, // 親と同じ
  parentAccountSubjectId = Some(parentAccountSubject.id),
  status = AccountSubjectStatus.Active,
  validPeriod = ValidPeriod(LocalDate.of(2020, 1, 1), None),
  version = Version(1)
)

// 3. バリデーション確認
val result = AccountSubjectValidator.validateHierarchy(
  childAccountSubject,
  Some(parentAccountSubject)
)
assert(result == Valid)
```

#### 演習3: マスターデータ同期の監視

**目的**: マスター変更イベントの伝播を監視し、同期遅延を測定

**手順**:
1. マスター変更イベントを発行
2. 各Bounded Contextでのイベント受信を確認
3. 同期遅延を測定
4. Grafanaダッシュボードで可視化

**実装例**:
```scala
// 1. 商品情報変更
productActor ! UpdateProductInfo(
  productId = ProductId("PROD-0001"),
  productName = ProductName("有機玄米 5kg（新パッケージ）"),
  categoryCode = CategoryCode("RICE"),
  replyTo = probe.ref
)

// 2. 在庫管理サービスでイベント受信確認
eventually(timeout(10.seconds)) {
  val product = inventoryProductRepository.findByProductCode("P-001").futureValue
  assert(product.productName == "有機玄米 5kg（新パッケージ）")
}

// 3. 同期遅延測定
val masterUpdateTime = Instant.now()
// ... イベント処理 ...
val referenceUpdateTime = Instant.now()
val lagSeconds = referenceUpdateTime.getEpochSecond - masterUpdateTime.getEpochSecond

SyncMetrics.eventProcessingLagGauge
  .labels("master-data.product", "inventory-management")
  .set(lagSeconds.toDouble)
```

#### 演習4: 過去時点のマスターデータ復元

**目的**: イベントリプレイにより過去の商品価格を復元

**手順**:
1. 指定日時のイベントをリプレイ
2. 過去の商品価格を復元
3. 会計監査用のデータとして提示

**実装例**:
```scala
// 1. 2024年1月1日時点の商品価格を復元
val targetDate = LocalDate.of(2024, 1, 1)

val readJournal = PersistenceQuery(system)
  .readJournalFor[DynamoDBReadJournal](DynamoDBReadJournal.Identifier)

// 2. 指定日時までのイベントをリプレイ
val productState = readJournal
  .eventsByPersistenceId(s"Product-${productId.value}", 0, Long.MaxValue)
  .takeWhile(_.event.asInstanceOf[ProductEvent].occurredAt.isBefore(targetDate.atStartOfDay().toInstant(ZoneOffset.UTC)))
  .runFold(EmptyState: ProductActor.State) { (state, envelope) =>
    ProductActor.eventHandler(state, envelope.event.asInstanceOf[ProductEvent])
  }

// 3. 復元された価格を確認
productState match {
  case ProductState(product) =>
    println(s"2024年1月1日時点の価格: ${product.listPrice.amount}円")
  case _ =>
    println("商品が存在しませんでした")
}
```

### 8.5.3 次のステップ

#### より高度なマスターデータ管理

**マスターデータのバージョン分岐（What-if分析）**
- 本番環境とは別に、仮想的な価格変更シナリオを検証
- イベントソーシングの分岐機能を活用

**グローバルマスター（多言語、多通貨）**
- 商品名の多言語対応
- 価格の多通貨対応
- 地域別の商品バリエーション管理

**データリネージ（データ系譜の追跡）**
- マスターデータの変更履歴を可視化
- データの流れを追跡（どのシステムがどのデータを使用しているか）

#### 他システムとの統合

**外部システムからのマスターデータインポート**
- CSVファイルからの一括インポート
- EDI（電子データ交換）連携
- REST API経由でのデータ取り込み

**マスターデータのエクスポート**
- 他社システムへのマスターデータ提供
- 標準フォーマット（CSV、JSON、XML）への変換
- スケジューリングされた定期エクスポート

**API Gatewayによるアクセス制御**
- OAuth 2.0認証
- レート制限
- APIキー管理

#### AI/MLによる高度化

**重複データの自動検出・マージ**
- 機械学習モデルによる重複候補の自動検出
- 類似度スコアリング
- 自動マージ提案

**データ品質スコアリング**
- データ品質を0-100点でスコアリング
- 品質低下の早期検知
- 改善アクションの提案

**異常値検出**
- 商品価格の異常値検出（通常価格から大きく外れた価格）
- 勘定科目の異常な使用パターン検出
- アラート通知

**マスター変更の予測と提案**
- 過去の変更パターンから次の変更を予測
- 季節性を考慮した価格変更提案
- 需要予測に基づく在庫管理パラメータ調整

## 8.6 総括

第7部「共用データ管理サービス」では、CQRS/イベントソーシングアーキテクチャにおけるマスターデータ管理の完全な実装を学習しました。

### 主要な成果

1. **イベントソーシングによるマスターデータ管理**: 全変更履歴の追跡と時点復元
2. **イベント駆動同期**: 疎結合なマスターデータ配信アーキテクチャ
3. **承認ワークフロー**: Sagaパターンによる変更管理
4. **データ品質保証**: バリデーション、重複チェック、クレンジング
5. **GraphQL API**: 柔軟で効率的なクエリインターフェース
6. **運用監視**: メトリクス収集とモニタリング

これらの知識を活用することで、エンタープライズグレードのマスターデータ管理サービスを構築できます。

### 本シリーズの完結

本章をもって、「Apache Pekko CQRS/Event Sourcing 実装ガイド」全7部・全85章が完結します。

- **第1部**: 環境構築（全10章）
- **第2部**: サービス構築（全11章）
- **第3部**: 在庫管理サービス（全13章）
- **第4部**: 受注管理サービス（全13章）
- **第5部**: 発注管理サービス（全11章）
- **第6部**: 会計サービス（全12章）
- **第7部**: 共用データ管理サービス（全8章）

皆様がこのシリーズで得た知識を実際のプロジェクトで活用し、堅牢でスケーラブルなシステムを構築されることを願っています。

---

**執筆者より**

CQRS/イベントソーシングは、複雑なドメインモデルを扱うエンタープライズシステムにおいて、非常に強力なアーキテクチャパターンです。本シリーズが、皆様のシステム設計・開発の一助となれば幸いです。

ハッピーコーディング！
