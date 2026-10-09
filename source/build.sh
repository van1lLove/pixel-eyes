#!/usr/bin/env bash
# Сборка Pixel Eyes: skins/*.json -> Builtins.java, src/*.java -> classes.dex -> base64 в плагин.
# Нужны JDK 11+, Android SDK (platforms/*/android.jar и build-tools/*/lib/d8.jar) и Python 3.
# Пути берутся из JAVA_HOME и ANDROID_HOME (или ANDROID_SDK_ROOT).
# Запуск: bash build.sh, готовый плагин: out/pixel_eyes.plugin
set -e
HERE="$(cd "$(dirname "$0")" && (pwd -W 2>/dev/null || pwd))"
# свои пути и шаги после сборки, в репозиторий не входит
if [ -f "$HERE/build.local.sh" ]; then
  . "$HERE/build.local.sh"
fi

PY="${PYTHON:-$(command -v python || command -v python3)}"
JAVA_BIN=""
if [ -n "$JAVA_HOME" ]; then
  JAVA_BIN="${JAVA_HOME%[/\\]}/bin/"
fi
SDK="${ANDROID_HOME:-$ANDROID_SDK_ROOT}"
if [ -z "$SDK" ]; then
  echo "задайте ANDROID_HOME: путь к Android SDK" >&2
  exit 1
fi
ANDROID_JAR="$(ls -d "$SDK"/platforms/*/android.jar | sort -V | tail -1)"
D8_JAR="$(ls -d "$SDK"/build-tools/*/lib/d8.jar | sort -V | tail -1)"
OUT="$HERE/out"
PLUGIN="$OUT/pixel_eyes.plugin"

"$PY" "$HERE/tools/gen_builtins.py"
rm -rf "$OUT" && mkdir -p "$OUT/classes"
"${JAVA_BIN}javac" -encoding UTF-8 -source 11 -target 11 -Xlint:-options \
  -cp "$ANDROID_JAR" -d "$OUT/classes" "$HERE"/src/com/pixeleyes/*.java
mapfile -t CLASSES < <(find "$OUT/classes" -name '*.class')
"${JAVA_BIN}java" -cp "$D8_JAR" com.android.tools.r8.D8 \
  --release --min-api 24 --lib "$ANDROID_JAR" --output "$OUT" "${CLASSES[@]}"
"$PY" -c "import base64,sys; d=open(sys.argv[1],'rb').read(); open(sys.argv[2],'w').write(base64.b64encode(d).decode())" \
  "$OUT/classes.dex" "$OUT/classes.dex.b64"
echo "DEX: $(wc -c < "$OUT/classes.dex") байт"

"$PY" - "$HERE/pixel_eyes.template.py" "$OUT/classes.dex.b64" "$PLUGIN" << 'PY'
import io, sys
tpl, b64, out = sys.argv[1:4]
s = io.open(tpl, encoding="utf-8").read()
data = io.open(b64, encoding="ascii").read().strip()
assert s.count('"@@DEX@@"') == 1, "метка ядра в шаблоне не найдена"
s = s.replace('"@@DEX@@"', '"' + data + '"', 1)
io.open(out, "w", encoding="utf-8", newline="\n").write(s)
print("плагин:", out, len(s), "символов")
PY
"$PY" -m py_compile "$PLUGIN" && echo "плагин компилируется"

if declare -F after_build >/dev/null; then
  after_build
fi
