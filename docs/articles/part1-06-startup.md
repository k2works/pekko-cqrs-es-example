# 第1部 環境構築編 - 第6章：初回起動とヘルスチェック

## はじめに

環境構築と設定が完了したので、いよいよシステムを起動します。本章では、**シングルノードモード**でCQRS/Event Sourcingシステム全体を起動し、各サービスが正常に動作していることを確認します。

### 本章で学ぶこと

1. **シングルノードモードの起動**: `./scripts/run-single.sh`による一括起動
2. **各サービスの動作確認**: Command API、Query API、LocalStack、PostgreSQL
3. **GraphQL Playgroundの使い方**: 対話的なAPI操作
4. **ヘルスチェックの実行**: システムの正常性確認

---

## 6.1 シングルノードモードでの起動

### 6.1.1 起動スクリプトの概要

`./scripts/run-single.sh`は、開発環境を一括で起動するスクリプトです。

#### 主な機能

1. **Dockerイメージの準備**: 必要なイメージがない場合はビルド
2. **インフラの起動**: LocalStack、PostgreSQL、DynamoDB
3. **データベースセットアップ**: DynamoDBテーブル作成、Flywayマイグレーション
4. **Lambda関数のデプロイ**: read-model-updater関数のデプロイ
5. **アプリケーションの起動**: Command API、Query API
6. **ヘルスチェック**: 全サービスの正常性確認

---

### 6.1.2 起動コマンドの実行

#### 基本的な起動

```bash
# プロジェクトルートで実行
./scripts/run-single.sh up

# または単に
./scripts/run-single.sh
```

#### 起動時の出力例

```
🐳 Starting Development Environment with Docker...
   (All services run in containers)

🚀 Starting services...
[+] Running 9/9
 ✔ Network pekko-cqrs-es-example_p-cqrs-es-network    Created
 ✔ Container postgres                                  Started
 ✔ Container localstack                                Started
 ✔ Container dynamodb-setup                            Started
 ✔ Container dynamodb-admin                            Started
 ✔ Container command-api                               Started
 ✔ Container query-api                                 Started

⏳ Waiting for services to be ready...

📊 Checking services status...
✅ Command API is ready! (http://localhost:50501/api/health)
✅ Query API is ready! (http://localhost:50502/api/health)
✅ DynamoDB Admin UI is available (http://localhost:50505)

🎉 All services are ready!

📍 Access points:
  - Command GraphQL API: http://localhost:50501/api/graphql
  - Command Health Check: http://localhost:50501/api/health
  - Command GraphQL Playground: http://localhost:50501/api/playground
  - Query GraphQL API: http://localhost:50502/api/graphql
  - Query Health Check: http://localhost:50502/api/health
  - Query GraphQL Playground: http://localhost:50502/api/playground
  - LocalStack: http://localhost:50503
  - PostgreSQL: localhost:50504
  - DynamoDB Admin: http://localhost:50505
```

---

### 6.1.3 起動プロセスの詳細

#### フェーズ1: インフラの起動

```
1. LocalStack起動 → DynamoDBサービス開始
2. PostgreSQL起動 → データベース初期化
3. ヘルスチェック待機（最大30秒）
```

#### フェーズ2: データベースセットアップ

```
1. dynamodb-setupコンテナ起動
   - Journal テーブル作成
   - Snapshot テーブル作成
   - State テーブル作成
   - DynamoDB Streams有効化

2. Flywayマイグレーション実行
   - PostgreSQLスキーマ作成
   - user_accountsテーブル作成
   - インデックス作成
```

#### フェーズ3: Lambda関数のデプロイ

```
1. read-model-updater関数のデプロイ
2. イベントソースマッピングの作成
   - DynamoDB Streams → Lambda接続
```

#### フェーズ4: アプリケーション起動

```
1. Command API起動（Port: 50501）
2. Query API起動（Port: 50502）
3. ヘルスチェック実行（最大120秒）
```

---

### 6.1.4 ログの確認方法

起動中または起動後にログを確認できます。

#### 全サービスのログを表示

```bash
# 全サービスのログを表示
./scripts/run-single.sh logs

# ログをフォロー（リアルタイム表示）
./scripts/run-single.sh logs -f

# 最新100行のみ表示
./scripts/run-single.sh logs --tail=100
```

#### 特定サービスのログを表示

```bash
# Command APIのログ
docker logs command-api

# Query APIのログ
docker logs query-api

# LocalStackのログ
docker logs localstack

# PostgreSQLのログ
docker logs postgres

# Lambda関数のログ（CloudWatch Logs経由）
awslocal logs tail /aws/lambda/read-model-updater --follow
```

---

## 6.2 各サービスの動作確認

### 6.2.1 Command API（http://localhost:50501）

#### ヘルスチェック

```bash
# ヘルスチェックエンドポイント
curl http://localhost:50501/api/health

# 期待されるレスポンス:
# {"status":"healthy"}
```

#### GraphQL Introspection

```bash
# GraphQLスキーマの取得
curl -X POST http://localhost:50501/api/graphql \
  -H "Content-Type: application/json" \
  -d '{"query": "{ __schema { queryType { name } mutationType { name } } }"}'

# 期待されるレスポンス:
# {
#   "data": {
#     "__schema": {
#       "queryType": null,
#       "mutationType": {
#         "name": "Mutation"
#       }
#     }
#   }
# }
```

Command APIは**Mutation専用**なので、`queryType`は`null`です。

#### 簡単なMutationテスト

```bash
curl -X POST http://localhost:50501/api/graphql \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation { createUserAccount(input: { firstName: \"太郎\", lastName: \"山田\", emailAddress: \"yamada@example.com\" }) { id } }"
  }'

# 期待されるレスポンス:
# {
#   "data": {
#     "createUserAccount": {
#       "id": "01KAAM3Q5PVKKWW1ZSEH6A68FT"
#     }
#   }
# }
```

---

### 6.2.2 Query API（http://localhost:50502）

#### ヘルスチェック

```bash
# ヘルスチェックエンドポイント
curl http://localhost:50502/api/health

# 期待されるレスポンス:
# {"status":"healthy"}
```

#### GraphQL Introspection

```bash
# GraphQLスキーマの取得
curl -X POST http://localhost:50502/api/graphql \
  -H "Content-Type: application/json" \
  -d '{"query": "{ __schema { queryType { name } mutationType { name } } }"}'

# 期待されるレスポンス:
# {
#   "data": {
#     "__schema": {
#       "queryType": {
#         "name": "Query"
#       },
#       "mutationType": null
#     }
#   }
# }
```

Query APIは**Query専用**なので、`mutationType`は`null`です。

#### 簡単なQueryテスト

```bash
# 全ユーザーの取得
curl -X POST http://localhost:50502/api/graphql \
  -H "Content-Type: application/json" \
  -d '{
    "query": "{ getUserAccounts { id firstName lastName email } }"
  }'

# 期待されるレスポンス（初回起動直後は空）:
# {
#   "data": {
#     "getUserAccounts": []
#   }
# }
```

---

### 6.2.3 LocalStack（http://localhost:50503）

#### LocalStackヘルスチェック

```bash
# LocalStackの状態確認
curl http://localhost:50503/_localstack/health

# 期待されるレスポンス:
# {
#   "services": {
#     "dynamodb": "available",
#     "lambda": "available",
#     "logs": "available"
#   },
#   "version": "4.7.0"
# }
```

#### DynamoDBテーブルの確認

```bash
# テーブル一覧の取得
awslocal dynamodb list-tables

# 期待されるレスポンス:
# {
#     "TableNames": [
#         "Journal",
#         "Snapshot",
#         "State"
#     ]
# }

# Journalテーブルの詳細
awslocal dynamodb describe-table --table-name Journal

# Journalテーブルの内容確認（初回起動直後は空）
awslocal dynamodb scan --table-name Journal
```

#### Lambda関数の確認

```bash
# Lambda関数の一覧
awslocal lambda list-functions

# 期待されるレスポンス:
# {
#     "Functions": [
#         {
#             "FunctionName": "read-model-updater",
#             "FunctionArn": "arn:aws:lambda:ap-northeast-1:000000000000:function:read-model-updater",
#             "Runtime": "provided.al2",
#             "Handler": "io.github.j5ik2o.pcqrses.readmodel.LambdaHandler",
#             ...
#         }
#     ]
# }

# イベントソースマッピングの確認
awslocal lambda list-event-source-mappings

# 期待されるレスポンス:
# {
#     "EventSourceMappings": [
#         {
#             "UUID": "...",
#             "BatchSize": 10,
#             "EventSourceArn": "arn:aws:dynamodb:ap-northeast-1:000000000000:table/Journal/stream/...",
#             "FunctionArn": "arn:aws:lambda:ap-northeast-1:000000000000:function:read-model-updater",
#             "State": "Enabled"
#         }
#     ]
# }
```

---

### 6.2.4 PostgreSQL（localhost:50504）

#### psqlでの接続

```bash
# PostgreSQLに接続
psql -h localhost -p 50504 -U postgres -d p-cqrs-es_development

# パスワードは不要（POSTGRES_HOST_AUTH_METHOD=trust）
```

#### テーブルの確認

```sql
-- テーブル一覧
\dt

-- 期待される出力:
--              List of relations
--  Schema |       Name        | Type  |  Owner
-- --------+-------------------+-------+----------
--  public | flyway_schema_history | table | postgres
--  public | user_accounts          | table | postgres

-- user_accountsテーブルの構造確認
\d user_accounts

-- 期待される出力:
--                           Table "public.user_accounts"
--    Column    |            Type             | Collation | Nullable | Default
-- -------------+-----------------------------+-----------+----------+---------
--  id          | character varying(255)      |           | not null |
--  first_name  | character varying(255)      |           | not null |
--  last_name   | character varying(255)      |           | not null |
--  email       | character varying(255)      |           | not null |
--  created_at  | timestamp without time zone |           | not null |
--  updated_at  | timestamp without time zone |           | not null |
--  deleted_at  | timestamp without time zone |           |          |
-- Indexes:
--     "user_accounts_pkey" PRIMARY KEY, btree (id)
--     "user_accounts_email_key" UNIQUE CONSTRAINT, btree (email)
--     "idx_user_accounts_active" btree (deleted_at) WHERE deleted_at IS NULL
--     "idx_user_accounts_created_at" btree (created_at)
--     "idx_user_accounts_email" btree (email)
--     "idx_user_accounts_name" btree (first_name, last_name)

-- データの確認（初回起動直後は空）
SELECT * FROM user_accounts;
```

#### Flywayマイグレーション履歴の確認

```sql
-- Flywayマイグレーション履歴
SELECT * FROM flyway_schema_history;

-- 期待される出力:
--  installed_rank | version |          description           |   type   |            script                | checksum | installed_by |     installed_on      | execution_time | success
-- ----------------+---------+--------------------------------+----------+----------------------------------+----------+--------------+-----------------------+----------------+---------
--               1 | 1       | create user accounts table     | SQL      | V1__create_user_accounts_table.sql | ...      | postgres     | 2025-11-27 10:00:00   |             45 | t
```

---

## 6.3 GraphQL Playgroundの使い方

GraphQL Playgroundは、GraphQL APIを対話的にテストできるツールです。

### 6.3.1 Playgroundへのアクセス

#### Command API Playground

ブラウザで以下のURLを開きます：

```
http://localhost:50501/api/playground
```

#### Query API Playground

```
http://localhost:50502/api/playground
```

---

### 6.3.2 スキーマの確認

Playgroundの右側にある「DOCS」タブをクリックすると、利用可能なクエリとミューテーションが表示されます。

#### Command API (Mutation専用)

- `createUserAccount(input: CreateUserAccountInput!): CreateUserAccountResult!`
- `renameUserAccount(input: RenameUserAccountInput!): RenameUserAccountResult!`
- `deleteUserAccount(input: DeleteUserAccountInput!): DeleteUserAccountResult!`

#### Query API (Query専用)

- `getUserAccounts: [UserAccount!]!`
- `getUserAccount(userAccountId: String!): UserAccount`
- `searchUserAccounts(searchTerm: String!): [UserAccount!]!`

---

### 6.3.3 基本的なクエリの実行

#### 1. ユーザーアカウントの作成（Command API）

Playgroundのクエリエディタに以下を入力：

```graphql
mutation CreateUser($input: CreateUserAccountInput!) {
  createUserAccount(input: $input) {
    id
  }
}
```

Variables（左下のパネル）に以下を入力：

```json
{
  "input": {
    "firstName": "太郎",
    "lastName": "山田",
    "emailAddress": "yamada@example.com"
  }
}
```

「▶」ボタンをクリックして実行。

**期待されるレスポンス**:

```json
{
  "data": {
    "createUserAccount": {
      "id": "01KAAM3Q5PVKKWW1ZSEH6A68FT"
    }
  }
}
```

#### 2. ユーザーアカウントの取得（Query API）

**重要**: イベント処理には数秒かかります。Mutationを実行後、5〜10秒待ってからQueryを実行してください。

Query API Playgroundで以下を実行：

```graphql
{
  getUserAccounts {
    id
    firstName
    lastName
    email
    createdAt
    updatedAt
  }
}
```

**期待されるレスポンス**:

```json
{
  "data": {
    "getUserAccounts": [
      {
        "id": "01KAAM3Q5PVKKWW1ZSEH6A68FT",
        "firstName": "太郎",
        "lastName": "山田",
        "email": "yamada@example.com",
        "createdAt": "2025-11-27T10:15:23.123Z",
        "updatedAt": "2025-11-27T10:15:23.123Z"
      }
    ]
  }
}
```

#### 3. 特定ユーザーの取得（Query API）

```graphql
query GetUser($id: String!) {
  getUserAccount(userAccountId: $id) {
    id
    firstName
    lastName
    fullName
    email
  }
}
```

Variables:

```json
{
  "id": "01KAAM3Q5PVKKWW1ZSEH6A68FT"
}
```

#### 4. ユーザー検索（Query API）

```graphql
query SearchUsers($term: String!) {
  searchUserAccounts(searchTerm: $term) {
    id
    firstName
    lastName
    fullName
    email
  }
}
```

Variables:

```json
{
  "term": "太郎"
}
```

---

### 6.3.4 エラーハンドリングの確認

#### バリデーションエラーの例

不正なメールアドレスでユーザー作成を試みます：

```graphql
mutation CreateUser($input: CreateUserAccountInput!) {
  createUserAccount(input: $input) {
    id
  }
}
```

Variables:

```json
{
  "input": {
    "firstName": "次郎",
    "lastName": "田中",
    "emailAddress": "invalid-email"  // 不正なメール形式
  }
}
```

**期待されるレスポンス**:

```json
{
  "errors": [
    {
      "message": "Invalid email address format",
      "path": ["createUserAccount"],
      "extensions": {
        "code": "VALIDATION_ERROR",
        "field": "emailAddress"
      }
    }
  ]
}
```

---

## まとめ

本章では、CQRS/Event Sourcingシステムの初回起動とヘルスチェックを実施しました。

### 達成したこと

1. ✅ **シングルノードモードの起動**: `./scripts/run-single.sh`で全サービスを起動
2. ✅ **各サービスの動作確認**: Command API、Query API、LocalStack、PostgreSQL
3. ✅ **GraphQL Playgroundの使用**: 対話的なAPI操作とスキーマ確認
4. ✅ **ヘルスチェックの実行**: 全サービスが正常に稼働していることを確認

### システムの状態

現時点で、以下のサービスが稼働しています：

- **Command API** (Port: 50501): Mutationを受け付け、イベントをDynamoDBに保存
- **Query API** (Port: 50502): PostgreSQLから読み取りモデルを提供
- **LocalStack** (Port: 50503): DynamoDB、Lambda、CloudWatch Logsをエミュレート
- **PostgreSQL** (Port: 50504): Read Modelのデータストア
- **DynamoDB Admin** (Port: 50505): DynamoDBの内容を視覚的に確認

---

## 次の章へ

システムが正常に起動したことを確認しました。次章では、**E2Eテスト**を実行し、完全なCQRS/Event Sourcingフローを自動的に検証します。

👉 [第7章：E2Eテストによる動作確認](part1-07-e2e-test.md)

---

## トラブルシューティング

### Command APIが起動しない

```bash
# ログを確認
docker logs command-api --tail=100

# よくある原因:
# - LocalStackが起動していない
# - DynamoDBテーブルが作成されていない
# - ポートがすでに使用されている
```

### Query APIでデータが表示されない

```bash
# Lambda関数のログを確認
awslocal logs tail /aws/lambda/read-model-updater --follow

# PostgreSQLに直接接続して確認
psql -h localhost -p 50504 -U postgres -d p-cqrs-es_development
SELECT * FROM user_accounts;

# よくある原因:
# - イベント処理に時間がかかっている（5〜10秒待つ）
# - Lambda関数がデプロイされていない
# - イベントソースマッピングが無効
```

### Playgroundでエラーが表示される

```bash
# ヘルスチェックを確認
curl http://localhost:50501/api/health
curl http://localhost:50502/api/health

# ブラウザのコンソールを確認（F12 → Console）

# よくある原因:
# - サービスが起動していない
# - CORS設定の問題（開発環境では通常問題なし）
```

---

## 参考資料

- [GraphQL Playground Documentation](https://github.com/graphql/graphql-playground)
- [Docker Compose CLI Reference](https://docs.docker.com/compose/reference/)
- [AWS CLI LocalStack Documentation](https://docs.localstack.cloud/user-guide/integrations/aws-cli/)
- [PostgreSQL psql Documentation](https://www.postgresql.org/docs/current/app-psql.html)
