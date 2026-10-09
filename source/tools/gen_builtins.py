"""Встроенные глаза из eyes/skins/*.json в src/com/pixeleyes/Builtins.java."""
import io
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
ORDER = ["cartoon", "cartoon_pink", "anime", "kawaii", "cat", "retro", "robot", "sleepy", "grumpy",
         "demon", "bloodshot", "void", "cyclops", "swarm"]
DEFAULT = "cartoon"


def main():
    parts = []
    for sid in ORDER:
        path = os.path.join(ROOT, "skins", sid + ".json")
        text = io.open(path, encoding="utf-8").read()
        json.loads(text)
        # компактно: строки сеток на ПК читаемы в файлах, в ядре место дороже
        compact = json.dumps(json.loads(text), ensure_ascii=False, separators=(",", ":"))
        lit = compact.replace("\\", "\\\\").replace('"', '\\"')
        parts.append('            "' + lit + '"')
    java = (
        "package com.pixeleyes;\n\n"
        "/** Встроенные глаза. Файл собирается из eyes/skins скриптом tools/gen_builtins.py, руками не править. */\n"
        "final class Builtins {\n\n"
        f"    static final int DEFAULT = {ORDER.index(DEFAULT)};\n\n"
        "    static final String[] JSON = {\n" + ",\n".join(parts) + ",\n    };\n\n"
        "    private Builtins() {\n    }\n}\n"
    )
    out = os.path.join(ROOT, "src", "com", "pixeleyes", "Builtins.java")
    io.open(out, "w", encoding="utf-8", newline="\n").write(java)
    print("встроенных глаз:", len(ORDER))


if __name__ == "__main__":
    main()
