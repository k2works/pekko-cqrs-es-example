# 第1部 環境構築編 - 第8章：トラブルシューティング

## はじめに

開発環境の構築や運用中に遭遇する可能性のある一般的な問題と、その解決方法を体系的に解説します。本章では、**問題の診断方法**と**具体的な解決手順**を提供し、迅速なトラブル解決をサポートします。

### トラブルシューティングの基本方針

1. **ログの確認**: 問題の根本原因を特定する
2. **段階的な診断**: サービスごとに切り分ける
3. **環境のリセット**: 最終手段として環境を再構築する

---

## 8.1 よくある問題と解決方法

### 8.1.1 LocalStackが起動しない

#### 症状

```bash
$ ./scripts/run-single.sh up
...
✗ LocalStack failed to start within 30 seconds
```

または

```bash
$ docker logs localstack
Error: Cannot connect to the Docker daemon at unix:///var/run/docker.sock
```

---

#### 原因1: Dockerデーモンが起動していない

**確認方法**:

```bash
# Dockerデーモンの状態確認
docker info

# エラーが出る場合、Dockerが起動していない
```

**解決方法**:

```bash
# macOS: Docker Desktopを起動
open -a Docker

# Linux: Dockerサービスを起動
sudo systemctl start docker
sudo systemctl enable docker

# 状態確認
docker info
```

---

#### 原因2: ポート4566が既に使用されている

**確認方法**:

```bash
# ポート使用状況の確認
lsof -i :4566

# または
netstat -an | grep 4566
```

**解決方法**:

```bash
# 1. 使用中のプロセスを特定して終了
lsof -i :4566
# PIDを確認後
kill -9 <PID>

# 2. または環境変数でポートを変更
export DOCKER_LOCALSTACK_PORT=4567
./scripts/run-single.sh up
```

---

#### 原因3: Dockerソケットのマウントエラー（Windows WSL2）

**確認方法**:

```bash
docker logs localstack
# "permission denied while trying to connect to the Docker daemon socket" が表示される
```

**解決方法**:

```bash
# WSL2でのDocker設定
# 1. Docker Desktopの設定で「WSL 2 integration」を有効化
# 2. ユーザーをdockerグループに追加
sudo usermod -aG docker $USER

# 3. WSL2を再起動
wsl --shutdown
# WSLを再起動後、再度試行
```

---

#### 原因4: メモリ不足

**確認方法**:

```bash
# Dockerのリソース使用状況
docker stats

# システム全体のメモリ使用量
free -h  # Linux
vm_stat  # macOS
```

**解決方法**:

```bash
# Docker Desktopのリソース設定を増やす
# Settings → Resources → Advanced
# - Memory: 最低4GB、推奨8GB
# - CPUs: 最低2コア、推奨4コア

# 不要なコンテナとイメージを削除
docker system prune -a --volumes
```

---

### 8.1.2 Lambda関数がイベントを処理しない

#### 症状

E2Eテストでユーザー作成後、Query APIでデータが取得できない：

```bash
$ ./scripts/test-e2e.sh
...
✓ UserAccount created successfully!
✗ UserAccount not found in database
✗ Failed to query user account after 10 retries
```

---

#### 原因1: Lambda関数がデプロイされていない

**確認方法**:

```bash
# Lambda関数の一覧を取得
awslocal lambda list-functions

# 期待される出力に "read-model-updater" が存在するか確認
```

**解決方法**:

```bash
# Lambda関数のデプロイスクリプトを再実行
# （起動スクリプトに含まれていますが、手動で実行も可能）

# 1. 環境を停止
./scripts/run-single.sh down

# 2. 再起動（Lambda関数を自動デプロイ）
./scripts/run-single.sh up
```

---

#### 原因2: イベントソースマッピングが無効

**確認方法**:

```bash
# イベントソースマッピングの確認
awslocal lambda list-event-source-mappings

# 出力例:
# {
#     "EventSourceMappings": [
#         {
#             "UUID": "...",
#             "State": "Disabled",  # <- 無効になっている
#             ...
#         }
#     ]
# }
```

**解決方法**:

```bash
# イベントソースマッピングのUUIDを取得
UUID=$(awslocal lambda list-event-source-mappings \
  --query 'EventSourceMappings[0].UUID' \
  --output text)

# イベントソースマッピングを有効化
awslocal lambda update-event-source-mapping \
  --uuid $UUID \
  --enabled
```

---

#### 原因3: Lambda関数内でエラーが発生している

**確認方法**:

```bash
# Lambda関数のログを確認
awslocal logs tail /aws/lambda/read-model-updater --follow

# エラーメッセージの例:
# "ERROR: Connection to PostgreSQL failed"
# "ERROR: Failed to deserialize event"
```

**解決方法**:

**PostgreSQL接続エラーの場合**:

```bash
# PostgreSQLの状態確認
docker logs postgres

# PostgreSQLコンテナが起動しているか確認
docker ps | grep postgres

# 接続テスト
psql -h localhost -p 50504 -U postgres -d p-cqrs-es_development

# Lambda関数の環境変数を確認
awslocal lambda get-function-configuration \
  --function-name read-model-updater \
  --query 'Environment.Variables'
```

**デシリアライゼーションエラーの場合**:

```bash
# イベントの内容を確認
awslocal dynamodb scan --table-name Journal --max-items 1

# Protocol Buffersのバージョン不一致の可能性
# Dockerイメージを再ビルド
sbt clean dockerBuildAll
./scripts/run-single.sh down
./scripts/run-single.sh up
```

---

### 8.1.3 PostgreSQLに接続できない

#### 症状

```bash
$ psql -h localhost -p 50504 -U postgres -d p-cqrs-es_development
psql: error: connection to server at "localhost" (127.0.0.1), port 50504 failed: Connection refused
```

---

#### 原因1: PostgreSQLコンテナが起動していない

**確認方法**:

```bash
# コンテナの状態確認
docker ps | grep postgres

# ログ確認
docker logs postgres
```

**解決方法**:

```bash
# コンテナを再起動
docker restart postgres

# または全体を再起動
./scripts/run-single.sh down
./scripts/run-single.sh up
```

---

#### 原因2: ポート競合

**確認方法**:

```bash
# ポート50504の使用状況
lsof -i :50504
```

**解決方法**:

```bash
# 1. 競合しているプロセスを終了
kill -9 <PID>

# 2. または別のポートを使用
export DOCKER_POSTGRES_PORT=5433
./scripts/run-single.sh up

# 接続時も変更後のポートを指定
psql -h localhost -p 5433 -U postgres -d p-cqrs-es_development
```

---

#### 原因3: データベースが初期化されていない

**確認方法**:

```bash
# PostgreSQLコンテナ内でデータベースを確認
docker exec -it postgres psql -U postgres -c "\l"

# p-cqrs-es_development が存在するか確認
```

**解決方法**:

```bash
# コンテナを完全に削除して再作成
./scripts/run-single.sh down --volumes
./scripts/run-single.sh up
```

---

### 8.1.4 DynamoDBにデータが保存されない

#### 症状

Command APIでMutationは成功するが、DynamoDBに何も保存されていない：

```bash
$ awslocal dynamodb scan --table-name Journal
{
    "Items": [],
    "Count": 0,
    "ScannedCount": 0
}
```

---

#### 原因1: DynamoDBテーブルが作成されていない

**確認方法**:

```bash
# テーブルの一覧を取得
awslocal dynamodb list-tables

# 期待される出力:
# {
#     "TableNames": [
#         "Journal",
#         "Snapshot",
#         "State"
#     ]
# }
```

**解決方法**:

```bash
# DynamoDBセットアップを再実行
docker compose -f docker-compose-common.yml up dynamodb-setup

# または全体を再起動
./scripts/run-single.sh down
./scripts/run-single.sh up
```

---

#### 原因2: Command APIの設定ミス

**確認方法**:

```bash
# Command APIのログを確認
docker logs command-api | grep -i "dynamodb"

# エラーメッセージの例:
# "Cannot do operations on a non-existent table"
# "Unable to connect to DynamoDB"
```

**解決方法**:

```bash
# 設定ファイルのDynamoDBエンドポイントを確認
# apps/command-api/src/main/resources/j5ik2o.conf

# 環境変数で上書き（デバッグ用）
export J5IK2O_DYNAMO_DB_JOURNAL_DYNAMO_DB_CLIENT_ENDPOINT="http://localhost:50503"

# Command APIを再起動
docker restart command-api

# ログを確認
docker logs command-api --follow
```

---

#### 原因3: Pekko Persistenceの初期化エラー

**確認方法**:

```bash
# Command APIのログでPersistence関連のエラーを検索
docker logs command-api | grep -i "persistence"

# エラーメッセージの例:
# "Journal plugin not configured"
# "Failed to create persistence id"
```

**解決方法**:

```bash
# Dockerイメージを再ビルド
sbt clean compile
sbt dockerBuildAll

# 環境を完全にリセット
./scripts/run-single.sh down --volumes
./scripts/run-single.sh up
```

---

### 8.1.5 ポートが既に使用されている

#### 症状

```bash
$ ./scripts/run-single.sh up
...
Error: Bind for 0.0.0.0:50501 failed: port is already allocated
```

---

#### 原因: 別のプロセスまたは古いコンテナがポートを使用

**確認方法**:

```bash
# ポート使用状況の確認
lsof -i :50501  # Command API
lsof -i :50502  # Query API
lsof -i :50503  # LocalStack
lsof -i :50504  # PostgreSQL

# 実行中のコンテナを確認
docker ps -a
```

**解決方法**:

**方法1: プロセスを終了**

```bash
# ポートを使用しているプロセスのPIDを確認
lsof -i :50501

# プロセスを終了
kill -9 <PID>
```

**方法2: 古いコンテナを削除**

```bash
# 全てのコンテナを停止・削除
./scripts/run-single.sh down

# 孤立したコンテナの削除
docker container prune

# 再起動
./scripts/run-single.sh up
```

**方法3: ポート番号を変更**

```bash
# 環境変数でポートを変更
export DOCKER_COMMAND_API_PORT=8501
export DOCKER_QUERY_API_PORT=8502
export DOCKER_LOCALSTACK_PORT=8503
export DOCKER_POSTGRES_PORT=8504

./scripts/run-single.sh up
```

---

## 8.2 デバッグ手法

### 8.2.1 ログの確認方法

#### Dockerコンテナのログ

```bash
# 全サービスのログ
./scripts/run-single.sh logs

# 最新100行のみ
./scripts/run-single.sh logs --tail=100

# リアルタイムでログを表示
./scripts/run-single.sh logs -f

# 特定サービスのみ
docker logs command-api
docker logs query-api
docker logs postgres
docker logs localstack

# タイムスタンプ付きで表示
docker logs --timestamps command-api

# 最近5分間のログ
docker logs --since 5m command-api
```

---

#### Lambda関数のログ（CloudWatch Logs）

```bash
# 最新のログを表示
awslocal logs tail /aws/lambda/read-model-updater

# リアルタイムでフォロー
awslocal logs tail /aws/lambda/read-model-updater --follow

# 最近10分間のログ
awslocal logs tail /aws/lambda/read-model-updater --since 10m

# フィルターパターンでエラーのみ抽出
awslocal logs filter-log-events \
  --log-group-name /aws/lambda/read-model-updater \
  --filter-pattern "ERROR"
```

---

### 8.2.2 DynamoDBの内容確認

#### テーブルの基本情報

```bash
# テーブル一覧
awslocal dynamodb list-tables

# テーブルの詳細（スキーマ、インデックス、Streams設定）
awslocal dynamodb describe-table --table-name Journal

# Stream情報の確認
awslocal dynamodbstreams list-streams
```

---

#### データの確認

```bash
# Journalテーブルの全データをスキャン
awslocal dynamodb scan --table-name Journal

# 最新5件のみ取得
awslocal dynamodb scan --table-name Journal --max-items 5

# 特定のpersistence-idでフィルタ
awslocal dynamodb query \
  --table-name Journal \
  --index-name GetJournalRowsIndex \
  --key-condition-expression "persistence-id = :pid" \
  --expression-attribute-values '{":pid":{"S":"user-account-123"}}'

# jqで整形して表示
awslocal dynamodb scan --table-name Journal | jq '.'
```

---

#### データの削除（デバッグ用）

```bash
# 注意: 全データが削除されます

# テーブルを削除
awslocal dynamodb delete-table --table-name Journal

# テーブルを再作成
docker compose -f docker-compose-common.yml up dynamodb-setup
```

---

### 8.2.3 PostgreSQLの直接クエリ

#### psqlでの接続

```bash
# ローカルから接続
psql -h localhost -p 50504 -U postgres -d p-cqrs-es_development

# Dockerコンテナ内から接続
docker exec -it postgres psql -U postgres -d p-cqrs-es_development
```

---

#### 基本的なSQL操作

```sql
-- テーブル一覧
\dt

-- user_accountsテーブルの構造
\d user_accounts

-- 全ユーザーを取得
SELECT * FROM user_accounts ORDER BY created_at DESC;

-- 特定ユーザーの検索
SELECT * FROM user_accounts WHERE email = 'yamada@example.com';

-- ユーザー数のカウント
SELECT COUNT(*) FROM user_accounts;

-- 削除されていないユーザーのみ
SELECT * FROM user_accounts WHERE deleted_at IS NULL;

-- Flywayマイグレーション履歴
SELECT * FROM flyway_schema_history;
```

---

#### データのクリーンアップ（デバッグ用）

```sql
-- 注意: 全データが削除されます

-- 全ユーザーを削除
DELETE FROM user_accounts;

-- テーブルを削除して再作成（マイグレーション含む）
```

```bash
# PostgreSQLをリセット
./scripts/run-single.sh down --volumes
./scripts/run-single.sh up
```

---

### 8.2.4 Lambda関数のCloudWatch Logs確認

#### ログストリームの確認

```bash
# ロググループの一覧
awslocal logs describe-log-groups

# 特定ロググループのストリーム一覧
awslocal logs describe-log-streams \
  --log-group-name /aws/lambda/read-model-updater

# 最新のログストリーム
awslocal logs describe-log-streams \
  --log-group-name /aws/lambda/read-model-updater \
  --order-by LastEventTime \
  --descending \
  --max-items 1
```

---

#### エラーログの抽出

```bash
# ERRORを含むログのみ抽出
awslocal logs filter-log-events \
  --log-group-name /aws/lambda/read-model-updater \
  --filter-pattern "ERROR"

# 特定の時間範囲でフィルタ（Unix timestamp）
awslocal logs filter-log-events \
  --log-group-name /aws/lambda/read-model-updater \
  --start-time $(date -d '10 minutes ago' +%s)000

# エラーメッセージをjqで整形
awslocal logs filter-log-events \
  --log-group-name /aws/lambda/read-model-updater \
  --filter-pattern "ERROR" \
  | jq -r '.events[].message'
```

---

## 8.3 環境のリセット

### 8.3.1 部分的なリセット

#### コンテナのみ再起動

```bash
# 全コンテナを再起動
docker restart $(docker ps -q)

# 特定コンテナのみ
docker restart command-api
docker restart query-api
```

---

#### データベースのみリセット

```bash
# PostgreSQLのボリュームを削除
docker volume rm pekko-cqrs-es-example_postgres-data

# DynamoDBテーブルを再作成
awslocal dynamodb delete-table --table-name Journal
awslocal dynamodb delete-table --table-name Snapshot
docker compose -f docker-compose-common.yml up dynamodb-setup
```

---

### 8.3.2 完全なリセット

#### 全サービスとデータの削除

```bash
# 全サービス停止とボリューム削除
./scripts/run-single.sh down --volumes

# Dockerイメージも削除（オプション）
docker rmi pekko-cqrs-es-example-command-api:latest
docker rmi pekko-cqrs-es-example-query-api:latest
docker rmi pekko-cqrs-es-example-read-model-updater:latest

# Dockerシステム全体のクリーンアップ
docker system prune -a --volumes
```

---

#### クリーンビルドと再起動

```bash
# SBTプロジェクトのクリーン
sbt clean

# Dockerイメージの再ビルド
sbt dockerBuildAll

# 環境を完全にリセットして起動
./scripts/run-single.sh down --volumes
./scripts/run-single.sh up
```

---

## 8.4 診断チェックリスト

問題が発生した際に、以下のチェックリストを使用して体系的に診断します。

### レベル1: 基本的な確認

- [ ] Dockerデーモンが起動しているか？ (`docker info`)
- [ ] 必要なポートが空いているか？ (`lsof -i :50501`, etc.)
- [ ] ディスク容量が十分にあるか？ (`df -h`)
- [ ] メモリが十分にあるか？ (`free -h` / `vm_stat`)

### レベル2: サービスの状態確認

- [ ] 全コンテナが起動しているか？ (`docker ps`)
- [ ] Command APIのヘルスチェック (`curl http://localhost:50501/api/health`)
- [ ] Query APIのヘルスチェック (`curl http://localhost:50502/api/health`)
- [ ] LocalStackが正常か？ (`curl http://localhost:50503/_localstack/health`)

### レベル3: データストアの確認

- [ ] DynamoDBテーブルが存在するか？ (`awslocal dynamodb list-tables`)
- [ ] PostgreSQLに接続できるか？ (`psql -h localhost -p 50504 -U postgres`)
- [ ] Flywayマイグレーションが完了しているか？ (`SELECT * FROM flyway_schema_history;`)

### レベル4: イベント処理の確認

- [ ] Lambda関数がデプロイされているか？ (`awslocal lambda list-functions`)
- [ ] イベントソースマッピングが有効か？ (`awslocal lambda list-event-source-mappings`)
- [ ] Lambda関数のログにエラーがないか？ (`awslocal logs tail /aws/lambda/read-model-updater`)

### レベル5: データフローの確認

- [ ] DynamoDBにイベントが保存されているか？ (`awslocal dynamodb scan --table-name Journal`)
- [ ] PostgreSQLにデータが書き込まれているか？ (`SELECT * FROM user_accounts;`)
- [ ] E2Eテストが成功するか？ (`./scripts/test-e2e.sh`)

---

## まとめ

本章では、開発環境で遭遇する可能性のある主要な問題と、その解決方法を学びました。

### 重要なポイント

1. **ログを常に確認**: 問題の根本原因はログに記録されている
2. **段階的に診断**: サービスごとに切り分けて調査する
3. **環境をリセット**: 原因不明の場合は環境を再構築
4. **診断チェックリスト**: 体系的なアプローチで迅速に解決

### トラブルシューティングのベストプラクティス

- **再現性の確保**: 問題を再現できる手順を記録
- **ログの保存**: エラー発生時のログを保存
- **バージョン管理**: 動作していた時点のコミットに戻れるようにする
- **ドキュメント化**: 解決方法をチームで共有

---

## 次の章へ

トラブルシューティングの方法を学びました。次章では、日常的な開発ワークフローを確立します。

👉 [第9章：開発ワークフローの確立](part1-09-workflow.md)

---

## 参考資料

- [Docker Troubleshooting](https://docs.docker.com/config/daemon/troubleshoot/)
- [LocalStack Debugging Guide](https://docs.localstack.cloud/references/troubleshooting/)
- [PostgreSQL Error Codes](https://www.postgresql.org/docs/current/errcodes-appendix.html)
- [AWS Lambda Troubleshooting](https://docs.aws.amazon.com/lambda/latest/dg/lambda-troubleshooting.html)
