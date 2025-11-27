# Java アプリケーション環境構築ガイド

## 概要

本ガイドは、Java アプリケーション開発環境をゼロから構築し、ソフトウェア開発の三種の神器（バージョン管理、テスティング、自動化）を実践するための手順書です。

## 前提条件

- Java 17 以降がインストールされていること
- Git がインストールされていること
- IntelliJ IDEA または VS Code がインストールされていること（推奨）

## ソフトウェア開発の三種の神器

### 1. バージョン管理

#### Git の基本設定

```bash
# ユーザー設定
git config --global user.name "Your Name"
git config --global user.email "your.email@example.com"

# リポジトリの初期化
git init

# .gitignore の作成
echo "build/" >> .gitignore
echo ".gradle/" >> .gitignore
echo "*.class" >> .gitignore
echo ".idea/" >> .gitignore
echo "*.iml" >> .gitignore
```

#### コミットメッセージ規約（Angular ルール）

```text
<タイプ>(<スコープ>): <タイトル>
<空行>
<ボディ>
<空行>
<フッタ>
```

**タイプの種類：**
- `feat`: 新機能の追加
- `fix`: バグ修正
- `docs`: ドキュメント変更のみ
- `style`: コードに影響を与えない変更（フォーマット等）
- `refactor`: 機能追加でもバグ修正でもないコード変更
- `perf`: パフォーマンスを改善するコード変更
- `test`: テストの追加または修正
- `chore`: ビルドプロセスやツールの変更

**例：**
```bash
git commit -m 'feat: FizzBuzz機能の実装'
git commit -m 'refactor: メソッドの抽出'
git commit -m 'chore: 静的コード解析セットアップ'
```

### 2. テスティング

#### JUnit 5 と AssertJ のセットアップ

Gradle プロジェクトの初期化：

```bash
./gradlew init --type java-application
```

`build.gradle` の基本設定：

```groovy
plugins {
    id 'java'
    id 'application'
}

repositories {
    mavenCentral()
}

dependencies {
    // JUnit 5
    testImplementation 'org.junit.jupiter:junit-jupiter-api:5.10.1'
    testImplementation 'org.junit.jupiter:junit-jupiter-params:5.10.1'
    testRuntimeOnly 'org.junit.jupiter:junit-jupiter-engine:5.10.1'
    
    // AssertJ
    testImplementation 'org.assertj:assertj-core:3.24.2'
}

test {
    useJUnitPlatform()
}

application {
    mainClass = 'App'
}
```

#### テストの実行

```bash
# テストの実行
./gradlew test

# 継続的テスト実行（ファイル変更監視）
./gradlew test --continuous
```

### 3. 自動化

#### 完全な Gradle セットアップ

`build.gradle` の完全版：

```groovy
plugins {
    id 'java'
    id 'application'
    id 'jacoco'
    id 'checkstyle'
    id 'pmd'
    id 'com.github.spotbugs' version '6.0.7'
    id 'org.gradle.test-retry' version '1.5.6'
}

repositories {
    mavenCentral()
}

dependencies {
    // テスト関連
    testImplementation 'org.junit.jupiter:junit-jupiter-api:5.10.1'
    testImplementation 'org.junit.jupiter:junit-jupiter-params:5.10.1'
    testRuntimeOnly 'org.junit.jupiter:junit-jupiter-engine:5.10.1'
    testImplementation 'org.assertj:assertj-core:3.24.2'
    
    // SpotBugs関連
    spotbugsPlugins 'com.h3xstream.findsecbugs:findsecbugs-plugin:1.12.0'
}

application {
    mainClass = 'App'
}

test {
    useJUnitPlatform()
    
    // テスト失敗時のリトライ設定
    retry {
        maxRetries = 3
        maxFailures = 20
        failOnPassedAfterRetry = false
    }
    
    // テストログ設定
    testLogging {
        events "passed", "skipped", "failed"
        exceptionFormat "full"
    }
}

// JaCoCo（コードカバレッジ）設定
jacoco {
    toolVersion = "0.8.11"
}

jacocoTestReport {
    dependsOn test
    reports {
        xml.required = false
        csv.required = false
        html.outputLocation = layout.buildDirectory.dir('jacocoHtml')
    }
}

// PMD 設定
pmd {
    consoleOutput = true
    toolVersion = "6.55.0"
    ruleSetFiles = files("config/pmd/ruleset.xml")
    ruleSets = []
}

// カスタムタスク：TDD用の継続的テスト実行
task tdd(type: Test) {
    useJUnitPlatform()
    testLogging {
        events "passed", "skipped", "failed"
        exceptionFormat "full"
    }
    outputs.upToDateWhen { false }
}

// カスタムタスク：品質チェック全実行
task qualityCheck {
    dependsOn 'checkstyleMain', 'checkstyleTest', 'pmdMain', 'pmdTest', 'spotbugsMain', 'spotbugsTest'
    description 'Run all quality checks'
    group 'verification'
}

// カスタムタスク：すべてのチェックとテストを実行
task fullCheck {
    dependsOn 'test', 'qualityCheck', 'jacocoTestReport'
    description 'Run all tests and quality checks'
    group 'verification'
}

// ファイル変更監視タスク
task watchTest {
    doLast {
        println "Watching for file changes..."
        println "Run: ./gradlew test --continuous"
        println "Or use your IDE's auto-test feature"
    }
}
```

## 静的コード解析ツール

### Checkstyle の設定

`config/checkstyle/checkstyle.xml` を作成：

```bash
mkdir -p config/checkstyle
```

```xml
<?xml version="1.0"?>
<!DOCTYPE module PUBLIC
        "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
        "https://checkstyle.org/dtds/configuration_1_3.dtd">

<module name="Checker">
    <property name="charset" value="UTF-8"/>
    
    <!-- ファイルレベルのチェック -->
    <module name="FileTabCharacter">
        <property name="eachLine" value="true"/>
    </module>
    
    <!-- TreeWalkerによるAST解析 -->
    <module name="TreeWalker">
        <!-- インデント -->
        <module name="Indentation">
            <property name="basicOffset" value="4"/>
        </module>
        
        <!-- 命名規則 -->
        <module name="TypeName"/>
        <module name="MethodName"/>
        <module name="VariableName"/>
        <module name="ConstantName"/>
        
        <!-- その他 -->
        <module name="EmptyStatement"/>
        <module name="EqualsHashCode"/>
        <module name="MagicNumber">
            <property name="ignoreHashCodeMethod" value="true"/>
            <property name="ignoreAnnotation" value="true"/>
        </module>
        
        <!-- 循環複雑度のチェック -->
        <module name="CyclomaticComplexity">
            <property name="max" value="7"/>
            <property name="switchBlockAsSingleDecisionPoint" value="false"/>
        </module>
    </module>
</module>
```

### PMD の設定

`config/pmd/ruleset.xml` を作成：

```bash
mkdir -p config/pmd
```

```xml
<?xml version="1.0"?>
<ruleset name="Custom Rules"
         xmlns="http://pmd.sourceforge.net/ruleset/2.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://pmd.sourceforge.net/ruleset/2.0.0
         https://pmd.sourceforge.io/ruleset_2_0_0.xsd">
    
    <description>カスタム PMD ルールセット</description>
    
    <!-- 基本ルールセット -->
    <rule ref="category/java/bestpractices.xml">
        <exclude name="JUnitTestContainsTooManyAsserts"/>
    </rule>
    
    <rule ref="category/java/codestyle.xml">
        <exclude name="AtLeastOneConstructor"/>
        <exclude name="CommentDefaultAccessModifier"/>
    </rule>
    
    <rule ref="category/java/design.xml">
        <!-- 循環複雑度を7に制限 -->
        <exclude name="CyclomaticComplexity"/>
    </rule>
    
    <!-- 循環複雑度のカスタム設定 -->
    <rule ref="category/java/design.xml/CyclomaticComplexity">
        <properties>
            <property name="classReportLevel" value="80"/>
            <property name="methodReportLevel" value="7"/>
            <property name="cycloOptions" value=""/>
        </properties>
    </rule>
    
    <!-- 認知複雑度の設定 -->
    <rule ref="category/java/design.xml/CognitiveComplexity">
        <properties>
            <property name="reportLevel" value="7"/>
        </properties>
    </rule>
    
    <rule ref="category/java/errorprone.xml">
        <exclude name="BeanMembersShouldSerialize"/>
    </rule>
    
    <rule ref="category/java/performance.xml"/>
    
    <rule ref="category/java/security.xml"/>
</ruleset>
```

build.gradle に PMD 設定を追加：

```groovy
// PMD 設定
pmd {
    consoleOutput = true
    toolVersion = "6.55.0"
    ruleSetFiles = files("config/pmd/ruleset.xml")
    ruleSets = []
}
```

PMD はカスタム設定で以下をチェック：
- マジックナンバーの使用
- 空の catch ブロック
- 循環複雑度が 7 を超えるメソッド
- 認知複雑度が 7 を超えるメソッド
- 不要なコード

### SpotBugs の設定（デフォルト設定を使用）

SpotBugs は以下のバグパターンを検出：
- Null ポインタ参照
- リソースリーク
- セキュリティ脆弱性
- パフォーマンス問題

## タスクランナーコマンド一覧

### 基本コマンド

```bash
# 依存関係のインストール
./gradlew build

# テストの実行
./gradlew test

# TDDモードでテスト実行
./gradlew tdd

# 継続的テスト実行
./gradlew test --continuous
```

### 品質チェックコマンド

```bash
# Checkstyle実行
./gradlew checkstyleMain
./gradlew checkstyleTest

# PMD実行
./gradlew pmdMain
./gradlew pmdTest

# SpotBugs実行
./gradlew spotbugsMain
./gradlew spotbugsTest

# すべての品質チェック実行
./gradlew qualityCheck
```

### カバレッジ測定

```bash
# テスト実行とカバレッジレポート生成
./gradlew test jacocoTestReport

# レポートは build/jacocoHtml/index.html で確認
```

### 統合コマンド

```bash
# すべてのテストと品質チェックを実行
./gradlew fullCheck

# タスク一覧の表示
./gradlew tasks --group verification
```

## 開発フロー

### 1. プロジェクトの初期化

```bash
# プロジェクトディレクトリの作成
mkdir my-java-project
cd my-java-project

# Git リポジトリの初期化
git init

# Gradle プロジェクトの初期化
./gradlew init --type java-application

# 設定ファイルの配置（上記の build.gradle、checkstyle.xml、PMD ruleset.xml）

# 依存関係のインストール
./gradlew build

# 初期コミット
git add .
git commit -m 'chore: プロジェクトの初期化'
```

### 2. TDD サイクルの実践

```bash
# 継続的テスト実行を開始
./gradlew test --continuous

# 別ターミナルで開発を進める
# 1. 失敗するテストを書く（Red）
# 2. テストを通す最小限のコードを書く（Green）
# 3. リファクタリング（Refactor）

# 品質チェック
./gradlew qualityCheck

# コミット
git add .
git commit -m 'feat: 新機能の実装'
```

### 3. カバレッジ確認

```bash
# テストとカバレッジレポート生成
./gradlew test jacocoTestReport

# ブラウザでレポートを確認
open build/jacocoHtml/index.html  # macOS
start build/jacocoHtml/index.html # Windows
```

## IDE 統合

### IntelliJ IDEA

1. プロジェクトを開く
2. `File` → `Settings` → `Build, Execution, Deployment` → `Build Tools` → `Gradle`
3. `Build and run using` を `Gradle` に設定
4. `Run tests using` を `Gradle` に設定

**自動テスト実行の設定：**
- `Run` → `Edit Configurations`
- `+` → `Gradle`
- `Tasks` に `test --continuous` を設定

### VS Code

必要な拡張機能：
- Extension Pack for Java
- Gradle for Java
- Test Runner for Java

`settings.json` に追加：
```json
{
    "java.test.runner": "junit",
    "java.test.config": {
        "workingDirectory": "${workspaceFolder}"
    }
}
```

## トラブルシューティング

### Gradle Wrapper が動作しない場合

```bash
# Gradle Wrapper の再生成
gradle wrapper --gradle-version=8.5

# 実行権限の付与（Unix系）
chmod +x gradlew
```

### テストが見つからない場合

- テストクラス名が `*Test.java` で終わっているか確認
- テストメソッドに `@Test` アノテーションがあるか確認
- `src/test/java` ディレクトリ配下にテストクラスがあるか確認

### 静的解析ツールのエラーを無視したい場合

```java
// Checkstyle を無視
// CHECKSTYLE:OFF
問題のあるコード
// CHECKSTYLE:ON

// PMD を無視
@SuppressWarnings("PMD.MethodNamingConventions")
public void my_method() { }

// SpotBugs を無視
@SuppressFBWarnings("NP_NULL_ON_SOME_PATH")
public void method() { }
```

## ベストプラクティス

1. **コミットは小さく頻繁に**
   - 機能単位でコミット
   - テストが通る状態でコミット

2. **テストファースト**
   - 実装前にテストを書く
   - テストが失敗することを確認してから実装

3. **継続的な品質チェック**
   - コミット前に `./gradlew qualityCheck` を実行
   - カバレッジ 80% 以上を目標
   - 循環複雑度を 7 以下に維持

4. **自動化の活用**
   - `./gradlew test --continuous` を常に起動
   - IDE の自動テスト機能を活用

5. **リファクタリングの習慣**
   - テストが通ったらリファクタリング
   - 重複コードの排除
   - 意図が明確なコード

6. **複雑度の管理**
   - メソッドの循環複雑度は 7 以下を維持
   - 複雑なロジックは小さなメソッドに分割
   - 早期リターンやガード節を活用して複雑度を削減

## まとめ

このガイドに従うことで、Java アプリケーション開発に必要な環境が整い、ソフトウェア開発の三種の神器を実践できます。継続的にテストを書き、品質をチェックし、自動化を活用することで、**動作するきれいなコード** を維持し続けることができます。