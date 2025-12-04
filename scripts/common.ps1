# Common helper functions for run scripts

# Wait for an HTTP endpoint to return content matching a pattern
# Usage: Wait-ForHttp -Name "Name" -Url "http://host:port/path" -Pattern "Pattern" -TimeoutSeconds 20
function Wait-ForHttp {
    param(
        [Parameter(Mandatory=$true)][string]$Name,
        [Parameter(Mandatory=$true)][string]$Url,
        [Parameter(Mandatory=$true)][string]$Pattern,
        [int]$TimeoutSeconds = 20
    )

    $waited = 0
    Write-Host "  ⏳ Waiting for ${Name}..."

    while ($waited -lt $TimeoutSeconds) {
        try {
            $response = Invoke-RestMethod -Uri $Url -Method Get -TimeoutSec 5 -ErrorAction SilentlyContinue
            if ($response -match $Pattern) {
                Write-Host "  ✅ ${Name} is ready"
                return $true
            }
        } catch {
            # Ignore errors and retry
        }
        Start-Sleep -Seconds 2
        $waited += 2
    }

    Write-Host "  ⚠️  ${Name} is not responding yet"
    return $false
}

# Check if Lambda function exists and is active in LocalStack
# Usage: Test-LambdaStatus -FunctionName "function-name" -EndpointUrl "http://localhost:50503" -MaxRetries 3
function Test-LambdaStatus {
    param(
        [Parameter(Mandatory=$true)][string]$FunctionName,
        [string]$EndpointUrl = "http://localhost:$($env:DOCKER_LOCALSTACK_PORT ?? '50503')",
        [int]$MaxRetries = 3
    )

    Write-Host "  🔍 Checking Lambda function: ${FunctionName}..."

    # LocalStack用のAWS認証情報を設定
    $env:AWS_REGION = "ap-northeast-1"
    $env:AWS_ACCESS_KEY_ID = "dummy"
    $env:AWS_SECRET_ACCESS_KEY = "dummy"

    # リトライロジック
    $retryCount = 0
    $found = $false

    while ($retryCount -lt $MaxRetries) {
        try {
            # Lambda関数リストを取得
            $allFunctions = aws lambda list-functions `
                --endpoint-url $EndpointUrl `
                --query 'Functions[*].FunctionName' `
                --output text 2>$null

            if ($allFunctions) {
                if ($allFunctions -match $FunctionName) {
                    $found = $true
                    break
                }
            }
        } catch {
            # Ignore errors and retry
        }

        $retryCount++
        if ($retryCount -lt $MaxRetries) {
            Write-Host "  ⏳ Waiting for Lambda to register... (attempt $retryCount/$MaxRetries)"
            Start-Sleep -Seconds 3
        }
    }

    if (-not $found) {
        if (-not $allFunctions) {
            Write-Host "  ⚠️  No Lambda functions found in LocalStack after $MaxRetries attempts"
            Write-Host "  💡 Hint: Lambda deployment may have failed or LocalStack is not ready"
        } else {
            Write-Host "  ⚠️  Lambda function '${FunctionName}' not found after $MaxRetries attempts"
            Write-Host "  📋 Available functions: ${allFunctions}"
            Write-Host "  💡 Try deploying with: .\scripts\deploy-lambda-localstack.ps1"
        }
        return $false
    }

    # Lambda関数の状態確認
    try {
        $state = aws lambda get-function `
            --endpoint-url $EndpointUrl `
            --function-name $FunctionName `
            --query 'Configuration.State' `
            --output text 2>$null
    } catch {
        $state = $null
    }

    if ($state -eq "Active") {
        Write-Host "  ✅ Lambda function is Active"

        # イベントソースマッピングの確認
        try {
            $mappings = aws lambda list-event-source-mappings `
                --endpoint-url $EndpointUrl `
                --function-name $FunctionName `
                --query 'EventSourceMappings[*].State' `
                --output text 2>$null
        } catch {
            $mappings = $null
        }

        if ($mappings) {
            Write-Host "  📎 Event source mappings: ${mappings}"
        } else {
            Write-Host "  ⚠️  No event source mappings found"
        }

        return $true
    } else {
        Write-Host "  ⏳ Lambda function state: ${state}"
        return $false
    }
}

# Check Lambda function logs in LocalStack
# Usage: Get-LambdaLogs -FunctionName "function-name" -TailLines 10
function Get-LambdaLogs {
    param(
        [Parameter(Mandatory=$true)][string]$FunctionName,
        [string]$EndpointUrl = "http://localhost:$($env:DOCKER_LOCALSTACK_PORT ?? '50503')",
        [int]$TailLines = 10
    )

    Write-Host "  📋 Recent Lambda logs (last ${TailLines} lines):"

    try {
        $logs = docker logs localstack 2>&1 | Select-String -Pattern $FunctionName -Context 0,2 | Select-Object -Last $TailLines
        if ($logs) {
            $logs | ForEach-Object { Write-Host $_ }
        } else {
            Write-Host "  ⚠️  No recent logs found for ${FunctionName}"
            return $false
        }
    } catch {
        Write-Host "  ⚠️  No recent logs found for ${FunctionName}"
        return $false
    }

    return $true
}
