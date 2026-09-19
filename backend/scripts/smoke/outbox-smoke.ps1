<#
.SYNOPSIS
Runs production smoke tests for FEELM transactional outbox events.

.DESCRIPTION
Runs all currently testable event flows in one execution:

1. RATING
2. DISMISS
3. OTT_CLICK

RATING also checks recommendation regeneration.
DISMISS and OTT_CLICK only verify that the public API successfully creates
the domain action. Kafka publication must be confirmed from the outbox table.

Currently skipped:
- VIEW
- RECOMMENDATION_CLICK
- PARTY_REFRESH

Expected Kafka topics:
- RATING    -> feelm.rating.v1
- DISMISS   -> feelm.dismiss.v1
- OTT_CLICK -> feelm.ott-click.v1

This script changes real test-account data.

.EXAMPLE
powershell .\scripts\smoke\outbox-smoke.ps1

.EXAMPLE
powershell .\scripts\smoke\outbox-smoke.ps1 `
    -ConfirmMutation `
    -EvidencePath .\outbox-evidence.json
#>

[CmdletBinding()]
param(
    [string]$BaseUrl = "https://j15e106.p.ssafy.io",

    [ValidateRange(10, 600)]
    [int]$TimeoutSeconds = 120,

    [ValidateRange(1, 30)]
    [int]$PollIntervalSeconds = 3,

    [ValidateRange(0.5, 5.0)]
    [double]$Score = 4.0,

    # Verified production data:
    # movieId 9 = Mona Lisa
    # ottId 3 = WATCHA
    [long]$OttMovieId = 9,

    [long]$OttId = 3,

    [switch]$ConfirmMutation,

    [string]$EvidencePath
)


# ============================================================
# Global state
# ============================================================

$ErrorActionPreference = "Stop"

$script:PassCount = 0
$script:FailCount = 0
$script:WarnCount = 0

$script:Evidence = [ordered]@{
    startedAt = [DateTimeOffset]::UtcNow.ToString("o")
    baseUrl   = $BaseUrl
    events    = [ordered]@{}
}


# ============================================================
# Output helpers
# ============================================================

function Write-Pass([string]$Message) {
    $script:PassCount++
    Write-Host "[PASS] $Message" -ForegroundColor Green
}

function Write-Fail([string]$Message) {
    $script:FailCount++
    Write-Host "[FAIL] $Message" -ForegroundColor Red
}

function Write-Warn([string]$Message) {
    $script:WarnCount++
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
}

function Write-Info([string]$Message) {
    Write-Host "[INFO] $Message" -ForegroundColor Cyan
}


# ============================================================
# HTTP helper
# ============================================================

function Invoke-Api {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Method,

        [Parameter(Mandatory = $true)]
        [string]$Uri,

        [hashtable]$Headers = @{},

        $Body = $null
    )

    $parameters = @{
        Method          = $Method
        Uri             = $Uri
        Headers         = $Headers
        UseBasicParsing = $true
        ErrorAction     = "Stop"
    }

    if ($null -ne $Body) {
        $parameters.ContentType = "application/json"

        $parameters.Body = if ($Body -is [string]) {
            $Body
        }
        else {
            $Body | ConvertTo-Json -Depth 20 -Compress
        }
    }

    try {
        $response = Invoke-WebRequest @parameters

        $parsed = $null

        if (![string]::IsNullOrWhiteSpace($response.Content)) {
            try {
                $parsed = $response.Content | ConvertFrom-Json
            }
            catch {
                $parsed = $response.Content
            }
        }

        return [PSCustomObject]@{
            Success = $true
            Status  = [int]$response.StatusCode
            Body    = $parsed
            Error   = $null
        }
    }
    catch {
        $status = 0
        $errorBody = $null

        try {
            $status = [int]$_.Exception.Response.StatusCode
            $errorBody = $_.ErrorDetails.Message
        }
        catch {
        }

        return [PSCustomObject]@{
            Success = $false
            Status  = $status
            Body    = $errorBody
            Error   = $_.Exception.Message
        }
    }
}


# ============================================================
# Authentication helper
# ============================================================

function Find-AccessToken($Object) {
    if ($null -eq $Object) {
        return $null
    }

    if ($Object.PSObject.Properties.Name -contains "accessToken") {
        return $Object.accessToken
    }

    if ($Object.PSObject.Properties.Name -contains "token") {
        return $Object.token
    }

    foreach ($property in $Object.PSObject.Properties) {
        if ($property.Value -is [PSCustomObject]) {
            $token = Find-AccessToken $property.Value

            if ($token) {
                return $token
            }
        }
    }

    return $null
}


# ============================================================
# Recommendation helpers
# ============================================================

function Get-Recommendations {
    param(
        [hashtable]$Headers
    )

    return Invoke-Api `
        -Method "GET" `
        -Uri "$BaseUrl/api/v1/recommendations" `
        -Headers $Headers
}


function Get-RecommendationItems {
    param(
        $ResponseBody
    )

    if ($null -eq $ResponseBody -or
        !($ResponseBody.PSObject.Properties.Name -contains "items")) {

        return @()
    }

    return @($ResponseBody.items)
}


function Get-MovieId {
    param(
        $Item
    )

    if ($null -ne $Item.movie) {
        return [long]$Item.movie.movieId
    }

    return [long]$Item.movieId
}


function Get-CurrentRecommendationTarget {
    param(
        [hashtable]$Headers
    )

    $response = Get-Recommendations -Headers $Headers

    if (!$response.Success) {
        return $null
    }

    $items = @(Get-RecommendationItems -ResponseBody $response.Body)

    if ($items.Count -eq 0) {
        return $null
    }

    $item = $items[0]

    return [PSCustomObject]@{
        MovieId              = Get-MovieId -Item $item
        RecommendationItemId = [long]$item.recommendationItemId
        RecommendationSetId  = [long]$response.Body.recommendationSetId
        Body                 = $response.Body
    }
}


function Wait-RecommendationGeneration {
    param(
        [hashtable]$Headers,

        [long]$PreviousSetId,

        [long]$ExcludedMovieId = 0,

        [string]$Label
    )

    $deadline =
        [DateTimeOffset]::UtcNow.AddSeconds($TimeoutSeconds)

    $attempt = 0

    while ([DateTimeOffset]::UtcNow -lt $deadline) {
        $attempt++

        $response =
            Get-Recommendations -Headers $Headers

        if (!$response.Success) {
            Write-Warn "$Label poll #$attempt failed with HTTP $($response.Status)."

            Start-Sleep -Seconds $PollIntervalSeconds
            continue
        }

        $currentSetId =
            [long]$response.Body.recommendationSetId

        $movieIds = @(
            Get-RecommendationItems -ResponseBody $response.Body |
                ForEach-Object {
                    Get-MovieId -Item $_
                }
        )

        if ($currentSetId -ne $PreviousSetId) {
            Write-Pass "$Label observed a new recommendation set: $PreviousSetId -> $currentSetId."

            if ($ExcludedMovieId -gt 0) {
                if ($movieIds -contains $ExcludedMovieId) {
                    Write-Warn "$Label new recommendation set still contains movie $ExcludedMovieId."
                }
                else {
                    Write-Pass "$Label removed movie $ExcludedMovieId from recommendations."
                }
            }

            return $response.Body
        }

        Write-Info "$Label waiting for recommendation update (set $currentSetId, poll #$attempt)."

        Start-Sleep -Seconds $PollIntervalSeconds
    }

    Write-Warn "$Label did not observe a new recommendation set within $TimeoutSeconds seconds."

    Write-Warn "This does not prove Kafka publication failure. Check the outbox status."

    return $null
}


# ============================================================
# Evidence helper
# ============================================================

function Save-Evidence {
    $script:Evidence.finishedAt =
        [DateTimeOffset]::UtcNow.ToString("o")

    $script:Evidence.pass =
        $script:PassCount

    $script:Evidence.fail =
        $script:FailCount

    $script:Evidence.warn =
        $script:WarnCount

    if (![string]::IsNullOrWhiteSpace($EvidencePath)) {
        $resolved =
            $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath(
                $EvidencePath
            )

        $parent = Split-Path -Parent $resolved

        if (![string]::IsNullOrWhiteSpace($parent)) {
            New-Item `
                -ItemType Directory `
                -Force `
                $parent | Out-Null
        }

        $script:Evidence |
            ConvertTo-Json -Depth 20 |
            Set-Content `
                -LiteralPath $resolved `
                -Encoding utf8

        Write-Info "Evidence saved to $resolved"
    }
}


# ============================================================
# Start
# ============================================================

Write-Host ""
Write-Host "===== FEELM OUTBOX PRODUCTION SMOKE TEST ====="
Write-Host ""

Write-Host "Events to test:"
Write-Host "  1. RATING"
Write-Host "  2. DISMISS"
Write-Host "  3. OTT_CLICK"
Write-Host ""

Write-Host "Events skipped:"
Write-Host "  - VIEW"
Write-Host "  - RECOMMENDATION_CLICK"
Write-Host "  - PARTY_REFRESH"
Write-Host ""

Write-Warn "This test changes real data in the supplied test account."
Write-Warn "DISMISS has no public undo API."

if (!$ConfirmMutation) {
    $confirmation =
        Read-Host "Type YES to continue"

    if ($confirmation -cne "YES") {
        Write-Warn "Cancelled before changing server data."
        exit 2
    }
}


# ============================================================
# 1. Login
# ============================================================

$email = $env:FEELM_EMAIL
$password = $env:FEELM_PASSWORD

if ([string]::IsNullOrWhiteSpace($email)) {
    $email =
        Read-Host "Test account email"
}

if ([string]::IsNullOrWhiteSpace($password)) {
    $securePassword =
        Read-Host "Password" -AsSecureString

    $pointer =
        [Runtime.InteropServices.Marshal]::SecureStringToBSTR(
            $securePassword
        )

    try {
        $password =
            [Runtime.InteropServices.Marshal]::PtrToStringBSTR(
                $pointer
            )
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR(
            $pointer
        )
    }
}

Write-Host ""
Write-Host "===== 1. LOGIN ====="

$login = Invoke-Api `
    -Method "POST" `
    -Uri "$BaseUrl/api/v1/auth/login" `
    -Headers @{
        accept = "application/json"
    } `
    -Body @{
        email    = $email
        password = $password
    }

$password = $null

if (!$login.Success) {
    Write-Fail "Login failed with HTTP $($login.Status): $($login.Error)"
    Save-Evidence
    exit 1
}

$token =
    Find-AccessToken -Object $login.Body

if ([string]::IsNullOrWhiteSpace($token)) {
    Write-Fail "Access token was not found in the login response."
    Save-Evidence
    exit 1
}

Write-Pass "Login succeeded."

$headers = @{
    Authorization = "Bearer $token"
    accept        = "application/json"
}


# ============================================================
# 2. RATING
# ============================================================

Write-Host ""
Write-Host "===== 2. RATING ====="

$ratingTarget =
    Get-CurrentRecommendationTarget `
        -Headers $headers

if ($null -eq $ratingTarget) {
    Write-Fail "No recommendation item is available for RATING."
}
else {
    $ratingMovieId =
        $ratingTarget.MovieId

    $ratingInitialSetId =
        $ratingTarget.RecommendationSetId

    Write-Info "RATING movie: $ratingMovieId"
    Write-Info "Current recommendation set: $ratingInitialSetId"

    $ratingStartedAt =
        [DateTimeOffset]::UtcNow.ToString("o")

    $rating = Invoke-Api `
        -Method "POST" `
        -Uri "$BaseUrl/api/v1/movies/$ratingMovieId/ratings" `
        -Headers $headers `
        -Body @{
            score = $Score
        }

    $ratingEvidence = [ordered]@{
        startedAt                 = $ratingStartedAt
        movieId                   = $ratingMovieId
        expectedTopic             = "feelm.rating.v1"
        initialRecommendationSet  = $ratingInitialSetId
        apiStatus                 = $rating.Status
    }

    if ($rating.Success -and
        $rating.Status -eq 201) {

        $ratingId =
            [long]$rating.Body.ratingId

        $ratingEvidence.ratingId =
            $ratingId

        Write-Pass "RATING API succeeded. ratingId=$ratingId."

        $ratingRefresh =
            Wait-RecommendationGeneration `
                -Headers $headers `
                -PreviousSetId $ratingInitialSetId `
                -ExcludedMovieId $ratingMovieId `
                -Label "RATING"

        if ($null -ne $ratingRefresh) {
            $ratingEvidence.refreshedRecommendationSet =
                [long]$ratingRefresh.recommendationSetId
        }
    }
    else {
        Write-Fail "RATING API failed with HTTP $($rating.Status): $($rating.Body)"
    }

    $ratingEvidence.finishedAt =
        [DateTimeOffset]::UtcNow.ToString("o")

    $script:Evidence.events.RATING =
        $ratingEvidence
}


# ============================================================
# 3. DISMISS
# ============================================================

Write-Host ""
Write-Host "===== 3. DISMISS ====="

$dismissTarget =
    Get-CurrentRecommendationTarget `
        -Headers $headers

if ($null -eq $dismissTarget) {
    Write-Fail "No recommendation item is available for DISMISS."
}
else {
    $dismissMovieId =
        $dismissTarget.MovieId

    $dismissItemId =
        $dismissTarget.RecommendationItemId

    $dismissSetId =
        $dismissTarget.RecommendationSetId

    Write-Info "DISMISS movie: $dismissMovieId"
    Write-Info "Recommendation item: $dismissItemId"
    Write-Info "Current recommendation set: $dismissSetId"

    $dismissStartedAt =
        [DateTimeOffset]::UtcNow.ToString("o")

    $dismiss = Invoke-Api `
        -Method "POST" `
        -Uri "$BaseUrl/api/v1/movies/$dismissMovieId/dismiss" `
        -Headers $headers `
        -Body @{
            recommendationItemId = $dismissItemId
        }

    $dismissEvidence = [ordered]@{
        startedAt            = $dismissStartedAt
        movieId              = $dismissMovieId
        recommendationItemId = $dismissItemId
        expectedTopic        = "feelm.dismiss.v1"
        apiStatus            = $dismiss.Status
    }

    if ($dismiss.Success -and
        $dismiss.Status -eq 200) {

        Write-Pass "DISMISS API succeeded."

        Write-Info "Kafka publication is not inferred from recommendation behavior."
        Write-Info "Check the DISMISS event in the outbox table."

    }
    else {
        Write-Fail "DISMISS API failed with HTTP $($dismiss.Status): $($dismiss.Body)"
    }

    $dismissEvidence.finishedAt =
        [DateTimeOffset]::UtcNow.ToString("o")

    $script:Evidence.events.DISMISS =
        $dismissEvidence
}


# ============================================================
# 4. OTT_CLICK
# ============================================================

Write-Host ""
Write-Host "===== 4. OTT_CLICK ====="

Write-Info "OTT movie: $OttMovieId"
Write-Info "OTT ID: $OttId"

$ottStartedAt =
    [DateTimeOffset]::UtcNow.ToString("o")

$ottClick = Invoke-Api `
    -Method "POST" `
    -Uri "$BaseUrl/api/v1/movies/$OttMovieId/ott-clicks" `
    -Headers $headers `
    -Body @{
        ottId = $OttId
    }

$ottEvidence = [ordered]@{
    startedAt     = $ottStartedAt
    movieId       = $OttMovieId
    ottId         = $OttId
    expectedTopic = "feelm.ott-click.v1"
    apiStatus     = $ottClick.Status
}

if ($ottClick.Success -and
    (
        $ottClick.Status -eq 200 -or
        $ottClick.Status -eq 201 -or
        $ottClick.Status -eq 204
    )) {

    Write-Pass "OTT_CLICK API succeeded."

    Write-Info "Check the OTT_CLICK event in the outbox table."

}
else {
    Write-Fail "OTT_CLICK API failed with HTTP $($ottClick.Status): $($ottClick.Body)"
}

$ottEvidence.finishedAt =
    [DateTimeOffset]::UtcNow.ToString("o")

$script:Evidence.events.OTT_CLICK =
    $ottEvidence


# ============================================================
# 5. Skipped events
# ============================================================

Write-Host ""
Write-Host "===== 5. SKIPPED EVENTS ====="

Write-Warn "VIEW skipped: no active event publishing module is currently connected."

Write-Warn "RECOMMENDATION_CLICK skipped: no active event publishing module is currently connected."

Write-Warn "PARTY_REFRESH skipped: current party flow does not use the common event publishing module."

$script:Evidence.events.VIEW = [ordered]@{
    status = "SKIPPED"
    reason = "No active event publishing module"
}

$script:Evidence.events.RECOMMENDATION_CLICK = [ordered]@{
    status = "SKIPPED"
    reason = "No active event publishing module"
}

$script:Evidence.events.PARTY_REFRESH = [ordered]@{
    status = "SKIPPED"
    reason = "Current flow does not use common event publishing module"
}


# ============================================================
# 6. Outbox verification
# ============================================================

Write-Host ""
Write-Host "===== 6. OUTBOX VERIFICATION ====="
Write-Host ""

Write-Host "Verify the events created during this run in the outbox table."
Write-Host ""

Write-Host "RATING"
Write-Host "  Expected topic : feelm.rating.v1"
Write-Host "  Success status : PUBLISHED"
Write-Host ""

Write-Host "DISMISS"
Write-Host "  Expected topic : feelm.dismiss.v1"
Write-Host "  Success status : PUBLISHED"
Write-Host ""

Write-Host "OTT_CLICK"
Write-Host "  Expected topic : feelm.ott-click.v1"
Write-Host "  Success status : PUBLISHED"
Write-Host ""

Write-Warn "API success does not mean Kafka publication success."

Write-Warn "PENDING with Send failed means Kafka publication failed."

Write-Warn "Use the outbox status as the final Kafka publication result."


# ============================================================
# Result
# ============================================================

Write-Host ""
Write-Host "===== RESULT ====="

Write-Host "PASS: $script:PassCount"
Write-Host "FAIL: $script:FailCount"
Write-Host "WARN: $script:WarnCount"

Write-Host ""
Write-Host "Tested Kafka topics:"
Write-Host "  RATING    -> feelm.rating.v1"
Write-Host "  DISMISS   -> feelm.dismiss.v1"
Write-Host "  OTT_CLICK -> feelm.ott-click.v1"

Write-Host ""
Write-Host "Skipped:"
Write-Host "  VIEW"
Write-Host "  RECOMMENDATION_CLICK"
Write-Host "  PARTY_REFRESH"

Write-Host ""
Write-Info "Use event timestamps and movie IDs above to find the new outbox rows."

Save-Evidence

if ($script:FailCount -gt 0) {
    exit 1
}

exit 0