# Sube las carpetas de videos categorizados (Animals, Anime, Faces, Game, Girls, Music, Nature, Navidad)
# al televisor en /sdcard/Android/data/com.gathin.tvhome/files/media/
#
# Uso: .\tools\push-videos.ps1 -TvHost 192.168.1.50 -Folder "C:\Ruta\A\Carpeta\wallpaper"
param(
    [string]$TvHost = $env:TV_HOST,
    [Parameter(Mandatory=$true)][string]$Folder
)
$ErrorActionPreference = 'Stop'
if (-not $TvHost) { throw 'Pasa -TvHost <IP del TV> o define $env:TV_HOST' }
if (-not (Test-Path $Folder)) { throw "No existe la carpeta: $Folder" }

$env:TV_HOST = $TvHost
$repo = Split-Path -Parent $PSScriptRoot
$py = Join-Path $repo 'tools\venv\Scripts\python.exe'
$tvPy = Join-Path $repo 'tools\tv.py'
if (-not (Test-Path $py)) { $py = 'python' }

$remoteBase = '/sdcard/Android/data/com.gathin.tvhome/files/media'
& $py $tvPy "mkdir -p '$remoteBase'"

$dirs = Get-ChildItem -Path $Folder -Directory
foreach ($catDir in $dirs) {
    $cat = $catDir.Name
    $remoteCat = "$remoteBase/$cat"
    & $py $tvPy "mkdir -p '$remoteCat'"
    $videos = Get-ChildItem -Path $catDir.FullName -File | Where-Object { $_.Extension -match '^\.(mp4|webm|mkv)$' } | Sort-Object Name
    foreach ($v in $videos) {
        $remoteFile = "$remoteCat/$($v.Name)"
        Write-Host "Subiendo $cat/$($v.Name)..." -ForegroundColor Cyan
        & $py $tvPy --push $v.FullName $remoteFile
    }
}
Write-Host "Todos los videos fueron subidos correctamente al TV." -ForegroundColor Green
