# --- Infrastructure ---
$PowerAdbPath = $env:PowerAdbPath
Import-Module -Name $PowerAdbPath -Force

$appPackageName = 'com.elianfabian.lapisbt_rpc_testing'

# Reusing common logic
. "$PSScriptRoot/../scripts/TestFunctions.ps1"
. "$PSScriptRoot/../scripts/BluetoothBridge.ps1" `
    -PackageName $appPackageName `
    -BroadcastReceiver '.RpcTestingBroadcastReceiver' `
    -Activity '.MainRpcTestingActivity'



$realDevices = Get-AdbDevice | Where-Object { -not (Test-AdbEmulator -SerialNumber $_) }
if ($realDevices.Count -lt 2)
{
    Write-Error "This test requires 2 real devices connected"
    Show-AdbDevice
    Read-Host "Press enter to exit"
    exit
}

$device1 = New-DeviceObject $realDevices[0]
$device2 = New-DeviceObject $realDevices[1]

# The one whose API level is higher is more likely to have the command to enable bluetooth
if ($device1.ApiLevel -ge $device2.ApiLevel)
{
    $clientDevice = $device1
    $serverDevice = $device2
}
else
{
    $clientDevice = $device2
    $serverDevice = $device1
}

Write-Host "client device: $clientDevice" -ForegroundColor Green
Write-Host "server device: $serverDevice" -ForegroundColor Green
$location = Get-Location
Set-Location "$PSScriptRoot/.."
./gradlew :lapisbt-rpc-testing:assembleDebug
$apkPath = 'lapisbt-rpc-testing/build/outputs/apk/debug/lapisbt-rpc-testing-debug.apk'
Set-Location $location

foreach ($device in @($clientDevice, $serverDevice))
{
    Install-AdbPackage -SerialNumber $device.SerialNumber -Path $apkPath
    Stop-AdbPackage -SerialNumber $device.SerialNumber -PackageName $appPackageName

    Grant-AdbPermission -SerialNumber $device.SerialNumber -PackageName $appPackageName `
        -PermissionName @(
        'android.permission.ACCESS_FINE_LOCATION'
        'android.permission.BLUETOOTH_SCAN'
        'android.permission.BLUETOOTH_CONNECT'
    ) `
        -ErrorAction SilentlyContinue

    Start-AdbPackage -SerialNumber $device.SerialNumber -PackageName $appPackageName

    Ensure-BluetoothOn $device
}

# --- Tests ---
Test "Connect devices" {
    $uuid = (New-Guid).Guid
    Invoke-LapisAction -SerialNumber $serverDevice.SerialNumber -Action 'start-server' -Extras {
        New-AdbBundlePair -Key 'uuid' -String $uuid
    }
    Invoke-LapisAction -SerialNumber $clientDevice.SerialNumber -Action 'connect-device' -Extras {
        New-AdbBundlePair -Key 'address' -String $serverDevice.Address
        New-AdbBundlePair -Key 'uuid' -String $uuid
    }

    Start-Sleep -Seconds 5
    $connected = Get-LapisState -SerialNumber $clientDevice.SerialNumber -Name 'get-connected-devices' | ConvertFrom-Json
    Assert-That $connected -Contain $serverDevice.Address
}

Test "RPC: Greet" {
    Invoke-LapisAction -SerialNumber $clientDevice.SerialNumber -Action 'call-greet' -Extras {
        New-AdbBundlePair -Key 'address' -String $serverDevice.Address
        New-AdbBundlePair -Key 'name' -String 'Lapis'
    }
    Start-Sleep -Seconds 2
    $result = Get-LapisState -SerialNumber $clientDevice.SerialNumber -Name 'get-last-rpc-result'
    Assert-That $result -IsEqualTo 'Hello, Lapis!'
}

Test "RPC: Add" {
    Invoke-LapisAction -SerialNumber $clientDevice.SerialNumber -Action 'call-add' -Extras {
        New-AdbBundlePair -Key 'address' -String $serverDevice.Address
        New-AdbBundlePair -Key 'a' -Int 10
        New-AdbBundlePair -Key 'b' -Int 20
    }
    Start-Sleep -Seconds 2
    $result = Get-LapisState -SerialNumber $clientDevice.SerialNumber -Name 'get-last-rpc-result'
    Assert-That $result -IsEqualTo '30'
}

Test "RPC: Counter (Flow)" {
    Invoke-LapisAction -SerialNumber $clientDevice.SerialNumber -Action 'call-counter' -Extras {
        New-AdbBundlePair -Key 'address' -String $serverDevice.Address
    }
    Start-Sleep -Seconds 5
    $result = Get-LapisState -SerialNumber $clientDevice.SerialNumber -Name 'get-last-rpc-result'
    Assert-That $result -IsEqualTo '[0, 1, 2, 3, 4, 5, 6, 7, 8, 9]'
}

Test "RPC: Process Flow (Flow as Parameter)" {
    Invoke-LapisAction -SerialNumber $clientDevice.SerialNumber -Action 'call-process-flow' -Extras {
        New-AdbBundlePair -Key 'address' -String $serverDevice.Address
        New-AdbBundlePair -Key 'data' -IntArray (1..30)
    }
    Start-Sleep -Seconds 5
    $result = Get-LapisState -SerialNumber $clientDevice.SerialNumber -Name 'get-last-rpc-result'
    Assert-That $result -IsEqualTo '465'
}

Test "RPC: Ping (Secondary Service)" {
    Invoke-LapisAction -SerialNumber $clientDevice.SerialNumber -Action 'call-ping' -Extras {
        New-AdbBundlePair -Key 'address' -String $serverDevice.Address
    }
    Start-Sleep -Seconds 2
    $result = Get-LapisState -SerialNumber $clientDevice.SerialNumber -Name 'get-last-rpc-result'
    Assert-That $result -IsEqualTo 'pong'
}


Read-Host "Press enter to exit..."
