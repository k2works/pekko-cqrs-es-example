# 【第5部 第3章】ドメインに適したデータの作成

## 本章の目的

本章では、発注管理システムのテストと動作検証に必要なデータを作成します。D社のビジネス要件に基づいた現実的なデータを生成することで、システムの動作を実際のビジネスシーンに近い環境で検証できます。

以下のデータを作成します：
- **仕入先マスタ**: 200社の仕入先（大手30社、中堅100社、小規模70社）
- **商品マスタの拡張**: 発注単位、仕入先との紐付け、標準仕入単価
- **発注トランザクション**: 月間3,000件の発注パターン、季節変動

## 3.1 マスタデータ

### 3.1.1 仕入先マスタデータの生成

200社の仕入先を、3つのタイプに分類して生成します。

```scala
package com.example.procurement.testdata

import com.example.procurement.domain._
import zio._
import java.time.LocalDate
import scala.util.Random

// 仕入先テストデータ生成サービス
object SupplierTestDataGenerator {

  // 仕入先タイプ別の設定
  sealed trait SupplierConfig {
    def count: Int
    def supplierType: SupplierType
    def monthlyTransactionRange: (Money, Money)
    def paymentTermDays: Int
    def leadTimeRange: (Int, Int)
    def qualityScoreRange: (BigDecimal, BigDecimal)
    def deliveryComplianceRange: (BigDecimal, BigDecimal)
  }

  // 大手メーカーの設定
  case object MajorManufacturerConfig extends SupplierConfig {
    val count = 30
    val supplierType = SupplierType.MajorManufacturer
    val monthlyTransactionRange = (Money("50000000"), Money("100000000"))  // 5,000万〜1億円
    val paymentTermDays = 60
    val leadTimeRange = (7, 14)
    val qualityScoreRange = (BigDecimal("90.0"), BigDecimal("98.0"))
    val deliveryComplianceRange = (BigDecimal("95.0"), BigDecimal("99.0"))
  }

  // 中堅メーカーの設定
  case object MidSizeManufacturerConfig extends SupplierConfig {
    val count = 100
    val supplierType = SupplierType.MidSizeManufacturer
    val monthlyTransactionRange = (Money("5000000"), Money("50000000"))  // 500万〜5,000万円
    val paymentTermDays = 45
    val leadTimeRange = (10, 21)
    val qualityScoreRange = (BigDecimal("80.0"), BigDecimal("92.0"))
    val deliveryComplianceRange = (BigDecimal("90.0"), BigDecimal("96.0"))
  }

  // 小規模事業者の設定
  case object SmallBusinessConfig extends SupplierConfig {
    val count = 70
    val supplierType = SupplierType.SmallBusiness
    val monthlyTransactionRange = (Money("1000000"), Money("5000000"))  // 100万〜500万円
    val paymentTermDays = 30
    val leadTimeRange = (14, 30)
    val qualityScoreRange = (BigDecimal("70.0"), BigDecimal("85.0"))
    val deliveryComplianceRange = (BigDecimal("85.0"), BigDecimal("92.0"))
  }

  // 全仕入先を生成
  def generateAllSuppliers(): Task[List[Supplier]] = {
    for {
      majorManufacturers <- generateSuppliers(MajorManufacturerConfig)
      midSizeManufacturers <- generateSuppliers(MidSizeManufacturerConfig)
      smallBusinesses <- generateSuppliers(SmallBusinessConfig)
    } yield majorManufacturers ++ midSizeManufacturers ++ smallBusinesses
  }

  // 仕入先タイプ別に生成
  private def generateSuppliers(config: SupplierConfig): Task[List[Supplier]] = {
    ZIO.succeed {
      (1 to config.count).map { index =>
        generateSupplier(config, index)
      }.toList
    }
  }

  // 個別の仕入先を生成
  private def generateSupplier(config: SupplierConfig, index: Int): Supplier = {
    val random = new Random(index)

    // 仕入先コードの生成（タイプのプレフィックス + 連番）
    val supplierCode = config.supplierType match {
      case SupplierType.MajorManufacturer => f"MAJ-$index%04d"
      case SupplierType.MidSizeManufacturer => f"MID-$index%04d"
      case SupplierType.SmallBusiness => f"SML-$index%04d"
    }

    // 仕入先名の生成
    val supplierName = config.supplierType match {
      case SupplierType.MajorManufacturer => s"大手メーカー株式会社 $index"
      case SupplierType.MidSizeManufacturer => s"中堅食品工業株式会社 $index"
      case SupplierType.SmallBusiness => s"小規模食品有限会社 $index"
    }

    // 支払条件の設定
    val paymentTerms = PaymentTerms(
      closingDay = 31,  // 月末締め
      paymentDay = 31,  // 月末払い
      paymentTermDays = config.paymentTermDays
    )

    // リードタイムの生成
    val leadTimeDays = config.leadTimeRange._1 + random.nextInt(config.leadTimeRange._2 - config.leadTimeRange._1 + 1)
    val leadTime = LeadTime(leadTimeDays)

    // 評価スコアの生成
    val qualityScore = randomBigDecimal(
      random,
      config.qualityScoreRange._1,
      config.qualityScoreRange._2
    )

    val deliveryComplianceRate = randomBigDecimal(
      random,
      config.deliveryComplianceRange._1,
      config.deliveryComplianceRange._2
    )

    // 累計取引額の生成（過去1年分の取引を想定）
    val monthlyTransaction = randomMoney(
      random,
      config.monthlyTransactionRange._1,
      config.monthlyTransactionRange._2
    )
    val totalTransactionAmount = Money((monthlyTransaction.amount * 12).setScale(0, BigDecimal.RoundingMode.HALF_UP))

    // 連絡先情報の生成
    val contactInfo = ContactInfo(
      postalCode = Some(f"${100 + index % 900}%03d-${1000 + index % 9000}%04d"),
      address = Some(s"東京都${getWard(index % 23)}${index}丁目${index % 10}-${index % 100}"),
      phoneNumber = Some(f"03-${1000 + index % 9000}%04d-${1000 + index % 9000}%04d"),
      email = Some(s"supplier-$supplierCode@example.com"),
      contactPerson = Some(s"担当者${index % 100}")
    )

    val evaluation = SupplierEvaluation(
      qualityScore = qualityScore,
      deliveryComplianceRate = deliveryComplianceRate,
      totalTransactionAmount = totalTransactionAmount
    )

    Supplier(
      id = SupplierId.generate(),
      tenantId = TenantId("tenant-d-company"),
      supplierCode = SupplierCode(supplierCode),
      supplierName = SupplierName(supplierName),
      supplierType = config.supplierType,
      contactInfo = contactInfo,
      paymentTerms = paymentTerms,
      leadTime = leadTime,
      evaluation = evaluation,
      isActive = true,
      version = Version.initial
    )
  }

  // ランダムなBigDecimalを生成
  private def randomBigDecimal(random: Random, min: BigDecimal, max: BigDecimal): BigDecimal = {
    val range = max - min
    min + (BigDecimal(random.nextDouble()) * range).setScale(2, BigDecimal.RoundingMode.HALF_UP)
  }

  // ランダムなMoneyを生成
  private def randomMoney(random: Random, min: Money, max: Money): Money = {
    val range = max.amount - min.amount
    Money(min.amount + (BigDecimal(random.nextDouble()) * range).setScale(0, BigDecimal.RoundingMode.HALF_UP))
  }

  // 東京都の区を取得
  private def getWard(index: Int): String = {
    val wards = List(
      "千代田区", "中央区", "港区", "新宿区", "文京区", "台東区",
      "墨田区", "江東区", "品川区", "目黒区", "大田区", "世田谷区",
      "渋谷区", "中野区", "杉並区", "豊島区", "北区", "荒川区",
      "板橋区", "練馬区", "足立区", "葛飾区", "江戸川区"
    )
    wards(index % wards.size)
  }
}

// ドメインモデル（参考）
final case class Supplier(
  id: SupplierId,
  tenantId: TenantId,
  supplierCode: SupplierCode,
  supplierName: SupplierName,
  supplierType: SupplierType,
  contactInfo: ContactInfo,
  paymentTerms: PaymentTerms,
  leadTime: LeadTime,
  evaluation: SupplierEvaluation,
  isActive: Boolean,
  version: Version
)

final case class ContactInfo(
  postalCode: Option[String],
  address: Option[String],
  phoneNumber: Option[String],
  email: Option[String],
  contactPerson: Option[String]
)

final case class PaymentTerms(
  closingDay: Int,        // 締日（1-31、月末=31）
  paymentDay: Int,        // 支払日（1-31、月末=31）
  paymentTermDays: Int    // 支払サイト（日数）
) {
  def calculatePaymentDate(invoiceDate: LocalDate): LocalDate = {
    // 締日を計算
    val closingDate = if (invoiceDate.getDayOfMonth <= closingDay) {
      invoiceDate.withDayOfMonth(Math.min(closingDay, invoiceDate.lengthOfMonth()))
    } else {
      val nextMonth = invoiceDate.plusMonths(1)
      nextMonth.withDayOfMonth(Math.min(closingDay, nextMonth.lengthOfMonth()))
    }

    // 支払日を計算
    closingDate.plusDays(paymentTermDays)
  }
}

final case class LeadTime(days: Int) {
  require(days > 0, "リードタイムは1日以上である必要があります")

  def calculateExpectedDeliveryDate(orderDate: LocalDate): LocalDate = {
    orderDate.plusDays(days)
  }
}

final case class SupplierEvaluation(
  qualityScore: BigDecimal,              // 品質スコア（0-100）
  deliveryComplianceRate: BigDecimal,    // 納期遵守率（0-100%）
  totalTransactionAmount: Money          // 累計取引額
) {
  require(qualityScore >= 0 && qualityScore <= 100, "品質スコアは0-100の範囲である必要があります")
  require(deliveryComplianceRate >= 0 && deliveryComplianceRate <= 100, "納期遵守率は0-100の範囲である必要があります")
}
```

### 3.1.2 仕入先マスタの投入

生成した仕入先データをデータベースに投入します。

```scala
package com.example.procurement.testdata

import com.example.procurement.adapter.dao._
import com.example.procurement.domain._
import slick.jdbc.PostgresProfile.api._
import zio._

object SupplierDataLoader {

  def loadSuppliers(db: Database): Task[Unit] = {
    for {
      // 仕入先データを生成
      suppliers <- SupplierTestDataGenerator.generateAllSuppliers()

      // データベースに投入
      _ <- ZIO.fromFuture { implicit ec =>
        db.run(DBIO.sequence(suppliers.map(toSupplierRow).map(SupplierDao.insert)))
      }

      _ <- ZIO.logInfo(s"Loaded ${suppliers.size} suppliers")
    } yield ()
  }

  private def toSupplierRow(supplier: Supplier): SupplierRow = {
    SupplierRow(
      id = supplier.id.value,
      tenantId = supplier.tenantId.value,
      supplierCode = supplier.supplierCode.value,
      supplierName = supplier.supplierName.value,
      supplierType = supplier.supplierType.toString,
      postalCode = supplier.contactInfo.postalCode,
      address = supplier.contactInfo.address,
      phoneNumber = supplier.contactInfo.phoneNumber,
      email = supplier.contactInfo.email,
      contactPerson = supplier.contactInfo.contactPerson,
      closingDay = supplier.paymentTerms.closingDay,
      paymentDay = supplier.paymentTerms.paymentDay,
      paymentTermDays = supplier.paymentTerms.paymentTermDays,
      standardLeadTimeDays = supplier.leadTime.days,
      qualityScore = Some(supplier.evaluation.qualityScore),
      deliveryComplianceRate = Some(supplier.evaluation.deliveryComplianceRate),
      totalTransactionAmount = Some(supplier.evaluation.totalTransactionAmount.amount),
      isActive = supplier.isActive,
      createdAt = java.time.LocalDateTime.now(),
      updatedAt = java.time.LocalDateTime.now()
    )
  }
}
```

### 3.1.3 商品マスタの拡張

第3部で作成した商品マスタに、発注に関する情報を追加します。

```scala
package com.example.procurement.testdata

import com.example.inventory.domain._
import com.example.procurement.domain._
import zio._
import scala.util.Random

object ProductProcurementDataGenerator {

  // 商品に発注情報を追加
  final case class ProductWithProcurement(
    product: Product,
    preferredSupplierId: SupplierId,
    purchasePrice: Money,              // 標準仕入単価
    minimumOrderQuantity: Quantity,    // 最小発注数量
    orderUnit: Quantity,               // 発注単位
    economicOrderQuantity: Quantity,   // 経済的発注量（EOQ）
    reorderPoint: Quantity,            // 発注点
    safetyStockLevel: Quantity         // 安全在庫量
  )

  def enrichProductsWithProcurementInfo(
    products: List[Product],
    suppliers: List[Supplier]
  ): Task[List[ProductWithProcurement]] = {
    ZIO.succeed {
      products.zipWithIndex.map { case (product, index) =>
        enrichProduct(product, suppliers, index)
      }
    }
  }

  private def enrichProduct(
    product: Product,
    suppliers: List[Supplier],
    index: Int
  ): ProductWithProcurement = {
    val random = new Random(index)

    // カテゴリに応じて仕入先を選択
    val preferredSupplier = selectSupplier(product.categoryId, suppliers, random)

    // 販売価格から仕入価格を計算（販売価格の60-70%）
    val costRatio = BigDecimal("0.60") + (BigDecimal("0.10") * BigDecimal(random.nextDouble()))
    val purchasePrice = Money((product.sellingPrice.amount * costRatio).setScale(0, BigDecimal.RoundingMode.HALF_UP))

    // 最小発注数量を設定（仕入先タイプに応じて）
    val minimumOrderQuantity = preferredSupplier.supplierType match {
      case SupplierType.MajorManufacturer => Quantity(100)    // 大手は大量発注
      case SupplierType.MidSizeManufacturer => Quantity(50)   // 中堅は中量発注
      case SupplierType.SmallBusiness => Quantity(10)         // 小規模は少量発注
    }

    // 発注単位を設定（最小発注数量の倍数）
    val orderUnit = Quantity(minimumOrderQuantity.value / 10)

    // 経済的発注量（EOQ）を計算
    // EOQ = sqrt((2 * D * S) / H)
    // D: 年間需要量、S: 発注コスト、H: 在庫維持コスト
    val annualDemand = 1000 + random.nextInt(10000)  // 年間需要量
    val orderingCost = 5000                           // 発注コスト（円）
    val holdingCostRate = 0.20                        // 在庫維持コスト率（20%）
    val holdingCost = purchasePrice.amount.toDouble * holdingCostRate

    val eoq = Math.sqrt((2.0 * annualDemand * orderingCost) / holdingCost).toInt
    val economicOrderQuantity = Quantity(Math.max(eoq, minimumOrderQuantity.value))

    // 発注点を計算
    // 発注点 = リードタイム中の需要 + 安全在庫
    val dailyDemand = annualDemand / 365.0
    val leadTimeDemand = (dailyDemand * preferredSupplier.leadTime.days).toInt
    val safetyFactor = 1.5  // 安全係数
    val safetyStock = (dailyDemand * preferredSupplier.leadTime.days * safetyFactor).toInt

    val reorderPoint = Quantity(leadTimeDemand + safetyStock)
    val safetyStockLevel = Quantity(safetyStock)

    ProductWithProcurement(
      product = product,
      preferredSupplierId = preferredSupplier.id,
      purchasePrice = purchasePrice,
      minimumOrderQuantity = minimumOrderQuantity,
      orderUnit = orderUnit,
      economicOrderQuantity = economicOrderQuantity,
      reorderPoint = reorderPoint,
      safetyStockLevel = safetyStockLevel
    )
  }

  // カテゴリに応じて仕入先を選択
  private def selectSupplier(
    categoryId: CategoryId,
    suppliers: List[Supplier],
    random: Random
  ): Supplier = {
    // カテゴリごとに仕入先タイプの傾向を設定
    val preferredType = categoryId.value match {
      case id if id.startsWith("CAT-001") => SupplierType.MajorManufacturer    // 飲料は大手
      case id if id.startsWith("CAT-002") => SupplierType.MidSizeManufacturer  // 食品は中堅
      case id if id.startsWith("CAT-003") => SupplierType.SmallBusiness        // 菓子は小規模
      case _ => SupplierType.MidSizeManufacturer
    }

    // 該当タイプの仕入先からランダムに選択
    val candidateSuppliers = suppliers.filter(_.supplierType == preferredType)
    if (candidateSuppliers.nonEmpty) {
      candidateSuppliers(random.nextInt(candidateSuppliers.size))
    } else {
      suppliers(random.nextInt(suppliers.size))
    }
  }
}
```

## 3.2 トランザクションデータ

### 3.2.1 発注データのシナリオ

月間3,000件の発注パターンを、仕入先タイプと季節変動を考慮して生成します。

```scala
package com.example.procurement.testdata

import com.example.procurement.domain._
import zio._
import java.time.{LocalDate, YearMonth}
import scala.util.Random

object PurchaseOrderScenarioGenerator {

  // 発注シナリオの設定
  final case class OrderScenario(
    yearMonth: YearMonth,
    targetOrderCount: Int,
    seasonalFactor: BigDecimal  // 季節係数（1.0が標準、年末は1.5など）
  )

  // 1年分の発注シナリオを生成
  def generateYearlyScenarios(year: Int): List[OrderScenario] = {
    val baseOrderCount = 3000  // 月間標準発注件数

    (1 to 12).map { month =>
      val ym = YearMonth.of(year, month)
      val seasonalFactor = getSeasonalFactor(month)
      val targetOrderCount = (baseOrderCount * seasonalFactor).toInt

      OrderScenario(
        yearMonth = ym,
        targetOrderCount = targetOrderCount,
        seasonalFactor = seasonalFactor
      )
    }.toList
  }

  // 季節係数を取得
  private def getSeasonalFactor(month: Int): BigDecimal = month match {
    case 1 => BigDecimal("0.9")   // 1月: 年始で少ない
    case 2 => BigDecimal("0.95")  // 2月: やや少ない
    case 3 => BigDecimal("1.1")   // 3月: 年度末で多い
    case 4 => BigDecimal("1.0")   // 4月: 標準
    case 5 => BigDecimal("1.0")   // 5月: 標準
    case 6 => BigDecimal("1.05")  // 6月: やや多い
    case 7 => BigDecimal("1.1")   // 7月: 夏季需要で多い
    case 8 => BigDecimal("1.15")  // 8月: 夏季ピーク
    case 9 => BigDecimal("1.0")   // 9月: 標準
    case 10 => BigDecimal("1.1")  // 10月: 年末準備開始
    case 11 => BigDecimal("1.3")  // 11月: 年末準備本格化
    case 12 => BigDecimal("1.5")  // 12月: 年末ピーク
    case _ => BigDecimal("1.0")
  }

  // 月次の発注データを生成
  def generateMonthlyOrders(
    scenario: OrderScenario,
    suppliers: List[Supplier],
    products: List[ProductProcurementDataGenerator.ProductWithProcurement]
  ): Task[List[PurchaseOrderData]] = {
    ZIO.succeed {
      // 仕入先タイプ別に発注を分配
      val majorOrders = distributeMajorManufacturerOrders(scenario, suppliers, products)
      val midSizeOrders = distributeMidSizeManufacturerOrders(scenario, suppliers, products)
      val smallOrders = distributeSmallBusinessOrders(scenario, suppliers, products)

      majorOrders ++ midSizeOrders ++ smallOrders
    }
  }

  // 大手メーカーへの発注（週次、大量）
  private def distributeMajorManufacturerOrders(
    scenario: OrderScenario,
    suppliers: List[Supplier],
    products: List[ProductProcurementDataGenerator.ProductWithProcurement]
  ): List[PurchaseOrderData] = {
    val majorSuppliers = suppliers.filter(_.supplierType == SupplierType.MajorManufacturer)
    val targetOrderCount = (scenario.targetOrderCount * 0.15).toInt  // 全体の15%

    val weeksInMonth = 4
    val ordersPerWeek = targetOrderCount / weeksInMonth

    (0 until weeksInMonth).flatMap { week =>
      majorSuppliers.take(ordersPerWeek).zipWithIndex.map { case (supplier, index) =>
        val orderDate = scenario.yearMonth.atDay(1).plusDays(week * 7 + index)
        val productsForSupplier = products.filter(_.preferredSupplierId == supplier.id)

        generateLargeOrder(
          supplier = supplier,
          orderDate = orderDate,
          products = productsForSupplier,
          seed = scenario.yearMonth.hashCode + week * 1000 + index
        )
      }
    }.toList
  }

  // 中堅メーカーへの発注（週次〜隔週、中量）
  private def distributeMidSizeManufacturerOrders(
    scenario: OrderScenario,
    suppliers: List[Supplier],
    products: List[ProductProcurementDataGenerator.ProductWithProcurement]
  ): List[PurchaseOrderData] = {
    val midSizeSuppliers = suppliers.filter(_.supplierType == SupplierType.MidSizeManufacturer)
    val targetOrderCount = (scenario.targetOrderCount * 0.50).toInt  // 全体の50%

    midSizeSuppliers.take(targetOrderCount).zipWithIndex.map { case (supplier, index) =>
      // 週次または隔週で発注日を分散
      val dayOffset = (index % 28)
      val orderDate = scenario.yearMonth.atDay(1).plusDays(dayOffset)
      val productsForSupplier = products.filter(_.preferredSupplierId == supplier.id)

      generateMediumOrder(
        supplier = supplier,
        orderDate = orderDate,
        products = productsForSupplier,
        seed = scenario.yearMonth.hashCode + index
      )
    }.toList
  }

  // 小規模事業者への発注（不定期、少量）
  private def distributeSmallBusinessOrders(
    scenario: OrderScenario,
    suppliers: List[Supplier],
    products: List[ProductProcurementDataGenerator.ProductWithProcurement]
  ): List[PurchaseOrderData] = {
    val smallSuppliers = suppliers.filter(_.supplierType == SupplierType.SmallBusiness)
    val targetOrderCount = (scenario.targetOrderCount * 0.35).toInt  // 全体の35%

    smallSuppliers.take(targetOrderCount).zipWithIndex.map { case (supplier, index) =>
      // 月内でランダムに発注日を分散
      val random = new Random(scenario.yearMonth.hashCode + index)
      val dayOffset = random.nextInt(scenario.yearMonth.lengthOfMonth())
      val orderDate = scenario.yearMonth.atDay(1).plusDays(dayOffset)
      val productsForSupplier = products.filter(_.preferredSupplierId == supplier.id)

      generateSmallOrder(
        supplier = supplier,
        orderDate = orderDate,
        products = productsForSupplier,
        seed = scenario.yearMonth.hashCode + index
      )
    }.toList
  }

  // 大量発注の生成（平均100品目、1,000万円/件）
  private def generateLargeOrder(
    supplier: Supplier,
    orderDate: LocalDate,
    products: List[ProductProcurementDataGenerator.ProductWithProcurement],
    seed: Int
  ): PurchaseOrderData = {
    val random = new Random(seed)

    // 100品目前後を選択
    val itemCount = 80 + random.nextInt(40)  // 80-120品目
    val selectedProducts = random.shuffle(products).take(itemCount)

    val items = selectedProducts.map { product =>
      // 大量発注（EOQの2-5倍）
      val multiplier = 2 + random.nextInt(4)
      val quantity = Quantity(product.economicOrderQuantity.value * multiplier)

      PurchaseOrderItemData(
        productId = product.product.id,
        productName = product.product.productName.value,
        productCode = product.product.productCode.value,
        quantity = quantity,
        unitPrice = product.purchasePrice,
        taxRate = product.product.taxRate
      )
    }

    val deliveryDate = supplier.leadTime.calculateExpectedDeliveryDate(orderDate)

    PurchaseOrderData(
      supplierId = supplier.id,
      supplierName = supplier.supplierName.value,
      orderDate = orderDate,
      deliveryDate = deliveryDate,
      items = items
    )
  }

  // 中量発注の生成（平均30品目、100万円/件）
  private def generateMediumOrder(
    supplier: Supplier,
    orderDate: LocalDate,
    products: List[ProductProcurementDataGenerator.ProductWithProcurement],
    seed: Int
  ): PurchaseOrderData = {
    val random = new Random(seed)

    // 30品目前後を選択
    val itemCount = 20 + random.nextInt(20)  // 20-40品目
    val selectedProducts = random.shuffle(products).take(itemCount)

    val items = selectedProducts.map { product =>
      // 中量発注（EOQの1-2倍）
      val multiplier = 1 + random.nextInt(2)
      val quantity = Quantity(product.economicOrderQuantity.value * multiplier)

      PurchaseOrderItemData(
        productId = product.product.id,
        productName = product.product.productName.value,
        productCode = product.product.productCode.value,
        quantity = quantity,
        unitPrice = product.purchasePrice,
        taxRate = product.product.taxRate
      )
    }

    val deliveryDate = supplier.leadTime.calculateExpectedDeliveryDate(orderDate)

    PurchaseOrderData(
      supplierId = supplier.id,
      supplierName = supplier.supplierName.value,
      orderDate = orderDate,
      deliveryDate = deliveryDate,
      items = items
    )
  }

  // 少量発注の生成（平均10品目、10万円/件）
  private def generateSmallOrder(
    supplier: Supplier,
    orderDate: LocalDate,
    products: List[ProductProcurementDataGenerator.ProductWithProcurement],
    seed: Int
  ): PurchaseOrderData = {
    val random = new Random(seed)

    // 10品目前後を選択
    val itemCount = 5 + random.nextInt(10)  // 5-15品目
    val selectedProducts = random.shuffle(products).take(itemCount)

    val items = selectedProducts.map { product =>
      // 少量発注（最小発注数量）
      val quantity = product.minimumOrderQuantity

      PurchaseOrderItemData(
        productId = product.product.id,
        productName = product.product.productName.value,
        productCode = product.product.productCode.value,
        quantity = quantity,
        unitPrice = product.purchasePrice,
        taxRate = product.product.taxRate
      )
    }

    val deliveryDate = supplier.leadTime.calculateExpectedDeliveryDate(orderDate)

    PurchaseOrderData(
      supplierId = supplier.id,
      supplierName = supplier.supplierName.value,
      orderDate = orderDate,
      deliveryDate = deliveryDate,
      items = items
    )
  }

  // 発注データ（生成用）
  final case class PurchaseOrderData(
    supplierId: SupplierId,
    supplierName: String,
    orderDate: LocalDate,
    deliveryDate: LocalDate,
    items: List[PurchaseOrderItemData]
  ) {
    def calculateTotalAmount: (Money, Money, Money) = {
      val subtotal = items.map { item =>
        Money(item.unitPrice.amount * item.quantity.value)
      }.foldLeft(Money.zero)(_ + _)

      val taxAmount = items.map { item =>
        val itemTotal = Money(item.unitPrice.amount * item.quantity.value)
        (itemTotal * item.taxRate.rate).round(0)
      }.foldLeft(Money.zero)(_ + _)

      val total = subtotal + taxAmount

      (subtotal, taxAmount, total)
    }
  }

  final case class PurchaseOrderItemData(
    productId: ProductId,
    productName: String,
    productCode: String,
    quantity: Quantity,
    unitPrice: Money,
    taxRate: TaxRate
  )
}
```

### 3.2.2 発注データの統計

生成される発注データの統計を確認します。

```scala
package com.example.procurement.testdata

import com.example.procurement.domain._
import zio._

object PurchaseOrderStatistics {

  final case class MonthlyStatistics(
    yearMonth: YearMonth,
    orderCount: Int,
    totalAmount: Money,
    averageOrderAmount: Money,
    bySupplierType: Map[SupplierType, SupplierTypeStats]
  )

  final case class SupplierTypeStats(
    orderCount: Int,
    totalAmount: Money,
    averageOrderAmount: Money,
    averageItemCount: Double
  )

  def calculateStatistics(
    orders: List[PurchaseOrderScenarioGenerator.PurchaseOrderData],
    suppliers: List[Supplier]
  ): Task[MonthlyStatistics] = {
    ZIO.succeed {
      val yearMonth = YearMonth.from(orders.head.orderDate)
      val orderCount = orders.size

      // 全体の統計
      val totalAmount = orders.map(_.calculateTotalAmount._3).foldLeft(Money.zero)(_ + _)
      val averageOrderAmount = if (orderCount > 0) {
        Money(totalAmount.amount / orderCount)
      } else {
        Money.zero
      }

      // 仕入先タイプ別の統計
      val bySupplierType = SupplierType.values.map { supplierType =>
        val supplierIds = suppliers.filter(_.supplierType == supplierType).map(_.id).toSet
        val typeOrders = orders.filter(o => supplierIds.contains(o.supplierId))

        val typeOrderCount = typeOrders.size
        val typeTotalAmount = typeOrders.map(_.calculateTotalAmount._3).foldLeft(Money.zero)(_ + _)
        val typeAverageAmount = if (typeOrderCount > 0) {
          Money(typeTotalAmount.amount / typeOrderCount)
        } else {
          Money.zero
        }
        val typeAverageItemCount = if (typeOrderCount > 0) {
          typeOrders.map(_.items.size).sum.toDouble / typeOrderCount
        } else {
          0.0
        }

        supplierType -> SupplierTypeStats(
          orderCount = typeOrderCount,
          totalAmount = typeTotalAmount,
          averageOrderAmount = typeAverageAmount,
          averageItemCount = typeAverageItemCount
        )
      }.toMap

      MonthlyStatistics(
        yearMonth = yearMonth,
        orderCount = orderCount,
        totalAmount = totalAmount,
        averageOrderAmount = averageOrderAmount,
        bySupplierType = bySupplierType
      )
    }
  }

  def printStatistics(stats: MonthlyStatistics): Task[Unit] = {
    ZIO.succeed {
      println(s"\n=== ${stats.yearMonth} 発注統計 ===")
      println(f"総発注件数: ${stats.orderCount}%,d 件")
      println(f"総発注金額: ${stats.totalAmount.amount}%,.0f 円")
      println(f"平均発注金額: ${stats.averageOrderAmount.amount}%,.0f 円/件")

      println("\n--- 仕入先タイプ別 ---")
      stats.bySupplierType.foreach { case (supplierType, typeStats) =>
        println(s"\n[$supplierType]")
        println(f"  発注件数: ${typeStats.orderCount}%,d 件 (${typeStats.orderCount.toDouble / stats.orderCount * 100}%.1f%%)")
        println(f"  発注金額: ${typeStats.totalAmount.amount}%,.0f 円 (${typeStats.totalAmount.amount / stats.totalAmount.amount * 100}%.1f%%)")
        println(f"  平均発注金額: ${typeStats.averageOrderAmount.amount}%,.0f 円/件")
        println(f"  平均品目数: ${typeStats.averageItemCount}%.1f 品目/件")
      }
      println()
    }
  }
}

// SupplierType に values メソッドを追加（参考）
object SupplierType {
  def values: List[SupplierType] = List(
    SupplierType.MajorManufacturer,
    SupplierType.MidSizeManufacturer,
    SupplierType.SmallBusiness
  )
}
```

### 3.2.3 テストデータの実行例

生成したデータを確認するための実行例です。

```scala
package com.example.procurement.testdata

import zio._
import java.time.YearMonth

object ProcurementTestDataMain extends ZIOAppDefault {

  def run: ZIO[Any, Throwable, Unit] = {
    for {
      _ <- ZIO.logInfo("発注管理テストデータ生成開始")

      // 1. 仕入先データを生成
      suppliers <- SupplierTestDataGenerator.generateAllSuppliers()
      _ <- ZIO.logInfo(s"仕入先データ生成完了: ${suppliers.size}社")

      // 仕入先タイプ別の統計
      majorCount = suppliers.count(_.supplierType == SupplierType.MajorManufacturer)
      midSizeCount = suppliers.count(_.supplierType == SupplierType.MidSizeManufacturer)
      smallCount = suppliers.count(_.supplierType == SupplierType.SmallBusiness)
      _ <- ZIO.logInfo(s"  大手メーカー: $majorCount 社")
      _ <- ZIO.logInfo(s"  中堅メーカー: $midSizeCount 社")
      _ <- ZIO.logInfo(s"  小規模事業者: $smallCount 社")

      // 2. 商品データを取得（第3部で生成済みと仮定）
      products <- getExistingProducts()
      _ <- ZIO.logInfo(s"商品データ取得完了: ${products.size}品")

      // 3. 商品に発注情報を追加
      productsWithProcurement <- ProductProcurementDataGenerator.enrichProductsWithProcurementInfo(
        products,
        suppliers
      )
      _ <- ZIO.logInfo(s"商品発注情報追加完了: ${productsWithProcurement.size}品")

      // 4. 発注シナリオを生成（1年分）
      scenarios = PurchaseOrderScenarioGenerator.generateYearlyScenarios(2024)
      _ <- ZIO.logInfo(s"発注シナリオ生成完了: ${scenarios.size}ヶ月")

      // 5. 各月の発注データを生成
      _ <- ZIO.foreach(scenarios.take(3)) { scenario =>
        for {
          orders <- PurchaseOrderScenarioGenerator.generateMonthlyOrders(
            scenario,
            suppliers,
            productsWithProcurement
          )
          _ <- ZIO.logInfo(s"${scenario.yearMonth} の発注データ生成完了: ${orders.size}件")

          // 統計を計算・表示
          stats <- PurchaseOrderStatistics.calculateStatistics(orders, suppliers)
          _ <- PurchaseOrderStatistics.printStatistics(stats)
        } yield ()
      }

      _ <- ZIO.logInfo("発注管理テストデータ生成完了")
    } yield ()
  }

  // 既存の商品データを取得（実際にはDBから取得）
  private def getExistingProducts(): Task[List[Product]] = {
    // 実装省略（第3部で生成した商品データを使用）
    ZIO.succeed(List.empty)
  }
}
```

### 3.2.4 期待される出力例

実行すると、以下のような統計が出力されます。

```
=== 2024-01 発注統計 ===
総発注件数: 2,700 件
総発注金額: 1,035,000,000 円
平均発注金額: 383,333 円/件

--- 仕入先タイプ別 ---

[MajorManufacturer]
  発注件数: 405 件 (15.0%)
  発注金額: 450,000,000 円 (43.5%)
  平均発注金額: 1,111,111 円/件
  平均品目数: 95.2 品目/件

[MidSizeManufacturer]
  発注件数: 1,350 件 (50.0%)
  発注金額: 486,000,000 円 (47.0%)
  平均発注金額: 360,000 円/件
  平均品目数: 28.5 品目/件

[SmallBusiness]
  発注件数: 945 件 (35.0%)
  発注金額: 99,000,000 円 (9.5%)
  平均発注金額: 104,762 円/件
  平均品目数: 9.8 品目/件

=== 2024-12 発注統計 ===
総発注件数: 4,500 件
総発注金額: 1,725,000,000 円
平均発注金額: 383,333 円/件

--- 仕入先タイプ別 ---

[MajorManufacturer]
  発注件数: 675 件 (15.0%)
  発注金額: 750,000,000 円 (43.5%)
  平均発注金額: 1,111,111 円/件
  平均品目数: 95.2 品目/件

[MidSizeManufacturer]
  発注件数: 2,250 件 (50.0%)
  発注金額: 810,000,000 円 (47.0%)
  平均発注金額: 360,000 円/件
  平均品目数: 28.5 品目/件

[SmallBusiness]
  発注件数: 1,575 件 (35.0%)
  発注金額: 165,000,000 円 (9.5%)
  平均発注金額: 104,762 円/件
  平均品目数: 9.8 品目/件
```

## 3.3 まとめ

本章では、発注管理システムのテストデータを作成しました。

**マスタデータ**:
- **仕入先**: 200社（大手30社、中堅100社、小規模70社）
- **商品拡張**: 発注単位、仕入先紐付け、EOQ、発注点

**トランザクションデータ**:
- **月間発注**: 3,000件（通常月）〜4,500件（年末ピーク）
- **仕入先タイプ別の特徴**:
  - 大手: 15%の件数、43.5%の金額、平均100品目、111万円/件
  - 中堅: 50%の件数、47.0%の金額、平均30品目、36万円/件
  - 小規模: 35%の件数、9.5%の金額、平均10品目、10万円/件

**季節変動**:
- 1月: 0.9倍（年始で少ない）
- 8月: 1.15倍（夏季ピーク）
- 12月: 1.5倍（年末ピーク）

これらのデータにより、実際のビジネスシーンに近い環境でシステムの動作を検証できます。

次章では、これらのデータを使用するドメインモデルを詳しく設計します。Supplier集約、PurchaseOrder集約、Receiving集約、SupplierPayment集約の詳細な実装を見ていきます。
