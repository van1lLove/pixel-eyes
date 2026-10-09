"""Глаза для каталога (их нет среди встроенных): eyes/repo/skins/*.json и index.json.
Запуск: python eyes/tools/make_catalog.py
"""
import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from make_skins import dump, grid, veins  # noqa: E402

REPO = os.path.join(HERE, "..", "repo")
if not os.path.isdir(REPO):
    # в опубликованном репозитории исходники лежат в source/ внутри сайта
    REPO = os.path.join(HERE, "..", "..")
CATALOG = []


def skin(**kw):
    s = {"format": 1}
    s.update(kw)
    s.setdefault("author", "@van1lLove")
    for key in ("eye", "iris", "highlight"):
        if key in s:
            grid(s[key], f"{s['id']}.{key}")
    for name, rows in s.get("frames", {}).items():
        grid(rows, f"{s['id']}.frames.{name}")
    CATALOG.append(s)


skin(
    id="alien", name="Пришелец", description="Огромные чёрные миндалевидные глаза с отблеском",
    tags=["забавные", "космос"],
    palette={"#": "#0b0f0a", "k": "#050806", "g": "#1d2b22", "h": "#d8ffe6", "e": "#8fe3a6"},
    eye=[
        "......####......",
        "....##kkkk##....",
        "..##kkkkkkkk##..",
        ".#kkkkkkkkkkkk#.",
        "#kkkkkkkkkkkkkk#",
        "#kkkkkkkkkkkkkk#",
        ".#kkkkkkkkkkkk#.",
        "..#kkkkkkkkkk#..",
        "...##kkkkkk##...",
        ".....######.....",
    ],
    sclera="k",
    iris=["gg", "gg"],
    highlight=["hhe", "he."], highlight_offset=[-2, -2],
    lid="#5f7a52", lash="#0b0f0a", travel=[4, 2],
    layout={"count": 2, "gap": 2, "mirror": True},
    behavior={"blink": [4, 9], "style": "snap", "stare": 0.6, "speed": 0.8},
)

OWL = [
    "...######...",
    ".##oooooo##.",
    "#ooyyyyyyoo#",
    "#oyyyyyyyyo#",
    "#oyyyyyyyyo#",
    "#oyyyyyyyyo#",
    "#oyyyyyyyyo#",
    "#ooyyyyyyoo#",
    ".##oooooo##.",
    "...######...",
]
skin(
    id="owl", name="Сова", description="Круглые жёлтые совиные глаза в оранжевой оправе",
    tags=["милые", "животные"],
    palette={"#": "#3a2412", "o": "#e07b28", "y": "#ffd23f", "p": "#140c06", "h": "#ffffff"},
    eye=OWL, sclera="y",
    iris=[".pppp.", "pppppp", "pppppp", "pppppp", ".pppp."], pupil="p",
    highlight=["hh", "h."], highlight_offset=[-1, -1],
    lid="#a8682e", lash="#3a2412", travel=[1, 1],
    layout={"count": 2, "gap": 1, "mirror": True},
    behavior={"blink": [3, 9], "style": "snap", "stare": 0.5, "speed": 0.9},
)

ZOMBIE = [
    "...######...",
    ".##wwwwww##.",
    "#wwwwwwwwww#",
    "#wwwwwwwwww#",
    "#wwwwwwwwww#",
    "#wwwwwwwwww#",
    ".#wwwwwwww#.",
    "..########..",
]
ZOMBIE = veins(ZOMBIE, [(2, 2), (3, 3), (9, 4), (8, 5), (4, 6)])
skin(
    id="zombie", name="Зомби", description="Мутные полуприкрытые глаза, моргают и дёргаются вразнобой",
    tags=["жуткие", "забавные"],
    palette={"#": "#1e2a16", "w": "#cfd8b0", "v": "#7a8f3a", "i": "#5c6b2a", "p": "#141a0c"},
    eye=ZOMBIE, sclera="wv",
    iris=[".i.", "ipi", ".i."], pupil="p",
    lid="#6f8a4a", lash="#1e2a16", travel=[3, 2],
    layout={"eyes": [{"x": 0, "y": 1}, {"x": 14, "y": 0, "mirror": True, "scale": 1}]},
    behavior={"blink": [2, 6], "style": "twitch", "twitch": 0.35, "open": 0.7, "speed": 0.6,
              "independent": True, "creepy": True},
)

NEON_ROW = "#kkkkkkkkkkkk#"
skin(
    id="neon", name="Неон", description="Розово-голубые светящиеся глаза в стиле ретровейв",
    tags=["техника", "яркие"],
    palette={"#": "#ff2fb4", "k": "#100318", "c": "#22f0ff", "C": "#e8fdff", "m": "#7a1bff"},
    eye=[".############.", NEON_ROW, NEON_ROW, NEON_ROW, NEON_ROW, NEON_ROW, NEON_ROW, NEON_ROW,
         ".############."],
    sclera="k",
    iris=[".cc.", "cCCc", "cCCc", "cmmc", ".cc."],
    lid="#100318", lash="#ff2fb4", travel=[4, 2],
    layout={"count": 2, "gap": 3, "mirror": True},
    behavior={"blink": [2.5, 6], "style": "snap", "speed": 1.5},
)

skin(
    id="frog", name="Лягушка", description="Выпученные лягушачьи глаза с горизонтальным зрачком",
    tags=["милые", "животные"],
    palette={"#": "#1f3d14", "g": "#4caf3a", "y": "#e8e070", "p": "#101010", "h": "#ffffff"},
    eye=[
        "...######...",
        ".##gggggg##.",
        "#ggyyyyyygg#",
        "#gyyyyyyyyg#",
        "#gyyyyyyyyg#",
        "#gyyyyyyyyg#",
        "#ggyyyyyygg#",
        ".##gggggg##.",
        "...######...",
    ],
    sclera="y",
    iris=["pppppp", "pppppp"], pupil="p",
    highlight=["h"], highlight_offset=[-2, -2], highlight_fixed=True,
    lid="#4caf3a", lash="#1f3d14", travel=[1, 2],
    layout={"count": 2, "gap": 2, "mirror": True},
    behavior={"blink": [1.5, 4], "style": "snap", "stare": 0.4},
)

skin(
    id="ghost", name="Призрак", description="Пустые белые глаза-дыры, плывут за пальцем",
    tags=["жуткие"],
    palette={"#": "#2a2f3a", "w": "#f4f7ff", "k": "#0a0c12"},
    eye=[
        "..####..",
        ".#wwww#.",
        "#wwwwww#",
        "#wwwwww#",
        "#wwwwww#",
        "#wwwwww#",
        "#wwwwww#",
        ".#wwww#.",
        "..####..",
    ],
    sclera="w",
    iris=[".kk.", "kkkk", "kkkk", "kkkk", ".kk."],
    lid="none", lash="#2a2f3a", travel=[1, 2],
    layout={"count": 2, "gap": 4, "mirror": True},
    behavior={"blink": [5, 12], "style": "smooth", "speed": 0.4, "stare": 0.7, "creepy": True},
)


def main():
    out = os.path.join(REPO, "skins")
    os.makedirs(out, exist_ok=True)
    entries = []
    for s in CATALOG:
        io.open(os.path.join(out, s["id"] + ".json"), "w", encoding="utf-8", newline="\n").write(dump(s))
        entries.append({"id": s["id"], "name": s["name"], "author": s["author"], "description": s["description"],
                        "tags": s["tags"], "url": "skins/" + s["id"] + ".json",
                        "preview": "previews/" + s["id"] + ".png"})
    index = {"format": 1, "name": "Pixel Eyes", "skins": entries}
    io.open(os.path.join(REPO, "index.json"), "w", encoding="utf-8", newline="\n").write(
        json.dumps(index, ensure_ascii=False, indent=2) + "\n")
    print("в каталоге:", len(entries))


if __name__ == "__main__":
    main()
