/* Общее для страниц: тема, меню, плавающие глаза, библиотека глаз, галерея. */
(function () {
  'use strict';

  var PE = window.PixelEyes;
  var REPO = 'https://github.com/van1lLove/pixel-eyes';
  var DOWNLOAD = REPO + '/releases/latest/download/pixel_eyes.plugin';
  var BUILTIN = ['cartoon', 'cartoon_pink', 'anime', 'kawaii', 'cat', 'retro', 'robot', 'sleepy', 'grumpy', 'demon',
    'bloodshot', 'void', 'cyclops', 'swarm'];

  function store(key, value) {
    try {
      if (value === undefined) {
        return localStorage.getItem(key);
      }
      if (value === null) {
        localStorage.removeItem(key);
      } else {
        localStorage.setItem(key, value);
      }
    } catch (e) {
      return null;
    }
    return null;
  }

  // ---- тема ------------------------------------------------------------------

  var media = window.matchMedia ? window.matchMedia('(prefers-color-scheme: dark)') : null;

  function isDark() {
    var t = document.documentElement.getAttribute('data-theme');
    if (t === 'dark') {
      return true;
    }
    if (t === 'light') {
      return false;
    }
    return !!(media && media.matches);
  }

  function applyTheme() {
    PE.setDark(isDark());
    var b = document.getElementById('theme-btn');
    if (b) {
      var t = document.documentElement.getAttribute('data-theme');
      b.textContent = t === 'dark' ? '🌙' : t === 'light' ? '☀️' : '🌗';
      b.title = t === 'dark' ? 'Тёмная тема' : t === 'light' ? 'Светлая тема' : 'Тема как в системе';
    }
  }

  function cycleTheme() {
    var t = document.documentElement.getAttribute('data-theme');
    var next = !t ? (isDark() ? 'light' : 'dark') : t === 'dark' ? 'light' : '';
    if (next) {
      document.documentElement.setAttribute('data-theme', next);
    } else {
      document.documentElement.removeAttribute('data-theme');
    }
    store('pe-theme', next || null);
    applyTheme();
  }

  // ---- мелочи ----------------------------------------------------------------

  var toastTimer = 0;

  function toast(msg) {
    var t = document.querySelector('.toast');
    if (!t) {
      t = document.createElement('div');
      t.className = 'toast';
      t.setAttribute('role', 'status');
      document.body.appendChild(t);
    }
    t.textContent = msg;
    requestAnimationFrame(function () {
      t.classList.add('show');
    });
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () {
      t.classList.remove('show');
    }, 2600);
  }

  function copy(text, done) {
    var ok = function () {
      toast(done || 'Скопировано');
    };
    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(text).then(ok, function () {
        fallbackCopy(text);
        ok();
      });
    } else {
      fallbackCopy(text);
      ok();
    }
  }

  function fallbackCopy(text) {
    var ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    try {
      document.execCommand('copy');
    } catch (e) {
      // ничего
    }
    ta.remove();
  }

  function download(name, text) {
    var blob = new Blob([text], {type: 'application/json'});
    var a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = name;
    document.body.appendChild(a);
    a.click();
    setTimeout(function () {
      URL.revokeObjectURL(a.href);
      a.remove();
    }, 1000);
  }

  /** На телефоне отдаёт файл в меню "Поделиться" (сразу в Telegram), иначе скачивает. */
  function share(name, text) {
    try {
      var file = new File([text], name, {type: 'application/json'});
      if (navigator.canShare && navigator.canShare({files: [file]})) {
        navigator.share({files: [file], title: name}).catch(function () {
          // отменили
        });
        return;
      }
    } catch (e) {
      // нет File или share
    }
    download(name, text);
    toast('Файл скачан, отправьте его в любой чат Telegram');
  }

  function esc(s) {
    return String(s).replace(/[&<>"]/g, function (c) {
      return {'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;'}[c];
    });
  }

  // ---- библиотека глаз --------------------------------------------------------

  var libPromise = null;

  function fetchText(url) {
    return fetch(url, {cache: 'no-cache'}).then(function (r) {
      if (!r.ok) {
        throw new Error(url + ': ' + r.status);
      }
      return r.text();
    });
  }

  /** Все глаза: встроенные из builtin/, каталог из index.json. [{id, text, raw, source, entry}] */
  function library() {
    if (libPromise) {
      return libPromise;
    }
    var builtin = Promise.all(BUILTIN.map(function (id) {
      return fetchText('builtin/' + id + '.json').then(function (text) {
        return {id: id, text: text, raw: JSON.parse(text), source: 'builtin'};
      }).catch(function () {
        return null;
      });
    }));
    var catalog = fetchText('index.json').then(function (t) {
      var index = JSON.parse(t);
      return Promise.all((index.skins || []).map(function (en) {
        return fetchText(en.url).then(function (text) {
          return {id: en.id, text: text, raw: JSON.parse(text), source: 'catalog', entry: en};
        }).catch(function () {
          return null;
        });
      }));
    }).catch(function () {
      return [];
    });
    libPromise = Promise.all([builtin, catalog]).then(function (r) {
      return r[0].concat(r[1]).filter(Boolean);
    });
    return libPromise;
  }

  // ---- плавающие глаза (как в плагине: поверх всего) ---------------------------

  var floating = null;

  function setFloating(on, skin) {
    var btn = document.getElementById('float-btn');
    if (btn) {
      btn.setAttribute('aria-pressed', on ? 'true' : 'false');
      btn.style.opacity = on ? '1' : '.55';
    }
    if (!on) {
      if (floating) {
        floating.canvas.remove();
        floating.destroy();
        floating = null;
      }
      return;
    }
    if (floating) {
      return;
    }
    var holder = document.createElement('canvas');
    holder.className = 'floating-eyes';
    holder.title = 'Тап: ай. Три тапа: злятся. Зажать и тащить: перенести. Зажать и отпустить: сон';
    document.body.appendChild(holder);
    var unit = window.innerWidth < 600 ? 3 : 4;
    floating = new PE.Eyes({canvas: holder, skin: skin, unit: unit, floating: true, sleepAfter: 60,
      onDrag: function (dx, dy) {
        var r = holder.getBoundingClientRect();
        var x = Math.max(0, Math.min(window.innerWidth - r.width, r.left + dx));
        var y = Math.max(0, Math.min(window.innerHeight - r.height, r.top + dy));
        holder.style.left = x + 'px';
        holder.style.top = y + 'px';
        holder.style.right = 'auto';
        holder.style.bottom = 'auto';
      },
      onDragEnd: function () {
        var r = holder.getBoundingClientRect();
        store('pe-float-pos', Math.round(r.left / window.innerWidth * 1000) + ',' +
          Math.round(r.top / window.innerHeight * 1000));
      }
    });
    var pos = store('pe-float-pos');
    if (pos) {
      var p = pos.split(',');
      holder.style.left = Math.min(window.innerWidth - 60, p[0] / 1000 * window.innerWidth) + 'px';
      holder.style.top = Math.min(window.innerHeight - 40, p[1] / 1000 * window.innerHeight) + 'px';
      holder.style.right = 'auto';
      holder.style.bottom = 'auto';
    }
  }

  function initFloating() {
    var btn = document.getElementById('float-btn');
    if (!btn) {
      return;
    }
    var saved = store('pe-float');
    var on = saved === null ? window.innerWidth > 700 : saved === '1';
    var skinId = store('pe-float-skin') || 'cartoon';
    var start = function (skin) {
      setFloating(on, skin);
    };
    library().then(function (lib) {
      var hit = lib.filter(function (s) {
        return s.id === skinId;
      })[0] || lib[0];
      start(hit ? hit.text : undefined);
    });
    btn.addEventListener('click', function () {
      on = !on;
      store('pe-float', on ? '1' : '0');
      library().then(function (lib) {
        var hit = lib.filter(function (s) {
          return s.id === (store('pe-float-skin') || 'cartoon');
        })[0];
        setFloating(on, hit ? hit.text : undefined);
        toast(on ? 'Глаза снова с вами' : 'Глаза спрятались');
      });
    });
  }

  function floatingSkin(text, id) {
    if (floating) {
      floating.setSkin(text);
    }
    if (id) {
      store('pe-float-skin', id);
    }
  }

  // ---- реакции на настоящие действия на странице -----------------------------

  function hookPageEvents(getEyes) {
    document.addEventListener('copy', function () {
      getEyes().forEach(function (e) {
        e.event('copy');
      });
    });
    var hits = [];
    window.addEventListener('devicemotion', function (ev) {
      var a = ev.acceleration || ev.accelerationIncludingGravity;
      if (!a || a.x === null) {
        return;
      }
      var g = Math.hypot(a.x || 0, a.y || 0, a.z || 0) - (ev.acceleration ? 0 : 9.8);
      if (g < 14) {
        return;
      }
      var t = Date.now();
      hits = hits.filter(function (h) {
        return t - h < 700;
      });
      hits.push(t);
      if (hits.length >= 3) {
        hits = [];
        getEyes().forEach(function (e) {
          e.event('shake');
        });
      }
    });
  }

  // ---- галерея ----------------------------------------------------------------

  var TAG_ORDER = ['милые', 'жуткие', 'забавные', 'животные', 'техника', 'аниме', 'классика', 'яркие', 'космос'];

  function skinCard(item, opts) {
    var raw = item.raw;
    var card = document.createElement('article');
    card.className = 'card skin-card';
    card.dataset.tags = (raw.tags || []).join('|') + '|' + item.source;
    var stage = document.createElement('div');
    stage.className = 'stage';
    card.appendChild(stage);
    var eyes = new PE.Eyes({skin: item.text, fit: stage, fitPad: 16, fitHeight: 134, maxUnit: 7, minUnit: 2});
    stage.appendChild(eyes.canvas);
    var head = document.createElement('div');
    head.innerHTML = '<h3>' + esc(raw.name || item.id) + '</h3><div class="meta">' +
      (item.source === 'builtin' ? 'встроенные' : 'каталог') + ' · ' + esc(raw.author || 'без автора') +
      ' · <code>' + esc(item.id) + '</code></div>';
    card.appendChild(head);
    var desc = document.createElement('div');
    desc.className = 'desc';
    desc.textContent = raw.description || '';
    card.appendChild(desc);
    var tags = document.createElement('div');
    tags.className = 'row';
    (raw.tags || []).forEach(function (t) {
      var s = document.createElement('span');
      s.className = 'tag';
      s.textContent = t;
      tags.appendChild(s);
    });
    card.appendChild(tags);
    var row = document.createElement('div');
    row.className = 'row';
    var btn = function (label, cls, fn) {
      var b = document.createElement('button');
      b.className = 'btn small ' + (cls || 'alt');
      b.type = 'button';
      b.textContent = label;
      b.addEventListener('click', fn);
      row.appendChild(b);
      return b;
    };
    btn('Скопировать', 'alt', function () {
      copy(item.text, 'JSON скопирован: в плагине "Из буфера обмена"');
    });
    btn('Файл', 'alt', function () {
      share(item.id + '.json', item.text);
    });
    var edit = document.createElement('a');
    edit.className = 'btn small purple';
    edit.href = 'editor.html#skin=' + encodeURIComponent(item.id);
    edit.textContent = 'Изменить';
    row.appendChild(edit);
    if (opts && opts.onPick) {
      btn('👀', 'alt', function () {
        opts.onPick(item, eyes);
      }).title = 'Посадить эти глаза поверх страницы';
    }
    card.appendChild(row);
    return card;
  }

  function gallery(container, filterBox, opts) {
    library().then(function (lib) {
      container.innerHTML = '';
      lib.forEach(function (item) {
        container.appendChild(skinCard(item, opts));
      });
      if (!filterBox) {
        return;
      }
      var tags = {};
      lib.forEach(function (it) {
        (it.raw.tags || []).forEach(function (t) {
          tags[t] = (tags[t] || 0) + 1;
        });
      });
      var names = Object.keys(tags).sort(function (a, b) {
        var ia = TAG_ORDER.indexOf(a);
        var ib = TAG_ORDER.indexOf(b);
        return (ia < 0 ? 99 : ia) - (ib < 0 ? 99 : ib);
      });
      var all = [['', 'Все (' + lib.length + ')'], ['builtin', 'Встроенные'], ['catalog', 'Каталог']]
        .concat(names.map(function (n) {
          return [n, n];
        }));
      all.forEach(function (p, k) {
        var c = document.createElement('button');
        c.type = 'button';
        c.className = 'chip' + (k === 0 ? ' on' : '');
        c.textContent = p[1];
        c.addEventListener('click', function () {
          filterBox.querySelectorAll('.chip').forEach(function (x) {
            x.classList.toggle('on', x === c);
          });
          container.querySelectorAll('.skin-card').forEach(function (card) {
            card.hidden = !!p[0] && card.dataset.tags.split('|').indexOf(p[0]) < 0;
          });
        });
        filterBox.appendChild(c);
      });
    }).catch(function () {
      container.innerHTML = '<p class="muted">Не получилось загрузить глаза. Откройте страницу через интернет, ' +
        'а не как файл.</p>';
    });
  }

  // ---- главная ----------------------------------------------------------------

  function caretPoint(input) {
    var r = input.getBoundingClientRect();
    var cs = getComputedStyle(input);
    var ctx = caretPoint.ctx || (caretPoint.ctx = document.createElement('canvas').getContext('2d'));
    ctx.font = cs.fontWeight + ' ' + cs.fontSize + ' ' + cs.fontFamily;
    var pos = input.selectionStart === null ? input.value.length : input.selectionStart;
    var w = ctx.measureText(input.value.slice(0, pos)).width;
    var x = r.left + parseFloat(cs.paddingLeft) + w - input.scrollLeft;
    return {x: Math.min(r.right - 8, x), y: r.top + r.height / 2};
  }

  function home() {
    var stage = document.getElementById('hero-stage');
    if (!stage) {
      return;
    }
    var hero = new PE.Eyes({skin: undefined, fit: stage, fitPad: 28, fitHeight: 230, maxUnit: 11, minUnit: 4,
      sleepAfter: 45});
    stage.insertBefore(hero.canvas, stage.firstChild);
    var allEyes = function () {
      return [hero, floating].filter(Boolean);
    };
    hookPageEvents(allEyes);
    var chipsBox = document.getElementById('hero-skins');
    library().then(function (lib) {
      var first = lib[0];
      if (first) {
        hero.setSkin(first.text);
      }
      lib.forEach(function (item, k) {
        var c = document.createElement('button');
        c.type = 'button';
        c.className = 'chip' + (k === 0 ? ' on' : '');
        c.textContent = item.raw.name || item.id;
        c.addEventListener('click', function () {
          hero.setSkin(item.text);
          chipsBox.querySelectorAll('.chip').forEach(function (x) {
            x.classList.toggle('on', x === c);
          });
        });
        chipsBox.appendChild(c);
      });
    });

    var input = document.getElementById('type-demo');
    var hint = document.getElementById('type-hint');
    var last = '';
    var dir = 'out';
    var shown = '';
    var moodTimer = 0;
    var MOOD_NAMES = {love: 'любовь', insult: 'гадости', laugh: 'смех', sad: 'грусть'};
    var WHAT = {
      rude: 'злятся вместе с вами', insulted: 'смотрят на сообщение, пугаются, потом плачут', love: 'влюбляются',
      laugh: 'смеются', sad_text: 'грустят', send: 'радуются отправке', incoming: 'оглядываются на сообщение'
    };
    /** Реакция на текст, как в плагине: свои гадости злят, чужие пугают. */
    var reactTo = function (text, sending) {
      var m = PE.mood(text);
      var ev;
      if (m === 'insult') {
        ev = dir === 'in' ? 'insulted' : 'rude';
      } else if (m !== 'none') {
        ev = {love: 'love', laugh: 'laugh', sad: 'sad_text'}[m];
      } else {
        ev = sending ? (dir === 'in' ? 'incoming' : 'send') : null;
      }
      if (text) {
        hint.innerHTML = m === 'none'
          ? (sending ? 'Обычное сообщение: глаза ' + WHAT[ev] + '.'
            : 'Пока ничего особенного. Попробуйте "люблю", "ахаха", "мне грустно" или гадость.')
          : 'Глаза поняли: <b>' + MOOD_NAMES[m] + '</b>. ' + (dir === 'in' ? 'Пишут вам' : 'Пишете вы') +
            ', поэтому глаза ' + WHAT[ev] + '.';
      }
      if (!ev) {
        return;
      }
      if (!sending && (m + dir) === shown) {
        return;
      }
      shown = sending ? '' : m + dir;
      var r = input.getBoundingClientRect();
      allEyes().forEach(function (e) {
        e.sync();
        e.event(ev, r.left + 30, r.top + r.height / 2);
      });
    };
    input.addEventListener('input', function () {
      var p = caretPoint(input);
      var delta = input.value.length - last.length;
      last = input.value;
      allEyes().forEach(function (e) {
        e.sync();
        e.brain.typing(p.x, p.y, delta);
      });
      clearTimeout(moodTimer);
      moodTimer = setTimeout(function () {
        reactTo(input.value.trim(), false);
      }, 450);
    });
    document.querySelectorAll('#type-dir [data-dir]').forEach(function (b) {
      b.addEventListener('click', function () {
        dir = b.getAttribute('data-dir');
        document.querySelectorAll('#type-dir [data-dir]').forEach(function (x) {
          x.classList.toggle('on', x === b);
        });
        shown = '';
        if (input.value.trim()) {
          reactTo(input.value.trim(), false);
        }
        input.focus();
      });
    });
    input.addEventListener('blur', function () {
      allEyes().forEach(function (e) {
        e.brain.typingDone();
      });
    });
    var send = function () {
      var text = input.value.trim();
      if (!text) {
        return;
      }
      clearTimeout(moodTimer);
      reactTo(text, true);
      input.value = '';
      last = '';
      shown = '';
    };
    input.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') {
        e.preventDefault();
        send();
      }
    });
    document.getElementById('type-send').addEventListener('click', send);

    document.querySelectorAll('[data-event]').forEach(function (b) {
      b.addEventListener('click', function () {
        var ev = b.getAttribute('data-event');
        var r = b.getBoundingClientRect();
        if (ev === 'poke3') {
          hero.brain.poke();
          setTimeout(function () {
            hero.brain.poke();
          }, 180);
          setTimeout(function () {
            hero.brain.poke();
          }, 360);
        } else if (ev === 'poke') {
          hero.brain.poke();
        } else {
          hero.event(ev, r.left + r.width / 2, r.top + r.height / 2);
        }
        stage.scrollIntoView({behavior: 'smooth', block: 'center'});
      });
    });

    var box = document.getElementById('gallery');
    if (box) {
      gallery(box, document.getElementById('gallery-filter'), {
        onPick: function (item) {
          if (!floating) {
            store('pe-float', '1');
            setFloating(true, item.text);
          } else {
            floatingSkin(item.text);
          }
          store('pe-float-skin', item.id);
          toast('Глаза "' + (item.raw.name || item.id) + '" теперь поверх страницы');
        }
      });
    }
  }

  // ---- запуск -----------------------------------------------------------------

  document.addEventListener('DOMContentLoaded', function () {
    applyTheme();
    if (media && media.addEventListener) {
      media.addEventListener('change', applyTheme);
    }
    var tb = document.getElementById('theme-btn');
    if (tb) {
      tb.addEventListener('click', cycleTheme);
    }
    var menu = document.querySelector('.menu-btn');
    if (menu) {
      menu.addEventListener('click', function () {
        var nav = document.querySelector('.nav');
        nav.classList.toggle('open');
        menu.setAttribute('aria-expanded', nav.classList.contains('open') ? 'true' : 'false');
      });
    }
    document.querySelectorAll('a[data-download]').forEach(function (a) {
      a.href = DOWNLOAD;
    });
    var logo = document.getElementById('logo-eyes');
    if (logo) {
      var mini = new PE.Eyes({canvas: logo, unit: 2, interactive: true});
      library().then(function (lib) {
        if (lib[0]) {
          mini.setSkin(lib[0].text);
        }
      });
    }
    initFloating();
    if (document.body.getAttribute('data-page') !== 'home') {
      hookPageEvents(function () {
        return [floating].filter(Boolean);
      });
    }
    home();
  });

  window.PESite = {
    REPO: REPO,
    DOWNLOAD: DOWNLOAD,
    BUILTIN: BUILTIN,
    library: library,
    gallery: gallery,
    toast: toast,
    copy: copy,
    download: download,
    share: share,
    esc: esc,
    store: store,
    isDark: isDark,
    floating: function () {
      return floating;
    }
  };
})();
