# Apache Pekkoを使用したCQRS/EventSourcingサービス開発

本シリーズでは、Apache Pekko（旧Akka）を使用したCQRS（コマンドクエリ責任分離）とイベントソーシングの実装について、環境構築から本番デプロイまで包括的に解説します。

## シリーズ構成

本シリーズは**7部構成・全97章**で構成されています：

- **第1部：環境構築編**（全10章） - LocalStackを使用した開発環境の構築から動作確認まで
- **第2部：サービス構築編**（全11章） - ドメインモデル設計、CQRS実装、テスト、本番デプロイまで
- **第3部：在庫管理サービスのケーススタディ**（全13章） - 実業務規模の複数集約システム（商品8,000、取引先430社、1日2,000件の受払、3拠点・9区画）の設計・実装・運用
- **第4部：受注管理サービスのケーススタディ**（全13章） - 在庫管理に受注管理を追加し、Sagaパターンによる分散トランザクション（月間50,000件の受注、与信管理、請求管理）を実現
- **第5部：発注管理サービスのケーススタディ**（全11章） - 仕入先管理、発注、入荷検品、支払管理を含む調達プロセス全体（月間3,000件の発注、200社の仕入先、3-way matching）をイベントソーシングで実装
- **第6部：会計サービスのケーススタディ**（全12章） - イベント駆動による仕訳自動生成、総勘定元帳、財務諸表作成、決算処理（月間66,000件の仕訳、年商150億円）を実現
- **第7部：共用データ管理サービスのケーススタディ**（全12章） - イベントソーシングによるマスターデータ管理、変更承認ワークフロー、GraphQL API、パフォーマンス最適化（商品5,000 SKU、月間800件の変更、3層キャッシュ）を実装

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

## 📚 第4部：受注管理サービスのケーススタディ

第4部では、第3部で構築した在庫管理システムに受注管理機能を追加し、受注管理システムとして完成させます。

**ケーススタディの規模**:

- 受注処理: 月間約50,000件の受注(430社の取引先から)
- 金額管理: 商品単価、数量ベースの金額計算、税金(標準10%、軽減8%)、割引
- 与信管理: 取引先ごとの与信限度額チェック(大口3,000万円、中口500万円、小口100万円)
- 分散トランザクション: Sagaパターンによる注文→在庫→与信→確定→出荷の調整

**扱うトピック**:

- 見積もりから注文への変換フロー(Quotation集約)
- 複数集約間の分散トランザクション(Sagaパターン - Orchestration)
- BigDecimalを使用したMoney値オブジェクト(浮動小数点演算の回避)
- 4つの新規集約の実装(Quotation、Order、CreditLimit、Invoice)
- 税金計算と端数処理の正確性
- 請求管理と入金管理
- 返品処理と在庫への戻し入れ

---

### [第1章：イントロダクション - 受注管理サービスの要件定義](part4-01-overview.md)

第4部のケーススタディとして、受注管理サービスの全体像と要件を定義します。

**主な内容**:

- 第3部の振り返り(在庫管理システムで実装した内容)
- 卸売事業者D社の受注業務(年商150億円、月間50,000件の受注)
- 取引先タイプ(大口30社、中口150社、小口250社)
- 受注フロー(見積もり作成 → 見積もり承認 → 注文受付 → 与信 → 在庫 → 確定 → 出荷 → 請求 → 入金)
- 見積もりから注文への変換、または直接注文の受付
- 技術的課題(分散トランザクション、金額計算の整合性、パフォーマンス要件)
- アーキテクチャ上の決定事項(Orchestration型Saga、BigDecimal + Money値オブジェクト、4つの集約)

**キーワード**: Business Requirements, Order Reception Management, Quotation, Credit Management, Saga Pattern, Money Value Object

---

### [第2章：Read Modelスキーマの設計](part4-02-read-model-schema.md)

受注管理システムのRead Model（PostgreSQL）スキーマを設計します。

**主な内容**:

- 見積もりテーブル設計（見積もりヘッダー、明細）
- 注文テーブル設計（注文ヘッダー、明細、ステータス管理）
- 与信テーブル設計（与信限度額、使用額、利用可能額）
- 請求テーブル設計（請求ヘッダー、入金管理）
- 入金テーブル設計（入金記録、入金方法）
- インデックス戦略とパフォーマンス最適化
- DynamoDBのイベントストア設計（Quotation、Order、CreditLimit、Invoice Events）

**キーワード**: Read Model Schema, PostgreSQL, Flyway, Event Store Design, Indexing Strategy

---

### [第3章：受注管理に適したドメインデータ作成](part4-03-domain-data.md)

D社の受注業務に基づいたマスタデータとテストデータを作成します。

**主な内容**:

- 商品マスタの拡張（商品単価、税区分の追加）
- 取引先マスタの拡張（与信限度額、支払条件の追加）
- 取引先タイプ別データ（大口30社、中口150社、小口250社）
- 注文データシナリオ（月間50,000件のパターン）
- 季節変動の考慮（年末繁忙期、夏季閑散期）
- テストデータ投入スクリプト

**キーワード**: Master Data, Test Data, Business Scenarios, Customer Types, Transaction Patterns

---

### [第4章：受注管理のドメインモデル設計](part4-04-domain-model.md)

受注管理の4つの集約（Quotation、Order、CreditLimit、Invoice）のドメインモデルを設計します。

**主な内容**:

- Order集約（Order エンティティ、OrderItem 値オブジェクト、OrderStatus）
- Quotation集約（見積もりから注文への変換）
- CreditLimit集約（与信限度額管理、与信枠の引当と解放）
- Invoice集約（請求書発行、入金管理）
- Money値オブジェクト（BigDecimalによる正確な金額計算）
- TaxRate、DiscountRate値オブジェクト
- ドメインイベントの設計

**キーワード**: Domain Model, Aggregate Design, Value Objects, Money Pattern, Domain Events

---

### [第5章：複数集約の実装](part4-05-aggregate-implementation.md)

4つの集約（Order、CreditLimit、Invoice、Quotation）を実装します。

**主な内容**:

- Order集約の実装（コマンド、イベント、ビジネスルール）
- 注文金額の計算（数量 × 単価 - 割引 + 税金）
- CreditLimit集約の実装（与信枠の引当、解放、調整）
- Invoice集約の実装（月次締め処理、入金記録、入金照合）
- Quotation集約の実装（見積もり作成、承認、注文変換）
- イベントハンドラーとステータス遷移制御

**キーワード**: Aggregate Implementation, Command Handlers, Event Handlers, Business Rules, State Transition

---

### [第6章：Sagaパターンによる注文プロセスの実装](part4-06-saga-implementation.md)

Orchestration型Sagaパターンで注文プロセス全体を実装します。

**主な内容**:

- Sagaパターンの基礎（Choreography vs Orchestration）
- 注文Sagaの設計（注文作成 → 在庫引当 → 与信チェック → 確定 → 出荷）
- 正常フローの実装
- 失敗時の補償フロー（在庫解放、与信枠解放、注文キャンセル）
- Saga Orchestratorの実装（Pekko Persistence）
- タイムアウト処理とリトライ戦略
- べき等性の保証

**キーワード**: Saga Pattern, Orchestration, Compensating Transaction, Distributed Transaction, Idempotency

---

### [第7章：金額計算と税金処理](part4-07-money-tax.md)

BigDecimalを使用した正確な金額計算と税金処理を実装します。

**主な内容**:

- 浮動小数点演算の問題（0.1 + 0.2 != 0.3）
- BigDecimalによる正確な10進数演算
- Money値オブジェクトの実装（加算、減算、乗算、丸め処理）
- 税率の管理（標準税率10%、軽減税率8%）
- 税金計算と端数処理
- 割引計算（率による割引、金額による割引）
- 金額計算の単体テスト

**キーワード**: BigDecimal, Money Value Object, Tax Calculation, Rounding, Discount Calculation

---

### [第8章：与信管理の実装](part4-08-credit-management.md)

取引先ごとの与信限度額管理と与信チェックプロセスを実装します。

**主な内容**:

- 与信限度額の設定（取引先タイプ別：大口3,000万円、中口500万円、小口100万円）
- 与信チェックプロセス（利用可能額の確認）
- 与信枠の引当と解放（注文時の引当、キャンセル時の解放）
- 与信超過時の処理
- 取引実績に基づく与信限度額の自動調整
- 与信使用状況のモニタリング

**キーワード**: Credit Management, Credit Limit, Credit Check, Credit Reservation, Credit Monitoring

---

### [第9章：請求管理の実装](part4-09-invoice-management.md)

月次締め処理、請求書発行、入金管理を実装します。

**主な内容**:

- 月次締め処理（確定済み注文の集計）
- 請求書の自動生成（取引先別、月別）
- 請求金額の計算（注文合計、税金合計）
- 入金処理（入金記録、入金方法管理）
- 入金照合（請求金額と入金額の突合）
- 未入金アラート（入金期限超過の検知）
- 入金催促機能

**キーワード**: Invoice Management, Monthly Closing, Payment Recording, Payment Matching, Payment Reminder

---

### [第10章：返品処理の実装](part4-10-return-process.md)

返品受付から在庫への戻し入れ、返品金額処理までを実装します。

**主な内容**:

- 返品コマンド（注文ID、返品理由、返品明細）
- 返品可能期間の検証
- 在庫への戻し入れ（返品在庫の検証、在庫増加イベント発行）
- 返品金額の計算（元の単価で計算、割引・税金の逆計算）
- 返品後の与信枠解放
- 返品後の請求金額調整
- 返品理由の分析

**キーワード**: Return Process, Return Validation, Inventory Return, Refund Calculation, Credit Release

---

### [第11章：パフォーマンス最適化](part4-11-performance-optimization.md)

注文照会、与信チェック、Sagaの最適化を実施します。

**主な内容**:

- 注文照会のキャッシング（注文ステータスのキャッシュ、TTL設定）
- 与信チェックの高速化（与信情報のキャッシュ、イベント受信時の無効化）
- Sagaの最適化（ステップの並列化、タイムアウトの調整）
- データベースインデックスの最適化
- GraphQLクエリの最適化（DataLoaderパターン、N+1問題の解決）
- パフォーマンステスト（Gatling）

**キーワード**: Performance Optimization, Caching, Database Indexing, Query Optimization, Load Testing

---

### [第12章：運用とモニタリング](part4-12-operations-monitoring.md)

ビジネスメトリクス、Saga監視、与信管理の監視を実装します。

**主な内容**:

- ビジネスメトリクス（注文処理レート、平均注文金額、与信使用率、請求・入金状況）
- Sagaの監視（Sagaステータスダッシュボード、完了率、失敗率、補償処理発生率）
- 与信管理の監視（与信使用率、与信超過検知、与信限度額調整履歴）
- アラート設定（Saga失敗、与信超過、未入金超過）
- ダッシュボードの構築
- ログ分析

**キーワード**: Operations, Monitoring, Business Metrics, Saga Monitoring, Alerting, Dashboard

---

### [第13章：まとめと実践演習](part4-13-summary-exercises.md)

第4部で学んだ内容をまとめ、実践的な演習課題を提供します。

**主な内容**:

- 学んだこと（Sagaパターン、Money値オブジェクト、与信管理、請求管理）
- アーキテクチャ上の決定事項の振り返り
- 実践演習1：割引クーポン機能の追加
- 実践演習2：複数配送先への分割配送
- 実践演習3：定期注文機能の実装
- 次のステップ（第5部：発注管理サービスへ）

**キーワード**: Summary, Best Practices, Exercises, Next Steps, Advanced Topics

---

## 📚 第5部：発注管理サービスのケーススタディ

第5部では、第3部で構築した在庫管理システムに発注管理機能を追加し、調達プロセス全体をイベントソーシングで実装します。

**ケーススタディの規模**:

- 発注処理: 月間約3,000件の発注（200社の仕入先へ）
- 入荷検品: 月間約3,000件の入荷検品処理
- 仕入先管理: 200社の仕入先の評価・支払条件管理
- 支払管理: 3-way matching（発注書・入荷検品・請求書の照合）による支払承認
- 年間調達額: 100億円

**扱うトピック**:

- 仕入先管理と仕入先評価（Supplier集約）
- 発注プロセスと承認ワークフロー（PurchaseOrder集約）
- 入荷検品と差異管理（Receiving集約）
- 3-way matchingによる支払管理（SupplierPayment集約）
- 自動発注推奨機能（在庫レベルに基づく発注提案）
- 発注承認Saga（金額に応じた多段階承認）
- 入荷検品Saga（入荷→検品→在庫計上→支払）

---

### [第1章：イントロダクション - 発注管理サービスの全体像](part5-01-introduction.md)

第5部のケーススタディとして、発注管理サービスの全体像と要件を定義します。

**主な内容**:

- 第3部・第4部の振り返り（在庫管理・受注管理）
- D社の調達業務概要（月間3,000件、200社の仕入先、年間100億円）
- 発注プロセスフロー（発注申請→承認→発注→入荷→検品→在庫計上→請求照合→支払）
- 承認ワークフロー（金額に応じた多段階承認）
- 3-way matching（発注書・入荷検品書・請求書の照合）
- 技術的課題（在庫との連携、Saga実装、支払管理）

**キーワード**: Procurement Management, Purchase Order, Approval Workflow, 3-way Matching, Supplier Management

---

### [第2章：Read Modelスキーマの設計](part5-02-read-model-schema.md)

発注管理システムのRead Model（PostgreSQL）スキーマを設計します。

**主な内容**:

- 仕入先テーブル設計（仕入先基本情報、評価指標）
- 発注書テーブル設計（ヘッダー、明細、承認履歴）
- 入荷検品テーブル設計（入荷予定、検品結果、差異管理）
- 請求書テーブル設計（請求ヘッダー、請求明細）
- 支払テーブル設計（支払予定、支払実績）
- 3-way matching結果テーブル（照合結果、差異詳細）
- インデックス戦略とパフォーマンス最適化

**キーワード**: Read Model Schema, PostgreSQL, Flyway, Database Design, Indexing Strategy

---

### [第3章：発注管理に適したドメインデータ作成](part5-03-domain-data.md)

D社の調達業務に基づいたマスタデータとテストデータを作成します。

**主な内容**:

- 仕入先マスタ（200社、業種別分類、取引条件）
- 発注データサンプル（月間3,000件、金額分布）
- 入荷検品データ（合格率、不合格品、差異パターン）
- 請求書データ（支払条件、締め日パターン）
- Flywayによるマスタデータ投入
- シードデータSQL

**キーワード**: Master Data, Supplier Data, Purchase Order Data, Test Data, Flyway Migration

---

### [第4章：ドメインモデルの設計](part5-04-domain-model.md)

DDDに基づき、発注管理システムのドメインモデルを設計します。

**主な内容**:

- Bounded Contextの識別（仕入先管理、発注管理、入荷管理、支払管理）
- 集約の設計（Supplier、PurchaseOrder、Receiving、SupplierPayment）
- ドメインイベント設計（発注承認、入荷完了、支払承認）
- コンテキストマップと在庫管理との統合
- 値オブジェクト（SupplierId、OrderNumber、InvoiceNumber）

**キーワード**: DDD, Bounded Context, Aggregate Design, Domain Event, Context Map

---

### [第5章：複数集約の実装](part5-05-aggregate-implementation.md)

Pekko Persistenceを使用して、発注管理の複数集約を実装します。

**主な内容**:

- PurchaseOrderActor実装（発注申請→承認→発注）
- 承認ワークフロー（金額別承認者、多段階承認）
- ReceivingActor実装（入荷予定→検品→在庫反映）
- 検品処理（合格品・不合格品の分離）
- SupplierPaymentActor実装（請求受領→照合→支払）
- イベント駆動在庫連携（入荷完了→在庫増加）

**キーワード**: Pekko Persistence, EventSourcedBehavior, Purchase Order Aggregate, Receiving Aggregate, Payment Aggregate

---

### [第6章：Sagaパターンによる発注プロセスの実装](part5-06-saga-pattern.md)

Sagaパターンで発注プロセス全体を管理します。

**主な内容**:

- PurchaseOrderApprovalSaga（承認→発注→仕入先通知）
- 補償トランザクション（承認取り消し、発注キャンセル）
- ReceivingInspectionSaga（入荷→検品→在庫更新）
- PaymentSaga（請求→照合→支払実行）
- リトライとエスカレーション（支払失敗時の再試行）
- Sagaステート管理とイベント駆動遷移

**キーワード**: Saga Pattern, Orchestration, Compensating Transaction, Purchase Order Saga, Payment Saga

---

### [第7章：在庫管理との連携](part5-07-inventory-integration.md)

在庫管理Bounded Contextとの統合を実装します。

**主な内容**:

- イベント駆動在庫連携（入荷完了→在庫増加イベント）
- Pekko Persistence Queryによるイベント購読
- 冪等性の保証（重複イベント検出）
- 発注残管理（発注数量 - 入荷数量）
- 発注点管理と自動発注（EOQ計算、安全在庫）
- 需要予測（移動平均、線形回帰、季節変動）
- クロスコンテキスト整合性チェック

**キーワード**: Bounded Context Integration, Event-Driven, Pekko Persistence Query, Idempotency, Reorder Point, Demand Forecasting

---

### [第8章：パフォーマンス最適化](part5-08-performance-optimization.md)

発注管理システムのパフォーマンス最適化を実装します。

**主な内容**:

- Redisキャッシング（承認ルールキャッシュ、仕入先情報）
- Pekko Streamsバッチ処理（月次3-way matchingバッチ）
- ストリーム処理（スロットリング、バックプレッシャー）
- データベースインデックス最適化
- パフォーマンスメトリクス（処理時間、スループット）

**キーワード**: Performance Optimization, Redis Caching, Pekko Streams, Batch Processing, Throttling, Backpressure

---

### [第9章：運用とモニタリング](part5-09-operations-monitoring.md)

発注管理システムの運用監視基盤を構築します。

**主な内容**:

- ビジネスメトリクス（月間発注件数、目標達成率）
- 承認プロセスメトリクス（承認待ち件数、承認時間）
- 入荷検品メトリクス（検品完了率、不合格率）
- 支払メトリクス（支払金額、3-way matching成功率）
- Saga監視（長時間実行Saga検出、失敗率）
- アラート管理（遅延検知、エスカレーション）
- 仕入先評価レポート（納期遵守率、品質スコア）

**キーワード**: Monitoring, Business Metrics, Prometheus, Grafana, Saga Monitoring, Supplier Evaluation

---

### [第10章：高度なトピック](part5-10-advanced-topics.md)

発注管理の発展的な機能を実装します。

**主な内容**:

- 高度な需要予測（指数平滑法、Holt-Winters法、ARIMA）
- アンサンブル予測（複数手法の統合）
- 仕入先選定アルゴリズム（多基準評価、スコアリング）
- 分散発注戦略（リスク分散、集中度管理）
- 複数通貨対応（MultiCurrencyMoney、為替レート管理）
- 為替ヘッジ戦略（先渡契約、通貨オプション）
- 国際調達（輸入税計算、CIF価額、関税）

**キーワード**: Demand Forecasting, Exponential Smoothing, Holt-Winters, Supplier Selection, Multi-Currency, Forex Hedging, International Tax

---

### [第11章：まとめと実践演習](part5-11-summary-exercises.md)

第5部で学んだ内容を振り返り、実践演習を通じて理解を深めます。

**主な内容**:

- **学んだことの振り返り**:
  - 発注管理の実装（承認ワークフロー、入荷検収、3-way matching）
  - 在庫との連携（イベント駆動、発注点管理、自動発注）
  - Sagaパターン（承認Saga、検収Saga、支払Saga）
  - パフォーマンス最適化（Redis、Pekko Streams、インデックス）
  - 運用監視（ビジネスメトリクス、Saga監視、仕入先評価）
  - 高度なトピック（需要予測、仕入先選定、複数通貨）
- **実践演習**（4つの課題）:
  - 演習1: 発注承認ワークフローの拡張（複数承認者、承認履歴、動的ルート）
  - 演習2: 在庫最適化機能（発注点自動調整、安全在庫、ABC分析）
  - 演習3: 仕入先評価システム（納期遵守率、品質スコア、総合評価ダッシュボード）
  - 演習4: グローバル調達機能（複数通貨、為替ヘッジ、国際輸送トラッキング）
- **次のステップ**:
  - より複雑なビジネスルール（ロット管理、有効期限、返品処理）
  - 他Bounded Contextとの統合（生産計画、品質管理、会計管理）
  - サプライチェーン最適化（可視化、ボトルネック分析、リードタイム短縮）

**演習で学べること**:

- 複数承認者フローの実装
- ABC分析と発注戦略
- 仕入先パフォーマンス評価
- 国際調達と通関処理

**キーワード**: Summary, Practical Exercises, Approval Workflow, ABC Analysis, Supplier Evaluation, Global Procurement, Next Steps

---

## 📚 第6部：会計サービスのケーススタディ

第6部では、イベント駆動による会計処理を実装し、ビジネスイベントから仕訳を自動生成します。

**ケーススタディの規模**:

- 仕訳処理: 月間約66,000件の仕訳（受注・発注・入金・支払から自動生成）
- 年間売上: 150億円
- 月次決算: 毎月1回の自動決算処理
- 年次決算: 減価償却、棚卸資産評価、税務申告
- 勘定科目: 500科目

**扱うトピック**:

- 勘定科目体系管理（ChartOfAccounts集約）
- イベント駆動仕訳生成（JournalEntry集約）
- 総勘定元帳管理（GeneralLedger集約）
- 財務諸表作成（FinancialStatement集約）
- 売掛金・買掛金管理（AccountsReceivable集約）
- 月次決算Saga（試算表→損益計算書→貸借対照表）
- 年次決算処理（減価償却、繰延税金、剰余金配当）
- 財務分析と経営指標（ROA、ROE、流動比率など）

---

### [第1章：イントロダクション - 会計サービスの要件定義](part6-01-introduction.md)

第6部のケーススタディとして、会計サービスの全体像と要件を定義します。

**主な内容**:

- D社の事業概要（年商150億円、月間66,000件の仕訳）
- 会計処理フロー（イベント受信→仕訳生成→総勘定元帳→試算表→財務諸表）
- イベント駆動会計の利点（自動仕訳生成、監査証跡、リアルタイム集計）
- 8つのステップ（受注→仕訳→元帳→試算表→決算整理→財務諸表→分析→決算）
- パフォーマンス要件（仕訳生成100ms、試算表5秒、財務諸表10秒）

**キーワード**: Accounting Service, Event-Driven Accounting, Journal Entry, General Ledger, Financial Statements

---

### [第2章：Read Modelスキーマの設計](part6-02-read-model-schema.md)

会計システムのRead Model（PostgreSQL）とイベントストア（DynamoDB）のスキーマを設計します。

**主な内容**:

- 勘定科目マスタ（500科目の階層構造）
- 仕訳テーブル（月間66,000件の仕訳データ）
- 総勘定元帳テーブル（年度別パーティショニング）
- 補助元帳テーブル（売掛金・買掛金の明細管理）
- 試算表・財務諸表テーブル（集計結果のキャッシュ）
- DynamoDBイベントテーブル（会計イベントの永続化）
- インデックス戦略とクエリ最適化

**キーワード**: Read Model Schema, Chart of Accounts, Journal Entry, General Ledger, Trial Balance, Financial Statements

---

### [第3章：会計に適したドメインデータ作成](part6-03-domain-data.md)

D社の会計データに基づいたマスタデータとテストデータを作成します。

**主な内容**:

- 勘定科目体系（資産150、負債80、純資産20、収益100、費用150）
- 期首残高データ（FY2024開始時の貸借対照表）
- イベント駆動仕訳生成（受注→売上仕訳、発注→仕入仕訳）
- 月次仕訳サンプル（約5,500件/月）
- Flywayマイグレーションとシードデータ

**キーワード**: Chart of Accounts, Opening Balance, Event-Driven Journal Entry, Seed Data

---

### [第4章：ドメインモデルの設計](part6-04-domain-model.md)

DDDに基づき、会計システムのドメインモデルを設計します。

**主な内容**:

- 5つの主要集約（JournalEntry、GeneralLedger、FinancialStatement、AccountsReceivable、AccountsPayable）
- ドメインイベント設計（仕訳計上、元帳転記、決算整理）
- 値オブジェクト（AccountCode、DebitCredit、Money、FiscalPeriod）
- ビジネスルール（借方＝貸方、勘定科目検証、承認ワークフロー）
- コンテキストマップ（在庫・受注・発注との統合）

**キーワード**: DDD, Aggregate Design, Journal Entry, General Ledger, Value Object, Business Rules

---

### [第5章：複数集約の実装](part6-05-aggregate-implementation.md)

Pekko Persistenceを使用して、会計システムの複数集約を実装します。

**主な内容**:

- JournalEntryActorの実装（仕訳計上、承認、取消）
- 仕訳バリデーション（借方＝貸方、勘定科目存在確認）
- 承認ワークフロー（100万円以上は承認必須）
- GeneralLedgerActorの実装（元帳転記、残高計算）
- イベントハンドラとステート管理
- ユニットテスト（ScalaTest + Pekko TestKit）

**キーワード**: Pekko Persistence, EventSourcedBehavior, Journal Entry Aggregate, General Ledger, Approval Workflow

---

### [第6章：イベント駆動会計処理](part6-06-event-driven-accounting.md)

ビジネスイベントから仕訳を自動生成するイベント駆動会計を実装します。

**主な内容**:

- ビジネスイベント購読（OrderConfirmed、PurchaseOrderReceived、PaymentCompleted）
- 仕訳自動生成ルール（売上仕訳、仕入仕訳、入金仕訳、支払仕訳）
- 冪等性の保証（イベントIDによる重複検出）
- 監査証跡（全仕訳とイベントの紐付け）
- 統合テスト（E2Eフロー検証）

**キーワード**: Event-Driven Accounting, Automatic Journal Entry, Idempotency, Audit Trail, Integration Test

---

### [第7章：決算処理の実装](part6-07-closing-process.md)

月次決算と年次決算のプロセスをSagaパターンで実装します。

**主な内容**:

- MonthlyClosingSagaの実装（7ステップの月次決算）
- AnnualClosingSagaの実装（12ステップの年次決算）
- 減価償却計算（定額法、定率法）
- 決算整理仕訳（売上原価算定、減価償却、引当金）
- 期末棚卸と評価（先入先出法、移動平均法）
- 繰越処理（次年度への残高繰越）

**キーワード**: Closing Process, Monthly Closing Saga, Annual Closing Saga, Depreciation, Adjusting Entry

---

### [第8章：財務分析機能](part6-08-financial-analysis.md)

財務諸表から経営指標を算出し、財務分析を実装します。

**主な内容**:

- 収益性指標（売上高総利益率、営業利益率、ROA、ROE）
- 安全性指標（流動比率、当座比率、自己資本比率）
- 効率性指標（総資産回転率、売上債権回転率、棚卸資産回転率）
- 予実管理（予算登録、実績比較、差異分析、着地予想）
- 予測サービス（移動平均、線形回帰による売上予測）
- GraphQL APIによる分析データ公開

**キーワード**: Financial Analysis, Profitability Indicators, Safety Indicators, Efficiency Indicators, Budget vs Actual, Forecasting

---

### [第9章：パフォーマンス最適化](part6-09-performance-optimization.md)

会計システムのパフォーマンス最適化を実装します。

**主な内容**:

- Pekko Streamsによる仕訳生成バッチ（日次2,200件を10倍高速化）
- Materialized Viewによる試算表高速化（60倍高速化）
- PostgreSQLパーティショニング（年度別テーブル分割で5倍高速化）
- Redisキャッシング（勘定科目マスタ、残高情報）
- インデックス最適化（勘定科目、会計期間、仕訳番号）
- パフォーマンスメトリクス（Prometheus + Grafana）

**キーワード**: Performance Optimization, Pekko Streams, Materialized View, Partitioning, Redis Caching

---

### [第10章：運用とモニタリング](part6-10-operations-monitoring.md)

会計システムの運用監視基盤を構築します。

**主な内容**:

- ビジネスメトリクス（月間仕訳件数、承認率、エラー率）
- 決算処理時間（月次決算30分以内、試算表5秒以内）
- 債権債務状況（売掛金・買掛金残高、エージング分析）
- 監査証跡（全イベント履歴、仕訳とトランザクションの紐付け）
- 内部統制（職務分掌、アクセス制御、決算後修正制限）
- アラート管理（仕訳エラー、承認遅延、決算処理遅延）

**キーワード**: Monitoring, Business Metrics, Audit Trail, Internal Control, Alerting

---

### [第11章：高度なトピック](part6-11-advanced-topics.md)

会計システムの発展的な機能を実装します。

**主な内容**:

- 複数通貨会計（外貨建取引、TTM/TTS/TTB為替レート、期末評価替え）
- 連結会計（子会社財務諸表合算、内部取引相殺消去、非支配株主持分）
- 管理会計（部門別損益、プロジェクト別損益、部門間配賦）
- セグメント別財務諸表（事業部別P/L、地域別売上分析）
- キャッシュフロー計算書（間接法による作成）
- 税効果会計（繰延税金資産・負債の計算）

**キーワード**: Multi-Currency Accounting, Consolidated Accounting, Management Accounting, Segment Reporting, Cash Flow Statement

---

### [第12章：まとめと実践演習](part6-12-summary-exercises.md)

第6部で学んだ内容を振り返り、実践演習を通じて理解を深めます。

**主な内容**:

- **学んだことの振り返り**:
  - イベント駆動会計（ビジネスイベントから仕訳自動生成）
  - 財務諸表作成（損益計算書、貸借対照表、キャッシュフロー計算書）
  - 決算処理（月次決算、年次決算、減価償却、棚卸資産評価）
  - 債権債務管理（売掛金・買掛金のエージング分析、延滞管理）
  - パフォーマンス最適化（10-250倍の高速化達成）
- **実践演習**（4つの課題）:
  - 演習1: 消費税申告データ作成（課税売上集計、仮受消費税・仮払消費税計算）
  - 演習2: キャッシュフロー計算書作成（間接法による営業・投資・財務CF）
  - 演習3: 経営ダッシュボード実装（GraphQL API、リアルタイム経営指標）
  - 演習4: 予実管理機能強化（詳細な差異分析、予測アルゴリズム改善）
- **次のステップ**:
  - より高度な会計処理（リース会計、税効果会計、退職給付会計）
  - 他Bounded Contextとの統合（人事給与、固定資産管理、予算管理）
  - BI・データ分析（OLAP、経営ダッシュボード、予測分析）

**演習で学べること**:

- 税務申告データの作成
- キャッシュフロー分析
- 経営ダッシュボードの実装
- 予実管理と予測

**キーワード**: Summary, Practical Exercises, Consumption Tax Return, Cash Flow Statement, Management Dashboard, Budget Management

---

## 📚 第7部：共用データ管理サービスのケーススタディ

第7部では、複数のBounded Contextで共有されるマスターデータ（商品、顧客、仕入先、勘定科目など）を一元管理する**共用データ管理サービス（Shared Data Management Service）**をイベントソーシングとCQRSパターンで実装します。

**ケーススタディの規模**:

- 商品数: 5,000種類（SKU）
- 顧客数: 430社（月間50,000件の受注）
- 仕入先数: 200社（月間3,000件の発注）
- 勘定科目数: 500科目
- 月間マスター変更: 約800件（商品情報、価格改定、取引先情報など）

**扱うトピック**:

- マスターデータのイベントソーシング（全変更履歴の追跡、時点復元）
- イベント駆動マスターデータ同期（Single Source of Truth、結果整合性）
- データガバナンス（変更承認ワークフロー、データ品質管理）
- GraphQL API（柔軟なクエリ、DataLoaderによるN+1問題解決）
- パフォーマンス最適化（3層キャッシュ、Materialized View）
- 運用とモニタリング（同期状況監視、データ品質メトリクス）

---

### [第1章：イントロダクション - 共用データ管理サービスの概要](part7-01-introduction.md)

複数のBounded Contextで共有されるマスターデータ管理の課題と解決策を説明します。

**主な内容**:

- マスターデータ管理の課題（データ分散、整合性欠如、変更伝播の困難）
- イベントソーシングによるマスターデータ管理（Single Source of Truth、完全な変更履歴）
- Bounded Context間の連携（在庫管理、受注管理、発注管理、会計サービス）
- D社のマスターデータ管理要件（商品5,000 SKU、月間800件の変更、承認ワークフロー）

**キーワード**: Master Data Management, Event Sourcing, Single Source of Truth, Bounded Context Integration

---

### [第2章：Read Modelスキーマの設計](part7-02-read-model-schema.md)

共用データ管理システムのRead Model（PostgreSQL）スキーマを設計します。

**主な内容**:

- 企業マスタ設計（自社・取引先企業情報）
- 商品マスタ設計（商品情報、価格履歴、有効期間管理）
- 勘定科目マスタ設計（階層構造、科目区分、補助科目）
- 部門・社員マスタ設計
- コードマスタ設計（税率、支払条件、配送方法）
- DynamoDBイベントストア設計（Product Events、AccountSubject Events）

**キーワード**: Read Model Schema, Master Data, Valid Period, Version Control, PostgreSQL, DynamoDB

---

### [第3章：共用データ管理に適したドメインデータ作成](part7-03-domain-data.md)

D社のマスターデータに基づいたテストデータを作成します。

**主な内容**:

- 企業マスタデータ（D社および取引先430社、仕入先200社）
- 商品マスタデータ（5,000 SKU、カテゴリ分類、価格情報）
- 勘定科目マスタデータ（500科目の階層構造）
- コードマスタデータ（税率、支払条件、配送方法）
- 変更履歴データ（月間800件のマスター変更パターン）
- Flywayマイグレーションとシードデータ

**キーワード**: Master Data, Test Data, Seed Data, Flyway Migration, Business Context

---

### [第4章：ドメインモデルの設計](part7-04-domain-model.md)

DDDに基づき、共用データ管理システムのドメインモデルを設計します。

**主な内容**:

- Product集約（商品情報、価格履歴、有効期間管理）
- AccountSubject集約（勘定科目、階層構造、科目区分）
- CodeMaster集約（共通コード管理）
- マスターデータのライフサイクル（下書き→承認待ち→有効→停止→廃止）
- ValidPeriod値オブジェクト（有効期間管理、重複チェック）
- ドメインイベント設計（ProductCreated、ProductPriceChanged、AccountSubjectCreated）

**キーワード**: DDD, Aggregate Design, Value Object, Domain Event, Lifecycle Management, Valid Period

---

### [第5章：複数集約の実装](part7-05-aggregate-implementation.md)

Pekko Persistenceを使用して、マスターデータ管理の複数集約を実装します。

**主な内容**:

- ProductActorの実装（商品作成、価格変更、情報更新）
- ビジネスルール（商品コード一意性、価格検証、有効期間重複チェック）
- AccountSubjectActorの実装（勘定科目作成、階層構造管理）
- 階層構造の整合性検証（親勘定科目の同一種別チェック）
- CodeMasterActorの実装（コード追加、更新、無効化）
- イベントハンドラとステート管理

**キーワード**: Pekko Persistence, EventSourcedBehavior, Product Aggregate, Account Subject Aggregate, Business Rules

---

### [第6章：イベント駆動マスターデータ同期](part7-06-master-data-integration.md)

イベント駆動によるマスターデータの各Bounded Contextへの同期を実装します。

**主な内容**:

- マスターデータ変更イベントの発行（ProductCreated、ProductPriceChanged）
- 他のBounded Contextでのイベント購読（在庫管理、受注管理、会計サービス）
- Materialized Viewによる参照データ最適化
- 結果整合性の保証（イベント順序保証、冪等性、リトライ）
- Pekko Cluster Pub/Subによるイベント配信
- 同期遅延の可視化

**キーワード**: Event-Driven Synchronization, Eventual Consistency, Materialized View, Idempotency, Pekko Pub/Sub

---

### [第7章：マスターデータ変更承認ワークフロー](part7-07-approval-workflow.md)

重要なマスターデータ変更に対する承認ワークフローをSagaパターンで実装します。

**主な内容**:

- 承認が必要な変更の定義（価格変更、勘定科目変更）
- 変更申請データモデル（ChangeRequest集約）
- PriceChangeApprovalSagaの実装（申請→通知→承認待ち→承認/却下→反映）
- 承認者への通知機能（メール、Slack）
- 却下時の処理（理由記録、申請者通知）
- タイムアウト処理（一定期間未承認の場合のエスカレーション）

**キーワード**: Approval Workflow, Saga Pattern, Change Request, Notification, Escalation

---

### [第8章：まとめと実践課題](part7-08-summary-and-practice.md)

第7部前半（第1章〜第7章）で学んだ内容を振り返り、実践課題を提示します。

**主な内容**:

- マスターデータのイベントソーシングの振り返り
- イベント駆動同期パターンの理解
- 承認ワークフローの実装
- 実践課題（データ品質チェック機能の追加、GraphQL APIの基本実装）

**キーワード**: Summary, Practical Exercises, Event Sourcing, Approval Workflow

---

### [第9章：GraphQL APIの実装](part7-09-graphql-api.md)

Sangria GraphQLを使用した柔軟なマスターデータAPIを実装します。

**主な内容**:

- 商品マスターAPI（Product Schema、Query、Mutation）
- 勘定科目マスターAPI（AccountSubject Schema、階層構造クエリ）
- 変更申請API（ChangeRequest Schema、承認/却下）
- DataLoaderによるN+1問題解決（バッチクエリ、100倍高速化）
- ページング実装（Relay Cursor Connections仕様）
- フィルタリングとソート
- Pekko HTTP統合とGraphQL Playground

**キーワード**: GraphQL, Sangria, DataLoader, N+1 Problem, Relay Connections, Pekko HTTP

---

### [第10章：パフォーマンス最適化](part7-10-performance-optimization.md)

マスターデータ参照の高速化とスケーラビリティ向上を実装します。

**主な内容**:

- 3層キャッシング戦略（Caffeine + Redis + PostgreSQL）
  - インメモリキャッシュ（Caffeine、ヒット率80%、1ms）
  - 分散キャッシュ（Redis、ヒット率15%、10ms）
  - データベース（PostgreSQL、5%、100ms）
- イベント駆動キャッシュ無効化
- Materialized Viewによるクエリ最適化（有効商品と価格のView）
- インデックス戦略（商品コード、有効期間、階層構造）
- Gatling負荷テスト（5,000商品、月間800件の変更、430社同時アクセス）

**キーワード**: Performance Optimization, Multi-Level Caching, Caffeine, Redis, Materialized View, Indexing, Gatling

---

### [第11章：運用とモニタリング](part7-11-operations-monitoring.md)

マスターデータ管理の安定運用に必要なモニタリング基盤を構築します。

**主な内容**:

- ビジネスメトリクス（商品マスター総数5,000、月間変更800件、承認状況）
- データ品質メトリクス（完全性スコア、重複データ検出、参照整合性エラー）
- 同期状況モニタリング（イベント処理遅延、未処理イベント件数、各コンテキスト同期状態）
- Grafanaダッシュボード（マスターデータ統計、変更頻度、データ品質、同期状況）
- Prometheusアラートルール（同期遅延、データ品質低下、承認遅延）
- データ整合性チェッカー（定期的なマスターとイベントストアの整合性検証）

**キーワード**: Monitoring, Business Metrics, Data Quality, Prometheus, Grafana, Alerting, Integrity Check

---

### [第12章：まとめと演習課題](part7-12-summary-and-exercises.md)

第7部で学んだ内容を総括し、実践的な演習課題を提供します。

**主な内容**:

- **学んだこと**:
  - マスターデータのイベントソーシング（全変更履歴、時点復元、監査証跡）
  - イベント駆動マスターデータ同期（Single Source of Truth、結果整合性、Materialized View）
  - データガバナンス（変更承認ワークフロー、データ品質管理、アクセス制御）
  - GraphQL API（柔軟なクエリ、DataLoader、ページング）
  - パフォーマンス最適化（3層キャッシュ、Materialized View、インデックス）
  - 運用監視（ビジネスメトリクス、データ品質、同期状況）
- **実践演習**（4つの課題）:
  - 演習1: 商品価格変更ワークフロー（申請、承認、反映、他サービス伝播）
  - 演習2: 勘定科目の階層構造管理（親変更、整合性検証、会計サービス参照）
  - 演習3: マスターデータ同期の監視（イベント発行、受信確認、ダッシュボード作成）
  - 演習4: 過去時点のマスターデータ復元（イベントリプレイ、価格復元、監査対応）
- **次のステップ**:
  - より高度なマスターデータ管理（バージョン分岐、グローバルマスター、データリネージ）
  - 外部システム統合（マスターインポート/エクスポート、EDI連携、API Gateway）
  - AI/ML活用（重複検出自動化、データ品質スコアリング、異常値検出、変更予測）

**演習で学べること**:

- 承認ワークフローの実装
- 階層構造データの管理
- イベント駆動同期の監視
- 時点復元機能の実装

**キーワード**: Summary, Practical Exercises, Event Sourcing, Point-in-Time Restoration, Data Lineage, AI/ML Enhancement

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

4. **第4部で分散トランザクションを学ぶ**: 在庫管理システムに受注管理を追加し、Sagaパターンによる分散トランザクションの実装を学びます。

5. **第5部で調達プロセスを学ぶ**: 仕入先管理から発注、入荷検品、支払管理までの調達プロセス全体をイベントソーシングで実装します。

6. **第6部で会計処理を学ぶ**: ビジネスイベントから仕訳を自動生成し、総勘定元帳、財務諸表、決算処理を実装します。

7. **自分なりの拡張を試す**: 演習課題を超えて、独自の機能（ロット管理、賞味期限管理、他のBounded Contextとの統合など）を追加してみましょう。

8. **コミュニティに参加する**: Scala Users Group JapanやPekko Discordで質問したり、知見を共有したりしましょう。

### 学習の流れ

```
第1部：環境構築編 (10章)
  ↓ 環境セットアップ完了
第2部：サービス構築編 (11章)
  ↓ 単一集約の実装理解完了
第3部：在庫管理サービスのケーススタディ (13章)
  ↓ 複数集約を含む実践的な実装完了
第4部：受注管理サービスのケーススタディ (13章)
  ↓ Sagaパターンによる分散トランザクション完了
第5部：発注管理サービスのケーススタディ (11章)
  ↓ 調達プロセスと3-way matching完了
第6部：会計サービスのケーススタディ (12章)
  ↓ イベント駆動会計と財務諸表作成完了
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
├── part3-13-summary.md                   # 第3部 第13章
│
├── 【第4部：受注管理サービスのケーススタディ】
├── part4-01-overview.md                  # 第4部 第1章
├── part4-02-read-model-schema.md         # 第4部 第2章
├── part4-03-domain-data.md               # 第4部 第3章
├── part4-04-domain-model.md              # 第4部 第4章
├── part4-05-aggregate-implementation.md  # 第4部 第5章
├── part4-06-saga-implementation.md       # 第4部 第6章
├── part4-07-money-tax.md                 # 第4部 第7章
├── part4-08-credit-management.md         # 第4部 第8章
├── part4-09-invoice-management.md        # 第4部 第9章
├── part4-10-return-process.md            # 第4部 第10章
├── part4-11-performance-optimization.md  # 第4部 第11章
├── part4-12-operations-monitoring.md     # 第4部 第12章
└── part4-13-summary-exercises.md         # 第4部 第13章
│
├── 【第5部：発注管理サービスのケーススタディ】
├── part5-01-introduction.md              # 第5部 第1章
├── part5-02-read-model-schema.md         # 第5部 第2章
├── part5-03-domain-data.md               # 第5部 第3章
├── part5-04-domain-model.md              # 第5部 第4章
├── part5-05-aggregate-implementation.md  # 第5部 第5章
├── part5-06-saga-pattern.md              # 第5部 第6章
├── part5-07-inventory-integration.md     # 第5部 第7章
├── part5-08-performance-optimization.md  # 第5部 第8章
├── part5-09-operations-monitoring.md     # 第5部 第9章
├── part5-10-advanced-topics.md           # 第5部 第10章
├── part5-11-summary-exercises.md         # 第5部 第11章
│
├── 【第6部：会計サービスのケーススタディ】
├── part6-01-introduction.md              # 第6部 第1章
├── part6-02-read-model-schema.md         # 第6部 第2章
├── part6-03-domain-data.md               # 第6部 第3章
├── part6-04-domain-model.md              # 第6部 第4章
├── part6-05-aggregate-implementation.md  # 第6部 第5章
├── part6-06-event-driven-accounting.md   # 第6部 第6章
├── part6-07-closing-process.md           # 第6部 第7章
├── part6-08-financial-analysis.md        # 第6部 第8章
├── part6-09-performance-optimization.md  # 第6部 第9章
├── part6-10-operations-monitoring.md     # 第6部 第10章
├── part6-11-advanced-topics.md           # 第6部 第11章
├── part6-12-summary-exercises.md         # 第6部 第12章
│
├── 【第7部：共用データ管理サービスのケーススタディ】
├── part7-01-introduction.md              # 第7部 第1章
├── part7-02-read-model-schema.md         # 第7部 第2章
├── part7-03-domain-data.md               # 第7部 第3章
├── part7-04-domain-model.md              # 第7部 第4章
├── part7-05-aggregate-implementation.md  # 第7部 第5章
├── part7-06-master-data-integration.md   # 第7部 第6章
├── part7-07-approval-workflow.md         # 第7部 第7章
├── part7-08-summary-and-practice.md      # 第7部 第8章
├── part7-09-graphql-api.md               # 第7部 第9章
├── part7-10-performance-optimization.md  # 第7部 第10章
├── part7-11-operations-monitoring.md     # 第7部 第11章
└── part7-12-summary-and-exercises.md     # 第7部 第12章
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

最終更新: 2025-12-03
