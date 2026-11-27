# Installs PanGaze to run in the background on Windows (called by "Install PanGaze on Windows (background).bat").
$ErrorActionPreference = 'Continue'
$src  = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$dest = Join-Path $env:LOCALAPPDATA 'PanGaze'
$url  = 'http://localhost:8899'
function Test-PanGaze { try { (Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 "$url/__xp_ping").Content -match 'xp-proxy' } catch { $false } }
function Say($t) { Write-Host "  $t" }

Write-Host ""
Say "Installing PanGaze to run in the background..."
Write-Host ""

# 1. Python 3 (the py launcher from python.org first, then python)
$pyExe = $null
foreach ($try in 'py', 'python') {
  if ($pyExe) { break }
  try {
    if ($try -eq 'py') { $out = & py -3 -c "import sys; print(sys.executable)" 2>$null }
    else { $out = & python -c "import sys; print(sys.executable)" 2>$null }
    $exe = "$($out | Select-Object -Last 1)".Trim()
    if ($LASTEXITCODE -eq 0 -and $exe -and (Test-Path $exe)) { $pyExe = $exe }
  } catch {}
}
if (-not $pyExe) {
  Say "PanGaze needs Python 3, which is free. Opening the download page now."
  Say "Run the installer, tick 'Add python.exe to PATH', then run this again."
  Start-Process 'https://www.python.org/downloads/windows/'
  exit 1
}
$pythonw = Join-Path (Split-Path $pyExe) 'pythonw.exe'
if (-not (Test-Path $pythonw)) { $pythonw = $pyExe }

# 2. Stop an older background copy; make sure nothing else is using the port
Get-CimInstance Win32_Process -Filter "Name='pythonw.exe' OR Name='python.exe'" -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -and $_.CommandLine -like "*$dest*server.py*" } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 1
if (Test-PanGaze) {
  Say "PanGaze is already running in another window."
  Say "Close that window (or press Ctrl+C in it), then press Enter here."
  [void](Read-Host)
  if (Test-PanGaze) { Say "It's still running. Close it and run this installer again."; exit 1 }
}

# 3. Copy the app; your data comes across the first time only
New-Item -ItemType Directory -Force -Path $dest | Out-Null
foreach ($f in 'xtream-player.html','server.py','recorder.py','caster.py','help.html','remote.html','README.txt','PanGaze.ico','windows\open_pangaze.pyw') {
  $p = Join-Path $src $f
  if (Test-Path $p) { Copy-Item $p (Join-Path $dest (Split-Path $f -Leaf)) -Force }
}
Get-ChildItem -Path $src -Filter 'xp-*.json' -ErrorAction SilentlyContinue | ForEach-Object {
  $t = Join-Path $dest $_.Name
  if (-not (Test-Path $t)) { Copy-Item $_.FullName $t }
}
Get-ChildItem $dest -File | Unblock-File -ErrorAction SilentlyContinue

# 4. Start with Windows (hidden), plus a PanGaze icon on the desktop and in the Start menu
$ws = New-Object -ComObject WScript.Shell
$log = Join-Path $dest 'PanGaze.log'
function New-Link($path, $target, $arguments, $icon, $style) {
  $l = $ws.CreateShortcut($path)
  $l.TargetPath = $target; $l.Arguments = $arguments; $l.WorkingDirectory = $dest
  if ($icon) { $l.IconLocation = "$icon,0" }
  if ($style) { $l.WindowStyle = $style }
  $l.Save()
}
$serverArgs = "`"$dest\server.py`" --lan --no-open --log `"$log`""
New-Link (Join-Path ([Environment]::GetFolderPath('Startup')) 'PanGaze (background).lnk') $pythonw $serverArgs "$dest\PanGaze.ico" 7
$openArgs = "`"$dest\open_pangaze.pyw`""
New-Link (Join-Path ([Environment]::GetFolderPath('Desktop')) 'PanGaze.lnk') $pythonw $openArgs "$dest\PanGaze.ico" $null
$menu = Join-Path ([Environment]::GetFolderPath('Programs')) 'PanGaze.lnk'
New-Link $menu $pythonw $openArgs "$dest\PanGaze.ico" $null

# 5. Start it now, wait for it, then open it
Start-Process -FilePath $pythonw -ArgumentList $serverArgs -WorkingDirectory $dest -WindowStyle Hidden
for ($i = 0; $i -lt 30 -and -not (Test-PanGaze); $i++) { Start-Sleep -Milliseconds 500 }
if (Test-PanGaze) {
  Say "Done. PanGaze is now running in the background and will start"
  Say "by itself whenever you sign in to Windows."
  Write-Host ""
  Say "Open it any time with the PanGaze icon on your desktop or in the"
  Say "Start menu (it opens in Firefox, or your default browser), or at $url"
  Write-Host ""
  Say "If Windows asks whether Python may use the network, allow it on"
  Say "Private networks so your phone and TV can reach PanGaze."
  Say "To remove the background version, run 'Uninstall PanGaze on Windows.bat'."
  Start-Process -FilePath $pythonw -ArgumentList $openArgs -WorkingDirectory $dest
} else {
  Say "PanGaze didn't start. The log may say why: $log"
  if (Test-Path $log) { Get-Content $log -Tail 15 }
}
