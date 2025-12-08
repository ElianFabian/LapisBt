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

function Should {
    [CmdletBinding(DefaultParameterSetName = 'None')]
    param(
        [Parameter(ValueFromPipeline = $true)]
        $Actual,

        # Equality
        [Parameter(ParameterSetName = 'Be')]
        $Be,

        [Parameter(ParameterSetName = 'BeEqualTo')]
        $BeEqualTo,

        # Booleans
        [Parameter(ParameterSetName = 'BeTrue')]
        [switch] $BeTrue,

        [Parameter(ParameterSetName = 'BeFalse')]
        [switch] $BeFalse,

        # Null / Empty
        [Parameter(ParameterSetName = 'BeNull')]
        [switch] $BeNull,

        [Parameter(ParameterSetName = 'BeNotNull')]
        [switch] $BeNotNull,

        [Parameter(ParameterSetName = 'BeEmpty')]
        [switch] $BeEmpty,

        [Parameter(ParameterSetName = 'BeNotEmpty')]
        [switch] $BeNotEmpty,

        # Containment & regex
        [Parameter(ParameterSetName = 'Contain')]
        $Contain,

        [Parameter(ParameterSetName = 'Match')]
        $Match,

        # Comparisons
        [Parameter(ParameterSetName = 'BeGreaterThan')]
        $BeGreaterThan,

        [Parameter(ParameterSetName = 'BeLessThan')]
        $BeLessThan,

        [Parameter(ParameterSetName = 'BeGreaterOrEqualTo')]
        $BeGreaterOrEqualTo,

        [Parameter(ParameterSetName = 'BeLessOrEqualTo')]
        $BeLessOrEqualTo,

        # Count
        [Parameter(ParameterSetName = 'HaveCount')]
        [int] $HaveCount,

        # Tolerant numeric comparison: -BeWithin <tolerance> -Of <expected>
        [Parameter(ParameterSetName = 'BeWithin')]
        [long] $Tolerance,

        [Parameter(ParameterSetName = 'BeWithin')]
        [long] $Of
    )

    process {
        $ps = $PSCmdlet.ParameterSetName

        switch ($ps) {
            'Be' {
                if ($Actual -ne $Be) {
                    Fail-Assertion "Expected value: $Be ($Be)`nActual value: $Actual ($($Actual.GetType().Name)`)"
                }
            }

            'BeEqualTo' {
                if ($Actual -ne $BeEqualTo) {
                    Fail-Assertion "Expected value (equal to): $BeEqualTo ($BeEqualTo)`nActual value: $Actual ($($Actual.GetType().Name)`)"
                }
            }

            'BeTrue' {
                if (-not $Actual) {
                    Fail-Assertion "Expected: True`nActual: $Actual ($($Actual.GetType().Name)`)"
                }
            }

            'BeFalse' {
                if ($Actual) {
                    Fail-Assertion "Expected: False`nActual: $Actual ($($Actual.GetType().Name)`)"
                }
            }

            'BeNull' {
                if ($null -ne $Actual) {
                    Fail-Assertion "Expected: $null`nActual: $Actual ($($Actual.GetType().Name)`)"
                }
            }

            'BeNotNull' {
                if ($null -eq $Actual) {
                    Fail-Assertion "Expected non-null value`nActual: $Actual"
                }
            }

            'BeEmpty' {
                if ($Actual -is [System.Collections.IEnumerable]) {
                    $count = ($Actual | Measure-Object | Select-Object -ExpandProperty Count)
                    if ($count -ne 0) {
                        Fail-Assertion "Expected empty collection`nActual count: $count`nActual items: $($Actual -join ', ')"
                    }
                } elseif ([string]::IsNullOrEmpty([string]$Actual) -eq $false) {
                    Fail-Assertion "Expected empty string`nActual: $Actual"
                }
            }

            'BeNotEmpty' {
                if ($Actual -is [System.Collections.IEnumerable]) {
                    $count = ($Actual | Measure-Object | Select-Object -ExpandProperty Count)
                    if ($count -eq 0) {
                        Fail-Assertion "Expected non-empty collection`nActual count: 0"
                    }
                } else {
                    if ([string]::IsNullOrEmpty([string]$Actual)) {
                        Fail-Assertion "Expected non-empty string`nActual: $Actual"
                    }
                }
            }

            'Contain' {
                if ($Actual -is [string]) {
                    if (-not ($Actual -like "*$Contain*")) {
                        Fail-Assertion "Expected string to contain: '$Contain'`nActual string: '$Actual'"
                    }
                } elseif ($Actual -is [System.Collections.IEnumerable]) {
                    if (-not ($Actual -contains $Contain)) {
                        Fail-Assertion "Expected collection to contain: '$Contain'`nActual collection: $($Actual -join ', ')"
                    }
                } else {
                    Fail-Assertion "Contain not supported for type: $($Actual.GetType().FullName)"
                }
            }

            'Match' {
                if (-not ($Actual -is [string])) {
                    Fail-Assertion "Match requires a string Actual value`nActual type: $($Actual.GetType().FullName)"
                } elseif (-not ([regex]::IsMatch([string]$Actual, [string]$Match))) {
                    Fail-Assertion "Expected string to match regex: '$Match'`nActual string: '$Actual'"
                }
            }

            'BeGreaterThan' {
                try {
                    if (-not ([decimal]$Actual -gt [decimal]$BeGreaterThan)) {
                        Fail-Assertion "Expected value > $BeGreaterThan`nActual value: $Actual ($($Actual.GetType().Name)`)"
                    }
                } catch {
                    Fail-Assertion "BeGreaterThan requires numeric-compatible types`nActual: $Actual , Expected: $BeGreaterThan"
                }
            }

            'BeLessThan' {
                try {
                    if (-not ([decimal]$Actual -lt [decimal]$BeLessThan)) {
                        Fail-Assertion "Expected value < $BeLessThan`nActual value: $Actual ($($Actual.GetType().Name)`)"
                    }
                } catch {
                    Fail-Assertion "BeLessThan requires numeric-compatible types`nActual: $Actual , Expected: $BeLessThan"
                }
            }

            'BeGreaterOrEqualTo' {
                try {
                    if (-not ([decimal]$Actual -ge [decimal]$BeGreaterOrEqualTo)) {
                        Fail-Assertion "Expected value >= $BeGreaterOrEqualTo`nActual value: $Actual ($($Actual.GetType().Name)`)"
                    }
                } catch {
                    Fail-Assertion "BeGreaterOrEqualTo requires numeric-compatible types`nActual: $Actual , Expected: $BeGreaterOrEqualTo"
                }
            }

            'BeLessOrEqualTo' {
                try {
                    if (-not ([decimal]$Actual -le [decimal]$BeLessOrEqualTo)) {
                        Fail-Assertion "Expected value <= $BeLessOrEqualTo`nActual value: $Actual ($($Actual.GetType().Name)`)"
                    }
                } catch {
                    Fail-Assertion "BeLessOrEqualTo requires numeric-compatible types`nActual: $Actual , Expected: $BeLessOrEqualTo"
                }
            }

            'HaveCount' {
                if (-not ($Actual -is [System.Collections.IEnumerable])) {
                    Fail-Assertion "HaveCount expects a collection/enumerable`nActual type: $($Actual.GetType().FullName)"
                } else {
                    $count = ($Actual | Measure-Object | Select-Object -ExpandProperty Count)
                    if ($count -ne $HaveCount) {
                        Fail-Assertion "Expected collection count: $HaveCount`nActual count: $count`nActual items: $($Actual -join ', ')"
                    }
                }
            }

            'BeWithin' {
                if ($Tolerance -lt 0) {
                    Fail-Assertion "Tolerance must be >= 0`nTolerance: $Tolerance"
                }
                try {
                    $a = [decimal]$Actual
                    $e = [decimal]$Of
                    $diff = [math]::Abs($a - $e)
                    if ($diff -gt $Tolerance) {
                        Fail-Assertion "Expected: $Of ± $Tolerance`nActual: $Actual`nDifference: $diff"
                    }
                } catch {
                    Fail-Assertion "BeWithin requires numeric-compatible types`nActual: $Actual , Expected: $Of"
                }
            }

            default {
                Fail-Assertion "No assertion parameter provided. Use e.g. -Be, -BeEqualTo, -BeGreaterThan, -Contain, -BeTrue, -BeWithin, etc."
            }
        }
    }
}
