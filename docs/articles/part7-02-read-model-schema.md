# 第7部 第2章 Read Modelスキーマの設計

本章では、共用データ管理サービスのRead Model（PostgreSQL）スキーマとDynamoDBイベントストアの設計を行います。

## 2.1 Read Model（PostgreSQL）のスキーマ設計

共用データ管理サービスのRead Modelは、以下の主要テーブルで構成されます。

### 2.1.1 商品マスタ（products）

商品の基本情報を管理するマスタテーブルです。

```sql
-- 商品マスタ
CREATE TABLE products (
  -- 主キー
  product_id VARCHAR(50) PRIMARY KEY,

  -- 商品コード（ユニーク）
  product_code VARCHAR(20) NOT NULL UNIQUE,

  -- 基本情報
  product_name VARCHAR(200) NOT NULL,
  product_name_kana VARCHAR(200),
  description TEXT,

  -- 分類
  category_code VARCHAR(20) NOT NULL,
  category_name VARCHAR(100),

  -- 単位
  unit_of_measure VARCHAR(10) NOT NULL,  -- 個、kg、L、箱など

  -- 価格情報
  standard_cost DECIMAL(18, 2) NOT NULL,  -- 標準原価
  list_price DECIMAL(18, 2) NOT NULL,     -- 定価

  -- 調達情報
  primary_supplier_id VARCHAR(50),        -- 主要仕入先ID
  lead_time_days INT NOT NULL DEFAULT 7,  -- リードタイム（日数）
  minimum_order_quantity DECIMAL(18, 2) NOT NULL DEFAULT 1,

  -- 保管条件
  storage_condition VARCHAR(20),          -- 常温、冷蔵、冷凍

  -- ステータス
  status VARCHAR(20) NOT NULL,            -- Active, Suspended, Obsolete

  -- 有効期間
  valid_from DATE NOT NULL,
  valid_to DATE,

  -- バージョン管理
  version INT NOT NULL DEFAULT 1,

  -- 監査情報
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by VARCHAR(50) NOT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by VARCHAR(50),

  -- 制約
  CONSTRAINT chk_products_cost CHECK (standard_cost >= 0),
  CONSTRAINT chk_products_price CHECK (list_price >= 0),
  CONSTRAINT chk_products_lead_time CHECK (lead_time_days >= 0),
  CONSTRAINT chk_products_moq CHECK (minimum_order_quantity > 0),
  CONSTRAINT chk_products_valid_period CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

-- インデックス
CREATE INDEX idx_products_code ON products(product_code);
CREATE INDEX idx_products_name ON products(product_name);
CREATE INDEX idx_products_category ON products(category_code);
CREATE INDEX idx_products_status ON products(status);
CREATE INDEX idx_products_valid ON products(valid_from, valid_to)
  WHERE status = 'Active';
CREATE INDEX idx_products_supplier ON products(primary_supplier_id)
  WHERE primary_supplier_id IS NOT NULL;

-- 全文検索インデックス（商品名検索の高速化）
CREATE INDEX idx_products_name_trgm ON products
  USING gin (product_name gin_trgm_ops);

-- コメント
COMMENT ON TABLE products IS '商品マスタ';
COMMENT ON COLUMN products.product_id IS '商品ID（UUID）';
COMMENT ON COLUMN products.product_code IS '商品コード（業務キー）';
COMMENT ON COLUMN products.product_name IS '商品名';
COMMENT ON COLUMN products.category_code IS 'カテゴリコード';
COMMENT ON COLUMN products.unit_of_measure IS '単位（個、kg、L、箱など）';
COMMENT ON COLUMN products.standard_cost IS '標準原価';
COMMENT ON COLUMN products.list_price IS '定価';
COMMENT ON COLUMN products.primary_supplier_id IS '主要仕入先ID';
COMMENT ON COLUMN products.lead_time_days IS 'リードタイム（日数）';
COMMENT ON COLUMN products.minimum_order_quantity IS '最小発注数量';
COMMENT ON COLUMN products.storage_condition IS '保管条件（常温、冷蔵、冷凍）';
COMMENT ON COLUMN products.status IS 'ステータス（Active:有効、Suspended:停止、Obsolete:廃止）';
COMMENT ON COLUMN products.valid_from IS '有効開始日';
COMMENT ON COLUMN products.valid_to IS '有効終了日';
```

**商品マスタのデータ例**:

| product_code | product_name | category_code | unit_of_measure | standard_cost | list_price | status |
|--------------|--------------|---------------|-----------------|---------------|------------|--------|
| P-001 | 有機玄米 5kg | RICE | 個 | 2,000 | 3,500 | Active |
| P-002 | 有機白米 5kg | RICE | 個 | 2,200 | 3,800 | Active |
| P-003 | 有機大豆 1kg | BEANS | 個 | 800 | 1,500 | Active |
| P-004 | 有機味噌 500g | MISO | 個 | 600 | 1,200 | Active |
| P-999 | 旧パッケージ商品 | OLD | 個 | 1,000 | 2,000 | Obsolete |

### 2.1.2 商品価格履歴テーブル（product_prices）

商品の価格履歴を管理するテーブルです。時点ごとの価格を保持し、過去の価格も参照可能です。

```sql
-- 商品価格履歴テーブル
CREATE TABLE product_prices (
  -- 主キー
  product_price_id VARCHAR(50) PRIMARY KEY,

  -- 外部キー
  product_id VARCHAR(50) NOT NULL,

  -- 価格タイプ
  price_type VARCHAR(20) NOT NULL,        -- Standard, Special, Discount

  -- 特別価格の場合の顧客ID
  customer_id VARCHAR(50),

  -- 価格
  unit_price DECIMAL(18, 2) NOT NULL,

  -- 有効期間
  valid_from DATE NOT NULL,
  valid_to DATE,

  -- 変更理由
  change_reason TEXT,

  -- 監査情報
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by VARCHAR(50) NOT NULL,

  -- 外部キー制約
  FOREIGN KEY (product_id) REFERENCES products(product_id),

  -- 制約
  CONSTRAINT chk_product_prices_amount CHECK (unit_price >= 0),
  CONSTRAINT chk_product_prices_valid_period CHECK (valid_to IS NULL OR valid_to >= valid_from),
  CONSTRAINT chk_product_prices_special CHECK (
    (price_type = 'Special' AND customer_id IS NOT NULL) OR
    (price_type != 'Special' AND customer_id IS NULL)
  )
);

-- インデックス
CREATE INDEX idx_product_prices_product ON product_prices(product_id);
CREATE INDEX idx_product_prices_customer ON product_prices(customer_id)
  WHERE customer_id IS NOT NULL;
CREATE INDEX idx_product_prices_valid ON product_prices(product_id, valid_from, valid_to);
CREATE INDEX idx_product_prices_type ON product_prices(price_type);

-- 一意制約（同一商品・同一タイプ・同一顧客で有効期間が重複しない）
CREATE UNIQUE INDEX idx_product_prices_unique ON product_prices(
  product_id,
  price_type,
  COALESCE(customer_id, ''),
  valid_from
);

-- コメント
COMMENT ON TABLE product_prices IS '商品価格履歴テーブル';
COMMENT ON COLUMN product_prices.product_price_id IS '価格ID（UUID）';
COMMENT ON COLUMN product_prices.price_type IS '価格タイプ（Standard:標準、Special:特別、Discount:割引）';
COMMENT ON COLUMN product_prices.customer_id IS '特別価格の場合の顧客ID';
COMMENT ON COLUMN product_prices.unit_price IS '単価';
COMMENT ON COLUMN product_prices.valid_from IS '有効開始日';
COMMENT ON COLUMN product_prices.valid_to IS '有効終了日';
```

**価格履歴のデータ例**:

| product_code | price_type | customer_id | unit_price | valid_from | valid_to |
|--------------|------------|-------------|------------|------------|----------|
| P-001 | Standard | - | 3,500 | 2024-01-01 | 2024-03-31 |
| P-001 | Standard | - | 3,800 | 2024-04-01 | null |
| P-001 | Special | C-001 | 3,200 | 2024-01-01 | null |
| P-002 | Standard | - | 3,800 | 2024-01-01 | null |

### 2.1.3 勘定科目マスタ（account_subjects）

勘定科目体系を管理するマスタテーブルです。

```sql
-- 勘定科目マスタ
CREATE TABLE account_subjects (
  -- 主キー
  account_subject_id VARCHAR(50) PRIMARY KEY,

  -- 勘定科目コード（ユニーク）
  account_code VARCHAR(10) NOT NULL UNIQUE,

  -- 基本情報
  account_name VARCHAR(100) NOT NULL,
  account_name_kana VARCHAR(100),

  -- 科目区分
  account_type VARCHAR(20) NOT NULL,      -- Asset, Liability, Equity, Revenue, Expense
  account_subtype VARCHAR(50),            -- Current, Fixed, Operating, NonOperating

  -- 貸借区分
  balance_side VARCHAR(10) NOT NULL,      -- Debit（借方）, Credit（貸方）

  -- 階層構造
  parent_account_id VARCHAR(50),
  level INT NOT NULL,                     -- 階層レベル（1:大分類、2:中分類、3:小分類）

  -- 表示順序
  display_order INT NOT NULL,

  -- 補助科目設定
  requires_auxiliary BOOLEAN DEFAULT FALSE,
  auxiliary_type VARCHAR(20),             -- Customer, Supplier, Department, Employee

  -- ステータス
  status VARCHAR(20) NOT NULL,            -- Active, Obsolete

  -- 有効期間
  valid_from DATE NOT NULL,
  valid_to DATE,

  -- バージョン管理
  version INT NOT NULL DEFAULT 1,

  -- 監査情報
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by VARCHAR(50) NOT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by VARCHAR(50),

  -- 外部キー制約
  FOREIGN KEY (parent_account_id) REFERENCES account_subjects(account_subject_id),

  -- 制約
  CONSTRAINT chk_account_subjects_level CHECK (level >= 1 AND level <= 4),
  CONSTRAINT chk_account_subjects_valid_period CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

-- インデックス
CREATE INDEX idx_account_subjects_code ON account_subjects(account_code);
CREATE INDEX idx_account_subjects_type ON account_subjects(account_type);
CREATE INDEX idx_account_subjects_parent ON account_subjects(parent_account_id);
CREATE INDEX idx_account_subjects_display ON account_subjects(display_order);
CREATE INDEX idx_account_subjects_active ON account_subjects(status)
  WHERE status = 'Active';

-- コメント
COMMENT ON TABLE account_subjects IS '勘定科目マスタ';
COMMENT ON COLUMN account_subjects.account_subject_id IS '勘定科目ID（UUID）';
COMMENT ON COLUMN account_subjects.account_code IS '勘定科目コード';
COMMENT ON COLUMN account_subjects.account_name IS '勘定科目名';
COMMENT ON COLUMN account_subjects.account_type IS '科目区分（Asset:資産、Liability:負債、Equity:純資産、Revenue:収益、Expense:費用）';
COMMENT ON COLUMN account_subjects.account_subtype IS 'サブ区分（Current:流動、Fixed:固定、Operating:営業、NonOperating:営業外）';
COMMENT ON COLUMN account_subjects.balance_side IS '貸借区分（Debit:借方、Credit:貸方）';
COMMENT ON COLUMN account_subjects.parent_account_id IS '親勘定科目ID';
COMMENT ON COLUMN account_subjects.level IS '階層レベル';
COMMENT ON COLUMN account_subjects.requires_auxiliary IS '補助科目必須フラグ';
COMMENT ON COLUMN account_subjects.auxiliary_type IS '補助科目タイプ（Customer:顧客、Supplier:仕入先、Department:部門、Employee:社員）';
```

**勘定科目マスタのデータ例**:

| account_code | account_name | account_type | balance_side | level | parent_code |
|--------------|--------------|--------------|--------------|-------|-------------|
| 1000 | 資産の部 | Asset | Debit | 1 | - |
| 1100 | 流動資産 | Asset | Debit | 2 | 1000 |
| 1111 | 現金 | Asset | Debit | 3 | 1100 |
| 1112 | 普通預金 | Asset | Debit | 3 | 1100 |
| 1120 | 売掛金 | Asset | Debit | 3 | 1100 |
| 1130 | 商品 | Asset | Debit | 3 | 1100 |
| 2000 | 負債の部 | Liability | Credit | 1 | - |
| 2100 | 流動負債 | Liability | Credit | 2 | 2000 |
| 2110 | 買掛金 | Liability | Credit | 3 | 2100 |
| 4000 | 収益 | Revenue | Credit | 1 | - |
| 4100 | 売上高 | Revenue | Credit | 2 | 4000 |
| 5000 | 費用 | Expense | Debit | 1 | - |
| 5100 | 売上原価 | Expense | Debit | 2 | 5000 |

### 2.1.4 部門マスタ（departments）

組織の部門情報を管理するマスタテーブルです。

```sql
-- 部門マスタ
CREATE TABLE departments (
  -- 主キー
  department_id VARCHAR(50) PRIMARY KEY,

  -- 部門コード（ユニーク）
  department_code VARCHAR(20) NOT NULL UNIQUE,

  -- 基本情報
  department_name VARCHAR(100) NOT NULL,
  department_name_kana VARCHAR(100),

  -- 階層構造
  parent_department_id VARCHAR(50),
  level INT NOT NULL,

  -- 責任者
  manager_employee_id VARCHAR(50),

  -- 有効期間
  start_year_month VARCHAR(7) NOT NULL,   -- YYYY-MM形式
  end_year_month VARCHAR(7),              -- YYYY-MM形式

  -- 監査情報
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by VARCHAR(50) NOT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by VARCHAR(50),

  -- 外部キー制約
  FOREIGN KEY (parent_department_id) REFERENCES departments(department_id),

  -- 制約
  CONSTRAINT chk_departments_period CHECK (
    end_year_month IS NULL OR end_year_month >= start_year_month
  )
);

-- インデックス
CREATE INDEX idx_departments_code ON departments(department_code);
CREATE INDEX idx_departments_parent ON departments(parent_department_id);
CREATE INDEX idx_departments_manager ON departments(manager_employee_id);
CREATE INDEX idx_departments_period ON departments(start_year_month, end_year_month);

-- コメント
COMMENT ON TABLE departments IS '部門マスタ';
COMMENT ON COLUMN departments.department_id IS '部門ID（UUID）';
COMMENT ON COLUMN departments.department_code IS '部門コード';
COMMENT ON COLUMN departments.department_name IS '部門名';
COMMENT ON COLUMN departments.parent_department_id IS '上位部門ID';
COMMENT ON COLUMN departments.manager_employee_id IS '部門長社員ID';
COMMENT ON COLUMN departments.start_year_month IS '開始年月（YYYY-MM）';
COMMENT ON COLUMN departments.end_year_month IS '終了年月（YYYY-MM）';
```

**部門マスタのデータ例**:

| department_code | department_name | parent_code | start_year_month | end_year_month |
|-----------------|-----------------|-------------|------------------|----------------|
| 1000 | 営業本部 | - | 2020-04 | null |
| 1100 | 営業第一部 | 1000 | 2020-04 | null |
| 1200 | 営業第二部 | 1000 | 2020-04 | null |
| 2000 | 管理本部 | - | 2020-04 | null |
| 2100 | 経理部 | 2000 | 2020-04 | null |
| 2200 | 人事部 | 2000 | 2020-04 | null |

### 2.1.5 社員マスタ（employees）

従業員情報を管理するマスタテーブルです。

```sql
-- 社員マスタ
CREATE TABLE employees (
  -- 主キー
  employee_id VARCHAR(50) PRIMARY KEY,

  -- 社員コード（ユニーク）
  employee_code VARCHAR(20) NOT NULL UNIQUE,

  -- 基本情報
  employee_name VARCHAR(100) NOT NULL,
  employee_name_kana VARCHAR(100),

  -- 所属情報
  department_id VARCHAR(50) NOT NULL,
  job_title VARCHAR(50),

  -- 在籍期間
  hire_date DATE NOT NULL,
  termination_date DATE,

  -- 連絡先
  email VARCHAR(100),
  phone VARCHAR(20),

  -- 監査情報
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by VARCHAR(50) NOT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by VARCHAR(50),

  -- 外部キー制約
  FOREIGN KEY (department_id) REFERENCES departments(department_id),

  -- 制約
  CONSTRAINT chk_employees_dates CHECK (
    termination_date IS NULL OR termination_date >= hire_date
  )
);

-- インデックス
CREATE INDEX idx_employees_code ON employees(employee_code);
CREATE INDEX idx_employees_department ON employees(department_id);
CREATE INDEX idx_employees_hire_date ON employees(hire_date);
CREATE INDEX idx_employees_active ON employees(termination_date)
  WHERE termination_date IS NULL;

-- コメント
COMMENT ON TABLE employees IS '社員マスタ';
COMMENT ON COLUMN employees.employee_id IS '社員ID（UUID）';
COMMENT ON COLUMN employees.employee_code IS '社員コード';
COMMENT ON COLUMN employees.employee_name IS '社員氏名';
COMMENT ON COLUMN employees.department_id IS '所属部門ID';
COMMENT ON COLUMN employees.job_title IS '役職';
COMMENT ON COLUMN employees.hire_date IS '入社日';
COMMENT ON COLUMN employees.termination_date IS '退職日';
```

**社員マスタのデータ例**:

| employee_code | employee_name | department_code | job_title | hire_date | termination_date |
|---------------|---------------|-----------------|-----------|-----------|------------------|
| E-001 | 山田太郎 | 1100 | 部長 | 2015-04-01 | null |
| E-002 | 佐藤花子 | 1100 | 課長 | 2017-04-01 | null |
| E-003 | 鈴木一郎 | 2100 | 部長 | 2016-04-01 | null |
| E-004 | 田中次郎 | 2100 | 主任 | 2019-04-01 | null |

### 2.1.6 コードマスタ（code_masters）

各種共通コード（税率、支払条件、配送方法など）を管理するマスタテーブルです。

```sql
-- コードマスタ
CREATE TABLE code_masters (
  -- 主キー
  code_master_id VARCHAR(50) PRIMARY KEY,

  -- コード種別
  code_type VARCHAR(50) NOT NULL,         -- TaxRate, PaymentTerms, ShippingMethod, etc.

  -- コード値
  code_value VARCHAR(50) NOT NULL,

  -- 表示名
  display_name VARCHAR(200) NOT NULL,
  display_name_short VARCHAR(50),

  -- 説明
  description TEXT,

  -- 表示順序
  display_order INT NOT NULL,

  -- 追加データ（JSON形式）
  additional_data JSONB,

  -- ステータス
  status VARCHAR(20) NOT NULL,            -- Active, Inactive

  -- 有効期間
  valid_from DATE NOT NULL,
  valid_to DATE,

  -- バージョン管理
  version INT NOT NULL DEFAULT 1,

  -- 監査情報
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by VARCHAR(50) NOT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by VARCHAR(50),

  -- 制約
  CONSTRAINT chk_code_masters_valid_period CHECK (valid_to IS NULL OR valid_to >= valid_from)
);

-- インデックス
CREATE INDEX idx_code_masters_type ON code_masters(code_type);
CREATE INDEX idx_code_masters_value ON code_masters(code_type, code_value);
CREATE INDEX idx_code_masters_display ON code_masters(code_type, display_order);
CREATE INDEX idx_code_masters_active ON code_masters(status)
  WHERE status = 'Active';

-- 一意制約（同一種別・同一値で有効期間が重複しない）
CREATE UNIQUE INDEX idx_code_masters_unique ON code_masters(
  code_type,
  code_value,
  valid_from
);

-- JSONBカラムのインデックス（追加データの検索高速化）
CREATE INDEX idx_code_masters_additional_data ON code_masters
  USING gin (additional_data);

-- コメント
COMMENT ON TABLE code_masters IS 'コードマスタ';
COMMENT ON COLUMN code_masters.code_master_id IS 'コードマスターID（UUID）';
COMMENT ON COLUMN code_masters.code_type IS 'コード種別（TaxRate:税率、PaymentTerms:支払条件、ShippingMethod:配送方法など）';
COMMENT ON COLUMN code_masters.code_value IS 'コード値';
COMMENT ON COLUMN code_masters.display_name IS '表示名';
COMMENT ON COLUMN code_masters.additional_data IS '追加データ（JSON形式）';
COMMENT ON COLUMN code_masters.status IS 'ステータス（Active:有効、Inactive:無効）';
```

**コードマスタのデータ例**:

```sql
-- 税率マスタ
INSERT INTO code_masters (code_master_id, code_type, code_value, display_name, additional_data, display_order, status, valid_from)
VALUES
  ('CM-TAX-001', 'TaxRate', 'STANDARD', '標準税率', '{"rate": 0.10, "description": "標準税率10%"}', 1, 'Active', '2019-10-01'),
  ('CM-TAX-002', 'TaxRate', 'REDUCED', '軽減税率', '{"rate": 0.08, "description": "軽減税率8%"}', 2, 'Active', '2019-10-01'),
  ('CM-TAX-003', 'TaxRate', 'EXEMPT', '非課税', '{"rate": 0.00, "description": "非課税"}', 3, 'Active', '2019-10-01');

-- 支払条件マスタ
INSERT INTO code_masters (code_master_id, code_type, code_value, display_name, additional_data, display_order, status, valid_from)
VALUES
  ('CM-PMT-001', 'PaymentTerms', 'NET30', '月末締め翌月末払い', '{"closing_day": "end_of_month", "payment_day": "end_of_next_month"}', 1, 'Active', '2020-01-01'),
  ('CM-PMT-002', 'PaymentTerms', 'NET60', '月末締め翌々月末払い', '{"closing_day": "end_of_month", "payment_day": "end_of_month_plus_2"}', 2, 'Active', '2020-01-01'),
  ('CM-PMT-003', 'PaymentTerms', 'IMMEDIATE', '即時払い', '{"closing_day": "immediate", "payment_day": "immediate"}', 3, 'Active', '2020-01-01');

-- 配送方法マスタ
INSERT INTO code_masters (code_master_id, code_type, code_value, display_name, additional_data, display_order, status, valid_from)
VALUES
  ('CM-SHIP-001', 'ShippingMethod', 'STANDARD', '通常配送', '{"delivery_days": 3, "fee": 500}', 1, 'Active', '2020-01-01'),
  ('CM-SHIP-002', 'ShippingMethod', 'EXPRESS', '速達配送', '{"delivery_days": 1, "fee": 1000}', 2, 'Active', '2020-01-01'),
  ('CM-SHIP-003', 'ShippingMethod', 'PICKUP', '店舗受取', '{"delivery_days": 0, "fee": 0}', 3, 'Active', '2020-01-01');
```

### 2.1.7 マスターデータ変更承認テーブル（master_change_requests）

重要なマスターデータ変更に対する承認申請を管理するテーブルです。

```sql
-- マスターデータ変更承認テーブル
CREATE TABLE master_change_requests (
  -- 主キー
  request_id VARCHAR(50) PRIMARY KEY,

  -- 変更対象
  target_type VARCHAR(50) NOT NULL,       -- Product, ProductPrice, AccountSubject
  target_id VARCHAR(50) NOT NULL,
  target_code VARCHAR(50),

  -- 変更種別
  change_type VARCHAR(20) NOT NULL,       -- Create, Update, Delete

  -- 変更内容（JSON形式）
  change_data JSONB NOT NULL,
  change_reason TEXT NOT NULL,

  -- 申請情報
  requested_by VARCHAR(50) NOT NULL,
  requested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  -- 承認情報
  approval_status VARCHAR(20) NOT NULL,   -- Pending, Approved, Rejected
  approver_id VARCHAR(50),
  approved_at TIMESTAMP,
  approval_comment TEXT,

  -- 適用情報
  applied_at TIMESTAMP,
  applied_by VARCHAR(50),

  -- 監査情報
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- インデックス
CREATE INDEX idx_change_requests_target ON master_change_requests(target_type, target_id);
CREATE INDEX idx_change_requests_status ON master_change_requests(approval_status);
CREATE INDEX idx_change_requests_requester ON master_change_requests(requested_by);
CREATE INDEX idx_change_requests_approver ON master_change_requests(approver_id);
CREATE INDEX idx_change_requests_requested_at ON master_change_requests(requested_at);

-- コメント
COMMENT ON TABLE master_change_requests IS 'マスターデータ変更承認テーブル';
COMMENT ON COLUMN master_change_requests.target_type IS '変更対象タイプ（Product:商品、ProductPrice:価格、AccountSubject:勘定科目）';
COMMENT ON COLUMN master_change_requests.change_type IS '変更種別（Create:作成、Update:更新、Delete:削除）';
COMMENT ON COLUMN master_change_requests.change_data IS '変更内容（JSON形式）';
COMMENT ON COLUMN master_change_requests.approval_status IS '承認ステータス（Pending:承認待ち、Approved:承認済み、Rejected:却下）';
```

## 2.2 DynamoDBのイベントストア設計

共用データ管理サービスのイベントは、以下のように設計されます。

### 2.2.1 Product Events

商品マスターに関連するイベントです。

```scala
sealed trait ProductEvent extends CborSerializable

object ProductEvent {
  // 商品作成
  final case class ProductCreated_V1(
    productId: String,
    productCode: String,
    productName: String,
    categoryCode: String,
    unitOfMeasure: String,
    standardCost: BigDecimal,
    listPrice: BigDecimal,
    primarySupplierId: Option[String],
    leadTimeDays: Int,
    minimumOrderQuantity: BigDecimal,
    storageCondition: Option[String],
    validFrom: String,
    createdBy: String,
    createdAt: String
  ) extends ProductEvent

  // 商品情報更新
  final case class ProductInfoUpdated_V1(
    productId: String,
    productName: Option[String],
    categoryCode: Option[String],
    unitOfMeasure: Option[String],
    storageCondition: Option[String],
    updatedBy: String,
    updatedAt: String,
    reason: String
  ) extends ProductEvent

  // 商品価格変更
  final case class ProductPriceChanged_V1(
    productId: String,
    oldListPrice: BigDecimal,
    newListPrice: BigDecimal,
    effectiveFrom: String,
    changedBy: String,
    changedAt: String,
    reason: String
  ) extends ProductEvent

  // 特別価格追加
  final case class SpecialPriceAdded_V1(
    productId: String,
    customerId: String,
    unitPrice: BigDecimal,
    validFrom: String,
    validTo: Option[String],
    createdBy: String,
    createdAt: String,
    reason: String
  ) extends ProductEvent

  // 主要仕入先変更
  final case class PrimarySupplierChanged_V1(
    productId: String,
    oldSupplierId: Option[String],
    newSupplierId: String,
    changedBy: String,
    changedAt: String,
    reason: String
  ) extends ProductEvent

  // 商品停止
  final case class ProductSuspended_V1(
    productId: String,
    suspendedBy: String,
    suspendedAt: String,
    reason: String
  ) extends ProductEvent

  // 商品再開
  final case class ProductReactivated_V1(
    productId: String,
    reactivatedBy: String,
    reactivatedAt: String,
    reason: String
  ) extends ProductEvent

  // 商品廃止
  final case class ProductObsoleted_V1(
    productId: String,
    obsoletedBy: String,
    obsoletedAt: String,
    reason: String
  ) extends ProductEvent
}
```

### 2.2.2 AccountSubject Events

勘定科目マスターに関連するイベントです。

```scala
sealed trait AccountSubjectEvent extends CborSerializable

object AccountSubjectEvent {
  // 勘定科目作成
  final case class AccountSubjectCreated_V1(
    accountSubjectId: String,
    accountCode: String,
    accountName: String,
    accountType: String,        // Asset, Liability, Equity, Revenue, Expense
    accountSubtype: Option[String],
    balanceSide: String,        // Debit, Credit
    parentAccountId: Option[String],
    level: Int,
    displayOrder: Int,
    requiresAuxiliary: Boolean,
    auxiliaryType: Option[String],
    validFrom: String,
    createdBy: String,
    createdAt: String
  ) extends AccountSubjectEvent

  // 勘定科目名更新
  final case class AccountSubjectNameUpdated_V1(
    accountSubjectId: String,
    oldAccountName: String,
    newAccountName: String,
    updatedBy: String,
    updatedAt: String,
    reason: String
  ) extends AccountSubjectEvent

  // 親勘定科目変更
  final case class ParentAccountChanged_V1(
    accountSubjectId: String,
    oldParentAccountId: Option[String],
    newParentAccountId: Option[String],
    changedBy: String,
    changedAt: String,
    reason: String
  ) extends AccountSubjectEvent

  // 勘定科目廃止
  final case class AccountSubjectObsoleted_V1(
    accountSubjectId: String,
    obsoletedBy: String,
    obsoletedAt: String,
    reason: String
  ) extends AccountSubjectEvent
}
```

### 2.2.3 CodeMaster Events

コードマスターに関連するイベントです。

```scala
sealed trait CodeMasterEvent extends CborSerializable

object CodeMasterEvent {
  // コードマスター作成
  final case class CodeMasterCreated_V1(
    codeMasterId: String,
    codeType: String,
    codeValue: String,
    displayName: String,
    description: Option[String],
    displayOrder: Int,
    additionalData: Option[String],  // JSON文字列
    validFrom: String,
    createdBy: String,
    createdAt: String
  ) extends CodeMasterEvent

  // コードマスター更新
  final case class CodeMasterUpdated_V1(
    codeMasterId: String,
    displayName: Option[String],
    description: Option[String],
    displayOrder: Option[Int],
    additionalData: Option[String],
    updatedBy: String,
    updatedAt: String,
    reason: String
  ) extends CodeMasterEvent

  // コードマスター無効化
  final case class CodeMasterDeactivated_V1(
    codeMasterId: String,
    deactivatedBy: String,
    deactivatedAt: String,
    reason: String
  ) extends CodeMasterEvent
}
```

## 2.3 インデックス戦略とパフォーマンス最適化

### 2.3.1 検索用インデックス

```sql
-- 商品検索の最適化
-- 商品コード検索（完全一致）: 50ms以内
CREATE UNIQUE INDEX idx_products_code ON products(product_code);

-- 商品名検索（部分一致）: 200ms以内
-- pg_trgm拡張を使用した類似度検索
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX idx_products_name_trgm ON products
  USING gin (product_name gin_trgm_ops);

-- カテゴリ別商品一覧: 100ms以内
CREATE INDEX idx_products_category_status ON products(category_code, status)
  WHERE status = 'Active';

-- 有効な商品の検索: 100ms以内
CREATE INDEX idx_products_valid_active ON products(valid_from, valid_to, status)
  WHERE status = 'Active';
```

### 2.3.2 価格照会の最適化

```sql
-- 指定日時点での価格照会: 100ms以内
CREATE INDEX idx_product_prices_lookup ON product_prices(
  product_id,
  price_type,
  valid_from,
  valid_to
) WHERE valid_to IS NULL OR valid_to >= CURRENT_DATE;

-- 顧客別特別価格の照会: 100ms以内
CREATE INDEX idx_product_prices_customer_lookup ON product_prices(
  customer_id,
  product_id,
  valid_from,
  valid_to
) WHERE customer_id IS NOT NULL;
```

### 2.3.3 勘定科目階層の最適化

```sql
-- 階層構造の取得: 50ms以内
-- 再帰クエリのパフォーマンス向上
CREATE INDEX idx_account_subjects_hierarchy ON account_subjects(
  parent_account_id,
  display_order
) WHERE status = 'Active';

-- Materialized Viewによる階層構造の事前計算
CREATE MATERIALIZED VIEW mv_account_subjects_hierarchy AS
WITH RECURSIVE account_hierarchy AS (
  -- ルート科目
  SELECT
    account_subject_id,
    account_code,
    account_name,
    account_type,
    level,
    parent_account_id,
    display_order,
    ARRAY[account_subject_id] AS path,
    account_code::TEXT AS path_names
  FROM account_subjects
  WHERE parent_account_id IS NULL AND status = 'Active'

  UNION ALL

  -- 子科目
  SELECT
    a.account_subject_id,
    a.account_code,
    a.account_name,
    a.account_type,
    a.level,
    a.parent_account_id,
    a.display_order,
    ah.path || a.account_subject_id,
    ah.path_names || ' > ' || a.account_code
  FROM account_subjects a
  INNER JOIN account_hierarchy ah ON a.parent_account_id = ah.account_subject_id
  WHERE a.status = 'Active'
)
SELECT * FROM account_hierarchy
ORDER BY path_names;

-- インデックス作成
CREATE INDEX idx_mv_account_hierarchy_code ON mv_account_subjects_hierarchy(account_code);
CREATE INDEX idx_mv_account_hierarchy_type ON mv_account_subjects_hierarchy(account_type);

-- 定期的にリフレッシュ（イベント受信時、または1時間ごと）
-- REFRESH MATERIALIZED VIEW CONCURRENTLY mv_account_subjects_hierarchy;
```

## 2.4 まとめ

本章では、共用データ管理サービスのRead Modelスキーマを設計しました。

**設計したテーブル**:
1. **商品マスタ（products）**: 商品の基本情報
2. **商品価格履歴（product_prices）**: 時点ごとの価格履歴
3. **勘定科目マスタ（account_subjects）**: 階層構造を持つ勘定科目体系
4. **部門マスタ（departments）**: 組織の部門情報
5. **社員マスタ（employees）**: 従業員情報
6. **コードマスタ（code_masters）**: 各種共通コード
7. **マスターデータ変更承認（master_change_requests）**: 承認ワークフロー

**イベント設計**:
- ProductEvent: 8種類のイベント（作成、更新、価格変更、特別価格、停止、再開、廃止など）
- AccountSubjectEvent: 4種類のイベント（作成、名称更新、親科目変更、廃止）
- CodeMasterEvent: 3種類のイベント（作成、更新、無効化）

**パフォーマンス最適化**:
- 検索用インデックス（商品コード、商品名、カテゴリ、有効期間）
- 価格照会の最適化（日時点での価格検索）
- 勘定科目階層のMaterialized View（再帰クエリの高速化）

次章では、これらのマスタデータに適したドメインデータを作成します。
