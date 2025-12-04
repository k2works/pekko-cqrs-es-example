# ========================================
# 単一ノード構成起動スクリプト
# ========================================
# 使い方:
#   .\run-single.ps1 [up]      # 全サービスをDockerで起動（デフォルト）
#   .\run-single.ps1 down      # 全サービスを停止
#   .\run-single.ps1 logs      # サービスのログを表示
#   .\run-single.ps1 --db-only # データベースのみ起動（デバッグ用）
#   .\run-single.ps1 -h        # ヘルプ表示

param(
    [Parameter(ValueFromRemainingArguments=$true)]
    [string[]]$Arguments
)

$ErrorActionPreference = "Stop"

# 共通関数の読み込み
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. "$ScriptDir\run-common.ps1"

# ========================================
# ヘルプ表示
# ========================================
function Show-Help {
    Write-Host "Usage: $($MyInvocation.MyCommand.Name) [COMMAND] [OPTIONS]"
    Write-Host ""
    Write-Host "Commands:"
    Write-Host "  up         Start services (default)"
    Write-Host "  down       Stop and remove containers"
    Write-Host "  logs       Show service logs"
    Write-Host ""
    Write-Host "Options:"
    Write-Host "  --attach     Run containers in foreground (default: background)"
    Write-Host "  --db-only    Run only database services in Docker (for debugging)"
    Write-Host "  --no-deploy  Do not auto-deploy LocalStack Lambda"
    Write-Host "  --deploy     Force auto-deploy (default)"
    Write-Host "  -h, --help   Show this help message"
    Write-Host ""
    Write-Host "Examples:"
    Write-Host "  .\run-single.ps1           # Start all services in background"
    Write-Host "  .\run-single.ps1 up        # Start all services in background (default)"
    Write-Host "  .\run-single.ps1 up --attach # Start all services in foreground"
    Write-Host "  .\run-single.ps1 down      # Stop all services"
    Write-Host "  .\run-single.ps1 logs      # Show service logs"
    Write-Host "  .\run-single.ps1 logs -f   # Follow service logs"
    Write-Host "  .\run-single.ps1 --db-only # Run only DBs, start APIs from IDE"
    Write-Host ""
    Write-Host "Environment variables:"
    Write-Host "  DOCKER_COMMAND_API_PORT (default: 50501)"
    Write-Host "  DOCKER_QUERY_API_PORT (default: 50502)"
    Write-Host "  DOCKER_POSTGRES_PORT (default: 50504)"
    Write-Host "  DOCKER_LOCALSTACK_PORT (default: 50503)"
    Write-Host "  DOCKER_DYNAMODB_ADMIN_PORT (default: 50505)"
    exit 0
}

# ========================================
# Command API起動（単一ノードモード）
# ========================================
function Start-SingleNodeServices {
    Write-Host "🐳 Starting Development Environment with Docker..."
    Write-Host "   (All services run in containers)"
    Write-Host ""

    $composeFiles = @("docker-compose-common.yml", "docker-compose-local.yml")
    Initialize-DockerEnvironment -ComposeFiles $composeFiles

    # 開発環境の起動
    Write-Host "🚀 Starting services..."
    if ($script:AttachMode) {
        Write-Host "📎 Running in foreground mode (Ctrl+C to stop)..."
        & docker compose -f docker-compose-common.yml -f docker-compose-local.yml up
    } else {
        & docker compose -f docker-compose-common.yml -f docker-compose-local.yml up -d

        # ヘルスチェック
        Write-Host "⏳ Waiting for services to be ready..."
        Start-Sleep -Seconds 5

        # Command API の状態確認
        Write-Host "📊 Checking services status..."
        $result = Wait-ForHttp -Name "Command API" -Url "http://localhost:$($env:DOCKER_COMMAND_API_PORT)/api/health" -Pattern "healthy" -TimeoutSeconds 120
        if (-not $result) {
            Write-Host "❌ Command API failed to start within 120 seconds"
            Write-Host "📜 Showing recent logs:"
            & docker compose -f docker-compose-common.yml -f docker-compose-local.yml logs --tail=50 command-api
            exit 1
        }

        # Query API の状態確認
        Test-QueryApi -ComposeFiles $composeFiles

        # DynamoDB Admin UI の確認（オプション）
        Test-DynamoDbAdmin

        Invoke-PostStartupTasks -ComposeFiles $composeFiles

        # アクセスポイント表示
        Write-Host "📍 Access points:"
        Write-Host "  - Command GraphQL API: http://localhost:$($env:DOCKER_COMMAND_API_PORT)/api/graphql"
        Write-Host "  - Command Health Check: http://localhost:$($env:DOCKER_COMMAND_API_PORT)/api/health"
        Write-Host "  - Command GraphQL Playground: http://localhost:$($env:DOCKER_COMMAND_API_PORT)/api/graphql (ブラウザで開く)"
        Show-CommonAccessPoints
    }
}

# ========================================
# メイン処理
# ========================================
function Main {
    # デフォルト設定
    Initialize-Defaults

    # 引数処理
    Read-Arguments -Arguments $Arguments

    # 環境変数設定
    Initialize-Environment

    # ヘルプ表示
    if ($script:ShowHelp) {
        Show-Help
    }

    # コマンド処理
    switch ($script:Command) {
        "down" {
            if ($script:DbOnly) {
                Invoke-DownCommand -ComposeFiles @("docker-compose-common.yml")
            } else {
                Invoke-DownCommand -ComposeFiles @("docker-compose-common.yml", "docker-compose-local.yml")
            }
        }

        "logs" {
            if ($script:DbOnly) {
                Invoke-LogsCommand -ComposeFiles @("docker-compose-common.yml") -ExtraArgs $script:RemainingArgs
            } else {
                Invoke-LogsCommand -ComposeFiles @("docker-compose-common.yml", "docker-compose-local.yml") -ExtraArgs $script:RemainingArgs
            }
        }

        "up" {
            if ($script:DbOnly) {
                Start-DbOnlyMode
            } else {
                Start-SingleNodeServices
            }
        }
    }
}

# スクリプト実行
Main
