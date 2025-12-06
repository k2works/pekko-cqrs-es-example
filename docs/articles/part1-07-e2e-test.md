# 第1部 環境構築編 - 第7章：E2Eテストによる動作確認

## はじめに

本章では、**E2Eテストスクリプト**を使用して、CQRS/Event Sourcingシステム全体の動作を自動的に検証します。このテストにより、Mutation → イベント生成 → イベント処理 → Query という完全なデータフローが正常に機能していることを確認できます。

### E2Eテストの目的

1. **完全なデータフローの検証**: Command側からQuery側まで一貫した動作確認
2. **結果整合性の確認**: イベント駆動アーキテクチャにおける非同期処理の検証
3. **自動化**: CI/CDパイプラインで自動実行可能
4. **リグレッション防止**: コード変更時の影響を早期に検出

---

## 7.1 E2Eテストスクリプトの実行

### 7.1.1 基本的な実行方法

システムが起動している状態で、E2Eテストを実行します。

```bash
# プロジェクトルートで実行
./scripts/test-e2e.sh
```

#### 実行例の出力

```
=== End-to-End Test Suite for UserAccount ===
ℹ Testing flow: GraphQL Mutation → Event Processing → GraphQL Query
ℹ Test ID: 1732705523

=== Health Check ===
ℹ Checking Command API (GraphQL) health...
✓ Command API is healthy
ℹ Checking Query API (GraphQL) health...
✓ Query API is healthy

=== Step 1: Create UserAccount via GraphQL Mutation ===
ℹ Creating user account with the following details:
  - First Name: 太郎1732705523
  - Last Name: テスト
  - Email: test1732705523@example.com

{
  "data": {
    "createUserAccount": {
      "id": "01KAAM3Q5PVKKWW1ZSEH6A68FT"
    }
  }
}
✓ UserAccount created successfully!
ℹ Created UserAccount ID: 01KAAM3Q5PVKKWW1ZSEH6A68FT

=== Step 2: Wait for Event Processing ===
ℹ Waiting for DynamoDB stream to process and update PostgreSQL...
  Waiting... Done!
✓ Event processing time elapsed

=== Step 3: Query UserAccount via GraphQL ===
ℹ Querying all user accounts to find created user...
✓ UserAccount found via GraphQL!
{
  "id": "01KAAM3Q5PVKKWW1ZSEH6A68FT",
  "firstName": "太郎1732705523",
  "lastName": "テスト",
  "fullName": "テスト 太郎1732705523",
  "createdAt": "2025-11-27T10:25:23.456Z",
  "updatedAt": "2025-11-27T10:25:23.456Z"
}
✓ User data matches: 太郎1732705523 テスト
ℹ Verifying user can be queried by ID: 01KAAM3Q5PVKKWW1ZSEH6A68FT
✓ UserAccount successfully queried by ID

=== Step 4: Data Consistency Verification ===
ℹ Verifying total user account count...
✓ Total user account count: 5

=== Test Summary ===
✓ End-to-End test completed successfully!
ℹ UserAccount (太郎1732705523 テスト) was created via GraphQL and retrieved successfully
```

---

### 7.1.2 テストの前提条件

E2Eテストを実行する前に、以下の条件を満たしていることを確認してください：

#### 1. システムが起動している

```bash
# システムの起動（まだの場合）
./scripts/run-single.sh up

# サービスの状態確認
docker ps
```

#### 2. 全サービスが正常に動作している

```bash
# ヘルスチェック
curl http://localhost:50501/api/health  # Command API
curl http://localhost:50502/api/health  # Query API
```

#### 3. Lambda関数がデプロイされている

```bash
# Lambda関数の確認
awslocal lambda list-functions

# イベントソースマッピングの確認
awslocal lambda list-event-source-mappings
```

---

## 7.2 テストフローの理解

E2Eテストスクリプトは、以下の5つのフェーズで構成されています。

### 7.2.1 フェーズ0: ヘルスチェック

#### 目的
Command APIとQuery APIが正常に稼働していることを確認します。

#### 処理内容

```bash
# Command API ヘルスチェック
curl -s http://localhost:50501/api/graphql

# Query API ヘルスチェック
curl -s http://localhost:50502/api/graphql
```

#### 期待される結果
- HTTPステータスコード: 200 または 400（GraphQLエンドポイントが応答）
- サービスが起動していない場合はテストを中断

---

### 7.2.2 フェーズ1: ユーザー作成（GraphQL Mutation）

#### 目的
Command APIを通じてユーザーアカウントを作成し、イベントを生成します。

#### 処理内容

**GraphQL Mutation**:

```graphql
mutation CreateUserAccount($input: CreateUserAccountInput!) {
  createUserAccount(input: $input) {
    id
  }
}
```

**Variables**:

```json
{
  "input": {
    "firstName": "太郎1732705523",
    "lastName": "テスト",
    "emailAddress": "test1732705523@example.com"
  }
}
```

**タイムスタンプの使用**:

テストデータには現在のUnixタイムスタンプを付与することで、テストごとに一意のデータを作成します：

```bash
TIMESTAMP=$(date +%s)
TEST_FIRST_NAME="太郎${TIMESTAMP}"
TEST_EMAIL="test${TIMESTAMP}@example.com"
```

#### 期待される結果

```json
{
  "data": {
    "createUserAccount": {
      "id": "01KAAM3Q5PVKKWW1ZSEH6A68FT"
    }
  }
}
```

#### 内部で発生する処理

```
1. Command APIがMutationを受け取る
2. UserAccountAggregateアクターがコマンドを処理
3. UserAccountEvent.Created_V1イベントを生成
4. DynamoDBのJournalテーブルに永続化
5. DynamoDB Streamsがイベントを配信
```

---

### 7.2.3 フェーズ2: イベント処理待機

#### 目的
DynamoDB StreamsからLambda関数へのイベント配信と、PostgreSQLへの書き込みが完了するまで待機します。

#### 処理内容

```bash
# デフォルト8秒待機（環境変数で調整可能）
E2E_WAIT_AFTER_CREATE=8

# カウントダウン表示
for i in $(seq $wait_time -1 1); do
    echo -ne "\r  Waiting... $i seconds remaining"
    sleep 1
done
```

#### なぜ待機が必要か

CQRS/Event Sourcingでは、**結果整合性**（Eventual Consistency）を採用しています：

```
Command実行 → イベント永続化 → Stream配信 → Lambda処理 → Read Model更新
└─────────────────────────────────────────┘
              非同期処理（数秒）
```

待機時間が短すぎると、Read Modelがまだ更新されておらず、Queryが失敗する可能性があります。

#### 待機時間の調整

環境やネットワーク状況に応じて調整できます：

```bash
# 待機時間を15秒に延長
E2E_WAIT_AFTER_CREATE=15 ./scripts/test-e2e.sh

# 高速な環境では短縮も可能
E2E_WAIT_AFTER_CREATE=5 ./scripts/test-e2e.sh
```

---

### 7.2.4 フェーズ3: データ取得（GraphQL Query）

#### 目的
Query APIを通じて、作成したユーザーアカウントが正しく取得できることを確認します。

#### 処理内容

**ステップ3.1: 全ユーザーの取得**

```graphql
{
  getUserAccounts {
    id
    firstName
    lastName
    fullName
    createdAt
    updatedAt
  }
}
```

レスポンスから、テストで作成したユーザーを検索：

```bash
# jqでフィルタリング
USER_DATA=$(echo "$RESPONSE" | jq ".data.getUserAccounts[] | select(.firstName == \"$TEST_FIRST_NAME\" and .lastName == \"$TEST_LAST_NAME\")")
```

**ステップ3.2: IDによる個別取得**

```graphql
query GetUserAccount($id: String!) {
  getUserAccount(userAccountId: $id) {
    id
    firstName
    lastName
    fullName
    createdAt
    updatedAt
  }
}
```

#### リトライ機能

イベント処理が遅延している場合に備え、リトライ機能が実装されています：

```bash
# デフォルト設定
E2E_MAX_RETRIES=10     # 最大10回リトライ
E2E_RETRY_DELAY=3      # リトライ間隔3秒

# リトライロジック
while [ $RETRY_COUNT -lt $MAX_RETRIES ] && [ "$SUCCESS" = false ]; do
    if query_user_account_via_graphql; then
        SUCCESS=true
    else
        RETRY_COUNT=$((RETRY_COUNT + 1))
        sleep "$E2E_RETRY_DELAY"
    fi
done
```

**最大待機時間**: 初期待機（8秒） + リトライ（10回 × 3秒） = **最大38秒**

---

### 7.2.5 フェーズ4: データ整合性検証

#### 目的
システム全体のデータ整合性を確認します。

#### 処理内容

**全ユーザー数のカウント**:

```graphql
{
  getUserAccounts {
    id
  }
}
```

```bash
TOTAL_COUNT=$(echo "$RESPONSE" | jq '.data.getUserAccounts | length')
print_success "Total user account count: $TOTAL_COUNT"
```

#### 検証項目

1. ✅ 作成したユーザーが取得できる
2. ✅ ユーザーデータが正確（firstName、lastName、emailが一致）
3. ✅ IDによる取得も成功する
4. ✅ 全ユーザー数が正しくカウントされる

---

## 7.3 テストのカスタマイズ（環境変数）

### 7.3.1 リトライとタイムアウトの調整

#### 環境変数一覧

| 環境変数 | デフォルト | 説明 |
|---------|-----------|------|
| `E2E_MAX_RETRIES` | 10 | クエリのリトライ最大回数 |
| `E2E_RETRY_DELAY` | 3 | リトライ間隔（秒） |
| `E2E_WAIT_AFTER_CREATE` | 8 | イベント処理待機時間（秒） |

#### 使用例

**高速な環境（LocalStack高性能マシン）**:

```bash
E2E_MAX_RETRIES=5 \
E2E_RETRY_DELAY=2 \
E2E_WAIT_AFTER_CREATE=5 \
./scripts/test-e2e.sh
```

**低速な環境（CI環境、リモートサーバー）**:

```bash
E2E_MAX_RETRIES=20 \
E2E_RETRY_DELAY=5 \
E2E_WAIT_AFTER_CREATE=15 \
./scripts/test-e2e.sh
```

---

### 7.3.2 APIエンドポイントのカスタマイズ

#### 環境変数一覧

| 環境変数 | デフォルト | 説明 |
|---------|-----------|------|
| `COMMAND_API_HOST` | localhost | Command APIホスト |
| `COMMAND_API_PORT` | 50501 | Command APIポート |
| `QUERY_API_HOST` | localhost | Query APIホスト |
| `QUERY_API_PORT` | 50502 | Query APIポート |

#### 使用例

**リモート環境でのテスト**:

```bash
COMMAND_API_HOST=dev-server.example.com \
COMMAND_API_PORT=8080 \
QUERY_API_HOST=dev-server.example.com \
QUERY_API_PORT=8081 \
./scripts/test-e2e.sh
```

**Docker Composeネットワーク内でのテスト**:

```bash
COMMAND_API_HOST=command-api \
COMMAND_API_PORT=18080 \
QUERY_API_HOST=query-api \
QUERY_API_PORT=18080 \
./scripts/test-e2e.sh
```

---

## 7.4 テスト結果の解釈

### 7.4.1 成功時の出力

```
✓ Command API is healthy
✓ Query API is healthy
✓ UserAccount created successfully!
✓ Event processing time elapsed
✓ UserAccount found via GraphQL!
✓ User data matches: 太郎1732705523 テスト
✓ UserAccount successfully queried by ID
✓ Total user account count: 5
✓ End-to-End test completed successfully!
```

**意味**:

- 全てのフェーズが成功
- CQRS/Event Sourcingのデータフローが正常に機能

---

### 7.4.2 失敗時の出力と対処法

#### ケース1: ヘルスチェック失敗

```
✗ Command API health check failed (HTTP 000)
```

**原因**:

- Command APIが起動していない
- ネットワーク接続の問題

**対処法**:

```bash
# サービスの状態確認
docker ps

# Command APIのログ確認
docker logs command-api

# サービスの再起動
./scripts/run-single.sh down
./scripts/run-single.sh up
```

---

#### ケース2: ユーザー作成失敗

```
✗ Failed to create user account: Invalid email address format
```

**原因**:

- バリデーションエラー
- Command API内部のエラー

**対処法**:

```bash
# Command APIのログを確認
docker logs command-api --tail=100

# GraphQL Playgroundで手動テスト
# http://localhost:50501/api/playground
```

---

#### ケース3: Query失敗（リトライ後）

```
ℹ Retry attempt 1/10... (sleep 3s)
ℹ Retry attempt 2/10... (sleep 3s)
...
✗ UserAccount not found in database
✗ Failed to query user account after 10 retries
ℹ Possible causes:
  - Lambda function not deployed or not running
  - DynamoDB streams not configured
  - Database connection issues
```

**原因**:

- Lambda関数が正常に動作していない
- DynamoDB Streamsの設定問題
- PostgreSQLへの接続エラー

**対処法**:

```bash
# 1. Lambda関数のログ確認
awslocal logs tail /aws/lambda/read-model-updater --follow

# 2. Lambda関数の存在確認
awslocal lambda list-functions

# 3. イベントソースマッピングの確認
awslocal lambda list-event-source-mappings

# 4. PostgreSQLに直接クエリ
psql -h localhost -p 50504 -U postgres -d p-cqrs-es_development
SELECT * FROM user_accounts ORDER BY created_at DESC LIMIT 5;

# 5. DynamoDBのJournalテーブルを確認
awslocal dynamodb scan --table-name Journal --max-items 5
```

---

## 7.5 CI/CDパイプラインでの活用

### 7.5.1 GitHub Actions設定例

`.github/workflows/e2e-test.yml`:

```yaml
name: E2E Test

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main, develop]

jobs:
  e2e-test:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout code
        uses: actions/checkout@v3

      - name: Set up Java
        uses: actions/setup-java@v3
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Set up SBT
        run: |
          echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | sudo tee /etc/apt/sources.list.d/sbt.list
          curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | sudo apt-key add
          sudo apt-get update
          sudo apt-get install sbt

      - name: Build Docker images
        run: sbt dockerBuildAll

      - name: Start services
        run: ./scripts/run-single.sh up

      - name: Run E2E tests
        env:
          E2E_MAX_RETRIES: 20
          E2E_RETRY_DELAY: 5
          E2E_WAIT_AFTER_CREATE: 15
        run: ./scripts/test-e2e.sh

      - name: Show logs on failure
        if: failure()
        run: |
          docker logs command-api
          docker logs query-api
          awslocal logs tail /aws/lambda/read-model-updater --since 10m

      - name: Stop services
        if: always()
        run: ./scripts/run-single.sh down
```

---

### 7.5.2 GitLab CI設定例

`.gitlab-ci.yml`:

```yaml
stages:
  - build
  - test

variables:
  E2E_MAX_RETRIES: "20"
  E2E_RETRY_DELAY: "5"
  E2E_WAIT_AFTER_CREATE: "15"

build:
  stage: build
  image: openjdk:21
  services:
    - docker:dind
  script:
    - apt-get update && apt-get install -y docker-compose
    - sbt dockerBuildAll

e2e-test:
  stage: test
  image: openjdk:21
  services:
    - docker:dind
  script:
    - ./scripts/run-single.sh up
    - ./scripts/test-e2e.sh
  after_script:
    - ./scripts/run-single.sh down
  artifacts:
    when: on_failure
    paths:
      - logs/
```

---

## まとめ

本章では、E2Eテストスクリプトを使用してCQRS/Event Sourcingシステム全体を検証しました。

### 達成したこと

1. ✅ **E2Eテストの実行**: `./scripts/test-e2e.sh`で完全なフローを検証
2. ✅ **テストフローの理解**: 5つのフェーズ（ヘルスチェック、Mutation、待機、Query、検証）
3. ✅ **環境変数によるカスタマイズ**: リトライ、タイムアウト、エンドポイントの調整
4. ✅ **トラブルシューティング**: 失敗時の診断と対処法
5. ✅ **CI/CD統合**: GitHub ActionsやGitLab CIでの自動化

### テストの重要性

E2Eテストにより、以下を保証できます：

- **完全なデータフロー**: Command → Event → Lambda → Read Model → Query
- **結果整合性**: 非同期イベント処理の正常性
- **リグレッション防止**: コード変更時の影響を早期検出
- **自動化**: CI/CDパイプラインでの継続的な検証

---

## 次の章へ

E2Eテストでシステムの正常性を確認しました。次章では、よくある問題とその解決方法を学びます。

👉 [第8章：トラブルシューティング](part1-08-troubleshooting.md)

---

## 参考資料

- [End-to-End Testing Best Practices](https://martinfowler.com/articles/practical-test-pyramid.html)
- [Eventual Consistency](https://www.allthingsdistributed.com/2008/12/eventually_consistent.html)
- [Testing Distributed Systems](https://asatarin.github.io/testing-distributed-systems/)
- [jq Manual](https://stedolan.github.io/jq/manual/)
