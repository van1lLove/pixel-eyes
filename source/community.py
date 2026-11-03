"""Общий валидатор данных каталога. Только стандартная библиотека, без Android."""
import base64 as pe_base64
import hashlib as pe_hashlib
import json as pe_json
import math as pe_math
import re as pe_re
import unicodedata as pe_unicode
import zlib as pe_zlib

PE_MAX_BYTES = 65536
PE_MARKER = "PIXEL_EYES_SUBMISSION_V1:"
PE_KEYS = set("format id name author description tags palette eye sclera iris pupil highlight "
              "iris_offset highlight_offset highlight_fixed lid lash travel layout behavior frames".split())
PE_FRAMES = set("closed happy love dizzy angry sad ouch surprised".split())


def pe_fail(message):
    raise ValueError(message)


def pe_object(value, keys, where):
    if not isinstance(value, dict) or set(value) - set(keys):
        pe_fail(where + ": неизвестные поля или не объект")
    return value


def pe_text(value, maximum, where, empty=True):
    if not isinstance(value, str) or len(value) > maximum or (not empty and not value.strip()):
        pe_fail(where + ": неверная длина текста")
    if any(pe_unicode.category(c) in ("Cc", "Cs") or c in "\u202a\u202b\u202c\u202d\u202e\u2066\u2067\u2068\u2069" for c in value):
        pe_fail(where + ": управляющие символы запрещены")
    return value


def pe_num(value, lo, hi, where, integer=False):
    if isinstance(value, bool) or not isinstance(value, (float, int)):
        pe_fail(where + ": требуется конечное число")
    if not lo <= value <= hi or not pe_math.isfinite(value) or integer and int(value) != value:
        pe_fail(where + ": число вне допустимого диапазона")
    return value


def pe_pair(value, lo, hi, where):
    if not isinstance(value, list) or len(value) != 2:
        pe_fail(where + ": нужны два числа")
    for v in value:
        pe_num(v, lo, hi, where, True)


def pe_bool(value, where):
    if not isinstance(value, bool):
        pe_fail(where + ": требуется true или false")


def pe_load(text):
    if not isinstance(text, str) or len(text.encode("utf-8")) > PE_MAX_BYTES:
        pe_fail("JSON глаз должен быть не больше 64 КБ")
    def pairs(items):
        d = {}
        for k, v in items:
            if k in d:
                pe_fail("Повтор ключа JSON")
            d[k] = v
        return d
    try:
        return pe_json.loads(text, object_pairs_hook=pairs,
                             parse_constant=lambda _: pe_fail("NaN и Infinity запрещены"))
    except (RecursionError, UnicodeError) as e:
        raise ValueError("Слишком сложный JSON") from e


def pe_validate(text):
    d = pe_object(pe_load(text), PE_KEYS, "Глаза")
    if type(d.get("format", 1)) is not int or d.get("format", 1) != 1:
        pe_fail("Поддерживается только format: 1")
    if not isinstance(d.get("id"), str) or not pe_re.fullmatch(r"[a-z][a-z0-9_-]{0,39}", d["id"]):
        pe_fail("id: латинские буквы, цифры, _ и -, до 40 символов")
    for key, limit in (("name", 40), ("author", 40), ("description", 200)):
        pe_text(d.get(key, ""), limit, key, key != "name")
    tags = d.get("tags", [])
    if not isinstance(tags, list) or len(tags) > 8:
        pe_fail("Не больше восьми тегов")
    for tag in tags:
        pe_text(tag, 20, "Тег", False)
    pal = d.get("palette")
    if not isinstance(pal, dict) or not 1 <= len(pal) <= 128:
        pe_fail("Палитра: от 1 до 128 цветов")
    for k, v in pal.items():
        pe_text(k, 1, "Символ палитры", False)
        if k in ". " or ord(k) > 65535:
            pe_fail("Символ палитры занят или не поддерживается")
        if not isinstance(v, str) or not pe_re.fullmatch(r"#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})", v):
            pe_fail("Цвет палитры должен быть #RRGGBB или #AARRGGBB")
    def grid(rows, where, mw=32, mh=32):
        if isinstance(rows, str):
            rows = rows.split("\n")
        if not isinstance(rows, list) or not 1 <= len(rows) <= mh:
            pe_fail(where + ": неверная высота сетки")
        width = max((len(r) if isinstance(r, str) else 0 for r in rows), default=0)
        if not 1 <= width <= mw:
            pe_fail(where + ": неверная ширина сетки")
        for row in rows:
            pe_text(row, mw, where)
            if any(c not in pal and c not in ". " for c in row):
                pe_fail(where + ": символ отсутствует в палитре")
        return width, len(rows)
    w, h = grid(d.get("eye"), "eye")
    sclera = pe_text(d.get("sclera", "w"), 128, "sclera", False)
    eye_rows = d["eye"].split("\n") if isinstance(d["eye"], str) else d["eye"]
    if not any(c in sclera and c not in ". " for row in eye_rows for c in row):
        pe_fail("В глазе нет клеток яблока sclera")
    for key in ("iris", "highlight"):
        if key in d:
            grid(d[key], key, w, h)
    for key in ("iris_offset", "highlight_offset"):
        if key in d:
            pe_pair(d[key], -32, 32, key)
    for key in ("lid", "lash"):
        if key in d and (not isinstance(d[key], str) or d[key] not in pal and d[key].lower() not in ("none", "transparent") and not pe_re.fullmatch(r"#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})", d[key])):
            pe_fail(key + ": неизвестный цвет")
    if "pupil" in d:
        pupil = pe_text(d["pupil"], 1, "pupil")
        if pupil and pupil not in pal:
            pe_fail("pupil: неизвестный цвет")
    if "travel" in d:
        pe_pair(d["travel"], 0, 10, "travel")
    if "highlight_fixed" in d:
        pe_bool(d["highlight_fixed"], "highlight_fixed")
    frames = pe_object(d.get("frames", {}), PE_FRAMES, "frames")
    for key, rows in frames.items():
        grid(rows, "frames." + key)
    layout = pe_object(d.get("layout", {}), ("eyes", "count", "gap", "mirror"), "layout")
    if "mirror" in layout:
        pe_bool(layout["mirror"], "mirror")
    pe_num(layout.get("count", 2), 1, 2, "count", True)
    pe_num(layout.get("gap", 2), 0, 32, "gap", True)
    places = layout.get("eyes")
    if places is not None:
        if not isinstance(places, list) or not 1 <= len(places) <= 12:
            pe_fail("layout.eyes: от 1 до 12 глаз")
        for place in places:
            pe_object(place, ("x", "y", "scale", "mirror"), "Положение глаза")
            x = pe_num(place.get("x", 0), 0, 120, "x", True)
            y = pe_num(place.get("y", 0), 0, 60, "y", True)
            scale = pe_num(place.get("scale", 1), 1, 3, "scale", True)
            if x + w * scale > 128 or y + h * scale > 64:
                pe_fail("Глаза не помещаются в холст 128x64")
            if "mirror" in place:
                pe_bool(place["mirror"], "mirror")
    b = pe_object(d.get("behavior", {}), "blink style stare twitch speed open slant independent creepy".split(), "behavior")
    if "style" in b and b["style"] not in ("smooth", "snap", "twitch"):
        pe_fail("Неизвестный стиль движения")
    for key, lo, hi in (("stare", 0, 1), ("twitch", 0, 1), ("speed", .2, 4), ("open", .3, 1), ("slant", -1, 1)):
        if key in b:
            pe_num(b[key], lo, hi, key)
    for key in ("creepy", "independent"):
        if key in b:
            pe_bool(b[key], key)
    if "blink" in b:
        blink = b["blink"]
        if isinstance(blink, list):
            if len(blink) != 2:
                pe_fail("blink: два интервала")
            pe_num(blink[0], 0, 60, "blink")
            pe_num(blink[1], blink[0], 60, "blink")
        else:
            pe_num(blink, 0, 60, "blink")
    return d


def pe_canonical(data):
    return pe_json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def pe_pack(text):
    data = pe_validate(text)
    raw = pe_canonical(data).encode("utf-8")
    return PE_MARKER + pe_base64.urlsafe_b64encode(pe_zlib.compress(raw, 9)).decode("ascii")


def pe_unpack(body):
    if not isinstance(body, str) or len(body) > 100000:
        pe_fail("Заявка слишком большая")
    matches = pe_re.findall(pe_re.escape(PE_MARKER) + r"([A-Za-z0-9_=\-]+)", body)
    if len(matches) != 1 or len(matches[0]) > 90000:
        pe_fail("Нужна одна заявка Pixel Eyes")
    try:
        packed = pe_base64.b64decode(matches[0], altchars=b"-_", validate=True)
        dec = pe_zlib.decompressobj()
        raw = dec.decompress(packed, PE_MAX_BYTES + 1)
        if len(raw) > PE_MAX_BYTES or dec.unconsumed_tail or dec.unused_data or not dec.eof:
            pe_fail("Слишком большой или повреждённый пакет")
        return pe_validate(raw.decode("utf-8"))
    except (pe_zlib.error, UnicodeError, pe_base64.binascii.Error) as e:
        raise ValueError("Повреждённая заявка") from e


def pe_filtered(skins, query="", tag="", installed=None, only_new=False, sort=0):
    query = query.strip().casefold()
    result = []
    for text in skins:
        d = pe_json.loads(text)
        if tag and tag not in d.get("tags", []):
            continue
        if only_new and d["id"] in (installed or set()):
            continue
        if query and query not in " ".join([d.get("name", ""), d.get("author", ""), d.get("description", ""), " ".join(d.get("tags", []))]).casefold():
            continue
        result.append(text)
    if sort == 1:
        result.sort(key=lambda t: pe_json.loads(t).get("name", "").casefold())
    elif sort == 2:
        result.sort(key=lambda t: (pe_json.loads(t).get("author", "").casefold(), pe_json.loads(t).get("name", "").casefold()))
    return result
