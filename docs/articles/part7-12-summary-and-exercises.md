# 第7部第12章：まとめと実践演習

## 本章の目的

第7部「共用データ管理サービス」の総まとめとして、これまでに学んだ知識を統合し、実践的な演習を通じてマスターデータ管理サービスの理解を深めます。本章では、4つの実践演習と今後の発展的なトピックを提供します。

## 12.1 学んだこと

### 12.1.1 マスターデータのイベントソーシング

第7部では、イベントソーシングパターンをマスターデータ管理に適用する方法を学びました。

#### 全変更履歴の追跡

すべてのマスターデータ変更がイベントとして記録されるため、完全な監査証跡が確保されます。

```scala
// イベントストアに記録される変更履歴
ProductCreated(
  productId = "PROD-0001",
  productCode = "P-001",
  productName = "有機玄米 5kg",
  listPrice = Money(3500),
  occurredAt = "2024-01-01T00:00:00Z"
)

ProductPriceChanged(
  productId = "PROD-0001",
  oldPrice = Money(3500),
  newPrice = Money(3800),
  validFrom = "2024-06-01",
  occurredAt = "2024-05-15T10:30:00Z"
)

ProductPriceChanged(
  productId = "PROD-0001",
  oldPrice = Money(3800),
  newPrice = Money(4000),
  validFrom = "2024-12-01",
  occurredAt = "2024-11-20T14:45:00Z"
)
```

**メリット**:
- コンプライアンス要件への対応（誰が、いつ、何を変更したか）
- 過去の任意時点への状態復元
- 変更理由の追跡（イベントに理由を含める）
- データの削除が不要（廃止フラグで対応）

#### 時点復元機能

過去の任意の時点のマスターデータ状態を復元できます。

```scala
// 2024年6月1日時点の商品価格を復元
val targetDate = LocalDate.of(2024, 6, 1)

val events = eventStore.readEvents("Product-PROD-0001")
  .filter(_.occurredAt <= targetDate.atStartOfDay().toInstant(ZoneOffset.UTC))

val productState = events.foldLeft(EmptyState) { (state, event) =>
  ProductActor.eventHandler(state, event)
}

// 結果: listPrice = 3800円（2024年6月1日時点）
```

**ユースケース**:
- 会計監査での過去データ提示
- 価格変更の影響分析
- データ移行時の検証
- 障害復旧（特定時点への巻き戻し）

#### 監査証跡の確保

すべてのイベントにメタデータ（ユーザーID、タイムスタンプ、変更理由）を付与することで、完全な監査証跡を実現します。

```scala
final case class ProductPriceChanged(
  eventId: UUID,
  productId: ProductId,
  oldPrice: Money,
  newPrice: Money,
  validFrom: LocalDate,
  reason: String,              // 変更理由
  changedBy: UserId,           // 変更者
  occurredAt: Instant,         // 変更日時
  approvedBy: Option[UserId],  // 承認者（承認フロー経由の場合）
  approvalId: Option[String]   // 承認ID
) extends ProductEvent
```

### 12.1.2 イベント駆動マスターデータ同期

#### Single Source of Truth

マスターデータは共用データ管理サービスが一元管理し、他のBounded Contextは参照データとして保持します。

```
┌──────────────────────────────────┐
│ 共用データ管理サービス             │
│ （Single Source of Truth）        │
│                                  │
│ ┌──────────┐  ┌─────────────┐  │
│ │商品マスター│  │勘定科目マスター│  │
│ └─────┬────┘  └──────┬──────┘  │
└───────┼──────────────┼──────────┘
        │              │
        │ イベント発行  │
        ▼              ▼
┌────────────────────────────────────┐
│    イベントバス（Pekko Pub/Sub）    │
└────┬─────────┬─────────┬───────────┘
     │         │         │
     ▼         ▼         ▼
┌─────────┐ ┌─────────┐ ┌─────────┐
│在庫管理 │ │受注管理 │ │会計     │
│参照データ│ │参照データ│ │参照データ│
└─────────┘ └─────────┘ └─────────┘
```

**設計原則**:
- マスターデータの更新は共用データ管理サービスのみが行う
- 他のサービスは参照データとして非正規化コピーを保持
- イベント駆動で同期（結果整合性）

#### 結果整合性による疎結合

各Bounded Contextは非同期にマスター変更を受信するため、疎結合が実現されます。

```scala
// 在庫管理サービス：商品参照データ更新
object ProductReadModelUpdater {
  def apply(): Behavior[ProductEvent] = {
    Behaviors.receiveMessage {
      case ProductPriceChanged(_, productId, _, newPrice, _, _, _, _) =>
        // 在庫管理サービスの商品参照テーブルを非同期に更新
        productReferenceRepository.updatePrice(productId, newPrice)
        Behaviors.same
    }
  }
}
```

**メリット**:
- マスターデータサービスと参照側サービスが疎結合
- サービス間の依存関係が最小化
- 高可用性（一方のサービス障害が他方に影響しない）
- スケーラビリティ（各サービスが独立してスケール）

**トレードオフ**:
- 即座には同期されない（数秒〜数分の遅延）
- 結果整合性を許容できるビジネス要件が必要

#### Materialized Viewによる参照最適化

各サービスに最適化された参照データを保持することで、高速なクエリを実現します。

```sql
-- 在庫管理サービス用の商品参照テーブル（必要最小限のカラム）
CREATE TABLE inventory_management.product_reference (
  product_id UUID PRIMARY KEY,
  product_code VARCHAR(50) NOT NULL,
  product_name VARCHAR(200) NOT NULL,
  current_price DECIMAL(15,2) NOT NULL,
  is_active BOOLEAN NOT NULL,
  last_updated_at TIMESTAMP NOT NULL
);

-- 受注管理サービス用の商品参照テーブル（価格情報を重視）
CREATE TABLE order_management.product_reference (
  product_id UUID PRIMARY KEY,
  product_code VARCHAR(50) NOT NULL,
  product_name VARCHAR(200) NOT NULL,
  standard_cost DECIMAL(15,2) NOT NULL,
  current_price DECIMAL(15,2) NOT NULL,
  is_orderable BOOLEAN NOT NULL,
  last_updated_at TIMESTAMP NOT NULL
);
```

### 12.1.3 データガバナンス

#### 変更承認ワークフロー

重要なマスターデータ変更にはSagaパターンによる承認フローを適用します。

```scala
// 価格変更承認Saga
PriceChangeApprovalSaga:
  1. RequestPriceChange（価格変更申請）
     → 変更申請テーブルに保存
  2. NotifyApprover（承認者に通知）
     → メール送信
  3. WaitForApproval（承認待ち）
     → タイムアウト設定（72時間）
  4a. ApproveAndApplyChange（承認して変更適用）
     → ProductActorに価格変更コマンド送信
  4b. RejectChange（却下）
     → 申請者に却下通知
```

**適用基準**:
- 商品価格変更（一定金額以上）
- 勘定科目の追加・変更
- 組織マスターの変更

#### データ品質管理

バリデーション、重複チェック、データクレンジングにより高品質なマスターデータを維持します。

```scala
// 3段階のバリデーション
1. 必須項目チェック
   → productCode, productName, listPrice

2. 形式チェック
   → 商品コード: 英数字とハイフンのみ
   → 価格: 0以上

3. ビジネスルールチェック
   → 定価 >= 標準原価
   → 有効期間の重複チェック
```

#### アクセス制御

GraphQL APIによる柔軟なアクセス制御を実現します。

```graphql
# 参照のみ可能（一般ユーザー）
query {
  products(filter: {status: ACTIVE}) {
    edges {
      node {
        productCode
        productName
        listPrice
      }
    }
  }
}

# 更新可能（マスター管理者）
mutation {
  updateProductInfo(input: {
    productId: "PROD-0001"
    productName: "有機玄米 5kg（新パッケージ）"
  }) {
    product { productName }
    success
  }
}
```

### 12.1.4 GraphQL API

#### 柔軟なクエリ

クライアントが必要なデータのみを取得できます。

```graphql
# 商品コードと名前のみ取得
query {
  products(page: 1, pageSize: 20) {
    edges {
      node {
        productCode
        productName
      }
    }
  }
}

# 商品の詳細情報と価格一覧も取得
query {
  products(page: 1, pageSize: 20) {
    edges {
      node {
        productCode
        productName
        listPrice
        prices {
          priceType
          unitPrice
          validFrom
          validTo
        }
      }
    }
  }
}
```

#### DataLoaderによるN+1問題解決

バッチ処理により効率的にデータを取得します。

```scala
// N+1問題が発生する例（悪い例）
products.foreach { product =>
  val prices = productPriceRepository.findByProductId(product.id) // N回のクエリ
}

// DataLoaderによる解決（良い例）
val productIds = products.map(_.id)
val pricesMap = productPriceLoader.loadMany(productIds) // 1回のバッチクエリ
```

#### ページングとフィルタリング

Relay仕様準拠のカーソルベースページングを実装します。

```graphql
query {
  products(
    filter: {
      categoryCode: "RICE"
      status: ACTIVE
      validAt: "2024-12-01"
    }
    first: 10
    after: "cursor123"
  ) {
    edges {
      node { productName }
      cursor
    }
    pageInfo {
      hasNextPage
      endCursor
    }
    totalCount
  }
}
```

## 12.2 実践演習

### 演習1: 商品価格変更ワークフロー

#### 目的
承認フロー付きの商品価格変更を実装し、他サービスへの伝播を確認します。

#### 手順

**ステップ1: 価格変更申請の作成**

```bash
# GraphQL APIで価格変更申請
curl -X POST http://localhost:8080/graphql \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation { changeProductPrice(input: { productId: \"PROD-0001\", newPrice: 3800, effectiveFrom: \"2024-12-15\", reason: \"原材料費の上昇により価格改定\" }) { requiresApproval changeRequest { id status } success message } }"
  }'
```

**期待される結果**:
```json
{
  "data": {
    "changeProductPrice": {
      "requiresApproval": true,
      "changeRequest": {
        "id": "CR-20241201-001",
        "status": "PENDING"
      },
      "success": true,
      "message": "価格変更申請を作成しました。承認が必要です。"
    }
  }
}
```

**ステップ2: 承認者による承認**

```bash
# 承認者としてログイン後、承認
curl -X POST http://localhost:8080/graphql \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer APPROVER_TOKEN" \
  -d '{
    "query": "mutation { approveChangeRequest(requestId: \"CR-20241201-001\") { id status approver { userName } approvedAt } }"
  }'
```

**期待される結果**:
```json
{
  "data": {
    "approveChangeRequest": {
      "id": "CR-20241201-001",
      "status": "APPROVED",
      "approver": {
        "userName": "佐藤花子"
      },
      "approvedAt": "2024-12-01T15:30:00Z"
    }
  }
}
```

**ステップ3: 承認後の価格反映確認**

```bash
# 商品価格が更新されたことを確認
curl -X POST http://localhost:8080/graphql \
  -H "Content-Type: application/json" \
  -d '{
    "query": "{ productByCode(productCode: \"P-001\") { productCode productName listPrice prices { priceType unitPrice validFrom validTo } } }"
  }'
```

**期待される結果**:
```json
{
  "data": {
    "productByCode": {
      "productCode": "P-001",
      "productName": "有機玄米 5kg",
      "listPrice": 3800,
      "prices": [
        {
          "priceType": "STANDARD",
          "unitPrice": 3800,
          "validFrom": "2024-12-15",
          "validTo": null
        }
      ]
    }
  }
}
```

**ステップ4: 在庫管理での新価格の適用確認**

```sql
-- 在庫管理サービスの商品参照テーブルを確認
SELECT product_code, product_name, current_price, last_updated_at
FROM inventory_management.product_reference
WHERE product_code = 'P-001';

-- 期待される結果:
-- product_code | product_name    | current_price | last_updated_at
-- P-001        | 有機玄米 5kg    | 3800.00       | 2024-12-01 15:30:05
```

**検証ポイント**:
- 承認前は価格が変更されていないこと
- 承認後に即座に価格が反映されること
- イベントが在庫管理サービスに伝播していること
- 同期遅延が許容範囲内（5秒以内）であること

### 演習2: 勘定科目の階層構造管理

#### 目的
勘定科目の親子関係を管理し、階層構造の整合性を検証します。

#### 手順

**ステップ1: 親勘定科目の作成**

```graphql
mutation {
  createAccountSubject(input: {
    accountCode: "1000"
    accountName: "流動資産"
    accountType: ASSET
  }) {
    accountSubject {
      id
      accountCode
      accountName
      accountType
      level
    }
    success
  }
}
```

**期待される結果**:
```json
{
  "data": {
    "createAccountSubject": {
      "accountSubject": {
        "id": "AS-0001",
        "accountCode": "1000",
        "accountName": "流動資産",
        "accountType": "ASSET",
        "level": 1
      },
      "success": true
    }
  }
}
```

**ステップ2: 子勘定科目の作成**

```graphql
mutation {
  createAccountSubject(input: {
    accountCode: "1010"
    accountName: "現金"
    accountType: ASSET
    parentAccountSubjectId: "AS-0001"
  }) {
    accountSubject {
      id
      accountCode
      accountName
      parentAccountSubjectId
      level
    }
    success
  }
}
```

**期待される結果**:
```json
{
  "data": {
    "createAccountSubject": {
      "accountSubject": {
        "id": "AS-0002",
        "accountCode": "1010",
        "accountName": "現金",
        "parentAccountSubjectId": "AS-0001",
        "level": 2
      },
      "success": true
    }
  }
}
```

**ステップ3: 階層構造の整合性検証（異なる種別の親を指定してエラー確認）**

```graphql
mutation {
  createAccountSubject(input: {
    accountCode: "4000"
    accountName: "売上高"
    accountType: REVENUE
    parentAccountSubjectId: "AS-0001"  # 資産の子に収益は不可
  }) {
    accountSubject { accountCode }
    success
    message
  }
}
```

**期待される結果**:
```json
{
  "data": {
    "createAccountSubject": {
      "accountSubject": null,
      "success": false,
      "message": "親勘定科目の種別（ASSET）と子勘定科目の種別（REVENUE）は一致する必要があります"
    }
  }
}
```

**ステップ4: 勘定科目階層ツリーの取得**

```graphql
query {
  accountSubjectTree(accountType: ASSET) {
    accountCode
    accountName
    level
    childAccountSubjects {
      accountCode
      accountName
      level
      childAccountSubjects {
        accountCode
        accountName
        level
      }
    }
  }
}
```

**期待される結果**:
```json
{
  "data": {
    "accountSubjectTree": [
      {
        "accountCode": "1000",
        "accountName": "流動資産",
        "level": 1,
        "childAccountSubjects": [
          {
            "accountCode": "1010",
            "accountName": "現金",
            "level": 2,
            "childAccountSubjects": []
          }
        ]
      }
    ]
  }
}
```

**ステップ5: 会計サービスでの勘定科目階層の参照**

```sql
-- 会計サービスの勘定科目参照テーブルを確認
SELECT account_code, account_name, account_type, level
FROM accounting.account_subject_reference
WHERE account_type = 'Asset'
ORDER BY account_code;

-- 期待される結果:
-- account_code | account_name | account_type | level
-- 1000         | 流動資産     | Asset        | 1
-- 1010         | 現金         | Asset        | 2
```

**検証ポイント**:
- 親子関係が正しく設定されること
- 階層レベルが自動計算されること
- 異なる種別の親子関係はエラーになること
- 会計サービスに階層構造が同期されること

### 演習3: マスターデータ同期の監視

#### 目的
マスター変更イベントの伝播を監視し、同期遅延を測定します。

#### 手順

**ステップ1: マスター変更イベントの発行**

```bash
# 商品情報を更新
MASTER_UPDATE_TIME=$(date +%s)

curl -X POST http://localhost:8080/graphql \
  -H "Content-Type: application/json" \
  -d '{
    "query": "mutation { updateProductInfo(input: { productId: \"PROD-0001\", productName: \"有機玄米 5kg（新パッケージ）\" }) { product { productName updatedAt } success } }"
  }'
```

**ステップ2: 各Bounded Contextでのイベント受信確認**

```bash
# 在庫管理サービスのログを確認
tail -f logs/inventory-management.log | grep "商品情報更新イベント受信"

# 期待されるログ:
# 2024-12-01 15:45:10 INFO  ProductReadModelUpdater - 商品情報更新イベント受信: PROD-0001
# 2024-12-01 15:45:10 INFO  ProductReadModelUpdater - 商品参照データ更新完了: P-001
```

**ステップ3: 同期遅延の測定**

```bash
# 在庫管理サービスの商品参照データを確認
REFERENCE_UPDATE_TIME=$(psql -h localhost -U postgres -d inventory -t -c \
  "SELECT EXTRACT(EPOCH FROM last_updated_at) FROM inventory_management.product_reference WHERE product_code = 'P-001'")

# 同期遅延を計算（秒）
SYNC_DELAY=$((REFERENCE_UPDATE_TIME - MASTER_UPDATE_TIME))

echo "同期遅延: ${SYNC_DELAY}秒"

# 期待される結果: 同期遅延: 2秒
```

**ステップ4: Prometheusメトリクスの確認**

```bash
# イベント処理遅延メトリクスを確認
curl http://localhost:9090/api/v1/query \
  --data-urlencode 'query=masterdata_event_processing_lag_seconds{bounded_context="inventory-management",aggregate_type="Product"}' \
  | jq '.data.result[0].value[1]'

# 期待される結果: "2.5" (2.5秒)
```

**ステップ5: Grafanaダッシュボードでの可視化**

ブラウザで `http://localhost:3000` にアクセスし、「マスターデータ管理 - 同期状況」ダッシュボードを開きます。

**確認項目**:
- イベント処理遅延時間グラフ
- 未処理イベント件数
- イベント処理成功率
- 参照データ最終更新時刻

**検証ポイント**:
- イベントが正常に発行されること
- 各Bounded Contextでイベントが受信されること
- 同期遅延が5秒以内であること
- Prometheusメトリクスが正しく記録されること
- Grafanaダッシュボードで可視化されること

### 演習4: 過去時点のマスターデータ復元

#### 目的
イベントリプレイにより過去の商品価格を復元し、会計監査用のデータとして提示します。

#### 手順

**ステップ1: 商品価格の履歴確認**

```sql
-- DynamoDBイベントストアから価格変更イベントを取得
SELECT event_type, event_data, occurred_at
FROM event_store.events
WHERE persistence_id = 'Product-PROD-0001'
  AND event_type LIKE '%PriceChanged%'
ORDER BY sequence_nr;

-- 期待される結果:
-- event_type         | event_data                                  | occurred_at
-- ProductCreated     | {"listPrice": 3500, ...}                    | 2024-01-01 00:00:00
-- ProductPriceChanged| {"oldPrice": 3500, "newPrice": 3800, ...}   | 2024-06-01 10:00:00
-- ProductPriceChanged| {"oldPrice": 3800, "newPrice": 4000, ...}   | 2024-12-01 15:00:00
```

**ステップ2: 指定日時のイベントリプレイ（2024年6月1日時点）**

```scala
import org.apache.pekko.persistence.query.PersistenceQuery
import org.apache.pekko.persistence.query.journal.dynamodb.scaladsl.DynamoDBReadJournal
import java.time.LocalDate

object ProductStateRestoration extends App {

  val targetDate = LocalDate.of(2024, 6, 1)
  val productId = "PROD-0001"

  val readJournal = PersistenceQuery(system)
    .readJournalFor[DynamoDBReadJournal](DynamoDBReadJournal.Identifier)

  // 指定日時までのイベントをリプレイ
  val restoredStateFuture = readJournal
    .eventsByPersistenceId(s"Product-$productId", 0, Long.MaxValue)
    .filter { envelope =>
      val event = envelope.event.asInstanceOf[ProductEvent]
      event.occurredAt.isBefore(targetDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC))
    }
    .runFold(ProductActor.EmptyState: ProductActor.State) { (state, envelope) =>
      ProductActor.eventHandler(state, envelope.event.asInstanceOf[ProductEvent])
    }

  restoredStateFuture.onComplete {
    case Success(ProductActor.ProductState(product)) =>
      println(s"2024年6月1日時点の商品価格: ${product.listPrice.amount}円")
      // 期待される出力: 2024年6月1日時点の商品価格: 3800円

    case _ =>
      println("商品が存在しませんでした")
  }
}
```

**ステップ3: 過去の商品価格をGraphQL APIで取得**

```graphql
query {
  productPriceAt(
    productId: "PROD-0001"
    date: "2024-06-01"
  ) {
    priceType
    unitPrice
    validFrom
    validTo
  }
}
```

**期待される結果**:
```json
{
  "data": {
    "productPriceAt": {
      "priceType": "STANDARD",
      "unitPrice": 3800,
      "validFrom": "2024-06-01",
      "validTo": null
    }
  }
}
```

**ステップ4: 会計監査用レポートの生成**

```scala
object AuditReportGenerator {

  def generatePriceHistoryReport(productId: String, fromDate: LocalDate, toDate: LocalDate): PriceHistoryReport = {
    val events = eventStore.readEvents(s"Product-$productId")
      .filter { event =>
        event.occurredAt.isAfter(fromDate.atStartOfDay().toInstant(ZoneOffset.UTC)) &&
        event.occurredAt.isBefore(toDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC))
      }
      .collect { case e: ProductPriceChanged => e }

    PriceHistoryReport(
      productId = productId,
      fromDate = fromDate,
      toDate = toDate,
      priceChanges = events.map { e =>
        PriceChange(
          changedAt = e.occurredAt,
          changedBy = e.changedBy,
          oldPrice = e.oldPrice.amount,
          newPrice = e.newPrice.amount,
          reason = e.reason,
          approvedBy = e.approvedBy
        )
      }
    )
  }
}

// レポート生成
val report = AuditReportGenerator.generatePriceHistoryReport(
  productId = "PROD-0001",
  fromDate = LocalDate.of(2024, 1, 1),
  toDate = LocalDate.of(2024, 12, 31)
)

// CSV出力
report.toCsv()
// 期待される出力:
// 変更日時,変更者,旧価格,新価格,変更理由,承認者
// 2024-06-01 10:00:00,田中太郎,3500,3800,原材料費の上昇,佐藤花子
// 2024-12-01 15:00:00,鈴木一郎,3800,4000,市場価格の動向,佐藤花子
```

**検証ポイント**:
- 過去の任意時点の価格を復元できること
- イベントリプレイが正常に動作すること
- 変更履歴が完全に記録されていること
- 会計監査用レポートが生成できること

## 12.3 次のステップ

### 12.3.1 より高度なマスターデータ管理

#### マスターデータのバージョン分岐（What-if分析）

本番環境とは別に、仮想的な価格変更シナリオを検証する機能を実装します。

```scala
// シナリオブランチの作成
val scenarioId = ScenarioId.generate()

scenarioManager.createScenario(
  scenarioId = scenarioId,
  baseDate = LocalDate.now(),
  description = "2025年価格改定シミュレーション"
)

// シナリオ内で価格を変更
scenarioManager.applyPriceChange(
  scenarioId = scenarioId,
  productId = "PROD-0001",
  newPrice = Money(4200),
  effectiveFrom = LocalDate.of(2025, 1, 1)
)

// シナリオでの売上影響を分析
val impact = scenarioAnalyzer.analyzeRevenueImpact(scenarioId)
// 結果: 年間売上が5%増加する見込み
```

**ユースケース**:
- 価格改定の影響シミュレーション
- 組織変更のインパクト分析
- M&A時の統合シミュレーション

#### グローバルマスター（多言語、多通貨）

多国籍企業向けに、商品名の多言語対応と価格の多通貨対応を実装します。

```scala
final case class Product(
  id: ProductId,
  productCode: ProductCode,
  productNames: Map[Locale, ProductName],  // 多言語対応
  prices: Map[Currency, ProductPrice],     // 多通貨対応
  // ...
)

// 日本語
product.productNames(Locale.JAPAN) // "有機玄米 5kg"

// 英語
product.productNames(Locale.US) // "Organic Brown Rice 5kg"

// 日本円
product.prices(Currency.JPY).amount // 4000

// 米ドル
product.prices(Currency.USD).amount // 27.50
```

#### データリネージ（データ系譜の追跡）

マスターデータの変更がどのシステムに伝播したかを追跡します。

```
商品PROD-0001の価格変更
  ↓
├─ 在庫管理サービス
│  └─ 商品参照テーブル更新（2024-12-01 15:30:05）
│
├─ 受注管理サービス
│  ├─ 商品参照テーブル更新（2024-12-01 15:30:07）
│  └─ 影響を受けた受注: 3件
│
└─ 会計サービス
   └─ 勘定科目マッピング更新（2024-12-01 15:30:10）
```

### 12.3.2 他システムとの統合

#### 外部システムからのマスターデータインポート

CSV、Excel、EDIなど、さまざまなフォーマットからマスターデータをインポートします。

```scala
object MasterDataImporter {

  def importFromCSV(csvFile: File): Future[ImportResult] = {
    CSVReader.read(csvFile).flatMap { rows =>
      rows.map { row =>
        productCommandService.createProduct(
          productCode = ProductCode(row("product_code")),
          productName = ProductName(row("product_name")),
          listPrice = Money(BigDecimal(row("list_price")))
        )
      }
    }
  }

  def importFromEDI(ediMessage: String): Future[ImportResult] = {
    EDIParser.parse(ediMessage).flatMap { products =>
      products.map { product =>
        productCommandService.createProduct(product)
      }
    }
  }
}
```

#### マスターデータのエクスポート

取引先企業にマスターデータを提供します。

```scala
object MasterDataExporter {

  def exportToCSV(filter: ProductFilter): Future[File] = {
    productRepository.find(filter).map { products =>
      CSVWriter.write(
        headers = Seq("product_code", "product_name", "list_price"),
        rows = products.map { p =>
          Seq(p.productCode, p.productName, p.listPrice.toString)
        }
      )
    }
  }

  def exportToEDI(filter: ProductFilter): Future[String] = {
    productRepository.find(filter).map { products =>
      EDIGenerator.generate(products)
    }
  }
}
```

#### API Gatewayによるアクセス制御

外部企業からのアクセスを制御します。

```yaml
# Kong API Gateway設定
routes:
  - name: masterdata-api
    paths:
      - /api/masterdata
    plugins:
      - name: key-auth
      - name: rate-limiting
        config:
          minute: 100
          hour: 1000
      - name: ip-restriction
        config:
          whitelist:
            - 192.168.1.0/24
```

### 12.3.3 AI/MLによる高度化

#### 重複データの自動検出・マージ

機械学習モデルで重複候補を自動検出します。

```scala
object DuplicateDetectionML {

  def detectDuplicates(products: List[Product]): Future[List[DuplicatePair]] = {
    // 機械学習モデルで類似度を計算
    val similarities = mlModel.calculateSimilarities(products)

    // 類似度が90%以上のペアを抽出
    similarities.filter(_.score >= 0.9)
  }

  def suggestMerge(duplicatePair: DuplicatePair): MergeSuggestion = {
    // どちらを残すか、どのフィールドを統合するかを提案
    MergeSuggestion(
      primary = duplicatePair.product1,
      secondary = duplicatePair.product2,
      mergeStrategy = MergeStrategy.KeepPrimaryUpdateSecondary
    )
  }
}
```

#### データ品質スコアリング

データ品質を0-100点でスコアリングします。

```scala
object DataQualityScoring {

  def calculateQualityScore(product: Product): DataQualityScore = {
    var score = 100.0

    // 必須項目の充足
    if (product.productName.value.isEmpty) score -= 20

    // データの鮮度
    val daysSinceUpdate = ChronoUnit.DAYS.between(product.updatedAt, Instant.now())
    if (daysSinceUpdate > 365) score -= 10

    // 参照整合性
    if (product.primarySupplierId.exists(id => !supplierExists(id))) score -= 15

    // 重複の疑い
    if (hasDuplicateSuspicion(product)) score -= 20

    DataQualityScore(score)
  }
}
```

#### 異常値検出

商品価格の異常値を自動検出します。

```scala
object AnomalyDetection {

  def detectPriceAnomalies(products: List[Product]): List[PriceAnomaly] = {
    products.flatMap { product =>
      val expectedPrice = mlModel.predictPrice(product)
      val actualPrice = product.listPrice.amount

      val deviation = (actualPrice - expectedPrice).abs / expectedPrice

      if (deviation > 0.3) { // 30%以上の乖離
        Some(PriceAnomaly(
          product = product,
          expectedPrice = expectedPrice,
          actualPrice = actualPrice,
          deviation = deviation
        ))
      } else {
        None
      }
    }
  }
}
```

#### マスター変更の予測と提案

過去の変更パターンから次の変更を予測します。

```scala
object ChangePredictor {

  def predictPriceChange(product: Product): Option[PriceChangePrediction] = {
    // 過去の価格変更パターンを分析
    val history = priceHistoryRepository.findByProduct(product.id)

    // 季節性を考慮
    val seasonalPattern = mlModel.analyzeSeasonality(history)

    // 次回の価格変更を予測
    if (shouldChangePriceSoon(history, seasonalPattern)) {
      Some(PriceChangePrediction(
        product = product,
        predictedNewPrice = mlModel.predictNextPrice(product, history),
        predictedDate = mlModel.predictNextChangeDate(history),
        confidence = 0.85
      ))
    } else {
      None
    }
  }
}
```

## 12.4 総括

### 12.4.1 第7部で学んだ技術

第7部「共用データ管理サービス」では、以下の技術とパターンを学びました。

1. **イベントソーシング**: 全変更履歴の追跡、時点復元、監査証跡
2. **イベント駆動アーキテクチャ**: Pub/Sub、結果整合性、疎結合
3. **CQRS**: コマンド/クエリ分離、Materialized View
4. **Sagaパターン**: 承認ワークフロー、長期トランザクション
5. **GraphQL API**: 柔軟なクエリ、DataLoader、ページング
6. **パフォーマンス最適化**: 多層キャッシュ、インデックス、Materialized View
7. **運用とモニタリング**: Prometheusメトリクス、Grafanaダッシュボード、アラート

### 12.4.2 本シリーズ全体の完結

本章をもって、「Apache Pekko CQRS/Event Sourcing 実装ガイド」全7部・全85章が完結します。

- **第1部**: 環境構築編（全10章）
- **第2部**: サービス構築編（全11章）
- **第3部**: 在庫管理サービス（全13章）
- **第4部**: 受注管理サービス（全13章）
- **第5部**: 発注管理サービス（全11章）
- **第6部**: 会計サービス（全12章）
- **第7部**: 共用データ管理サービス（全12章）

皆様がこのシリーズで得た知識を実際のプロジェクトで活用し、堅牢でスケーラブルなシステムを構築されることを願っています。

---

**執筆者より**

CQRS/イベントソーシングは、複雑なドメインモデルを扱うエンタープライズシステムにおいて、非常に強力なアーキテクチャパターンです。特に、マスターデータ管理のような全社的に重要なデータを扱う場合、イベントソーシングによる完全な監査証跡と時点復元機能は、ビジネス価値を大きく高めます。

本シリーズが、皆様のシステム設計・開発の一助となれば幸いです。

ハッピーコーディング！
