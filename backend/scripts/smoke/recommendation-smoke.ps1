$ErrorActionPreference = "Stop"

$BASE_URL = "https://j15e106.p.ssafy.io"

$PASS_COUNT = 0
$FAIL_COUNT = 0
$WARN_COUNT = 0

# ============================================================
# Output helpers
# ============================================================

function Pass($message) {
    $script:PASS_COUNT++
    Write-Host "[PASS] $message"
}

function Fail($message) {
    $script:FAIL_COUNT++
    Write-Host "[FAIL] $message"
}

function Warn($message) {
    $script:WARN_COUNT++
    Write-Host "[WARN] $message"
}

function Info($message) {
    Write-Host "[INFO] $message"
}

# ============================================================
# Input
# ============================================================

$email = $env:FEELM_EMAIL
$password = $env:FEELM_PASSWORD

if ([string]::IsNullOrWhiteSpace($email)) {
    $email = Read-Host "Test account email"
}

if ([string]::IsNullOrWhiteSpace($password)) {

    $securePassword = Read-Host "Password" -AsSecureString
    $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)

    try {
        $password =
            [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr)
    }
    finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr)
    }
}

# ============================================================
# HTTP helper
# ============================================================

function Invoke-Api {

    param(
        [string]$Method,
        [string]$Uri,
        [hashtable]$Headers = @{},
        $Body = $null
    )

    $params = @{
        Method          = $Method
        Uri             = $Uri
        Headers         = $Headers
        UseBasicParsing = $true
        ErrorAction     = "Stop"
    }

    if ($null -ne $Body) {

        $params["ContentType"] = "application/json"

        if ($Body -is [string]) {
            $params["Body"] = $Body
        }
        else {
            $params["Body"] = $Body | ConvertTo-Json -Depth 20
        }
    }

    try {

        $response = Invoke-WebRequest @params

        $parsedBody = $null

        if (![string]::IsNullOrWhiteSpace($response.Content)) {

            try {
                $parsedBody = $response.Content | ConvertFrom-Json
            }
            catch {
                $parsedBody = $response.Content
            }
        }

        return [PSCustomObject]@{
            Success = $true
            Status  = [int]$response.StatusCode
            Body    = $parsedBody
            Error   = $null
        }
    }
    catch {

        $status = 0

        try {
            $status = [int]$_.Exception.Response.StatusCode
        }
        catch {
        }

        return [PSCustomObject]@{
            Success = $false
            Status  = $status
            Body    = $null
            Error   = $_.Exception.Message
        }
    }
}

# ============================================================
# Object helpers
# ============================================================

function Find-AccessToken($obj) {

    if ($null -eq $obj) {
        return $null
    }

    if ($obj.PSObject.Properties.Name -contains "accessToken") {
        return $obj.accessToken
    }

    if ($obj.PSObject.Properties.Name -contains "token") {
        return $obj.token
    }

    foreach ($property in $obj.PSObject.Properties) {

        $value = $property.Value

        if ($value -is [PSCustomObject]) {

            $token = Find-AccessToken $value

            if ($token) {
                return $token
            }
        }
    }

    return $null
}

function Get-MovieIds($obj) {

    $result = @()

    if ($null -eq $obj) {
        return $result
    }

    if (
        $obj -is [System.Collections.IEnumerable] -and
        $obj -isnot [string] -and
        $obj -isnot [PSCustomObject]
    ) {

        foreach ($item in $obj) {
            $result += Get-MovieIds $item
        }

        return $result
    }

    if ($obj.PSObject.Properties.Name -contains "movieId") {
        $result += [long]$obj.movieId
    }

    foreach ($property in $obj.PSObject.Properties) {

        $value = $property.Value

        if (
            $value -is [PSCustomObject] -or
            (
                $value -is [System.Collections.IEnumerable] -and
                $value -isnot [string]
            )
        ) {
            $result += Get-MovieIds $value
        }
    }

    return $result
}

function Same-Set($left, $right) {

    $a = @($left | Sort-Object)
    $b = @($right | Sort-Object)

    if ($a.Count -ne $b.Count) {
        return $false
    }

    return (($a -join ",") -eq ($b -join ","))
}

function Get-CommonCount($left, $right) {

    $leftUnique = @($left | Select-Object -Unique)
    $rightUnique = @($right | Select-Object -Unique)

    $common = @(
        $leftUnique |
        Where-Object { $rightUnique -contains $_ }
    )

    return $common.Count
}

function Validate-Recommendations {

    param(
        [string]$Label,
        $Ids
    )

    $idsArray = @($Ids)

    if ($idsArray.Count -eq 3) {
        Pass "$Label contains exactly 3 movies."
    }
    else {
        Fail "$Label expected 3 movies but got $($idsArray.Count)."
    }

    $unique = @($idsArray | Select-Object -Unique)

    if ($unique.Count -eq $idsArray.Count) {
        Pass "$Label has no duplicate movie IDs."
    }
    else {
        Fail "$Label contains duplicate movie IDs."
    }
}

function Get-Recommendations($headers) {

    return Invoke-Api `
        -Method "GET" `
        -Uri "$BASE_URL/api/v1/recommendations" `
        -Headers $headers
}

function Get-History($headers) {

    return Invoke-Api `
        -Method "GET" `
        -Uri "$BASE_URL/api/v1/recommendations/history" `
        -Headers $headers
}

function To-CanonicalJson($obj) {

    if ($null -eq $obj) {
        return ""
    }

    return $obj | ConvertTo-Json -Depth 100 -Compress
}

# ============================================================
# 1. Login
# ============================================================

Write-Host ""
Write-Host "===== 1. LOGIN ====="

$loginBody = @{
    email    = $email
    password = $password
}

$login = Invoke-Api `
    -Method "POST" `
    -Uri "$BASE_URL/api/v1/auth/login" `
    -Headers @{ accept = "*/*" } `
    -Body $loginBody

if (!$login.Success) {

    Fail "Login failed. HTTP $($login.Status)"
    Write-Host $login.Error
    exit 1
}

$token = Find-AccessToken $login.Body

if ([string]::IsNullOrWhiteSpace($token)) {

    Fail "Access token was not found in login response."
    exit 1
}

Pass "Login succeeded."

$headers = @{
    Authorization = "Bearer $token"
    accept        = "application/json"
}

$password = $null

# ============================================================
# 2. Initial recommendations
# ============================================================

Write-Host ""
Write-Host "===== 2. INITIAL RECOMMENDATIONS ====="

$initialResponse = Get-Recommendations $headers

if (!$initialResponse.Success) {

    Fail "Initial recommendation request failed."
    exit 1
}

$initialIds = @(Get-MovieIds $initialResponse.Body)

Write-Host "Initial movie IDs:" $initialIds

Validate-Recommendations `
    -Label "Initial recommendation list" `
    -Ids $initialIds

if ($initialIds.Count -lt 2) {

    Fail "At least two movies are required for this smoke test."
    exit 1
}

$ratingMovie = $initialIds[0]

# ============================================================
# 3. Rating
# ============================================================

Write-Host ""
Write-Host "===== 3. RATING ====="

Write-Host "Rating movie ID:" $ratingMovie

$ratingBody = @{
    score      = 4.0
    comment    = "smoke-test"
    visibility = "PRIVATE"
    spoiler    = $false
}

$ratingResponse = Invoke-Api `
    -Method "POST" `
    -Uri "$BASE_URL/api/v1/movies/$ratingMovie/ratings" `
    -Headers $headers `
    -Body $ratingBody

if ($ratingResponse.Success) {
    Pass "Rating request succeeded."
}
else {
    Fail "Rating request failed. HTTP $($ratingResponse.Status)"
    exit 1
}

Start-Sleep -Seconds 1

$afterRatingResponse = Get-Recommendations $headers

if (!$afterRatingResponse.Success) {

    Fail "Recommendation request after rating failed."
    exit 1
}

$afterRatingIds = @(Get-MovieIds $afterRatingResponse.Body)

Write-Host "Movie IDs after rating:" $afterRatingIds

Validate-Recommendations `
    -Label "Recommendation list after rating" `
    -Ids $afterRatingIds

if ($afterRatingIds -contains $ratingMovie) {
    Fail "Rated movie is still present."
}
else {
    Pass "Rated movie was removed."
}

$ratingCommonCount =
    Get-CommonCount $initialIds $afterRatingIds

if ($ratingCommonCount -eq 2) {
    Pass "Exactly one replacement movie was added after rating."
}
else {
    Fail "Expected one replacement after rating. Common count: $ratingCommonCount"
}

# ============================================================
# 4. Duplicate rating request
# ============================================================

Write-Host ""
Write-Host "===== 4. DUPLICATE RATING REQUEST ====="

$beforeDuplicateRatingIds = @($afterRatingIds)

$duplicateRating = Invoke-Api `
    -Method "POST" `
    -Uri "$BASE_URL/api/v1/movies/$ratingMovie/ratings" `
    -Headers $headers `
    -Body $ratingBody

if ($duplicateRating.Success) {
    Info "Duplicate rating returned 2xx."
}
else {
    Info "Duplicate rating returned HTTP $($duplicateRating.Status)."
}

Start-Sleep -Seconds 1

$duplicateRatingCheck = Get-Recommendations $headers
$duplicateRatingIds = @(Get-MovieIds $duplicateRatingCheck.Body)

if (Same-Set $beforeDuplicateRatingIds $duplicateRatingIds) {
    Pass "Duplicate rating did not consume another recommendation."
}
else {
    Fail "Duplicate rating changed the recommendation set."
    Write-Host "Before:" $beforeDuplicateRatingIds
    Write-Host "After :" $duplicateRatingIds
}

if ($duplicateRatingIds -contains $ratingMovie) {
    Fail "Rated movie reappeared after duplicate rating."
}
else {
    Pass "Rated movie did not reappear after duplicate rating."
}

# ============================================================
# 5. Dismiss
# ============================================================

Write-Host ""
Write-Host "===== 5. DISMISS ====="

$dismissMovie = $duplicateRatingIds[0]

if ($dismissMovie -eq $ratingMovie) {
    Fail "Dismiss target unexpectedly matches rated movie."
    exit 1
}

Write-Host "Dismiss movie ID:" $dismissMovie

$beforeDismissIds = @($duplicateRatingIds)

$dismissResponse = Invoke-Api `
    -Method "POST" `
    -Uri "$BASE_URL/api/v1/movies/$dismissMovie/dismiss" `
    -Headers $headers `
    -Body "{}"

if ($dismissResponse.Success) {
    Pass "Dismiss request succeeded."
}
else {
    Fail "Dismiss request failed. HTTP $($dismissResponse.Status)"
    exit 1
}

Start-Sleep -Seconds 1

$afterDismissResponse = Get-Recommendations $headers

if (!$afterDismissResponse.Success) {

    Fail "Recommendation request after dismiss failed."
    exit 1
}

$afterDismissIds = @(Get-MovieIds $afterDismissResponse.Body)

Write-Host "Movie IDs after dismiss:" $afterDismissIds

Validate-Recommendations `
    -Label "Recommendation list after dismiss" `
    -Ids $afterDismissIds

if ($afterDismissIds -contains $dismissMovie) {
    Fail "Dismissed movie is still present."
}
else {
    Pass "Dismissed movie was removed."
}

if ($afterDismissIds -contains $ratingMovie) {
    Fail "Previously rated movie reappeared."
}
else {
    Pass "Previously rated movie is still excluded."
}

$dismissCommonCount =
    Get-CommonCount $beforeDismissIds $afterDismissIds

if ($dismissCommonCount -eq 2) {
    Pass "Exactly one replacement movie was added after dismiss."
}
else {
    Fail "Expected one replacement after dismiss. Common count: $dismissCommonCount"
}

# ============================================================
# 6. Duplicate dismiss request
# ============================================================

Write-Host ""
Write-Host "===== 6. DUPLICATE DISMISS REQUEST ====="

$beforeDuplicateDismissIds = @($afterDismissIds)

$duplicateDismiss = Invoke-Api `
    -Method "POST" `
    -Uri "$BASE_URL/api/v1/movies/$dismissMovie/dismiss" `
    -Headers $headers `
    -Body "{}"

if ($duplicateDismiss.Success) {
    Info "Duplicate dismiss returned 2xx."
}
else {
    Info "Duplicate dismiss returned HTTP $($duplicateDismiss.Status)."
}

Start-Sleep -Seconds 1

$duplicateDismissCheck = Get-Recommendations $headers
$duplicateDismissIds = @(Get-MovieIds $duplicateDismissCheck.Body)

if (Same-Set $beforeDuplicateDismissIds $duplicateDismissIds) {
    Pass "Duplicate dismiss did not consume another recommendation."
}
else {
    Fail "Duplicate dismiss changed the recommendation set."
    Write-Host "Before:" $beforeDuplicateDismissIds
    Write-Host "After :" $duplicateDismissIds
}

if ($duplicateDismissIds -contains $dismissMovie) {
    Fail "Dismissed movie reappeared after duplicate dismiss."
}
else {
    Pass "Dismissed movie did not reappear after duplicate dismiss."
}

# ============================================================
# 7. Repeated GET stability
# ============================================================

Write-Host ""
Write-Host "===== 7. REPEATED GET STABILITY ====="

$stableBaseIds = @($duplicateDismissIds)

for ($i = 1; $i -le 3; $i++) {

    $response = Get-Recommendations $headers

    if (!$response.Success) {

        Fail "Repeated GET #$i failed."
        continue
    }

    $ids = @(Get-MovieIds $response.Body)

    Validate-Recommendations `
        -Label "Repeated GET #$i" `
        -Ids $ids

    if ($ids -contains $ratingMovie) {
        Fail "Rated movie reappeared on repeated GET #$i."
    }
    else {
        Pass "Rated movie stayed excluded on GET #$i."
    }

    if ($ids -contains $dismissMovie) {
        Fail "Dismissed movie reappeared on repeated GET #$i."
    }
    else {
        Pass "Dismissed movie stayed excluded on GET #$i."
    }

    if (Same-Set $stableBaseIds $ids) {
        Pass "Repeated GET #$i kept the same recommendation set."
    }
    else {
        Fail "Repeated GET #$i changed the recommendation set."
        Write-Host "Expected:" $stableBaseIds
        Write-Host "Actual  :" $ids
    }
}

# ============================================================
# 8. Re-login persistence
# ============================================================

Write-Host ""
Write-Host "===== 8. RE-LOGIN PERSISTENCE ====="

if ([string]::IsNullOrWhiteSpace($env:FEELM_PASSWORD)) {

    Warn "Re-login test skipped because password is not available after secure input."
}
else {

    $secondLoginBody = @{
        email    = $email
        password = $env:FEELM_PASSWORD
    }

    $secondLogin = Invoke-Api `
        -Method "POST" `
        -Uri "$BASE_URL/api/v1/auth/login" `
        -Headers @{ accept = "*/*" } `
        -Body $secondLoginBody

    if (!$secondLogin.Success) {

        Fail "Second login failed."
    }
    else {

        $secondToken = Find-AccessToken $secondLogin.Body

        if ([string]::IsNullOrWhiteSpace($secondToken)) {

            Fail "Second access token was not found."
        }
        else {

            $secondHeaders = @{
                Authorization = "Bearer $secondToken"
                accept        = "application/json"
            }

            $reloginRecommendations =
                Get-Recommendations $secondHeaders

            if (!$reloginRecommendations.Success) {

                Fail "Recommendation request after re-login failed."
            }
            else {

                $reloginIds =
                    @(Get-MovieIds $reloginRecommendations.Body)

                Validate-Recommendations `
                    -Label "Recommendation list after re-login" `
                    -Ids $reloginIds

                if ($reloginIds -contains $ratingMovie) {
                    Fail "Rated movie reappeared after re-login."
                }
                else {
                    Pass "Rated movie remained excluded after re-login."
                }

                if ($reloginIds -contains $dismissMovie) {
                    Fail "Dismissed movie reappeared after re-login."
                }
                else {
                    Pass "Dismissed movie remained excluded after re-login."
                }
            }
        }
    }
}

# ============================================================
# 9. Recommendation history stability
# ============================================================

Write-Host ""
Write-Host "===== 9. HISTORY DUPLICATE CHECK ====="

$historyBefore = Get-History $headers

if (!$historyBefore.Success) {

    Warn "History API request failed. HTTP $($historyBefore.Status)"
    Warn "History duplicate check was skipped."
}
else {

    $historyBeforeJson =
        To-CanonicalJson $historyBefore.Body

    $noopGet1 = Get-Recommendations $headers
    Start-Sleep -Milliseconds 300
    $noopGet2 = Get-Recommendations $headers
    Start-Sleep -Milliseconds 300

    if (!$noopGet1.Success -or !$noopGet2.Success) {

        Fail "No-op recommendation GET failed during history test."
    }
    else {

        $historyAfter = Get-History $headers

        if (!$historyAfter.Success) {

            Fail "History API failed after no-op GET requests."
        }
        else {

            $historyAfterJson =
                To-CanonicalJson $historyAfter.Body

            if ($historyBeforeJson -eq $historyAfterJson) {

                Pass "Recommendation history did not change after no-op GET requests."
            }
            else {

                Fail "Recommendation history changed after no-op GET requests."
                Write-Host "Possible duplicate history save detected."
            }
        }
    }
}

# ============================================================
# 10. Concurrent GET smoke test
# ============================================================

Write-Host ""
Write-Host "===== 10. CONCURRENT GET ====="

$jobs = @()

for ($i = 1; $i -le 3; $i++) {

    $jobs += Start-Job `
        -ArgumentList $BASE_URL, $token `
        -ScriptBlock {

            param($baseUrl, $accessToken)

            try {

                $headers = @{
                    Authorization = "Bearer $accessToken"
                    accept        = "application/json"
                }

                $response = Invoke-RestMethod `
                    -Method GET `
                    -Uri "$baseUrl/api/v1/recommendations" `
                    -Headers $headers `
                    -ErrorAction Stop

                # Extract movieIds inside the job.
                $json = $response | ConvertTo-Json -Depth 50

                $matches = [regex]::Matches(
                    $json,
                    '"movieId"\s*:\s*(\d+)'
                )

                $ids = @()

                foreach ($match in $matches) {
                    $ids += [long]$match.Groups[1].Value
                }

                return [PSCustomObject]@{
                    Success  = $true
                    MovieIds = $ids
                    Error    = $null
                }
            }
            catch {

                return [PSCustomObject]@{
                    Success  = $false
                    MovieIds = @()
                    Error    = $_.Exception.Message
                }
            }
        }
}

for ($i = 0; $i -lt $jobs.Count; $i++) {

    $jobResult = Receive-Job `
        -Job $jobs[$i] `
        -Wait

    Remove-Job `
        -Job $jobs[$i] `
        -Force

    $number = $i + 1

    if (
        $null -eq $jobResult -or
        !$jobResult.Success
    ) {

        Fail "Concurrent GET #$number failed."

        if ($null -ne $jobResult) {
            Write-Host $jobResult.Error
        }

        continue
    }

    $ids = @($jobResult.MovieIds)

    Write-Host "Concurrent GET #$number movie IDs:" $ids

    if ($ids.Count -ne 3) {

        Fail "Concurrent GET #$number expected 3 movies but got $($ids.Count)."

        # Do not run meaningless assertions on an empty result.
        continue
    }

    Validate-Recommendations `
        -Label "Concurrent GET #$number" `
        -Ids $ids

    if ($ids -contains $ratingMovie) {
        Fail "Rated movie appeared in concurrent GET #$number."
    }
    else {
        Pass "Rated movie stayed excluded in concurrent GET #$number."
    }

    if ($ids -contains $dismissMovie) {
        Fail "Dismissed movie appeared in concurrent GET #$number."
    }
    else {
        Pass "Dismissed movie stayed excluded in concurrent GET #$number."
    }
}

# ============================================================
# Summary
# ============================================================

Write-Host ""
Write-Host "===== SMOKE TEST SUMMARY ====="

Write-Host "PASS:" $PASS_COUNT
Write-Host "FAIL:" $FAIL_COUNT
Write-Host "WARN:" $WARN_COUNT

Write-Host ""
Write-Host "Rated movie ID    :" $ratingMovie
Write-Host "Dismissed movie ID:" $dismissMovie

Write-Host ""
Write-Host "Candidate depletion test: SKIPPED"

if ($FAIL_COUNT -gt 0) {

    Write-Host ""
    Write-Host "===== SMOKE TEST FAILED ====="

    exit 1
}
else {

    Write-Host ""
    Write-Host "===== SMOKE TEST PASSED ====="

    exit 0
}