# LocalStack Lambda クリーンアップスクリプト
# read-model-updater Lambda関数とイベントソースマッピングを削除します

$ErrorActionPreference = "Stop"

# 環境変数の設定
$env:AWS_ACCESS_KEY_ID = "dummy"
$env:AWS_SECRET_ACCESS_KEY = "dummy"
$env:AWS_DEFAULT_REGION = "ap-northeast-1"
$EndpointUrl = "http://localhost:4566"

# Lambda関数名
$FunctionName = "pcqrses-read-model-updater"

Write-Host "🧹 LocalStack Lambda クリーンアップを開始します..."

# イベントソースマッピングを削除
Write-Host "🔗 イベントソースマッピングを削除中..."
try {
    $uuids = aws lambda list-event-source-mappings `
        --endpoint-url $EndpointUrl `
        --function-name $FunctionName `
        --query 'EventSourceMappings[].UUID' `
        --output text 2>$null

    if ($uuids -and $uuids -ne "None") {
        foreach ($uuid in $uuids.Split()) {
            if ($uuid) {
                Write-Host "🗑️  イベントソースマッピング $uuid を削除中..."
                aws lambda delete-event-source-mapping `
                    --endpoint-url $EndpointUrl `
                    --uuid $uuid 2>$null
            }
        }
    }
} catch {
    # Ignore errors
}

# Lambda関数を削除
Write-Host "🗑️  Lambda関数 $FunctionName を削除中..."
try {
    aws lambda delete-function `
        --endpoint-url $EndpointUrl `
        --function-name $FunctionName 2>$null
    Write-Host "✅ Lambda関数を削除しました"
} catch {
    Write-Host "⚠️  Lambda関数が見つかりませんでした（既に削除済みの可能性があります）"
}

Write-Host ""
Write-Host "✅ クリーンアップが完了しました!"
