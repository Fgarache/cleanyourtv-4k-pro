param (
    [Parameter(Position=0)]
    [string]$TvHost,

    [Parameter(Position=1)]
    [ValidateSet("pro4k", "original")]
    [string]$Edition,

    [Parameter(Mandatory=$false)]
    [string]$VideosFolder
)

$ErrorActionPreference = "Stop"
$RepoRoot = $PSScriptRoot

Write-Host ""
Write-Host "==================================================================" -ForegroundColor Cyan
Write-Host "   CLEANYOURTV WALLPAPER 4K LIVE - INSTALADOR AUTOMATICO          " -ForegroundColor Cyan
Write-Host "==================================================================" -ForegroundColor Cyan
Write-Host ""

# 1. Configurar o detectar entorno Python
$Tools = Join-Path $RepoRoot "tools"
$Venv = Join-Path $Tools "venv"
$Py = Join-Path $Venv "Scripts\python.exe"

if (-not (Test-Path $Py)) {
    Write-Host "[1/5] Configurando entorno de herramientas Python..." -ForegroundColor Yellow
    $SystemPy = (Get-Command python -ErrorAction SilentlyContinue)
    if (-not $SystemPy) {
        $SystemPy = (Get-Command py -ErrorAction SilentlyContinue)
    }
    if (-not $SystemPy) {
        Write-Error "No se encontro Python instalado en el sistema. Instala Python 3 desde python.org y vuelve a ejecutar."
        exit 1
    }
    & $SystemPy.Source -m venv $Venv
    & $Py -m pip install -r (Join-Path $Tools "requirements.txt") --quiet
    Write-Host "  -> Entorno configurado correctamente." -ForegroundColor Green
} else {
    Write-Host "[1/5] Entorno Python detectado." -ForegroundColor Green
}

# 2. Obtener o escanear IP de la TV
if ([string]::IsNullOrWhiteSpace($TvHost)) {
    Write-Host "[2/5] Buscando TV en tu red local (puerto ADB 5555)..." -ForegroundColor Yellow
    $ScanScript = @"
import socket
def test_ip(ip):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(0.3)
    res = s.connect_ex((ip, 5555))
    s.close()
    return res == 0
found = []
for i in range(2, 60):
    ip = f'192.168.0.{i}'
    if test_ip(ip): found.append(ip)
    ip2 = f'192.168.1.{i}'
    if test_ip(ip2): found.append(ip2)
if found:
    print(found[0])
"@
    $DetectedIp = (& $Py -c $ScanScript).Trim()
    if (-not [string]::IsNullOrWhiteSpace($DetectedIp)) {
        Write-Host "  -> TV detectada automaticamente en: $DetectedIp" -ForegroundColor Green
        $TvHost = $DetectedIp
    } else {
        $TvHost = Read-Host "Ingresa la direccion IP de tu TV (Ejemplo: 192.168.0.8)"
    }
}
$env:TV_HOST = $TvHost
Write-Host "  -> Conectando con la TV en: $TvHost" -ForegroundColor Cyan

# 3. Preguntar edicion de launcher si no viene por parametro
if ([string]::IsNullOrWhiteSpace($Edition)) {
    Write-Host ""
    Write-Host "Elige que version deseas instalar:" -ForegroundColor Yellow
    Write-Host "  [1] CleanYourTV Wallpaper 4K Live (Mod 4K con videos, espectro de audio RGB y vinilo)"
    Write-Host "      ADVERTENCIA: EXCLUSIVO SOLO PARA TELEVISORES 4K" -ForegroundColor Red
    Write-Host "  [2] CleanYourTV Original (Clasico ultraligero de fondo negro estatico)"
    $Choice = Read-Host "Selecciona [1 o 2] (Por defecto: 1)"
    if ($Choice -eq "2") {
        $Edition = "original"
    } else {
        $Edition = "pro4k"
    }
}

# 4. Instalar APK precompilado
Write-Host "[3/5] Instalando Launcher en la TV..." -ForegroundColor Yellow
$ApkPath = if ($Edition -eq "pro4k") {
    Join-Path $RepoRoot "bin\launcher-pro4k.apk"
} else {
    Join-Path $RepoRoot "bin\launcher-original.apk"
}

if (-not (Test-Path $ApkPath)) {
    # Fallback compilar si no existe bin
    Write-Host "Compilando launcher desde codigo fuente..." -ForegroundColor Yellow
    powershell -ExecutionPolicy Bypass -File (Join-Path $RepoRoot "launcher\build-and-install.ps1") -TvHost $TvHost -Edition $Edition
} else {
    # Usar b64push para instalacion instantanea
    & $Py -I (Join-Path $Tools "b64push.py") $ApkPath "/data/local/tmp/tvhome.apk"
    $InstallOut = & $Py -I (Join-Path $Tools "tv.py") "pm install -r /data/local/tmp/tvhome.apk; rm -f /data/local/tmp/tvhome.apk"
    Write-Host $InstallOut

    # Iniciar y dar permisos
    & $Py -I (Join-Path $Tools "tv.py") "am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n com.gathin.tvhome/.MainActivity" | Out-Null
    & $Py -I (Join-Path $Tools "tv.py") "pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null; cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity" | Out-Null

    if ($Edition -eq "pro4k") {
        & $Py -I (Join-Path $Tools "tv.py") "pm grant com.gathin.tvhome android.permission.RECORD_AUDIO 2>/dev/null; true" | Out-Null
        & $Py -I (Join-Path $Tools "tv.py") "cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.GuardRevival 2>/dev/null; true" | Out-Null
        & $Py -I (Join-Path $Tools "tv.py") "settings put secure screensaver_components com.gathin.tvhome/com.gathin.tvhome.ClockDream 2>/dev/null; settings put secure screensaver_enabled 1; true" | Out-Null
    }
    Write-Host "  -> Launcher instalado y configurado correctamente como Home." -ForegroundColor Green
}

# 5. Opcional: Subir videos si se especifico o si existen en wallpaper/
if ($Edition -eq "pro4k") {
    $LocalWallpaper = Join-Path $RepoRoot "wallpaper"
    $TargetVideos = if (-not [string]::IsNullOrWhiteSpace($VideosFolder) -and (Test-Path $VideosFolder)) {
        $VideosFolder
    } elseif (Test-Path $LocalWallpaper) {
        $LocalWallpaper
    } else {
        $null
    }

    if ($TargetVideos) {
        $VidCount = (Get-ChildItem -Path $TargetVideos -Filter "*.mp4" -Recurse -ErrorAction SilentlyContinue).Count
        if ($VidCount -gt 0) {
            Write-Host "[4/5] Transfiriendo $VidCount videos de fondo a la TV..." -ForegroundColor Yellow
            powershell -ExecutionPolicy Bypass -File (Join-Path $Tools "push-videos.ps1") -TvHost $TvHost -Folder $TargetVideos
            Write-Host "  -> Videos transferidos correctamente a la TV." -ForegroundColor Green
        }
    }
}

Write-Host ""
Write-Host "==================================================================" -ForegroundColor Green
Write-Host "  INSTALACION COMPLETADA CON EXITO!" -ForegroundColor Green
Write-Host "  Presiona el boton HOME en tu control remoto para ver el launcher." -ForegroundColor Green
Write-Host "==================================================================" -ForegroundColor Green
Write-Host ""
