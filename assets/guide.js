/* Живые примеры в инструкции: код и глаза рядом, песочницы с ползунками, сетка эмоций. */
(function () {
  'use strict';

  var PE = window.PixelEyes;

  var EMO_LABELS = {
    happy: '😊', love: '😍', surprised: '😮', angry: '😠', sad: '😢', dizzy: '😵', ouch: '><', wink: '😉',
    suspicious: '🤨', stare: '👀', asleep: '😴', curious: '🤔', sleepy: '🥱', focus: '🧐'
  };
  var DEFAULT_EMO = ['happy', 'love', 'surprised', 'angry', 'sad', 'dizzy', 'ouch', 'wink', 'stare', 'asleep'];

  function source(id) {
    var el = document.getElementById(id);
    if (!el) {
      throw new Error('нет примера ' + id);
    }
    return PE.parseJson(el.textContent);
  }

  function editorLink(obj) {
    return 'editor.html#json=' + encodeURIComponent(JSON.stringify(obj));
  }

  function emotionChips(eyes, list) {
    var box = document.createElement('div');
    box.className = 'chips';
    list.forEach(function (name) {
      var b = document.createElement('button');
      b.type = 'button';
      b.className = 'chip';
      b.textContent = EMO_LABELS[name] || name;
      b.title = name;
      b.addEventListener('click', function () {
        if (name === 'ouch') {
          eyes.brain.poke();
        } else if (name === 'asleep' && eyes.brain.napping) {
          eyes.brain.toggleNap();
        } else {
          eyes.event('emo:' + name);
        }
      });
      box.appendChild(b);
    });
    return box;
  }

  function demoBlock(el, obj, controls) {
    el.innerHTML = '';
    var title = document.createElement('div');
    title.className = 'demo-title';
    var t = document.createElement('span');
    t.textContent = el.getAttribute('data-title') || 'Пример';
    title.appendChild(t);
    var acts = document.createElement('span');
    acts.className = 'chips';
    var copyBtn = document.createElement('button');
    copyBtn.type = 'button';
    copyBtn.className = 'chip';
    copyBtn.textContent = 'Скопировать';
    acts.appendChild(copyBtn);
    var open = document.createElement('a');
    open.className = 'chip';
    open.textContent = 'В редактор';
    open.style.textDecoration = 'none';
    acts.appendChild(open);
    title.appendChild(acts);
    el.appendChild(title);

    var pre = document.createElement('pre');
    var code = document.createElement('code');
    pre.appendChild(code);
    el.appendChild(pre);

    var side = document.createElement('div');
    side.className = 'side';
    var stage = document.createElement('div');
    stage.className = 'stage';
    side.appendChild(stage);
    var eyes = new PE.Eyes({skin: obj, fit: stage, fitPad: 24, fitHeight: 190, maxUnit: 9, minUnit: 3});
    stage.appendChild(eyes.canvas);
    var emos = (el.getAttribute('data-emotions') || DEFAULT_EMO.join(',')).split(',').filter(Boolean);
    side.appendChild(emotionChips(eyes, emos));
    var ctlBox = document.createElement('div');
    side.appendChild(ctlBox);
    var warnBox = document.createElement('div');
    warnBox.style.fontSize = '13.5px';
    side.appendChild(warnBox);
    el.appendChild(side);

    var refresh = function () {
      var text = PE.writeJson(obj);
      code.textContent = text;
      open.href = editorLink(obj);
      copyBtn.onclick = function () {
        window.PESite.copy(text, 'JSON скопирован');
      };
      var ok = eyes.setSkin(JSON.parse(JSON.stringify(obj)));
      warnBox.innerHTML = '';
      if (!ok) {
        warnBox.innerHTML = '<div class="note bad"><b>Плагин не примет:</b> ' + window.PESite.esc(eyes.error) + '</div>';
      } else if (el.hasAttribute('data-warn')) {
        var w = PE.warnings(eyes.skin, obj);
        if (w.length) {
          warnBox.innerHTML = '<div class="note warn"><b>Подсказки проверки:</b><ul style="margin:6px 0 0">' +
            w.map(function (x) {
              return '<li>' + window.PESite.esc(x) + '</li>';
            }).join('') + '</ul></div>';
        }
      }
    };
    if (controls) {
      controls.forEach(function (c) {
        ctlBox.appendChild(control(obj, c, refresh));
      });
    }
    refresh();
  }

  // ---- песочница: ползунки меняют поле по пути вида "behavior.speed" или "travel.0"

  function getPath(obj, path) {
    return path.split('.').reduce(function (o, k) {
      return o == null ? undefined : o[k];
    }, obj);
  }

  function setPath(obj, path, value) {
    var keys = path.split('.');
    var o = obj;
    for (var i = 0; i < keys.length - 1; i++) {
      var k = keys[i];
      if (o[k] === undefined || o[k] === null || typeof o[k] !== 'object') {
        o[k] = /^\d+$/.test(keys[i + 1]) ? [] : {};
      }
      o = o[k];
    }
    var last = keys[keys.length - 1];
    if (value === undefined) {
      delete o[last];
    } else {
      o[last] = value;
    }
  }

  function control(obj, c, onChange) {
    var row = document.createElement('label');
    row.style.cssText = 'display:grid;grid-template-columns:120px 1fr 44px;gap:8px;align-items:center;font-size:13.5px;margin:4px 0';
    var name = document.createElement('code');
    name.textContent = c.label || c.path;
    name.style.whiteSpace = 'nowrap';
    row.appendChild(name);
    var val = document.createElement('span');
    val.style.textAlign = 'right';
    val.className = 'muted';
    var input;
    var cur = getPath(obj, c.path);
    if (c.type === 'check') {
      input = document.createElement('input');
      input.type = 'checkbox';
      input.checked = !!cur;
      input.addEventListener('change', function () {
        setPath(obj, c.path, input.checked);
        val.textContent = input.checked ? 'да' : 'нет';
        onChange();
      });
      val.textContent = input.checked ? 'да' : 'нет';
    } else if (c.type === 'select') {
      input = document.createElement('select');
      c.options.forEach(function (o) {
        var op = document.createElement('option');
        op.value = o;
        op.textContent = o;
        input.appendChild(op);
      });
      input.value = cur === undefined ? c.options[0] : cur;
      input.addEventListener('change', function () {
        setPath(obj, c.path, input.value);
        onChange();
      });
    } else {
      input = document.createElement('input');
      input.type = 'range';
      input.min = c.min;
      input.max = c.max;
      input.step = c.step || 1;
      input.value = cur === undefined ? c.def : cur;
      var show = function () {
        val.textContent = String(Number(input.value));
      };
      input.addEventListener('input', function () {
        setPath(obj, c.path, Number(input.value));
        show();
        onChange();
      });
      show();
    }
    input.style.width = '100%';
    row.appendChild(input);
    row.appendChild(val);
    return row;
  }

  // ---- неподвижные кадры для схемы глаза

  function still(obj, setup, unit) {
    var skin = PE.parseSkin(obj);
    var r = new PE.Renderer(skin);
    var f = new PE.Face(skin.places.length);
    if (setup) {
      setup(f);
    }
    r.edge = 0;
    var out = r.render(f);
    var off = document.createElement('canvas');
    off.width = r.cw;
    off.height = r.ch;
    var ctx = off.getContext('2d');
    var img = ctx.createImageData(r.cw, r.ch);
    for (var k = 0; k < out.length; k++) {
      img.data[k * 4] = (out[k] >>> 16) & 0xFF;
      img.data[k * 4 + 1] = (out[k] >>> 8) & 0xFF;
      img.data[k * 4 + 2] = out[k] & 0xFF;
      img.data[k * 4 + 3] = out[k] >>> 24;
    }
    ctx.putImageData(img, 0, 0);
    var c = document.createElement('canvas');
    var dpr = window.devicePixelRatio || 1;
    c.width = r.cw * unit * dpr;
    c.height = r.ch * unit * dpr;
    c.style.width = r.cw * unit + 'px';
    c.style.height = r.ch * unit + 'px';
    c.className = 'pe-canvas';
    var cx = c.getContext('2d');
    cx.imageSmoothingEnabled = false;
    cx.drawImage(off, 0, 0, c.width, c.height);
    return c;
  }

  function anatomy(el) {
    var base = source(el.getAttribute('data-anatomy'));
    var one = JSON.parse(JSON.stringify(base));
    one.layout = {count: 1};
    var noIris = JSON.parse(JSON.stringify(one));
    noIris.iris = ['.'];
    delete noIris.highlight;
    var noHl = JSON.parse(JSON.stringify(one));
    delete noHl.highlight;
    var parts = [
      [noIris, null, 'Яблоко и контур', '"eye" и "sclera"'],
      [noHl, function (f) {
        f.gx[0] = -0.6;
      }, 'Радужка со зрачком', '"iris" и "pupil"'],
      [one, function (f) {
        f.gx[0] = -0.6;
      }, 'Блик', '"highlight"'],
      [one, function (f) {
        f.openTop[0] = 0.45;
      }, 'Веко прикрыто', '"lid", кромка "lash"'],
      [one, function (f) {
        f.openTop[0] = 0;
      }, 'Закрыт, спит', 'линия "‿" цветом "lash"'],
      [one, function (f) {
        f.openTop[0] = 0;
        f.arcBot[0] = 1;
      }, 'Закрыт, радуется', 'линия "∩"'],
      [one, function (f) {
        f.pupil = 1.5;
      }, 'Зрачок шире', 'взгляд на вас, грусть'],
      [one, function (f) {
        f.pupil = 0.5;
      }, 'Зрачок уже', 'удивление, испуг']
    ];
    parts.forEach(function (p) {
      var fig = document.createElement('figure');
      fig.className = 'card';
      var st = document.createElement('div');
      st.className = 'stage checker';
      st.style.height = '110px';
      st.appendChild(still(p[0], p[1], 6));
      fig.appendChild(st);
      var cap = document.createElement('figcaption');
      cap.innerHTML = '<b>' + p[2] + '</b><small>' + p[3] + '</small>';
      fig.appendChild(cap);
      el.appendChild(fig);
    });
  }

  function emoGrid(el) {
    var base = source(el.getAttribute('data-emo-grid'));
    var list = [
      ['happy', 'Радость', 'отправили сообщение'],
      ['love', 'Влюблённость', 'сердечки, "люблю"'],
      ['surprised', 'Удивление', 'новое сообщение в открытом чате, проснулись'],
      ['angry', 'Злость', 'три тапа подряд'],
      ['sad', 'Грусть', 'удалили сообщение'],
      ['dizzy', 'Головокружение', 'тряска, перетаскивание'],
      ['ouch', 'Ай', 'тап по глазам'],
      ['wink', 'Подмигивание', 'скопировали текст'],
      ['suspicious', 'Подозрение', 'стёрли больше 12 символов подряд'],
      ['stare', 'Взгляд на вас', 'перестали печатать'],
      ['focus', 'Сосредоточенность', 'пока печатаете'],
      ['curious', 'Любопытство', 'собеседник печатает'],
      ['sleepy', 'Клюют носом', 'долго без дела'],
      ['asleep', 'Сон', 'ещё дольше без дела, или зажать и отпустить']
    ];
    var all = [];
    list.forEach(function (e) {
      var card = document.createElement('div');
      card.className = 'card';
      var st = document.createElement('div');
      st.className = 'stage';
      card.appendChild(st);
      var eyes = new PE.Eyes({skin: base, fit: st, fitPad: 12, fitHeight: 84, maxUnit: 5, minUnit: 2,
        follow: false, interactive: false});
      st.appendChild(eyes.canvas);
      var cap = document.createElement('div');
      cap.innerHTML = '<b>' + e[1] + '</b><small><code>' + e[0] + '</code> ' + e[2] + '</small>';
      card.appendChild(cap);
      el.appendChild(card);
      all.push([eyes, e[0]]);
    });
    var pulse = function () {
      all.forEach(function (p) {
        var eyes = p[0];
        var name = p[1];
        if (name === 'asleep') {
          eyes.brain.napping = true;
        } else if (name === 'ouch') {
          eyes.brain.feel(7, 1.6);
        } else if (name === 'sleepy') {
          eyes.brain.feel(14, 2);
        } else if (name === 'focus') {
          eyes.brain.feel(12, 2);
        } else {
          eyes.event('emo:' + name);
        }
      });
    };
    pulse();
    setInterval(pulse, 1700);
  }

  // ---- оглавление

  function toc() {
    var desk = document.querySelector('.toc nav');
    var mob = document.querySelector('.toc-mobile nav');
    if (desk && mob) {
      mob.innerHTML = desk.innerHTML;
      mob.addEventListener('click', function (e) {
        if (e.target.tagName === 'A') {
          mob.parentNode.open = false;
        }
      });
    }
    var links = Array.prototype.slice.call(document.querySelectorAll('.toc a[href^="#"]'));
    if (!links.length || typeof IntersectionObserver === 'undefined') {
      return;
    }
    var map = {};
    links.forEach(function (a) {
      map[a.getAttribute('href').slice(1)] = a;
    });
    var io = new IntersectionObserver(function (entries) {
      entries.forEach(function (en) {
        if (en.isIntersecting && map[en.target.id]) {
          links.forEach(function (a) {
            a.classList.remove('on');
          });
          map[en.target.id].classList.add('on');
        }
      });
    }, {rootMargin: '-80px 0px -70% 0px'});
    Object.keys(map).forEach(function (id) {
      var t = document.getElementById(id);
      if (t) {
        io.observe(t);
      }
    });
  }

  document.addEventListener('DOMContentLoaded', function () {
    document.querySelectorAll('[data-demo]').forEach(function (el) {
      try {
        var obj = source(el.getAttribute('data-demo'));
        var ctl = el.getAttribute('data-controls');
        demoBlock(el, obj, ctl ? JSON.parse(ctl) : null);
      } catch (e) {
        el.textContent = 'Пример сломан: ' + e.message;
      }
    });
    document.querySelectorAll('[data-anatomy]').forEach(function (el) {
      try {
        anatomy(el);
      } catch (e) {
        el.textContent = 'Схема сломана: ' + e.message;
      }
    });
    document.querySelectorAll('[data-emo-grid]').forEach(function (el) {
      try {
        emoGrid(el);
      } catch (e) {
        el.textContent = 'Эмоции сломаны: ' + e.message;
      }
    });
    toc();
  });
})();
