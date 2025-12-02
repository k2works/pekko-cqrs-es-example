# 【第4部 第2章】データモデルの設計

## 本章の目的

本章では、受注管理システムのデータモデルを設計します。CQRS/Event Sourcingアーキテクチャに基づき、以下の2つのデータストアを設計します：

- **Read Model（PostgreSQL）**: クエリ最適化されたリレーショナルスキーマ
- **Event Store（DynamoDB）**: イベントソーシング用のイベントストア

これらのデータモデルは、第1章で定義した要件（月間50,000件の受注処理、与信管理、請求管理）を満たすよう設計されています。

## 2.1 Read Model（PostgreSQL）のスキーマ設計

Read Modelは、クエリ性能を最適化するために非正規化されたスキーマを採用します。受注管理システムでは、以下のテーブル群を設計します。

### 見積もりテーブル群

#### quotations（見積もりテーブル）

取引先からの見積もり依頼に対する見積もり情報を格納します。

```sql
CREATE TABLE quotations (
  -- 主キー
  quotation_id UUID PRIMARY KEY,

  -- 外部キー
  customer_id UUID NOT NULL,
  company_id UUID NOT NULL,  -- 自社（D社）のID

  -- ビジネスキー
  quotation_number VARCHAR(50) NOT NULL UNIQUE,

  -- 見積もり情報
  quotation_date DATE NOT NULL,
  valid_until DATE NOT NULL,  -- 有効期限

  -- ステータス
  status VARCHAR(20) NOT NULL,  -- Draft, Submitted, Approved, Expired, ConvertedToOrder, Cancelled

  -- 金額情報
  subtotal_amount DECIMAL(15, 2) NOT NULL,  -- 小計（税抜）
  discount_amount DECIMAL(15, 2) NOT NULL DEFAULT 0,  -- 割引額
  tax_amount DECIMAL(15, 2) NOT NULL,  -- 消費税額
  total_amount DECIMAL(15, 2) NOT NULL,  -- 合計金額（税込）

  -- 備考
  notes TEXT,

  -- 監査情報
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by VARCHAR(100) NOT NULL,
  updated_by VARCHAR(100) NOT NULL,

  -- 制約
  CONSTRAINT fk_quotations_customer FOREIGN KEY (customer_id) REFERENCES customers(customer_id),
  CONSTRAINT fk_quotations_company FOREIGN KEY (company_id) REFERENCES companies(company_id),
  CONSTRAINT chk_quotations_status CHECK (status IN ('Draft', 'Submitted', 'Approved', 'Expired', 'ConvertedToOrder', 'Cancelled')),
  CONSTRAINT chk_quotations_valid_until CHECK (valid_until >= quotation_date),
  CONSTRAINT chk_quotations_amounts CHECK (
    subtotal_amount >= 0 AND
    discount_amount >= 0 AND
    tax_amount >= 0 AND
    total_amount >= 0
  )
);

-- インデックス
CREATE INDEX idx_quotations_customer ON quotations(customer_id, quotation_date DESC);
CREATE INDEX idx_quotations_status ON quotations(status, quotation_date DESC);
CREATE INDEX idx_quotations_valid_until ON quotations(valid_until) WHERE status IN ('Submitted', 'Approved');
CREATE INDEX idx_quotations_number ON quotations(quotation_number);
```

**設計のポイント**:

1. **ステータス管理**: 見積もりのライフサイクルを明確に管理
   - Draft: 作成中（取引先未送付）
   - Submitted: 提出済み（取引先確認待ち）
   - Approved: 承認済み（注文変換可能）
   - Expired: 有効期限切れ
   - ConvertedToOrder: 注文に変換済み
   - Cancelled: キャンセル

2. **金額の非正規化**: subtotal、discount、tax、totalを全て保存し、クエリ時の計算を不要に

3. **インデックス戦略**:
   - 取引先別の見積もり一覧照会を高速化（idx_quotations_customer）
   - ステータス別の見積もり管理を高速化（idx_quotations_status）
   - 有効期限切れチェックを高速化（idx_quotations_valid_until）

#### quotation_items（見積もり明細テーブル）

見積もりの明細行を格納します。

```sql
CREATE TABLE quotation_items (
  -- 主キー
  quotation_item_id UUID PRIMARY KEY,

  -- 外部キー
  quotation_id UUID NOT NULL,
  product_id UUID NOT NULL,

  -- 明細情報
  line_number INT NOT NULL,  -- 行番号（1から開始）

  -- 商品情報（非正規化）
  product_code VARCHAR(50) NOT NULL,
  product_name VARCHAR(200) NOT NULL,

  -- 数量・単価
  quantity DECIMAL(10, 2) NOT NULL,
  unit_of_measure VARCHAR(20) NOT NULL,  -- 単位（個、kg、L など）
  unit_price DECIMAL(15, 2) NOT NULL,  -- 単価（税抜）

  -- 金額計算
  subtotal DECIMAL(15, 2) NOT NULL,  -- 小計 = quantity × unit_price
  discount_rate DECIMAL(5, 4),  -- 割引率（0.1000 = 10%）
  discount_amount DECIMAL(15, 2) NOT NULL DEFAULT 0,  -- 割引額

  -- 税金
  tax_category VARCHAR(20) NOT NULL,  -- Standard（10%）, Reduced（8%）, TaxFree（0%）
  tax_rate DECIMAL(5, 4) NOT NULL,  -- 税率（0.1000 = 10%）
  tax_amount DECIMAL(15, 2) NOT NULL,  -- 税額

  -- 合計
  total_amount DECIMAL(15, 2) NOT NULL,  -- 合計金額（税込）

  -- 備考
  notes TEXT,

  -- 監査情報
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  -- 制約
  CONSTRAINT fk_quotation_items_quotation FOREIGN KEY (quotation_id) REFERENCES quotations(quotation_id) ON DELETE CASCADE,
  CONSTRAINT fk_quotation_items_product FOREIGN KEY (product_id) REFERENCES products(product_id),
  CONSTRAINT uq_quotation_items_line UNIQUE (quotation_id, line_number),
  CONSTRAINT chk_quotation_items_quantity CHECK (quantity > 0),
  CONSTRAINT chk_quotation_items_tax_category CHECK (tax_category IN ('Standard', 'Reduced', 'TaxFree')),
  CONSTRAINT chk_quotation_items_amounts CHECK (
    unit_price >= 0 AND
    subtotal >= 0 AND
    discount_amount >= 0 AND
    tax_amount >= 0 AND
    total_amount >= 0
  )
);

-- インデックス
CREATE INDEX idx_quotation_items_quotation ON quotation_items(quotation_id, line_number);
CREATE INDEX idx_quotation_items_product ON quotation_items(product_id);
```

**設計のポイント**:

1. **商品情報の非正規化**: product_codeとproduct_nameを保存し、商品マスタへのJOINを削減

2. **金額計算の明示**:
   ```
   subtotal = quantity × unit_price
   discount_amount = subtotal × discount_rate
   tax_amount = (subtotal - discount_amount) × tax_rate
   total_amount = subtotal - discount_amount + tax_amount
   ```

3. **税区分の管理**: 軽減税率対応（標準10%、軽減8%、非課税0%）

### 注文テーブル群

#### orders（注文テーブル）

確定した注文情報を格納します。見積もりから変換された注文と、直接作成された注文の両方を管理します。

```sql
CREATE TABLE orders (
  -- 主キー
  order_id UUID PRIMARY KEY,

  -- 外部キー
  customer_id UUID NOT NULL,
  company_id UUID NOT NULL,
  quotation_id UUID,  -- 見積もりから変換された場合

  -- ビジネスキー
  order_number VARCHAR(50) NOT NULL UNIQUE,

  -- 注文情報
  order_date DATE NOT NULL,
  requested_delivery_date DATE,  -- 希望配送日
  scheduled_delivery_date DATE,  -- 配送予定日
  actual_delivery_date DATE,  -- 実際の配送完了日

  -- ステータス
  status VARCHAR(30) NOT NULL,  -- Created, StockReserved, CreditApproved, Confirmed, Shipped, Delivered, Cancelled, Returned

  -- 配送先情報（非正規化）
  shipping_address_postal_code VARCHAR(10),
  shipping_address_prefecture VARCHAR(20),
  shipping_address_city VARCHAR(100),
  shipping_address_street VARCHAR(200),
  shipping_address_building VARCHAR(200),

  -- 金額情報
  subtotal_amount DECIMAL(15, 2) NOT NULL,
  discount_amount DECIMAL(15, 2) NOT NULL DEFAULT 0,
  tax_amount DECIMAL(15, 2) NOT NULL,
  total_amount DECIMAL(15, 2) NOT NULL,

  -- Saga情報
  saga_id UUID,  -- 注文Sagaの識別子

  -- 備考
  notes TEXT,

  -- 監査情報
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by VARCHAR(100) NOT NULL,
  updated_by VARCHAR(100) NOT NULL,

  -- 制約
  CONSTRAINT fk_orders_customer FOREIGN KEY (customer_id) REFERENCES customers(customer_id),
  CONSTRAINT fk_orders_company FOREIGN KEY (company_id) REFERENCES companies(company_id),
  CONSTRAINT fk_orders_quotation FOREIGN KEY (quotation_id) REFERENCES quotations(quotation_id),
  CONSTRAINT chk_orders_status CHECK (status IN (
    'Created', 'StockReserved', 'CreditApproved', 'Confirmed',
    'Shipped', 'Delivered', 'Cancelled', 'Returned'
  )),
  CONSTRAINT chk_orders_amounts CHECK (
    subtotal_amount >= 0 AND
    discount_amount >= 0 AND
    tax_amount >= 0 AND
    total_amount >= 0
  )
);

-- インデックス
CREATE INDEX idx_orders_customer_date ON orders(customer_id, order_date DESC);
CREATE INDEX idx_orders_status_date ON orders(status, order_date DESC);
CREATE INDEX idx_orders_delivery_date ON orders(scheduled_delivery_date)
  WHERE status IN ('Confirmed', 'Shipped');
CREATE INDEX idx_orders_number ON orders(order_number);
CREATE INDEX idx_orders_saga ON orders(saga_id) WHERE saga_id IS NOT NULL;

-- 全文検索インデックス（注文番号、顧客名での検索用）
CREATE INDEX idx_orders_fulltext ON orders
  USING gin(to_tsvector('japanese', order_number));
```

**設計のポイント**:

1. **ステータス遷移**: 注文のライフサイクルを8つのステータスで管理
   ```
   Created → StockReserved → CreditApproved → Confirmed → Shipped → Delivered
                ↓                ↓               ↓           ↓
            Cancelled        Cancelled       Cancelled    Returned
   ```

2. **配送先情報の非正規化**: 注文時点の配送先住所を保存（顧客マスタの住所変更の影響を受けない）

3. **Saga追跡**: saga_idにより、分散トランザクションの進捗を追跡可能

#### order_items（注文明細テーブル）

注文の明細行を格納します。

```sql
CREATE TABLE order_items (
  -- 主キー
  order_item_id UUID PRIMARY KEY,

  -- 外部キー
  order_id UUID NOT NULL,
  product_id UUID NOT NULL,
  warehouse_id UUID,  -- 引当倉庫

  -- 明細情報
  line_number INT NOT NULL,

  -- 商品情報（非正規化）
  product_code VARCHAR(50) NOT NULL,
  product_name VARCHAR(200) NOT NULL,

  -- 数量・単価
  quantity DECIMAL(10, 2) NOT NULL,
  unit_of_measure VARCHAR(20) NOT NULL,
  unit_price DECIMAL(15, 2) NOT NULL,

  -- 金額計算
  subtotal DECIMAL(15, 2) NOT NULL,
  discount_rate DECIMAL(5, 4),
  discount_amount DECIMAL(15, 2) NOT NULL DEFAULT 0,

  -- 税金
  tax_category VARCHAR(20) NOT NULL,
  tax_rate DECIMAL(5, 4) NOT NULL,
  tax_amount DECIMAL(15, 2) NOT NULL,

  -- 合計
  total_amount DECIMAL(15, 2) NOT NULL,

  -- 在庫引当情報
  reserved_quantity DECIMAL(10, 2),  -- 引当済み数量
  shipped_quantity DECIMAL(10, 2),  -- 出荷済み数量

  -- 備考
  notes TEXT,

  -- 監査情報
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  -- 制約
  CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders(order_id) ON DELETE CASCADE,
  CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products(product_id),
  CONSTRAINT fk_order_items_warehouse FOREIGN KEY (warehouse_id) REFERENCES warehouses(warehouse_id),
  CONSTRAINT uq_order_items_line UNIQUE (order_id, line_number),
  CONSTRAINT chk_order_items_quantity CHECK (quantity > 0),
  CONSTRAINT chk_order_items_reserved CHECK (reserved_quantity IS NULL OR reserved_quantity <= quantity),
  CONSTRAINT chk_order_items_shipped CHECK (shipped_quantity IS NULL OR shipped_quantity <= quantity)
);

-- インデックス
CREATE INDEX idx_order_items_order ON order_items(order_id, line_number);
CREATE INDEX idx_order_items_product ON order_items(product_id);
CREATE INDEX idx_order_items_warehouse ON order_items(warehouse_id) WHERE warehouse_id IS NOT NULL;
```

### 与信テーブル

#### credit_limits（与信テーブル）

取引先ごとの与信限度額と使用状況を管理します。

```sql
CREATE TABLE credit_limits (
  -- 主キー
  customer_id UUID PRIMARY KEY,

  -- 与信情報
  limit_amount DECIMAL(15, 2) NOT NULL,  -- 与信限度額
  used_amount DECIMAL(15, 2) NOT NULL DEFAULT 0,  -- 現在使用額
  available_amount DECIMAL(15, 2) GENERATED ALWAYS AS (limit_amount - used_amount) STORED,  -- 利用可能額

  -- バージョン管理（楽観的ロック）
  version BIGINT NOT NULL DEFAULT 1,

  -- 監査情報
  last_updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  last_updated_by VARCHAR(100) NOT NULL,

  -- 制約
  CONSTRAINT fk_credit_limits_customer FOREIGN KEY (customer_id) REFERENCES customers(customer_id),
  CONSTRAINT chk_credit_limits_amounts CHECK (
    limit_amount >= 0 AND
    used_amount >= 0 AND
    used_amount <= limit_amount
  )
);

-- インデックス
CREATE INDEX idx_credit_limits_usage ON credit_limits((used_amount::FLOAT / limit_amount::FLOAT))
  WHERE limit_amount > 0;  -- 与信使用率でのソート用
```

**設計のポイント**:

1. **計算カラム（GENERATED ALWAYS）**: available_amountを自動計算し、整合性を保証

2. **楽観的ロック**: versionカラムにより、与信枠の競合更新を検出

3. **使用率インデックス**: 与信使用率（used_amount / limit_amount）での検索を高速化

### 請求・入金テーブル群

#### invoices（請求テーブル）

月次締めで発行される請求書情報を格納します。

```sql
CREATE TABLE invoices (
  -- 主キー
  invoice_id UUID PRIMARY KEY,

  -- 外部キー
  customer_id UUID NOT NULL,
  company_id UUID NOT NULL,

  -- ビジネスキー
  invoice_number VARCHAR(50) NOT NULL UNIQUE,

  -- 請求情報
  billing_year_month CHAR(7) NOT NULL,  -- YYYY-MM形式
  closing_date DATE NOT NULL,  -- 締日（月末）

  -- 金額情報
  subtotal_amount DECIMAL(15, 2) NOT NULL,
  tax_amount DECIMAL(15, 2) NOT NULL,
  total_amount DECIMAL(15, 2) NOT NULL,
  paid_amount DECIMAL(15, 2) NOT NULL DEFAULT 0,  -- 入金済み金額
  balance_amount DECIMAL(15, 2) GENERATED ALWAYS AS (total_amount - paid_amount) STORED,  -- 未入金残高

  -- ステータス
  payment_status VARCHAR(20) NOT NULL,  -- Unpaid, PartiallyPaid, FullyPaid, Overdue

  -- 期日
  issue_date DATE NOT NULL,  -- 請求書発行日
  due_date DATE NOT NULL,  -- 支払期限

  -- 備考
  notes TEXT,

  -- 監査情報
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  -- 制約
  CONSTRAINT fk_invoices_customer FOREIGN KEY (customer_id) REFERENCES customers(customer_id),
  CONSTRAINT fk_invoices_company FOREIGN KEY (company_id) REFERENCES companies(company_id),
  CONSTRAINT chk_invoices_status CHECK (payment_status IN ('Unpaid', 'PartiallyPaid', 'FullyPaid', 'Overdue')),
  CONSTRAINT chk_invoices_amounts CHECK (
    subtotal_amount >= 0 AND
    tax_amount >= 0 AND
    total_amount >= 0 AND
    paid_amount >= 0 AND
    paid_amount <= total_amount
  ),
  CONSTRAINT chk_invoices_due_date CHECK (due_date >= issue_date)
);

-- インデックス
CREATE INDEX idx_invoices_customer_month ON invoices(customer_id, billing_year_month DESC);
CREATE INDEX idx_invoices_status ON invoices(payment_status, due_date);
CREATE INDEX idx_invoices_due_date ON invoices(due_date) WHERE payment_status IN ('Unpaid', 'PartiallyPaid');
CREATE INDEX idx_invoices_number ON invoices(invoice_number);
```

#### invoice_orders（請求書-注文関連テーブル）

請求書にどの注文が含まれているかを管理します。

```sql
CREATE TABLE invoice_orders (
  -- 複合主キー
  invoice_id UUID NOT NULL,
  order_id UUID NOT NULL,

  PRIMARY KEY (invoice_id, order_id),

  -- 制約
  CONSTRAINT fk_invoice_orders_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(invoice_id) ON DELETE CASCADE,
  CONSTRAINT fk_invoice_orders_order FOREIGN KEY (order_id) REFERENCES orders(order_id)
);

-- インデックス
CREATE INDEX idx_invoice_orders_order ON invoice_orders(order_id);
```

#### payments（入金テーブル）

取引先からの入金情報を格納します。

```sql
CREATE TABLE payments (
  -- 主キー
  payment_id UUID PRIMARY KEY,

  -- 外部キー
  invoice_id UUID NOT NULL,

  -- 入金情報
  payment_date DATE NOT NULL,
  payment_amount DECIMAL(15, 2) NOT NULL,
  payment_method VARCHAR(20) NOT NULL,  -- BankTransfer, Bill, Cash, CreditCard

  -- 振込情報
  bank_name VARCHAR(100),
  account_holder_name VARCHAR(100),
  transfer_reference VARCHAR(100),  -- 振込依頼人名・参照番号

  -- 備考
  notes TEXT,

  -- 監査情報
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by VARCHAR(100) NOT NULL,

  -- 制約
  CONSTRAINT fk_payments_invoice FOREIGN KEY (invoice_id) REFERENCES invoices(invoice_id),
  CONSTRAINT chk_payments_method CHECK (payment_method IN ('BankTransfer', 'Bill', 'Cash', 'CreditCard')),
  CONSTRAINT chk_payments_amount CHECK (payment_amount > 0)
);

-- インデックス
CREATE INDEX idx_payments_invoice ON payments(invoice_id, payment_date DESC);
CREATE INDEX idx_payments_date ON payments(payment_date DESC);
```

## 2.2 DynamoDBのテーブル設計（Event Store）

Event Storeには、集約のライフサイクル全体を記録するイベントを保存します。DynamoDBを使用する理由は以下の通りです：

- **高スループット**: 月間50,000件（1日平均1,667件）の書き込みに対応
- **スケーラビリティ**: 自動的にスケールアウト
- **イベント順序保証**: パーティションキー内での順序保証

### テーブル構造

すべてのイベントは単一のテーブル `events` に格納されます。

```
テーブル名: events

パーティションキー: aggregate_id (String)
ソートキー: sequence_number (Number)

属性:
- aggregate_id: 集約ID（例: "Quotation-uuid", "Order-uuid"）
- sequence_number: シーケンス番号（1から開始）
- aggregate_type: 集約タイプ（"Quotation", "Order", "CreditLimit", "Invoice"）
- event_type: イベントタイプ（"QuotationCreated", "OrderConfirmed"など）
- event_version: イベントのバージョン（"V1", "V2"など）
- event_data: イベントデータ（JSON形式）
- metadata: メタデータ（タイムスタンプ、コマンドID、ユーザーIDなど）
- timestamp: イベント発生時刻（ISO 8601形式）
```

**GSI（グローバルセカンダリインデックス）**:

```
GSI名: events_by_type_and_time
パーティションキー: event_type
ソートキー: timestamp

用途: 特定イベントタイプの時系列検索（例: 過去7日間のOrderConfirmedイベント）
```

### Quotation Events（見積もりイベント）

見積もり集約のライフサイクルを表すイベント：

```json
{
  "aggregate_id": "Quotation-123e4567-e89b-12d3-a456-426614174000",
  "sequence_number": 1,
  "aggregate_type": "Quotation",
  "event_type": "QuotationCreated",
  "event_version": "V1",
  "event_data": {
    "quotation_id": "123e4567-e89b-12d3-a456-426614174000",
    "customer_id": "customer-uuid",
    "quotation_number": "Q-2025-00001",
    "quotation_date": "2025-01-15",
    "valid_until": "2025-02-14",
    "items": [
      {
        "product_id": "product-uuid-1",
        "product_code": "P-001",
        "product_name": "商品A",
        "quantity": 100,
        "unit_price": 1000.00,
        "tax_rate": 0.10
      }
    ],
    "total_amount": 110000.00
  },
  "metadata": {
    "command_id": "cmd-uuid",
    "user_id": "user-uuid",
    "correlation_id": "correlation-uuid"
  },
  "timestamp": "2025-01-15T10:30:00Z"
}
```

**イベント一覧**:

| イベント | 説明 | 次のステータス |
|---------|------|--------------|
| QuotationCreated_V1 | 見積もり作成 | Draft |
| QuotationSubmitted_V1 | 見積もり提出（取引先へ送付） | Submitted |
| QuotationApproved_V1 | 見積もり承認（取引先による承認） | Approved |
| QuotationExpired_V1 | 見積もり有効期限切れ | Expired |
| QuotationConvertedToOrder_V1 | 注文への変換 | ConvertedToOrder |
| QuotationCancelled_V1 | 見積もりキャンセル | Cancelled |

### Order Events（注文イベント）

注文集約のライフサイクルとSagaの進捗を表すイベント：

```json
{
  "aggregate_id": "Order-456e4567-e89b-12d3-a456-426614174001",
  "sequence_number": 1,
  "aggregate_type": "Order",
  "event_type": "OrderCreated",
  "event_version": "V1",
  "event_data": {
    "order_id": "456e4567-e89b-12d3-a456-426614174001",
    "customer_id": "customer-uuid",
    "order_number": "O-2025-00001",
    "order_date": "2025-01-15",
    "quotation_id": "quotation-uuid",  // null if direct order
    "items": [
      {
        "product_id": "product-uuid-1",
        "quantity": 100,
        "unit_price": 1000.00
      }
    ],
    "total_amount": 110000.00,
    "saga_id": "saga-uuid"
  },
  "metadata": {
    "command_id": "cmd-uuid",
    "user_id": "user-uuid",
    "correlation_id": "correlation-uuid"
  },
  "timestamp": "2025-01-15T11:00:00Z"
}
```

**イベント一覧**:

| イベント | 説明 | Sagaステップ | 次のステータス |
|---------|------|-------------|--------------|
| OrderCreated_V1 | 注文作成 | 1 | Created |
| OrderCreatedFromQuotation_V1 | 見積もりから注文作成 | 1 | Created |
| StockReservationRequested_V1 | 在庫引当要求 | 2（開始） | Created |
| StockReserved_V1 | 在庫引当成功 | 2（完了） | StockReserved |
| StockReservationFailed_V1 | 在庫引当失敗 | 2（失敗） | Cancelled |
| CreditCheckRequested_V1 | 与信チェック要求 | 3（開始） | StockReserved |
| CreditChecked_V1 | 与信チェック成功 | 3（完了） | CreditApproved |
| CreditCheckFailed_V1 | 与信チェック失敗 | 3（失敗） | Cancelled |
| OrderConfirmed_V1 | 注文確定 | 4 | Confirmed |
| ShippingInstructionIssued_V1 | 出荷指示発行 | 5 | Confirmed |
| OrderShipped_V1 | 出荷完了 | - | Shipped |
| OrderDelivered_V1 | 配送完了 | - | Delivered |
| OrderCancelled_V1 | 注文キャンセル | - | Cancelled |
| OrderReturned_V1 | 返品 | - | Returned |

### CreditLimit Events（与信イベント）

与信枠の管理を表すイベント：

```json
{
  "aggregate_id": "CreditLimit-customer-uuid",
  "sequence_number": 5,
  "aggregate_type": "CreditLimit",
  "event_type": "CreditReserved",
  "event_version": "V1",
  "event_data": {
    "customer_id": "customer-uuid",
    "order_id": "order-uuid",
    "reserved_amount": 110000.00,
    "previous_used_amount": 500000.00,
    "new_used_amount": 610000.00,
    "available_amount": 2390000.00  // limit 3,000,000 - used 610,000
  },
  "metadata": {
    "saga_id": "saga-uuid",
    "command_id": "cmd-uuid"
  },
  "timestamp": "2025-01-15T11:01:00Z"
}
```

**イベント一覧**:

| イベント | 説明 |
|---------|------|
| CreditLimitSet_V1 | 与信限度額設定 |
| CreditReserved_V1 | 与信枠引当（注文確定前） |
| CreditReleased_V1 | 与信枠解放（注文キャンセル時） |
| CreditUsed_V1 | 与信使用（注文確定時） |
| CreditRecovered_V1 | 与信回収（入金時） |
| CreditLimitAdjusted_V1 | 与信限度額調整 |

### Invoice Events（請求イベント）

請求書の発行と入金を表すイベント：

```json
{
  "aggregate_id": "Invoice-789e4567-e89b-12d3-a456-426614174002",
  "sequence_number": 1,
  "aggregate_type": "Invoice",
  "event_type": "InvoiceGenerated",
  "event_version": "V1",
  "event_data": {
    "invoice_id": "789e4567-e89b-12d3-a456-426614174002",
    "customer_id": "customer-uuid",
    "invoice_number": "INV-2025-01-0001",
    "billing_year_month": "2025-01",
    "closing_date": "2025-01-31",
    "order_ids": ["order-uuid-1", "order-uuid-2"],
    "total_amount": 5500000.00,
    "due_date": "2025-02-28"
  },
  "metadata": {
    "command_id": "cmd-uuid",
    "user_id": "system",
    "batch_id": "batch-uuid"
  },
  "timestamp": "2025-02-01T00:00:00Z"
}
```

**イベント一覧**:

| イベント | 説明 |
|---------|------|
| InvoiceGenerated_V1 | 請求書発行（月次バッチ） |
| PaymentRecorded_V1 | 入金記録 |
| PaymentReminded_V1 | 入金催促通知 |
| InvoiceFullyPaid_V1 | 全額入金完了 |

## イベントのバージョニング戦略

イベントスキーマは時間とともに進化します。後方互換性を保つため、以下の戦略を採用します：

### 1. イベントバージョンの命名規則

```
EventName_V1, EventName_V2, EventName_V3, ...
```

例:
- `OrderCreated_V1`: 初期バージョン
- `OrderCreated_V2`: 配送先住所フィールド追加
- `OrderCreated_V3`: 決済方法フィールド追加

### 2. アップキャスティング（Upcasting）

古いバージョンのイベントを読み込む際、最新バージョンに変換：

```scala
object OrderCreatedUpcaster {
  def upcast(event: DomainEvent): OrderCreated_V3 = event match {
    case e: OrderCreated_V1 =>
      // V1からV3への変換
      OrderCreated_V3(
        orderId = e.orderId,
        customerId = e.customerId,
        // V2で追加されたフィールドはデフォルト値
        shippingAddress = None,
        // V3で追加されたフィールドはデフォルト値
        paymentMethod = PaymentMethod.Default
      )

    case e: OrderCreated_V2 =>
      // V2からV3への変換
      OrderCreated_V3(
        orderId = e.orderId,
        customerId = e.customerId,
        shippingAddress = e.shippingAddress,
        // V3で追加されたフィールドはデフォルト値
        paymentMethod = PaymentMethod.Default
      )

    case e: OrderCreated_V3 =>
      // 既にV3なのでそのまま返す
      e
  }
}
```

### 3. イベント削除の禁止

**重要**: イベントは絶対に削除しない

- イベントソーシングでは、イベントが唯一の真実の源泉
- 削除すると過去の状態を復元できなくなる
- GDPR等で削除が必要な場合は、暗号化またはマスキングで対応

## 本章のまとめ

本章では、受注管理システムのデータモデルを設計しました：

### Read Model（PostgreSQL）

**8つのテーブル群を設計**:
1. quotations / quotation_items（見積もり）
2. orders / order_items（注文）
3. credit_limits（与信）
4. invoices / invoice_orders / payments（請求・入金）

**設計原則**:
- クエリ最適化のための非正規化（商品名、配送先住所等）
- 適切なインデックス設計（取引先別、ステータス別、日付範囲）
- 金額計算の明示的な保存（再計算不要）
- 制約による整合性保証（CHECK制約、外部キー制約）

### Event Store（DynamoDB）

**4つの集約のイベント設計**:
1. Quotation Events（見積もりライフサイクル）
2. Order Events（注文SagaとステータS遷移）
3. CreditLimit Events（与信枠の管理）
4. Invoice Events（請求と入金）

**設計原則**:
- イベントの不変性（追記のみ、削除なし）
- イベントバージョニング（_V1, _V2, ...）
- アップキャスティングによる後方互換性
- メタデータによるトレーサビリティ（correlation_id、saga_id等）

次章では、これらのテーブルに投入する実践的なテストデータの設計を行います。
