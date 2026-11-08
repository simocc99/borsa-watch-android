# Borsa Watch - disinstalla il server da Windows
$Host.UI.RawUI.WindowTitle = 'Borsa Watch - disinstallazione del server'
$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) {
    Start-Process powershell -Verb RunAs -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    exit
}
Write-Host 'Fermo il server...'
Get-Process BorsaServer -ErrorAction SilentlyContinue | Stop-Process -Force
Write-Host 'Tolgo l''avvio automatico e la regola del firewall...'
Unregister-ScheduledTask -TaskName 'BorsaWatchServer' -Confirm:$false -ErrorAction SilentlyContinue
Get-NetFirewallRule -DisplayName 'Borsa Watch' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
Write-Host 'Cancello C:\BorsaWatch...'
Remove-Item 'C:\BorsaWatch' -Recurse -Force -ErrorAction SilentlyContinue
$dati = Join-Path $env:APPDATA 'BorsaWatch'
if (Test-Path $dati) {
    $r = Read-Host "Cancello anche i dati (titoli, avvisi, storico) in $dati ? (S/N)"
    if ($r -match '^[sSyY]') { Remove-Item $dati -Recurse -Force; Write-Host 'Dati cancellati.' }
}
Write-Host 'Fatto. La sospensione automatica del PC, se l''avevi disattivata, resta com''è.' -ForegroundColor Green
Read-Host 'Premi Invio per chiudere'
