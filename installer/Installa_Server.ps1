# Borsa Watch - installazione del server su Windows (finestra visibile, passo per passo)
$ErrorActionPreference = 'Stop'
$Host.UI.RawUI.WindowTitle = 'Borsa Watch - installazione del server'
function Passo($t) { Write-Host ""; Write-Host "=== $t ===" -ForegroundColor Cyan }
function Fine($code) { Write-Host ""; Read-Host 'Premi Invio per chiudere questa finestra'; exit $code }

# serve l'amministratore (solo per aprire la porta nel firewall): lo chiedo con la finestra di Windows
$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) {
    Write-Host 'Mi serve il permesso di amministratore: premi Sì nella finestra che compare.' -ForegroundColor Yellow
    Start-Process powershell -Verb RunAs -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    exit
}

$here = Split-Path -Parent $PSCommandPath
$dest = 'C:\BorsaWatch'
$exe  = Join-Path $here 'BorsaServer.exe'
$port = 8765
$task = 'BorsaWatchServer'
try {
    if (-not (Test-Path $exe)) {
        Write-Host "Non trovo BorsaServer.exe accanto a questo file ($here)." -ForegroundColor Red
        Write-Host 'Scarica insieme BorsaServer.exe e Installa_Server.ps1 nella stessa cartella e riprova.'
        Fine 1
    }

    Passo '1/5  Copio il programma in C:\BorsaWatch'
    Get-Process BorsaServer -ErrorAction SilentlyContinue | Stop-Process -Force
    Start-Sleep 1
    New-Item -ItemType Directory -Force $dest | Out-Null
    Copy-Item $exe (Join-Path $dest 'BorsaServer.exe') -Force
    $lista = Join-Path $here 'tickers.txt'
    if (Test-Path $lista) { Copy-Item $lista (Join-Path $dest 'tickers.txt') -Force; Write-Host 'Copiata anche la lista dei titoli (tickers.txt).' }
    Write-Host 'Fatto.' -ForegroundColor Green

    Passo "2/5  Apro la porta $port nel firewall (solo per i dispositivi della tua rete di casa)"
    Get-NetFirewallRule -DisplayName 'Borsa Watch' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
    New-NetFirewallRule -DisplayName 'Borsa Watch' -Direction Inbound -Protocol TCP -LocalPort $port -Action Allow -Profile Any -RemoteAddress LocalSubnet | Out-Null
    Write-Host 'Fatto.' -ForegroundColor Green

    Passo '3/5  Faccio partire il server da solo ad ogni accesso a Windows'
    $user = (Get-CimInstance Win32_ComputerSystem).UserName
    if (-not $user) { $user = "$env:USERDOMAIN\$env:USERNAME" }
    $arg = "-NoProfile -WindowStyle Hidden -Command `"Start-Process '$dest\BorsaServer.exe' -WorkingDirectory '$dest' -WindowStyle Hidden`""
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arg
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
    Register-ScheduledTask -TaskName $task -Action $action -Trigger $trigger -Settings $settings -User $user -RunLevel Limited -Force | Out-Null
    Write-Host "Fatto: parte quando entra $user." -ForegroundColor Green

    Passo '4/5  Evitare che il PC si addormenti (consigliato)'
    Write-Host 'Se il PC va in sospensione, il server si ferma e gli avvisi non arrivano.'
    $r = Read-Host 'Disattivo la sospensione automatica quando è collegato alla corrente? (S/N)'
    if ($r -match '^[sSyY]') {
        powercfg /change standby-timeout-ac 0
        powercfg /change hibernate-timeout-ac 0
        Write-Host 'Fatto: lo schermo può spegnersi, ma il PC resta acceso.' -ForegroundColor Green
    } else { Write-Host 'Lasciato com''è.' }

    Passo '5/5  Avvio il server'
    Start-ScheduledTask -TaskName $task
    $ok = $false
    for ($i = 0; $i -lt 60 -and -not $ok; $i++) {
        Start-Sleep 1
        try { $ok = (Invoke-RestMethod "http://localhost:$port/api/ping" -TimeoutSec 3).app -eq 'Borsa Watch' } catch {}
    }
    if ($ok) {
        Write-Host 'Il server è acceso.' -ForegroundColor Green
        Write-Host ''
        Write-Host 'Ora si apre una pagina con l''indirizzo e il codice da scrivere nell''app sul telefono.' -ForegroundColor Cyan
        Start-Process "http://localhost:$port/"
    } else {
        Write-Host 'Il server non risponde. Se hai un antivirus (es. Norton), aggiungi C:\BorsaWatch\BorsaServer.exe alle eccezioni e riavvia il PC.' -ForegroundColor Red
        Fine 2
    }
    Fine 0
} catch {
    Write-Host ''
    Write-Host "Qualcosa non ha funzionato: $($_.Exception.Message)" -ForegroundColor Red
    Fine 3
}
