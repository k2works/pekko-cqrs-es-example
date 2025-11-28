# 第3部 第12章：高度なトピック

## 本章の目的

在庫管理システムの発展的な機能と将来の拡張について学びます：

- **在庫予測**: 機械学習を使用した需要予測と自動発注
- **マルチテナント対応**: 複数企業での共有利用を可能にするテナント分離
- **グローバル展開**: マルチリージョンデプロイとレイテンシ最適化

これらの高度なトピックは、在庫管理システムをさらにスケールさせ、ビジネス価値を高めるための発展的な機能です。

## 12.1 在庫予測

### 12.1.1 過去の販売データ分析

在庫予測を行うには、まず過去の受払データを分析して傾向を把握します。

**時系列データの集計**

```scala
// modules/query/interface-adapter/src/main/scala/adapters/analytics/InventoryTimeSeriesAnalyzer.scala
package adapters.analytics

import domain.model._
import adapters.dao.InventoryHistoryDao
import scala.concurrent.{Future, ExecutionContext}
import java.time.{LocalDate, DayOfWeek}

class InventoryTimeSeriesAnalyzer(
  historyDao: InventoryHistoryDao
)(implicit ec: ExecutionContext) {

  /**
   * 日次受払データの集計
   *
   * @param productId 商品ID
   * @param startDate 開始日
   * @param endDate 終了日
   * @return 日次の入庫数・出庫数のリスト
   */
  def analyzeDailyTransactions(
    productId: ProductId,
    startDate: LocalDate,
    endDate: LocalDate
  ): Future[List[DailyTransaction]] = {
    historyDao.aggregateByProductAndDate(productId, startDate, endDate).map { results =>
      results.map { case (date, receiveQty, shipQty) =>
        DailyTransaction(
          date = date,
          received = Quantity(receiveQty),
          shipped = Quantity(shipQty),
          net = Quantity(receiveQty - shipQty),
          dayOfWeek = date.getDayOfWeek
        )
      }
    }
  }

  /**
   * 曜日別の傾向分析
   *
   * 曜日ごとの平均出庫数を計算
   */
  def analyzeWeekdayPattern(
    productId: ProductId,
    startDate: LocalDate,
    endDate: LocalDate
  ): Future[Map[DayOfWeek, Double]] = {
    analyzeDailyTransactions(productId, startDate, endDate).map { transactions =>
      transactions.groupBy(_.dayOfWeek).map { case (dayOfWeek, txs) =>
        val avgShipped = txs.map(_.shipped.value).sum.toDouble / txs.size.toDouble
        dayOfWeek -> avgShipped
      }
    }
  }

  /**
   * 移動平均の計算
   *
   * @param window 移動平均の期間（日数）
   */
  def calculateMovingAverage(
    productId: ProductId,
    startDate: LocalDate,
    endDate: LocalDate,
    window: Int = 7
  ): Future[List[(LocalDate, Double)]] = {
    analyzeDailyTransactions(productId, startDate, endDate).map { transactions =>
      val sorted = transactions.sortBy(_.date)

      sorted.sliding(window).map { windowTxs =>
        val avgShipped = windowTxs.map(_.shipped.value).sum.toDouble / window.toDouble
        (windowTxs.last.date, avgShipped)
      }.toList
    }
  }

  /**
   * 季節性の検出
   *
   * 月ごとの出庫傾向を分析
   */
  def detectSeasonality(
    productId: ProductId,
    startDate: LocalDate,
    endDate: LocalDate
  ): Future[Map[Int, Double]] = {
    analyzeDailyTransactions(productId, startDate, endDate).map { transactions =>
      transactions.groupBy(_.date.getMonthValue).map { case (month, txs) =>
        val avgShipped = txs.map(_.shipped.value).sum.toDouble / txs.size.toDouble
        month -> avgShipped
      }
    }
  }
}

final case class DailyTransaction(
  date: LocalDate,
  received: Quantity,
  shipped: Quantity,
  net: Quantity,
  dayOfWeek: DayOfWeek
)
```

**SQL実装（DAO）**

```scala
// modules/query/interface-adapter/src/main/scala/adapters/dao/InventoryHistoryDao.scala
package adapters.dao

import slick.jdbc.PostgresProfile.api._
import scala.concurrent.{Future, ExecutionContext}
import java.time.LocalDate

class InventoryHistoryDao(db: Database)(implicit ec: ExecutionContext) {

  /**
   * 商品・日付別の受払集計
   */
  def aggregateByProductAndDate(
    productId: ProductId,
    startDate: LocalDate,
    endDate: LocalDate
  ): Future[List[(LocalDate, Int, Int)]] = {
    val query = sql"""
      SELECT
        DATE(受払日時) AS 日付,
        COALESCE(SUM(CASE WHEN 受払区分 = '入庫' THEN 受払数量 ELSE 0 END), 0) AS 入庫数,
        COALESCE(SUM(CASE WHEN 受払区分 = '出庫' THEN 受払数量 ELSE 0 END), 0) AS 出庫数
      FROM 受払履歴
      WHERE 商品ID = ${productId.value}
        AND DATE(受払日時) >= ${startDate}
        AND DATE(受払日時) <= ${endDate}
      GROUP BY DATE(受払日時)
      ORDER BY DATE(受払日時)
    """.as[(LocalDate, Int, Int)]

    db.run(query).map(_.toList)
  }
}
```

### 12.1.2 機械学習による需要予測

簡易的な線形回帰モデルを使用して需要予測を実装します。

**需要予測モデル**

```scala
// modules/query/interface-adapter/src/main/scala/adapters/ml/DemandForecastModel.scala
package adapters.ml

import domain.model._
import scala.math._
import java.time.LocalDate

class DemandForecastModel {

  /**
   * 単純な線形回帰による需要予測
   *
   * y = a + b*x
   * - y: 予測出庫数
   * - x: 日数（基準日からの経過日数）
   * - a: 切片
   * - b: 傾き
   */
  def forecast(
    historicalData: List[DailyTransaction],
    forecastDays: Int
  ): List[ForecastResult] = {
    require(historicalData.nonEmpty, "Historical data must not be empty")

    // 最小二乗法でa, bを計算
    val baseDate = historicalData.head.date
    val dataPoints = historicalData.map { tx =>
      val x = baseDate.until(tx.date, java.time.temporal.ChronoUnit.DAYS).toDouble
      val y = tx.shipped.value.toDouble
      (x, y)
    }

    val (slope, intercept) = calculateLinearRegression(dataPoints)

    // 予測
    val lastDate = historicalData.last.date
    (1 to forecastDays).map { day =>
      val forecastDate = lastDate.plusDays(day)
      val x = baseDate.until(forecastDate, java.time.temporal.ChronoUnit.DAYS).toDouble
      val predicted = intercept + slope * x

      ForecastResult(
        date = forecastDate,
        predictedDemand = Quantity(max(0, predicted.toInt)), // 負の値は0に
        confidenceInterval = calculateConfidenceInterval(dataPoints, slope, intercept, x)
      )
    }.toList
  }

  /**
   * 最小二乗法による線形回帰
   *
   * @return (傾き, 切片)
   */
  private def calculateLinearRegression(dataPoints: List[(Double, Double)]): (Double, Double) = {
    val n = dataPoints.size.toDouble
    val sumX = dataPoints.map(_._1).sum
    val sumY = dataPoints.map(_._2).sum
    val sumXY = dataPoints.map { case (x, y) => x * y }.sum
    val sumX2 = dataPoints.map { case (x, _) => x * x }.sum

    val slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX)
    val intercept = (sumY - slope * sumX) / n

    (slope, intercept)
  }

  /**
   * 信頼区間の計算（簡易版）
   *
   * 標準誤差を使用して95%信頼区間を計算
   */
  private def calculateConfidenceInterval(
    dataPoints: List[(Double, Double)],
    slope: Double,
    intercept: Double,
    x: Double
  ): ConfidenceInterval = {
    val predicted = intercept + slope * x

    // 残差の計算
    val residuals = dataPoints.map { case (xi, yi) =>
      val yPredicted = intercept + slope * xi
      yi - yPredicted
    }

    // 標準誤差
    val standardError = sqrt(residuals.map(r => r * r).sum / (dataPoints.size - 2))

    // 95%信頼区間（t分布の代わりに簡易的に1.96を使用）
    val margin = 1.96 * standardError

    ConfidenceInterval(
      lower = Quantity(max(0, (predicted - margin).toInt)),
      upper = Quantity((predicted + margin).toInt)
    )
  }

  /**
   * 移動平均による需要予測（シンプル版）
   */
  def forecastWithMovingAverage(
    historicalData: List[DailyTransaction],
    forecastDays: Int,
    window: Int = 7
  ): List[ForecastResult] = {
    require(historicalData.size >= window, s"Historical data must have at least $window days")

    // 直近window日間の平均を取得
    val recentAvg = historicalData.takeRight(window).map(_.shipped.value).sum.toDouble / window.toDouble

    val lastDate = historicalData.last.date
    (1 to forecastDays).map { day =>
      ForecastResult(
        date = lastDate.plusDays(day),
        predictedDemand = Quantity(recentAvg.toInt),
        confidenceInterval = ConfidenceInterval(
          lower = Quantity((recentAvg * 0.8).toInt),
          upper = Quantity((recentAvg * 1.2).toInt)
        )
      )
    }.toList
  }
}

final case class ForecastResult(
  date: LocalDate,
  predictedDemand: Quantity,
  confidenceInterval: ConfidenceInterval
)

final case class ConfidenceInterval(
  lower: Quantity,
  upper: Quantity
)
```

**予測の実行と可視化**

```scala
// modules/query/interface-adapter/src/main/scala/adapters/analytics/DemandForecastService.scala
package adapters.analytics

import domain.model._
import adapters.ml.{DemandForecastModel, ForecastResult}
import scala.concurrent.{Future, ExecutionContext}
import java.time.LocalDate

class DemandForecastService(
  timeSeriesAnalyzer: InventoryTimeSeriesAnalyzer,
  forecastModel: DemandForecastModel
)(implicit ec: ExecutionContext) {

  /**
   * 需要予測の実行
   *
   * @param productId 商品ID
   * @param forecastDays 予測日数
   * @param historicalDays 学習に使用する過去データ日数
   */
  def predict(
    productId: ProductId,
    forecastDays: Int = 30,
    historicalDays: Int = 90
  ): Future[ForecastReport] = {
    val endDate = LocalDate.now()
    val startDate = endDate.minusDays(historicalDays)

    for {
      // 過去データの取得
      historicalData <- timeSeriesAnalyzer.analyzeDailyTransactions(productId, startDate, endDate)

      // 曜日パターン分析
      weekdayPattern <- timeSeriesAnalyzer.analyzeWeekdayPattern(productId, startDate, endDate)

      // 季節性分析
      seasonality <- timeSeriesAnalyzer.detectSeasonality(productId, startDate, endDate)

      // 需要予測
      forecast = forecastModel.forecast(historicalData, forecastDays)

    } yield {
      ForecastReport(
        productId = productId,
        generatedAt = LocalDate.now(),
        historicalPeriod = (startDate, endDate),
        forecastPeriod = (endDate.plusDays(1), endDate.plusDays(forecastDays)),
        historicalData = historicalData,
        forecast = forecast,
        weekdayPattern = weekdayPattern,
        seasonality = seasonality,
        recommendations = generateRecommendations(forecast, historicalData)
      )
    }
  }

  /**
   * 推奨事項の生成
   */
  private def generateRecommendations(
    forecast: List[ForecastResult],
    historicalData: List[DailyTransaction]
  ): List[String] = {
    val avgHistorical = historicalData.map(_.shipped.value).sum.toDouble / historicalData.size.toDouble
    val avgForecast = forecast.map(_.predictedDemand.value).sum.toDouble / forecast.size.toDouble

    val recommendations = scala.collection.mutable.ListBuffer.empty[String]

    // 需要増加の検出
    if (avgForecast > avgHistorical * 1.2) {
      recommendations += s"需要増加が予測されます（過去平均: ${avgHistorical.toInt}個/日 → 予測平均: ${avgForecast.toInt}個/日）。在庫補充を検討してください。"
    }

    // 需要減少の検出
    if (avgForecast < avgHistorical * 0.8) {
      recommendations += s"需要減少が予測されます（過去平均: ${avgHistorical.toInt}個/日 → 予測平均: ${avgForecast.toInt}個/日）。過剰在庫に注意してください。"
    }

    // 在庫不足リスクの検出
    val maxForecast = forecast.map(_.predictedDemand.value).max
    if (maxForecast > avgHistorical * 1.5) {
      recommendations += s"需要ピークが予測されます（最大: ${maxForecast}個/日）。安全在庫を確保してください。"
    }

    recommendations.toList
  }
}

final case class ForecastReport(
  productId: ProductId,
  generatedAt: LocalDate,
  historicalPeriod: (LocalDate, LocalDate),
  forecastPeriod: (LocalDate, LocalDate),
  historicalData: List[DailyTransaction],
  forecast: List[ForecastResult],
  weekdayPattern: Map[java.time.DayOfWeek, Double],
  seasonality: Map[Int, Double],
  recommendations: List[String]
)
```

### 12.1.3 自動発注の実装

需要予測に基づいて自動発注を提案します。

```scala
// modules/query/interface-adapter/src/main/scala/adapters/purchasing/AutoPurchaseRecommender.scala
package adapters.purchasing

import domain.model._
import adapters.analytics.{DemandForecastService, ForecastReport}
import adapters.dao.InventoryDao
import scala.concurrent.{Future, ExecutionContext}
import java.time.LocalDate

class AutoPurchaseRecommender(
  inventoryDao: InventoryDao,
  forecastService: DemandForecastService
)(implicit ec: ExecutionContext) {

  /**
   * 自動発注推奨の生成
   *
   * @param productId 商品ID
   * @param leadTime リードタイム（日数）
   * @param safetyStockDays 安全在庫日数
   */
  def recommend(
    productId: ProductId,
    leadTime: Int = 7,
    safetyStockDays: Int = 14
  ): Future[PurchaseRecommendation] = {
    for {
      // 現在の在庫状況を取得
      currentInventory <- inventoryDao.getTotalByProduct(productId)

      // 需要予測を取得
      forecast <- forecastService.predict(productId, forecastDays = leadTime + safetyStockDays)

    } yield {
      // 予測期間中の総需要
      val totalDemand = forecast.forecast.map(_.predictedDemand.value).sum

      // 安全在庫
      val avgDailyDemand = totalDemand.toDouble / forecast.forecast.size.toDouble
      val safetyStock = (avgDailyDemand * safetyStockDays).toInt

      // 発注点
      val reorderPoint = (avgDailyDemand * leadTime).toInt + safetyStock

      // 発注推奨数量
      val recommendedOrderQty = if (currentInventory.value < reorderPoint) {
        // 発注点を下回っている場合、安全在庫までの補充量を計算
        val orderQty = reorderPoint - currentInventory.value + (avgDailyDemand * leadTime).toInt
        Some(Quantity(orderQty))
      } else {
        None
      }

      PurchaseRecommendation(
        productId = productId,
        currentInventory = currentInventory,
        avgDailyDemand = avgDailyDemand,
        reorderPoint = Quantity(reorderPoint),
        safetyStock = Quantity(safetyStock),
        leadTime = leadTime,
        recommendedOrderQty = recommendedOrderQty,
        urgency = calculateUrgency(currentInventory, reorderPoint, avgDailyDemand),
        forecast = forecast
      )
    }
  }

  /**
   * 緊急度の計算
   */
  private def calculateUrgency(
    currentInventory: Quantity,
    reorderPoint: Int,
    avgDailyDemand: Double
  ): PurchaseUrgency = {
    val daysUntilStockout = currentInventory.value.toDouble / avgDailyDemand

    if (daysUntilStockout < 3) PurchaseUrgency.Critical
    else if (daysUntilStockout < 7) PurchaseUrgency.High
    else if (currentInventory.value < reorderPoint) PurchaseUrgency.Medium
    else PurchaseUrgency.Low
  }
}

final case class PurchaseRecommendation(
  productId: ProductId,
  currentInventory: Quantity,
  avgDailyDemand: Double,
  reorderPoint: Quantity,
  safetyStock: Quantity,
  leadTime: Int,
  recommendedOrderQty: Option[Quantity],
  urgency: PurchaseUrgency,
  forecast: ForecastReport
)

sealed trait PurchaseUrgency
object PurchaseUrgency {
  case object Critical extends PurchaseUrgency  // 3日以内に在庫切れ
  case object High extends PurchaseUrgency      // 7日以内に在庫切れ
  case object Medium extends PurchaseUrgency    // 発注点を下回っている
  case object Low extends PurchaseUrgency       // 在庫十分
}
```

**自動発注アラートの実装**

```scala
// apps/read-model-updater/src/main/scala/batch/AutoPurchaseBatch.scala
package batch

import domain.model._
import adapters.purchasing.{AutoPurchaseRecommender, PurchaseUrgency}
import notification.SlackNotifier
import org.apache.pekko.actor.typed.ActorSystem
import scala.concurrent.{Future, ExecutionContext}

class AutoPurchaseBatch(
  recommender: AutoPurchaseRecommender,
  productIds: List[ProductId]
)(implicit system: ActorSystem[_], ec: ExecutionContext) {

  /**
   * 全商品の発注推奨をチェック
   */
  def runPurchaseCheck(): Future[List[PurchaseRecommendation]] = {
    Future.traverse(productIds) { productId =>
      recommender.recommend(productId)
    }.map { recommendations =>
      // 発注が必要な商品のみフィルタ
      val needsOrder = recommendations.filter(_.recommendedOrderQty.isDefined)

      // 緊急度別にソート
      val sorted = needsOrder.sortBy(_.urgency match {
        case PurchaseUrgency.Critical => 1
        case PurchaseUrgency.High => 2
        case PurchaseUrgency.Medium => 3
        case PurchaseUrgency.Low => 4
      })

      // Slackに通知
      if (sorted.nonEmpty) {
        system.log.info(s"Found ${sorted.size} products that need ordering")
        sorted.foreach(sendAlert)
      }

      sorted
    }
  }

  private def sendAlert(recommendation: PurchaseRecommendation): Unit = {
    val urgencyEmoji = recommendation.urgency match {
      case PurchaseUrgency.Critical => "🚨"
      case PurchaseUrgency.High => "⚠️"
      case PurchaseUrgency.Medium => "ℹ️"
      case PurchaseUrgency.Low => "✅"
    }

    system.log.warn(
      s"$urgencyEmoji Auto-purchase recommendation: " +
      s"productId=${recommendation.productId.value}, " +
      s"currentInventory=${recommendation.currentInventory.value}, " +
      s"recommendedOrderQty=${recommendation.recommendedOrderQty.map(_.value).getOrElse(0)}, " +
      s"urgency=${recommendation.urgency}"
    )

    // Slack通知の実装は11.3.4を参照
  }
}
```

## 12.2 マルチテナント対応

### 12.2.1 テナント分離戦略

複数企業（テナント）で在庫管理システムを共有する場合、テナント分離戦略が必要です。

**テナント分離の3つのアプローチ**

| アプローチ | 説明 | メリット | デメリット |
|----------|------|---------|----------|
| **Database per Tenant** | テナントごとに専用データベース | 完全な分離、スケーラビリティ | 運用コスト高、マイグレーション複雑 |
| **Schema per Tenant** | 同一データベース内で専用スキーマ | 適度な分離、コスト削減 | スキーマ管理が複雑 |
| **Shared Schema** | 全テナントで同一スキーマ、行レベル分離 | 運用コスト最小、リソース効率的 | 分離が弱い、クエリ性能に注意 |

今回は**Shared Schema（行レベル分離）**を採用します。

### 12.2.2 データの分離とセキュリティ

**テナントIDの追加**

```sql
-- 在庫テーブルにテナントIDを追加
ALTER TABLE 在庫 ADD COLUMN テナントID VARCHAR(50) NOT NULL DEFAULT 'default';

-- 受払履歴テーブルにテナントIDを追加
ALTER TABLE 受払履歴 ADD COLUMN テナントID VARCHAR(50) NOT NULL DEFAULT 'default';

-- テナント別インデックス
CREATE INDEX idx_在庫_テナントID ON 在庫(テナントID);
CREATE INDEX idx_受払履歴_テナントID ON 受払履歴(テナントID);

-- Row Level Security（PostgreSQL）
ALTER TABLE 在庫 ENABLE ROW LEVEL SECURITY;

CREATE POLICY 在庫_tenant_isolation ON 在庫
  USING (テナントID = current_setting('app.current_tenant_id')::VARCHAR);

ALTER TABLE 受払履歴 ENABLE ROW LEVEL SECURITY;

CREATE POLICY 受払履歴_tenant_isolation ON 受払履歴
  USING (テナントID = current_setting('app.current_tenant_id')::VARCHAR);
```

**ドメインモデルの拡張**

```scala
// modules/command/domain/src/main/scala/domain/model/TenantId.scala
package domain.model

final case class TenantId(value: String) extends AnyVal {
  require(value.nonEmpty, "TenantId must not be empty")
  require(value.matches("^[a-zA-Z0-9_-]+$"), "TenantId must be alphanumeric")
}

// Inventoryにテナント情報を追加
final case class Inventory private (
  id: InventoryId,
  tenantId: TenantId,  // 追加
  warehouseCode: WarehouseCode,
  productId: ProductId,
  zoneNumber: ZoneNumber,
  quantityOnHand: Quantity,
  quantityReserved: Quantity,
  version: Version
) extends Entity {
  // ... メソッドは変更なし
}
```

**テナントコンテキストの管理**

```scala
// modules/infrastructure/src/main/scala/tenancy/TenantContext.scala
package tenancy

import domain.model.TenantId
import scala.concurrent.{Future, ExecutionContext}

/**
 * テナントコンテキスト
 *
 * リクエストスコープでテナントIDを管理
 */
object TenantContext {
  private val threadLocal = new ThreadLocal[TenantId]

  def set(tenantId: TenantId): Unit = {
    threadLocal.set(tenantId)
  }

  def get: Option[TenantId] = {
    Option(threadLocal.get())
  }

  def clear(): Unit = {
    threadLocal.remove()
  }

  def require: TenantId = {
    get.getOrElse(throw new IllegalStateException("TenantId not set in context"))
  }

  /**
   * テナントコンテキストを設定して処理を実行
   */
  def withTenant[T](tenantId: TenantId)(f: => T): T = {
    try {
      set(tenantId)
      f
    } finally {
      clear()
    }
  }

  /**
   * 非同期処理用
   */
  def withTenantAsync[T](tenantId: TenantId)(f: => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    set(tenantId)
    f.andThen { case _ => clear() }
  }
}
```

**HTTPミドルウェアでのテナント抽出**

```scala
// apps/command-api/src/main/scala/api/middleware/TenantMiddleware.scala
package api.middleware

import domain.model.TenantId
import tenancy.TenantContext
import org.apache.pekko.http.scaladsl.server.Directive1
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.model.StatusCodes

object TenantMiddleware {

  /**
   * リクエストヘッダーからテナントIDを抽出
   *
   * X-Tenant-ID: tenant-abc
   */
  def extractTenant: Directive1[TenantId] = {
    optionalHeaderValueByName("X-Tenant-ID").flatMap {
      case Some(tenantIdStr) =>
        try {
          val tenantId = TenantId(tenantIdStr)
          TenantContext.set(tenantId)
          provide(tenantId)
        } catch {
          case _: IllegalArgumentException =>
            complete(StatusCodes.BadRequest, "Invalid tenant ID format")
        }

      case None =>
        complete(StatusCodes.BadRequest, "X-Tenant-ID header is required")
    }
  }

  /**
   * テナント認証（簡易版）
   *
   * 実際の実装ではJWTトークン検証などを行う
   */
  def authenticateTenant: Directive1[TenantId] = {
    extractTenant.flatMap { tenantId =>
      // ここで認証ロジックを実装
      // 例: JWTトークンの検証、テナントの存在確認など

      provide(tenantId)
    }
  }
}
```

**DAOでのテナント分離**

```scala
// modules/query/interface-adapter/src/main/scala/adapters/dao/MultiTenantInventoryDao.scala
package adapters.dao

import domain.model._
import tenancy.TenantContext
import slick.jdbc.PostgresProfile.api._
import scala.concurrent.{Future, ExecutionContext}

class MultiTenantInventoryDao(db: Database)(implicit ec: ExecutionContext) {

  /**
   * テナント別在庫取得
   *
   * テナントコンテキストから自動的にフィルタ
   */
  def findByProduct(productId: ProductId): Future[List[Inventory]] = {
    val tenantId = TenantContext.require

    val query = sql"""
      SELECT
        在庫ID, 商品ID, 倉庫コード, 区画番号, 現在庫数, 引当済数, バージョン
      FROM 在庫
      WHERE テナントID = ${tenantId.value}
        AND 商品ID = ${productId.value}
        AND 削除フラグ = false
    """.as[(String, String, String, Int, Int, Int, Int)]

    db.run(query).map { results =>
      results.map { case (invId, prodId, whCode, zone, onHand, reserved, ver) =>
        Inventory(
          id = InventoryId(invId),
          tenantId = tenantId,
          productId = ProductId(prodId),
          warehouseCode = WarehouseCode(whCode),
          zoneNumber = ZoneNumber(zone),
          quantityOnHand = Quantity(onHand),
          quantityReserved = Quantity(reserved),
          version = Version(ver)
        )
      }.toList
    }
  }

  /**
   * PostgreSQL Row Level Securityを使用する場合
   */
  def findByProductWithRLS(productId: ProductId): Future[List[Inventory]] = {
    val tenantId = TenantContext.require

    // セッション変数を設定してRLSを有効化
    val setTenantId = sqlu"SET LOCAL app.current_tenant_id = ${tenantId.value}"

    val query = sql"""
      SELECT
        在庫ID, 商品ID, 倉庫コード, 区画番号, 現在庫数, 引当済数, バージョン
      FROM 在庫
      WHERE 商品ID = ${productId.value}
        AND 削除フラグ = false
    """.as[(String, String, String, Int, Int, Int, Int)]

    db.run(setTenantId.andThen(query).transactionally).map { results =>
      // ... マッピング処理
      List.empty
    }
  }
}
```

### 12.2.3 テナントごとのカスタマイズ

```scala
// modules/infrastructure/src/main/scala/tenancy/TenantConfigRepository.scala
package tenancy

import domain.model.TenantId
import scala.concurrent.{Future, ExecutionContext}

class TenantConfigRepository()(implicit ec: ExecutionContext) {

  /**
   * テナント別設定の取得
   */
  def getConfig(tenantId: TenantId): Future[TenantConfig] = Future {
    // 実際はデータベースから取得
    TenantConfig(
      tenantId = tenantId,
      name = s"Tenant ${tenantId.value}",
      features = TenantFeatures(
        inventoryForecast = true,
        autoPurchase = true,
        multiWarehouse = true
      ),
      limits = TenantLimits(
        maxWarehouses = 10,
        maxProducts = 10000,
        maxDailyTransactions = 5000
      ),
      customization = TenantCustomization(
        lowStockThreshold = 100,
        safetyStockDays = 14,
        leadTimeDays = 7
      )
    )
  }
}

final case class TenantConfig(
  tenantId: TenantId,
  name: String,
  features: TenantFeatures,
  limits: TenantLimits,
  customization: TenantCustomization
)

final case class TenantFeatures(
  inventoryForecast: Boolean,
  autoPurchase: Boolean,
  multiWarehouse: Boolean
)

final case class TenantLimits(
  maxWarehouses: Int,
  maxProducts: Int,
  maxDailyTransactions: Int
)

final case class TenantCustomization(
  lowStockThreshold: Int,
  safetyStockDays: Int,
  leadTimeDays: Int
)
```

## 12.3 グローバル展開

### 12.3.1 マルチリージョンデプロイ

地理的に分散した拠点で在庫管理システムを運用する場合、マルチリージョンデプロイが必要です。

**アーキテクチャ概要**

```plantuml
@startuml
!define RECTANGLE class

cloud "Asia-Pacific Region" as APRegion {
  RECTANGLE "Command API\n(Tokyo)" as CommandAP
  database "DynamoDB\n(ap-northeast-1)" as DynamoAP
  RECTANGLE "Query API\n(Tokyo)" as QueryAP
  database "PostgreSQL\n(RDS ap-northeast-1)" as PostgresAP
}

cloud "Europe Region" as EURegion {
  RECTANGLE "Command API\n(Frankfurt)" as CommandEU
  database "DynamoDB\n(eu-central-1)" as DynamoEU
  RECTANGLE "Query API\n(Frankfurt)" as QueryEU
  database "PostgreSQL\n(RDS eu-central-1)" as PostgresEU
}

cloud "US Region" as USRegion {
  RECTANGLE "Command API\n(Virginia)" as CommandUS
  database "DynamoDB\n(us-east-1)" as DynamoUS
  RECTANGLE "Query API\n(Virginia)" as QueryUS
  database "PostgreSQL\n(RDS us-east-1)" as PostgresUS
}

actor "Asia User" as UserAP
actor "EU User" as UserEU
actor "US User" as UserUS

UserAP --> CommandAP: 書き込み（低レイテンシ）
UserAP --> QueryAP: 読み取り（低レイテンシ）

UserEU --> CommandEU: 書き込み（低レイテンシ）
UserEU --> QueryEU: 読み取り（低レイテンシ）

UserUS --> CommandUS: 書き込み（低レイテンシ）
UserUS --> QueryUS: 読み取り（低レイテンシ）

DynamoAP -[dashed]-> DynamoEU: Global Table\n（自動レプリケーション）
DynamoEU -[dashed]-> DynamoUS: Global Table
DynamoUS -[dashed]-> DynamoAP: Global Table

PostgresAP -[dashed]-> PostgresEU: Read Replica\n（非同期レプリケーション）
PostgresEU -[dashed]-> PostgresUS: Read Replica
PostgresUS -[dashed]-> PostgresAP: Read Replica

note right of DynamoAP
  DynamoDB Global Tablesにより
  各リージョンで書き込み可能
  （マルチマスター）
end note

note right of PostgresAP
  Read Modelは各リージョンで
  独立して更新
  （結果整合性）
end note

@enduml
```

### 12.3.2 地理的分散とレイテンシ最適化

**リージョン別ルーティング**

```scala
// modules/infrastructure/src/main/scala/geo/RegionRouter.scala
package geo

import domain.model.WarehouseCode
import scala.concurrent.{Future, ExecutionContext}

class RegionRouter()(implicit ec: ExecutionContext) {

  /**
   * 倉庫コードからリージョンを決定
   */
  def getRegionForWarehouse(warehouseCode: WarehouseCode): Region = {
    warehouseCode.value match {
      case wh if wh.startsWith("WH-JP") => Region.AsiaPacific
      case wh if wh.startsWith("WH-EU") => Region.Europe
      case wh if wh.startsWith("WH-US") => Region.UnitedStates
      case _ => Region.AsiaPacific // デフォルト
    }
  }

  /**
   * リージョン別のエンドポイント取得
   */
  def getEndpointForRegion(region: Region): String = {
    region match {
      case Region.AsiaPacific => "https://inventory-api.ap-northeast-1.example.com"
      case Region.Europe => "https://inventory-api.eu-central-1.example.com"
      case Region.UnitedStates => "https://inventory-api.us-east-1.example.com"
    }
  }

  /**
   * 最寄りのリージョンを選択（レイテンシベース）
   */
  def selectNearestRegion(clientIp: String): Future[Region] = Future {
    // IPアドレスからジオロケーションを取得
    // 実際の実装ではMaxMind GeoIP2などを使用
    Region.AsiaPacific
  }
}

sealed trait Region
object Region {
  case object AsiaPacific extends Region
  case object Europe extends Region
  case object UnitedStates extends Region
}
```

**DynamoDB Global Tablesの設定**

```scala
// modules/infrastructure/src/main/scala/persistence/GlobalTableConfig.scala
package persistence

object GlobalTableConfig {

  /**
   * DynamoDB Global Tablesの設定
   *
   * aws dynamodb create-global-table \
   *   --global-table-name inventory-events \
   *   --replication-group \
   *     RegionName=ap-northeast-1 \
   *     RegionName=eu-central-1 \
   *     RegionName=us-east-1
   */
  val globalTableConfig = Map(
    "TableName" -> "inventory-events",
    "Regions" -> List(
      "ap-northeast-1",
      "eu-central-1",
      "us-east-1"
    )
  )
}
```

**リージョン間のレプリケーション遅延対策**

```scala
// modules/infrastructure/src/main/scala/geo/ReplicationAwareReadModel.scala
package geo

import domain.model._
import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._

class ReplicationAwareReadModel(
  localDao: InventoryDao,
  regionRouter: RegionRouter
)(implicit ec: ExecutionContext) {

  /**
   * レプリケーション遅延を考慮した読み取り
   *
   * 1. ローカルリージョンから読み取り
   * 2. データが存在しない場合、他のリージョンを試行
   * 3. それでも見つからない場合、短時間待機後にリトライ
   */
  def findByIdWithReplication(
    inventoryId: InventoryId,
    maxRetries: Int = 3,
    retryDelay: FiniteDuration = 500.millis
  ): Future[Option[Inventory]] = {
    def retry(attemptsLeft: Int): Future[Option[Inventory]] = {
      localDao.findById(inventoryId).flatMap {
        case Some(inventory) =>
          Future.successful(Some(inventory))

        case None if attemptsLeft > 0 =>
          // レプリケーション遅延の可能性があるため、待機後にリトライ
          Future {
            Thread.sleep(retryDelay.toMillis)
          }.flatMap(_ => retry(attemptsLeft - 1))

        case None =>
          Future.successful(None)
      }
    }

    retry(maxRetries)
  }
}
```

### 12.3.3 グローバル展開のベストプラクティス

**1. データの局所性（Data Locality）**

```scala
// データを地理的に近い場所に配置
val inventory = Inventory(
  id = InventoryId("INV-JP-001"),
  tenantId = TenantId("tenant-jp"),
  warehouseCode = WarehouseCode("WH-JP-Tokyo"),  // 東京倉庫
  // ... 東京リージョンに配置
)
```

**2. 読み取りの最適化**

- ローカルリージョンから読み取り
- キャッシュ（Redis）の活用
- CDNでの静的コンテンツ配信

**3. 書き込みの最適化**

- マルチマスター構成（DynamoDB Global Tables）
- 非同期レプリケーション
- 競合解決戦略（Last-Write-Wins）

**4. モニタリング**

```scala
// リージョン間レプリケーション遅延のモニタリング
class ReplicationMonitor(
  metricsCollector: MetricsCollector
) {

  def recordReplicationLag(
    sourceRegion: Region,
    targetRegion: Region,
    lagMillis: Long
  ): Unit = {
    metricsCollector.recordReplicationLag(
      sourceRegion.toString,
      targetRegion.toString,
      lagMillis
    )
  }
}
```

## まとめ

### 実装した高度な機能

1. **在庫予測**
   - 時系列データ分析（日次受払、曜日パターン、季節性）
   - 機械学習モデル（線形回帰、移動平均）
   - 自動発注推奨（発注点、安全在庫、緊急度判定）

2. **マルチテナント対応**
   - Shared Schema方式（行レベル分離）
   - PostgreSQL Row Level Security
   - テナントコンテキスト管理
   - テナント別カスタマイズ（機能制限、設定）

3. **グローバル展開**
   - マルチリージョンデプロイ
   - DynamoDB Global Tables（マルチマスター）
   - リージョン別ルーティング
   - レプリケーション遅延対策

### 発展的な考慮事項

1. **在庫予測の高度化**
   - より高度な機械学習モデル（ARIMA、Prophet、LSTMなど）
   - 外部要因の考慮（天候、イベント、プロモーション）
   - A/Bテストによるモデル精度向上

2. **マルチテナントの拡張**
   - テナント間リソース制限（Rate Limiting）
   - テナント別SLA保証
   - テナント別バックアップとリストア

3. **グローバル展開の最適化**
   - エッジコンピューティング
   - 地理的ルーティング最適化
   - リージョン間競合解決の高度化

次章では、これまで学んだ内容をまとめ、実践演習を通じて理解を深めます。
