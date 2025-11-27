# Apache Pekkoを使用したCQRS/EventSourcingサービス開発

本シリーズでは、Apache Pekko（旧Akka）を使用したCQRS（コマンドクエリ責任分離）とイベントソーシングの実装について、環境構築から本番デプロイまで包括的に解説します。

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

1. **まず第2部の第1章から順番に読む**: 各章は前の章の内容を前提としているため、順番に読むことを推奨します。

2. **実際にコードを動かしてみる**: 理論だけでなく、GitHubリポジトリをクローンして実際に動かしてみることで理解が深まります。

3. **自分なりの拡張を試す**: UserAccount以外の集約（Order、Productなど）を追加してみることで、学んだことを定着させられます。

4. **コミュニティに参加する**: Scala Users Group JapanやPekko Discordで質問したり、知見を共有したりしましょう。

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
