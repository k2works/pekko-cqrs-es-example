# pekko-cqrs-es-example

Apache Pekkoを使用したCQRS（Command Query Responsibility Segregation）とEvent Sourcingのサンプル実装です。

## アーキテクチャ概要

このプロジェクトは、CQRS+ESパターンを実装したサンプルアプリケーションです。

- **コマンド側（Command API）**: GraphQL経由でコマンドを受け取り、イベントを生成してDynamoDB Journalに保存
- **イベント処理（Read Model Updater）**: DynamoDBストリームからイベントを取得し、Read Model（PostgreSQL）を更新
- **クエリ側（Query API）**: GraphQL経由でRead Modelからデータを取得

## 技術スタック

- **言語**: Scala 3
- **ビルドツール**: SBT
- **アクターフレームワーク**: Apache Pekko (型付きアクター、永続化、クラスター)
- **イベントストア**: DynamoDB (Pekko Persistence)
- **Read Model**: PostgreSQL + Slick
- **API**: GraphQL (Sangria)
- **シリアライゼーション**: Protocol Buffers (ScalaPB経由)
- **イベント処理**: AWS Lambda (DynamoDBストリーム)

## セットアップ

### 前提条件

- Docker & Docker Compose
- Java 17以降
- SBT 1.8以降

### ローカル環境の起動

```bash
# Docker Composeでインフラを起動
docker-compose -f docker-compose-common.yml up -d

# DynamoDBテーブルを作成
cd tools/dynamodb-setup
make create-tables

# データベースマイグレーション（PostgreSQL）
sbt migrateQuery

# アプリケーションを起動
# 単一ノードモード
./scripts/run-single.sh

# またはクラスターモード
./scripts/run-cluster.sh
```

## エンドツーエンド動作例

### 1. ユーザーアカウントの作成（コマンド側）

GraphQL Mutationを使用してユーザーアカウントを作成します：

```bash
curl -X POST http://localhost:18080/api/graphql \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation CreateUserAccount($input: CreateUserAccountInput!) { createUserAccount(input: $input) { id } }",
    "variables": {
      "input": {
        "firstName": "太郎",
        "lastName": "山田",
        "emailAddress": "yamada@example.com"
      }
    }
  }'
```

レスポンス例：
```json
{
  "data": {
    "createUserAccount": {
      "id": "01HZXXXXXXXXXXXXXX"
    }
  }
}
```

### 2. Read Modelの更新確認

イベントがDynamoDBストリーム経由でRead Model Updaterに処理され、PostgreSQLのRead Modelが更新されます。
数秒待ってからクエリを実行してください。

### 3. ユーザーアカウントの取得（クエリ側）

GraphQL Queryを使用してユーザーアカウントを取得します：

```bash
# 単一ユーザーの取得
curl -X POST http://localhost:18082/api/graphql \
  -H "Content-Type: application/json" \
  -d '{
    "query": "query GetUserAccount($id: String!) { getUserAccount(userAccountId: $id) { id firstName lastName fullName createdAt updatedAt } }",
    "variables": {
      "id": "01HZXXXXXXXXXXXXXX"
    }
  }'

# 全ユーザーの取得
curl -X POST http://localhost:18082/api/graphql \
  -H "Content-Type: application/json" \
  -d '{
    "query": "{ getUserAccounts { id firstName lastName fullName createdAt updatedAt } }"
  }'

# ユーザー検索
curl -X POST http://localhost:18082/api/graphql \
  -H "Content-Type: application/json" \
  -d '{
    "query": "query SearchUsers($term: String!) { searchUserAccounts(searchTerm: $term) { id firstName lastName fullName } }",
    "variables": {
      "term": "山田"
    }
  }'
```

レスポンス例：
```json
{
  "data": {
    "getUserAccount": {
      "id": "01HZXXXXXXXXXXXXXX",
      "firstName": "太郎",
      "lastName": "山田",
      "fullName": "太郎 山田",
      "createdAt": "2024-01-01T12:00:00Z",
      "updatedAt": "2024-01-01T12:00:00Z"
    }
  }
}
```

### 4. GraphQL Playgroundを使用

ブラウザでGraphQL Playgroundを開いて、インタラクティブにクエリを実行できます：

- **コマンドAPI**: http://localhost:18080/api/playground
- **クエリAPI**: http://localhost:18082/api/playground

## プロジェクト構造

```
pekko-cqrs-es-example/
├── apps/
│   ├── command-api/          # コマンド側HTTP/GraphQLサーバー
│   ├── query-api/            # クエリ側GraphQLサーバー
│   └── read-model-updater/   # Lambda関数（イベント→Read Model更新）
├── modules/
│   ├── command/              # コマンド側モジュール
│   │   ├── domain/           # ドメインエンティティ、値オブジェクト、集約
│   │   ├── use-case/         # アプリケーションサービス、コマンドハンドラ
│   │   └── interface-adapter/ # Pekkoアクター、永続化、HTTP/gRPCエンドポイント
│   ├── query/                # クエリ側モジュール
│   │   └── interface-adapter/ # GraphQL API、Slick DAO、プロジェクション
│   └── infrastructure/        # 共有インフラコード
└── tools/
    └── dynamodb-setup/        # DynamoDBテーブル定義
```

## テスト

```bash
# 単体テスト
sbt test

# E2Eテスト
./scripts/test-e2e.sh

# GraphQLテスト
./scripts/test-graphql.sh
```

## ドキュメント

詳細なドキュメントは `CLAUDE.md` を参照してください。

## ライセンス

LICENSEファイルを参照してください。
