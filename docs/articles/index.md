# Apache Pekkoを使用したCQRS/EventSourcingサービス開発

本シリーズでは、Apache Pekko（旧Akka）を使用したCQRS（コマンドクエリ責任分離）とイベントソーシングの実装について、環境構築から本番デプロイまで包括的に解説します。

## シリーズ構成

本シリーズは**3部構成・全34章**で構成されています：

- **第1部：環境構築編**（全10章） - LocalStackを使用した開発環境の構築から動作確認まで
- **第2部：サービス構築編**（全11章） - ドメインモデル設計、CQRS実装、テスト、本番デプロイまで
- **第3部：在庫管理サービスのケーススタディ**（全13章） - 実業務規模の複数集約システム（商品8,000、取引先430社、1日2,000件の受払、3拠点・9区画）の設計・実装・運用

## 対象読者

- CQRS/Event Sourcingを実践的に学びたいScala開発者
- Apache Pekko（旧Akka）への移行を検討している開発者
- イベント駆動アーキテクチャに興味がある方
- 分散システム・マイクロサービスの設計に携わる方

## 技術スタック

- **言語**: Scala 3.6.2
- **アクターフレームワーク**: Apache Pekko 1.1.2
- **イベントストア**: DynamoDB (AWS)
- **読み取りモデル**: PostgreSQL
- **API**: GraphQL (Sangria)
- **シリアライゼーション**: Protocol Buffers (ScalaPB)
- **開発環境**: LocalStack、Docker Compose
- **エフェクトシステム**: ZIO

---

## 📚 第1部：環境構築編

### [第1章：イントロダクション](part1-01-introduction.md)

CQRS/Event Sourcingの概要と、このシリーズで学べる内容について説明します。

**主な内容**:
- CQRS/Event Sourcingの基本概念
- Apache Pekkoの選択理由（Akkaからの移行背景）
- システム全体のアーキテクチャ概要
- 対象読者と前提知識

**キーワード**: CQRS, Event Sourcing, Apache Pekko, アーキテクチャ概要

---

### [第2章：アーキテクチャ概要](part1-02-architecture.md)

システム全体のアーキテクチャとデータフローについて詳しく解説します。

**主な内容**:
- コマンド側の構成（Pekko Actors + DynamoDB）
- クエリ側の構成（PostgreSQL + Slick）
- イベント処理フロー（DynamoDB Streams + Lambda）
- データフローの全体像

**キーワード**: System Architecture, Data Flow, Command Side, Query Side, Event Processing

---

### [第3章：技術スタックの選定](part1-03-tech-stack.md)

このプロジェクトで採用した技術スタックとその選定理由を説明します。

**主な内容**:
- Scala 3.6.2の採用理由（型安全性、関数型プログラミング）
- Apache Pekko 1.1.2の特徴（型付きアクター、永続化、クラスター）
- DynamoDB vs PostgreSQLの使い分け
- GraphQLとProtocol Buffersの選択理由

**キーワード**: Scala 3, Apache Pekko, DynamoDB, PostgreSQL, GraphQL, Protocol Buffers

---

### [第4章：開発環境のセットアップ](part1-04-setup.md)

LocalStackとDocker Composeを使用したローカル開発環境の構築手順を解説します。

**主な内容**:
- 前提条件の確認（Java、SBT、Docker）
- プロジェクトのクローンとビルド
- LocalStackの設定とDynamoDBテーブル作成
- PostgreSQLとFlywayマイグレーション
- Lambda関数のローカルデプロイ

**キーワード**: LocalStack, Docker Compose, DynamoDB, PostgreSQL, Lambda

---

### [第5章：設定管理の体系化](part1-05-configuration.md)

Typesafe Configによる階層的な設定管理と環境別の設定方法について学びます。

**主な内容**:
- 設定ファイルの階層構造（application.conf、pcqrses.conf、pekko.conf、j5ik2o.conf）
- 環境変数による設定の上書き
- ローカル/テスト/本番環境の設定分離
- シリアライゼーション設定

**キーワード**: Typesafe Config, Configuration Management, Environment Variables

---

### [第6章：初回起動とヘルスチェック](part1-06-startup.md)

システムの初回起動手順と各サービスの動作確認方法を解説します。

**主な内容**:
- シングルノードモードでの起動（`./scripts/run-single.sh`）
- 各サービスのヘルスチェック
- GraphQL Playgroundの使い方
- 基本的なクエリとミューテーションの実行

**キーワード**: Startup, Health Check, GraphQL Playground

---

### [第7章：E2Eテストによる動作確認](part1-07-e2e-test.md)

E2Eテストスクリプトを使用したシステム全体の動作確認方法を学びます。

**主な内容**:
- E2Eテストスクリプトの実行（`./scripts/test-e2e.sh`）
- テストフローの理解（ヘルスチェック → Mutation → 待機 → Query → 検証）
- 結果整合性の確認方法
- テストのカスタマイズ

**キーワード**: E2E Test, Integration Test, Eventual Consistency

---

### [第8章：トラブルシューティング](part1-08-troubleshooting.md)

開発中によく遭遇する問題とその解決方法を紹介します。

**主な内容**:
- LocalStackが起動しない場合の対処法
- Lambda関数がイベントを処理しない問題
- PostgreSQL接続エラーの解決
- DynamoDBデータ保存の確認方法
- ポート競合の解決

**キーワード**: Troubleshooting, Debugging, Common Issues

---

### [第9章：開発ワークフローの確立](part1-09-workflow.md)

日常的な開発作業で使用するコマンドとワークフローを学びます。

**主な内容**:
- コードフォーマット（`sbt fmt`）
- リントチェック（`sbt lint`）
- テスト実行とカバレッジレポート（`sbt test`、`sbt testCoverage`）
- 環境のクリーンアップ

**キーワード**: Development Workflow, Code Formatting, Testing, Code Coverage

---

### [第10章：まとめと次のステップ](part1-10-summary.md)

第1部で学んだ環境構築の内容を振り返り、第2部への橋渡しを行います。

**主な内容**:
- 環境構築で学んだことの振り返り
- LocalStackを使用した開発の利点
- 第2部（サービス構築編）への導入
- ドメインモデル設計の準備

**キーワード**: Summary, Next Steps, Service Development

---

## 📚 第2部：サービス構築編

### [第1章：ドメイン駆動設計の基礎](part2-01-ddd-basics.md)

ドメイン駆動設計（DDD）の基本概念と、このプロジェクトにおける実装方法を解説します。

**主な内容**:
- レイヤードアーキテクチャの理解
- 集約（Aggregate）と値オブジェクト（Value Object）
- UserAccountドメインモデルの設計
- ドメインイベントの定義

**キーワード**: DDD, Bounded Context, Aggregate Root, Value Object, Domain Event

---

### [第2章：イベントソーシングの実装](part2-02-event-sourcing.md)

イベントソーシングパターンの詳細と、Pekko Persistenceを使用した実装方法を学びます。

**主な内容**:
- イベントソーシングの概念と利点
- Envelopeパターンによるバージョニング
- Protocol Buffersによるシリアライゼーション
- スナップショット戦略とイベントリプレイ

**キーワード**: Event Sourcing, Pekko Persistence, Protocol Buffers, Snapshot, Event Replay

---

### [第3章：コマンド側の実装](part2-03-command-side.md)

CQRS における書き込みモデル（コマンド側）の実装について解説します。

**主な内容**:
- UserAccountAggregateの実装
- Registry Patternによるアクター管理
- Cluster Shardingによる分散配置
- GraphQL Mutationによるコマンド受付

**キーワード**: Command Side, Write Model, Aggregate Actor, Cluster Sharding, GraphQL Mutation

---

### [第4章：クエリ側の実装](part2-04-query-side.md)

CQRS における読み取りモデル（クエリ側）の実装について解説します。

**主な内容**:
- PostgreSQLスキーマ設計とFlyway
- Slick DAOの自動生成
- GraphQL Queryによるデータ取得
- Read Model Updaterによる非同期更新

**キーワード**: Query Side, Read Model, Flyway, Slick, GraphQL Query, Projection

---

### [第5章：イベント処理の実装](part2-05-event-processing.md)

DynamoDB Streamsを使用したイベント駆動の読み取りモデル更新について学びます。

**主な内容**:
- DynamoDB Streams統合
- Lambda関数によるイベント処理
- 結果整合性（Eventual Consistency）の管理
- 冪等性の実装

**キーワード**: Event Processing, DynamoDB Streams, Lambda, Eventual Consistency, Idempotency

---

### [第6章：設定管理とデプロイ](part2-06-configuration.md)

Typesafe Configによる階層的設定管理と、Docker Composeを使用したデプロイ戦略を解説します。

**主な内容**:
- 設定ファイルの階層化（application.conf、pcqrses.conf、pekko.conf、j5ik2o.conf）
- 環境変数による設定の上書き
- シリアライゼーション戦略
- Docker Composeによるサービス構成

**キーワード**: Typesafe Config, Environment Variables, Serialization, Docker Compose

---

### [第7章：テスト戦略](part2-07-testing.md)

単体テストから統合テスト、E2Eテストまで、包括的なテスト戦略について学びます。

**主な内容**:
- テストピラミッド（単体60%、統合30%、E2E10%）
- ActorTestKitによるアクターテスト
- GraphQL APIのテスト
- E2Eテストによる結果整合性の検証

**キーワード**: Test Pyramid, Unit Test, Integration Test, E2E Test, ScalaTest, ActorTestKit

---

### [第8章：パフォーマンスとスケーラビリティ](part2-08-performance-scalability.md)

本番環境でのパフォーマンス最適化とスケーラビリティ確保の手法を解説します。

**主な内容**:
- Prometheus/Grafanaによるメトリクス収集
- Logbackによる構造化ログ
- アクターディスパッチャーとデータベース接続プールのチューニング
- Cluster Shardingによる水平スケーリング
- Graceful Shutdownの実装

**キーワード**: Metrics, Logging, Performance Tuning, Horizontal Scaling, Graceful Shutdown

---

### [第9章：実践的なトピック](part2-09-practical-topics.md)

本番運用で遭遇する実践的な課題とその解決策について学びます。

**主な内容**:
- イベントスキーマの進化とバージョニング戦略
- EventAdapterによるアップキャスト
- GraphQL APIの改善（Validation型の活用）
- エラーハンドリングとレジリエンス（Supervision Strategy、Retry、Circuit Breaker）
- デッドレター処理

**キーワード**: Schema Evolution, Event Upcasting, Validation, Resilience, Circuit Breaker

---

### [第10章：本番環境への準備](part2-10-production-readiness.md)

セキュリティ、運用、AWSへのデプロイなど、本番環境へのデプロイに必要な準備について解説します。

**主な内容**:
- セキュリティ（JWT認証・認可、APIレート制限、暗号化）
- 運用（バックアップ/リストア、ディザスタリカバリ、オートスケーリング）
- デプロイ戦略（Blue-Green、カナリアリリース）
- AWSへのデプロイ（LocalStackから本番環境への移行）
- Terraformによるインフラ管理

**キーワード**: Security, JWT, Rate Limiting, Backup, Disaster Recovery, Blue-Green Deployment, Terraform

---

### [第11章：まとめと発展的なトピック](part2-11-conclusion.md)

シリーズ全体の総括と、さらなる学習リソース、発展的なトピックについて紹介します。

**主な内容**:
- 学習内容の振り返り
- CQRS/イベントソーシングのベストプラクティス
- 推奨書籍・オンラインコース・コミュニティ
- 発展的なトピック（Saga Pattern、Event-Carried State Transfer、Polyglot Persistence、Serverless Event Sourcing）

**キーワード**: Best Practices, Learning Resources, Saga Pattern, Polyglot Persistence

---

## 📚 第3部：在庫管理サービスのケーススタディ

第3部では、実業務規模の在庫管理システムを通じて、CQRS/イベントソーシングの実践的な実装を学びます。

**ケーススタディの規模**:
- 商品マスタ: 約8,000品目（SKU単位）
- 取引先: 430社（B2B取引）
- 拠点・区画: 3拠点・9区画（常温・冷蔵・冷凍の保管条件別）
- 在庫受払処理: 1日約2,000件（ピーク時は10倍）

**扱うトピック**:
- 複数集約の設計と実装（5つの集約）
- イベントソーシングとCRUD管理のハイブリッドアーキテクチャ
- 在庫引当の競合制御（楽観的ロック + CAS）
- 複雑なクエリの実装（Materialized View、DataLoader）
- パフォーマンス最適化（Redisキャッシング、Pekko Streams、Gatling負荷テスト）
- 運用とモニタリング（Prometheus + Grafana、監査ログ、アラート）
- 高度なトピック（在庫予測、マルチテナント対応、グローバル展開）

---

### [第1章：イントロダクション - 在庫管理サービスの要件定義](part3-01-introduction.md)

第3部のケーススタディとして、卸売事業者D社の在庫管理サービスの要件を定義します。

**主な内容**:
- 第1部・第2部の振り返り
- 卸売事業者D社の事業概要
- 在庫管理サービスのビジネス要件
- 技術的課題（在庫引当の競合制御、分散トランザクション、イベント順序保証）

**キーワード**: Business Requirements, Inventory Management, Technical Challenges

---

### [第2章：データモデルの設計](part3-02-data-model.md)

在庫管理システムのRead Model（PostgreSQL）とイベントストア（DynamoDB）のデータモデルを設計します。

**主な内容**:
- Read Model（PostgreSQL）のスキーマ設計
- DynamoDBのテーブル設計とパーティションキー選択
- インデックス戦略とパフォーマンス最適化
- 日本語カラム名によるビジネスとの整合性

**キーワード**: Data Model, PostgreSQL, DynamoDB, Schema Design, Indexing

---

### [第3章：ドメインに適したデータの作成](part3-03-domain-data.md)

卸売事業者D社の事業実態に基づいたマスタデータとテストデータを作成します。

**主な内容**:
- 事業概要（年商150億円、SKU数8,000、1日2,000件の受払）
- マスタデータの準備（企業、商品、倉庫、取引先）
- テストデータの設計（在庫データ、受払データ）
- Flywayによるデータ投入とシードデータSQL

**キーワード**: Master Data, Test Data, Flyway, Seed Data, Business Context

---

### [第4章：ドメインモデルの設計](part3-04-domain-model.md)

DDDに基づき、在庫管理システムのドメインモデルを設計します。

**主な内容**:
- Bounded Contextの識別（商品カタログ、取引先管理、倉庫管理、在庫管理）
- 集約の設計（Product、Customer、Warehouse、WarehouseZone、Inventory）
- ドメインイベントの設計（在庫受払、引当、区画移動）
- コンテキストマップと集約関係（PlantUML）

**キーワード**: DDD, Bounded Context, Aggregate, Domain Event, Context Map

---

### [第5章：複数集約の実装](part3-05-aggregate-implementation.md)

TDD（テスト駆動開発）のアプローチで、複数の集約を実装します。

**主な内容**:
- TDDサイクル（RED → GREEN → REFACTOR）
- Product集約のTDD実装（値オブジェクト、エンティティ、イベント、アクター）
- Inventory集約の重要テストケース（在庫引当、保管条件検証）
- モジュール配置と実装の順序

**キーワード**: TDD, Aggregate Implementation, ScalaTest, Pekko TestKit, Test-First

---

### [第6章：Sagaパターン（参考）](part3-06-saga-pattern.md)

分散トランザクションを実現するSagaパターンの基本概念と実装パターンを学びます。

> **注意**: この章は参考情報です。今回の実装スコープ外ですが、Sagaパターンの理解を深めるための資料として活用してください。

**主な内容**:
- Sagaパターンの基礎（Long Running Transaction、補償トランザクション）
- ChoreographyとOrchestrationの違い
- OrderSagaの設計（注文処理の状態遷移）
- Apache PekkoでのSaga実装パターン

**キーワード**: Saga Pattern, Distributed Transaction, Compensating Transaction, Orchestration

---

### [第7章：在庫引当の競合制御](part3-07-concurrency-control.md)

在庫引当における同時アクセス制御と楽観的ロックの実装について学びます。

**主な内容**:
- 楽観的ロック（Optimistic Locking）の実装（TDD）
- CAS（Compare-And-Swap）オペレーション
- リトライ戦略とエラーハンドリング
- FIFO戦略と保管条件を考慮した引当アルゴリズム
- デッドロック回避（一貫性のあるロック順序、タイムアウト）
- パフォーマンスチューニング（バッチ引当、キャッシュ）

**キーワード**: Concurrency Control, Optimistic Locking, CAS, Retry Strategy, Version Conflict, Deadlock Avoidance

---

### [第8章：複雑なクエリの実装](part3-08-complex-queries.md)

在庫照会や集計など、複雑なRead Modelクエリの実装について学びます。

**主な内容**:
- 在庫照会のRead Model設計（商品別、倉庫別、区画別）
- カテゴリ別・保管条件別の集計クエリ
- 区画容量使用率の計算
- マテリアライズドビューの活用（リアルタイム更新 vs バッチ更新）
- 複雑なGraphQLクエリ（ネスト、フィルタリング、ソート、ページネーション）
- DataLoaderによるN+1問題の解決（バッチクエリで100倍高速化）
- インデックス戦略とクエリパフォーマンスチューニング

**キーワード**: Complex Query, Materialized View, GraphQL, DataLoader, N+1 Problem, Batch Query, Query Optimization

---

### [第9章：イベントの順序保証](part3-09-event-ordering.md)

DynamoDB Streamsによるイベント順序保証とRead Model更新の整合性について学びます。

**主な内容**:
- 順序保証が必要な理由（在庫計算、状態遷移、監査ログ）
- DynamoDB Streamsの仕組み（パーティションキー単位でのFIFO保証）
- シャードキーによるパーティショニング
- Sequence Numberによる順序制御
- 重複イベントの検出（At-Least-Once配信）
- 欠落イベントの検知と対処（リトライ、DLQ、定期チェック）
- 結果整合性の可視化（更新遅延の表示）

**キーワード**: Event Ordering, DynamoDB Streams, Sequence Number, At-Least-Once Delivery, Idempotency, Eventual Consistency

---

### [第10章：パフォーマンス最適化](part3-10-performance.md)

在庫管理システムのパフォーマンス最適化とスケーラビリティ向上について学びます。

**主な内容**:
- **Redisキャッシング**: 商品マスタ（8,000品目）・倉庫情報（3拠点）・在庫情報（24,000件）のキャッシュ戦略
  - Cache-Aside vs Write-Through の比較と使い分け
  - イベント駆動キャッシュ無効化
  - キャッシュヒット率のモニタリング
  - 商品詳細取得が25倍高速化、在庫一覧取得が20倍高速化を実現
- **Pekko Streamsバッチ処理**: 1日2,000件の在庫受払を効率的に処理
  - 在庫集計バッチ（区画別・カテゴリ別集計）
  - 低在庫アラートバッチ（9区画の閾値監視）
  - ストリーム処理によるメモリ効率化とバックプレッシャー制御
  - バッチスケジューリング（定期実行）
- **Gatling負荷テスト**: 430社の取引先からの同時アクセスを検証
  - ピーク時の在庫受払処理（1日2,000件）のシナリオ
  - 在庫引当の競合シナリオ（複数社による同時引き当て）
  - パフォーマンスメトリクスの測定（P50/P95/P99レイテンシ）
  - ボトルネックの特定と改善
- **水平スケーリング**: Pekko Cluster Shardingによる負荷分散
  - 3拠点への拠点別シャード配置
  - アイドルエンティティの自動パッシベーション

**実装例**:
- ProductCacheRepository（Circe JSONシリアライゼーション、TTL管理）
- CachedProductQueryService（Cache-Asideパターン、バッチキャッシュ読み込み）
- InventoryAggregationBatch（Pekko Streamsによるストリーム集計）
- LowStockAlertBatch（閾値監視と重要度判定）
- InventoryLoadTest（Gatlingシミュレーション、3つの負荷テストシナリオ）
- WarehouseShardAllocation（倉庫コードベースのシャーディング）

**キーワード**: Performance Optimization, Redis Caching, Pekko Streams, Gatling Load Testing, Horizontal Scaling, Cache-Aside, Write-Through, Backpressure

---

### [第11章：運用とモニタリング](part3-11-operations.md)

在庫管理システムの安定運用に必要なモニタリング基盤を構築します。

**主な内容**:
- **ビジネスメトリクス**: Prometheus + Grafanaによる可視化
  - 在庫受払処理レート（1日2,000件の処理状況監視）
  - 在庫引当成功率/失敗率（区画別・保管条件別、目標95%以上）
  - 平均処理時間（P50/P95/P99レイテンシ、目標1秒以内）
  - 区画ごとの稼働率（9区画の使用状況、満杯警告90%）
  - 商品カテゴリ別の受払状況（食品類・日用品の需給バランス）
  - PrometheusメトリクスとPromQLクエリ
  - Grafanaダッシュボード設計（リアルタイム可視化）
- **在庫監査ログ**: 全ての在庫変動を追跡可能に
  - 受払履歴テーブル（入庫、出庫、引当、引当解除、調整、移動）
  - イベント駆動での受払履歴記録（1日2,000件）
  - 監査レポート生成（区画別受払集計、在庫差異レポート、カテゴリ別・保管条件別集計）
  - 不整合の検出と修正（イベントソーシングとRead Modelの整合性チェック、自動修復）
  - 定期的な整合性チェッカー（1日1回実行）
- **アラートと通知**: 早期検知と迅速な対応
  - Prometheusアラートルール（低在庫、引当失敗率、区画満杯、API遅延、受払処理遅延）
  - Alertmanager設定（重要度別ルーティング、Slack + メール通知）
  - カスタムアラート（保管条件不一致、連続引当失敗）
  - Slack通知実装（リアルタイムアラート、重要度別の色分け）

**実装例**:
- MetricsCollector（Prometheusメトリクス収集、カウンター、ヒストグラム、ゲージ）
- MetricsRoute（Prometheusスクレイプエンドポイント）
- InventoryHistoryRecorder（受払履歴記録、イベント駆動）
- InventoryAuditReportGenerator（監査レポート生成、差異検出）
- IntegrityChecker（定期的な整合性チェック、自動修復）
- AlertManager（カスタムアラート、重要度判定）
- SlackNotifier（Slack Webhook通知）

**キーワード**: Monitoring, Prometheus, Grafana, Audit Log, Alerting, Metrics, PromQL, Alertmanager, Slack Notification, Integrity Check

---

### [第12章：高度なトピック](part3-12-advanced-topics.md)

在庫管理システムの発展的な機能と将来の拡張について学びます。

**主な内容**:
- **在庫予測**: 機械学習による需要予測と自動発注
  - 時系列データ分析（日次受払データ集計、曜日パターン分析、季節性検出）
  - 移動平均の計算（7日間ウィンドウ）
  - 線形回帰モデルによる需要予測（最小二乗法、信頼区間計算）
  - 自動発注推奨（発注点計算、安全在庫、リードタイム考慮）
  - 緊急度判定（Critical/High/Medium/Low）
  - 自動発注アラート（Slackへの通知）
- **マルチテナント対応**: 複数企業での共有利用
  - テナント分離戦略（Database/Schema/Shared Schema比較）
  - Shared Schema方式の実装（行レベル分離）
  - PostgreSQL Row Level Security（RLS）
  - テナントコンテキスト管理（ThreadLocal、リクエストスコープ）
  - HTTPミドルウェアでのテナント抽出（X-Tenant-IDヘッダー）
  - テナント別カスタマイズ（機能制限、設定、リソースリミット）
- **グローバル展開**: マルチリージョンデプロイ
  - マルチリージョンアーキテクチャ（Asia/Europe/US）
  - DynamoDB Global Tables（マルチマスター、自動レプリケーション）
  - PostgreSQL Read Replica（非同期レプリケーション）
  - リージョン別ルーティング（倉庫コードベース、IPベース）
  - レプリケーション遅延対策（リトライ、タイムアウト）
  - データの局所性（Data Locality）

**実装例**:
- InventoryTimeSeriesAnalyzer（時系列分析、曜日パターン、季節性）
- DemandForecastModel（線形回帰、移動平均、信頼区間）
- DemandForecastService（予測実行、推奨事項生成）
- AutoPurchaseRecommender（発注推奨、緊急度判定）
- TenantContext（テナントコンテキスト管理）
- TenantMiddleware（HTTPミドルウェア、認証）
- MultiTenantInventoryDao（テナント分離、RLS）
- RegionRouter（リージョンルーティング）
- ReplicationAwareReadModel（レプリケーション遅延対策）

**キーワード**: Inventory Forecasting, Machine Learning, Linear Regression, Auto-Purchase, Multi-tenancy, Row Level Security, Tenant Context, Global Deployment, DynamoDB Global Tables, Region Routing

---

### [第13章：まとめと実践演習](part3-13-summary.md)

第3部で学んだ内容を振り返り、実践演習を通じて理解を深めます。

**主な内容**:
- **学んだことの振り返り**:
  - 複数集約の設計と実装（Product、Warehouse、WarehouseZone、Inventory、Customer）
  - イベントソーシングとCRUD管理のハイブリッドアーキテクチャ（静的マスタはCRUD、動的データはイベントソーシング）
  - 在庫管理特有の課題と解決策（区画管理、保管条件整合性、競合制御、監査ログ）
  - 本格的なCQRS/イベントソーシングシステムの構築（商品8,000、取引先430社、1日2,000件の受払、3拠点・9区画）
  - 技術スタックまとめ（Pekko、DynamoDB、PostgreSQL、Redis、Prometheus、Gatling）
- **実践演習**（4つの課題）:
  - 演習1: 在庫棚卸機能の追加（区画ごとの実地棚卸、差異検出、調整、レポート生成）
  - 演習2: 区画間自動移動機能（保管条件に基づく自動移動提案、容量考慮、在庫バランス調整）
  - 演習3: 在庫分析機能（カテゴリ別受払分析、保管条件別回転率分析、区画別稼働率分析）
  - 演習4: 在庫予測機能の強化（季節変動考慮、曜日パターン考慮、複数モデル比較）
- **次のステップ**:
  - より複雑なビジネスルールの追加（区画容量管理、温度監視、ロット管理、賞味期限管理）
  - 他のBounded Contextとの統合（配送管理、仕入管理、注文管理、請求管理）
  - プロダクション環境への展開（3拠点分散デプロイ、スケーリング検証、運用体制構築）
  - 継続的な学習（Apache Pekko、CQRS/ES、DDD、コミュニティ参加）

**演習で学べること**:
- 複数の在庫を一括処理する方法
- ドメインロジックの複雑な実装
- ビジネスメトリクスの実装
- 時系列分析の高度な手法

**参考資料**:
- Apache Pekko公式ドキュメント
- CQRS Journey（Microsoft）
- Event Sourcing（Martin Fowler）
- Domain-Driven Design（Eric Evans）

**キーワード**: Summary, Practical Exercises, Stock Taking, Inventory Analysis, Turnover Rate, Next Steps, Production Deployment, Bounded Context Integration

---

## 🚀 学習の進め方

### 推奨される学習順序

1. **第1部から順番に読む**: まず環境構築編（第1部）で開発環境をセットアップし、サービス構築編（第2部）で実装の詳細を学びます。各章は前の章の内容を前提としているため、順番に読むことを推奨します。

2. **実際にコードを動かしてみる**: 理論だけでなく、GitHubリポジトリをクローンして実際に動かしてみることで理解が深まります。
   - 第1部で環境を構築
   - E2Eテストで動作確認
   - 第2部で各コンポーネントの理解を深める
   - 第3部のケーススタディで実践的な実装を学ぶ

3. **実践演習に取り組む**: 第3部第13章で提示された4つの演習課題（在庫棚卸、区画間自動移動、在庫分析、在庫予測）に取り組むことで、学んだことを定着させられます。

4. **自分なりの拡張を試す**: 演習課題を超えて、独自の機能（ロット管理、賞味期限管理、他のBounded Contextとの統合など）を追加してみましょう。

5. **コミュニティに参加する**: Scala Users Group JapanやPekko Discordで質問したり、知見を共有したりしましょう。

### 学習の流れ

```
第1部：環境構築編 (10章)
  ↓ 環境セットアップ完了
第2部：サービス構築編 (11章)
  ↓ 単一集約の実装理解完了
第3部：在庫管理サービスのケーススタディ (13章)
  ↓ 複数集約を含む実践的な実装完了
本番環境へのデプロイ
```

### 前提知識

このシリーズを最大限活用するには、以下の知識があることが望ましいです：

- **Scala基礎**: 基本的な構文、クラス、トレイト、パターンマッチング
- **関数型プログラミング**: イミュータビリティ、純粋関数、副作用の分離
- **分散システムの基礎**: CAP定理、結果整合性、分散トランザクション
- **Docker**: 基本的なコマンド、Dockerfileの記述、docker-compose

もちろん、これらの知識がなくても読み進めることは可能ですが、適宜公式ドキュメントや参考書籍を併読することをお勧めします。

---

## 📖 参考資料

### 関連ドキュメント

- [プロジェクトREADME](../../README.md) - プロジェクト概要とクイックスタート
- [CLAUDE.md](../../CLAUDE.md) - このプロジェクトでのClaude Code使用ガイド
- [outline.md](outline.md) - 記事シリーズの詳細なアウトライン

### 記事ファイル構成

```
docs/articles/
├── index.md                              # 本ファイル（目次）
├── outline.md                            # 詳細なアウトライン
│
├── 【第1部：環境構築編】
├── part1-01-introduction.md              # 第1部 第1章
├── part1-02-architecture.md              # 第1部 第2章
├── part1-03-tech-stack.md                # 第1部 第3章
├── part1-04-setup.md                     # 第1部 第4章
├── part1-05-configuration.md             # 第1部 第5章
├── part1-06-startup.md                   # 第1部 第6章
├── part1-07-e2e-test.md                  # 第1部 第7章
├── part1-08-troubleshooting.md           # 第1部 第8章
├── part1-09-workflow.md                  # 第1部 第9章
├── part1-10-summary.md                   # 第1部 第10章
│
├── 【第2部：サービス構築編】
├── part2-01-ddd-basics.md                # 第2部 第1章
├── part2-02-event-sourcing.md            # 第2部 第2章
├── part2-03-command-side.md              # 第2部 第3章
├── part2-04-query-side.md                # 第2部 第4章
├── part2-05-event-processing.md          # 第2部 第5章
├── part2-06-configuration.md             # 第2部 第6章
├── part2-07-testing.md                   # 第2部 第7章
├── part2-08-performance-scalability.md   # 第2部 第8章
├── part2-09-practical-topics.md          # 第2部 第9章
├── part2-10-production-readiness.md      # 第2部 第10章
├── part2-11-conclusion.md                # 第2部 第11章
│
├── 【第3部：在庫管理サービスのケーススタディ】
├── part3-01-introduction.md              # 第3部 第1章
├── part3-02-data-model.md                # 第3部 第2章
├── part3-03-domain-data.md               # 第3部 第3章
├── part3-04-domain-model.md              # 第3部 第4章
├── part3-05-aggregate-implementation.md  # 第3部 第5章
├── part3-06-saga-pattern.md              # 第3部 第6章
├── part3-07-concurrency-control.md       # 第3部 第7章
├── part3-08-complex-queries.md           # 第3部 第8章
├── part3-09-event-ordering.md            # 第3部 第9章
├── part3-10-performance.md               # 第3部 第10章
├── part3-11-operations.md                # 第3部 第11章
├── part3-12-advanced-topics.md           # 第3部 第12章
└── part3-13-summary.md                   # 第3部 第13章
```

### 公式ドキュメント

- [Apache Pekko公式サイト](https://pekko.apache.org/)
- [Scala 3公式ドキュメント](https://docs.scala-lang.org/scala3/)
- [ZIO公式サイト](https://zio.dev/)

### GitHubリポジトリ

このプロジェクトのソースコードは以下で公開されています：
- [pekko-cqrs-es-example](https://github.com/j5ik2o/pekko-cqrs-es-example)

---

## 💡 フィードバック

この記事シリーズについてのフィードバックや改善提案がありましたら、GitHubのIssueやPull Requestでお知らせください。皆様のフィードバックが、コンテンツの質を高める助けとなります。

---

## ライセンス

このドキュメントはMITライセンスの下で公開されています。

---

**Happy Event Sourcing! 🎉**

最終更新: 2025-11-28
