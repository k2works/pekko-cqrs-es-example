# 【第5部 第2章】データモデルの設計

## 本章の目的

本章では、発注管理システムのデータモデルを設計します。CQRS（Command Query Responsibility Segregation）パターンに従い、**Read Model**（PostgreSQL）と**Write Model**（イベントストア）を分離して設計します。

- **Read Model**: クエリに最適化されたリレーショナルスキーマ
- **Write Model**: イベントソーシングによる完全な履歴管理

## 2.1 Read Model（PostgreSQL）のスキーマ設計

Read Modelは、GraphQL APIを通じて提供するクエリ機能に最適化されたスキーマです。

### 2.1.1 仕入先テーブル（suppliers）

仕入先の基本情報を管理します。

```sql
CREATE TABLE suppliers (
  -- 識別子
  id VARCHAR(255) PRIMARY KEY,
  tenant_id VARCHAR(255) NOT NULL,

  -- 基本情報
  supplier_code VARCHAR(50) NOT NULL,
  supplier_name VARCHAR(200) NOT NULL,
  supplier_type VARCHAR(50) NOT NULL,  -- MAJOR, MEDIUM, SMALL

  -- 連絡先情報
  postal_code VARCHAR(10),
  address VARCHAR(500),
  phone_number VARCHAR(20),
  email VARCHAR(255),
  contact_person VARCHAR(100),

  -- 支払条件
  closing_day INTEGER NOT NULL,        -- 締日（1-31、月末=31）
  payment_day INTEGER NOT NULL,        -- 支払日（1-31、月末=31）
  payment_term_days INTEGER NOT NULL,  -- 支払サイト（日数）

  -- リードタイム
  standard_lead_time_days INTEGER NOT NULL,  -- 標準リードタイム

  -- 仕入先評価
  quality_score DECIMAL(5, 2),              -- 品質スコア（0-100）
  delivery_compliance_rate DECIMAL(5, 2),   -- 納期遵守率（0-100%）
  total_transaction_amount DECIMAL(15, 2),  -- 累計取引額

  -- ステータス
  is_active BOOLEAN NOT NULL DEFAULT TRUE,

  -- タイムスタンプ
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  -- インデックス用制約
  CONSTRAINT uk_suppliers_code UNIQUE (tenant_id, supplier_code)
);

-- インデックス
CREATE INDEX idx_suppliers_tenant ON suppliers(tenant_id);
CREATE INDEX idx_suppliers_type ON suppliers(supplier_type);
CREATE INDEX idx_suppliers_active ON suppliers(is_active);
CREATE INDEX idx_suppliers_name ON suppliers(supplier_name);

-- コメント
COMMENT ON TABLE suppliers IS '仕入先マスタ';
COMMENT ON COLUMN suppliers.id IS '仕入先ID';
COMMENT ON COLUMN suppliers.tenant_id IS 'テナントID';
COMMENT ON COLUMN suppliers.supplier_code IS '仕入先コード';
COMMENT ON COLUMN suppliers.supplier_name IS '仕入先名';
COMMENT ON COLUMN suppliers.supplier_type IS '仕入先タイプ（MAJOR:大手、MEDIUM:中堅、SMALL:小規模）';
COMMENT ON COLUMN suppliers.closing_day IS '締日（月の日付、月末=31）';
COMMENT ON COLUMN suppliers.payment_day IS '支払日（月の日付、月末=31）';
COMMENT ON COLUMN suppliers.payment_term_days IS '支払サイト（締日からの日数）';
COMMENT ON COLUMN suppliers.standard_lead_time_days IS '標準リードタイム（日数）';
COMMENT ON COLUMN suppliers.quality_score IS '品質スコア（0-100点）';
COMMENT ON COLUMN suppliers.delivery_compliance_rate IS '納期遵守率（0-100%）';
```

### 2.1.2 発注テーブル（purchase_orders）

発注の基本情報を管理します。

```sql
CREATE TABLE purchase_orders (
  -- 識別子
  id VARCHAR(255) PRIMARY KEY,
  tenant_id VARCHAR(255) NOT NULL,
  supplier_id VARCHAR(255) NOT NULL,

  -- 発注情報
  order_number VARCHAR(50) NOT NULL,
  order_date DATE NOT NULL,
  delivery_date DATE NOT NULL,
  expected_receiving_date DATE,

  -- ステータス
  status VARCHAR(50) NOT NULL,  -- CREATED, PENDING_APPROVAL, APPROVED, ISSUED, PARTIALLY_RECEIVED, COMPLETED, CANCELLED

  -- 金額
  subtotal_amount DECIMAL(15, 2) NOT NULL,
  tax_amount DECIMAL(15, 2) NOT NULL,
  total_amount DECIMAL(15, 2) NOT NULL,

  -- 承認情報
  approval_required BOOLEAN NOT NULL DEFAULT FALSE,
  approver_id VARCHAR(255),
  approved_at TIMESTAMP,
  rejection_reason TEXT,

  -- 発注書情報
  issued_at TIMESTAMP,
  issued_by VARCHAR(255),

  -- メモ
  notes TEXT,

  -- タイムスタンプ
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  -- 外部キー
  CONSTRAINT fk_purchase_orders_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id),
  CONSTRAINT uk_purchase_orders_number UNIQUE (tenant_id, order_number)
);

-- インデックス
CREATE INDEX idx_purchase_orders_tenant ON purchase_orders(tenant_id);
CREATE INDEX idx_purchase_orders_supplier ON purchase_orders(supplier_id);
CREATE INDEX idx_purchase_orders_status ON purchase_orders(status);
CREATE INDEX idx_purchase_orders_date ON purchase_orders(order_date);
CREATE INDEX idx_purchase_orders_delivery ON purchase_orders(delivery_date);

-- コメント
COMMENT ON TABLE purchase_orders IS '発注テーブル';
COMMENT ON COLUMN purchase_orders.id IS '発注ID';
COMMENT ON COLUMN purchase_orders.tenant_id IS 'テナントID';
COMMENT ON COLUMN purchase_orders.supplier_id IS '仕入先ID';
COMMENT ON COLUMN purchase_orders.order_number IS '発注番号';
COMMENT ON COLUMN purchase_orders.order_date IS '発注日';
COMMENT ON COLUMN purchase_orders.delivery_date IS '納期';
COMMENT ON COLUMN purchase_orders.expected_receiving_date IS '入荷予定日';
COMMENT ON COLUMN purchase_orders.status IS 'ステータス';
COMMENT ON COLUMN purchase_orders.approval_required IS '承認必要フラグ';
COMMENT ON COLUMN purchase_orders.approver_id IS '承認者ID';
COMMENT ON COLUMN purchase_orders.approved_at IS '承認日時';
```

### 2.1.3 発注明細テーブル（purchase_order_items）

発注の明細情報を管理します。

```sql
CREATE TABLE purchase_order_items (
  -- 識別子
  id VARCHAR(255) PRIMARY KEY,
  purchase_order_id VARCHAR(255) NOT NULL,
  product_id VARCHAR(255) NOT NULL,

  -- 数量・単価
  ordered_quantity INTEGER NOT NULL,
  received_quantity INTEGER NOT NULL DEFAULT 0,
  accepted_quantity INTEGER NOT NULL DEFAULT 0,
  unit_price DECIMAL(15, 2) NOT NULL,

  -- 金額
  subtotal_amount DECIMAL(15, 2) NOT NULL,

  -- 税金
  tax_type VARCHAR(50) NOT NULL,        -- STANDARD, REDUCED, TAX_FREE
  tax_rate DECIMAL(5, 4) NOT NULL,
  tax_amount DECIMAL(15, 2) NOT NULL,

  -- 商品情報（スナップショット）
  product_name VARCHAR(200) NOT NULL,
  product_code VARCHAR(50) NOT NULL,

  -- 入荷予定
  expected_receiving_date DATE,

  -- タイムスタンプ
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  -- 外部キー
  CONSTRAINT fk_purchase_order_items_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders(id) ON DELETE CASCADE
);

-- インデックス
CREATE INDEX idx_purchase_order_items_order ON purchase_order_items(purchase_order_id);
CREATE INDEX idx_purchase_order_items_product ON purchase_order_items(product_id);

-- コメント
COMMENT ON TABLE purchase_order_items IS '発注明細テーブル';
COMMENT ON COLUMN purchase_order_items.id IS '発注明細ID';
COMMENT ON COLUMN purchase_order_items.purchase_order_id IS '発注ID';
COMMENT ON COLUMN purchase_order_items.product_id IS '商品ID';
COMMENT ON COLUMN purchase_order_items.ordered_quantity IS '発注数量';
COMMENT ON COLUMN purchase_order_items.received_quantity IS '入荷数量';
COMMENT ON COLUMN purchase_order_items.accepted_quantity IS '検収数量';
COMMENT ON COLUMN purchase_order_items.unit_price IS '単価';
COMMENT ON COLUMN purchase_order_items.tax_type IS '税区分';
COMMENT ON COLUMN purchase_order_items.tax_rate IS '税率';
```

### 2.1.4 入荷テーブル（receivings）

入荷・検収の情報を管理します。

```sql
CREATE TABLE receivings (
  -- 識別子
  id VARCHAR(255) PRIMARY KEY,
  tenant_id VARCHAR(255) NOT NULL,
  purchase_order_id VARCHAR(255) NOT NULL,
  warehouse_id VARCHAR(255) NOT NULL,

  -- 入荷情報
  receiving_number VARCHAR(50) NOT NULL,
  receiving_date DATE NOT NULL,

  -- 検収情報
  inspection_date DATE,
  inspector_id VARCHAR(255),

  -- ステータス
  status VARCHAR(50) NOT NULL,  -- RECEIVED, INSPECTING, COMPLETED, DISCREPANCY

  -- 金額
  subtotal_amount DECIMAL(15, 2) NOT NULL,
  tax_amount DECIMAL(15, 2) NOT NULL,
  total_amount DECIMAL(15, 2) NOT NULL,

  -- 差異情報
  has_discrepancy BOOLEAN NOT NULL DEFAULT FALSE,
  discrepancy_notes TEXT,

  -- タイムスタンプ
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  -- 外部キー
  CONSTRAINT fk_receivings_purchase_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders(id),
  CONSTRAINT uk_receivings_number UNIQUE (tenant_id, receiving_number)
);

-- インデックス
CREATE INDEX idx_receivings_tenant ON receivings(tenant_id);
CREATE INDEX idx_receivings_purchase_order ON receivings(purchase_order_id);
CREATE INDEX idx_receivings_warehouse ON receivings(warehouse_id);
CREATE INDEX idx_receivings_status ON receivings(status);
CREATE INDEX idx_receivings_date ON receivings(receiving_date);
CREATE INDEX idx_receivings_discrepancy ON receivings(has_discrepancy);

-- コメント
COMMENT ON TABLE receivings IS '入荷テーブル';
COMMENT ON COLUMN receivings.id IS '入荷ID';
COMMENT ON COLUMN receivings.tenant_id IS 'テナントID';
COMMENT ON COLUMN receivings.purchase_order_id IS '発注ID';
COMMENT ON COLUMN receivings.warehouse_id IS '倉庫ID';
COMMENT ON COLUMN receivings.receiving_number IS '入荷番号';
COMMENT ON COLUMN receivings.receiving_date IS '入荷日';
COMMENT ON COLUMN receivings.inspection_date IS '検収日';
COMMENT ON COLUMN receivings.inspector_id IS '検収担当者ID';
COMMENT ON COLUMN receivings.status IS 'ステータス';
COMMENT ON COLUMN receivings.has_discrepancy IS '差異ありフラグ';
```

### 2.1.5 入荷明細テーブル（receiving_items）

入荷・検収の明細情報を管理します。

```sql
CREATE TABLE receiving_items (
  -- 識別子
  id VARCHAR(255) PRIMARY KEY,
  receiving_id VARCHAR(255) NOT NULL,
  purchase_order_item_id VARCHAR(255) NOT NULL,
  product_id VARCHAR(255) NOT NULL,

  -- 数量
  ordered_quantity INTEGER NOT NULL,
  received_quantity INTEGER NOT NULL,
  accepted_quantity INTEGER NOT NULL,
  rejected_quantity INTEGER NOT NULL DEFAULT 0,

  -- 差異
  discrepancy_quantity INTEGER NOT NULL DEFAULT 0,
  discrepancy_reason VARCHAR(100),  -- SHORT_SHIPMENT, OVER_SHIPMENT, DAMAGED, DEFECTIVE, WRONG_ITEM, EXPIRED

  -- 検収結果
  inspection_result VARCHAR(50) NOT NULL,  -- ACCEPTED, REJECTED, PARTIALLY_ACCEPTED

  -- ロット情報
  lot_number VARCHAR(100),
  expiry_date DATE,
  manufacturing_date DATE,

  -- 保管情報
  warehouse_zone_id VARCHAR(255),

  -- タイムスタンプ
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  -- 外部キー
  CONSTRAINT fk_receiving_items_receiving FOREIGN KEY (receiving_id) REFERENCES receivings(id) ON DELETE CASCADE,
  CONSTRAINT fk_receiving_items_po_item FOREIGN KEY (purchase_order_item_id) REFERENCES purchase_order_items(id)
);

-- インデックス
CREATE INDEX idx_receiving_items_receiving ON receiving_items(receiving_id);
CREATE INDEX idx_receiving_items_po_item ON receiving_items(purchase_order_item_id);
CREATE INDEX idx_receiving_items_product ON receiving_items(product_id);
CREATE INDEX idx_receiving_items_result ON receiving_items(inspection_result);
CREATE INDEX idx_receiving_items_lot ON receiving_items(lot_number);

-- コメント
COMMENT ON TABLE receiving_items IS '入荷明細テーブル';
COMMENT ON COLUMN receiving_items.id IS '入荷明細ID';
COMMENT ON COLUMN receiving_items.receiving_id IS '入荷ID';
COMMENT ON COLUMN receiving_items.purchase_order_item_id IS '発注明細ID';
COMMENT ON COLUMN receiving_items.product_id IS '商品ID';
COMMENT ON COLUMN receiving_items.ordered_quantity IS '発注数量';
COMMENT ON COLUMN receiving_items.received_quantity IS '入荷数量';
COMMENT ON COLUMN receiving_items.accepted_quantity IS '検収数量';
COMMENT ON COLUMN receiving_items.rejected_quantity IS '不合格数量';
COMMENT ON COLUMN receiving_items.discrepancy_quantity IS '差異数量';
COMMENT ON COLUMN receiving_items.discrepancy_reason IS '差異理由';
COMMENT ON COLUMN receiving_items.inspection_result IS '検収結果';
```

### 2.1.6 仕入先請求テーブル（supplier_invoices）

仕入先からの請求書情報を管理します。

```sql
CREATE TABLE supplier_invoices (
  -- 識別子
  id VARCHAR(255) PRIMARY KEY,
  tenant_id VARCHAR(255) NOT NULL,
  supplier_id VARCHAR(255) NOT NULL,
  purchase_order_id VARCHAR(255) NOT NULL,
  receiving_id VARCHAR(255),

  -- 請求書情報
  invoice_number VARCHAR(50) NOT NULL,
  invoice_date DATE NOT NULL,

  -- 金額
  subtotal_amount DECIMAL(15, 2) NOT NULL,
  tax_amount DECIMAL(15, 2) NOT NULL,
  total_amount DECIMAL(15, 2) NOT NULL,

  -- ステータス
  status VARCHAR(50) NOT NULL,  -- RECEIVED, MATCHING, APPROVED, PAID, REJECTED

  -- 3-way matching情報
  matching_status VARCHAR(50),  -- FULL_MATCH, PARTIAL_MATCH, NO_MATCH
  matching_completed_at TIMESTAMP,
  matching_notes TEXT,

  -- 支払情報
  payment_due_date DATE NOT NULL,
  payment_scheduled_date DATE,

  -- タイムスタンプ
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  -- 外部キー
  CONSTRAINT fk_supplier_invoices_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id),
  CONSTRAINT fk_supplier_invoices_purchase_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders(id),
  CONSTRAINT fk_supplier_invoices_receiving FOREIGN KEY (receiving_id) REFERENCES receivings(id),
  CONSTRAINT uk_supplier_invoices_number UNIQUE (tenant_id, supplier_id, invoice_number)
);

-- インデックス
CREATE INDEX idx_supplier_invoices_tenant ON supplier_invoices(tenant_id);
CREATE INDEX idx_supplier_invoices_supplier ON supplier_invoices(supplier_id);
CREATE INDEX idx_supplier_invoices_purchase_order ON supplier_invoices(purchase_order_id);
CREATE INDEX idx_supplier_invoices_status ON supplier_invoices(status);
CREATE INDEX idx_supplier_invoices_matching ON supplier_invoices(matching_status);
CREATE INDEX idx_supplier_invoices_due_date ON supplier_invoices(payment_due_date);

-- コメント
COMMENT ON TABLE supplier_invoices IS '仕入先請求書テーブル';
COMMENT ON COLUMN supplier_invoices.id IS '請求書ID';
COMMENT ON COLUMN supplier_invoices.tenant_id IS 'テナントID';
COMMENT ON COLUMN supplier_invoices.supplier_id IS '仕入先ID';
COMMENT ON COLUMN supplier_invoices.purchase_order_id IS '発注ID';
COMMENT ON COLUMN supplier_invoices.receiving_id IS '入荷ID';
COMMENT ON COLUMN supplier_invoices.invoice_number IS '請求書番号';
COMMENT ON COLUMN supplier_invoices.invoice_date IS '請求日';
COMMENT ON COLUMN supplier_invoices.status IS 'ステータス';
COMMENT ON COLUMN supplier_invoices.matching_status IS '突合ステータス';
COMMENT ON COLUMN supplier_invoices.payment_due_date IS '支払期限';
```

### 2.1.7 仕入先請求明細テーブル（supplier_invoice_items）

仕入先請求書の明細情報を管理します。

```sql
CREATE TABLE supplier_invoice_items (
  -- 識別子
  id VARCHAR(255) PRIMARY KEY,
  supplier_invoice_id VARCHAR(255) NOT NULL,
  purchase_order_item_id VARCHAR(255),
  product_id VARCHAR(255) NOT NULL,

  -- 商品情報
  product_name VARCHAR(200) NOT NULL,
  product_code VARCHAR(50) NOT NULL,

  -- 数量・単価
  quantity INTEGER NOT NULL,
  unit_price DECIMAL(15, 2) NOT NULL,

  -- 金額
  subtotal_amount DECIMAL(15, 2) NOT NULL,
  tax_amount DECIMAL(15, 2) NOT NULL,
  total_amount DECIMAL(15, 2) NOT NULL,

  -- 税金
  tax_type VARCHAR(50) NOT NULL,
  tax_rate DECIMAL(5, 4) NOT NULL,

  -- タイムスタンプ
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  -- 外部キー
  CONSTRAINT fk_supplier_invoice_items_invoice FOREIGN KEY (supplier_invoice_id) REFERENCES supplier_invoices(id) ON DELETE CASCADE,
  CONSTRAINT fk_supplier_invoice_items_po_item FOREIGN KEY (purchase_order_item_id) REFERENCES purchase_order_items(id)
);

-- インデックス
CREATE INDEX idx_supplier_invoice_items_invoice ON supplier_invoice_items(supplier_invoice_id);
CREATE INDEX idx_supplier_invoice_items_po_item ON supplier_invoice_items(purchase_order_item_id);
CREATE INDEX idx_supplier_invoice_items_product ON supplier_invoice_items(product_id);

-- コメント
COMMENT ON TABLE supplier_invoice_items IS '仕入先請求明細テーブル';
COMMENT ON COLUMN supplier_invoice_items.id IS '請求明細ID';
COMMENT ON COLUMN supplier_invoice_items.supplier_invoice_id IS '請求書ID';
COMMENT ON COLUMN supplier_invoice_items.purchase_order_item_id IS '発注明細ID';
COMMENT ON COLUMN supplier_invoice_items.product_id IS '商品ID';
```

### 2.1.8 支払テーブル（supplier_payments）

仕入先への支払情報を管理します。

```sql
CREATE TABLE supplier_payments (
  -- 識別子
  id VARCHAR(255) PRIMARY KEY,
  tenant_id VARCHAR(255) NOT NULL,
  supplier_id VARCHAR(255) NOT NULL,
  supplier_invoice_id VARCHAR(255) NOT NULL,

  -- 支払情報
  payment_number VARCHAR(50) NOT NULL,
  payment_date DATE NOT NULL,
  payment_amount DECIMAL(15, 2) NOT NULL,

  -- 支払方法
  payment_method VARCHAR(50) NOT NULL,  -- BANK_TRANSFER, PROMISSORY_NOTE, CASH

  -- 銀行情報（振込の場合）
  bank_name VARCHAR(100),
  branch_name VARCHAR(100),
  account_type VARCHAR(50),
  account_number VARCHAR(50),

  -- 手形情報（手形の場合）
  note_number VARCHAR(50),
  note_due_date DATE,

  -- 支払承認
  approved_by VARCHAR(255),
  approved_at TIMESTAMP,

  -- メモ
  notes TEXT,

  -- タイムスタンプ
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  -- 外部キー
  CONSTRAINT fk_supplier_payments_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id),
  CONSTRAINT fk_supplier_payments_invoice FOREIGN KEY (supplier_invoice_id) REFERENCES supplier_invoices(id),
  CONSTRAINT uk_supplier_payments_number UNIQUE (tenant_id, payment_number)
);

-- インデックス
CREATE INDEX idx_supplier_payments_tenant ON supplier_payments(tenant_id);
CREATE INDEX idx_supplier_payments_supplier ON supplier_payments(supplier_id);
CREATE INDEX idx_supplier_payments_invoice ON supplier_payments(supplier_invoice_id);
CREATE INDEX idx_supplier_payments_date ON supplier_payments(payment_date);
CREATE INDEX idx_supplier_payments_method ON supplier_payments(payment_method);

-- コメント
COMMENT ON TABLE supplier_payments IS '仕入先支払テーブル';
COMMENT ON COLUMN supplier_payments.id IS '支払ID';
COMMENT ON COLUMN supplier_payments.tenant_id IS 'テナントID';
COMMENT ON COLUMN supplier_payments.supplier_id IS '仕入先ID';
COMMENT ON COLUMN supplier_payments.supplier_invoice_id IS '請求書ID';
COMMENT ON COLUMN supplier_payments.payment_number IS '支払番号';
COMMENT ON COLUMN supplier_payments.payment_date IS '支払日';
COMMENT ON COLUMN supplier_payments.payment_amount IS '支払金額';
COMMENT ON COLUMN supplier_payments.payment_method IS '支払方法';
```

### 2.1.9 ER図

発注管理システムのER図を以下に示します。

```
┌─────────────────┐
│   suppliers     │
│  (仕入先)       │
└─────────────────┘
        │ 1
        │
        │ *
┌─────────────────┐
│purchase_orders  │
│   (発注)        │
└─────────────────┘
        │ 1
        │
        │ *
┌──────────────────────┐
│purchase_order_items  │
│   (発注明細)         │
└──────────────────────┘
        │
        │
┌─────────────────┐         ┌─────────────────┐
│   receivings    │         │supplier_invoices│
│   (入荷)        │─────────│  (仕入先請求)   │
└─────────────────┘    1:1  └─────────────────┘
        │ 1                          │ 1
        │                            │
        │ *                          │ *
┌─────────────────┐         ┌──────────────────────┐
│receiving_items  │         │supplier_invoice_items│
│ (入荷明細)      │         │  (請求明細)          │
└─────────────────┘         └──────────────────────┘
                                     │
                                     │
                            ┌─────────────────┐
                            │supplier_payments│
                            │   (支払)        │
                            └─────────────────┘
```

## 2.2 Write Model（イベントストア）のスキーマ設計

Write Modelは、イベントソーシングによる完全な履歴管理を実現します。各集約のイベントをProtocol Buffersで定義します。

### 2.2.1 Supplier Events

仕入先集約のイベント定義です。

```protobuf
// modules/procurement/interface-adapter-contract/src/main/protobuf/supplier_events.proto
syntax = "proto3";

package com.example.procurement.adapter.serializer;

import "google/protobuf/timestamp.proto";

// 仕入先登録イベント
message SupplierRegisteredV1 {
  string supplier_id = 1;
  string tenant_id = 2;
  string supplier_code = 3;
  string supplier_name = 4;
  string supplier_type = 5;  // MAJOR, MEDIUM, SMALL

  // 支払条件
  int32 closing_day = 6;
  int32 payment_day = 7;
  int32 payment_term_days = 8;

  // リードタイム
  int32 standard_lead_time_days = 9;

  // 連絡先
  string postal_code = 10;
  string address = 11;
  string phone_number = 12;
  string email = 13;
  string contact_person = 14;

  google.protobuf.Timestamp occurred_at = 15;
}

// 仕入先情報更新イベント
message SupplierUpdatedV1 {
  string supplier_id = 1;
  string supplier_name = 2;

  // 更新可能なフィールド
  optional string postal_code = 3;
  optional string address = 4;
  optional string phone_number = 5;
  optional string email = 6;
  optional string contact_person = 7;
  optional int32 standard_lead_time_days = 8;

  google.protobuf.Timestamp occurred_at = 9;
}

// 仕入先評価更新イベント
message SupplierEvaluatedV1 {
  string supplier_id = 1;

  // 評価指標
  double quality_score = 2;                // 品質スコア（0-100）
  double delivery_compliance_rate = 3;     // 納期遵守率（0-100%）
  string total_transaction_amount = 4;     // 累計取引額（BigDecimal文字列）

  // 評価期間
  string evaluation_period_start = 5;
  string evaluation_period_end = 6;

  google.protobuf.Timestamp occurred_at = 7;
}

// 仕入先無効化イベント
message SupplierDeactivatedV1 {
  string supplier_id = 1;
  string reason = 2;
  google.protobuf.Timestamp occurred_at = 3;
}
```

### 2.2.2 PurchaseOrder Events

発注集約のイベント定義です。

```protobuf
// modules/procurement/interface-adapter-contract/src/main/protobuf/purchase_order_events.proto
syntax = "proto3";

package com.example.procurement.adapter.serializer;

import "google/protobuf/timestamp.proto";

// 発注作成イベント
message PurchaseOrderCreatedV1 {
  string purchase_order_id = 1;
  string tenant_id = 2;
  string supplier_id = 3;
  string order_number = 4;
  string order_date = 5;       // LocalDate文字列（ISO-8601）
  string delivery_date = 6;

  repeated PurchaseOrderItemV1 items = 7;

  string subtotal_amount = 8;  // BigDecimal文字列
  string tax_amount = 9;
  string total_amount = 10;

  bool approval_required = 11;

  google.protobuf.Timestamp occurred_at = 12;
}

message PurchaseOrderItemV1 {
  string purchase_order_item_id = 1;
  string product_id = 2;
  string product_name = 3;
  string product_code = 4;

  int32 ordered_quantity = 5;
  string unit_price = 6;        // BigDecimal文字列
  string subtotal_amount = 7;

  string tax_type = 8;          // STANDARD, REDUCED, TAX_FREE
  string tax_rate = 9;          // BigDecimal文字列
  string tax_amount = 10;

  optional string expected_receiving_date = 11;
}

// 発注承認申請イベント
message PurchaseOrderApprovalRequestedV1 {
  string purchase_order_id = 1;
  string requester_id = 2;
  repeated string required_approvers = 3;
  google.protobuf.Timestamp occurred_at = 4;
}

// 発注承認完了イベント
message PurchaseOrderApprovedV1 {
  string purchase_order_id = 1;
  string approver_id = 2;
  google.protobuf.Timestamp occurred_at = 3;
}

// 発注承認却下イベント
message PurchaseOrderRejectedV1 {
  string purchase_order_id = 1;
  string rejector_id = 2;
  string rejection_reason = 3;
  google.protobuf.Timestamp occurred_at = 4;
}

// 発注書発行イベント
message PurchaseOrderIssuedV1 {
  string purchase_order_id = 1;
  string issued_by = 2;
  google.protobuf.Timestamp occurred_at = 3;
}

// 発注キャンセルイベント
message PurchaseOrderCancelledV1 {
  string purchase_order_id = 1;
  string cancelled_by = 2;
  string cancellation_reason = 3;
  google.protobuf.Timestamp occurred_at = 4;
}

// 発注一部入荷イベント
message PurchaseOrderPartiallyReceivedV1 {
  string purchase_order_id = 1;
  string receiving_id = 2;
  repeated PartiallyReceivedItemV1 items = 3;
  google.protobuf.Timestamp occurred_at = 4;
}

message PartiallyReceivedItemV1 {
  string purchase_order_item_id = 1;
  int32 received_quantity = 2;
}

// 発注完了イベント
message PurchaseOrderCompletedV1 {
  string purchase_order_id = 1;
  google.protobuf.Timestamp occurred_at = 2;
}
```

### 2.2.3 Receiving Events

入荷集約のイベント定義です。

```protobuf
// modules/procurement/interface-adapter-contract/src/main/protobuf/receiving_events.proto
syntax = "proto3";

package com.example.procurement.adapter.serializer;

import "google/protobuf/timestamp.proto";

// 入荷記録作成イベント
message ReceivingCreatedV1 {
  string receiving_id = 1;
  string tenant_id = 2;
  string purchase_order_id = 3;
  string warehouse_id = 4;
  string receiving_number = 5;
  string receiving_date = 6;  // LocalDate文字列

  google.protobuf.Timestamp occurred_at = 7;
}

// 商品入荷イベント
message GoodsReceivedV1 {
  string receiving_id = 1;
  repeated ReceivedItemV1 items = 2;
  google.protobuf.Timestamp occurred_at = 3;
}

message ReceivedItemV1 {
  string receiving_item_id = 1;
  string purchase_order_item_id = 2;
  string product_id = 3;
  int32 ordered_quantity = 4;
  int32 received_quantity = 5;

  optional string lot_number = 6;
  optional string expiry_date = 7;
  optional string manufacturing_date = 8;
}

// 検収開始イベント
message InspectionStartedV1 {
  string receiving_id = 1;
  string inspector_id = 2;
  google.protobuf.Timestamp occurred_at = 3;
}

// 検収完了イベント
message InspectionCompletedV1 {
  string receiving_id = 1;
  repeated InspectedItemV1 items = 2;

  string subtotal_amount = 3;
  string tax_amount = 4;
  string total_amount = 5;

  google.protobuf.Timestamp occurred_at = 6;
}

message InspectedItemV1 {
  string receiving_item_id = 1;
  string product_id = 2;
  int32 received_quantity = 3;
  int32 accepted_quantity = 4;
  int32 rejected_quantity = 5;

  string inspection_result = 6;  // ACCEPTED, REJECTED, PARTIALLY_ACCEPTED

  string warehouse_zone_id = 7;
  optional string lot_number = 8;
  optional string expiry_date = 9;
}

// 差異検出イベント
message DiscrepancyDetectedV1 {
  string receiving_id = 1;
  repeated DiscrepancyItemV1 items = 2;
  google.protobuf.Timestamp occurred_at = 3;
}

message DiscrepancyItemV1 {
  string receiving_item_id = 1;
  string product_id = 2;
  int32 ordered_quantity = 3;
  int32 received_quantity = 4;
  int32 discrepancy_quantity = 5;
  string discrepancy_reason = 6;  // SHORT_SHIPMENT, OVER_SHIPMENT, DAMAGED, etc.
}
```

### 2.2.4 SupplierPayment Events

支払集約のイベント定義です。

```protobuf
// modules/procurement/interface-adapter-contract/src/main/protobuf/supplier_payment_events.proto
syntax = "proto3";

package com.example.procurement.adapter.serializer;

import "google/protobuf/timestamp.proto";

// 請求書受領イベント
message InvoiceReceivedV1 {
  string supplier_invoice_id = 1;
  string tenant_id = 2;
  string supplier_id = 3;
  string purchase_order_id = 4;
  string receiving_id = 5;

  string invoice_number = 6;
  string invoice_date = 7;     // LocalDate文字列

  repeated InvoiceItemV1 items = 8;

  string subtotal_amount = 9;
  string tax_amount = 10;
  string total_amount = 11;

  string payment_due_date = 12;

  google.protobuf.Timestamp occurred_at = 13;
}

message InvoiceItemV1 {
  string supplier_invoice_item_id = 1;
  string purchase_order_item_id = 2;
  string product_id = 3;
  string product_name = 4;
  string product_code = 5;

  int32 quantity = 6;
  string unit_price = 7;
  string subtotal_amount = 8;
  string tax_amount = 9;
  string total_amount = 10;

  string tax_type = 11;
  string tax_rate = 12;
}

// 3-way matching完了イベント
message ThreeWayMatchingCompletedV1 {
  string supplier_invoice_id = 1;
  string matching_status = 2;  // FULL_MATCH, PARTIAL_MATCH

  repeated MatchingResultV1 matching_results = 3;

  google.protobuf.Timestamp occurred_at = 4;
}

message MatchingResultV1 {
  string item_id = 1;
  bool quantity_matches = 2;
  bool amount_matches = 3;
  bool unit_price_matches = 4;
  repeated string discrepancies = 5;
}

// 3-way matching失敗イベント
message ThreeWayMatchingFailedV1 {
  string supplier_invoice_id = 1;
  repeated MatchingDiscrepancyV1 discrepancies = 2;
  google.protobuf.Timestamp occurred_at = 3;
}

message MatchingDiscrepancyV1 {
  string discrepancy_type = 1;  // QUANTITY_MISMATCH, AMOUNT_MISMATCH, UNIT_PRICE_MISMATCH
  string description = 2;
  string expected_value = 3;
  string actual_value = 4;
}

// 支払予定イベント
message PaymentScheduledV1 {
  string supplier_invoice_id = 1;
  string payment_scheduled_date = 2;
  string payment_amount = 3;
  google.protobuf.Timestamp occurred_at = 4;
}

// 支払完了イベント
message PaymentCompletedV1 {
  string supplier_payment_id = 1;
  string supplier_invoice_id = 2;
  string supplier_id = 3;
  string payment_number = 4;
  string payment_date = 5;
  string payment_amount = 6;
  string payment_method = 7;  // BANK_TRANSFER, PROMISSORY_NOTE, CASH

  optional BankTransferInfoV1 bank_transfer_info = 8;
  optional PromissoryNoteInfoV1 promissory_note_info = 9;

  google.protobuf.Timestamp occurred_at = 10;
}

message BankTransferInfoV1 {
  string bank_name = 1;
  string branch_name = 2;
  string account_type = 3;
  string account_number = 4;
}

message PromissoryNoteInfoV1 {
  string note_number = 1;
  string note_due_date = 2;
}

// 請求書承認イベント
message InvoiceApprovedV1 {
  string supplier_invoice_id = 1;
  string approver_id = 2;
  google.protobuf.Timestamp occurred_at = 3;
}

// 請求書却下イベント
message InvoiceRejectedV1 {
  string supplier_invoice_id = 1;
  string rejector_id = 2;
  string rejection_reason = 3;
  google.protobuf.Timestamp occurred_at = 4;
}
```

## 2.3 Flyway Migrationスクリプト

Read Modelのスキーマを管理するためのFlywayマイグレーションスクリプトを作成します。

### 2.3.1 V1__create_procurement_tables.sql

```sql
-- modules/procurement/flyway-migration/src/main/resources/db/migration/V1__create_procurement_tables.sql

-- 仕入先テーブル
CREATE TABLE suppliers (
  id VARCHAR(255) PRIMARY KEY,
  tenant_id VARCHAR(255) NOT NULL,
  supplier_code VARCHAR(50) NOT NULL,
  supplier_name VARCHAR(200) NOT NULL,
  supplier_type VARCHAR(50) NOT NULL,
  postal_code VARCHAR(10),
  address VARCHAR(500),
  phone_number VARCHAR(20),
  email VARCHAR(255),
  contact_person VARCHAR(100),
  closing_day INTEGER NOT NULL,
  payment_day INTEGER NOT NULL,
  payment_term_days INTEGER NOT NULL,
  standard_lead_time_days INTEGER NOT NULL,
  quality_score DECIMAL(5, 2),
  delivery_compliance_rate DECIMAL(5, 2),
  total_transaction_amount DECIMAL(15, 2),
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_suppliers_code UNIQUE (tenant_id, supplier_code)
);

CREATE INDEX idx_suppliers_tenant ON suppliers(tenant_id);
CREATE INDEX idx_suppliers_type ON suppliers(supplier_type);
CREATE INDEX idx_suppliers_active ON suppliers(is_active);

-- 発注テーブル
CREATE TABLE purchase_orders (
  id VARCHAR(255) PRIMARY KEY,
  tenant_id VARCHAR(255) NOT NULL,
  supplier_id VARCHAR(255) NOT NULL,
  order_number VARCHAR(50) NOT NULL,
  order_date DATE NOT NULL,
  delivery_date DATE NOT NULL,
  expected_receiving_date DATE,
  status VARCHAR(50) NOT NULL,
  subtotal_amount DECIMAL(15, 2) NOT NULL,
  tax_amount DECIMAL(15, 2) NOT NULL,
  total_amount DECIMAL(15, 2) NOT NULL,
  approval_required BOOLEAN NOT NULL DEFAULT FALSE,
  approver_id VARCHAR(255),
  approved_at TIMESTAMP,
  rejection_reason TEXT,
  issued_at TIMESTAMP,
  issued_by VARCHAR(255),
  notes TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_purchase_orders_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id),
  CONSTRAINT uk_purchase_orders_number UNIQUE (tenant_id, order_number)
);

CREATE INDEX idx_purchase_orders_tenant ON purchase_orders(tenant_id);
CREATE INDEX idx_purchase_orders_supplier ON purchase_orders(supplier_id);
CREATE INDEX idx_purchase_orders_status ON purchase_orders(status);
CREATE INDEX idx_purchase_orders_date ON purchase_orders(order_date);

-- 発注明細テーブル
CREATE TABLE purchase_order_items (
  id VARCHAR(255) PRIMARY KEY,
  purchase_order_id VARCHAR(255) NOT NULL,
  product_id VARCHAR(255) NOT NULL,
  ordered_quantity INTEGER NOT NULL,
  received_quantity INTEGER NOT NULL DEFAULT 0,
  accepted_quantity INTEGER NOT NULL DEFAULT 0,
  unit_price DECIMAL(15, 2) NOT NULL,
  subtotal_amount DECIMAL(15, 2) NOT NULL,
  tax_type VARCHAR(50) NOT NULL,
  tax_rate DECIMAL(5, 4) NOT NULL,
  tax_amount DECIMAL(15, 2) NOT NULL,
  product_name VARCHAR(200) NOT NULL,
  product_code VARCHAR(50) NOT NULL,
  expected_receiving_date DATE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_purchase_order_items_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders(id) ON DELETE CASCADE
);

CREATE INDEX idx_purchase_order_items_order ON purchase_order_items(purchase_order_id);
CREATE INDEX idx_purchase_order_items_product ON purchase_order_items(product_id);

-- 入荷テーブル
CREATE TABLE receivings (
  id VARCHAR(255) PRIMARY KEY,
  tenant_id VARCHAR(255) NOT NULL,
  purchase_order_id VARCHAR(255) NOT NULL,
  warehouse_id VARCHAR(255) NOT NULL,
  receiving_number VARCHAR(50) NOT NULL,
  receiving_date DATE NOT NULL,
  inspection_date DATE,
  inspector_id VARCHAR(255),
  status VARCHAR(50) NOT NULL,
  subtotal_amount DECIMAL(15, 2) NOT NULL,
  tax_amount DECIMAL(15, 2) NOT NULL,
  total_amount DECIMAL(15, 2) NOT NULL,
  has_discrepancy BOOLEAN NOT NULL DEFAULT FALSE,
  discrepancy_notes TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_receivings_purchase_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders(id),
  CONSTRAINT uk_receivings_number UNIQUE (tenant_id, receiving_number)
);

CREATE INDEX idx_receivings_tenant ON receivings(tenant_id);
CREATE INDEX idx_receivings_purchase_order ON receivings(purchase_order_id);
CREATE INDEX idx_receivings_warehouse ON receivings(warehouse_id);
CREATE INDEX idx_receivings_status ON receivings(status);

-- 入荷明細テーブル
CREATE TABLE receiving_items (
  id VARCHAR(255) PRIMARY KEY,
  receiving_id VARCHAR(255) NOT NULL,
  purchase_order_item_id VARCHAR(255) NOT NULL,
  product_id VARCHAR(255) NOT NULL,
  ordered_quantity INTEGER NOT NULL,
  received_quantity INTEGER NOT NULL,
  accepted_quantity INTEGER NOT NULL,
  rejected_quantity INTEGER NOT NULL DEFAULT 0,
  discrepancy_quantity INTEGER NOT NULL DEFAULT 0,
  discrepancy_reason VARCHAR(100),
  inspection_result VARCHAR(50) NOT NULL,
  lot_number VARCHAR(100),
  expiry_date DATE,
  manufacturing_date DATE,
  warehouse_zone_id VARCHAR(255),
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_receiving_items_receiving FOREIGN KEY (receiving_id) REFERENCES receivings(id) ON DELETE CASCADE,
  CONSTRAINT fk_receiving_items_po_item FOREIGN KEY (purchase_order_item_id) REFERENCES purchase_order_items(id)
);

CREATE INDEX idx_receiving_items_receiving ON receiving_items(receiving_id);
CREATE INDEX idx_receiving_items_po_item ON receiving_items(purchase_order_item_id);
CREATE INDEX idx_receiving_items_product ON receiving_items(product_id);

-- 仕入先請求テーブル
CREATE TABLE supplier_invoices (
  id VARCHAR(255) PRIMARY KEY,
  tenant_id VARCHAR(255) NOT NULL,
  supplier_id VARCHAR(255) NOT NULL,
  purchase_order_id VARCHAR(255) NOT NULL,
  receiving_id VARCHAR(255),
  invoice_number VARCHAR(50) NOT NULL,
  invoice_date DATE NOT NULL,
  subtotal_amount DECIMAL(15, 2) NOT NULL,
  tax_amount DECIMAL(15, 2) NOT NULL,
  total_amount DECIMAL(15, 2) NOT NULL,
  status VARCHAR(50) NOT NULL,
  matching_status VARCHAR(50),
  matching_completed_at TIMESTAMP,
  matching_notes TEXT,
  payment_due_date DATE NOT NULL,
  payment_scheduled_date DATE,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_supplier_invoices_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id),
  CONSTRAINT fk_supplier_invoices_purchase_order FOREIGN KEY (purchase_order_id) REFERENCES purchase_orders(id),
  CONSTRAINT fk_supplier_invoices_receiving FOREIGN KEY (receiving_id) REFERENCES receivings(id),
  CONSTRAINT uk_supplier_invoices_number UNIQUE (tenant_id, supplier_id, invoice_number)
);

CREATE INDEX idx_supplier_invoices_tenant ON supplier_invoices(tenant_id);
CREATE INDEX idx_supplier_invoices_supplier ON supplier_invoices(supplier_id);
CREATE INDEX idx_supplier_invoices_purchase_order ON supplier_invoices(purchase_order_id);
CREATE INDEX idx_supplier_invoices_status ON supplier_invoices(status);

-- 仕入先請求明細テーブル
CREATE TABLE supplier_invoice_items (
  id VARCHAR(255) PRIMARY KEY,
  supplier_invoice_id VARCHAR(255) NOT NULL,
  purchase_order_item_id VARCHAR(255),
  product_id VARCHAR(255) NOT NULL,
  product_name VARCHAR(200) NOT NULL,
  product_code VARCHAR(50) NOT NULL,
  quantity INTEGER NOT NULL,
  unit_price DECIMAL(15, 2) NOT NULL,
  subtotal_amount DECIMAL(15, 2) NOT NULL,
  tax_amount DECIMAL(15, 2) NOT NULL,
  total_amount DECIMAL(15, 2) NOT NULL,
  tax_type VARCHAR(50) NOT NULL,
  tax_rate DECIMAL(5, 4) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_supplier_invoice_items_invoice FOREIGN KEY (supplier_invoice_id) REFERENCES supplier_invoices(id) ON DELETE CASCADE,
  CONSTRAINT fk_supplier_invoice_items_po_item FOREIGN KEY (purchase_order_item_id) REFERENCES purchase_order_items(id)
);

CREATE INDEX idx_supplier_invoice_items_invoice ON supplier_invoice_items(supplier_invoice_id);
CREATE INDEX idx_supplier_invoice_items_po_item ON supplier_invoice_items(purchase_order_item_id);

-- 支払テーブル
CREATE TABLE supplier_payments (
  id VARCHAR(255) PRIMARY KEY,
  tenant_id VARCHAR(255) NOT NULL,
  supplier_id VARCHAR(255) NOT NULL,
  supplier_invoice_id VARCHAR(255) NOT NULL,
  payment_number VARCHAR(50) NOT NULL,
  payment_date DATE NOT NULL,
  payment_amount DECIMAL(15, 2) NOT NULL,
  payment_method VARCHAR(50) NOT NULL,
  bank_name VARCHAR(100),
  branch_name VARCHAR(100),
  account_type VARCHAR(50),
  account_number VARCHAR(50),
  note_number VARCHAR(50),
  note_due_date DATE,
  approved_by VARCHAR(255),
  approved_at TIMESTAMP,
  notes TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_supplier_payments_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id),
  CONSTRAINT fk_supplier_payments_invoice FOREIGN KEY (supplier_invoice_id) REFERENCES supplier_invoices(id),
  CONSTRAINT uk_supplier_payments_number UNIQUE (tenant_id, payment_number)
);

CREATE INDEX idx_supplier_payments_tenant ON supplier_payments(tenant_id);
CREATE INDEX idx_supplier_payments_supplier ON supplier_payments(supplier_id);
CREATE INDEX idx_supplier_payments_invoice ON supplier_payments(supplier_invoice_id);
CREATE INDEX idx_supplier_payments_date ON supplier_payments(payment_date);
```

### 2.3.2 マイグレーション実行

Flywayマイグレーションは、SBTタスクで実行します。

```bash
# マイグレーション実行
sbt migrateProcurement

# マイグレーション情報表示
sbt infoProcurement

# マイグレーション検証
sbt validateProcurement

# クリーン後マイグレーション
sbt cleanMigrateProcurement
```

## 2.4 DAO生成

sbt-dao-generatorを使用して、Read ModelのテーブルからDAOクラスを自動生成します。

### 2.4.1 DAO生成設定

```scala
// modules/procurement/interface-adapter/build.sbt

enablePlugins(SbtDaoGenerator)

daoGeneratorConfig := DaoGeneratorConfig(
  outputDir = (Compile / sourceManaged).value,
  packageName = "com.example.procurement.adapter.dao",
  databaseConfig = DatabaseConfig(
    driver = "org.postgresql.Driver",
    url = "jdbc:postgresql://localhost:5432/procurement_query",
    user = "postgres",
    password = "postgres"
  ),
  tableConfigs = Seq(
    TableConfig(
      tableName = "suppliers",
      entityName = "Supplier",
      idType = "String"
    ),
    TableConfig(
      tableName = "purchase_orders",
      entityName = "PurchaseOrder",
      idType = "String"
    ),
    TableConfig(
      tableName = "purchase_order_items",
      entityName = "PurchaseOrderItem",
      idType = "String"
    ),
    TableConfig(
      tableName = "receivings",
      entityName = "Receiving",
      idType = "String"
    ),
    TableConfig(
      tableName = "receiving_items",
      entityName = "ReceivingItem",
      idType = "String"
    ),
    TableConfig(
      tableName = "supplier_invoices",
      entityName = "SupplierInvoice",
      idType = "String"
    ),
    TableConfig(
      tableName = "supplier_invoice_items",
      entityName = "SupplierInvoiceItem",
      idType = "String"
    ),
    TableConfig(
      tableName = "supplier_payments",
      entityName = "SupplierPayment",
      idType = "String"
    )
  )
)
```

### 2.4.2 DAO生成コマンド

```bash
# データベースを起動してマイグレーションを実行
sbt migrateProcurement

# DAOクラスを生成
sbt "procurementInterfaceAdapter/generateAllWithDb"
```

### 2.4.3 生成されるDAOクラス

以下のDAOクラスが自動生成されます。

```scala
// modules/procurement/interface-adapter/src/main/scala/com/example/procurement/adapter/dao/SupplierDao.scala
package com.example.procurement.adapter.dao

import slick.jdbc.PostgresProfile.api._

case class SupplierRow(
  id: String,
  tenantId: String,
  supplierCode: String,
  supplierName: String,
  supplierType: String,
  postalCode: Option[String],
  address: Option[String],
  phoneNumber: Option[String],
  email: Option[String],
  contactPerson: Option[String],
  closingDay: Int,
  paymentDay: Int,
  paymentTermDays: Int,
  standardLeadTimeDays: Int,
  qualityScore: Option[BigDecimal],
  deliveryComplianceRate: Option[BigDecimal],
  totalTransactionAmount: Option[BigDecimal],
  isActive: Boolean,
  createdAt: java.time.LocalDateTime,
  updatedAt: java.time.LocalDateTime
)

class SupplierTable(tag: Tag) extends Table[SupplierRow](tag, "suppliers") {
  def id = column[String]("id", O.PrimaryKey)
  def tenantId = column[String]("tenant_id")
  def supplierCode = column[String]("supplier_code")
  def supplierName = column[String]("supplier_name")
  def supplierType = column[String]("supplier_type")
  def postalCode = column[Option[String]]("postal_code")
  def address = column[Option[String]]("address")
  def phoneNumber = column[Option[String]]("phone_number")
  def email = column[Option[String]]("email")
  def contactPerson = column[Option[String]]("contact_person")
  def closingDay = column[Int]("closing_day")
  def paymentDay = column[Int]("payment_day")
  def paymentTermDays = column[Int]("payment_term_days")
  def standardLeadTimeDays = column[Int]("standard_lead_time_days")
  def qualityScore = column[Option[BigDecimal]]("quality_score")
  def deliveryComplianceRate = column[Option[BigDecimal]]("delivery_compliance_rate")
  def totalTransactionAmount = column[Option[BigDecimal]]("total_transaction_amount")
  def isActive = column[Boolean]("is_active")
  def createdAt = column[java.time.LocalDateTime]("created_at")
  def updatedAt = column[java.time.LocalDateTime]("updated_at")

  def * = (
    id,
    tenantId,
    supplierCode,
    supplierName,
    supplierType,
    postalCode,
    address,
    phoneNumber,
    email,
    contactPerson,
    closingDay,
    paymentDay,
    paymentTermDays,
    standardLeadTimeDays,
    qualityScore,
    deliveryComplianceRate,
    totalTransactionAmount,
    isActive,
    createdAt,
    updatedAt
  ) <> (SupplierRow.tupled, SupplierRow.unapply)
}

object SupplierDao {
  val suppliers = TableQuery[SupplierTable]

  def findById(id: String): DBIO[Option[SupplierRow]] = {
    suppliers.filter(_.id === id).result.headOption
  }

  def findByTenant(tenantId: String): DBIO[Seq[SupplierRow]] = {
    suppliers.filter(_.tenantId === tenantId).result
  }

  def findByType(supplierType: String): DBIO[Seq[SupplierRow]] = {
    suppliers.filter(_.supplierType === supplierType).result
  }

  def findActive(): DBIO[Seq[SupplierRow]] = {
    suppliers.filter(_.isActive === true).result
  }

  def insert(row: SupplierRow): DBIO[Int] = {
    suppliers += row
  }

  def update(row: SupplierRow): DBIO[Int] = {
    suppliers.filter(_.id === row.id).update(row)
  }

  def delete(id: String): DBIO[Int] = {
    suppliers.filter(_.id === id).delete
  }
}
```

同様に、以下のDAOクラスも生成されます：
- `PurchaseOrderDao`
- `PurchaseOrderItemDao`
- `ReceivingDao`
- `ReceivingItemDao`
- `SupplierInvoiceDao`
- `SupplierInvoiceItemDao`
- `SupplierPaymentDao`

## 2.5 まとめ

本章では、発注管理システムのデータモデルを設計しました。

**Read Model（PostgreSQL）**:
- 8つのテーブル（仕入先、発注、発注明細、入荷、入荷明細、仕入先請求、請求明細、支払）
- クエリに最適化されたリレーショナルスキーマ
- Flyway Migrationによるバージョン管理
- sbt-dao-generatorによるDAO自動生成

**Write Model（イベントストア）**:
- 4つの集約のイベント定義（Supplier、PurchaseOrder、Receiving、SupplierPayment）
- Protocol Buffersによる型安全なシリアライゼーション
- イベントソーシングによる完全な履歴管理

次章では、これらのデータモデルを使用する具体的なテストデータを作成します。200社の仕入先、月間3,000件の発注パターン、季節変動を考慮したリアルなデータを生成し、システムの動作を検証します。
