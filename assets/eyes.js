/*
 * Pixel Eyes в браузере: разбор JSON, отрисовка и поведение глаз.
 * Перенесено с ядра плагина (Skin.java, Renderer.java, Brain.java), поэтому
 * глаза на сайте выглядят и ведут себя так же, как в Telegram.
 */
(function (root) {
  'use strict';

  var FORMAT = 1;
  var MAX_EYE = 32;
  var MAX_EYES = 12;
  var FRAME_NAMES = ['closed', 'happy', 'love', 'dizzy', 'angry', 'sad', 'ouch', 'surprised'];

  function fail(msg) {
    var e = new Error(msg);
    e.skin = true;
    throw e;
  }

  // ---- JSON ------------------------------------------------------------------
  // Строгий, как json.loads в плагине: без комментариев и лишних запятых.

  function parseJson(text) {
    var s = String(text == null ? '' : text);
    var i = s.charCodeAt(0) === 0xFEFF ? 1 : 0;
    var NUM = /-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/y;

    function err(what) {
      var line = 1;
      var col = 1;
      for (var k = 0; k < Math.min(i, s.length); k++) {
        if (s[k] === '\n') {
          line++;
          col = 1;
        } else {
          col++;
        }
      }
      var e = new Error(what + ' (строка ' + line + ', символ ' + col + ')');
      e.line = line;
      e.col = col;
      e.json = true;
      throw e;
    }

    function ws() {
      while (i < s.length) {
        var c = s[i];
        if (c === ' ' || c === '\t' || c === '\n' || c === '\r') {
          i++;
        } else if (c === '/' && (s[i + 1] === '/' || s[i + 1] === '*')) {
          err('комментарии в JSON нельзя, плагин такой файл не примет');
        } else {
          break;
        }
      }
    }

    function put(m, k, v) {
      Object.defineProperty(m, k, {value: v, enumerable: true, writable: true, configurable: true});
    }

    function value(depth) {
      if (depth > 32) {
        err('слишком глубокая вложенность');
      }
      if (i >= s.length) {
        err('JSON оборвался, не хватает закрывающей скобки');
      }
      var c = s[i];
      if (c === '{') {
        return object(depth);
      }
      if (c === '[') {
        return array(depth);
      }
      if (c === '"') {
        return string();
      }
      if (c === '\'' || c === '«' || c === '“' || c === '”') {
        err('строки берутся в обычные двойные кавычки "..."');
      }
      if (s.startsWith('true', i)) {
        i += 4;
        return true;
      }
      if (s.startsWith('false', i)) {
        i += 5;
        return false;
      }
      if (s.startsWith('null', i)) {
        i += 4;
        return null;
      }
      if (c === '-' || (c >= '0' && c <= '9')) {
        NUM.lastIndex = i;
        var mm = NUM.exec(s);
        if (!mm) {
          err('плохое число');
        }
        i += mm[0].length;
        return Number(mm[0]);
      }
      err('непонятный символ \'' + c + '\'');
    }

    function object(depth) {
      var m = {};
      i++;
      ws();
      if (s[i] === '}') {
        i++;
        return m;
      }
      for (;;) {
        ws();
        if (i >= s.length) {
          err('объект не закрыт, не хватает }');
        }
        if (s[i] !== '"') {
          err(s[i] === '\'' ? 'ключи берутся в двойные кавычки "..."' : 'ожидался ключ в кавычках');
        }
        var k = string();
        ws();
        if (s[i] !== ':') {
          err('после ключа "' + k + '" нужно двоеточие');
        }
        i++;
        ws();
        put(m, k, value(depth + 1));
        ws();
        if (i >= s.length) {
          err('объект не закрыт, не хватает }');
        }
        var c = s[i];
        if (c === '}') {
          i++;
          return m;
        }
        if (c !== ',') {
          err('ожидалась запятая или }');
        }
        i++;
        ws();
        if (s[i] === '}') {
          err('лишняя запятая перед }');
        }
      }
    }

    function array(depth) {
      var a = [];
      i++;
      ws();
      if (s[i] === ']') {
        i++;
        return a;
      }
      for (;;) {
        ws();
        a.push(value(depth + 1));
        ws();
        if (i >= s.length) {
          err('массив не закрыт, не хватает ]');
        }
        var c = s[i];
        if (c === ']') {
          i++;
          return a;
        }
        if (c !== ',') {
          err(c === '"' ? 'между строками сетки не хватает запятой' : 'ожидалась запятая или ]');
        }
        i++;
        ws();
        if (s[i] === ']') {
          err('лишняя запятая перед ]');
        }
      }
    }

    function string() {
      var b = '';
      i++;
      for (;;) {
        if (i >= s.length) {
          err('строка не закрыта, не хватает кавычки');
        }
        var c = s[i];
        if (c === '"') {
          i++;
          return b;
        }
        if (c < ' ') {
          err(c === '\n' ? 'перенос строки внутри кавычек: у каждой строки сетки свои кавычки'
            : 'управляющий символ внутри строки');
        }
        i++;
        if (c !== '\\') {
          b += c;
          continue;
        }
        var e = s[i++];
        switch (e) {
          case '"': b += '"'; break;
          case '\\': b += '\\'; break;
          case '/': b += '/'; break;
          case 'b': b += '\b'; break;
          case 'f': b += '\f'; break;
          case 'n': b += '\n'; break;
          case 'r': b += '\r'; break;
          case 't': b += '\t'; break;
          case 'u':
            var hex = s.substr(i, 4);
            if (!/^[0-9a-fA-F]{4}$/.test(hex)) {
              err('плохой \\u');
            }
            b += String.fromCharCode(parseInt(hex, 16));
            i += 4;
            break;
          default:
            i--;
            err('плохая экранировка \\' + e);
        }
      }
    }

    ws();
    var v = value(0);
    ws();
    if (i !== s.length) {
      err('лишние символы после JSON');
    }
    return v;
  }

  /** Запись как у плагина: сетки по строке на элемент, короткие массивы чисел в строку. */
  function writeJson(o, level) {
    level = level || 0;
    var pad = function (n) {
      return new Array(n + 1).join('  ');
    };
    if (o === null || o === undefined) {
      return 'null';
    }
    if (typeof o === 'string') {
      return JSON.stringify(o);
    }
    if (typeof o === 'boolean') {
      return String(o);
    }
    if (typeof o === 'number') {
      return isFinite(o) ? String(Math.round(o * 1000) / 1000) : '0';
    }
    if (Array.isArray(o)) {
      if (!o.length) {
        return '[]';
      }
      var simple = o.every(function (x) {
        return x === null || typeof x !== 'object';
      });
      var strings = o.every(function (x) {
        return typeof x === 'string';
      });
      var flatObjects = o.every(function (x) {
        return x && typeof x === 'object' && !Array.isArray(x) && Object.keys(x).every(function (k) {
          return x[k] === null || typeof x[k] !== 'object';
        });
      });
      if (simple && !strings) {
        return '[' + o.map(function (x) {
          return writeJson(x, level + 1);
        }).join(', ') + ']';
      }
      return '[\n' + o.map(function (x) {
        if (flatObjects) {
          return pad(level + 1) + '{' + Object.keys(x).map(function (k) {
            return JSON.stringify(k) + ': ' + writeJson(x[k], 0);
          }).join(', ') + '}';
        }
        return pad(level + 1) + writeJson(x, level + 1);
      }).join(',\n') + '\n' + pad(level) + ']';
    }
    var keys = Object.keys(o);
    if (!keys.length) {
      return '{}';
    }
    var flat = level > 0 && keys.length <= 6 && keys.every(function (k) {
      var v = o[k];
      return v === null || typeof v !== 'object' || (Array.isArray(v) && v.length <= 2 && v.every(function (x) {
        return typeof x === 'number';
      }));
    });
    if (flat) {
      return '{' + keys.map(function (k) {
        return JSON.stringify(k) + ': ' + writeJson(o[k], 0);
      }).join(', ') + '}';
    }
    return '{\n' + keys.map(function (k) {
      var v = o[k];
      var text;
      if (k === 'tags' && Array.isArray(v)) {
        text = '[' + v.map(function (x) {
          return JSON.stringify(x);
        }).join(', ') + ']';
      } else if (k === 'palette' && isMap(v) && Object.keys(v).length > 3) {
        text = '{\n' + Object.keys(v).map(function (c) {
          return pad(level + 2) + JSON.stringify(c) + ': ' + JSON.stringify(v[c]);
        }).join(',\n') + '\n' + pad(level + 1) + '}';
      } else {
        text = writeJson(v, level + 1);
      }
      return pad(level + 1) + JSON.stringify(k) + ': ' + text;
    }).join(',\n') + '\n' + pad(level) + '}';
  }

  /** Текст из сообщения или буфера: как в плагине, убираем ``` и всё вокруг { ... }. */
  function cleanPasted(text) {
    var t = String(text || '').trim();
    t = t.replace(/^```[a-zA-Z]*/, '').trim();
    if (t.endsWith('```')) {
      t = t.slice(0, -3).trim();
    }
    var a = t.indexOf('{');
    var b = t.lastIndexOf('}');
    if (a < 0 || b < a) {
      fail('не похоже на JSON с глазами');
    }
    return t.slice(a, b + 1);
  }

  // ---- скин ------------------------------------------------------------------

  function Sprite(w, h) {
    this.w = w;
    this.h = h;
    this.px = new Uint32Array(w * h);
  }

  Sprite.prototype.at = function (x, y) {
    return x < 0 || y < 0 || x >= this.w || y >= this.h ? 0 : this.px[y * this.w + x];
  };

  Sprite.prototype.mirrored = function () {
    var m = new Sprite(this.w, this.h);
    for (var y = 0; y < this.h; y++) {
      for (var x = 0; x < this.w; x++) {
        m.px[y * this.w + x] = this.px[y * this.w + (this.w - 1 - x)];
      }
    }
    return m;
  };

  function isMap(o) {
    return o !== null && typeof o === 'object' && !Array.isArray(o);
  }

  function str(o, d) {
    return typeof o === 'string' ? o : d;
  }

  function bool(o, d) {
    return typeof o === 'boolean' ? o : d;
  }

  function num(o, d) {
    return typeof o === 'number' ? Math.round(o) : d;
  }

  function fnum(o, d) {
    return typeof o === 'number' ? o : d;
  }

  function clampI(v, lo, hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  function clampF(v, lo, hi) {
    if (isNaN(v)) {
      return lo;
    }
    return Math.max(lo, Math.min(hi, v));
  }

  function limit(v, n) {
    v = v.replace(/\n/g, ' ').trim();
    return v.length > n ? v.substring(0, n) : v;
  }

  function cleanId(v) {
    var b = '';
    var low = String(v).toLowerCase();
    for (var k = 0; k < low.length; k++) {
      var c = low[k];
      if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c === '_' || c === '-') {
        b += c;
      } else if (c === ' ') {
        b += '_';
      }
      if (b.length >= 32) {
        break;
      }
    }
    return b || 'custom';
  }

  function pair(o, dx, dy) {
    if (Array.isArray(o) && o.length === 2) {
      return [num(o[0], dx), num(o[1], dy)];
    }
    return [dx, dy];
  }

  function parseColor(v, where) {
    var t = String(v).trim();
    if (t[0] === '#') {
      t = t.substring(1);
    }
    if (/^[0-9a-fA-F]+$/.test(t)) {
      if (t.length === 3) {
        var r = parseInt(t[0], 16) * 17;
        var g = parseInt(t[1], 16) * 17;
        var b = parseInt(t[2], 16) * 17;
        return (0xFF000000 | r << 16 | g << 8 | b) >>> 0;
      }
      if (t.length === 6) {
        return (0xFF000000 | parseInt(t, 16)) >>> 0;
      }
      if (t.length === 8) {
        return parseInt(t, 16) >>> 0;
      }
    }
    fail(where + ': непонятный цвет "' + v + '", нужен #RRGGBB');
  }

  function palette(o) {
    var pal = new Map();
    if (o === undefined || o === null) {
      return pal;
    }
    if (!isMap(o)) {
      fail('"palette" должна быть объектом {"символ": "#цвет"}');
    }
    Object.keys(o).forEach(function (k) {
      if (k.length !== 1) {
        fail('в палитре ключ "' + k + '": нужен ровно один символ');
      }
      if (k === '.' || k === ' ') {
        fail('символы \'.\' и пробел заняты под прозрачность');
      }
      var v = o[k];
      if (typeof v !== 'string') {
        fail('цвет для \'' + k + '\' должен быть строкой "#RRGGBB"');
      }
      pal.set(k, parseColor(v, 'palette.' + k));
    });
    return pal;
  }

  function color(o, pal, d, where) {
    if (typeof o !== 'string' || !o) {
      return d;
    }
    if (o.length === 1 && pal.has(o)) {
      return pal.get(o);
    }
    var low = o.toLowerCase();
    if (low === 'none' || low === 'transparent') {
      return 0;
    }
    return parseColor(o, where);
  }

  function darker(c) {
    var a = c >>> 24;
    var r = Math.trunc(((c >>> 16) & 0xFF) * 6 / 10);
    var g = Math.trunc(((c >>> 8) & 0xFF) * 6 / 10);
    var b = Math.trunc((c & 0xFF) * 6 / 10);
    return (a << 24 | r << 16 | g << 8 | b) >>> 0;
  }

  function rows(o, where) {
    if (o === undefined || o === null) {
      return null;
    }
    if (typeof o === 'string') {
      var r = o.split('\n');
      while (r.length > 1 && r[r.length - 1] === '') {
        r.pop();
      }
      return r;
    }
    if (!Array.isArray(o)) {
      fail('"' + where + '" должна быть массивом строк');
    }
    o.forEach(function (x) {
      if (typeof x !== 'string') {
        fail('в "' + where + '" все строки должны быть в кавычках');
      }
    });
    if (!o.length) {
      fail('"' + where + '" пустая');
    }
    return o.slice();
  }

  function grid(rs, pal, where, maxW, maxH) {
    var w = 0;
    rs.forEach(function (r) {
      w = Math.max(w, r.length);
    });
    var h = rs.length;
    if (w === 0) {
      fail('"' + where + '" пустая');
    }
    if (w > maxW || h > maxH) {
      fail('"' + where + '" ' + w + 'x' + h + ', больше допустимого ' + maxW + 'x' + maxH);
    }
    var s = new Sprite(w, h);
    for (var y = 0; y < h; y++) {
      var r = rs[y];
      for (var x = 0; x < r.length; x++) {
        var c = r[x];
        if (c === '.' || c === ' ') {
          continue;
        }
        if (!pal.has(c)) {
          fail('в "' + where + '" символ \'' + c + '\' (строка ' + (y + 1) + '), а в палитре его нет');
        }
        s.px[y * w + x] = pal.get(c);
      }
    }
    return s;
  }

  function fit(g, w, h) {
    if (g.w === w && g.h === h) {
      return g;
    }
    var out = new Sprite(w, h);
    var ox = Math.trunc((w - g.w) / 2);
    var oy = Math.trunc((h - g.h) / 2);
    for (var y = 0; y < g.h; y++) {
      for (var x = 0; x < g.w; x++) {
        var tx = x + ox;
        var ty = y + oy;
        if (tx >= 0 && ty >= 0 && tx < w && ty < h) {
          out.px[ty * w + tx] = g.px[y * g.w + x];
        }
      }
    }
    return out;
  }

  function Skin() {
    this.id = 'custom';
    this.name = 'Без имени';
    this.author = '';
    this.description = '';
    this.tags = [];
    this.frames = {};
    this.places = [];
    this.blinkMin = 2.5;
    this.blinkMax = 6;
    this.style = 0;
    this.stare = 0;
    this.twitch = 0;
    this.speed = 1;
    this.baseOpen = 1;
    this.baseSlant = 0;
    this.independent = false;
    this.creepy = false;
  }

  Skin.prototype.inside = function (x, y, mirror) {
    if (mirror) {
      x = this.eye.w - 1 - x;
    }
    return x >= 0 && y >= 0 && x < this.eye.w && y < this.eye.h && this.interior[y * this.eye.w + x];
  };

  Skin.prototype.centerX = function () {
    return (this.boxL + this.boxR) / 2;
  };

  Skin.prototype.centerY = function () {
    return (this.boxT + this.boxB) / 2;
  };

  function parseSkin(input) {
    var m = typeof input === 'string' ? parseJson(input) : input;
    if (!isMap(m)) {
      fail('ожидался объект { ... }');
    }
    var s = new Skin();
    s.source = m;
    if (typeof m.format === 'number' && Math.trunc(m.format) > FORMAT) {
      fail('формат ' + Math.trunc(m.format) + ' новее этой версии плагина, обновите плагин');
    }
    s.id = cleanId(str(m.id, 'custom'));
    s.name = limit(str(m.name, s.id), 40);
    s.author = limit(str(m.author, ''), 40);
    s.description = limit(str(m.description, ''), 200);
    if (Array.isArray(m.tags)) {
      m.tags.forEach(function (t) {
        if (typeof t === 'string' && s.tags.length < 8) {
          s.tags.push(limit(t, 20));
        }
      });
    }

    var pal = palette(m.palette);
    s.palette = pal;
    var scleraChars = str(m.sclera, 'w');

    var eyeRows = rows(m.eye, 'eye');
    if (eyeRows === null) {
      fail('нет сетки "eye": это главное поле, сам глаз');
    }
    s.eye = grid(eyeRows, pal, 'eye', MAX_EYE, MAX_EYE);
    s.interior = new Uint8Array(s.eye.w * s.eye.h);
    var count = 0;
    for (var y = 0; y < eyeRows.length; y++) {
      var r = eyeRows[y];
      for (var x = 0; x < s.eye.w && x < r.length; x++) {
        if (scleraChars.indexOf(r[x]) >= 0) {
          s.interior[y * s.eye.w + x] = 1;
          count++;
        }
      }
    }
    if (count === 0) {
      fail('в "eye" нет ни одной клетки яблока (символы из "sclera": ' + scleraChars + ')');
    }
    measure(s);
    s.outlineColor = mostCommonEdge(s);

    var irisRows = rows(m.iris, 'iris');
    if (irisRows !== null) {
      s.iris = grid(irisRows, pal, 'iris', s.eye.w, s.eye.h);
    } else {
      s.iris = new Sprite(2, 2);
      s.iris.px.fill(0xFF111111);
    }
    var io = pair(m.iris_offset, 0, 0);
    s.irisDx = clampI(io[0], -s.eye.w, s.eye.w);
    s.irisDy = clampI(io[1], -s.eye.h, s.eye.h);
    var pupil = str(m.pupil, '');
    s.pupilColor = pupil.length === 1 && pal.has(pupil) ? pal.get(pupil) : 0;

    var hlRows = rows(m.highlight, 'highlight');
    s.highlight = null;
    s.hlDx = 0;
    s.hlDy = 0;
    s.hlFixed = false;
    if (hlRows !== null) {
      s.highlight = grid(hlRows, pal, 'highlight', s.eye.w, s.eye.h);
      var ho = pair(m.highlight_offset, 0, 0);
      s.hlDx = clampI(ho[0], -s.eye.w, s.eye.w);
      s.hlDy = clampI(ho[1], -s.eye.h, s.eye.h);
      s.hlFixed = bool(m.highlight_fixed, false);
    }

    s.lidColor = color(m.lid, pal, s.outlineColor, 'lid');
    s.lashColor = color(m.lash, pal, darker(s.lidColor), 'lash');

    var scW = s.boxR - s.boxL + 1;
    var scH = s.boxB - s.boxT + 1;
    var tr = pair(m.travel, Math.max(1, Math.trunc((scW - s.iris.w) / 2)), Math.max(0, Math.trunc((scH - s.iris.h) / 2)));
    s.travelX = clampI(tr[0], 0, 10);
    s.travelY = clampI(tr[1], 0, 10);

    if (isMap(m.frames)) {
      Object.keys(m.frames).forEach(function (key) {
        if (FRAME_NAMES.indexOf(key) < 0) {
          fail('неизвестный кадр "' + key + '", бывают: ' + FRAME_NAMES.join(', '));
        }
        var fr = rows(m.frames[key], 'frames.' + key);
        if (fr !== null) {
          s.frames[key] = fit(grid(fr, pal, 'frames.' + key, MAX_EYE, MAX_EYE), s.eye.w, s.eye.h);
        }
      });
    }
    layout(s, m.layout);
    behavior(s, m.behavior);
    return s;
  }

  function measure(s) {
    var w = s.eye.w;
    var h = s.eye.h;
    s.colTop = new Int32Array(w);
    s.colBot = new Int32Array(w);
    s.boxL = w;
    s.boxT = h;
    s.boxR = -1;
    s.boxB = -1;
    for (var x = 0; x < w; x++) {
      s.colTop[x] = -1;
      s.colBot[x] = -1;
      for (var y = 0; y < h; y++) {
        if (s.interior[y * w + x]) {
          if (s.colTop[x] < 0) {
            s.colTop[x] = y;
          }
          s.colBot[x] = y;
          s.boxL = Math.min(s.boxL, x);
          s.boxR = Math.max(s.boxR, x);
          s.boxT = Math.min(s.boxT, y);
          s.boxB = Math.max(s.boxB, y);
        }
      }
    }
  }

  function mostCommonEdge(s) {
    var count = new Map();
    for (var k = 0; k < s.eye.px.length; k++) {
      var c = s.eye.px[k];
      if (c === 0 || s.interior[k]) {
        continue;
      }
      count.set(c, (count.get(c) || 0) + 1);
    }
    var best = 0xFF1A1A1A;
    var n = 0;
    count.forEach(function (v, c) {
      if (v > n) {
        n = v;
        best = c;
      }
    });
    return best;
  }

  function layout(s, o) {
    var lm = isMap(o) ? o : null;
    var eyes = lm ? lm.eyes : null;
    if (Array.isArray(eyes)) {
      for (var k = 0; k < eyes.length; k++) {
        var em = eyes[k];
        if (!isMap(em)) {
          continue;
        }
        s.places.push({
          x: clampI(num(em.x, 0), 0, 120),
          y: clampI(num(em.y, 0), 0, 60),
          mirror: bool(em.mirror, false),
          scale: clampI(num(em.scale, 1), 1, 3)
        });
        if (s.places.length >= MAX_EYES) {
          break;
        }
      }
      if (!s.places.length) {
        fail('layout.eyes пустой');
      }
    } else {
      var count = lm ? clampI(num(lm.count, 2), 1, 2) : 2;
      var gap = lm ? clampI(num(lm.gap, 2), 0, 32) : 2;
      var mirror = !lm || bool(lm.mirror, true);
      s.places.push({x: 0, y: 0, mirror: false, scale: 1});
      if (count === 2) {
        s.places.push({x: s.eye.w + gap, y: 0, mirror: mirror, scale: 1});
      }
    }
    var w = 0;
    var h = 0;
    s.places.forEach(function (p) {
      w = Math.max(w, p.x + s.eye.w * p.scale);
      h = Math.max(h, p.y + s.eye.h * p.scale);
    });
    if (w > 128 || h > 64) {
      fail('глаза не помещаются в 128x64 пикселя (' + w + 'x' + h + ')');
    }
  }

  function behavior(s, b) {
    if (!isMap(b)) {
      return;
    }
    if (Array.isArray(b.blink) && b.blink.length === 2) {
      s.blinkMin = clampF(fnum(b.blink[0], 2.5), 0, 60);
      s.blinkMax = clampF(fnum(b.blink[1], 6), s.blinkMin, 60);
    } else if (typeof b.blink === 'number') {
      var v = clampF(b.blink, 0, 60);
      s.blinkMin = v * 0.6;
      s.blinkMax = v * 1.4;
    }
    var st = str(b.style, 'smooth').toLowerCase();
    s.style = st === 'snap' ? 1 : st === 'twitch' ? 2 : 0;
    s.stare = clampF(fnum(b.stare, 0), 0, 1);
    s.twitch = clampF(fnum(b.twitch, 0), 0, 1);
    s.speed = clampF(fnum(b.speed, 1), 0.2, 4);
    s.baseOpen = clampF(fnum(b.open, 1), 0.3, 1);
    s.baseSlant = clampF(fnum(b.slant, 0), -1, 1);
    s.independent = bool(b.independent, false);
    s.creepy = bool(b.creepy, false);
  }

  // ---- лицо и отрисовка -------------------------------------------------------

  var PUPIL_NORMAL = 0;
  var PUPIL_HEART = 1;
  var PUPIL_SPIRAL = 2;
  var PUPIL_OUCH = 3;

  var P_Z = 0;
  var P_HEART = 1;
  var P_ANGER = 2;
  var P_DROP = 3;
  var P_EXCL = 4;
  var P_STAR = 6;
  var P_QUESTION = 7;

  function Face(n) {
    this.n = n;
    this.gx = new Float32Array(n);
    this.gy = new Float32Array(n);
    this.openTop = new Float32Array(n).fill(1);
    this.openBot = new Float32Array(n).fill(1);
    this.arcBot = new Float32Array(n);
    this.slant = 0;
    this.pupil = 1;
    this.pupilMode = PUPIL_NORMAL;
    this.frame = null;
    this.time = 0;
    this.shakeX = 0;
    this.shakeY = 0;
    this.particles = [];
  }

  Face.prototype.signature = function (s) {
    var a = [];
    for (var i = 0; i < this.n; i++) {
      a.push(Math.round(this.gx[i] * s.travelX * 2), Math.round(this.gy[i] * s.travelY * 2),
        Math.round(this.openTop[i] * 32), Math.round(this.openBot[i] * 32), Math.round(this.arcBot[i] * 16));
    }
    a.push(Math.round(this.slant * 16), Math.round(this.pupil * 8), this.pupilMode, this.frame || '',
      this.shakeX, this.shakeY);
    if (this.pupilMode === PUPIL_SPIRAL) {
      a.push(Math.round(this.time * 12));
    }
    this.particles.forEach(function (p) {
      a.push(Math.round(p.x * 2), Math.round(p.y * 2), p.type, Math.round(p.age / p.life * 4));
    });
    return a.join(',');
  };

  var MX = 3;
  var MT = 7;
  var MB = 4;
  var EDGE_NONE = 0;
  var EDGE_SHADOW = 1;
  var EDGE_HALO = 2;
  var NEIGHBOURS = [[1, 0], [-1, 0], [0, 1], [0, -1]];
  // ядро считает во float: повторяем округление до float32, чтобы пиксели совпадали
  var F = Math.fround;
  var HEART5 = ['.#.#.', '#####', '#####', '.###.', '..#..'];
  var HEART3 = ['#.#', '###', '.#.'];
  var GLYPHS = [
    ['####', '..#.', '.#..', '####'],
    ['.#.#.', '#####', '.###.', '..#..'],
    ['##.##', '#...#', '.....', '#...#', '##.##'],
    ['.#.', '###', '###', '.#.'],
    ['#', '#', '#', '.', '#'],
    ['.##', '.#.', '.#.', '##.', '##.'],
    ['.#.', '###', '.#.'],
    ['###', '..#', '.#.', '...', '.#.']
  ];
  var GLYPH_COLORS = [0xFFFFFFFF, 0xFFFF4F7B, 0xFFFF3B3B, 0xFF7FD4FF, 0xFFFFD23F, 0xFFFFFFFF, 0xFFFFE066, 0xFFFFFFFF];

  function blend(src, dst) {
    var sa = src >>> 24;
    if (sa === 255 || dst === 0) {
      return src;
    }
    if (sa === 0) {
      return dst;
    }
    var da = dst >>> 24;
    var a = F(sa / 255);
    var na = F(1 - a);
    var oa = Math.min(255, sa + Math.round(F(da * na)));
    var r = Math.round(F(F(((src >>> 16) & 0xFF) * a) + F(((dst >>> 16) & 0xFF) * na)));
    var g = Math.round(F(F(((src >>> 8) & 0xFF) * a) + F(((dst >>> 8) & 0xFF) * na)));
    var b = Math.round(F(F((src & 0xFF) * a) + F((dst & 0xFF) * na)));
    return (oa << 24 | r << 16 | g << 8 | b) >>> 0;
  }

  function clamp1(v) {
    return Math.max(-1, Math.min(1, v));
  }

  function Renderer(s) {
    this.skin = s;
    var w = 0;
    var h = 0;
    s.places.forEach(function (p) {
      w = Math.max(w, p.x + s.eye.w * p.scale);
      h = Math.max(h, p.y + s.eye.h * p.scale);
    });
    this.cw = w + 2 * MX;
    this.ch = h + MT + MB;
    this.buf = new Uint32Array(this.cw * this.ch);
    this.out = new Uint32Array(this.cw * this.ch);
    this.tmp = new Uint32Array(s.eye.w * s.eye.h);
    this.edge = EDGE_SHADOW;
    this.eyeM = s.eye.mirrored();
    this.framesM = {};
    var self = this;
    Object.keys(s.frames).forEach(function (k) {
      self.framesM[k] = s.frames[k].mirrored();
    });
    this.irisMain = mainColor(s.iris, s.pupilColor);
    this.pupilLv = [];
    this.pupilLvM = [];
    for (var lv = -2; lv <= 2; lv++) {
      var v = this.pupilVariant(s.iris, lv);
      this.pupilLv[lv + 2] = v;
      this.pupilLvM[lv + 2] = v.mirrored();
    }
  }

  Renderer.prototype.eyeCenterX = function (i) {
    var s = this.skin;
    var p = s.places[i];
    var cx = p.mirror ? s.eye.w - 1 - s.centerX() : s.centerX();
    return MX + p.x + (cx + 0.5) * p.scale;
  };

  Renderer.prototype.eyeCenterY = function (i) {
    var p = this.skin.places[i];
    return MT + p.y + (this.skin.centerY() + 0.5) * p.scale;
  };

  Renderer.prototype.eyeTop = function (i) {
    var p = this.skin.places[i];
    return MT + p.y + this.skin.boxT * p.scale;
  };

  Renderer.prototype.eyeBottom = function (i) {
    var p = this.skin.places[i];
    return MT + p.y + (this.skin.boxB + 1) * p.scale;
  };

  Renderer.prototype.render = function (f) {
    var s = this.skin;
    this.buf.fill(0);
    var faceCx = 0;
    var i;
    for (i = 0; i < f.n; i++) {
      faceCx += this.eyeCenterX(i);
    }
    faceCx /= Math.max(1, f.n);
    for (i = 0; i < f.n && i < s.places.length; i++) {
      var p = s.places[i];
      var side;
      if (f.n === 1) {
        side = 0;
      } else {
        var ex = this.eyeCenterX(i);
        side = ex < faceCx - 0.5 ? 1 : ex > faceCx + 0.5 ? -1 : 0;
      }
      this.renderEye(f, i, p.mirror, side);
      this.blit(this.tmp, s.eye.w, s.eye.h, MX + p.x + f.shakeX, MT + p.y + f.shakeY, p.scale);
    }
    for (i = 0; i < f.particles.length; i++) {
      this.drawParticle(f.particles[i]);
    }
    if (this.edge === EDGE_SHADOW) {
      this.applyShadow();
    } else if (this.edge === EDGE_HALO) {
      this.applyHalo();
    } else {
      this.out.set(this.buf);
    }
    return this.out;
  };

  Renderer.prototype.renderEye = function (f, i, mirror, side) {
    var s = this.skin;
    var w = s.eye.w;
    var tmp = this.tmp;
    if (f.frame) {
      var fr = (mirror ? this.framesM : s.frames)[f.frame];
      if (fr) {
        tmp.set(fr.px);
        return;
      }
    }
    tmp.set((mirror ? this.eyeM : s.eye).px);
    var cx = mirror ? w - 1 - s.centerX() : s.centerX();
    var cy = s.centerY();
    var dx = mirror ? -s.irisDx : s.irisDx;
    var ix = cx + dx + Math.round(F(clamp1(f.gx[i]) * s.travelX));
    var iy = cy + s.irisDy + Math.round(F(clamp1(f.gy[i]) * s.travelY));
    var x;
    var y;
    var x0;
    var y0;
    var c;
    if (f.pupilMode === PUPIL_HEART) {
      this.drawHeartPupil(ix, iy, mirror);
    } else if (f.pupilMode === PUPIL_SPIRAL) {
      this.drawSpiral(ix, iy, mirror, f.time * (side >= 0 ? 1 : -1));
    } else if (f.pupilMode !== PUPIL_OUCH) {
      var lv = Math.max(-2, Math.min(2, Math.round(F(F(f.pupil - 1) / F(0.22)))));
      var iris = (mirror ? this.pupilLvM : this.pupilLv)[lv + 2];
      x0 = Math.round(ix - (iris.w - 1) / 2);
      y0 = Math.round(iy - (iris.h - 1) / 2);
      for (y = 0; y < iris.h; y++) {
        for (x = 0; x < iris.w; x++) {
          c = iris.px[y * iris.w + x];
          if (c !== 0) {
            this.plotInside(x0 + x, y0 + y, c, mirror);
          }
        }
      }
    }
    if (s.highlight && f.pupilMode === PUPIL_NORMAL) {
      var hl = s.highlight;
      var hx = (s.hlFixed ? cx : ix) + s.hlDx;
      var hy = (s.hlFixed ? cy : iy) + s.hlDy;
      x0 = Math.round(hx - (hl.w - 1) / 2);
      y0 = Math.round(hy - (hl.h - 1) / 2);
      for (y = 0; y < hl.h; y++) {
        for (x = 0; x < hl.w; x++) {
          c = hl.px[y * hl.w + x];
          if (c !== 0) {
            this.plotInside(x0 + x, y0 + y, c, mirror);
          }
        }
      }
    }
    var top = f.openTop[i];
    var bot = f.openBot[i];
    var ouch = f.pupilMode === PUPIL_OUCH;
    if (ouch) {
      top = 0;
      bot = 1;
    }
    this.lids(top, bot, f.arcBot[i], f.slant, side, mirror, cx, !ouch);
    if (ouch) {
      this.chevron(side, mirror);
    }
  };

  Renderer.prototype.plotInside = function (x, y, c, mirror) {
    var s = this.skin;
    if (s.inside(x, y, mirror)) {
      this.tmp[y * s.eye.w + x] = blend(c, this.tmp[y * s.eye.w + x]);
    }
  };

  Renderer.prototype.lids = function (top, bot, arc, slant, side, mirror, cx, line) {
    var s = this.skin;
    var tmp = this.tmp;
    var w = s.eye.w;
    var T = s.boxT;
    var B = s.boxB;
    var H = B - T + 1;
    var half = Math.max(1, (s.boxR - s.boxL + 1) / 2);
    if (top >= F(0.999) && bot >= F(0.999) && Math.abs(slant) < F(0.01) && arc < F(0.01)) {
      return;
    }
    for (var x = 0; x < w; x++) {
      var sx = mirror ? w - 1 - x : x;
      var ct = s.colTop[sx];
      var cb = s.colBot[sx];
      if (ct < 0) {
        continue;
      }
      var nx = Math.max(-1, Math.min(1, F(F(x - cx) / half)));
      var nn = F(1 - F(nx * nx));
      var tilt = side !== 0 ? F(F(slant * side) * nx) : F(slant * F(0.5 - Math.abs(nx)));
      var yT = F(F(T + F(F(1 - top) * H)) + F(F(tilt * H) * F(0.4)));
      var yB = F(F((B + 1) - F(F(1 - bot) * H)) - F(F(F(arc * H) * F(0.45)) * nn));
      var closed = yT >= F(yB - 0.5);
      var lashRow = -1;
      var lashRow2 = -1;
      if (closed) {
        var bend = arc > F(0.05) ? F(F(-arc * H) * F(0.4)) : F(H * F(0.12));
        var row = F(F(T + F(H * F(0.6))) + F(bend * nn));
        lashRow = Math.max(ct, Math.min(cb, Math.round(row)));
        if (H >= 11 && arc > F(0.05)) {
          lashRow2 = Math.max(ct, Math.min(cb, lashRow + 1));
        }
      }
      var lastTop = -1;
      var firstBot = -1;
      for (var y = ct; y <= cb; y++) {
        if (!s.interior[y * w + sx]) {
          continue;
        }
        var cover;
        if (closed) {
          cover = true;
        } else if (y + 0.5 < yT) {
          cover = true;
          lastTop = y;
        } else if (y + 0.5 > yB) {
          cover = true;
          if (firstBot < 0) {
            firstBot = y;
          }
        } else {
          cover = false;
        }
        if (cover) {
          tmp[y * w + x] = s.lidColor;
        }
      }
      if (closed) {
        if (line && s.lashColor !== 0 && s.interior[lashRow * w + sx]) {
          tmp[lashRow * w + x] = s.lashColor;
          if (lashRow2 >= 0 && s.interior[lashRow2 * w + sx]) {
            tmp[lashRow2 * w + x] = s.lashColor;
          }
        }
      } else {
        if (lastTop >= 0 && s.lashColor !== 0) {
          tmp[lastTop * w + x] = s.lashColor;
        }
        if (firstBot >= 0 && s.lashColor !== 0 && arc > F(0.3)) {
          tmp[firstBot * w + x] = s.lashColor;
        }
      }
    }
  };

  Renderer.prototype.chevron = function (side, mirror) {
    var s = this.skin;
    var w = s.eye.w;
    var boxW = s.boxR - s.boxL + 1;
    var boxH = s.boxB - s.boxT + 1;
    var arm = Math.max(1, Math.trunc(Math.min(boxW - 2, boxH - 2) / 2));
    var cx = Math.round((s.boxL + s.boxR) / 2);
    if (mirror) {
      cx = w - 1 - cx;
    }
    var cy = Math.round((s.boxT + s.boxB) / 2);
    var dir = side === 0 ? 1 : side;
    var c = s.lashColor !== 0 ? s.lashColor : 0xFF1A1A1A;
    var tipX = cx + Math.trunc(dir * arm / 2);
    for (var k = 0; k <= arm; k++) {
      var x = tipX - dir * k;
      var ys = [cy - k, cy + k];
      for (var q = 0; q < 2; q++) {
        var y = ys[q];
        if (x >= 0 && x < w && y >= 0 && y < s.eye.h && s.inside(x, y, mirror)) {
          this.tmp[y * w + x] = c;
        }
      }
    }
  };

  Renderer.prototype.drawHeartPupil = function (ix, iy, mirror) {
    var s = this.skin;
    var g = Math.min(s.iris.w, s.iris.h) >= 5 ? HEART5 : HEART3;
    var x0 = Math.round(ix - (g[0].length - 1) / 2);
    var y0 = Math.round(iy - (g.length - 1) / 2);
    for (var y = 0; y < g.length; y++) {
      for (var x = 0; x < g[y].length; x++) {
        if (g[y][x] === '#') {
          this.plotInside(x0 + x, y0 + y, 0xFFFF4F7B, mirror);
        }
      }
    }
    this.plotInside(x0 + 1, y0 + (g.length >= 5 ? 1 : 0), 0xFFFFD6E0, mirror);
  };

  Renderer.prototype.drawSpiral = function (ix, iy, mirror, t) {
    var s = this.skin;
    var r = Math.max(2, Math.trunc(Math.max(s.iris.w, s.iris.h) / 2) + 1);
    var c1 = s.pupilColor !== 0 ? s.pupilColor : 0xFF1A1A1A;
    var c2 = this.irisMain !== 0 ? this.irisMain : 0xFF8F6BFF;
    for (var y = -r; y <= r; y++) {
      for (var x = -r; x <= r; x++) {
        var d = F(Math.sqrt(x * x + y * y));
        if (d > F(r + F(0.3))) {
          continue;
        }
        var a = Math.atan2(y, x) + d * 1.7 - F(t) * 7.0;
        var m = ((a % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
        this.plotInside(Math.round(ix) + x, Math.round(iy) + y, m < Math.PI ? c1 : c2, mirror);
      }
    }
  };

  Renderer.prototype.blit = function (src, w, h, ox, oy, scale) {
    for (var y = 0; y < h; y++) {
      for (var x = 0; x < w; x++) {
        var c = src[y * w + x];
        if (c === 0) {
          continue;
        }
        for (var sy = 0; sy < scale; sy++) {
          for (var sx = 0; sx < scale; sx++) {
            this.put(ox + x * scale + sx, oy + y * scale + sy, c);
          }
        }
      }
    }
  };

  Renderer.prototype.put = function (x, y, c) {
    if (x < 0 || y < 0 || x >= this.cw || y >= this.ch) {
      return;
    }
    var k = y * this.cw + x;
    this.buf[k] = blend(c, this.buf[k]);
  };

  Renderer.prototype.putIfEmpty = function (x, y, c) {
    if (x < 0 || y < 0 || x >= this.cw || y >= this.ch) {
      return;
    }
    var k = y * this.cw + x;
    if ((this.buf[k] >>> 24) === 0) {
      this.buf[k] = c;
    }
  };

  Renderer.prototype.applyShadow = function () {
    var cw = this.cw;
    var buf = this.buf;
    for (var y = 0; y < this.ch; y++) {
      for (var x = 0; x < cw; x++) {
        var k = y * cw + x;
        var c = buf[k];
        if ((c >>> 24) !== 0) {
          this.out[k] = c;
          continue;
        }
        var src = x > 0 && y > 0 ? buf[k - cw - 1] : 0;
        this.out[k] = (src >>> 24) > 0x40 ? 0x55000000 : 0;
      }
    }
  };

  Renderer.prototype.applyHalo = function () {
    var cw = this.cw;
    var ch = this.ch;
    var buf = this.buf;
    for (var y = 0; y < ch; y++) {
      for (var x = 0; x < cw; x++) {
        var k = y * cw + x;
        var c = buf[k];
        if ((c >>> 24) !== 0) {
          this.out[k] = c;
          continue;
        }
        var near = (x > 0 && (buf[k - 1] >>> 24) > 0x40) || (x + 1 < cw && (buf[k + 1] >>> 24) > 0x40)
          || (y > 0 && (buf[k - cw] >>> 24) > 0x40) || (y + 1 < ch && (buf[k + cw] >>> 24) > 0x40);
        this.out[k] = near ? 0x4DFFFFFF : 0;
      }
    }
  };

  Renderer.prototype.drawParticle = function (p) {
    if (p.type < 0 || p.type >= GLYPHS.length) {
      return;
    }
    var g = GLYPHS[p.type];
    var fade = p.life <= 0 ? 1 : F(1 - F(F(p.age) / F(p.life)));
    if (fade <= F(0.05)) {
      return;
    }
    var alpha = Math.round(F(Math.min(1, F(fade * F(1.6))) * 255));
    var col = ((alpha << 24) | (GLYPH_COLORS[p.type] & 0xFFFFFF)) >>> 0;
    var edge = ((Math.trunc(alpha * 3 / 4) << 24) | 0x1A1A1A) >>> 0;
    var x0 = Math.round(F(F(p.x) - (g[0].length - 1) / 2));
    var y0 = Math.round(F(F(p.y) - (g.length - 1) / 2));
    var x;
    var y;
    for (y = 0; y < g.length; y++) {
      for (x = 0; x < g[y].length; x++) {
        if (g[y][x] !== '#') {
          continue;
        }
        for (var d = 0; d < 4; d++) {
          var nx = x + NEIGHBOURS[d][0];
          var ny = y + NEIGHBOURS[d][1];
          var solid = ny >= 0 && ny < g.length && nx >= 0 && nx < g[ny].length && g[ny][nx] === '#';
          if (!solid) {
            this.putIfEmpty(x0 + nx, y0 + ny, edge);
          }
        }
      }
    }
    for (y = 0; y < g.length; y++) {
      for (x = 0; x < g[y].length; x++) {
        if (g[y][x] === '#') {
          this.put(x0 + x, y0 + y, col);
        }
      }
    }
  };

  function mainColor(iris, pupil) {
    var n = new Map();
    iris.px.forEach(function (c) {
      if (c !== 0 && c !== pupil) {
        n.set(c, (n.get(c) || 0) + 1);
      }
    });
    var best = 0;
    var cnt = 0;
    n.forEach(function (v, c) {
      if (v > cnt) {
        cnt = v;
        best = c;
      }
    });
    return best;
  }

  Renderer.prototype.pupilVariant = function (iris, lv) {
    var cur = new Sprite(iris.w, iris.h);
    cur.px.set(iris.px);
    var pc = this.skin.pupilColor;
    if (pc === 0 || lv === 0) {
      return cur;
    }
    for (var step = 0; step < Math.abs(lv); step++) {
      var next = new Sprite(cur.w, cur.h);
      next.px.set(cur.px);
      var kept = 0;
      var x;
      var y;
      for (y = 0; y < cur.h; y++) {
        for (x = 0; x < cur.w; x++) {
          var c = cur.at(x, y);
          var isPupil = c === pc;
          var nearPupil = false;
          var allPupil = true;
          for (var d = 0; d < 4; d++) {
            var nc = cur.at(x + NEIGHBOURS[d][0], y + NEIGHBOURS[d][1]);
            if (nc === pc) {
              nearPupil = true;
            } else {
              allPupil = false;
            }
          }
          if (lv > 0 && !isPupil && c !== 0 && nearPupil) {
            next.px[y * cur.w + x] = pc;
          } else if (lv < 0 && isPupil && !allPupil) {
            next.px[y * cur.w + x] = this.irisMain !== 0 ? this.irisMain : c;
          }
          if (next.px[y * cur.w + x] === pc) {
            kept++;
          }
        }
      }
      if (lv < 0 && kept === 0) {
        var bx = -1;
        var by = -1;
        var bd = Infinity;
        for (y = 0; y < cur.h; y++) {
          for (x = 0; x < cur.w; x++) {
            if (cur.at(x, y) === pc) {
              var dd = Math.abs(x - (cur.w - 1) / 2) + Math.abs(y - (cur.h - 1) / 2);
              if (dd < bd) {
                bd = dd;
                bx = x;
                by = y;
              }
            }
          }
        }
        if (bx >= 0) {
          next.px[by * cur.w + bx] = pc;
        }
        return next;
      }
      cur = next;
    }
    return cur;
  };

  // ---- поведение -------------------------------------------------------------

  var NEUTRAL = 0;
  var HAPPY = 1;
  var LOVE = 2;
  var SURPRISED = 3;
  var ANGRY = 4;
  var SAD = 5;
  var DIZZY = 6;
  var OUCH = 7;
  var WINK = 8;
  var SUSPICIOUS = 9;
  var SCARED = 10;
  var STARE = 11;
  var FOCUS = 12;
  var CURIOUS = 13;
  var SLEEPY = 14;
  var ASLEEP = 15;
  var NAMES = ['neutral', 'happy', 'love', 'surprised', 'angry', 'sad', 'dizzy', 'ouch', 'wink', 'suspicious',
    'scared', 'stare', 'focus', 'curious', 'sleepy', 'asleep'];

  function Brain(skin, geo) {
    this.skin = skin;
    this.geo = geo;
    var n = this.n = skin.places.length;
    this.face = new Face(n);
    this.followTouch = true;
    this.followTyping = true;
    this.stareAfterTyping = true;
    this.sleepOn = false;
    this.blinkOn = true;
    this.sleepAfter = 90;
    this.ex = new Float32Array(n);
    this.ey = new Float32Array(n);
    this.reach = 300;
    this.geometry = false;
    this.now = 0;
    this.lastNow = -1;
    this.touching = false;
    this.tx = 0;
    this.ty = 0;
    this.touchUpAt = -99;
    this.typedAt = -99;
    this.caretX = NaN;
    this.caretY = NaN;
    this.stareUntil = -99;
    this.stareArmed = false;
    this.delAt = -99;
    this.delRun = 0;
    this.glanceUntil = -99;
    this.glanceX = 0;
    this.glanceY = 0;
    this.lastInteraction = 0;
    this.dragging = false;
    this.dragVX = 0;
    this.dragVY = 0;
    this.pokes = 0;
    this.lastPoke = -99;
    this.napping = false;
    this.peekUntil = -99;
    this.emo = NEUTRAL;
    this.emoUntil = 0;
    this.idleX = new Float32Array(n);
    this.idleY = new Float32Array(n);
    this.nextSaccade = new Float32Array(n);
    this.nextBlink = new Float32Array(n);
    this.blinkAt = new Float32Array(n);
    this.twitchUntil = -99;
    this.twX = 0;
    this.twY = 0;
    this.nextParticle = 0;
    this.nextShake = 0;
    this.gx = new Float32Array(n);
    this.gy = new Float32Array(n);
    this.top = new Float32Array(n);
    this.bot = new Float32Array(n);
    this.arc = new Float32Array(n);
    this.slant = 0;
    this.pupil = 1;
    for (var i = 0; i < n; i++) {
      this.top[i] = skin.baseOpen;
      this.bot[i] = 1;
      this.blinkAt[i] = -99;
      this.nextBlink[i] = this.rand(skin.blinkMin, skin.blinkMax);
      this.nextSaccade[i] = this.rand(0.5, 2);
    }
    this.nextTwitch = this.rand(0.3, 1.2);
  }

  Brain.prototype.rand = function (lo, hi) {
    if (hi <= lo) {
      return lo <= 0 ? 9999 : lo;
    }
    return lo + Math.random() * (hi - lo);
  };

  Brain.prototype.setGeometry = function (cx, cy, reach) {
    for (var i = 0; i < this.n; i++) {
      this.ex[i] = cx[i];
      this.ey[i] = cy[i];
    }
    this.reach = Math.max(40, reach);
    this.geometry = true;
  };

  Brain.prototype.touch = function (action, x, y) {
    if (action === 2) {
      if (this.touching) {
        this.touchUpAt = this.now;
      }
      this.touching = false;
      return;
    }
    this.tx = x;
    this.ty = y;
    this.touching = true;
    this.interact();
  };

  Brain.prototype.typing = function (x, y, delta) {
    this.typedAt = this.now;
    this.caretX = x;
    this.caretY = y;
    this.stareArmed = true;
    if (delta < 0) {
      if (this.now - this.delAt > 1.5) {
        this.delRun = 0;
      }
      this.delAt = this.now;
      this.delRun += -delta;
      if (this.delRun >= 12 && this.emo !== SUSPICIOUS) {
        this.feel(SUSPICIOUS, 1.6);
        this.delRun = 0;
      }
    }
    this.interact();
  };

  Brain.prototype.typingDone = function () {
    if (this.now - this.typedAt < 1.5) {
      this.typedAt = this.now - 1.5;
    }
  };

  Brain.prototype.poke = function () {
    if (this.now - this.lastPoke > 2.5) {
      this.pokes = 0;
    }
    this.pokes++;
    this.lastPoke = this.now;
    this.napping = false;
    if (this.pokes >= 3) {
      this.feel(ANGRY, 3);
      this.pokes = 0;
    } else {
      this.feel(OUCH, 0.45);
    }
    this.interact();
  };

  Brain.prototype.toggleNap = function () {
    this.napping = !this.napping;
    if (this.napping) {
      this.emo = NEUTRAL;
    } else {
      this.feel(SURPRISED, 0.5);
    }
    this.interact();
  };

  Brain.prototype.dragStart = function () {
    this.dragging = true;
    this.napping = false;
    this.interact();
  };

  Brain.prototype.drag = function (vx, vy) {
    this.dragVX = vx;
    this.dragVY = vy;
    this.interact();
  };

  Brain.prototype.dragEnd = function (quiet) {
    this.dragging = false;
    if (!quiet) {
      this.feel(DIZZY, 1.2);
      this.interact();
    }
  };

  Brain.prototype.event = function (name, x, y) {
    if (!name) {
      return;
    }
    if (name.indexOf('emo:') === 0) {
      var k = NAMES.indexOf(name.substring(4));
      if (k < 0) {
        return;
      }
      if (k === ASLEEP) {
        this.napping = true;
      } else {
        this.napping = false;
        this.feel(k, k === STARE ? 3 : 2);
        if (k === STARE) {
          this.stareUntil = this.now + 3;
        }
      }
      this.interact();
      return;
    }
    var asleep = this.isAsleep();
    switch (name) {
      case 'incoming':
        if (asleep) {
          this.peekUntil = this.now + 1.6;
          return;
        }
        this.glance(x, y, 1.1);
        this.feel(SURPRISED, 0.35);
        break;
      case 'send':
        this.typedAt = -99;
        this.stareArmed = false;
        this.stareUntil = -99;
        this.feel(HAPPY, 1.3);
        this.interact();
        break;
      case 'love':
        this.feel(LOVE, 2.4);
        this.interact();
        break;
      case 'deleted':
        this.feel(SAD, 1.8);
        this.interact();
        break;
      case 'shake':
        this.napping = false;
        this.feel(DIZZY, 2.6);
        this.interact();
        break;
      case 'copy':
        this.feel(WINK, 0.7);
        this.interact();
        break;
      case 'peer_typing':
        if (!asleep) {
          this.glance(x, y, 2.5);
          if (this.emo === NEUTRAL) {
            this.feel(CURIOUS, 2.5);
          }
        }
        break;
      default:
        break;
    }
  };

  Brain.prototype.glance = function (x, y, sec) {
    this.glanceX = x;
    this.glanceY = y;
    this.glanceUntil = this.now + sec;
  };

  Brain.prototype.interact = function () {
    var wasAsleep = this.isAsleep() && !this.napping;
    this.lastInteraction = this.now;
    if (wasAsleep && this.emo !== OUCH && this.emo !== ANGRY) {
      this.feel(SURPRISED, 0.5);
      for (var i = 0; i < this.n; i++) {
        this.blinkAt[i] = this.now;
      }
    }
  };

  Brain.prototype.feel = function (e, sec) {
    this.emo = e;
    this.emoUntil = this.now + sec;
    if (e === SURPRISED) {
      this.spawn(P_EXCL, -1);
    } else if (e === ANGRY) {
      this.spawn(P_ANGER, this.n - 1);
    } else if (e === OUCH) {
      this.spawn(P_DROP, this.n - 1);
    } else if (e === CURIOUS) {
      this.spawn(P_QUESTION, -1);
    }
    this.nextParticle = this.now + 0.3;
  };

  Brain.prototype.isAsleep = function () {
    return this.napping || this.sleepStage() === 2;
  };

  Brain.prototype.sleepStage = function () {
    if (this.napping) {
      return 2;
    }
    if (!this.sleepOn) {
      return 0;
    }
    var idle = this.now - this.lastInteraction;
    if (idle > this.sleepAfter + 20) {
      return 2;
    }
    if (idle > this.sleepAfter) {
      return 1;
    }
    return 0;
  };

  Brain.prototype.lookAt = function (px, py, tgx, tgy) {
    for (var i = 0; i < this.n; i++) {
      var dx = px - this.ex[i];
      var dy = py - this.ey[i];
      var d = Math.hypot(dx, dy);
      var k = 1.25 / Math.sqrt(d * d + this.reach * this.reach);
      tgx[i] = dx * k;
      tgy[i] = dy * k;
    }
  };

  Brain.prototype.idle = function (tgx, tgy) {
    var s = this.skin;
    for (var i = 0; i < this.n; i++) {
      var src = s.independent ? i : 0;
      if (i === src && this.now >= this.nextSaccade[i]) {
        if (Math.random() < s.stare) {
          this.idleX[i] = 0;
          this.idleY[i] = 0;
          this.nextSaccade[i] = this.now + this.rand(1.5, 4);
        } else {
          var a = Math.random() * Math.PI * 2;
          var r = 0.25 + Math.random() * 0.65;
          this.idleX[i] = Math.cos(a) * r;
          this.idleY[i] = Math.sin(a) * r * 0.7;
          this.nextSaccade[i] = this.now + this.rand(0.7, 3.2);
        }
      }
      tgx[i] = this.idleX[src];
      tgy[i] = this.idleY[src];
    }
  };

  function clampUnit(x, y, wantX) {
    var len = Math.hypot(x, y);
    if (len > 1) {
      x /= len;
      y /= len;
    }
    return wantX ? x : y;
  }

  function px(g, travel) {
    return Math.round(Math.max(-1, Math.min(1, g)) * travel);
  }

  function settle(v, target, eps) {
    return Math.abs(v - target) < eps ? target : v;
  }

  function blinkCurve(since, slow) {
    var close = 0.06 * slow;
    var open = 0.1 * slow;
    if (since < 0 || since > close + open) {
      return 0;
    }
    if (since < close) {
      return since / close;
    }
    return 1 - (since - close) / open;
  }

  Brain.prototype.spawn = function (type, eye) {
    var f = this.face;
    var geo = this.geo;
    var n = this.n;
    if (f.particles.length > 12) {
      return;
    }
    var below = (eye & 0x100) !== 0;
    var i = eye < 0 ? -1 : (eye & 0xFF);
    var p = {type: type, x: 0, y: 0, vx: 0, vy: 0, age: 0, life: 1};
    var cx;
    if (i < 0 || i >= n) {
      cx = 0;
      for (var k = 0; k < n; k++) {
        cx += geo.eyeCenterX(k);
      }
      cx /= Math.max(1, n);
      i = n - 1;
    } else {
      cx = geo.eyeCenterX(i);
    }
    switch (type) {
      case P_Z:
        p.x = cx + 3;
        p.y = geo.eyeTop(i) - 2;
        p.vx = 2.2;
        p.vy = -2.8;
        p.life = 1.8;
        break;
      case P_HEART:
        p.x = cx + (Math.random() - 0.5) * 4;
        p.y = geo.eyeTop(i) - 2;
        p.vx = (Math.random() - 0.5) * 2;
        p.vy = -4;
        p.life = 1.3;
        break;
      case P_ANGER:
        p.x = cx + 3;
        p.y = geo.eyeTop(i) - 3;
        p.life = 1.1;
        break;
      case P_DROP:
        if (below) {
          var leftEye = geo.eyeCenterX(i) < geo.cw / 2;
          p.x = cx + (leftEye ? -2 : 2);
          p.y = geo.eyeBottom(i) - 1;
          p.vy = 5;
          p.life = 0.9;
        } else {
          p.x = cx + 3;
          p.y = geo.eyeTop(i) - 1;
          p.vy = 2;
          p.life = 0.7;
        }
        break;
      case P_EXCL:
        p.x = cx;
        p.y = geo.eyeTop(i) - 4;
        p.life = 0.7;
        break;
      case P_QUESTION:
        p.x = cx;
        p.y = geo.eyeTop(i) - 4;
        p.life = 1.4;
        break;
      case P_STAR:
        p.x = cx + (Math.random() - 0.5) * geo.cw * 0.6;
        p.y = geo.eyeTop(i) - 2 - Math.random() * 2;
        p.life = 0.5;
        break;
      default:
        p.x = cx;
        p.y = geo.eyeTop(i) - 3;
        break;
    }
    f.particles.push(p);
  };

  Brain.prototype.update = function (t) {
    var s = this.skin;
    var n = this.n;
    var face = this.face;
    var dt = this.lastNow < 0 ? 0.016 : Math.max(0, Math.min(0.1, t - this.lastNow));
    this.lastNow = t;
    this.now = t;
    var now = t;
    var i;
    if (this.emo !== NEUTRAL && now >= this.emoUntil) {
      this.emo = NEUTRAL;
    }
    var stage = this.sleepStage();
    var asleep = stage === 2;

    var tgx = new Float32Array(n);
    var tgy = new Float32Array(n);
    var fast = false;
    var typingNow = this.followTyping && now - this.typedAt < 1.3;
    if (this.stareArmed && !typingNow && this.typedAt > 0 && now - this.typedAt >= 1.3) {
      this.stareArmed = false;
      if (this.stareAfterTyping) {
        this.stareUntil = now + (s.creepy ? 6 : 3);
        if (this.emo === NEUTRAL || this.emo === FOCUS) {
          this.feel(STARE, s.creepy ? 6 : 3);
        }
      }
    }
    if (this.dragging) {
      var len = Math.hypot(this.dragVX, this.dragVY);
      var kk = len < 1 ? 0 : Math.min(1, len / 1500);
      for (i = 0; i < n; i++) {
        tgx[i] = len < 1 ? 0 : -this.dragVX / len * kk;
        tgy[i] = len < 1 ? 0 : -this.dragVY / len * kk;
      }
      fast = true;
    } else if (this.followTouch && (this.touching || now - this.touchUpAt < 0.7) && this.geometry) {
      this.lookAt(this.tx, this.ty, tgx, tgy);
      fast = true;
    } else if (typingNow && this.geometry && !isNaN(this.caretX)) {
      this.lookAt(this.caretX, this.caretY, tgx, tgy);
      fast = true;
      if (this.emo === NEUTRAL) {
        this.feel(FOCUS, 1.3);
      }
    } else if (now < this.stareUntil) {
      for (i = 0; i < n; i++) {
        tgx[i] = 0;
        tgy[i] = 0;
      }
    } else if (now < this.glanceUntil && this.geometry) {
      this.lookAt(this.glanceX, this.glanceY, tgx, tgy);
      fast = true;
    } else {
      this.idle(tgx, tgy);
    }
    if (s.twitch > 0 && !asleep) {
      if (now >= this.nextTwitch) {
        this.twX = (Math.random() - 0.5) * 0.5;
        this.twY = (Math.random() - 0.5) * 0.4;
        this.twitchUntil = now + 0.12;
        this.nextTwitch = now + this.rand(0.25, 1.6) / s.twitch;
      }
      if (now < this.twitchUntil) {
        for (i = 0; i < n; i++) {
          tgx[i] += this.twX;
          tgy[i] += this.twY;
        }
      }
    }

    var baseOpen = s.baseOpen;
    var baseSlant = s.baseSlant;
    var wantTop = baseOpen;
    var wantBot = 1;
    var wantArc = 0;
    var wantSlant = baseSlant;
    var wantPupil = 1;
    var pupilMode = PUPIL_NORMAL;
    var frame = null;
    var blinking = this.blinkOn && s.blinkMax > 0;
    var winkEye = -1;
    var e = asleep ? ASLEEP : this.emo;
    if (e === NEUTRAL && stage === 1) {
      e = SLEEPY;
    }
    switch (e) {
      case HAPPY:
        wantTop = 0;
        wantBot = 1;
        wantArc = 1;
        frame = 'happy';
        blinking = false;
        break;
      case LOVE:
        wantTop = 1;
        wantBot = 0.9;
        wantArc = 0.3;
        pupilMode = PUPIL_HEART;
        frame = 'love';
        break;
      case SURPRISED:
        wantTop = 1;
        wantPupil = 0.55;
        frame = 'surprised';
        blinking = false;
        break;
      case ANGRY:
        wantTop = 0.62;
        wantBot = 0.95;
        wantSlant = 0.85;
        wantPupil = 0.75;
        frame = 'angry';
        break;
      case SAD:
        wantTop = 0.72;
        wantBot = 0.92;
        wantSlant = -0.75;
        wantPupil = 1.15;
        frame = 'sad';
        break;
      case DIZZY:
        wantTop = 1;
        pupilMode = PUPIL_SPIRAL;
        frame = 'dizzy';
        blinking = false;
        break;
      case OUCH:
        pupilMode = PUPIL_OUCH;
        frame = 'ouch';
        blinking = false;
        break;
      case WINK:
        winkEye = n - 1;
        wantTop = 1;
        break;
      case SUSPICIOUS:
        wantTop = 0.55;
        wantBot = 0.85;
        wantSlant = 0.15;
        wantPupil = 0.9;
        break;
      case SCARED:
        wantTop = 1;
        wantPupil = 0.5;
        blinking = false;
        break;
      case STARE:
        wantTop = 1;
        wantPupil = 1.3;
        if (s.creepy) {
          blinking = false;
        }
        break;
      case FOCUS:
        wantTop = Math.min(wantTop, 0.82);
        wantPupil = 0.95;
        break;
      case CURIOUS:
        wantTop = 1;
        wantPupil = 1.15;
        break;
      case SLEEPY:
        wantTop = Math.min(wantTop, 0.35);
        break;
      case ASLEEP:
        wantTop = 0;
        wantBot = 1;
        frame = 'closed';
        blinking = false;
        break;
      default:
        break;
    }
    if (this.dragging) {
      wantTop = 1;
      wantPupil = 0.6;
    }

    var tauG = (s.style === 0 ? 0.075 : 0.03) / s.speed;
    if (fast) {
      tauG *= 0.7;
    }
    var ag = 1 - Math.exp(-dt / tauG);
    var al = 1 - Math.exp(-dt / 0.07);
    for (i = 0; i < n; i++) {
      var nx = clampUnit(tgx[i], tgy[i], true);
      var ny = clampUnit(tgx[i], tgy[i], false);
      this.gx[i] += (nx - this.gx[i]) * ag;
      this.gy[i] += (ny - this.gy[i]) * ag;
      if (px(this.gx[i], s.travelX) === px(nx, s.travelX)) {
        this.gx[i] = nx;
      }
      if (px(this.gy[i], s.travelY) === px(ny, s.travelY)) {
        this.gy[i] = ny;
      }
      var t0 = wantTop;
      var b0 = wantBot;
      var a0 = wantArc;
      if (i === winkEye) {
        t0 = 0;
        b0 = 1;
        a0 = 0.9;
      }
      if (asleep && now < this.peekUntil && i === 0) {
        t0 = 0.4;
      }
      this.top[i] = settle(this.top[i] + (t0 - this.top[i]) * al, t0, 0.02);
      this.bot[i] = settle(this.bot[i] + (b0 - this.bot[i]) * al, b0, 0.02);
      this.arc[i] = settle(this.arc[i] + (a0 - this.arc[i]) * al, a0, 0.03);
    }
    this.slant = settle(this.slant + (wantSlant - this.slant) * al, wantSlant, 0.03);
    this.pupil = settle(this.pupil + (wantPupil - this.pupil) * al, wantPupil, 0.03);

    for (i = 0; i < n; i++) {
      var src = s.independent ? i : 0;
      if (i === src && blinking && now >= this.nextBlink[i]) {
        this.blinkAt[i] = now;
        var gap = this.rand(s.blinkMin, s.blinkMax);
        if (stage === 1) {
          gap *= 0.6;
        }
        this.nextBlink[i] = now + (Math.random() < 0.18 ? 0.28 : gap);
      }
      var closeK = blinkCurve(now - this.blinkAt[src], stage === 1 ? 1.8 : 1);
      face.openTop[i] = this.top[i] * (1 - closeK);
      face.openBot[i] = this.bot[i] * (1 - 0.12 * closeK);
      face.arcBot[i] = this.arc[i];
      face.gx[i] = this.gx[i];
      face.gy[i] = this.gy[i];
    }
    face.slant = this.slant;
    face.pupil = this.pupil;
    face.pupilMode = pupilMode;
    face.frame = frame;
    face.time = now;

    if ((e === SCARED || this.dragging || (s.style === 2 && !asleep)) && now >= this.nextShake) {
      this.nextShake = now + 0.07;
      var amp = s.style === 2 && e !== SCARED && !this.dragging ? 0.25 : 1;
      face.shakeX = Math.random() < amp ? Math.floor(Math.random() * 3) - 1 : 0;
      face.shakeY = Math.random() < amp * 0.5 ? Math.floor(Math.random() * 3) - 1 : 0;
    } else if (e !== SCARED && !this.dragging && s.style !== 2) {
      face.shakeX = 0;
      face.shakeY = 0;
    }

    if (now >= this.nextParticle) {
      switch (e) {
        case ASLEEP:
          this.spawn(P_Z, n - 1);
          this.nextParticle = now + 1.3;
          break;
        case LOVE:
          this.spawn(P_HEART, Math.floor(Math.random() * n));
          this.nextParticle = now + 0.55;
          break;
        case SAD:
          this.spawn(P_DROP, Math.floor(Math.random() * n) | 0x100);
          this.nextParticle = now + 1.0;
          break;
        case DIZZY:
          this.spawn(P_STAR, -1);
          this.nextParticle = now + 0.4;
          break;
        case ANGRY:
          this.spawn(P_ANGER, n - 1);
          this.nextParticle = now + 1.3;
          break;
        default:
          this.nextParticle = now + 0.5;
          break;
      }
    }
    var timers = s.independent ? n : 1;
    for (i = 0; i < timers; i++) {
      if (this.nextSaccade[i] < now) {
        this.nextSaccade[i] = now + 0.3;
      }
      if (this.nextBlink[i] < now) {
        this.nextBlink[i] = now + (blinking ? 0.05 : 0.5);
      }
    }
    if (this.nextTwitch < now) {
      this.nextTwitch = now + 0.3;
    }
    face.particles = face.particles.filter(function (p) {
      p.age += dt;
      p.x += p.vx * dt;
      p.y += p.vy * dt;
      return p.age < p.life;
    });
  };

  // ---- виджет ----------------------------------------------------------------

  var widgets = new Set();
  var pointer = {x: 0, y: 0, at: -99, touch: false, down: false};
  var darkTheme = false;
  var running = false;
  var observer = null;

  function clock() {
    return performance.now() / 1000;
  }

  /**
   * Живые глаза в canvas.
   * opts: skin (JSON-строка, объект или Skin), unit (CSS-пикселей на пиксель сетки),
   * fit (элемент, под ширину которого подбирать unit), maxUnit, minUnit,
   * edge ('auto' | 'shadow' | 'halo' | 'none'), interactive (тыкать), sleepAfter (секунды, 0 не спать),
   * follow (следить за курсором), floating (свободно таскать по странице).
   */
  function Eyes(opts) {
    opts = opts || {};
    this.opts = opts;
    this.canvas = opts.canvas || document.createElement('canvas');
    this.canvas.classList.add('pe-canvas');
    this.ctx = this.canvas.getContext('2d');
    this.off = document.createElement('canvas');
    this.offCtx = this.off.getContext('2d');
    this.unit = opts.unit || 6;
    this.visible = true;
    this.lastSig = '';
    this.error = '';
    this.followMouse = opts.follow !== false;
    this.setSkin(opts.skin);
    if (opts.interactive !== false) {
      this.bindPoke();
    }
    widgets.add(this);
    if (observer) {
      observer.observe(this.canvas);
    }
    if (opts.fit && typeof ResizeObserver !== 'undefined') {
      var self = this;
      this.ro = new ResizeObserver(function () {
        self.refit();
      });
      this.ro.observe(opts.fit);
    }
    start();
  }

  Eyes.prototype.setSkin = function (skin) {
    var s;
    try {
      s = skin instanceof Skin ? skin : parseSkin(skin || BLANK);
      this.error = '';
    } catch (e) {
      this.error = e.message;
      if (this.skin) {
        return false;
      }
      s = parseSkin(BLANK);
    }
    var keepNap = this.brain ? this.brain.napping : false;
    this.skin = s;
    this.renderer = new Renderer(s);
    this.brain = new Brain(s, this.renderer);
    this.brain.sleepOn = !!this.opts.sleepAfter;
    this.brain.sleepAfter = this.opts.sleepAfter || 90;
    this.brain.napping = keepNap;
    this.off.width = this.renderer.cw;
    this.off.height = this.renderer.ch;
    this.image = this.offCtx.createImageData(this.renderer.cw, this.renderer.ch);
    this.lastSig = '';
    this.refit();
    return !this.error;
  };

  Eyes.prototype.refit = function () {
    var o = this.opts;
    if (o.fit) {
      var w = o.fit.clientWidth - (o.fitPad || 0);
      var h = o.fitHeight || Infinity;
      var u = Math.floor(Math.min(w / this.renderer.cw, h / this.renderer.ch));
      this.unit = Math.max(o.minUnit || 2, Math.min(o.maxUnit || 12, u || 1));
    }
    var dpr = window.devicePixelRatio || 1;
    var cssW = this.renderer.cw * this.unit;
    var cssH = this.renderer.ch * this.unit;
    this.canvas.style.width = cssW + 'px';
    this.canvas.style.height = cssH + 'px';
    this.canvas.width = Math.round(cssW * dpr);
    this.canvas.height = Math.round(cssH * dpr);
    this.lastSig = '';
  };

  Eyes.prototype.setUnit = function (u) {
    this.unit = u;
    this.refit();
  };

  Eyes.prototype.event = function (name, x, y) {
    this.brain.event(name, x, y);
  };

  Eyes.prototype.edgeMode = function () {
    var e = this.opts.edge || 'auto';
    if (e === 'none') {
      return EDGE_NONE;
    }
    if (e === 'shadow') {
      return EDGE_SHADOW;
    }
    if (e === 'halo') {
      return EDGE_HALO;
    }
    var dark = this.opts.dark !== undefined ? this.opts.dark : darkTheme;
    return dark ? EDGE_HALO : EDGE_SHADOW;
  };

  Eyes.prototype.geometry = function () {
    var r = this.canvas.getBoundingClientRect();
    if (!r.width) {
      return;
    }
    var u = r.width / this.renderer.cw;
    var n = this.skin.places.length;
    var cx = new Float32Array(n);
    var cy = new Float32Array(n);
    for (var i = 0; i < n; i++) {
      cx[i] = r.left + this.renderer.eyeCenterX(i) * u;
      cy[i] = r.top + this.renderer.eyeCenterY(i) * u;
    }
    this.brain.setGeometry(cx, cy, 37 * u);
  };

  Eyes.prototype.tick = function (t) {
    var b = this.brain;
    if (b.now > 0 || b.lastNow >= 0) {
      this.geometry();
    }
    if (this.followMouse) {
      var fresh = pointer.touch ? pointer.down : t - pointer.at < 2.2;
      if (fresh && pointer.at > 0) {
        b.touch(1, pointer.x, pointer.y);
      } else if (b.touching) {
        b.touch(2);
      }
    }
    b.update(t);
    this.renderer.edge = this.edgeMode();
    var sig = b.face.signature(this.skin) + '|' + this.renderer.edge + '|' + this.canvas.width;
    if (sig === this.lastSig) {
      return;
    }
    this.lastSig = sig;
    var out = this.renderer.render(b.face);
    var d = this.image.data;
    for (var k = 0; k < out.length; k++) {
      var c = out[k];
      d[k * 4] = (c >>> 16) & 0xFF;
      d[k * 4 + 1] = (c >>> 8) & 0xFF;
      d[k * 4 + 2] = c & 0xFF;
      d[k * 4 + 3] = c >>> 24;
    }
    this.offCtx.putImageData(this.image, 0, 0);
    this.ctx.imageSmoothingEnabled = false;
    this.ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
    this.ctx.drawImage(this.off, 0, 0, this.canvas.width, this.canvas.height);
  };

  Eyes.prototype.bindPoke = function () {
    var self = this;
    var c = this.canvas;
    var downAt = 0;
    var sx = 0;
    var sy = 0;
    var moved = false;
    var timer = 0;
    var longDone = false;
    var lastX = 0;
    var lastY = 0;
    var lastT = 0;
    var floating = !!this.opts.floating;
    c.style.touchAction = floating ? 'none' : 'manipulation';
    c.addEventListener('pointerdown', function (e) {
      if (e.button > 0) {
        return;
      }
      downAt = clock();
      sx = lastX = e.clientX;
      sy = lastY = e.clientY;
      lastT = downAt;
      moved = false;
      longDone = false;
      try {
        c.setPointerCapture(e.pointerId);
      } catch (ignored) {
        // старые браузеры
      }
      clearTimeout(timer);
      timer = setTimeout(function () {
        if (!moved) {
          longDone = true;
          self.brain.toggleNap();
          if (self.opts.onNap) {
            self.opts.onNap(self.brain.napping);
          }
        }
      }, 480);
    });
    c.addEventListener('pointermove', function (e) {
      if (!downAt) {
        return;
      }
      var dist = Math.hypot(e.clientX - sx, e.clientY - sy);
      if (!moved && floating && dist > 8) {
        moved = true;
        clearTimeout(timer);
        self.brain.dragStart();
        if (self.opts.onDragStart) {
          self.opts.onDragStart();
        }
      } else if (!moved && dist > 12) {
        moved = true;
        clearTimeout(timer);
      }
      if (moved && floating) {
        var now = clock();
        var dt = Math.max(0.008, now - lastT);
        self.brain.drag((e.clientX - lastX) / dt, (e.clientY - lastY) / dt);
        if (self.opts.onDrag) {
          self.opts.onDrag(e.clientX - lastX, e.clientY - lastY);
        }
        lastX = e.clientX;
        lastY = e.clientY;
        lastT = now;
      }
    });
    var up = function (e) {
      if (!downAt) {
        return;
      }
      clearTimeout(timer);
      downAt = 0;
      if (moved && floating) {
        self.brain.dragEnd(false);
        if (self.opts.onDragEnd) {
          self.opts.onDragEnd();
        }
        return;
      }
      if (!moved && !longDone && e.type === 'pointerup') {
        self.brain.poke();
      }
    };
    c.addEventListener('pointerup', up);
    c.addEventListener('pointercancel', up);
    c.addEventListener('contextmenu', function (e) {
      e.preventDefault();
    });
  };

  Eyes.prototype.destroy = function () {
    widgets.delete(this);
    if (observer) {
      observer.unobserve(this.canvas);
    }
    if (this.ro) {
      this.ro.disconnect();
    }
  };

  function loop() {
    if (!widgets.size) {
      running = false;
      return;
    }
    var t = clock();
    widgets.forEach(function (w) {
      if (!w.canvas.isConnected) {
        w.destroy();
        return;
      }
      if (w.visible) {
        w.tick(t);
      } else {
        // невидимые не рисуем, но часы ведём: иначе эмоция, включённая за кадром, кончится сразу
        w.brain.now = t;
        w.brain.lastNow = t;
      }
    });
    requestAnimationFrame(loop);
  }

  function start() {
    if (running) {
      return;
    }
    running = true;
    requestAnimationFrame(loop);
  }

  function track(x, y, touch) {
    pointer.x = x;
    pointer.y = y;
    pointer.at = clock();
    pointer.touch = touch;
  }

  if (typeof window !== 'undefined') {
    window.addEventListener('pointermove', function (e) {
      if (e.pointerType !== 'touch') {
        track(e.clientX, e.clientY, false);
      }
    }, {passive: true});
    window.addEventListener('pointerdown', function (e) {
      if (e.pointerType !== 'touch') {
        track(e.clientX, e.clientY, false);
      }
    }, {passive: true});
    window.addEventListener('touchstart', function (e) {
      var t = e.touches[0];
      if (t) {
        track(t.clientX, t.clientY, true);
        pointer.down = true;
      }
    }, {passive: true});
    window.addEventListener('touchmove', function (e) {
      var t = e.touches[0];
      if (t) {
        track(t.clientX, t.clientY, true);
      }
    }, {passive: true});
    var release = function (e) {
      if (!e.touches || !e.touches.length) {
        pointer.down = false;
      }
    };
    window.addEventListener('touchend', release, {passive: true});
    window.addEventListener('touchcancel', release, {passive: true});
    document.addEventListener('mouseleave', function () {
      pointer.at = -99;
    });
    if (typeof IntersectionObserver !== 'undefined') {
      observer = new IntersectionObserver(function (entries) {
        entries.forEach(function (en) {
          widgets.forEach(function (w) {
            if (w.canvas === en.target) {
              w.visible = en.isIntersecting;
            }
          });
        });
      }, {rootMargin: '80px'});
    }
  }

  var BLANK = {
    id: 'blank',
    palette: {'#': '#1b1b22', 'w': '#ffffff', 'p': '#15151c'},
    eye: ['..####..', '.#wwww#.', '#wwwwww#', '#wwwwww#', '#wwwwww#', '.#wwww#.', '..####..'],
    iris: ['pp', 'pp']
  };

  /** Предупреждения: в плагин глаза пройдут, но, скорее всего, задумано не так. */
  function warnings(skin, raw) {
    var w = [];
    if (raw.iris === undefined || raw.iris === null) {
      w.push('нет "iris": вместо радужки будет чёрный квадрат 2 на 2');
    }
    if (raw.iris && !skin.pupilColor) {
      w.push('не задан "pupil": зрачок не будет расширяться (влюблённость, взгляд на вас) и сужаться (удивление)');
    }
    if (skin.iris.w > skin.boxR - skin.boxL + 1 || skin.iris.h > skin.boxB - skin.boxT + 1) {
      w.push('радужка больше глазного яблока: края обрежутся');
    }
    if (skin.travelX === 0 && skin.travelY === 0) {
      w.push('"travel" [0, 0]: радужка стоит на месте, глаза никуда не смотрят');
    }
    var sclera = 0;
    for (var k = 0; k < skin.eye.px.length; k++) {
      if (skin.interior[k]) {
        sclera = skin.eye.px[k];
        break;
      }
    }
    if (skin.lidColor !== 0 && skin.lidColor === sclera) {
      w.push('цвет века совпадает с цветом яблока: моргание почти не видно, задайте "lid" темнее');
    }
    if (skin.lidColor === 0 && skin.lashColor === 0) {
      w.push('"lid": "none" без "lash": закрытые глаза просто пропадут, линии не будет');
    }
    if (typeof raw.id !== 'string' || !raw.id) {
      w.push('нет "id": плагин назовёт глаза "custom"');
    } else if (cleanId(raw.id) !== raw.id) {
      w.push('"id" лучше писать латиницей, цифрами, _ и -: плагин превратит "' + raw.id + '" в "' + cleanId(raw.id) + '"');
    }
    if (typeof raw.name !== 'string' || !raw.name) {
      w.push('нет "name": в списке будет показан id');
    }
    if (raw.format === undefined) {
      w.push('нет "format": 1, лучше добавить, чтобы будущие версии плагина знали формат');
    }
    return w;
  }

  root.PixelEyes = {
    FORMAT: FORMAT,
    FRAME_NAMES: FRAME_NAMES,
    EMOTIONS: NAMES,
    parseJson: parseJson,
    writeJson: writeJson,
    cleanPasted: cleanPasted,
    parseSkin: parseSkin,
    cleanId: cleanId,
    parseColor: parseColor,
    warnings: warnings,
    Renderer: Renderer,
    Brain: Brain,
    Face: Face,
    Eyes: Eyes,
    BLANK: BLANK,
    setDark: function (d) {
      darkTheme = !!d;
    },
    all: function () {
      return Array.from(widgets);
    },
    clock: clock
  };
})(this);
