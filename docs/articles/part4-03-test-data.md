# 【第4部 第3章】ドメインに適したデータの作成

## 本章の目的

本章では、受注管理システムの動作確認とテストに必要なデータを設計・準備します。ドメイン駆動設計（DDD）において、テストデータは実際のビジネスシナリオを反映した、意味のあるデータであるべきです。

本章で準備するデータ：
1. **マスタデータ**: 商品マスタ（価格・税区分追加）、取引先マスタ（与信情報追加）
2. **トランザクションデータ**: 取引先タイプ別の注文パターン、季節変動を考慮した注文データ
3. **データ投入スクリプト**: Flywayマイグレーション、シードデータSQL、テストデータ生成ツール

## 3.1 マスタデータの準備

### 商品マスタの拡張

第3部で作成した商品マスタに、受注管理で必要な価格情報と税区分を追加します。

#### 商品カテゴリと税区分の対応

D社が扱う商品は、大きく2つのカテゴリに分類され、それぞれ異なる税率が適用されます：

| カテゴリ | 商品例 | 税区分 | 税率 | 商品数 | 構成比 |
|---------|--------|--------|------|--------|--------|
| 食品類 | 米、パン、野菜、肉、乳製品、調味料 | Reduced（軽減税率） | 8% | 4,800品目 | 60% |
| 日用品 | 洗剤、トイレットペーパー、文房具 | Standard（標準税率） | 10% | 3,200品目 | 40% |

**軽減税率の適用ルール**（消費税法に基づく）:
- 飲食料品（酒類を除く）: 8%
- 新聞（定期購読契約）: 8%
- 外食、ケータリング: 10%
- 酒類: 10%

#### 商品価格帯の設計

商品価格は、取引先タイプに応じた購入量を考慮して設定します：

```sql
-- 価格帯の例（税抜価格）
-- 低価格帯（100円〜500円）: 40% - 日用品、調味料など
-- 中価格帯（500円〜2,000円）: 40% - 加工食品、飲料など
-- 高価格帯（2,000円〜10,000円）: 20% - 高級食材、大容量商品など
```

#### 商品マスタ拡張SQL

```sql
-- 商品マスタに価格・税区分カラムを追加
ALTER TABLE products
  ADD COLUMN standard_price DECIMAL(15, 2) NOT NULL DEFAULT 0,  -- 標準価格（税抜）
  ADD COLUMN tax_category VARCHAR(20) NOT NULL DEFAULT 'Standard',  -- Standard, Reduced, TaxFree
  ADD COLUMN tax_rate DECIMAL(5, 4) NOT NULL DEFAULT 0.1000;  -- 0.1000 = 10%, 0.0800 = 8%

-- 税区分のチェック制約
ALTER TABLE products
  ADD CONSTRAINT chk_products_tax_category
  CHECK (tax_category IN ('Standard', 'Reduced', 'TaxFree'));

-- 税率と税区分の整合性チェック
ALTER TABLE products
  ADD CONSTRAINT chk_products_tax_rate
  CHECK (
    (tax_category = 'Standard' AND tax_rate = 0.1000) OR
    (tax_category = 'Reduced' AND tax_rate = 0.0800) OR
    (tax_category = 'TaxFree' AND tax_rate = 0.0000)
  );
```

#### 商品マスタのサンプルデータ

```sql
-- 食品類（軽減税率8%）のサンプル
INSERT INTO products (
  product_id, product_code, product_name, category_code,
  unit_of_measure, standard_price, tax_category, tax_rate,
  storage_condition, status
) VALUES
  (gen_random_uuid(), 'P-FOOD-001', 'コシヒカリ 5kg', 'FOOD-RICE', 'kg', 2500.00, 'Reduced', 0.0800, 'Normal', 'Active'),
  (gen_random_uuid(), 'P-FOOD-002', '食パン 6枚切', 'FOOD-BREAD', '個', 150.00, 'Reduced', 0.0800, 'Normal', 'Active'),
  (gen_random_uuid(), 'P-FOOD-003', '牛乳 1L', 'FOOD-DAIRY', 'L', 200.00, 'Reduced', 0.0800, 'Refrigerated', 'Active'),
  (gen_random_uuid(), 'P-FOOD-004', '豚肉ロース 100g', 'FOOD-MEAT', 'kg', 300.00, 'Reduced', 0.0800, 'Refrigerated', 'Active'),
  (gen_random_uuid(), 'P-FOOD-005', '冷凍うどん 5食', 'FOOD-FROZEN', '個', 400.00, 'Reduced', 0.0800, 'Frozen', 'Active');

-- 日用品（標準税率10%）のサンプル
INSERT INTO products (
  product_id, product_code, product_name, category_code,
  unit_of_measure, standard_price, tax_category, tax_rate,
  storage_condition, status
) VALUES
  (gen_random_uuid(), 'P-DAILY-001', 'トイレットペーパー 12ロール', 'DAILY-TISSUE', '個', 500.00, 'Standard', 0.1000, 'Normal', 'Active'),
  (gen_random_uuid(), 'P-DAILY-002', '食器用洗剤 500ml', 'DAILY-DETERGENT', '個', 300.00, 'Standard', 0.1000, 'Normal', 'Active'),
  (gen_random_uuid(), 'P-DAILY-003', 'ボールペン 黒 10本', 'DAILY-STATIONERY', '個', 800.00, 'Standard', 0.1000, 'Normal', 'Active');
```

**価格分布のシミュレーション**:

```scala
// 価格分布生成ロジック（テストデータ生成ツール用）
object PriceDistribution {
  import scala.util.Random

  def generatePrice(category: String): BigDecimal = category match {
    case "FOOD-RICE" | "FOOD-MEAT" =>
      // 高価格帯: 2,000円〜10,000円
      BigDecimal(Random.between(2000, 10000))

    case "FOOD-DAIRY" | "FOOD-BREAD" | "FOOD-FROZEN" =>
      // 中価格帯: 500円〜2,000円
      BigDecimal(Random.between(500, 2000))

    case "DAILY-TISSUE" | "DAILY-DETERGENT" =>
      // 中価格帯: 500円〜2,000円
      BigDecimal(Random.between(500, 2000))

    case "DAILY-STATIONERY" =>
      // 低価格帯: 100円〜500円
      BigDecimal(Random.between(100, 500))

    case _ =>
      // デフォルト: 500円〜2,000円
      BigDecimal(Random.between(500, 2000))
  }
}
```

### 取引先マスタの拡張

第3部で作成した取引先マスタに、与信情報と支払条件を追加します。

#### 取引先マスタ拡張SQL

```sql
-- 取引先マスタに与信・支払情報カラムを追加
ALTER TABLE customers
  ADD COLUMN customer_type VARCHAR(20) NOT NULL DEFAULT 'Small',  -- Large, Medium, Small
  ADD COLUMN credit_limit DECIMAL(15, 2) NOT NULL DEFAULT 1000000,  -- 与信限度額
  ADD COLUMN payment_terms VARCHAR(50) NOT NULL DEFAULT 'MonthEnd',  -- 支払条件
  ADD COLUMN payment_days INT NOT NULL DEFAULT 30,  -- 支払サイト（日数）
  ADD COLUMN contract_start_date DATE,  -- 取引開始日
  ADD COLUMN assigned_sales_rep_id UUID;  -- 担当営業ID

-- 取引先タイプのチェック制約
ALTER TABLE customers
  ADD CONSTRAINT chk_customers_type
  CHECK (customer_type IN ('Large', 'Medium', 'Small'));

-- 与信限度額と取引先タイプの整合性チェック
ALTER TABLE customers
  ADD CONSTRAINT chk_customers_credit_limit
  CHECK (
    (customer_type = 'Large' AND credit_limit <= 30000000) OR
    (customer_type = 'Medium' AND credit_limit <= 5000000) OR
    (customer_type = 'Small' AND credit_limit <= 1000000)
  );
```

#### 取引先タイプ別の与信設定

| 取引先タイプ | 社数 | デフォルト与信限度額 | 月間平均取引額 | 与信限度額/月間取引額 |
|------------|------|------------------|--------------|-------------------|
| Large | 30社 | 30,000,000円 | 10,000,000円 | 3.0倍（3ヶ月分） |
| Medium | 150社 | 5,000,000円 | 1,000,000円 | 5.0倍（5ヶ月分） |
| Small | 250社 | 1,000,000円 | 100,000円 | 10.0倍（10ヶ月分） |

**与信限度額の設定根拠**:
- **大口取引先**: 取引額が大きいため余裕を持たせつつ、リスクを分散（3ヶ月分）
- **中口取引先**: 安定性を重視し、やや余裕を持った設定（5ヶ月分）
- **小口取引先**: リスクが相対的に低いため、大きめの余裕（10ヶ月分）

#### 取引先マスタのサンプルデータ

```sql
-- 大口取引先のサンプル
INSERT INTO customers (
  customer_id, customer_code, customer_name, company_id, customer_type,
  credit_limit, payment_terms, payment_days, contract_start_date,
  billing_address_postal_code, billing_address_prefecture, billing_address_city
) VALUES
  (
    gen_random_uuid(), 'C-LARGE-001', 'スーパーマーケットA',
    (SELECT company_id FROM companies WHERE company_type = 'Partner' LIMIT 1),
    'Large', 30000000.00, 'MonthEnd', 30, '2020-04-01',
    '100-0001', '東京都', '千代田区'
  ),
  (
    gen_random_uuid(), 'C-LARGE-002', '量販店チェーンB',
    (SELECT company_id FROM companies WHERE company_type = 'Partner' LIMIT 1),
    'Large', 30000000.00, 'MonthEnd', 30, '2019-07-01',
    '530-0001', '大阪府', '大阪市北区'
  );

-- 中口取引先のサンプル
INSERT INTO customers (
  customer_id, customer_code, customer_name, company_id, customer_type,
  credit_limit, payment_terms, payment_days, contract_start_date,
  billing_address_postal_code, billing_address_prefecture, billing_address_city
) VALUES
  (
    gen_random_uuid(), 'C-MEDIUM-001', '地域スーパーC',
    (SELECT company_id FROM companies WHERE company_type = 'Partner' LIMIT 1),
    'Medium', 5000000.00, 'MonthEnd', 30, '2021-01-01',
    '460-0001', '愛知県', '名古屋市中区'
  );

-- 小口取引先のサンプル
INSERT INTO customers (
  customer_id, customer_code, customer_name, company_id, customer_type,
  credit_limit, payment_terms, payment_days, contract_start_date,
  billing_address_postal_code, billing_address_prefecture, billing_address_city
) VALUES
  (
    gen_random_uuid(), 'C-SMALL-001', '個人商店D',
    (SELECT company_id FROM companies WHERE company_type = 'Partner' LIMIT 1),
    'Small', 1000000.00, 'MonthEnd', 30, '2022-03-01',
    '810-0001', '福岡県', '福岡市中央区'
  );
```

#### 与信限度額の初期化

```sql
-- credit_limitsテーブルへの初期データ投入
INSERT INTO credit_limits (customer_id, limit_amount, used_amount, last_updated_by)
SELECT
  customer_id,
  credit_limit,
  0.00,  -- 初期状態では使用額0
  'system'
FROM customers;
```

## 3.2 トランザクションデータのシナリオ設計

実際のビジネスシナリオに基づいた注文データを生成します。

### 取引先タイプ別の注文パターン

#### 大口取引先の注文パターン

**特徴**:
- 定期発注（週1回、毎週月曜日）
- 大量かつ多品目の注文
- 食品類と日用品をバランスよく発注

```scala
case class LargeCustomerOrderPattern(
  customerId: CustomerId,
  orderFrequency: Duration = 7.days,  // 週1回
  averageItemCount: Int = 50,  // 平均50品目
  averageOrderAmount: Money = Money(5000000),  // 平均500万円
  itemCategories: Map[String, Double] = Map(
    "FOOD-RICE" -> 0.15,     // 米・穀物 15%
    "FOOD-DAIRY" -> 0.20,    // 乳製品 20%
    "FOOD-MEAT" -> 0.15,     // 肉類 15%
    "FOOD-FROZEN" -> 0.10,   // 冷凍食品 10%
    "FOOD-BREAD" -> 0.10,    // パン 10%
    "DAILY-TISSUE" -> 0.15,  // 日用品（紙類） 15%
    "DAILY-DETERGENT" -> 0.10, // 日用品（洗剤） 10%
    "DAILY-STATIONERY" -> 0.05  // 文房具 5%
  )
)
```

**注文生成ロジック**:

```scala
def generateLargeCustomerOrder(
  pattern: LargeCustomerOrderPattern,
  orderDate: LocalDate
): CreateOrderRequest = {
  val items = pattern.itemCategories.flatMap { case (category, ratio) =>
    val itemCount = (pattern.averageItemCount * ratio).toInt
    generateOrderItems(category, itemCount)
  }.toList

  CreateOrderRequest(
    customerId = pattern.customerId,
    orderDate = orderDate,
    items = items,
    requestedDeliveryDate = Some(orderDate.plusDays(3))  // 3日後配送
  )
}

def generateOrderItems(category: String, count: Int): List[OrderItemRequest] = {
  val products = productRepository.findByCategory(category).take(count)

  products.map { product =>
    // 大口取引先は大量発注
    val quantity = Random.between(100, 500)

    OrderItemRequest(
      productId = product.id,
      quantity = Quantity(quantity),
      unitPrice = product.standardPrice,
      discountRate = Some(0.05)  // 大口割引5%
    )
  }
}
```

#### 中口取引先の注文パターン

**特徴**:
- 準定期発注（週1〜2回）
- 特定カテゴリに集中した注文
- 地域性による商品選定の偏り

```scala
case class MediumCustomerOrderPattern(
  customerId: CustomerId,
  orderFrequency: Duration = 4.days,  // 週1〜2回
  averageItemCount: Int = 20,  // 平均20品目
  averageOrderAmount: Money = Money(500000),  // 平均50万円
  preferredCategories: List[String] = List("FOOD-DAIRY", "FOOD-BREAD", "FOOD-FROZEN")
)
```

**注文生成ロジック**:

```scala
def generateMediumCustomerOrder(
  pattern: MediumCustomerOrderPattern,
  orderDate: LocalDate
): CreateOrderRequest = {
  // 好みのカテゴリから70%、その他から30%
  val preferredItems = pattern.preferredCategories.flatMap { category =>
    val itemCount = (pattern.averageItemCount * 0.7 / pattern.preferredCategories.size).toInt
    generateOrderItems(category, itemCount, quantityRange = (50, 200))
  }

  val otherItems = generateRandomCategoryItems(
    count = (pattern.averageItemCount * 0.3).toInt,
    quantityRange = (20, 100)
  )

  CreateOrderRequest(
    customerId = pattern.customerId,
    orderDate = orderDate,
    items = preferredItems ++ otherItems,
    requestedDeliveryDate = Some(orderDate.plusDays(2))
  )
}
```

#### 小口取引先の注文パターン

**特徴**:
- 不定期発注（需要に応じて）
- 少品目・少量の注文
- 即納希望が多い

```scala
case class SmallCustomerOrderPattern(
  customerId: CustomerId,
  orderFrequency: Duration = 14.days,  // 2週間に1回程度（不定期）
  averageItemCount: Int = 5,  // 平均5品目
  averageOrderAmount: Money = Money(50000)  // 平均5万円
)
```

**注文生成ロジック**:

```scala
def generateSmallCustomerOrder(
  pattern: SmallCustomerOrderPattern,
  orderDate: LocalDate
): CreateOrderRequest = {
  // 完全にランダムなカテゴリから少量発注
  val items = (1 to pattern.averageItemCount).map { _ =>
    val product = productRepository.findRandom()
    val quantity = Random.between(5, 20)  // 少量

    OrderItemRequest(
      productId = product.id,
      quantity = Quantity(quantity),
      unitPrice = product.standardPrice,
      discountRate = None  // 割引なし
    )
  }.toList

  CreateOrderRequest(
    customerId = pattern.customerId,
    orderDate = orderDate,
    items = items,
    requestedDeliveryDate = Some(orderDate.plusDays(1))  // 翌日配送希望
  )
}
```

### 月間50,000件の注文データ生成戦略

#### 取引先タイプ別の注文件数配分

月間50,000件を取引先タイプ別に配分：

```
大口取引先（30社）: 月4回 × 30社 = 120件（0.24%）
中口取引先（150社）: 月6回 × 150社 = 900件（1.8%）
小口取引先（250社）: 月平均196回 × 250社 = 49,000件（98%）

※実際には小口取引先の発注頻度にばらつきがあるため、
  活発な取引先50社が月400回、残り200社が月120回程度と想定
```

#### データ生成スクリプト

```scala
object OrderDataGenerator {
  def generateMonthlyOrders(yearMonth: YearMonth): List[CreateOrderRequest] = {
    val startDate = yearMonth.atDay(1)
    val endDate = yearMonth.atEndOfMonth()

    val largeCustomers = customerRepository.findByType(CustomerType.Large)
    val mediumCustomers = customerRepository.findByType(CustomerType.Medium)
    val smallCustomers = customerRepository.findByType(CustomerType.Small)

    val largeOrders = largeCustomers.flatMap { customer =>
      generateWeeklyOrders(customer, startDate, endDate, frequency = 7.days)
    }

    val mediumOrders = mediumCustomers.flatMap { customer =>
      generateWeeklyOrders(customer, startDate, endDate, frequency = 4.days)
    }

    val smallOrders = generateSmallCustomerOrders(
      smallCustomers,
      startDate,
      endDate,
      targetCount = 49000
    )

    largeOrders ++ mediumOrders ++ smallOrders
  }

  private def generateWeeklyOrders(
    customer: Customer,
    startDate: LocalDate,
    endDate: LocalDate,
    frequency: Duration
  ): List[CreateOrderRequest] = {
    val pattern = customer.customerType match {
      case CustomerType.Large =>
        LargeCustomerOrderPattern(customer.id)
      case CustomerType.Medium =>
        MediumCustomerOrderPattern(customer.id)
      case _ =>
        SmallCustomerOrderPattern(customer.id)
    }

    Iterator.iterate(startDate)(_ plusDays frequency.toDays)
      .takeWhile(_ <= endDate)
      .map(date => generateOrder(pattern, date))
      .toList
  }

  private def generateSmallCustomerOrders(
    customers: List[Customer],
    startDate: LocalDate,
    endDate: LocalDate,
    targetCount: Int
  ): List[CreateOrderRequest] = {
    // 活発な取引先50社: 月400回
    val activeCustomers = customers.take(50)
    val activeOrders = activeCustomers.flatMap { customer =>
      (1 to 400).map { _ =>
        val randomDate = randomDateInRange(startDate, endDate)
        generateSmallCustomerOrder(
          SmallCustomerOrderPattern(customer.id),
          randomDate
        )
      }
    }

    // 残り200社: 月120回
    val normalCustomers = customers.drop(50)
    val normalOrders = normalCustomers.flatMap { customer =>
      (1 to 120).map { _ =>
        val randomDate = randomDateInRange(startDate, endDate)
        generateSmallCustomerOrder(
          SmallCustomerOrderPattern(customer.id),
          randomDate
        )
      }
    }

    activeOrders ++ normalOrders
  }

  private def randomDateInRange(start: LocalDate, end: LocalDate): LocalDate = {
    val days = ChronoUnit.DAYS.between(start, end)
    start.plusDays(Random.nextLong(days + 1))
  }
}
```

### 季節変動の考慮

実際のビジネスでは、季節により注文量が変動します。

#### 月別の変動係数

```scala
object SeasonalVariation {
  // 月別の注文量変動係数（平均1.0）
  val monthlyCoefficients: Map[Int, Double] = Map(
    1 -> 0.9,   // 1月: 年末年始の影響で少なめ
    2 -> 0.85,  // 2月: 最も少ない月
    3 -> 1.0,   // 3月: 平均的
    4 -> 1.1,   // 4月: 新年度で増加
    5 -> 1.0,   // 5月: 平均的
    6 -> 0.95,  // 6月: やや少なめ
    7 -> 0.9,   // 7月: 夏季で少なめ
    8 -> 0.85,  // 8月: 夏季閑散期
    9 -> 1.0,   // 9月: 平均的
    10 -> 1.1,  // 10月: 行楽シーズン
    11 -> 1.2,  // 11月: 年末に向けて増加
    12 -> 1.4   // 12月: 年末繁忙期（最も多い）
  )

  def applySeasonalVariation(
    baseOrderCount: Int,
    month: Int
  ): Int = {
    (baseOrderCount * monthlyCoefficients(month)).toInt
  }

  // 例: 平均50,000件/月の場合
  // 2月（0.85）: 42,500件
  // 12月（1.4）: 70,000件
}
```

## 3.3 データ投入スクリプト

### Flywayマイグレーション

商品・取引先マスタの拡張とサンプルデータの投入をFlywayマイグレーションで管理します。

#### V004__add_order_management_master_data.sql

```sql
-- =============================================
-- 受注管理用のマスタデータ拡張
-- =============================================

-- 商品マスタに価格・税区分を追加
ALTER TABLE products
  ADD COLUMN IF NOT EXISTS standard_price DECIMAL(15, 2) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS tax_category VARCHAR(20) NOT NULL DEFAULT 'Standard',
  ADD COLUMN IF NOT EXISTS tax_rate DECIMAL(5, 4) NOT NULL DEFAULT 0.1000;

-- 取引先マスタに与信・支払情報を追加
ALTER TABLE customers
  ADD COLUMN IF NOT EXISTS customer_type VARCHAR(20) NOT NULL DEFAULT 'Small',
  ADD COLUMN IF NOT EXISTS credit_limit DECIMAL(15, 2) NOT NULL DEFAULT 1000000,
  ADD COLUMN IF NOT EXISTS payment_terms VARCHAR(50) NOT NULL DEFAULT 'MonthEnd',
  ADD COLUMN IF NOT EXISTS payment_days INT NOT NULL DEFAULT 30,
  ADD COLUMN IF NOT EXISTS contract_start_date DATE,
  ADD COLUMN IF NOT EXISTS assigned_sales_rep_id UUID;

-- 制約の追加
ALTER TABLE products
  ADD CONSTRAINT IF NOT EXISTS chk_products_tax_category
  CHECK (tax_category IN ('Standard', 'Reduced', 'TaxFree')),
  ADD CONSTRAINT IF NOT EXISTS chk_products_tax_rate
  CHECK (
    (tax_category = 'Standard' AND tax_rate = 0.1000) OR
    (tax_category = 'Reduced' AND tax_rate = 0.0800) OR
    (tax_category = 'TaxFree' AND tax_rate = 0.0000)
  );

ALTER TABLE customers
  ADD CONSTRAINT IF NOT EXISTS chk_customers_type
  CHECK (customer_type IN ('Large', 'Medium', 'Small')),
  ADD CONSTRAINT IF NOT EXISTS chk_customers_credit_limit
  CHECK (
    (customer_type = 'Large' AND credit_limit <= 30000000) OR
    (customer_type = 'Medium' AND credit_limit <= 5000000) OR
    (customer_type = 'Small' AND credit_limit <= 1000000)
  );

-- 既存商品の価格・税区分を更新
UPDATE products
SET
  standard_price = CASE
    WHEN category_code LIKE 'FOOD%' THEN FLOOR(RANDOM() * 8000 + 2000)
    WHEN category_code LIKE 'DAILY%' THEN FLOOR(RANDOM() * 1500 + 500)
    ELSE FLOOR(RANDOM() * 1000 + 100)
  END,
  tax_category = CASE
    WHEN category_code LIKE 'FOOD%' THEN 'Reduced'
    ELSE 'Standard'
  END,
  tax_rate = CASE
    WHEN category_code LIKE 'FOOD%' THEN 0.0800
    ELSE 0.1000
  END
WHERE standard_price = 0;

-- 既存取引先の与信情報を更新
UPDATE customers
SET
  customer_type = CASE
    WHEN customer_code LIKE 'C-LARGE%' THEN 'Large'
    WHEN customer_code LIKE 'C-MEDIUM%' THEN 'Medium'
    ELSE 'Small'
  END,
  credit_limit = CASE
    WHEN customer_code LIKE 'C-LARGE%' THEN 30000000
    WHEN customer_code LIKE 'C-MEDIUM%' THEN 5000000
    ELSE 1000000
  END,
  contract_start_date = CURRENT_DATE - INTERVAL '2 years'
WHERE customer_type = 'Small';  -- デフォルト値のままの場合のみ更新

-- 与信限度額テーブルの初期化
INSERT INTO credit_limits (customer_id, limit_amount, used_amount, last_updated_by)
SELECT
  customer_id,
  credit_limit,
  0.00,
  'system'
FROM customers
ON CONFLICT (customer_id) DO NOTHING;
```

### テストデータ生成ツール

大量のトランザクションデータを生成するScalaツール：

```scala
// modules/test-data-generator/src/main/scala/OrderDataGenerator.scala
package com.example.testdata

import java.time.{LocalDate, YearMonth}
import scala.util.Random

object OrderDataGeneratorApp extends App {
  val config = TestDataConfig(
    yearMonth = YearMonth.of(2025, 1),
    targetOrderCount = 50000
  )

  println(s"Generating ${config.targetOrderCount} orders for ${config.yearMonth}...")

  val orders = OrderDataGenerator.generateMonthlyOrders(config.yearMonth)

  println(s"Generated ${orders.size} orders")
  println(s"Writing to SQL file...")

  val sqlFile = s"data/orders_${config.yearMonth}.sql"
  OrderSqlWriter.writeToFile(orders, sqlFile)

  println(s"Done! SQL file: $sqlFile")
}

object OrderSqlWriter {
  def writeToFile(orders: List[CreateOrderRequest], filePath: String): Unit = {
    val writer = new java.io.PrintWriter(filePath)

    try {
      writer.println("-- Generated Order Data")
      writer.println(s"-- Total Orders: ${orders.size}")
      writer.println()

      orders.zipWithIndex.foreach { case (order, index) =>
        if (index % 1000 == 0) {
          writer.println(s"-- Progress: $index / ${orders.size}")
        }

        writeOrderSql(writer, order)
      }
    } finally {
      writer.close()
    }
  }

  private def writeOrderSql(writer: java.io.PrintWriter, order: CreateOrderRequest): Unit = {
    val orderId = java.util.UUID.randomUUID()
    val orderNumber = f"O-${order.orderDate.getYear}-${orderId.toString.take(8)}"

    writer.println(
      s"""INSERT INTO orders (order_id, customer_id, company_id, order_number, order_date, status, subtotal_amount, tax_amount, total_amount, created_by, updated_by)
         |VALUES (
         |  '$orderId',
         |  '${order.customerId}',
         |  (SELECT company_id FROM companies WHERE company_type = 'Own' LIMIT 1),
         |  '$orderNumber',
         |  '${order.orderDate}',
         |  'Created',
         |  ${order.subtotalAmount},
         |  ${order.taxAmount},
         |  ${order.totalAmount},
         |  'system',
         |  'system'
         |);
         |""".stripMargin
    )

    order.items.zipWithIndex.foreach { case (item, lineNumber) =>
      val itemId = java.util.UUID.randomUUID()

      writer.println(
        s"""INSERT INTO order_items (order_item_id, order_id, product_id, line_number, quantity, unit_price, subtotal, tax_rate, tax_amount, total_amount)
           |VALUES (
           |  '$itemId',
           |  '$orderId',
           |  '${item.productId}',
           |  ${lineNumber + 1},
           |  ${item.quantity},
           |  ${item.unitPrice},
           |  ${item.subtotal},
           |  ${item.taxRate},
           |  ${item.taxAmount},
           |  ${item.totalAmount}
           |);
           |""".stripMargin
      )
    }

    writer.println()
  }
}
```

### データ投入手順

```bash
# 1. Flywayマイグレーション実行（マスタデータ拡張）
sbt "queryInterfaceAdapter/flywayMigrate"

# 2. テストデータ生成ツール実行
sbt "testDataGenerator/run"

# 3. 生成されたSQLファイルの投入
psql -U postgres -d pekko_cqrs_es -f data/orders_2025-01.sql
```

## 本章のまとめ

本章では、受注管理システムの動作確認に必要なデータを設計・準備しました：

### マスタデータの拡張

1. **商品マスタ**: 価格・税区分を追加
   - 食品類: 軽減税率8%（4,800品目）
   - 日用品: 標準税率10%（3,200品目）
   - 価格帯: 低価格（100〜500円）、中価格（500〜2,000円）、高価格（2,000〜10,000円）

2. **取引先マスタ**: 与信・支払条件を追加
   - 大口（30社）: 与信30百万円、月間取引額10百万円
   - 中口（150社）: 与信5百万円、月間取引額1百万円
   - 小口（250社）: 与信1百万円、月間取引額0.1百万円

### トランザクションデータのシナリオ

1. **注文パターンの設計**
   - 大口: 週1回、平均50品目、500万円/件
   - 中口: 週1〜2回、平均20品目、50万円/件
   - 小口: 不定期、平均5品目、5万円/件

2. **月間50,000件のデータ生成**
   - 大口: 120件（0.24%）
   - 中口: 900件（1.8%）
   - 小口: 49,000件（98%）

3. **季節変動の考慮**
   - 繁忙期（12月）: 70,000件（係数1.4）
   - 閑散期（8月）: 42,500件（係数0.85）

### データ投入スクリプト

1. **Flywayマイグレーション**: マスタデータ拡張とサンプルデータ
2. **テストデータ生成ツール**: Scalaによる大量データ生成
3. **投入手順**: sbt + psqlによる自動化

次章では、これらのデータを扱うドメインモデル（集約、値オブジェクト）を設計します。
