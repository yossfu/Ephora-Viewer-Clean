#!/data/data/com.termux/files/usr/bin/bash
# EPHORASL - Compilar en GitHub desde Termux
# Uso: ./compilar.sh "mi cambio"
# Debe ejecutarse desde la raiz del repo clonado
set -e

MSG="${1:-cambio desde termux $(date '+%Y-%m-%d %H:%M')}"
ART_NAME="EPHORASL-7.42-debug"
OUTDIR="./APK-SALIDA"

echo "== EPHORASL -> GitHub Actions =="
echo "Mensaje: $MSG"
echo ""

# 1. Verificar gh logueado
if ! gh auth status >/dev/null 2>&1; then
  echo "[ERROR] No hay sesion de gh. Ejecuta primero:"
  echo "  gh auth login"
  exit 1
fi

# 2. Verificar que estamos en un repo git
if [ ! -d .git ]; then
  echo "[ERROR] No hay .git aqui. Clona primero:"
  echo "  git clone https://github.com/yossfu/Ephora-Viewer-Clean.git"
  exit 1
fi

# 3. Subir cambios
git add -A
if git diff --cached --quiet; then
  echo "[INFO] Sin cambios nuevos, solo disparo workflow..."
  git push origin main || true
else
  git commit -m "$MSG"
  git push origin main
fi

echo ""
echo "Esperando que GitHub cree el run (10s)..."
sleep 10

# 4. Obtener ultimo run de la rama main
RUN_ID=$(gh run list --branch main --limit 1 --json databaseId --jq '.[0].databaseId')
if [ -z "$RUN_ID" ]; then
  echo "[ERROR] No se pudo obtener el RUN_ID"
  exit 1
fi
echo "RUN_ID=$RUN_ID"
echo "Ver: https://github.com/yossfu/Ephora-Viewer-Clean/actions/runs/$RUN_ID"
echo ""

# 5. Esperar a que termine (sale con error si falla el build)
gh run watch "$RUN_ID" --exit-status

echo ""
echo "Descargando APK..."
mkdir -p "$OUTDIR"
rm -f "$OUTDIR"/*.apk 2>/dev/null || true

# Intenta por nombre exacto, si cambia usa el primero disponible
if ! gh run download "$RUN_ID" -n "$ART_NAME" -D "$OUTDIR"; then
  echo "[INFO] Nombre exacto no encontrado, descargando primer artifact..."
  gh run download "$RUN_ID" -D "$OUTDIR"
fi

echo ""
echo "== LISTO =="
ls -lh "$OUTDIR"
echo ""
echo "Tu APK esta en: $(realpath "$OUTDIR")"
