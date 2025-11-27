# 第1部 環境構築編 - 第10章：まとめと次のステップ

## はじめに

第1部「環境構築編」では、Apache PekkoによるCQRS/Event Sourcingシステムの開発環境を構築し、基本的な操作方法を学びました。本章では、これまでの学習内容を振り返り、第2部「サービス構築編」へと進むための準備を行います。

---

## 10.1 第1部で学んだこと

### 10.1.1 到達目標の確認

第1章で設定した到達目標を振り返ります：

- ✅ **ローカル環境でCQRS/ESシステムを起動できる**
  - `./scripts/run-single.sh up` で全サービスを起動
  - Command API、Query API、LocalStack、PostgreSQLが正常に稼働

- ✅ **GraphQL Playgroundで基本的な操作ができる**
  - Mutationでユーザー作成
  - Queryでデータ取得
  - スキーマの確認とドキュメント参照

- ✅ **システム全体のデータフローを理解している**
  - Command → Event → DynamoDB → Streams → Lambda → PostgreSQL → Query
  - 結果整合性（Eventual Consistency）の理解

---

### 10.1.2 章ごとの振り返り

#### 第1章：イントロダクション

**学んだこと**:
- CQRS/Event Sourcingの基本概念と利点
- Apache Pekkoを選択した理由（Akkaからの移行）
- ユーザーアカウント管理システムの機能概要

**重要なポイント**:
- CQRS/Event Sourcingは、スケーラビリティと監査証跡を提供
- Apache Pekkoは完全なオープンソース（Apache License 2.0）
- イベント駆動アーキテクチャによる柔軟なシステム設計

---

#### 第2章：アーキテクチャ概要

**学んだこと**:
- CQRS（コマンドクエリ責任分離）の設計思想
- Event Sourcingパターンの実装方法
- システム全体のコンポーネント構成

**PlantUML図で理解したアーキテクチャ**:
```
Command Side (DynamoDB) ⇄ DynamoDB Streams ⇄ Lambda ⇄ Query Side (PostgreSQL)
```

**重要なポイント**:
- 書き込みと読み取りを完全に分離
- イベントが唯一の真実の源（Source of Truth）
- 非同期処理による結果整合性

---

#### 第3章：技術スタックの選定

**学んだこと**:
- Scala 3の強力な型システムと関数型プログラミング
- Apache Pekkoのイベントソーシングサポート
- DynamoDB（Event Store）とPostgreSQL（Read Model）の使い分け
- LocalStackによるローカル開発環境
- GraphQL（Sangria）とProtocol Buffersの採用理由

**重要なポイント**:
- 型安全性を最優先した技術選定
- スケーラビリティと開発者体験のバランス
- 本番実績のある技術を採用

---

#### 第4章：開発環境のセットアップ

**学んだこと**:
- Java、SBT、Dockerのインストール
- プロジェクトのクローンとビルド
- LocalStackのセットアップとDynamoDBテーブル作成
- PostgreSQLのFlywayマイグレーション
- Lambda関数のデプロイとイベントソースマッピング

**重要なポイント**:
- 環境構築は自動化スクリプトで簡単に実行可能
- LocalStackにより実際のAWSなしで開発が可能
- Flywayによるデータベーススキーマのバージョン管理

---

#### 第5章：設定管理の体系化

**学んだこと**:
- 階層化された設定ファイル（application.conf、pcqrses.conf、pekko.conf、j5ik2o.conf）
- 環境変数による柔軟な設定の上書き（`${?VARIABLE}`パターン）
- Protocol BuffersとCBORのシリアライゼーション戦略

**重要なポイント**:
- 関心の分離による保守性向上
- 開発/テスト/本番環境への対応
- Typesafe Configによる型安全な設定管理

---

#### 第6章：初回起動とヘルスチェック

**学んだこと**:
- `./scripts/run-single.sh up` による一括起動
- 各サービスのヘルスチェック方法
- GraphQL Playgroundの使い方
- 基本的なMutation/Queryの実行

**重要なポイント**:
- 起動プロセスの理解（インフラ → DB → Lambda → アプリ）
- Command APIとQuery APIの役割分担
- GraphQL Playgroundによる対話的な開発

---

#### 第7章：E2Eテストによる動作確認

**学んだこと**:
- E2Eテストスクリプト（`./scripts/test-e2e.sh`）の実行
- 5つのテストフェーズ（ヘルスチェック、Mutation、待機、Query、検証）
- リトライ機能による結果整合性の検証
- 環境変数によるテストのカスタマイズ

**重要なポイント**:
- CQRS/Event Sourcingの完全なデータフローを自動検証
- 結果整合性を考慮した待機とリトライ
- CI/CDパイプラインへの統合が可能

---

#### 第8章：トラブルシューティング

**学んだこと**:
- LocalStack、Lambda、PostgreSQL、DynamoDBの一般的な問題と解決方法
- ログの確認方法（Docker、Lambda CloudWatch Logs）
- データベースの直接クエリによるデバッグ
- 環境のリセット手順

**重要なポイント**:
- ログは問題解決の鍵
- 段階的な診断（サービスごとに切り分け）
- 診断チェックリストによる体系的なアプローチ

---

#### 第9章：開発ワークフローの確立

**学んだこと**:
- コード品質管理（フォーマット、リント、テスト）
- データベース操作（マイグレーション、DAO生成）
- 環境管理（起動、停止、ログ確認）
- 新機能開発のワークフロー

**重要なポイント**:
- `sbt fmt && sbt lint && sbt test` がコミット前の標準フロー
- Flywayマイグレーション → DAO再生成のサイクル
- 継続的なフィードバックによる開発効率化

---

## 10.2 実践的なスキルの獲得

### 10.2.1 手を動かして学んだこと

第1部を通じて、以下の実践的なスキルを獲得しました：

#### 環境構築スキル

- Docker ComposeによるマルチコンテナアプリケーションのSetup
- LocalStackを使用したAWSサービスのローカルエミュレーション
- Flywayによるデータベースマイグレーション管理
- SBTによるScalaプロジェクトのビルド

#### 開発スキル

- GraphQL APIの操作（Mutation/Query）
- E2Eテストの実行と結果の解釈
- ログ分析によるデバッグ
- 環境変数による設定の柔軟な管理

#### トラブルシューティングスキル

- Dockerコンテナの診断と再起動
- DynamoDB/PostgreSQLのデータ確認
- Lambda関数のログ分析
- ポート競合やリソース不足の解決

---

### 10.2.2 理解したアーキテクチャパターン

#### CQRS（コマンドクエリ責任分離）

```
Command Side              Query Side
┌─────────────┐          ┌─────────────┐
│ Command API │          │  Query API  │
│  (GraphQL)  │          │  (GraphQL)  │
└──────┬──────┘          └──────▲──────┘
       │                        │
       ▼                        │
┌─────────────┐          ┌─────┴───────┐
│  DynamoDB   │  Stream  │ PostgreSQL  │
│(Event Store)├─────────►│ (Read Model)│
└─────────────┘  Lambda  └─────────────┘
```

**理解したポイント**:
- 書き込みと読み取りのデータストアを分離
- 各サイドを独立してスケーリング可能
- クエリパフォーマンスの最適化

---

#### Event Sourcing

```
State = Replay(Events)

Events: [Created, Renamed, Deleted]
   ↓
Current State: User{id, name, email}
```

**理解したポイント**:
- 全ての状態変更をイベントとして記録
- 過去の任意の時点の状態を再現可能
- 完全な監査証跡

---

#### 結果整合性（Eventual Consistency）

```
Time: T0  T1       T2      T3
      ├──────┼────────┼───────►
Command側: [Write] ────► [Committed]
Query側:          [Updating...] ─► [Consistent]
```

**理解したポイント**:
- 非同期処理による遅延が発生
- 最終的には整合性が保証される
- リトライ機能で整合性を確認

---

## 10.3 第2部への準備

### 10.3.1 第2部で学ぶこと

第2部「サービス構築編」では、実際のコード実装を深く学びます：

#### 主要トピック

1. **ドメイン駆動設計の基礎**
   - 集約（Aggregate）、値オブジェクト（Value Object）
   - ドメインイベント（Domain Event）
   - ユビキタス言語（Ubiquitous Language）

2. **イベントソーシングの実装**
   - EventSourcedBehaviorの詳細
   - PersistenceIdの設計
   - スナップショット戦略

3. **コマンド側の実装**
   - UserAccountAggregateの実装
   - GenericAggregateRegistryパターン
   - Cluster Shardingの活用

4. **クエリ側の実装**
   - Read Modelの設計思想
   - Slick DAOの活用
   - GraphQL Queryの実装

5. **イベント処理の実装**
   - Read Model Updater（Lambda）の詳細
   - DynamoDB Streamsの統合
   - 冪等性の実装

---

### 10.3.2 推奨する学習順序

第2部を効果的に学ぶための推奨順序：

```
1. ドメイン駆動設計の基礎（第2部第1章）
   ↓
2. イベントソーシングの実装（第2部第2章）
   ↓
3. コマンド側の実装（第2部第3章）
   ↓
4. クエリ側の実装（第2部第4章）
   ↓
5. イベント処理の実装（第2部第5章）
   ↓
6. テスト戦略（第2部第7章）
   ↓
7. パフォーマンスとスケーラビリティ（第2部第8章）
```

---

### 10.3.3 事前に確認しておくこと

第2部に進む前に、以下を確認してください：

- [ ] 環境が正常に起動できる（`./scripts/run-single.sh up`）
- [ ] E2Eテストが成功する（`./scripts/test-e2e.sh`）
- [ ] GraphQL Playgroundで基本操作ができる
- [ ] システム全体のデータフローを説明できる
- [ ] トラブルシューティングの基本手法を理解している

---

## 10.4 さらなる学習リソース

### 10.4.1 公式ドキュメント

#### Apache Pekko

- [Apache Pekko Documentation](https://pekko.apache.org/docs/pekko/current/)
- [Pekko Persistence](https://pekko.apache.org/docs/pekko-persistence/current/)
- [Pekko Cluster Sharding](https://pekko.apache.org/docs/pekko/current/typed/cluster-sharding.html)

#### CQRS/Event Sourcing

- [CQRS Journey - Microsoft](https://docs.microsoft.com/en-us/previous-versions/msp-n-p/jj554200(v=pandp.10))
- [Event Sourcing - Martin Fowler](https://martinfowler.com/eaaDev/EventSourcing.html)
- [Domain-Driven Design - Eric Evans](https://www.domainlanguage.com/ddd/)

#### GraphQL & Scala

- [GraphQL Official](https://graphql.org/)
- [Sangria Documentation](https://sangria-graphql.github.io/)
- [Scala 3 Documentation](https://docs.scala-lang.org/scala3/)

---

### 10.4.2 コミュニティとサポート

#### オンラインリソース

- **Apache Pekko Gitter**: リアルタイムでの質問と回答
- **Stack Overflow**: `pekko`、`akka-persistence`、`cqrs` タグ
- **GitHub Issues**: [pekko-cqrs-es-example](https://github.com/j5ik2o/pekko-cqrs-es-example/issues)

#### 書籍

- 「ドメイン駆動設計」エリック・エヴァンス著
- 「実践ドメイン駆動設計」ヴァーン・ヴァーノン著
- 「マイクロサービスアーキテクチャ」サム・ニューマン著

---

## 10.5 まとめ

### 10.5.1 第1部の成果

第1部「環境構築編」を完了し、以下を達成しました：

1. ✅ CQRS/Event Sourcingの基本概念を理解
2. ✅ Apache Pekkoによる開発環境を構築
3. ✅ LocalStackを活用したローカル開発環境を確立
4. ✅ E2Eテストによる完全なデータフローを検証
5. ✅ 日常的な開発ワークフローを確立

---

### 10.5.2 重要な教訓

#### アーキテクチャ設計

- **関心の分離**: Command/Queryを分離することでスケーラビリティと保守性を向上
- **イベント駆動**: 非同期処理により疎結合なシステムを実現
- **結果整合性**: 即座の一貫性を諦め、最終的な整合性を保証

#### 開発プラクティス

- **環境の再現性**: Docker Composeにより全開発者が同じ環境で作業可能
- **自動化**: テスト、デプロイ、マイグレーションをスクリプト化
- **ログ駆動**: 問題解決の鍵はログにある

#### トレードオフ

- **複雑性 vs 柔軟性**: CQRSは複雑だが、スケーラビリティと柔軟性を提供
- **即座の一貫性 vs スケーラビリティ**: 結果整合性を受け入れることで水平スケーリングが可能
- **型安全性 vs 学習コスト**: Scala 3の型システムは学習コストがあるが、長期的には生産性向上

---

### 10.5.3 次のステップ

環境構築が完了し、システムの全体像を理解できました。次は**第2部「サービス構築編」**に進み、実際のコード実装を深く学びましょう。

第2部では、以下のスキルを習得します：

- ドメインモデルの設計と実装
- Pekko Persistenceの詳細な活用方法
- GraphQL APIの構築
- テスト戦略とパフォーマンス最適化
- 本番環境への準備

---

## おわりに

第1部「環境構築編」にお付き合いいただき、ありがとうございました。

CQRS/Event Sourcingは、一見複雑に見えますが、適切に実装すれば非常に強力なアーキテクチャパターンです。Apache Pekkoは、このパターンを実装するための優れたツールを提供しています。

第2部では、さらに深くコードレベルでの実装を学び、本番環境で使用できるスキルを習得します。ぜひ引き続き学習を進めてください。

---

👉 **第2部「サービス構築編」へ進む**（準備中）

---

## 参考資料

### 第1部で使用した主要リソース

- [Apache Pekko](https://pekko.apache.org/)
- [LocalStack](https://localstack.cloud/)
- [GraphQL](https://graphql.org/)
- [Protocol Buffers](https://protobuf.dev/)
- [Flyway](https://flywaydb.org/)
- [Docker](https://www.docker.com/)
- [Scala 3](https://www.scala-lang.org/)

### コミュニティ

- [Apache Pekko Gitter](https://gitter.im/apache/pekko)
- [Scala Users Forum](https://users.scala-lang.org/)
- [GraphQL Community](https://graphql.org/community/)

---

**ハッピーコーディング！** 🎉
