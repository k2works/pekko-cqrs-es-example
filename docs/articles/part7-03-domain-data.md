# 第7部 第3章 共用データ管理に適したマスターデータ作成

本章では、D社の業務で使用する商品マスタ、勘定科目マスタ、コードマスタなどのマスターデータを作成します。

## 3.1 マスタデータ

### 3.1.1 商品カテゴリマスタ

D社で取り扱う商品のカテゴリ分類です。

```sql
-- 商品カテゴリマスタ（簡易的にcode_mastersテーブルで管理）
INSERT INTO code_masters (
  code_master_id, code_type, code_value, display_name, display_name_short,
  description, display_order, status, valid_from, created_by, created_at
) VALUES
-- 穀物類
('CAT-001', 'ProductCategory', 'RICE', '米・穀物類', '米穀物', '玄米、白米、雑穀など', 1, 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('CAT-002', 'ProductCategory', 'BEANS', '豆類', '豆類', '大豆、小豆、その他豆類', 2, 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),

-- 調味料類
('CAT-011', 'ProductCategory', 'MISO', '味噌', '味噌', '有機味噌、無添加味噌', 11, 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('CAT-012', 'ProductCategory', 'SOY_SAUCE', '醤油', '醤油', '有機醤油、無添加醤油', 12, 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('CAT-013', 'ProductCategory', 'VINEGAR', '酢', '酢', '米酢、りんご酢、黒酢', 13, 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('CAT-014', 'ProductCategory', 'SALT', '塩', '塩', '天然塩、岩塩', 14, 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),

-- 油類
('CAT-021', 'ProductCategory', 'OIL', '油類', '油類', '食用油、ごま油、オリーブオイル', 21, 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),

-- 乾物類
('CAT-031', 'ProductCategory', 'SEAWEED', '海藻類', '海藻', 'わかめ、昆布、海苔', 31, 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('CAT-032', 'ProductCategory', 'DRIED_GOODS', '乾物', '乾物', '干し椎茸、切り干し大根', 32, 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),

-- 麺類
('CAT-041', 'ProductCategory', 'NOODLES', '麺類', '麺類', 'うどん、そば、ラーメン', 41, 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),

-- 飲料
('CAT-051', 'ProductCategory', 'TEA', 'お茶', 'お茶', '緑茶、麦茶、ほうじ茶', 51, 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('CAT-052', 'ProductCategory', 'JUICE', 'ジュース', 'ジュース', '野菜ジュース、果物ジュース', 52, 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),

-- 菓子類
('CAT-061', 'ProductCategory', 'SNACKS', '菓子', '菓子', 'せんべい、クッキー', 61, 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP);
```

### 3.1.2 商品マスタ

D社で取り扱う5,000種類の商品のうち、代表的な商品データを定義します。

```sql
-- 商品マスタ（代表的な商品）
INSERT INTO products (
  product_id, product_code, product_name, product_name_kana,
  description, category_code, category_name, unit_of_measure,
  standard_cost, list_price,
  primary_supplier_id, lead_time_days, minimum_order_quantity,
  storage_condition, status, valid_from,
  created_by, created_at
) VALUES
-- 米・穀物類
(
  'PROD-0001', 'P-001', '有機玄米 5kg', 'ユウキゲンマイ5kg',
  '契約農家から仕入れた有機JAS認証の玄米', 'RICE', '米・穀物類', '個',
  2000.00, 3500.00,
  'SUP-001', 7, 1,
  '常温', 'Active', '2020-01-01',
  'system', CURRENT_TIMESTAMP
),
(
  'PROD-0002', 'P-002', '有機白米 5kg', 'ユウキハクマイ5kg',
  '有機玄米を精米した白米', 'RICE', '米・穀物類', '個',
  2200.00, 3800.00,
  'SUP-001', 7, 1,
  '常温', 'Active', '2020-01-01',
  'system', CURRENT_TIMESTAMP
),
(
  'PROD-0003', 'P-003', '発芽玄米 1kg', 'ハツガゲンマイ1kg',
  '栄養価の高い発芽玄米', 'RICE', '米・穀物類', '個',
  1200.00, 2200.00,
  'SUP-001', 7, 1,
  '冷蔵', 'Active', '2020-01-01',
  'system', CURRENT_TIMESTAMP
),
(
  'PROD-0004', 'P-004', '十六穀米 500g', 'ジュウロッコクマイ500g',
  '16種類の雑穀をブレンド', 'RICE', '米・穀物類', '個',
  800.00, 1500.00,
  'SUP-002', 10, 5,
  '常温', 'Active', '2020-01-01',
  'system', CURRENT_TIMESTAMP
),

-- 豆類
(
  'PROD-0011', 'P-011', '有機大豆 1kg', 'ユウキダイズ1kg',
  '国産有機大豆', 'BEANS', '豆類', '個',
  800.00, 1500.00,
  'SUP-003', 14, 5,
  '常温', 'Active', '2020-01-01',
  'system', CURRENT_TIMESTAMP
),
(
  'PROD-0012', 'P-012', '有機小豆 500g', 'ユウキアズキ500g',
  '北海道産有機小豆', 'BEANS', '豆類', '個',
  600.00, 1200.00,
  'SUP-003', 14, 5,
  '常温', 'Active', '2020-01-01',
  'system', CURRENT_TIMESTAMP
),
(
  'PROD-0013', 'P-013', '黒豆 300g', 'クロマメ300g',
  '丹波産黒豆', 'BEANS', '豆類', '個',
  900.00, 1800.00,
  'SUP-003', 14, 5,
  '常温', 'Active', '2020-01-01',
  'system', CURRENT_TIMESTAMP
),

-- 調味料類
(
  'PROD-0021', 'P-021', '有機味噌 500g', 'ユウキミソ500g',
  '長期熟成の有機味噌', 'MISO', '味噌', '個',
  600.00, 1200.00,
  'SUP-004', 7, 10,
  '冷蔵', 'Active', '2020-01-01',
  'system', CURRENT_TIMESTAMP
),
(
  'PROD-0022', 'P-022', '有機味噌 1kg', 'ユウキミソ1kg',
  '長期熟成の有機味噌（業務用）', 'MISO', '味噌', '個',
  1100.00, 2000.00,
  'SUP-004', 7, 5,
  '冷蔵', 'Active', '2020-01-01',
  'system', CURRENT_TIMESTAMP
),
(
  'PROD-0031', 'P-031', '有機醤油 500ml', 'ユウキショウユ500ml',
  '国産有機大豆・小麦使用', 'SOY_SAUCE', '醤油', '個',
  400.00, 800.00,
  'SUP-005', 7, 10,
  '常温', 'Active', '2020-01-01',
  'system', CURRENT_TIMESTAMP
),
(
  'PROD-0032', 'P-032', '有機醤油 1L', 'ユウキショウユ1L',
  '国産有機大豆・小麦使用（業務用）', 'SOY_SAUCE', '醤油', '個',
  750.00, 1400.00,
  'SUP-005', 7, 5,
  '常温', 'Active', '2020-01-01',
  'system', CURRENT_TIMESTAMP
),
(
  'PROD-0041', 'P-041', '米酢 500ml', 'コメズ500ml',
  '国産米100%使用', 'VINEGAR', '酢', '個',
  350.00, 700.00,
  'SUP-006', 7, 10,
  '常温', 'Active', '2020-01-01',
  'system', CURRENT_TIMESTAMP
),
(
  'PROD-0042', 'P-042', 'りんご酢 500ml', 'リンゴス500ml',
  '青森産りんご使用', 'VINEGAR', '酢', '個',
  400.00, 800.00,
  'SUP-006', 7, 10,
  '常温', 'Active', '2020-01-01',
  'system', CURRENT_TIMESTAMP
),
(
  'PROD-0051', 'P-051', '天然塩 1kg', 'テンネンエン1kg',
  '海水から作られた天然塩', 'SALT', '塩', '個',
  300.00, 600.00,
  'SUP-007', 14, 10,
  '常温', 'Active', '2020-01-01',
  'system', CURRENT_TIMESTAMP
),

-- 油類
(
  'PROD-0061', 'P-061', 'ごま油 300ml', 'ゴマアブラ300ml',
  '低温圧搾製法のごま油', 'OIL', '油類', '個',
  500.00, 1000.00,
  'SUP-008', 14, 10,
  '常温', 'Active', '2020-01-01',
  'system', CURRENT_TIMESTAMP
),
(
  'PROD-0062', 'P-062', 'オリーブオイル 500ml', 'オリーブオイル500ml',
  'エクストラバージンオリーブオイル', 'OIL', '油類', '個',
  1200.00, 2400.00,
  'SUP-009', 21, 5,
  '常温', 'Active', '2020-01-01',
  'system', CURRENT_TIMESTAMP
),

-- 海藻類
(
  'PROD-0071', 'P-071', '乾燥わかめ 50g', 'カンソウワカメ50g',
  '三陸産乾燥わかめ', 'SEAWEED', '海藻類', '個',
  300.00, 600.00,
  'SUP-010', 14, 10,
  '常温', 'Active', '2020-01-01',
  'system', CURRENT_TIMESTAMP
),
(
  'PROD-0072', 'P-072', '昆布 100g', 'コンブ100g',
  '北海道産昆布', 'SEAWEED', '海藻類', '個',
  500.00, 1000.00,
  'SUP-010', 14, 10,
  '常温', 'Active', '2020-01-01',
  'system', CURRENT_TIMESTAMP
),

-- 麺類
(
  'PROD-0081', 'P-081', 'うどん 200g', 'ウドン200g',
  '国産小麦使用の半生うどん', 'NOODLES', '麺類', '個',
  150.00, 300.00,
  'SUP-011', 7, 20,
  '冷蔵', 'Active', '2020-01-01',
  'system', CURRENT_TIMESTAMP
),
(
  'PROD-0082', 'P-082', 'そば 200g', 'ソバ200g',
  '国産そば粉使用', 'NOODLES', '麺類', '個',
  200.00, 400.00,
  'SUP-011', 7, 20,
  '冷蔵', 'Active', '2020-01-01',
  'system', CURRENT_TIMESTAMP
),

-- お茶
(
  'PROD-0091', 'P-091', '有機緑茶 100g', 'ユウキリョクチャ100g',
  '静岡産有機緑茶', 'TEA', 'お茶', '個',
  800.00, 1500.00,
  'SUP-012', 14, 5,
  '常温', 'Active', '2020-01-01',
  'system', CURRENT_TIMESTAMP
),
(
  'PROD-0092', 'P-092', 'ほうじ茶 200g', 'ホウジチャ200g',
  '香ばしいほうじ茶', 'TEA', 'お茶', '個',
  400.00, 800.00,
  'SUP-012', 14, 10,
  '常温', 'Active', '2020-01-01',
  'system', CURRENT_TIMESTAMP
);
```

**注記**: 実際には5,000種類の商品データを投入しますが、ここでは代表的な商品のみを記載しています。

### 3.1.3 商品価格履歴

商品の標準価格と特別価格の履歴を管理します。

```sql
-- 商品価格履歴（標準価格）
INSERT INTO product_prices (
  product_price_id, product_id, price_type, customer_id, unit_price,
  valid_from, valid_to, change_reason,
  created_by, created_at
) VALUES
-- P-001の価格履歴（2024年4月1日に値上げ）
('PRICE-00001', 'PROD-0001', 'Standard', NULL, 3500.00, '2020-01-01', '2024-03-31', '初期価格', 'system', CURRENT_TIMESTAMP),
('PRICE-00002', 'PROD-0001', 'Standard', NULL, 3800.00, '2024-04-01', NULL, '原材料費高騰による価格改定', 'system', CURRENT_TIMESTAMP),

-- P-002の価格履歴
('PRICE-00011', 'PROD-0002', 'Standard', NULL, 3800.00, '2020-01-01', NULL, '初期価格', 'system', CURRENT_TIMESTAMP),

-- P-003の価格履歴
('PRICE-00021', 'PROD-0003', 'Standard', NULL, 2200.00, '2020-01-01', NULL, '初期価格', 'system', CURRENT_TIMESTAMP),

-- P-004の価格履歴
('PRICE-00031', 'PROD-0004', 'Standard', NULL, 1500.00, '2020-01-01', NULL, '初期価格', 'system', CURRENT_TIMESTAMP),

-- P-021の価格履歴（段階的な値上げ）
('PRICE-00211', 'PROD-0021', 'Standard', NULL, 1200.00, '2020-01-01', '2023-03-31', '初期価格', 'system', CURRENT_TIMESTAMP),
('PRICE-00212', 'PROD-0021', 'Standard', NULL, 1300.00, '2023-04-01', '2024-03-31', '第一次価格改定', 'system', CURRENT_TIMESTAMP),
('PRICE-00213', 'PROD-0021', 'Standard', NULL, 1400.00, '2024-04-01', NULL, '第二次価格改定', 'system', CURRENT_TIMESTAMP);

-- 大口顧客向け特別価格（C-001: 大手スーパーチェーン）
INSERT INTO product_prices (
  product_price_id, product_id, price_type, customer_id, unit_price,
  valid_from, valid_to, change_reason,
  created_by, created_at
) VALUES
-- 有機玄米の特別価格（標準3,800円 → 特別3,200円）
('PRICE-SP001', 'PROD-0001', 'Special', 'CUST-001', 3200.00, '2024-01-01', NULL, '大口顧客特別価格', 'system', CURRENT_TIMESTAMP),
-- 有機白米の特別価格
('PRICE-SP002', 'PROD-0002', 'Special', 'CUST-001', 3400.00, '2024-01-01', NULL, '大口顧客特別価格', 'system', CURRENT_TIMESTAMP),
-- 有機味噌の特別価格
('PRICE-SP003', 'PROD-0021', 'Special', 'CUST-001', 1200.00, '2024-01-01', NULL, '大口顧客特別価格', 'system', CURRENT_TIMESTAMP);
```

### 3.1.4 勘定科目マスタ

第6部で使用した勘定科目マスタを共用データ管理サービスでも管理します。

```sql
-- 勘定科目マスタ（抜粋）
INSERT INTO account_subjects (
  account_subject_id, account_code, account_name, account_name_kana,
  account_type, account_subtype, balance_side,
  parent_account_id, level, display_order,
  requires_auxiliary, auxiliary_type,
  status, valid_from,
  created_by, created_at
) VALUES
-- 資産の部
('ACC-1000', '1000', '資産の部', 'シサンノブ', 'Asset', NULL, 'Debit', NULL, 1, 1000, FALSE, NULL, 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('ACC-1100', '1100', '流動資産', 'リュウドウシサン', 'Asset', 'Current', 'Debit', 'ACC-1000', 2, 1100, FALSE, NULL, 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('ACC-1111', '1111', '現金', 'ゲンキン', 'Asset', 'Current', 'Debit', 'ACC-1100', 3, 1111, FALSE, NULL, 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('ACC-1112', '1112', '普通預金', 'フツウヨキン', 'Asset', 'Current', 'Debit', 'ACC-1100', 3, 1112, FALSE, NULL, 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('ACC-1120', '1120', '売掛金', 'ウリカケキン', 'Asset', 'Current', 'Debit', 'ACC-1100', 3, 1120, TRUE, 'Customer', 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('ACC-1130', '1130', '商品', 'ショウヒン', 'Asset', 'Current', 'Debit', 'ACC-1100', 3, 1130, FALSE, NULL, 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('ACC-1190', '1190', '仮払消費税', 'カリバライショウヒゼイ', 'Asset', 'Current', 'Debit', 'ACC-1100', 3, 1190, FALSE, NULL, 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),

-- 負債の部
('ACC-2000', '2000', '負債の部', 'フサイノブ', 'Liability', NULL, 'Credit', NULL, 1, 2000, FALSE, NULL, 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('ACC-2100', '2100', '流動負債', 'リュウドウフサイ', 'Liability', 'Current', 'Credit', 'ACC-2000', 2, 2100, FALSE, NULL, 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('ACC-2110', '2110', '買掛金', 'カイカケキン', 'Liability', 'Current', 'Credit', 'ACC-2100', 3, 2110, TRUE, 'Supplier', 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('ACC-2190', '2190', '仮受消費税', 'カリウケショウヒゼイ', 'Liability', 'Current', 'Credit', 'ACC-2100', 3, 2190, FALSE, NULL, 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),

-- 純資産の部
('ACC-3000', '3000', '純資産の部', 'ジュンシサンノブ', 'Equity', NULL, 'Credit', NULL, 1, 3000, FALSE, NULL, 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('ACC-3100', '3100', '資本金', 'シホンキン', 'Equity', NULL, 'Credit', 'ACC-3000', 2, 3100, FALSE, NULL, 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),

-- 収益
('ACC-4000', '4000', '収益', 'シュウエキ', 'Revenue', NULL, 'Credit', NULL, 1, 4000, FALSE, NULL, 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('ACC-4100', '4100', '売上高', 'ウリアゲダカ', 'Revenue', 'Operating', 'Credit', 'ACC-4000', 2, 4100, FALSE, NULL, 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),

-- 費用
('ACC-5000', '5000', '費用', 'ヒヨウ', 'Expense', NULL, 'Debit', NULL, 1, 5000, FALSE, NULL, 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('ACC-5100', '5100', '売上原価', 'ウリアゲゲンカ', 'Expense', 'Operating', 'Debit', 'ACC-5000', 2, 5100, FALSE, NULL, 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP);
```

### 3.1.5 部門マスタ

D社の組織構造（20部門）を定義します。

```sql
-- 部門マスタ
INSERT INTO departments (
  department_id, department_code, department_name, department_name_kana,
  parent_department_id, level, manager_employee_id,
  start_year_month,
  created_by, created_at
) VALUES
-- レベル1: 本部
('DEPT-1000', '1000', '営業本部', 'エイギョウホンブ', NULL, 1, NULL, '2020-04', 'system', CURRENT_TIMESTAMP),
('DEPT-2000', '2000', '管理本部', 'カンリホンブ', NULL, 1, NULL, '2020-04', 'system', CURRENT_TIMESTAMP),
('DEPT-3000', '3000', '物流本部', 'ブツリュウホンブ', NULL, 1, NULL, '2020-04', 'system', CURRENT_TIMESTAMP),

-- レベル2: 営業本部配下
('DEPT-1100', '1100', '営業第一部', 'エイギョウダイイチブ', 'DEPT-1000', 2, NULL, '2020-04', 'system', CURRENT_TIMESTAMP),
('DEPT-1200', '1200', '営業第二部', 'エイギョウダイニブ', 'DEPT-1000', 2, NULL, '2020-04', 'system', CURRENT_TIMESTAMP),
('DEPT-1300', '1300', '営業第三部', 'エイギョウダイサンブ', 'DEPT-1000', 2, NULL, '2020-04', 'system', CURRENT_TIMESTAMP),
('DEPT-1400', '1400', '営業企画部', 'エイギョウキカクブ', 'DEPT-1000', 2, NULL, '2020-04', 'system', CURRENT_TIMESTAMP),

-- レベル2: 管理本部配下
('DEPT-2100', '2100', '経理部', 'ケイリブ', 'DEPT-2000', 2, NULL, '2020-04', 'system', CURRENT_TIMESTAMP),
('DEPT-2200', '2200', '人事部', 'ジンジブ', 'DEPT-2000', 2, NULL, '2020-04', 'system', CURRENT_TIMESTAMP),
('DEPT-2300', '2300', '総務部', 'ソウムブ', 'DEPT-2000', 2, NULL, '2020-04', 'system', CURRENT_TIMESTAMP),
('DEPT-2400', '2400', '情報システム部', 'ジョウホウシステムブ', 'DEPT-2000', 2, NULL, '2020-04', 'system', CURRENT_TIMESTAMP),

-- レベル2: 物流本部配下
('DEPT-3100', '3100', '物流管理部', 'ブツリュウカンリブ', 'DEPT-3000', 2, NULL, '2020-04', 'system', CURRENT_TIMESTAMP),
('DEPT-3200', '3200', '倉庫運営部', 'ソウコウンエイブ', 'DEPT-3000', 2, NULL, '2020-04', 'system', CURRENT_TIMESTAMP),
('DEPT-3300', '3300', '配送部', 'ハイソウブ', 'DEPT-3000', 2, NULL, '2020-04', 'system', CURRENT_TIMESTAMP),

-- レベル3: 営業第一部配下（課）
('DEPT-1110', '1110', '東日本営業課', 'ヒガシニホンエイギョウカ', 'DEPT-1100', 3, NULL, '2020-04', 'system', CURRENT_TIMESTAMP),
('DEPT-1120', '1120', '西日本営業課', 'ニシニホンエイギョウカ', 'DEPT-1100', 3, NULL, '2020-04', 'system', CURRENT_TIMESTAMP),

-- レベル3: 営業第二部配下（課）
('DEPT-1210', '1210', 'EC営業課', 'ECエイギョウカ', 'DEPT-1200', 3, NULL, '2020-04', 'system', CURRENT_TIMESTAMP),
('DEPT-1220', '1220', '通販営業課', 'ツウハンエイギョウカ', 'DEPT-1200', 3, NULL, '2020-04', 'system', CURRENT_TIMESTAMP),

-- レベル3: 営業第三部配下（課）
('DEPT-1310', '1310', '法人営業課', 'ホウジンエイギョウカ', 'DEPT-1300', 3, NULL, '2020-04', 'system', CURRENT_TIMESTAMP),
('DEPT-1320', '1320', '卸売営業課', 'オロシウリエイギョウカ', 'DEPT-1300', 3, NULL, '2020-04', 'system', CURRENT_TIMESTAMP);
```

### 3.1.6 社員マスタ

D社の従業員情報（1,000名のうち代表的な社員）を定義します。

```sql
-- 社員マスタ（代表的な社員）
INSERT INTO employees (
  employee_id, employee_code, employee_name, employee_name_kana,
  department_id, job_title,
  hire_date, termination_date,
  email, phone,
  created_by, created_at
) VALUES
-- 経営層
('EMP-0001', 'E-001', '山田太郎', 'ヤマダタロウ', 'DEPT-1000', '営業本部長', '2010-04-01', NULL, 'yamada.t@example.com', '03-1234-5601', 'system', CURRENT_TIMESTAMP),
('EMP-0002', 'E-002', '佐藤花子', 'サトウハナコ', 'DEPT-2000', '管理本部長', '2012-04-01', NULL, 'sato.h@example.com', '03-1234-5602', 'system', CURRENT_TIMESTAMP),
('EMP-0003', 'E-003', '鈴木一郎', 'スズキイチロウ', 'DEPT-3000', '物流本部長', '2013-04-01', NULL, 'suzuki.i@example.com', '03-1234-5603', 'system', CURRENT_TIMESTAMP),

-- 営業本部
('EMP-0101', 'E-101', '田中次郎', 'タナカジロウ', 'DEPT-1100', '営業第一部長', '2015-04-01', NULL, 'tanaka.j@example.com', '03-1234-5611', 'system', CURRENT_TIMESTAMP),
('EMP-0102', 'E-102', '高橋三郎', 'タカハシサブロウ', 'DEPT-1200', '営業第二部長', '2016-04-01', NULL, 'takahashi.s@example.com', '03-1234-5612', 'system', CURRENT_TIMESTAMP),
('EMP-0103', 'E-103', '伊藤四郎', 'イトウシロウ', 'DEPT-1300', '営業第三部長', '2017-04-01', NULL, 'ito.s@example.com', '03-1234-5613', 'system', CURRENT_TIMESTAMP),
('EMP-0104', 'E-104', '渡辺五郎', 'ワタナベゴロウ', 'DEPT-1400', '営業企画部長', '2018-04-01', NULL, 'watanabe.g@example.com', '03-1234-5614', 'system', CURRENT_TIMESTAMP),

-- 管理本部
('EMP-0201', 'E-201', '中村六郎', 'ナカムラロクロウ', 'DEPT-2100', '経理部長', '2014-04-01', NULL, 'nakamura.r@example.com', '03-1234-5621', 'system', CURRENT_TIMESTAMP),
('EMP-0202', 'E-202', '小林七子', 'コバヤシナナコ', 'DEPT-2200', '人事部長', '2015-04-01', NULL, 'kobayashi.n@example.com', '03-1234-5622', 'system', CURRENT_TIMESTAMP),
('EMP-0203', 'E-203', '加藤八郎', 'カトウハチロウ', 'DEPT-2300', '総務部長', '2016-04-01', NULL, 'kato.h@example.com', '03-1234-5623', 'system', CURRENT_TIMESTAMP),
('EMP-0204', 'E-204', '吉田九郎', 'ヨシダクロウ', 'DEPT-2400', '情報システム部長', '2017-04-01', NULL, 'yoshida.k@example.com', '03-1234-5624', 'system', CURRENT_TIMESTAMP),

-- 物流本部
('EMP-0301', 'E-301', '山本十郎', 'ヤマモトジュウロウ', 'DEPT-3100', '物流管理部長', '2015-04-01', NULL, 'yamamoto.j@example.com', '03-1234-5631', 'system', CURRENT_TIMESTAMP),
('EMP-0302', 'E-302', '松本花', 'マツモトハナ', 'DEPT-3200', '倉庫運営部長', '2016-04-01', NULL, 'matsumoto.h@example.com', '03-1234-5632', 'system', CURRENT_TIMESTAMP),
('EMP-0303', 'E-303', '井上桜', 'イノウエサクラ', 'DEPT-3300', '配送部長', '2017-04-01', NULL, 'inoue.s@example.com', '03-1234-5633', 'system', CURRENT_TIMESTAMP),

-- 営業担当者（課長・主任クラス）
('EMP-1111', 'E-1111', '木村葵', 'キムラアオイ', 'DEPT-1110', '課長', '2018-04-01', NULL, 'kimura.a@example.com', '03-1234-5711', 'system', CURRENT_TIMESTAMP),
('EMP-1112', 'E-1112', '林蓮', 'ハヤシレン', 'DEPT-1110', '主任', '2019-04-01', NULL, 'hayashi.r@example.com', '03-1234-5712', 'system', CURRENT_TIMESTAMP),
('EMP-1121', 'E-1121', '斎藤楓', 'サイトウカエデ', 'DEPT-1120', '課長', '2018-04-01', NULL, 'saito.k@example.com', '03-1234-5721', 'system', CURRENT_TIMESTAMP),
('EMP-1122', 'E-1122', '清水陽菜', 'シミズヒナ', 'DEPT-1120', '主任', '2020-04-01', NULL, 'shimizu.h@example.com', '03-1234-5722', 'system', CURRENT_TIMESTAMP),

-- 経理担当者
('EMP-2111', 'E-2111', '山口結衣', 'ヤマグチユイ', 'DEPT-2100', '課長', '2017-04-01', NULL, 'yamaguchi.y@example.com', '03-1234-5811', 'system', CURRENT_TIMESTAMP),
('EMP-2112', 'E-2112', '森美咲', 'モリミサキ', 'DEPT-2100', '主任', '2019-04-01', NULL, 'mori.m@example.com', '03-1234-5812', 'system', CURRENT_TIMESTAMP),
('EMP-2113', 'E-2113', '池田凛', 'イケダリン', 'DEPT-2100', '担当', '2021-04-01', NULL, 'ikeda.r@example.com', '03-1234-5813', 'system', CURRENT_TIMESTAMP);
```

### 3.1.7 コードマスタ

各種共通コード（税率、支払条件、配送方法など）を定義します。

```sql
-- 税率マスタ
INSERT INTO code_masters (
  code_master_id, code_type, code_value, display_name, display_name_short,
  description, display_order, additional_data, status, valid_from,
  created_by, created_at
) VALUES
('TAX-001', 'TaxRate', 'STANDARD', '標準税率10%', '標準10%', '標準税率（2019年10月1日以降）', 1, '{"rate": 0.10}', 'Active', '2019-10-01', 'system', CURRENT_TIMESTAMP),
('TAX-002', 'TaxRate', 'REDUCED', '軽減税率8%', '軽減8%', '軽減税率（食品等）', 2, '{"rate": 0.08}', 'Active', '2019-10-01', 'system', CURRENT_TIMESTAMP),
('TAX-003', 'TaxRate', 'EXEMPT', '非課税', '非課税', '非課税取引', 3, '{"rate": 0.00}', 'Active', '2019-10-01', 'system', CURRENT_TIMESTAMP),
('TAX-004', 'TaxRate', 'OLD_STANDARD', '旧標準税率8%', '旧標準8%', '標準税率（2014年4月1日〜2019年9月30日）', 4, '{"rate": 0.08}', 'Inactive', '2014-04-01', 'system', CURRENT_TIMESTAMP);

-- 支払条件マスタ
INSERT INTO code_masters (
  code_master_id, code_type, code_value, display_name, display_name_short,
  description, display_order, additional_data, status, valid_from,
  created_by, created_at
) VALUES
('PMT-001', 'PaymentTerms', 'NET30', '月末締め翌月末払い', '翌月末', '当月末締め、翌月末日払い', 1, '{"closing_day": "end_of_month", "payment_day": "end_of_next_month", "payment_day_offset": 1}', 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('PMT-002', 'PaymentTerms', 'NET60', '月末締め翌々月末払い', '翌々月末', '当月末締め、翌々月末日払い', 2, '{"closing_day": "end_of_month", "payment_day": "end_of_month_plus_2", "payment_day_offset": 2}', 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('PMT-003', 'PaymentTerms', 'IMMEDIATE', '即時払い', '即時', '即時払い', 3, '{"closing_day": "immediate", "payment_day": "immediate", "payment_day_offset": 0}', 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('PMT-004', 'PaymentTerms', 'NET15', '月末締め翌月15日払い', '翌月15日', '当月末締め、翌月15日払い', 4, '{"closing_day": "end_of_month", "payment_day": "15th_of_next_month", "payment_day_offset": 1}', 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP);

-- 配送方法マスタ
INSERT INTO code_masters (
  code_master_id, code_type, code_value, display_name, display_name_short,
  description, display_order, additional_data, status, valid_from,
  created_by, created_at
) VALUES
('SHIP-001', 'ShippingMethod', 'STANDARD', '通常配送（3営業日）', '通常配送', '通常配送（3営業日以内）', 1, '{"delivery_days": 3, "fee": 500, "fee_free_threshold": 10000}', 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('SHIP-002', 'ShippingMethod', 'EXPRESS', '速達配送（翌営業日）', '速達配送', '速達配送（翌営業日）', 2, '{"delivery_days": 1, "fee": 1000, "fee_free_threshold": 30000}', 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('SHIP-003', 'ShippingMethod', 'PICKUP', '店舗受取', '店舗受取', '店舗での受取（送料無料）', 3, '{"delivery_days": 0, "fee": 0, "fee_free_threshold": 0}', 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('SHIP-004', 'ShippingMethod', 'REFRIGERATED', '冷蔵配送（3営業日）', '冷蔵配送', '冷蔵商品の配送', 4, '{"delivery_days": 3, "fee": 800, "fee_free_threshold": 15000, "temperature": "refrigerated"}', 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('SHIP-005', 'ShippingMethod', 'FROZEN', '冷凍配送（3営業日）', '冷凍配送', '冷凍商品の配送', 5, '{"delivery_days": 3, "fee": 1000, "fee_free_threshold": 15000, "temperature": "frozen"}', 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP);

-- 単位マスタ
INSERT INTO code_masters (
  code_master_id, code_type, code_value, display_name, display_name_short,
  description, display_order, additional_data, status, valid_from,
  created_by, created_at
) VALUES
('UNIT-001', 'UnitOfMeasure', 'PCS', '個', '個', '個数単位', 1, '{"type": "count"}', 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('UNIT-002', 'UnitOfMeasure', 'KG', 'キログラム', 'kg', '重量単位', 2, '{"type": "weight", "base_unit": "g", "conversion": 1000}', 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('UNIT-003', 'UnitOfMeasure', 'L', 'リットル', 'L', '容量単位', 3, '{"type": "volume", "base_unit": "ml", "conversion": 1000}', 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('UNIT-004', 'UnitOfMeasure', 'BOX', '箱', '箱', '箱単位', 4, '{"type": "package"}', 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP),
('UNIT-005', 'UnitOfMeasure', 'CASE', 'ケース', 'ケース', 'ケース単位', 5, '{"type": "package"}', 'Active', '2020-01-01', 'system', CURRENT_TIMESTAMP);
```

## 3.2 テストデータ

### 3.2.1 価格改定のシナリオ

2024年4月1日の価格改定シナリオをテストデータとして用意します。

```sql
-- 2024年4月1日の価格改定対象商品
-- 原材料費高騰により、米・穀物類と調味料類を平均8%値上げ

-- 値上げ前後の価格比較
SELECT
  p.product_code,
  p.product_name,
  p.category_name,
  old_price.unit_price AS old_price,
  new_price.unit_price AS new_price,
  ROUND((new_price.unit_price - old_price.unit_price) / old_price.unit_price * 100, 1) AS increase_rate
FROM products p
INNER JOIN product_prices old_price
  ON p.product_id = old_price.product_id
  AND old_price.price_type = 'Standard'
  AND old_price.valid_to = '2024-03-31'
INNER JOIN product_prices new_price
  ON p.product_id = new_price.product_id
  AND new_price.price_type = 'Standard'
  AND new_price.valid_from = '2024-04-01'
WHERE p.category_code IN ('RICE', 'BEANS', 'MISO', 'SOY_SAUCE')
ORDER BY p.product_code;

-- 期待結果:
-- P-001: 3,500円 → 3,800円（8.6%値上げ）
-- P-021: 1,300円 → 1,400円（7.7%値上げ）
```

### 3.2.2 特別価格の適用シナリオ

大口顧客向けの特別価格適用シナリオをテストします。

```sql
-- 顧客CUST-001の特別価格照会（2024年1月以降）
SELECT
  p.product_code,
  p.product_name,
  standard.unit_price AS standard_price,
  special.unit_price AS special_price,
  standard.unit_price - special.unit_price AS discount_amount,
  ROUND((standard.unit_price - special.unit_price) / standard.unit_price * 100, 1) AS discount_rate
FROM products p
INNER JOIN product_prices standard
  ON p.product_id = standard.product_id
  AND standard.price_type = 'Standard'
  AND standard.valid_from <= '2024-01-01'
  AND (standard.valid_to IS NULL OR standard.valid_to >= '2024-01-01')
INNER JOIN product_prices special
  ON p.product_id = special.product_id
  AND special.price_type = 'Special'
  AND special.customer_id = 'CUST-001'
  AND special.valid_from <= '2024-01-01'
  AND (special.valid_to IS NULL OR special.valid_to >= '2024-01-01')
ORDER BY p.product_code;

-- 期待結果:
-- P-001: 標準3,800円 → 特別3,200円（15.8%割引）
-- P-002: 標準3,800円 → 特別3,400円（10.5%割引）
-- P-021: 標準1,400円 → 特別1,200円（14.3%割引）
```

### 3.2.3 勘定科目の階層構造テスト

勘定科目の階層構造を再帰クエリで取得するテストデータです。

```sql
-- 資産の部の階層構造を取得
WITH RECURSIVE account_hierarchy AS (
  -- ルート科目（資産の部）
  SELECT
    account_subject_id,
    account_code,
    account_name,
    level,
    parent_account_id,
    ARRAY[account_subject_id] AS path,
    account_code::TEXT AS path_codes,
    account_name::TEXT AS path_names
  FROM account_subjects
  WHERE account_code = '1000' AND status = 'Active'

  UNION ALL

  -- 子科目
  SELECT
    a.account_subject_id,
    a.account_code,
    a.account_name,
    a.level,
    a.parent_account_id,
    ah.path || a.account_subject_id,
    ah.path_codes || ' > ' || a.account_code,
    ah.path_names || ' > ' || a.account_name
  FROM account_subjects a
  INNER JOIN account_hierarchy ah ON a.parent_account_id = ah.account_subject_id
  WHERE a.status = 'Active'
)
SELECT
  account_code,
  REPEAT('  ', level - 1) || account_name AS account_name_indented,
  level,
  path_codes,
  path_names
FROM account_hierarchy
ORDER BY path_codes;

-- 期待結果:
-- 1000   資産の部           (level 1)
-- 1100     流動資産         (level 2)
-- 1111       現金           (level 3)
-- 1112       普通預金       (level 3)
-- 1120       売掛金         (level 3)
-- ...
```

## 3.3 データ投入スクリプト

Flywayマイグレーションスクリプトとして、マスターデータを投入します。

```sql
-- V011__insert_master_data.sql

-- 商品カテゴリマスタ
\i sql/master_data/product_categories.sql

-- 商品マスタ
\i sql/master_data/products.sql

-- 商品価格履歴
\i sql/master_data/product_prices.sql

-- 勘定科目マスタ
\i sql/master_data/account_subjects.sql

-- 部門マスタ
\i sql/master_data/departments.sql

-- 社員マスタ
\i sql/master_data/employees.sql

-- コードマスタ
\i sql/master_data/code_masters.sql

-- マスターデータの整合性チェック
DO $$
DECLARE
  product_count INT;
  account_count INT;
  department_count INT;
  employee_count INT;
BEGIN
  SELECT COUNT(*) INTO product_count FROM products WHERE status = 'Active';
  SELECT COUNT(*) INTO account_count FROM account_subjects WHERE status = 'Active';
  SELECT COUNT(*) INTO department_count FROM departments;
  SELECT COUNT(*) INTO employee_count FROM employees WHERE termination_date IS NULL;

  RAISE NOTICE '商品マスタ: % 件', product_count;
  RAISE NOTICE '勘定科目マスタ: % 件', account_count;
  RAISE NOTICE '部門マスタ: % 件', department_count;
  RAISE NOTICE '社員マスタ（在籍中）: % 件', employee_count;

  -- 最低限のデータ件数チェック
  IF product_count < 20 THEN
    RAISE EXCEPTION '商品マスタの件数が不足しています';
  END IF;

  IF account_count < 10 THEN
    RAISE EXCEPTION '勘定科目マスタの件数が不足しています';
  END IF;
END $$;
```

## 3.4 まとめ

本章では、共用データ管理サービスで使用するマスターデータを作成しました。

**作成したマスタデータ**:
1. **商品カテゴリマスタ**: 12カテゴリ
2. **商品マスタ**: 代表的な20商品（実際には5,000商品）
3. **商品価格履歴**: 標準価格と特別価格
4. **勘定科目マスタ**: 資産、負債、純資産、収益、費用の階層構造
5. **部門マスタ**: 20部門（3階層）
6. **社員マスタ**: 代表的な20名（実際には1,000名）
7. **コードマスタ**: 税率、支払条件、配送方法、単位

**テストシナリオ**:
- 価格改定シナリオ（2024年4月1日の値上げ）
- 特別価格適用シナリオ（大口顧客向け割引）
- 勘定科目階層構造の取得

次章では、これらのマスターデータを管理するドメインモデルを設計します。
