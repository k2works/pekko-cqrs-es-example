# 第1部 環境構築編 - 第1章：イントロダクション

## Apache Pekkoを使用したCQRS/EventSourcingサービス開発

### 目的と対象読者

本記事は、**Apache Pekko**を使用してCQRS（Command Query Responsibility Segregation）とEvent Sourcingパターンを実践的に学びたい開発者を対象としています。

#### こんな方におすすめ

- **CQRS/Event Sourcingを実践的に学びたいScala開発者**
  - 理論は理解しているが、実装経験がない方
  - サンプルコードではなく、実際に動くシステムで学びたい方

- **Apache Pekko（旧Akka）への移行を検討している開発者**
  - Akka 2.xからPekkoへの移行を考えている方
  - Akkaのライセンス変更により、オープンソース代替を探している方

- **イベント駆動アーキテクチャに興味がある開発者**
  - マイクロサービスアーキテクチャを採用している方
  - 分散システムのスケーラビリティに関心がある方

#### 前提知識

以下の知識があることを前提としています：

- **Scala**の基本的な文法（Scala 2.xの経験があればOK）
- **関数型プログラミング**の基礎概念（イミュータビリティ、パターンマッチング等）
- **Docker & Docker Compose**の基本的な使い方
- **REST API**または**GraphQL**の基本概念
- **リレーショナルデータベース**（PostgreSQL）の基礎

Scala 3の詳しい知識は必須ではありません。記事内で必要に応じて説明します。

### この記事で学べること

本記事を通じて、以下のスキルと知識を習得できます：

#### 1. Apache Pekkoによるイベントソーシング実装

- **Pekko Persistence**を使用したイベントストアの構築
- **EventSourcedBehavior**による型付きアクターの実装
- **Protocol Buffers**を用いた効率的なイベントシリアライゼーション
- **スナップショット戦略**によるパフォーマンス最適化

#### 2. CQRS（コマンドクエリ責任分離）の実践的なアーキテクチャ

- **書き込みモデル**（Command Side）と**読み取りモデル**（Query Side）の分離
- **DynamoDB**をイベントストアとして活用
- **PostgreSQL**をRead Modelとして最適化
- **結果整合性**（Eventual Consistency）の実装と課題

#### 3. LocalStackを使用したローカル開発環境構築

- **AWS DynamoDB**のローカルエミュレーション
- **AWS Lambda**関数のローカル実行
- **DynamoDB Streams**によるイベント駆動アーキテクチャ
- インフラストラクチャのコード化（IaC）とDocker Compose

#### 4. GraphQL APIの設計と実装

- **Sangria**を使用したGraphQLスキーマ定義
- **型安全なAPI**設計（Mutation/Query）
- **バリデーション戦略**とエラーハンドリング
- GraphQL Playgroundによる対話的な開発

#### 5. 本番環境への準備

- **水平スケーリング**（Pekko Cluster Sharding）
- **監視とロギング**の戦略
- **エラーハンドリング**とレジリエンスパターン
- **セキュリティ考慮事項**

### 全体の構成

本記事は**2部構成**となっており、段階的に学習を進められるよう設計されています。

#### 第1部：環境構築編（本記事）

開発環境のセットアップから、サービスの初回起動、動作確認までを扱います。

```
1. イントロダクション（本章）
2. アーキテクチャ概要
3. 技術スタックの選定
4. 開発環境のセットアップ
5. 設定管理の体系化
6. 初回起動とヘルスチェック
7. E2Eテストによる動作確認
8. トラブルシューティング
9. 開発ワークフローの確立
10. まとめと次のステップ
```

**学習時間の目安**: 3〜4時間

**到達目標**:
- ローカル環境でCQRS/ESシステムを起動できる
- GraphQL Playgroundで基本的な操作ができる
- システム全体のデータフローを理解している

#### 第2部：サービス構築編

実際のコード実装、テスト戦略、パフォーマンス最適化、本番環境への準備を扱います。

```
1. ドメイン駆動設計の基礎
2. イベントソーシングの実装
3. コマンド側の実装（書き込みモデル）
4. クエリ側の実装（読み取りモデル）
5. イベント処理の実装
6. 設定管理とデプロイ
7. テスト戦略
8. パフォーマンスとスケーラビリティ
9. 実践的なトピック
10. 本番環境への準備
11. まとめと発展的なトピック
```

**学習時間の目安**: 6〜8時間

**到達目標**:
- ドメインモデルを設計し、実装できる
- イベントソーシングの実装パターンを理解している
- 本番環境へのデプロイ準備ができる

### なぜCQRS/Event Sourcingなのか

#### 従来のCRUDアーキテクチャの課題

多くのアプリケーションは、シンプルなCRUD（Create/Read/Update/Delete）パターンで構築されます。しかし、システムが複雑化すると以下の課題に直面します：

1. **読み取りと書き込みの競合**
   - 同じデータモデルを読み書き両方で使用すると、最適化が困難
   - 複雑なクエリがパフォーマンスに影響

2. **監査証跡の欠如**
   - 「誰が」「いつ」「何を」変更したか追跡が困難
   - データの履歴を後から確認できない

3. **スケーラビリティの限界**
   - 読み取りと書き込みを同じデータベースで処理
   - 負荷に応じた個別のスケーリングができない

4. **ビジネスロジックの散在**
   - データモデル中心の設計になりがち
   - ドメインロジックがサービス層に散在

#### CQRS/Event Sourcingがもたらすメリット

**CQRS（コマンドクエリ責任分離）**と**Event Sourcing**の組み合わせにより、これらの課題を解決できます：

1. **パフォーマンスの最適化**
   - 読み取り専用のRead Modelを用途に応じて設計
   - 書き込みはイベントストアに高速に記録
   - 読み取りと書き込みを独立してスケール

2. **完全な監査証跡**
   - 全ての状態変更をイベントとして記録
   - 時間を遡って過去の状態を再現可能
   - コンプライアンス要件への対応が容易

3. **柔軟なデータモデル**
   - Read Modelは用途に応じて複数作成可能
   - イベントから新しいビューを後から構築
   - レガシーシステムとの統合が容易

4. **ドメイン駆動設計との親和性**
   - ビジネスイベント中心の設計
   - ドメインエキスパートとの共通言語（ユビキタス言語）
   - 集約とトランザクション境界が明確

### なぜApache Pekkoなのか

#### Akkaからの進化

Apache Pekkoは、**Apache Software Foundation**によって管理される、Akkaのフォークプロジェクトです。

**背景**:
- 2022年9月、Lightbend社がAkka 2.7以降をBSL（Business Source License）に変更
- 商用利用には制限があり、多くの企業がオープンソース代替を必要としていた
- Apache Pekkoは、Akka 2.6.xをベースにApache License 2.0で開発を継続

**Pekkoの利点**:
- ✅ **完全なオープンソース**（Apache License 2.0）
- ✅ **商用利用に制限なし**
- ✅ **Akka 2.6との高い互換性**（移行が容易）
- ✅ **活発な開発コミュニティ**
- ✅ **Apacheプロジェクトとしての信頼性**

#### Event Sourcingに最適な理由

Pekkoが提供する以下の機能により、Event Sourcingの実装が容易になります：

1. **Pekko Persistence**
   - イベントストアへの抽象化されたインターフェース
   - DynamoDB、Cassandra、PostgreSQL等のバックエンドをサポート
   - スナップショットによるパフォーマンス最適化

2. **型付きアクター（Typed Actors）**
   - 型安全なメッセージ処理
   - コンパイル時のエラー検出
   - EventSourcedBehaviorによる宣言的な実装

3. **Cluster Sharding**
   - エンティティの自動分散
   - 水平スケーリングが容易
   - ノード障害時の自動フェイルオーバー

4. **豊富なエコシステム**
   - pekko-http（HTTPサーバー）
   - pekko-grpc（gRPCサポート）
   - pekko-management（クラスター管理）

### 実装するシステムの概要

本記事では、**ユーザーアカウント管理システム**を題材に、CQRS/Event Sourcingを実装します。

#### 機能概要

- **ユーザーアカウントの作成**（CreateUserAccount）
- **ユーザー名の変更**（RenameUserAccount）
- **ユーザーアカウントの削除**（DeleteUserAccount）
- **ユーザーアカウントの取得**（GetUserAccount）
- **ユーザーアカウントの検索**（SearchUserAccounts）

シンプルな機能ですが、CQRS/Event Sourcingの核心的な概念を全て網羅しています。

#### アーキテクチャハイライト

```
Command Side (書き込み)
  ↓
GraphQL Mutation → Pekko Actor → イベント生成 → DynamoDB
  ↓
DynamoDB Streams → Lambda関数
  ↓
Query Side (読み取り)
  ↓
PostgreSQL ← Slick DAO ← GraphQL Query
```

- **非同期イベント処理**：DynamoDB StreamsとLambda
- **完全な分離**：コマンド側とクエリ側のデータストアが独立
- **GraphQL API**：型安全で柔軟なAPI設計

### 次の章へ

次章では、システム全体のアーキテクチャを詳しく解説します。データがどのように流れるか、各コンポーネントの役割、CQRS/Event Sourcingの理論的な背景を学びます。

👉 [第2章：アーキテクチャ概要](part1-02-architecture.md)

---

## 参考資料

- [Apache Pekko 公式サイト](https://pekko.apache.org/)
- [CQRS Journey - Microsoft](https://docs.microsoft.com/en-us/previous-versions/msp-n-p/jj554200(v=pandp.10))
- [Event Sourcing - Martin Fowler](https://martinfowler.com/eaaDev/EventSourcing.html)
- [Domain-Driven Design - Eric Evans](https://www.domainlanguage.com/ddd/)
