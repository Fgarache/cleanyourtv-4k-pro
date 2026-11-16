#!/usr/bin/env bash
# Quick one-command installer for CleanYourTV Wallpaper 4K Live
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"
TOOLS="$REPO_ROOT/tools"
VENV="$TOOLS/venv"
PY="$VENV/bin/python"

echo ""
echo "=================================================================="
echo "   CLEANYOURTV WALLPAPER 4K LIVE - INSTALADOR AUTOMATICO          "
echo "=================================================================="
echo ""

# 1. Configurar entorno Python si falta
if [ ! -x "$PY" ]; then
    echo "[1/4] Configurando entorno de herramientas Python..."
    python3 -m venv "$VENV"
    "$PY" -m pip install -r "$TOOLS/requirements.txt" --quiet
fi

# 2. Obtener o escanear IP de la TV
TV_HOST="${1:-${TV_HOST:-}}"
if [ -z "$TV_HOST" ]; then
    echo "[2/4] Buscando TV en tu red local (puerto ADB 5555)..."
    DETECTED_IP=$("$PY" -c '
import socket
def test_ip(ip):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(0.3)
    res = s.connect_ex((ip, 5555))
    s.close()
    return res == 0
found = []
for i in range(2, 60):
    for base in ["192.168.0", "192.168.1"]:
        ip = f"{base}.{i}"
        if test_ip(ip): found.append(ip); break
    if found: break
if found: print(found[0])
' || true)
    if [ -n "$DETECTED_IP" ]; then
        echo "  -> TV detectada automaticamente en: $DETECTED_IP"
        TV_HOST="$DETECTED_IP"
    else
        read -r -p "Ingresa la direccion IP de tu TV (Ejemplo: 192.168.0.8): " TV_HOST
    fi
fi
export TV_HOST

# 3. Preguntar edicion de launcher si no viene por entorno
EDITION="${LAUNCHER_EDITION:-${2:-}}"
if [ -z "$EDITION" ]; then
    echo ""
    echo "Elige que version deseas instalar:"
    echo "  [1] CleanYourTV Wallpaper 4K Live (Mod 4K con videos, espectro de audio RGB y vinilo)"
    echo "      ADVERTENCIA: EXCLUSIVO SOLO PARA TELEVISORES 4K"
    echo "  [2] CleanYourTV Original (Clasico ultraligero de fondo negro estatico)"
    read -r -p "Selecciona [1 o 2] (Por defecto: 1): " CHOICE || CHOICE="1"
    if [ "$CHOICE" = "2" ]; then
        EDITION="original"
    else
        EDITION="pro4k"
    fi
fi

# 4. Instalar APK
echo "[3/4] Instalando Launcher en la TV ($EDITION)..."
if [ "$EDITION" = "pro4k" ]; then
    APK_FILE="$REPO_ROOT/bin/launcher-pro4k.apk"
else
    APK_FILE="$REPO_ROOT/bin/launcher-original.apk"
fi

"$PY" -I "$TOOLS/b64push.py" "$APK_FILE" /data/local/tmp/tvhome.apk
"$PY" -I "$TOOLS/tv.py" 'pm install -r /data/local/tmp/tvhome.apk; rm -f /data/local/tmp/tvhome.apk'
"$PY" -I "$TOOLS/tv.py" 'am start -W -a android.intent.action.MAIN -c android.intent.category.HOME -n com.gathin.tvhome/.MainActivity' >/dev/null 2>&1 || true
"$PY" -I "$TOOLS/tv.py" 'pm grant com.gathin.tvhome android.permission.ACCESS_FINE_LOCATION 2>/dev/null; cmd package set-home-activity --user 0 com.gathin.tvhome/.MainActivity' >/dev/null 2>&1 || true

if [ "$EDITION" = "pro4k" ]; then
    "$PY" -I "$TOOLS/tv.py" 'pm grant com.gathin.tvhome android.permission.RECORD_AUDIO 2>/dev/null; cmd notification allow_listener com.gathin.tvhome/com.gathin.tvhome.GuardRevival 2>/dev/null; settings put secure screensaver_components com.gathin.tvhome/com.gathin.tvhome.ClockDream 2>/dev/null; settings put secure screensaver_enabled 1; true' >/dev/null 2>&1 || true
fi

echo "  -> Launcher instalado y configurado correctamente como Home."

# 5. Opcional transferir videos
if [ "$EDITION" = "pro4k" ] && [ -d "$REPO_ROOT/wallpaper" ]; then
    echo "[4/4] Transfiriendo videos de fondo de wallpaper/ a la TV..."
    "$REPO_ROOT/tools/push-videos.sh" "$REPO_ROOT/wallpaper" || true
fi

echo ""
echo "=================================================================="
echo "  INSTALACION COMPLETADA CON EXITO!"
echo "  Presiona el boton HOME en tu control remoto para ver el launcher."
echo "=================================================================="
echo ""
