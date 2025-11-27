# 第1部 環境構築編 - 第4章：開発環境のセットアップ

## はじめに

本章では、実際に手を動かして開発環境を構築します。ステップバイステップで進めることで、約30分〜1時間で完全に動作するCQRS/Event Sourcingシステムを起動できるようになります。

セットアップの全体像は以下の通りです：

```
1. 前提条件の確認（Java、SBT、Docker）
   ↓
2. プロジェクトのクローンとビルド
   ↓
3. LocalStackのセットアップ（AWS環境のエミュレーション）
   ↓
4. PostgreSQLのセットアップ（Read Model）
   ↓
5. Lambda関数のデプロイ（イベント処理）
   ↓
6. アプリケーションの起動
```

---

## 4.1 前提条件の確認

開発環境を構築する前に、以下のツールが正しくインストールされているか確認します。

### 4.1.1 Java（OpenJDK 17以降）

Apache PekkoとScala 3はJVM上で動作するため、Javaが必須です。

#### インストール確認

```bash
# Javaバージョンの確認
java -version

# 期待される出力例:
# openjdk version "21.0.1" 2023-10-17 LTS
# OpenJDK Runtime Environment Temurin-21.0.1+12 (build 21.0.1+12-LTS)
```

**必須バージョン**: Java 17以降（推奨: OpenJDK 21）

#### インストール（macOS）

```bash
# Homebrewを使用
brew install openjdk@21

# シンボリックリンクの作成
sudo ln -sfn /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk \
  /Library/Java/JavaVirtualMachines/openjdk-21.jdk
```

#### インストール（Ubuntu/Debian）

```bash
# OpenJDK 21のインストール
sudo apt update
sudo apt install openjdk-21-jdk

# デフォルトのJavaバージョン設定
sudo update-alternatives --config java
```

---

### 4.1.2 SBT（1.8以降）

SBT（Scala Build Tool）は、Scalaプロジェクトのビルドツールです。

#### インストール確認

```bash
# SBTバージョンの確認
sbt -version

# 期待される出力例:
# sbt version in this project: 1.10.6
# sbt script version: 1.10.6
```

**必須バージョン**: SBT 1.8以降

#### インストール（macOS）

```bash
# Homebrewを使用
brew install sbt
```

#### インストール（Ubuntu/Debian）

```bash
# SBT公式リポジトリの追加
echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | \
  sudo tee /etc/apt/sources.list.d/sbt.list
curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | \
  sudo apt-key add

# インストール
sudo apt update
sudo apt install sbt
```

---

### 4.1.3 Docker & Docker Compose

LocalStack、PostgreSQL、DynamoDBをコンテナで実行するため、Dockerが必須です。

#### インストール確認

```bash
# Dockerバージョンの確認
docker --version
docker compose version

# 期待される出力例:
# Docker version 24.0.7, build afdd53b
# Docker Compose version v2.23.3
```

**必須バージョン**: Docker 20.10以降、Docker Compose 2.0以降

#### インストール（macOS）

```bash
# Docker Desktopをダウンロードしてインストール
# https://www.docker.com/products/docker-desktop/

# または Homebrewを使用
brew install --cask docker
```

#### インストール（Ubuntu/Debian）

```bash
# Docker公式のインストールスクリプトを使用
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh

# 現在のユーザーをdockerグループに追加
sudo usermod -aG docker $USER

# Docker Composeのインストール（最新版）
sudo apt install docker-compose-plugin
```

**重要**: インストール後、一度ログアウトして再ログインしてください。

---

### 4.1.4 awslocal CLI（オプション）

`awslocal`は、LocalStackとやり取りするための便利なツールです。必須ではありませんが、トラブルシューティングに役立ちます。

#### インストール

```bash
# pipを使用してインストール
pip install awscli-local

# 確認
awslocal --version

# 期待される出力例:
# aws-cli/2.13.25 Python/3.11.5
```

---

## 4.2 プロジェクトのクローンとビルド

### 4.2.1 リポジトリのクローン

```bash
# GitHubからクローン
git clone https://github.com/j5ik2o/pekko-cqrs-es-example.git

# プロジェクトディレクトリに移動
cd pekko-cqrs-es-example
```

#### プロジェクト構造の確認

```bash
# ディレクトリ構造を確認
tree -L 2 -I 'target|node_modules'

# 期待される出力:
# .
# ├── apps/                    # アプリケーション
# │   ├── command-api/         # コマンドAPI
# │   ├── query-api/           # クエリAPI
# │   └── read-model-updater/  # Lambda関数
# ├── modules/                 # ドメインモジュール
# │   ├── command/             # コマンド側
# │   ├── query/               # クエリ側
# │   └── infrastructure/      # 共通インフラ
# ├── scripts/                 # シェルスクリプト
# ├── tools/                   # ツール（DynamoDBセットアップ等）
# ├── docker-compose-common.yml
# ├── build.sbt
# └── README.md
```

---

### 4.2.2 SBTビルドの実行

最初のビルドは依存関係のダウンロードが発生するため、時間がかかります（10〜15分程度）。

```bash
# 全モジュールのコンパイル
sbt compile

# ビルド中の出力例:
# [info] welcome to sbt 1.10.6
# [info] loading settings for project ...
# [info] compiling 42 Scala sources to target/scala-3.6.2/classes ...
# [success] Total time: 3 min 25 s
```

#### コンパイル時の注意点

**Protocol Buffersの自動生成**:
- `.proto`ファイルから自動的にScalaコードが生成されます
- 生成先: `target/scala-3.6.2/pekko-grpc/main/`
- 変更を加えた場合は`sbt clean compile`で再生成

**並列ビルド**:
- SBTはデフォルトで並列ビルドを行います
- メモリ不足の場合は`.sbtopts`で`-J-Xmx4G`等を設定

---

### 4.2.3 Dockerイメージのビルド

3つのアプリケーションのDockerイメージをビルドします。

```bash
# 全サービスのDockerイメージをビルド
sbt dockerBuildAll

# 内部で以下が実行されます:
# - command-api/docker:publishLocal
# - query-api/docker:publishLocal
# - read-model-updater/docker:publishLocal
```

#### ビルドされるイメージの確認

```bash
# Dockerイメージのリストを表示
docker images | grep pekko-cqrs-es-example

# 期待される出力:
# pekko-cqrs-es-example-command-api          latest    abc123...    10 minutes ago    500MB
# pekko-cqrs-es-example-query-api            latest    def456...    10 minutes ago    480MB
# pekko-cqrs-es-example-read-model-updater   latest    ghi789...    10 minutes ago    450MB
```

---

## 4.3 LocalStackの理解とセットアップ

### 4.3.1 LocalStackとは

**LocalStack**は、AWS サービスをローカル環境でエミュレートするツールです。これにより、実際のAWSアカウントなしで開発とテストが可能になります。

#### 本プロジェクトで使用するサービス

- **DynamoDB**: イベントストア（Journalテーブル、Snapshotテーブル）
- **DynamoDB Streams**: イベントの変更データキャプチャ
- **Lambda**: Read Model Updater関数
- **CloudWatch Logs**: Lambda関数のログ

#### LocalStackの利点

1. **コスト削減**: AWS料金が発生しない
2. **高速開発**: ネットワークレイテンシがない
3. **リセット可能**: 環境を簡単にクリーンアップできる
4. **オフライン開発**: インターネット接続不要

---

### 4.3.2 docker-compose-common.ymlの解説

LocalStackの設定は`docker-compose-common.yml`で定義されています。

```yaml
# docker-compose-common.yml（抜粋）
services:
  localstack:
    image: localstack/localstack:4.7
    hostname: localstack
    ports:
      - "50503:4566"  # LocalStackのエンドポイント
    environment:
      - SERVICES=lambda,dynamodb  # 使用するサービス
      - LAMBDA_EXECUTOR=docker    # Lambda実行環境
      - AWS_DEFAULT_REGION=ap-northeast-1
      - AWS_ACCESS_KEY_ID=dummy
      - AWS_SECRET_ACCESS_KEY=dummy
      - LAMBDA_DOCKER_NETWORK=pekko-cqrs-es-example_p-cqrs-es-network
    volumes:
      - "/var/run/docker.sock:/var/run/docker.sock"  # Docker-in-Docker
```

#### 重要な環境変数の説明

| 環境変数 | 説明 |
|---------|------|
| `SERVICES=lambda,dynamodb` | 使用するAWSサービスの指定 |
| `LAMBDA_EXECUTOR=docker` | Lambda関数をDockerコンテナで実行 |
| `LAMBDA_DOCKER_NETWORK` | Lambda関数が使用するDockerネットワーク |
| `AWS_DEFAULT_REGION` | デフォルトリージョン（ap-northeast-1） |

#### Docker-in-Docker設定

LocalStackがLambda関数をDockerコンテナとして実行するため、Dockerソケットをマウントしています：

```yaml
volumes:
  - "/var/run/docker.sock:/var/run/docker.sock"
```

---

### 4.3.3 DynamoDBテーブルの作成

#### テーブル定義ファイル

DynamoDBテーブルの定義は`tools/dynamodb-setup/`ディレクトリにJSONファイルで格納されています。

**主要なテーブル**:
- `journal-table.json`: イベントジャーナル
- `snapshot-table.json`: スナップショット
- `state-table.json`: アクター状態（Cluster Sharding用）

#### Journalテーブルの構造

`tools/dynamodb-setup/journal-table.json`を確認してみましょう：

```json
{
  "TableName": "Journal",
  "AttributeDefinitions": [
    {
      "AttributeName": "pkey",
      "AttributeType": "S"
    },
    {
      "AttributeName": "skey",
      "AttributeType": "S"
    },
    {
      "AttributeName": "persistence-id",
      "AttributeType": "S"
    },
    {
      "AttributeName": "sequence-nr",
      "AttributeType": "N"
    }
  ],
  "KeySchema": [
    {
      "KeyType": "HASH",
      "AttributeName": "pkey"
    },
    {
      "KeyType": "RANGE",
      "AttributeName": "skey"
    }
  ],
  "GlobalSecondaryIndexes": [
    {
      "IndexName": "GetJournalRowsIndex",
      "KeySchema": [
        {
          "AttributeName": "persistence-id",
          "KeyType": "HASH"
        },
        {
          "AttributeName": "sequence-nr",
          "KeyType": "RANGE"
        }
      ],
      "Projection": {
        "ProjectionType": "ALL"
      }
    }
  ],
  "StreamSpecification": {
    "StreamEnabled": true,
    "StreamViewType": "NEW_IMAGE"
  }
}
```

#### 重要なポイント

**1. パーティションキーとソートキー**:
- `pkey`: パーティションキー（`persistence-id`の一部）
- `skey`: ソートキー（シーケンス番号を含む）

**2. Global Secondary Index (GSI)**:
- `GetJournalRowsIndex`: persistence-idとsequence-nrでクエリ可能
- イベントの高速な読み取りを実現

**3. DynamoDB Streams**:
- `StreamEnabled: true`: ストリームを有効化
- `StreamViewType: NEW_IMAGE`: 新しい値のみを配信

#### 自動セットアップ

テーブルの作成は、`dynamodb-setup`サービスが自動的に実行します：

```yaml
# docker-compose-common.yml
dynamodb-setup:
  build:
    context: ./tools/dynamodb-setup
  environment:
    AWS_ACCESS_KEY_ID: dummy
    AWS_SECRET_ACCESS_KEY: dummy
    AWS_DEFAULT_REGION: ap-northeast-1
    DYNAMODB_ENDPOINT: http://localstack:4566
  command: ["-e", "dev"]
  depends_on:
    localstack:
      condition: service_healthy
```

---

### 4.3.4 DynamoDB Streamsの設定

DynamoDB Streamsは、テーブルの変更をリアルタイムで検知し、Lambda関数をトリガーします。

#### データフロー

```
Journal テーブルへの INSERT
  ↓
DynamoDB Streams（NEW_IMAGE）
  ↓
Lambda 関数（read-model-updater）
  ↓
PostgreSQL（user_accountsテーブルの更新）
```

#### Streamsの動作確認

LocalStack起動後、以下のコマンドでStreamsの状態を確認できます：

```bash
# DynamoDB Streamsのリストを表示
awslocal dynamodbstreams list-streams

# 期待される出力:
# {
#     "Streams": [
#         {
#             "StreamArn": "arn:aws:dynamodb:ap-northeast-1:000000000000:table/Journal/stream/...",
#             "TableName": "Journal",
#             "StreamLabel": "..."
#         }
#     ]
# }
```

---

## 4.4 PostgreSQLのセットアップ

### 4.4.1 PostgreSQL起動の仕組み

PostgreSQLは、`docker-compose-common.yml`で定義されています：

```yaml
# docker-compose-common.yml（抜粋）
postgres:
  image: postgres:16.4
  environment:
    POSTGRES_USER: postgres
    POSTGRES_PASSWORD: postgres
    POSTGRES_DB: p-cqrs-es_development
    POSTGRES_HOST_AUTH_METHOD: trust  # ローカル開発用（パスワード不要）
  ports:
    - "50504:5432"
  restart: unless-stopped
```

#### 接続情報

| 項目 | 値 |
|------|-----|
| ホスト | `localhost` |
| ポート | `50504` |
| データベース名 | `p-cqrs-es_development` |
| ユーザー名 | `postgres` |
| パスワード | `postgres` |

---

### 4.4.2 Flywayによるマイグレーション戦略

**Flyway**は、データベースのスキーマをバージョン管理するツールです。

#### マイグレーションファイルの配置

マイグレーションファイルは以下のディレクトリに配置されています：

```
modules/query/flyway-migration/
└── src/main/resources/db/migration/
    └── V1__create_user_accounts_table.sql
```

#### V1__create_user_accounts_table.sql

```sql
-- ユーザーアカウントテーブル（Read Model）
CREATE TABLE user_accounts (
  id VARCHAR(255) PRIMARY KEY,
  first_name VARCHAR(255) NOT NULL,
  last_name VARCHAR(255) NOT NULL,
  email VARCHAR(255) NOT NULL UNIQUE,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  deleted_at TIMESTAMP
);

-- 検索用インデックス
CREATE INDEX idx_user_accounts_email ON user_accounts(email);
CREATE INDEX idx_user_accounts_name ON user_accounts(first_name, last_name);
CREATE INDEX idx_user_accounts_created_at ON user_accounts(created_at);

-- 削除されていないユーザーの検索用インデックス
CREATE INDEX idx_user_accounts_active ON user_accounts(deleted_at) WHERE deleted_at IS NULL;
```

#### マイグレーションの実行

SBTタスクでマイグレーションを実行します：

```bash
# マイグレーション実行
sbt migrateQuery

# 出力例:
# [info] Flyway Community Edition 10.8.1 by Redgate
# [info] Database: jdbc:postgresql://localhost:50504/p-cqrs-es_development (PostgreSQL 16.4)
# [info] Successfully validated 1 migration (execution time 00:00.015s)
# [info] Current version of schema "public": << Empty Schema >>
# [info] Migrating schema "public" to version "1 - create user accounts table"
# [info] Successfully applied 1 migration to schema "public" (execution time 00:00.045s)
```

#### マイグレーション管理コマンド

```bash
# マイグレーション情報の表示
sbt infoQuery

# マイグレーションの検証
sbt validateQuery

# クリーン後にマイグレーション実行
sbt cleanMigrateQuery
```

---

### 4.4.3 スキーマ設計（user_accountsテーブル）

#### テーブル設計の考え方

**Read Modelの非正規化**:
- クエリパフォーマンスを最優先
- JOINを避け、単一テーブルで完結
- 必要に応じて冗長なフィールドを持つ

**フィールドの説明**:

| フィールド | 型 | 説明 |
|-----------|-----|------|
| `id` | VARCHAR(255) | ユーザーID（ULID形式） |
| `first_name` | VARCHAR(255) | 名 |
| `last_name` | VARCHAR(255) | 姓 |
| `email` | VARCHAR(255) | メールアドレス（一意制約） |
| `created_at` | TIMESTAMP | 作成日時 |
| `updated_at` | TIMESTAMP | 更新日時 |
| `deleted_at` | TIMESTAMP | 削除日時（論理削除） |

**論理削除の採用**:
- `deleted_at IS NULL`: 有効なユーザー
- `deleted_at IS NOT NULL`: 削除済みユーザー
- 監査証跡を保持しつつ、削除機能を実現

**インデックス戦略**:
- `email`: メールアドレス検索用（UNIQUE制約）
- `(first_name, last_name)`: 名前検索用
- `created_at`: 作成日時でのソート用
- `deleted_at`: 論理削除フィルタ用（部分インデックス）

---

### 4.4.4 Slick DAOの自動生成

#### sbt-dao-generatorプラグイン

Slick DAOは、データベーススキーマから自動生成されます。

```bash
# PostgreSQL起動とマイグレーション実行後に実行
sbt "queryInterfaceAdapter/generateAllWithDb"

# 内部で以下が実行されます:
# 1. PostgreSQLに接続
# 2. user_accountsテーブルの構造を読み取り
# 3. Slickのテーブル定義とDAOを生成
```

#### 生成されるコード

生成先: `modules/query/interface-adapter/src/main/scala/dao/`

```scala
// 自動生成されるテーブル定義（イメージ）
class UserAccountsTable(tag: Tag) extends Table[UserAccountRecord](tag, "user_accounts") {
  def id = column[String]("id", O.PrimaryKey)
  def firstName = column[String]("first_name")
  def lastName = column[String]("last_name")
  def email = column[String]("email")
  def createdAt = column[Instant]("created_at")
  def updatedAt = column[Instant]("updated_at")
  def deletedAt = column[Option[Instant]]("deleted_at")

  def * = (id, firstName, lastName, email, createdAt, updatedAt, deletedAt).mapTo[UserAccountRecord]
}
```

#### DAOの使用例

```scala
// ユーザーの検索（実装例）
def findById(id: String): Future[Option[UserAccountRecord]] = {
  db.run(
    UserAccounts
      .filter(_.id === id)
      .filter(_.deletedAt.isEmpty)  // 削除されていないもの
      .result
      .headOption
  )
}
```

---

## 4.5 Lambda関数のデプロイ

### 4.5.1 Read Model Updaterの役割

**read-model-updater**は、イベントストアから読み取りモデルを更新するLambda関数です。

#### 処理フロー

```
1. DynamoDB Streams からイベント受信
2. PersistentRepr のデシリアライゼーション
3. イベントペイロードの抽出
4. イベントタイプに応じた処理
   - Created_V1 → INSERT
   - Renamed_V1 → UPDATE
   - Deleted_V1 → UPDATE (deleted_at設定)
5. PostgreSQL への書き込み
```

#### LambdaHandler.scalaの概要

`apps/read-model-updater/src/main/scala/LambdaHandler.scala`:

```scala
class LambdaHandler extends RequestHandler[DynamodbEvent, String] {
  override def handleRequest(event: DynamodbEvent, context: Context): String = {
    val records = event.getRecords.asScala

    records.foreach { record =>
      val newImage = record.getDynamodb.getNewImage

      // PersistentReprのデシリアライゼーション
      val persistentRepr = deserializePersistentRepr(newImage)

      // イベントの抽出
      val event = extractEvent(persistentRepr)

      // Read Modelの更新
      event match {
        case Created_V1(id, firstName, lastName, email, occurredAt) =>
          insertUserAccount(id, firstName, lastName, email, occurredAt)

        case Renamed_V1(id, firstName, lastName, occurredAt) =>
          updateUserAccountName(id, firstName, lastName, occurredAt)

        case Deleted_V1(id, occurredAt) =>
          deleteUserAccount(id, occurredAt)
      }
    }

    "SUCCESS"
  }
}
```

---

### 4.5.2 LocalStackへのデプロイ手順

Lambda関数のデプロイは、起動スクリプトが自動的に実行します。

#### デプロイの仕組み

`scripts/run-single.sh up`実行時、以下の処理が行われます：

```bash
# 1. Dockerイメージのビルド（すでに実行済み）
sbt dockerBuildAll

# 2. LocalStackの起動
docker compose -f docker-compose-common.yml -f docker-compose-local.yml up -d localstack

# 3. DynamoDBテーブルの作成
docker compose -f docker-compose-common.yml -f docker-compose-local.yml up dynamodb-setup

# 4. Lambda関数のデプロイ
awslocal lambda create-function \
  --function-name read-model-updater \
  --runtime provided.al2 \
  --role arn:aws:iam::000000000000:role/lambda-role \
  --handler io.github.j5ik2o.pcqrses.readmodel.LambdaHandler \
  --zip-file fileb://apps/read-model-updater/target/read-model-updater.zip \
  --environment Variables="{POSTGRES_HOST=postgres,POSTGRES_PORT=5432}"
```

---

### 4.5.3 イベントソースマッピングの設定

Lambda関数とDynamoDB Streamsを接続します。

#### イベントソースマッピングの作成

```bash
# DynamoDB StreamのARNを取得
STREAM_ARN=$(awslocal dynamodbstreams list-streams \
  --table-name Journal \
  --query 'Streams[0].StreamArn' \
  --output text)

# イベントソースマッピングの作成
awslocal lambda create-event-source-mapping \
  --function-name read-model-updater \
  --event-source-arn $STREAM_ARN \
  --starting-position LATEST \
  --batch-size 10
```

#### イベントソースマッピングの確認

```bash
# マッピングの一覧を表示
awslocal lambda list-event-source-mappings

# 期待される出力:
# {
#     "EventSourceMappings": [
#         {
#             "UUID": "...",
#             "BatchSize": 10,
#             "EventSourceArn": "arn:aws:dynamodb:ap-northeast-1:000000000000:table/Journal/stream/...",
#             "FunctionArn": "arn:aws:lambda:ap-northeast-1:000000000000:function:read-model-updater",
#             "State": "Enabled",
#             "StateTransitionReason": "User action"
#         }
#     ]
# }
```

#### Lambda関数のログ確認

```bash
# CloudWatch Logsでログを確認
awslocal logs tail /aws/lambda/read-model-updater --follow

# イベント処理のログ例:
# 2025-11-27 10:15:23 START RequestId: abc-123
# 2025-11-27 10:15:23 Processing event: Created_V1(user-001, 太郎, 山田, yamada@example.com)
# 2025-11-27 10:15:23 Inserted user account: user-001
# 2025-11-27 10:15:23 END RequestId: abc-123
```

---

## まとめ

本章では、開発環境のセットアップを実施しました：

### 達成したこと

1. ✅ **前提条件の確認**: Java、SBT、Dockerのインストール
2. ✅ **プロジェクトのビルド**: SBTコンパイルとDockerイメージのビルド
3. ✅ **LocalStackのセットアップ**: DynamoDB、DynamoDB Streams、Lambda環境の構築
4. ✅ **PostgreSQLのセットアップ**: Flywayマイグレーション、Slick DAO自動生成
5. ✅ **Lambda関数のデプロイ**: Read Model Updaterのデプロイとイベントソースマッピング

### 次のステップ

環境構築が完了しましたが、まだアプリケーションを起動していません。次章では、設定ファイルの体系的な管理方法を学びます。

👉 [第5章：設定管理の体系化](part1-05-configuration.md)

---

## トラブルシューティング

### Dockerコンテナが起動しない

```bash
# Dockerデーモンの状態確認
docker info

# ディスク容量の確認
df -h

# Dockerのクリーンアップ
docker system prune -a
```

### SBTビルドが失敗する

```bash
# クリーンビルド
sbt clean compile

# 依存関係のキャッシュをクリア
rm -rf ~/.ivy2/cache
rm -rf ~/.sbt/boot
sbt update
```

### PostgreSQLに接続できない

```bash
# コンテナのログを確認
docker logs postgres

# ポートの使用状況を確認
lsof -i :50504

# コンテナの再起動
docker compose restart postgres
```

### LocalStackが正常に動作しない

```bash
# ヘルスチェック
curl http://localhost:50503/_localstack/health

# ログの確認
docker logs localstack

# 完全な再起動
docker compose down
docker compose up -d localstack
```

---

## 参考資料

- [LocalStack Documentation](https://docs.localstack.cloud/)
- [Flyway Documentation](https://flywaydb.org/documentation/)
- [Slick Documentation](https://scala-slick.org/doc/)
- [Docker Compose Reference](https://docs.docker.com/compose/compose-file/)
- [AWS Lambda Documentation](https://docs.aws.amazon.com/lambda/)
