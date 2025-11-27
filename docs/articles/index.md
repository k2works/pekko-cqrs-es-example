# Apache Pekkoを使用したCQRS/EventSourcingサービス開発

本シリーズでは、Apache Pekko（旧Akka）を使用したCQRS（コマンドクエリ責任分離）とイベントソーシングの実装について、環境構築から本番デプロイまで包括的に解説します。

## シリーズ構成

本シリーズは**2部構成・全21章**で構成されています：

- **第1部：環境構築編**（全10章） - LocalStackを使用した開発環境の構築
- **第2部：サービス構築編**（全11章） - ドメインモデル設計から本番デプロイまで

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

## 🚀 学習の進め方

### 推奨される学習順序

1. **第1部から順番に読む**: まず環境構築編（第1部）で開発環境をセットアップし、サービス構築編（第2部）で実装の詳細を学びます。各章は前の章の内容を前提としているため、順番に読むことを推奨します。

2. **実際にコードを動かしてみる**: 理論だけでなく、GitHubリポジトリをクローンして実際に動かしてみることで理解が深まります。
   - 第1部で環境を構築
   - E2Eテストで動作確認
   - 第2部で各コンポーネントの理解を深める

3. **自分なりの拡張を試す**: UserAccount以外の集約（Order、Productなど）を追加してみることで、学んだことを定着させられます。

4. **コミュニティに参加する**: Scala Users Group JapanやPekko Discordで質問したり、知見を共有したりしましょう。

### 学習の流れ

```
第1部：環境構築編 (10章)
  ↓ 環境セットアップ完了
第2部：サービス構築編 (11章)
  ↓ 実装理解完了
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
└── part2-11-conclusion.md                # 第2部 第11章
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

最終更新: 2025-11-27
