__id__ = "pixel_eyes"
__name__ = "Pixel Eyes"
__description__ = ("Пиксельные глаза поверх приложения. Следят за пальцем и за тем, что вы печатаете, "
                   "а когда перестаёте, смотрят прямо на вас. Понимают текст сообщений: пугаются гадостей, "
                   "влюбляются от признаний, смеются над смешным. В списке чатов смотрят на того, кто "
                   "печатает. Кружатся от тряски, засыпают без дела. 14 наборов от милых аниме до жутких, "
                   "свой редактор и каталог глаз.")
__version__ = "1.2"
__author__ = "@van1lLove (идея: @psyhomane)"
__min_version__ = "12.5.1"
__icon__ = "exteraPlugins/1"

import base64
import io
import json
import os
import re
import threading
import time

from base_plugin import BasePlugin
from android_utils import run_on_ui_thread
from ui.settings import Header, Switch, Divider, Input, Text
try:
    from ui.settings import Selector
except ImportError:
    Selector = None
try:
    from ui.settings import Custom
except ImportError:
    Custom = None
try:
    from ui.bulletin import BulletinHelper
except ImportError:
    BulletinHelper = None
try:
    from base_plugin import MenuItemData, MenuItemType
except ImportError:
    MenuItemData = None
    MenuItemType = None
try:
    from client_utils import get_last_fragment
except ImportError:
    get_last_fragment = None
from java import jclass, dynamic_proxy, jint

from org.telegram.messenger import ApplicationLoader

# ядро собирается из eyes/src скриптом eyes/build.sh и вставляется сюда
DEX_B64 = "@@DEX@@"
CORE_CLASS = "com.pixeleyes.EyesCore"
CORE_VERSION = "pe-core-3"

CATALOG_URLS = [
    "https://raw.githubusercontent.com/van1lLove/pixel-eyes/main/index.json",
    "https://van1llove.github.io/pixel-eyes/index.json",
    "https://cdn.jsdelivr.net/gh/van1lLove/pixel-eyes@main/index.json",
]
REPO_URL = "https://github.com/van1lLove/pixel-eyes"
SITE_URL = "https://van1llove.github.io/pixel-eyes/"
GUIDE_URL = SITE_URL + "guide.html"
EDITOR_URL = SITE_URL + "editor.html"
SEP = "\u0001"
STATS_EVERY_MS = 5000

SIZE_NAMES = ["Крошечные", "Маленькие", "Средние", "Крупные", "Огромные"]
SIZE_DP = [1.5, 2.2, 3.0, 4.0, 5.5]
OPACITY_NAMES = ["100%", "85%", "70%", "50%"]
OPACITY_VALUES = [100, 85, 70, 50]
WHERE_NAMES = ["Везде", "Только в чатах", "Только пока печатаю"]
EDGE_NAMES = ["Авто по теме", "Тень", "Светлая обводка", "Без обводки"]
# свои слова для реакций на текст: ключ настройки и раздел в ядре
WORD_KEYS = [("words_insult", "insult"), ("words_love", "love"), ("words_laugh", "laugh"),
             ("words_sad", "sad"), ("words_ignore", "ignore")]
MOOD_NAMES = {"none": "ничего особенного", "love": "любовь", "insult": "гадости", "laugh": "смех",
              "sad": "грусть"}

# условие пункта в меню сообщения (MVEL клиента): JSON с глазами текстом или файлом .json
MSG_CONDITION = ("message != null && ((message.messageOwner != null && message.messageOwner.message != null "
                 "&& message.messageOwner.message.contains('\"eye\"')) || (message.getDocumentName() != null "
                 "&& (message.getDocumentName().endsWith('.json') || message.getDocumentName().endsWith('.eyes'))))")

FORMAT_HELP = (
    "Глаза описываются JSON-файлом. Главное поле \"eye\": сетка строк, символ это пиксель, точка "
    "прозрачна, остальные символы берутся из \"palette\" (\"#\": \"#1b1b22\"). Символы из \"sclera\" "
    "это яблоко глаза: по нему ходит зрачок \"iris\" и его закрывают веки (\"lid\", \"lash\"). "
    "Необязательно: \"highlight\" (блик), \"layout\" (сколько глаз и где), \"frames\" (свои кадры для "
    "closed, happy, love, dizzy, angry, sad, ouch, surprised), \"behavior\" (моргание, характер "
    "взгляда, жуткие). Проще всего нарисовать в редакторе или взять встроенные и поправить. "
    "Готовый файл можно скинуть в любой чат: по нажатию на сообщение появится \"Добавить глаза\"."
)


class _Listener(dynamic_proxy(jclass("java.util.function.Consumer"))):
    """Сюда ядро шлёт события своих вью: перетащили глаза, выбрали набор в галерее."""

    def __init__(self, plugin):
        super().__init__()
        self.plugin = plugin

    def accept(self, msg):
        try:
            self.plugin._on_core(str(msg))
        except Exception as e:
            self.plugin._log(f"слушатель: {e}")


class PixelEyesPlugin(BasePlugin):

    # ---- журнал -----------------------------------------------------------------

    def _write_log(self, text):
        try:
            base = ApplicationLoader.applicationContext.getExternalFilesDir(None)
            with io.open(str(base.getAbsolutePath()) + "/pixel_eyes_log.txt", "a", encoding="utf-8") as f:
                f.write(text)
        except Exception:
            pass

    def _log(self, msg):
        if not self._opt("debug", False):
            return
        self._write_log(time.strftime("%H:%M:%S ") + str(msg) + "\n")

    def _toast(self, text, kind="info"):
        def show():
            try:
                if BulletinHelper is None:
                    return
                if kind == "error":
                    BulletinHelper.show_error(text)
                elif kind == "success":
                    BulletinHelper.show_success(text)
                else:
                    BulletinHelper.show_info(text)
            except Exception:
                pass
        run_on_ui_thread(show)

    # ---- настройки ---------------------------------------------------------------

    def _opt(self, key, default=None):
        try:
            return self.get_setting(key, default)
        except Exception:
            return default

    def _sel(self, key, default, count):
        try:
            return max(0, min(count - 1, int(self._opt(key, default))))
        except Exception:
            return default

    def _float(self, key, default, lo, hi):
        try:
            return max(lo, min(hi, float(str(self._opt(key, default)).replace(",", "."))))
        except Exception:
            return default

    def _settings_str(self):
        def on(key, default=True):
            return "1" if bool(self._opt(key, default)) else "0"
        parts = [
            ("enabled", on("enabled")),
            ("px", SIZE_DP[self._sel("size", 2, len(SIZE_DP))]),
            ("alpha", OPACITY_VALUES[self._sel("opacity", 0, len(OPACITY_VALUES))]),
            ("posx", self._float("pos_x", 0.82, 0.0, 1.0)),
            ("posy", self._float("pos_y", 0.22, 0.0, 1.0)),
            ("where", self._sel("where", 0, len(WHERE_NAMES))),
            ("edge", self._sel("edge", 0, len(EDGE_NAMES))),
            ("touch", on("follow_touch")),
            ("typing", on("follow_typing")),
            ("stare", on("stare")),
            ("messages", on("react_messages")),
            ("send", on("react_send")),
            ("love", on("react_love")),
            ("text", on("react_text")),
            ("peer", on("react_peer")),
            ("screen", on("react_screen")),
            ("shake", on("react_shake")),
            ("clip", on("react_copy")),
            ("sleep", on("sleep")),
            ("sleepsec", int(self._float("sleep_sec", 90, 10, 3600))),
            ("night", on("night")),
            ("battery", on("battery")),
            ("poke", on("poke")),
            ("debug", on("debug", False)),
        ]
        return ";".join(f"{k}={v}" for k, v in parts)

    def _words_spec(self):
        lines = []
        for key, part in WORD_KEYS:
            value = str(self._opt(key, "") or "").replace("\n", ",").strip()
            if value:
                lines.append(f"{part}={value}")
        return "\n".join(lines)

    def _push(self):
        if self._core is None:
            return
        try:
            self._call("configure", self._settings_str())
        except Exception as e:
            self._log(f"настройки в ядро: {e}")
        try:
            self._call("setWords", self._words_spec())
        except Exception as e:
            self._log(f"свои слова в ядро: {e}")

    def _changed(self, key, value):
        if key == "words_test":
            self._test_phrase(value)
            return
        try:
            self._push()
            if key == "debug":
                self._restart_tick()
        except Exception as e:
            self._log(f"смена {key}: {e}")

    # ---- ядро ----------------------------------------------------------------------

    def _load_core(self):
        self._core = None
        self._m = {}
        if not DEX_B64 or DEX_B64.startswith("@@"):
            return "ядро не вшито в плагин"
        try:
            data = base64.b64decode(DEX_B64)
        except Exception as e:
            return f"ядро повреждено: {e}"
        parent = ApplicationLoader.applicationContext.getClassLoader()
        loader = None
        try:
            from dalvik.system import InMemoryDexClassLoader
            from java.nio import ByteBuffer
            loader = InMemoryDexClassLoader(ByteBuffer.wrap(data), parent)
        except Exception as e:
            self._log(f"загрузка из памяти не вышла ({e}), пробую через файл")
            try:
                from dalvik.system import DexClassLoader
                cache = ApplicationLoader.applicationContext.getCodeCacheDir()
                path = str(cache.getAbsolutePath()) + "/pixel_eyes_core.dex"
                jclass("java.io.File")(path).delete()
                with io.open(path, "wb") as f:
                    f.write(data)
                jclass("java.io.File")(path).setReadOnly()
                loader = DexClassLoader(path, str(cache.getAbsolutePath()), None, parent)
            except Exception as e2:
                return f"не загрузить ядро: {e2}"
        try:
            cls = loader.loadClass(CORE_CLASS)
            for m in cls.getMethods():
                self._m.setdefault(str(m.getName()), m)
            version = str(self._m["version"].invoke(None, []))
            if version != CORE_VERSION:
                return f"не та версия ядра: {version}"
            self._core = cls
            self._loader = loader
        except Exception as e:
            self._core = None
            return f"ядро не запустилось: {e}"
        return None

    def _call(self, name, *args):
        return self._m[name].invoke(None, list(args))

    # ---- жизненный цикл -----------------------------------------------------------

    def on_plugin_load(self):
        self._unloaded = False
        self._core = None
        self._m = {}
        self._gen = int(getattr(self, "_gen", 0)) + 1
        self._ticking = False
        self._last_stats = ""
        self._catalog = {}
        self._listener = None
        reason = self._load_core()
        self._core_status = reason
        if reason is not None:
            self._log(f"ядро недоступно: {reason}")
            return
        try:
            self._listener = _Listener(self)
            self._call("setListener", self._listener)
        except Exception as e:
            self._log(f"слушатель ядра: {e}")
        self._push()
        self._apply_skin()
        try:
            err = str(self._call("install", ApplicationLoader.applicationContext) or "")
            if err:
                self._log(f"установка: {err}")
        except Exception as e:
            self._log(f"установка: {e}")
        self._add_menu()
        self._restart_tick()
        self._probe()
        self._log(f"ядро {CORE_VERSION} загружено, глаза: {self._opt('skin', '')}")

    def _probe(self):
        """Проверка вёрстки на устройстве: если рядом с журналом лежит файл
        pixel_eyes_probe, рисует свои вью в PNG. У обычных людей файла нет."""
        try:
            base = str(ApplicationLoader.applicationContext.getExternalFilesDir(None).getAbsolutePath())
            if not os.path.exists(base + "/pixel_eyes_probe"):
                return
        except Exception:
            return

        def shoot():
            try:
                la = jclass("org.telegram.ui.LaunchActivity").instance
                ctx = la if la is not None else ApplicationLoader.applicationContext
                width = int(ctx.getResources().getDisplayMetrics().widthPixels)
                lib = self._library()
                views = {
                    "preview": self._call("createPreview", ctx, ""),
                    "gallery": self._call("createGallery", ctx, SEP.join(t for _, t, _ in lib), "anime", "library"),
                    "editor": self._call("createEditor", ctx, self._skin_json("anime")),
                }
                for name, view in views.items():
                    err = str(self._call("snapshot", view, jint(width), base + f"/pe_{name}.png"))
                    self._log(f"снимок {name}: {err or 'ok'}")
            except Exception as e:
                self._log(f"снимки: {e}")

            def catalog():
                try:
                    skins = self._fetch_catalog()

                    def shot():
                        la = jclass("org.telegram.ui.LaunchActivity").instance
                        view = self._call("createGallery", la, SEP.join(skins), "", "catalog")
                        width = int(la.getResources().getDisplayMetrics().widthPixels)
                        err = str(self._call("snapshot", view, jint(width), base + "/pe_catalog.png"))
                        self._log(f"снимок catalog: {err or 'ok'}")
                    run_on_ui_thread(shot)
                except Exception as e:
                    self._log(f"каталог в пробе: {e}")
            threading.Thread(target=catalog, daemon=True).start()

        def settings():
            try:
                pc = jclass("com.exteragram.messenger.plugins.PluginsController")
                plugin = pc.getInstance().plugins.get(__id__)
                activity_cls = jclass("com.exteragram.messenger.plugins.ui.PluginSettingsActivity")
                get_last_fragment().presentFragment(activity_cls(plugin))
                self._log("проба: открыл настройки")

                def grab():
                    la = jclass("org.telegram.ui.LaunchActivity").instance
                    decor = la.getWindow().getDecorView()
                    err = str(self._call("snapshot", decor, jint(0), base + "/pe_settings.png"))
                    self._log(f"снимок settings: {err or 'ok'}")
                run_on_ui_thread(grab, 2500)
            except Exception as e:
                self._log(f"проба настроек: {e}")
        def texts():
            try:
                samples = [("ты дебил", False), ("ты дебил", True), ("люблю тебя ❤️", False),
                           ("ахахах 😂", True), ("мне грустно", False), ("бля, пиздец", False)]
                for i, (t, out) in enumerate(samples):
                    def one(t=t, out=out):
                        mood = str(self._call("mood", t))
                        ev = str(self._call("testText", t, out))
                        self._log(f"проба текста: {t!r} {'наш' if out else 'чужой'} -> {mood} / {ev or 'нет'}")
                    threading.Timer(1.5 * i, one).start()
            except Exception as e:
                self._log(f"проба текста: {e}")

        def dialogs():
            try:
                info = str(self._call("dialogsInfo"))
                self._log(f"проба списка: {info}")
                ids = info.split("|", 1)[1].split() if "|" in info else []
                if ids:
                    did = int(ids[0].split("@")[0])
                    ok = bool(self._call("lookAtDialog", did))
                    self._log(f"проба списка: смотрим на {did}: {ok}")
            except Exception as e:
                self._log(f"проба списка: {e}")

        if os.path.exists(base + "/pixel_eyes_probe_dialogs"):
            threading.Timer(3, dialogs).start()
            threading.Timer(15, dialogs).start()
            threading.Timer(5, texts).start()
            return
        run_on_ui_thread(shoot, 3000)
        run_on_ui_thread(settings, 6000)

    def on_plugin_unload(self):
        self._unloaded = True
        self._gen = int(getattr(self, "_gen", 0)) + 1
        if getattr(self, "_core", None) is not None:
            try:
                self._call("setListener", None)
                self._call("uninstall")
            except Exception:
                pass
            self._flush_core_log()
        self._core = None
        self._listener = None

    def _add_menu(self):
        if MenuItemData is None or MenuItemType is None:
            return
        try:
            self.add_menu_item(MenuItemData(
                menu_type=MenuItemType.MESSAGE_CONTEXT_MENU,
                text="Добавить глаза",
                on_click=self._from_message,
                icon="msg_add",
                item_id="pixel_eyes_add",
                condition=MSG_CONDITION,
            ))
        except TypeError:
            # старый SDK без условий: пункт висел бы на каждом сообщении, обойдёмся без него
            self._log("меню сообщения: SDK без условий, пункт не добавлен")
        except Exception as e:
            self._log(f"меню сообщения: {e}")

    # ---- события ядра ----------------------------------------------------------------

    def _on_core(self, msg):
        if getattr(self, "_unloaded", False):
            return
        kind, _, arg = msg.partition(":")
        if kind == "pos":
            try:
                x, y = arg.split(",")
                self.set_setting("pos_x", x)
                self.set_setting("pos_y", y)
            except Exception as e:
                self._log(f"позиция: {e}")
        elif kind == "select":
            self.set_setting("skin", arg)
            self._apply_skin()
            name = self._skin_name(arg)
            if name:
                self._toast(f"Глаза: {name}")
        elif kind == "long":
            run_on_ui_thread(lambda: self._skin_actions(arg))
        elif kind == "pick":
            self._install_from_catalog(arg)

    # ---- библиотека -------------------------------------------------------------------

    def _user_dir(self):
        base = ApplicationLoader.applicationContext.getExternalFilesDir(None)
        path = str(base.getAbsolutePath()) + "/pixel_eyes"
        try:
            os.makedirs(path, exist_ok=True)
        except Exception:
            pass
        return path

    def _builtins(self):
        out = []
        if self._core is None:
            return out
        try:
            for i in range(int(self._call("builtinCount"))):
                out.append(str(self._call("builtinJson", jint(i))))
        except Exception as e:
            self._log(f"встроенные: {e}")
        return out

    def _user_files(self):
        """Свои глаза: (путь, текст) для каждого .json в папке."""
        out = []
        folder = self._user_dir()
        try:
            names = sorted(os.listdir(folder))
        except Exception:
            names = []
        for n in names:
            if not (n.endswith(".json") or n.endswith(".eyes")):
                continue
            try:
                with io.open(os.path.join(folder, n), encoding="utf-8") as f:
                    out.append((os.path.join(folder, n), f.read()))
            except Exception:
                continue
        return out

    def _library(self):
        """[(id, json, user_path или None)]: встроенные, потом свои. Повторы id у своих пропускаются."""
        seen = set()
        lib = []
        for text in self._builtins():
            sid = self._id_of(text)
            if sid and sid not in seen:
                seen.add(sid)
                lib.append((sid, text, None))
        for path, text in self._user_files():
            sid = self._id_of(text)
            if not sid or sid in seen:
                continue
            if self._core is not None and str(self._call("check", text)):
                continue
            seen.add(sid)
            lib.append((sid, text, path))
        return lib

    @staticmethod
    def _id_of(text):
        try:
            return str(json.loads(text).get("id") or "")
        except Exception:
            return ""

    def _skin_json(self, sid):
        for s, text, _ in self._library():
            if s == sid:
                return text
        return None

    def _skin_name(self, sid):
        text = self._skin_json(sid)
        try:
            return str(json.loads(text).get("name") or sid) if text else ""
        except Exception:
            return sid

    def _apply_skin(self):
        if self._core is None:
            return
        sid = str(self._opt("skin", "") or "")
        text = self._skin_json(sid) if sid else None
        if text is None:
            try:
                sid = str(self._call("defaultId"))
                text = self._skin_json(sid)
            except Exception:
                text = None
        if text is None:
            return
        err = str(self._call("setSkin", text) or "")
        if err:
            self._log(f"глаза {sid}: {err}")

    def _save_user(self, text, select=True):
        """Сохраняет свои глаза. Возвращает (id, имя) или кидает ValueError с понятной причиной."""
        if self._core is None:
            raise ValueError("ядро не загружено")
        text = self._clean_json(text)
        err = str(self._call("check", text) or "")
        if err:
            raise ValueError(err)
        data = json.loads(text)
        lib = self._library()
        builtin_ids = {s for s, _, p in lib if p is None}
        taken = {s for s, _, _ in lib}
        base_id = re.sub(r"[^a-z0-9_-]", "", str(data.get("id") or "custom").lower())[:28] or "custom"
        sid = base_id
        n = 2
        while sid in builtin_ids or (sid in taken and self._user_path(sid) is None):
            sid = f"{base_id}_{n}"
            n += 1
        data["id"] = sid
        if not data.get("author"):
            data["author"] = self._username()
        path = self._user_path(sid) or os.path.join(self._user_dir(), sid + ".json")
        with io.open(path, "w", encoding="utf-8") as f:
            f.write(json.dumps(data, ensure_ascii=False, indent=2))
        if select:
            self.set_setting("skin", sid)
            self._apply_skin()
        return sid, str(data.get("name") or sid)

    def _user_path(self, sid):
        for s, _, path in self._library():
            if s == sid and path:
                return path
        return None

    @staticmethod
    def _clean_json(text):
        """Текст из сообщения или буфера: убираем обёртку ``` и всё до первой {."""
        t = str(text or "").strip()
        t = re.sub(r"^```[a-zA-Z]*", "", t).strip()
        if t.endswith("```"):
            t = t[:-3].strip()
        start = t.find("{")
        end = t.rfind("}")
        if start < 0 or end < start:
            raise ValueError("не похоже на JSON с глазами")
        return t[start:end + 1]

    @staticmethod
    def _username():
        try:
            from client_utils import get_user_config
            user = get_user_config().getCurrentUser()
            if user is not None and user.username:
                return "@" + str(user.username)
        except Exception:
            pass
        return ""

    # ---- импорт и экспорт ------------------------------------------------------------

    def _clipboard(self):
        ctx = ApplicationLoader.applicationContext
        cm = ctx.getSystemService(jclass("android.content.Context").CLIPBOARD_SERVICE)
        return cm

    def _import_clipboard(self, *args):
        try:
            clip = self._clipboard().getPrimaryClip()
            if clip is None or clip.getItemCount() == 0:
                self._toast("Буфер обмена пуст", "error")
                return
            text = str(clip.getItemAt(0).coerceToText(ApplicationLoader.applicationContext))
            sid, name = self._save_user(text)
            self._toast(f"Добавлены глаза \"{name}\"", "success")
        except ValueError as e:
            self._toast(f"Не получилось: {e}", "error")
        except Exception as e:
            self._toast(f"Не получилось: {e}", "error")

    def _export_current(self, *args):
        try:
            sid = str(self._opt("skin", "") or "")
            text = self._skin_json(sid) or self._skin_json(str(self._call("defaultId")))
            clip = jclass("android.content.ClipData").newPlainText("Pixel Eyes", text)
            self._clipboard().setPrimaryClip(clip)
            self._toast("JSON глаз скопирован: отправьте его в чат, там его можно добавить себе", "success")
        except Exception as e:
            self._toast(f"Не скопировалось: {e}", "error")

    @staticmethod
    def _http_get(url, limit=300_000):
        URL = jclass("java.net.URL")
        conn = URL(url).openConnection()
        conn.setConnectTimeout(10000)
        conn.setReadTimeout(15000)
        conn.setRequestProperty("User-Agent", "PixelEyes/1.0")
        code = int(conn.getResponseCode())
        if code != 200:
            raise ValueError(f"сервер ответил {code}")
        stream = conn.getInputStream()
        try:
            scanner = jclass("java.util.Scanner")(stream, "UTF-8").useDelimiter("\\A")
            text = str(scanner.next()) if scanner.hasNext() else ""
        finally:
            stream.close()
        if len(text) > limit:
            raise ValueError("слишком большой файл")
        return text

    def _import_url(self, *args):
        url = str(self._opt("import_url", "") or "").strip()
        if not url.startswith("http"):
            self._toast("Вставьте ссылку на JSON с глазами в поле выше", "error")
            return
        self._toast("Скачиваю глаза...")

        def work():
            try:
                text = self._http_get(url)
                sid, name = self._save_user(text)
                self._toast(f"Добавлены глаза \"{name}\"", "success")
            except Exception as e:
                self._toast(f"Не получилось: {e}", "error")
        threading.Thread(target=work, daemon=True).start()

    def _from_message(self, context):
        try:
            mo = context.get("message")
            if mo is None:
                return
            text = None
            owner = mo.messageOwner
            raw = str(owner.message) if owner is not None and owner.message is not None else ""
            if '"eye"' in raw:
                text = raw
            else:
                fl = jclass("org.telegram.messenger.FileLoader").getInstance(int(mo.currentAccount))
                f = fl.getPathToMessage(owner)
                if f is None or not f.exists():
                    self._toast("Сначала скачайте файл, потом добавляйте", "error")
                    return
                if int(f.length()) > 300_000:
                    self._toast("Файл слишком большой для глаз", "error")
                    return
                with io.open(str(f.getAbsolutePath()), encoding="utf-8") as fh:
                    text = fh.read()
            sid, name = self._save_user(text)
            self._toast(f"Добавлены и выбраны глаза \"{name}\"", "success")
        except ValueError as e:
            self._toast(f"Это не глаза: {e}", "error")
        except Exception as e:
            self._toast(f"Не получилось: {e}", "error")

    # ---- каталог -----------------------------------------------------------------------

    def _fetch_catalog(self):
        """Скачивает каталог (долго, не на потоке интерфейса). Список JSON или ValueError."""
        last = None
        for url in CATALOG_URLS:
            try:
                index = json.loads(self._http_get(url))
                base = url.rsplit("/", 1)[0] + "/"
                skins = []
                for entry in index.get("skins", [])[:60]:
                    try:
                        rel = str(entry.get("url") or "")
                        full = rel if rel.startswith("http") else base + rel
                        text = self._http_get(full)
                        if self._core is not None and not str(self._call("check", text)):
                            skins.append(text)
                    except Exception as e:
                        self._log(f"каталог {entry.get('id')}: {e}")
                if not skins:
                    raise ValueError("в каталоге пусто")
                self._catalog = {self._id_of(t): t for t in skins}
                self._log(f"каталог: {len(skins)} с {url}")
                return skins
            except Exception as e:
                last = e
                self._log(f"каталог {url}: {e}")
        raise ValueError(str(last))

    def _open_catalog(self, *args):
        self._toast("Загружаю каталог...")

        def work():
            try:
                skins = self._fetch_catalog()
                run_on_ui_thread(lambda: self._show_catalog(skins))
            except Exception as e:
                self._toast(f"Каталог недоступен: {e}", "error")
        threading.Thread(target=work, daemon=True).start()

    def _show_catalog(self, skins):
        try:
            from ui.alert import AlertDialogBuilder
            activity = self._context()
            view = self._call("createGallery", activity, SEP.join(skins), "", "catalog")
            scroll = jclass("android.widget.ScrollView")(activity)
            scroll.addView(view)
            builder = AlertDialogBuilder(activity)
            builder.set_title("Каталог глаз: нажмите, чтобы добавить")
            self._set_view(builder, scroll, 0.62)
            builder.set_positive_button("Закрыть", lambda d, w: d.dismiss())
            builder.show()
        except Exception as e:
            self._toast(f"Каталог: {e}", "error")

    def _install_from_catalog(self, sid):
        text = self._catalog.get(sid)
        if not text:
            return
        try:
            new_id, name = self._save_user(text)
            self._toast(f"Добавлены и выбраны глаза \"{name}\"", "success")
        except Exception as e:
            self._toast(f"Не получилось: {e}", "error")

    # ---- редактор ------------------------------------------------------------------------

    def _set_view(self, builder, view, fraction):
        """Вью в окно: высота в dp, доля экрана. Без высоты окно с длинным
        содержимым уехало бы за край."""
        try:
            dm = ApplicationLoader.applicationContext.getResources().getDisplayMetrics()
            height = int(dm.heightPixels / dm.density * fraction)
        except Exception:
            height = 420
        try:
            builder.set_view(view, height)
        except TypeError:
            builder.set_view(view)

    def _context(self):
        try:
            fragment = get_last_fragment() if get_last_fragment is not None else None
            activity = fragment.getParentActivity() if fragment is not None else None
            if activity is not None:
                return activity
        except Exception:
            pass
        return ApplicationLoader.applicationContext

    def _open_editor(self, source_json=None, view=None):
        if self._core is None:
            return
        try:
            from ui.alert import AlertDialogBuilder
            activity = self._context()
            if view is None:
                view = self._call("createEditor", activity, source_json or "")
            else:
                parent = view.getParent()
                if parent is not None:
                    parent.removeView(view)
            builder = AlertDialogBuilder(activity)
            builder.set_title("Свои глаза")
            self._set_view(builder, view, 0.66)

            def save(dialog, which):
                text = str(self._call("editorJson", view, None) or "")
                if text.startswith("!"):
                    self._toast(text[1:], "error")
                    run_on_ui_thread(lambda: self._open_editor(view=view), 300)
                    return
                try:
                    sid, name = self._save_user(text)
                    self._toast(f"Глаза \"{name}\" сохранены и выбраны", "success")
                except Exception as e:
                    self._toast(f"Не сохранилось: {e}", "error")
                    run_on_ui_thread(lambda: self._open_editor(view=view), 300)
            builder.set_positive_button("Сохранить", save)
            builder.set_negative_button("Отмена", lambda d, w: d.dismiss())
            builder.show()
        except Exception as e:
            self._toast(f"Редактор: {e}", "error")
            self._log(f"редактор: {e}")

    def _edit_current(self, *args):
        sid = str(self._opt("skin", "") or "")
        text = self._skin_json(sid)
        if text and self._user_path(sid) is None:
            # встроенные не трогаем: правим копию
            try:
                data = json.loads(text)
                data["id"] = "my_" + str(data.get("id", "eyes"))
                data["name"] = str(data.get("name", "")) + " (моя)"
                data["author"] = ""
                text = json.dumps(data, ensure_ascii=False)
            except Exception:
                pass
        self._open_editor(text)

    def _skin_actions(self, sid):
        """Долгое нажатие по карточке: поделиться, изменить, удалить свои."""
        try:
            from ui.alert import AlertDialogBuilder
            text = self._skin_json(sid)
            if not text:
                return
            name = self._skin_name(sid)
            user = self._user_path(sid)
            builder = AlertDialogBuilder(self._context())
            builder.set_title(name)
            builder.set_message("Свои глаза" if user else "Встроенные глаза")

            def share(d, w):
                clip = jclass("android.content.ClipData").newPlainText("Pixel Eyes", text)
                self._clipboard().setPrimaryClip(clip)
                self._toast("JSON скопирован: отправьте его в чат", "success")

            def edit(d, w):
                if user:
                    self._open_editor(text)
                else:
                    self.set_setting("skin", sid)
                    self._edit_current()

            builder.set_positive_button("Скопировать", share)
            builder.set_neutral_button("Изменить" if user else "Изменить копию", edit)
            if user:
                def delete(d, w):
                    try:
                        os.remove(user)
                        if str(self._opt("skin", "")) == sid:
                            self.set_setting("skin", "")
                            self._apply_skin()
                        self._toast(f"Глаза \"{name}\" удалены")
                    except Exception as e:
                        self._toast(f"Не удалилось: {e}", "error")
                builder.set_negative_button("Удалить", delete)
            builder.show()
            if user:
                try:
                    builder.make_button_red(-2)
                except Exception:
                    pass
        except Exception as e:
            self._log(f"действия с глазами: {e}")

    def _reset_position(self, *args):
        self.set_setting("pos_x", "0.82")
        self.set_setting("pos_y", "0.22")
        self._push()
        self._toast("Глаза вернулись на место")

    def _open_url(self, url):
        """Ссылка во встроенном браузере клиента, а если его нет, в обычном."""
        def go():
            ctx = self._context()
            try:
                jclass("org.telegram.messenger.browser.Browser").openUrl(ctx, url)
                return
            except Exception as e:
                self._log(f"Browser.openUrl: {e}")
            try:
                Intent = jclass("android.content.Intent")
                Uri = jclass("android.net.Uri")
                intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
            except Exception as e:
                self._toast(f"Не открылось: {url}", "error")
                self._log(f"ссылка: {e}")
        run_on_ui_thread(go)

    def _show_format(self, *args):
        try:
            from ui.alert import AlertDialogBuilder
            builder = AlertDialogBuilder(self._context())
            builder.set_title("Как сделать свои глаза")
            builder.set_message(FORMAT_HELP + "\n\nПодробная инструкция с примерами: " + GUIDE_URL
                                + "\nОнлайн-редактор: " + EDITOR_URL)
            builder.set_positive_button("Понятно", lambda d, w: d.dismiss())
            builder.show()
        except Exception as e:
            self._log(f"справка: {e}")

    # ---- журнал ядра ----------------------------------------------------------------------

    def _restart_tick(self):
        if self._opt("debug", False) and not self._ticking:
            self._ticking = True
            gen = self._gen
            run_on_ui_thread(lambda: self._stats_tick(gen), STATS_EVERY_MS)

    def _flush_core_log(self):
        try:
            text = str(self._call("drainLog") or "")
            if text:
                self._write_log(text)
        except Exception:
            pass

    def _stats_tick(self, gen):
        if gen != self._gen or not self._opt("debug", False):
            if gen == self._gen:
                self._ticking = False
            return
        if self._core is not None:
            self._flush_core_log()
            try:
                stats = str(self._call("stats"))
                if stats != self._last_stats:
                    self._last_stats = stats
                    self._log("ядро: " + stats)
            except Exception:
                pass
        run_on_ui_thread(lambda: self._stats_tick(gen), STATS_EVERY_MS)

    # ---- экран настроек ---------------------------------------------------------------------

    def _ctl(self, cls, **kw):
        key = kw.get("key")
        if key:
            kw["on_change"] = lambda value, k=key: self._changed(k, value)
        try:
            return cls(**kw)
        except TypeError:
            kw.pop("on_change", None)
            try:
                return cls(**kw)
            except TypeError:
                kw.pop("icon", None)
                return cls(**kw)

    def _choice(self, key, text, default, items, subtext=None):
        kw = {"key": key, "text": text, "default": default}
        if Selector is not None:
            return self._ctl(Selector, items=items, **kw)
        kw["default"] = str(default)
        kw["subtext"] = subtext or " / ".join(f"{i} {n}" for i, n in enumerate(items))
        return self._ctl(Input, **kw)

    def _button(self, text, on_click, subtext=None):
        kw = {"text": text, "on_click": on_click}
        if subtext:
            kw["subtext"] = subtext
        try:
            return Text(**kw)
        except TypeError:
            kw.pop("subtext", None)
            return Text(**kw)

    def _page(self, title, build, subtext=None):
        try:
            kw = {"text": title, "create_sub_fragment": build}
            if subtext:
                kw["subtext"] = subtext
            return [Text(**kw)]
        except TypeError:
            return [Divider(), Header(text=title)] + build()

    def _preview_items(self):
        if self._core is None or Custom is None:
            return []
        try:
            return [Custom(view=self._call("createPreview", self._context(), ""))]
        except Exception as e:
            self._log(f"пример: {e}")
            return []

    def _gallery_items(self):
        if self._core is None:
            return [Text(text="Ядро не загружено")]
        items = []
        lib = self._library()
        sel = str(self._opt("skin", "") or "") or str(self._call("defaultId"))
        if Custom is not None:
            try:
                view = self._call("createGallery", self._context(), SEP.join(t for _, t, _ in lib), sel, "library")
                items.append(Custom(view=view))
            except Exception as e:
                self._log(f"галерея: {e}")
        if not items:
            for sid, text, _ in lib:
                name = self._skin_name(sid)
                items.append(self._button(("✓ " if sid == sel else "") + name,
                                          lambda *a, s=sid: self._on_core("select:" + s)))
        items.append(Text(text="Нажмите на глаза, чтобы выбрать. Долгое нажатие: поделиться, "
                               "изменить или удалить свои"))
        return items

    def _test_phrase(self, text):
        text = str(text or "").strip()
        if not text or self._core is None:
            return
        try:
            mood = str(self._call("mood", text))
            self._toast(f"Глаза поймут: {MOOD_NAMES.get(mood, mood)}")
            event = {"insult": "emo:scared", "love": "emo:love", "laugh": "emo:happy", "sad": "emo:sad"}.get(mood)
            if event:
                self._call("event", event)
        except Exception as e:
            self._log(f"проверка фразы: {e}")

    def _words_items(self):
        hint = "Через запятую. Звёздочка в конце слова: любое окончание"
        return [
            Header(text="Свои слова"),
            Text(text="Добавляются к встроенным, регистр и ё не важны"),
            self._ctl(Input, key="words_insult", text="Гадости", default="",
                      subtext=hint + ". Например: ты бот, чепух*"),
            self._ctl(Input, key="words_love", text="Любовь", default="",
                      subtext=hint + ". Например: зая, котёнок, мой хороший"),
            self._ctl(Input, key="words_laugh", text="Смех", default="",
                      subtext=hint + ". Например: кек, хд, жиза"),
            self._ctl(Input, key="words_sad", text="Грусть", default="",
                      subtext=hint + ". Например: эх, жаль, печалька"),
            Header(text="Не реагировать"),
            self._ctl(Input, key="words_ignore", text="Никогда не считать", default="",
                      subtext="Эти слова глаза пропускают совсем, даже встроенные. Например, шутливые "
                              "прозвища друзей: дурак, сучка, дебил*"),
            Header(text="Проверка"),
            self._ctl(Input, key="words_test", text="Проверить фразу", default="",
                      subtext="Напишите фразу: глаза покажут реакцию, а снизу появится, что они поняли"),
        ]

    def _reaction_items(self):
        S = Switch
        return [
            Header(text="На что смотрят"),
            self._ctl(S, key="follow_touch", text="Следить за пальцем", default=True,
                      subtext="Где бы вы ни коснулись экрана, глаза смотрят туда"),
            self._ctl(S, key="follow_typing", text="Следить за набором", default=True,
                      subtext="Пока печатаете, глаза бегают за курсором"),
            self._ctl(S, key="stare", text="Смотреть на вас", default=True,
                      subtext="Перестали печатать: глаза поднимаются и смотрят прямо на вас"),
            Header(text="На что реагируют"),
            self._ctl(S, key="react_messages", text="Новые сообщения", default=True,
                      subtext="Оглядываются на пришедшее, грустят, когда сообщение удалили"),
            self._ctl(S, key="react_send", text="Отправка", default=True,
                      subtext="Радуются вашему отправленному сообщению"),
            self._ctl(S, key="react_love", text="Любовь", default=True,
                      subtext="Влюбляются от сердечек и признаний: ваших и собеседника"),
            self._ctl(S, key="react_text", text="Понимают текст сообщений", default=True,
                      subtext="Пишут гадости вам: пугаются и плачут. Грубите вы: злятся вместе с вами. "
                              "Смешное смешит, грустное печалит. Простой мат как междометие не считается"),
        ] + self._page("Свои слова для текста", self._words_items,
                       subtext="Добавить слова для реакций или исключить ненужные") + [
            self._ctl(S, key="react_peer", text="Кто-то печатает", default=True,
                      subtext="В чате смотрят на шапку, в списке чатов на строку \"печатает...\""),
            self._ctl(S, key="react_screen", text="Переходы между экранами", default=True,
                      subtext="Оглядываются, когда открывается новый экран"),
            self._ctl(S, key="react_shake", text="Тряска телефона", default=True,
                      subtext="Встряхните телефон, и у глаз закружится голова"),
            self._ctl(S, key="react_copy", text="Копирование", default=True,
                      subtext="Подмигивают, когда вы что-то копируете"),
            self._ctl(S, key="poke", text="Нажатия по глазам", default=True,
                      subtext="Тап: ай. Три тапа подряд: злятся. Зажать и тащить: перенести. "
                              "Зажать и отпустить: уснуть или проснуться"),
            Header(text="Сон"),
            self._ctl(S, key="sleep", text="Засыпать без дела", default=True,
                      subtext="Сначала клюют носом, потом спят с Z над головой"),
            self._ctl(Input, key="sleep_sec", text="Засыпать через, секунд", default="90",
                      subtext="От 10 до 3600"),
            self._ctl(S, key="night", text="Сонные ночью", default=True,
                      subtext="С полуночи до шести утра глаза тяжелее и засыпают быстрее"),
            self._ctl(S, key="battery", text="Устают от разряда", default=True,
                      subtext="Меньше 15% заряда без зарядки: глаза уставшие и грустные"),
        ]

    def _own_items(self):
        folder = self._user_dir()
        return [
            Header(text="Сделать свои"),
            self._button("Нарисовать новые", lambda *a: self._open_editor(None),
                         subtext="Редактор с живым примером: глаз, радужка, блик, палитра"),
            self._button("Изменить текущие", self._edit_current,
                         subtext="Встроенные откроются копией, оригинал не тронется"),
            self._button("Как устроен файл глаз", self._show_format),
            self._button("Инструкция и онлайн-редактор", lambda *a: self._open_url(GUIDE_URL),
                         subtext="Подробно про каждое поле, урок с нуля, рисование в браузере"),
            Header(text="Добавить чужие"),
            self._button("Каталог глаз", self._open_catalog,
                         subtext="Глаза от других людей с живым примером, нажмите, чтобы добавить"),
            self._button("Из буфера обмена", self._import_clipboard,
                         subtext="Скопируйте JSON с глазами и нажмите сюда"),
            self._ctl(Input, key="import_url", text="Ссылка на JSON", default="",
                      subtext="Например, raw-ссылка с GitHub"),
            self._button("Скачать по ссылке", self._import_url),
            Text(text="Из сообщения",
                 subtext="Зажмите сообщение с JSON глаз или файлом .json, в меню будет \"Добавить глаза\""),
            Text(text="Папка с глазами", subtext=folder + "\nФайлы .json отсюда появятся в списке сами"),
            Header(text="Поделиться"),
            self._button("Скопировать текущие", self._export_current,
                         subtext="JSON в буфер: отправьте другу, он добавит их из сообщения"),
        ]

    def create_settings(self):
        try:
            if getattr(self, "_core", None) is not None:
                self._push()
                self._restart_tick()
        except Exception:
            pass
        if getattr(self, "_core", None) is None:
            return [
                Header(text="Pixel Eyes"),
                Text(text="Ядро не загрузилось",
                     subtext=str(getattr(self, "_core_status", "неизвестно"))),
                self._ctl(Switch, key="debug", text="Запись диагностики", default=False),
            ]
        sid = str(self._opt("skin", "") or "") or str(self._call("defaultId"))
        items = [
            Header(text="Pixel Eyes"),
            self._ctl(Switch, key="enabled", text="Включено", default=True),
        ]
        items += self._preview_items()
        items += self._page("Выбрать глаза", self._gallery_items, subtext=f"Сейчас: {self._skin_name(sid)}")
        items += [
            self._choice("size", "Размер", 2, SIZE_NAMES),
            self._choice("opacity", "Непрозрачность", 0, OPACITY_NAMES),
            self._choice("where", "Где показывать", 0, WHERE_NAMES),
        ]
        items += self._page("Реакции и сон", self._reaction_items,
                            subtext="Палец, набор, сообщения, тряска, сон")
        items += self._page("Свои глаза и каталог", self._own_items,
                            subtext="Редактор, импорт, обмен")
        items += [
            Divider(),
            Header(text="Дополнительно"),
            self._choice("edge", "Обводка", 0, EDGE_NAMES),
            self._button("Вернуть глаза на место", self._reset_position,
                         subtext="Если утащили за край или потеряли"),
            self._ctl(Switch, key="debug", text="Запись диагностики", default=False,
                      subtext="Пишет ход работы в pixel_eyes_log.txt"),
        ]
        return items
