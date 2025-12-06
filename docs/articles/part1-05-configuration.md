# 第1部 環境構築編 - 第5章：設定管理の体系化

## 設定管理の重要性

複雑な分散システムでは、設定管理が成功の鍵を握ります。本プロジェクトでは、**階層化された設定ファイル**と**環境変数による柔軟な上書き**を組み合わせることで、開発環境から本番環境まで一貫した管理を実現しています。

### 設定管理の設計方針

1. **関心の分離**: 技術領域ごとに設定ファイルを分割
2. **環境の抽象化**: 環境変数で環境固有の値を注入
3. **デフォルト値**: 開発環境向けのデフォルト値を提供
4. **型安全性**: Typesafe Configによるコンパイル時検証

---

## 5.1 設定ファイルの階層化

本プロジェクトでは、設定を**4つの階層**に分割しています。

### 設定ファイルの構造

```
apps/command-api/src/main/resources/
├── application.conf      # エントリーポイント（includeのみ）
├── pcqrses.conf          # アプリケーション固有設定
├── pekko.conf            # Pekkoフレームワーク設定
└── j5ik2o.conf           # DynamoDB永続化プラグイン設定
```

#### 階層の読み込み順序

```
application.conf
 ├── include "pcqrses.conf"
 ├── include "pekko.conf"
 └── include "j5ik2o.conf"
```

Typesafe Configは、後から読み込まれた設定が先の設定を**上書き**します。

---

### 5.1.1 application.conf（エントリーポイント）

**役割**: 設定ファイル群のエントリーポイント

`apps/command-api/src/main/resources/application.conf`:

```hocon
include "pcqrses.conf"
include "pekko.conf"
include "j5ik2o.conf"
```

**設計理由**:

- **単一責任**: 設定の読み込みのみを担当
- **可視性**: どの設定ファイルが使用されているか一目瞭然
- **拡張性**: 新しい設定ファイルの追加が容易

---

### 5.1.2 pcqrses.conf（アプリケーション固有設定）

**役割**: このプロジェクト固有のビジネスロジック設定

`apps/command-api/src/main/resources/pcqrses.conf`:

```hocon
pcqrses {
  command-api {
    # アクター間通信のタイムアウト設定
    actor-timeout = 5s
    actor-timeout = ${?COMMAND_API_ACTOR_TIMEOUT}

    # HTTPサーバーの設定
    server {
      host = "127.0.0.1"
      host = ${?COMMAND_API_SERVER_HOST}
      port = 18080
      port = ${?COMMAND_API_SERVER_PORT}

      # シャットダウン時のタイムアウト
      shutdown-timeout = 10s
      shutdown-timeout = ${?COMMAND_API_SHUTDOWN_TIMEOUT}
    }

    # LoadBalancer環境での設定
    load-balancer {
      # デタッチ待機時間（開発環境: 短時間、本番環境: 30秒程度）
      detach-wait-duration = 3s
      detach-wait-duration = ${?COMMAND_API_LOADBALANCER_DETACH_WAIT_DURATION}

      # ヘルスチェックの猶予期間
      health-check-grace-period = 5s
      health-check-grace-period = ${?COMMAND_API_LOADBALANCER_HEALTH_GRACE_PERIOD}
    }
  }
}
```

#### 設定項目の詳細

**1. actor-timeout**:

- アクターへのメッセージ送信時のタイムアウト
- デフォルト: 5秒（開発環境向け）
- 本番環境では状況に応じて調整が必要

**2. server設定**:

- `host`: バインドするホスト（デフォルト: ループバック）
- `port`: HTTPサーバーのポート（デフォルト: 18080）
- `shutdown-timeout`: グレースフルシャットダウンの猶予時間

**3. load-balancer設定**:

- `detach-wait-duration`: ロードバランサーからのデタッチ待機時間
- `health-check-grace-period`: ヘルスチェック失敗の猶予期間

#### 環境変数による上書き

`${?VARIABLE}`パターンにより、環境変数が存在する場合のみ上書きします：

```bash
# 本番環境での起動例
export COMMAND_API_SERVER_HOST="0.0.0.0"
export COMMAND_API_SERVER_PORT="8080"
export COMMAND_API_ACTOR_TIMEOUT="10s"
export COMMAND_API_LOADBALANCER_DETACH_WAIT_DURATION="30s"

java -jar command-api.jar
```

---

### 5.1.3 pekko.conf（Pekkoフレームワーク設定）

**役割**: Apache Pekkoフレームワークの設定

`apps/command-api/src/main/resources/pekko.conf`（抜粋）:

```hocon
pekko {
  http {
    server {
      preview {
        enable-http2 = on
      }
      http2 {
        enabled = on
      }
    }
  }

  actor {
    provider = local

    cluster {
      enabled = false
      enabled = ${?PEKKO_CLUSTER_ENABLED}
    }

    warn-about-java-serializer-usage = on
    allow-java-serialization = off
  }

  test {
    single-expect-default = 5s
    filter-leeway = 5s
    timefactor = 1.0
  }

  coordinated-shutdown.run-by-actor-system-terminate = off
}

# Persistence設定
pekko.persistence.journal.plugin = "j5ik2o.dynamo-db-journal"
pekko.persistence.snapshot-store.plugin = "j5ik2o.dynamo-db-snapshot"
pekko.persistence.state.plugin = "j5ik2o.dynamo-db-state"
```

#### 重要な設定項目

**1. HTTP/2サポート**:
```hocon
http {
  server {
    preview.enable-http2 = on
    http2.enabled = on
  }
}
```

- gRPCやHTTP/2クライアントとの通信に必要

**2. Actor Provider**:
```hocon
actor {
  provider = local  # ローカルモード（クラスター無効）
}
```

- `local`: 単一ノード構成
- `cluster`: Pekko Cluster使用時は`cluster`に変更

**3. クラスター設定**:
```hocon
cluster {
  enabled = false
  enabled = ${?PEKKO_CLUSTER_ENABLED}
}
```

- 環境変数`PEKKO_CLUSTER_ENABLED=true`でクラスターモードに切り替え

**4. Javaシリアライゼーションの禁止**:
```hocon
warn-about-java-serializer-usage = on
allow-java-serialization = off
```

- セキュリティリスクを避けるため、Javaシリアライゼーションを無効化
- Protocol Buffersやその他の明示的なシリアライザのみ使用

**5. Persistence プラグインの指定**:
```hocon
pekko.persistence.journal.plugin = "j5ik2o.dynamo-db-journal"
pekko.persistence.snapshot-store.plugin = "j5ik2o.dynamo-db-snapshot"
pekko.persistence.state.plugin = "j5ik2o.dynamo-db-state"
```

- DynamoDBをイベントストアとして使用

#### デバッグ用InMemory設定（コメントアウト）

開発中のデバッグには、インメモリJournalも使用可能です：

```hocon
// デバッグ用: InMemory
// pekko {
//   persistence {
//     journal {
//       plugin = "pekko.persistence.journal.inmem"
//       inmem {
//         class = "org.apache.pekko.persistence.journal.inmem.InmemJournal"
//         plugin-dispatcher = "pekko.actor.default-dispatcher"
//       }
//     }
//     snapshot-store {
//       plugin = "pekko.persistence.snapshot-store.local"
//       local {
//         dir = "target/snapshot/$id"
//       }
//     }
//   }
// }
```

---

### 5.1.4 j5ik2o.conf（DynamoDB永続化プラグイン設定）

**役割**: j5ik2o/pekko-persistence-dynamodbプラグインの設定

`apps/command-api/src/main/resources/j5ik2o.conf`（抜粋）:

```hocon
j5ik2o.dynamo-db-journal {
  class = "com.github.j5ik2o.pekko.persistence.dynamodb.journal.DynamoDBJournal"
  table-name = "Journal"
  table-name = ${?J5IK2O_DYNAMO_DB_JOURNAL_TABLE_NAME}
  get-journal-rows-index-name = "GetJournalRowsIndex"
  get-journal-rows-index-name = ${?J5IK2O_DYNAMO_DB_JOURNAL_GET_JOURNAL_ROWS_INDEX_NAME}

  dynamo-db-client {
    access-key-id = "x"
    access-key-id = ${?J5IK2O_DYNAMO_DB_JOURNAL_DYNAMO_DB_CLIENT_ACCESS_KEY_ID}
    secret-access-key = "x"
    secret-access-key = ${?J5IK2O_DYNAMO_DB_JOURNAL_DYNAMO_DB_CLIENT_SECRET_ACCESS_KEY}
    endpoint = "http://localhost:8000/"
    endpoint = ${?J5IK2O_DYNAMO_DB_JOURNAL_DYNAMO_DB_CLIENT_ENDPOINT}
  }
}

j5ik2o.dynamo-db-snapshot {
  class = "com.github.j5ik2o.pekko.persistence.dynamodb.snapshot.DynamoDBSnapshotStore"
  table-name = "Snapshot"
  table-name = ${?J5IK2O_DYNAMO_DB_SNAPSHOT_TABLE_NAME}

  dynamo-db-client {
    access-key-id = "DUMMY"
    access-key-id = ${?J5IK2O_DYNAMO_DB_SNAPSHOT_DYNAMO_DB_CLIENT_ACCESS_KEY_ID}
    secret-access-key = "DUMMY"
    secret-access-key = ${?J5IK2O_DYNAMO_DB_SNAPSHOT_DYNAMO_DB_CLIENT_SECRET_ACCESS_KEY}
    endpoint = "http://localhost:8000/"
    endpoint = ${?J5IK2O_DYNAMO_DB_SNAPSHOT_DYNAMO_DB_CLIENT_ENDPOINT}
  }
}

j5ik2o.dynamo-db-state {
  class = "com.github.j5ik2o.pekko.persistence.dynamodb.state.DynamoDBDurableStateStoreProvider"
  table-name = "State"
  table-name = ${?J5IK2O_DYNAMO_DB_STATE_TABLE_NAME}

  dynamo-db-client {
    access-key-id = "x"
    access-key-id = ${?J5IK2O_DYNAMO_DB_STATE_DYNAMO_DB_CLIENT_ACCESS_KEY_ID}
    secret-access-key = "x"
    secret-access-key = ${?J5IK2O_DYNAMO_DB_STATE_DYNAMO_DB_CLIENT_SECRET_ACCESS_KEY}
    endpoint = "http://localhost:8000/"
    endpoint = ${?J5IK2O_DYNAMO_DB_STATE_DYNAMO_DB_CLIENT_ENDPOINT}
  }
}
```

#### 設定項目の詳細

**1. DynamoDB Journal（イベントストア）**:

- `table-name`: DynamoDBテーブル名（デフォルト: `Journal`）
- `get-journal-rows-index-name`: Global Secondary Index名
- `dynamo-db-client.endpoint`: DynamoDBエンドポイント（LocalStack: `http://localhost:8000/`）

**2. DynamoDB Snapshot（スナップショット）**:

- スナップショット専用のテーブル設定
- イベントリプレイのパフォーマンス最適化に使用

**3. DynamoDB State（Cluster Sharding用）**:

- Cluster Sharding使用時の状態管理
- 単一ノード構成では通常使用しない

---

## 5.2 環境変数による設定の上書き

### 5.2.1 環境変数パターンの理解

Typesafe Configは、`${?VARIABLE}`構文で環境変数の存在チェックと上書きを行います。

#### 構文の違い

| 構文 | 動作 |
|------|------|
| `${VARIABLE}` | 環境変数が**必須**。存在しない場合はエラー |
| `${?VARIABLE}` | 環境変数が**オプション**。存在する場合のみ上書き |

#### 使用例

```hocon
# デフォルト値を提供し、環境変数で上書き可能
server {
  host = "127.0.0.1"
  host = ${?COMMAND_API_SERVER_HOST}

  port = 18080
  port = ${?COMMAND_API_SERVER_PORT}
}
```

環境変数が設定されていない場合:

- `host` = `"127.0.0.1"`
- `port` = `18080`

環境変数が設定されている場合:
```bash
export COMMAND_API_SERVER_HOST="0.0.0.0"
export COMMAND_API_SERVER_PORT="8080"
```

- `host` = `"0.0.0.0"`（環境変数で上書き）
- `port` = `8080`（環境変数で上書き）

---

### 5.2.2 ローカル開発環境

**目的**: 開発者の手元で迅速に起動し、デバッグを容易にする

#### デフォルト設定（環境変数不要）

```hocon
pcqrses.command-api {
  actor-timeout = 5s
  server {
    host = "127.0.0.1"  # ローカルのみアクセス可能
    port = 18080
  }
}

j5ik2o.dynamo-db-journal.dynamo-db-client {
  endpoint = "http://localhost:8000/"  # LocalStack
}
```

#### 起動コマンド

```bash
# 環境変数なしで起動（デフォルト設定を使用）
sbt "commandApi/run"

# または Docker Composeで起動
./scripts/run-single.sh up
```

---

### 5.2.3 テスト環境

**目的**: CI/CDパイプラインやテスト専用環境での実行

#### テスト用の環境変数設定

```bash
# テスト環境用の設定
export COMMAND_API_SERVER_HOST="0.0.0.0"
export COMMAND_API_SERVER_PORT="8080"
export COMMAND_API_ACTOR_TIMEOUT="3s"  # テストでは短めに設定

# DynamoDBエンドポイント（CI環境のLocalStack）
export J5IK2O_DYNAMO_DB_JOURNAL_DYNAMO_DB_CLIENT_ENDPOINT="http://ci-localstack:4566"
export J5IK2O_DYNAMO_DB_SNAPSHOT_DYNAMO_DB_CLIENT_ENDPOINT="http://ci-localstack:4566"

# PostgreSQLエンドポイント（CI環境）
export QUERY_API_DB_URL="jdbc:postgresql://ci-postgres:5432/test_db"
```

#### GitHub Actions設定例

```yaml
# .github/workflows/test.yml
name: Test
on: [push, pull_request]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '21'

      - name: Start LocalStack and PostgreSQL
        run: docker compose -f docker-compose-common.yml up -d

      - name: Run tests
        env:
          J5IK2O_DYNAMO_DB_JOURNAL_DYNAMO_DB_CLIENT_ENDPOINT: http://localhost:4566
          QUERY_API_DB_URL: jdbc:postgresql://localhost:5432/p-cqrs-es_development
        run: sbt test
```

---

### 5.2.4 本番環境への対応

**目的**: 本番環境で安全にスケールし、監視・運用可能にする

#### 本番環境用の環境変数例

```bash
# アプリケーション設定
export COMMAND_API_SERVER_HOST="0.0.0.0"  # 全てのインターフェースでリッスン
export COMMAND_API_SERVER_PORT="8080"
export COMMAND_API_ACTOR_TIMEOUT="10s"  # 本番では余裕を持たせる
export COMMAND_API_SHUTDOWN_TIMEOUT="30s"  # グレースフルシャットダウン

# ロードバランサー設定
export COMMAND_API_LOADBALANCER_DETACH_WAIT_DURATION="30s"  # ALBのderegistration delay
export COMMAND_API_LOADBALANCER_HEALTH_GRACE_PERIOD="10s"

# Pekko Cluster有効化
export PEKKO_CLUSTER_ENABLED="true"

# 実際のDynamoDB（AWS）
export J5IK2O_DYNAMO_DB_JOURNAL_DYNAMO_DB_CLIENT_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID}"
export J5IK2O_DYNAMO_DB_JOURNAL_DYNAMO_DB_CLIENT_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY}"
export J5IK2O_DYNAMO_DB_JOURNAL_DYNAMO_DB_CLIENT_ENDPOINT="https://dynamodb.ap-northeast-1.amazonaws.com"

export J5IK2O_DYNAMO_DB_SNAPSHOT_DYNAMO_DB_CLIENT_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID}"
export J5IK2O_DYNAMO_DB_SNAPSHOT_DYNAMO_DB_CLIENT_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY}"
export J5IK2O_DYNAMO_DB_SNAPSHOT_DYNAMO_DB_CLIENT_ENDPOINT="https://dynamodb.ap-northeast-1.amazonaws.com"

# PostgreSQL（RDS）
export QUERY_API_DB_URL="jdbc:postgresql://prod-rds.xxxxx.ap-northeast-1.rds.amazonaws.com:5432/pcqrses"
export QUERY_API_DB_USER="app_user"
export QUERY_API_DB_PASSWORD="${DB_PASSWORD}"  # Secrets Managerから注入
```

#### Kubernetes ConfigMap & Secret例

```yaml
# configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: command-api-config
data:
  COMMAND_API_SERVER_HOST: "0.0.0.0"
  COMMAND_API_SERVER_PORT: "8080"
  COMMAND_API_ACTOR_TIMEOUT: "10s"
  PEKKO_CLUSTER_ENABLED: "true"
  J5IK2O_DYNAMO_DB_JOURNAL_DYNAMO_DB_CLIENT_ENDPOINT: "https://dynamodb.ap-northeast-1.amazonaws.com"

---
# secret.yaml
apiVersion: v1
kind: Secret
metadata:
  name: command-api-secret
type: Opaque
data:
  AWS_ACCESS_KEY_ID: <base64-encoded-value>
  AWS_SECRET_ACCESS_KEY: <base64-encoded-value>
```

---

## 5.3 シリアライゼーション設定

### 5.3.1 Protocol Buffersの設定

Protocol Buffersを使用したイベントシリアライゼーションの設定は、`pekko.conf`で管理されます。

#### シリアライザの登録

`modules/command/interface-adapter/src/main/resources/serialization.conf`（例）:

```hocon
pekko.actor {
  serializers {
    # Protocol Buffers シリアライザ
    proto = "org.apache.pekko.serialization.SerializationExtension$"

    # カスタムシリアライザ
    user-account-event-serializer = "io.github.j5ik2o.pcqrses.serialization.UserAccountEventSerializer"
    user-account-snapshot-serializer = "io.github.j5ik2o.pcqrses.serialization.UserAccountSnapshotSerializer"
  }

  serialization-bindings {
    # UserAccountEvent → カスタムシリアライザ
    "io.github.j5ik2o.pcqrses.domain.UserAccountEvent" = user-account-event-serializer

    # UserAccountSnapshot → カスタムシリアライザ
    "io.github.j5ik2o.pcqrses.domain.UserAccountSnapshot" = user-account-snapshot-serializer
  }
}
```

---

### 5.3.2 カスタムシリアライザの登録

#### UserAccountEventSerializerの実装

`modules/command/interface-adapter-event-serializer/src/main/scala/UserAccountEventSerializer.scala`:

```scala
class UserAccountEventSerializer extends SerializerWithStringManifest {
  override def identifier: Int = 1001  // 一意のシリアライザID

  override def manifest(o: AnyRef): String = o match {
    case _: UserAccountEvent.Created_V1 => "Created_V1"
    case _: UserAccountEvent.Renamed_V1 => "Renamed_V1"
    case _: UserAccountEvent.Deleted_V1 => "Deleted_V1"
  }

  override def toBinary(o: AnyRef): Array[Byte] = o match {
    case event: UserAccountEvent.Created_V1 =>
      UserAccountEventProto.Created_V1(
        userAccountId = event.userAccountId.value,
        firstName = event.firstName,
        lastName = event.lastName,
        email = event.email,
        occurredAt = Some(Timestamp.fromJavaInstant(event.occurredAt))
      ).toByteArray

    case event: UserAccountEvent.Renamed_V1 =>
      UserAccountEventProto.Renamed_V1(
        userAccountId = event.userAccountId.value,
        firstName = event.firstName,
        lastName = event.lastName,
        occurredAt = Some(Timestamp.fromJavaInstant(event.occurredAt))
      ).toByteArray

    case event: UserAccountEvent.Deleted_V1 =>
      UserAccountEventProto.Deleted_V1(
        userAccountId = event.userAccountId.value,
        occurredAt = Some(Timestamp.fromJavaInstant(event.occurredAt))
      ).toByteArray
  }

  override def fromBinary(bytes: Array[Byte], manifest: String): AnyRef = manifest match {
    case "Created_V1" =>
      val proto = UserAccountEventProto.Created_V1.parseFrom(bytes)
      UserAccountEvent.Created_V1(
        userAccountId = UserAccountId(proto.userAccountId),
        firstName = proto.firstName,
        lastName = proto.lastName,
        email = proto.email,
        occurredAt = proto.occurredAt.get.asJavaInstant
      )

    case "Renamed_V1" =>
      val proto = UserAccountEventProto.Renamed_V1.parseFrom(bytes)
      UserAccountEvent.Renamed_V1(
        userAccountId = UserAccountId(proto.userAccountId),
        firstName = proto.firstName,
        lastName = proto.lastName,
        occurredAt = proto.occurredAt.get.asJavaInstant
      )

    case "Deleted_V1" =>
      val proto = UserAccountEventProto.Deleted_V1.parseFrom(bytes)
      UserAccountEvent.Deleted_V1(
        userAccountId = UserAccountId(proto.userAccountId),
        occurredAt = proto.occurredAt.get.asJavaInstant
      )
  }
}
```

#### シリアライザIDの管理

シリアライザIDは**一意**である必要があります：

| シリアライザ | ID | 用途 |
|------------|-----|------|
| `UserAccountEventSerializer` | 1001 | UserAccountイベント |
| `UserAccountSnapshotSerializer` | 1002 | UserAccountスナップショット |

---

### 5.3.3 CBORシリアライゼーション

内部メッセージやコマンドには、**CBOR（Concise Binary Object Representation）**を使用します。

#### CborSerializableマーカートレイト

`modules/infrastructure/src/main/scala/serialization/CborSerializable.scala`:

```scala
trait CborSerializable
```

#### コマンドへの適用

```scala
sealed trait UserAccountCommand extends CborSerializable

case class CreateUserAccount(
  id: UserAccountId,
  firstName: String,
  lastName: String,
  email: String
) extends UserAccountCommand
```

#### 自動シリアライゼーション設定

`pekko.conf`:

```hocon
pekko.actor {
  serialization-bindings {
    "io.github.j5ik2o.pcqrses.serialization.CborSerializable" = jackson-cbor
  }
}
```

Pekkoが自動的にJackson-CBORシリアライザを適用します。

---

## まとめ

本章では、設定管理の体系化について学びました：

### 達成したこと

1. ✅ **階層化された設定ファイル**: 関心の分離による保守性向上
   - `application.conf`: エントリーポイント
   - `pcqrses.conf`: アプリケーション固有設定
   - `pekko.conf`: フレームワーク設定
   - `j5ik2o.conf`: プラグイン設定

2. ✅ **環境変数による柔軟な上書き**: ローカル→テスト→本番への対応
   - `${?VARIABLE}`パターンによるオプショナルな上書き
   - デフォルト値で開発環境をサポート

3. ✅ **シリアライゼーション戦略**: Protocol BuffersとCBORの使い分け
   - イベント: Protocol Buffers（効率的、スキーマ進化対応）
   - コマンド: CBOR（型安全、軽量）

### 設定管理のベストプラクティス

- **環境ごとの分離**: 開発/テスト/本番で異なる設定を使用
- **デフォルト値の提供**: 開発者が環境変数なしで起動できる
- **型安全性**: Typesafe Configで設定を検証
- **セキュリティ**: パスワードや認証情報は環境変数またはSecrets Managerから注入

---

## 次の章へ

設定ファイルの準備が整いました。次章では、実際にシステムを起動し、ヘルスチェックを行います。

👉 [第6章：初回起動とヘルスチェック](part1-06-startup.md)

---

## 参考資料

- [Typesafe Config Documentation](https://github.com/lightbend/config)
- [Pekko Configuration](https://pekko.apache.org/docs/pekko/current/general/configuration.html)
- [Protocol Buffers Language Guide](https://protobuf.dev/programming-guides/proto3/)
- [Jackson CBOR Documentation](https://github.com/FasterXML/jackson-dataformats-binary)
- [The Twelve-Factor App: Config](https://12factor.net/config)
