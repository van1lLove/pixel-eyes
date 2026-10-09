#!/usr/bin/env bash
# Проверки чистой части ядра Pixel Eyes на ПК, без Android. Нужны JDK 11+ и Python 3.
# Запуск: bash test/run.sh
set -e
HERE="$(cd "$(dirname "$0")/.." && (pwd -W 2>/dev/null || pwd))"
if [ -f "$HERE/build.local.sh" ]; then
  . "$HERE/build.local.sh"
fi
PY="${PYTHON:-$(command -v python || command -v python3)}"
JAVA_BIN=""
if [ -n "$JAVA_HOME" ]; then
  JAVA_BIN="${JAVA_HOME%[/\\]}/bin/"
fi
OUT="$HERE/test/out"
"$PY" "$HERE/tools/gen_builtins.py" >/dev/null
rm -rf "$OUT" && mkdir -p "$OUT"
S="$HERE/src/com/pixeleyes"
"${JAVA_BIN}javac" -encoding UTF-8 -d "$OUT" "$S/MiniJson.java" "$S/Skin.java" "$S/Face.java" "$S/Renderer.java" \
  "$S/Brain.java" "$S/Builtins.java" "$S/Mood.java" "$HERE/test/CoreTest.java" "$HERE/test/RenderSheet.java"
"${JAVA_BIN}java" -Dstdout.encoding=UTF-8 -cp "$OUT" com.pixeleyes.CoreTest "$HERE/test/mood_cases.txt"
