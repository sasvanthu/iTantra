$adb = "C:\Users\sasva\AppData\Local\Android\Sdk\platform-tools\adb.exe"
$phoneA = "10BE3E2ZP40007A"
$phoneB = "AQNZNJ89FIDYFMFY"

Write-Host "=========================================="
Write-Host "STARTING 20 MESSAGES: PHONE A -> PHONE B"
Write-Host "=========================================="

# Clear logcat buffers
& $adb -s $phoneA logcat -c
& $adb -s $phoneB logcat -c

for ($i = 1; $i -le 20; $i++) {
    $msg = "Message $i from Phone A at $(Get-Date -Format 'HH:mm:ss')"
    Write-Host "Sending A->B ($i/20): $msg"
    & $adb -s $phoneA shell "am broadcast -a com.example.itantra.CMD -p com.example.itantra --es action SEND_TEXT --es text '$msg' --es lang ENGLISH"
    Start-Sleep -Milliseconds 1200
}

Write-Host "`nWaiting 3 seconds for link drain..."
Start-Sleep -Seconds 3

Write-Host "=========================================="
Write-Host "STARTING 20 MESSAGES: PHONE B -> PHONE A"
Write-Host "=========================================="

for ($i = 1; $i -le 20; $i++) {
    $msg = "Message $i from Phone B at $(Get-Date -Format 'HH:mm:ss')"
    Write-Host "Sending B->A ($i/20): $msg"
    & $adb -s $phoneB shell "am broadcast -a com.example.itantra.CMD -p com.example.itantra --es action SEND_TEXT --es text '$msg' --es lang ENGLISH"
    Start-Sleep -Milliseconds 1200
}

Start-Sleep -Seconds 3

Write-Host "`n=========================================="
Write-Host "QUERYING LINK METRICS ON BOTH PHONES"
Write-Host "=========================================="
& $adb -s $phoneA shell am broadcast -a com.example.itantra.CMD -p com.example.itantra --es action GET_STATUS
& $adb -s $phoneB shell am broadcast -a com.example.itantra.CMD -p com.example.itantra --es action GET_STATUS
Start-Sleep -Milliseconds 800

Write-Host "`n--- PHONE A STATUS ---"
& $adb -s $phoneA shell "logcat -d | grep 'iTantraTest: STATUS' | tail -n 2"

Write-Host "`n--- PHONE B STATUS ---"
& $adb -s $phoneB logcat -d -s iTantraTest:I | Select-String -Pattern "STATUS" | Select-Object -Last 2
