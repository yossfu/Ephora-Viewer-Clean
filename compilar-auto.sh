#!/data/data/com.termux/files/usr/bin/bash
# EPHORASL - Auto: aplica ZIP + push + compila + descarga APK
# Uso:
#   ./compilar-auto.sh "mensaje del cambio"
#   ./compilar-auto.sh "mensaje" /ruta/al/zip  (opcional)
set -e

MSG="${1:-cambio desde termux $(date '+%Y-%m-%d %H:%M')}"
ZIP_ARG="$2"
ART_NAME="EPHORASL-7.42-debug"
OUTDIR="./APK-SALIDA"

echo "== EPHORASL AUTO =="
echo "Mensaje: $MSG"

# 0. Estamos en repo?
if [ ! -d .git ]; then
  echo "[ERROR] Ejecuta esto dentro de ~/Ephora-Viewer-Clean"
  exit 1
fi
if ! gh auth status >/dev/null 2>&1; then
  echo "[ERROR] Haz primero: gh auth login"
  exit 1
fi

# 1. Localizar ZIP
ZIP=""
if [ -n "$ZIP_ARG" ] && [ -f "$ZIP_ARG" ]; then
  ZIP="$ZIP_ARG"
else
  # el mas reciente que empiece con Ephora en Descargas
  ZIP=$(ls -t ~/storage/downloads/Ephora*.zip 2>/dev/null | head -n 1 || true)
fi

if [ -z "$ZIP" ] || [ ! -f "$ZIP" ]; then
  echo "[INFO] No se encontro ZIP nuevo en Descargas, sigo solo con lo que hay en el repo."
else
  echo "ZIP encontrado: $ZIP"
  echo "Aplicando encima del repo..."
  unzip -o "$ZIP" -d .
  echo "[OK] ZIP aplicado."
fi

# No subir basura al repo
rm -rf ./APK-SALIDA ./.tools 2>/dev/null || true

# 2. Push
git add -A
if git diff --cached --quiet; then
  echo "[INFO] Sin cambios para commitear."
  # si no hay nada nuevo, igual intentamos push por si habia commits pendientes
  git push origin main || true
else
  git commit -m "$MSG"
  git push origin main
fi

echo ""
echo "Esperando run (10s)..."
sleep 10
RUN_ID=$(gh run list --branch main --limit 1 --json databaseId --jq '.[0].databaseId')
if [ -z "$RUN_ID" ]; then
  echo "[ERROR] No se obtuvo RUN_ID"
  exit 1
fi
echo "RUN_ID=$RUN_ID"
echo "Ver: https://github.com/yossfu/Ephora-Viewer-Clean/actions/runs/$RUN_ID"
echo ""
gh run watch "$RUN_ID" --exit-status

echo ""
echo "Descargando APK..."
mkdir -p "$OUTDIR"
rm -f "$OUTDIR"/*.apk 2>/dev/null || true
if ! gh run download "$RUN_ID" -n "$ART_NAME" -D "$OUTDIR"; then
  echo "[INFO] Nombre exacto no hallado, bajando todo..."
  gh run download "$RUN_ID" -D "$OUTDIR"
fi

echo ""
echo "== LISTO =="
ls -lh "$OUTDIR" || true
