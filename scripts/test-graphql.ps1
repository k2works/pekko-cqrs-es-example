# GraphQL エンドポイントをテストするスクリプト

$ErrorActionPreference = "Stop"

# 設定
$GRAPHQL_HOST = if ($env:GRAPHQL_HOST) { $env:GRAPHQL_HOST } else { "localhost" }
$GRAPHQL_PORT = if ($env:GRAPHQL_PORT) { $env:GRAPHQL_PORT } else { "50502" }
$GRAPHQL_ENDPOINT = "http://${GRAPHQL_HOST}:${GRAPHQL_PORT}/graphql"

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
    param($Object)
    try {
        if ($Object -is [string]) {
            $Object | ConvertFrom-Json | ConvertTo-Json -Depth 10
        } else {
            $Object | ConvertTo-Json -Depth 10
        }
    } catch {
        Write-Host $Object
    }
}

# GraphQL クエリを実行する関数
function Invoke-GraphQL {
    param(
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
        $response = Invoke-RestMethod -Uri $GRAPHQL_ENDPOINT -Method Post `
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
    Write-Info "Checking Query API health..."

    try {
        $response = Invoke-WebRequest -Uri "http://${GRAPHQL_HOST}:${GRAPHQL_PORT}/health" -Method Get -TimeoutSec 5
        if ($response.StatusCode -eq 200) {
            Write-Success "Query API is healthy: $($response.Content)"
        } else {
            Write-Error_ "Query API health check failed (HTTP $($response.StatusCode))"
            exit 1
        }
    } catch {
        Write-Error_ "Query API health check failed"
        exit 1
    }
}

# GraphiQL の確認
function Test-GraphiQL {
    Write-Header "GraphiQL Interface Check"
    Write-Info "Checking if GraphiQL is available..."

    try {
        $response = Invoke-WebRequest -Uri $GRAPHQL_ENDPOINT -Method Get -TimeoutSec 5
        if ($response.StatusCode -eq 200) {
            Write-Success "GraphiQL is available at $GRAPHQL_ENDPOINT"
        } elseif ($response.StatusCode -eq 404) {
            Write-Info "GraphiQL is disabled (production mode)"
        } else {
            Write-Error_ "Unexpected response (HTTP $($response.StatusCode))"
        }
    } catch {
        if ($_.Exception.Response.StatusCode.value__ -eq 404) {
            Write-Info "GraphiQL is disabled (production mode)"
        } else {
            Write-Error_ "Unexpected error checking GraphiQL"
        }
    }
}

# イントロスペクションクエリ
function Test-Introspection {
    Write-Header "Schema Introspection"
    Write-Info "Fetching GraphQL schema..."

    $query = "{ __schema { types { name kind description } } }"

    $response = Invoke-GraphQL -Query $query

    if ($response -and $response.data.__schema.types) {
        Write-Success "Schema introspection successful"
        $staffTypes = $response.data.__schema.types | Where-Object { $_.name -like "Staff*" }
        if ($staffTypes) {
            Write-Json $staffTypes
        }
    } else {
        Write-Error_ "Schema introspection failed"
        if ($response) {
            Write-Json $response
        }
    }
}

# 利用可能なクエリの一覧取得
function Get-AvailableQueries {
    Write-Header "Available Queries"
    Write-Info "Listing all available queries..."

    $query = "{ __schema { queryType { fields { name description args { name type { name kind } } } } } }"

    $response = Invoke-GraphQL -Query $query

    if ($response -and $response.data.__schema.queryType.fields) {
        Write-Success "Query list retrieved"
        foreach ($field in $response.data.__schema.queryType.fields) {
            Write-Host "  - $($field.name): $($field.description)"
        }
    } else {
        Write-Error_ "Failed to retrieve query list"
        if ($response) {
            Write-Json $response
        }
    }
}

# 全スタッフ取得クエリ
function Test-AllStaff {
    Write-Header "Test: Get All Staff"
    Write-Info "Executing allStaff query..."

    $query = @"
{
    allStaff {
        id
        staffNo
        globalFamilyName
        globalMiddleName
        globalGivenName
        localFamilyName
        localMiddleName
        localGivenName
        nameLocale
        createdAt
        updatedAt
    }
}
"@

    $response = Invoke-GraphQL -Query $query

    if ($response -and $response.data.allStaff) {
        $count = $response.data.allStaff.Count
        Write-Success "Query executed successfully (Found $count staff members)"
        if ($count -gt 0) {
            $response.data.allStaff | Select-Object -First 2 | ForEach-Object { Write-Json $_ }
        }
    } else {
        if ($response -and $response.errors) {
            Write-Error_ "Query failed with errors:"
            Write-Json $response.errors
        } else {
            Write-Info "No staff data found (empty result)"
            if ($response) {
                Write-Json $response
            }
        }
    }
}

# 特定スタッフ取得クエリ
function Test-StaffByNo {
    Write-Header "Test: Get Staff by Staff Number"
    Write-Info "Executing staff query with staffNo parameter..."

    $query = "query GetStaff(`$staffNo: String!) { staffByNo(staffNo: `$staffNo) { id staffNo globalFamilyName globalGivenName localFamilyName localGivenName nameLocale } }"
    $variables = @{ staffNo = "STF001" }

    $response = Invoke-GraphQL -Query $query -Variables $variables

    if ($response -and $response.data.staffByNo) {
        if ($response.data.staffByNo -ne $null) {
            Write-Success "Staff found:"
            Write-Json $response
        } else {
            Write-Info "No staff found with staffNo: STF001"
        }
    } else {
        Write-Error_ "Query failed"
        if ($response) {
            Write-Json $response
        }
    }
}

# ロケールによるスタッフ検索
function Test-StaffByLocale {
    Write-Header "Test: Get Staff by Locale"
    Write-Info "Executing staffByLocale query..."

    $query = "query GetStaffByLocale(`$locale: String) { staffByLocale(nameLocale: `$locale) { staffNo nameLocale globalFamilyName globalGivenName } }"
    $variables = @{ locale = "ja_JP" }

    $response = Invoke-GraphQL -Query $query -Variables $variables

    if ($response -and $response.data.staffByLocale) {
        $count = $response.data.staffByLocale.Count
        Write-Success "Query executed successfully (Found $count staff members with locale ja_JP)"
    } else {
        Write-Error_ "Query failed"
        if ($response) {
            Write-Json $response
        }
    }
}

# 名前検索テスト
function Test-SearchStaff {
    Write-Header "Test: Search Staff by Name"
    Write-Info "Executing searchStaff query..."

    $query = "query SearchStaff(`$globalFamily: String, `$localFamily: String) { searchStaff(globalFamilyName: `$globalFamily, localFamilyName: `$localFamily) { staffNo globalFamilyName globalGivenName localFamilyName localGivenName } }"
    $variables = @{ globalFamily = "Yamada" }

    $response = Invoke-GraphQL -Query $query -Variables $variables

    if ($response -and $response.data.searchStaff) {
        $count = $response.data.searchStaff.Count
        Write-Success "Search executed successfully (Found $count matches)"
        if ($count -gt 0) {
            $response.data.searchStaff | Select-Object -First 2 | ForEach-Object { Write-Json $_ }
        }
    } else {
        Write-Error_ "Search failed"
        if ($response) {
            Write-Json $response
        }
    }
}

# バッチクエリテスト
function Test-BatchQuery {
    Write-Header "Test: Batch Query"
    Write-Info "Executing multiple queries in single request..."

    $query = @"
{
    allStaffCount: allStaff { staffNo }
    japaneseStaff: staffByLocale(nameLocale: "ja_JP") { staffNo nameLocale }
    searchYamada: searchStaff(globalFamilyName: "Yamada") { staffNo globalFamilyName }
}
"@

    $response = Invoke-GraphQL -Query $query

    if ($response -and $response.data) {
        Write-Success "Batch query executed successfully"
        Write-Host "Results summary:"
        Write-Host "  - All staff count: $($response.data.allStaffCount.Count)"
        Write-Host "  - Japanese staff: $($response.data.japaneseStaff.Count)"
        Write-Host "  - Search results: $($response.data.searchYamada.Count)"
    } else {
        Write-Error_ "Batch query failed"
        if ($response) {
            Write-Json $response
        }
    }
}

# フラグメント使用のテスト
function Test-WithFragments {
    Write-Header "Test: Query with Fragments"
    Write-Info "Testing GraphQL fragments..."

    $query = @"
fragment StaffBasicInfo on Staff {
    staffNo
    nameLocale
}

fragment StaffNameInfo on Staff {
    globalFamilyName
    globalGivenName
    localFamilyName
    localGivenName
}

{
    allStaff {
        ...StaffBasicInfo
        ...StaffNameInfo
        createdAt
    }
}
"@

    $response = Invoke-GraphQL -Query $query

    if ($response -and $response.data.allStaff) {
        Write-Success "Fragment query executed successfully"
    } else {
        Write-Error_ "Fragment query failed"
        if ($response) {
            Write-Json $response
        }
    }
}

# エラーハンドリングテスト
function Test-ErrorHandling {
    Write-Header "Test: Error Handling"
    Write-Info "Testing error responses..."

    # 1. 不正なクエリ
    Write-Info "Testing malformed query..."
    $badQuery = "{ invalid query }"
    $response = Invoke-GraphQL -Query $badQuery

    if ($response -and $response.errors) {
        Write-Success "Error handled correctly for malformed query"
    } else {
        Write-Error_ "Expected error not returned"
    }

    # 2. 存在しないフィールド
    Write-Info "Testing non-existent field..."
    $invalidFieldQuery = "{ allStaff { staffNo nonExistentField } }"
    $response = Invoke-GraphQL -Query $invalidFieldQuery

    if ($response -and $response.errors) {
        Write-Success "Error handled correctly for non-existent field"
    } else {
        Write-Error_ "Expected error not returned"
    }
}

# パフォーマンステスト
function Test-Performance {
    Write-Header "Performance Test"
    Write-Info "Measuring query response times..."

    $query = "{ allStaff { staffNo } }"
    $iterations = 5
    $times = @()

    for ($i = 1; $i -le $iterations; $i++) {
        $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
        Invoke-GraphQL -Query $query | Out-Null
        $stopwatch.Stop()
        $elapsed = $stopwatch.ElapsedMilliseconds
        $times += $elapsed
        Write-Host "  Run $i : ${elapsed}ms"
    }

    $avg = ($times | Measure-Object -Average).Average
    Write-Info "Average response time: ${avg}ms"

    if ($avg -lt 100) {
        Write-Success "Excellent performance (< 100ms)"
    } elseif ($avg -lt 500) {
        Write-Success "Good performance (< 500ms)"
    } else {
        Write-Error_ "Poor performance (>= 500ms)"
    }
}

# メイン処理
function Main {
    Write-Header "GraphQL API Test Suite"
    Write-Info "Target: $GRAPHQL_ENDPOINT"

    # 基本的な接続確認
    Test-Health
    Test-GraphiQL

    # スキーマテスト
    Test-Introspection
    Get-AvailableQueries

    # クエリテスト
    Test-AllStaff
    Test-StaffByNo
    Test-StaffByLocale
    Test-SearchStaff

    # 高度なテスト
    Test-BatchQuery
    Test-WithFragments
    Test-ErrorHandling

    # パフォーマンステスト
    Test-Performance

    Write-Header "Test Summary"
    Write-Success "All GraphQL API tests completed"
    Write-Info "GraphiQL interface available at: $GRAPHQL_ENDPOINT"
}

# スクリプト実行
Main
