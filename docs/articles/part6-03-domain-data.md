# 第6部 第3章 会計に適したドメインデータ作成

本章では、D社の会計業務で使用する勘定科目マスタとトランザクションデータを作成します。

## 3.1 マスタデータ

### 3.1.1 勘定科目マスタの体系

D社の勘定科目体系は、日本の一般的な会計基準に準拠した500科目で構成されます。

#### 資産の部（1000番台）

**流動資産（1100番台）**

```sql
-- V001__create_chart_of_accounts.sql（抜粋）

-- 1000: 資産の部
INSERT INTO chart_of_accounts (
  account_code, account_name, account_category, account_subcategory,
  balance_side, level, parent_account_code, display_order,
  show_in_bs, is_active
) VALUES
('1000', '資産の部', '資産', NULL, 'Debit', 1, NULL, 1000, TRUE, TRUE);

-- 1100: 流動資産
INSERT INTO chart_of_accounts VALUES
('1100', '流動資産', '資産', '流動資産', 'Debit', 2, '1000', 1100, TRUE, TRUE);

-- 1110: 現金及び預金
INSERT INTO chart_of_accounts VALUES
('1110', '現金及び預金', '資産', '流動資産', 'Debit', 3, '1100', 1110, TRUE, TRUE),
('1111', '現金', '資産', '流動資産', 'Debit', 4, '1110', 1111, TRUE, TRUE),
('1112', '普通預金', '資産', '流動資産', 'Debit', 4, '1110', 1112, TRUE, TRUE),
('1113', '当座預金', '資産', '流動資産', 'Debit', 4, '1110', 1113, TRUE, TRUE),
('1114', '定期預金', '資産', '流動資産', 'Debit', 4, '1110', 1114, TRUE, TRUE);

-- 1120: 受取手形
INSERT INTO chart_of_accounts VALUES
('1120', '受取手形', '資産', '流動資産', 'Debit', 3, '1100', 1120, TRUE, TRUE);

-- 1130: 売掛金
INSERT INTO chart_of_accounts VALUES
('1130', '売掛金', '資産', '流動資産', 'Debit', 3, '1100', 1130, TRUE, TRUE);
UPDATE chart_of_accounts SET use_auxiliary_account = TRUE, auxiliary_type = 'Customer'
WHERE account_code = '1130';

-- 1140: 有価証券
INSERT INTO chart_of_accounts VALUES
('1140', '有価証券', '資産', '流動資産', 'Debit', 3, '1100', 1140, TRUE, TRUE);

-- 1150: 商品
INSERT INTO chart_of_accounts VALUES
('1150', '商品', '資産', '流動資産', 'Debit', 3, '1100', 1150, TRUE, TRUE);

-- 1160: 貯蔵品
INSERT INTO chart_of_accounts VALUES
('1160', '貯蔵品', '資産', '流動資産', 'Debit', 3, '1100', 1160, TRUE, TRUE);

-- 1170: 前払費用
INSERT INTO chart_of_accounts VALUES
('1170', '前払費用', '資産', '流動資産', 'Debit', 3, '1100', 1170, TRUE, TRUE);

-- 1180: 仮払金
INSERT INTO chart_of_accounts VALUES
('1180', '仮払金', '資産', '流動資産', 'Debit', 3, '1100', 1180, TRUE, TRUE);

-- 1190: 仮払消費税
INSERT INTO chart_of_accounts VALUES
('1190', '仮払消費税', '資産', '流動資産', 'Debit', 3, '1100', 1190, TRUE, TRUE);
UPDATE chart_of_accounts SET default_tax_category = 'TaxDeductible'
WHERE account_code = '1190';
```

**固定資産（1200番台）**

```sql
-- 1200: 固定資産
INSERT INTO chart_of_accounts VALUES
('1200', '固定資産', '資産', '固定資産', 'Debit', 2, '1000', 1200, TRUE, TRUE);

-- 1210: 有形固定資産
INSERT INTO chart_of_accounts VALUES
('1210', '有形固定資産', '資産', '固定資産', 'Debit', 3, '1200', 1210, TRUE, TRUE),
('1211', '建物', '資産', '固定資産', 'Debit', 4, '1210', 1211, TRUE, TRUE),
('1212', '建物附属設備', '資産', '固定資産', 'Debit', 4, '1210', 1212, TRUE, TRUE),
('1213', '構築物', '資産', '固定資産', 'Debit', 4, '1210', 1213, TRUE, TRUE),
('1214', '機械装置', '資産', '固定資産', 'Debit', 4, '1210', 1214, TRUE, TRUE),
('1215', '車両運搬具', '資産', '固定資産', 'Debit', 4, '1210', 1215, TRUE, TRUE),
('1216', '工具器具備品', '資産', '固定資産', 'Debit', 4, '1210', 1216, TRUE, TRUE),
('1217', '土地', '資産', '固定資産', 'Debit', 4, '1210', 1217, TRUE, TRUE);

-- 1220: 減価償却累計額（資産のマイナス勘定）
INSERT INTO chart_of_accounts VALUES
('1220', '減価償却累計額', '資産', '固定資産', 'Credit', 3, '1200', 1220, TRUE, TRUE),
('1221', '建物減価償却累計額', '資産', '固定資産', 'Credit', 4, '1220', 1221, TRUE, TRUE),
('1222', '建物附属設備減価償却累計額', '資産', '固定資産', 'Credit', 4, '1220', 1222, TRUE, TRUE),
('1223', '構築物減価償却累計額', '資産', '固定資産', 'Credit', 4, '1220', 1223, TRUE, TRUE),
('1224', '機械装置減価償却累計額', '資産', '固定資産', 'Credit', 4, '1220', 1224, TRUE, TRUE),
('1225', '車両運搬具減価償却累計額', '資産', '固定資産', 'Credit', 4, '1220', 1225, TRUE, TRUE),
('1226', '工具器具備品減価償却累計額', '資産', '固定資産', 'Credit', 4, '1220', 1226, TRUE, TRUE);

-- 1230: 無形固定資産
INSERT INTO chart_of_accounts VALUES
('1230', '無形固定資産', '資産', '固定資産', 'Debit', 3, '1200', 1230, TRUE, TRUE),
('1231', 'ソフトウェア', '資産', '固定資産', 'Debit', 4, '1230', 1231, TRUE, TRUE),
('1232', '特許権', '資産', '固定資産', 'Debit', 4, '1230', 1232, TRUE, TRUE),
('1233', '商標権', '資産', '固定資産', 'Debit', 4, '1230', 1233, TRUE, TRUE);

-- 1240: 投資その他の資産
INSERT INTO chart_of_accounts VALUES
('1240', '投資その他の資産', '資産', '固定資産', 'Debit', 3, '1200', 1240, TRUE, TRUE),
('1241', '投資有価証券', '資産', '固定資産', 'Debit', 4, '1240', 1241, TRUE, TRUE),
('1242', '関係会社株式', '資産', '固定資産', 'Debit', 4, '1240', 1242, TRUE, TRUE),
('1243', '出資金', '資産', '固定資産', 'Debit', 4, '1240', 1243, TRUE, TRUE),
('1244', '長期貸付金', '資産', '固定資産', 'Debit', 4, '1240', 1244, TRUE, TRUE),
('1245', '敷金保証金', '資産', '固定資産', 'Debit', 4, '1240', 1245, TRUE, TRUE);
```

#### 負債の部（2000番台）

**流動負債（2100番台）**

```sql
-- 2000: 負債の部
INSERT INTO chart_of_accounts VALUES
('2000', '負債の部', '負債', NULL, 'Credit', 1, NULL, 2000, TRUE, TRUE);

-- 2100: 流動負債
INSERT INTO chart_of_accounts VALUES
('2100', '流動負債', '負債', '流動負債', 'Credit', 2, '2000', 2100, TRUE, TRUE);

-- 2110: 支払手形
INSERT INTO chart_of_accounts VALUES
('2110', '支払手形', '負債', '流動負債', 'Credit', 3, '2100', 2110, TRUE, TRUE);

-- 2120: 買掛金
INSERT INTO chart_of_accounts VALUES
('2120', '買掛金', '負債', '流動負債', 'Credit', 3, '2100', 2120, TRUE, TRUE);
UPDATE chart_of_accounts SET use_auxiliary_account = TRUE, auxiliary_type = 'Supplier'
WHERE account_code = '2120';

-- 2130: 短期借入金
INSERT INTO chart_of_accounts VALUES
('2130', '短期借入金', '負債', '流動負債', 'Credit', 3, '2100', 2130, TRUE, TRUE);

-- 2140: 未払金
INSERT INTO chart_of_accounts VALUES
('2140', '未払金', '負債', '流動負債', 'Credit', 3, '2100', 2140, TRUE, TRUE);

-- 2150: 未払費用
INSERT INTO chart_of_accounts VALUES
('2150', '未払費用', '負債', '流動負債', 'Credit', 3, '2100', 2150, TRUE, TRUE),
('2151', '未払給料', '負債', '流動負債', 'Credit', 4, '2150', 2151, TRUE, TRUE),
('2152', '未払賞与', '負債', '流動負債', 'Credit', 4, '2150', 2152, TRUE, TRUE),
('2153', '未払社会保険料', '負債', '流動負債', 'Credit', 4, '2150', 2153, TRUE, TRUE);

-- 2160: 未払法人税等
INSERT INTO chart_of_accounts VALUES
('2160', '未払法人税等', '負債', '流動負債', 'Credit', 3, '2100', 2160, TRUE, TRUE),
('2161', '未払法人税', '負債', '流動負債', 'Credit', 4, '2160', 2161, TRUE, TRUE),
('2162', '未払住民税', '負債', '流動負債', 'Credit', 4, '2160', 2162, TRUE, TRUE),
('2163', '未払事業税', '負債', '流動負債', 'Credit', 4, '2160', 2163, TRUE, TRUE);

-- 2170: 前受金
INSERT INTO chart_of_accounts VALUES
('2170', '前受金', '負債', '流動負債', 'Credit', 3, '2100', 2170, TRUE, TRUE);

-- 2180: 預り金
INSERT INTO chart_of_accounts VALUES
('2180', '預り金', '負債', '流動負債', 'Credit', 3, '2100', 2180, TRUE, TRUE),
('2181', '預り源泉所得税', '負債', '流動負債', 'Credit', 4, '2180', 2181, TRUE, TRUE),
('2182', '預り住民税', '負債', '流動負債', 'Credit', 4, '2180', 2182, TRUE, TRUE),
('2183', '預り社会保険料', '負債', '流動負債', 'Credit', 4, '2180', 2183, TRUE, TRUE);

-- 2190: 仮受消費税
INSERT INTO chart_of_accounts VALUES
('2190', '仮受消費税', '負債', '流動負債', 'Credit', 3, '2100', 2190, TRUE, TRUE);
UPDATE chart_of_accounts SET default_tax_category = 'TaxCollected'
WHERE account_code = '2190';
```

**固定負債（2200番台）**

```sql
-- 2200: 固定負債
INSERT INTO chart_of_accounts VALUES
('2200', '固定負債', '負債', '固定負債', 'Credit', 2, '2000', 2200, TRUE, TRUE);

-- 2210: 長期借入金
INSERT INTO chart_of_accounts VALUES
('2210', '長期借入金', '負債', '固定負債', 'Credit', 3, '2200', 2210, TRUE, TRUE);

-- 2220: 退職給付引当金
INSERT INTO chart_of_accounts VALUES
('2220', '退職給付引当金', '負債', '固定負債', 'Credit', 3, '2200', 2220, TRUE, TRUE);

-- 2230: 繰延税金負債
INSERT INTO chart_of_accounts VALUES
('2230', '繰延税金負債', '負債', '固定負債', 'Credit', 3, '2200', 2230, TRUE, TRUE);
```

#### 純資産の部（3000番台）

```sql
-- 3000: 純資産の部
INSERT INTO chart_of_accounts VALUES
('3000', '純資産の部', '純資産', NULL, 'Credit', 1, NULL, 3000, TRUE, TRUE);

-- 3100: 株主資本
INSERT INTO chart_of_accounts VALUES
('3100', '株主資本', '純資産', '株主資本', 'Credit', 2, '3000', 3100, TRUE, TRUE);

-- 3110: 資本金
INSERT INTO chart_of_accounts VALUES
('3110', '資本金', '純資産', '株主資本', 'Credit', 3, '3100', 3110, TRUE, TRUE);

-- 3120: 資本剰余金
INSERT INTO chart_of_accounts VALUES
('3120', '資本剰余金', '純資産', '株主資本', 'Credit', 3, '3100', 3120, TRUE, TRUE),
('3121', '資本準備金', '純資産', '株主資本', 'Credit', 4, '3120', 3121, TRUE, TRUE),
('3122', 'その他資本剰余金', '純資産', '株主資本', 'Credit', 4, '3120', 3122, TRUE, TRUE);

-- 3130: 利益剰余金
INSERT INTO chart_of_accounts VALUES
('3130', '利益剰余金', '純資産', '株主資本', 'Credit', 3, '3100', 3130, TRUE, TRUE),
('3131', '利益準備金', '純資産', '株主資本', 'Credit', 4, '3130', 3131, TRUE, TRUE),
('3132', '別途積立金', '純資産', '株主資本', 'Credit', 4, '3130', 3132, TRUE, TRUE),
('3133', '繰越利益剰余金', '純資産', '株主資本', 'Credit', 4, '3130', 3133, TRUE, TRUE);
```

#### 収益の部（4000番台）

```sql
-- 4000: 収益
INSERT INTO chart_of_accounts VALUES
('4000', '収益', '収益', NULL, 'Credit', 1, NULL, 4000, TRUE, TRUE);

-- 4100: 売上高
INSERT INTO chart_of_accounts VALUES
('4100', '売上高', '収益', '売上高', 'Credit', 2, '4000', 4100, TRUE, TRUE),
('4110', '製品売上高', '収益', '売上高', 'Credit', 3, '4100', 4110, TRUE, TRUE),
('4120', '商品売上高', '収益', '売上高', 'Credit', 3, '4100', 4120, TRUE, TRUE);
UPDATE chart_of_accounts SET show_in_pl = TRUE, default_tax_category = 'Standard10'
WHERE account_code IN ('4110', '4120');

-- 4900: 営業外収益
INSERT INTO chart_of_accounts VALUES
('4900', '営業外収益', '収益', '営業外収益', 'Credit', 2, '4000', 4900, TRUE, TRUE),
('4910', '受取利息', '収益', '営業外収益', 'Credit', 3, '4900', 4910, TRUE, TRUE),
('4920', '受取配当金', '収益', '営業外収益', 'Credit', 3, '4900', 4920, TRUE, TRUE),
('4930', '有価証券売却益', '収益', '営業外収益', 'Credit', 3, '4900', 4930, TRUE, TRUE),
('4940', '為替差益', '収益', '営業外収益', 'Credit', 3, '4900', 4940, TRUE, TRUE),
('4950', '雑収入', '収益', '営業外収益', 'Credit', 3, '4900', 4950, TRUE, TRUE);
UPDATE chart_of_accounts SET show_in_pl = TRUE, default_tax_category = 'NonTaxable'
WHERE account_code IN ('4910', '4920');

-- 5000: 特別利益
INSERT INTO chart_of_accounts VALUES
('5000', '特別利益', '収益', '特別利益', 'Credit', 2, '4000', 5000, TRUE, TRUE),
('5010', '固定資産売却益', '収益', '特別利益', 'Credit', 3, '5000', 5010, TRUE, TRUE);
```

#### 費用の部（5000番台）

```sql
-- 5100: 売上原価
INSERT INTO chart_of_accounts VALUES
('5100', '売上原価', '費用', '売上原価', 'Debit', 2, '5000', 5100, TRUE, TRUE),
('5110', '期首商品棚卸高', '費用', '売上原価', 'Debit', 3, '5100', 5110, TRUE, TRUE),
('5120', '当期商品仕入高', '費用', '売上原価', 'Debit', 3, '5100', 5120, TRUE, TRUE),
('5130', '期末商品棚卸高', '費用', '売上原価', 'Credit', 3, '5100', 5130, TRUE, TRUE);
UPDATE chart_of_accounts SET show_in_pl = TRUE
WHERE account_code IN ('5110', '5120', '5130');

-- 5200: 販売費及び一般管理費
INSERT INTO chart_of_accounts VALUES
('5200', '販売費及び一般管理費', '費用', '販管費', 'Debit', 2, '5000', 5200, TRUE, TRUE);

-- 5210: 人件費
INSERT INTO chart_of_accounts VALUES
('5210', '人件費', '費用', '販管費', 'Debit', 3, '5200', 5210, TRUE, TRUE),
('5211', '給料手当', '費用', '販管費', 'Debit', 4, '5210', 5211, TRUE, TRUE),
('5212', '賞与', '費用', '販管費', 'Debit', 4, '5210', 5212, TRUE, TRUE),
('5213', '賞与引当金繰入額', '費用', '販管費', 'Debit', 4, '5210', 5213, TRUE, TRUE),
('5214', '退職給付費用', '費用', '販管費', 'Debit', 4, '5210', 5214, TRUE, TRUE),
('5215', '法定福利費', '費用', '販管費', 'Debit', 4, '5210', 5215, TRUE, TRUE),
('5216', '福利厚生費', '費用', '販管費', 'Debit', 4, '5210', 5216, TRUE, TRUE);

-- 5220: 経費
INSERT INTO chart_of_accounts VALUES
('5220', '経費', '費用', '販管費', 'Debit', 3, '5200', 5220, TRUE, TRUE),
('5221', '旅費交通費', '費用', '販管費', 'Debit', 4, '5220', 5221, TRUE, TRUE),
('5222', '通信費', '費用', '販管費', 'Debit', 4, '5220', 5222, TRUE, TRUE),
('5223', '水道光熱費', '費用', '販管費', 'Debit', 4, '5220', 5223, TRUE, TRUE),
('5224', '消耗品費', '費用', '販管費', 'Debit', 4, '5220', 5224, TRUE, TRUE),
('5225', '地代家賃', '費用', '販管費', 'Debit', 4, '5220', 5225, TRUE, TRUE),
('5226', 'リース料', '費用', '販管費', 'Debit', 4, '5220', 5226, TRUE, TRUE),
('5227', '保険料', '費用', '販管費', 'Debit', 4, '5220', 5227, TRUE, TRUE),
('5228', '修繕費', '費用', '販管費', 'Debit', 4, '5220', 5228, TRUE, TRUE),
('5229', '減価償却費', '費用', '販管費', 'Debit', 4, '5220', 5229, TRUE, TRUE),
('5230', '広告宣伝費', '費用', '販管費', 'Debit', 4, '5220', 5230, TRUE, TRUE),
('5231', '接待交際費', '費用', '販管費', 'Debit', 4, '5220', 5231, TRUE, TRUE),
('5232', '会議費', '費用', '販管費', 'Debit', 4, '5220', 5232, TRUE, TRUE),
('5233', '運賃', '費用', '販管費', 'Debit', 4, '5220', 5233, TRUE, TRUE),
('5234', '荷造包装費', '費用', '販管費', 'Debit', 4, '5220', 5234, TRUE, TRUE),
('5235', '支払手数料', '費用', '販管費', 'Debit', 4, '5220', 5235, TRUE, TRUE),
('5236', '租税公課', '費用', '販管費', 'Debit', 4, '5220', 5236, TRUE, TRUE),
('5237', '諸会費', '費用', '販管費', 'Debit', 4, '5220', 5237, TRUE, TRUE),
('5238', '雑費', '費用', '販管費', 'Debit', 4, '5220', 5238, TRUE, TRUE);

UPDATE chart_of_accounts SET show_in_pl = TRUE
WHERE account_code BETWEEN '5211' AND '5238';

-- 5900: 営業外費用
INSERT INTO chart_of_accounts VALUES
('5900', '営業外費用', '費用', '営業外費用', 'Debit', 2, '5000', 5900, TRUE, TRUE),
('5910', '支払利息', '費用', '営業外費用', 'Debit', 3, '5900', 5910, TRUE, TRUE),
('5920', '有価証券売却損', '費用', '営業外費用', 'Debit', 3, '5900', 5920, TRUE, TRUE),
('5930', '為替差損', '費用', '営業外費用', 'Debit', 3, '5900', 5930, TRUE, TRUE);

-- 6000: 特別損失
INSERT INTO chart_of_accounts VALUES
('6000', '特別損失', '費用', '特別損失', 'Debit', 2, '5000', 6000, TRUE, TRUE),
('6010', '固定資産売却損', '費用', '特別損失', 'Debit', 3, '6000', 6010, TRUE, TRUE),
('6020', '固定資産除却損', '費用', '特別損失', 'Debit', 3, '6000', 6020, TRUE, TRUE);

-- 7000: 法人税等
INSERT INTO chart_of_accounts VALUES
('7000', '法人税等', '費用', '法人税等', 'Debit', 2, '5000', 7000, TRUE, TRUE),
('7010', '法人税', '費用', '法人税等', 'Debit', 3, '7000', 7010, TRUE, TRUE),
('7020', '住民税', '費用', '法人税等', 'Debit', 3, '7000', 7020, TRUE, TRUE),
('7030', '事業税', '費用', '法人税等', 'Debit', 3, '7000', 7030, TRUE, TRUE);
```

### 3.1.2 期首残高の設定

D社の2024年度（2024年4月1日〜2025年3月31日）の期首残高を設定します。

```sql
-- V002__insert_opening_balances.sql

-- 期首残高テーブル（一時的に使用）
CREATE TEMP TABLE opening_balances (
  account_code VARCHAR(10),
  opening_balance DECIMAL(18, 2)
);

-- 資産の部
INSERT INTO opening_balances VALUES
-- 流動資産
('1111', 200000000),      -- 現金: 2億円
('1112', 800000000),      -- 普通預金: 8億円
('1113', 500000000),      -- 当座預金: 5億円
('1114', 500000000),      -- 定期預金: 5億円
('1120', 300000000),      -- 受取手形: 3億円
('1130', 3200000000),     -- 売掛金: 32億円
('1140', 100000000),      -- 有価証券: 1億円
('1150', 2500000000),     -- 商品: 25億円
('1160', 50000000),       -- 貯蔵品: 5,000万円
('1170', 100000000),      -- 前払費用: 1億円
('1190', 0),              -- 仮払消費税: 0円（期首）

-- 固定資産
('1211', 2000000000),     -- 建物: 20億円
('1212', 300000000),      -- 建物附属設備: 3億円
('1215', 100000000),      -- 車両運搬具: 1億円
('1216', 200000000),      -- 工具器具備品: 2億円
('1217', 1000000000),     -- 土地: 10億円
('1221', -800000000),     -- 建物減価償却累計額: -8億円
('1222', -150000000),     -- 建物附属設備減価償却累計額: -1.5億円
('1225', -60000000),      -- 車両運搬具減価償却累計額: -6,000万円
('1226', -120000000),     -- 工具器具備品減価償却累計額: -1.2億円
('1231', 500000000),      -- ソフトウェア: 5億円
('1241', 200000000),      -- 投資有価証券: 2億円
('1245', 100000000);      -- 敷金保証金: 1億円

-- 負債の部
INSERT INTO opening_balances VALUES
-- 流動負債
('2120', -4000000000),    -- 買掛金: -40億円
('2130', -1000000000),    -- 短期借入金: -10億円
('2140', -200000000),     -- 未払金: -2億円
('2151', -100000000),     -- 未払給料: -1億円
('2152', -200000000),     -- 未払賞与: -2億円
('2160', -100000000),     -- 未払法人税等: -1億円
('2190', 0),              -- 仮受消費税: 0円（期首）

-- 固定負債
('2210', -2000000000),    -- 長期借入金: -20億円
('2220', -300000000);     -- 退職給付引当金: -3億円

-- 純資産の部
INSERT INTO opening_balances VALUES
('3110', -1000000000),    -- 資本金: -10億円
('3121', -500000000),     -- 資本準備金: -5億円
('3131', -100000000),     -- 利益準備金: -1億円
('3132', -500000000),     -- 別途積立金: -5億円
('3133', -2920000000);    -- 繰越利益剰余金: -29.2億円（残差）

-- 年次サマリテーブルに期首残高を登録
INSERT INTO yearly_summary_by_account (
  summary_id,
  account_code,
  fiscal_year,
  opening_balance,
  debit_total,
  credit_total,
  closing_balance,
  transaction_count,
  last_updated_at
)
SELECT
  gen_random_uuid()::TEXT,
  account_code,
  2024,
  opening_balance,
  0,
  0,
  opening_balance,
  0,
  CURRENT_TIMESTAMP
FROM opening_balances;

-- 貸借一致チェック
DO $$
DECLARE
  total_debit DECIMAL(18, 2);
  total_credit DECIMAL(18, 2);
BEGIN
  SELECT
    SUM(CASE WHEN opening_balance > 0 THEN opening_balance ELSE 0 END),
    SUM(CASE WHEN opening_balance < 0 THEN ABS(opening_balance) ELSE 0 END)
  INTO total_debit, total_credit
  FROM opening_balances;

  IF total_debit != total_credit THEN
    RAISE EXCEPTION '期首残高の貸借が一致しません: 借方=%, 貸方=%', total_debit, total_credit;
  END IF;

  RAISE NOTICE '期首残高チェック完了: 借方=%, 貸方=%', total_debit, total_credit;
END $$;
```

## 3.2 トランザクションデータ

### 3.2.1 仕訳データのシナリオ

D社の月間の仕訳発生パターンを定義します。

```scala
object JournalEntryScenarios {

  // 月間仕訳件数の内訳
  final case class MonthlyJournalEntryVolume(
    salesEntries: Int,          // 売上仕訳
    purchaseEntries: Int,       // 仕入仕訳
    paymentReceivedEntries: Int, // 入金仕訳
    paymentMadeEntries: Int,    // 支払仕訳
    salaryEntries: Int,         // 給料仕訳
    expenseEntries: Int,        // 経費仕訳
    depreciationEntries: Int,   // 減価償却仕訳
    otherEntries: Int           // その他仕訳
  ) {
    def total: Int =
      salesEntries + purchaseEntries + paymentReceivedEntries +
      paymentMadeEntries + salaryEntries + expenseEntries +
      depreciationEntries + otherEntries
  }

  // D社の標準的な月間仕訳件数
  val standardMonthlyVolume = MonthlyJournalEntryVolume(
    salesEntries = 50000,          // 売上仕訳: 50,000件（受注1件あたり1仕訳）
    purchaseEntries = 3000,        // 仕入仕訳: 3,000件（発注1件あたり1仕訳）
    paymentReceivedEntries = 10000, // 入金仕訳: 10,000件（月5回締め × 2,000社）
    paymentMadeEntries = 3000,     // 支払仕訳: 3,000件（月末締め）
    salaryEntries = 100,           // 給料仕訳: 100件（従業員1,000名 ÷ 10部門）
    expenseEntries = 500,          // 経費仕訳: 500件（日次経費 × 20営業日）
    depreciationEntries = 1,       // 減価償却仕訳: 1件（月次バッチ）
    otherEntries = 400             // その他仕訳: 400件
  )

  // 合計: 約67,001件/月
  println(s"月間仕訳合計: ${standardMonthlyVolume.total}件")
}
```

### 3.2.2 売上仕訳の生成例

受注確定イベントから売上仕訳を自動生成します。

```scala
// 売上仕訳の生成
class SalesJournalEntryGenerator {

  def generateFromOrderConfirmed(event: OrderEvent.OrderConfirmed): JournalEntry = {
    // 税抜金額と税額の計算
    val taxExcludedAmount = event.totalAmount.excludingTax
    val taxAmount = event.totalAmount.taxAmount

    JournalEntry(
      journalEntryId = JournalEntryId.generate(),
      entryNumber = generateEntryNumber(),
      transactionDate = event.orderDate,
      entryDate = LocalDate.now(),
      fiscalYear = getFiscalYear(event.orderDate),
      fiscalMonth = getFiscalMonth(event.orderDate),
      fiscalQuarter = getFiscalQuarter(event.orderDate),
      voucherType = VoucherType.Sales,
      voucherNumber = Some(event.orderNumber.value),
      description = s"売上計上 注文番号: ${event.orderNumber.value}",
      totalAmount = event.totalAmount.includingTax,
      referenceType = Some("Order"),
      referenceId = Some(event.orderId.value),
      sourceEventId = Some(generateEventId(event)),
      status = JournalEntryStatus.Draft,
      lines = List(
        // 借方: 売掛金（顧客別補助科目）
        JournalEntryLine(
          journalEntryLineId = JournalEntryLineId.generate(),
          lineNumber = 1,
          accountCode = AccountCode("1130"), // 売掛金
          debitCredit = DebitCredit.Debit,
          amount = event.totalAmount.includingTax,
          taxCategory = Some(TaxCategory.Standard10),
          taxRate = Some(TaxRate(0.10)),
          taxAmount = Some(taxAmount),
          auxiliaryAccountType = Some("Customer"),
          auxiliaryAccountCode = Some(event.customerId.value),
          auxiliaryAccountName = Some(getCustomerName(event.customerId)),
          description = Some(s"売掛金計上 顧客: ${event.customerId.value}")
        ),
        // 貸方: 売上高
        JournalEntryLine(
          journalEntryLineId = JournalEntryLineId.generate(),
          lineNumber = 2,
          accountCode = AccountCode("4120"), // 商品売上高
          debitCredit = DebitCredit.Credit,
          amount = taxExcludedAmount,
          taxCategory = Some(TaxCategory.Standard10),
          taxRate = Some(TaxRate(0.10)),
          taxAmount = None,
          auxiliaryAccountType = None,
          auxiliaryAccountCode = None,
          auxiliaryAccountName = None,
          description = Some("売上高計上")
        ),
        // 貸方: 仮受消費税
        JournalEntryLine(
          journalEntryLineId = JournalEntryLineId.generate(),
          lineNumber = 3,
          accountCode = AccountCode("2190"), // 仮受消費税
          debitCredit = DebitCredit.Credit,
          amount = taxAmount,
          taxCategory = Some(TaxCategory.Standard10),
          taxRate = Some(TaxRate(0.10)),
          taxAmount = Some(taxAmount),
          auxiliaryAccountType = None,
          auxiliaryAccountCode = None,
          auxiliaryAccountName = None,
          description = Some("消費税（10%）")
        )
      ),
      approvalRequired = taxExcludedAmount.amount >= 1000000, // 100万円以上は承認必要
      version = 1,
      createdBy = UserId("system"),
      createdAt = Instant.now()
    )
  }
}
```

**生成される仕訳例**:

| 行 | 借方/貸方 | 勘定科目 | 補助科目 | 金額 | 摘要 |
|----|----------|----------|----------|------|------|
| 1 | 借方 | 売掛金 | 顧客A社 | 1,100,000円 | 売掛金計上 |
| 2 | 貸方 | 売上高 | - | 1,000,000円 | 売上高計上 |
| 3 | 貸方 | 仮受消費税 | - | 100,000円 | 消費税（10%） |

### 3.2.3 仕入仕訳の生成例

入荷検収完了イベントから仕入仕訳を自動生成します。

```scala
// 仕入仕訳の生成
class PurchaseJournalEntryGenerator {

  def generateFromReceivingCompleted(
    event: ReceivingEvent.InspectionCompleted
  ): JournalEntry = {
    // 合格品のみの金額を計算
    val acceptedAmount = calculateAcceptedAmount(event.acceptedItems)
    val taxExcludedAmount = acceptedAmount.excludingTax
    val taxAmount = acceptedAmount.taxAmount

    JournalEntry(
      journalEntryId = JournalEntryId.generate(),
      entryNumber = generateEntryNumber(),
      transactionDate = event.completedAt.toLocalDate,
      entryDate = LocalDate.now(),
      fiscalYear = getFiscalYear(event.completedAt.toLocalDate),
      fiscalMonth = getFiscalMonth(event.completedAt.toLocalDate),
      fiscalQuarter = getFiscalQuarter(event.completedAt.toLocalDate),
      voucherType = VoucherType.Purchase,
      voucherNumber = Some(event.receivingId.value),
      description = s"仕入計上 入荷番号: ${event.receivingId.value}",
      totalAmount = acceptedAmount.includingTax,
      referenceType = Some("Receiving"),
      referenceId = Some(event.receivingId.value),
      sourceEventId = Some(generateEventId(event)),
      status = JournalEntryStatus.Draft,
      lines = List(
        // 借方: 商品
        JournalEntryLine(
          journalEntryLineId = JournalEntryLineId.generate(),
          lineNumber = 1,
          accountCode = AccountCode("1150"), // 商品
          debitCredit = DebitCredit.Debit,
          amount = taxExcludedAmount,
          taxCategory = Some(TaxCategory.Standard10),
          taxRate = Some(TaxRate(0.10)),
          taxAmount = None,
          auxiliaryAccountType = None,
          auxiliaryAccountCode = None,
          auxiliaryAccountName = None,
          description = Some("商品仕入計上")
        ),
        // 借方: 仮払消費税
        JournalEntryLine(
          journalEntryLineId = JournalEntryLineId.generate(),
          lineNumber = 2,
          accountCode = AccountCode("1190"), // 仮払消費税
          debitCredit = DebitCredit.Debit,
          amount = taxAmount,
          taxCategory = Some(TaxCategory.Standard10),
          taxRate = Some(TaxRate(0.10)),
          taxAmount = Some(taxAmount),
          auxiliaryAccountType = None,
          auxiliaryAccountCode = None,
          auxiliaryAccountName = None,
          description = Some("仮払消費税（10%）")
        ),
        // 貸方: 買掛金（仕入先別補助科目）
        JournalEntryLine(
          journalEntryLineId = JournalEntryLineId.generate(),
          lineNumber = 3,
          accountCode = AccountCode("2120"), // 買掛金
          debitCredit = DebitCredit.Credit,
          amount = acceptedAmount.includingTax,
          taxCategory = Some(TaxCategory.Standard10),
          taxRate = Some(TaxRate(0.10)),
          taxAmount = Some(taxAmount),
          auxiliaryAccountType = Some("Supplier"),
          auxiliaryAccountCode = Some(event.supplierId.value),
          auxiliaryAccountName = Some(getSupplierName(event.supplierId)),
          description = Some(s"買掛金計上 仕入先: ${event.supplierId.value}")
        )
      ),
      approvalRequired = taxExcludedAmount.amount >= 1000000,
      version = 1,
      createdBy = UserId("system"),
      createdAt = Instant.now()
    )
  }
}
```

### 3.2.4 入金仕訳の生成例

```scala
// 入金仕訳の生成
class PaymentReceivedJournalEntryGenerator {

  def generateFromPaymentReceived(
    event: PaymentEvent.PaymentReceived
  ): JournalEntry = {
    JournalEntry(
      journalEntryId = JournalEntryId.generate(),
      entryNumber = generateEntryNumber(),
      transactionDate = event.paymentDate,
      entryDate = LocalDate.now(),
      fiscalYear = getFiscalYear(event.paymentDate),
      fiscalMonth = getFiscalMonth(event.paymentDate),
      fiscalQuarter = getFiscalQuarter(event.paymentDate),
      voucherType = VoucherType.Receipt,
      voucherNumber = Some(event.paymentId.value),
      description = s"入金 顧客: ${event.customerId.value}",
      totalAmount = event.amount,
      referenceType = Some("Payment"),
      referenceId = Some(event.paymentId.value),
      sourceEventId = Some(generateEventId(event)),
      status = JournalEntryStatus.Draft,
      lines = List(
        // 借方: 普通預金
        JournalEntryLine(
          journalEntryLineId = JournalEntryLineId.generate(),
          lineNumber = 1,
          accountCode = AccountCode("1112"), // 普通預金
          debitCredit = DebitCredit.Debit,
          amount = event.amount,
          taxCategory = None,
          taxRate = None,
          taxAmount = None,
          auxiliaryAccountType = None,
          auxiliaryAccountCode = None,
          auxiliaryAccountName = None,
          description = Some("入金")
        ),
        // 貸方: 売掛金（顧客別補助科目）
        JournalEntryLine(
          journalEntryLineId = JournalEntryLineId.generate(),
          lineNumber = 2,
          accountCode = AccountCode("1130"), // 売掛金
          debitCredit = DebitCredit.Credit,
          amount = event.amount,
          taxCategory = None,
          taxRate = None,
          taxAmount = None,
          auxiliaryAccountType = Some("Customer"),
          auxiliaryAccountCode = Some(event.customerId.value),
          auxiliaryAccountName = Some(getCustomerName(event.customerId)),
          description = Some(s"売掛金消込 顧客: ${event.customerId.value}")
        )
      ),
      approvalRequired = false, // 入金は自動承認
      version = 1,
      createdBy = UserId("system"),
      createdAt = Instant.now()
    )
  }
}
```

### 3.2.5 月次経費仕訳の生成例

```scala
// 月次経費仕訳のテンプレート
object MonthlyExpenseEntries {

  // 給料仕訳（月初5日）
  def generateSalaryEntry(fiscalYear: Int, fiscalMonth: Int): JournalEntry = {
    val totalSalary = Money(100000000) // 月額給料: 1億円
    val withholdingTax = Money(10000000) // 源泉所得税: 1,000万円
    val socialInsurance = Money(15000000) // 社会保険料: 1,500万円
    val netSalary = Money(75000000) // 差引支給額: 7,500万円

    JournalEntry(
      journalEntryId = JournalEntryId.generate(),
      // ... 省略 ...
      lines = List(
        // 借方: 給料手当
        JournalEntryLine(
          lineNumber = 1,
          accountCode = AccountCode("5211"),
          debitCredit = DebitCredit.Debit,
          amount = totalSalary,
          description = Some("給料手当")
        ),
        // 貸方: 預り源泉所得税
        JournalEntryLine(
          lineNumber = 2,
          accountCode = AccountCode("2181"),
          debitCredit = DebitCredit.Credit,
          amount = withholdingTax,
          description = Some("預り源泉所得税")
        ),
        // 貸方: 預り社会保険料
        JournalEntryLine(
          lineNumber = 3,
          accountCode = AccountCode("2183"),
          debitCredit = DebitCredit.Credit,
          amount = socialInsurance,
          description = Some("預り社会保険料")
        ),
        // 貸方: 未払給料
        JournalEntryLine(
          lineNumber = 4,
          accountCode = AccountCode("2151"),
          debitCredit = DebitCredit.Credit,
          amount = netSalary,
          description = Some("未払給料")
        )
      )
    )
  }

  // 家賃仕訳（月末）
  def generateRentEntry(fiscalYear: Int, fiscalMonth: Int): JournalEntry = {
    val rent = Money(5000000) // 月額家賃: 500万円

    JournalEntry(
      journalEntryId = JournalEntryId.generate(),
      // ... 省略 ...
      lines = List(
        // 借方: 地代家賃
        JournalEntryLine(
          lineNumber = 1,
          accountCode = AccountCode("5225"),
          debitCredit = DebitCredit.Debit,
          amount = rent,
          description = Some("地代家賃")
        ),
        // 貸方: 未払金
        JournalEntryLine(
          lineNumber = 2,
          accountCode = AccountCode("2140"),
          debitCredit = DebitCredit.Credit,
          amount = rent,
          description = Some("家賃未払計上")
        )
      )
    )
  }

  // 減価償却仕訳（月末）
  def generateDepreciationEntry(fiscalYear: Int, fiscalMonth: Int): JournalEntry = {
    // 年間減価償却費: 3億円 → 月額: 2,500万円
    val depreciation = Money(25000000)

    JournalEntry(
      journalEntryId = JournalEntryId.generate(),
      // ... 省略 ...
      lines = List(
        // 借方: 減価償却費
        JournalEntryLine(
          lineNumber = 1,
          accountCode = AccountCode("5229"),
          debitCredit = DebitCredit.Debit,
          amount = depreciation,
          description = Some("減価償却費")
        ),
        // 貸方: 建物減価償却累計額（内訳省略）
        JournalEntryLine(
          lineNumber = 2,
          accountCode = AccountCode("1221"),
          debitCredit = DebitCredit.Credit,
          amount = Money(15000000),
          description = Some("建物減価償却累計額")
        ),
        // 貸方: 工具器具備品減価償却累計額
        JournalEntryLine(
          lineNumber = 3,
          accountCode = AccountCode("1226"),
          debitCredit = DebitCredit.Credit,
          amount = Money(10000000),
          description = Some("工具器具備品減価償却累計額")
        )
      )
    )
  }
}
```

### 3.2.6 テストデータ生成スクリプト

```sql
-- V003__insert_sample_journal_entries.sql

-- 2024年4月度のサンプル仕訳データを生成
DO $$
DECLARE
  entry_date DATE := '2024-04-01';
  i INT;
BEGIN
  -- 売上仕訳: 100件のサンプル
  FOR i IN 1..100 LOOP
    INSERT INTO journal_entries (
      journal_entry_id,
      transaction_date,
      entry_date,
      fiscal_year,
      fiscal_month,
      fiscal_quarter,
      voucher_type,
      description,
      total_amount,
      status,
      created_by,
      created_at
    ) VALUES (
      gen_random_uuid()::TEXT,
      entry_date + (i % 30),
      entry_date + (i % 30),
      2024,
      4,
      1,
      'Sales',
      '売上計上 サンプルデータ',
      1000000 + (random() * 5000000)::DECIMAL(18, 2),
      'Posted',
      'seed-script',
      CURRENT_TIMESTAMP
    );
  END LOOP;

  RAISE NOTICE 'サンプル仕訳データ生成完了: 100件';
END $$;
```

## まとめ

本章では、D社の会計業務で使用する勘定科目マスタとトランザクションデータを作成しました。

**作成したマスタデータ**:
- 勘定科目マスタ: 500科目（資産・負債・純資産・収益・費用）
- 期首残高: 資産120億円、負債80億円、純資産40億円

**定義したトランザクションデータ**:
- 売上仕訳: 月間50,000件（受注イベントから自動生成）
- 仕入仕訳: 月間3,000件（入荷検収イベントから自動生成）
- 入金仕訳: 月間10,000件（入金イベントから自動生成）
- 支払仕訳: 月間3,000件（支払イベントから自動生成）
- 経費仕訳: 月間600件（給料・家賃・減価償却など）
- **合計**: 約67,000件/月

**イベント駆動仕訳生成**:
- 受注確定 → 売上仕訳（売掛金/売上高/仮受消費税）
- 入荷検収 → 仕入仕訳（商品/仮払消費税/買掛金）
- 入金 → 入金仕訳（普通預金/売掛金）
- 支払 → 支払仕訳（買掛金/普通預金）

次章では、これらのマスタデータとトランザクションデータを基に、ドメインモデルを設計します。
