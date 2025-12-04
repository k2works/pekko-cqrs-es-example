# ========================================
# 共通起動スクリプト関数
# ========================================
# run-single.ps1 と run-cluster.ps1 で共通利用される関数群

# 共通関数の読み込み
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. "$ScriptDir\common.ps1"

# ========================================
# デフォルト設定
# ========================================
function Initialize-Defaults {
    $script:Command = if ($env:COMMAND) { $env:COMMAND } else { "up" }
    $script:DbOnly = if ($env:DB_ONLY -eq "true") { $true } else { $false }
    $script:ShowHelp = $false
    $script:Detached = "-d"
    $script:AttachMode = $false
    $script:AutoDeploy = if ($env:AUTO_DEPLOY -eq "false") { $false } else { $true }
}

# ========================================
# 環境変数設定
# ========================================
function Initialize-Environment {
    $env:DOCKER_COMMAND_API_PORT = if ($env:DOCKER_COMMAND_API_PORT) { $env:DOCKER_COMMAND_API_PORT } else { "50501" }
    $env:DOCKER_QUERY_API_PORT = if ($env:DOCKER_QUERY_API_PORT) { $env:DOCKER_QUERY_API_PORT } else { "50502" }
    $env:DOCKER_POSTGRES_PORT = if ($env:DOCKER_POSTGRES_PORT) { $env:DOCKER_POSTGRES_PORT } else { "50504" }
    $env:DOCKER_LOCALSTACK_PORT = if ($env:DOCKER_LOCALSTACK_PORT) { $env:DOCKER_LOCALSTACK_PORT } else { "50503" }
    $env:DOCKER_DYNAMODB_ADMIN_PORT = if ($env:DOCKER_DYNAMODB_ADMIN_PORT) { $env:DOCKER_DYNAMODB_ADMIN_PORT } else { "50505" }
    $env:DOCKER_PGADMIN_PORT = if ($env:DOCKER_PGADMIN_PORT) { $env:DOCKER_PGADMIN_PORT } else { "50506" }
}

# ========================================
# 引数処理
# ========================================
function Read-Arguments {
    param([string[]]$Arguments)

    $script:RemainingArgs = @()

    for ($i = 0; $i -lt $Arguments.Count; $i++) {
        $arg = $Arguments[$i]

        switch -Regex ($arg) {
            "^(up|down|logs)$" {
                $script:Command = $arg
            }
            "^--attach$" {
                $script:Detached = ""
                $script:AttachMode = $true
            }
            "^--db-only$" {
                $script:DbOnly = $true
            }
            "^--no-deploy$" {
                $script:AutoDeploy = $false
            }
            "^--deploy$" {
                $script:AutoDeploy = $true
            }
            "^(-h|--help)$" {
                $script:ShowHelp = $true
            }
            default {
                if ($script:Command -eq "logs") {
                    $script:RemainingArgs += $arg
                } else {
                    Write-Host "Unknown option: $arg"
                    exit 1
                }
            }
        }
    }
}

# ========================================
# downコマンド処理
# ========================================
function Invoke-DownCommand {
    param([string[]]$ComposeFiles)

    Write-Host "🛑 Stopping services..."
    $composeArgs = $ComposeFiles | ForEach-Object { @("-f", $_) } | ForEach-Object { $_ }
    & docker compose @composeArgs down
    Write-Host "✅ Services stopped"
    exit 0
}

# ========================================
# logsコマンド処理
# ========================================
function Invoke-LogsCommand {
    param(
        [string[]]$ComposeFiles,
        [string[]]$ExtraArgs
    )

    Write-Host "📜 Showing service logs..."
    $composeArgs = $ComposeFiles | ForEach-Object { @("-f", $_) } | ForEach-Object { $_ }
    & docker compose @composeArgs logs @ExtraArgs
    exit 0
}

# ========================================
# DB_ONLYモード実行
# ========================================
function Start-DbOnlyMode {
    Write-Host "🗄️  Starting Database Services Only..."
    Write-Host "   (DBs run in Docker, APIs will be started separately)"
    Write-Host ""

    # 既存のコンテナを停止・削除
    Write-Host "🧹 Cleaning up existing containers..."
    & docker compose -f docker-compose-common.yml down

    # データベースサービスのみ起動
    Write-Host "🚀 Starting database services..."
    if ($script:AttachMode) {
        Write-Host "📎 Running in foreground mode (Ctrl+C to stop)..."
        & docker compose -f docker-compose-common.yml up localstack dynamodb-setup dynamodb-admin postgres flyway
    } else {
        & docker compose -f docker-compose-common.yml up -d localstack dynamodb-setup dynamodb-admin postgres flyway

        Write-Host "⏳ Waiting for services to be ready..."
        Start-Sleep -Seconds 10

        # サービスの状態確認
        Test-DbServices

        Write-Host ""
        Write-Host "🎉 Database services are running!"
        Write-Host ""
        Show-ManualStartInstructions

        Write-Host ""
        Write-Host "🛑 To stop databases: $($MyInvocation.MyCommand.Name) down"
        Write-Host ""
        Show-DbOnlyAccessPoints
    }
}

# ========================================
# データベースサービスの状態確認
# ========================================
function Test-DbServices {
    Write-Host "📊 Checking services status..."

    # LocalStackの確認
    try {
        $response = Invoke-RestMethod -Uri "http://localhost:$($env:DOCKER_LOCALSTACK_PORT)/_localstack/health" -Method Get -TimeoutSec 5 -ErrorAction SilentlyContinue
        if ($response) {
            Write-Host "  ✅ LocalStack is running"
        } else {
            Write-Host "  ❌ LocalStack is not responding"
        }
    } catch {
        Write-Host "  ❌ LocalStack is not responding"
    }

    # PostgreSQLの確認
    try {
        $pgResult = & pg_isready -h localhost -p $env:DOCKER_POSTGRES_PORT 2>$null
        if ($LASTEXITCODE -eq 0) {
            Write-Host "  ✅ PostgreSQL is ready"
        } else {
            Write-Host "  ⚠️  PostgreSQL is not responding"
        }
    } catch {
        Write-Host "  ⚠️  PostgreSQL is not responding (pg_isready not available)"
    }

    # DynamoDB Adminの確認
    try {
        $response = Invoke-WebRequest -Uri "http://localhost:$($env:DOCKER_DYNAMODB_ADMIN_PORT)" -Method Get -TimeoutSec 5 -ErrorAction SilentlyContinue
        if ($response.StatusCode -eq 200) {
            Write-Host "  ✅ DynamoDB Admin UI is available"
        } else {
            Write-Host "  ⚠️  DynamoDB Admin UI is not responding"
        }
    } catch {
        Write-Host "  ⚠️  DynamoDB Admin UI is not responding"
    }
}

# ========================================
# 手動起動手順の表示
# ========================================
function Show-ManualStartInstructions {
    Write-Host "🔧 To start APIs manually:"
    Write-Host "  1. Command API:"
    Write-Host "     - From IntelliJ: Run 'io.github.j5ik2o.pcqrses.commandapi.Main'"
    Write-Host "     - From sbt: sbt `"commandApi/run`""
    Write-Host "  2. Query API:"
    Write-Host "     - From IntelliJ: Run 'io.github.j5ik2o.pcqrses.queryapi.Main'"
    Write-Host "     - From sbt: sbt `"queryApi/run`""
    Write-Host ""
    Write-Host "💡 Debug configuration for IntelliJ:"
    Write-Host "  - Set these environment variables:"
    Write-Host "    J5IK2O_DYNAMO_DB_JOURNAL_DYNAMO_DB_CLIENT_ENDPOINT=http://localhost:$($env:DOCKER_LOCALSTACK_PORT)"
    Write-Host "    J5IK2O_DYNAMO_DB_SNAPSHOT_DYNAMO_DB_CLIENT_ENDPOINT=http://localhost:$($env:DOCKER_LOCALSTACK_PORT)"
    Write-Host "    J5IK2O_DYNAMO_DB_STATE_DYNAMO_DB_CLIENT_ENDPOINT=http://localhost:$($env:DOCKER_LOCALSTACK_PORT)"
    Write-Host "    AWS_REGION=ap-northeast-1"
    Write-Host "    AWS_ACCESS_KEY_ID=dummy"
    Write-Host "    AWS_SECRET_ACCESS_KEY=dummy"
    Write-Host "    PEKKO_CLUSTER_ENABLED=false"
}

# ========================================
# Lambda デプロイ処理
# ========================================
function Invoke-LambdaDeployIfEnabled {
    if ($script:AutoDeploy) {
        Write-Host ""
        Write-Host "🪄 Deploying Lambda to LocalStack..."
        $env:PORT = $env:DOCKER_LOCALSTACK_PORT
        $tries = 0
        $maxTries = 10

        while ($tries -lt $maxTries) {
            try {
                & "$ScriptDir\deploy-lambda-localstack.ps1"
                if ($LASTEXITCODE -eq 0) {
                    break
                }
            } catch {
                # Continue retrying
            }
            $tries++
            if ($tries -lt $maxTries) {
                Write-Host "⏳ Retry deploy in 3s... ($tries/$maxTries)"
                Start-Sleep -Seconds 3
            } else {
                Write-Host "❌ Lambda deploy failed after ${maxTries} attempts" -ForegroundColor Red
            }
        }

        # Lambda関数の状態確認
        Write-Host ""
        Write-Host "⏳ Waiting for Lambda to be fully registered..."
        Start-Sleep -Seconds 5
        Write-Host "📊 Checking Lambda function status..."
        $result = Test-LambdaStatus -FunctionName "pcqrses-read-model-updater" -EndpointUrl "http://localhost:$($env:DOCKER_LOCALSTACK_PORT)"
        if (-not $result) {
            Write-Host "⚠️  Lambda function may not be ready yet, but continuing..."
        }
    } else {
        Write-Host "ℹ️  Skipping Lambda auto-deploy (--no-deploy)"
    }
}

# ========================================
# DB_ONLYモードのアクセスポイント表示
# ========================================
function Show-DbOnlyAccessPoints {
    Write-Host "📍 Access points:"
    Write-Host "  - PostgreSQL: localhost:$($env:DOCKER_POSTGRES_PORT)"
    Write-Host "  - DynamoDB Admin: http://localhost:$($env:DOCKER_DYNAMODB_ADMIN_PORT)"
    Write-Host "  - pgAdmin: http://localhost:$($env:DOCKER_PGADMIN_PORT)"
    Write-Host "  - LocalStack: http://localhost:$($env:DOCKER_LOCALSTACK_PORT)"
    Write-Host ""
    Write-Host "🔄 Read Model Updater (Lambda):"
    Write-Host "  - Function: pcqrses-read-model-updater"
    Write-Host "  - Trigger: DynamoDB Streams (automatic)"
    Write-Host "  - Check status: aws lambda get-function --endpoint-url http://localhost:$($env:DOCKER_LOCALSTACK_PORT) --function-name pcqrses-read-model-updater"
}

# ========================================
# Query API のヘルスチェック
# ========================================
function Test-QueryApi {
    param([string[]]$ComposeFiles)

    Write-Host ""
    Write-Host "📊 Checking Query API status..."
    $result = Wait-ForHttp -Name "Query API" -Url "http://localhost:$($env:DOCKER_QUERY_API_PORT)/api/health" -Pattern "healthy" -TimeoutSeconds 120
    if (-not $result) {
        Write-Host "❌ Query API failed to start within 120 seconds"
        Write-Host "📜 Showing recent logs:"
        $composeArgs = $ComposeFiles | ForEach-Object { @("-f", $_) } | ForEach-Object { $_ }
        & docker compose @composeArgs logs --tail=50 query-api
        exit 1
    }
}

# ========================================
# DynamoDB Admin UI のチェック（オプション）
# ========================================
function Test-DynamoDbAdmin {
    $result = Wait-ForHttp -Name "DynamoDB Admin UI" -Url "http://localhost:$($env:DOCKER_DYNAMODB_ADMIN_PORT)" -Pattern ".*" -TimeoutSeconds 30
    if (-not $result) {
        Write-Host "  ⚠️  DynamoDB Admin UI is not available (optional service)"
    }
}

# ========================================
# 共通アクセスポイント表示
# ========================================
function Show-CommonAccessPoints {
    Write-Host "📍 Other services:"
    Write-Host "  - Query GraphQL API: http://localhost:$($env:DOCKER_QUERY_API_PORT)/api/graphql"
    Write-Host "  - Query Health Check: http://localhost:$($env:DOCKER_QUERY_API_PORT)/api/health"
    Write-Host "  - Query GraphQL Playground: http://localhost:$($env:DOCKER_QUERY_API_PORT)/api/graphql (ブラウザで開く)"
    Write-Host "  - DynamoDB Admin: http://localhost:$($env:DOCKER_DYNAMODB_ADMIN_PORT)"
    Write-Host "  - pgAdmin: http://localhost:$($env:DOCKER_PGADMIN_PORT)"
    Write-Host "  - PostgreSQL: localhost:$($env:DOCKER_POSTGRES_PORT)"
    Write-Host "  - LocalStack: http://localhost:$($env:DOCKER_LOCALSTACK_PORT)"
    Write-Host ""
    Write-Host "🔄 Read Model Updater (Lambda):"
    Write-Host "  - Function: pcqrses-read-model-updater"
    Write-Host "  - Trigger: DynamoDB Streams (automatic)"
    Write-Host "  - Check status: aws lambda get-function --endpoint-url http://localhost:$($env:DOCKER_LOCALSTACK_PORT) --function-name pcqrses-read-model-updater"
}

# ========================================
# Dockerイメージビルドと環境準備
# ========================================
function Initialize-DockerEnvironment {
    param([string[]]$ComposeFiles)

    # Dockerイメージのビルド
    Write-Host "🏗️  Building Docker images..."
    & sbt dockerBuildAll

    # 既存のコンテナを停止・削除
    Write-Host "🧹 Cleaning up existing containers..."
    $composeArgs = $ComposeFiles | ForEach-Object { @("-f", $_) } | ForEach-Object { $_ }
    & docker compose @composeArgs down
}

# ========================================
# サービス起動後の共通処理
# ========================================
function Invoke-PostStartupTasks {
    param([string[]]$ComposeFiles)

    Write-Host ""
    Write-Host "🎉 Services are running!"

    # Lambda自動デプロイ
    Invoke-LambdaDeployIfEnabled

    Write-Host ""
    Write-Host "🛑 To stop: $($MyInvocation.MyCommand.Name) down"
    Write-Host ""
}
