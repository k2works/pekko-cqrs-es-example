# LocalStack Lambda デプロイスクリプト
# read-model-updater を LocalStack Lambda にデプロイします

param(
    [switch]$Force
)

$ErrorActionPreference = "Stop"

# 強制再作成オプションの確認
$ForceRecreate = $Force
if ($ForceRecreate) {
    Write-Host "⚠️  強制再作成モードが有効です"
}

# 環境変数の設定
$env:AWS_ACCESS_KEY_ID = "dummy"
$env:AWS_SECRET_ACCESS_KEY = "dummy"
$env:AWS_DEFAULT_REGION = "ap-northeast-1"
$Port = if ($env:PORT) { $env:PORT } else { "50503" }
$Host_ = if ($env:HOST) { $env:HOST } else { "localhost" }
$EndpointUrl = "http://${Host_}:${Port}"
$ScalaVersion = "3.6.2"
$ProjectName = "read-model-updater"

# Lambda関数名
$FunctionName = "pcqrses-read-model-updater"

# DynamoDB テーブル名
$TableName = "Journal"

# アーキテクチャの自動検出
$arch = [System.Runtime.InteropServices.RuntimeInformation]::ProcessArchitecture
if ($arch -eq "Arm64") {
    $LambdaArch = "arm64"
    Write-Host "🔧 検出されたアーキテクチャ: ARM64"
} else {
    $LambdaArch = "x86_64"
    Write-Host "🔧 検出されたアーキテクチャ: x86_64"
}

# 環境変数でオーバーライド可能
if ($env:LAMBDA_ARCHITECTURE) {
    $LambdaArch = $env:LAMBDA_ARCHITECTURE
    Write-Host "⚙️  環境変数によるアーキテクチャ指定: ${LambdaArch}"
}

Write-Host "🚀 LocalStack Lambda デプロイを開始します..."

# プロジェクトルートに移動
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location "$ScriptDir\.."

try {
    Write-Host "📦 read-model-updater をビルド中..."
    # sbt assembly で fat JAR を作成
    $env:PAGER = "cat"
    & sbt --batch "project readModelUpdater" assembly
    if ($LASTEXITCODE -ne 0) {
        throw "sbt assembly failed"
    }

    # Assembly JARのパス
    $AssemblyJarPath = "apps\${ProjectName}\target\scala-${ScalaVersion}\${ProjectName}-lambda.jar"

    # Assembly JARが存在することを確認
    if (-not (Test-Path $AssemblyJarPath)) {
        throw "❌ エラー: Assembly JAR が見つかりません: $AssemblyJarPath"
    }

    Write-Host "✅ Assembly JAR が作成されました: $AssemblyJarPath"

    # DynamoDB ストリーム ARN を取得
    Write-Host "🔍 DynamoDB ストリーム ARN を取得中..."
    $StreamArn = aws dynamodb describe-table `
        --endpoint-url $EndpointUrl `
        --table-name $TableName `
        --query 'Table.LatestStreamArn' `
        --output text

    if (-not $StreamArn -or $StreamArn -eq "None") {
        throw "❌ エラー: DynamoDB テーブル '$TableName' のストリームが見つかりません`n   テーブルにストリームが有効化されていることを確認してください"
    }

    Write-Host "✅ ストリーム ARN: $StreamArn"

    # Lambda関数の存在確認
    Write-Host "🔧 Lambda関数の存在確認中..."
    $FunctionExists = $false
    try {
        $existingFunction = aws lambda get-function `
            --endpoint-url $EndpointUrl `
            --function-name $FunctionName `
            --query 'Configuration.FunctionName' `
            --output text 2>$null
        if ($existingFunction) {
            $FunctionExists = $true
        }
    } catch {
        $FunctionExists = $false
    }

    # 環境変数JSONを作成
    $EnvJson = @{
        Variables = @{
            DATABASE_URL = "jdbc:postgresql://postgres:5432/p-cqrs-es_development"
            DATABASE_USER = "postgres"
            DATABASE_PASSWORD = "postgres"
            AWS_DEFAULT_REGION = "ap-northeast-1"
            DYNAMODB_ENDPOINT_URI = "http://localstack:4566"
            KINESIS_ENDPOINT_URI = "http://localstack:4566"
            CLOUDWATCH_ENDPOINT_URI = "http://localstack:4566"
        }
    } | ConvertTo-Json -Compress

    $EnvJsonFile = [System.IO.Path]::GetTempFileName()
    $EnvJson | Out-File -FilePath $EnvJsonFile -Encoding utf8

    # LambdaのActive化を待機する関数
    function Wait-LambdaActive {
        param([string]$FnName)

        $timeout = 60
        $waited = 0
        Write-Host "⏳ Lambda関数のActive化を待機中... ($FnName)"

        while ($true) {
            try {
                $state = aws lambda get-function `
                    --endpoint-url $EndpointUrl `
                    --function-name $FnName `
                    --query 'Configuration.State' `
                    --output text 2>$null
            } catch {
                $state = ""
            }

            if ($state -eq "Active") {
                Write-Host "✅ Lambda関数がActiveになりました: $FnName"
                break
            }
            if ($state -eq "Failed") {
                throw "❌ Lambda関数がFailed状態です: $FnName"
            }

            Start-Sleep -Seconds 2
            $waited += 2
            if ($waited -ge $timeout) {
                Write-Host "⚠️  タイムアウト: Lambda関数がActiveになりませんでした ($FnName, state=$state)"
                break
            }
        }
    }

    if ($FunctionExists) {
        if ($ForceRecreate) {
            Write-Host "🗑️  既存のLambda関数を削除中..."
            aws lambda delete-function `
                --endpoint-url $EndpointUrl `
                --function-name $FunctionName

            Write-Host "🆕 新しいLambda関数を作成中..."
            aws lambda create-function `
                --endpoint-url $EndpointUrl `
                --function-name $FunctionName `
                --runtime java17 `
                --handler io.github.j5ik2o.pcqrses.readModelUpdater.LambdaHandler `
                --role "arn:aws:iam::000000000000:role/lambda-role" `
                --zip-file "fileb://$AssemblyJarPath" `
                --timeout 300 `
                --memory-size 512 `
                --architectures $LambdaArch `
                --environment "file://$EnvJsonFile"

            Wait-LambdaActive -FnName $FunctionName
        } else {
            Write-Host "🔄 既存のLambda関数を更新中..."
            # 関数の設定を更新
            aws lambda update-function-configuration `
                --endpoint-url $EndpointUrl `
                --function-name $FunctionName `
                --runtime java17 `
                --handler io.github.j5ik2o.pcqrses.readModelUpdater.LambdaHandler `
                --timeout 300 `
                --memory-size 512 `
                --environment "file://$EnvJsonFile"

            # 設定の更新を待つ
            Start-Sleep -Seconds 2

            # 関数のコードを更新
            aws lambda update-function-code `
                --endpoint-url $EndpointUrl `
                --function-name $FunctionName `
                --zip-file "fileb://$AssemblyJarPath"

            Wait-LambdaActive -FnName $FunctionName
        }
    } else {
        Write-Host "🆕 新しいLambda関数を作成中..."
        aws lambda create-function `
            --endpoint-url $EndpointUrl `
            --function-name $FunctionName `
            --runtime java17 `
            --handler io.github.j5ik2o.pcqrses.readModelUpdater.LambdaHandler `
            --role "arn:aws:iam::000000000000:role/lambda-role" `
            --zip-file "fileb://$AssemblyJarPath" `
            --timeout 300 `
            --memory-size 512 `
            --architectures $LambdaArch `
            --environment "file://$EnvJsonFile"

        Wait-LambdaActive -FnName $FunctionName
    }

    # 一時ファイルを削除
    Remove-Item $EnvJsonFile -ErrorAction SilentlyContinue

    # DynamoDB ストリームのイベントソースマッピングを作成
    Write-Host "🔗 DynamoDB ストリームのイベントソースマッピングを作成中..."

    # 既存のイベントソースマッピングを確認して削除
    Write-Host "📋 既存のイベントソースマッピングを確認中..."
    $existingUuids = aws lambda list-event-source-mappings `
        --endpoint-url $EndpointUrl `
        --function-name $FunctionName `
        --query "EventSourceMappings[?EventSourceArn=='$StreamArn'].UUID" `
        --output text 2>$null

    $mappingDeleted = $false
    if ($existingUuids -and $existingUuids -ne "None") {
        foreach ($uuid in $existingUuids.Split()) {
            if ($uuid) {
                Write-Host "🗑️  既存のイベントソースマッピング $uuid を削除中..."
                try {
                    aws lambda delete-event-source-mapping `
                        --endpoint-url $EndpointUrl `
                        --uuid $uuid 2>$null
                    Write-Host "   ✓ 削除リクエスト送信完了: $uuid"
                    $mappingDeleted = $true
                } catch {
                    # Ignore errors
                }
            }
        }

        if ($mappingDeleted) {
            Write-Host "⏳ イベントソースマッピングの削除完了を待機中..."
            $retries = 0
            while ($retries -lt 15) {
                Start-Sleep -Seconds 2
                $allMappings = aws lambda list-event-source-mappings `
                    --endpoint-url $EndpointUrl `
                    --function-name $FunctionName `
                    --query "EventSourceMappings[?EventSourceArn=='$StreamArn'].[UUID, State]" `
                    --output text 2>$null

                if (-not $allMappings -or $allMappings -eq "None") {
                    Write-Host "✅ 既存のマッピングが削除されました"
                    break
                }

                Write-Host "   待機中... ($($retries+1)/15) - 状態: $allMappings"
                $retries++
            }
        }
    } else {
        Write-Host "✅ 既存のイベントソースマッピングなし"
    }

    # 新しいイベントソースマッピングを作成
    Write-Host "📝 新しいイベントソースマッピングを作成中..."
    aws lambda create-event-source-mapping `
        --endpoint-url $EndpointUrl `
        --function-name $FunctionName `
        --event-source-arn $StreamArn `
        --starting-position LATEST `
        --batch-size 10 `
        --maximum-batching-window-in-seconds 5

    Write-Host "🧹 デプロイメント完了"

    Write-Host ""
    Write-Host "✅ Lambda関数のデプロイが完了しました!"
    Write-Host "   関数名: $FunctionName"
    Write-Host "   ハンドラー: io.github.j5ik2o.pcqrses.readModelUpdater.LambdaHandler"
    Write-Host "   ストリーム ARN: $StreamArn"
    Write-Host ""
    Write-Host "📋 確認用コマンド:"
    Write-Host "   aws lambda list-functions --endpoint-url $EndpointUrl --query `"Functions[?FunctionName=='$FunctionName']`""
    Write-Host "   aws lambda list-event-source-mappings --endpoint-url $EndpointUrl --function-name $FunctionName"
    Write-Host ""
    Write-Host "🧪 テスト用コマンド:"
    Write-Host "   aws lambda invoke --endpoint-url $EndpointUrl --function-name $FunctionName --payload '{}' response.json"
    Write-Host ""

    # 最終確認
    Write-Host "🔍 最終確認: デプロイされたLambda関数"
    aws lambda list-functions `
        --endpoint-url $EndpointUrl `
        --query "Functions[?FunctionName=='$FunctionName'].{Name:FunctionName,State:State,Runtime:Runtime}" `
        --output table 2>$null

} finally {
    Pop-Location
}
