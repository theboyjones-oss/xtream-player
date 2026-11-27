# Stops PanGaze running in the background on Windows and removes its icons. Keeps your data.
$dest = Join-Path $env:LOCALAPPDATA 'PanGaze'
Get-CimInstance Win32_Process -Filter "Name='pythonw.exe' OR Name='python.exe'" -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -and $_.CommandLine -like "*$dest*server.py*" } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
foreach ($p in @(
  (Join-Path ([Environment]::GetFolderPath('Startup')) 'PanGaze (background).lnk'),
  (Join-Path ([Environment]::GetFolderPath('Desktop')) 'PanGaze.lnk'),
  (Join-Path ([Environment]::GetFolderPath('Programs')) 'PanGaze.lnk'))) {
  if (Test-Path $p) { Remove-Item $p -Force }
}
Write-Host ""
Write-Host "  PanGaze no longer runs in the background, and its icons are removed."
Write-Host "  You can still start it by hand with 'Start PanGaze (Windows).bat'."
Write-Host "  Your saved data is kept in: $dest"
