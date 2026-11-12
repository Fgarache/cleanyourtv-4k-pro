#!/usr/bin/env bash
# Sube las carpetas de videos categorizados al televisor en /sdcard/Android/data/com.gathin.tvhome/files/media/
# Uso: TV_HOST=192.168.1.50 ./tools/push-videos.sh /ruta/a/carpeta/wallpaper
set -euo pipefail

FOLDER="${1:-}"
if [ -z "${TV_HOST:-}" ]; then
    echo "Error: define TV_HOST=<IP_DEL_TV>" >&2
    exit 1
fi
if [ -z "$FOLDER" ] || [ ! -d "$FOLDER" ]; then
    echo "Uso: TV_HOST=<IP_DEL_TV> $0 /ruta/a/carpeta/wallpaper" >&2
    exit 1
fi

REPO="$(cd "$(dirname "$0")/.." && pwd)"
PY="$REPO/tools/venv/bin/python"
[ -x "$PY" ] || PY="python3"
TV_PY="$REPO/tools/tv.py"

REMOTE_BASE="/sdcard/Android/data/com.gathin.tvhome/files/media"
"$PY" "$TV_PY" "mkdir -p '$REMOTE_BASE'"

for cat_dir in "$FOLDER"/*/; do
    [ -d "$cat_dir" ] || continue
    cat="$(basename "$cat_dir")"
    remote_cat="$REMOTE_BASE/$cat"
    "$PY" "$TV_PY" "mkdir -p '$remote_cat'"
    for v in "$cat_dir"*.mp4 "$cat_dir"*.webm "$cat_dir"*.mkv; do
        [ -f "$v" ] || continue
        fname="$(basename "$v")"
        echo "Subiendo $cat/$fname..."
        "$PY" "$TV_PY" --push "$v" "$remote_cat/$fname"
    done
done
echo "Todos los videos fueron subidos correctamente al TV."
