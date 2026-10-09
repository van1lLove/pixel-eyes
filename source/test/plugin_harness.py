"""Прогон Python-части Pixel Eyes на ПК: SDK, Java и ядро заменены заглушками.
Запуск: python eyes/test/plugin_harness.py (после bash eyes/build.sh)."""
import io
import json
import os
import shutil
import sys
import tempfile
import threading
import time
import types

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, ".."))
PLUGIN = os.path.join(ROOT, "out", "pixel_eyes.plugin")
SKINS = os.path.join(ROOT, "skins")
ORDER = ["cartoon", "cartoon_pink", "anime", "kawaii", "cat", "retro", "robot", "sleepy", "grumpy",
         "demon", "bloodshot", "void", "cyclops", "swarm"]

FAILS = []
CHECKS = [0]


def check(name, ok, extra=None):
    CHECKS[0] += 1
    print(("  ok   " if ok else "  FAIL ") + name + ("" if ok or extra is None else f"  [{extra}]"))
    if not ok:
        FAILS.append(name)


# ---- заглушки ---------------------------------------------------------------------

TMP = tempfile.mkdtemp(prefix="pe_")
CALLS = []
TOASTS = []
DIALOGS = []
UI_QUEUE = []
CLIP = {"text": None}


def mod(name, **attrs):
    m = types.ModuleType(name)
    for k, v in attrs.items():
        setattr(m, k, v)
    sys.modules[name] = m
    return m


class BasePlugin:
    def __init__(self):
        self._settings = {}
        self.menu = []

    def get_setting(self, key, default=None):
        return self._settings.get(key, default)

    def set_setting(self, key, value, *a, **k):
        self._settings[key] = value

    def add_menu_item(self, item):
        self.menu.append(item)

    def log(self, msg):
        pass


class MenuItemData:
    def __init__(self, **kw):
        self.kw = kw


class MenuItemType:
    MESSAGE_CONTEXT_MENU = "message"


mod("base_plugin", BasePlugin=BasePlugin, MenuItemData=MenuItemData, MenuItemType=MenuItemType)


def run_on_ui_thread(fn, delay=0):
    UI_QUEUE.append(fn)


mod("android_utils", run_on_ui_thread=run_on_ui_thread)


def item(kind):
    class Item:
        def __init__(self, **kw):
            self.kind = kind
            self.kw = kw
    Item.__name__ = kind
    return Item


mod("ui")
mod("ui.settings", Header=item("Header"), Switch=item("Switch"), Divider=item("Divider"), Input=item("Input"),
    Text=item("Text"), Selector=item("Selector"), Custom=item("Custom"))


class BulletinHelper:
    @staticmethod
    def show_info(t, *a):
        TOASTS.append(("info", t))

    @staticmethod
    def show_error(t, *a):
        TOASTS.append(("error", t))

    @staticmethod
    def show_success(t, *a):
        TOASTS.append(("success", t))


mod("ui.bulletin", BulletinHelper=BulletinHelper)


class AlertDialogBuilder:
    def __init__(self, ctx):
        self.title = None
        self.message = None
        self.view = None
        self.buttons = {}
        DIALOGS.append(self)

    def set_title(self, t):
        self.title = t

    def set_message(self, m):
        self.message = m

    def set_view(self, v, h=None):
        self.view = v
        self.height = h

    def set_positive_button(self, t, cb):
        self.buttons["positive"] = (t, cb)

    def set_negative_button(self, t, cb):
        self.buttons["negative"] = (t, cb)

    def set_neutral_button(self, t, cb):
        self.buttons["neutral"] = (t, cb)

    def make_button_red(self, which):
        self.red = which

    def show(self):
        self.shown = True

    def press(self, which):
        self.buttons[which][1](self, 0)


mod("ui.alert", AlertDialogBuilder=AlertDialogBuilder)


class FakeUser:
    username = "tester"


class FakeUserConfig:
    def getCurrentUser(self):
        return FakeUser()


mod("client_utils", get_last_fragment=lambda: None, get_user_config=lambda: FakeUserConfig())


class JFile:
    def __init__(self, path):
        self.path = path

    def getAbsolutePath(self):
        return self.path

    def exists(self):
        return os.path.exists(self.path)

    def length(self):
        return os.path.getsize(self.path)

    def delete(self):
        pass

    def setReadOnly(self):
        pass


class Clip:
    def __init__(self, text):
        self.text = text

    def getItemCount(self):
        return 0 if self.text is None else 1

    def getItemAt(self, i):
        outer = self

        class It:
            def coerceToText(self, ctx):
                return outer.text
        return It()


class ClipboardManager:
    def getPrimaryClip(self):
        return Clip(CLIP["text"]) if CLIP["text"] is not None else None

    def setPrimaryClip(self, clip):
        CLIP["text"] = clip.text


class Ctx:
    CLIPBOARD_SERVICE = "clipboard"

    def getExternalFilesDir(self, x):
        return JFile(TMP)

    def getClassLoader(self):
        return object()

    def getSystemService(self, name):
        return ClipboardManager()

    def getCodeCacheDir(self):
        return JFile(TMP)

    def getResources(self):
        class R:
            def getDisplayMetrics(self):
                class D:
                    heightPixels = 2000
                    density = 2.5
                return D()
        return R()


class ApplicationLoader:
    applicationContext = Ctx()


mod("org")
mod("org.telegram")
mod("org.telegram.messenger", ApplicationLoader=ApplicationLoader)


# ---- поддельное ядро ------------------------------------------------------------------

def builtin_jsons():
    out = []
    for sid in ORDER:
        out.append(io.open(os.path.join(SKINS, sid + ".json"), encoding="utf-8").read())
    return out


class FakeCore:
    listener = None
    skin = None
    config = ""
    installed = False

    def version(self):
        return "pe-core-3"

    def setListener(self, l):
        FakeCore.listener = l

    def configure(self, s):
        FakeCore.config = s

    def check(self, text):
        try:
            d = json.loads(text)
        except Exception as e:
            return f"битый JSON: {e}"
        if "eye" not in d:
            return "нет сетки \"eye\""
        return ""

    def setSkin(self, text):
        err = self.check(text)
        if not err:
            FakeCore.skin = json.loads(text)["id"]
        return err

    def builtinCount(self):
        return len(ORDER)

    def builtinJson(self, i):
        return builtin_jsons()[i]

    def defaultId(self):
        return "cartoon"

    def install(self, ctx):
        FakeCore.installed = True
        return ""

    def uninstall(self):
        FakeCore.installed = False

    def createPreview(self, ctx, j):
        return ("preview", j)

    def createGallery(self, ctx, joined, sel, mode):
        return ("gallery", joined.split("\u0001"), sel, mode)

    def createEditor(self, ctx, j):
        return FakeView(j)

    def editorJson(self, view, name):
        return FakeCore.editor_result

    editor_result = "!пусто"

    def stats(self):
        return "ok"

    def drainLog(self):
        return ""

    words = ""
    events = []

    def event(self, name):
        FakeCore.events.append(name)

    def setWords(self, spec):
        FakeCore.words = spec

    def mood(self, text):
        return "insult" if "бот" in text else "none"


class FakeView:
    def __init__(self, data):
        self.data = data

    def getParent(self):
        return None


class FakeMethod:
    def __init__(self, name):
        self.name = name

    def getName(self):
        return self.name

    def invoke(self, target, args):
        CALLS.append((self.name, list(args)))
        return getattr(FakeCore(), self.name)(*args)


class FakeClass:
    def getMethods(self):
        return [FakeMethod(n) for n in dir(FakeCore) if not n.startswith("_") and callable(getattr(FakeCore, n))]


class FakeLoader:
    def __init__(self, *a):
        pass

    def loadClass(self, name):
        assert name == "com.pixeleyes.EyesCore"
        return FakeClass()


mod("dalvik")
mod("dalvik.system", InMemoryDexClassLoader=FakeLoader, DexClassLoader=FakeLoader)


class ByteBuffer:
    @staticmethod
    def wrap(d):
        return d


mod("java.nio", ByteBuffer=ByteBuffer)


class Scanner:
    pass


def jclass(name):
    if name == "java.util.function.Consumer":
        class Consumer:
            pass
        return Consumer
    if name == "java.io.File":
        return JFile
    if name == "android.content.Context":
        return Ctx
    if name == "android.content.ClipData":
        class ClipData:
            @staticmethod
            def newPlainText(label, text):
                return Clip(text)
        return ClipData
    if name == "android.widget.ScrollView":
        class SV:
            def __init__(self, ctx):
                self.child = None

            def addView(self, v):
                self.child = v
        return SV
    if name == "org.telegram.messenger.FileLoader":
        class FL:
            @staticmethod
            def getInstance(acc):
                class I:
                    def getPathToMessage(self, owner):
                        return JFile(getattr(owner, "path", os.path.join(TMP, "nope.json")))
                return I()
        return FL
    raise KeyError(name)


def dynamic_proxy(iface):
    class Proxy:
        def __init__(self):
            pass
    return Proxy


mod("java", jclass=jclass, dynamic_proxy=dynamic_proxy, jint=lambda x: x)


def drain():
    while UI_QUEUE:
        UI_QUEUE.pop(0)()


# ---- загрузка плагина -------------------------------------------------------------------

src = io.open(PLUGIN, encoding="utf-8").read()
ns = {"__name__": "pixel_eyes_plugin"}
exec(compile(src, PLUGIN, "exec"), ns)
Plugin = ns["PixelEyesPlugin"]


def fresh(**settings):
    CALLS.clear()
    TOASTS.clear()
    DIALOGS.clear()
    p = Plugin()
    p._settings.update(settings)
    p.on_plugin_load()
    drain()
    return p


def called(name):
    return [a for n, a in CALLS if n == name]


print("1. загрузка")
for f in os.listdir(TMP):
    pass
p = fresh()
check("ядро загружено", p._core is not None, getattr(p, "_core_status", None))
check("слушатель отдан ядру", FakeCore.listener is not None and called("setListener"))
check("настройки ушли до установки", CALLS.index(("configure", called("configure")[0])) < [n for n, _ in CALLS].index("install"))
check("по умолчанию мультяшные", FakeCore.skin == "cartoon")
check("глаза поставлены", FakeCore.installed)
cfg = dict(kv.split("=") for kv in FakeCore.config.split(";"))
check("настройки по умолчанию", cfg["enabled"] == "1" and cfg["px"] == "3.0" and cfg["alpha"] == "100"
      and cfg["where"] == "0" and cfg["sleepsec"] == "90" and cfg["debug"] == "0" and cfg.get("text") == "1", cfg)
menu = [m.kw for m in p.menu]
check("пункт в меню сообщения с условием", menu and menu[0]["item_id"] == "pixel_eyes_add" and "condition" in menu[0]
      and "'\"eye\"'" in menu[0]["condition"])

print("2. экран настроек")
items = p.create_settings()
kinds = [i.kind for i in items]
check("заголовок, включатель, живой пример", kinds[:3] == ["Header", "Switch", "Custom"], kinds)
texts = [i.kw.get("text") for i in items]
check("страницы глаз, реакций и своих", "Выбрать глаза" in texts and "Реакции и сон" in texts
      and "Свои глаза и каталог" in texts)
page = next(i for i in items if i.kw.get("text") == "Выбрать глаза")
check("подпись с текущими глазами", page.kw.get("subtext") == "Сейчас: Мультяшные", page.kw.get("subtext"))
gal = page.kw["create_sub_fragment"]()
g = gal[0].kw["view"]
check("галерея: 14 встроенных, выбраны мультяшные", g[0] == "gallery" and len(g[1]) == 14 and g[2] == "cartoon"
      and g[3] == "library", (len(g[1]), g[2]))
reacts = next(i for i in items if i.kw.get("text") == "Реакции и сон").kw["create_sub_fragment"]()
keys = [i.kw.get("key") for i in reacts if i.kind in ("Switch", "Input")]
check("все реакции на месте", all(k in keys for k in ("follow_touch", "follow_typing", "stare", "react_messages",
                                                         "react_send", "react_love", "react_text", "react_peer",
                                                         "react_shake",
                                                         "react_copy", "poke", "sleep", "sleep_sec", "night",
                                                         "battery")), keys)
wp = next(i for i in reacts if i.kw.get("text") == "Свои слова для текста")
words = wp.kw["create_sub_fragment"]()
wkeys = [i.kw.get("key") for i in words if i.kind == "Input"]
check("страница своих слов", wkeys == ["words_insult", "words_love", "words_laugh", "words_sad", "words_ignore",
                                       "words_test"], wkeys)
p._settings["words_insult"] = "ты бот, чепух*"
p._settings["words_ignore"] = "дурак"
next(i for i in words if i.kw.get("key") == "words_insult").kw["on_change"]("ты бот, чепух*")
check("свои слова ушли в ядро", FakeCore.words == "insult=ты бот, чепух*\nignore=дурак", FakeCore.words)
TOASTS.clear()
FakeCore.events.clear()
p._settings["words_test"] = "ну ты бот"
next(i for i in words if i.kw.get("key") == "words_test").kw["on_change"]("ну ты бот")
drain()
check("проверка фразы: подсказка и реакция", ("info", "Глаза поймут: гадости") in TOASTS
      and "emo:scared" in FakeCore.events, (TOASTS, FakeCore.events))
p._settings["words_insult"] = ""
p._settings["words_ignore"] = ""
tw = next(i for i in reacts if i.kw.get("key") == "react_text")
p._settings["react_text"] = False
tw.kw["on_change"](False)
cfg = dict(kv.split("=") for kv in FakeCore.config.split(";"))
check("текст сообщений выключается", cfg["text"] == "0")
p._settings["react_text"] = True
sw = next(i for i in reacts if i.kw.get("key") == "react_shake")
p._settings["react_shake"] = False
sw.kw["on_change"](False)
cfg = dict(kv.split("=") for kv in FakeCore.config.split(";"))
check("смена настройки сразу в ядре", cfg["shake"] == "0")
own = next(i for i in items if i.kw.get("text") == "Свои глаза и каталог").kw["create_sub_fragment"]()
check("свои: редактор, каталог, буфер, ссылка, папка", [t for t in ("Нарисовать новые", "Каталог глаз",
      "Из буфера обмена", "Скачать по ссылке", "Папка с глазами") if t not in [i.kw.get("text") for i in own]] == [])
check("ссылка на инструкцию", "Инструкция и онлайн-редактор" in [i.kw.get("text") for i in own])
check("каталог с нового адреса", all("van1l" in u for u in ns["CATALOG_URLS"]), ns["CATALOG_URLS"])

print("3. события ядра")
p._on_core("pos:0.1234,0.8765")
check("позиция сохранена", p._settings["pos_x"] == "0.1234" and p._settings["pos_y"] == "0.8765")
p._on_core("select:anime")
check("выбор в галерее: сохранён и отдан ядру", p._settings["skin"] == "anime" and FakeCore.skin == "anime")
drain()
check("и сказали какие", ("info", "Глаза: Аниме") in TOASTS, TOASTS)

print("4. свои глаза")
anime = json.loads(builtin_jsons()[2])
anime["name"] = "Копия"
CLIP["text"] = "вот мои глаза:\n```json\n" + json.dumps(anime, ensure_ascii=False) + "\n```"
TOASTS.clear()
p._import_clipboard()
drain()
files = os.listdir(os.path.join(TMP, "pixel_eyes"))
check("из буфера: обёртка ``` снята, файл сохранён", any(f.startswith("anime_2") for f in files), files)
check("id не перебил встроенные", p._settings["skin"] == "anime_2" and FakeCore.skin == "anime_2")
saved = json.load(io.open(os.path.join(TMP, "pixel_eyes", "anime_2.json"), encoding="utf-8"))
check("автор подставлен", saved["author"] == "@van1lLove")
lib = p._library()
check("в библиотеке 15", len(lib) == 15 and lib[-1][0] == "anime_2" and lib[-1][2])
CLIP["text"] = "просто текст"
TOASTS.clear()
p._import_clipboard()
drain()
check("мусор из буфера: понятная ошибка", TOASTS and TOASTS[-1][0] == "error" and "JSON" in TOASTS[-1][1], TOASTS)


class Owner:
    def __init__(self, message, path=None):
        self.message = message
        if path:
            self.path = path


class MO:
    currentAccount = 0

    def __init__(self, owner):
        self.messageOwner = owner


robot = json.loads(builtin_jsons()[6])
robot["id"] = "friend_robot"
robot["name"] = "Робот друга"
TOASTS.clear()
p._from_message({"message": MO(Owner(json.dumps(robot, ensure_ascii=False)))})
drain()
check("из сообщения текстом: добавлены и выбраны", p._settings["skin"] == "friend_robot"
      and ("success", "Добавлены и выбраны глаза \"Робот друга\"") in TOASTS, TOASTS)
TOASTS.clear()
p._from_message({"message": MO(Owner("", path=os.path.join(TMP, "missing.json")))})
drain()
check("файл не скачан: просим скачать", TOASTS and "скачайте" in TOASTS[-1][1], TOASTS)
doc = os.path.join(TMP, "doc.json")
robot["id"] = "doc_robot"
io.open(doc, "w", encoding="utf-8").write(json.dumps(robot, ensure_ascii=False))
TOASTS.clear()
p._from_message({"message": MO(Owner("", path=doc))})
drain()
check("из файла: добавлены", p._settings["skin"] == "doc_robot", TOASTS)

print("5. действия с глазами")
DIALOGS.clear()
p._skin_actions("doc_robot")
d = DIALOGS[-1]
check("свои: скопировать, изменить, удалить", set(d.buttons) == {"positive", "neutral", "negative"})
d.press("positive")
check("скопировали JSON", CLIP["text"] and "doc_robot" in CLIP["text"])
d.press("negative")
drain()
check("удалили файл и вернулись к встроенным", not os.path.exists(os.path.join(TMP, "pixel_eyes", "doc_robot.json"))
      and FakeCore.skin == "cartoon", FakeCore.skin)
DIALOGS.clear()
p._skin_actions("cartoon")
check("встроенные удалить нельзя", "negative" not in DIALOGS[-1].buttons)
DIALOGS[-1].press("neutral")
ed = DIALOGS[-1]
check("изменить встроенные: копия в редакторе", ed.title == "Свои глаза" and "my_cartoon" in ed.view.data)
FakeCore.editor_result = "!Отметьте хотя бы один цвет глаза как яблоко"
TOASTS.clear()
ed.press("positive")
drain()
check("ошибка редактора: сказали и открыли снова", TOASTS[-1][0] == "error" and DIALOGS[-1] is not ed
      and DIALOGS[-1].title == "Свои глаза")
mine = json.loads(builtin_jsons()[0])
mine["id"] = "my_cartoon"
mine["name"] = "Мои мультяшные"
FakeCore.editor_result = json.dumps(mine, ensure_ascii=False)
TOASTS.clear()
DIALOGS[-1].press("positive")
drain()
check("сохранение из редактора", p._settings["skin"] == "my_cartoon"
      and os.path.exists(os.path.join(TMP, "pixel_eyes", "my_cartoon.json")), TOASTS)

print("6. каталог")
INDEX = {"format": 1, "skins": [{"id": "cat", "url": "skins/cat.json"}, {"id": "bad", "url": "skins/bad.json"}]}


def fake_get(url, limit=300_000):
    if url.endswith("index.json"):
        if "raw.githubusercontent" in url:
            raise ValueError("сервер ответил 403")
        return json.dumps(INDEX)
    if url.endswith("cat.json"):
        c = json.loads(builtin_jsons()[4])
        c["id"] = "catalog_cat"
        return json.dumps(c, ensure_ascii=False)
    return "{\"bad\": true}"


p._http_get = fake_get
DIALOGS.clear()
p._open_catalog()
for _ in range(50):
    if UI_QUEUE and any(True for _ in UI_QUEUE):
        drain()
    if DIALOGS:
        break
    time.sleep(0.05)
drain()
check("каталог: первый адрес недоступен, второй сработал, плохие отброшены",
      DIALOGS and DIALOGS[-1].view.child[0] == "gallery" and len(DIALOGS[-1].view.child[1]) == 1
      and DIALOGS[-1].view.child[3] == "catalog")
p._on_core("pick:catalog_cat")
drain()
check("нажали в каталоге: добавлены и выбраны", p._settings["skin"] == "catalog_cat")

print("7. выгрузка")
p.on_plugin_unload()
check("глаза сняты, слушатель отвязан", not FakeCore.installed and called("setListener")[-1] == [None])
p._on_core("select:anime")
check("выгруженный плагин событий не обрабатывает", p._settings["skin"] == "catalog_cat")

print("8. старый SDK")
saved_cls = ns["MenuItemData"]


class OldMenu:
    def __init__(self, menu_type, text, on_click, icon=None, item_id=None):
        self.kw = dict(menu_type=menu_type, text=text)


ns["MenuItemData"] = OldMenu
p2 = fresh()
check("без условий пункт не добавляется (иначе висел бы на каждом сообщении)", p2.menu == [])
ns["MenuItemData"] = saved_cls
ns["Custom"] = None
items = p2.create_settings()
page = next(i for i in items if i.kw.get("text") == "Выбрать глаза")
gal = page.kw["create_sub_fragment"]()
check("без Custom галерея списком кнопок", gal[0].kind == "Text" and "on_click" in gal[0].kw)

shutil.rmtree(TMP, ignore_errors=True)
print()
print("ИТОГ: всё прошло (%d)" % CHECKS[0] if not FAILS else "ИТОГ: провалов %d из %d" % (len(FAILS), CHECKS[0]))
sys.exit(1 if FAILS else 0)
