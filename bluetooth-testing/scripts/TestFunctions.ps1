function Fail($message) {
    Write-Error $message
    Read-Host "Press enter to exit"
    exit
}

function Ensure-BluetoothOn {
    param(
        [PSCustomObject] $Device
    )

    if (-not (Test-AdbBluetooth -SerialNumber $Device.SerialNumber)) {
        if ($Device.ApiLevel -ge 33) {
            Enable-AdbBluetooth -SerialNumber $Device.SerialNumber
        }

        Write-Host "Waiting until you turn on bluetooth for device: $Device" -ForegroundColor Yellow

        do {
            $state = Get-AdbBluetoothState -SerialNumber $Device.SerialNumber
        }
        while ($state -ne 'ON')
    }
}

function New-DeviceObject {
    param([string] $SerialNumber)

    return [pscustomobject]@{
        SerialNumber = $SerialNumber
        Name         = Get-AdbDeviceName -SerialNumber $SerialNumber
        Address      = Get-AdbBluetoothAddress -SerialNumber $SerialNumber
        ApiLevel     = Get-AdbApiLevel -SerialNumber $SerialNumber
    }
}

function Test {
    param ([string] $Name, [scriptblock] $Block)

    Write-Host "START TEST: $Name" -ForegroundColor Cyan
    $Block.Invoke()
    Write-Host "END TEST" -ForegroundColor Cyan
    
    Write-Host
    Write-Host  
}


function Fail-Assertion {
    param([string] $Message)

    Write-Host "❌ ASSERTION FAILED:`n$Message" -ForegroundColor Red
    Read-Host "Press enter to exit"
    exit 1
}

function Assert-That {

    [CmdletBinding(DefaultParameterSetName = 'None')]
    param (
        [AllowNull()]
        [AllowEmptyCollection()]
        [AllowEmptyString()]
        [Parameter(Mandatory, Position = 0)]
        $Actual,

        [AllowNull()]
        [AllowEmptyCollection()]
        [AllowEmptyString()]
        [Parameter(ParameterSetName = 'IsEqualTo')]
        $IsEqualTo,

        [AllowNull()]
        [AllowEmptyCollection()]
        [AllowEmptyString()]
        [Parameter(ParameterSetName = 'IsNotEqualTo')]
        $IsNotEqualTo,

        [AllowNull()]
        [AllowEmptyCollection()]
        [AllowEmptyString()]
        [Parameter(ParameterSetName = 'IsTrue')]
        [switch] $IsTrue,

        [AllowNull()]
        [AllowEmptyCollection()]
        [AllowEmptyString()]
        [Parameter(ParameterSetName = 'IsFalse')]
        [switch] $IsFalse,

        [AllowNull()]
        [AllowEmptyCollection()]
        [AllowEmptyString()]
        [Parameter(ParameterSetName = 'IsNull')]
        [switch] $IsNull,
        
        [AllowNull()]
        [AllowEmptyCollection()]
        [AllowEmptyString()]
        [Parameter(ParameterSetName = 'IsNotNull')]
        [switch] $IsNotNull,
        
        [AllowNull()]
        [AllowEmptyCollection()]
        [AllowEmptyString()]
        [Parameter(ParameterSetName = 'IsEmpty')]
        [switch] $IsEmpty,
        
        [AllowNull()]
        [AllowEmptyCollection()]
        [AllowEmptyString()]
        [Parameter(ParameterSetName = 'IsNotEmpty')]
        [switch] $IsNotEmpty,

        
        [AllowNull()]
        [AllowEmptyCollection()]
        [AllowEmptyString()]
        [Parameter(ParameterSetName = 'Contain')]
        $Contain,
        
        [AllowNull()]
        [AllowEmptyCollection()]
        [AllowEmptyString()]
        [Parameter(ParameterSetName = 'NotContain')]
        $NotContain,
        
        [AllowNull()]
        [AllowEmptyCollection()]
        [AllowEmptyString()]
        [Parameter(ParameterSetName = 'Match')]
        $Match,

        
        [AllowNull()]
        [AllowEmptyCollection()]
        [AllowEmptyString()]
        [Parameter(ParameterSetName = 'IsGreaterThan')]
        $IsGreaterThan,
        
        [AllowNull()]
        [AllowEmptyCollection()]
        [AllowEmptyString()]
        [Parameter(ParameterSetName = 'IsLessThan')]
        $IsLessThan,
        
        [AllowNull()]
        [AllowEmptyCollection()]
        [AllowEmptyString()]
        [Parameter(ParameterSetName = 'IsGreaterOrEqualTo')]
        $IsGreaterOrEqualTo,
        
        [AllowNull()]
        [AllowEmptyCollection()]
        [AllowEmptyString()]
        [Parameter(ParameterSetName = 'IsLessOrEqualTo')]
        $IsLessOrEqualTo,

        
        [AllowNull()]
        [AllowEmptyCollection()]
        [AllowEmptyString()]
        [Parameter(ParameterSetName = 'HasCount')]
        [int] $HasCount,

        
        [AllowNull()]
        [AllowEmptyCollection()]
        [AllowEmptyString()]
        [Parameter(ParameterSetName = 'IsWithin')]
        [long] $Tolerance,
        
        [AllowNull()]
        [AllowEmptyCollection()]
        [AllowEmptyString()]
        [Parameter(ParameterSetName = 'IsWithin')]
        [long] $Of
    )

    process {
        switch ($PSCmdlet.ParameterSetName) {
            'IsEqualTo' {
                $isEqual = $true
                
                if ($Actual -is [System.Collections.IEnumerable] -and $Actual -isnot [string] -and $IsEqualTo -is [System.Collections.IEnumerable] -and $IsEqualTo -isnot [string]) {
                    $diff = Compare-Object -ReferenceObject $IsEqualTo -DifferenceObject $Actual -SyncWindow 0 -ErrorAction SilentlyContinue
                    if ($null -ne $diff) { $isEqual = $false }
                }
                else {
                    if ($Actual -ne $IsEqualTo) { $isEqual = $false }
                }

                if (-not $isEqual) {
                    $strExpected = if ($IsEqualTo -is [System.Collections.IEnumerable] -and $IsEqualTo -isnot [string]) { "@(" + ($IsEqualTo -join ', ') + ")" } else { $IsEqualTo }
                    $strActual = if ($Actual -is [System.Collections.IEnumerable] -and $Actual -isnot [string]) { "@(" + ($Actual -join ', ') + ")" } else { $Actual }

                    Fail-Assertion "Expected value (equal to): $strExpected`nActual value: $strActual ($($Actual.GetType().Name))"
                }
            }
            'IsNotEqualTo' {
                $isEqual = $true
                $Expected = $IsNotEqualTo
                
                if ($Actual -is [System.Collections.IEnumerable] -and $Actual -isnot [string] -and $Expected -is [System.Collections.IEnumerable] -and $Expected -isnot [string]) {
                    $diff = Compare-Object -ReferenceObject $Expected -DifferenceObject $Actual -SyncWindow 0 -ErrorAction SilentlyContinue
                    if ($null -ne $diff) { $isEqual = $false }
                }
                else {
                    if ($Actual -ne $Expected) { $isEqual = $false }
                }
                
                if ($isEqual) {
                    $strExpected = if ($Expected -is [System.Collections.IEnumerable] -and $Expected -isnot [string]) { "@(" + ($Expected -join ', ') + ")" } else { $Expected }
                    $strActual = if ($Actual -is [System.Collections.IEnumerable] -and $Actual -isnot [string]) { "@(" + ($Actual -join ', ') + ")" } else { $Actual }

                    Fail-Assertion "Expected value **not** to be equal to: $strExpected`nActual value: $strActual ($($Actual.GetType().Name))"
                }
            }
            'IsTrue' {
                if (-not $Actual) {
                    Fail-Assertion "Expected: True`nActual: $Actual ($($Actual.GetType().Name))"
                }
            }
            'IsFalse' {
                if ($Actual) {
                    Fail-Assertion "Expected: False`nActual: $Actual ($($Actual.GetType().Name))"
                }
            }
            'IsNull' {
                if ($null -ne $Actual) {
                    Fail-Assertion "Expected: `$null`nActual: $Actual ($($Actual.GetType().Name))"
                }
            }
            'IsNotNull' {
                if ($null -eq $Actual) {
                    Fail-Assertion "Expected non-null value`nActual: $Actual"
                }
            }
            'IsEmpty' {
                if ($null -eq $Actual) { return }
                if ($Actual -is [string]) {
                    if ([string]::IsNullOrEmpty($Actual) -eq $false) {
                        Fail-Assertion "Expected empty string`nActual: '$Actual'"
                    }
                }
                else {
                    $count = @($Actual | Where-Object { $_ -ne $null }).Count
                    if ($count -ne 0) {
                        $strActual = if ($Actual -is [System.Collections.IEnumerable] -and $Actual -isnot [string]) { "@(" + ($Actual -join ', ') + ")" } else { $Actual }
                        Fail-Assertion "Expected empty collection`nActual count: $count`nActual items: $strActual"
                    }
                }
            }
            'IsNotEmpty' {
                if ($null -eq $Actual) {
                    Fail-Assertion "Expected non-empty value`nActual is null"
                }
                elseif ($Actual -is [string]) {
                    if ([string]::IsNullOrEmpty($Actual)) {
                        Fail-Assertion "Expected non-empty string`nActual is empty/null"
                    }
                }
                else {
                    $count = @($Actual | Where-Object { $_ -ne $null }).Count
                    if ($count -eq 0) {
                        $strActual = if ($Actual -is [System.Collections.IEnumerable] -and $Actual -isnot [string]) { "@(" + ($Actual -join ', ') + ")" } else { $Actual }
                        Fail-Assertion "Expected non-empty collection`nActual count: $count`nActual items: $strActual"
                    }
                }
            }
            'Contain' {
                if ($Actual -is [string]) {
                    if (-not ($Actual -like "*$Contain*")) {
                        Fail-Assertion "Expected string to contain: '$Contain'`nActual string: '$Actual'"
                    }
                }
                else {
                    if (-not (@($Actual) -contains $Contain)) {
                        Fail-Assertion "Expected collection to contain: '$Contain'`nActual collection: $($Actual -join ', ')"
                    }
                }
            }
            'Match' {
                if (-not ($Actual -is [string])) {
                    Fail-Assertion "Match requires a string Actual value`nActual type: $($Actual.GetType().FullName)"
                }
                elseif (-not ([regex]::IsMatch([string]$Actual, [string]$Match))) {
                    Fail-Assertion "Expected string to match regex: '$Match'`nActual string: '$Actual'"
                }
            }
            'NotContain' {
                if ($Actual -is [string]) {
                    if ($Actual -like "*$NotContain*") {
                        Fail-Assertion "Expected string **not** to contain: '$NotContain'`nActual string: '$Actual'"
                    }
                }
                else {
                    if (@($Actual) -contains $NotContain) {
                        Fail-Assertion "Expected collection **not** to contain: '$NotContain'`nActual collection: $($Actual -join ', ')"
                    }
                }
            }
            'IsGreaterThan' {
                try {
                    if (-not ([decimal]$Actual -gt [decimal]$IsGreaterThan)) {
                        Fail-Assertion "Expected value > $IsGreaterThan`nActual value: $Actual"
                    }
                }
                catch {
                    Fail-Assertion "IsGreaterThan requires numeric-compatible types`nActual: $Actual"
                }
            }
            'IsLessThan' {
                try {
                    if (-not ([decimal]$Actual -lt [decimal]$IsLessThan)) {
                        Fail-Assertion "Expected value < $IsLessThan`nActual value: $Actual"
                    }
                }
                catch {
                    Fail-Assertion "IsLessThan requires numeric-compatible types`nActual: $Actual"
                }
            }
            'IsGreaterOrEqualTo' {
                try {
                    if (-not ([decimal]$Actual -ge [decimal]$IsGreaterOrEqualTo)) {
                        Fail-Assertion "Expected value >= $IsGreaterOrEqualTo`nActual value: $Actual"
                    }
                }
                catch {
                    Fail-Assertion "IsGreaterOrEqualTo requires numeric-compatible types`nActual: $Actual"
                }
            }
            'IsLessOrEqualTo' {
                try {
                    if (-not ([decimal]$Actual -le [decimal]$IsLessOrEqualTo)) {
                        Fail-Assertion "Expected value <= $IsLessOrEqualTo`nActual value: $Actual"
                    }
                }
                catch {
                    Fail-Assertion "IsLessOrEqualTo requires numeric-compatible types`nActual: $Actual"
                }
            }
            'HasCount' {
                $normalizedCollection = @($Actual)
                $count = $normalizedCollection.Count
                
                if ($count -ne $HasCount) {
                    Fail-Assertion "Expected count: $HasCount`nActual count: $count`nActual items: $($normalizedCollection -join ', ')"
                }
            }
            'IsWithin' {
                if ($Tolerance -lt 0) {
                    Fail-Assertion "Tolerance must is >= 0`nTolerance: $Tolerance"
                }
                try {
                    $a = [decimal]$Actual
                    $e = [decimal]$Of
                    $diff = [math]::Abs($a - $e)
                    if ($diff -gt $Tolerance) {
                        Fail-Assertion "Expected: $Of ± $Tolerance`nActual: $Actual`nDifference: $diff"
                    }
                }
                catch {
                    Fail-Assertion "IsWithin requires numeric-compatible types`nActual: $Actual"
                }
            }
            default {
                Fail-Assertion "No assertion parameter provided."
            }
        }
    }
}
