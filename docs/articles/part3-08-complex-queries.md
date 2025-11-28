# 第3部 第8章：複雑なクエリの実装

## この章で学ぶこと

- 在庫照会のRead Model設計（商品別、倉庫別、区画別）
- マテリアライズドビューによる集計クエリの最適化
- 複雑なGraphQLクエリの実装（ネスト、フィルタリング、ページネーション）
- DataLoaderによるN+1問題の解決
- インデックス戦略とクエリパフォーマンスチューニング

---

## 8.1 在庫照会のRead Model設計

在庫管理システムでは、様々な観点から在庫を照会する必要があります。第2章で設計したRead Modelを基に、複雑なクエリを実装します。

### 8.1.1 商品別在庫サマリー

商品ごとの在庫状況を集計します。

#### GraphQLスキーマ定義

```graphql
# 商品別在庫サマリー
type ProductInventorySummary {
  """商品ID"""
  productId: ID!

  """商品情報"""
  product: Product!

  """総在庫数"""
  totalQuantityOnHand: Int!

  """総引当済数"""
  totalQuantityReserved: Int!

  """総有効在庫数"""
  totalQuantityAvailable: Int!

  """倉庫別内訳"""
  warehouseBreakdown: [WarehouseInventory!]!

  """区画別内訳"""
  zoneBreakdown: [ZoneInventory!]!
}

# 倉庫別在庫
type WarehouseInventory {
  """倉庫コード"""
  warehouseCode: String!

  """倉庫情報"""
  warehouse: Warehouse!

  """在庫数"""
  quantityOnHand: Int!

  """引当済数"""
  quantityReserved: Int!

  """有効在庫数"""
  quantityAvailable: Int!
}

# 区画別在庫
type ZoneInventory {
  """倉庫コード"""
  warehouseCode: String!

  """区画番号"""
  zoneNumber: Int!

  """区画情報"""
  zone: WarehouseZone!

  """在庫数"""
  quantityOnHand: Int!

  """引当済数"""
  quantityReserved: Int!

  """有効在庫数"""
  quantityAvailable: Int!
}

# クエリ
type Query {
  """商品別在庫サマリーを取得"""
  productInventorySummary(productId: ID!): ProductInventorySummary

  """商品一覧の在庫サマリーを取得（ページネーション対応）"""
  productInventorySummaries(
    """カテゴリでフィルタ"""
    category: ProductCategory

    """保管条件でフィルタ"""
    storageCondition: StorageCondition

    """在庫あり商品のみ"""
    availableOnly: Boolean

    """ページネーション"""
    first: Int
    after: String
  ): ProductInventorySummaryConnection!
}

# ページネーション用Connection型
type ProductInventorySummaryConnection {
  edges: [ProductInventorySummaryEdge!]!
  pageInfo: PageInfo!
  totalCount: Int!
}

type ProductInventorySummaryEdge {
  node: ProductInventorySummary!
  cursor: String!
}

type PageInfo {
  hasNextPage: Boolean!
  hasPreviousPage: Boolean!
  startCursor: String
  endCursor: String
}
```

#### SQLクエリ実装

```scala
// modules/query/interface-adapter/src/main/scala/.../dao/InventoryDao.scala
import slick.jdbc.PostgresProfile.api._

class InventoryDao(db: Database) {

  /**
   * 商品別在庫サマリーを取得
   */
  def findProductInventorySummary(
    productId: ProductId
  ): Future[Option[ProductInventorySummary]] = {
    val query = sql"""
      SELECT
        商品ID,
        SUM(現在庫数) AS 総在庫数,
        SUM(引当済数) AS 総引当済数,
        SUM(有効在庫数) AS 総有効在庫数
      FROM 在庫
      WHERE 商品ID = ${productId.value}
        AND 削除フラグ = false
      GROUP BY 商品ID
    """.as[(String, Int, Int, Int)]

    db.run(query.headOption).map {
      case Some((prodId, onHand, reserved, available)) =>
        Some(ProductInventorySummary(
          productId = ProductId(prodId),
          totalQuantityOnHand = onHand,
          totalQuantityReserved = reserved,
          totalQuantityAvailable = available
        ))
      case None => None
    }
  }

  /**
   * 倉庫別在庫内訳を取得
   */
  def findWarehouseBreakdown(
    productId: ProductId
  ): Future[List[WarehouseInventory]] = {
    val query = sql"""
      SELECT
        倉庫コード,
        SUM(現在庫数) AS 在庫数,
        SUM(引当済数) AS 引当済数,
        SUM(有効在庫数) AS 有効在庫数
      FROM 在庫
      WHERE 商品ID = ${productId.value}
        AND 削除フラグ = false
      GROUP BY 倉庫コード
      ORDER BY 倉庫コード
    """.as[(String, Int, Int, Int)]

    db.run(query).map { results =>
      results.map { case (warehouseCode, onHand, reserved, available) =>
        WarehouseInventory(
          warehouseCode = WarehouseCode(warehouseCode),
          quantityOnHand = onHand,
          quantityReserved = reserved,
          quantityAvailable = available
        )
      }.toList
    }
  }

  /**
   * 区画別在庫内訳を取得
   */
  def findZoneBreakdown(
    productId: ProductId
  ): Future[List[ZoneInventory]] = {
    val query = sql"""
      SELECT
        倉庫コード,
        区画番号,
        現在庫数,
        引当済数,
        有効在庫数
      FROM 在庫
      WHERE 商品ID = ${productId.value}
        AND 削除フラグ = false
      ORDER BY 倉庫コード, 区画番号
    """.as[(String, Int, Int, Int, Int)]

    db.run(query).map { results =>
      results.map { case (warehouseCode, zoneNumber, onHand, reserved, available) =>
        ZoneInventory(
          warehouseCode = WarehouseCode(warehouseCode),
          zoneNumber = ZoneNumber(zoneNumber),
          quantityOnHand = onHand,
          quantityReserved = reserved,
          quantityAvailable = available
        )
      }.toList
    }
  }
}
```

#### GraphQLリゾルバ実装

```scala
// modules/query/interface-adapter/src/main/scala/.../graphql/ProductInventoryResolver.scala
import sangria.schema._

object ProductInventoryResolver {

  val QueryType: ObjectType[QueryContext, Unit] = ObjectType(
    "Query",
    fields[QueryContext, Unit](
      Field(
        "productInventorySummary",
        OptionType(ProductInventorySummaryType),
        arguments = List(
          Argument("productId", IDType)
        ),
        resolve = ctx => {
          val productId = ProductId(ctx.arg[String]("productId"))
          ctx.ctx.inventoryDao.findProductInventorySummary(productId)
        }
      ),

      Field(
        "productInventorySummaries",
        ProductInventorySummaryConnectionType,
        arguments = List(
          Argument("category", OptionInputType(ProductCategoryType)),
          Argument("storageCondition", OptionInputType(StorageConditionType)),
          Argument("availableOnly", OptionInputType(BooleanType)),
          Argument("first", OptionInputType(IntType)),
          Argument("after", OptionInputType(StringType))
        ),
        resolve = ctx => {
          val category = ctx.argOpt[ProductCategory]("category")
          val storageCondition = ctx.argOpt[StorageCondition]("storageCondition")
          val availableOnly = ctx.argOpt[Boolean]("availableOnly").getOrElse(false)
          val first = ctx.argOpt[Int]("first").getOrElse(20)
          val after = ctx.argOpt[String]("after")

          ctx.ctx.inventoryDao.findProductInventorySummaries(
            category,
            storageCondition,
            availableOnly,
            first,
            after
          )
        }
      )
    )
  )

  val ProductInventorySummaryType: ObjectType[QueryContext, ProductInventorySummary] = ObjectType(
    "ProductInventorySummary",
    fields[QueryContext, ProductInventorySummary](
      Field("productId", IDType, resolve = _.value.productId.value),
      Field("totalQuantityOnHand", IntType, resolve = _.value.totalQuantityOnHand),
      Field("totalQuantityReserved", IntType, resolve = _.value.totalQuantityReserved),
      Field("totalQuantityAvailable", IntType, resolve = _.value.totalQuantityAvailable),

      // DataLoaderを使用して商品情報を取得（N+1問題を回避）
      Field(
        "product",
        ProductType,
        resolve = ctx => ctx.ctx.productLoader.load(ctx.value.productId)
      ),

      // 倉庫別内訳
      Field(
        "warehouseBreakdown",
        ListType(WarehouseInventoryType),
        resolve = ctx => ctx.ctx.inventoryDao.findWarehouseBreakdown(ctx.value.productId)
      ),

      // 区画別内訳
      Field(
        "zoneBreakdown",
        ListType(ZoneInventoryType),
        resolve = ctx => ctx.ctx.inventoryDao.findZoneBreakdown(ctx.value.productId)
      )
    )
  )
}
```

### 8.1.2 カテゴリ別・保管条件別集計

商品カテゴリ（食品類、日用品）や保管条件（常温、冷蔵、冷凍）ごとの在庫を集計します。

#### GraphQLスキーマ

```graphql
# カテゴリ別在庫集計
type CategoryInventorySummary {
  """カテゴリ"""
  category: ProductCategory!

  """商品数"""
  productCount: Int!

  """総在庫数"""
  totalQuantityOnHand: Int!

  """総引当済数"""
  totalQuantityReserved: Int!

  """総有効在庫数"""
  totalQuantityAvailable: Int!

  """保管条件別内訳"""
  storageConditionBreakdown: [StorageConditionInventory!]!
}

# 保管条件別在庫
type StorageConditionInventory {
  """保管条件"""
  storageCondition: StorageCondition!

  """商品数"""
  productCount: Int!

  """総在庫数"""
  totalQuantityOnHand: Int!

  """総引当済数"""
  totalQuantityReserved: Int!

  """総有効在庫数"""
  totalQuantityAvailable: Int!
}

# Enum型
enum ProductCategory {
  FOOD       # 食品類
  DAILY_NECESSITIES  # 日用品
}

enum StorageCondition {
  ROOM_TEMPERATURE  # 常温
  REFRIGERATED      # 冷蔵
  FROZEN            # 冷凍
}

type Query {
  """カテゴリ別在庫集計"""
  categoryInventorySummaries: [CategoryInventorySummary!]!

  """保管条件別在庫集計"""
  storageConditionInventorySummaries: [StorageConditionInventory!]!
}
```

#### SQL実装

```scala
/**
 * カテゴリ別在庫集計
 */
def findCategoryInventorySummaries(): Future[List[CategoryInventorySummary]] = {
  val query = sql"""
    SELECT
      p.カテゴリ,
      COUNT(DISTINCT p.商品ID) AS 商品数,
      COALESCE(SUM(i.現在庫数), 0) AS 総在庫数,
      COALESCE(SUM(i.引当済数), 0) AS 総引当済数,
      COALESCE(SUM(i.有効在庫数), 0) AS 総有効在庫数
    FROM 商品 p
    LEFT JOIN 在庫 i ON p.商品ID = i.商品ID AND i.削除フラグ = false
    WHERE p.ステータス = '有効'
    GROUP BY p.カテゴリ
    ORDER BY p.カテゴリ
  """.as[(String, Int, Int, Int, Int)]

  db.run(query).map { results =>
    results.map { case (category, productCount, onHand, reserved, available) =>
      CategoryInventorySummary(
        category = ProductCategory.fromString(category),
        productCount = productCount,
        totalQuantityOnHand = onHand,
        totalQuantityReserved = reserved,
        totalQuantityAvailable = available
      )
    }.toList
  }
}

/**
 * 保管条件別在庫集計（カテゴリ指定）
 */
def findStorageConditionBreakdown(
  category: ProductCategory
): Future[List[StorageConditionInventory]] = {
  val query = sql"""
    SELECT
      p.保管条件,
      COUNT(DISTINCT p.商品ID) AS 商品数,
      COALESCE(SUM(i.現在庫数), 0) AS 総在庫数,
      COALESCE(SUM(i.引当済数), 0) AS 総引当済数,
      COALESCE(SUM(i.有効在庫数), 0) AS 総有効在庫数
    FROM 商品 p
    LEFT JOIN 在庫 i ON p.商品ID = i.商品ID AND i.削除フラグ = false
    WHERE p.カテゴリ = ${category.toString}
      AND p.ステータス = '有効'
    GROUP BY p.保管条件
    ORDER BY p.保管条件
  """.as[(String, Int, Int, Int, Int)]

  db.run(query).map { results =>
    results.map { case (condition, productCount, onHand, reserved, available) =>
      StorageConditionInventory(
        storageCondition = StorageCondition.fromString(condition),
        productCount = productCount,
        totalQuantityOnHand = onHand,
        totalQuantityReserved = reserved,
        totalQuantityAvailable = available
      )
    }.toList
  }
}
```

### 8.1.3 倉庫別・区画別在庫一覧

3拠点（東京、大阪、福岡）、9区画（各倉庫3区画）の在庫状況を表示します。

#### GraphQLスキーマ

```graphql
# 倉庫別在庫一覧
type WarehouseInventoryList {
  """倉庫情報"""
  warehouse: Warehouse!

  """区画別在庫"""
  zoneInventories: [ZoneInventoryDetail!]!

  """倉庫合計在庫数"""
  totalQuantityOnHand: Int!

  """倉庫合計引当済数"""
  totalQuantityReserved: Int!

  """倉庫合計有効在庫数"""
  totalQuantityAvailable: Int!

  """倉庫容量使用率（%）"""
  capacityUtilization: Float!
}

# 区画別在庫詳細
type ZoneInventoryDetail {
  """区画情報"""
  zone: WarehouseZone!

  """商品別在庫"""
  productInventories: [ProductInventoryInZone!]!

  """区画合計在庫数"""
  totalQuantityOnHand: Int!

  """区画容量使用率（%）"""
  utilizationRate: Float!
}

# 区画内の商品在庫
type ProductInventoryInZone {
  """商品情報"""
  product: Product!

  """在庫数"""
  quantityOnHand: Int!

  """引当済数"""
  quantityReserved: Int!

  """有効在庫数"""
  quantityAvailable: Int!

  """最終受入日時"""
  lastReceivedAt: DateTime
}

type Query {
  """倉庫別在庫一覧を取得"""
  warehouseInventoryList(
    """倉庫コードでフィルタ"""
    warehouseCode: String
  ): [WarehouseInventoryList!]!

  """区画別在庫詳細を取得"""
  zoneInventoryDetail(
    warehouseCode: String!
    zoneNumber: Int!
  ): ZoneInventoryDetail
}
```

#### SQL実装（区画容量使用率を含む）

```scala
/**
 * 区画別在庫詳細を取得
 */
def findZoneInventoryDetail(
  warehouseCode: WarehouseCode,
  zoneNumber: ZoneNumber
): Future[Option[ZoneInventoryDetail]] = {
  // 区画内の商品別在庫を取得
  val inventoryQuery = sql"""
    SELECT
      i.商品ID,
      i.現在庫数,
      i.引当済数,
      i.有効在庫数,
      i.最終受入日時
    FROM 在庫 i
    WHERE i.倉庫コード = ${warehouseCode.value}
      AND i.区画番号 = ${zoneNumber.value}
      AND i.削除フラグ = false
    ORDER BY i.最終受入日時 DESC
  """.as[(String, Int, Int, Int, Option[java.time.LocalDateTime])]

  // 区画情報と容量を取得
  val zoneQuery = sql"""
    SELECT
      区画容量,
      区画タイプ
    FROM 倉庫別区画
    WHERE 倉庫コード = ${warehouseCode.value}
      AND 区画番号 = ${zoneNumber.value}
  """.as[(Int, String)].headOption

  for {
    inventories <- db.run(inventoryQuery)
    zoneInfo <- db.run(zoneQuery)
  } yield {
    zoneInfo.map { case (capacity, zoneType) =>
      val totalQuantity = inventories.map(_._2).sum
      val utilizationRate = if (capacity > 0) {
        (totalQuantity.toDouble / capacity.toDouble) * 100.0
      } else {
        0.0
      }

      ZoneInventoryDetail(
        zone = WarehouseZone(warehouseCode, zoneNumber, ZoneType.fromString(zoneType)),
        productInventories = inventories.map { case (productId, onHand, reserved, available, receivedAt) =>
          ProductInventoryInZone(
            productId = ProductId(productId),
            quantityOnHand = onHand,
            quantityReserved = reserved,
            quantityAvailable = available,
            lastReceivedAt = receivedAt
          )
        }.toList,
        totalQuantityOnHand = totalQuantity,
        utilizationRate = utilizationRate
      )
    }
  }
}
```

---

## 8.2 マテリアライズドビューの活用

集計クエリのパフォーマンスを向上させるため、マテリアライズドビューを使用します。

### 8.2.1 在庫集計ビューの作成

```sql
-- 商品別在庫集計ビュー
CREATE MATERIALIZED VIEW 商品別在庫集計 AS
SELECT
  p.商品ID,
  p.商品名,
  p.カテゴリ,
  p.保管条件,
  COUNT(DISTINCT i.在庫ID) AS 在庫レコード数,
  COALESCE(SUM(i.現在庫数), 0) AS 総在庫数,
  COALESCE(SUM(i.引当済数), 0) AS 総引当済数,
  COALESCE(SUM(i.有効在庫数), 0) AS 総有効在庫数,
  MAX(i.更新日時) AS 最終更新日時
FROM 商品 p
LEFT JOIN 在庫 i ON p.商品ID = i.商品ID AND i.削除フラグ = false
WHERE p.ステータス = '有効'
GROUP BY p.商品ID, p.商品名, p.カテゴリ, p.保管条件;

-- インデックスを作成
CREATE UNIQUE INDEX idx_商品別在庫集計_商品ID ON 商品別在庫集計(商品ID);
CREATE INDEX idx_商品別在庫集計_カテゴリ ON 商品別在庫集計(カテゴリ);
CREATE INDEX idx_商品別在庫集計_保管条件 ON 商品別在庫集計(保管条件);
CREATE INDEX idx_商品別在庫集計_総有効在庫数 ON 商品別在庫集計(総有効在庫数);

COMMENT ON MATERIALIZED VIEW 商品別在庫集計 IS '商品別の在庫集計（高速検索用）';
```

```sql
-- 倉庫・区画別在庫集計ビュー
CREATE MATERIALIZED VIEW 倉庫区画別在庫集計 AS
SELECT
  i.倉庫コード,
  i.区画番号,
  wz.区画タイプ,
  wz.区画容量,
  COUNT(DISTINCT i.商品ID) AS 商品数,
  COALESCE(SUM(i.現在庫数), 0) AS 総在庫数,
  COALESCE(SUM(i.引当済数), 0) AS 総引当済数,
  COALESCE(SUM(i.有効在庫数), 0) AS 総有効在庫数,
  CASE
    WHEN wz.区画容量 > 0 THEN
      (COALESCE(SUM(i.現在庫数), 0)::FLOAT / wz.区画容量::FLOAT) * 100.0
    ELSE 0.0
  END AS 使用率パーセント,
  MAX(i.更新日時) AS 最終更新日時
FROM 在庫 i
INNER JOIN 倉庫別区画 wz ON i.倉庫コード = wz.倉庫コード AND i.区画番号 = wz.区画番号
WHERE i.削除フラグ = false
GROUP BY i.倉庫コード, i.区画番号, wz.区画タイプ, wz.区画容量;

-- インデックス
CREATE UNIQUE INDEX idx_倉庫区画別在庫集計_PK ON 倉庫区画別在庫集計(倉庫コード, 区画番号);
CREATE INDEX idx_倉庫区画別在庫集計_倉庫 ON 倉庫区画別在庫集計(倉庫コード);
CREATE INDEX idx_倉庫区画別在庫集計_区画タイプ ON 倉庫区画別在庫集計(区画タイプ);
CREATE INDEX idx_倉庫区画別在庫集計_使用率 ON 倉庫区画別在庫集計(使用率パーセント);

COMMENT ON MATERIALIZED VIEW 倉庫区画別在庫集計 IS '倉庫・区画別の在庫集計と使用率';
```

### 8.2.2 マテリアライズドビューの更新戦略

#### リアルタイム更新 vs バッチ更新

在庫管理システムでは、1日約2,000件の受払処理があります。更新頻度に応じて戦略を選択します。

**戦略1：イベント駆動のリアルタイム更新**

```scala
// Read Model Updaterで在庫イベントを受信したときにビューを更新
class InventoryEventHandler(db: Database)(implicit ec: ExecutionContext) {

  def handleStockReceived(event: StockReceived): Future[Unit] = {
    for {
      // 在庫テーブルを更新
      _ <- updateInventoryTable(event)

      // マテリアライズドビューを部分的にリフレッシュ
      _ <- refreshMaterializedViewForProduct(event.productId)
    } yield ()
  }

  private def refreshMaterializedViewForProduct(
    productId: ProductId
  ): Future[Unit] = {
    // 該当商品のみをリフレッシュ（PostgreSQL 9.4以降）
    val query = sqlu"""
      REFRESH MATERIALIZED VIEW CONCURRENTLY 商品別在庫集計
      WHERE 商品ID = ${productId.value}
    """

    db.run(query).map(_ => ())
  }
}
```

**戦略2：定期バッチ更新**

```scala
// 定期的にマテリアライズドビューを更新
import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import scala.concurrent.duration._

object MaterializedViewRefreshActor {

  sealed trait Command
  case object Refresh extends Command

  def apply(db: Database): Behavior[Command] = {
    Behaviors.setup { context =>
      // 5分ごとにリフレッシュ
      context.system.scheduler.scheduleAtFixedRate(
        initialDelay = 5.minutes,
        interval = 5.minutes
      )(() => context.self ! Refresh)(context.executionContext)

      Behaviors.receiveMessage {
        case Refresh =>
          context.log.info("Refreshing materialized views...")

          // 並行リフレッシュ（ロックなし）
          db.run(sqlu"REFRESH MATERIALIZED VIEW CONCURRENTLY 商品別在庫集計")
            .flatMap(_ => db.run(sqlu"REFRESH MATERIALIZED VIEW CONCURRENTLY 倉庫区画別在庫集計"))
            .onComplete {
              case Success(_) =>
                context.log.info("Materialized views refreshed successfully")
              case Failure(ex) =>
                context.log.error("Failed to refresh materialized views", ex)
            }(context.executionContext)

          Behaviors.same
      }
    }
  }
}
```

**推奨戦略**：
- **商品別在庫集計**：5分ごとのバッチ更新（CONCURRENTLYオプションでロックなし）
- **倉庫区画別在庫集計**：5分ごとのバッチ更新
- 低在庫アラートなど重要なクエリは、マテリアライズドビューを使用

### 8.2.3 マテリアライズドビューを使用したクエリ

```scala
/**
 * 低在庫商品一覧を取得（マテリアライズドビュー使用）
 */
def findLowStockProducts(threshold: Int): Future[List[LowStockProduct]] = {
  val query = sql"""
    SELECT
      商品ID,
      商品名,
      カテゴリ,
      保管条件,
      総有効在庫数,
      最終更新日時
    FROM 商品別在庫集計
    WHERE 総有効在庫数 < $threshold
      AND 総有効在庫数 >= 0
    ORDER BY 総有効在庫数 ASC, 商品名
    LIMIT 100
  """.as[(String, String, String, String, Int, java.time.LocalDateTime)]

  db.run(query).map { results =>
    results.map { case (id, name, category, condition, available, updatedAt) =>
      LowStockProduct(
        productId = ProductId(id),
        productName = name,
        category = ProductCategory.fromString(category),
        storageCondition = StorageCondition.fromString(condition),
        availableQuantity = available,
        lastUpdatedAt = updatedAt
      )
    }.toList
  }
}

/**
 * 区画使用率の高い順に取得（マテリアライズドビュー使用）
 */
def findHighUtilizationZones(): Future[List[ZoneUtilization]] = {
  val query = sql"""
    SELECT
      倉庫コード,
      区画番号,
      区画タイプ,
      商品数,
      総在庫数,
      区画容量,
      使用率パーセント
    FROM 倉庫区画別在庫集計
    WHERE 使用率パーセント >= 80.0
    ORDER BY 使用率パーセント DESC
  """.as[(String, Int, String, Int, Int, Int, Double)]

  db.run(query).map { results =>
    results.map { case (warehouse, zone, zoneType, productCount, quantity, capacity, utilization) =>
      ZoneUtilization(
        warehouseCode = WarehouseCode(warehouse),
        zoneNumber = ZoneNumber(zone),
        zoneType = ZoneType.fromString(zoneType),
        productCount = productCount,
        totalQuantity = quantity,
        capacity = capacity,
        utilizationRate = utilization
      )
    }.toList
  }
}
```

---

## 8.3 複雑なGraphQLクエリ

### 8.3.1 ネストされたリレーション

商品 → 在庫 → 区画 → 倉庫のような深いネストを効率的に処理します。

#### GraphQLクエリ例

```graphql
query GetProductWithInventories($productId: ID!) {
  product(id: $productId) {
    id
    name
    sku
    category
    storageCondition

    # 在庫情報
    inventories {
      id
      quantityOnHand
      quantityReserved
      quantityAvailable

      # 区画情報
      zone {
        id
        zoneNumber
        zoneType
        capacity

        # 倉庫情報
        warehouse {
          id
          code
          name
          location
          floorArea
        }
      }
    }
  }
}
```

#### リゾルバ実装（DataLoaderなし）

```scala
// ❌ N+1問題が発生する実装例
val ProductType: ObjectType[QueryContext, Product] = ObjectType(
  "Product",
  fields[QueryContext, Product](
    Field("id", IDType, resolve = _.value.id.value),
    Field("name", StringType, resolve = _.value.name.value),
    Field("sku", StringType, resolve = _.value.sku.value),

    // N+1問題：商品ごとにDBクエリが発生
    Field(
      "inventories",
      ListType(InventoryType),
      resolve = ctx => {
        // 商品が100個あれば、100回DBアクセス！
        ctx.ctx.inventoryDao.findByProductId(ctx.value.id)
      }
    )
  )
)

val InventoryType: ObjectType[QueryContext, Inventory] = ObjectType(
  "Inventory",
  fields[QueryContext, Inventory](
    Field("id", IDType, resolve = _.value.id.value),
    Field("quantityOnHand", IntType, resolve = _.value.quantityOnHand),

    // さらにN+1問題：在庫ごとにDBクエリが発生
    Field(
      "zone",
      ZoneType,
      resolve = ctx => {
        ctx.ctx.zoneDao.find(ctx.value.warehouseCode, ctx.value.zoneNumber)
      }
    )
  )
)
```

### 8.3.2 DataLoaderによるN+1問題の解決

DataLoaderを使用して、バッチクエリで効率的にデータを取得します。

#### DataLoader実装

```scala
// modules/query/interface-adapter/src/main/scala/.../graphql/DataLoaders.scala
import org.dataloader.{DataLoader, DataLoaderFactory, DataLoaderOptions}
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.jdk.FutureConverters._

class DataLoaders(
  productDao: ProductDao,
  inventoryDao: InventoryDao,
  zoneDao: WarehouseZoneDao,
  warehouseDao: WarehouseDao
)(implicit ec: ExecutionContext) {

  /**
   * 商品DataLoader
   */
  val productLoader: DataLoader[ProductId, Product] = DataLoaderFactory.newDataLoader(
    (productIds: java.util.List[ProductId]) => {
      // バッチで一度に取得
      productDao.findByIds(productIds.asScala.toList).map { products =>
        val productMap = products.map(p => p.id -> p).toMap
        productIds.asScala.map(id => productMap.get(id).orNull).asJava
      }.asJava
    },
    DataLoaderOptions.newOptions().setCachingEnabled(true)
  )

  /**
   * 在庫DataLoader（商品IDで検索）
   */
  val inventoryByProductLoader: DataLoader[ProductId, java.util.List[Inventory]] =
    DataLoaderFactory.newDataLoader(
      (productIds: java.util.List[ProductId]) => {
        inventoryDao.findByProductIds(productIds.asScala.toList).map { inventories =>
          val inventoryMap = inventories.groupBy(_.productId)
          productIds.asScala.map { id =>
            inventoryMap.getOrElse(id, List.empty).asJava
          }.asJava
        }.asJava
      }
    )

  /**
   * 区画DataLoader
   */
  val zoneLoader: DataLoader[(WarehouseCode, ZoneNumber), WarehouseZone] =
    DataLoaderFactory.newDataLoader(
      (keys: java.util.List[(WarehouseCode, ZoneNumber)]) => {
        zoneDao.findByKeys(keys.asScala.toList).map { zones =>
          val zoneMap = zones.map(z => (z.warehouseCode, z.zoneNumber) -> z).toMap
          keys.asScala.map(key => zoneMap.get(key).orNull).asJava
        }.asJava
      }
    )

  /**
   * 倉庫DataLoader
   */
  val warehouseLoader: DataLoader[WarehouseCode, Warehouse] =
    DataLoaderFactory.newDataLoader(
      (warehouseCodes: java.util.List[WarehouseCode]) => {
        warehouseDao.findByCodes(warehouseCodes.asScala.toList).map { warehouses =>
          val warehouseMap = warehouses.map(w => w.code -> w).toMap
          warehouseCodes.asScala.map(code => warehouseMap.get(code).orNull).asJava
        }.asJava
      }
    )

  /**
   * 全DataLoaderをディスパッチ
   */
  def dispatch(): Future[Unit] = {
    Future.sequence(List(
      productLoader.dispatch().asScala,
      inventoryByProductLoader.dispatch().asScala,
      zoneLoader.dispatch().asScala,
      warehouseLoader.dispatch().asScala
    )).map(_ => ())
  }
}
```

#### バッチクエリDAO実装

```scala
// modules/query/interface-adapter/src/main/scala/.../dao/ProductDao.scala
class ProductDao(db: Database) {

  /**
   * 複数の商品IDで一括取得
   */
  def findByIds(productIds: List[ProductId]): Future[List[Product]] = {
    if (productIds.isEmpty) {
      Future.successful(List.empty)
    } else {
      val ids = productIds.map(_.value).mkString("','")
      val query = sql"""
        SELECT
          商品ID, 商品名, SKU, カテゴリ, 保管条件, 説明, 単位
        FROM 商品
        WHERE 商品ID IN (#$ids)
          AND ステータス = '有効'
      """.as[(String, String, String, String, String, Option[String], Option[String])]

      db.run(query).map { results =>
        results.map { case (id, name, sku, category, condition, desc, unit) =>
          Product(
            id = ProductId(id),
            name = ProductName(name),
            sku = SKU(sku),
            category = ProductCategory.fromString(category),
            storageCondition = StorageCondition.fromString(condition),
            description = desc,
            unit = unit
          )
        }.toList
      }
    }
  }
}

// modules/query/interface-adapter/src/main/scala/.../dao/InventoryDao.scala
class InventoryDao(db: Database) {

  /**
   * 複数の商品IDで在庫を一括取得
   */
  def findByProductIds(productIds: List[ProductId]): Future[List[Inventory]] = {
    if (productIds.isEmpty) {
      Future.successful(List.empty)
    } else {
      val ids = productIds.map(_.value).mkString("','")
      val query = sql"""
        SELECT
          在庫ID, 商品ID, 倉庫コード, 区画番号,
          現在庫数, 引当済数, 有効在庫数
        FROM 在庫
        WHERE 商品ID IN (#$ids)
          AND 削除フラグ = false
        ORDER BY 倉庫コード, 区画番号
      """.as[(String, String, String, Int, Int, Int, Int)]

      db.run(query).map { results =>
        results.map { case (id, productId, warehouse, zone, onHand, reserved, available) =>
          Inventory(
            id = InventoryId(id),
            productId = ProductId(productId),
            warehouseCode = WarehouseCode(warehouse),
            zoneNumber = ZoneNumber(zone),
            quantityOnHand = onHand,
            quantityReserved = reserved,
            quantityAvailable = available
          )
        }.toList
      }
    }
  }
}
```

#### DataLoaderを使用したリゾルバ

```scala
// ✅ DataLoaderでN+1問題を解決
val ProductType: ObjectType[QueryContext, Product] = ObjectType(
  "Product",
  fields[QueryContext, Product](
    Field("id", IDType, resolve = _.value.id.value),
    Field("name", StringType, resolve = _.value.name.value),
    Field("sku", StringType, resolve = _.value.sku.value),

    // DataLoaderを使用：バッチクエリで効率的に取得
    Field(
      "inventories",
      ListType(InventoryType),
      resolve = ctx => {
        ctx.ctx.dataLoaders.inventoryByProductLoader.load(ctx.value.id).asScala
          .map(_.asScala.toList)
      }
    )
  )
)

val InventoryType: ObjectType[QueryContext, Inventory] = ObjectType(
  "Inventory",
  fields[QueryContext, Inventory](
    Field("id", IDType, resolve = _.value.id.value),
    Field("quantityOnHand", IntType, resolve = _.value.quantityOnHand),

    // DataLoaderを使用
    Field(
      "zone",
      ZoneType,
      resolve = ctx => {
        val key = (ctx.value.warehouseCode, ctx.value.zoneNumber)
        ctx.ctx.dataLoaders.zoneLoader.load(key).asScala
      }
    )
  )
)

val ZoneType: ObjectType[QueryContext, WarehouseZone] = ObjectType(
  "WarehouseZone",
  fields[QueryContext, WarehouseZone](
    Field("id", IDType, resolve = _.value.id.value),
    Field("zoneNumber", IntType, resolve = _.value.zoneNumber.value),
    Field("zoneType", ZoneTypeEnum, resolve = _.value.zoneType),

    // DataLoaderを使用
    Field(
      "warehouse",
      WarehouseType,
      resolve = ctx => {
        ctx.ctx.dataLoaders.warehouseLoader.load(ctx.value.warehouseCode).asScala
      }
    )
  )
)
```

#### QueryContextの設定

```scala
case class QueryContext(
  productDao: ProductDao,
  inventoryDao: InventoryDao,
  zoneDao: WarehouseZoneDao,
  warehouseDao: WarehouseDao,
  dataLoaders: DataLoaders
)

// GraphQL実行時にDataLoaderをディスパッチ
def executeQuery(query: String, variables: JsObject): Future[JsValue] = {
  val dataLoaders = new DataLoaders(productDao, inventoryDao, zoneDao, warehouseDao)
  val context = QueryContext(productDao, inventoryDao, zoneDao, warehouseDao, dataLoaders)

  Executor.execute(
    schema = GraphQLSchema,
    queryAst = queryAst,
    userContext = context,
    variables = variables,
    middleware = List(
      // クエリ実行後にDataLoaderをディスパッチ
      new Middleware[QueryContext] {
        override def afterQuery(
          queryVal: Value,
          ctx: MiddlewareQueryContext[QueryContext, _, _]
        ): Unit = {
          ctx.ctx.dataLoaders.dispatch()
        }
      }
    )
  )
}
```

**効果**：
- 商品100個を取得する場合
  - DataLoaderなし：100 + 100 + 100 + 100 = 400クエリ
  - DataLoaderあり：1 + 1 + 1 + 1 = 4クエリ（100倍高速化！）

### 8.3.3 フィルタリングとページネーション

複雑なフィルタリング条件とページネーションを実装します。

#### GraphQLスキーマ

```graphql
# フィルタ入力型
input ProductInventoryFilter {
  """カテゴリでフィルタ"""
  category: ProductCategory

  """保管条件でフィルタ"""
  storageCondition: StorageCondition

  """倉庫コードでフィルタ"""
  warehouseCode: String

  """区画タイプでフィルタ"""
  zoneType: ZoneType

  """在庫あり商品のみ"""
  availableOnly: Boolean

  """低在庫商品のみ（閾値以下）"""
  lowStockThreshold: Int

  """商品名で検索（部分一致）"""
  nameContains: String
}

# ソート順
enum ProductInventorySortField {
  PRODUCT_NAME
  TOTAL_QUANTITY_ON_HAND
  TOTAL_QUANTITY_AVAILABLE
  LAST_UPDATED_AT
}

enum SortOrder {
  ASC
  DESC
}

input ProductInventorySortInput {
  field: ProductInventorySortField!
  order: SortOrder!
}

type Query {
  """商品在庫一覧（フィルタ・ソート・ページネーション対応）"""
  productInventories(
    """フィルタ条件"""
    filter: ProductInventoryFilter

    """ソート条件"""
    sort: ProductInventorySortInput

    """ページネーション"""
    first: Int
    after: String
    last: Int
    before: String
  ): ProductInventoryConnection!
}
```

#### DAO実装

```scala
/**
 * 商品在庫一覧を取得（フィルタ・ソート・ページネーション）
 */
def findProductInventories(
  filter: Option[ProductInventoryFilter],
  sort: Option[ProductInventorySortInput],
  limit: Int,
  offset: Int
): Future[(List[ProductInventorySummary], Int)] = {

  // WHERE句を動的に構築
  val whereConditions = buildWhereConditions(filter)
  val whereClause = if (whereConditions.nonEmpty) {
    "WHERE " + whereConditions.mkString(" AND ")
  } else {
    ""
  }

  // ORDER BY句を構築
  val orderByClause = sort match {
    case Some(s) =>
      val field = s.field match {
        case ProductInventorySortField.ProductName => "p.商品名"
        case ProductInventorySortField.TotalQuantityOnHand => "総在庫数"
        case ProductInventorySortField.TotalQuantityAvailable => "総有効在庫数"
        case ProductInventorySortField.LastUpdatedAt => "最終更新日時"
      }
      val order = if (s.order == SortOrder.Desc) "DESC" else "ASC"
      s"ORDER BY $field $order"
    case None =>
      "ORDER BY p.商品名"
  }

  // 件数取得クエリ
  val countQuery = sql"""
    SELECT COUNT(DISTINCT p.商品ID)
    FROM 商品 p
    LEFT JOIN 在庫 i ON p.商品ID = i.商品ID AND i.削除フラグ = false
    #$whereClause
  """.as[Int].head

  // データ取得クエリ
  val dataQuery = sql"""
    SELECT
      p.商品ID,
      SUM(COALESCE(i.現在庫数, 0)) AS 総在庫数,
      SUM(COALESCE(i.引当済数, 0)) AS 総引当済数,
      SUM(COALESCE(i.有効在庫数, 0)) AS 総有効在庫数,
      MAX(i.更新日時) AS 最終更新日時
    FROM 商品 p
    LEFT JOIN 在庫 i ON p.商品ID = i.商品ID AND i.削除フラグ = false
    #$whereClause
    GROUP BY p.商品ID
    #$orderByClause
    LIMIT #$limit OFFSET #$offset
  """.as[(String, Int, Int, Int, Option[java.time.LocalDateTime])]

  for {
    totalCount <- db.run(countQuery)
    data <- db.run(dataQuery)
  } yield {
    val summaries = data.map { case (productId, onHand, reserved, available, updatedAt) =>
      ProductInventorySummary(
        productId = ProductId(productId),
        totalQuantityOnHand = onHand,
        totalQuantityReserved = reserved,
        totalQuantityAvailable = available,
        lastUpdatedAt = updatedAt
      )
    }.toList

    (summaries, totalCount)
  }
}

private def buildWhereConditions(
  filter: Option[ProductInventoryFilter]
): List[String] = {
  filter.map { f =>
    var conditions = List("p.ステータス = '有効'")

    f.category.foreach { cat =>
      conditions = conditions :+ s"p.カテゴリ = '${cat.toString}'"
    }

    f.storageCondition.foreach { cond =>
      conditions = conditions :+ s"p.保管条件 = '${cond.toString}'"
    }

    f.warehouseCode.foreach { code =>
      conditions = conditions :+ s"i.倉庫コード = '$code'"
    }

    f.zoneType.foreach { zt =>
      conditions = conditions :+ s"EXISTS (SELECT 1 FROM 倉庫別区画 wz WHERE wz.倉庫コード = i.倉庫コード AND wz.区画番号 = i.区画番号 AND wz.区画タイプ = '${zt.toString}')"
    }

    if (f.availableOnly.getOrElse(false)) {
      conditions = conditions :+ "COALESCE(i.有効在庫数, 0) > 0"
    }

    f.lowStockThreshold.foreach { threshold =>
      conditions = conditions :+ s"COALESCE(i.有効在庫数, 0) < $threshold"
    }

    f.nameContains.foreach { name =>
      conditions = conditions :+ s"p.商品名 LIKE '%$name%'"
    }

    conditions
  }.getOrElse(List("p.ステータス = '有効'"))
}
```

---

## まとめ

この章では、複雑なRead Modelクエリの実装について学びました。

### 学んだこと

1. **在庫照会のRead Model設計**
   - 商品別、倉庫別、区画別の集計クエリ
   - カテゴリ別・保管条件別の在庫集計
   - 区画容量使用率の計算

2. **マテリアライズドビューの活用**
   - 集計クエリのパフォーマンス向上
   - リアルタイム更新 vs バッチ更新の選択
   - CONCURRENTLY オプションによるロックフリー更新

3. **複雑なGraphQLクエリ**
   - ネストされたリレーション（Product → Inventory → Zone → Warehouse）
   - DataLoaderによるN+1問題の解決（100倍高速化）
   - フィルタリング、ソート、ページネーション

4. **パフォーマンス最適化**
   - バッチクエリによる効率化
   - インデックス戦略
   - クエリプランの最適化

### 次のステップ

次章では、DynamoDB Streamsによるイベント順序保証とRead Model更新の整合性について学びます。

**第9章：イベントの順序保証**
- DynamoDB Streamsの順序保証の仕組み
- Sequence Numberによる制御
- 重複イベントの検出と対処
- 欠落イベントの検知

複雑なクエリの実装は、ユーザー体験に直結する重要な部分です。DataLoaderとマテリアライズドビューを活用することで、高速で使いやすいAPIを提供できます。

---

## 参考資料

### 書籍
- **"GraphQL in Action"** by Samer Buna（GraphQLのベストプラクティス）
- **"SQL Performance Explained"** by Markus Winand（クエリ最適化）

### オンラインリソース
- [Sangria Documentation](https://sangria-graphql.github.io/)（Scala GraphQLライブラリ）
- [DataLoader Pattern](https://github.com/graphql/dataloader)（N+1問題の解決）
- [PostgreSQL Materialized Views](https://www.postgresql.org/docs/current/rules-materializedviews.html)

### 関連章
- 第2章：データモデルの設計 - Read Modelのスキーマ設計
- 第9章：イベントの順序保証 - Read Model更新の整合性
