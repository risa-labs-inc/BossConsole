<#
.SYNOPSIS
  Report Windows scheduling QoS for a browser's Chromium processes.

.DESCRIPTION
  On a hybrid CPU (this machine is a Core Ultra 7 155H: 6 P-cores + 8 E-cores +
  2 LP-E-cores) it is not enough to know that a renderer is running -- what
  matters is WHERE Windows runs it. Two mechanisms decide that:

    * PriorityClass. Chromium lowers a renderer to BelowNormal/Idle when it
      considers the page backgrounded.
    * EcoQoS (PROCESS_POWER_THROTTLING_EXECUTION_SPEED). Chromium opts
      background renderers into it, and Windows then parks them on E-cores at
      reduced clocks. This has no macOS equivalent, which is why an embedder can
      be fast on a Mac and slow here on the same engine.

  An off-screen (OFF_SCREEN rendering mode) browser is a prime candidate for
  both, because its native window is hidden by construction.

  Run this WHILE a benchmark is in flight.

.EXAMPLE
  .\probe-process-qos.ps1 -Name boss-browser
  .\probe-process-qos.ps1 -Name msedge
#>
[CmdletBinding()]
param([string]$Name = "boss-browser")

if (-not ("Qos.Native" -as [type])) {
    Add-Type -Namespace Qos -Name Native -MemberDefinition @'
[StructLayout(LayoutKind.Sequential)]
public struct PROCESS_POWER_THROTTLING_STATE {
    public uint Version;
    public uint ControlMask;
    public uint StateMask;
}
[DllImport("kernel32.dll", SetLastError = true)]
public static extern bool GetProcessInformation(IntPtr hProcess, int InformationClass,
    ref PROCESS_POWER_THROTTLING_STATE Info, int Size);
'@
}

# ProcessInformationClass::ProcessPowerThrottling
$CLASS_POWER_THROTTLING = 4
$EXECUTION_SPEED = 0x1

$rows = foreach ($p in Get-Process -Name $Name -ErrorAction SilentlyContinue) {
    $cim = Get-CimInstance Win32_Process -Filter "ProcessId=$($p.Id)" -ErrorAction SilentlyContinue
    $cmd = $cim.CommandLine
    # Chromium tags every child with --type=; the browser process has none.
    $type = if ($cmd -match '--type=([a-zA-Z-]+)') { $Matches[1] } else { "browser" }

    $state = New-Object Qos.Native+PROCESS_POWER_THROTTLING_STATE
    $state.Version = 1
    $size = [System.Runtime.InteropServices.Marshal]::SizeOf($state)
    $eco = "?"
    try {
        if ([Qos.Native]::GetProcessInformation($p.Handle, $CLASS_POWER_THROTTLING, [ref]$state, $size)) {
            # EcoQoS is ON only when the app both controls the knob and sets it.
            $controlled = ($state.ControlMask -band $EXECUTION_SPEED) -ne 0
            $throttled = ($state.StateMask -band $EXECUTION_SPEED) -ne 0
            $eco = if ($controlled -and $throttled) { "ECO (throttled)" }
                   elseif ($controlled) { "HighPerf (explicit)" }
                   else { "default" }
        }
    } catch {
        # A process can exit mid-probe, or be one we cannot open a handle to.
        $eco = "n/a"
    }

    [pscustomobject]@{
        Pid      = $p.Id
        Type     = $type
        Priority = $p.PriorityClass
        EcoQoS   = $eco
        CpuSec   = [math]::Round($p.TotalProcessorTime.TotalSeconds, 1)
        WorkingMB = [math]::Round($p.WorkingSet64 / 1MB)
    }
}

if (-not $rows) {
    Write-Warning "No '$Name' processes running -- start the browser first."
    return
}
$rows | Sort-Object Type, Pid | Format-Table -AutoSize
