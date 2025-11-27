# 第1部 環境構築編 - 第9章：開発ワークフローの確立

## はじめに

本章では、CQRS/Event Sourcingシステムの日常的な開発ワークフローを確立します。コードフォーマット、リントチェック、テスト実行、環境の停止・再起動など、開発者が頻繁に行う操作を体系的に整理します。

### 本章で学ぶこと

1. **コード品質管理**: フォーマット、リント、テスト
2. **データベース操作**: マイグレーション、DAO生成
3. **環境管理**: 起動、停止、クリーンアップ
4. **開発の効率化**: よく使うコマンドとショートカット

---

## 9.1 コード品質管理

### 9.1.1 コードフォーマット

#### Scalafmtによる自動フォーマット

プロジェクトでは、**Scalafmt**を使用してコードスタイルを統一しています。

```bash
# 全ファイルをフォーマット
sbt fmt

# 内部で実行されるコマンド:
# - scalafmtAll    # すべてのソースコードをフォーマット
# - scalafmtSbt    # build.sbtをフォーマット
```

#### フォーマット設定

`.scalafmt.conf`で設定を管理：

```hocon
version = "3.7.17"
runner.dialect = scala3

# インデント
indent.main = 2
indent.callSite = 2

# 行の長さ
maxColumn = 120

# その他のスタイル設定
align.preset = more
rewrite.rules = [SortImports, RedundantBraces]
```

---

#### コミット前の自動フォーマット（推奨）

Gitフックを使用して、コミット前に自動的にフォーマットを適用できます：

```bash
# .git/hooks/pre-commit を作成
cat > .git/hooks/pre-commit << 'EOF'
#!/bin/bash
sbt scalafmtCheckAll scalafmtSbtCheck
if [ $? -ne 0 ]; then
    echo "Code formatting issues detected. Running sbt fmt..."
    sbt fmt
    git add -u
fi
EOF

# 実行権限を付与
chmod +x .git/hooks/pre-commit
```

---

### 9.1.2 リントチェック

#### Scalafix + Scalafmtによるリント

```bash
# リントチェック（フォーマット検証を含む）
sbt lint

# 内部で実行されるコマンド:
# - scalafmtCheckAll    # フォーマットが適用されているか確認
# - scalafmtSbtCheck    # build.sbtのフォーマット確認
```

#### リントエラーの修正

```bash
# エラーが出た場合
$ sbt lint
[error] Scalafmt: 5 files need formatting

# フォーマットを適用
$ sbt fmt

# 再度チェック
$ sbt lint
[success] All files are formatted correctly
```

---

### 9.1.3 テスト実行

#### 全テストの実行

```bash
# 全モジュールのテストを実行
sbt test

# 並列実行を無効化（デバッグ時）
sbt "set Global / concurrentRestrictions := Seq(Tags.limit(Tags.Test, 1))" test
```

---

#### 特定モジュールのテスト

```bash
# コマンドドメインのテスト
sbt "commandDomain/test"

# クエリインターフェースアダプターのテスト
sbt "queryInterfaceAdapter/test"

# インフラストラクチャのテスト
sbt "infrastructure/test"
```

---

#### 特定のテストクラスのみ実行

```bash
# 完全修飾クラス名を指定
sbt "testOnly io.github.j5ik2o.pcqrses.domain.users.UserAccountSpec"

# パターンマッチで複数テスト
sbt "testOnly *UserAccount*"

# 特定のテストメソッドのみ（ScalaTestの場合）
sbt "testOnly io.github.j5ik2o.pcqrses.domain.users.UserAccountSpec -- -z 'should create user account'"
```

---

#### カバレッジ付きテスト

```bash
# カバレッジレポートを生成
sbt testCoverage

# 内部で実行されるコマンド:
# - coverage          # カバレッジを有効化
# - test              # テスト実行
# - coverageReport    # HTMLレポート生成

# レポートの場所:
# target/scala-3.6.2/scoverage-report/index.html
```

**レポートの確認**:

```bash
# macOS
open target/scala-3.6.2/scoverage-report/index.html

# Linux
xdg-open target/scala-3.6.2/scoverage-report/index.html
```

---

### 9.1.4 コンパイル

#### 全モジュールのコンパイル

```bash
# 全モジュールをコンパイル
sbt compile

# クリーン後にコンパイル
sbt clean compile

# Protobufコードの再生成を含む
sbt clean compile
```

---

#### 継続的コンパイル

```bash
# ファイル変更を監視して自動コンパイル
sbt ~compile

# Ctrl+C で終了
```

---

## 9.2 データベース操作

### 9.2.1 Flywayマイグレーション

#### マイグレーション実行

```bash
# PostgreSQLマイグレーションを実行
sbt migrateQuery

# 出力例:
# [info] Flyway Community Edition 10.8.1 by Redgate
# [info] Database: jdbc:postgresql://localhost:50504/p-cqrs-es_development (PostgreSQL 16.4)
# [info] Successfully validated 1 migration (execution time 00:00.015s)
# [info] Current version of schema "public": 1
# [info] Schema "public" is up to date. No migration necessary.
```

---

#### マイグレーション情報の表示

```bash
# 現在のマイグレーション状態を表示
sbt infoQuery

# 出力例:
# +-----------+---------+------------------------------+----------+---------------------+---------+
# | Category  | Version | Description                  | Type     | Installed On        | State   |
# +-----------+---------+------------------------------+----------+---------------------+---------+
# | Versioned | 1       | create user accounts table   | SQL      | 2025-11-27 10:00:00 | Success |
# +-----------+---------+------------------------------+----------+---------------------+---------+
```

---

#### マイグレーションの検証

```bash
# マイグレーションスクリプトの検証
sbt validateQuery

# チェックサムが変更されていないか確認
```

---

#### クリーン後にマイグレーション

```bash
# データベースを完全にクリーンアップして再マイグレーション
sbt cleanMigrateQuery

# 警告: 全データが削除されます
```

---

### 9.2.2 Slick DAO自動生成

#### DAOの生成

```bash
# PostgreSQLから自動生成
sbt "queryInterfaceAdapter/generateAllWithDb"

# 内部で実行される処理:
# 1. PostgreSQLに接続
# 2. テーブル定義を読み取り
# 3. Slickのテーブルマッピングを生成
# 4. DAOクラスを生成
```

---

#### 生成されるファイル

```
modules/query/interface-adapter/src/main/scala/dao/
├── Tables.scala           # テーブル定義
├── UserAccountsTable.scala # user_accountsテーブルのマッピング
└── UserAccountDao.scala   # DAO実装（必要に応じて手動作成）
```

---

#### マイグレーション後のワークフロー

```bash
# 1. マイグレーションスクリプトを作成
# modules/query/flyway-migration/src/main/resources/db/migration/V2__add_user_status.sql

# 2. マイグレーションを実行
sbt migrateQuery

# 3. DAOを再生成
sbt "queryInterfaceAdapter/generateAllWithDb"

# 4. コンパイルして確認
sbt compile
```

---

## 9.3 環境管理

### 9.3.1 環境の起動

#### シングルノードモード

```bash
# 全サービスを起動（バックグラウンド）
./scripts/run-single.sh up

# フォアグラウンドで起動（ログを直接表示）
./scripts/run-single.sh up --attach

# データベースのみ起動（API開発時）
./scripts/run-single.sh --db-only
```

---

#### クラスターモード（3ノード）

```bash
# クラスター構成で起動
./scripts/run-cluster.sh up

# 3つのCommand APIノードが起動:
# - Node 1: Port 50501
# - Node 2: Port 50511
# - Node 3: Port 50521
```

---

### 9.3.2 環境の停止

#### 通常の停止

```bash
# 全サービスを停止（データは保持）
./scripts/run-single.sh down

# コンテナのみ停止（ネットワークは保持）
docker compose -f docker-compose-common.yml -f docker-compose-local.yml stop
```

---

#### 完全なクリーンアップ

```bash
# 全サービスとボリュームを削除
./scripts/run-single.sh down --volumes

# 警告: PostgreSQLとDynamoDBのデータも削除されます
```

---

### 9.3.3 ログの確認

```bash
# 全サービスのログ
./scripts/run-single.sh logs

# リアルタイムでフォロー
./scripts/run-single.sh logs -f

# 特定サービスのログ
docker logs command-api --follow
docker logs query-api --tail=100
docker logs postgres
docker logs localstack

# Lambda関数のログ
awslocal logs tail /aws/lambda/read-model-updater --follow
```

---

## 9.4 よく使うコマンド集

### 9.4.1 日常的な開発作業

#### 朝のセットアップ

```bash
# 1. 最新コードを取得
git pull origin develop

# 2. 依存関係を更新
sbt update

# 3. 環境を起動
./scripts/run-single.sh up

# 4. ヘルスチェック
curl http://localhost:50501/api/health
curl http://localhost:50502/api/health
```

---

#### コード変更後

```bash
# 1. フォーマットとリント
sbt fmt
sbt lint

# 2. コンパイル
sbt compile

# 3. テスト実行
sbt test

# 4. Dockerイメージ再ビルド（API変更時）
sbt dockerBuildAll

# 5. サービス再起動
docker restart command-api
docker restart query-api
```

---

#### 終業時のクリーンアップ

```bash
# 環境を停止（データは保持）
./scripts/run-single.sh down

# または完全にクリーンアップ
./scripts/run-single.sh down --volumes
```

---

### 9.4.2 新機能開発のワークフロー

#### ドメインイベントの追加

```bash
# 1. イベント定義を追加
# modules/command/domain/src/main/scala/users/UserAccountEvent.scala

# 2. Protobuf定義を追加
# modules/command/interface-adapter-contract/src/main/protobuf/user_account_event.proto

# 3. コンパイル（Protobufコード生成）
sbt compile

# 4. シリアライザを更新
# modules/command/interface-adapter-event-serializer/src/main/scala/UserAccountEventSerializer.scala

# 5. テスト作成と実行
sbt "commandDomain/test"
```

---

#### GraphQL Mutation/Queryの追加

**Command側（Mutation）**:

```bash
# 1. スキーマ定義を追加
# modules/command/interface-adapter/src/main/scala/graphql/TypeDefinitions.scala

# 2. リゾルバを実装
# modules/command/interface-adapter/src/main/scala/graphql/MutationResolver.scala

# 3. コンパイルとテスト
sbt compile
sbt "commandInterfaceAdapter/test"

# 4. Dockerイメージ再ビルド
sbt "commandApi/docker:publishLocal"

# 5. Command API再起動
docker restart command-api
```

**Query側（Query）**:

```bash
# 1. マイグレーションスクリプト作成
# modules/query/flyway-migration/src/main/resources/db/migration/V2__add_new_field.sql

# 2. マイグレーション実行
sbt migrateQuery

# 3. DAO再生成
sbt "queryInterfaceAdapter/generateAllWithDb"

# 4. GraphQLスキーマとリゾルバを追加
# modules/query/interface-adapter/src/main/scala/graphql/

# 5. コンパイルとテスト
sbt compile
sbt "queryInterfaceAdapter/test"

# 6. Dockerイメージ再ビルド
sbt "queryApi/docker:publishLocal"

# 7. Query API再起動
docker restart query-api
```

---

#### Read Model Updater（Lambda）の変更

```bash
# 1. LambdaHandler.scalaを編集
# apps/read-model-updater/src/main/scala/LambdaHandler.scala

# 2. コンパイル
sbt compile

# 3. Dockerイメージ再ビルド
sbt "readModelUpdater/docker:publishLocal"

# 4. Lambda関数を再デプロイ
./scripts/deploy-lambda.sh  # または環境を再起動

# 5. ログで動作確認
awslocal logs tail /aws/lambda/read-model-updater --follow
```

---

### 9.4.3 デバッグ用コマンド

#### データの確認

```bash
# DynamoDB Journalテーブル
awslocal dynamodb scan --table-name Journal | jq '.Items | length'

# PostgreSQL user_accountsテーブル
psql -h localhost -p 50504 -U postgres -d p-cqrs-es_development \
  -c "SELECT COUNT(*) FROM user_accounts;"

# Lambda関数のログ（最新10行）
awslocal logs tail /aws/lambda/read-model-updater --max-items 10
```

---

#### データのリセット

```bash
# PostgreSQLのみリセット
docker exec -it postgres psql -U postgres -d p-cqrs-es_development \
  -c "TRUNCATE TABLE user_accounts RESTART IDENTITY CASCADE;"

# DynamoDBのみリセット
awslocal dynamodb delete-table --table-name Journal
docker compose -f docker-compose-common.yml up dynamodb-setup

# 完全リセット
./scripts/run-single.sh down --volumes
./scripts/run-single.sh up
```

---

## 9.5 開発効率化のTips

### 9.5.1 エイリアスの設定

`.bashrc`または`.zshrc`に追加：

```bash
# プロジェクトディレクトリへの移動
alias cdpekko="cd ~/projects/pekko-cqrs-es-example"

# よく使うコマンド
alias pekko-up="./scripts/run-single.sh up"
alias pekko-down="./scripts/run-single.sh down"
alias pekko-logs="./scripts/run-single.sh logs -f"
alias pekko-test="./scripts/test-e2e.sh"

# SBTコマンド
alias pekko-fmt="sbt fmt"
alias pekko-compile="sbt compile"
alias pekko-test-unit="sbt test"

# データベース接続
alias pekko-psql="psql -h localhost -p 50504 -U postgres -d p-cqrs-es_development"
```

---

### 9.5.2 IntelliJ IDEA設定

#### Scalafmt統合

1. `Preferences` → `Editor` → `Code Style` → `Scala`
2. `Formatter` → `Scalafmt` を選択
3. `Reformat on file save` を有効化

---

#### SBTシェルの活用

```scala
// IntelliJ IDEAのSBTシェルで
sbt:pekko-cqrs-es-example> ~compile  // 継続的コンパイル
sbt:pekko-cqrs-es-example> testOnly *UserAccountSpec  // 特定テスト
sbt:pekko-cqrs-es-example> project commandDomain  // プロジェクト切り替え
```

---

### 9.5.3 GraphQL Playgroundのブックマーク

よく使うクエリをブックマークしておくと便利です：

```graphql
# ブラウザのブックマーク: Command API Playground
http://localhost:50501/api/playground

# ブラウザのブックマーク: Query API Playground
http://localhost:50502/api/playground

# よく使うクエリをHistory/Tabsに保存
```

---

## まとめ

本章では、日常的な開発ワークフローを確立しました。

### 達成したこと

1. ✅ **コード品質管理**: フォーマット、リント、テストの実行方法
2. ✅ **データベース操作**: マイグレーション、DAO生成のワークフロー
3. ✅ **環境管理**: 起動、停止、ログ確認の方法
4. ✅ **開発効率化**: よく使うコマンド集とTips

### 開発ワークフローのベストプラクティス

- **コミット前のチェック**: `sbt fmt && sbt lint && sbt test`
- **継続的なフィードバック**: `sbt ~compile`で即座にエラーを検出
- **テストの自動化**: E2Eテストを定期的に実行
- **ログの活用**: 問題発生時は即座にログを確認

---

## 次の章へ

開発ワークフローが確立できました。最終章では、第1部のまとめと、第2部（サービス構築編）への導入を行います。

👉 [第10章：まとめと次のステップ](part1-10-summary.md)

---

## 参考資料

- [Scalafmt Documentation](https://scalameta.org/scalafmt/)
- [Scoverage Documentation](https://github.com/scoverage/sbt-scoverage)
- [Flyway Documentation](https://flywaydb.org/documentation/)
- [SBT Reference Manual](https://www.scala-sbt.org/1.x/docs/)
