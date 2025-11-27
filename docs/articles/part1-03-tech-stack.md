# 第1部 環境構築編 - 第3章：技術スタックの選定

## なぜ技術選定が重要なのか

CQRS/Event Sourcingのアーキテクチャを実現するには、適切な技術スタックの選定が不可欠です。本章では、このプロジェクトで採用した各技術の選定理由と、それぞれがアーキテクチャにもたらす利点を詳しく解説します。

技術選定では以下の基準を重視しました：

1. **型安全性**：コンパイル時のエラー検出
2. **スケーラビリティ**：水平スケーリングの容易さ
3. **エコシステム**：ライブラリとコミュニティの充実度
4. **本番実績**：エンタープライズ環境での採用実績
5. **開発者体験**：学習コストと生産性のバランス

---

## 1. コア技術スタック

### 1.1 Scala 3.6.2

#### 採用理由

**Scala 3**は、Scala 2からの大幅な改善を提供する次世代言語です。本プロジェクトでは以下の理由から採用しました：

**1. 強力な型システム**

Scala 3は、より洗練された型システムを提供します：

```scala
// Union型による柔軟な型表現
type ErrorOr[A] = A | UserAccountError

// Opaque型による型安全性の向上
opaque type UserAccountId = String
object UserAccountId:
  def apply(value: String): UserAccountId = value
  extension (id: UserAccountId)
    def value: String = id
```

**2. 関数型プログラミングとの親和性**

イミュータブルなデータ構造と純粋関数により、Event Sourcingとの相性が良好です：

```scala
// イミュータブルなイベント定義
sealed trait UserAccountEvent derives CborSerializable
case class Created(
  id: UserAccountId,
  name: UserAccountName,
  email: EmailAddress,
  occurredAt: Instant
) extends UserAccountEvent
```

**3. 簡潔な構文**

Scala 3の新構文により、コードがより読みやすくなりました：

```scala
// Scala 3の新構文（braceless syntax）
def createUser(name: String, email: String): Either[Error, User] =
  for
    validName <- validateName(name)
    validEmail <- validateEmail(email)
  yield User(validName, validEmail)
```

#### Scala 2との比較

| 項目 | Scala 2 | Scala 3 |
|------|---------|---------|
| 型システム | 複雑で学習コストが高い | シンプルで直感的 |
| 構文 | verboseな部分がある | 簡潔（braceless、new不要等） |
| コンパイル速度 | 遅い | 大幅に改善 |
| エラーメッセージ | わかりにくい | 改善され理解しやすい |

#### なぜJavaやKotlinではないのか

**Javaとの比較**：
- ✅ Scala：関数型プログラミングのファーストクラスサポート
- ✅ Scala：パターンマッチング、ケースクラス、for内包表記
- ❌ Java：冗長な構文、ボイラープレートコードが多い

**Kotlinとの比較**：
- ✅ Scala：より高度な型システム（Higher-Kinded Types等）
- ✅ Scala：Apache Pekkoとのシームレスな統合
- ✅ Kotlin：学習コストが低い（特にJava開発者）
- ⚖️ どちらも優れた言語だが、Pekkoエコシステムとの親和性でScalaに軍配

---

### 1.2 Apache Pekko 1.1.2

#### 採用理由

**Apache Pekko**は、Akka 2.6のフォークとして、オープンソースで継続開発されているアクターフレームワークです。

**1. 完全なオープンソースライセンス**

```
Apache Pekko: Apache License 2.0
Akka 2.7+:    Business Source License (BSL)
```

商用利用に制限がなく、エンタープライズ環境で安心して採用できます。

**2. Pekko Persistenceによるイベントソーシング**

Pekko Persistenceは、イベントソーシングを実装するための強力な抽象化を提供します：

```scala
object UserAccountAggregate:
  def apply(): EventSourcedBehavior[Command, Event, State] =
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId("user-account-123"),
      emptyState = State.empty,
      commandHandler = commandHandler,
      eventHandler = eventHandler
    ).withRetention(RetentionCriteria.snapshotEvery(numberOfEvents = 100))
```

**主な機能**：
- イベントの永続化
- スナップショット戦略
- イベントリプレイによる状態復元
- 複数のバックエンドサポート（DynamoDB、Cassandra、JDBC等）

**3. 型付きアクター（Typed Actors）**

型安全なメッセージ処理により、コンパイル時にエラーを検出できます：

```scala
// 型付きアクターの定義
object UserAccountAggregate:
  sealed trait Command
  case class CreateUser(id: String, name: String, replyTo: ActorRef[Reply]) extends Command

  sealed trait Reply
  case class UserCreated(id: String) extends Reply
  case class Rejected(reason: String) extends Reply
```

**4. Cluster Shardingによる分散処理**

エンティティを複数ノードに分散し、水平スケーリングを実現：

```scala
val sharding = ClusterSharding(system)
val shardRegion = sharding.init(Entity(UserAccountAggregate.EntityTypeName)(
  createBehavior = entityContext => UserAccountAggregate()
).withMessageExtractor(messageExtractor))
```

#### Akka vs Pekko比較

| 項目 | Apache Pekko | Akka 2.7+ |
|------|--------------|-----------|
| ライセンス | Apache 2.0（完全フリー） | BSL（商用制限あり） |
| コミュニティ | Apache Foundation | Lightbend社 |
| 互換性 | Akka 2.6と高い互換性 | 2.6→2.7で破壊的変更 |
| 長期サポート | Apacheプロジェクトとして継続 | ライセンス料次第 |

#### なぜ他のアクターフレームワークではないのか

**Akka（商用版）**：
- ❌ BSLライセンスによる商用利用の制限
- ❌ ライセンス料が必要

**Vert.x**：
- ✅ リアクティブアプリケーションに適している
- ❌ アクターモデルのサポートが弱い
- ❌ Event Sourcing用のライブラリが不足

**Erlang/Elixir**：
- ✅ アクターモデルの元祖で成熟している
- ❌ JVMエコシステムとの統合が困難
- ❌ 型安全性がScalaに劣る

---

## 2. データストア

### 2.1 DynamoDB（イベントストア）

#### 採用理由

**Amazon DynamoDB**をイベントストアとして採用した理由は以下の通りです：

**1. スケーラビリティと可用性**

DynamoDBは、以下の特性によりイベントストアに最適です：

- **自動スケーリング**：トラフィックに応じた自動的な容量調整
- **高可用性**：複数のAZにまたがる自動レプリケーション
- **一貫したパフォーマンス**：数ミリ秒のレイテンシ

**2. DynamoDB Streamsによるイベント駆動アーキテクチャ**

```
DynamoDB → DynamoDB Streams → Lambda → PostgreSQL
```

DynamoDB Streamsは、変更データキャプチャ（CDC）を提供し、イベントを非同期に処理できます：

```json
{
  "eventName": "INSERT",
  "dynamodb": {
    "NewImage": {
      "pkey": {"S": "user-account-123"},
      "event_type": {"S": "UserAccountCreated"},
      "payload": {"B": "..."}
    }
  }
}
```

**3. j5ik2o/pekko-persistence-dynamodbの活用**

Scala/Pekko向けの高品質なDynamoDBプラグインが存在します：

```scala
// application.confでの設定
j5ik2o.dynamo-db-journal {
  table-name = "Journal"
  get-journal-rows-index-name = "GetJournalRowsIndex"
}

j5ik2o.dynamo-db-snapshot {
  table-name = "Snapshot"
}
```

#### 代替技術との比較

| イベントストア | 利点 | 欠点 |
|---------------|------|------|
| **DynamoDB** | スケーラビリティ、マネージドサービス、Streams統合 | AWS依存、クエリ制限 |
| **Cassandra** | オンプレ可、高スループット | 運用負荷大、複雑な設定 |
| **PostgreSQL** | リレーショナルクエリ可、ACID保証 | スケーラビリティに限界 |
| **EventStore DB** | Event Sourcing専用設計 | 運用実績が少ない、ツール不足 |

DynamoDBは、**マネージドサービスの利便性**と**スケーラビリティ**のバランスが優れています。

---

### 2.2 PostgreSQL（Read Model）

#### 採用理由

**PostgreSQL**をRead Model（クエリ側）のデータストアとして採用した理由：

**1. リレーショナルクエリの柔軟性**

Read Modelでは、複雑な検索クエリが求められます：

```sql
-- 複雑な検索クエリの例
SELECT * FROM user_accounts
WHERE
  name LIKE '%John%'
  AND email LIKE '%@example.com'
  AND deleted_at IS NULL
ORDER BY created_at DESC
LIMIT 10 OFFSET 20;
```

DynamoDBでは実現困難なクエリも、PostgreSQLなら容易に実装できます。

**2. Slickによる型安全なデータアクセス**

Slickを使用することで、SQLを型安全に扱えます：

```scala
// Slickによるクエリ（コンパイル時型チェック）
def findByEmail(email: String): Future[Option[UserAccountRecord]] =
  db.run(
    UserAccounts
      .filter(_.email === email)
      .filter(_.deletedAt.isEmpty)
      .result
      .headOption
  )
```

**3. インデックス戦略による高速クエリ**

```sql
-- インデックスの作成
CREATE INDEX idx_user_accounts_email ON user_accounts(email);
CREATE INDEX idx_user_accounts_name ON user_accounts(name);
CREATE INDEX idx_user_accounts_created_at ON user_accounts(created_at);
```

適切なインデックス設計により、大量データでも高速なクエリを実現できます。

**4. ACID保証**

Read Modelの更新では、トランザクションが重要です：

```scala
// トランザクション内での複数テーブル更新
db.run(
  DBIO.seq(
    UserAccounts += userRecord,
    UserAccountHistory += historyRecord
  ).transactionally
)
```

#### なぜNoSQLではなくRDBMSなのか

**DynamoDB（クエリ側として）**：
- ❌ 複雑な検索クエリが困難
- ❌ インデックス設計の制約
- ✅ スケーラビリティは優れている

**MongoDB（ドキュメントDB）**：
- ✅ 柔軟なスキーマ
- ❌ トランザクション機能がRDBMSに劣る
- ❌ 複雑なJOINが苦手

**PostgreSQL（採用理由）**：
- ✅ 成熟したRDBMS
- ✅ 豊富なクエリ機能
- ✅ ACID保証
- ✅ 充実したエコシステム

---

## 3. 開発環境

### 3.1 LocalStack

#### LocalStackとは

**LocalStack**は、AWS サービスをローカル環境でエミュレートするツールです。

```yaml
# docker-compose.ymlでのLocalStack設定
localstack:
  image: localstack/localstack:latest
  environment:
    - SERVICES=dynamodb,lambda,logs
    - DOCKER_HOST=unix:///var/run/docker.sock
  ports:
    - "4566:4566"
```

#### 採用理由

**1. ローカル開発の高速化**

実際のAWSサービスを使用せず、ローカルで完結した開発が可能：

```bash
# LocalStackへのDynamoDBテーブル作成
awslocal dynamodb create-table \
  --table-name Journal \
  --attribute-definitions AttributeName=pkey,AttributeType=S \
  --key-schema AttributeName=pkey,KeyType=HASH \
  --billing-mode PAY_PER_REQUEST
```

**2. コスト削減**

開発中のAWS料金を気にせず、自由に実験できます。

**3. CI/CDパイプラインでの活用**

```yaml
# GitHub Actionsでの利用例
- name: Start LocalStack
  run: docker-compose up -d localstack
- name: Run E2E tests
  run: ./scripts/test-e2e.sh
```

#### LocalStackでサポートするサービス

本プロジェクトでは以下のサービスを使用：

- **DynamoDB**：イベントストア
- **Lambda**：Read Model Updater
- **CloudWatch Logs**：Lambda関数のログ

---

### 3.2 Docker Compose

#### 採用理由

**Docker Compose**により、複雑なインフラストラクチャを一元管理します。

```yaml
# docker-compose-common.yml（抜粋）
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: pcqrses
      POSTGRES_USER: pcqrses
      POSTGRES_PASSWORD: pcqrses
    ports:
      - "5432:5432"

  localstack:
    image: localstack/localstack:latest
    environment:
      - SERVICES=dynamodb,lambda,logs

  command-api:
    image: pekko-cqrs-es-example-command-api:latest
    depends_on:
      - postgres
      - localstack

  query-api:
    image: pekko-cqrs-es-example-query-api:latest
    depends_on:
      - postgres
```

**利点**：

1. **環境の再現性**：全ての開発者が同じ環境で作業可能
2. **ワンコマンド起動**：`docker-compose up -d`で全サービス起動
3. **依存関係の管理**：`depends_on`で起動順序を制御
4. **環境変数の一元管理**：`.env`ファイルで設定を管理

---

## 4. API & シリアライゼーション

### 4.1 GraphQL（Sangria）

#### 採用理由

**GraphQL**を採用することで、柔軟で型安全なAPIを提供します。

**1. 型安全なスキーマ定義**

```scala
// GraphQLスキーマの定義
val UserAccountType = ObjectType(
  "UserAccount",
  "ユーザーアカウント",
  fields[Unit, UserAccountView](
    Field("id", StringType, resolve = _.value.id),
    Field("firstName", StringType, resolve = _.value.firstName),
    Field("lastName", StringType, resolve = _.value.lastName),
    Field("email", StringType, resolve = _.value.email)
  )
)
```

**2. 柔軟なクエリ**

クライアントが必要なフィールドだけを取得できます：

```graphql
# 必要なフィールドのみ取得
query {
  getUserAccount(id: "123") {
    id
    firstName
    email
  }
}
```

**3. Validation型による検証**

Sangriaの`Validation`型により、複数の検証エラーを一度に返せます：

```scala
def validateCreateUserAccount(input: CreateUserAccountInput): Validation[CreateUserAccountInput] =
  (
    validateUserAccountName(input.firstName, input.lastName),
    validateEmail(input.email)
  ).mapN((_, _) => input)
```

#### REST APIとの比較

| 項目 | GraphQL | REST API |
|------|---------|----------|
| クエリの柔軟性 | 必要なフィールドのみ取得可 | 固定レスポンス |
| オーバーフェッチング | なし | あり（不要なデータも取得） |
| アンダーフェッチング | なし | 複数エンドポイントが必要 |
| 型安全性 | スキーマベース | OpenAPI等で補完可 |
| 学習コスト | やや高い | 低い |

GraphQLは、**柔軟性**と**型安全性**のバランスが優れています。

---

### 4.2 Protocol Buffers

#### 採用理由

**Protocol Buffers（protobuf）**を、イベントとスナップショットのシリアライゼーションに使用します。

**1. 効率的なバイナリフォーマット**

JSONと比較して、サイズとパフォーマンスが優れています：

```
JSON:     {"id": "123", "name": "John Doe", "email": "john@example.com"}  (68 bytes)
Protobuf: [Binary data]                                                   (25 bytes)
```

**2. スキーマの進化に対応**

フィールドの追加・削除が容易です：

```protobuf
// バージョン1
message UserAccountCreated_V1 {
  string user_account_id = 1;
  string name = 2;
  string email = 3;
}

// バージョン2（フィールド追加）
message UserAccountCreated_V2 {
  string user_account_id = 1;
  string first_name = 2;  // nameをfirst_nameに変更
  string last_name = 3;   // 新規追加
  string email = 4;
}
```

**3. ScalaPBによる型安全なコード生成**

```scala
// ScalaPBで生成されたケースクラス
case class UserAccountCreated_V1(
  userAccountId: String,
  name: String,
  email: String
) extends scalapb.GeneratedMessage
```

#### 代替技術との比較

| シリアライゼーション | サイズ | パフォーマンス | 可読性 | スキーマ進化 |
|---------------------|--------|--------------|--------|------------|
| **Protocol Buffers** | 小 | 高速 | 低 | 優れている |
| **JSON** | 大 | 遅い | 高 | 手動管理が必要 |
| **Avro** | 小 | 高速 | 低 | 優れている |
| **CBOR** | 中 | 中 | 低 | 手動管理が必要 |

Protobufは、**効率性**と**スキーマ管理**のバランスが最適です。

---

## 5. その他の重要なライブラリ

### 5.1 ZIO（エフェクトシステム）

非同期処理とエラーハンドリングをエレガントに扱います：

```scala
def getUserAccount(id: String): ZIO[DatabaseService, DatabaseError, Option[UserAccount]] =
  for
    record <- ZIO.serviceWithZIO[DatabaseService](_.findById(id))
    account <- ZIO.foreach(record)(r => ZIO.succeed(toUserAccount(r)))
  yield account
```

### 5.2 Flyway（データベースマイグレーション）

スキーマ変更をバージョン管理します：

```sql
-- V1__create_user_accounts_table.sql
CREATE TABLE user_accounts (
  id VARCHAR(255) PRIMARY KEY,
  first_name VARCHAR(255) NOT NULL,
  last_name VARCHAR(255) NOT NULL,
  email VARCHAR(255) NOT NULL UNIQUE,
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  deleted_at TIMESTAMP
);
```

### 5.3 ScalaTest（テストフレームワーク）

型安全で表現力豊かなテストを記述できます：

```scala
class UserAccountAggregateSpec extends AnyWordSpec with Matchers {
  "UserAccountAggregate" should {
    "create user account" in {
      // テストコード
    }
  }
}
```

---

## まとめ：技術スタックの全体像

本プロジェクトの技術スタックは、以下の設計思想に基づいて選定されました：

### 設計原則

1. **型安全性を最優先**
   - Scala 3の強力な型システム
   - Pekkoの型付きアクター
   - GraphQLのスキーマベースAPI

2. **スケーラビリティへの対応**
   - DynamoDBの自動スケーリング
   - Cluster Shardingによる分散処理
   - 水平スケーリング可能なアーキテクチャ

3. **開発者体験の重視**
   - LocalStackによる高速ローカル開発
   - Docker Composeによる環境構築の簡素化
   - GraphQL Playgroundによる対話的API開発

4. **本番環境での実績**
   - 全ての技術がエンタープライズ環境で実績あり
   - オープンソースで継続的な開発が保証されている

### 技術マップ

```
┌─────────────────────────────────────────────────────────┐
│                    アプリケーション層                      │
├─────────────────────────────────────────────────────────┤
│  言語: Scala 3.6.2                                      │
│  API: GraphQL (Sangria)                                 │
│  エフェクト: ZIO                                         │
└─────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────┐
│                    アクター/永続化層                      │
├─────────────────────────────────────────────────────────┤
│  フレームワーク: Apache Pekko 1.1.2                      │
│  永続化: Pekko Persistence                              │
│  シリアライゼーション: Protocol Buffers (ScalaPB)         │
└─────────────────────────────────────────────────────────┘
                           ↓
┌──────────────────────┬──────────────────────────────────┐
│   イベントストア      │        Read Model                │
├──────────────────────┼──────────────────────────────────┤
│  DynamoDB            │  PostgreSQL                      │
│  + Streams           │  + Slick                         │
│                      │  + Flyway                        │
└──────────────────────┴──────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────┐
│                    開発環境/インフラ                      │
├─────────────────────────────────────────────────────────┤
│  LocalStack (AWS emulation)                             │
│  Docker & Docker Compose                                │
│  Lambda (Read Model Updater)                            │
└─────────────────────────────────────────────────────────┘
```

---

## 次の章へ

次章では、実際に開発環境をセットアップします。Java、SBT、Dockerのインストールから、プロジェクトのビルド、LocalStackの起動まで、ステップバイステップで解説します。

👉 [第4章：開発環境のセットアップ](part1-04-setup.md)

---

## 参考資料

### 公式ドキュメント

- [Scala 3 Documentation](https://docs.scala-lang.org/scala3/)
- [Apache Pekko Documentation](https://pekko.apache.org/docs/pekko/current/)
- [GraphQL Specification](https://spec.graphql.org/)
- [Protocol Buffers](https://protobuf.dev/)
- [LocalStack Documentation](https://docs.localstack.cloud/)

### コミュニティリソース

- [Scala公式サイト](https://www.scala-lang.org/)
- [Sangria GraphQL](https://sangria-graphql.github.io/)
- [ScalaPB](https://scalapb.github.io/)
- [Slick Documentation](https://scala-slick.org/doc/)
