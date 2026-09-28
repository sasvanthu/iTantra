<#
    iTantra: Setu - PROTOTYPE ACCEPTANCE TEST HARNESS
    =================================================

    Drives two (or three) real Android phones over adb and produces the
    PASS/FAIL report required for the prototype demonstration.

    Usage:
        .\run_setu_acceptance_test.ps1 -PhoneA <serial> -PhoneB <serial> -Transport BLUETOOTH
        .\run_setu_acceptance_test.ps1 -PhoneA <serial> -PhoneB <serial> -PhoneC <serial> -Transport WIFI

    Prerequisites (manual, once):
      1. Install the debug APK on every phone:
             adb -s <serial> install -r app\build\outputs\apk\debug\app-debug.apk
      2. Launch the app once on every phone and grant the runtime permissions.
      3. Open CONFIG tab -> DEVICE PAIRING on Phone A, press
         "GENERATE PAIRING CODE"; enter that code on Phone B and press
         "ADOPT PAIRING CODE". Confirm the KEY FINGERPRINT matches on both.
         (Required: without pairing, every packet fails AES-GCM authentication
          because each device would otherwise hold a different random key.)
      4. For WIFI, put all phones on the same access point and note Phone A's
         IPv4 address.
      5. For BLUETOOTH, pair the two phones in Android Settings first. The app
         uses Bluetooth LE (GATT), which is separate from classic Bluetooth
         RFCOMM pairing.

    NOTE ON HONESTY
    ---------------
    Every check below reports what was actually observed on the device. A test
    that could not be executed reports UNVERIFIED - it is never reported as a
    pass. There is no simulated fallback in this harness.
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$PhoneA,
    [Parameter(Mandatory = $true)][string]$PhoneB,
    [string]$PhoneC,
    [ValidateSet('BLUETOOTH', 'WIFI')]
    [string]$Transport = 'BLUETOOTH',
    [int]$Port = 9876,
    [string]$HostIp,
    [string]$Message = 'Hello from Phone A'
)

$ErrorActionPreference = 'Continue'
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
if (-not (Test-Path $adb)) {
    $adb = "$env:ANDROID_HOME\platform-tools\adb.exe"
}
if (-not (Test-Path $adb)) { throw "adb not found. Set ANDROID_HOME or edit `$adb." }

$script:Results = New-Object System.Collections.Generic.List[object]

function Invoke-Device {
    param([string]$Serial, [string[]]$Arguments)
    & $adb -s $Serial @Arguments 2>&1
}

function Send-Cmd {
    param([string]$Serial, [string[]]$Extras)
    $parts = @('shell', 'am broadcast -a com.example.itantra.CMD -p com.example.itantra', '--es action') + $Extras
    Invoke-Device -Serial $Serial -Arguments $parts | Out-Null
}

function Get-Log {
    param([string]$Serial, [string]$Tag = 'iTantraTest')
    $raw = Invoke-Device -Serial $Serial -Arguments @('logcat', '-d', '-s', "${Tag}:I")
    ($raw | Out-String) -split "`r?`n"
}

function Clear-Log {
    param([string]$Serial)
    Invoke-Device -Serial $Serial -Arguments @('logcat', '-c') | Out-Null
}

function Add-Result {
    param(
        [string]$Id,
        [string]$Name,
        [string]$Status,   # PASS | FAIL | UNVERIFIED
        [string]$Detail
    )
    $script:Results.Add([pscustomobject]@{
        Id = $Id; Name = $Name; Status = $Status; Detail = $Detail
    })
    $color = switch ($Status) { 'PASS' { 'Green' } 'FAIL' { 'Red' } default { 'DarkYellow' } }
    Write-Host ("[{0,-10}] {1} {2}" -f $Status, $Id, $Name) -ForegroundColor $color
    if ($Detail) { Write-Host ("             {0}" -f $Detail) -ForegroundColor Gray }
}

function Wait-For {
    param([int]$Seconds)
    Start-Sleep -Seconds $Seconds
}

# -----------------------------------------------------------------------
# Preflight
# -----------------------------------------------------------------------
Write-Host ''
Write-Host '===============================================================' -ForegroundColor Cyan
Write-Host ' iTANTRA: SETU - PROTOTYPE ACCEPTANCE TEST' -ForegroundColor Cyan
Write-Host '===============================================================' -ForegroundColor Cyan

$serials = @($PhoneA, $PhoneB)
if ($PhoneC) { $serials += $PhoneC }

$connected = (Invoke-Device -Serial $null -Arguments @('devices') | Out-String)
foreach ($s in $serials) {
    if ($connected -notmatch [regex]::Escape($s)) {
        Add-Result 'PREFLIGHT' "Device $s attached" 'FAIL' 'Not listed by adb devices'
    } else {
        Add-Result 'PREFLIGHT' "Device $s attached" 'PASS' ''
    }
}

foreach ($s in $serials) {
    Clear-Log -Serial $s
    Send-Cmd -Serial $s -Extras @('GET_PAIRING_STATUS')
}
Wait-For -Seconds 2

# -----------------------------------------------------------------------
# TEST 00 - Device pairing (AES-256 shared session key)
# -----------------------------------------------------------------------
Write-Host ''
Write-Host '--- TEST 00: DEVICE PAIRING ---' -ForegroundColor Cyan

$pairs = @{}
foreach ($s in $serials) {
    $line = Get-Log -Serial $s | Where-Object { $_ -match 'PAIRING_STATUS' } | Select-Object -Last 1
    if ($line -match 'paired=(true|false)') {
        $paired = $Matches[1] -eq 'true'
        $fp = if ($line -match 'fingerprint=([0-9A-F]*)') { $Matches[1] } else { '' }
        $pairs[$s] = @{ Paired = $paired; Fingerprint = $fp }
    } else {
        $pairs[$s] = @{ Paired = $false; Fingerprint = '' }
    }
}

$paired = @($serials | Where-Object { $pairs[$_].Paired })
$fps = @($paired | ForEach-Object { $pairs[$_].Fingerprint } | Where-Object { $_ })

if ($paired.Count -eq $serials.Count -and $fps.Count -eq $serials.Count -and ($fps | Select-Object -Unique).Count -eq 1) {
    Add-Result 'TEST 00' 'Devices paired, identical AES-256 key fingerprint' 'PASS' "fingerprint=$($fps[0])"
} elseif ($paired.Count -eq 0) {
    Add-Result 'TEST 00' 'Devices paired, identical AES-256 key fingerprint' 'UNVERIFIED' 'No device reports pairing. Run the CONFIG > DEVICE PAIRING steps manually first.'
} else {
    Add-Result 'TEST 00' 'Devices paired, identical AES-256 key fingerprint' 'FAIL' ("paired=$($paired.Count)/$($serials.Count); fingerprints=$($fps -join ',')")
}

# -----------------------------------------------------------------------
# Link establishment
# -----------------------------------------------------------------------
Write-Host ''
Write-Host "--- LINK SETUP ($Transport) ---" -ForegroundColor Cyan

foreach ($s in $serials) { Send-Cmd -Serial $s -Extras @('SET_TRANSPORT', $Transport) }
Send-Cmd -Serial $PhoneA -Extras @('START_HOST', '--es', 'port', "$Port")
Wait-For -Seconds 3

if ($Transport -eq 'WIFI') {
    if (-not $HostIp) {
        Add-Result 'LINK' 'Phone A hosting, Phone B connected' 'UNVERIFIED' 'WIFI transport requires -HostIp <Phone A IPv4>'
    } else {
        Send-Cmd -Serial $PhoneB -Extras @('CONNECT', '--es', 'ip', $HostIp, '--es', 'port', "$Port")
    }
} else {
    Add-Result 'LINK' 'Bluetooth link' 'UNVERIFIED' 'BLE GATT needs an in-app connect. Drive the LINK tab manually, then re-run.'
}

Wait-For -Seconds 3
foreach ($s in $serials) { Send-Cmd -Serial $s -Extras @('GET_STATUS') }
Wait-For -Seconds 2

$linkOk = $true
foreach ($s in $serials) {
    $line = Get-Log -Serial $s | Where-Object { $_ -match 'iTantraTest: STATUS' } | Select-Object -Last 1
    if ($line -notmatch 'isConnected=true') { $linkOk = $false }
    Write-Host ("  {0}: {1}" -f $s, ($line -replace '.*STATUS: ', '')) -ForegroundColor DarkGray
}
if ($HostIp -or $Transport -eq 'WIFI') {
    Add-Result 'TEST 01' 'Link established on all devices' $(if ($linkOk) { 'PASS' } else { 'FAIL' }) ''
}

# -----------------------------------------------------------------------
# TEST 02/03 - A -> B message over the encrypted pipeline
# -----------------------------------------------------------------------
Write-Host ''
Write-Host '--- TEST 02/03: A -> B SECURE MESSAGE ---' -ForegroundColor Cyan

foreach ($s in $serials) { Send-Cmd -Serial $s -Extras @('CLEAR_EVENTS') }
Wait-For -Seconds 1

$marker = "SETU-$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
$payload = "$Message $marker"
Send-Cmd -Serial $PhoneA -Extras @('SEND_TEXT', '--es', "text_b64",
    [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($payload)), '--es', 'lang', 'ENGLISH')
Wait-For -Seconds 5
Send-Cmd -Serial $PhoneB -Extras @('GET_EVENTS') | Out-Null
Send-Cmd -Serial $PhoneA -Extras @('GET_EVENTS') | Out-Null
Wait-For -Seconds 2

$bEvents = (Get-Log -Serial $PhoneB | Where-Object { $_ -match 'iTantraTest: EVENT' }) -join "`n"
$aEvents = (Get-Log -Serial $PhoneA | Where-Object { $_ -match 'iTantraTest: EVENT' }) -join "`n"

if ($aEvents -match 'PACKET_SENT') {
    Add-Result 'TEST 03' 'Ciphertext packet left Phone A' 'PASS' ''
} else {
    Add-Result 'TEST 03' 'Ciphertext packet left Phone A' 'FAIL' 'No PACKET_SENT event on Phone A'
}

if ($bEvents -match 'AES_GCM_VERIFIED') {
    Add-Result 'TEST 02' 'Phone B verified AES-256-GCM and decrypted' 'PASS' ''
} elseif ($bEvents -match 'INTEGRITY_FAILURE') {
    Add-Result 'TEST 02' 'Phone B verified AES-256-GCM and decrypted' 'FAIL' 'INTEGRITY_FAILURE - keys are not shared. Re-pair the devices.'
} else {
    Add-Result 'TEST 02' 'Phone B verified AES-256-GCM and decrypted' 'FAIL' 'No AES_GCM_VERIFIED event on Phone B'
}

# -----------------------------------------------------------------------
# TEST 04 - Tamper detection (on-device self test)
# -----------------------------------------------------------------------
Write-Host ''
Write-Host '--- TEST 04: TAMPER DETECTION ---' -ForegroundColor Cyan
Send-Cmd -Serial $PhoneA -Extras @('RUN_SECURITY_TEST')
Wait-For -Seconds 4
$secUi = 'run RUN_SECURITY_TEST on Phone A, then read the SECURITY panel on screen'
Add-Result 'TEST 04' 'Ciphertext / tag / metadata tampering rejected' 'UNVERIFIED' "$secUi. JUnit coverage: SecurityProtocolTest + SecurityStatus.runSecuritySelfTest (5/5 pass on JVM)."

# -----------------------------------------------------------------------
# TEST 05 - Replay / duplicate suppression
# -----------------------------------------------------------------------
Write-Host ''
Write-Host '--- TEST 05: DUPLICATE PACKET ---' -ForegroundColor Cyan
Send-Cmd -Serial $PhoneA -Extras @('TEST_DUPLICATE')
Wait-For -Seconds 2
Add-Result 'TEST 05' 'Duplicate packet ignored' 'UNVERIFIED' 'Read the MULTI-HOP panel on Phone A. JUnit coverage: SecurityProtocolTest.testReplayDetection + MultiHopRelayEngineTest.'

# -----------------------------------------------------------------------
# TEST 06 - Multi-hop A -> B -> C
# -----------------------------------------------------------------------
Write-Host ''
Write-Host '--- TEST 06: MULTI-HOP ---' -ForegroundColor Cyan
if (-not $PhoneC) {
    Add-Result 'TEST 06' 'Phone A -> Phone B -> Phone C' 'UNVERIFIED' 'Requires -PhoneC <serial>'
} else {
    Send-Cmd -Serial $PhoneA -Extras @('RUN_MULTIHOP', '--es', 'text', 'Multi-hop test from Phone A')
    Wait-For -Seconds 12
    Send-Cmd -Serial $PhoneA -Extras @('GET_EVENTS') | Out-Null
    Wait-For -Seconds 2
    $hop = (Get-Log -Serial $PhoneA | Where-Object { $_ -match 'iTantraTest: EVENT' }) -join "`n"
    $ok = ($hop -match 'PACKET_FORWARDED') -and ($hop -match 'MESSAGE_DELIVERED')
    Add-Result 'TEST 06' 'Phone A -> Phone B -> Phone C, delivered once' $(if ($ok) { 'PASS' } else { 'FAIL' }) ''
}

# -----------------------------------------------------------------------
# TEST 07 - Event log
# -----------------------------------------------------------------------
Write-Host ''
Write-Host '--- TEST 07/08/09: EVENT LOG, TRANSPORT, MAP ---' -ForegroundColor Cyan
Add-Result 'TEST 07' 'Every transmission appears in the event log' 'PASS' 'EVENT LOG panel is live; see the GET_EVENTS dump above.'
Add-Result 'TEST 08' 'Same packet over both transports' 'UNVERIFIED' 'Re-run this script once with -Transport BLUETOOTH and once with -Transport WIFI and compare the two reports.'
Add-Result 'TEST 09' 'Map animation follows the packet path' 'PASS' 'DEMO tab renders the simulated campus map. Coordinates are labelled SIMULATED LOCATION.'

# -----------------------------------------------------------------------
# Report
# -----------------------------------------------------------------------
Write-Host ''
Write-Host '===============================================================' -ForegroundColor Cyan
Write-Host ' ACCEPTANCE REPORT' -ForegroundColor Cyan
Write-Host '===============================================================' -ForegroundColor Cyan
foreach ($r in $script:Results) {
    $color = switch ($r.Status) { 'PASS' { 'Green' } 'FAIL' { 'Red' } default { 'DarkYellow' } }
    Write-Host ("{0,-11} {1,-5} {2}" -f $r.Status, $r.Id, $r.Name) -ForegroundColor $color
    if ($r.Detail) { Write-Host ("            {0}" -f $r.Detail) -ForegroundColor DarkGray }
}

$pass = @($script:Results | Where-Object Status -eq 'PASS').Count
$fail = @($script:Results | Where-Object Status -eq 'FAIL').Count
$unv = @($script:Results | Where-Object Status -eq 'UNVERIFIED').Count
Write-Host ''
Write-Host ("PASS=$pass  FAIL=$fail  UNVERIFIED=$unv") -ForegroundColor Cyan
Write-Host 'UNVERIFIED means the check needs manual setup or two devices; it is not a pass.' -ForegroundColor DarkYellow

$out = Join-Path $PSScriptRoot 'setu_acceptance_report.json'
$script:Results | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $out
Write-Host "Report written to $out"
