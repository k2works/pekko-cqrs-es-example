# 第6部 第2章 Read Modelスキーマの設計

本章では、会計システムのRead Model（PostgreSQL）スキーマとDynamoDBイベントストアの設計を行います。

## 2.1 Read Model（PostgreSQL）のスキーマ設計

会計システムのRead Modelは、以下の主要テーブルで構成されます。

### 2.1.1 勘定科目マスタ（chart_of_accounts）

勘定科目体系を管理するマスタテーブルです。

```sql
-- 勘定科目マスタ
CREATE TABLE chart_of_accounts (
  -- 主キー
  account_code VARCHAR(10) PRIMARY KEY,

  -- 基本情報
  account_name VARCHAR(100) NOT NULL,
  account_name_kana VARCHAR(100),

  -- 勘定科目分類
  account_category VARCHAR(20) NOT NULL,  -- 資産、負債、純資産、収益、費用
  account_subcategory VARCHAR(50),        -- 流動資産、固定資産、流動負債など
  balance_side VARCHAR(10) NOT NULL,      -- 借方（Debit）、貸方（Credit）

  -- 階層構造
  level INT NOT NULL,                     -- 階層レベル（1:大分類、2:中分類、3:小分類）
  parent_account_code VARCHAR(10),        -- 上位勘定科目コード

  -- 出力順序
  display_order INT NOT NULL,

  -- 財務諸表表示設定
  show_in_pl BOOLEAN DEFAULT FALSE,       -- 損益計算書に表示
  show_in_bs BOOLEAN DEFAULT FALSE,       -- 貸借対照表に表示
  show_in_cf BOOLEAN DEFAULT FALSE,       -- キャッシュフロー計算書に表示

  -- 補助科目設定
  use_auxiliary_account BOOLEAN DEFAULT FALSE,  -- 補助科目使用可否
  auxiliary_type VARCHAR(20),             -- 補助科目タイプ（取引先、部門、プロジェクト）

  -- 税区分
  default_tax_category VARCHAR(20),       -- デフォルト税区分

  -- ステータス
  is_active BOOLEAN DEFAULT TRUE,

  -- 監査情報
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by VARCHAR(50),
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by VARCHAR(50),

  -- 外部キー制約
  FOREIGN KEY (parent_account_code) REFERENCES chart_of_accounts(account_code)
);

-- インデックス
CREATE INDEX idx_coa_category ON chart_of_accounts(account_category);
CREATE INDEX idx_coa_parent ON chart_of_accounts(parent_account_code);
CREATE INDEX idx_coa_display_order ON chart_of_accounts(display_order);
CREATE INDEX idx_coa_active ON chart_of_accounts(is_active) WHERE is_active = TRUE;

-- コメント
COMMENT ON TABLE chart_of_accounts IS '勘定科目マスタ';
COMMENT ON COLUMN chart_of_accounts.account_code IS '勘定科目コード';
COMMENT ON COLUMN chart_of_accounts.account_name IS '勘定科目名';
COMMENT ON COLUMN chart_of_accounts.account_category IS '勘定区分（資産、負債、純資産、収益、費用）';
COMMENT ON COLUMN chart_of_accounts.balance_side IS '貸借区分（借方、貸方）';
```

**勘定科目体系の例**:

| 科目コード | 科目名 | 区分 | 貸借 | レベル | 親科目 |
|-----------|--------|------|------|--------|--------|
| 1000 | 資産の部 | 資産 | 借方 | 1 | - |
| 1100 | 流動資産 | 資産 | 借方 | 2 | 1000 |
| 1110 | 現金及び預金 | 資産 | 借方 | 3 | 1100 |
| 1111 | 現金 | 資産 | 借方 | 4 | 1110 |
| 1112 | 普通預金 | 資産 | 借方 | 4 | 1110 |
| 1120 | 売掛金 | 資産 | 借方 | 3 | 1100 |
| 1130 | 商品 | 資産 | 借方 | 3 | 1100 |
| 1140 | 仮払消費税 | 資産 | 借方 | 3 | 1100 |
| 2000 | 負債の部 | 負債 | 貸方 | 1 | - |
| 2100 | 流動負債 | 負債 | 貸方 | 2 | 2000 |
| 2110 | 買掛金 | 負債 | 貸方 | 3 | 2100 |
| 2120 | 未払金 | 負債 | 貸方 | 3 | 2100 |
| 2130 | 仮受消費税 | 負債 | 貸方 | 3 | 2100 |
| 3000 | 純資産の部 | 純資産 | 貸方 | 1 | - |
| 3100 | 資本金 | 純資産 | 貸方 | 2 | 3000 |
| 3200 | 利益剰余金 | 純資産 | 貸方 | 2 | 3000 |
| 4000 | 収益 | 収益 | 貸方 | 1 | - |
| 4100 | 売上高 | 収益 | 貸方 | 2 | 4000 |
| 5000 | 費用 | 費用 | 借方 | 1 | - |
| 5100 | 売上原価 | 費用 | 借方 | 2 | 5000 |
| 5200 | 販売費及び一般管理費 | 費用 | 借方 | 2 | 5000 |

### 2.1.2 仕訳見出しテーブル（journal_entries）

仕訳のヘッダー情報を管理するテーブルです。

```sql
-- 仕訳見出しテーブル
CREATE TABLE journal_entries (
  -- 主キー
  journal_entry_id VARCHAR(50) PRIMARY KEY,

  -- 仕訳番号（連番）
  entry_number BIGSERIAL NOT NULL UNIQUE,

  -- 日付情報
  transaction_date DATE NOT NULL,         -- 取引日
  entry_date DATE NOT NULL,               -- 仕訳日

  -- 会計期間
  fiscal_year INT NOT NULL,               -- 会計年度
  fiscal_month INT NOT NULL,              -- 会計月度（1-12）
  fiscal_quarter INT NOT NULL,            -- 四半期（1-4）

  -- 伝票情報
  voucher_type VARCHAR(20) NOT NULL,      -- 伝票種類（売上、仕入、入金、出金、振替）
  voucher_number VARCHAR(50),             -- 伝票番号

  -- 摘要
  description TEXT NOT NULL,

  -- 金額
  total_amount DECIMAL(18, 2) NOT NULL,   -- 合計金額（借方=貸方）

  -- 参照情報
  reference_type VARCHAR(50),             -- 参照元タイプ（Order, PurchaseOrder, Payment）
  reference_id VARCHAR(50),               -- 参照元ID
  source_event_id VARCHAR(100),           -- 元となったイベントID

  -- ステータス
  status VARCHAR(20) NOT NULL,            -- Draft, PendingApproval, Approved, Posted, Cancelled

  -- 承認情報
  approval_required BOOLEAN DEFAULT FALSE,
  approver_id VARCHAR(50),
  approved_at TIMESTAMP,
  approval_comment TEXT,

  -- 転記情報
  posted_by VARCHAR(50),
  posted_at TIMESTAMP,

  -- 取消情報
  is_reversal BOOLEAN DEFAULT FALSE,      -- 取消仕訳かどうか
  original_entry_id VARCHAR(50),          -- 元仕訳ID（取消の場合）
  reversal_entry_id VARCHAR(50),          -- 取消仕訳ID（元仕訳の場合）
  reversal_reason TEXT,

  -- 監査情報
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_by VARCHAR(50) NOT NULL,
  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by VARCHAR(50),

  -- バージョン管理
  version INT NOT NULL DEFAULT 1,

  -- 外部キー制約
  FOREIGN KEY (original_entry_id) REFERENCES journal_entries(journal_entry_id),
  FOREIGN KEY (reversal_entry_id) REFERENCES journal_entries(journal_entry_id)
);

-- インデックス
CREATE INDEX idx_je_transaction_date ON journal_entries(transaction_date);
CREATE INDEX idx_je_entry_date ON journal_entries(entry_date);
CREATE INDEX idx_je_fiscal_year_month ON journal_entries(fiscal_year, fiscal_month);
CREATE INDEX idx_je_voucher_type ON journal_entries(voucher_type);
CREATE INDEX idx_je_status ON journal_entries(status);
CREATE INDEX idx_je_reference ON journal_entries(reference_type, reference_id);
CREATE INDEX idx_je_source_event ON journal_entries(source_event_id);
CREATE INDEX idx_je_posted ON journal_entries(posted_at) WHERE posted_at IS NOT NULL;

-- コメント
COMMENT ON TABLE journal_entries IS '仕訳見出しテーブル';
COMMENT ON COLUMN journal_entries.entry_number IS '仕訳番号（連番）';
COMMENT ON COLUMN journal_entries.transaction_date IS '取引日';
COMMENT ON COLUMN journal_entries.entry_date IS '仕訳日';
COMMENT ON COLUMN journal_entries.voucher_type IS '伝票種類（売上、仕入、入金、出金、振替）';
COMMENT ON COLUMN journal_entries.status IS 'ステータス（Draft, PendingApproval, Approved, Posted, Cancelled）';
```

### 2.1.3 仕訳明細テーブル（journal_entry_lines）

仕訳の明細情報を管理するテーブルです。

```sql
-- 仕訳明細テーブル
CREATE TABLE journal_entry_lines (
  -- 主キー
  journal_entry_line_id VARCHAR(50) PRIMARY KEY,

  -- 外部キー
  journal_entry_id VARCHAR(50) NOT NULL,

  -- 行番号
  line_number INT NOT NULL,

  -- 勘定科目
  account_code VARCHAR(10) NOT NULL,

  -- 貸借区分
  debit_credit VARCHAR(10) NOT NULL,      -- Debit（借方）、Credit（貸方）

  -- 金額
  amount DECIMAL(18, 2) NOT NULL,

  -- 税情報
  tax_category VARCHAR(20),               -- 課税区分（課税10%、軽減8%、非課税、対象外）
  tax_rate DECIMAL(5, 2),                 -- 税率
  tax_amount DECIMAL(18, 2),              -- 消費税額

  -- 補助科目
  auxiliary_account_type VARCHAR(20),     -- 補助科目タイプ（Customer, Supplier, Department, Project）
  auxiliary_account_code VARCHAR(50),     -- 補助科目コード
  auxiliary_account_name VARCHAR(100),    -- 補助科目名

  -- 摘要
  description TEXT,

  -- 監査情報
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

  -- 外部キー制約
  FOREIGN KEY (journal_entry_id) REFERENCES journal_entries(journal_entry_id) ON DELETE CASCADE,
  FOREIGN KEY (account_code) REFERENCES chart_of_accounts(account_code),

  -- ユニーク制約
  UNIQUE (journal_entry_id, line_number)
);

-- インデックス
CREATE INDEX idx_jel_entry_id ON journal_entry_lines(journal_entry_id);
CREATE INDEX idx_jel_account_code ON journal_entry_lines(account_code);
CREATE INDEX idx_jel_debit_credit ON journal_entry_lines(debit_credit);
CREATE INDEX idx_jel_auxiliary ON journal_entry_lines(auxiliary_account_type, auxiliary_account_code);

-- コメント
COMMENT ON TABLE journal_entry_lines IS '仕訳明細テーブル';
COMMENT ON COLUMN journal_entry_lines.line_number IS '行番号';
COMMENT ON COLUMN journal_entry_lines.account_code IS '勘定科目コード';
COMMENT ON COLUMN journal_entry_lines.debit_credit IS '貸借区分（Debit:借方、Credit:貸方）';
COMMENT ON COLUMN journal_entry_lines.amount IS '金額';
```

### 2.1.4 総勘定元帳テーブル（general_ledger）

勘定科目ごとの取引履歴を管理するテーブルです。

```sql
-- 総勘定元帳テーブル
CREATE TABLE general_ledger (
  -- 主キー
  ledger_entry_id VARCHAR(50) PRIMARY KEY,

  -- 勘定科目
  account_code VARCHAR(10) NOT NULL,

  -- 会計期間
  fiscal_year INT NOT NULL,
  fiscal_month INT NOT NULL,

  -- 日付
  entry_date DATE NOT NULL,

  -- 仕訳参照
  journal_entry_id VARCHAR(50) NOT NULL,
  journal_entry_line_id VARCHAR(50) NOT NULL,

  -- 貸借区分
  debit_credit VARCHAR(10) NOT NULL,

  -- 金額
  amount DECIMAL(18, 2) NOT NULL,

  -- 累計残高
  balance DECIMAL(18, 2) NOT NULL,

  -- 補助科目
  auxiliary_account_type VARCHAR(20),
  auxiliary_account_code VARCHAR(50),

  -- 摘要
  description TEXT,

  -- 転記情報
  posted_at TIMESTAMP NOT NULL,
  posted_by VARCHAR(50) NOT NULL,

  -- 外部キー制約
  FOREIGN KEY (account_code) REFERENCES chart_of_accounts(account_code),
  FOREIGN KEY (journal_entry_id) REFERENCES journal_entries(journal_entry_id),
  FOREIGN KEY (journal_entry_line_id) REFERENCES journal_entry_lines(journal_entry_line_id)
);

-- インデックス
CREATE INDEX idx_gl_account_date ON general_ledger(account_code, entry_date);
CREATE INDEX idx_gl_fiscal_period ON general_ledger(fiscal_year, fiscal_month);
CREATE INDEX idx_gl_journal_entry ON general_ledger(journal_entry_id);
CREATE INDEX idx_gl_auxiliary ON general_ledger(account_code, auxiliary_account_type, auxiliary_account_code);
CREATE INDEX idx_gl_posted_at ON general_ledger(posted_at);

-- パーティショニング（年度別）
-- CREATE TABLE general_ledger_2024 PARTITION OF general_ledger FOR VALUES IN (2024);
-- CREATE TABLE general_ledger_2025 PARTITION OF general_ledger FOR VALUES IN (2025);

-- コメント
COMMENT ON TABLE general_ledger IS '総勘定元帳テーブル';
COMMENT ON COLUMN general_ledger.account_code IS '勘定科目コード';
COMMENT ON COLUMN general_ledger.balance IS '累計残高';
```

### 2.1.5 勘定科目別月次サマリテーブル（monthly_summary_by_account）

勘定科目別の月次集計を保存するテーブルです（Materialized View的に使用）。

```sql
-- 勘定科目別月次サマリテーブル
CREATE TABLE monthly_summary_by_account (
  -- 主キー
  summary_id VARCHAR(50) PRIMARY KEY,

  -- 勘定科目
  account_code VARCHAR(10) NOT NULL,

  -- 会計期間
  fiscal_year INT NOT NULL,
  fiscal_month INT NOT NULL,

  -- 期首残高
  opening_balance DECIMAL(18, 2) NOT NULL,

  -- 月次集計
  debit_total DECIMAL(18, 2) NOT NULL,    -- 借方合計
  credit_total DECIMAL(18, 2) NOT NULL,   -- 貸方合計

  -- 期末残高
  closing_balance DECIMAL(18, 2) NOT NULL,

  -- 取引件数
  transaction_count INT NOT NULL,

  -- 更新情報
  last_updated_at TIMESTAMP NOT NULL,

  -- 外部キー制約
  FOREIGN KEY (account_code) REFERENCES chart_of_accounts(account_code),

  -- ユニーク制約
  UNIQUE (account_code, fiscal_year, fiscal_month)
);

-- インデックス
CREATE INDEX idx_ms_account_period ON monthly_summary_by_account(account_code, fiscal_year, fiscal_month);
CREATE INDEX idx_ms_fiscal_period ON monthly_summary_by_account(fiscal_year, fiscal_month);

-- コメント
COMMENT ON TABLE monthly_summary_by_account IS '勘定科目別月次サマリテーブル';
COMMENT ON COLUMN monthly_summary_by_account.opening_balance IS '期首残高';
COMMENT ON COLUMN monthly_summary_by_account.debit_total IS '借方合計';
COMMENT ON COLUMN monthly_summary_by_account.credit_total IS '貸方合計';
COMMENT ON COLUMN monthly_summary_by_account.closing_balance IS '期末残高';
```

### 2.1.6 勘定科目別年次サマリテーブル（yearly_summary_by_account）

勘定科目別の年次集計を保存するテーブルです。

```sql
-- 勘定科目別年次サマリテーブル
CREATE TABLE yearly_summary_by_account (
  -- 主キー
  summary_id VARCHAR(50) PRIMARY KEY,

  -- 勘定科目
  account_code VARCHAR(10) NOT NULL,

  -- 会計年度
  fiscal_year INT NOT NULL,

  -- 期首残高
  opening_balance DECIMAL(18, 2) NOT NULL,

  -- 年次集計
  debit_total DECIMAL(18, 2) NOT NULL,
  credit_total DECIMAL(18, 2) NOT NULL,

  -- 期末残高
  closing_balance DECIMAL(18, 2) NOT NULL,

  -- 取引件数
  transaction_count INT NOT NULL,

  -- 更新情報
  last_updated_at TIMESTAMP NOT NULL,

  -- 外部キー制約
  FOREIGN KEY (account_code) REFERENCES chart_of_accounts(account_code),

  -- ユニーク制約
  UNIQUE (account_code, fiscal_year)
);

-- インデックス
CREATE INDEX idx_ys_account_year ON yearly_summary_by_account(account_code, fiscal_year);
CREATE INDEX idx_ys_fiscal_year ON yearly_summary_by_account(fiscal_year);

-- コメント
COMMENT ON TABLE yearly_summary_by_account IS '勘定科目別年次サマリテーブル';
```

### 2.1.7 試算表テーブル（trial_balance）

月次・年次の試算表を保存するテーブルです。

```sql
-- 試算表テーブル
CREATE TABLE trial_balance (
  -- 主キー
  trial_balance_id VARCHAR(50) PRIMARY KEY,

  -- 会計期間
  fiscal_year INT NOT NULL,
  fiscal_month INT,                       -- NULLの場合は年次試算表

  -- 作成情報
  created_at TIMESTAMP NOT NULL,
  created_by VARCHAR(50) NOT NULL,

  -- ステータス
  status VARCHAR(20) NOT NULL,            -- Draft, Confirmed, Closed

  -- 貸借合計（検証用）
  total_debit DECIMAL(18, 2) NOT NULL,
  total_credit DECIMAL(18, 2) NOT NULL,

  -- ユニーク制約
  UNIQUE (fiscal_year, fiscal_month)
);

-- 試算表明細テーブル
CREATE TABLE trial_balance_lines (
  -- 主キー
  trial_balance_line_id VARCHAR(50) PRIMARY KEY,

  -- 外部キー
  trial_balance_id VARCHAR(50) NOT NULL,

  -- 勘定科目
  account_code VARCHAR(10) NOT NULL,

  -- 残高情報
  opening_balance DECIMAL(18, 2) NOT NULL,
  debit_total DECIMAL(18, 2) NOT NULL,
  credit_total DECIMAL(18, 2) NOT NULL,
  closing_balance DECIMAL(18, 2) NOT NULL,

  -- 外部キー制約
  FOREIGN KEY (trial_balance_id) REFERENCES trial_balance(trial_balance_id) ON DELETE CASCADE,
  FOREIGN KEY (account_code) REFERENCES chart_of_accounts(account_code)
);

-- インデックス
CREATE INDEX idx_tb_period ON trial_balance(fiscal_year, fiscal_month);
CREATE INDEX idx_tbl_trial_balance ON trial_balance_lines(trial_balance_id);

-- コメント
COMMENT ON TABLE trial_balance IS '試算表テーブル';
COMMENT ON TABLE trial_balance_lines IS '試算表明細テーブル';
```

### 2.1.8 財務諸表テーブル（financial_statements）

損益計算書、貸借対照表、キャッシュフロー計算書を保存するテーブルです。

```sql
-- 財務諸表テーブル
CREATE TABLE financial_statements (
  -- 主キー
  statement_id VARCHAR(50) PRIMARY KEY,

  -- 諸表タイプ
  statement_type VARCHAR(20) NOT NULL,    -- IncomeStatement, BalanceSheet, CashFlowStatement

  -- 会計期間
  fiscal_year INT NOT NULL,
  period_type VARCHAR(20) NOT NULL,       -- Monthly, Quarterly, Annual
  period_value INT,                       -- 月(1-12)または四半期(1-4)

  -- 基準日
  as_of_date DATE NOT NULL,

  -- JSON形式で財務諸表データを保存
  statement_data JSONB NOT NULL,

  -- 作成情報
  created_at TIMESTAMP NOT NULL,
  created_by VARCHAR(50) NOT NULL,

  -- ステータス
  status VARCHAR(20) NOT NULL,            -- Draft, Confirmed, Published

  -- ユニーク制約
  UNIQUE (statement_type, fiscal_year, period_type, period_value)
);

-- インデックス
CREATE INDEX idx_fs_type_period ON financial_statements(statement_type, fiscal_year, period_type);
CREATE INDEX idx_fs_as_of_date ON financial_statements(as_of_date);
CREATE INDEX idx_fs_status ON financial_statements(status);

-- GINインデックス（JSONB検索用）
CREATE INDEX idx_fs_statement_data ON financial_statements USING GIN (statement_data);

-- コメント
COMMENT ON TABLE financial_statements IS '財務諸表テーブル';
COMMENT ON COLUMN financial_statements.statement_type IS '諸表タイプ（損益計算書、貸借対照表、キャッシュフロー計算書）';
COMMENT ON COLUMN financial_statements.statement_data IS '財務諸表データ（JSON形式）';
```

### 2.1.9 決算処理テーブル（closing_processes）

月次・年次決算処理の履歴を管理するテーブルです。

```sql
-- 決算処理テーブル
CREATE TABLE closing_processes (
  -- 主キー
  closing_id VARCHAR(50) PRIMARY KEY,

  -- 決算種類
  closing_type VARCHAR(20) NOT NULL,      -- Monthly, Quarterly, Annual

  -- 会計期間
  fiscal_year INT NOT NULL,
  fiscal_month INT,

  -- ステータス
  status VARCHAR(20) NOT NULL,            -- Started, InProgress, Completed, Failed

  -- 処理手順
  steps JSONB NOT NULL,                   -- 決算手順とそれぞれのステータス

  -- 開始・終了時刻
  started_at TIMESTAMP NOT NULL,
  completed_at TIMESTAMP,

  -- 実行者
  started_by VARCHAR(50) NOT NULL,

  -- エラー情報
  error_message TEXT,

  -- ユニーク制約
  UNIQUE (closing_type, fiscal_year, fiscal_month)
);

-- インデックス
CREATE INDEX idx_cp_type_period ON closing_processes(closing_type, fiscal_year, fiscal_month);
CREATE INDEX idx_cp_status ON closing_processes(status);
CREATE INDEX idx_cp_started_at ON closing_processes(started_at);

-- コメント
COMMENT ON TABLE closing_processes IS '決算処理テーブル';
COMMENT ON COLUMN closing_processes.steps IS '決算手順（JSON形式）';
```

## 2.2 DynamoDBのテーブル設計

イベントストアとしてDynamoDBを使用します。各集約のイベントを保存します。

### 2.2.1 JournalEntry Events

仕訳に関連するイベントです。

```scala
// 仕訳作成イベント
final case class JournalEntryCreated(
  journalEntryId: JournalEntryId,
  transactionDate: LocalDate,
  entryDate: LocalDate,
  fiscalYear: Int,
  fiscalMonth: Int,
  voucherType: VoucherType,
  voucherNumber: Option[String],
  description: String,
  lines: List[JournalEntryLineData],
  totalAmount: Money,
  referenceType: Option[String],
  referenceId: Option[String],
  sourceEventId: Option[String],
  createdBy: UserId,
  createdAt: Instant
) extends JournalEntryEvent

// 承認要求イベント
final case class JournalEntryApprovalRequested(
  journalEntryId: JournalEntryId,
  requestedBy: UserId,
  requestedAt: Instant,
  requiredApprover: UserId,
  reason: Option[String]
) extends JournalEntryEvent

// 承認完了イベント
final case class JournalEntryApproved(
  journalEntryId: JournalEntryId,
  approver: UserId,
  approvedAt: Instant,
  comment: Option[String]
) extends JournalEntryEvent

// 承認却下イベント
final case class JournalEntryRejected(
  journalEntryId: JournalEntryId,
  rejectedBy: UserId,
  rejectedAt: Instant,
  reason: String
) extends JournalEntryEvent

// 総勘定元帳へ転記イベント
final case class JournalEntryPosted(
  journalEntryId: JournalEntryId,
  postedBy: UserId,
  postedAt: Instant,
  ledgerEntries: List[LedgerEntryId]
) extends JournalEntryEvent

// 取消イベント
final case class JournalEntryCancelled(
  journalEntryId: JournalEntryId,
  reversalEntryId: JournalEntryId,
  reason: String,
  cancelledBy: UserId,
  cancelledAt: Instant
) extends JournalEntryEvent
```

### 2.2.2 GeneralLedger Events

総勘定元帳に関連するイベントです。

```scala
// 元帳エントリ転記イベント
final case class LedgerEntryPosted(
  ledgerEntryId: LedgerEntryId,
  accountCode: AccountCode,
  fiscalYear: Int,
  fiscalMonth: Int,
  entryDate: LocalDate,
  journalEntryId: JournalEntryId,
  journalEntryLineId: JournalEntryLineId,
  debitCredit: DebitCredit,
  amount: Money,
  balance: Money,
  auxiliaryAccountType: Option[String],
  auxiliaryAccountCode: Option[String],
  description: String,
  postedBy: UserId,
  postedAt: Instant
) extends GeneralLedgerEvent

// 残高更新イベント
final case class LedgerBalanceUpdated(
  accountCode: AccountCode,
  fiscalYear: Int,
  fiscalMonth: Int,
  previousBalance: Money,
  newBalance: Money,
  updatedAt: Instant
) extends GeneralLedgerEvent
```

### 2.2.3 FinancialStatement Events

財務諸表に関連するイベントです。

```scala
// 試算表生成イベント
final case class TrialBalanceGenerated(
  trialBalanceId: TrialBalanceId,
  fiscalYear: Int,
  fiscalMonth: Option[Int],
  balances: List[AccountBalanceData],
  totalDebit: Money,
  totalCredit: Money,
  generatedBy: UserId,
  generatedAt: Instant
) extends FinancialStatementEvent

final case class AccountBalanceData(
  accountCode: AccountCode,
  accountName: String,
  openingBalance: Money,
  debitTotal: Money,
  creditTotal: Money,
  closingBalance: Money
)

// 損益計算書生成イベント
final case class IncomeStatementGenerated(
  statementId: IncomeStatementId,
  fiscalYear: Int,
  periodType: PeriodType,
  periodValue: Option[Int],
  asOfDate: LocalDate,
  revenue: Money,
  costOfGoodsSold: Money,
  grossProfit: Money,
  operatingExpenses: Money,
  operatingIncome: Money,
  nonOperatingIncome: Money,
  nonOperatingExpenses: Money,
  ordinaryIncome: Money,
  extraordinaryIncome: Money,
  extraordinaryLoss: Money,
  incomeBeforeTax: Money,
  corporateTax: Money,
  netIncome: Money,
  generatedBy: UserId,
  generatedAt: Instant
) extends FinancialStatementEvent

// 貸借対照表生成イベント
final case class BalanceSheetGenerated(
  statementId: BalanceSheetId,
  fiscalYear: Int,
  periodType: PeriodType,
  periodValue: Option[Int],
  asOfDate: LocalDate,
  currentAssets: Money,
  fixedAssets: Money,
  totalAssets: Money,
  currentLiabilities: Money,
  fixedLiabilities: Money,
  totalLiabilities: Money,
  equity: Money,
  totalLiabilitiesAndEquity: Money,
  generatedBy: UserId,
  generatedAt: Instant
) extends FinancialStatementEvent

// キャッシュフロー計算書生成イベント
final case class CashFlowStatementGenerated(
  statementId: CashFlowStatementId,
  fiscalYear: Int,
  periodType: PeriodType,
  periodValue: Option[Int],
  period: Period,
  operatingActivities: Money,
  investingActivities: Money,
  financingActivities: Money,
  netCashIncrease: Money,
  cashAtBeginning: Money,
  cashAtEnd: Money,
  generatedBy: UserId,
  generatedAt: Instant
) extends FinancialStatementEvent
```

### 2.2.4 Closing Events

決算処理に関連するイベントです。

```scala
// 月次決算開始イベント
final case class MonthlyClosingStarted(
  closingId: ClosingId,
  fiscalYear: Int,
  fiscalMonth: Int,
  startedBy: UserId,
  startedAt: Instant
) extends ClosingEvent

// 月次決算完了イベント
final case class MonthlyClosingCompleted(
  closingId: ClosingId,
  fiscalYear: Int,
  fiscalMonth: Int,
  trialBalanceId: TrialBalanceId,
  incomeStatementId: IncomeStatementId,
  balanceSheetId: BalanceSheetId,
  completedBy: UserId,
  completedAt: Instant
) extends ClosingEvent

// 年次決算開始イベント
final case class AnnualClosingStarted(
  closingId: ClosingId,
  fiscalYear: Int,
  startedBy: UserId,
  startedAt: Instant
) extends ClosingEvent

// 減価償却計算完了イベント
final case class DepreciationCalculated(
  closingId: ClosingId,
  fiscalYear: Int,
  depreciations: List[DepreciationData],
  totalDepreciation: Money,
  journalEntryId: JournalEntryId,
  calculatedAt: Instant
) extends ClosingEvent

final case class DepreciationData(
  assetId: FixedAssetId,
  assetName: String,
  depreciationAmount: Money,
  accumulatedDepreciation: Money
)

// 棚卸資産評価完了イベント
final case class InventoryValuationCompleted(
  closingId: ClosingId,
  fiscalYear: Int,
  valuationMethod: InventoryValuationMethod,
  totalValuation: Money,
  journalEntryId: JournalEntryId,
  completedAt: Instant
) extends ClosingEvent

// 年次決算完了イベント
final case class AnnualClosingCompleted(
  closingId: ClosingId,
  fiscalYear: Int,
  trialBalanceId: TrialBalanceId,
  incomeStatementId: IncomeStatementId,
  balanceSheetId: BalanceSheetId,
  cashFlowStatementId: CashFlowStatementId,
  netIncome: Money,
  completedBy: UserId,
  completedAt: Instant
) extends ClosingEvent
```

## 2.3 インデックス戦略とパフォーマンス最適化

### 2.3.1 クエリパターンに基づくインデックス設計

会計システムで頻繁に実行されるクエリパターンを分析し、最適なインデックスを設計します。

**主要クエリパターン**:

1. **勘定科目別の残高照会**（最も頻繁）
```sql
-- クエリ
SELECT * FROM general_ledger
WHERE account_code = '1120'
  AND fiscal_year = 2024
  AND fiscal_month = 10
ORDER BY entry_date;

-- インデックス
CREATE INDEX idx_gl_account_date ON general_ledger(account_code, entry_date);
```

2. **期間別の仕訳検索**
```sql
-- クエリ
SELECT * FROM journal_entries
WHERE fiscal_year = 2024
  AND fiscal_month = 10
  AND status = 'Posted';

-- インデックス
CREATE INDEX idx_je_fiscal_year_month ON journal_entries(fiscal_year, fiscal_month);
```

3. **参照元からの仕訳検索**
```sql
-- クエリ
SELECT * FROM journal_entries
WHERE reference_type = 'Order'
  AND reference_id = 'ORD-20241001-001';

-- インデックス
CREATE INDEX idx_je_reference ON journal_entries(reference_type, reference_id);
```

4. **補助科目別の元帳検索**
```sql
-- クエリ
SELECT * FROM general_ledger
WHERE account_code = '1120'
  AND auxiliary_account_type = 'Customer'
  AND auxiliary_account_code = 'CUST-001'
  AND fiscal_year = 2024;

-- インデックス
CREATE INDEX idx_gl_auxiliary ON general_ledger(
  account_code,
  auxiliary_account_type,
  auxiliary_account_code,
  fiscal_year
);
```

### 2.3.2 パーティショニング戦略

大量のトランザクションデータを効率的に管理するため、テーブルパーティショニングを活用します。

```sql
-- 総勘定元帳を年度別にパーティショニング
CREATE TABLE general_ledger (
  ledger_entry_id VARCHAR(50),
  account_code VARCHAR(10) NOT NULL,
  fiscal_year INT NOT NULL,
  -- ... その他のカラム
) PARTITION BY LIST (fiscal_year);

-- 年度別パーティション作成
CREATE TABLE general_ledger_2024 PARTITION OF general_ledger FOR VALUES IN (2024);
CREATE TABLE general_ledger_2025 PARTITION OF general_ledger FOR VALUES IN (2025);
CREATE TABLE general_ledger_2026 PARTITION OF general_ledger FOR VALUES IN (2026);

-- 仕訳テーブルも同様にパーティショニング
CREATE TABLE journal_entries (
  journal_entry_id VARCHAR(50),
  fiscal_year INT NOT NULL,
  -- ... その他のカラム
) PARTITION BY LIST (fiscal_year);

CREATE TABLE journal_entries_2024 PARTITION OF journal_entries FOR VALUES IN (2024);
CREATE TABLE journal_entries_2025 PARTITION OF journal_entries FOR VALUES IN (2025);
```

### 2.3.3 Materialized Viewの活用

頻繁に集計されるデータをMaterialized Viewで事前計算します。

```sql
-- 勘定科目別月次集計のMaterialized View
CREATE MATERIALIZED VIEW mv_monthly_account_summary AS
SELECT
  account_code,
  fiscal_year,
  fiscal_month,
  SUM(CASE WHEN debit_credit = 'Debit' THEN amount ELSE 0 END) as debit_total,
  SUM(CASE WHEN debit_credit = 'Credit' THEN amount ELSE 0 END) as credit_total,
  COUNT(*) as transaction_count
FROM general_ledger
GROUP BY account_code, fiscal_year, fiscal_month;

-- インデックス作成
CREATE INDEX idx_mv_mas_account_period
ON mv_monthly_account_summary(account_code, fiscal_year, fiscal_month);

-- 定期的にリフレッシュ（日次バッチで実行）
REFRESH MATERIALIZED VIEW CONCURRENTLY mv_monthly_account_summary;
```

## まとめ

本章では、会計システムのRead ModelスキーマとDynamoDBイベントストアを設計しました。

**設計したテーブル**:
1. **勘定科目マスタ**: 500科目の勘定科目体系
2. **仕訳見出し・明細**: 年間190万件の仕訳を管理
3. **総勘定元帳**: 勘定科目別の取引履歴
4. **月次・年次サマリ**: 集計データのキャッシュ
5. **試算表**: 月次・年次の貸借一致チェック
6. **財務諸表**: 損益計算書・貸借対照表・キャッシュフロー計算書
7. **決算処理**: 月次・年次決算の履歴管理

**DynamoDBイベント**:
- JournalEntry Events（仕訳ライフサイクル）
- GeneralLedger Events（元帳転記）
- FinancialStatement Events（財務諸表生成）
- Closing Events（決算処理）

**パフォーマンス最適化**:
- クエリパターンに基づくインデックス設計
- 年度別パーティショニング
- Materialized Viewによる事前集計

次章では、勘定科目体系を管理するChartOfAccounts集約を実装し、実際の勘定科目マスタデータを作成します。