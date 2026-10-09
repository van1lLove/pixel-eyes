"""Встроенные глаза: пиксельные сетки здесь, на выходе eyes/skins/*.json.

Скрипт проверяет, что строки одной сетки одинаковой длины, и пишет JSON в том
же виде, в каком его пишут люди: по строке сетки на строку файла.
Запуск: python eyes/tools/make_skins.py
"""
import io
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "skins")
AUTHOR = "@van1lLove"


def grid(rows, name):
    w = len(rows[0])
    for i, r in enumerate(rows):
        if len(r) != w:
            raise SystemExit(f"{name}: строка {i} длиной {len(r)}, а нужна {w}: {r!r}")
    return rows


def veins(rows, spots, ch="v"):
    out = [list(r) for r in rows]
    for x, y in spots:
        if out[y][x] in "ws":
            out[y][x] = ch
    return ["".join(r) for r in out]


def dump(skin):
    """JSON с сетками по строке на элемент."""
    def enc(v, level):
        pad = "  " * level
        if isinstance(v, dict):
            if not v:
                return "{}"
            parts = []
            for k, x in v.items():
                parts.append(f'{pad}  {json.dumps(k, ensure_ascii=False)}: {enc(x, level + 1)}')
            return "{\n" + ",\n".join(parts) + f"\n{pad}}}"
        if isinstance(v, list):
            if all(isinstance(x, str) for x in v) and v:
                inner = ",\n".join(f"{pad}  {json.dumps(x, ensure_ascii=False)}" for x in v)
                return "[\n" + inner + f"\n{pad}]"
            if all(not isinstance(x, (dict, list)) for x in v):
                return "[" + ", ".join(json.dumps(x, ensure_ascii=False) for x in v) + "]"
            inner = ",\n".join(f"{pad}  {enc(x, level + 1)}" for x in v)
            return "[\n" + inner + f"\n{pad}]"
        return json.dumps(v, ensure_ascii=False)
    return enc(skin, 0) + "\n"


SKINS = []


def skin(**kw):
    s = {"format": 1}
    s.update(kw)
    s.setdefault("author", AUTHOR)
    for key in ("eye", "iris", "highlight"):
        if key in s:
            grid(s[key], f"{s['id']}.{key}")
    for name, rows in s.get("frames", {}).items():
        grid(rows, f"{s['id']}.frames.{name}")
        if len(rows) != len(s["eye"]) or len(rows[0]) != len(s["eye"][0]):
            raise SystemExit(f"{s['id']}.frames.{name}: размер не как у eye")
    SKINS.append(s)


# ---- милые -------------------------------------------------------------------

KAWAII_EYE = [
    "...####...",
    ".##dddd##.",
    ".#dddddd#.",
    "#dddddddd#",
    "#dddddddd#",
    "#dddddddd#",
    "#dddddddd#",
    "#deeeeeed#",
    "#eeeeeeee#",
    ".#eeeeee#.",
    ".##eeee##.",
    "...####...",
]
skin(
    id="kawaii", name="Кавай", description="Тёмные чиби-глаза с большими бликами и розовым отсветом",
    tags=["милые", "аниме"],
    palette={"#": "#1a0f24", "d": "#3d2257", "e": "#55306f", "m": "#ff7ab8", "q": "#ffc2de",
             "h": "#ffffff"},
    eye=KAWAII_EYE, sclera="de",
    iris=[".mm.", "mqqm", ".mm."], iris_offset=[0, 2],
    highlight=["hhh..", "hhh..", "hhh..", ".....", "...h."], highlight_offset=[-1, -4],
    lid="#f9d9cf", lash="#1a0f24", travel=[2, 2],
    layout={"count": 2, "gap": 4, "mirror": True},
    frames={
        "happy": [
            "..........",
            "..........",
            "..........",
            "....##....",
            "...####...",
            "..##..##..",
            ".##....##.",
            ".#......#.",
            "..........",
            "..........",
            "..........",
            "..........",
        ],
        "closed": [
            "..........",
            "..........",
            "..........",
            "..........",
            "..........",
            "..........",
            ".#......#.",
            ".##....##.",
            "..######..",
            "..........",
            "..........",
            "..........",
        ],
    },
    behavior={"blink": [2, 5], "style": "smooth", "stare": 0.3, "speed": 1.1},
)

skin(
    id="cat", name="Котик", description="Жёлто-зелёные кошачьи глаза с вертикальным зрачком",
    tags=["милые", "животные"],
    palette={"#": "#1c1a14", "w": "#d6ee5f", "g": "#a4c93c", "p": "#121212", "h": "#ffffff"},
    eye=[
        "....######....",
        "..##gggggg##..",
        ".#gwwwwwwwwg#.",
        "#gwwwwwwwwwwg#",
        "#wwwwwwwwwwww#",
        "#gwwwwwwwwwwg#",
        ".#gwwwwwwwwg#.",
        "..##gggggg##..",
        "....######....",
    ],
    sclera="wg",
    iris=[".p.", ".p.", "ppp", "ppp", "ppp", ".p.", ".p."], pupil="p",
    highlight=["hh", "h."], highlight_offset=[-3, -2], highlight_fixed=True,
    lid="#4a4038", lash="#121212", travel=[3, 1],
    layout={"count": 2, "gap": 4, "mirror": True},
    behavior={"blink": [3, 8], "style": "smooth", "stare": 0.35, "speed": 1.3},
)

skin(
    id="retro", name="Ретро", description="Чёрные овалы с бликом, как в старых 8-битных играх",
    tags=["милые", "ретро"],
    palette={"k": "#101010", "h": "#ffffff"},
    eye=[
        ".kkkk.",
        "kkkkkk",
        "kkkkkk",
        "kkkkkk",
        "kkkkkk",
        "kkkkkk",
        "kkkkkk",
        "kkkkkk",
        "kkkkkk",
        ".kkkk.",
    ],
    sclera="k",
    iris=["hh", "hh", "h."], iris_offset=[0, -2],
    lid="none", lash="#101010", travel=[1, 2],
    layout={"count": 2, "gap": 4, "mirror": True},
    behavior={"blink": [2.5, 6], "style": "snap", "stare": 0.2},
)

ROBOT_FRAME_TOP = "############"
ROBOT_ROW = "#kkkkkkkkkk#"
skin(
    id="robot", name="Робот", description="Светодиодные глаза на тёмной панели",
    tags=["милые", "техника"],
    palette={"#": "#4b5363", "k": "#0a0f14", "c": "#33e6ff", "C": "#c9fbff", "r": "#ff4f8b"},
    eye=[ROBOT_FRAME_TOP] + [ROBOT_ROW] * 8 + [ROBOT_FRAME_TOP],
    sclera="k",
    iris=["cccc", "cCCc", "cCCc", "cccc"],
    lid="#0a0f14", lash="#1f7f8c", travel=[3, 2],
    layout={"count": 2, "gap": 3, "mirror": True},
    frames={
        "closed": [ROBOT_FRAME_TOP] + [ROBOT_ROW] * 4 + ["#kcccccccck#"] + [ROBOT_ROW] * 3 + [ROBOT_FRAME_TOP],
        "happy": [
            ROBOT_FRAME_TOP, ROBOT_ROW, ROBOT_ROW,
            "#kkkkcckkkk#",
            "#kkkckkckkk#",
            "#kkckkkkckk#",
            "#kckkkkkkck#",
            ROBOT_ROW, ROBOT_ROW, ROBOT_FRAME_TOP,
        ],
        "love": [
            ROBOT_FRAME_TOP, ROBOT_ROW,
            "#kkrrkkrrkk#",
            "#krrrrrrrrk#",
            "#krrrrrrrrk#",
            "#kkrrrrrrkk#",
            "#kkkrrrrkkk#",
            "#kkkkrrkkkk#",
            ROBOT_ROW, ROBOT_FRAME_TOP,
        ],
    },
    behavior={"blink": [3, 8], "style": "snap", "stare": 0.2, "speed": 1.4},
)

ROUND12 = [
    "...######...",
    ".##wwwwww##.",
    "#wwwwwwwwww#",
    "#wwwwwwwwww#",
    "#wwwwwwwwww#",
    "#wwwwwwwwww#",
    "#wwwwwwwwww#",
    ".#wwwwwwww#.",
    "..##wwww##..",
    "....####....",
]
skin(
    id="sleepy", name="Сонные", description="Тяжёлые веки, медленный взгляд, всегда хотят спать",
    tags=["забавные"],
    palette={"#": "#2a1d14", "w": "#fbf6ef", "b": "#8a5a36", "p": "#2a170c", "h": "#ffffff"},
    eye=ROUND12, sclera="w",
    iris=[".bb.", "bppb", "bppb", ".bb."], pupil="p",
    highlight=["h"], highlight_offset=[-1, -1],
    lid="#e8c4a8", lash="#5a3a26", travel=[3, 2],
    layout={"count": 2, "gap": 3, "mirror": True},
    behavior={"blink": [3, 7], "style": "smooth", "speed": 0.55, "open": 0.5, "stare": 0.2},
)

skin(
    id="grumpy", name="Злюка", description="Насупленные брови и красная радужка, всем недоволен",
    tags=["забавные"],
    palette={"#": "#1f1410", "w": "#f6efe6", "r": "#b3261e", "p": "#1a0b08", "h": "#ffffff"},
    eye=[
        "##..........",
        ".####.......",
        "...#####....",
        ".....####...",
        "..########..",
        ".#wwwwwwww#.",
        "#wwwwwwwwww#",
        "#wwwwwwwwww#",
        "#wwwwwwwwww#",
        ".#wwwwwwww#.",
        "..########..",
    ],
    sclera="w",
    iris=[".rr.", "rppr", "rppr", ".rr."], pupil="p",
    highlight=["h"], highlight_offset=[-1, -1],
    lid="#9c7a6a", lash="#2a1a14", travel=[3, 1],
    layout={"count": 2, "gap": 2, "mirror": True},
    behavior={"blink": [3, 7], "style": "snap", "slant": 0.35, "open": 0.85, "stare": 0.4},
)

skin(
    id="cartoon_pink", name="Конфетка", description="Мультяшные глаза с яркой розовой радужкой",
    tags=["милые"],
    palette={"#": "#2b1630", "w": "#ffffff", "s": "#f1e4f2", "a": "#e0479e", "b": "#ff8cc6",
             "p": "#2b1630", "h": "#ffffff"},
    eye=[
        "....####....",
        "..##wwww##..",
        ".#wwwwwwww#.",
        ".#wwwwwwww#.",
        "#wwwwwwwwww#",
        "#wwwwwwwwww#",
        "#wwwwwwwwww#",
        "#wwwwwwwwww#",
        ".#swwwwwws#.",
        ".#sswwwwss#.",
        "..##ssss##..",
        "....####....",
    ],
    sclera="ws",
    iris=[".aaaa.", "aabbaa", "abppba", "abppba", "aabbaa", ".aaaa."], pupil="p",
    highlight=["hh", "h."], highlight_offset=[-1, -1],
    lid="#ffd3e2", lash="#2b1630", travel=[3, 3],
    layout={"count": 2, "gap": 3, "mirror": True},
    behavior={"blink": [2, 5], "style": "smooth", "stare": 0.25, "speed": 1.1},
)

# ---- жуткие ------------------------------------------------------------------

skin(
    id="demon", name="Демон", description="Красное яблоко и жёлтый змеиный зрачок",
    tags=["жуткие"],
    palette={"#": "#0d0405", "r": "#8f0f17", "v": "#5c0a10", "y": "#ffcc1f", "o": "#0d0405"},
    eye=[
        "....#####....",
        "..##rrrrr##..",
        ".#rrvrrrrrr#.",
        "#rrrrrrrvrrr#",
        "#rrrrrrrrrrr#",
        "#rrvrrrrrrrr#",
        ".#rrrrrrvrr#.",
        "..##rrrrr##..",
        "....#####....",
    ],
    sclera="rv",
    iris=[".y.", "yoy", "yoy", "yoy", "yoy", ".y."], pupil="o",
    lid="#3a0a0e", lash="#0d0405", travel=[3, 1],
    layout={"count": 2, "gap": 3, "mirror": True},
    behavior={"blink": [4, 10], "style": "snap", "slant": 0.35, "stare": 0.5, "creepy": True,
              "speed": 1.2},
)

BLOOD = [
    ".....####.....",
    "...##wwww##...",
    "..#wwwwwwww#..",
    ".#wwwwwwwwww#.",
    ".#wwwwwwwwww#.",
    "#wwwwwwwwwwww#",
    "#wwwwwwwwwwww#",
    "#wwwwwwwwwwww#",
    "#wwwwwwwwwwww#",
    ".#wwwwwwwwww#.",
    ".#wwwwwwwwww#.",
    "..##wwwwww##..",
    "....######....",
]
BLOOD = veins(BLOOD, [(3, 2), (4, 3), (9, 3), (2, 4), (11, 4), (2, 5), (11, 6), (12, 6), (1, 7), (2, 7),
                      (11, 8), (3, 9), (2, 10), (9, 10), (10, 10), (7, 11)])
BLOOD = veins(BLOOD, [(10, 2), (1, 6), (12, 7), (11, 9)], ch="u")
skin(
    id="bloodshot", name="Налитые кровью", description="Красные прожилки, маленький зрачок, дёргаются и почти не моргают",
    tags=["жуткие"],
    palette={"#": "#3a2a22", "w": "#efe7d6", "v": "#c2272d", "u": "#8c1a1f", "i": "#6b1d15",
             "p": "#0a0505"},
    eye=BLOOD, sclera="wvu",
    iris=[".ii.", "ippi", "ippi", ".ii."], pupil="p",
    lid="#b89a8a", lash="#3a1a14", travel=[4, 3],
    layout={"count": 2, "gap": 3, "mirror": True},
    behavior={"blink": [6, 14], "style": "twitch", "twitch": 0.7, "stare": 0.6, "creepy": True,
              "speed": 1.6},
)

skin(
    id="void", name="Пустота", description="Чёрные глаза с крошечным белым зрачком, медленно следят и не моргают",
    tags=["жуткие"],
    palette={"#": "#3d0c0c", "k": "#050506", "g": "#6b6b70", "h": "#ffffff"},
    eye=[
        "...######...",
        ".##kkkkkk##.",
        "#kkkkkkkkkk#",
        "#kkkkkkkkkk#",
        "#kkkkkkkkkk#",
        "#kkkkkkkkkk#",
        "#kkkkkkkkkk#",
        ".##kkkkkk##.",
        "...######...",
    ],
    sclera="k",
    iris=[".g.", "ghg", ".g."], pupil="h",
    lid="#050506", lash="#3d0c0c", travel=[3, 2],
    layout={"count": 2, "gap": 3, "mirror": True},
    behavior={"blink": [0, 0], "style": "smooth", "speed": 0.45, "stare": 0.8, "creepy": True},
)

CYC = [
    "......########......",
    "....##wwwwwwww##....",
    "..##wwwwwwwwwwww##..",
    ".#wwwwwwwwwwwwwwww#.",
    ".#wwwwwwwwwwwwwwww#.",
    "#wwwwwwwwwwwwwwwwww#",
    "#wwwwwwwwwwwwwwwwww#",
    "#wwwwwwwwwwwwwwwwww#",
    "#wwwwwwwwwwwwwwwwww#",
    ".#wwwwwwwwwwwwwwww#.",
    ".#wwwwwwwwwwwwwwww#.",
    "..##wwwwwwwwwwww##..",
    "....##wwwwwwww##....",
    "......########......",
]
CYC = veins(CYC, [(5, 2), (6, 2), (3, 3), (15, 3), (2, 5), (3, 5), (17, 5), (16, 6), (2, 8), (17, 8),
                  (4, 9), (15, 9), (14, 10), (6, 11), (13, 11)])
skin(
    id="cyclops", name="Циклоп", description="Один большой глаз с прожилками и змеиным зрачком",
    tags=["жуткие"],
    palette={"#": "#2a1d18", "w": "#f2ead8", "v": "#c23a3a", "g": "#5f8a1a", "y": "#c9e64a",
             "p": "#0c0c0c", "h": "#ffffff"},
    eye=CYC, sclera="wv",
    iris=["..gg..", ".gyyg.", "gyppyg", "gyppyg", "gyppyg", "gyppyg", ".gyyg.", "..gg.."], pupil="p",
    highlight=["h"], highlight_offset=[-4, -3], highlight_fixed=True,
    lid="#8c6e5e", lash="#2a1810", travel=[5, 3],
    layout={"count": 1},
    behavior={"blink": [4, 10], "style": "snap", "twitch": 0.3, "stare": 0.5, "creepy": True,
              "speed": 1.2},
)

skin(
    id="swarm", name="Рой", description="Семь глаз разного размера, каждый смотрит и моргает сам по себе",
    tags=["жуткие"],
    palette={"#": "#2a1f1f", "w": "#e8e0d0", "v": "#b8423a", "p": "#3a0d0d"},
    eye=[
        "..####..",
        ".#wwww#.",
        "#wwwwww#",
        "#wvwwww#",
        ".#wwww#.",
        "..####..",
    ],
    sclera="wv",
    iris=["pp", "pp"], pupil="p",
    lid="#9c7f72", lash="#2a1f1f", travel=[2, 1],
    layout={"eyes": [
        {"x": 13, "y": 5, "scale": 2},
        {"x": 0, "y": 3},
        {"x": 3, "y": 13, "mirror": True},
        {"x": 31, "y": 0, "mirror": True},
        {"x": 32, "y": 11},
        {"x": 20, "y": 0},
        {"x": 6, "y": 0},
    ]},
    behavior={"blink": [1.5, 7], "style": "snap", "twitch": 0.4, "stare": 0.3, "independent": True,
              "creepy": True},
)


def main():
    os.makedirs(OUT, exist_ok=True)
    for s in SKINS:
        path = os.path.join(OUT, s["id"] + ".json")
        io.open(path, "w", encoding="utf-8", newline="\n").write(dump(s))
    print("глаз:", len(SKINS), "->", os.path.normpath(OUT))


if __name__ == "__main__":
    main()
