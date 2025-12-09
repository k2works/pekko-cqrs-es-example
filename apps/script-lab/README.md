# Excel Script Lab クライアント

Excel Script Lab で動作する管理クライアント集です。GraphQL APIを通じてデータの管理を行います。

## スニペット一覧

| ファイル | 説明 |
|---------|------|
| `user-management.yaml` | ユーザーアカウント管理 |
| `inventory-management.yaml` | 在庫・商品・倉庫・顧客の参照 |
| `master-management.yaml` | マスタデータの一括登録・更新・削除 |

## 前提条件

- Microsoft Excel (デスクトップ版またはWeb版)
- Script Lab アドインがインストールされていること
- バックエンドAPIが起動していること
  - コマンドAPI: `http://localhost:18080` (Docker: ポート50501)
  - クエリAPI: `http://localhost:18082` (Docker: ポート50502)

## セットアップ

### 1. Script Lab アドインのインストール

1. Excelを開く
2. **挿入** > **アドインを取得** をクリック
3. 「Script Lab」を検索してインストール

### 2. スニペットのインポート

1. Script Lab タブを開く
2. **Code** をクリック
3. ハンバーガーメニュー (≡) > **Import** を選択
4. `user-management.yaml` ファイルの内容をペースト
5. **Import** ボタンをクリック

### 3. API URLの設定 (Docker環境の場合)

スクリプト内の `CONFIG` を変更してください：

```typescript
const CONFIG = {
  QUERY_API_URL: "http://localhost:50502/api/graphql",
  COMMAND_API_URL: "http://localhost:50501/api/graphql",
};
```

### 4. 実行

1. Script Lab タブ > **Run** をクリック
2. 作業ウィンドウにUIが表示される

## 機能

### user-management.yaml（ユーザー管理）

| 機能 | 説明 |
|-----|------|
| 全ユーザー取得 | 全ユーザーを「Users」シートに出力 |
| ユーザー検索 | キーワードで検索して「SearchResults」シートに出力 |
| ユーザー作成 | 姓・名・メールで新規ユーザー作成 |
| 一括作成 | 「NewUsers」シートから複数ユーザーを一括登録 |

### inventory-management.yaml（在庫管理）

| 機能 | 説明 |
|-----|------|
| 商品一覧 | 商品マスターを「Products」シートに出力 |
| 在庫一覧 | 在庫データを「Inventories」シートに出力 |
| 倉庫一覧 | 倉庫マスターを「Warehouses」シートに出力 |
| ゾーン一覧 | 倉庫ゾーンを「WarehouseZones」シートに出力 |
| 顧客一覧 | 顧客マスターを「Customers」シートに出力 |
| 履歴取得 | 在庫IDを指定してトランザクション履歴を取得 |
| 全データ取得 | 上記マスターデータを一括取得 |

### master-management.yaml（マスタ管理）

各マスタに対して以下の操作が可能です：

| マスタ | 操作 |
|-------|------|
| 商品 | CREATE（作成）/ UPDATE（更新）/ DELETE（廃止） |
| 倉庫 | CREATE / UPDATE / DEACTIVATE（無効化）/ REACTIVATE（有効化） |
| 倉庫ゾーン | CREATE / UPDATE / DEACTIVATE / REACTIVATE |
| 顧客 | CREATE / UPDATE / DEACTIVATE / REACTIVATE |
| 在庫 | CREATE / RECEIVE（入庫）/ RESERVE（引当）/ RELEASE（解放）/ ISSUE（出庫）/ ADJUST（調整） |

**使用方法：**
1. 「テンプレート」ボタンで入力シートを作成
2. シートにデータを入力（操作列に CREATE/UPDATE 等を指定）
3. 「実行」ボタンで一括処理
4. 「Results」シートに結果が出力される

## トラブルシューティング

### CORSエラーが発生する場合
Excel Online (Web版) で実行している場合、CORSの問題が発生することがあります。
デスクトップ版Excelでの実行を推奨します。

### 接続エラーが発生する場合
```bash
# バックエンドAPIの起動確認
docker-compose ps
```

## ファイル構成

```
apps/script-lab/
├── user-management.yaml       # ユーザー管理スニペット
├── inventory-management.yaml  # 在庫参照スニペット
├── master-management.yaml     # マスタ管理スニペット
└── README.md                  # このファイル
```
