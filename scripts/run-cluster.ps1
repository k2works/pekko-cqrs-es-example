# ========================================
# クラスターモード起動スクリプト（3ノード）
# ========================================
# 使い方:
#   .\run-cluster.ps1 [up]   # クラスターを起動（デフォルト）
#   .\run-cluster.ps1 down   # クラスターを停止
#   .\run-cluster.ps1 logs   # サービスのログを表示
#   .\run-cluster.ps1 -h     # ヘルプ表示

param(
    [Parameter(ValueFromRemainingArguments=$true)]
    [string[]]$Arguments
)

$ErrorActionPreference = "Stop"

# 共通関数の読み込み
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. "$ScriptDir\run-common.ps1"

# ========================================
# クラスター用環境変数設定
# ========================================
function Initialize-ClusterEnvironment {
    # 基本環境変数設定
    Initialize-Environment

    # クラスターノード設定
    $env:CLUSTER_NODE1_API_PORT = if ($env:CLUSTER_NODE1_API_PORT) { $env:CLUSTER_NODE1_API_PORT } else { "50501" }
    $env:CLUSTER_NODE1_MANAGEMENT_PORT = if ($env:CLUSTER_NODE1_MANAGEMENT_PORT) { $env:CLUSTER_NODE1_MANAGEMENT_PORT } else { "8558" }
    $env:CLUSTER_NODE1_REMOTE_PORT = if ($env:CLUSTER_NODE1_REMOTE_PORT) { $env:CLUSTER_NODE1_REMOTE_PORT } else { "2551" }

    $env:CLUSTER_NODE2_API_PORT = if ($env:CLUSTER_NODE2_API_PORT) { $env:CLUSTER_NODE2_API_PORT } else { "50511" }
    $env:CLUSTER_NODE2_MANAGEMENT_PORT = if ($env:CLUSTER_NODE2_MANAGEMENT_PORT) { $env:CLUSTER_NODE2_MANAGEMENT_PORT } else { "8559" }
    $env:CLUSTER_NODE2_REMOTE_PORT = if ($env:CLUSTER_NODE2_REMOTE_PORT) { $env:CLUSTER_NODE2_REMOTE_PORT } else { "2552" }

    $env:CLUSTER_NODE3_API_PORT = if ($env:CLUSTER_NODE3_API_PORT) { $env:CLUSTER_NODE3_API_PORT } else { "50521" }
    $env:CLUSTER_NODE3_MANAGEMENT_PORT = if ($env:CLUSTER_NODE3_MANAGEMENT_PORT) { $env:CLUSTER_NODE3_MANAGEMENT_PORT } else { "8560" }
    $env:CLUSTER_NODE3_REMOTE_PORT = if ($env:CLUSTER_NODE3_REMOTE_PORT) { $env:CLUSTER_NODE3_REMOTE_PORT } else { "2553" }
}

# ========================================
# ヘルプ表示
# ========================================
function Show-Help {
    Write-Host "Usage: $($MyInvocation.MyCommand.Name) [COMMAND] [OPTIONS]"
    Write-Host ""
    Write-Host "Commands:"
    Write-Host "  up         Start cluster (default)"
    Write-Host "  down       Stop and remove containers"
    Write-Host "  logs       Show service logs"
    Write-Host ""
    Write-Host "Options:"
    Write-Host "  --attach     Run containers in foreground (default: background)"
    Write-Host "  --no-deploy  Do not auto-deploy LocalStack Lambda"
    Write-Host "  --deploy     Force auto-deploy (default)"
    Write-Host "  -h, --help   Show this help message"
    Write-Host ""
    Write-Host "Examples:"
    Write-Host "  .\run-cluster.ps1           # Start cluster in background"
    Write-Host "  .\run-cluster.ps1 up        # Start cluster in background (default)"
    Write-Host "  .\run-cluster.ps1 up --attach # Start cluster in foreground"
    Write-Host "  .\run-cluster.ps1 down      # Stop cluster"
    Write-Host "  .\run-cluster.ps1 logs      # Show cluster logs"
    Write-Host "  .\run-cluster.ps1 logs -f   # Follow cluster logs"
    Write-Host ""
    Write-Host "Environment variables:"
    Write-Host "  【共通設定】"
    Write-Host "  DOCKER_LOCALSTACK_PORT (default: 50503)"
    Write-Host "  DOCKER_DYNAMODB_ADMIN_PORT (default: 50505)"
    Write-Host "  DOCKER_POSTGRES_PORT (default: 50504)"
    Write-Host "  DOCKER_QUERY_API_PORT (default: 50502)"
    Write-Host ""
    Write-Host "  【クラスター設定】"
    Write-Host "  CLUSTER_NODE1_API_PORT (default: 50501)"
    Write-Host "  CLUSTER_NODE1_MANAGEMENT_PORT (default: 8558)"
    Write-Host "  CLUSTER_NODE2_API_PORT (default: 50511)"
    Write-Host "  CLUSTER_NODE2_MANAGEMENT_PORT (default: 8559)"
    Write-Host "  CLUSTER_NODE3_API_PORT (default: 50521)"
    Write-Host "  CLUSTER_NODE3_MANAGEMENT_PORT (default: 8560)"
    exit 0
}

# ========================================
# クラスターノードのヘルスチェック
# ========================================
function Test-ClusterNodes {
    param([string[]]$ComposeFiles)

    $apiPorts = @($env:CLUSTER_NODE1_API_PORT, $env:CLUSTER_NODE2_API_PORT, $env:CLUSTER_NODE3_API_PORT)
    $mgmtPorts = @($env:CLUSTER_NODE1_MANAGEMENT_PORT, $env:CLUSTER_NODE2_MANAGEMENT_PORT, $env:CLUSTER_NODE3_MANAGEMENT_PORT)

    for ($i = 0; $i -lt 3; $i++) {
        $nodeNum = $i + 1
        $apiPort = $apiPorts[$i]
        $mgmtPort = $mgmtPorts[$i]

        Write-Host ""
        Write-Host "Node $nodeNum (API: $apiPort, Management: $mgmtPort):"

        # コンテナが実行中か確認
        $containerStatus = & docker compose -f docker-compose-common.yml -f docker-compose-cluster.yml ps "command-api-$nodeNum" 2>&1
        if ($containerStatus -notmatch "Up|running") {
            Write-Host "  ❌ Node $nodeNum container is not running!"
            Write-Host "  📜 Showing recent logs:"
            & docker compose -f docker-compose-common.yml -f docker-compose-cluster.yml logs --tail=50 "command-api-$nodeNum"
            exit 1
        }

        # HTTPヘルスチェック
        $result = Wait-ForHttp -Name "Node $nodeNum HTTP" -Url "http://localhost:$apiPort/health" -Pattern "Healthy" -TimeoutSeconds 120
        if (-not $result) {
            Write-Host "❌ Node $nodeNum Command API failed to start within 120 seconds"
            Write-Host "📜 Showing recent logs for node $nodeNum :"
            $composeArgs = $ComposeFiles | ForEach-Object { @("-f", $_) } | ForEach-Object { $_ }
            & docker compose @composeArgs logs --tail=50 "command-api-$nodeNum"
            exit 1
        }

        # Pekko Management確認
        $result = Wait-ForHttp -Name "Node $nodeNum Cluster" -Url "http://localhost:$mgmtPort/cluster/members" -Pattern "Up" -TimeoutSeconds 120
        if (-not $result) {
            Write-Host "❌ Node $nodeNum Cluster Management failed to start within 120 seconds"
            Write-Host "📜 Showing recent logs for node $nodeNum :"
            $composeArgs = $ComposeFiles | ForEach-Object { @("-f", $_) } | ForEach-Object { $_ }
            & docker compose @composeArgs logs --tail=50 "command-api-$nodeNum"
            exit 1
        }
    }
}

# ========================================
# クラスターアクセスポイント表示
# ========================================
function Show-ClusterAccessPoints {
    Write-Host "📍 Access points:"
    Write-Host "  - Command Node 1: http://localhost:$($env:CLUSTER_NODE1_API_PORT)"
    Write-Host "    Command Health: http://localhost:$($env:CLUSTER_NODE1_API_PORT)/health"
    Write-Host "    Command Management: http://localhost:$($env:CLUSTER_NODE1_MANAGEMENT_PORT)"
    Write-Host ""
    Write-Host "  - Command Node 2: http://localhost:$($env:CLUSTER_NODE2_API_PORT)"
    Write-Host "    Command Health: http://localhost:$($env:CLUSTER_NODE2_API_PORT)/health"
    Write-Host "    Command Management: http://localhost:$($env:CLUSTER_NODE2_MANAGEMENT_PORT)"
    Write-Host ""
    Write-Host "  - Command Node 3: http://localhost:$($env:CLUSTER_NODE3_API_PORT)"
    Write-Host "    Command Health: http://localhost:$($env:CLUSTER_NODE3_API_PORT)/health"
    Write-Host "    Command Management: http://localhost:$($env:CLUSTER_NODE3_MANAGEMENT_PORT)"
    Write-Host ""
    Write-Host "📊 Cluster Management UI:"
    Write-Host "  - http://localhost:$($env:CLUSTER_NODE1_MANAGEMENT_PORT)/cluster/members"
    Write-Host ""
}

# ========================================
# クラスター起動処理
# ========================================
function Start-ClusterServices {
    Write-Host "🌐 Starting Command API Cluster (3 nodes)..."
    Write-Host ""

    $composeFiles = @("docker-compose-common.yml", "docker-compose-cluster.yml")
    Initialize-DockerEnvironment -ComposeFiles $composeFiles

    # クラスターの起動
    Write-Host "🚀 Starting cluster nodes..."
    if ($script:AttachMode) {
        Write-Host "📎 Running in foreground mode (Ctrl+C to stop)..."
        & docker compose -f docker-compose-common.yml -f docker-compose-cluster.yml up
    } else {
        & docker compose -f docker-compose-common.yml -f docker-compose-cluster.yml up -d

        # ヘルスチェック
        Write-Host "⏳ Waiting for cluster to be ready..."
        Start-Sleep -Seconds 10

        # 各ノードの状態を確認
        Write-Host "📊 Checking cluster status..."
        Test-ClusterNodes -ComposeFiles $composeFiles

        # Query API の状態確認
        Test-QueryApi -ComposeFiles $composeFiles

        Invoke-PostStartupTasks -ComposeFiles $composeFiles

        # アクセスポイント表示
        Show-ClusterAccessPoints
        Show-CommonAccessPoints
    }
}

# ========================================
# メイン処理
# ========================================
function Main {
    # デフォルト設定
    Initialize-Defaults

    # 引数処理（--db-onlyはクラスターモードでは無効）
    $script:DbOnly = $false
    Read-Arguments -Arguments $Arguments

    # 環境変数設定
    Initialize-ClusterEnvironment

    # ヘルプ表示
    if ($script:ShowHelp) {
        Show-Help
    }

    # コマンド処理
    switch ($script:Command) {
        "down" {
            Invoke-DownCommand -ComposeFiles @("docker-compose-common.yml", "docker-compose-cluster.yml")
        }

        "logs" {
            Invoke-LogsCommand -ComposeFiles @("docker-compose-common.yml", "docker-compose-cluster.yml") -ExtraArgs $script:RemainingArgs
        }

        "up" {
            Start-ClusterServices
        }
    }
}

# スクリプト実行
Main
