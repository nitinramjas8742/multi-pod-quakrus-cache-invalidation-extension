# Verifies the Redis-backed Hibernate 2LC invalidation across two running app instances.
#
# Prerequisites:
#   1. Redis reachable on localhost:6379 (the app's configured Redis).
#   2. Two app instances already running, e.g.:
#        java -Dquarkus.http.port=8081 -jar build\quarkus-app\quarkus-run.jar
#        java -Dquarkus.http.port=8082 -jar build\quarkus-app\quarkus-run.jar
#   3. The instances were started with SQL logging enabled (default here), and
#      their stdout was redirected to the log files referenced by -LogA/-LogB.
#
# The script mutates the target agency and restores it at the end.

param(
    [int]$PortA = 8081,
    [int]$PortB = 8082,
    [string]$AgencyCode = "AG1",
    [string]$LogA = "build\app-8081.log",
    [string]$LogB = "build\app-8082.log",
    [int]$RaceIterations = 10
)

$ErrorActionPreference = "Stop"

$script:failures = New-Object System.Collections.Generic.List[string]

function Pass([string]$message) {
    Write-Host "PASS: $message" -ForegroundColor Green
}

function Fail([string]$message) {
    Write-Host "FAIL: $message" -ForegroundColor Red
    $script:failures.Add($message)
}

function Get-SqlCount([string]$path) {
    if (Test-Path $path) {
        return (Select-String -Path $path -Pattern "select" -SimpleMatch).Count
    }
    return $null
}

function Get-AgencyByCode([int]$port, [string]$code) {
    try {
        return Invoke-RestMethod -Uri "http://localhost:$port/api/agencies/code/$code" -TimeoutSec 20
    } catch {
        if ($_.Exception.Response -ne $null -and [int]$_.Exception.Response.StatusCode -eq 404) {
            return $null
        }
        throw
    }
}

function Get-AgencyById([int]$port, [long]$id) {
    try {
        return Invoke-RestMethod -Uri "http://localhost:$port/api/agencies/$id" -TimeoutSec 20
    } catch {
        if ($_.Exception.Response -ne $null -and [int]$_.Exception.Response.StatusCode -eq 404) {
            return $null
        }
        throw
    }
}

function Put-Agency([int]$port, [string]$code, [string]$name, [string]$email, [string[]]$agentCodes) {
    $body = @{
        name       = $name
        email      = $email
        status     = "ACTIVE"
        agentCodes = $agentCodes
    } | ConvertTo-Json
    return Invoke-RestMethod -Uri "http://localhost:$port/api/agencies/code/$code" -Method PUT `
        -ContentType "application/json" -Body $body -TimeoutSec 20
}

Write-Host "== Redis L2 cache multi-pod verification =="
Write-Host "Pod A: $PortA (log: $LogA)"
Write-Host "Pod B: $PortB (log: $LogB)"
Write-Host "Agency: $AgencyCode"
Write-Host ""

# ---------------------------------------------------------------------------
# 0. Reachability + snapshot for restore
# ---------------------------------------------------------------------------
$original = Get-AgencyByCode $PortA $AgencyCode
if ($null -eq $original) {
    Write-Host "Cannot find agency '$AgencyCode' on port $PortA. Aborting." -ForegroundColor Red
    exit 2
}
Write-Host ("Original: name='{0}' email='{1}' agentCodes=[{2}]" -f `
    $original.name, $original.email, ($original.agentCodes -join ","))
Write-Host ""

try {
    # -----------------------------------------------------------------------
    # 1. Local cache actually serves repeated reads
    # -----------------------------------------------------------------------
    $sqlBefore = Get-SqlCount $LogA
    $null = Get-AgencyByCode $PortA $AgencyCode
    $sqlAfterFirst = Get-SqlCount $LogA
    $null = Get-AgencyByCode $PortA $AgencyCode
    $sqlAfterSecond = Get-SqlCount $LogA

    if ($null -ne $sqlAfterFirst -and $null -ne $sqlAfterSecond) {
        if ($sqlAfterSecond -eq $sqlAfterFirst) {
            Pass "second read on pod A did not hit the database (SQL count stayed $sqlAfterSecond)"
        } else {
            Fail "second read on pod A issued SQL ($sqlAfterFirst -> $sqlAfterSecond); L2 cache is not active"
        }
    } else {
        Write-Host "SKIP: SQL-count check (log file not found)" -ForegroundColor Yellow
    }

    # -----------------------------------------------------------------------
    # 2. Cross-pod invalidation on update
    # -----------------------------------------------------------------------
    $null = Get-AgencyByCode $PortB $AgencyCode   # warm pod B
    Start-Sleep -Milliseconds 300

    $testName = "Verify-" + (Get-Date -Format "HHmmss")
    $sqlBeforeB = Get-SqlCount $LogB
    $null = Put-Agency $PortA $AgencyCode $testName "verify@x.com" @("A1", "A2", "A3")
    Start-Sleep -Milliseconds 400
    $fromB = Get-AgencyByCode $PortB $AgencyCode
    $sqlAfterB = Get-SqlCount $LogB

    if ($null -ne $fromB -and $fromB.name -eq $testName) {
        Pass "pod B observed the update made on pod A ('$testName')"
    } else {
        $actual = if ($null -eq $fromB) { "<404>" } else { $fromB.name }
        Fail "pod B returned stale data after update on pod A (expected '$testName', got '$actual')"
    }

    if ($null -ne $sqlBeforeB -and $null -ne $sqlAfterB) {
        if ($sqlAfterB -gt $sqlBeforeB) {
            Pass "pod B reloaded from the database after invalidation (SQL count $sqlBeforeB -> $sqlAfterB)"
        } else {
            Fail "pod B did not reload after invalidation (SQL count $sqlBeforeB -> $sqlAfterB)"
        }
    }

    # -----------------------------------------------------------------------
    # 3. Update/read race: repeated write-then-read across pods
    # -----------------------------------------------------------------------
    if ($RaceIterations -gt 0) {
        $mismatches = 0
        for ($i = 1; $i -le $RaceIterations; $i++) {
            $name = "Race-$i-" + (Get-Date -Format "HHmmss")
            $null = Put-Agency $PortA $AgencyCode $name "race@x.com" @("A1", "A2", "A3")
            $seen = Get-AgencyByCode $PortB $AgencyCode
            if ($null -eq $seen -or $seen.name -ne $name) {
                $mismatches++
                Write-Host ("  mismatch at iteration {0}: expected '{1}', got '{2}'" -f `
                    $i, $name, $(if ($null -eq $seen) { "<404>" } else { $seen.name }))
            }
        }
        if ($mismatches -eq 0) {
            Pass "$RaceIterations consecutive cross-pod update/read iterations had no stale reads"
        } else {
            Fail "$mismatches of $RaceIterations cross-pod update/read iterations were stale"
        }
    }

    # -----------------------------------------------------------------------
    # 4. Delete propagation
    # -----------------------------------------------------------------------
    $tempCode = "AGTMP" + (Get-Date -Format "HHmmss")
    $createBody = @{
        agencyCode = $tempCode
        name       = "Temp Agency"
        email      = "temp@x.com"
        status     = "ACTIVE"
        agentCodes = @("T1", "T2")
    } | ConvertTo-Json
    $created = Invoke-RestMethod -Uri "http://localhost:$PortA/api/agencies" -Method POST `
        -ContentType "application/json" -Body $createBody -TimeoutSec 20
    Write-Host "Created temporary agency $tempCode (id=$($created.id))"

    $null = Get-AgencyByCode $PortB $tempCode   # warm pod B
    $null = Get-AgencyByCode $PortA $tempCode   # warm pod A
    Invoke-WebRequest -Uri "http://localhost:$PortA/api/agencies/$($created.id)" -Method DELETE `
        -UseBasicParsing -TimeoutSec 20 | Out-Null
    Start-Sleep -Milliseconds 500

    $afterDeleteB = Get-AgencyByCode $PortB $tempCode
    $afterDeleteA = Get-AgencyByCode $PortA $tempCode

    if ($null -eq $afterDeleteB -and $null -eq $afterDeleteA) {
        Pass "deleted agency returns 404 on both pods (no stale cached row)"
    } else {
        Fail ("deleted agency is still visible: podA={0} podB={1}" -f `
            $(if ($null -eq $afterDeleteA) { "404" } else { "200" }), `
            $(if ($null -eq $afterDeleteB) { "404" } else { "200" }))
    }
}
finally {
    # -----------------------------------------------------------------------
    # 5. Restore original data
    # -----------------------------------------------------------------------
    try {
        $null = Put-Agency $PortA $AgencyCode $original.name $original.email @($original.agentCodes)
        $restored = Get-AgencyByCode $PortA $AgencyCode
        if ($null -ne $restored -and $restored.name -eq $original.name) {
            Write-Host "Restored '$AgencyCode' to its original values." -ForegroundColor Cyan
        } else {
            Write-Host "WARNING: could not confirm restore of '$AgencyCode'." -ForegroundColor Yellow
        }
    } catch {
        Write-Host "WARNING: restore failed: $($_.Exception.Message)" -ForegroundColor Yellow
    }
}

Write-Host ""
if ($script:failures.Count -eq 0) {
    Write-Host "RESULT: ALL CHECKS PASSED" -ForegroundColor Green
    exit 0
}

Write-Host ("RESULT: {0} CHECK(S) FAILED" -f $script:failures.Count) -ForegroundColor Red
$script:failures | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
exit 1
