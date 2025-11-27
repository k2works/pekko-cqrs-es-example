# Apache Pekkoを使用したCQRS/EventSourcingサービス開発

## 記事アウトライン

---

## 【第1部】環境構築編

### 1. イントロダクション
- **目的と対象読者**
  - CQRS/Event Sourcingを実践的に学びたいScala開発者
  - Apache Pekko（旧Akka）への移行を検討している開発者
- **この記事で学べること**
  - Apache Pekkoによるイベントソーシング実装
  - CQRS（コマンドクエリ責任分離）の実践的なアーキテクチャ
  - LocalStackを使用したローカル開発環境構築
- **全体の構成**
  - 第1部：環境構築編（本記事）
  - 第2部：サービス構築編

### 2. アーキテクチャ概要
- **CQRS/Event Sourcingとは**
  - CQRS（コマンドクエリ責任分離）の基本概念
  - Event Sourcingパターンの利点と課題
  - なぜPekkoを選択したか（Akkaからの移行背景）
- **システム構成図**
  - コマンド側：Pekko Actors + DynamoDB
  - クエリ側：PostgreSQL + Slick
  - イベント処理：Lambda + DynamoDB Streams
- **データフローの理解**
  - Mutation → Event生成 → 永続化 → Stream → Lambda → Read Model更新 → Query

### 3. 技術スタックの選定
- **コア技術**
  - Scala 3.6.2の採用理由（型安全性、関数型プログラミング）
  - Apache Pekko 1.1.2（型付きアクター、永続化、クラスター）
- **データストア**
  - DynamoDB：イベントストアとしての利点（スケーラビリティ、可用性）
  - PostgreSQL：Read Modelとしての最適化
- **開発環境**
  - LocalStack：AWS サービスのローカルエミュレーション
  - Docker Compose：インフラストラクチャの一元管理
- **API & シリアライゼーション**
  - GraphQL (Sangria)：型安全なAPI設計
  - Protocol Buffers：効率的なイベントシリアライゼーション

### 4. 開発環境のセットアップ
#### 4.1 前提条件の確認
- Java（OpenJDK 17以降）のインストール
- SBT（1.8以降）のセットアップ
- Docker & Docker Composeのインストール
- awslocal CLIのセットアップ（オプション）

#### 4.2 プロジェクトのクローンとビルド
- リポジトリのクローン
- SBTビルドの実行
- Dockerイメージのビルド（`sbt dockerBuildAll`）

#### 4.3 LocalStackの理解とセットアップ
- LocalStackとは何か
- 設定ファイルの解説（docker-compose-common.yml）
- DynamoDBテーブルの作成（tools/dynamodb-setup/）
- DynamoDB Streamsの設定

#### 4.4 PostgreSQLのセットアップ
- Flywayによるマイグレーション戦略
- スキーマ設計（user_accountsテーブル）
- Slick DAOの自動生成

#### 4.5 Lambda関数のデプロイ
- Read Model Updaterの役割
- LocalStackへのデプロイ手順
- イベントソースマッピングの設定

### 5. 設定管理の体系化
#### 5.1 設定ファイルの階層化
- application.conf（エントリーポイント）
- pcqrses.conf（アプリケーション固有設定）
- pekko.conf（Pekkoフレームワーク設定）
- j5ik2o.conf（DynamoDB永続化プラグイン設定）

#### 5.2 環境変数による設定の上書き
- ローカル開発環境
- テスト環境
- 本番環境への対応

#### 5.3 シリアライゼーション設定
- Protocol Buffersの設定
- カスタムシリアライザの登録
- CBORシリアライゼーション

### 6. 初回起動とヘルスチェック
#### 6.1 シングルノードモードでの起動
- `./scripts/run-single.sh up` の実行
- 起動プロセスの確認
- ログの確認方法

#### 6.2 各サービスの動作確認
- Command API：http://localhost:50501/api/graphql
- Query API：http://localhost:50502/api/graphql
- LocalStack：http://localhost:4566
- PostgreSQL：localhost:5432

#### 6.3 GraphQL Playgroundの使い方
- Playgroundへのアクセス
- スキーマの確認
- 基本的なクエリの実行

### 7. E2Eテストによる動作確認
- E2Eテストスクリプトの実行（`./scripts/test-e2e.sh`）
- テストフローの理解
  1. ヘルスチェック
  2. ユーザー作成（Mutation）
  3. イベント処理待機
  4. データ取得（Query）
  5. 整合性検証
- テストのカスタマイズ（環境変数）

### 8. トラブルシューティング
#### 8.1 よくある問題と解決方法
- LocalStackが起動しない
- Lambda関数がイベントを処理しない
- PostgreSQLに接続できない
- DynamoDBにデータが保存されない
- ポートが既に使用されている

#### 8.2 デバッグ手法
- ログの確認方法
- DynamoDBの内容確認（`awslocal dynamodb scan`）
- PostgreSQLの直接クエリ
- Lambda関数のCloudWatch Logs確認

### 9. 開発ワークフローの確立
- コードフォーマット（`sbt fmt`）
- リントチェック（`sbt lint`）
- テスト実行（`sbt test`）
- カバレッジレポート（`sbt testCoverage`）
- 環境の停止とクリーンアップ

### 10. まとめと次のステップ
- 環境構築で学んだこと
- 第2部（サービス構築編）への導入
  - ドメインモデルの設計
  - イベントソーシングの実装
  - GraphQL APIの構築
  - Read Modelの更新

---

## 【第2部】サービス構築編

### 1. ドメイン駆動設計の基礎
#### 1.1 プロジェクト構造の理解
- モジュール構成の説明
- レイヤードアーキテクチャ
  - Domain層（ドメインロジック）
  - Use Case層（アプリケーションサービス）
  - Interface Adapter層（技術的詳細）

#### 1.2 UserAccountドメインモデルの設計
- 集約ルート（UserAccount）
- 値オブジェクト（UserAccountId、UserAccountName、EmailAddress）
- ドメインイベント（UserAccountEvent）
- ビジネスルールの実装

### 2. イベントソーシングの実装
#### 2.1 ドメインイベントの設計
- イベント定義（Created_V1、Renamed_V1、Deleted_V1）
- イベントバージョニング戦略
- Envelopeパターンの採用理由

#### 2.2 Protocol Buffersによるシリアライゼーション
- .protoファイルの定義
- ScalaPBによるコード生成
- シリアライザの実装（UserAccountEventSerializer）
- スナップショットのシリアライゼーション

#### 2.3 Pekko Persistenceの活用
- EventSourcedBehaviorの実装
- PersistenceIdの設計
- イベントハンドラーの実装
- スナップショット戦略

### 3. コマンド側の実装（書き込みモデル）
#### 3.1 UserAccountAggregateの実装
- 型付きアクターの基礎
- コマンドハンドラー（Create、Rename、Delete）
- イベントハンドラー
- 状態管理（UserAccountAggregateState）

#### 3.2 レジストリパターンの実装
- GenericAggregateRegistryの設計
- ローカルモード vs クラスターモード
- Cluster Shardingの設定
- パッシベーション戦略

#### 3.3 Command API（GraphQL）の実装
- スキーマ定義（TypeDefinitions）
- Mutationリゾルバーの実装
- バリデーション戦略
- エラーハンドリング

### 4. クエリ側の実装（読み取りモデル）
#### 4.1 Read Modelの設計
- PostgreSQLスキーマ設計
- 非正規化の戦略
- Flywayによるマイグレーション管理

#### 4.2 Slick DAOの自動生成
- sbt-dao-generatorの活用
- DAOの生成プロセス
- カスタマイズポイント

#### 4.3 Query API（GraphQL）の実装
- スキーマ定義
- Queryリゾルバーの実装
- 検索機能の実装
- ページネーション

### 5. イベント処理の実装
#### 5.1 Read Model Updater（Lambda）の実装
- Lambda Handlerの構造
- イベントのデシリアライゼーション
- PersistentReprの処理
- PostgreSQLへの更新処理

#### 5.2 DynamoDB Streamsの統合
- Streamsの設定
- イベントソースマッピング
- リトライ戦略
- エラーハンドリング

#### 5.3 結果整合性の管理
- 非同期処理の課題
- イベント順序の保証
- 冪等性の実装
- トランザクション境界

### 6. 設定管理とデプロイ
#### 6.1 CommandApiConfigの階層化
- 設定の体系化（actor-timeout、server、load-balancer）
- Typesafe Configの活用
- 環境別設定の管理

#### 6.2 シリアライゼーション戦略
- CborSerializableマーカートレイト
- Protocol Buffers vs CBOR
- シリアライザの登録と設定

#### 6.3 Main.scalaの実装
- アプリケーションのエントリーポイント
- ActorSystemの起動
- グレースフルシャットダウン

### 7. テスト戦略
#### 7.1 ドメインモデルのテスト
- 単体テストの実装
- ビジネスルールの検証
- イミュータビリティのテスト

#### 7.2 アグリゲートのテスト
- ActorTestKitの活用
- UserAccountTestHelperの設計
- テストパターンの再利用
- ローカルモード vs クラスターモードのテスト

#### 7.3 GraphQL APIのテスト
- GraphQLServiceSpecの実装
- バリデーションのテスト
- エラーケースのテスト

#### 7.4 E2Eテストの実装
- テストスクリプトの詳細解説
- リトライ戦略
- 環境変数によるカスタマイズ

### 8. パフォーマンスとスケーラビリティ
#### 8.1 Cluster Shardingによる水平スケーリング
- クラスターモードの起動
- エンティティの分散
- ノードの追加・削除

#### 8.2 最適化ポイント
- イベントスナップショット
- 接続プール（HikariCP）
- Read Modelのインデックス設計
- キャッシング戦略

#### 8.3 モニタリングとロギング
- Logbackの設定
- メトリクスの収集
- トレーシング

### 9. 実践的なトピック
#### 9.1 イベントスキーマの進化
- バージョニング戦略の詳細
- イベントアップキャスト
- 複数バージョンのサポート
- 段階的な移行

#### 9.2 GraphQL APIの改善
- firstName/lastNameへのフィールド分割
- Validation型の活用
- ドメインモデルとの整合性
- APIのユーザビリティ向上

#### 9.3 エラーハンドリングとレジリエンス
- アクターの監督戦略
- リトライと Circuit Breaker
- デッドレター処理
- エラーログの管理

### 10. 本番環境への準備
#### 10.1 セキュリティ考慮事項
- 認証・認可の実装
- APIレート制限
- 入力バリデーション
- 暗号化（転送時・保管時）

#### 10.2 運用上の考慮事項
- デプロイ戦略
- バックアップとリストア
- ディザスタリカバリ
- スケーリング計画

#### 10.3 AWSへのデプロイ
- LocalStackから実際のAWSサービスへの移行
- DynamoDB、Lambda、PostgreSQL (RDS)の設定
- インフラストラクチャのコード化（Terraform）

### 11. まとめと発展的なトピック
- この記事で学んだこと
- CQRS/Event Sourcingのベストプラクティス
- さらなる学習リソース
- コミュニティとサポート

---

## 補足資料

### A. 用語集
- CQRS、Event Sourcing、Aggregate、Domain Event、Read Model、Event Store 等

### B. 参考資料
- Apache Pekko公式ドキュメント
- CQRS Journey（Microsoft）
- Event Sourcing（Martin Fowler）
- DDD（Domain-Driven Design）

### C. トラブルシューティングFAQ
- よくある質問と回答

---

## 執筆方針

### 各トピックの構成
1. **概要**：そのトピックが何で、なぜ重要か
2. **技術的背景**：設計判断の理由、代替案との比較
3. **実装の詳細**：具体的なコード例と解説
4. **ベストプラクティス**：推奨される使い方、避けるべきパターン
5. **演習/実践**：読者が試せる具体的なステップ

### コードサンプル
- 実際のリポジトリのコードを引用
- ファイルパスと行番号を明記（例：`UserAccount.scala:25-35`）
- 重要な部分をハイライト
- コメントで説明を補足

### 図表
- Plantuml記法でアーキテクチャ図を作成
- データフロー図
- シーケンス図
- 設定ファイルの構造図
