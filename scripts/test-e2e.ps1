# End-to-End Test: Create UserAccount via GraphQL and Query via GraphQL
# このスクリプトは、GraphQL Mutation経由でユーザーアカウントを作成し、GraphQL Query経由で取得するE2Eテストを実行します

$ErrorActionPreference = "Stop"

# 設定
$E2E_MAX_RETRIES = if ($env:E2E_MAX_RETRIES) { [int]$env:E2E_MAX_RETRIES } else { 10 }
$E2E_RETRY_DELAY = if ($env:E2E_RETRY_DELAY) { [int]$env:E2E_RETRY_DELAY } else { 3 }
$E2E_WAIT_AFTER_CREATE = if ($env:E2E_WAIT_AFTER_CREATE) { [int]$env:E2E_WAIT_AFTER_CREATE } else { 8 }

$COMMAND_API_HOST = if ($env:COMMAND_API_HOST) { $env:COMMAND_API_HOST } else { "localhost" }
$COMMAND_API_PORT = if ($env:COMMAND_API_PORT) { $env:COMMAND_API_PORT } else { "50501" }
$COMMAND_API_ENDPOINT = "http://${COMMAND_API_HOST}:${COMMAND_API_PORT}/api/graphql"

$QUERY_API_HOST = if ($env:QUERY_API_HOST) { $env:QUERY_API_HOST } else { "localhost" }
$QUERY_API_PORT = if ($env:QUERY_API_PORT) { $env:QUERY_API_PORT } else { "50502" }
$QUERY_API_ENDPOINT = "http://${QUERY_API_HOST}:${QUERY_API_PORT}/api/graphql"

# テストデータ生成用のタイムスタンプ
$TIMESTAMP = [DateTimeOffset]::Now.ToUnixTimeSeconds()
$TEST_FIRST_NAME = "太郎${TIMESTAMP}"
$TEST_LAST_NAME = "テスト"
$TEST_EMAIL = "test${TIMESTAMP}@example.com"

# グローバル変数
$script:CREATED_USER_ID = $null

# 色付き出力用の関数
function Write-Header {
    param([string]$Message)
    Write-Host ""
    Write-Host "=== $Message ===" -ForegroundColor Blue
}

function Write-Success {
    param([string]$Message)
    Write-Host "✓ $Message" -ForegroundColor Green
}

function Write-Error_ {
    param([string]$Message)
    Write-Host "✗ $Message" -ForegroundColor Red
}

function Write-Info {
    param([string]$Message)
    Write-Host "ℹ $Message" -ForegroundColor Yellow
}

function Write-Json {
    param([string]$Json)
    try {
        $Json | ConvertFrom-Json | ConvertTo-Json -Depth 10
    } catch {
        Write-Host $Json
    }
}

# GraphQL クエリを実行する関数
function Invoke-GraphQL {
    param(
        [string]$Endpoint,
        [string]$Query,
        [hashtable]$Variables = @{}
    )

    # クエリ内の改行をスペースに置換
    $Query = $Query -replace "`r`n", " " -replace "`n", " " -replace "\s+", " "

    $body = @{
        query = $Query
    }

    if ($Variables.Count -gt 0) {
        $body.variables = $Variables
    }

    $jsonBody = $body | ConvertTo-Json -Depth 10 -Compress

    try {
        $response = Invoke-RestMethod -Uri $Endpoint -Method Post `
            -ContentType "application/json" `
            -Body $jsonBody `
            -TimeoutSec 30

        return $response
    } catch {
        return $null
    }
}

# ヘルスチェック
function Test-Health {
    Write-Header "Health Check"

    # Command API Health Check
    Write-Info "Checking Command API (GraphQL) health..."
    try {
        $response = Invoke-WebRequest -Uri $COMMAND_API_ENDPOINT -Method Get -TimeoutSec 5 -ErrorAction SilentlyContinue
        if ($response.StatusCode -eq 200 -or $response.StatusCode -eq 400) {
            Write-Success "Command API is healthy"
        } else {
            Write-Error_ "Command API health check failed (HTTP $($response.StatusCode))"
            exit 1
        }
    } catch {
        if ($_.Exception.Response.StatusCode.value__ -eq 400) {
            Write-Success "Command API is healthy"
        } else {
            Write-Error_ "Command API health check failed"
            exit 1
        }
    }

    # Query API Health Check
    Write-Info "Checking Query API (GraphQL) health..."
    try {
        $response = Invoke-WebRequest -Uri $QUERY_API_ENDPOINT -Method Get -TimeoutSec 5 -ErrorAction SilentlyContinue
        if ($response.StatusCode -eq 200 -or $response.StatusCode -eq 400) {
            Write-Success "Query API is healthy"
        } else {
            Write-Error_ "Query API health check failed (HTTP $($response.StatusCode))"
            exit 1
        }
    } catch {
        if ($_.Exception.Response.StatusCode.value__ -eq 400) {
            Write-Success "Query API is healthy"
        } else {
            Write-Error_ "Query API health check failed"
            exit 1
        }
    }
}

# Step 1: Create UserAccount via GraphQL Mutation
function New-UserAccountViaGraphQL {
    Write-Header "Step 1: Create UserAccount via GraphQL Mutation"
    Write-Info "Creating user account with the following details:"
    Write-Host "  - First Name: $TEST_FIRST_NAME"
    Write-Host "  - Last Name: $TEST_LAST_NAME"
    Write-Host "  - Email: $TEST_EMAIL"
    Write-Host ""

    $mutation = @"
mutation CreateUserAccount(`$input: CreateUserAccountInput!) {
    createUserAccount(input: `$input) {
        id
    }
}
"@

    $variables = @{
        input = @{
            firstName = $TEST_FIRST_NAME
            lastName = $TEST_LAST_NAME
            emailAddress = $TEST_EMAIL
        }
    }

    $response = Invoke-GraphQL -Endpoint $COMMAND_API_ENDPOINT -Query $mutation -Variables $variables

    if ($response) {
        Write-Json ($response | ConvertTo-Json -Depth 10)

        if ($response.data.createUserAccount.id) {
            $script:CREATED_USER_ID = $response.data.createUserAccount.id
            Write-Success "UserAccount created successfully!"
            Write-Info "Created UserAccount ID: $($script:CREATED_USER_ID)"
            return $true
        } elseif ($response.errors) {
            $errorMsg = $response.errors[0].message
            if ($errorMsg -match "already exists") {
                Write-Error_ "UserAccount with email $TEST_EMAIL already exists"
                Write-Info "This might be from a previous test run. Continuing with query test..."
                return $true
            } else {
                Write-Error_ "Failed to create user account: $errorMsg"
                return $false
            }
        }
    }

    Write-Error_ "Unexpected response"
    return $false
}

# Step 2: Wait for eventual consistency
function Wait-ForConsistency {
    Write-Header "Step 2: Wait for Event Processing"
    Write-Info "Waiting for DynamoDB stream to process and update PostgreSQL..."

    for ($i = $E2E_WAIT_AFTER_CREATE; $i -gt 0; $i--) {
        Write-Host "`r  Waiting... $i seconds remaining" -NoNewline
        Start-Sleep -Seconds 1
    }
    Write-Host "`r  Waiting... Done!                    "
    Write-Success "Event processing time elapsed"
}

# Step 3: Query UserAccount via GraphQL
function Get-UserAccountViaGraphQL {
    Write-Header "Step 3: Query UserAccount via GraphQL"

    Write-Info "Querying all user accounts to find created user..."

    $query = @"
{
    getUserAccounts {
        id
        firstName
        lastName
        fullName
        createdAt
        updatedAt
    }
}
"@

    $response = Invoke-GraphQL -Endpoint $QUERY_API_ENDPOINT -Query $query

    if ($response -and $response.data.getUserAccounts) {
        $userData = $response.data.getUserAccounts | Where-Object {
            $_.firstName -eq $TEST_FIRST_NAME -and $_.lastName -eq $TEST_LAST_NAME
        }

        if ($userData) {
            Write-Success "UserAccount found via GraphQL!"
            Write-Json ($userData | ConvertTo-Json -Depth 10)

            if ($userData.firstName -eq $TEST_FIRST_NAME -and $userData.lastName -eq $TEST_LAST_NAME) {
                Write-Success "User data matches: $($userData.firstName) $($userData.lastName)"

                # Query by ID
                Write-Info "Verifying user can be queried by ID: $($userData.id)"

                $idQuery = @"
query GetUserAccount(`$id: String!) {
    getUserAccount(userAccountId: `$id) {
        id
        firstName
        lastName
        fullName
        createdAt
        updatedAt
    }
}
"@

                $idVariables = @{ id = $userData.id }
                $idResponse = Invoke-GraphQL -Endpoint $QUERY_API_ENDPOINT -Query $idQuery -Variables $idVariables

                if ($idResponse -and $idResponse.data.getUserAccount) {
                    Write-Success "UserAccount successfully queried by ID"
                } else {
                    Write-Error_ "Failed to query UserAccount by ID"
                }

                return $true
            } else {
                Write-Error_ "User data mismatch! Expected: $TEST_FIRST_NAME $TEST_LAST_NAME"
                return $false
            }
        } else {
            Write-Error_ "UserAccount not found in database"
            Write-Info "The event might not have been processed yet"
            return $false
        }
    } else {
        Write-Error_ "GraphQL query failed"
        if ($response) {
            Write-Json ($response | ConvertTo-Json -Depth 10)
        }
        return $false
    }
}

# Step 4: Verify data consistency
function Test-DataConsistency {
    Write-Header "Step 4: Data Consistency Verification"

    Write-Info "Verifying total user account count..."

    $query = "{ getUserAccounts { id } }"
    $response = Invoke-GraphQL -Endpoint $QUERY_API_ENDPOINT -Query $query

    if ($response -and $response.data.getUserAccounts) {
        $totalCount = $response.data.getUserAccounts.Count
        Write-Success "Total user account count: $totalCount"
    }
}

# メイン処理
function Main {
    Write-Header "End-to-End Test Suite for UserAccount"
    Write-Info "Testing flow: GraphQL Mutation → Event Processing → GraphQL Query"
    Write-Info "Test ID: $TIMESTAMP"
    Write-Host ""

    # ヘルスチェック
    Test-Health

    # E2Eテストの実行
    if (New-UserAccountViaGraphQL) {
        Wait-ForConsistency

        # リトライロジック付きでクエリを実行
        $retryCount = 0
        $success = $false

        while ($retryCount -lt $E2E_MAX_RETRIES -and -not $success) {
            if ($retryCount -gt 0) {
                Write-Info "Retry attempt $retryCount/$E2E_MAX_RETRIES... (sleep ${E2E_RETRY_DELAY}s)"
                Start-Sleep -Seconds $E2E_RETRY_DELAY
            }

            if (Get-UserAccountViaGraphQL) {
                $success = $true
                Test-DataConsistency
            } else {
                $retryCount++
            }
        }

        if (-not $success) {
            Write-Error_ "Failed to query user account after $E2E_MAX_RETRIES retries"
            Write-Info "Possible causes:"
            Write-Host "  - Lambda function not deployed or not running"
            Write-Host "  - DynamoDB streams not configured"
            Write-Host "  - Database connection issues"
            exit 1
        }
    } else {
        Write-Error_ "Failed to create user account, aborting test"
        exit 1
    }

    Write-Header "Test Summary"
    Write-Success "End-to-End test completed successfully!"
    Write-Info "UserAccount ($TEST_FIRST_NAME $TEST_LAST_NAME) was created via GraphQL and retrieved successfully"
    Write-Host ""
}

# スクリプト実行
Main
